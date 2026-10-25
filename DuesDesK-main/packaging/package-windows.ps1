# Builds the Windows installer (target\installer\DuesDesk-<version>.msi, or .exe) on Windows 10/11.
# Requires JDK 25 (with jpackage), Maven, and the WiX Toolset (https://wixtoolset.org) on PATH for .msi/.exe.
# Usage:  powershell -ExecutionPolicy Bypass -File packaging\package-windows.ps1 [-Type msi|exe]
param([ValidateSet('msi', 'exe')][string]$Type = 'msi')
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

if (-not (Get-Command jpackage -ErrorAction SilentlyContinue)) {
    throw 'jpackage was not found. Install JDK 25 and add its bin folder to PATH.'
}
if (-not (Get-Command candle.exe -ErrorAction SilentlyContinue) -and -not (Get-Command wix.exe -ErrorAction SilentlyContinue)) {
    throw 'The WiX Toolset was not found. Install it from https://wixtoolset.org and add it to PATH.'
}

mvn -Pjpackage "-Djpackage.type=$Type" -DskipTests clean package
if ($LASTEXITCODE -ne 0) { throw "Maven build failed (exit $LASTEXITCODE)." }
Write-Host ''
Write-Host ('Installer written to: ' + (Get-ChildItem target\installer -Filter "*.$Type" | Select-Object -First 1).FullName)
