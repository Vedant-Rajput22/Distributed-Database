<#
.SYNOPSIS
    Run the full benchmark suite on the cloud-deployed MiniDB cluster.

.DESCRIPTION
    Reads cluster-info.json (created by deploy-azure.ps1), finds the leader,
    and runs: scalability benchmarks, failure injection tests, and latency breakdown.
    Results are saved to deploy/cloud-results.json.

.PARAMETER NumOps
    Number of operations per benchmark run. Default: 1000.

.PARAMETER Concurrency
    Number of concurrent threads. Default: 10.

.PARAMETER Runs
    Number of benchmark runs to average. Default: 3.

.EXAMPLE
    .\deploy\cloud-benchmark.ps1
    .\deploy\cloud-benchmark.ps1 -NumOps 2000 -Runs 5
#>

param(
    [int]$NumOps = 1000,
    [int]$Concurrency = 10,
    [int]$ValueSize = 256,
    [int]$Runs = 3,
    [int]$WaitBetweenRuns = 15
)

$ErrorActionPreference = "Stop"

function Log {
    param([string]$Msg, [string]$Color = "White")
    Write-Host $Msg -ForegroundColor $Color
}

# ── Load cluster info ──
$infoPath = "deploy/cluster-info.json"
if (-not (Test-Path $infoPath)) {
    Log "ERROR: $infoPath not found. Run deploy-azure.ps1 first." Red
    exit 1
}

$cluster = Get-Content $infoPath | ConvertFrom-Json
$Nodes = $cluster.nodes
$publicIps = $cluster.publicIps
Log ""
Log "=============================================" Cyan
Log "  MiniDB Cloud Benchmark Suite" Cyan
Log "=============================================" Cyan
Log ""
Log "  Cluster: $Nodes nodes ($($cluster.vmSize) in $($cluster.location))"
Log "  Ops/run: $NumOps | Concurrency: $Concurrency | Value: ${ValueSize}B"
Log "  Runs: $Runs (results averaged)"
Log ""

# ── Find Leader ──
Log "[1/5] Finding leader..." Yellow

$leaderIp = $null
$leaderNode = $null

for ($i = 0; $i -lt $publicIps.Count; $i++) {
    $ip = $publicIps[$i]
    try {
        $status = Invoke-RestMethod -Uri "http://${ip}:8080/api/cluster/status" -TimeoutSec 10
        if ($status.role -eq "LEADER") {
            $leaderIp = $ip
            $leaderNode = $i + 1
            Log "  Leader: node-$leaderNode ($leaderIp)" Green
            break
        }
    } catch {
        Log "  node-$($i+1) ($ip): not responding" Gray
    }
}

if (-not $leaderIp) {
    Log "  ERROR: No leader found. Wait 30s and retry, or check cluster health." Red
    exit 1
}

# Show all node status
for ($i = 0; $i -lt $publicIps.Count; $i++) {
    $ip = $publicIps[$i]
    try {
        $status = Invoke-RestMethod -Uri "http://${ip}:8080/api/cluster/status" -TimeoutSec 5
        Log "  node-$($i+1): $($status.role) (term $($status.term))" $(if ($status.role -eq "LEADER") { "Green" } else { "Gray" })
    } catch {
        Log "  node-$($i+1): offline" Red
    }
}

# ── Benchmark Runs ──
Log ""
Log "[2/5] Running $Runs benchmark iterations..." Yellow

$benchBody = @{
    mode = "BOTH"
    numOps = $NumOps
    concurrency = $Concurrency
    valueSizeBytes = $ValueSize
} | ConvertTo-Json

$allResults = @()

for ($run = 1; $run -le $Runs; $run++) {
    Log ""
    Log "  --- Run $run/$Runs ---" Cyan

    try {
        $result = Invoke-RestMethod -Method Post `
            -Uri "http://${leaderIp}:8080/api/benchmark/run" `
            -Body $benchBody `
            -ContentType "application/json" `
            -TimeoutSec 300

        $allResults += $result

        # Display run results
        if ($result.standard) {
            $s = $result.standard
            Log "  Standard:    p50=$($s.p50Latency)ms  p95=$($s.p95Latency)ms  throughput=$($s.throughput) ops/s" White
        }
        if ($result.speculative) {
            $sp = $result.speculative
            Log "  Speculative: p50=$($sp.p50Latency)ms  p95=$($sp.p95Latency)ms  throughput=$($sp.throughput) ops/s" Green
        }

    } catch {
        Log "  ERROR: Benchmark failed - $_" Red

        # Re-find leader in case of failover
        Log "  Trying to find new leader..."
        for ($i = 0; $i -lt $publicIps.Count; $i++) {
            try {
                $status = Invoke-RestMethod -Uri "http://$($publicIps[$i]):8080/api/cluster/status" -TimeoutSec 5
                if ($status.role -eq "LEADER") {
                    $leaderIp = $publicIps[$i]
                    $leaderNode = $i + 1
                    Log "  New leader: node-$leaderNode ($leaderIp)" Green
                    break
                }
            } catch { }
        }
    }

    if ($run -lt $Runs) {
        Log "  Cooling down ${WaitBetweenRuns}s..." Gray
        Start-Sleep -Seconds $WaitBetweenRuns
    }
}

