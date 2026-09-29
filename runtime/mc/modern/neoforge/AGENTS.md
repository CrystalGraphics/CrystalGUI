# runtime/mc/modern/neoforge — Agent Knowledge Base

## Target versions

**MC 1.20.2–26.2 / NeoForge**, a node per `versions/<version>`: 1.20.2, 1.20.3, 1.20.4, 1.20.6 (also
1.20.5), 1.21.1 (also 1.21), 1.21.3 (also 1.21.2), 1.21.4, 1.21.5, 1.21.6, 1.21.8 (also 1.21.7), 1.21.10
(also 1.21.9), 1.21.11, 26.1.2 (also 26.1, 26.1.1) and 26.2. NeoForge published nothing for 1.20.1;
1.21.2, 1.21.6, 1.21.7, 1.21.9, 26.1 and 26.1.1 run its only builds, betas.

## The loader is registration only

`NeoForgeBootstrap` is the one `@Mod` class for every node, and constructs the running version's
`CrystalGUINeoForge` (a `VariantEntry`). Its `Events` inner class registers every listener on
`NeoForge.EVENT_BUS` by hand, and its `Network` inner class is the payload-based transport.
`LanguageNeoForgeBootstrap` (`src/lang`) is the language mod's own `@Mod`.

The engine's own render, reload and shutdown hooks are **not** here: CrystalGraphics ships as its own
mod and owns them. Everything this loader forwards to lives in the common branch's `LifecycleCrystalGUI`.

## Toolchain, sources and checks

1.20.2 and 1.20.3 are built from parts through NeoForm and have no dev run; from 1.20.4 it is
ModDevGradle. Pins: `CrystalGraphics/docs/BUILD.md` § *Nodes and toolchains*.

```bash
./gradlew :runtime:mc:modern:neoforge:<version>:extractMcSources              # into versions/<version>/build/mc-src/
./gradlew :runtime:mc:modern:neoforge:<version>:serverSmoke -PcgAcceptEula    # 1.20.4 onward
```
