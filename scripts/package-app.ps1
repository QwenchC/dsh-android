<#
  Package the Android application payload: the Node runtime, the helper
  executables, and the DeepSeek Harness tree.

  Three facts drive every step here.

  * An application may execute only code the installer extracted from the APK,
    so the Node executable and its libraries are native library entries. The
    platform extracts an entry only when its name starts with "lib" and ends
    with ".so", which is why non-conforming libraries are renamed upstream.

  * The Harness tree must be a real directory tree, because an APK cannot carry
    symbolic links and Node resolves its packages through them.

  * Nothing is shipped that the Android surface cannot use: PowerShell, the
    LibreOffice engine, PTY prebuilds, and the platform payloads of packages
    that have an Android equivalent are removed before the archive is written.

  Usage: powershell -File scripts/package-app.ps1 -Abi arm64-v8a -NodeAbi arm64
#>
param(
  [string]$Abi = 'arm64-v8a',
  [string]$NodeAbi = 'arm64',
  [string]$Workspace = 'E:\Lumi\DSH-Android',
  [string]$Deploy = '',
  [string]$Jdk = '',
  [switch]$SkipArchive
)

$ErrorActionPreference = 'Stop'
if ($Deploy -eq '') { $Deploy = Join-Path $Workspace 'dist\dsh-hoisted' }
if ($Jdk -eq '')    { $Jdk = Join-Path $Workspace 'toolchain\jdk-21' }

$app       = Join-Path $Workspace 'android-app'
$nodeRoot  = Join-Path $Workspace ('runtime\node-android\{0}' -f $NodeAbi)
$toolsRoot = Join-Path $Workspace ('runtime\android-tools\{0}' -f $NodeAbi)
$stage     = Join-Path $Workspace 'dist\payload'
# Each ABI's libraries live in their own cache directory, because one archive
# serves every ABI the application ships.
$libCache  = Join-Path $Workspace ('dist\nativelib\{0}' -f $Abi)
$jniLibs   = Join-Path $app ('app\src\main\jniLibs\{0}' -f $Abi)
$assets    = Join-Path $app 'app\src\main\assets'
$archive   = Join-Path $assets 'dsh-runtime.zip'
$jar       = Join-Path $Jdk 'bin\jar.exe'

function Assert-Path([string]$Path, [string]$What) {
  if (-not (Test-Path $Path)) { throw "$What is missing: $Path" }
}

Assert-Path (Join-Path $nodeRoot 'bin\node') 'the Node executable'
Assert-Path (Join-Path $Deploy 'lib\bin.js') 'the deployed Harness CLI'
Assert-Path $jar 'the packaging JDK'

Write-Output "=== payload for $Abi (node abi $NodeAbi) ==="

# native library entries
if (Test-Path $jniLibs) { Remove-Item $jniLibs -Recurse -Force }
New-Item -ItemType Directory -Force -Path $jniLibs, $assets | Out-Null

