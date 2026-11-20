# Compatibility matrix

| Component | Value | Status |
|---|---|---|
| Host package | `pro.sketchware` | Confirmed from source |
| Host version | v7.1.0 / 150 | Source baseline only |
| LSPosed API | legacy API 82 entry | Compiling; device unverified |
| libxposed API 102 | not yet integrated | Needs installed-framework check |
| Android | minSdk 26, compileSdk 34 | CI verified at build level |

Status meanings:

- **Source confirmed**: read from the pinned source tree.
- **CI verified**: compiled/tested by GitHub Actions.
- **Device unverified**: requires an actual rooted device with LSPosed and the host APK.

No hook is considered working until verified on a device. All entry hooks are
wrapped so a missing class or changed signature cannot crash Sketchware.
