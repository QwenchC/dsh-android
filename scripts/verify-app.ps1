<# Validate Android regressions and packaging; optionally run keyless tests on an already attached disposable device. #>
param(
  [string]$Workspace = (Split-Path $PSScriptRoot -Parent),
  [string]$Jdk = '',
  [string]$DeviceSerial = '',
  [ValidateSet('Regression', 'PlatformCompatibility')][string]$DeviceSuite = 'Regression',
  [switch]$Offline
)
$ErrorActionPreference = 'Stop'
if ($Jdk -eq '') { $Jdk = Join-Path $Workspace 'toolchain\jdk-21' }
$gradle = Join-Path $Workspace 'android-app\gradlew.bat'
$policyTests = Join-Path $Workspace 'scripts\tests\android-policy.test.mjs'
if (-not (Test-Path -LiteralPath (Join-Path $Workspace 'dist\payload\package.json'))) {
  throw 'The packaged runtime tree is missing. Build and package the Harness before running its policy composition tests.'
}
$archive = Join-Path $Workspace 'android-app\app\src\main\assets\dsh-runtime.zip'
$stamp = (Get-Content -LiteralPath (Join-Path (Split-Path $archive) 'runtime-stamp.txt') -Raw).Trim()
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $stamp) {
  throw 'The runtime archive does not match its packaged stamp.'
}
$previousJava = $env:JAVA_HOME
try {
  $env:JAVA_HOME = $Jdk
  Push-Location (Join-Path $Workspace 'android-app')
  try {
    $runner = "com.dsh.android.${DeviceSuite}Instrumentation"
    $arguments = @("-PdshTestRunner=$runner", ':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebug', ':app:assembleRelease', ':app:assembleDebugAndroidTest', '--warning-mode=fail', '--console=plain')
    if ($Offline) { $arguments += '--offline' }
    & $gradle @arguments 2>&1 | Tee-Object -Variable gradleOutput
    if ($LASTEXITCODE -ne 0) { throw 'Android checks or packaging failed.' }
    if (($gradleOutput -join "`n") -match '(?im)^\s*WARNING:') {
      throw 'Android build tools logged a warning; inspect and fix it before accepting verification.'
    }
  } finally { Pop-Location }
  [xml]$lintReport = Get-Content -LiteralPath (Join-Path $Workspace 'android-app\app\build\reports\lint-results-debug.xml') -Raw
  if (@($lintReport.SelectNodes('/issues/issue')).Count -ne 0) { throw 'Android lint must have zero reported issues.' }
  node --expose-internals --test $policyTests
  if ($LASTEXITCODE -ne 0) { throw 'Harness policy composition tests failed.' }
  if ($DeviceSerial -ne '') {
    $sdk = $env:ANDROID_HOME
    if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    $adb = Join-Path $sdk 'platform-tools\adb.exe'
    foreach ($relative in @('debug\app-debug.apk', 'androidTest\debug\app-debug-androidTest.apk')) {
      & $adb -s $DeviceSerial install -r (Join-Path $Workspace ('android-app\app\build\outputs\apk\' + $relative))
      if ($LASTEXITCODE -ne 0) { throw 'Device APK installation failed.' }
    }
    $deviceOutput = & $adb -s $DeviceSerial shell am instrument -w "com.dsh.android.test/$runner"
    $deviceExit = $LASTEXITCODE
    $deviceOutput | Write-Output
    $successPattern = if ($DeviceSuite -eq 'Regression') { 'PASS: \d+ device regressions' } else { 'PASS: \d+ platform compatibility checks' }
    if ($deviceExit -ne 0 -or ($deviceOutput -join "`n") -notmatch $successPattern -or ($deviceOutput -join "`n") -match 'FAIL:|Process crashed') {
      throw 'Device regression runner did not report success.'
    }
  }
  Get-FileHash -LiteralPath (Join-Path $Workspace 'android-app\app\build\outputs\apk\debug\app-debug.apk') -Algorithm SHA256
} finally { $env:JAVA_HOME = $previousJava }
