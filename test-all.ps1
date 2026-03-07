###############################################################################
# Mini Distributed Database - Complete Test & Benchmark Script
# Run from: C:\Users\vedan\Mini-Distributed-Database - Copy
# Prerequisites: Docker cluster running (docker compose up --build -d)
###############################################################################

$ErrorActionPreference = "Continue"

# Auto-detect leader node
$BASE = "http://localhost:8081"
foreach ($port in @(8081, 8082, 8083)) {
    try {
        $s = Invoke-RestMethod "http://localhost:$port/api/cluster/status" -TimeoutSec 3
        if ($s.role -eq "LEADER") { $BASE = "http://localhost:$port"; break }
    } catch {}
}
Write-Host "Using leader at: $BASE" -ForegroundColor Cyan

function Write-Section($title) {
    Write-Host ""
    Write-Host "=" * 70 -ForegroundColor Cyan
    Write-Host "  $title" -ForegroundColor Cyan
    Write-Host "=" * 70 -ForegroundColor Cyan
}

function Post($path, $body) {
    Invoke-RestMethod -Uri "$BASE$path" -Method Post -ContentType "application/json" -Body $body -TimeoutSec 120
}

function Get-Api($path) {
    Invoke-RestMethod -Uri "$BASE$path" -Method Get -TimeoutSec 30
}

###############################################################################
# 1. CLUSTER HEALTH CHECK
###############################################################################
Write-Section "1. CLUSTER HEALTH CHECK"

Write-Host "`nChecking all 3 nodes..." -ForegroundColor Yellow
@(8081, 8082, 8083) | ForEach-Object {
    $s = Invoke-RestMethod "http://localhost:$_/api/cluster/status"
    Write-Host "  $($s.nodeId): $($s.role) | term=$($s.term) | commitIndex=$($s.commitIndex) | peers healthy: $($s.peerHealth | ConvertTo-Json -Compress)" -ForegroundColor Green
}

###############################################################################
# 2. BASIC KV OPERATIONS
###############################################################################
Write-Section "2. BASIC KV OPERATIONS (Standard Raft Path)"

# PUT
Write-Host "`n[PUT] key=hello, value=world" -ForegroundColor Yellow
$put = Post "/api/kv/put" '{"key":"hello","value":"world"}'
Write-Host "  success=$($put.success) latency=$($put.latencyMs)ms" -ForegroundColor Green

# GET
Write-Host "`n[GET] key=hello" -ForegroundColor Yellow
$get = Post "/api/kv/get" '{"key":"hello"}'
Write-Host "  found=$($get.found) value=$($get.value) state=$($get.versionState) readMode=$($get.readMode)" -ForegroundColor Green

