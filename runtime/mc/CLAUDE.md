# `runtime/mc` — the Minecraft hosts

> Loads itself: Claude Code reads this file the first time an agent reads any file in this folder or below. Moved verbatim from [`AGENTS.md`](../../AGENTS.md), which keeps the rules every session needs.

## A loader module declares a mod, and nothing else

**This is the rule for everything under `runtime/mc/`.** CrystalGUI holds no code per loader or per Minecraft
version, like the language mod (`docs/CGUI_LANGUAGE_STACK.md` § 1a). Everything starts from `core`'s CrystalGraphics
lifecycle listeners, declared in `core/src/main/resources/META-INF/crystalgraphics/listeners`, and reads the game
through CrystalGraphics' services. A loader only has to see a mod, so CrystalGraphics reads that file from its root:

| Loader | What makes CrystalGUI a mod |
|---|---|
| Forge 1.7.10–26.3 | `runtime/mc/forge-stubs`'s `CrystalGUIForge`, carrying both FML 1.7.10's `@Mod` and later Forge's: `ForgeStart` picks the running version's variant, which refuses an unsupported one, and constructs nothing (on 1.7.10, `acceptedMinecraftVersions` checks) |
| NeoForge | `runtime/mc/forge-stubs`'s `CrystalGUINeoForge`: `NeoForgeStart`, the same |
| Fabric | the generated `fabric.mod.json`, with empty entrypoint lists |

What used to be per loader, and where it is now:

| Job | Owner |
|---|---|
| The desktop: `CgUiScreen.install()`, its key, paint stages, input and screen | `CgUiClientLifecycle.onPreInit` (a `client` listener), and its tick at `onFrame`. `CgUiScreen` owns the rest: what opens, the frame clock, input through CrystalGraphics' `SCREEN` stage (`CgUiScreenInput`), its screen (`CgScreens`), and every host answer from `CgGameDirectory`, `CgGraphicsLifecycle`'s size, `CgI18n`, `CgNetwork` and `CgGameThreads` |
| The protocols, the workspace, the gauntlet's server and connection tests | `CgUiCommonLifecycle` (a `common` listener) |
| Service providers across mod files (ModLauncher 5 lists no resource of another mod) | `Providers`, over `CgModLoader.roots()` |
| Which variant ran | `VariantBootstrap`'s log line, and CrystalGraphics' crash-report section |

**Code a loader would need belongs to CrystalGraphics**: a service in its `platform`, answered by each era's host.
A decision made in one loader is a decision the other loader got wrong, and nothing can see it: each copy is
internally consistent, so no test fails and no guard fires.

> **Packages: every loader tree owns a segment under `com.crystalgui.mc`, and none of them owns the
> root.** `com.crystalgui.mc.v1710`, `com.crystalgui.mc.modern`, and `com.crystalgui.mc.forge` / `.neoforge` /
> `.fabric` for the mod classes. 1.7.10 sat at `com.crystalgui.mc` itself until 2026-09-16: any relocation rule
> anchored at `com/crystalgui/mc/` then rewrote 1.7.10 too.
>
> **What a rename here does not reach**: **the class strings in `cg-descriptors.gradle.kts`**, the merged jar's variant table. `checkSingleJar`'s `requiredEntries` names the same
> classes, so it fails first and on a laptop. Keep those two lists in step.
