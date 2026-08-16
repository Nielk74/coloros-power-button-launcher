param(
    [string]$Serial,
    [string]$ApkPath
)

$ErrorActionPreference = 'Stop'
$shimPackage = 'com.heytap.wallet'
$legacyPackage = 'com.nielk74.colorospowerlauncher'
$selectorSetting = 'double_tap_power_button_value'
$redirectService = 'com.heytap.wallet/com.heytap.wallet.WalletRedirectAccessibilityService'
$secureSettingsPermission = 'android.permission.WRITE_SECURE_SETTINGS'
$apkFileName = 'ColorOS-Wallet-Shim-v1.2.0.apk'
$adbCommand = Get-Command adb -ErrorAction SilentlyContinue
$adb = if ($adbCommand) {
    $adbCommand.Source
} else {
    Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
}

if (-not (Test-Path -LiteralPath $adb)) {
    throw 'ADB was not found. Install Android SDK Platform Tools or add adb to PATH.'
}
if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    $builtApk = Join-Path $PSScriptRoot "build\$apkFileName"
    $downloadedApk = Join-Path $PSScriptRoot $apkFileName
    $ApkPath = if (Test-Path -LiteralPath $builtApk) { $builtApk } else { $downloadedApk }
}
if (-not (Test-Path -LiteralPath $ApkPath)) {
    throw "APK not found: $ApkPath. Build it or download $apkFileName beside this script."
}

$deviceArgs = if ($Serial) { @('-s', $Serial) } else { @() }
$previousValue = ((& $adb @deviceArgs shell settings get secure $selectorSetting) | Out-String).Trim()
if ($LASTEXITCODE -ne 0) {
    throw 'Could not read the current ColorOS double-power selector.'
}

# ColorOS can lose a streamed ADB staging file while its confirmation UI is open.
# A complete push before installation avoids the misleading parsing error.
& $adb @deviceArgs install --no-streaming -r -g -t $ApkPath
if ($LASTEXITCODE -ne 0) {
    throw "Wallet shim installation failed with exit code $LASTEXITCODE"
}

# ColorOS removes this third-party accessibility entry at boot. The narrowly scoped shim uses
# this install-time grant only to add its own entry back, without replacing other services.
$packageDump = ((& $adb @deviceArgs shell dumpsys package $shimPackage) | Out-String)
if ($packageDump -notmatch "(?m)^\s*$([regex]::Escape($secureSettingsPermission)): granted=true\s*$") {
    throw 'The shim was installed, but its reboot-repair permission was not granted.'
}

& $adb @deviceArgs shell am force-stop $legacyPackage
if ($LASTEXITCODE -ne 0) {
    Write-Warning 'Could not stop the legacy monitor; disable it manually to avoid two launches.'
}

& $adb @deviceArgs shell settings put secure $selectorSetting 1
if ($LASTEXITCODE -ne 0) {
    throw 'The shim was installed, but the ColorOS Wallet selector could not be enabled.'
}

$installedPath = ((& $adb @deviceArgs shell pm path $shimPackage) | Out-String).Trim()
$activeValue = ((& $adb @deviceArgs shell settings get secure $selectorSetting) | Out-String).Trim()
if (-not $installedPath -or $activeValue -ne '1') {
    throw 'Post-install verification failed. Check the package and selector manually.'
}

$finshellPath = ((& $adb @deviceArgs shell pm path com.finshell.wallet) | Out-String).Trim()
if ($finshellPath) {
    Write-Warning 'Official FinShell Wallet is installed. Disable or remove it because ColorOS gives it priority over this redirect.'
}

Write-Output "Installed $shimPackage and selected ColorOS Wallet mode."
Write-Output "Previous $selectorSetting value: $previousValue"

$enabledServices = ((& $adb @deviceArgs shell settings get secure enabled_accessibility_services) | Out-String).Trim()
if ($enabledServices -eq 'null') {
    $enabledServices = ''
}
$enabledServiceEntries = @($enabledServices -split ':' | Where-Object { $_ })
if ($enabledServiceEntries -notcontains $redirectService) {
    $updatedServices = (@($enabledServiceEntries) + $redirectService) -join ':'
    & $adb @deviceArgs shell settings put secure enabled_accessibility_services $updatedServices
    if ($LASTEXITCODE -ne 0) {
        throw 'The redirect service could not be enabled.'
    }
}
& $adb @deviceArgs shell settings put secure accessibility_enabled 1
if ($LASTEXITCODE -ne 0) {
    throw 'Android accessibility could not be enabled.'
}

$verifiedServices = ((& $adb @deviceArgs shell settings get secure enabled_accessibility_services) | Out-String).Trim()
if (@($verifiedServices -split ':') -notcontains $redirectService) {
    throw 'The redirect service was not present after installation.'
}
Write-Output 'The persistent Google Wallet redirect is enabled, including boot-time self-repair.'

Write-Output 'Double-press the physical power button and authenticate to open Google Wallet.'
Write-Output 'Rollback commands are documented in README.md.'
