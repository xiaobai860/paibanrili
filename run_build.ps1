$env:JAVA_HOME = "C:\Users\xiaom\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2"
Set-Location "D:\Android\app-ckal0pi0u8sh\android_native"
.\gradlew.bat :app:compileDebugKotlin --no-daemon 2>&1 | Select-Object -Last 40
