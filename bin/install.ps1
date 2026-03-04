#Requires -Version 5.1
<#
.SYNOPSIS
    KCP + Claude Code superpowers installer for Windows

.DESCRIPTION
    Installs on a fresh Windows machine (nothing pre-installed):
      - Azul Zulu JDK 21  (via winget)
      - Node.js LTS        (via winget)
      - Claude Code        (via npm)
      - kcp-commands       (PreToolUse hook — 283 command manifests)
      - kcp-memory         (episodic memory daemon, port 7735)
      - Synthesis MCP      (optional — provide -Synthesis path to JAR)

    Re-running is safe (idempotent).

.EXAMPLE
    # Download and run directly:
    iwr -useb https://raw.githubusercontent.com/Cantara/kcp-commands/main/bin/install.ps1 | iex

    # Or save first, then run:
    powershell -ExecutionPolicy Bypass -File install.ps1

    # With Synthesis MCP server:
    powershell -ExecutionPolicy Bypass -File install.ps1 -Synthesis C:\Users\you\.synthesis\lib\synthesis.jar

.PARAMETER Synthesis
    Optional path to the Synthesis JAR. When provided, registers it as an MCP server.
#>

param(
    [string]$Synthesis = ""
)

$ErrorActionPreference = "Stop"

# ── Constants ──────────────────────────────────────────────────────────────────

$KcpDir          = "$env:USERPROFILE\.kcp"
$SettingsFile    = "$env:USERPROFILE\.claude\settings.json"
$KcpCommandsCli  = "$KcpDir\cli.js"
$KcpMemoryJar    = "$KcpDir\kcp-memory-daemon.jar"
$KcpMemoryHook   = "$KcpDir\memory-hook.ps1"
$KcpMemoryLog    = "$env:TEMP\kcp-memory-daemon.log"

$KcpCommandsDownload = "https://github.com/Cantara/kcp-commands/releases/latest/download/kcp-commands-cli.js"
$KcpMemoryDownload   = "https://github.com/Cantara/kcp-memory/releases/latest/download/kcp-memory-daemon.jar"

# ── Helpers ───────────────────────────────────────────────────────────────────

function Write-Step { param($msg) Write-Host "`n-> $msg" -ForegroundColor Cyan }
function Write-Done { param($msg) Write-Host "   OK  $msg" -ForegroundColor Green }
function Write-Warn { param($msg) Write-Host "   !!  $msg" -ForegroundColor Yellow }
function Write-Fail { param($msg) Write-Host "   !!  $msg" -ForegroundColor Red }

# ── Banner ────────────────────────────────────────────────────────────────────

Write-Host ""
Write-Host "   KCP + Claude Code installer for Windows" -ForegroundColor White
Write-Host "   ========================================" -ForegroundColor White
Write-Host ""

# ── 1. winget check ────────────────────────────────────────────────────────────

Write-Step "Checking prerequisites..."

if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
    Write-Fail "winget not found."
    Write-Host "   Open Microsoft Store, search for 'App Installer', click Update." -ForegroundColor Yellow
    Write-Host "   Then re-run this script." -ForegroundColor Yellow
    exit 1
}
Write-Done "winget available"

# ── 2. Azul Zulu JDK 21 ───────────────────────────────────────────────────────

Write-Step "Java 21 (Azul Zulu)..."

$javaOk = $false
try {
    $javaOut = java -version 2>&1
    if ("$javaOut" -match '"21\.') { $javaOk = $true }
} catch {}

if (-not $javaOk) {
    Write-Host "   Installing Azul Zulu JDK 21 via winget..." -ForegroundColor Gray
    winget install --id Azul.Zulu.21.JDK --exact `
        --accept-source-agreements --accept-package-agreements -e
    Write-Done "Azul Zulu JDK 21 installed"
    # Refresh PATH so java is visible in this session
    $env:PATH = [System.Environment]::GetEnvironmentVariable("PATH","Machine") + ";" +
                [System.Environment]::GetEnvironmentVariable("PATH","User")
} else {
    Write-Done "Java 21 already installed"
}

# ── 3. Node.js LTS ────────────────────────────────────────────────────────────

Write-Step "Node.js LTS..."

$nodeOk = $false
$nodeVer = ""
try {
    $nodeVer = (node --version 2>&1).ToString().Trim()
    if ($nodeVer -match "^v\d") { $nodeOk = $true }
} catch {}

if (-not $nodeOk) {
    Write-Host "   Installing Node.js LTS via winget..." -ForegroundColor Gray
    winget install --id OpenJS.NodeJS.LTS --exact `
        --accept-source-agreements --accept-package-agreements -e
    Write-Done "Node.js LTS installed"
    $env:PATH = [System.Environment]::GetEnvironmentVariable("PATH","Machine") + ";" +
                [System.Environment]::GetEnvironmentVariable("PATH","User")
    $nodeVer = (node --version 2>&1).ToString().Trim()
} else {
    Write-Done "Node.js already installed ($nodeVer)"
}

