$env:JAVA_HOME = "C:\Users\xiaom\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2"
Set-Location "D:\Android\app-ckal0pi0u8sh\android_native"
$buildOutput = & { .\gradlew.bat :app:compileDebugKotlin --no-daemon 2>&1 | Out-String }
$buildOutput | Out-File -FilePath "D:\Android\app-ckal0pi0u8sh\android_native\build_result.txt" -Encoding utf8
Write-Host "Build completed. Output saved to build_result.txt"
