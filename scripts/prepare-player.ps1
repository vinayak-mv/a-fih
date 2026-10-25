$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskRelease = '20261021'
$taskRevision = 'e471496c56'
$taskArchive = "mpv-dev-x86_64-$taskRelease-git-$taskRevision.7z"
$taskChecksum = 'efc719eff3dc5954b4a5efc800632808bcec01cc9034940dd14e5eb7fe9ea633'
$taskTools = Join-Path $taskRoot '.tools/player'
New-Item -ItemType Directory -Force -Path $taskTools | Out-Null
$taskDownload = Join-Path $taskTools $taskArchive
# Baseline x86_64, not the x86_64-v3 build that requires newer CPU instructions.
Invoke-WebRequest -Uri "https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/$taskRelease/$taskArchive" -OutFile $taskDownload
if ((Get-FileHash -LiteralPath $taskDownload -Algorithm SHA256).Hash.ToLowerInvariant() -ne $taskChecksum) {
    throw 'The pinned libmpv archive checksum did not match.'
}
$taskDestination = Join-Path $taskRoot 'native/windows-x64/mpv'
New-Item -ItemType Directory -Force -Path $taskDestination | Out-Null
$taskSevenZip = (Get-Command '7z.exe' -ErrorAction SilentlyContinue).Source
if (-not $taskSevenZip) { $taskSevenZip = Join-Path $env:ProgramFiles '7-Zip/7z.exe' }
if (-not (Test-Path -LiteralPath $taskSevenZip)) { throw '7-Zip is required to prepare libmpv locally. GitHub Actions includes it.' }
& $taskSevenZip x $taskDownload "-o$taskDestination" -y | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'libmpv extraction failed.' }
if (-not (Test-Path -LiteralPath (Join-Path $taskDestination 'libmpv-2.dll'))) { throw 'libmpv-2.dll is missing from the archive.' }
foreach ($taskNotice in @('Copyright', 'LICENSE.GPL', 'LICENSE.LGPL')) {
    Invoke-WebRequest -Uri "https://raw.githubusercontent.com/mpv-player/mpv/$taskRevision/$taskNotice" -OutFile (Join-Path $taskDestination $taskNotice)
}
@"
libmpv build: https://github.com/shinchiro/mpv-winbuild-cmake/releases/tag/$taskRelease
mpv source: https://github.com/mpv-player/mpv/tree/$taskRevision
Build scripts and dependency sources: https://github.com/shinchiro/mpv-winbuild-cmake
Archive SHA-256: $taskChecksum
"@ | Set-Content -LiteralPath (Join-Path $taskDestination 'BUILD-SOURCES.txt') -Encoding utf8
Write-Output "Prepared checksum-verified libmpv $taskRelease x64 runtime."
