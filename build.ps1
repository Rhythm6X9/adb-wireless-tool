# =====================================================================
#  build.ps1 —— 直接调用 Android SDK 工具链编译 APK（不需要 Gradle）
#  结构：<root>\{repo\app\..., jdk17, sdk, libs, keystore}
# =====================================================================
$ErrorActionPreference = 'Stop'
$root = 'C:\adbtool_env'
$src  = Join-Path $root 'repo'
$sdk  = Join-Path $root 'sdk'
$jdk  = Join-Path $root 'jdk17'
$libs = Join-Path $root 'libs'
$bt   = Join-Path $sdk 'build-tools\34.0.0'
$ajr  = Join-Path $sdk 'platforms\android-34\android.jar'
$ks   = Join-Path $root 'keystore\release.keystore'

foreach ($p in @($jdk, $sdk, $bt, $ajr, $ks)) {
    if (-not (Test-Path $p)) { throw "missing: $p" }
}

$env:JAVA_HOME = $jdk
$env:PATH = (Join-Path $jdk 'bin') + ';' + $env:PATH

$res     = Join-Path $src 'app\src\main\res'
$man     = Join-Path $src 'app\src\main\AndroidManifest.xml'
$javaDir = Join-Path $src 'app\src\main\java'
$work    = Join-Path $root 'work'
$out     = Join-Path $root 'out'

foreach ($d in @($work, $out)) {
    if (Test-Path $d) { Remove-Item $d -Recurse -Force }
    New-Item -ItemType Directory -Path $d -Force | Out-Null
}
$gen = Join-Path $work 'gen'
$cls = Join-Path $work 'classes'
$dex = Join-Path $work 'dex'
foreach ($d in @($gen, $cls, $dex)) { New-Item -ItemType Directory -Path $d -Force | Out-Null }

$aapt2    = Join-Path $bt 'aapt2.exe'
$javac    = Join-Path $jdk 'bin\javac.exe'
$jar      = Join-Path $jdk 'bin\jar.exe'
$d8       = Join-Path $bt 'd8.bat'
$zipalign = Join-Path $bt 'zipalign.exe'
$apksign  = Join-Path $bt 'apksigner.bat'
$aapt     = Join-Path $bt 'aapt.exe'

function ToFs([string]$p) { return $p.Replace('\', '/') }

Write-Host '=== [1/6] aapt2 compile ==='
$flat = Join-Path $gen 'res.zip'
& $aapt2 compile --dir $res -o $flat
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }

Write-Host '=== [2/6] aapt2 link ==='
$baseApk = Join-Path $gen 'base.apk'
$jnigen  = Join-Path $gen 'gen'
New-Item -ItemType Directory -Path $jnigen -Force | Out-Null
& $aapt2 link -o $baseApk -I $ajr --manifest $man --java $jnigen --min-sdk-version 24 --target-sdk-version 34 --version-code 2 --version-name 2.0 --auto-add-overlay $flat
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

Write-Host '=== [3/6] javac ==='
$javaFiles = @(Get-ChildItem -Path $javaDir -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$genFiles  = @(Get-ChildItem -Path $jnigen -Recurse -Filter *.java -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
Write-Host ("sources: {0} + generated {1}" -f $javaFiles.Count, $genFiles.Count)

$cp = (@($ajr, (Join-Path $libs 'api-classes.jar'), (Join-Path $libs 'provider-classes.jar')) | ForEach-Object { ToFs $_ }) -join ';'
$argFile = Join-Path $gen 'javac.args'
$lines = New-Object System.Collections.ArrayList
[void]$lines.Add('-encoding'); [void]$lines.Add('UTF-8')
[void]$lines.Add('-source'); [void]$lines.Add('8')
[void]$lines.Add('-target'); [void]$lines.Add('8')
[void]$lines.Add('-nowarn')
[void]$lines.Add('-cp'); [void]$lines.Add("`"$cp`"")
[void]$lines.Add('-d'); [void]$lines.Add("`"$(ToFs $cls)`"")
foreach ($f in ($javaFiles + $genFiles)) { [void]$lines.Add("`"$(ToFs $f)`"") }
[IO.File]::WriteAllLines($argFile, $lines, (New-Object Text.UTF8Encoding($false)))
& $javac "@$argFile"
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

Write-Host '=== [4/6] d8 ==='
$appJar = Join-Path $work 'app.jar'
Push-Location $cls
& $jar cf $appJar .
Pop-Location
$allJars = @($appJar, (Join-Path $libs 'api-classes.jar'), (Join-Path $libs 'provider-classes.jar'))
& $d8 --release --min-api 24 --output $dex --lib $ajr @allJars
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }
$dexFile = Join-Path $dex 'classes.dex'
if (-not (Test-Path $dexFile)) { throw 'classes.dex not generated' }
Write-Host ("classes.dex = {0:N0} bytes" -f (Get-Item $dexFile).Length)

Write-Host '=== [5/6] pack + align ==='
Add-Type -AssemblyName System.IO.Compression.FileSystem
$unsigned = Join-Path $out 'ADBTool-unsigned.apk'
Copy-Item $baseApk $unsigned -Force
$zip = [IO.Compression.ZipFile]::Open($unsigned, 'Update')
$entry = $zip.GetEntry('classes.dex')
if ($entry) { $entry.Delete() }
[void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $dexFile, 'classes.dex', [IO.Compression.CompressionLevel]::Optimal)
$zip.Dispose()

$aligned = Join-Path $out 'ADBTool-aligned.apk'
& $zipalign -f -p 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

Write-Host '=== [6/6] sign ==='
$final = Join-Path $out 'ADBTool.apk'
& $apksign sign --ks $ks --ks-pass pass:android --key-pass pass:android --ks-key-alias adbtool --out $final $aligned
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }

Remove-Item $unsigned, $aligned -Force -ErrorAction SilentlyContinue

Write-Host ''
Write-Host '=== BUILD OK ==='
Write-Host ("APK  : {0}" -f $final)
Write-Host ("SIZE : {0:N0} bytes ({1:N1} KB)" -f (Get-Item $final).Length, ((Get-Item $final).Length / 1KB))

Write-Host ''
Write-Host '=== sign verify ==='
& $apksign verify --print-certs $final

Write-Host ''
Write-Host '=== badging ==='
& $aapt dump badging $final | Select-String -Pattern 'package:|application-label|sdkVersion|launchable-activity'
