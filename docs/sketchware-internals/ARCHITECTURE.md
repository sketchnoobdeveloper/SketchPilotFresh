# Sketchware Pro internals — architecture

Evidence baseline: `Sketchware-Pro/Sketchware-Pro`, commit `2c721f04580c74fd42197130b7c1becb6c00ca53`.
Host declared: `applicationId=pro.sketchware`, versionName `v7.1.0`, versionCode `150`.

Verified source responsibilities:

| Class | Responsibility |
|---|---|
| `a.a.a.ProjectBuilder` | resource/Java/Kotlin compilation, dexing, APK packaging, debug signing |
| `a.a.a.Ix` | AndroidManifest generation |
| `a.a.a.Jx` | Activity Java source generation |
| `a.a.a.Lx` | component/listener source and exported Gradle content |
| `a.a.a.Ox` | layout XML generation |
| `a.a.a.yq` | generated project paths (`mysc/<id>/...`) |
| `a.a.a.wq` | storage root helpers (`.sketchware/...`) |
| `com.besome.sketch.design.DesignActivity` | active editor lifecycle; gets `sc_id` from intent |

`qq` was not present in this source snapshot; library handling is in
`pro.sketchware.util.library.BuiltInLibraryManager` and `mod.jbk.build.BuiltInLibraries`.

The module never assumes these names on another release; they belong in a
version-specific adapter with target validation.
