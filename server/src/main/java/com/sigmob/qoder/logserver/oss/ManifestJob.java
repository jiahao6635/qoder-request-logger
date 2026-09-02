package com.sigmob.qoder.logserver.oss;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sigmob.qoder.logserver.ingest.SpoolWriter;

/**
 * Daily manifest generator: at 02:00 Beijing time it aggregates the upload
 * metadata of D-1 and D-2 into one gzipped JSON object per date and overwrites
 * the manifest in storage ({@code {prefix}/_manifest/date=D.json.gz}). Being
 * idempotent, any rerun simply regenerates the object.
 */
@Component
public class ManifestJob {

    private static final Logger log = LoggerFactory.getLogger(ManifestJob.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final SpoolWriter spool;
    private final StorageClient storage;
    private final String prefix;

    @Autowired
    public ManifestJob(SpoolWriter spool, StorageClient storage,
                       @Value("${oss.prefix:logs/qoder/v1}") String prefix) {
        this.spool = spool;
        this.storage = storage;
        this.prefix = prefix;
    }

    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Shanghai")
    public void dailyManifest() {
        LocalDate today = LocalDate.now(SHANGHAI);
        generateFor(today.minusDays(1));
        generateFor(today.minusDays(2));
    }

    /** Builds and uploads the manifest for one record date; public for tests. */
    public synchronized void generateFor(LocalDate date) {
        Path metaFile = spool.metaDir().resolve("uploads.jsonl");
        if (!Files.isRegularFile(metaFile)) {
            log.info("no upload metadata yet, skipping manifest for {}", date);
            return;
        }
        // user -> aggregated file list + totals
        Map<String, List<ObjectNode>> filesByUser = new TreeMap<>();
        Map<String, long[]> totalsByUser = new LinkedHashMap<>(); // [lines, present]
        Map<String, double[]> creditsByUser = new LinkedHashMap<>(); // [credits]
        Map<String, Instant[]> tsByUser = new LinkedHashMap<>(); // [min, max]
        try {
            List<String> lines = Files.readAllLines(metaFile, StandardCharsets.UTF_8);
            // dedupe by object key FIRST: a crash after a successful upload can
            // make the same key be re-uploaded on restart and appends a second
            // meta line for it; counting both would inflate lines/credits in the
            // manifest. Later lines win (they describe the object now in storage).
            Map<String, JsonNode> byKey = new LinkedHashMap<>();
            for (String line : lines) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node;
                try {
                    node = MAPPER.readTree(line);
                } catch (IOException ignore) {
                    continue;
                }
                if (!date.toString().equals(node.path("record_date").asText())) {
                    continue;
                }
                byKey.put(node.path("key").asText(), node); // duplicate key: replace, keep position
            }
            for (JsonNode node : byKey.values()) {
                String user = node.path("user").asText("unknown");
                ObjectNode fileEntry = MAPPER.createObjectNode();
                fileEntry.put("key", node.path("key").asText());
                fileEntry.put("lines", node.path("lines").asLong());
                fileEntry.put("credits", node.path("credits").asDouble());
                filesByUser.computeIfAbsent(user, u -> new ArrayList<>()).add(fileEntry);

                long[] totals = totalsByUser.computeIfAbsent(user, u -> new long[1]);
                totals[0] += node.path("lines").asLong();
                double[] credits = creditsByUser.computeIfAbsent(user, u -> new double[1]);
                credits[0] += node.path("credits").asDouble();
                Instant[] range = tsByUser.computeIfAbsent(user, u -> new Instant[2]);
                Instant min = parseTs(node.path("min_ts"));
                Instant max = parseTs(node.path("max_ts"));
                if (min != null) {
                    range[0] = range[0] == null || min.isBefore(range[0]) ? min : range[0];
                }
                if (max != null) {
                    range[1] = range[1] == null || max.isAfter(range[1]) ? max : range[1];
                }
            }
        } catch (IOException e) {
            log.error("cannot read upload metadata {}", metaFile, e);
            return;
        }
        if (filesByUser.isEmpty()) {
            log.info("no uploads recorded for {}, skipping manifest", date);
            return;
        }

        ObjectNode manifest = MAPPER.createObjectNode();
        manifest.put("date", date.toString());
        manifest.put("generated_at", Instant.now().toString());
        ArrayNode users = manifest.putArray("users");
        filesByUser.keySet().stream()
                .sorted(Comparator.naturalOrder())
                .forEach(user -> {
                    ObjectNode userNode = users.addObject();
                    userNode.put("user", user);
                    ArrayNode files = userNode.putArray("files");
                    filesByUser.get(user).forEach(files::add);
                    userNode.put("lines_total", totalsByUser.get(user)[0]);
                    userNode.put("credits_total", creditsByUser.get(user)[0]);
                    Instant[] range = tsByUser.get(user);
                    userNode.put("first_ts", range[0] == null ? "" : range[0].toString());
                    userNode.put("last_ts", range[1] == null ? "" : range[1].toString());
                });

        try {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(buffer)) {
                gz.write(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
            }
            String key = OssPathPolicy.manifestKey(prefix, date.toString());
            storage.put(key, buffer.toByteArray());
            log.info("manifest uploaded for {} ({} users)", key, filesByUser.size());
        } catch (IOException e) {
            log.error("manifest upload failed for {}", date, e);
        }
    }

    private static Instant parseTs(JsonNode node) {
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            return null;
        }
        try {
            return Instant.parse(node.asText());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
