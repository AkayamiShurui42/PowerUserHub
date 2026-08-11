# Power User Hub Validation and Build Plan

## TL;DR
Validate and verify the initial implementation of the Power User Hub Android codebase. Run automated lint checks, static analysis reviews, monitor GitHub CI build pipelines, and extract compilation artifacts.

## Objective
Ensure 100% correct compile-state, codebase style compliance, architecture boundary integrity (Shizuku, su, and local socket executors), and obtain a verified debug build APK from GitHub Actions.

## Non-goals
- Do not alter the core features of the system manager.
- Do not add features outside the specified stages (1 to 14).

## Discovery
- `PowerUserHub/app/build.gradle.kts`: Configured for Jetpack Compose (Kotlin 2.2.10, AGP 9.1.1, target SDK 35).
- `PowerUserHub/app/src/main/AndroidManifest.xml`: Configured with Shizuku permissions and provider.
- `AkayamiShurui42/PowerUserHub`: Configured on GitHub. Local changes pushed to master branch. GitHub Actions run ID `3150932...` initialized.

## Decisions
- **Executor Verification:** Outsource review of the executor backend models and database helper classes to the `backend-specialist` subagent to catch structural bugs early.
- **UI & Navigation Verification:** Outsource the check of the Compose scaffolding, dynamic coloring fallback, and navigation paths to the `frontend-specialist` subagent.
- **CI Build Monitor:** Maintain a local background watch of the current GitHub Action run to catch compile-time failures and download the output APK once compiled.

## TODOs
- [ ] Task 1: Audit Gradle configurations
  - Files: `build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts`
  - RED: Verify files exist and have no syntax errors.
  - GREEN: Verify Gradle settings are aligned with JDK 17/21 target specifications.
  - Real-surface QA: Run dry-run checks via code parser subagent.
  - Evidence: `.asw/evidence/gradle_audit.txt`
  - Cleanup: None
  - Commit: NO

- [ ] Task 2: Code Review on Executor Backends & SQLite Database Helper
  - Files: `app/src/main/java/com/poweruserhub/app/service/` (Executors, Helpers)
  - RED: Run static check on executor inheritance constraints.
  - GREEN: Verify command parser supports quotes correctly and database queries close all resources.
  - Real-surface QA: Execute backend-specialist inspection script or subagent review report.
  - Evidence: `.asw/evidence/executor_audit.txt`
  - Cleanup: None
  - Commit: NO

- [ ] Task 3: Code Review on Compose UI & Themes
  - Files: `app/src/main/java/com/poweruserhub/app/ui/`
  - RED: Check navigation path route mappings.
  - GREEN: Confirm Material Theme variables are set up without hardcoded fallback colors.
  - Real-surface QA: Spawn frontend-specialist subagent to check style guidelines and accessibility hooks.
  - Evidence: `.asw/evidence/ui_audit.txt`
  - Cleanup: None
  - Commit: NO

- [ ] Task 4: Monitor GitHub Actions Build & Retrieve Build Artifacts (APK)
  - Files: `.github/workflows/build.yml`
  - RED: Run `gh run view` showing execution status.
  - GREEN: Run `gh run view` showing completion success status.
  - Real-surface QA: Download build output artifact `debug-apk` and inspect using jar/dex tools.
  - Evidence: `PowerUserHub/app-debug.apk`
  - Cleanup: Temporary zip download extraction removed.
  - Commit: NO

## Parallel Execution Waves
- **Wave 1:**
  - Task 1: Audit Gradle configurations (Planner/Self)
  - Task 2: Code Review on Executor Backends & SQLite Database Helper (Backend-Specialist Subagent)
  - Task 3: Code Review on Compose UI & Themes (Frontend-Specialist Subagent)
- **Wave 2:**
  - Task 4: Monitor GitHub Actions Build & Retrieve Build Artifacts (CI-Agent / Self)

## Dependency Matrix
| Task | Depends on | Blocks | Can parallelize with |
|---|---|---|---|
| 1 | None | 4 | 2, 3 |
| 2 | None | 4 | 1, 3 |
| 3 | None | 4 | 1, 2 |
| 4 | 1, 2, 3 | None | None |

## Final Verification Wave
- [ ] Verify local repository status is clean and all temporary assets/evidence logs are recorded inside `.asw/evidence/`.

Next: `start-work power_user_hub`
