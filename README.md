# CrystalGUI

A UI engine for Minecraft shaped like a lightweight web browser: a DOM-like `UIElement` tree, Taffy
flexbox layout, a real CSS cascade with selectors and transitions, and twelve widgets — all
loader-blind, and able to run headless on a dedicated server.

**Start here:** [`AGENTS.md`](AGENTS.md) — package map and the rules. Then, by task:
[`docs/CGUI_STYLE_RENDER_PIPELINE.md`](docs/CGUI_STYLE_RENDER_PIPELINE.md) (cascade, stylesheets,
painting) · [`docs/CGUI_WIDGETS.md`](docs/CGUI_WIDGETS.md) (the widgets) ·
[`docs/CGUI_SERVER_AND_SERIALIZATION.md`](docs/CGUI_SERVER_AND_SERIALIZATION.md) (codecs, packets,
sessions).

## Build and run

```bash
./gradlew :core:compileJava
./gradlew :core:test           # needs CrystalGraphics on the classpath
./gradlew :core:headlessTest   # deliberately without it — the server-safety guard
```

The UI runs today only in the GL debug harness; neither `mc1710` nor `mc1201` is wired into
`settings.gradle.kts` yet:

```bash
./gradlew :gl-debug-harness:runHarness --args="--mode=cgui-gallery"
```

Harness scenes stay open until you close the window. Kill lingering `java.exe` processes matching
`harness` after a run.

# VERY IMPORTANT
During development, use the `Run Client (Java 25, hotswap)` task <br>
<sub>(An IDE run configuration — not checked into this repository. Note the Gradle toolchain for
`core/` is pinned to **Java 21**, because Jabel is stable on 17 and 21 but not on 25.)</sub>


## Shadowed libraries
Shadowed libraries will also get downgraded to Java 8. 


**DO NOT** use libraries that rely on JNI *unless* their natives were compiled against Java 8.
<br>If the natives were compiled against a higher version of the Java API, there will be major problems.
<br>(Recompiling shouldn't be too big of an issue if the project is OpenSource)