# ── Compute Averages ──
Log ""
Log "[3/5] Computing averages across $Runs runs..." Yellow

$stdResults = $allResults | Where-Object { $_.standard } | ForEach-Object { $_.standard }
$specResults = $allResults | Where-Object { $_.speculative } | ForEach-Object { $_.speculative }

function Average-Metric {
    param($results, $metric)
    if (-not $results -or $results.Count -eq 0) { return "N/A" }
    $vals = $results | ForEach-Object { $_.$metric } | Where-Object { $_ -ne $null }
    if ($vals.Count -eq 0) { return "N/A" }
    return [math]::Round(($vals | Measure-Object -Average).Average, 2)
}

$summary = @{
    cluster = @{
        nodes = $Nodes
        vmSize = $cluster.vmSize
        location = $cluster.location
        infrastructure = "Azure VMs (separate hosts, real network)"
    }
    config = @{
        numOps = $NumOps
        concurrency = $Concurrency
        valueSize = $ValueSize
        runs = $Runs
    }
    standard = @{
        p50 = Average-Metric $stdResults "p50Latency"
        p95 = Average-Metric $stdResults "p95Latency"
        p99 = Average-Metric $stdResults "p99Latency"
        avg = Average-Metric $stdResults "avgLatency"
        throughput = Average-Metric $stdResults "throughput"
    }
    speculative = @{
        p50 = Average-Metric $specResults "p50Latency"
        p95 = Average-Metric $specResults "p95Latency"
        p99 = Average-Metric $specResults "p99Latency"
        avg = Average-Metric $specResults "avgLatency"
        throughput = Average-Metric $specResults "throughput"
        promotionP50 = Average-Metric $specResults "promotionP50"
        promotionP95 = Average-Metric $specResults "promotionP95"
        successRate = Average-Metric $specResults "successRate"
    }
}

# Display summary table
Log ""
Log "  ========== CLOUD BENCHMARK RESULTS ($Nodes-node) ==========" Cyan
Log ""
Log "  Infrastructure: Azure $($cluster.vmSize), $($cluster.location), separate VMs" Gray
Log "  Config: $NumOps ops x $Concurrency threads x ${ValueSize}B values x $Runs runs" Gray
Log ""
Log "  Metric           Standard Raft    Speculative MVCC    Improvement" Cyan
Log "  ────────────     ─────────────    ────────────────    ───────────" Gray

$p50Std = $summary.standard.p50
$p50Spec = $summary.speculative.p50
$p50Imp = if ($p50Std -ne "N/A" -and $p50Spec -ne "N/A" -and $p50Std -gt 0) {
    "$([math]::Round((1 - $p50Spec/$p50Std) * 100, 1))%"
} else { "N/A" }

$p95Std = $summary.standard.p95
$p95Spec = $summary.speculative.p95
$p95Imp = if ($p95Std -ne "N/A" -and $p95Spec -ne "N/A" -and $p95Std -gt 0) {
    "$([math]::Round((1 - $p95Spec/$p95Std) * 100, 1))%"
} else { "N/A" }

$tpStd = $summary.standard.throughput
$tpSpec = $summary.speculative.throughput
$tpImp = if ($tpStd -ne "N/A" -and $tpSpec -ne "N/A" -and $tpStd -gt 0) {
    "+$([math]::Round(($tpSpec/$tpStd - 1) * 100, 1))%"
} else { "N/A" }

Log ("  p50 Latency      {0,8}ms         {1,8}ms          {2}" -f $p50Std, $p50Spec, $p50Imp)
Log ("  p95 Latency      {0,8}ms         {1,8}ms          {2}" -f $p95Std, $p95Spec, $p95Imp)
Log ("  p99 Latency      {0,8}ms         {1,8}ms          " -f $summary.standard.p99, $summary.speculative.p99)
Log ("  Avg Latency      {0,8}ms         {1,8}ms          " -f $summary.standard.avg, $summary.speculative.avg)
Log ("  Throughput       {0,8} ops/s     {1,8} ops/s      {2}" -f $tpStd, $tpSpec, $tpImp)
Log ""
if ($summary.speculative.successRate -ne "N/A") {
    Log ("  Promotion Success Rate: {0}%" -f $summary.speculative.successRate) Green
}
if ($summary.speculative.promotionP50 -ne "N/A") {
    Log ("  Promotion Latency:      p50={0}ms  p95={1}ms" -f $summary.speculative.promotionP50, $summary.speculative.promotionP95)
}

