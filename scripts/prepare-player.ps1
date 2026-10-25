$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskVersion = '3.0.24'
$taskChecksum = 'fcf30850371ad10c9373cc4f0f4501e7dee49e3e9ae9f20c72fb2661a1ca6323'
$taskTools = Join-Path $taskRoot '.tools/player'
New-Item -ItemType Directory -Force -Path $taskTools | Out-Null
$taskZip = Join-Path $taskTools "vlc-$taskVersion-win64.zip"
Invoke-WebRequest -Uri "https://download.videolan.org/pub/videolan/vlc/$taskVersion/win64/vlc-$taskVersion-win64.zip" -OutFile $taskZip
if ((Get-FileHash -LiteralPath $taskZip -Algorithm SHA256).Hash.ToLowerInvariant() -ne $taskChecksum) {
    throw 'The official player archive checksum did not match.'
}
Expand-Archive -LiteralPath $taskZip -DestinationPath $taskTools -Force
$taskDestination = Join-Path $taskRoot 'native/windows-x64/vlc'
New-Item -ItemType Directory -Force -Path $taskDestination | Out-Null
# Keep the complete runtime, plugins and upstream license/attribution files together.
Get-ChildItem -LiteralPath (Join-Path $taskTools "vlc-$taskVersion") | Copy-Item -Destination $taskDestination -Recurse -Force
Write-Output "Prepared checksum-verified VLC $taskVersion x64 runtime."
