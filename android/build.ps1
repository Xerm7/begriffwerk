param([string]$KeyStore = "$PSScriptRoot/signing/begriffwerk.jks")
$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
Set-Location $project
$buildTools=Join-Path $PSScriptRoot 'tools/build-tools/android-15'
$platform=Join-Path $PSScriptRoot 'tools/platform/android-35/android.jar'
$javaBin=Join-Path $env:JAVA_HOME 'bin'
$output=Join-Path $PSScriptRoot 'build'
$source=Join-Path $PSScriptRoot 'app/src/main'
New-Item -ItemType Directory -Force "$output/native-classes","$output/dex","$output/package-assets","$PSScriptRoot/signing","$project/releases" | Out-Null
function Check-Exit { if($LASTEXITCODE -ne 0){throw "Build command failed with exit code $LASTEXITCODE"} }
& node "$PSScriptRoot/prepare.mjs"
Check-Exit
foreach($asset in @('index.html','app.js','styles.css','native-api.js','seed.sqlite','dataset-report.json')){
    Copy-Item "$source/assets/$asset" "$output/package-assets/$asset" -Force
}
& "$buildTools/aapt2.exe" compile --dir "$source/res" -o "$output/resources.zip"
Check-Exit
& "$buildTools/aapt2.exe" link -o "$output/resources.apk" -I $platform --manifest "$source/AndroidManifest.xml" -A "$output/package-assets" "$output/resources.zip"
Check-Exit
& "$javaBin/javac.exe" -encoding UTF-8 -source 8 -target 8 -bootclasspath "$buildTools/core-lambda-stubs.jar;$platform" -d "$output/native-classes" "$source/java/de/begriffwerk/app/NativeActivity.java" "$source/java/de/begriffwerk/app/QuizEngine.java"
Check-Exit
& "$javaBin/jar.exe" cf "$output/classes.jar" -C "$output/native-classes" .
Check-Exit
& "$javaBin/java.exe" -cp "$buildTools/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 26 --lib $platform --output "$output/dex" "$output/classes.jar"
Check-Exit
Copy-Item "$output/resources.apk" "$output/unsigned.apk" -Force
& "$javaBin/jar.exe" uf "$output/unsigned.apk" -C "$output/dex" classes.dex
Check-Exit
& "$buildTools/zipalign.exe" -f -p 4 "$output/unsigned.apk" "$output/aligned.apk"
Check-Exit
# Private personal signing identity: reuse for every update. Password is random,
# stored locally in an ignored file, never in application assets or source control.
$passwordFile=Join-Path (Split-Path $KeyStore -Parent) 'keystore-password.txt'
if(!(Test-Path $KeyStore)){
    $randomBytes=New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($randomBytes)
    $signingPassword=[Convert]::ToBase64String($randomBytes)
    [System.IO.File]::WriteAllText($passwordFile,$signingPassword)
    & "$javaBin/keytool.exe" -genkeypair -keystore $KeyStore -storepass:file $passwordFile -keypass:file $passwordFile -alias begriffwerk -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Begriffwerk Local, OU=Personal App, O=Begriffwerk, C=DE' -noprompt
    Check-Exit
}
if(!(Test-Path $passwordFile)){throw 'Missing local signing password file. Restore it with the signing key.'}
$manifestXml=[xml](Get-Content -Raw "$source/AndroidManifest.xml")
$version=$manifestXml.manifest.GetAttribute('versionName','http://schemas.android.com/apk/res/android')
$apkName="Begriffwerk-$version.apk"
$apk=Join-Path $project "releases/$apkName"
& "$javaBin/java.exe" -jar "$buildTools/lib/apksigner.jar" sign --ks $KeyStore --ks-key-alias begriffwerk --ks-pass "file:$passwordFile" --out $apk "$output/aligned.apk"
Check-Exit
& "$javaBin/java.exe" -jar "$buildTools/lib/apksigner.jar" verify --verbose --print-certs $apk
Check-Exit
& "$buildTools/zipalign.exe" -c -v 4 $apk | Select-Object -Last 1
Check-Exit
$hash=(Get-FileHash $apk -Algorithm SHA256).Hash.ToLower()
[System.IO.File]::WriteAllText("$apk.sha256","$hash  $apkName`n")
Write-Output "APK ready: $apk"
