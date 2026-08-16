# ColorOS Power Launcher

A small offline Android utility that lets you choose the app opened by a physical power-button double-press on ColorOS. It was created for OPPO firmware that hard-wires the gesture to Wallet and does not expose a normal app picker.

![ColorOS Power Launcher app picker](docs/power-launcher.png)

## Recommended Find X9 Ultra Google Wallet bridge

The repository includes a minimal second app in `walletshim/`. It waits for the
normal secure unlock when necessary and opens Google Wallet's explicit
`QUICKDRAW` activity. Firmware that launches the shim directly uses Android's
system biometric/device-credential prompt while the phone is locked.

ColorOS currently has two relevant implementations:

- Some builds directly launch the absent `com.heytap.wallet` package. The bridge
  uses that package name and receives the shortcut directly.
- On the tested Chinese OPPO PMA120 build `PMA120_16.0.9.402(CN01)`, the power
  policy instead launches the preinstalled
  `com.heytap.tas/com.nearme.wallet.nfc.ui.NfcConsumeActivity`. For this route,
  the bridge includes a persistent accessibility redirect. It dismisses that
  one OEM activity before opening the bridge, with short duplicate-event
  filtering so separate presses remain responsive.

The redirect receives only window-state events from `com.heytap.tas`, checks the
exact activity class above, and declares `canRetrieveWindowContent=false`. It
cannot inspect screen contents and neither app has network permission. This
route needs no device-log access, overlay, foreground service, or root. A small
boot receiver restores only this service entry because the tested ColorOS build
removes it during restart.

### Install