# ── Failure Benchmarks ──
Log ""
Log "[4/5] Running failure injection benchmarks..." Yellow

$failureResults = @{}

# Correctness test
Log "  Running correctness smoking gun (200 versions)..." Yellow
try {
    $correctness = Invoke-RestMethod -Method Post `
        -Uri "http://${leaderIp}:8080/api/failure-benchmark/correctness" `
        -Body '{"numCommitted": 100, "numUncommitted": 200}' `
        -ContentType "application/json" `
        -TimeoutSec 120

    $failureResults.correctness = $correctness

    if ($correctness.overallResult -eq "PASS") {
        Log "  Correctness: PASS" Green
        Log "    Committed preserved: $($correctness.committedReadable)/$($correctness.numCommitted)" Green
        Log "    Uncommitted rolled back: visible=$($correctness.uncommittedVisible)/$($correctness.numUncommitted)" Green
    } else {
        Log "  Correctness: FAIL" Red
        Log "    $($correctness | ConvertTo-Json -Depth 3)"
    }
} catch {
    Log "  Correctness benchmark failed: $_" Red
}

# Wait for re-election after leader kill
Log "  Waiting 30s for cluster recovery..." Gray
Start-Sleep -Seconds 30

# Re-find leader
for ($i = 0; $i -lt $publicIps.Count; $i++) {
    try {
        $status = Invoke-RestMethod -Uri "http://$($publicIps[$i]):8080/api/cluster/status" -TimeoutSec 5
        if ($status.role -eq "LEADER") {
            $leaderIp = $publicIps[$i]
            $leaderNode = $i + 1
            Log "  New leader after recovery: node-$leaderNode" Green
            break
        }
    } catch { }
}

# Recover all nodes
try {
    Invoke-RestMethod -Method Post -Uri "http://${leaderIp}:8080/api/chaos/recover-all" -TimeoutSec 10 | Out-Null
    Start-Sleep -Seconds 10
} catch { }

# Leader crash under load
Log "  Running leader crash benchmark (200 ops, 50ms delay)..." Yellow
try {
    $crash = Invoke-RestMethod -Method Post `
        -Uri "http://${leaderIp}:8080/api/failure-benchmark/leader-crash" `
        -Body '{"numOps": 200, "crashDelayMs": 50, "concurrency": 10}' `
        -ContentType "application/json" `
        -TimeoutSec 120

    $failureResults.leaderCrash = $crash
    Log "  Leader crash results:" Cyan
    Log "    Writes before crash: $($crash.writesBeforeCrash)" White
    Log "    Committed: $($crash.committedCount) | Rolled back: $($crash.rolledBackCount)" White
    if ($crash.cascadeRollbackUs) {
        Log "    cascadeRollback: $($crash.cascadeRollbackUs)us ($($crash.cascadeRollbackVersions) versions)" White
    }
    if ($crash.recoveryThroughput) {
        Log "    Recovery throughput: $($crash.recoveryThroughput) ops/s" Green
    }
} catch {
    Log "  Leader crash benchmark failed: $_" Red
}

# ── Save Results ──
Log ""
Log "[5/5] Saving results..." Yellow

$fullResults = @{
    timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    cluster = $summary.cluster
    config = $summary.config
    benchmarkSummary = @{
        standard = $summary.standard
        speculative = $summary.speculative
    }
    failureBenchmarks = $failureResults
    rawRuns = $allResults
}

$resultsPath = "deploy/cloud-results-$Nodes-node.json"
$fullResults | ConvertTo-Json -Depth 10 | Set-Content -Path $resultsPath -Encoding utf8
Log "  Results saved to $resultsPath" Green

Log ""
Log "=============================================" Green
Log "  BENCHMARKS COMPLETE!" Green
Log "=============================================" Green
Log ""
Log "Key results for paper (Table 3.2):" Yellow
Log "  $Nodes-node cluster on Azure $($cluster.vmSize) (separate VMs)" Gray
Log "  Standard Raft: p50=$($summary.standard.p50)ms, throughput=$($summary.standard.throughput) ops/s" White
Log "  Speculative:   p50=$($summary.speculative.p50)ms, throughput=$($summary.speculative.throughput) ops/s" Green
Log "  Improvement:   p50 $p50Imp reduction, throughput $tpImp gain" Cyan
Log ""
Log "Don't forget to run cleanup when done:" Red
Log "  .\deploy\cleanup-azure.ps1" Red
Log ""
