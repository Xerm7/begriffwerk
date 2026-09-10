$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
& node android/prepare.mjs
if($LASTEXITCODE -ne 0){throw 'Asset preparation failed'}
& node android/test-native.mjs
if($LASTEXITCODE -ne 0){throw 'Parity fixture generation failed'}
& "$env:JAVA_HOME/bin/javac.exe" -encoding UTF-8 -cp android/tools/json.jar -d android/build/native-tests android/app/src/main/java/de/begriffwerk/app/QuizEngine.java android/NativeEngineTest.java
if($LASTEXITCODE -ne 0){throw 'Native test compilation failed'}
& "$env:JAVA_HOME/bin/java.exe" -cp 'android/build/native-tests;android/tools/json.jar;android/tools/sqlite-jdbc.jar;android/tools/slf4j-api.jar' NativeEngineTest
if($LASTEXITCODE -ne 0){throw 'Native tests failed'}
