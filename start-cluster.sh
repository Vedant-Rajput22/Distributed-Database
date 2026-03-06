#!/usr/bin/env bash
#
# start-cluster.sh — Start the Mini Distributed Database cluster
#
# Usage:
#   ./start-cluster.sh              # 3 nodes + dashboard (default)
#   ./start-cluster.sh -n 5         # 5 nodes + dashboard
#   ./start-cluster.sh --skip-build # skip Maven build
#   ./start-cluster.sh --skip-ui    # skip frontend
#   ./start-cluster.sh --help

set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
BACKEND="$ROOT/backend"
FRONTEND="$ROOT/frontend"
JAR="$BACKEND/target/mini-distributed-db-1.0.0-SNAPSHOT-boot.jar"
LOGS="$ROOT/logs"
PIDFILE="$ROOT/.cluster-pids"

NODES=3
SKIP_BUILD=false
SKIP_FRONTEND=false

# ── Colors ──────────────────────────────────────────────
CYAN='\033[0;36m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
RED='\033[0;31m'
MAGENTA='\033[0;35m'
NC='\033[0m'

step()  { echo -e "\n${CYAN}>> $1${NC}"; }
ok()    { echo -e "   ${GREEN}$1${NC}"; }
info()  { echo -e "   ${YELLOW}$1${NC}"; }
err()   { echo -e "   ${RED}$1${NC}"; }

# ── Parse args ──────────────────────────────────────────
while [[ $# -gt 0 ]]; do
    case $1 in
        -n|--nodes)       NODES="$2"; shift 2 ;;
        --skip-build)     SKIP_BUILD=true; shift ;;
        --skip-ui)        SKIP_FRONTEND=true; shift ;;
        -h|--help)
            echo "Usage: $0 [-n NODES] [--skip-build] [--skip-ui]"
            echo "  -n, --nodes N     Number of nodes (1-5, default: 3)"
            echo "  --skip-build      Skip Maven build"
            echo "  --skip-ui         Skip starting the React dashboard"
            exit 0 ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

if [[ $NODES -lt 1 || $NODES -gt 5 ]]; then
    err "Nodes must be between 1 and 5."
    exit 1
fi

# ── Port helpers ────────────────────────────────────────
http_port() { echo $(( 8080 + 2 * ($1 - 1) )); }
grpc_port() { echo $(( 9090 + 2 * ($1 - 1) )); }

get_peers() {
    local node=$1 total=$2 peers=""
    for (( i=1; i<=total; i++ )); do
        if [[ $i -ne $node ]]; then
            [[ -n "$peers" ]] && peers+=","
            peers+="node-$i@localhost:$(grpc_port $i)"
        fi
    done
    echo "$peers"
}

# ── Banner ──────────────────────────────────────────────
echo ""
echo -e "${MAGENTA}  ╔══════════════════════════════════════════╗${NC}"
echo -e "${MAGENTA}  ║   Mini Distributed Database — Launcher   ║${NC}"
echo -e "${MAGENTA}  ╚══════════════════════════════════════════╝${NC}"
echo ""

# ── Step 0: Stop previous cluster ───────────────────────
if [[ -f "$PIDFILE" ]]; then
    step "Stopping previously running cluster..."
    while IFS= read -r pid; do
        [[ -z "$pid" ]] && continue
        if kill -0 "$pid" 2>/dev/null; then
            kill -9 "$pid" 2>/dev/null && ok "Stopped old PID $pid"
        fi
    done < "$PIDFILE"
    rm -f "$PIDFILE"
    # Kill frontend on port 3000
    fp=$(lsof -ti :3000 2>/dev/null || true)
    [[ -n "$fp" ]] && kill -9 $fp 2>/dev/null
    sleep 2
fi

# Clean up stale RocksDB LOCK files
lock_files=$(find "$ROOT" -path "*/rocksdb*/LOCK" 2>/dev/null || true)
if [[ -n "$lock_files" ]]; then
    step "Cleaning stale RocksDB LOCK files..."
    echo "$lock_files" | while IFS= read -r lf; do
        rm -f "$lf" && ok "Removed $lf"
    done
fi

# ── Step 1: Build ───────────────────────────────────────
if [[ "$SKIP_BUILD" == false ]]; then
    step "Building backend (Maven)..."
    cd "$BACKEND"
    mvn clean package -DskipTests -q
    cd "$ROOT"
    ok "Backend built successfully."
else
    if [[ ! -f "$JAR" ]]; then
        err "JAR not found at $JAR. Run without --skip-build first."
        exit 1
    fi
    info "Skipping build (--skip-build). Using existing JAR."
fi

# ── Step 2: Logs dir ───────────────────────────────────
mkdir -p "$LOGS"

# ── Step 3: Start nodes ────────────────────────────────
step "Starting $NODES Raft nodes..."
> "$PIDFILE"  # clear PID file

for (( i=1; i<=NODES; i++ )); do
    HP=$(http_port $i)
    GP=$(grpc_port $i)
    NID="node-$i"
    PEERS=$(get_peers $i $NODES)

    SERVER_PORT=$HP \
    GRPC_SERVER_PORT=$GP \
    NODE_ID=$NID \
    CLUSTER_PEERS="$PEERS" \
        java -jar "$JAR" \
            > "$LOGS/$NID.log" 2>"$LOGS/$NID-error.log" &

    PID=$!
    echo "$PID" >> "$PIDFILE"
    ok "$NID  HTTP: $HP  gRPC: $GP  PID: $PID"
done

# ── Step 4: Frontend ───────────────────────────────────
if [[ "$SKIP_FRONTEND" == false ]]; then
    step "Starting React dashboard..."
    cd "$FRONTEND"

    if [[ ! -d "node_modules" ]]; then
        info "Installing frontend dependencies (npm install)..."
        npm install --silent 2>/dev/null
    fi

    npm run dev > "$LOGS/frontend.log" 2>"$LOGS/frontend-error.log" &
    FPID=$!
    echo "$FPID" >> "$PIDFILE"
    cd "$ROOT"
    ok "Dashboard starting...  PID: $FPID"
fi

# ── Summary ─────────────────────────────────────────────
echo ""
echo -e "${GREEN}  ┌──────────────────────────────────────────┐${NC}"
echo -e "${GREEN}  │         Cluster is starting up!          │${NC}"
echo -e "${GREEN}  └──────────────────────────────────────────┘${NC}"
echo ""
info "Wait ~10 seconds for nodes to elect a leader."
echo ""

for (( i=1; i<=NODES; i++ )); do
    HP=$(http_port $i)
    echo -e "   Node $i API:   ${CYAN}http://localhost:$HP${NC}"
done
if [[ "$SKIP_FRONTEND" == false ]]; then
    echo -e "   Dashboard:    ${CYAN}http://localhost:3000${NC}"
fi
echo ""
echo -e "   Logs:         ${YELLOW}logs/${NC}"
echo -e "   Stop cluster: ${YELLOW}./stop-cluster.sh${NC}"
echo ""