# Only the executable becomes a native library entry. The platform extracts a
# lib/ entry only when it is named lib*.so, and the runtime's shared libraries
# keep the names their SONAMEs require; they travel inside the archive and are
# found through LD_LIBRARY_PATH instead.
Copy-Item (Join-Path $nodeRoot 'bin\node') (Join-Path $jniLibs 'libnode.so') -Force
$tools = @(@{ Name = 'rg'; Target = 'librg.so' }, @{ Name = 'bash'; Target = 'libbash.so' })
foreach ($tool in $tools) {
  $source = Join-Path $toolsRoot ('bin\' + $tool.Name)
  if (Test-Path $source) {
    Copy-Item $source (Join-Path $jniLibs $tool.Target) -Force
    Write-Output ('  {0} -> {1}' -f $tool.Name, $tool.Target)
  } else {
    Write-Output ('  {0}: not bundled' -f $tool.Name)
  }
}
$native = Get-ChildItem $jniLibs -File
Write-Output ('  native entries: {0} ({1} MB)' -f $native.Count, [math]::Round(($native | Measure-Object Length -Sum).Sum / 1MB, 1))

# Harness tree
Write-Output '  staging the Harness tree'
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $stage | Out-Null
& robocopy $Deploy $stage /MIR /NFL /NDL /NJH /NJS /NP /MT:16 | Out-Null
if ($LASTEXITCODE -ge 8) { throw "robocopy failed with $LASTEXITCODE" }

# The vendored Cordis packages. pnpm keeps a "link:" workspace dependency as a
# symbolic link, which the deployment carries as a link and an archive cannot
# carry at all, so each vendored package is copied in as a real directory. Their
# own dependencies resolve through the deployed tree's hoisted node_modules.
$vendor = Join-Path $Workspace 'dsh-harness\vendor'
$vendored = 0
if (Test-Path $vendor) {
  foreach ($package in Get-ChildItem $vendor -Directory) {
    $manifest = Join-Path $package.FullName 'package.json'
    if (-not (Test-Path $manifest)) { continue }
    # Read as UTF-8: these manifests carry localized descriptions and the
    # default Windows PowerShell encoding would corrupt them.
    $name = ([System.IO.File]::ReadAllText($manifest, [System.Text.Encoding]::UTF8) | ConvertFrom-Json).name
    if (-not $name) { continue }
    $destination = Join-Path $stage ('node_modules\' + $name.Replace('/', '\'))
    if (Test-Path $destination) { Remove-Item $destination -Recurse -Force -ErrorAction SilentlyContinue }
    New-Item -ItemType Directory -Force -Path $destination | Out-Null
    & robocopy $package.FullName $destination /MIR /XD node_modules /NFL /NDL /NJH /NJS /NP | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "robocopy failed for $name with $LASTEXITCODE" }
    $vendored++
  }
}
Write-Output ('  vendored packages: {0}' -f $vendored)

# node-addon-require-builtin publishes a native binding per desktop platform and
# none for Android. Its entry builds the binding while the module is imported,
# so a missing payload throws before any caller's fallback can run. The stub
# keeps the package importable and reports unavailability; the loader reaches
# Node internals through --expose-internals instead.
$addonEntry = Join-Path $stage 'node_modules\node-addon-require-builtin\lib\index.js'
if (Test-Path $addonEntry) {
  $stub = @'
"use strict";
// Android payload: this family publishes no native binding for this platform.
// Callers that need Node internals use the --expose-internals path instead, so
// this module reports unavailability rather than throwing during import.
function unavailable() {
    throw Object.assign(new Error("require-builtin is unavailable on " + process.platform + "-" + process.arch), {
        code: "ERR_REQUIRE_BUILTIN_UNAVAILABLE",
    });
}
exports.requireBuiltin = unavailable;
exports.isAllowedInternalId = function isAllowedInternalId() { return false; };
exports.getBindingInfo = function getBindingInfo() {
    return { available: false, platform: process.platform, arch: process.arch };
};
exports.default = {
    requireBuiltin: exports.requireBuiltin,
    isAllowedInternalId: exports.isAllowedInternalId,
    getBindingInfo: exports.getBindingInfo,
};
'@
  [System.IO.File]::WriteAllText($addonEntry, $stub, (New-Object System.Text.UTF8Encoding($false)))
  Write-Output '  require-builtin: replaced with the unavailable-on-Android entry'
}

# The native system addon's Android payload. The workspace declares one platform
# package per operating system and the installer fetches only the running host's,
# so an Android deployment must add its own to the tree.
# The addon resolves its payload by the running platform and architecture, so
# every shipped ABI's package goes into the one archive; each is a few kilobytes.
$nativePackages = Join-Path $Workspace 'dsh-harness\native\system\packages'
$nativeAdded = 0
foreach ($platform in Get-ChildItem $nativePackages -Directory -Filter 'android-*') {
  $nativeDestination = Join-Path $stage ('node_modules\@deepseek-ai\node-addon-system-' + $platform.Name)
  if (Test-Path $nativeDestination) { Remove-Item $nativeDestination -Recurse -Force -ErrorAction SilentlyContinue }
  New-Item -ItemType Directory -Force -Path $nativeDestination | Out-Null
  & robocopy $platform.FullName $nativeDestination /MIR /NFL /NDL /NJH /NJS /NP | Out-Null
  if ($LASTEXITCODE -ge 8) { throw "robocopy failed for the native system payload with $LASTEXITCODE" }
  $nativeAdded++
}
if ($nativeAdded -eq 0) {
  throw 'no android native system payload is present; run scripts/build-android-native.ps1'
}
Write-Output ('  native system payloads: {0}' -f $nativeAdded)

# The Node runtime's libraries, under the file names their SONAMEs name, kept
# in this ABI's cache and merged into the shared tree below.
if (Test-Path $libCache) { Remove-Item $libCache -Recurse -Force }
New-Item -ItemType Directory -Force -Path $libCache | Out-Null
Get-ChildItem (Join-Path $nodeRoot 'lib') -File | ForEach-Object {
  Copy-Item $_.FullName (Join-Path $libCache $_.Name) -Force
}
# The helper executables' own libraries, which resolve through the same
# LD_LIBRARY_PATH. A library both need is bundled once.
if (Test-Path (Join-Path $toolsRoot 'lib')) {
  Get-ChildItem (Join-Path $toolsRoot 'lib') -File | ForEach-Object {
    $destination = Join-Path $libCache $_.Name
    if (-not (Test-Path $destination)) { Copy-Item $_.FullName $destination -Force }
  }
}
$libraries = Get-ChildItem $libCache -File
Write-Output ('  runtime libraries for {0}: {1} ({2} MB)' -f $Abi, $libraries.Count, [math]::Round(($libraries | Measure-Object Length -Sum).Sum / 1MB, 1))

# Every ABI's cached libraries go into the one archive the application ships.
$runtimeLib = Join-Path $stage 'nativelib'
New-Item -ItemType Directory -Force -Path $runtimeLib | Out-Null
foreach ($cached in Get-ChildItem (Join-Path $Workspace 'dist\nativelib') -Directory) {
  $destination = Join-Path $runtimeLib $cached.Name
  New-Item -ItemType Directory -Force -Path $destination | Out-Null
  Get-ChildItem $cached.FullName -File | ForEach-Object {
    Copy-Item $_.FullName (Join-Path $destination $_.Name) -Force
  }
  Write-Output ('  archive libraries/{0}: {1} files' -f $cached.Name, (Get-ChildItem $destination -File).Count)
}

$before = (Get-ChildItem $stage -Recurse -File -Force | Measure-Object Length -Sum)

# node-pty is kept: the Android payload carries a binding built for each ABI
# under prebuilds/android-<arch>/, which is where its loader looks.
$dropNames = @()
$dropScoped = @(
  @{ Scope = '@img';         Pattern = 'sharp-*' },
  @{ Scope = '@vscode';      Pattern = 'ripgrep-*' },
  @{ Scope = '@koromix';     Pattern = 'koffi-*' },
  @{ Scope = '@deepseek-ai'; Pattern = 'libreoffice-kit-*' }
)
$dropped = 0
$directories = Get-ChildItem $stage -Recurse -Directory -Force | Sort-Object { $_.FullName.Length } -Descending
foreach ($directory in $directories) {
  if (-not (Test-Path $directory.FullName)) { continue }
  $name = $directory.Name
  $parent = Split-Path (Split-Path $directory.FullName -Parent) -Leaf
  $scoped = $false
  foreach ($rule in $dropScoped) {
    if ($parent -eq $rule.Scope -and $name -like $rule.Pattern) { $scoped = $true; break }
  }
  if ($scoped -or ($dropNames -contains $name) -or ($name -like 'node-addon-require-builtin-*')) {
    Remove-Item $directory.FullName -Recurse -Force -ErrorAction SilentlyContinue
    $dropped++
  }
}
Write-Output ('  dropped {0} platform payload directories' -f $dropped)

$removed = 0
$artifacts = Get-ChildItem $stage -Recurse -File -Force | Where-Object {
  $_.Extension -eq '.map' -or $_.Name -like '*.d.ts' -or $_.Name -like '*.d.mts' -or $_.Name -like '*.d.cts'
}
foreach ($file in $artifacts) {
  Remove-Item $file.FullName -Force -ErrorAction SilentlyContinue
  $removed++
}
Write-Output ('  removed {0} build artifact files' -f $removed)

$emptied = 0
while ($true) {
  $empty = Get-ChildItem $stage -Recurse -Directory -Force | Where-Object {
    (Get-ChildItem $_.FullName -Force -ErrorAction SilentlyContinue | Measure-Object).Count -eq 0
  }
  if ($empty.Count -eq 0) { break }
  foreach ($directory in $empty) {
    Remove-Item $directory.FullName -Recurse -Force -ErrorAction SilentlyContinue
    $emptied++
  }
}
Write-Output ('  removed {0} empty directories' -f $emptied)

$after = (Get-ChildItem $stage -Recurse -File -Force | Measure-Object Length -Sum)
$summary = '  tree: {0} MB in {1} files -> {2} MB in {3} files'
Write-Output ($summary -f [math]::Round($before.Sum / 1MB, 1), $before.Count, [math]::Round($after.Sum / 1MB, 1), $after.Count)

if (-not $SkipArchive) {
  Write-Output '  writing assets/dsh-runtime.zip'
  if (Test-Path $archive) { Remove-Item $archive -Force }
  & $jar --create --file $archive -C $stage .
  if ($LASTEXITCODE -ne 0) { throw "jar failed with $LASTEXITCODE" }
  Write-Output ('  archive: {0} MB' -f [math]::Round((Get-Item $archive).Length / 1MB, 1))
} else {
  Write-Output '  archive: reused'
}

# The installed runtime is replaced when its payload changes, so the stamp is
# the archive's own digest rather than a version number that can move without
# the payload moving with it.
$stampFile = Join-Path $assets 'runtime-stamp.txt'
$digest = (Get-FileHash -Path $archive -Algorithm SHA256).Hash.ToLowerInvariant()
[System.IO.File]::WriteAllText($stampFile, $digest, (New-Object System.Text.UTF8Encoding($false)))
Write-Output ('  stamp: {0}' -f $digest.Substring(0, 16))
Write-Output 'done'
