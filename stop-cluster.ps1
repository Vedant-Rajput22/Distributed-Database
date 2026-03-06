<#
.SYNOPSIS
    Stop all Mini Distributed Database cluster processes.

.DESCRIPTION
    Reads saved PIDs from .cluster-pids and gracefully stops all
    backend nodes and the frontend dev server. Optionally cleans up
    data and log directories.

.PARAMETER Clean
    Also delete logs/ and data/ directories after stopping.

.EXAMPLE
    .\stop-cluster.ps1
    .\stop-cluster.ps1 -Clean
#>

param(
    [switch]$Clean
)

$ROOT = $PSScriptRoot
$PIDFILE = Join-Path $ROOT ".cluster-pids"

function Write-Step($msg) { Write-Host "`n>> $msg" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "   $msg" -ForegroundColor Green }
function Write-Info($msg)  { Write-Host "   $msg" -ForegroundColor Yellow }

Write-Host ""
Write-Host "  +==========================================+" -ForegroundColor Magenta
Write-Host "  |    Mini Distributed Database - Stopper   |" -ForegroundColor Magenta
Write-Host "  +==========================================+" -ForegroundColor Magenta

# --- Stop processes from PID file ---
if (Test-Path $PIDFILE) {
    Write-Step "Stopping cluster processes..."
    $pids = Get-Content $PIDFILE | Where-Object { $_.Trim() -ne "" }

    foreach ($procId in $pids) {
        try {
            $proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
            if ($proc) {
                Stop-Process -Id $procId -Force
                Write-Ok "Stopped PID $procId ($($proc.ProcessName))"
            } else {
                Write-Info "PID $procId already stopped."
            }
        } catch {
            Write-Info "PID $procId not found (already exited)."
        }
    }

    Remove-Item $PIDFILE -Force
    Write-Ok "PID file cleaned up."
} else {
    Write-Info "No .cluster-pids file found."
}

# --- Fallback: kill any java processes on cluster ports ---
Write-Step "Checking for processes on cluster ports..."
$clusterPorts = @(8080, 8082, 8084, 8086, 8088, 9090, 9092, 9094, 9096, 9098)
foreach ($port in $clusterPorts) {
    $conn = Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($conn -and $conn.OwningProcess -gt 0) {
        try {
            Stop-Process -Id $conn.OwningProcess -Force -ErrorAction SilentlyContinue
            Write-Ok "Stopped process on port $port (PID $($conn.OwningProcess))"
        } catch { }
    }
}

# --- Also kill any node/npm dev server on port 3000 ---
Write-Step "Checking for frontend dev server..."
$frontendPid = (Get-NetTCPConnection -LocalPort 3000 -ErrorAction SilentlyContinue |
    Select-Object -First 1).OwningProcess
if ($frontendPid) {
    Stop-Process -Id $frontendPid -Force -ErrorAction SilentlyContinue
    Write-Ok "Stopped frontend dev server (PID $frontendPid)."
} else {
    Write-Info "No frontend dev server running on port 3000."
}

# Allow OS to release file handles
Start-Sleep -Seconds 2

# --- Clean up ---
if ($Clean) {
    Write-Step "Cleaning up data and logs..."
    $logsDir = Join-Path $ROOT "logs"
    $dataDir = Join-Path $ROOT "data"
    $backendDataDir = Join-Path $ROOT "backend\data"

    foreach ($dir in @($logsDir, $dataDir, $backendDataDir)) {
        if (Test-Path $dir) {
            # Retry removal in case handles take a moment to release
            for ($attempt = 1; $attempt -le 3; $attempt++) {
                try {
                    Remove-Item $dir -Recurse -Force -ErrorAction Stop
                    Write-Ok "Deleted $dir"
                    break
                } catch {
                    if ($attempt -lt 3) {
                        Start-Sleep -Seconds 2
                    } else {
                        Write-Info "Could not fully delete $dir (some files may be locked). Continuing..."
                    }
                }
            }
        }
    }
}

Write-Host ""
Write-Host "  +------------------------------------------+" -ForegroundColor Green
Write-Host "  |           Cluster stopped.               |" -ForegroundColor Green
Write-Host "  +------------------------------------------+" -ForegroundColor Green
Write-Host ""