# Multiple writes
Write-Host "`n[PUT] Writing 5 keys..." -ForegroundColor Yellow
1..5 | ForEach-Object {
    $b = "{`"key`":`"user:$_`",`"value`":`"data-$_`"}"
    $r = Post "/api/kv/put" $b
    Write-Host "  user:$_ -> success=$($r.success) latency=$($r.latencyMs)ms" -ForegroundColor Green
}

# SCAN
Write-Host "`n[SCAN] prefix=user:" -ForegroundColor Yellow
$scan = Post "/api/kv/scan" '{"startKey":"user:","endKey":"user:~","limit":10}'
Write-Host "  Found $($scan.entries.Count) entries:" -ForegroundColor Green
$scan.entries | ForEach-Object { Write-Host "    $($_.key) = $($_.value) [$($_.versionState)]" }

# DELETE
Write-Host "`n[DELETE] key=user:5" -ForegroundColor Yellow
$del = Post "/api/kv/delete" '{"key":"user:5"}'
Write-Host "  success=$($del.success)" -ForegroundColor Green

# VERSION HISTORY
Write-Host "`n[VERSIONS] key=hello" -ForegroundColor Yellow
$ver = Get-Api "/api/kv/versions/hello"
Write-Host "  $($ver.Count) version(s):" -ForegroundColor Green
$ver | ForEach-Object { Write-Host "    ts=$($_.timestamp) state=$($_.versionState)" }

###############################################################################
# 3. SPECULATIVE WRITE PATH (THE NOVEL CONTRIBUTION)
###############################################################################
Write-Section "3. SPECULATIVE WRITES (Novel Contribution)"

# Enable speculation (explicitly set enabled=true)
Write-Host "`n[TOGGLE] Enabling speculation..." -ForegroundColor Yellow
$toggle = Post "/api/kv/speculation/toggle" '{"enabled":true}'
Write-Host "  Speculation enabled: $($toggle.speculationEnabled)" -ForegroundColor Green

# Speculative PUT
Write-Host "`n[SPECULATIVE PUT] key=fast-1, value=speed-test" -ForegroundColor Yellow
$sp = Post "/api/kv/speculative-put" '{"key":"fast-1","value":"speed-test"}'
Write-Host "  success=$($sp.success) speculative=$($sp.speculative) ackPolicy=$($sp.ackPolicy)" -ForegroundColor Green
Write-Host "  writeLatency=$($sp.writeLatencyMs)ms raftLogIndex=$($sp.raftLogIndex)" -ForegroundColor Green

# Speculative PUT with WAIT_FOR_COMMIT (for apps needing strong consistency)
Write-Host "`n[SPECULATIVE PUT + WAIT] key=safe-1, waitForCommit=true" -ForegroundColor Yellow
$sp2 = Post "/api/kv/speculative-put" '{"key":"safe-1","value":"committed-value","waitForCommit":true}'
Write-Host "  success=$($sp2.success) speculative=$($sp2.speculative) ackPolicy=$($sp2.ackPolicy)" -ForegroundColor Green
Write-Host "  writeLatency=$($sp2.writeLatencyMs)ms" -ForegroundColor Green

# Read with different modes
Write-Host "`n[READ LINEARIZABLE] key=fast-1" -ForegroundColor Yellow
$r1 = Post "/api/kv/get" '{"key":"fast-1","readMode":"LINEARIZABLE"}'
Write-Host "  found=$($r1.found) value=$($r1.value) state=$($r1.versionState) mode=$($r1.readMode)" -ForegroundColor Green

Write-Host "`n[READ SPECULATIVE] key=fast-1" -ForegroundColor Yellow
$r2 = Post "/api/kv/get" '{"key":"fast-1","readMode":"SPECULATIVE"}'
Write-Host "  found=$($r2.found) value=$($r2.value) state=$($r2.versionState) mode=$($r2.readMode)" -ForegroundColor Green

# Speculative DELETE
Write-Host "`n[SPECULATIVE DELETE] key=fast-1" -ForegroundColor Yellow
$sd = Post "/api/kv/speculative-delete" '{"key":"fast-1"}'
Write-Host "  success=$($sd.success) speculative=$($sd.speculative)" -ForegroundColor Green

# Speculation metrics
Write-Host "`n[METRICS] Speculation stats:" -ForegroundColor Yellow
$metrics = Get-Api "/api/kv/speculation/metrics"
Write-Host "  Enabled: $($metrics.speculationEnabled)" -ForegroundColor Green
Write-Host "  Speculative writes: $($metrics.speculativeWriteCount)" -ForegroundColor Green
Write-Host "  Standard writes: $($metrics.standardWriteCount)" -ForegroundColor Green
Write-Host "  Commit promotions: $($metrics.mvccCommitPromotions)" -ForegroundColor Green
Write-Host "  Rollbacks: $($metrics.mvccRollbacks)" -ForegroundColor Green
Write-Host "  Success rate: $([math]::Round($metrics.mvccSpeculationSuccessRate * 100, 1))%" -ForegroundColor Green
Write-Host "  Avg speculative latency: $([math]::Round($metrics.avgSpeculativeLatencyMs, 2))ms" -ForegroundColor Green
Write-Host "  Avg standard latency: $([math]::Round($metrics.avgStandardLatencyMs, 2))ms" -ForegroundColor Green

###############################################################################
# 4. A/B BENCHMARK — THE KEY RESULT FOR THE PAPER
###############################################################################
Write-Section "4. A/B BENCHMARK: Speculative vs Standard (Paper Figure 1)"

Write-Host "`nRunning 1000-op benchmark (10 concurrent threads, 256B values)..." -ForegroundColor Yellow
Write-Host "  This takes ~10-20 seconds..." -ForegroundColor Yellow

$bench = Post "/api/benchmark/run" '{"numWrites":1000}'

Write-Host "`n--- STANDARD RAFT (optimized baseline with 0.5ms batching) ---" -ForegroundColor Magenta
$std = $bench.standard
Write-Host "  Total ops:     $($std.totalOps)" -ForegroundColor White
Write-Host "  Throughput:    $([math]::Round($std.throughputOpsPerSec, 1)) ops/sec" -ForegroundColor White
Write-Host "  p50 latency:   $([math]::Round($std.writeLatency.p50Ms, 2))ms" -ForegroundColor White
Write-Host "  p95 latency:   $([math]::Round($std.writeLatency.p95Ms, 2))ms" -ForegroundColor White
Write-Host "  p99 latency:   $([math]::Round($std.writeLatency.p99Ms, 2))ms" -ForegroundColor White
Write-Host "  avg latency:   $([math]::Round($std.writeLatency.avgMs, 2))ms" -ForegroundColor White

Write-Host "`n--- SPECULATIVE MVCC (our contribution) ---" -ForegroundColor Green
$spec = $bench.speculative
Write-Host "  Total ops:     $($spec.totalOps)" -ForegroundColor White
Write-Host "  Throughput:    $([math]::Round($spec.throughputOpsPerSec, 1)) ops/sec" -ForegroundColor White
Write-Host "  p50 latency:   $([math]::Round($spec.writeLatency.p50Ms, 2))ms" -ForegroundColor White
Write-Host "  p95 latency:   $([math]::Round($spec.writeLatency.p95Ms, 2))ms" -ForegroundColor White
Write-Host "  p99 latency:   $([math]::Round($spec.writeLatency.p99Ms, 2))ms" -ForegroundColor White
Write-Host "  avg latency:   $([math]::Round($spec.writeLatency.avgMs, 2))ms" -ForegroundColor White

$improvement = [math]::Round((1 - $spec.writeLatency.p50Ms / $std.writeLatency.p50Ms) * 100, 1)
Write-Host "`n>>> P50 LATENCY REDUCTION: ${improvement}% <<<" -ForegroundColor Yellow

$p95improvement = [math]::Round((1 - $spec.writeLatency.p95Ms / $std.writeLatency.p95Ms) * 100, 1)
Write-Host ">>> P95 LATENCY REDUCTION: ${p95improvement}% <<<" -ForegroundColor Yellow

Write-Host "`n--- PROMOTION LATENCY (background consensus) ---" -ForegroundColor DarkYellow
if ($spec.promotionLatency) {
    Write-Host "  Promotions:    $($spec.promotionLatency.count) / $($spec.totalOps)" -ForegroundColor White
    Write-Host "  p50 promotion: $([math]::Round($spec.promotionLatency.p50Ms, 2))ms" -ForegroundColor White
    Write-Host "  p99 promotion: $([math]::Round($spec.promotionLatency.p99Ms, 2))ms" -ForegroundColor White
    Write-Host "  avg promotion: $([math]::Round($spec.promotionLatency.avgMs, 2))ms" -ForegroundColor White
}
if ($spec.promotionSuccessRate) {
    Write-Host "  Success rate:  $([math]::Round($spec.promotionSuccessRate * 100, 1))%" -ForegroundColor White
}

Write-Host "`n--- BASELINE FAIRNESS ---" -ForegroundColor DarkCyan
$bo = $bench.baselineOptimizations
Write-Host "  Write batching: $($bo.writeBatching)" -ForegroundColor White
Write-Host "  Batch window:   $($bo.batchWindowUs)us" -ForegroundColor White
Write-Host "  Description:    $($bo.description)" -ForegroundColor White

###############################################################################
# 5. LATENCY BREAKDOWN (Paper Figure: Latency Dissection)
###############################################################################
Write-Section "5. LATENCY BREAKDOWN (Paper Figure 2)"

Write-Host "`nDissecting speculative write path into exact microsecond buckets..." -ForegroundColor Yellow
$bd = Post "/api/benchmark/latency-breakdown" '{"numOps":500,"concurrency":10}'

Write-Host "  Phase            | p50       | p95       | p99       | avg" -ForegroundColor White
Write-Host "  -----------------|-----------|-----------|-----------|----------" -ForegroundColor White
if ($bd.logAppend) {
    Write-Host ("  Log Append       | {0,7:N1}us | {1,7:N1}us | {2,7:N1}us | {3,7:N1}us" -f $bd.logAppend.p50Us, $bd.logAppend.p95Us, $bd.logAppend.p99Us, $bd.logAppend.avgUs) -ForegroundColor Green
}
if ($bd.mvccWrite) {
    Write-Host ("  MVCC Write       | {0,7:N1}us | {1,7:N1}us | {2,7:N1}us | {3,7:N1}us" -f $bd.mvccWrite.p50Us, $bd.mvccWrite.p95Us, $bd.mvccWrite.p99Us, $bd.mvccWrite.avgUs) -ForegroundColor Green
}
if ($bd.callbackSetup) {
    Write-Host ("  Callback+Trigger | {0,7:N1}us | {1,7:N1}us | {2,7:N1}us | {3,7:N1}us" -f $bd.callbackSetup.p50Us, $bd.callbackSetup.p95Us, $bd.callbackSetup.p99Us, $bd.callbackSetup.avgUs) -ForegroundColor Green
}
if ($bd.totalWritePath) {
    Write-Host ("  TOTAL WRITE PATH | {0,7:N1}us | {1,7:N1}us | {2,7:N1}us | {3,7:N1}us" -f $bd.totalWritePath.p50Us, $bd.totalWritePath.p95Us, $bd.totalWritePath.p99Us, $bd.totalWritePath.avgUs) -ForegroundColor Yellow
}
if ($bd.promotionLatency) {
    Write-Host ("  Promotion (bg)   | {0,7:N2}ms | {1,7:N2}ms | {2,7:N2}ms | {3,7:N2}ms" -f $bd.promotionLatency.p50Ms, $bd.promotionLatency.p95Ms, $bd.promotionLatency.p99Ms, $bd.promotionLatency.avgMs) -ForegroundColor DarkYellow
}
if ($bd.promotionRate) {
    Write-Host "  Promotion rate: $([math]::Round($bd.promotionRate * 100, 1))%" -ForegroundColor White
}

###############################################################################
# 6. ROLLBACK BENCHMARK (Paper Figure 3)
###############################################################################
Write-Section "6. ROLLBACK BENCHMARK (Paper Figure 3)"

Write-Host "`nMeasuring O(1) rollback cost..." -ForegroundColor Yellow
$rb = Post "/api/benchmark/rollback-benchmark" '{"numWrites":200}'
Write-Host "  Versions created:  $($rb.numVersions)" -ForegroundColor Green
Write-Host "  Create time:       $([math]::Round($rb.createTimeMs, 2))ms" -ForegroundColor Green
Write-Host "  Rollback time:     $([math]::Round($rb.rollbackTimeMs, 2))ms" -ForegroundColor Green
Write-Host "  Rolled back count: $($rb.rolledBackCount)" -ForegroundColor Green
Write-Host "  Per-version cost:  $([math]::Round($rb.perVersionRollbackUs, 2))us" -ForegroundColor Green
Write-Host "  Storage overhead:  $($rb.storageOverheadBytesPerVersion) byte/version (state byte only)" -ForegroundColor Green

###############################################################################
# 7. GC STRESS TEST (Paper Figure 4)
###############################################################################
Write-Section "7. GC STRESS TEST (Paper Figure 4)"

Write-Host "`nRunning GC under 30% rollback rate..." -ForegroundColor Yellow
$gc = Post "/api/benchmark/gc-stress" '{"rollbackRate":0.3,"numVersions":500}'
Write-Host "  Versions:         $($gc.numVersions)" -ForegroundColor Green
Write-Host "  Rollback rate:    $($gc.rollbackPercent)%" -ForegroundColor Green
Write-Host "  Rolled back:      $($gc.rolledBackCount)" -ForegroundColor Green
Write-Host "  Committed:        $($gc.committedCount)" -ForegroundColor Green
Write-Host "  GC purged:        $($gc.gc.purgedCount) versions" -ForegroundColor Green
Write-Host "  GC duration:      $([math]::Round($gc.gc.gcDurationMs, 2))ms" -ForegroundColor Green
Write-Host "  Per-version GC:   $([math]::Round($gc.gc.perVersionGcUs, 2))us" -ForegroundColor Green

$tc = $gc.throughputComparison
Write-Host "`n  Throughput without GC: $([math]::Round($tc.withoutGcOpsPerSec, 0)) ops/sec" -ForegroundColor White
Write-Host "  Throughput during GC:  $([math]::Round($tc.duringGcOpsPerSec, 0)) ops/sec" -ForegroundColor White
Write-Host "  Impact:                $([math]::Round($tc.throughputImpactPercent, 1))%" -ForegroundColor White
Write-Host "  Verdict:               $($gc.verdict)" -ForegroundColor $(if ($gc.verdict -match "PASS") { "Green" } else { "Red" })

###############################################################################
# 8. VARYING ROLLBACK RATES (Paper Figure 5)
###############################################################################
Write-Section "8. GC ACROSS ROLLBACK RATES (Paper Figure 5)"

Write-Host "`nSweeping rollback rates: 10%, 30%, 50%, 70%, 90%..." -ForegroundColor Yellow
@(0.1, 0.3, 0.5, 0.7, 0.9) | ForEach-Object {
    $rate = $_
    $body = "{`"rollbackRate`":$rate,`"numVersions`":300}"
    $g = Post "/api/benchmark/gc-stress" $body
    $impact = [math]::Round($g.throughputComparison.throughputImpactPercent, 1)
    $gcUs = [math]::Round($g.gc.perVersionGcUs, 1)
    Write-Host "  Rate=$([math]::Round($rate*100))%: impact=${impact}% | gc/version=${gcUs}us | verdict=$($g.verdict)" -ForegroundColor $(if ($g.verdict -match "PASS") { "Green" } else { "Yellow" })
}

