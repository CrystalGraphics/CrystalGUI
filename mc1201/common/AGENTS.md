# mc1201/common — Agent Knowledge Base

> ⚠️ **Not in the build.** `includeBuild("mc1201")` is commented out in the root
> `settings.gradle.kts`, so none of the commands below run until it is uncommented. The sources are
> real; the module is not currently compiled or tested by anything.

Shared MC 1.20.x platform implementation. Compiles against MC 1.20.1 + MinecraftForge 47.2.0
via `legacyForge` in `cg-mc1201-common.gradle.kts`. The compiled JAR is consumed by all three
loader subprojects (`forge`, `neoforge`, `fabric`) via the `commonOutput` configuration.

## Build

```bash
./gradlew :mc1201:common:compileJava   # compiles shared sources only
```

No loader-specific types (Forge/NeoForge/Fabric APIs) appear in this module.

## Package Guide

| Package | What it contains |
|---|---|
| `com.crystalgui.mc.platform` | `CgPlatformService1201` (the CrystalGUI-side platform bridge) and `CrystalGUI1201` (shared bootstrap) — the module's only two source files |

There is no `com.crystalgui.mc.mixin` package and no per-package `AGENTS.md` here. (An earlier version
of this table claimed both, having been copied from `CrystalGraphics/mc1201/common/AGENTS.md`, where
`MixinGameRenderer` / `MixinMinecraftShutdown` do exist. CrystalGUI's own mixin configs are per-loader
JSON in `forge/`, `neoforge/` and `fabric/`.)

## Key Design Points

- **No GL calls in constructors** — all GL work deferred to first `CgGraphicsLifecycle.onOpaquePass` call (lazy init via `onRenderFrame`)
- **Mixin AP**: provided by `legacyForge`; do NOT add a second `annotationProcessor` for Mixin in this module — it causes duplicate-AP SRG mapping errors
- **`legacyForge` not `neoForge`**: NeoForm 1.20.1 was never published; `legacyForge{version="1.20.1-47.2.0"}` is the only ModDevGradle path
