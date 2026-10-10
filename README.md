# CrystalGUI

A platform-agnostic UI engine shaped like a lightweight web browser: a DOM-like node tree with shadow
roots, flexbox and grid layout through Taffy, a real CSS cascade with selectors and transitions, and a
widget set that runs up to a code editor and a node graph. It ships for Minecraft:

- **One jar** for Forge 1.7.10–26.3, NeoForge 1.20.2–26.3 and Fabric 1.14.4–26.3, plus an optional
  language jar, beside [CrystalGraphics](https://github.com/CrystalGraphics/CrystalGraphics), the Vulkan-first
  engine that renders it.
- **Loader-blind.** The engine names no Minecraft type; each loader only wires it in.
- **Server-safe.** A dedicated server builds and sends UI trees with no GL and no fonts; the client
  lays them out and draws them.

## Using it

Start with [`docs/CGUI_SETUP.md`](docs/CGUI_SETUP.md) to add CrystalGUI to a mod, for one Minecraft
version or many. Then [`docs/CGUI_BUILDING_UIS.md`](docs/CGUI_BUILDING_UIS.md) builds your first UI.

## Working on it

```bash
git clone --recursive https://github.com/CrystalGraphics/CrystalGUI.git   # CrystalGraphics, Taffy, the harness

./gradlew :core:compileJava
./gradlew :core:test --tests "<Class>"    # with CrystalGraphics
./gradlew :core:headlessTest              # without it -- what a dedicated server has
./gradlew checkAllTargets                 # every Minecraft version compiles
```

The fastest way to see the UI is the GL debug harness: no Minecraft, a window in seconds.

```bash
./gradlew :gl-debug-harness:runHarness --args="--mode=cgui-gallery"   # every widget, a page each
./gradlew :gl-debug-harness:runHarness --args="--list"                # every scene
```

| Read | For |
|---|---|
| [`AGENTS.md`](AGENTS.md) | The package map and the rules — first, before any change |
| [`docs/CGUI_BUILD.md`](docs/CGUI_BUILD.md) | The build, the shipped jars, running real Minecraft clients |
| [`docs/CGUI_CROSS_VERSION.md`](docs/CGUI_CROSS_VERSION.md) | Code that must run on every Minecraft version and loader |
| [`docs/CGUI_STYLE_RENDER_PIPELINE.md`](docs/CGUI_STYLE_RENDER_PIPELINE.md) | The cascade, stylesheets and painting |
| [`docs/CGUI_WIDGETS.md`](docs/CGUI_WIDGETS.md) | Every widget, its parts and its states |
| [`docs/CGUI_SERVER_AND_SERIALIZATION.md`](docs/CGUI_SERVER_AND_SERIALIZATION.md) | Codecs, packets and sessions |

A library bundled into the shipped jars must run on Java 8: the jars are downgraded whole, and a JNI
library's natives must be compiled against Java 8 too.

## License

[LGPL-3.0-or-later](COPYING.LESSER), building on the [GPL-3.0](COPYING). Both texts ship in each jar
under `META-INF/`, beside the third-party notices indexed by [`THIRD-PARTY.md`](THIRD-PARTY.md).

## Hosting

[![OSS hosting by Cloudsmith](https://img.shields.io/badge/OSS%20hosting%20by-cloudsmith-blue?logo=cloudsmith&style=flat-square)](https://cloudsmith.com)

Maven artifacts are hosted for free by [Cloudsmith](https://cloudsmith.com).
