#!/bin/sh
# Qoder request logger wrapper.
#
# Responsibilities:
#   1. Resolve a usable Node.js runtime. The IDE / JetBrains plugin launches
#      hooks from a GUI process whose PATH usually does not include nvm or
#      Homebrew shims, so `node` alone is not reliable; probe the usual homes.
#   2. Fall back to a jq-based collector when Node.js is unavailable, so events
#      are still recorded instead of silently disappearing.
#   3. Buffer stdin once and hand the same payload to the chosen backend.
#   4. ALWAYS exit 0 and never write to stdout. For SessionStart and
#      UserPromptSubmit, non-empty stdout is injected into the model context as
#      additional context, which would corrupt the conversation.
#
# Diagnostics go to stderr (collected by Qoder's hook logs) and to the
# collector's own logger-error.log.

HOOK_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
INPUT=$(cat 2>/dev/null)

# Never let a logging failure propagate into the agent's control flow.
trap 'exit 0' HUP INT TERM

quiet_note() {
  # One-time-ish notice about degraded collection, written to the log directory.
  NOTE_DIR=${QODER_LOG_DIR:-"$HOME/.qoder/logs"}
  NOTE_FILE="$NOTE_DIR/.request-logger/logger-error.log"
  if [ -d "$NOTE_DIR/.request-logger" ] || mkdir -p "$NOTE_DIR/.request-logger" 2>/dev/null; then
    printf '%s\n' "$1" >>"$NOTE_FILE" 2>/dev/null
  fi
}

find_node() {
  # 1. PATH
  if command -v node >/dev/null 2>&1; then
    command -v node
    return 0
  fi
  # 2. Fixed install locations (Homebrew on Apple Silicon / Intel, system)
  for candidate in \
    /opt/homebrew/bin/node \
    /usr/local/bin/node \
    "$HOME/.volta/bin/node" \
    "$HOME/Library/pnpm/node" \
    "$HOME/.linuxbrew/bin/node"
  do
    [ -x "$candidate" ] && { printf '%s\n' "$candidate"; return 0; }
  done
  # 3. Version managers: newest first
  if [ -d "$HOME/.nvm/versions/node" ]; then
    latest=$(ls -d "$HOME"/.nvm/versions/node/*/bin/node 2>/dev/null | sort -Vr | head -n 1)
    [ -n "$latest" ] && [ -x "$latest" ] && { printf '%s\n' "$latest"; return 0; }
  fi
  if [ -d "$HOME/.fnm/node-versions" ]; then
    latest=$(ls -d "$HOME"/.fnm/node-versions/*/installation/bin/node 2>/dev/null | sort -Vr | head -n 1)
    [ -n "$latest" ] && [ -x "$latest" ] && { printf '%s\n' "$latest"; return 0; }
  fi
  # 4. Runtime bundled with the Qoder application, when exposed as a CLI
  for candidate in \
    "/Applications/Qoder.app/Contents/Resources/app/node" \
    "$HOME/.qoder/.bin/node"
  do
    [ -x "$candidate" ] && { printf '%s\n' "$candidate"; return 0; }
  done
  return 1
}

NODE_BIN=$(find_node)

# Bundled deployment config (hooks/config.json): the hook runner does not
# inject the hooks.json env block, so the fleet package carries its settings
# in that file. The collector reads every key from the file itself except
# NODE_EXTRA_CA_CERTS, which only takes effect when set before Node starts
# (TLS trust is initialised at boot) - hence exported here, guarded so an
# absent/unreadable file changes nothing.
if [ -n "$NODE_BIN" ] && [ -f "$HOOK_DIR/config.json" ]; then
  BUNDLED_CA=$("$NODE_BIN" -e 'try{const c=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));if(typeof c.NODE_EXTRA_CA_CERTS==="string")process.stdout.write(c.NODE_EXTRA_CA_CERTS)}catch(e){}' "$HOOK_DIR/config.json" 2>/dev/null)
  if [ -n "$BUNDLED_CA" ]; then
    NODE_EXTRA_CA_CERTS="$BUNDLED_CA"
    export NODE_EXTRA_CA_CERTS
  fi
fi

if [ -n "$NODE_BIN" ]; then
  printf '%s' "$INPUT" | "$NODE_BIN" "$HOOK_DIR/log-request.js" >/dev/null 2>&1
  exit 0
fi

# No Node.js: prefer jq so the audit trail keeps flowing at reduced fidelity.
# Credits / token accounting needs transcript diffing and is skipped here.
if command -v jq >/dev/null 2>&1 && [ -n "$INPUT" ]; then
  printf '%s' "$INPUT" | sh "$HOOK_DIR/log-request.sh" >/dev/null 2>&1
  exit 0
fi

quiet_note "[$(date -u +%Y-%m-%dT%H:%M:%SZ)] collector unavailable: neither node nor jq found; event dropped"
exit 0
