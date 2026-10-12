# Calden-style local Android build: repository Gradle wrapper, versioned
# APK in app/build/outputs/versioned, package/version + signature + SHA256.
$ErrorActionPreference = "Stop"

$Root = $PSScriptRoot
Set-Location $Root

if (-not $env:ANDROID_HOME) {
    $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA "Android\Sdk"
}
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
if (-not (Test-Path (Join-Path $env:ANDROID_HOME "platforms\android-35\android.jar"))) {
    throw "Android SDK Platform 35 missing. Install API 35 through Android Studio SDK Manager."
}
if (-not $env:JAVA_HOME) {
    $StudioJdk = Join-Path $env:ProgramFiles "Android\Android Studio\jbr"
    if (Test-Path (Join-Path $StudioJdk "bin\javac.exe")) { $env:JAVA_HOME = $StudioJdk }
}
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME "bin\javac.exe"))) {
    throw "JAVA_HOME must point to a JDK (Android Studio's JBR works)."
}
if (-not (Test-Path ".\gradlew.bat") -or -not (Test-Path ".\gradle\wrapper\gradle-wrapper.jar")) {
    throw "Gradle wrapper is missing. Run git pull --ff-only origin main."
}

$gradleText = Get-Content (Join-Path $Root "app\build.gradle") -Raw
$version = [regex]::Match($gradleText, '(?m)^\s*versionName\s+[''"]([^''"]+)[''"]')
$package = [regex]::Match($gradleText, '(?m)^\s*applicationId\s+[''"]([^''"]+)[''"]')
$code = [regex]::Match($gradleText, '(?m)^\s*versionCode\s+(\d+)')
if (-not ($version.Success -and $package.Success -and $code.Success)) {
    throw "Could not read app version and package from app\build.gradle."
}

Write-Host "===== RECIPE IMPORTER ANDROID v$($version.Groups[1].Value) =====" -ForegroundColor Cyan
& .\gradlew.bat --no-daemon --console=plain clean testDebugUnitTest assembleDebug
if ($LASTEXITCODE -ne 0) { throw "Gradle tests or APK build failed." }

$folder = Join-Path $Root "app\build\outputs\versioned"
$apk = Join-Path $folder "Recipe-Importer-v$($version.Groups[1].Value)-debug.apk"
if (-not (Test-Path $apk)) { throw "Versioned APK was not created: $apk" }

$buildTools = Join-Path $env:ANDROID_HOME "build-tools"
$tools = @(Get-ChildItem -Path $buildTools -Directory -ErrorAction Stop |
    Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
    Sort-Object { [version]$_.Name } -Descending)
if ($tools.Count -eq 0) { throw "Android SDK build-tools not found." }
$selected = $tools[0].FullName
$aapt = Join-Path $selected "aapt.exe"
$signer = Join-Path $selected "apksigner.bat"
if (-not (Test-Path $aapt) -or -not (Test-Path $signer)) {
    throw "aapt.exe or apksigner.bat missing in Android build-tools."
}

$badging = @(& $aapt dump badging $apk)
if ($LASTEXITCODE -ne 0) { throw "APK badging check failed." }
$packageLine = $badging | Where-Object { $_ -like "package:*" } | Select-Object -First 1
$expect = "package: name='$($package.Groups[1].Value)' versionCode='$($code.Groups[1].Value)' versionName='$($version.Groups[1].Value)'"
if (-not $packageLine -or -not $packageLine.StartsWith($expect)) {
    throw "Wrong package or app version in APK: $packageLine"
}

& $signer verify --verbose --print-certs $apk
if ($LASTEXITCODE -ne 0) { throw "APK signature check failed." }

$hash = (Get-FileHash -Path $apk -Algorithm SHA256).Hash.ToLowerInvariant()
"$hash  $(Split-Path $apk -Leaf)" | Set-Content (Join-Path $folder "SHA256SUMS.txt")
Write-Host ""
Write-Host "SUCCESS: $apk" -ForegroundColor Green
Write-Host "Size: $([math]::Round((Get-Item $apk).Length / 1MB, 2)) MB"
Write-Host "SHA256: $hash"
