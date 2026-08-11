# Power User Hub

**Power User Hub** is a premium, modern system manager for Android (specifically optimized for OxygenOS and devices like the OnePlus 15) built using **Kotlin**, **Jetpack Compose**, **Material 3 (M3)**, and **Shizuku**. 

It provides an elegant graphical user interface (GUI) to view, edit, and enforce Android settings databases, inspect packages and intent entrypoints, restrict background applications, and run developer shell operations—without requiring native root access on the device.

---

## Architecture Overview

```
                  ┌──────────────────────────────────┐
                  │          POWER USER HUB          │
                  │         (Compose M3 UI)          │
                  └────────────────┬─────────────────┘
                                   │
                    ┌──────────────┼──────────────┐
                    ▼              ▼              ▼
              [Settings]      [Packages]    [Shell Console]
                    │              │              │
                    └──────────────┼──────────────┘
                                   │
                                   ▼
                        ┌──────────────────────┐
                        │     ShellService     │
                        └──────────┬───────────┘
                                   │
              ┌────────────────────┼────────────────────┐
              ▼                    ▼                    ▼
     [ShizukuExecutor]      [RootExecutor]       [AdbExecutor]
      (via Binder IPC)       (via /system/bin)   (via TCP socket)
```

---

## Features

### 1. Overview (Dashboard)
*   **System Engine Detection:** Real-time checking for Shizuku binder authorizations and Root (su) command binaries.
*   **Device Info Summary:** Renders standard hardware manufacturer details, API target levels, and display build targets.
*   **Stats & Actions:** Quick counts of enforced rules and system details.

### 2. Settings Explorer
*   **Database Browsing:** Tabbed interfaces for settings namespaces (`GLOBAL`, `SECURE`, and `SYSTEM`).
*   **Interactive Editing:** Direct input modals to modify configuration databases on live devices.
*   **Durable State Lock:** Write rules to our local SQLite database to prevent third-party overlays or OS processes from resetting values.

### 3. Application Explorer
*   **App Listing:** Packages sorted and filtered into User, System, Enabled, or Disabled lists.
*   **Component Inspection:** Expandable views showing all declared Manifest components:
    *   Activities
    *   Services
    *   Broadcast Receivers
    *   Content Providers
    *   Requested System Permissions
*   **Opt-In Policies:** Check and override standby behaviors:
    *   Exempt apps from Doze Mode (Battery Optimization).
    *   Toggle `RUN_IN_BACKGROUND` runtime parameters (AppOps).
    *   Assign apps to Standby Buckets (`active`, `working_set`, `frequent`, `rare`, `restricted`).

### 4. Locked Settings Monitor
*   **SQLite DB Store:** Keeps a record of target configuration values.
*   **Background Worker:** A WorkManager background daemon (`LockEnforcementWorker`) periodically compares database rules with current setting values and attempts automatic restorations if any value diverged.
*   **Log Verification:** Outputs status results (e.g. `Verified`, `Restored`, `Failed (OS Protected)`) and verification timestamps.

### 5. Service & Process Monitor
*   **Process Enumeration:** Discovers active process PIDs and packages system-wide using privileged `ps -A` checks.
*   **Label Mapping:** Maps PIDs back to installed friendly app labels using `PackageManager`.

### 6. Developer Console (Mode)
*   **Shell Terminal:** Direct text input console to execute raw commands (like `dumpsys`, `am`, `pm`, `settings`).
*   **Command Presets:** Buttons to immediately run and learn from common query patterns.

---

## Technical Specifications

*   **Min SDK:** API 26 (Android 8.0 Oreo)
*   **Compile SDK:** API 35 (Android 15)
*   **Target SDK:** API 35
*   **Tooling:** Gradle 9.3, Android Gradle Plugin (AGP) 9.1.1, Java 17/21 compatible.
*   **Frameworks:** Jetpack Compose, WorkManager, Rikka Shizuku API (Client binder integration).

---

## Build Configuration

This project is configured with a GitHub Actions workflow (`.github/workflows/build.yml`) that compiles the app, signs a debug build, runs check gates, and uploads the built `.apk` artifact on every push or pull request to the `main` or `master` branches.