# ── 4. Claude Code ────────────────────────────────────────────────────────────

Write-Step "Claude Code..."

$ccOk = $false
try {
    $ccVer = (claude --version 2>&1).ToString().Trim()
    if ($ccVer -match "\d") { $ccOk = $true }
} catch {}

if (-not $ccOk) {
    Write-Host "   Installing via npm..." -ForegroundColor Gray
    npm install -g @anthropic-ai/claude-code
    Write-Done "Claude Code installed"
} else {
    Write-Done "Claude Code already installed ($ccVer)"
}

# ── 5. Create ~/.kcp ──────────────────────────────────────────────────────────

Write-Step "Creating $KcpDir..."
New-Item -ItemType Directory -Force -Path $KcpDir | Out-Null
Write-Done "Directory ready"

# ── 6. kcp-commands ───────────────────────────────────────────────────────────

Write-Step "kcp-commands..."
Write-Host "   Downloading CLI from GitHub Releases..." -ForegroundColor Gray
Invoke-WebRequest -Uri $KcpCommandsDownload -OutFile $KcpCommandsCli -UseBasicParsing
Write-Done "CLI downloaded: $KcpCommandsCli"

# ── 7. kcp-memory daemon ──────────────────────────────────────────────────────

Write-Step "kcp-memory daemon..."
Write-Host "   Downloading JAR from GitHub Releases..." -ForegroundColor Gray
Invoke-WebRequest -Uri $KcpMemoryDownload -OutFile $KcpMemoryJar -UseBasicParsing
Write-Done "JAR downloaded: $KcpMemoryJar"

# Check if daemon already running
$daemonRunning = $false
try {
    Invoke-RestMethod -Uri "http://localhost:7735/health" -TimeoutSec 2 | Out-Null
    $daemonRunning = $true
    Write-Done "Daemon already running on port 7735"
} catch {}

