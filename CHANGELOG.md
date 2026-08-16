# Changelog

## 1.2.0 - 2026-08-16

- Add a source-built `com.heytap.wallet` shim that authenticates and forwards the
  ColorOS Wallet shortcut to Google Wallet's explicit quick-draw activity.
- Add a persistent, content-blind accessibility fallback for the exact
  `com.heytap.tas` quick-launch activity used by PMA120 Chinese firmware.
- Dismiss the OEM activity before forwarding, wait for the normal keyguard
  transition, and use short state-aware duplicate filtering to prevent loops
  without delaying separate gestures.
- Restore only the shim's Accessibility entry at boot after ColorOS clears it,
  using an install-time ADB grant that persists without a computer.
- Add a non-streaming installer that selects the OEM Wallet value and stops the
  legacy logcat monitor, grants boot repair, and preserves other enabled
  Accessibility services.
- Remove unsuccessful `com.finshell.wallet` and OEM-signature provider
  experiments from the distributable project.
- Document installation, package-name compatibility, privacy boundaries,
  persistence, and exact rollback steps.

## 1.1.0 - 2026-08-14

- Rename the package to `com.nielk74.colorospowerlauncher` for the public, app-agnostic release.
- Note that v1.1.0 is a separate Android app and the old v1.0.0 package should be uninstalled.
- Clarify that the APK can be installed directly and ADB is needed only for the one-time `READ_LOGS` grant.
- Explain briefly why power-button detection needs the permission and why ColorOS system optimization is disabled temporarily.

## 1.0.0 — 2026-08-14

- Turn the working ChatGPT prototype into a general installed-app picker.
- Preserve the fast `USER_PRESENT` handoff after secure unlock.
- Add setup diagnostics for device logs, overlays, notifications, and full-screen launches.
- Add test launching, app search, light/dark themes, and accessible selection rows.
- Package the project as a standard dependency-free Android Gradle app.
