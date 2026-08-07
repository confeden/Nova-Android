# Nova Android Agent Rules

- Diagnostic, verification, benchmark, one-off migration, and helper scripts must be created under `tools/`.
- Do not place temporary scripts, generated reports, pulled APKs, logs, screenshots, or scratch files in the project root or under `app/src`.
- Files that are not part of the active source tree and are not reusable tools belong under `old/`.
- Reusable utilities and test/diagnostic helpers that are not app source belong under `tools/`.
- Do not read `app/src/main/java/com/example/nova/NovaVpnService.kt` or `app/src/main/java/com/example/nova/ClientData.kt` end to end. Use targeted search and small line ranges.
- Do not move or delete source, Gradle, assets, native libraries, keystore, or workspace configuration files unless the user explicitly requests that exact path.
