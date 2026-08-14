# ColorOS Power Launcher

A small offline Android utility that lets you choose the app opened by a physical power-button double-press on ColorOS. It was created for OPPO firmware that hard-wires the gesture to Wallet and does not expose a normal app picker.

![ColorOS Power Launcher app picker](docs/power-launcher.png)

## Features

- Chooses any installed launchable app, with search and a one-tap test button.
- Opens the selected app immediately when the phone is already unlocked.
- Requests authentication first when the secure lock screen is showing, then opens the app as soon as ColorOS completes the unlock transition.
- Starts its monitor after app updates and phone restarts.
- Supports Android light and dark themes, large touch targets, and screen-reader labels.
- Uses no network permission, analytics, ads, root, or Accessibility service.

## Requirements

- Android 12 or newer (`minSdk 31`).
- OPPO, OnePlus, or realme firmware with the same ColorOS power-policy log format.
- The built-in power-button double-press Wallet shortcut enabled in ColorOS.
- A one-time ADB grant for `READ_LOGS`.

This project was developed and tested on an OPPO PMA120 running Android 16 / ColorOS 16.1. Other devices and firmware versions may use a different policy log tag or may block the background handoff.

## Install

Download the APK from the [latest GitHub release](https://github.com/Nielk74/coloros-power-button-launcher/releases/latest), or build it locally.

On the tested ColorOS build, the normal ADB grant is blocked while the OEM permission monitor is active:

1. In **Developer options**, temporarily enable the setting that disables ColorOS permission monitoring. On the tested phone it is labeled **Disable system optimization**.
2. Install the APK and grant log access:

   ```powershell
   adb install -r .\ColorOS-Power-Launcher-v1.0.0.apk
   adb shell pm grant com.antoine.chatgptpower android.permission.READ_LOGS
   ```

3. Immediately restore **Disable system optimization** to its previous/off state. It does not need to remain enabled.
4. Open **ColorOS Power Launcher**. Complete any setup card for display-over-apps, notifications, or locked-screen full-screen notifications.
5. Choose the target app and approve Android's one-time device-log dialog.
6. Keep ColorOS's built-in Wallet double-press shortcut enabled.

The included PowerShell helper performs the install, ADB grant, and app launch:

```powershell
.\install.ps1 -Serial '<device-ip>:<port>'
```

After a phone restart, tap the persistent **Power shortcut active** notification and choose **Allow** in Android's device-log dialog again. That one-time approval lasts while the foreground monitor process remains alive.

## Why Wallet must stay enabled

On the tested firmware, changing Android's standard camera double-tap setting does not change the OEM action. The Wallet shortcut is the part of ColorOS that keeps the first power press from immediately locking the screen and supplies the native multi-press window. Power Launcher detects the two real hardware key-downs and brings the selected app forward after the second press.

Wallet can flash briefly before the selected app on some firmware. The helper minimizes that interval, but removing Wallet entirely also removes the OEM delay that makes an unlocked double-press possible.

## Privacy and security

`READ_LOGS` is a powerful Android development permission, so the monitor starts `logcat` with an explicit allow-list for only this ColorOS tag:

```text
KEYLOG_PhoneWindowManagerExtImpl
```

It accepts only non-synthetic `KEYCODE_POWER` down events and ignores `deviceId=-1`. The display-over-apps permission is used for a transparent, non-touchable 1×1-pixel window that helps Android authorize the activity handoff. No log content or app choice leaves the device; the manifest has no internet permission.

The internal package ID remains `com.antoine.chatgptpower` so people using the original ChatGPT prototype can install this as an update without losing existing grants.

## Build

The app has no third-party runtime dependencies. Install Android SDK 36 and JDK 17, then run:

```powershell
.\build.ps1
```

Or use Gradle directly:

```powershell
.\gradlew.bat lintDebug assembleDebug
```

The convenience script writes `build\ColorOS-Power-Launcher-v1.0.0.apk`. Public release APKs are signed separately; no signing credentials are stored in this repository.

## Known limitations

- Compatibility depends on an undocumented ColorOS log tag and may break after an OEM update.
- The persistent foreground service and one-time device-log approval are required for reliable detection.
- Locked launches always respect Android authentication; the app does not bypass the keyguard.
- Some ColorOS builds may show Wallet briefly or need battery/background-activity allowances adjusted manually.

This is an independent utility and is not affiliated with OPPO, OnePlus, realme, Google, OpenAI, or any selected app vendor.
