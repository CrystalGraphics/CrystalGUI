# CrystalGUI

A general platform-agnostic UI engine for shaped like a lightweight web browser: a DOM-like `UIElement` tree, Taffy
flexbox layout, a real CSS cascade with selectors and transitions, and twelve widgets — all
loader-blind, and able to run headless on a dedicated server.

**Using CrystalGUI in your mod: [`docs/CGUI_SETUP.md`](docs/CGUI_SETUP.md)** — setting the project up,
for one Minecraft version or many — then [`docs/CGUI_BUILDING_UIS.md`](docs/CGUI_BUILDING_UIS.md).

**Working on CrystalGUI itself:** [`AGENTS.md`](AGENTS.md) — package map and the rules. Then, by task:
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

Minecraft — every loader, one jar, real clients — is [`docs/CGUI_BUILD.md`](docs/CGUI_BUILD.md). For
rendering work the GL debug harness is faster:

```bash
./gradlew :gl-debug-harness:runHarness --args="--mode=cgui-gallery"
```

Harness scenes stay open until you close the window. Kill lingering `java.exe` processes matching
`harness` after a run.

# VERY IMPORTANT
During development, use the `Run Client (Java 25, hotswap)` task <br>
<sub>(An IDE run configuration — not checked into this repository. `core/` is Java 25; every consumer
below 25 gets its Java 8 copy — `docs/CGUI_BUILD.md`.)</sub>


## Shadowed libraries
Shadowed libraries will also get downgraded to Java 8. 


**DO NOT** use libraries that rely on JNI *unless* their natives were compiled against Java 8.
<br>If the natives were compiled against a higher version of the Java API, there will be major problems.
<br>(Recompiling shouldn't be too big of an issue if the project is OpenSource)

## Licence

CrystalGUI is licensed under the **GNU Lesser General Public License, version 3 or later**
(LGPL-3.0-or-later): [`COPYING.LESSER`](COPYING.LESSER), which builds on the GPL-3.0 in
[`COPYING`](COPYING). Both texts ship inside each jar under `META-INF/`, beside the third-party notice
(`notices/`, indexed by [`THIRD-PARTY.md`](THIRD-PARTY.md)).
