$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$tdSource = Join-Path $projectRoot '.tools/tdlib'
$vcpkgSource = Join-Path $projectRoot '.tools/vcpkg'
$nativeOutput = Join-Path $projectRoot 'native/windows-x64'
$tdBuild = Join-Path $projectRoot '.tools/tdlib-build'
$tdInstall = Join-Path $projectRoot '.tools/tdlib-install'

function Invoke-Checked {
    param([string]$Command, [string[]]$Arguments)
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Command failed with exit code $LASTEXITCODE" }
}

# Sources are checked out at immutable revisions by the workflow.
Invoke-Checked (Join-Path $vcpkgSource 'bootstrap-vcpkg.bat') @('-disableMetrics')
Invoke-Checked (Join-Path $vcpkgSource 'vcpkg.exe') @('install', 'openssl:x64-windows-static', 'zlib:x64-windows-static', 'gperf:x64-windows-static', '--disable-metrics')
Invoke-Checked 'cmake' @(
    '-S', $tdSource, '-B', $tdBuild, '-A', 'x64',
    "-DCMAKE_TOOLCHAIN_FILE=$vcpkgSource/scripts/buildsystems/vcpkg.cmake",
    '-DVCPKG_TARGET_TRIPLET=x64-windows-static',
    "-DCMAKE_INSTALL_PREFIX=$tdInstall",
    '-DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded',
    '-DCMAKE_POLICY_DEFAULT_CMP0091=NEW',
    "-DGPERF_EXECUTABLE:FILEPATH=$vcpkgSource/installed/x64-windows-static/tools/gperf/gperf.exe",
    '-DTD_ENABLE_JNI=OFF', '-DTD_ENABLE_DOTNET=OFF',
    '-DTD_INSTALL_STATIC_LIBRARIES=OFF', '-DTD_ENABLE_LTO=OFF', '-DBUILD_TESTING=OFF'
)
Invoke-Checked 'cmake' @('--build', $tdBuild, '--config', 'Release', '--target', 'install', '--parallel', '2')

# Compose's windows-x64 resource directory is copied into the packaged resources root.
New-Item -ItemType Directory -Force -Path $nativeOutput | Out-Null
Copy-Item -LiteralPath (Join-Path $tdInstall 'bin/tdjson.dll') -Destination (Join-Path $nativeOutput 'tdjson.dll')
$licenses = Join-Path $nativeOutput 'licenses'
New-Item -ItemType Directory -Force -Path $licenses | Out-Null
Copy-Item -LiteralPath (Join-Path $tdSource 'LICENSE_1_0.txt') -Destination (Join-Path $licenses 'TDLib.txt')
foreach ($dependency in @('openssl', 'zlib')) {
    Copy-Item -LiteralPath (Join-Path $vcpkgSource "installed/x64-windows-static/share/$dependency/copyright") -Destination (Join-Path $licenses "$dependency.txt")
}
