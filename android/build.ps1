# Builds Camera Finder into a signed APK with the Android SDK command-line tools
# (no Gradle): aapt2 -> javac -> d8 -> zipalign -> apksigner.
#
#   powershell -ExecutionPolicy Bypass -File build.ps1
#
# Needs: a JDK (JAVA_HOME), and an Android SDK with build-tools;34.0.0 and
# platforms;android-28 (ANDROID_HOME, or a tools\sdk folder in a parent directory).
#
# Signing: set CAMFINDER_KEYSTORE and CAMFINDER_KEYSTORE_PASS (or put the password
# in a keystore.pass file next to this script - it is git-ignored). Without a
# release key, the build signs with a local debug key so it still works.
$ErrorActionPreference = 'Stop'

function Say ($m){ Write-Host $m }
function Die ($m){ Write-Host "ERROR: $m" -ForegroundColor Red; exit 1 }

$App = $PSScriptRoot

# Look for a file or folder in this directory and each parent directory.
function Find-Up ($rel) {
  $d = $App
  while ($d) {
    $p = Join-Path $d $rel
    if (Test-Path $p) { return $p }
    $parent = Split-Path $d -Parent
    if (-not $parent -or $parent -eq $d) { break }
    $d = $parent
  }
  return $null
}

# --- Android SDK ---
$sdk = $null
foreach ($c in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
  if ($c -and (Test-Path (Join-Path $c 'build-tools\34.0.0'))) { $sdk = $c; break }
}
if (-not $sdk) { $sdk = Find-Up 'tools\sdk' }
if (-not $sdk) { Die "Android SDK not found. Set ANDROID_HOME (needs build-tools;34.0.0 and platforms;android-28)." }

$bt         = Join-Path $sdk 'build-tools\34.0.0'
$androidJar = Join-Path $sdk 'platforms\android-28\android.jar'
$aapt2  = Join-Path $bt 'aapt2.exe'
$d8     = Join-Path $bt 'd8.bat'
$zipal  = Join-Path $bt 'zipalign.exe'
$signer = Join-Path $bt 'apksigner.bat'
foreach ($p in @($androidJar,$aapt2,$d8,$zipal,$signer)) {
  if (-not (Test-Path $p)) { Die "missing build tool: $p" }
}

# --- JDK (d8 in build-tools 34 reads the --release 8 class files from JDK 17 or 21) ---
$javacCandidates = @()
if ($env:JAVA_HOME) { $javacCandidates += (Join-Path $env:JAVA_HOME 'bin\javac.exe') }
$javacCandidates += (Get-ChildItem `
    "$env:USERPROFILE\Documents\Codex\*\ca\tools\java\jdk-*\bin\javac.exe",
    'C:\Program Files\Java\jdk*\bin\javac.exe',
    'C:\Program Files\Eclipse Adoptium\jdk-*\bin\javac.exe',
    'C:\Program Files\Microsoft\jdk-*\bin\javac.exe',
    'C:\Program Files\Android\Android Studio\jbr\bin\javac.exe' `
    -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
$javac = $javacCandidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $javac) { Die "no JDK found. Install JDK 17 or 21 and set JAVA_HOME." }
$jdkBin = Split-Path $javac -Parent
$env:PATH = "$jdkBin;$env:PATH"   # d8.bat / apksigner.bat look for java on PATH
Say "JDK:   $javac"
Say "SDK:   $bt"