if (-not $daemonRunning) {
    Write-Host "   Starting daemon..." -ForegroundColor Gray
    Start-Process -FilePath "java" `
        -ArgumentList @("-jar", "`"$KcpMemoryJar`"", "daemon") `
        -WindowStyle Hidden

    Write-Host "   Waiting for startup..." -ForegroundColor Gray
    for ($i = 0; $i -lt 12; $i++) {
        Start-Sleep -Seconds 1
        try {
            Invoke-RestMethod -Uri "http://localhost:7735/health" -TimeoutSec 1 | Out-Null
            $daemonRunning = $true
            break
        } catch {}
    }

    if ($daemonRunning) {
        Write-Done "Daemon running on port 7735"
    } else {
        Write-Warn "Daemon did not respond within 12s"
        Write-Warn "Log: $KcpMemoryLog"
        Write-Warn "Manual start: java -jar `"$KcpMemoryJar`" daemon"
    }
}

# ── 8. kcp-memory PostToolUse hook script ─────────────────────────────────────

Write-Step "Writing PostToolUse hook script..."

$hookContent = @'
# kcp-memory PostToolUse hook for Windows
# Fire-and-forget POST to kcp-memory daemon. Never blocks Claude Code.
try {
    $r = Invoke-RestMethod -Uri "http://localhost:7735/scan" `
        -Method Post -TimeoutSec 1 -ErrorAction SilentlyContinue
} catch {}
'@

Set-Content $KcpMemoryHook $hookContent -Encoding UTF8
Write-Done "Hook written: $KcpMemoryHook"

# ── 9. Wire settings.json ─────────────────────────────────────────────────────

Write-Step "Configuring Claude Code settings.json..."

$settingsDir = Split-Path $SettingsFile -Parent
New-Item -ItemType Directory -Force -Path $settingsDir | Out-Null
if (-not (Test-Path $SettingsFile)) {
    Set-Content $SettingsFile '{}' -Encoding UTF8
    Write-Host "   Created new settings.json" -ForegroundColor Gray
}

# Use Node.js for JSON manipulation — avoids PowerShell ConvertFrom-Json gotchas
$cliEsc      = $KcpCommandsCli.Replace('\', '\\')
$jarEsc      = $KcpMemoryJar.Replace('\', '\\')
$hookEsc     = $KcpMemoryHook.Replace('\', '\\')
$settingsEsc = $SettingsFile.Replace('\', '\\')

$mcpSynthesisBlock = ""
if ($Synthesis -and (Test-Path $Synthesis)) {
    $synthEsc = $Synthesis.Replace('\', '\\')
    $mcpSynthesisBlock = @"
s.mcpServers['synthesis'] = {
    command: 'java',
    args: ['-jar', '$synthEsc', 'mcp']
};
"@
}

$jsScript = @"
const fs = require('fs');
const path = '$settingsEsc';
let s = {};
try { s = JSON.parse(fs.readFileSync(path, 'utf8')); } catch {}

// kcp-commands PreToolUse hook (Node.js backend)
s.hooks = s.hooks || {};
s.hooks.PreToolUse = (s.hooks.PreToolUse || []).filter(
    g => !((g.hooks || []).some(h => (h.command || '').includes('kcp-commands')))
);
s.hooks.PreToolUse.push({
    matcher: 'Bash',
    hooks: [{
        type: 'command',
        command: 'node "$cliEsc"',
        timeout: 10,
        statusMessage: 'kcp-commands: looking up manifest...'
    }]
});

// kcp-memory PostToolUse hook
s.hooks.PostToolUse = (s.hooks.PostToolUse || []).filter(
    g => !((g.hooks || []).some(h => (h.command || '').includes('memory-hook')))
);
s.hooks.PostToolUse.push({
    matcher: '.*',
    hooks: [{
        type: 'command',
        command: 'powershell -NonInteractive -File "$hookEsc"'
    }]
});

// kcp-memory MCP server
s.mcpServers = s.mcpServers || {};
s.mcpServers['kcp-memory'] = {
    command: 'java',
    args: ['-jar', '$jarEsc', 'mcp']
};

$mcpSynthesisBlock

fs.writeFileSync(path, JSON.stringify(s, null, 2) + '\n');
console.log('settings.json updated');
"@

$tmpJs = "$env:TEMP\kcp-setup-$([System.Guid]::NewGuid().ToString('N')).js"
try {
    Set-Content $tmpJs $jsScript -Encoding UTF8
    node $tmpJs
    Write-Done "Hooks and MCP servers registered"
} finally {
    Remove-Item $tmpJs -ErrorAction SilentlyContinue
}

# ── 10. Initial kcp-memory scan ───────────────────────────────────────────────

Write-Step "Initial session scan..."
try {
    java -jar "$KcpMemoryJar" scan
    Write-Done "Sessions indexed"
} catch {
    Write-Warn "Scan failed — run later: java -jar `"$KcpMemoryJar`" scan"
}

# ── 11. PowerShell profile alias ──────────────────────────────────────────────

Write-Step "PowerShell profile alias..."

$profilePath = $PROFILE.CurrentUserCurrentHost
$profileDir  = Split-Path $profilePath -Parent
New-Item -ItemType Directory -Force -Path $profileDir | Out-Null
if (-not (Test-Path $profilePath)) {
    Set-Content $profilePath "" -Encoding UTF8
}

$aliasLine = "function kcp-memory { java -jar `"$KcpMemoryJar`" @args }"
$existing  = Get-Content $profilePath -Raw -ErrorAction SilentlyContinue
if (-not ($existing -match "kcp-memory")) {
    Add-Content $profilePath "`n$aliasLine"
    Write-Done "Alias added to $profilePath"
} else {
    Write-Done "Alias already in profile"
}

# ── 12. Synthesis summary (if provided) ───────────────────────────────────────

if ($Synthesis) {
    Write-Step "Synthesis MCP server..."
    if (Test-Path $Synthesis) {
        Write-Done "Registered: $Synthesis"
    } else {
        Write-Warn "JAR not found: $Synthesis"
        Write-Warn "Re-run with correct path once JAR is available"
    }
}

# ── Summary ───────────────────────────────────────────────────────────────────

Write-Host ""
Write-Host "   ─────────────────────────────────────────" -ForegroundColor DarkGray
Write-Host "   Installation complete!" -ForegroundColor Green
Write-Host "   ─────────────────────────────────────────" -ForegroundColor DarkGray
Write-Host ""
Write-Host "   Installed:" -ForegroundColor White
Write-Host "     Azul Zulu JDK 21"
Write-Host "     Node.js LTS"
Write-Host "     Claude Code"
Write-Host "     kcp-commands  — PreToolUse hook (283 command manifests)"
Write-Host "     kcp-memory    — episodic session memory (port 7735)"
if ($Synthesis -and (Test-Path $Synthesis)) {
    Write-Host "     Synthesis     — semantic knowledge MCP server"
}
Write-Host ""
Write-Host "   Next steps:" -ForegroundColor White
Write-Host "     1. Restart Claude Code to activate the hooks"
Write-Host "     2. Run: claude"
Write-Host "     3. Run: kcp-memory stats"
Write-Host ""

if (-not ($Synthesis -and (Test-Path $Synthesis))) {
    Write-Host "   Synthesis (semantic knowledge layer — optional):" -ForegroundColor Yellow
    Write-Host "     Once you have synthesis.jar, re-run:"
    Write-Host "     powershell -ExecutionPolicy Bypass -File install.ps1 -Synthesis C:\path\to\synthesis.jar"
    Write-Host ""
}

Write-Host "   Auto-start kcp-memory on login (optional):" -ForegroundColor Gray
Write-Host "     Task Scheduler -> New Basic Task -> On Logon ->"
Write-Host "     Program: java   Arguments: -jar `"$KcpMemoryJar`" daemon"
Write-Host ""
