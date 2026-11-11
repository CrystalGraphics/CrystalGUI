# runtime/mc/modern/neoforge — Agent Knowledge Base

## Target versions

**MC 1.20.2–26.3 / NeoForge**, a node per `versions/<version>`: 1.20.2, 1.20.3, 1.20.4, 1.20.6 (also
1.20.5), 1.21.1 (also 1.21), 1.21.3 (also 1.21.2), 1.21.4, 1.21.5, 1.21.6, 1.21.8 (also 1.21.7), 1.21.10
(also 1.21.9), 1.21.11, 26.1.2 (also 26.1, 26.1.1), 26.2 and 26.3 (a beta, its only builds). NeoForge published nothing for 1.20.1;
1.21.2, 1.21.6, 1.21.7, 1.21.9, 26.1 and 26.1.1 run its only builds, betas.

## No class of CrystalGUI's

The one `@Mod` class for every NeoForge is `CrystalGUINeoForge` (`runtime/mc/forge-stubs`), compiled once: `NeoForgeStart`
picks the running version's variant, which refuses an unsupported one, and constructs nothing. CrystalGraphics reads
the mod's lifecycle listeners from its root (`runtime/mc/CLAUDE.md`). `CrystalGUILangNeoForge`, beside it, is the
language mod's own `@Mod`.

## Toolchain, sources and checks

1.20.2 and 1.20.3 are built from parts through NeoForm and have no dev run; from 1.20.4 it is
ModDevGradle. Pins: `CrystalGraphics/docs/BUILD.md` § *Nodes and toolchains*.

```bash
./gradlew :runtime:mc:modern:neoforge:<version>:extractMcSources              # into versions/<version>/build/mc-src/
./gradlew :runtime:mc:modern:neoforge:<version>:serverSmoke -PcgAcceptEula    # 1.20.4 onward
```
