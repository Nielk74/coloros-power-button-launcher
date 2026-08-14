# Changelog

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
