<#
.SYNOPSIS
    Failure Injection Benchmark — runs all four failure scenarios and produces paper-ready data.
.DESCRIPTION
    Addresses the reviewer challenge: "Where is the failure benchmark?"
    Runs 4 scenarios:
      1. Leader Crash Under Load — proves O(1) rollback during crash
      2. Network Partition Under Load — proves throughput maintenance during partition
      3. Sustained Throughput During Failures — time-series chart data
      4. Correctness Under Failure — smoking gun test for O(1) rollback correctness
.NOTES
    Requires 3-node cluster running (docker compose up -d).
    Run from project root directory.
#>

param(
    [int]$WaitSeconds = 25,
    [int]$NumOps = 200,
    [int]$Concurrency = 10,
    [int]$ValueSize = 256
)

$ErrorActionPreference = "Continue"

# ========================================
# Section 0: Find the leader
# ========================================
Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "  FAILURE INJECTION BENCHMARK" -ForegroundColor Cyan
Write-Host "  Proving O(1) Rollback Under Real Failures" -ForegroundColor Cyan
Write-Host "========================================`n" -ForegroundColor Cyan

Write-Host "[0/5] Finding cluster leader..." -ForegroundColor Yellow

$LEADER = $null
foreach ($port in 8081, 8082, 8083, 8084, 8085) {
    try {
        $status = Invoke-RestMethod -Uri "http://localhost:$port/api/cluster/status" -TimeoutSec 3 -ErrorAction SilentlyContinue
        if ($status.role -eq "LEADER") {
            $LEADER = "http://localhost:$port"
            Write-Host "  Leader found: $LEADER (node: $($status.nodeId))" -ForegroundColor Green
            break
        }
    } catch { }
}

if (-not $LEADER) {
    Write-Host "  ERROR: No leader found. Is the cluster running?" -ForegroundColor Red
    Write-Host "  Run: docker compose up --build -d" -ForegroundColor Yellow
    exit 1
}

# Wait for cluster stabilization
Write-Host "  Waiting ${WaitSeconds}s for cluster stabilization..." -ForegroundColor Gray
Start-Sleep -Seconds $WaitSeconds

# Re-check leader (may have changed during stabilization)
$LEADER = $null
foreach ($port in 8081, 8082, 8083, 8084, 8085) {
    try {
        $status = Invoke-RestMethod -Uri "http://localhost:$port/api/cluster/status" -TimeoutSec 3 -ErrorAction SilentlyContinue
        if ($status.role -eq "LEADER") {
            $LEADER = "http://localhost:$port"
            break
        }
    } catch { }
}

if (-not $LEADER) {
    Write-Host "  ERROR: Leader lost after stabilization." -ForegroundColor Red
    exit 1
}

Write-Host "  Leader confirmed: $LEADER`n" -ForegroundColor Green

# ========================================
# Section 1: Correctness Under Failure
# ========================================
Write-Host "[1/4] SCENARIO: Correctness Under Failure" -ForegroundColor Yellow
Write-Host "  Writing committed keys, then speculative keys, then crashing..." -ForegroundColor Gray

try {
    $correctnessBody = @{
        committedCount = 50
        uncommittedCount = 50
        valueSizeBytes = $ValueSize
    } | ConvertTo-Json

    $correctness = Invoke-RestMethod -Method Post -Uri "$LEADER/api/benchmark/failure/correctness" `
        -ContentType "application/json" -Body $correctnessBody -TimeoutSec 120

    Write-Host "`n  === Correctness Results ===" -ForegroundColor Cyan

    $phase4 = $correctness.phase4_committedVerification
    $phase5 = $correctness.phase5_rollbackVerification
    $killTime = $correctness.phase3_leaderKill.killTimeUs

    Write-Host "  Committed keys readable after crash: $($phase4.committedKeysReadable)/50" -ForegroundColor ($(if ($phase4.committedKeysMissing -eq 0) { "Green" } else { "Red" }))
    Write-Host "  Uncommitted keys rolled back:        $($phase5.uncommittedRolledBack)/50" -ForegroundColor ($(if ($phase5.uncommittedStillVisible -eq 0) { "Green" } else { "Red" }))
    Write-Host "  Uncommitted still visible (BUG):     $($phase5.uncommittedStillVisible)" -ForegroundColor ($(if ($phase5.uncommittedStillVisible -eq 0) { "Green" } else { "Red" }))
    Write-Host "  Kill time:                           $([math]::Round($killTime, 1)) us" -ForegroundColor White
    Write-Host "  VERDICT: $($correctness.overallVerdict)" -ForegroundColor ($(if ($correctness.overallVerdict -like "PASS*") { "Green" } else { "Red" }))
} catch {
    Write-Host "  ERROR: Correctness benchmark failed: $_" -ForegroundColor Red
}

