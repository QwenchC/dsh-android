<#
  Build node-pty for Android.

  node-pty compiles one POSIX source file and calls forkpty(2), which bionic
  provides from API 23, so the NDK builds the binding directly. Its loader looks
  in prebuilds/<platform>-<arch>/ after build/Release, which is where a
  per-ABI binding belongs when one archive serves every ABI — and the
  spawn-helper it also needs is resolved from the same directory.

  Usage: powershell -File scripts/build-node-pty.ps1
#>
param(
  [string]$Checkout = 'E:\Lumi\DSH-Android\dsh-harness',
  [string]$Deploy = 'E:\Lumi\DSH-Android\dist\dsh-hoisted',
  [string]$Ndk = 'C:\Users\LumiTree\AppData\Local\Android\Sdk\ndk\27.2.12479018',
  [string]$Headers = 'E:\Lumi\DSH-Android\runtime\node-headers\include\node',
  [string]$Api = '24'
)

$ErrorActionPreference = 'Stop'
$tc = Join-Path $Ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin'
$pty = Join-Path $Deploy 'node_modules\node-pty'
$napi = Join-Path $Deploy 'node_modules\node-addon-api'
if (-not (Test-Path $pty)) { throw "node-pty is not in the deployment: $pty" }
if (-not (Test-Path $napi)) { throw "node-addon-api is not in the deployment: $napi" }
if (-not (Test-Path (Join-Path $Headers 'node_api.h'))) { throw "no Node headers at $Headers" }

$targets = @(
  @{ Arch = 'x64';    Cc = 'x86_64-linux-android' },
  @{ Arch = 'arm64';  Cc = 'aarch64-linux-android' }
)

foreach ($target in $targets) {
  $out = Join-Path $pty ('prebuilds\android-{0}' -f $target.Arch)
  New-Item -ItemType Directory -Force -Path $out | Out-Null
  $compiler = Join-Path $tc ('{0}{1}-clang++.cmd' -f $target.Cc, $Api)
  Write-Output ('== android-{0} ==' -f $target.Arch)
  & $compiler -shared -fPIC -O2 -std=c++17 -Wall -Wno-unused-parameter `
      -I $Headers -I $napi `
      -o (Join-Path $out 'pty.node') (Join-Path $pty 'src\unix\pty.cc')
  if ($LASTEXITCODE -ne 0) { throw "pty.node failed for android-$($target.Arch)" }
  & $compiler -O2 -std=c++17 -Wall `
      -I $Headers -I $napi `
      -o (Join-Path $out 'spawn-helper') (Join-Path $pty 'src\unix\spawn-helper.cc')
  if ($LASTEXITCODE -ne 0) { throw "spawn-helper failed for android-$($target.Arch)" }
  Get-ChildItem $out -File | ForEach-Object { '   {0}  {1} bytes' -f $_.Name, $_.Length }
  & (Join-Path $tc 'llvm-readelf.exe') -h (Join-Path $out 'pty.node') | Select-String 'Machine:' | ForEach-Object { '   ' + $_.Line.Trim() }
}
Write-Output 'done'