Install Android SDK Platform Tools and enable Wireless debugging on the phone.
From the [v1.2.0 release](https://github.com/Nielk74/coloros-power-button-launcher/releases/tag/v1.2.0),
download these two files into the same folder:

- `ColorOS-Wallet-Shim-v1.2.0.apk`
- `install-wallet-shim.ps1`

Then run:

```powershell
.\install-wallet-shim.ps1 -Serial '<device-ip>:<port>'
```

To build from source instead, run `build.ps1` before the same installer command.

The installer uses a non-streaming ADB transfer because ColorOS can otherwise
leave its confirmation screen open after the temporary APK has expired and show
a misleading **Problem parsing the package** error. It then:

1. Installs the source-built `com.heytap.wallet` shim.
2. Grants `WRITE_SECURE_SETTINGS` through ADB's install-time grant so the shim
   can restore its own Accessibility entry after a restart.
3. Stops the legacy logcat monitor so both routes cannot launch at once.
4. Sets ColorOS's `double_tap_power_button_value` to `1` (Wallet). On the tested
   firmware, `0` is Camera, `1` is Wallet, `2` is Assistant, and `3` is Quick app.
5. Adds the redirect to the enabled Accessibility-service list without replacing
   any existing service.

No manual Accessibility step is required when using the installer. Double-press
the physical power button and authenticate normally when the phone is locked.

The tested PMA120 firmware removes third-party Accessibility entries during a
restart. The boot receiver adds back only
`com.heytap.wallet/.WalletRedirectAccessibilityService`, preserving every other
enabled service. The ADB-granted permission and the repair both persist; the
bridge does not depend on a computer after setup.

The standard Android `double_tap_power_button_gesture` setting is not changed.

To remove the bridge and its secure-settings grant, run:

```text
adb uninstall com.heytap.wallet
```

To select Camera instead of Wallet and optionally return to the legacy monitor:

```text
adb shell settings put secure double_tap_power_button_value 0
adb shell am start -n com.nielk74.colorospowerlauncher/.MainActivity
```

`com.heytap.wallet` is an OPPO-owned package name. This shim is intended for
personal sideloading on firmware where that package is absent. Uninstall it before
installing an official OPPO Wallet package that uses the same name.

Do not rename the bridge to `com.finshell.wallet`. On the tested firmware that
package is marketplace-managed and ColorOS may substitute the official Chinese
FinShell Wallet. If the official FinShell app is already installed, disable or
remove it first; ColorOS gives it priority over the `com.heytap.tas` fallback.

## Features

- Chooses any installed launchable app, with search and a one-tap test button.
- Opens the selected app immediately when the phone is already unlocked.
- Requests authentication first when the secure lock screen is showing, then opens the app as soon as ColorOS completes the unlock transition.
- Starts its monitor after app updates and phone restarts.
- Supports Android light and dark themes, large touch targets, and screen-reader labels.
- The compatibility monitor uses no network permission, analytics, ads, root,
  or Accessibility service.

## Compatibility-monitor requirements

- Android 12 or newer (`minSdk 31`).
- OPPO, OnePlus, or realme firmware with the same ColorOS power-policy log format.
- The built-in power-button double-press Wallet shortcut enabled in ColorOS.
- A one-time ADB command to grant `READ_LOGS`. ADB is not required to install the APK.

This project was developed and tested on an OPPO PMA120 running Android 16 / ColorOS 16.1. Other devices and firmware versions may use a different policy log tag or may block the background handoff.

## Install the compatibility monitor

1. Download the APK from the [latest GitHub release](https://github.com/Nielk74/coloros-power-button-launcher/releases/latest).
2. Install it normally by opening the APK on your phone. **You do not need ADB to install the app.**
3. Enable USB or wireless debugging and connect the phone to a computer with [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools).
4. In ColorOS **Developer options**, temporarily turn on **Disable system optimization**. This only allows the ADB permission command below to work; it does not grant the permission itself.
5. Run:

   ```text
   adb shell pm grant com.nielk74.colorospowerlauncher android.permission.READ_LOGS
   ```

6. Turn **Disable system optimization** back off immediately. It does not need to remain enabled.
7. Open **ColorOS Power Launcher**, finish the setup cards, select an app, and approve Android's device-log dialog.
8. Keep ColorOS's built-in Wallet double-press shortcut enabled.

If you installed v1.0.0, uninstall it first. The cleaner package name in v1.1.0 makes Android treat it as a separate app, and running both versions would start two power-button monitors.

Installing with ADB is optional. If preferred, the included PowerShell helper installs the APK, grants the permission, and opens the app:

```powershell
.\install.ps1 -Serial '<device-ip>:<port>'
```

After a phone restart, tap the persistent **Power shortcut active** notification and choose **Allow** in Android's device-log dialog again. That one-time approval lasts while the foreground monitor process remains alive.

### Why is `READ_LOGS` needed?

Android does not let a normal app receive global power-button presses, and ColorOS does not provide an app picker for this shortcut. Power Launcher detects the two physical presses from a narrowly filtered ColorOS system log. Android treats access to that log as a development permission, so the APK cannot grant it to itself and the one-time ADB command is required.

### Removing the ColorOS "wants to open" prompt

When Power Launcher opens the selected app for the first time, ColorOS may ask whether it should be allowed. Choosing **Allow for 30 days** suppresses the prompt for that app pair for 30 days.

On firmware without a permanent per-app option, the confirmations can be disabled system-wide with ADB:

```text
adb shell settings put global app_start_confirm_rus_enable 0
```

This disables ColorOS's unexpected-app-opening warning for every app, not only Power Launcher. Restore the protection at any time with:

```text
adb shell settings put global app_start_confirm_rus_enable 1
```

## Why Wallet must stay enabled

On the tested firmware, changing Android's standard camera double-tap setting does not change the OEM action. The Wallet shortcut is the part of ColorOS that keeps the first power press from immediately locking the screen and supplies the native multi-press window. Power Launcher detects the two real hardware key-downs and brings the selected app forward after the second press.

Wallet can flash briefly before the selected app on some firmware. The helper minimizes that interval, but removing Wallet entirely also removes the OEM delay that makes an unlocked double-press possible.

## Privacy and security

The compatibility monitor starts `logcat` with an explicit allow-list for only this ColorOS tag:

```text
KEYLOG_PhoneWindowManagerExtImpl
```

It accepts only non-synthetic `KEYCODE_POWER` down events and ignores `deviceId=-1`. The display-over-apps permission is used for a transparent, non-touchable 1×1-pixel window that helps Android authorize the activity handoff. No log content or app choice leaves the device; neither application manifest has internet permission.

The Wallet bridge's `WRITE_SECURE_SETTINGS` grant is used only to append its own
Accessibility component after ColorOS removes it at boot. It does not replace
other enabled services. Uninstalling `com.heytap.wallet` removes the grant.

## Build

The app has no third-party runtime dependencies. Install Android SDK 36 and JDK 17, then run:

```powershell
.\build.ps1
```

Or use Gradle directly:

```powershell
.\gradlew.bat lintDebug assembleDebug
```

The convenience script writes both:

- `build\ColorOS-Power-Launcher-v1.1.0.apk`
- `build\ColorOS-Wallet-Shim-v1.2.0.apk`

Public release APKs are signed separately; no signing credentials are stored in this repository.

## Known limitations

- Compatibility depends on an undocumented ColorOS log tag and may break after an OEM update.
- The persistent foreground service and one-time device-log approval are required for reliable detection.
- Locked launches always respect Android authentication; the app does not bypass the keyguard.
- Reinstalling the Wallet bridge without the installer may omit the boot-repair
  grant; rerun `install-wallet-shim.ps1` after such an update.
- Some ColorOS builds may show Wallet briefly or need battery/background-activity allowances adjusted manually.
- The direct Wallet bridge works only where `com.heytap.wallet` is available;
  the scoped fallback currently recognizes the tested `com.heytap.tas`
  quick-launch activity.
- If an OEM update changes its wallet package or activity, the redirect may need
  a corresponding update.

This is an independent utility and is not affiliated with OPPO, OnePlus, realme, Google, OpenAI, or any selected app vendor.
