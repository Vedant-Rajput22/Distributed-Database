<#
.SYNOPSIS
    Start the Mini Distributed Database cluster (3 nodes + React dashboard).

.DESCRIPTION
    Builds the backend (if needed), starts 3 Raft nodes as background processes,
    installs frontend dependencies (if needed), and launches the Vite dev server.
    All logs are written to the logs/ directory.

.PARAMETER Nodes
    Number of nodes to start (default: 3, max: 5).

.PARAMETER SkipBuild
    Skip the Maven build step if the JAR already exists.

.PARAMETER SkipFrontend
    Skip starting the frontend dashboard.

.EXAMPLE
    .\start-cluster.ps1
    .\start-cluster.ps1 -Nodes 5
    .\start-cluster.ps1 -SkipBuild
#>

param(
    [int]$Nodes = 3,
    [switch]$SkipBuild,
    [switch]$SkipFrontend
)

$ErrorActionPreference = "Stop"
$ROOT = $PSScriptRoot
$BACKEND = Join-Path $ROOT "backend"
$FRONTEND = Join-Path $ROOT "frontend"
$JAR = Join-Path $BACKEND "target\mini-distributed-db-1.0.0-SNAPSHOT-boot.jar"
$LOGS = Join-Path $ROOT "logs"
$PIDFILE = Join-Path $ROOT ".cluster-pids"

# --- Colors ---
function Write-Step($msg) { Write-Host "`n>> $msg" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "   $msg" -ForegroundColor Green }
function Write-Info($msg)  { Write-Host "   $msg" -ForegroundColor Yellow }

# --- Banner ---
Write-Host ""
Write-Host "  +==========================================+" -ForegroundColor Magenta
Write-Host "  |   Mini Distributed Database - Launcher   |" -ForegroundColor Magenta
Write-Host "  +==========================================+" -ForegroundColor Magenta
Write-Host ""

if ($Nodes -lt 1 -or $Nodes -gt 5) {
    Write-Host "Error: Nodes must be between 1 and 5." -ForegroundColor Red
    exit 1
}

# --- Port config ---
# Node 1: HTTP 8080, gRPC 9090
# Node 2: HTTP 8082, gRPC 9092
# Node N: HTTP 8080+2*(N-1), gRPC 9090+2*(N-1)
function Get-HttpPort($n)  { return 8080 + 2 * ($n - 1) }
function Get-GrpcPort($n)  { return 9090 + 2 * ($n - 1) }

# --- Build peers string for a given node ---
function Get-Peers($nodeNum, $totalNodes) {
    $peers = @()
    for ($i = 1; $i -le $totalNodes; $i++) {
        if ($i -ne $nodeNum) {
            $grpc = Get-GrpcPort $i
            $peers += "node-$i@localhost:$grpc"
        }
    }
    return $peers -join ","
}

