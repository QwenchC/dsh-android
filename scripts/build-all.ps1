<#
  Build the whole Android port from source, in order.

  Every step is idempotent: the runtime assemblies skip work already done,
  the deployment replaces the previous tree, and the payload is restaged.

  Prerequisites: WSL Ubuntu with build tools, the Android NDK, a JDK 21, and
  the Android SDK. The WSL half needs HTTPS egress; set DSH_BUILD_PROXY when
  the host can only reach the network through a proxy.

  Usage: powershell -File scripts/build-all.ps1 [-Abi arm64-v8a] [-NodeAbi arm64] [-SkipRuntime]
#>
param(
  [string]$Abi = 'arm64-v8a',
  [string]$NodeAbi = 'arm64',
  [string]$Workspace = 'E:\Lumi\DSH-Android',
  [string]$Distro = 'Ubuntu-24.04',
  [string]$Proxy = 'http://127.0.0.1:7897',
  [switch]$SkipRuntime,
  [switch]$SkipHarness
)

$ErrorActionPreference = 'Stop'
function Step([string]$Name) { Write-Output ''; Write-Output ("=== $Name ===") }
function Wsl([string]$Script) {
  $command = "export https_proxy=$Proxy http_proxy=$Proxy; bash $Script"
  wsl -d $Distro -u root -- bash -c $command
  if ($LASTEXITCODE -ne 0) { throw "WSL step failed: $Script" }
}

if (-not $SkipRuntime) {
  Step 'Android Node runtime (Termux packages, unpatched)'
  Wsl '/mnt/e/Lumi/DSH-Android/scripts/build-node-runtime.sh'

  Step 'Android helper executables (ripgrep, bash)'
  Wsl '/mnt/e/Lumi/DSH-Android/scripts/build-android-tools.sh'

  Step 'Android native addons (flock)'
  & (Join-Path $Workspace 'scripts\build-android-native.ps1')
}

if (-not $SkipHarness) {
  Step 'DeepSeek Harness libraries and web frontend'
  Push-Location (Join-Path $Workspace 'dsh-harness')
  try {
    pnpm install --prefer-offline
    if ($LASTEXITCODE -ne 0) { throw 'pnpm install failed' }
    pnpm run build:lib
    if ($LASTEXITCODE -ne 0) { throw 'build:lib failed' }
    pnpm run build:web
    if ($LASTEXITCODE -ne 0) { throw 'build:web failed' }

    Step 'Self-contained deployment (hoisted, symlink-free)'
    $deploy = Join-Path $Workspace 'dist\dsh-hoisted'
    if (Test-Path $deploy) { Remove-Item $deploy -Recurse -Force }
    pnpm --filter @deepseek-ai/dsh deploy --prod --legacy --config.node-linker=hoisted --config.allow-unused-patches=true $deploy
    if ($LASTEXITCODE -ne 0) { throw 'deploy failed' }

    Step 'Peer closure (service definitions the deployment omits)'
    node (Join-Path $Workspace 'scripts\complete-deployment.mjs') (Join-Path $Workspace 'dsh-harness') $deploy
    if ($LASTEXITCODE -ne 0) { throw 'completing the deployment failed' }
  } finally { Pop-Location }
}

Step "Application payload ($Abi)"
& (Join-Path $Workspace 'scripts\package-app.ps1') -Abi $Abi -NodeAbi $NodeAbi

Step 'APK'
$env:JAVA_HOME = Join-Path $Workspace 'toolchain\jdk-21'
$env:ANDROID_HOME = 'C:\Users\LumiTree\AppData\Local\Android\Sdk'
Push-Location (Join-Path $Workspace 'android-app')
try {
  .\gradlew.bat assembleDebug
  if ($LASTEXITCODE -ne 0) { throw 'gradle assembleDebug failed' }
} finally { Pop-Location }

$apk = Join-Path $Workspace "android-app\app\build\outputs\apk\debug\app-debug.apk"
Write-Output ''
Write-Output ("APK: {0} ({1} MB)" -f $apk, [math]::Round((Get-Item $apk).Length / 1MB, 1))
