#!/bin/bash
# Cloud Benchmark Runner — runs on the Azure VM
set -e

LEADER_PORT=8081

echo "=== Finding Leader ==="
for port in 8081 8082 8083; do
    STATUS=$(curl -s http://localhost:$port/api/cluster/status 2>/dev/null || echo '{}')
    IS_LEADER=$(echo "$STATUS" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('role',''))" 2>/dev/null || echo "")
    if [ "$IS_LEADER" = "LEADER" ]; then
        LEADER_PORT=$port
        NODE_ID=$(echo "$STATUS" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('nodeId',''))" 2>/dev/null)
        echo "Leader found: $NODE_ID on port $LEADER_PORT"
        break
    fi
done

echo ""
echo "=== Performance Benchmark (1000 ops, 10 concurrency, 256B) ==="
curl -s -X POST "http://localhost:${LEADER_PORT}/api/benchmark/run" \
  -H "Content-Type: application/json" \
  -d '{"numOps":1000,"concurrency":10,"valueSizeBytes":256}' \
  | python3 -m json.tool
echo ""

echo "=== Performance Benchmark (5000 ops, 20 concurrency, 256B) ==="
curl -s -X POST "http://localhost:${LEADER_PORT}/api/benchmark/run" \
  -H "Content-Type: application/json" \
  -d '{"numOps":5000,"concurrency":20,"valueSizeBytes":256}' \
  | python3 -m json.tool
echo ""

echo "=== Performance Benchmark (1000 ops, 50 concurrency, 1024B) ==="
curl -s -X POST "http://localhost:${LEADER_PORT}/api/benchmark/run" \
  -H "Content-Type: application/json" \
  -d '{"numOps":1000,"concurrency":50,"valueSizeBytes":1024}' \
  | python3 -m json.tool
echo ""

echo "=== Correctness Benchmark ==="
curl -s -X POST "http://localhost:${LEADER_PORT}/api/benchmark/failure/correctness" \
  -H "Content-Type: application/json" \
  | python3 -m json.tool
echo ""

echo "=== Leader Crash Recovery Benchmark ==="
curl -s -X POST "http://localhost:${LEADER_PORT}/api/benchmark/failure/leader-crash" \
  -H "Content-Type: application/json" \
  | python3 -m json.tool
echo ""

echo "=== Network Partition Benchmark ==="
curl -s -X POST "http://localhost:${LEADER_PORT}/api/benchmark/failure/partition" \
  -H "Content-Type: application/json" \
  | python3 -m json.tool
echo ""

echo "=== ALL BENCHMARKS COMPLETE ==="
