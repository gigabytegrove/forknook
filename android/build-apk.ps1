# Forknook local Windows APK build (Calden-style).
# Reuses the existing Android Studio SDK/JDK; installs missing API 35 when possible.
# Produces a versioned APK and checks its identity, signature and SHA-256.
$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot
Set-Location $Root

$SdkCandidates = @(
    $env:ANDROID_HOME,
    $env:ANDROID_SDK_ROOT,
    (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
) | Where-Object { $_ -and (Test-Path $_ -PathType Container) } | Select-Object -Unique

$Sdk = $SdkCandidates | Where-Object {
    Test-Path (Join-Path $_ 'platforms') -PathType Container
} | Select-Object -First 1
if (-not $Sdk) { throw 'Android SDK not found. Reuse the same ANDROID_HOME used by Calden.' }
$env:ANDROID_HOME = $Sdk
$env:ANDROID_SDK_ROOT = $Sdk

if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
    $StudioJdk = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
    if (Test-Path (Join-Path $StudioJdk 'bin\javac.exe')) { $env:JAVA_HOME = $StudioJdk }
}
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
    throw 'A JDK is required; configure JAVA_HOME to the JDK used for Calden.'
}
$env:Path = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:Path

$Api35 = Join-Path $Sdk 'platforms\android-35\android.jar'
if (-not (Test-Path $Api35)) {
    Write-Host 'Android SDK 35 not installed; attempting to add it to the existing SDK...' -ForegroundColor Yellow
    $Manager = Join-Path $Sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
    if (-not (Test-Path $Manager)) {
        $Manager = Get-ChildItem (Join-Path $Sdk 'cmdline-tools') -Recurse -File -Filter 'sdkmanager.bat' -ErrorAction SilentlyContinue |
            Select-Object -First 1 -ExpandProperty FullName
    }
    if ($Manager -and (Test-Path $Manager)) {
        & $Manager "--sdk_root=$Sdk" 'platforms;android-35'
        if ($LASTEXITCODE -ne 0) { throw 'Android SDK Platform 35 could not be installed.' }
    } else {
        Write-Host 'SDK manager is unavailable; Gradle will attempt to provision Platform 35.' -ForegroundColor Yellow
    }
}

$Gradle = Join-Path $Root 'gradlew.bat'
$WrapperJar = Join-Path $Root 'gradle\wrapper\gradle-wrapper.jar'
if (-not (Test-Path $Gradle) -or -not (Test-Path $WrapperJar)) {
    throw 'Committed Gradle wrapper is missing. Pull the latest Forknook repository.'
}

$BuildGradle = Get-Content (Join-Path $Root 'app\build.gradle') -Raw
$VersionMatch = [regex]::Match($BuildGradle, "versionName\s+'([^']+)'")
$CodeMatch = [regex]::Match($BuildGradle, 'versionCode\s+(\d+)')
$PackageMatch = [regex]::Match($BuildGradle, "applicationId\s+'([^']+)'")
if (-not ($VersionMatch.Success -and $CodeMatch.Success -and $PackageMatch.Success)) {
    throw 'Android app version/package could not be read from app/build.gradle.'
}
$AppVersion = $VersionMatch.Groups[1].Value
$AppCode = $CodeMatch.Groups[1].Value
$AppPackage = $PackageMatch.Groups[1].Value

Write-Host "===== FORKNOOK ANDROID v$AppVersion =====" -ForegroundColor Cyan
& $Gradle --no-daemon --console=plain clean testDebugUnitTest assembleDebug
if ($LASTEXITCODE -ne 0) { throw 'Gradle tests or Android APK build failed.' }

$Built = Join-Path $Root 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $Built)) { throw 'Gradle did not produce a debug APK.' }
$OutDir = Join-Path $Root 'app\build\outputs\versioned'
New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
$Apk = Join-Path $OutDir "Forknook-v$AppVersion-debug.apk"
Copy-Item $Built $Apk -Force

$BuildTools = @(Get-ChildItem (Join-Path $Sdk 'build-tools') -Directory -ErrorAction Stop |
    Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
    Sort-Object { [version]$_.Name } -Descending)
if ($BuildTools.Count -eq 0) { throw 'Android build-tools are missing.' }
$Aapt = Join-Path $BuildTools[0].FullName 'aapt.exe'
$Signer = Join-Path $BuildTools[0].FullName 'apksigner.bat'
if (-not (Test-Path $Aapt) -or -not (Test-Path $Signer)) {
    throw 'aapt.exe or apksigner.bat not installed.'
}

$Badging = @(& $Aapt dump badging $Apk)
if ($LASTEXITCODE -ne 0) { throw 'APK package metadata verification failed.' }
$PackageLine = $Badging | Where-Object { $_ -like 'package:*' } | Select-Object -First 1
foreach ($Expected in @("name='$AppPackage'", "versionCode='$AppCode'", "versionName='$AppVersion'")) {
    if (-not $PackageLine -or -not $PackageLine.Contains($Expected)) {
        throw "Incorrect APK identity: $PackageLine (missing $Expected)"
    }
}
& $Signer verify --verbose --print-certs $Apk
if ($LASTEXITCODE -ne 0) { throw 'Android APK signature verification failed.' }

$Hash = (Get-FileHash -Path $Apk -Algorithm SHA256).Hash.ToLowerInvariant()
"$Hash  $(Split-Path $Apk -Leaf)" | Set-Content (Join-Path $OutDir 'SHA256SUMS.txt')
Write-Host ""
Write-Host "SUCCESS: $Apk" -ForegroundColor Green
Write-Host "SHA256: $Hash"
Write-Host "Size: $([math]::Round((Get-Item $Apk).Length / 1MB, 2)) MB"
