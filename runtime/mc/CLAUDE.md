# `runtime/mc` — the Minecraft hosts

> Loads itself: Claude Code reads this file the first time an agent reads any file in this folder or below. Moved verbatim from [`AGENTS.md`](../../AGENTS.md), which keeps the rules every session needs.

## Loader modules: wiring, never logic

**This is the rule for everything under `runtime/mc/`.** A loader module says how *this* Minecraft
spells something; it decides nothing. The seams it answers, all in `core/`:

| Seam | A loader answers |
|---|---|
| `desktop.host.HostServices` | where the game directory is, how big the surface is, the locale, the connection, how to give the current screen a key the desktop left (`reinjectKey`), and the game's client and server threads (`clientThread`, `serverThread`: what `HostThread.CLIENT`/`SERVER` run work on; null where the client thread is the one the desktop is framed on, or there is no integrated server) |
| `desktop.host.HostSession` | *(nothing — it OWNS)* what opens, when it is raised, the frame clock, the first-run geometry, whether a pointer event may reach a pinned window (`offerMouse` takes the host's grab state and decides), and the `SCREEN`/`HUD` render stages each paint fires (`UiStages`) |
| `desktop.host.HostSession.PaintHost` | whether a screen is up and whose, and how to bracket a draw |
| `desktop.app.ServerWindowHost` | *(an application, not a loader)* where a server's windows land |
| `fs.server.WorkspaceRoles` | is this actor the single-player owner, and is it a connected operator |
| `probe.ServerSmoke.Host` | is this a dedicated server, which package is client-only, how to stop |
| `ui.input.HostPointer` | *(nothing — it OWNS)* the scroll sign, and that a move carries no click time |
| `desktop.host.HostInput` | *(nothing — it OWNS)* where its screen's input enters: a host calls `HostSession.session().input()`, never `document.input()`, so the document's `DocumentDriver` can post events to a document on its own thread |

**A decision made in one loader is a decision the other loader got wrong.** Nothing can see it: each
copy is internally consistent, so no test fails and no guard fires. Four such decisions had already
drifted across two loaders by the time anyone compared them.

> **Naming: bare, unless the simple name collides with the seam it adapts — then an era SUFFIX.** J9
> deliberately dropped the `1201` suffixes, so `CgUiScreen`, `CgUiHud` and `ClientProbe` are bare. An
> adapter whose core counterpart shares its name cannot import it, and a fully-qualified name at a call
> site is the thing to avoid — so `WorkspaceHostModern` adapts `fs.server.WorkspaceHost`,
> `ServerSmokeModern` adapts `probe.ServerSmoke`, `HostModern`/`Host1710` answer `HostServices`, and
> `MachineExample1710` wires `app.machine.MachineExample`. **Never a prefix** — not `Mc1710Host`, and
> not `ModernHost`.

> **Packages: every loader tree owns a segment under `com.crystalgui.mc`, and none of them owns the
> root.** `com.crystalgui.mc.v1710` (one per Minecraft version, so 1.12.2 becomes `.v1122` with no
> collision), `com.crystalgui.mc.modern` (one per ERA, because three loaders share one `common`
> module), and `com.crystalgui.mc.forge` / `.neoforge` / `.fabric` for the entry points.
>
> 1.7.10 was at `com.crystalgui.mc` itself until 2026-09-16, which made that package **both its own
> and every other loader's parent** — and its `@Mod` class and generated `Tags` sat in
> `com.crystalgui`, the engine's root, split across two jars. Nothing failed: the hazard is that any
> relocation rule anchored at `com/crystalgui/mc/` rewrites 1.7.10 too, which J9 hit from the other
> side and fixed by moving `modern` down rather than moving 1.7.10.
>
> **What a rename here does not reach**: `@SidedProxy`'s two class strings, `mixins.crystalgui.json`'s
> `package`/`plugin`, `mixinsPackage`/`mixinPlugin`/`generateGradleTokenClass` in the module's
> `gradle.properties` (all relative to `modGroup`, which stays `com.crystalgui` — it is the maven
> group), `ServerSmoke.Host.clientPackage()`, and **the entry-class strings in
> `cg-descriptors.gradle.kts`**. That last one is the merged jar's variant table and only `prodSmoke`
> would catch it in the wild — but `checkSingleJar`'s `requiredEntries` names the same classes, so it
> fails first and on a laptop. Keep those two lists in step.