# Wait for re-election and re-find leader
Write-Host "`n  Waiting 15s for leader re-election..." -ForegroundColor Gray
Start-Sleep -Seconds 15

# Recover all nodes first
foreach ($port in 8081, 8082, 8083) {
    try {
        Invoke-RestMethod -Method Post -Uri "http://localhost:$port/api/chaos/recover-all" -TimeoutSec 5 -ErrorAction SilentlyContinue | Out-Null
    } catch { }
}

Start-Sleep -Seconds 10

$LEADER = $null
foreach ($port in 8081, 8082, 8083, 8084, 8085) {
    try {
        $status = Invoke-RestMethod -Uri "http://localhost:$port/api/cluster/status" -TimeoutSec 3 -ErrorAction SilentlyContinue
        if ($status.role -eq "LEADER") {
            $LEADER = "http://localhost:$port"
            Write-Host "  New leader: $LEADER" -ForegroundColor Green
            break
        }
    } catch { }
}

if (-not $LEADER) {
    Write-Host "  WARNING: No leader found after re-election. Trying to continue..." -ForegroundColor Yellow
    Start-Sleep -Seconds 10
    foreach ($port in 8081, 8082, 8083) {
        try {
            $status = Invoke-RestMethod -Uri "http://localhost:$port/api/cluster/status" -TimeoutSec 3 -ErrorAction SilentlyContinue
            if ($status.role -eq "LEADER") {
                $LEADER = "http://localhost:$port"
                break
            }
        } catch { }
    }
}

# ========================================
# Section 2: Leader Crash Under Load
# ========================================
if ($LEADER) {
    Write-Host "`n[2/4] SCENARIO: Leader Crash Under Load" -ForegroundColor Yellow
    Write-Host "  Issuing $NumOps speculative writes, crashing leader after 100ms..." -ForegroundColor Gray

    try {
        $crashBody = @{
            numOps = $NumOps
            crashDelayMs = 100
            concurrency = $Concurrency
            valueSizeBytes = $ValueSize
        } | ConvertTo-Json

        $crash = Invoke-RestMethod -Method Post -Uri "$LEADER/api/benchmark/failure/leader-crash" `
            -ContentType "application/json" -Body $crashBody -TimeoutSec 120

        Write-Host "`n  === Leader Crash Results ===" -ForegroundColor Cyan

        $crashPhase = $crash.crashPhase
        $commitRes = $crash.commitResolution
        $rollback = $crash.rollbackCost
        $recovery = $crash.recovery

        Write-Host "  Writes issued:          $($crashPhase.writesIssued)" -ForegroundColor White
        Write-Host "  Writes before crash:    $($crashPhase.writesBeforeCrash)" -ForegroundColor White
        Write-Host "  Writes after crash:     $($crashPhase.writesAfterCrash)" -ForegroundColor White
        Write-Host "  Committed:              $($commitRes.committed)" -ForegroundColor Green
        Write-Host "  Rolled back:            $($commitRes.rolledBack)" -ForegroundColor Yellow
        Write-Host "  Rollback percent:       $([math]::Round($commitRes.rollbackPercent, 1))%" -ForegroundColor White
        Write-Host "  Total rollback time:    $([math]::Round($rollback.totalRollbackTimeMs, 2)) ms" -ForegroundColor White
        Write-Host "  Per-version rollback:   $([math]::Round($rollback.perVersionRollbackUs, 1)) us" -ForegroundColor Cyan
        Write-Host "  Rollback mechanism:     $($rollback.mechanism)" -ForegroundColor Gray

        if ($recovery.electionTimeMs) {
            Write-Host "  New leader elected in:  $($recovery.electionTimeMs) ms" -ForegroundColor Green
            Write-Host "  New leader:             $($recovery.newLeader)" -ForegroundColor White
        }

        $state = $crash.stateTransition
        Write-Host "  Term change:            $($state.preTerm) -> $($state.postTerm)" -ForegroundColor Gray
    } catch {
        Write-Host "  ERROR: Leader crash benchmark failed: $_" -ForegroundColor Red
    }
} else {
    Write-Host "`n[2/4] SKIPPED: No leader available" -ForegroundColor Yellow
}