###############################################################################
# 9. COMPARISON TABLE (Paper Table 1)
###############################################################################
Write-Section "9. COMPARISON SUMMARY"

$finalMetrics = Get-Api "/api/kv/speculation/metrics"
Write-Host ""
Write-Host "  Metric                  | Value" -ForegroundColor White
Write-Host "  ------------------------|------------------" -ForegroundColor White
Write-Host "  Speculation enabled     | $($finalMetrics.speculationEnabled)" -ForegroundColor Green
Write-Host "  Total speculative writes| $($finalMetrics.speculativeWriteCount)" -ForegroundColor Green
Write-Host "  Total standard writes   | $($finalMetrics.standardWriteCount)" -ForegroundColor Green
Write-Host "  Commit promotions       | $($finalMetrics.mvccCommitPromotions)" -ForegroundColor Green
Write-Host "  Rollbacks               | $($finalMetrics.mvccRollbacks)" -ForegroundColor Green
Write-Host "  Success rate            | $([math]::Round($finalMetrics.mvccSpeculationSuccessRate * 100, 2))%" -ForegroundColor Green
Write-Host "  Avg spec latency        | $([math]::Round($finalMetrics.avgSpeculativeLatencyMs, 2))ms" -ForegroundColor Green
Write-Host "  Avg std latency         | $([math]::Round($finalMetrics.avgStandardLatencyMs, 2))ms" -ForegroundColor Green
Write-Host "  Avg promotion latency   | $([math]::Round($finalMetrics.avgPromotionLatencyMs, 2))ms" -ForegroundColor Green

###############################################################################
# 10. CLUSTER FINAL STATE
###############################################################################
Write-Section "10. CLUSTER FINAL STATE"

@(8081, 8082, 8083) | ForEach-Object {
    $s = Invoke-RestMethod "http://localhost:$_/api/cluster/status"
    Write-Host "  $($s.nodeId): $($s.role) | term=$($s.term) | log=$($s.logSize) | commit=$($s.commitIndex)" -ForegroundColor Green
}

Write-Host ""
Write-Host "=" * 70 -ForegroundColor Cyan
Write-Host "  ALL TESTS COMPLETE" -ForegroundColor Cyan
Write-Host "  Dashboard: http://localhost:3000" -ForegroundColor Cyan
Write-Host "  Grafana:   http://localhost:3001 (admin/admin)" -ForegroundColor Cyan
Write-Host "=" * 70 -ForegroundColor Cyan
