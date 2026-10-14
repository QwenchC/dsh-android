<#
  Compile the Android payloads of @deepseek-ai/node-addon-system on a Windows
  host.

  The Windows NDK ships the same Clang driver the Linux NDK does, so the flock
  addon is compiled here without a Linux toolchain. Run
  scripts/build-android-native.sh instead when the build host is Linux or WSL.
#>
param(
  [string]$Checkout = 'E:\Lumi\DSH-Android\dsh-harness',
  [string]$Ndk = 'C:\Users\LumiTree\AppData\Local\Android\Sdk\ndk\27.2.12479018',
  [string]$Headers = 'E:\Lumi\DSH-Android\runtime\node-headers\include\node',
  [string]$Api = '24'
)

$ErrorActionPreference = 'Stop'
$tc = Join-Path $Ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin'
if (-not (Test-Path $tc)) { throw "no NDK toolchain at $tc" }
if (-not (Test-Path (Join-Path $Headers 'node_api.h'))) { throw "no Node headers at $Headers" }
$src = Join-Path $Checkout 'native\system\packages\entry\src\flock.c'

$targets = @(
  @{ Package = 'android-arm64'; Cc = 'aarch64-linux-android' },
  @{ Package = 'android-x64';   Cc = 'x86_64-linux-android' }
)

foreach ($t in $targets) {
  $out = Join-Path $Checkout ("native\system\packages\{0}\bin\bionic" -f $t.Package)
  New-Item -ItemType Directory -Force -Path $out | Out-Null
  $compiler = Join-Path $tc ("{0}{1}-clang.cmd" -f $t.Cc, $Api)
  Write-Output ("== {0} ({1}) ==" -f $t.Package, $t.Cc)
  & $compiler -shared -fPIC -O2 -Wall -I $Headers -o (Join-Path $out 'system.node') $src
  if ($LASTEXITCODE -ne 0) { throw "compile failed for $($t.Package)" }
  $node = Join-Path $out 'system.node'
  Write-Output ("   {0} bytes" -f (Get-Item $node).Length)
  & (Join-Path $tc 'llvm-readelf.exe') -h $node | Select-String 'Class:|Machine:|Type:' | ForEach-Object { '   ' + $_.Line.Trim() }
}
Write-Output 'done'