# --- signing key (never committed: *.keystore and keystore.pass are git-ignored) ---
$keystore = $env:CAMFINDER_KEYSTORE
if (-not $keystore) { $keystore = Find-Up 'release.keystore' }
$kpass = $env:CAMFINDER_KEYSTORE_PASS
$passFile = Join-Path $App 'keystore.pass'
if (-not $kpass -and (Test-Path $passFile)) { $kpass = (Get-Content $passFile -Raw).Trim() }
if (-not $keystore -or -not (Test-Path $keystore) -or -not $kpass) {
  $keystore = Join-Path $App 'debug.keystore'
  $kpass = 'android'
  if (-not (Test-Path $keystore)) {
    & (Join-Path $jdkBin 'keytool.exe') -genkeypair -keystore $keystore -alias debug -keyalg RSA `
        -keysize 2048 -validity 10000 -storepass $kpass -keypass $kpass -dname 'CN=Camera Finder debug' | Out-Null
    if ($LASTEXITCODE -ne 0) { Die "could not create debug.keystore" }
  }
  Say "Key:   local debug key (set CAMFINDER_KEYSTORE / CAMFINDER_KEYSTORE_PASS for a release build)"
} else {
  Say "Key:   $keystore"
}

$Work = Join-Path $App 'build'
if (Test-Path $Work) { Remove-Item $Work -Recurse -Force }
New-Item -ItemType Directory -Path (Join-Path $Work 'gen')     -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $Work 'classes') -Force | Out-Null

# --- 1. compile resources ---
Say "`n[1/6] aapt2 compile (resources)"
& $aapt2 compile --dir (Join-Path $App 'res') -o (Join-Path $Work 'res.zip')
if ($LASTEXITCODE -ne 0) { Die "aapt2 compile failed" }

# --- 2. link resources + manifest -> app.apk (+ R.java in gen) ---
Say "[2/6] aapt2 link"
& $aapt2 link -I $androidJar `
    --manifest (Join-Path $App 'AndroidManifest.xml') `
    (Join-Path $Work 'res.zip') `
    --min-sdk-version 28 --target-sdk-version 34 `
    --version-code 3 --version-name 1.2 `
    --java (Join-Path $Work 'gen') `
    -o (Join-Path $Work 'app.apk')
if ($LASTEXITCODE -ne 0) { Die "aapt2 link failed" }

# --- 3. compile Java (--release 8 so d8 can read the class files) ---
Say "[3/6] javac"
$sources = Join-Path $Work 'sources.txt'
Get-ChildItem -Path (Join-Path $App 'src'), (Join-Path $Work 'gen') -Recurse -Filter *.java |
  ForEach-Object { $_.FullName } | Out-File $sources -Encoding ascii
& $javac --release 8 -parameters -encoding UTF-8 -nowarn -classpath $androidJar `
    -d (Join-Path $Work 'classes') "@$sources"
if ($LASTEXITCODE -ne 0) { Die "javac failed" }

# --- 4. dex ---
Say "[4/6] d8 -> classes.dex"
$classes = Join-Path $Work 'classes.txt'
Get-ChildItem -Path (Join-Path $Work 'classes') -Recurse -Filter *.class |
  ForEach-Object { $_.FullName } | Out-File $classes -Encoding ascii
& $d8 --release --min-api 28 --lib $androidJar --output $Work "@$classes"
if ($LASTEXITCODE -ne 0 -or -not (Test-Path (Join-Path $Work 'classes.dex'))) { Die "d8 failed" }

# --- 5. inject classes.dex + assets/index.html into the APK ---
Say "[5/6] add classes.dex + assets"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open((Join-Path $Work 'app.apk'), 'Update')
try {
  foreach ($pair in @(
      @((Join-Path $Work 'classes.dex'), 'classes.dex'),
      @((Join-Path $App  'assets\index.html'), 'assets/index.html'))) {
    $entryName = $pair[1]
    $existing = $zip.Entries | Where-Object { $_.FullName -eq $entryName }
    if ($existing) { $existing.Delete() }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip, $pair[0], $entryName,
        [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
  }
} finally { $zip.Dispose() }

# --- 6. align + sign ---
Say "[6/6] zipalign + apksigner"
& $zipal -f -p 4 (Join-Path $Work 'app.apk') (Join-Path $Work 'aligned.apk')
if ($LASTEXITCODE -ne 0) { Die "zipalign failed" }

$apkOut = Join-Path $App 'camera-finder.apk'
& $signer sign --ks $keystore --ks-pass "pass:$kpass" --key-pass "pass:$kpass" `
    --out $apkOut (Join-Path $Work 'aligned.apk')
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $apkOut)) { Die "apksigner failed" }

$kb = [math]::Round((Get-Item $apkOut).Length / 1KB)
Say "`nBuilt: $apkOut  ($kb KB)"
& $signer verify --print-certs $apkOut | Select-Object -First 1