# --- Step 0: Stop any previously running cluster ---
if (Test-Path $PIDFILE) {
    Write-Step "Stopping previously running cluster..."
    $oldPids = Get-Content $PIDFILE | Where-Object { $_.Trim() -ne "" }
    foreach ($procId in $oldPids) {
        try {
            $proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
            if ($proc) {
                Stop-Process -Id $procId -Force
                Write-Ok "Stopped old PID $procId ($($proc.ProcessName))"
            }
        } catch { }
    }
    Remove-Item $PIDFILE -Force -ErrorAction SilentlyContinue
    # Also kill frontend on port 3000
    $fp = (Get-NetTCPConnection -LocalPort 3000 -ErrorAction SilentlyContinue | Select-Object -First 1).OwningProcess
    if ($fp) { Stop-Process -Id $fp -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Seconds 2
}

# Clean up stale RocksDB LOCK files (prevents "file in use" errors after unclean shutdown)
$lockFiles = Get-ChildItem -Path $ROOT -Recurse -Filter "LOCK" -ErrorAction SilentlyContinue | Where-Object { $_.DirectoryName -match "rocksdb" }
if ($lockFiles) {
    Write-Step "Cleaning stale RocksDB LOCK files..."
    foreach ($lf in $lockFiles) {
        Remove-Item $lf.FullName -Force -ErrorAction SilentlyContinue
        Write-Ok "Removed $($lf.FullName)"
    }
}

# --- Step 1: Build backend ---
if (-not $SkipBuild) {
    Write-Step "Building backend (Maven)..."
    Push-Location $BACKEND
    & mvn package -DskipTests -q
    if ($LASTEXITCODE -ne 0) {
        Write-Host "   Maven build failed!" -ForegroundColor Red
        Pop-Location
        exit 1
    }
    Pop-Location
    Write-Ok "Backend built successfully."
} else {
    if (-not (Test-Path $JAR)) {
        Write-Host "   JAR not found at $JAR. Run without -SkipBuild first." -ForegroundColor Red
        exit 1
    }
    Write-Info "Skipping build (-SkipBuild). Using existing JAR."
}

# --- Step 2: Create logs directory ---
if (-not (Test-Path $LOGS)) {
    New-Item -ItemType Directory -Path $LOGS | Out-Null
}

# --- Step 3: Start backend nodes ---
Write-Step "Starting $Nodes Raft nodes..."

$pids = @()

for ($i = 1; $i -le $Nodes; $i++) {
    $httpPort = Get-HttpPort $i
    $grpcPort = Get-GrpcPort $i
    $nodeId   = "node-$i"
    $peers    = Get-Peers $i $Nodes
    $logFile  = Join-Path $LOGS "$nodeId.log"

    $env:SERVER_PORT      = $httpPort
    $env:GRPC_SERVER_PORT = $grpcPort
    $env:NODE_ID          = $nodeId
    $env:CLUSTER_PEERS    = $peers

    $proc = Start-Process -FilePath "java" `
        -ArgumentList "-jar", $JAR `
        -RedirectStandardOutput $logFile `
        -RedirectStandardError (Join-Path $LOGS "$nodeId-error.log") `
        -PassThru -WindowStyle Hidden

    $pids += $proc.Id
    Write-Ok "$nodeId  HTTP: $httpPort  gRPC: $grpcPort  PID: $($proc.Id)"
}

# Save PIDs for stop script
$pids | Out-File -FilePath $PIDFILE -Encoding ascii
Write-Info "PIDs saved to .cluster-pids"

# --- Step 4: Start frontend ---
if (-not $SkipFrontend) {
    Write-Step "Starting React dashboard..."

    Push-Location $FRONTEND
    if (-not (Test-Path "node_modules")) {
        Write-Info "Installing frontend dependencies (npm install)..."
        & npm install --silent 2>$null
    }

    $frontendLog = Join-Path $LOGS "frontend.log"
    $frontendErrorLog = Join-Path $LOGS "frontend-error.log"
    $frontendProc = Start-Process -FilePath "cmd.exe" `
        -ArgumentList "/c", "npm run dev" `
        -WorkingDirectory $FRONTEND `
        -RedirectStandardOutput $frontendLog `
        -RedirectStandardError $frontendErrorLog `
        -PassThru -WindowStyle Hidden

    Pop-Location
    # Append frontend PID
    $frontendProc.Id | Out-File -FilePath $PIDFILE -Append -Encoding ascii
    Write-Ok "Dashboard starting...  PID: $($frontendProc.Id)"
}

# --- Summary ---
Write-Host ""
Write-Host "  +------------------------------------------+" -ForegroundColor Green
Write-Host "  |         Cluster is starting up!          |" -ForegroundColor Green
Write-Host "  +------------------------------------------+" -ForegroundColor Green
Write-Host ""
Write-Info "Wait ~10 seconds for nodes to elect a leader."
Write-Host ""

for ($i = 1; $i -le $Nodes; $i++) {
    $httpPort = Get-HttpPort $i
    Write-Host "   Node $i API:   " -NoNewline; Write-Host "http://localhost:$httpPort" -ForegroundColor Cyan
}
if (-not $SkipFrontend) {
    Write-Host "   Dashboard:    " -NoNewline; Write-Host "http://localhost:3000" -ForegroundColor Cyan
}
Write-Host ""
Write-Host "   Logs:         " -NoNewline; Write-Host "logs/" -ForegroundColor Yellow
Write-Host "   Stop cluster: " -NoNewline; Write-Host ".\stop-cluster.ps1" -ForegroundColor Yellow
Write-Host ""
