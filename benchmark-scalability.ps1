###############################################################################
# Multi-Node Scalability Benchmark
# Runs A/B benchmark on 3-node, 5-node, and 7-node clusters sequentially
# Produces paper-quality results for scalability analysis
###############################################################################

param(
    [int]$NumOps = 1000,
    [int]$Concurrency = 10,
    [int]$ValueSize = 256,
    [int]$WaitSeconds = 25
)

$ErrorActionPreference = "Continue"
$ROOT = "c:\Users\vedan\Mini-Distributed-Database - Copy"

function Write-Section($title) {
    Write-Host ""
    Write-Host "=" * 78 -ForegroundColor Cyan
    Write-Host "  $title" -ForegroundColor Cyan
    Write-Host "=" * 78 -ForegroundColor Cyan
}

function Find-Leader($ports) {
    foreach ($port in $ports) {
        try {
            $s = Invoke-RestMethod "http://localhost:$port/api/cluster/status" -TimeoutSec 3
            if ($s.role -eq "LEADER") { return $port }
        } catch {}
    }
    return $null
}

function Wait-ForCluster($ports, $waitSec) {
    Write-Host "  Waiting ${waitSec}s for leader election..." -ForegroundColor Yellow
    Start-Sleep -Seconds $waitSec

    # Verify all nodes are up
    $healthy = 0
    foreach ($port in $ports) {
        try {
            $s = Invoke-RestMethod "http://localhost:$port/api/cluster/status" -TimeoutSec 5
            Write-Host "    $($s.nodeId): $($s.role) | term=$($s.term)" -ForegroundColor Green
            $healthy++
        } catch {
            Write-Host "    port $port : DOWN" -ForegroundColor Red
        }
    }

    $leader = Find-Leader $ports
    if (-not $leader) {
        Write-Host "  ERROR: No leader found! Retrying in 10s..." -ForegroundColor Red
        Start-Sleep -Seconds 10
        $leader = Find-Leader $ports
    }

    return @{ Healthy = $healthy; LeaderPort = $leader }
}

function Run-Benchmark($leaderPort, $numOps, $concurrency, $valueSize) {
    $body = "{`"numOps`":$numOps,`"concurrency`":$concurrency,`"valueSizeBytes`":$valueSize}"
    try {
        $r = Invoke-RestMethod -Method POST -Uri "http://localhost:$leaderPort/api/benchmark/run" `
            -ContentType "application/json" -Body $body -TimeoutSec 180
        return $r
    } catch {
        Write-Host "  Benchmark failed: $($_.Exception.Message)" -ForegroundColor Red
        return $null
    }
}

function Run-LatencyBreakdown($leaderPort) {
    try {
        $r = Invoke-RestMethod -Method POST -Uri "http://localhost:$leaderPort/api/benchmark/latency-breakdown" `
            -ContentType "application/json" -Body '{"iterations":500}' -TimeoutSec 60
        return $r
    } catch {
        Write-Host "  Breakdown failed: $($_.Exception.Message)" -ForegroundColor Red
        return $null
    }
}

