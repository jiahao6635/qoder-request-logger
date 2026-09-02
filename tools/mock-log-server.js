#!/usr/bin/env node
// Mock receiver for client-side integration tests. Zero dependencies.
// Behaviour contract mirrors the real Server: any 2xx means a whole batch
// landed, poison NDJSON lines are skipped and counted (never a 4xx), and a
// missing X-API-Key is a 401 when --require-key is on.
// Usage: node mock-log-server.js [--port 18080] [--out ./mock-received.jsonl] [--require-key]
"use strict";
const http = require("http");
const fs = require("fs");
const zlib = require("zlib");

function arg(name, fallback) {
  const i = process.argv.indexOf(name);
  return i > 0 && i + 1 < process.argv.length ? process.argv[i + 1] : fallback;
}
const PORT = Number(arg("--port", "18080")) || 18080;
const OUT = arg("--out", "./mock-received.jsonl");
const REQUIRE_KEY = process.argv.includes("--require-key");

let accepted = 0;
let rejected = 0;
const out = fs.createWriteStream(OUT, { flags: "a" });

function writeRecords(text) {
  for (const line of text.split("\n")) {
    if (!line.trim()) continue;
    try {
      const record = JSON.parse(line);
      record.received_at = new Date().toISOString();
      out.write(JSON.stringify(record) + "\n");
      accepted += 1;
    } catch (err) {
      rejected += 1; // poison line: skipped, not an error
    }
  }
}

const server = http.createServer((req, res) => {
  const chunks = [];
  req.on("data", (chunk) => chunks.push(chunk));
  req.on("end", () => {
    if (req.method === "GET" && req.url === "/api/health") {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end('{"status":"ok"}');
      return;
    }
    if (req.method !== "POST" || (req.url !== "/api/logs" && req.url !== "/api/logs/batch")) {
      res.writeHead(404, { "Content-Type": "application/json" });
      res.end('{"error":"not found"}');
      return;
    }
    if (REQUIRE_KEY && !String(req.headers["x-api-key"] || "").trim()) {
      res.writeHead(401, { "Content-Type": "application/json" });
      res.end('{"error":"missing api key"}');
      return;
    }
    let body = Buffer.concat(chunks);
    try {
      if (String(req.headers["content-encoding"] || "").toLowerCase() === "gzip") body = zlib.gunzipSync(body);
    } catch (err) {
      res.writeHead(400, { "Content-Type": "application/json" });
      res.end('{"error":"bad gzip body"}');
      return;
    }
    writeRecords(body.toString("utf8"));
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ accepted: accepted, rejected: rejected }));
    process.stdout.write("accepted=" + accepted + " rejected=" + rejected + "\n");
  });
});

server.listen(PORT, "127.0.0.1", () => {
  process.stdout.write("mock-log-server listening on 127.0.0.1:" + PORT + " out=" + OUT + "\n");
});

process.on("SIGINT", () => {
  process.stdout.write("shutting down: accepted=" + accepted + " rejected=" + rejected + "\n");
  out.end(() => process.exit(0));
  setTimeout(() => process.exit(0), 1000).unref();
});
