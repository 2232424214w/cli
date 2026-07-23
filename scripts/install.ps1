# Install BetterCLI as a global `bettercli` command (Claude Code style).
#
# Layout:
#   %USERPROFILE%\.bettercli\bin\bettercli.cmd
#   %USERPROFILE%\.bettercli\lib\bettercli.jar
#
# Usage:
#   .\scripts\install.ps1
#   .\scripts\install.ps1 -SkipBuild
#   .\scripts\install.ps1 -NoPathUpdate
param(
    [switch]$SkipBuild,
    [switch]$NoPathUpdate,
    [switch]$Help
)

$ErrorActionPreference = "Stop"

if ($Help) {
    Write-Host "Usage: .\scripts\install.ps1 [-SkipBuild] [-NoPathUpdate]"
    exit 0
}

$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$InstallRoot = if ($env:BETTERCLI_HOME) { $env:BETTERCLI_HOME } else { Join-Path $env:USERPROFILE ".bettercli" }
$BinDir = Join-Path $InstallRoot "bin"
$LibDir = Join-Path $InstallRoot "lib"
$JarName = "bettercli-1.0-SNAPSHOT.jar"
$SrcJar = Join-Path $Root "target\$JarName"

function Test-Java {
    try {
        $null = Get-Command java -ErrorAction Stop
        return $true
    } catch {
        return $false
    }
}

if (-not (Test-Java)) {
    Write-Error "Java not found. BetterCLI requires Java 17+."
}

if (-not $SkipBuild) {
    try {
        $null = Get-Command mvn -ErrorAction Stop
    } catch {
        Write-Error "Maven not found. Install Maven, or pass -SkipBuild with an existing jar."
    }
    Write-Host "Packaging BetterCLI..."
    Push-Location $Root
    try {
        & mvn -q clean package "-DskipTests"
        if ($LASTEXITCODE -ne 0) {
            Write-Error "mvn package failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

if (-not (Test-Path $SrcJar)) {
    Write-Error "Missing jar: $SrcJar`nRun: mvn clean package"
}

New-Item -ItemType Directory -Force -Path $BinDir, $LibDir | Out-Null
Copy-Item -Force $SrcJar (Join-Path $LibDir "bettercli.jar")
Copy-Item -Force (Join-Path $Root "scripts\bettercli.cmd") (Join-Path $BinDir "bettercli.cmd")
Copy-Item -Force (Join-Path $Root "scripts\bettercli") (Join-Path $BinDir "bettercli")

Write-Host "Installed:"
Write-Host "  $(Join-Path $BinDir 'bettercli.cmd')"
Write-Host "  $(Join-Path $LibDir 'bettercli.jar')"

if (-not $NoPathUpdate) {
    $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
    if ($null -eq $userPath) { $userPath = "" }
    $parts = $userPath -split ";" | Where-Object { $_ -ne "" }
    $already = $parts | Where-Object { $_.TrimEnd("\") -ieq $BinDir.TrimEnd("\") }
    if (-not $already) {
        $newPath = if ($userPath.Trim() -eq "") { $BinDir } else { "$BinDir;$userPath" }
        [Environment]::SetEnvironmentVariable("Path", $newPath, "User")
        $env:Path = "$BinDir;$env:Path"
        Write-Host "Added to User PATH: $BinDir"
        Write-Host "(New terminals pick this up automatically; current session already updated.)"
    } else {
        Write-Host "PATH already contains $BinDir"
        if ($env:Path -notlike "*$BinDir*") {
            $env:Path = "$BinDir;$env:Path"
        }
    }
} else {
    Write-Host "Skipped PATH update. Add manually: $BinDir"
}

Write-Host ""
Write-Host "Then run:  bettercli"