function Print-BenchmarkResult($label, $result) {
    if (-not $result) { Write-Host "  $label : NO DATA" -ForegroundColor Red; return }

    $std = $result.standard
    $spec = $result.speculative

    Write-Host ""
    Write-Host "  +------ $label ------+" -ForegroundColor White

    # Standard
    Write-Host "  STANDARD RAFT (batching=0.5ms):" -ForegroundColor Magenta
    Write-Host ("    p50={0:F2}ms  p95={1:F2}ms  p99={2:F2}ms  avg={3:F2}ms  thr={4:F0} ops/s" -f `
        $std.writeLatency.p50Ms, $std.writeLatency.p95Ms, $std.writeLatency.p99Ms, `
        $std.writeLatency.avgMs, $std.throughputOpsPerSec) -ForegroundColor White

    # Speculative
    Write-Host "  SPECULATIVE MVCC:" -ForegroundColor Green
    Write-Host ("    p50={0:F2}ms  p95={1:F2}ms  p99={2:F2}ms  avg={3:F2}ms  thr={4:F0} ops/s" -f `
        $spec.writeLatency.p50Ms, $spec.writeLatency.p95Ms, $spec.writeLatency.p99Ms, `
        $spec.writeLatency.avgMs, $spec.throughputOpsPerSec) -ForegroundColor White

    # Improvements
    if ($std.writeLatency.p50Ms -gt 0) {
        $p50i = [math]::Round((1 - $spec.writeLatency.p50Ms / $std.writeLatency.p50Ms) * 100, 1)
        $p95i = [math]::Round((1 - $spec.writeLatency.p95Ms / $std.writeLatency.p95Ms) * 100, 1)
        $thri = [math]::Round(($spec.throughputOpsPerSec / $std.throughputOpsPerSec - 1) * 100, 1)
        Write-Host "  IMPROVEMENT:" -ForegroundColor Yellow
        Write-Host "    p50: ${p50i}% reduction | p95: ${p95i}% reduction | Throughput: +${thri}%" -ForegroundColor Yellow
    }

    # Promotion
    if ($spec.promotionSuccessRate) {
        Write-Host "  PROMOTION:" -ForegroundColor DarkYellow
        Write-Host ("    p50={0:F1}ms  success={1:F1}%  failures={2}" -f `
            $spec.promotionLatency.p50Ms, `
            ($spec.promotionSuccessRate * 100), `
            $spec.promotionFailCount) -ForegroundColor White
    }
}

###############################################################################
# RESULTS STORAGE
###############################################################################
$allResults = @{}

###############################################################################
# PHASE 1: 3-NODE CLUSTER
###############################################################################
Write-Section "PHASE 1: 3-NODE CLUSTER BENCHMARK"

Write-Host "`n  Stopping any running cluster..." -ForegroundColor Yellow
Push-Location $ROOT
docker compose down 2>$null
docker compose -f docker-compose-5node.yml down 2>$null
docker compose -f docker-compose-7node.yml down 2>$null

Write-Host "  Starting 3-node cluster..." -ForegroundColor Yellow
docker compose up --build -d 2>$null

$ports3 = @(8081, 8082, 8083)
$cluster3 = Wait-ForCluster $ports3 $WaitSeconds

if ($cluster3.LeaderPort) {
    Write-Host "`n  Running benchmark on leader (port $($cluster3.LeaderPort))..." -ForegroundColor Yellow
    $allResults["3-node"] = Run-Benchmark $cluster3.LeaderPort $NumOps $Concurrency $ValueSize
    Print-BenchmarkResult "3-NODE CLUSTER" $allResults["3-node"]

    Write-Host "`n  Running latency breakdown..." -ForegroundColor Yellow
    $allResults["3-node-breakdown"] = Run-LatencyBreakdown $cluster3.LeaderPort
} else {
    Write-Host "  SKIPPED: No leader available for 3-node cluster" -ForegroundColor Red
}

###############################################################################
# PHASE 2: 5-NODE CLUSTER
###############################################################################
Write-Section "PHASE 2: 5-NODE CLUSTER BENCHMARK"

Write-Host "`n  Stopping 3-node cluster..." -ForegroundColor Yellow
docker compose down 2>$null

Write-Host "  Starting 5-node cluster..." -ForegroundColor Yellow
docker compose -f docker-compose-5node.yml up --build -d 2>$null

$ports5 = @(8081, 8082, 8083, 8084, 8085)
$cluster5 = Wait-ForCluster $ports5 $WaitSeconds

if ($cluster5.LeaderPort) {
    Write-Host "`n  Running benchmark on leader (port $($cluster5.LeaderPort))..." -ForegroundColor Yellow
    $allResults["5-node"] = Run-Benchmark $cluster5.LeaderPort $NumOps $Concurrency $ValueSize
    Print-BenchmarkResult "5-NODE CLUSTER" $allResults["5-node"]

    Write-Host "`n  Running latency breakdown..." -ForegroundColor Yellow
    $allResults["5-node-breakdown"] = Run-LatencyBreakdown $cluster5.LeaderPort
} else {
    Write-Host "  SKIPPED: No leader available for 5-node cluster" -ForegroundColor Red
}

###############################################################################
# PHASE 3: 7-NODE CLUSTER
###############################################################################
Write-Section "PHASE 3: 7-NODE CLUSTER BENCHMARK"

Write-Host "`n  Stopping 5-node cluster..." -ForegroundColor Yellow
docker compose -f docker-compose-5node.yml down 2>$null

Write-Host "  Starting 7-node cluster..." -ForegroundColor Yellow
docker compose -f docker-compose-7node.yml up --build -d 2>$null

$ports7 = @(8081, 8082, 8083, 8084, 8085, 8086, 8087)
$cluster7 = Wait-ForCluster $ports7 $WaitSeconds

if ($cluster7.LeaderPort) {
    Write-Host "`n  Running benchmark on leader (port $($cluster7.LeaderPort))..." -ForegroundColor Yellow
    $allResults["7-node"] = Run-Benchmark $cluster7.LeaderPort $NumOps $Concurrency $ValueSize
    Print-BenchmarkResult "7-NODE CLUSTER" $allResults["7-node"]

    Write-Host "`n  Running latency breakdown..." -ForegroundColor Yellow
    $allResults["7-node-breakdown"] = Run-LatencyBreakdown $cluster7.LeaderPort
} else {
    Write-Host "  SKIPPED: No leader available for 7-node cluster" -ForegroundColor Red
}

###############################################################################
# PHASE 4: COMPARATIVE SUMMARY TABLE
###############################################################################
Write-Section "SCALABILITY SUMMARY (Paper Table: Node Scaling)"

Write-Host ""
Write-Host "  +--------+------+------+------+------+------+------+------+------+---------+---------+" -ForegroundColor White
Write-Host "  | Nodes  | Std  | Std  | Std  | Std  | Spec | Spec | Spec | Spec | p50     | Thr     |" -ForegroundColor White
Write-Host "  |        | p50  | p95  | p99  | thr  | p50  | p95  | p99  | thr  | Reduc.  | Gain    |" -ForegroundColor White
Write-Host "  +--------+------+------+------+------+------+------+------+------+---------+---------+" -ForegroundColor White

foreach ($nodes in @("3-node", "5-node", "7-node")) {
    $r = $allResults[$nodes]
    if (-not $r) { continue }

    $std = $r.standard
    $spec = $r.speculative
    $n = $nodes -replace "-node", ""

    $p50r = if ($std.writeLatency.p50Ms -gt 0) { [math]::Round((1 - $spec.writeLatency.p50Ms / $std.writeLatency.p50Ms) * 100, 0) } else { 0 }
    $thrg = if ($std.throughputOpsPerSec -gt 0) { [math]::Round(($spec.throughputOpsPerSec / $std.throughputOpsPerSec - 1) * 100, 0) } else { 0 }

    Write-Host ("  | {0,-6} | {1,4:F1} | {2,4:F1} | {3,4:F1} | {4,4:F0} | {5,4:F1} | {6,4:F1} | {7,4:F1} | {8,4:F0} | {9,5}%  | +{10,4}%  |" -f `
        $nodes, `
        $std.writeLatency.p50Ms, $std.writeLatency.p95Ms, $std.writeLatency.p99Ms, $std.throughputOpsPerSec, `
        $spec.writeLatency.p50Ms, $spec.writeLatency.p95Ms, $spec.writeLatency.p99Ms, $spec.throughputOpsPerSec, `
        $p50r, $thrg) -ForegroundColor Green
}
Write-Host "  +--------+------+------+------+------+------+------+------+------+---------+---------+" -ForegroundColor White

# Promotion success rates
Write-Host ""
Write-Host "  Promotion Success Rates:" -ForegroundColor Yellow
foreach ($nodes in @("3-node", "5-node", "7-node")) {
    $r = $allResults[$nodes]
    if (-not $r) { continue }
    $spec = $r.speculative
    $rate = if ($spec.promotionSuccessRate) { [math]::Round($spec.promotionSuccessRate * 100, 1) } else { "N/A" }
    $promoP50 = if ($spec.promotionLatency) { [math]::Round($spec.promotionLatency.p50Ms, 1) } else { "N/A" }
    Write-Host "    $nodes : ${rate}% success | promotion p50=${promoP50}ms | failures=$($spec.promotionFailCount)" -ForegroundColor White
}

###############################################################################
# PHASE 5: LATENCY BREAKDOWN COMPARISON
###############################################################################
Write-Section "LATENCY BREAKDOWN BY CLUSTER SIZE (Paper Figure)"

Write-Host ""
Write-Host "  Phase            | 3-node p50  | 5-node p50  | 7-node p50  |" -ForegroundColor White
Write-Host "  -----------------+-------------+-------------+-------------+" -ForegroundColor White

$phases = @("logAppend", "mvccWrite", "callbackSetup", "totalWritePath")
$labels = @("Log Append      ", "MVCC Write      ", "Callback+Trigger", "TOTAL WRITE PATH")

for ($i = 0; $i -lt $phases.Count; $i++) {
    $phase = $phases[$i]
    $label = $labels[$i]
    $vals = @()
    foreach ($nodes in @("3-node", "5-node", "7-node")) {
        $bd = $allResults["$nodes-breakdown"]
        if ($bd -and $bd.$phase) {
            $vals += ("{0,8:N1}us" -f $bd.$phase.p50Us)
        } else {
            $vals += "       N/A  "
        }
    }
    $color = if ($phase -eq "totalWritePath") { "Yellow" } else { "Green" }
    Write-Host ("  $label | {0} | {1} | {2} |" -f $vals[0], $vals[1], $vals[2]) -ForegroundColor $color
}

# Promotion row (ms)
$promoVals = @()
foreach ($nodes in @("3-node", "5-node", "7-node")) {
    $bd = $allResults["$nodes-breakdown"]
    if ($bd -and $bd.promotionLatency) {
        $promoVals += ("{0,8:N1}ms" -f $bd.promotionLatency.p50Ms)
    } else {
        $promoVals += "       N/A  "
    }
}
Write-Host ("  Promotion (bg)  | {0} | {1} | {2} |" -f $promoVals[0], $promoVals[1], $promoVals[2]) -ForegroundColor DarkYellow

###############################################################################
# CLEANUP: Restore 3-node cluster
###############################################################################
Write-Section "CLEANUP"

Write-Host "  Stopping current cluster..." -ForegroundColor Yellow
docker compose -f docker-compose-7node.yml down 2>$null
docker compose -f docker-compose-5node.yml down 2>$null

Write-Host "  Restoring 3-node cluster..." -ForegroundColor Yellow
docker compose up -d 2>$null

Pop-Location

Write-Host ""
Write-Host "=" * 78 -ForegroundColor Cyan
Write-Host "  MULTI-NODE BENCHMARK COMPLETE" -ForegroundColor Cyan
Write-Host "  Results collected for 3, 5, and 7 nodes" -ForegroundColor Cyan
Write-Host "=" * 78 -ForegroundColor Cyan
