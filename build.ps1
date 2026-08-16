param(
    [switch]$SkipLint
)

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot

if (-not $env:JAVA_HOME) {
    $androidStudioJava = 'C:\Program Files\Android\Android Studio\jbr'
    if (Test-Path -LiteralPath $androidStudioJava) {
        $env:JAVA_HOME = $androidStudioJava
    }
}

if (-not $env:ANDROID_HOME) {
    $localSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (Test-Path -LiteralPath $localSdk) {
        $env:ANDROID_HOME = $localSdk
    }
}

$tasks = if ($SkipLint) {
    @('assembleDebug')
} else {
    @('lintDebug', 'assembleDebug')
}

& (Join-Path $projectRoot 'gradlew.bat') --no-daemon @tasks
if ($LASTEXITCODE -ne 0) {
    throw "Gradle failed with exit code $LASTEXITCODE"
}

$sourceApk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
$shimSourceApk = Join-Path $projectRoot 'walletshim\build\outputs\apk\debug\walletshim-debug.apk'
$artifactDir = Join-Path $projectRoot 'build'
$artifactApk = Join-Path $artifactDir 'ColorOS-Power-Launcher-v1.1.0.apk'
$shimArtifactApk = Join-Path $artifactDir 'ColorOS-Wallet-Shim-v1.2.0.apk'
New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null
Copy-Item -LiteralPath $sourceApk -Destination $artifactApk -Force
Copy-Item -LiteralPath $shimSourceApk -Destination $shimArtifactApk -Force
Write-Output $artifactApk
Write-Output $shimArtifactApk
