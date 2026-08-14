param(
    [string]$Serial,
    [string]$ApkPath = (Join-Path $PSScriptRoot 'build\ColorOS-Power-Launcher-v1.0.0.apk')
)

$ErrorActionPreference = 'Stop'
$packageName = 'com.antoine.chatgptpower'
$adbCommand = Get-Command adb -ErrorAction SilentlyContinue
$adb = if ($adbCommand) {
    $adbCommand.Source
} else {
    Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
}

if (-not (Test-Path -LiteralPath $adb)) {
    throw 'ADB was not found. Install Android SDK Platform Tools or add adb to PATH.'
}
if (-not (Test-Path -LiteralPath $ApkPath)) {
    throw "APK not found: $ApkPath. Run .\build.ps1 first or pass -ApkPath."
}

$deviceArgs = if ($Serial) { @('-s', $Serial) } else { @() }
& $adb @deviceArgs install -r $ApkPath
if ($LASTEXITCODE -ne 0) {
    throw "APK installation failed with exit code $LASTEXITCODE"
}

& $adb @deviceArgs shell pm grant $packageName android.permission.READ_LOGS
$grantExitCode = $LASTEXITCODE
if ($grantExitCode -ne 0) {
    Write-Warning 'READ_LOGS was not granted. Temporarily disable ColorOS permission monitoring/system optimization, rerun the pm grant command from the README, then restore the setting.'
}

& $adb @deviceArgs shell am start -n "$packageName/.MainActivity"
if ($LASTEXITCODE -ne 0) {
    throw "Could not open ColorOS Power Launcher (exit code $LASTEXITCODE)"
}

Write-Output 'Installed. Finish the setup cards on the phone and approve one-time device-log access.'