# Re-find leader after crash scenario
Write-Host "`n  Waiting 15s for cluster recovery..." -ForegroundColor Gray

foreach ($port in 8081, 8082, 8083) {
    try {
        Invoke-RestMethod -Method Post -Uri "http://localhost:$port/api/chaos/recover-all" -TimeoutSec 5 -ErrorAction SilentlyContinue | Out-Null
    } catch { }
}

Start-Sleep -Seconds 15

$LEADER = $null
foreach ($port in 8081, 8082, 8083) {
    try {
        $status = Invoke-RestMethod -Uri "http://localhost:$port/api/cluster/status" -TimeoutSec 3 -ErrorAction SilentlyContinue
        if ($status.role -eq "LEADER") {
            $LEADER = "http://localhost:$port"
            Write-Host "  Leader: $LEADER" -ForegroundColor Green
            break
        }
    } catch { }
}

# ========================================
# Section 3: Network Partition Under Load
# ========================================
if ($LEADER) {
    Write-Host "`n[3/4] SCENARIO: Network Partition Under Load" -ForegroundColor Yellow
    Write-Host "  Partitioning followers during writes..." -ForegroundColor Gray

    try {
        $partBody = @{
            numOps = [math]::Min($NumOps, 100)
            concurrency = $Concurrency
            valueSizeBytes = $ValueSize
        } | ConvertTo-Json

        $partition = Invoke-RestMethod -Method Post -Uri "$LEADER/api/benchmark/failure/partition" `
            -ContentType "application/json" -Body $partBody -TimeoutSec 180

        Write-Host "`n  === Network Partition Results ===" -ForegroundColor Cyan

        $summary = $partition.summary

        $baselineThr = if ($summary.baselineThroughput) { [math]::Round($summary.baselineThroughput, 0) } else { "N/A" }
        $partialThr = if ($summary.partialPartitionThroughput) { [math]::Round($summary.partialPartitionThroughput, 0) } else { "N/A" }
        $majorityThr = if ($summary.majorityLostThroughput) { [math]::Round($summary.majorityLostThroughput, 0) } else { "N/A" }
        $recoveredThr = if ($summary.recoveredThroughput) { [math]::Round($summary.recoveredThroughput, 0) } else { "N/A" }

        Write-Host "  Throughput (baseline):            $baselineThr ops/s" -ForegroundColor Green
        Write-Host "  Throughput (1 node partitioned):  $partialThr ops/s" -ForegroundColor Yellow
        Write-Host "  Throughput (majority lost):       $majorityThr ops/s" -ForegroundColor Red
        Write-Host "  Throughput (recovered):           $recoveredThr ops/s" -ForegroundColor Green

        if ($partition.consistencyCheck) {
            Write-Host "  Consistency: $($partition.consistencyCheck.verdict)" -ForegroundColor ($(if ($partition.consistencyCheck.verdict -like "PASS*") { "Green" } else { "Yellow" }))
        }
    } catch {
        Write-Host "  ERROR: Partition benchmark failed: $_" -ForegroundColor Red
    }
} else {
    Write-Host "`n[3/4] SKIPPED: No leader available" -ForegroundColor Yellow
}

# Heal partitions and re-find leader
Write-Host "`n  Healing partitions and recovering..." -ForegroundColor Gray
foreach ($port in 8081, 8082, 8083) {
    try {
        Invoke-RestMethod -Method Post -Uri "http://localhost:$port/api/chaos/recover-all" -TimeoutSec 5 -ErrorAction SilentlyContinue | Out-Null
    } catch { }
}
Start-Sleep -Seconds 10

$LEADER = $null
foreach ($port in 8081, 8082, 8083) {
    try {
        $status = Invoke-RestMethod -Uri "http://localhost:$port/api/cluster/status" -TimeoutSec 3 -ErrorAction SilentlyContinue
        if ($status.role -eq "LEADER") {
            $LEADER = "http://localhost:$port"
            break
        }
    } catch { }
}

