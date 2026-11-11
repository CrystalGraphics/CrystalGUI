# runtime/mc/modern/forge — Agent Knowledge Base

## Target Versions

MC 1.13.2–26.3 / MinecraftForge 25–66 — a node each for 1.13.2, 1.14.3 (also 1.14.2), 1.14.4, 1.15.2 (also 1.15, 1.15.1), 1.16.5 (also 1.16.1-1.16.4), 1.17.1, 1.18.2 (also 1.18, 1.18.1), 1.19.2
(also 1.19, 1.19.1), 1.19.3, 1.19.4, 1.20.1, 1.20.2, 1.20.4, 1.20.6, 1.21.1, 1.21.3, 1.21.4, 1.21.5, 1.21.6
(also 1.21.7), 1.21.8, 1.21.10 (also 1.21.9), 1.21.11, 26.1.2 (also 26.1.1), 26.2 and 26.3, pinned in singlejar-logic's pin catalog (`catalog/modern/<branch>/<version>.properties`).
Forge 1.21 is refused (Forge 51 has no HUD event), and 26.1 (Forge 62 fails in Minecraft's own bootstrap);
Forge published nothing for 1.17, 1.20.5 or 1.21.2. Forge 64 (26.1) moved its mod-bus events onto a static
`BUS` of their own.

**Forge 25-36 (1.13.2-1.16.x)** are built by Unimined, run on Java 8, and differ once more: `fml.network`,
`fml.event.server`, `CrashReportExtender`, and key registration through `DeferredWorkQueue`. **Forge 25-27**
(1.13.2-1.14.3) also keep ticks and logins in `fml.common.gameevent` and have no `ClientPlayerNetworkEvent`,
so the client tick watches `getConnection()` come and go instead; those nodes compile against Mojang names
carried back from 1.14.4 (`CrystalGraphics/runtime/mc/modern/mappings/`).

## No class of CrystalGUI's

The one `@Mod` class for every Forge is `CrystalGUIForge` (`runtime/mc/forge-stubs`): it picks the running
version's variant, which refuses an unsupported one, and constructs nothing. CrystalGraphics reads the mod's
lifecycle listeners from its root (`runtime/mc/CLAUDE.md`). A node's `main` holds nothing: the shipped jar's
`pack.mcmeta` is generated with its descriptors (`cg-descriptors.gradle.kts`).

## Toolchain, sources and checks

Which toolchain builds a node (Unimined below 1.17, ModDevGradle `legacyForge` 1.17–1.20.1, parts
through NeoForm from 1.20.2, which has no dev run): `CrystalGraphics/docs/BUILD.md` § *Nodes and
toolchains*.

```bash
./gradlew :runtime:mc:modern:forge:<version>:extractMcSources              # into versions/<version>/build/mc-src/
./gradlew :runtime:mc:modern:forge:<version>:serverSmoke -PcgAcceptEula    # nodes with a dev run
```
