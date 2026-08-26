# Pixel 17 Shade Standalone

This branch is the standalone replacement-shade application track. It is intentionally isolated from the normal PowerUserHub branch and will be reduced to a single-purpose APK.

Target app responsibilities:
- replacement notification shade UI
- top-edge trigger and gesture service
- NotificationListenerService
- AccessibilityService
- Material adaptive / manual / hybrid theming
- horizontal brightness gesture
- Shizuku / Shizuku+ authorization and privileged backend
- in-app permission setup
- stock shade suppression controls where supported

PowerUserHub-only explorers, locks, monitor, and developer tools are not part of the final standalone APK.