# ========================================
# Section 4: Sustained Throughput During Failures
# ========================================
if ($LEADER) {
    Write-Host "`n[4/4] SCENARIO: Sustained Throughput During Failures (30s)" -ForegroundColor Yellow
    Write-Host "  Running continuous writes with periodic partition injection..." -ForegroundColor Gray

    try {
        $sustainedBody = @{
            durationSec = 30
            concurrency = $Concurrency
            failureIntervalSec = 8
            valueSizeBytes = $ValueSize
        } | ConvertTo-Json

        $sustained = Invoke-RestMethod -Method Post -Uri "$LEADER/api/benchmark/failure/sustained-throughput" `
            -ContentType "application/json" -Body $sustainedBody -TimeoutSec 120

        Write-Host "`n  === Sustained Throughput Results ===" -ForegroundColor Cyan

        $sSummary = $sustained.summary
        Write-Host "  Total ops:                   $($sSummary.totalOps)" -ForegroundColor White
        Write-Host "  Success rate:                $([math]::Round($sSummary.overallSuccessRate, 1))%" -ForegroundColor Green
        Write-Host "  Throughput p50:              $($sSummary.throughputP50) ops/s" -ForegroundColor White
        Write-Host "  Throughput min:              $($sSummary.throughputMin) ops/s" -ForegroundColor Yellow
        Write-Host "  Throughput max:              $($sSummary.throughputMax) ops/s" -ForegroundColor White
        Write-Host "  Normal window avg:           $([math]::Round($sSummary.normalWindowAvgOps, 0)) ops/s" -ForegroundColor Green
        Write-Host "  Failure window avg:          $([math]::Round($sSummary.failureWindowAvgOps, 0)) ops/s" -ForegroundColor Yellow

        if ($sSummary.throughputDropDuringFailurePercent) {
            $drop = [math]::Round($sSummary.throughputDropDuringFailurePercent, 1)
            $color = if ($drop -lt 30) { "Green" } elseif ($drop -lt 60) { "Yellow" } else { "Red" }
            Write-Host "  Throughput drop during fail: $drop%" -ForegroundColor $color
        }

        Write-Host "  Failure events injected:     $($sSummary.failureCount)" -ForegroundColor White

        # Print time-series
        if ($sustained.timeSeries) {
            Write-Host "`n  Time-Series (ops/sec per 1-second window):" -ForegroundColor Gray
            Write-Host "  " -NoNewline
            foreach ($w in $sustained.timeSeries) {
                $ops = $w.opsCount
                # Simple bar chart
                $bar = if ($ops -gt 0) { "#" * [math]::Min([math]::Max($ops / 10, 1), 50) } else { "." }
                $isFailureWindow = $false
                foreach ($ev in $sustained.failureEvents) {
                    if ($ev.timeSec -eq $w.windowSec -or ($ev.timeSec + 1) -eq $w.windowSec) {
                        $isFailureWindow = $true
                        break
                    }
                }
                $color = if ($isFailureWindow) { "Red" } else { "Green" }
                Write-Host "  t=$($w.windowSec.ToString().PadLeft(2))s: $($ops.ToString().PadLeft(5)) ops $bar" -ForegroundColor $color
            }
        }
    } catch {
        Write-Host "  ERROR: Sustained throughput benchmark failed: $_" -ForegroundColor Red
    }
} else {
    Write-Host "`n[4/4] SKIPPED: No leader available" -ForegroundColor Yellow
}

# ========================================
# Final: Heal everything
# ========================================
Write-Host "`n  Final cleanup: recovering all nodes..." -ForegroundColor Gray
foreach ($port in 8081, 8082, 8083) {
    try {
        Invoke-RestMethod -Method Post -Uri "http://localhost:$port/api/chaos/recover-all" -TimeoutSec 5 -ErrorAction SilentlyContinue | Out-Null
    } catch { }
}

Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "  FAILURE BENCHMARK COMPLETE" -ForegroundColor Cyan
Write-Host "  All four scenarios executed." -ForegroundColor Cyan
Write-Host "  The key metrics for the paper:" -ForegroundColor Cyan
Write-Host "    - Per-version rollback cost (us)" -ForegroundColor White
Write-Host "    - Committed data preservation (should be 100%)" -ForegroundColor White
Write-Host "    - Throughput drop during failure (%)" -ForegroundColor White
Write-Host "    - Recovery time (election time ms)" -ForegroundColor White
Write-Host "========================================`n" -ForegroundColor Cyan
