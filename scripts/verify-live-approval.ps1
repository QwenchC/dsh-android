<# Opt-in: sends real, billable DeepSeek requests using the selected disposable device's configured key. #>
param(
  [Parameter(Mandatory=$true)][string]$DeviceSerial,
  [string]$Workspace = (Split-Path $PSScriptRoot -Parent),
  [string]$Python = 'python',
  [string]$Jdk = '',
  [string]$CredentialDeviceSerial = '',
  [string]$OutputDirectory = '',
  [switch]$Offline
)
$ErrorActionPreference = 'Stop'
if (-not $Jdk) { $Jdk = Join-Path $Workspace 'toolchain\jdk-21' }
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$out = if ($OutputDirectory) { $OutputDirectory } else { Join-Path $Workspace 'dist\live-approval' }
New-Item -ItemType Directory -Force -Path $out | Out-Null
$previousJava = $env:JAVA_HOME
try {
  $env:JAVA_HOME = $Jdk
  Push-Location (Join-Path $Workspace 'android-app')
  try {
    $arguments = @('-PdshTestRunner=com.dsh.android.LiveApprovalInstrumentation', ':app:assembleDebug', ':app:assembleDebugAndroidTest', '--warning-mode=fail', '--console=plain')
    if ($Offline) { $arguments += '--offline' }
    & .\gradlew.bat @arguments 2>&1 | Tee-Object -Variable gradleOutput
    if ($LASTEXITCODE -ne 0) { throw 'Live approval test build failed.' }
    if (($gradleOutput -join "`n") -match '(?im)^\s*WARNING:') { throw 'Android build tools logged a warning.' }
  } finally { Pop-Location }
  foreach ($relative in @('debug\app-debug.apk', 'androidTest\debug\app-debug-androidTest.apk')) {
    & $adb -s $DeviceSerial install -r (Join-Path $Workspace ('android-app\app\build\outputs\apk\' + $relative))
    if ($LASTEXITCODE -ne 0) { throw 'Device APK installation failed.' }
  }
  $stagingArguments = @((Join-Path $PSScriptRoot 'tests\stage-live-credentials.py'), '--adb', $adb, '--serial', $DeviceSerial)
  if ($CredentialDeviceSerial) { $stagingArguments += @('--credential-serial', $CredentialDeviceSerial) }
  & $Python @stagingArguments
  if ($LASTEXITCODE -ne 0) { throw 'A configured device DeepSeek key and Python PyYAML are required.' }
  $output = & $adb -s $DeviceSerial shell am instrument -w com.dsh.android.test/com.dsh.android.LiveApprovalInstrumentation
  $exitCode = $LASTEXITCODE
  $output | Set-Content -Encoding utf8 (Join-Path $out 'instrumentation.txt')
  $output | Write-Output
  $report = & $adb -s $DeviceSerial shell run-as com.dsh.android cat cache/live-approval-result.json
  if ($LASTEXITCODE -eq 0) { $report | Set-Content -Encoding utf8 (Join-Path $out 'result.json') }
  foreach ($label in @('initial', 'settings', 'reloaded', 'allow-pending', 'allow-finished', 'resumed', 'reject-pending', 'reject-finished', 'stop-pending', 'native-menu', 'stopped')) {
    $screenshot = "cache/live-approval-ui-$label.png"
    $present = & $adb -s $DeviceSerial shell run-as com.dsh.android /system/bin/ls $screenshot 2>$null
    if ($LASTEXITCODE -eq 0) {
      # Copy only known synthetic-test screenshots out of app-private cache; no user state is exported.
      & $Python (Join-Path $PSScriptRoot 'tests\export-live-screenshot.py') --adb $adb --serial $DeviceSerial --label $label --output $out
      if ($LASTEXITCODE -ne 0) { throw 'Live UI screenshot export failed.' }
    }
  }
  if ($exitCode -ne 0 -or ($output -join "`n") -notmatch 'PASS: 3 real-model approval interactions' -or ($output -join "`n") -match 'FAIL:|Process crashed') {
    throw 'Real model approval interactions did not all pass; inspect the secret-free report.'
  }
} finally {
  # Remove only the transient input, even when instrumentation cannot start.
  & $adb -s $DeviceSerial shell run-as com.dsh.android rm -f cache/live-approval-input.json | Out-Null
  $env:JAVA_HOME = $previousJava
}
