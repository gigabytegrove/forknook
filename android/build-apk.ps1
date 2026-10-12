# Run in PowerShell with Android SDK installed (e.g. by Android Studio).
$ErrorActionPreference = 'Stop'
$Version = '8.11.1'
$Sha256 = 'f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $Root
if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
    $DefaultSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (Test-Path $DefaultSdk) { $env:ANDROID_HOME = $DefaultSdk }
}
if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
    throw 'Android SDK not found. Install Android Studio SDK Platform 35, then set ANDROID_HOME.'
}
$Cache = Join-Path $env:LOCALAPPDATA 'RecipeBridge\gradle'
New-Item -Path $Cache -ItemType Directory -Force | Out-Null
$Archive = Join-Path $Cache "gradle-$Version-bin.zip"
$Binary = Join-Path $Cache "gradle-$Version\bin\gradle.bat"
if (-not (Test-Path $Binary)) {
    if (-not (Test-Path $Archive)) {
        Invoke-WebRequest -Uri "https://services.gradle.org/distributions/gradle-$Version-bin.zip" -OutFile $Archive
    }
    $Actual = (Get-FileHash -Path $Archive -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($Actual -ne $Sha256) { Remove-Item $Archive -Force; throw 'Gradle archive SHA-256 verification failed.' }
    Expand-Archive -Path $Archive -DestinationPath $Cache -Force
}
& $Binary --no-daemon wrapper --gradle-version $Version
if ($LASTEXITCODE -ne 0) { throw 'Could not initialize Gradle wrapper.' }
& (Join-Path $Root 'gradlew.bat') --no-daemon :app:assembleDebug
if ($LASTEXITCODE -ne 0) { throw 'APK compilation failed.' }
$BuiltApk = Join-Path $Root 'app\build\outputs\apk\debug\app-debug.apk'
$FinalApk = Join-Path $Root 'recipe-bridge-1.0.0-debug.apk'
Copy-Item -Path $BuiltApk -Destination $FinalApk -Force
Write-Host "APK created: $FinalApk" -ForegroundColor Green
