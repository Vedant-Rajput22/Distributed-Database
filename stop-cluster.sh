#!/usr/bin/env bash
#
# stop-cluster.sh — Stop all Mini Distributed Database processes
#
# Usage:
#   ./stop-cluster.sh          # stop all nodes + frontend
#   ./stop-cluster.sh --clean  # also delete logs/ and data/

set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
PIDFILE="$ROOT/.cluster-pids"

CLEAN=false
[[ "${1:-}" == "--clean" ]] && CLEAN=true

# ── Colors ──────────────────────────────────────────────
CYAN='\033[0;36m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
MAGENTA='\033[0;35m'
NC='\033[0m'

step()  { echo -e "\n${CYAN}>> $1${NC}"; }
ok()    { echo -e "   ${GREEN}$1${NC}"; }
info()  { echo -e "   ${YELLOW}$1${NC}"; }

echo ""
echo -e "${MAGENTA}  ╔══════════════════════════════════════════╗${NC}"
echo -e "${MAGENTA}  ║    Mini Distributed Database — Stopper   ║${NC}"
echo -e "${MAGENTA}  ╚══════════════════════════════════════════╝${NC}"

# ── Stop from PID file ──────────────────────────────────
if [[ -f "$PIDFILE" ]]; then
    step "Stopping cluster processes..."
    while IFS= read -r pid; do
        pid=$(echo "$pid" | tr -d '[:space:]')
        [[ -z "$pid" ]] && continue
        if kill -0 "$pid" 2>/dev/null; then
            kill "$pid" 2>/dev/null || true
            ok "Stopped PID $pid"
        else
            info "PID $pid already stopped."
        fi
    done < "$PIDFILE"
    rm -f "$PIDFILE"
    ok "PID file cleaned up."
else
    info "No .cluster-pids file found."
    step "Trying to find java processes (mini-distributed-db)..."
    pkill -f "mini-distributed-db" 2>/dev/null && ok "Killed java processes." || info "No matching java processes."
fi

# ── Kill dev servers (dashboard + demo app) ─────────────
step "Checking for dev servers (dashboard + demo app)..."
for devport in 3000 4000; do
    DPID=$(lsof -ti :$devport 2>/dev/null || true)
    if [[ -n "$DPID" ]]; then
        kill "$DPID" 2>/dev/null || true
        ok "Stopped dev server on port $devport (PID $DPID)."
    else
        info "No dev server running on port $devport."
    fi
done

# ── Clean ───────────────────────────────────────────────
if [[ "$CLEAN" == true ]]; then
    step "Cleaning up data and logs..."
    for dir in "$ROOT/logs" "$ROOT/data" "$ROOT/backend/data"; do
        if [[ -d "$dir" ]]; then
            rm -rf "$dir"
            ok "Deleted $dir"
        fi
    done
fi

echo ""
echo -e "${GREEN}  ┌──────────────────────────────────────────┐${NC}"
echo -e "${GREEN}  │           Cluster stopped.               │${NC}"
echo -e "${GREEN}  └──────────────────────────────────────────┘${NC}"
echo ""
