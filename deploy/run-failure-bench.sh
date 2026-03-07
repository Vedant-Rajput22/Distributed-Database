#!/bin/bash
# Failure Benchmarks Only
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
echo "=== Correctness Benchmark ==="
curl -s -X POST "http://localhost:${LEADER_PORT}/api/benchmark/failure/correctness" \
  -H "Content-Type: application/json" \
  -d '{"committedCount":50,"uncommittedCount":50,"valueSizeBytes":256}' \
  | python3 -m json.tool
echo ""

echo "=== Leader Crash Recovery Benchmark ==="
# Need to find current leader again since correctness test may have changed it
for port in 8081 8082 8083; do
    STATUS=$(curl -s http://localhost:$port/api/cluster/status 2>/dev/null || echo '{}')
    IS_LEADER=$(echo "$STATUS" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('role',''))" 2>/dev/null || echo "")
    if [ "$IS_LEADER" = "LEADER" ]; then
        LEADER_PORT=$port
        NODE_ID=$(echo "$STATUS" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('nodeId',''))" 2>/dev/null)
        echo "Current leader: $NODE_ID on port $LEADER_PORT"
        break
    fi
done
curl -s -X POST "http://localhost:${LEADER_PORT}/api/benchmark/failure/leader-crash" \
  -H "Content-Type: application/json" \
  -d '{"numOps":200,"crashDelayMs":100,"concurrency":10,"valueSizeBytes":256}' \
  | python3 -m json.tool
echo ""

echo "=== Network Partition Benchmark ==="
# Need to find leader again since leader-crash test changes it
sleep 10
for port in 8081 8082 8083; do
    STATUS=$(curl -s http://localhost:$port/api/cluster/status 2>/dev/null || echo '{}')
    IS_LEADER=$(echo "$STATUS" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('role',''))" 2>/dev/null || echo "")
    if [ "$IS_LEADER" = "LEADER" ]; then
        LEADER_PORT=$port
        NODE_ID=$(echo "$STATUS" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('nodeId',''))" 2>/dev/null)
        echo "Current leader: $NODE_ID on port $LEADER_PORT"
        break
    fi
done
curl -s -X POST "http://localhost:${LEADER_PORT}/api/benchmark/failure/partition" \
  -H "Content-Type: application/json" \
  -d '{"numOps":200,"partitionDurationMs":2000,"concurrency":10}' \
  | python3 -m json.tool
echo ""

echo "=== FAILURE BENCHMARKS COMPLETE ==="
