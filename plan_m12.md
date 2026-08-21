# M12 — Platform integration: 1.7.10

Detail for the M12 row in `plan_syntax.md` §20. **Phases 1 through 3 have landed**; **Phase 4 is scoped
at the end** (2026-08-21) as the network and server layer, with its backlog of platform-deferred
prerequisites in [`plan_prephase4.md`](plan_prephase4.md).

> **Phase 1 goal, in one sentence:** press a key in a real 1.7.10 client and `CrystalEditor` opens,
> paints, and can be typed in.
>
> **Phase 3 goal, in one sentence:** write `Minecraft.getMinecraft().thePlayer` in that editor, on a
> client whose jar calls it something else entirely, and have it resolve, complete, compile and run.

## Status

| § | Item | State |
|---|---|---|
| 25.0 | The platform audit | **done** — and it is the reason this milestone is smaller than its row in §20 claims. Nine services already exist and four of them are better than the harness's |
| 25.1 | The two defects the audit found | **done** — a drifted duplicate GL backend, and a coremod injected after its deletion. **Both before the first launch** |
| 25.2 | Build revival — `mc1710` back in the build and launching | **done** |
| 25.3 | The host — `CgUiScreen` | **done** |
| 25.4 | The input pump | **done** — 1.7.10's plumbing was read rather than assumed, and it moved the pump off `GuiScreen` entirely |
| 25.5 | Workspace, config, and the way in | **done** — real workspace under `.minecraft/crystalgui/workspace`, served over the RPC protocol, F6 keybind |
| — | Unattended capture (`-PcgAutoTest`) | **done** — not in the original plan and should have been; see 25.9 |
| 26 | **Phase 2** — the language stack in-game | **done** — see the Phase 2 section |
| 26 | **Phase 3** — readable names in a live client | **done** — all eight exit criteria; see 26.13a for where each one actually stands, since two are met with honest caveats that are not ours to close |
| — | In-client completion probe (`-PcgComplete`) | **done** — like `-PcgAutoTest`, not in the plan and should have been. It is what found a defect four layers of passing tests could not; see the dev-client section in 26.13a |

---

## 25.0 The audit, and why it reshapes this milestone

The M12 row says "add all missing platform services". **Almost none are missing.** The audit below is
the reason Phase 1 is four days of wiring rather than three weeks of adapters.

**CrystalGraphics owns every platform service implementation, and always will** — it is the parent
mod, it is always present, and CrystalGUI reads everything through `CgPlatform`. So CrystalGUI's
`mc1710` implements **zero** platform services. It is a consumer. Anything missing from the platform
gets fixed in `CrystalGraphics/mc1710/`, never here.

And `CrystalGraphics/mc1710/` already ships a complete nine-service bundle
(`PlatformService1710`). Measured against the harness — which is the reference, because it is what
everything is tested on:

| Service | Harness | CrystalGraphics `mc1710` | Verdict |
|---|---|---|---|
| `gl()` | `Lwjgl2GLBackend`, 997 lines | `Lwjgl2GLBackend`, 996 lines | present — **but a drifted near-duplicate, see 25.1** |
| `capabilities()` | `Lwjgl2GLContext` | `Lwjgl2GLContext` | present |
| `resources()` | **returns `null` always** | `IResourceManager` lookup | **mc1710 is the better one** |
| `rendering()` | `HarnessContext` w/h | `Minecraft.displayWidth/Height` | present |
| `lifecycle()` | → `CgGraphicsLifecycle` | identical, line for line | present |
| `reload()` | → `CgAssetReloader` | that **plus** `attachToResourceManager()` (F3+T) | **mc1710 is the better one** |
| `input()` | `InputAdapter`, AWT clipboard | `InputService1710`, `GuiScreen` clipboard | present — MC's wrapper is the *right* choice in-game, since it already swallows the failures clipboard access throws for reasons outside this process |
| `sound()` | `soundId -> {}` no-op lambda | real `SoundHandler.playSound` | **mc1710 is the better one** |
| `cursor()` | `Lwjgl2CursorService`, 186 lines | `CursorService1710`, 187 lines | present — near-duplicate, same class with a different javadoc |

So the platform layer is **done**, and in four places the Minecraft one is more complete than the
thing we test against. The harness's `ResourceServiceHarness` returning `null` unconditionally is
worth pausing on: it means **every `CgIO` resource read in the harness falls through to the classpath
strategy**, and the in-game path — MC's resource manager, priority 2 in the waterfall, ahead of the
classpath — has *never been exercised by anything we run*. That is not a defect in the harness (it
has no resource manager to offer), but it does mean 25.2's first launch is the first time fonts,
stylesheets, sprites and shaders are resolved through Minecraft. Expect the first failures there.

## 25.1 The two defects the audit found

**A. `Lwjgl2GLBackend` exists twice and has already drifted.** 996 and 997 lines, same package-private
shape, and a `diff` finds three differences. One is legitimate — `bindFramebuffer` goes through
`OpenGlHelper.func_153171_g` in-game and raw `GL30` in the harness, which is correct on both sides.
One is cosmetic (overload ordering). The third is a **regression**:

```java
// harness — capability-checked, with the ARB fallback
if (GLContext.getCapabilities().OpenGL43) { GL43.glCopyImageSubData(...); }
else                                      { ARBCopyImage.glCopyImageSubData(...); }

// mc1710 — unconditional
GL43.glCopyImageSubData(...);
```

GL 4.3 is a 2012 feature and 1.7.10's audience is not on 2012 hardware. This is exactly the failure
mode `AGENTS.md` records for `stroke.glsl` — *"two copies means the fourth fix lands in one file and
the other keeps the bug, silently, while still rendering something plausible"* — reached by the same
route, and it is the second time in this repository. **Do not fix it in place.** Both copies are pure
LWJGL2 against the `CgGLBackend` SPI, with no Minecraft types in either, and the harness's own class
javadoc for the cursor service already says *"MC 1.7.10 is also LWJGL2 and can reuse it almost
verbatim"* — the sentence describes the code that then got copied instead. The fix is one shared
LWJGL2 backend that both consume, with `bindFramebuffer` as an overridable seam. Same for the cursor
service. That is a CrystalGraphics change and it belongs to 25.1, before the first launch, because a
launch that dies inside a duplicated backend costs a day of looking in the wrong copy.

**B. The composite build still injects a coremod that was deleted.**
`gradle/module_integration/composite.settings.gradle.kts` carries:

```kotlin
"coremods" to listOf("com.crystalgraphics.mc.coremod.CrystalGraphicsCoremod")
```

`integration.gradle.kts` turns that into a `--coremod` argument on `RunMinecraftTask`. That class was
deleted on 2026-07-31 with the ASM redirect layer (`CrystalGraphics/AGENTS.md` § *GL state*
documents the removal and why the mirror could never be reliable), and `coreModClass` is empty in
`CrystalGraphics/mc1710/gradle.properties`. The May 23 log in `mc1710/run/client/logs/` shows FML
loading it happily — that run predates the deletion. **This is a certain launch failure**, and a
cheap one to miss, because FML reports it as a coremod class-load problem rather than as stale build
config. Delete the entry. The `tweakClasses`/`mixinConfigs` entries beside it are still correct —
`usesMixins = true` and `mixins.crystalgraphics.json` both exist.

---

## 25.2 Build revival

**The current state.** `mc1710/` has `build.gradle.kts`, `dependencies.gradle`, `gradle.properties`,
`repositories.gradle`, one `@Mod` stub and — crucially — a `run/client/` directory with a world in it
and logs from **23 May**. It launched then. Everything below is restoring a configuration that
worked, not inventing one.

**`include`, not `includeBuild`.** `settings.gradle.kts` line 27 reads `//includeBuild("mc1710")`,
which is why the obvious first move is to uncomment it — and that cannot work: `includeBuild` requires
the directory to be a standalone Gradle build with its own settings file, and `mc1710/settings.gradle`
does not exist and **never has** (`git log --all` over that path is empty; it is not gitignored
either). The commented line is aspirational, not a regression. `git log -L` over that region finds the
configuration that actually ran, in `2a10724`:

```kotlin
include(":core")
include(":mc1710")            // ← a plain subproject
apply(from = "gradle/module_integration/composite.settings.gradle.kts")
```

**The three things to restore**, in order:

1. **The `pluginManagement` block.** It is entirely absent from today's `settings.gradle.kts`, and
   `mc1710/build.gradle.kts` opens with `id("com.gtnewhorizons.gtnhconvention")` with no version — so
   configuration fails immediately without the pin. Take the block from `2a10724` verbatim: it pins
   `gtnhconvention` and `gtnhsettingsconvention` at 2.0.20 and declares the GTNH Nexus repository.
   Its own comment records why `gtnhsettingsconvention` is **pinned but not applied** (applying it
   injects spotless onto every subproject's buildscript classpath), which is the kind of thing that is
   free to keep and expensive to rediscover.
2. **Reconcile the two `includeBuild("CrystalGraphics")` declarations.** This is the one genuine
   conflict. Today's root settings has its own block with three substitutions; `composite.settings.
   gradle.kts` has a block with five. **Two `includeBuild`s of the same path is a configuration
   error**, so one has to go. Keep `composite.settings.gradle.kts` and delete the root's block: its
   substitution list is a strict superset (it adds `com.crystalgraphics:crystalgraphics → :mc1710`,
   which is how `mc1710` resolves the CrystalGraphics *mod* rather than its libraries), and
   `integration.gradle.kts` — which `mc1710/build.gradle.kts` applies — reads
   `rootProject.extra["submoduleMods"]`, a value only that file sets. Deleting it instead means
   rewriting `integration.gradle.kts` too.
   > Verify that `integration.gradle.kts`'s `add("devOnlyNonPublishable", dep)` is reached only for
   > RFG projects. That configuration exists only in an RFG build, and the file's mc1201 branch
   > carries dependency lists for subprojects that are not in this build. The task-type guard
   > (`task.javaClass.simpleName == "RunMinecraftTask"`) covers the bootstrap-args half; the
   > dependency half needs checking.
3. **`include(":mc1710")`** and nothing else — no `mc1201` lines yet.

**JVMDowngrader is already configured, and that is not the whole story.** `mc1710/gradle.properties`
carries `enableModernJavaSyntax = jvmDowngrader`, `downgradeTargetVersion = 8` and
`jvmDowngraderStubsProvider = shade`, and `build.gradle.kts` already carries the
`jvmdg.multiReleaseVersions.set(emptySet())` workaround with a comment describing the hour it cost.
Leave all of it exactly as is. **The risk is `core/`, not `mc1710/`:**

```
core/build/classes/java/main/com/crystalgui/ui/UIWindow.class → major version 65 (Java 21)
```

`core/build.gradle.kts` sets source/target 21 and its Jabel `annotationProcessor` line is **commented
out** — so nothing is desugaring. `AGENTS.md` still claims *"Java 21 (Jabel-desugared toward Java 8
bytecode)"*, which is now false, and is the same staleness `CrystalGraphics/AGENTS.md` already carries
a warning about for its own modules. Fix that sentence when this lands.

Why it matters here and nowhere else: `mc1710/build.gradle.kts` bundles `core.jar` into the shadow jar
via `from(zipTree(coreJar))` in an `afterEvaluate`, and **jvmdg downgrades the module it is applied
to** — whether its task runs over classes injected into the shadow jar that way is a task-ordering
question, not a settings question. LaunchWrapper's ASM (5.0.3 on the May 23 classpath) reads Java 8
class files; a Java 21 class it must transform throws. The run JVM is Java 25, so anything LaunchWrapper
does *not* transform will load fine — which is the nasty part: it will half-work, and the failure will
name a random UI class rather than the build. **Verify this before writing any code**: build the
shadow jar and read the major version of a `com/crystalgui/**` entry inside it. If it is 65, the
options are (a) turn `core/`'s Jabel processor back on, (b) bring `core/` into jvmdg's input set, or
(c) `include(":core")` in a way that lets the GTNH convention downgrade it. Pick after measuring.

**Parallelism — turn it off for the bring-up, then put it back.** `gradle.properties` has
`org.gradle.parallel = true`. There is no evidence it broke anything (the May 23 run had it on), and
RFG's decompile/deobfuscate steps are exactly the shape that races: shared caches, build services,
a composite build reaching into another build's projects. It is not worth debugging a race and a
missing coremod at the same time. Set it `false` for 25.2, get one clean launch, set it back to
`true`, confirm the launch still works, and record the answer here. If it survives, leave it on.

---

## 25.3 The host — `CgUiScreen`

One class, `com.crystalgui.mc.client.CgUiScreen extends GuiScreen`, in `mc1710/`. The template is
`CgUiDockScene` in the harness — it is the only assembled `CrystalEditor` host that exists, and
`CrystalEditor`'s own javadoc states the contract: *"A host supplies a `WorkspaceClient` and a window;
everything else is decided here."* So the host is genuinely small.

```java
editor = new CrystalEditor(workspace.client());
editor.useConfig(new LocalConfigStorage(configDir));
uiWindow = new UIWindow(Ui.of(editor));
uiWindow.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
```

`StyleSheet.DEFAULT` is **not** applied automatically — that is a real call a host has to make, and a
window without it matches no CSS at all.

**Real device pixels, never `ScaledResolution`.** `GuiScreen.drawScreen(mouseX, mouseY, partialTicks)`
hands over coordinates already divided by MC's GUI scale factor. `UIWindow` wants raw pixels and
applies its own `uiScale` through `getRootTransform()`, which `AGENTS.md` names as *the single
definition of what `uiScale` means* — feeding it pre-scaled coordinates creates a second definition,
and the two disagree by exactly the scale factor. The symptom is the classic one: everything draws
correctly and every click lands somewhere else. So:

- `uiWindow.init(mc.displayWidth, mc.displayHeight)` — raw fields, not `ScaledResolution`.
- **Ignore `drawScreen`'s `mouseX`/`mouseY` entirely.** Input comes from 25.4's pump.
- `uiWindow.setUiScale(...)` is where MC's scale preference belongs if we want the editor to track it.
  Default to `new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor()` so the
  editor is legible at 4K out of the box, and leave it settable — **the user has said visual size is
  their call**, so this ships as a default and a setting, not as a decision.

**Per-frame, in `drawScreen`,** mirroring the scene exactly:

```java
CgRenderPipeline.getInstance().getFrameData().timeSecs = ...;  // or every node preview renders sin(0)
workspace.pump(deltaSeconds);                                  // one network tick, before anything reads it
uiWindow.init(mc.displayWidth, mc.displayHeight);
uiWindow.paintFrame();
editor.giveInitialFocus();
```

The `timeSecs` line is not optional and not cosmetic — `CgUiDockScene` and `CgUiGalleryScene` both
carry it with the same paragraph of comment, ending *"in Minecraft the loader drives it"*. This is
that loader. Without it `CG_TIME` is permanently zero and any shader-graph preview downstream of a
Time node renders black, which reads as "previews don't recompile".

**Do not call `super.drawScreen` or `drawDefaultBackground`.** The editor paints its own full-screen
chrome; MC's dirt/gradient backdrop would be drawn over or under it depending on order, and neither is
wanted.

**`onGuiClosed`** does what the scene's `dispose` does: `editor.saveSession(...)`,
`editor.savePreferences()`, `Keyboard.enableRepeatEvents(false)`. `Disposer.dispose(editor)` only if
the screen is genuinely single-use — decide alongside whether reopening restores state, and prefer
keeping the editor alive across opens, since a rebuilt workbench loses every open document.

**`doesGuiPauseGame()` → `true`** to start with. It is the conservative answer (the world stops while
you type), it is what every editor-like GUI in 1.7.10 does, and returning `false` invites a whole
class of "the game ticked while a modal was open" questions that are not worth answering in Phase 1.

**GL state is the first thing to check when it draws wrong.** MC's GUI pass leaves a specific state —
alpha test on, lighting off, colour set, a particular blend func. `CgUiPaintContext.beginFrame` brackets
itself with `CgGlScope`, so CrystalGUI should restore what it found; the risk is the other direction,
that MC's state is not what `beginFrame` assumes. Verification step, not a design decision.

---

## 25.4 The input pump

> **Read this section before writing the screen.** Three things about 1.7.10's input plumbing are not
> what they look like, all three were found by reading `Minecraft.runTick` rather than by reasoning,
> and one of them decides where the pump lives.

**1. Minecraft owns the `next()` loop.** `Minecraft.runTick` runs `while (Mouse.next())` and
`while (Keyboard.next())` itself and calls `handleMouseInput()` / `handleKeyboardInput()` **once per
event**, with that event already current. So `InteractiveSceneRunner.pollInput()` cannot be ported
verbatim: what transfers is the **body** of its loop, not the loop. Draining `Mouse.next()` inside an
override would eat events MC has not dispatched yet and double-drain the queue.

**2. `GuiScreen` never sees a key release.** `Minecraft.runTick` guards the screen dispatch:

```java
if (Keyboard.getEventKeyState())          // ← key DOWN only
{
    ...
    if (this.currentScreen != null) this.currentScreen.handleKeyboardInput();
}
```

`GuiScreen.handleKeyboardInput()` then re-tests the same flag and calls `keyTyped` — so a **release
is unreachable from a screen in 1.7.10.** That is fatal here and not cosmetic: `KeyboardEvent.Up`
bubbles, and `UIInputHandler`'s Space/Enter activation synthesises the `MouseEvent.Down`/`Up` pair a
real click would — the release is what fires the `Up`. Without it every keyboard-activated button
latches down forever.

**3. Forge's hook sits outside that guard, and that is the answer.**
`FMLCommonHandler.instance().fireKeyInput()` is emitted inside `while (Keyboard.next())` but **after**
the `if (getEventKeyState()) … else …` pair closes — so `InputEvent.KeyInputEvent` fires for **every**
keyboard event, press and release, with the LWJGL event still current and readable through
`Keyboard.getEventKey()/getEventKeyState()/getEventCharacter()`. `fireMouseInput()` sits at the same
nesting inside the mouse loop.

**So the pump is an FML event handler, not a `GuiScreen` override.** Both halves listen for
`InputEvent.KeyInputEvent` / `InputEvent.MouseInputEvent`, each guarded by
`mc.currentScreen instanceof CgUiScreen`. Mixin-free — `usesMixins` stays `false` — and symmetric,
which matters because the alternative is mouse-from-a-screen-override and keyboard-from-an-event, a
split nobody remembers a year later.

> **Two 1.7.10 spellings to get right, both of which fail silently.** The class is
> **`cpw.mods.fml.common.gameevent.InputEvent`**, not `net.minecraftforge.client.event.InputEvent` —
> that package is the 1.20 one and will be what an IDE offers first. And `fireKeyInput`/`fireMouseInput`
> post to **`FMLCommonHandler.instance().bus()`**, the FML bus, *not* `MinecraftForge.EVENT_BUS`;
> registering a `@SubscribeEvent` handler on the wrong bus compiles, runs, and never fires.
>
> Both event classes are **fieldless** (`public static class KeyInputEvent extends InputEvent {}`).
> That is not an oversight — they are posted from inside MC's `while (…next())` loop, so the handler
> reads `Keyboard.getEventKey()` / `Mouse.getEventX()` directly and the current event is still the one
> being announced. It also means the handler must not defer: read the event state synchronously.

> **`keyTyped` must still be overridden to a no-op.** MC's base implementation closes the screen on
> Escape unconditionally (`if (keyCode == 1) this.mc.displayGuiScreen(null)`), and it is reached through
> `handleKeyboardInput` whatever the pump does. CrystalGUI has a close-watcher cascade — a live drag
> eats Escape first, then the topmost popover, then a modal — and `AGENTS.md` states the rule for
> anything consuming Escape: *"a control must stop consuming it once it has nothing left to do."*
> Leaving MC's close above that cascade means the first Escape inside an open dropdown closes the whole
> editor. Override `keyTyped` to do nothing, and close the screen from the pump only when `uiWindow`
> did not consume the Escape.

**The normalisations transfer exactly**, and every one of them is a convention that is invisible when
wrong:

| Line | Why |
|---|---|
| `mc.displayHeight - Mouse.getEventY()` | LWJGL2's origin is bottom-left; CrystalGUI's is top-left |
| `Mouse.getEventDY() * -1` | same flip, for the delta |
| `Mouse.getEventDWheel() * (1/120f) * -1` | notches to units, **and** the sign — `AGENTS.md` records that a *positive* `MouseEvent.Scroll` means the wheel rolled **down**, and that `CanvasView` shipped zooming the wrong way because a test written from the implementation agreed with it |
| `buttonId == -1 ? -1 : nanos/1_000_000` | a move event has no button and must not carry a click timestamp, or multi-click detail counting drifts |
| `mc.displayWidth/Height`, never `this.width/height` | `GuiScreen`'s own fields are the **scaled** GUI size, and `handleMouseInput` divides by them; the pump needs raw device pixels for the same reason 25.3 does |

The harness's *"drain unconditionally"* note does **not** transfer — there is no draining decision left
to make once MC owns the loop. Its underlying warning does: a handler that fires in some contexts and
not others, for a reason nothing on screen explains, is the failure being avoided, and that is exactly
what item 2 above turned out to be.

`Keyboard.enableRepeatEvents(true)` in `initGui()`, off in `onGuiClosed()`. Held-key repeat is what
makes arrow navigation and backspace usable, and `CgUiDockScene` sets it as its very first line.

**One thing to watch:** `CursorService1710` calls `Mouse.setNativeCursor`, and MC also manages the
cursor around GUI open/close. If they fight, the symptom is a cursor that reverts every frame.

---

## 25.5 Workspace, config, and the way in

**The workspace is `HarnessWorkspace` with a different root.** Both halves in-process, over
`InMemoryTransport`, nothing shortcutting the protocol:

```java
ProjectRegistry registry = new ProjectRegistry().register(() -> List.of(
        new WorkspaceProject(PROJECT_ID, "Workspace", root)));
WorkspaceService service = new WorkspaceService(
        registry, new LocalFileSystem(registry), WorkspacePermission.ALLOW_ALL);
// InMemoryTransport.pair() → ServerUiSession / ClientUiSession → WorkspaceRpc → WorkspaceClient
```

- **Root:** `mc.mcDataDir/crystalgui/workspace`.
- **Config:** `LocalConfigStorage(mc.mcDataDir/config/crystalgui)` — **beside** the workspace, not
  inside it. The harness comment says why: a session record is private and must not become part of a
  project a resource pack could ship. Same reason trash lives outside.
- **`ALLOW_ALL` is a deliberate choice and gets a comment.** The default is `DENY_ALL` precisely so a
  host has to choose on purpose. On a single-player client, against local disk, with no other actor,
  `ALLOW_ALL` is right. When a *server-hosted* workspace lands in a later phase, this is the line that
  changes, and it should be easy to find.
- **`isConnected()` gates the first call.** Before the session has a window id the server discards
  every packet addressed to another window — silently, with no error — so `loadProjects()` and
  `restoreSession(...)` must wait, and `restoreSession` must come *after* `loadProjects`.

**The way in:** a `KeyBinding` registered through `ClientRegistry.registerKeyBinding`, handled on
`InputEvent.KeyInputEvent`, opening the screen via `mc.displayGuiScreen(...)`. Add a `/crystalgui`
client command as a second route, because a key binding that collides with another mod's is
indistinguishable from the feature not working.

**All of it client-side.** `CgUiScreen` imports `GuiScreen`, so it must sit behind a `@SidedProxy` (or
at minimum a `FMLCommonHandler.instance().getSide().isClient()` guard) and never be reached from
common init. `core/` is headless-clean by construction — the import guard enforces it — and that
property is worth not undoing at the loader.

---

## 25.6 What Phase 1 deliberately does not include

Each of these is a real deliverable that is *not* in scope, with the reason:

- **The language stack — tree-sitter grammars, ECJ, Rhino.** `HarnessWorkspace`'s constructor opens
  with `TreeSitterLanguages.register()`, `JavaLanguage.register()` and `JsLanguage.register()`, and
  none of the three is reachable here yet: the `language` module is not a dependency of `mc1710`
  (which has only `api(project(":core"))`), the grammars need native libraries extracted at runtime,
  and the engine bands are staged jars produced by `:language:stageEngines`. **Deferring is honest
  rather than a shortcut** — `core/` ships word-list lexers exactly so it can load with no natives,
  and the harness already prints a warning and carries on when the bands are absent. That designed
  degradation is what Phase 1 runs on. Getting the real parsers into a Forge jar is a phase of its own,
  and it is where `enableModernJavaSyntax` will bite a second time.
- **A server-hosted workspace over the MC network channel.** Phase 1's client talks to a server in its
  own process. The protocol is already the real one, which is the point — swapping the transport is a
  later phase's job and this one must not shortcut the protocol to make that harder.
- **`mc1201`.** Both `//includeBuild("mc1201")` lines stay commented.
- **§15.5 A (the live name environment) and §15.5 C (the mapping data).** These are what keep **M6 at
  ◐**, and they are the *reason* M12 exists — but they need a client that already runs CrystalGUI to
  be validated in. They are Phase 2.

---

## 25.7 Risks, ranked

1. **`core/` is Java 21 bytecode and LaunchWrapper's ASM is 5.0.3.** Highest, because it half-works:
   untransformed classes load on the Java 25 run JVM and the failure names a UI class. Measure the
   shadow jar before writing code (25.1).
2. **The stale coremod.** Certain launch failure, one line to fix, and it will look like a
   CrystalGraphics bug rather than a build one.
3. **Two `includeBuild("CrystalGraphics")` declarations.** Configuration-time error the moment
   `composite.settings.gradle.kts` is applied again.
4. **`CgIO`'s Minecraft resource path has never been exercised.** The harness's resource service
   returns `null`, so every read we have ever done fell through to the classpath. Fonts, `default.css`,
   sprites and the four shaders all resolve differently in-game.
5. **Escape and close semantics.** Silent and confusing rather than fatal — see 25.4.
6. **The drifted GL backend.** Wrong hardware only, which means it will not show up on the machine it
   is developed on.
7. **Registering the input handler on the wrong event bus.** Compiles, runs, fires nothing, and looks
   identical to "the pump is not implemented yet" — see the two spellings in 25.4.

> **Not a risk any more, and worth recording as such:** *"`GuiScreen` never sees a key release"* was
> found by reading `Minecraft.runTick`, not by reasoning, and it would not have surfaced until a
> keyboard-activated control latched down in a way that looked like a `UIInputHandler` bug. The reason
> it is not on the list is that 25.4 answers it; the reason it is written down is that the answer
> (listen on FML's bus, not the screen) looks arbitrary without it.

---

## 25.8 Exit criteria for Phase 1

Concrete, in the order they become true:

1. `./gradlew :mc1710:compileJava` succeeds from the root build.
2. `./gradlew :mc1710:runClient` reaches the main menu with both `crystalgraphics` and `crystalgui` in
   the mod list, and no coremod error.
3. A `com/crystalgui/**` class inside the built shadow jar reports a bytecode major version the
   LaunchWrapper ASM on the classpath can read — **or** a recorded reason why it does not have to.
4. The key binding opens `CgUiScreen`, and `CrystalEditor` paints: dock, file tree, status bar,
   stylesheets applied (i.e. it does not render as an unstyled column).
5. The file tree lists the real workspace directory, a file opens in a `TextEditor`, and typing,
   arrows, selection, and Ctrl+C/Ctrl+V all work — clipboard proving `InputService1710` end to end.
6. **A key release is delivered**: tab to a `Button` and press Space — it activates and comes back up,
   rather than latching down. This is the one-line proof that 25.4's finding was handled, and nothing
   else in the criteria list would catch it.
7. Escape closes a popover, and only a second Escape closes the screen.
8. Reopening the screen restores the session (`saveSession`/`restoreSession` round-trip through
   `LocalConfigStorage` under `.minecraft/config/crystalgui`).
9. `org.gradle.parallel` is back to `true` with a launch confirmed, or set to `false` with the reason
   written into this file.

---

## 25.9 What building it turned up

Seven hard failures between "the module is in the build" and "the editor paints", each fatal alone.
Two were predicted, one was predicted with the wrong mechanism, and four were not foreseen at all.

**1. The stale coremod** — 25.1 B, exactly as written.

**2. Java 21 bytecode, but not for the reason 25.7 gave.** The prediction was LaunchWrapper's ASM
failing to *transform* a class. The actual failure is earlier and dumber: FML's `ModDiscoverer` opens
**every jar on the classpath** with `asm-debug-all-5.0.3` looking for `@Mod`, and dies on the first
Java 21 entry with `probably a corrupt zip` — FML guessing, and the zip is fine. Fixed by making
`:core` `compileOnly` in `mc1710`; the shadow jar's jvmdg-downgraded copy (major 52, measured) is what
reaches runtime. **The run JVM is Java 8**, not the Java 25 the May log shows, which is why this bit
now and did not then.

**3. CrystalGraphics emitted a multi-release jar.** `META-INF/versions/17/**`, which ASM 5.0.3 also
cannot read — even though a Java 8 JVM would never load those entries. `mc1710/build.gradle.kts` had
carried the `jvmdg.multiReleaseVersions.set(emptySet())` workaround for months; the CrystalGraphics
one had not.

**4. `CrystalGraphicsIntegrationTest` drew a font demo unconditionally.** Full-screen, from
`RenderGameOverlayEvent.Text`, every frame the player is in a world — a white screen with the sky band
surviving at the top, plus `GL_INVALID_OPERATION` once per frame from the first frame after login. The
self-checks beside it were already opt-in; the demo now matches. **Not a CrystalGUI bug and it looked
exactly like one**, which is the general hazard of a diagnostic that is on by default.

**5. log4j — the one with the longest fuse.** MC 1.7.10 ships **2.0-beta9**, whose `Logger` has
`warn(String, Object...)` but none of the parameterised `warn(String, Object)` overloads (those arrived
in 2.6). `core/` compiled against 2.26.1, and **overload selection happens at compile time**, so every
`LOGGER.warn("x {}", a)` in the module — 27 files' worth — emitted a call to a method that does not
exist in game. It is a landmine rather than a build break: it fires only on the branch that logs. The
first one reached was a CSS warning path, so opening the editor died inside the user-agent stylesheet
parse and read as the resource-manager problem 25.7 predicted. It was not.

> **Compile against the OLDEST log4j the module must run on.** That binds every call to the varargs
> overload, which every later version still has, so the harness and tests are unaffected. Verified at
> the bytecode level rather than by inspection: a sweep of every class in `core/` finds six distinct
> log4j signatures and all six exist in beta9. Moving log4j off the compile classpath also took
> `org.jspecify` with it — it had been arriving transitively through modern log4j-api — so that is now
> declared where it is used.

**6. `org.gradle.parallel = false` is not enough for a composite build.** It governs the root build;
Gradle still runs an **included** build's tasks on separate workers, so `:mc1710` and
`:CrystalGraphics:mc1710` hashed the shared MC source tree concurrently and RFG threw
`ConcurrentModificationException` from `HashUtils.addDirContentsToHash`. It presents as
`Could not evaluate onlyIf predicate for task ':mc1710:mergeVanillaSidedJars'`, is intermittent, and
**the task succeeds when run alone**, which is what makes it look like corruption rather than a race.
`--max-workers=1` serialises across the composite and is the actual requirement — 25.2's advice to
flip `org.gradle.parallel` was aimed at the right problem and could not have fixed it.

**7. A relocated library across a module boundary.** `shadowImplementation` *relocates* — the header
comment in `dependencies.gradle` says so — and the rewrite reaches references to **CrystalGraphics**
API whose signatures mention the relocated type. So the shipped `CgUiPaintContext` looked for

```
CgFrameData.viewMatrix : Lcom/crystalgui/shadow/org/joml/Matrix4f;
```

against a `CgFrameData` that declares `Lorg/joml/Matrix4f;`. A field descriptor is part of resolution,
so it is `NoSuchFieldError` — **naming a field that plainly exists in both jars**, which is why it read
as a stale build twice before anyone looked at the descriptor instead of the field.

> **THE RULE: a library whose types appear in a cross-module signature can never be relocated by one
> side of that boundary.** CrystalGraphics is the parent mod and always present, so JOML is provided,
> not bundled — the same treatment `:core` and `:platform` already get. Taffy and fastutil stay
> relocated and that is correct: neither crosses into CrystalGraphics. Check the general case, not the
> one instance — scan the built jar for any `com/crystalgraphics/**` member reference whose descriptor
> mentions a relocated package.

**8. The editor rendered perfectly and the window showed nothing.** The last one, the longest, and the
only one where every symptom pointed at the wrong subsystem.

Minecraft presents by drawing **one fixed-function quad** — `Framebuffer.framebufferRender`:
`glEnable(GL_TEXTURE_2D)`, `bindFramebufferTexture()`, a `Tessellator` quad, and no `glUseProgram(0)`
anywhere. Fixed-function samples texture unit **0**, while `bindFramebufferTexture` binds to whatever
unit is *currently active*. Leave the active unit elsewhere and Minecraft binds its screen texture where
nothing reads it, so the quad falls back to its vertex colour: a **white** window. Leave a shader
program bound and its own blit runs through our vertex shader instead.

> **The UI was correct the entire time.** It was sitting in Minecraft's framebuffer — a `glReadPixels`
> there produces a pixel-perfect editor — while the screen showed a flat fill, because the step
> *between* the two was broken rather than the drawing. Anything that reads the framebuffer therefore
> **disagrees with the screen**, which is why the first automated capture "proved" the editor worked
> while the person sitting in front of it saw nothing. Two bugs, both first-execution:

- **`CgUiPaintContext.endFrame` left UI state behind.** `blitLayer` composites *after* `glScope.close()`
  — it has to, since the scope is what rebinds the real target — so the material it binds is the one
  piece of frame state nothing restores. Now scoped on its own (`PROGRAM`, `TEXTURES`). A `core/` fix;
  every host gets it.
- **The loader owes Minecraft a state handback.** `CgGlStateManager` restores into *its* shadow's idea
  of the world, and Minecraft never writes through `CgGL`. The design note already said so —
  *"Two shadows, each blind to the other. Before calling in, state MC cares about must be set through
  MC's API"* — and `hostForeign` was recorded as having **zero callers**. `CgUiScreen` now resets the
  active unit, drops the program, re-enables `GL_TEXTURE_2D` and invalidates the shadow after painting.

> **What actually broke the deadlock was tooling, not insight.** Eight rounds went into theories that
> were each individually reasonable — MSAA sample counts, the FBO restore, dedup elision, fonts, the
> instancing path — and every one was eliminated by a measurement rather than confirmed by one. The
> turn came from `-PcgAutoTest`: launch, load a world, open the editor, screenshot, quit, unattended.
> **Build the capture loop first.** The harness has had `ArtifactService.requestCapture` from the
> beginning and that is why harness bugs take minutes; Minecraft had no equivalent, so every iteration
> cost a person getting up to press a key. The plan should have called for it in 25.3.

### Corrections owed to earlier sections

- 25.7's risk 1 keeps its rank and loses its mechanism — rewrite it around `ModDiscoverer`.
- 25.7's risk 4 (`CgIO` through Minecraft's resource manager) is **still unproven**: the stylesheet
  parse that failed was reached, so resources resolved far enough to be parsed. Do not tick it off
  until something has actually rendered from an asset.
- `AGENTS.md`'s "Java 21 (Jabel-desugared toward Java 8 bytecode)" is false and was relied on. The
  desugaring that does happen is jvmdg's, in `mc1710`, over the shadow jar — not in `core/`.
- 25.7's risk 4 (`CgIO` through Minecraft's resource manager) is **cleared**, and by measurement: fonts,
  a UA stylesheet, a shader and an SVG icon all resolve in game. It was the leading suspect twice and
  was wrong both times.
- **`--max-workers=1` is a requirement, not a preference**, for anything touching `:mc1710`. 25.2's
  advice to flip `org.gradle.parallel` was aimed at the right race and could not have fixed it: that
  property governs the root build, while an *included* build's tasks still run on separate workers.
  `org.gradle.parallel` is back to `true`.

---

## Phase 2 — the language stack in-game — **done**

Scoped as ":language into the jar, native extraction, staged engine bands, and the second
`enableModernJavaSyntax` fight", and all of it landed alongside Phase 1:

- `:language` is `compileOnly` and reaches runtime through the shadow jar, downgraded.
- **tree-sitter grammars are live on Java 8.** The JNI hazard is real — upstream `tree-sitter-ng`
  returns `JNI_VERSION_10` from `JNI_OnLoad`, and a Java 8 VM rejects anything above
  `JNI_VERSION_1_8` — but the vendored jars come from a fork whose C returns `JNI_VERSION_1_8`, so the
  natives load. Verified by forcing `org.treesitter.TSParser` rather than trusting a lazy registration,
  which proves nothing.
- The tree-sitter jars are bundled **unrelocated** (`from(zipTree(...))`, never
  `shadowImplementation`): a JNI symbol is `Java_<mangled-package>_…`, so relocating the package
  renames the symbol the `.dll` does not export.
- **ECJ and Rhino open**, after fixing `EngineHost.withOwnClasses` — see 25.9 item 8.
- `ScriptWorkbench` is installed by the host, so Run and Stop exist and scripts execute.

**One item remains, and it is 26.1 below**: the bands are found through a dev-run system property
pointing at a Gradle output directory. A shipped mod has no such directory.

---

# Phase 3 — readable names in a live client

**This is what M12 exists for.** Its §20 exit criterion is *"a script written in readable names
compiles, runs and links inside a real 1.7.10 client, against MC classes and a mixin-added member;
completion never shows `func_147439_a`; the same script runs unchanged in dev and prod."* Phase 1
delivered "runs inside a real client" and none of the rest. **It is also the only thing keeping M6 at ◐.**

> **The architecture rule for this whole phase.** Every mechanism below lives in `language/`. A platform
> contributes exactly two kinds of thing — **what to provide** (a route to live bytes, mapping
> coordinates) and **where to put it** (a directory) — through one small interface, and contributes no
> logic at all. Downloading, verifying, caching, parsing, remapping, detection and compilation belong to
> the core, once, so that `mc1201` is an implementation of one interface rather than a second copy of
> this phase. Anything that starts to look like per-platform logic is a design error and belongs behind
> the SPI instead.

## 26.0 What is already built, and why that changes the shape

`plan_syntax.md` §15.5 splits this in two, and **the half with the hard logic is done and proven**:

| Piece | Where | State |
|---|---|---|
| `MappingSet` — readable ⇄ runtime, keyed by owner, with `IDENTITY` | `language.map` | ✅ built |
| `ReadableView` — the **in** direction, remapping runtime bytes to readable types | `language.map` | ✅ built |
| `InheritanceAwareRemapper` — the **out** direction, ~180 lines on plain ASM | `language.map` | ✅ built |
| `MemberNameMapper` — the bridge-crossing name seam, both directions | `language.engine.bridge` | ✅ built, used by Rhino |
| The round trip, incl. a negative control against a plain `ClassRemapper` | tests | ✅ nine tests |
| **A compiler that can accept a name environment** | — | ❌ 26.3 |
| **The live name environment** | — | ❌ 26.4 |
| **The mapping data** | — | ❌ 26.5–26.7 |

Three consequences, all of which make this smaller or differently shaped than it first looks.

**`MappingSet.IDENTITY` means a dev environment takes the SAME path.** It is documented as "the common
case, not a fallback", so there is no dev-only branch to keep in step, and every mechanism below is
exercised from the first run in a dev client — with an identity mapping, which is what 1.7.10 dev
genuinely is.

**The obfuscation problem and the live-bytes problem are different problems.** §15.5 A needs a *live
classloader*, not an obfuscated one. Its hardest claim — a **mixin-added member** resolving — is testable
in the dev client today, because CrystalGraphics ships mixins into it. Only "unchanged in dev and prod"
needs a reobfuscated client, and that is 26.8: validation, not a blocker.

**The JavaScript half already has its seam.** `MemberNameMapper` exists, carries both directions, and
crosses the bridge as strings precisely because `MappingSet` is not parent-first. So once 26.5–26.7
produce a `MappingSet`, wiring Rhino is adapting one interface rather than new design. Java is the side
that needs 26.3 and 26.4.

## 26.1 The platform SPI — one interface, and nothing else

Everything a platform knows and the core cannot. Lives in `language.platform`; `mc1710` implements it,
and `mc1201` will implement the same one.

```java
public interface ScriptService {

    /** Post-transform bytes for an internal name, or null. @see ReadableView.ByteSource */
    ReadableView.ByteSource liveBytes();

    /** Root for anything this module downloads or extracts. Must survive a restart. */
    Path cacheRoot();

    /** Which mapping artifact this environment needs, or NONE where the runtime is already readable. */
    MappingCoordinates mappings();

    /** How to tell a readable runtime from an obfuscated one. @see 26.7 */
    NamespaceProbe namespaceProbe();
}
```

- **`liveBytes()`** is the only genuinely per-platform *code*, and on 1.7.10 it is a dozen lines (26.4).
- **`cacheRoot()`** answers "where to put it" and nothing more — `.minecraft/config/crystalgui` here. The
  core decides the layout *under* it, so every platform gets the same tree and a layout bug is fixed once.
- **`mappings()`** is data, not behaviour: channel, version, base URL, per-file digests. A platform states
  which artifact it needs; the core fetches, verifies, caches and parses it.
- **`namespaceProbe()`** is data too — a type and two member names (26.7).

> **Registration mirrors `CgPlatform`**: one bundle, registered by the loader, read through a static
> accessor. Two registries is how a loader wires up half of something, and CrystalGraphics already
> learned that once — `AGENTS.md`, "CrystalGUI has no platform registry".

> **`ScriptService.NONE` is a real deployment, not a test double.** The harness, the tests and a
> dedicated server all run with no platform: `liveBytes()` falls back to `ByteSource.ofClassLoader`,
> `mappings()` is `NONE`, and the stack behaves exactly as it does today. That is what keeps `language/`
> runnable off a Minecraft host, which is the property the module exists for.

## 26.2 Ship the engine bands

Every `EngineSource` today is filesystem-based — `NONE`, `directory(Path)`, `of(Collection<Path>)`,
`ofPathList(String)` — and the dev run points at `language/build/engines` through
`-Dcrystalgui.engines.dir`. Nothing reads from inside a jar, so a shipped mod has no bands at all.

**Bundled**, decided: jar size does not matter for 1.7.10, and offline-by-default is worth more than a
slim jar for a tool people install in order to write code. This is the opposite call from 26.5's
mappings, deliberately — see there.

- **Band 8 only** in the 1.7.10 jar: ECJ 3.26.0 (~15 MB) and Rhino 1.7.15.1 (~1.5 MB). Shipping 11 and 17
  as well would triple it for jars this platform can never load. Which band a jar carries is a build
  decision, so a `mc1201` jar carries 17 by the same rule.
- **`EngineSource.extractedFrom(ClassLoader, String resourceRoot, Path into)`** — core-side. Copies the
  band's jars out of the mod jar into `<cacheRoot>/engines/<band>/`, then delegates to `directory(...)`.
- **The same present/verify/atomic/delete discipline as 26.5.** The failure modes are identical and there
  is no reason for two implementations; factor the file half out and have both call it.
- `EngineHost.defaultSource()` keeps the system property as a first-priority override, so dev runs and
  `runHarness` are untouched.

## 26.3 A compiler that can be given a name environment

**A prerequisite for 26.4, and not small.** Both ECJ entry points are file-path based today, and neither
can accept a live name source:

| | today | why it cannot work |
|---|---|---|
| `EcjScriptCompiler.compile` | `BatchCompiler.compile("-classpath …")` | a command line of **file paths** |
| `EcjSourceAnalyzer.analyze` | `ASTParser.setEnvironment(…)` | also **file paths** |

`EcjScriptCompiler`'s own javadoc has been waiting for this and names the route exactly:

> *"That path is `org.eclipse.jdt.internal.compiler.Compiler` with an `ICompilerRequestor` collecting
> bytes and a custom `INameEnvironment` supplying types, which is also exactly where §15.5's
> obfuscated-name mapping has to hook in. Both are present in all three bands; neither is driven yet."*
> … *"So: this proves the seam and runs scripts. It is not the compiler the editor will use."*

So 26.3 is that replacement, and it pays for itself three times:

- **`ICompilerRequestor` collects bytes in memory** — no temp directory, no class files written, no I/O
  per compile. The current design is documented as "the cheapest thing that is genuinely correct … far
  too slow for the per-keystroke analysis M6 needs".
- **`INameEnvironment` is the seam 26.4 plugs into.** Without this step there is nowhere to plug in.
- **One implementation serves both** compile and analyse, so the editor and the runner cannot disagree
  about what resolves — which they can today, taking separate routes to the same jars.

**Band risk, and the reason to do this first.** `org.eclipse.jdt.internal.compiler.*` is internal API
across three pinned ECJ versions (3.26.0 / 3.33.0 / 3.46.0). The javadoc asserts both types exist in all
three; signatures are not guaranteed stable. `smokeEngineBands` already runs each band on a JVM of its
era — **extend it to drive a real compile through the new path in each**, so a band that cannot support
it fails at build time rather than at a user.

## 26.4 The live name environment (§15.5 A)

**What it replaces.** `ReadableView.materialise` writes remapped classes to a directory and hands over the
path, because `setEnvironment` takes file paths. Correct wherever bytes are obtainable — and wrong on a
live host, where bytes come from the launch classloader through the transformer chain. Feeding those to
ECJ wants an `INameEnvironment`: no writing, no staleness, and it works for a class whose bytes exist
**only because a mixin produced them**. `HostClasspath` is the file-based baseline this supersedes on MC
hosts, and its javadoc says so, "because a file list that looks complete is exactly how this gets
forgotten".

**The seam already exists.** `ReadableView.ByteSource` is one method — `byte[] bytesOf(String
internalName)` — and its default `ofClassLoader` carries the warning in its own javadoc: *"Correct off a
Minecraft host and not on one, because it reads what is on disk and that is precisely the thing that lies
there. Named as the default so a platform that needs the transformer chain has something obvious to
replace."*

**LaunchWrapper makes the 1.7.10 implementation trivial, which was not obvious.** Both halves are public:

```java
public byte[] getClassBytes(String name)               // RAW, pre-transform
public List<IClassTransformer> getTransformers()       // the chain itself
```

`runTransformers` is private, but it is only a loop over that list. So `liveBytes()` reads the raw bytes
and walks the public transformer list, applying each `transform(name, transformedName, bytes)` in order.
**No reflection, and mixins come for free**, because Mixin applies through a transformer in exactly that
list. That is the entire platform-specific part of this phase.

**The core side**, in `language.java.ecj`:

- `findType(char[][])` / `findType(char[], char[][])` → ask `ScriptService.liveBytes()`, remap through
  `ReadableView`, answer with a `NameEnvironmentAnswer` over the bytes. Nothing is written.
- `isPackage(char[][], char[])` → answered from the same source. Getting this wrong makes a type resolve
  while its package does not, which reads as a phantom compile error.
- **A reflection-synthesized stub as fallback**, per §15.5 A, for classes whose bytes cannot be retrieved:
  a type that exists to the JVM but whose bytes the loader will not yield must still resolve, or a script
  fails to compile against a class it can demonstrably call. Synthesize from
  `Class#getDeclaredMethods`/`getDeclaredFields` — signatures only, no bodies.
- **Cache per compile, never per process.** A mixin can add a member between runs, and a stale answer is
  exactly the "compiles and then does not link" failure this design exists to remove.

## 26.5 Acquiring the mapping data — downloaded, not shipped

**Not bundled, and the reason is a distinction rather than caution.** Two different acts get conflated:

- **Building a mod with MCP mappings.** The compiled bytecode carries SRG or notch names; the mapping
  data is not in the jar. Every Forge mod since 2011 does this and nobody questions it.
- **Putting the CSVs in the jar as runtime data.** That is redistributing the mapping data itself.

Only the second is in question, and classic MCP terms prohibited exactly it — Forge distributes the data
through Maven for *build* use. 1.7.10-era `mcp_stable` predates the 2020 relicensing around MCPConfig and
official Mojang mappings, so the old terms apply. Fetching from the canonical source sidesteps the
question entirely: nothing is redistributed, and the user's machine gets the files from where Gradle
already gets them.

> Deliberately the opposite call from 26.2. Bundle the engines — EPL/MPL, redistribution plainly
> permitted, and offline matters for a 16 MB compiler. Download the mappings — 670 KB, and the only one
> of the two with a licence question.

**Source**, both already in `mc1710/gradle.properties`, so the mod and the build cannot disagree:

```
remoteMappings = https://raw.githubusercontent.com/MinecraftForge/FML/1.7.10/conf/
channel = stable          mappingsVersion = 12
```

**Layout**, decided by the core beneath `ScriptService.cacheRoot()`:

```
<cacheRoot>/mappings/<mcVersion>/<channel>-<version>/{methods,fields,params}.csv
             1.7.10   stable-12
```

### Staleness is designed out, not managed

**A published mapping version is immutable.** `mcp_stable` 12 for 1.7.10 is frozen — it will never change
content under that name. So the version *is* the cache key and **there is nothing to invalidate**: a
different requirement is a different directory. Moving to `stable-13` simply misses and downloads, and
the old directory becomes inert. No TTL, no revalidation, no invalidation logic anywhere.

That property only holds if the coordinates are **pinned in the mod** rather than discovered from the
environment. A version read at runtime is a version that can differ between dev and prod, which is the
one thing this phase exists to prevent.

What still needs handling is not staleness but **partial and damaged state**:

- **Present-and-valid on every launch, and missing is treated identically to invalid.** Three files,
  670 KB, hashed in single-digit milliseconds. Checking mere existence is what lets a truncated download
  persist forever, so there is no "assume it is fine because the file is there".
- **Verify against a digest, not a size.** Upstream publishes `.md5` beside each artifact and the expected
  digests are pinned alongside the version in `MappingCoordinates`, so a corrupted *download* and a
  corrupted *cache* are caught by one check — and a mirror serving something unexpected cannot be
  silently accepted.
- **Atomic install.** Fetch to `<name>.part` in the same directory, verify there, then
  `Files.move(..., ATOMIC_MOVE)`. Nothing incomplete is ever visible under the real name, two clients
  starting at once cannot observe a half-written file, and a crash mid-download leaves a `.part` the next
  launch overwrites.
- **Delete on verification failure**, so the next launch retries rather than being wedged on bad bytes.

### When it happens, and what happens without it

- **Off the client thread, on first need.** A network fetch must never sit inside `initGui`.
- **Absent is a supported state and already the designed one.** No mappings means the runtime namespace
  is presented as-is: the editor opens, colours, compiles and runs scripts — it shows `func_147439_a`
  instead of `getBlock`. The same degradation `EngineHost` applies to an absent band, reported once
  rather than thrown.
- **Report which state, once.** "No mappings configured" and "the download failed" are different things
  to somebody offline on purpose, and the line that distinguishes them is the difference between a bug
  report and a shrug.

## 26.6 Parsing them — one format SPI in the core

**`language/` takes mapping *files* and parses them itself, and for 1.7.10 that is entirely plausible.**
The MCP data is the easiest version of this problem that exists — already in the Gradle cache at
`mcp_stable/12`, matching `mappingsVersion = 12`:

| file | size | lines | shape |
|---|---|---|---|
| `methods.csv` | 375 KB | 4,820 | `searge,name,side,desc` |
| `fields.csv` | 253 KB | 4,792 | same |
| `params.csv` | 40 KB | 1,885 | same |

11,500 lines of flat CSV, `srg → readable`, and **no owner qualification is needed, because SRG names are
globally unique by construction**. That is a `split(",", 4)` into `MappingSet.builder()`.

> ### Revision: that guarantee holds in ONE DIRECTION, and the other one is 20% of the data
>
> **Measured while writing the parser, not predicted.** SRG → readable is a function — every `func_*`
> names one method, exactly as claimed. Readable → SRG is **not**, because unrelated classes are allowed
> the same readable name. `getBlock` is four distinct SRG methods in `mcp_stable/12`
> (`func_145805_f`, `func_147439_a`, `func_150810_a`, `func_151337_f`), and across the real files:
>
> | | rows | distinct readable names | ambiguous | rows they cover |
> |---|---|---|---|---|
> | `methods.csv` | 4,819 | 4,311 | 357 | 865 (18%) |
> | `fields.csv` | 4,791 | 4,058 | 329 | 1,062 (22%) |
>
> **The reverse is the direction that makes a script link**, so this is not a corner. A map that kept the
> last entry would remap `world.getBlock(…)` to whichever of four it happened to hold, and the script
> would fail at run time with a `NoSuchMethodError` naming an SRG name its author never wrote — after
> compiling, verifying and looking entirely correct.
>
> So `MappingSet` **refuses to guess**: a colliding readable name is left out of the reverse table and
> reported by `isAmbiguousReadableMethod`. Unmapped is also wrong, and it is wrong in the direction that
> can be detected and repaired.
>
> **What repairs it is the owner, and the owner does not need `packaged.srg`.** At remap time the
> declaring type is known, and its live bytes are already being read — so "which of the four `getBlock`s
> does `net/minecraft/world/World` declare" is answered by forward-mapping that class's own method names,
> which is a lookup in the direction that IS a function. That is `InheritanceAwareRemapper`'s existing
> job, and it means risk 1's conclusion stands: the CSV pair suffices, and `SrgFormat` is still not
> needed.

**It does not generalise cleanly to every version, so the design admits that up front.** Modern targets
use genuinely different formats — TSRG2 for Forge's SRG data, ProGuard `.txt` for Mojmap, Tiny v2 for
Fabric — and none is a variation on CSV.

```
com.crystalgui.language.map.format
    MappingFormat     SPI: does this file look like mine, and parse it into a MappingSet.Builder
    McpCsvFormat      methods.csv / fields.csv / params.csv          <- now
    SrgFormat         packaged.srg / joined.srg, notch <-> srg       <- only if risk 1 says so
    TsrgFormat  ProGuardFormat  TinyFormat                           <- when a platform needs one
    MappingFiles.load(List<Path>) -> MappingSet
```

**A platform hands over paths, never parsed data.** That keeps preprocessing at zero for every format the
core knows and leaves the escape hatch open: a platform with something exotic reduces it minimally to a
supported form rather than teaching this module a one-off dialect. One parser per format, in the module
that owns `MappingSet`, is the version that does not drift.

**Parse once, hold one `MappingSet`.** It is keyed by owner and is immutable; rebuilding it per compile
would re-read 670 KB for nothing.

## 26.7 Choosing the namespace — detected, never configured

- 1.7.10 **dev** is `IDENTITY`: the classes really are at MCP names, so the mapping is the identity and
  the same code path runs.
- 1.7.10 **prod** builds a `MappingSet` from the fetched CSVs.

**Which applies is probed, not declared.** `NamespaceProbe` names a type and the two spellings of one of
its members — ask `liveBytes()` for `net/minecraft/world/World`; if it declares `getBlock` this is a
readable runtime, if it declares `func_147439_a` it is not. A flag someone has to set is a flag that will
be wrong in exactly the environment nobody tests, and the probe costs one class read at startup.

> The probe reads through **the same `ByteSource` as everything else**, so it cannot disagree with what
> the compiler will later see — which a check against a file on disk could.

## 26.8 Reobfuscated validation

The last mile, and the only part that genuinely needs a non-dev client. RFG already produces a
reobfuscated jar; run a real client against it and confirm that **the same script file, unchanged**, does
what it did in dev.

Do this last, and do not let it gate 26.3–26.7 — all of them are testable in the dev client, and treating
it as a prerequisite would stall the work that can actually proceed.

## 26.9 Order of work, and what each step unblocks

Ordered so that every step is verifiable when it lands, and nothing waits on the reobf client.

| # | Step | Unblocks | Verified by |
|---|---|---|---|
| 1 | **26.1** `ScriptService` + `NONE`, `mc1710` impl returning `cacheRoot` only | everything | existing suites still green with `NONE` |
| 2 | **26.3** internal `Compiler` + `ICompilerRequestor`, replacing `BatchCompiler` | 26.4 | `smokeEngineBands` drives a compile per band |
| 3 | **26.3** ~~same environment used by `EcjSourceAnalyzer`~~ — **not achievable, see below** | — | the two already share a classpath; pinned by `theEditorAndTheRunnerGetTheSameClasspath` |
| 4 | **26.4** `liveBytes()` on `mc1710` + `INameEnvironment` | readable names | mixin-added member resolves in the dev client |
| 5 | **26.2** bundle + extract the bands | shippable | non-dev jar analyses with no system property |
| 6 | **26.6** `MappingFormat` + `McpCsvFormat` | 26.5, 26.7 | unit test over the real CSVs |
| 7 | **26.5** fetch, verify, cache | prod names | delete-and-relaunch restores; corrupt-and-relaunch re-downloads |
| 8 | **26.7** `NamespaceProbe` + wiring | dev/prod parity | dev probes readable, fixture probes obfuscated |
| 9 | **26.8** reobf client | the milestone | the same script file runs unchanged |

**Steps 2–4 are the spine.** They are also the only ones with real unknowns, which is why they come
before the mapping work — that half is a parser and a downloader, both well understood.

### Revision (superseded below): step 3 cannot be done as written, and did not need to be

**`ASTParser` has no name-environment seam.** Its whole public surface for saying what a parse resolves
against is `setEnvironment(String[] classpath, String[] sourcepath, String[] encodings, boolean vmBoot)`,
`setProject(IJavaProject)` and `setWorkingCopyOwner` — file paths, an Eclipse workspace, or nothing. There
is no overload taking an `INameEnvironment`, on any of the three bands.

**And the internal route is closed by the signing rule, not merely by taste.** The class that would do it
is `org.eclipse.jdt.core.dom.CompilationUnitResolver`, which *is*
`org.eclipse.jdt.internal.compiler.Compiler` plus the DOM converter — but it is **package-private**, so
reaching it means putting a class in `org.eclipse.jdt.core.dom`. Eclipse jars are signed, and a JVM
refuses a package whose classes come from differently-signed sources: that is the `SecurityException`
this plan's own build already has `signerConflicts` to catch. Reflection over a package-private static
across three pinned ECJ versions is the remaining option, and §26.3 already names that class of thing as
the band risk worth avoiding — the plan asserts `internal.compiler.Compiler`'s signature is stable across
bands and asserts nothing of the kind here.

**What step 3 was actually for was already true.** The stated goal is that *"the editor and the runner
cannot disagree about what resolves — which they can today, since they take separate paths to the same
jars"*. Separate paths, yes; **same jars, already** — every asker calls `HostClasspath.detect()`:
`JavaLanguage` for the analyser, `ScriptHost` for the compiler, and `JsLanguage` twice for the interop
tier. The probe collects into a `LinkedHashSet`, so repeated calls are equal and everything that keys on
the list (notably `JavaLanguageServices.typeIndexFor`) actually hits.

That agreement rested on four independent call sites and nothing enforced it, so it is now pinned by a
test rather than left as a convention — including the reference-equality half, which is the only way to
observe from outside that one classpath means one type index rather than a fresh fifty-thousand-entry
scan per document.

**What this defers to 26.4.** The divergence that matters is not the classpath, it is **live bytes**: the
compiler can be handed an `INameEnvironment` and the analyser cannot, so a class whose bytes exist only
because a transformer produced them will resolve for the runner and not for the editor. That is a
decision 26.4 has to make explicitly — a materialised cache under `cacheRoot()` for the analyser, or an
accepted asymmetry with the editor falling back to the file view. It is no longer a step that can be
quietly assumed away, which is why it is written down here.

### Correction: the analyser CAN be given the name environment, and now is

**The revision above was half wrong, and the half it got wrong was the important one.** The deferred
asymmetry turned out not to be tolerable at all: on an obfuscated client the editor could not resolve a
single Minecraft type, offered none in completion, and could not even suggest the import — while the
runner compiled and ran the same file. It was reported from a real session, not found by a test.

`ASTParser` genuinely has no seam; that part stands. What was wrong was concluding that the internal
route was closed. `CompilationUnitResolver` is package-private, and that blocks putting a class **beside**
it in `org.eclipse.jdt.core.dom` — the signing rule. It never blocked reaching it reflectively, and the
three members needed are **public** on that class:

```
public CompilationUnitResolver(INameEnvironment, IErrorHandlingPolicy, CompilerOptions,
                               ICompilerRequestor, IProblemFactory, IProgressMonitor, boolean)
public CompilationUnitDeclaration resolve(ICompilationUnit, boolean, boolean, boolean)
public static CompilationUnit convert(…, BindingTables, int, IProgressMonitor, boolean)
```

Byte-identical in `jdt.core` 3.26.0 (2021) and 3.46.0 (2026) — twenty-five releases apart — so the band
risk §26.3 avoided this for does not hold here, and it is reflection for *accessibility* rather than into
internals. Every failure returns null and falls back to the `ASTParser` parse, so the worst case is the
behaviour that shipped before.

**The rejected alternative is worth recording.** Materialising a remapped copy of the runtime to disk
would also have worked, and was the first proposal: ~4,300 classes transformed and written on first
launch, tens of MB of cache, mixin-added members stale in the editor until a rebuild — and, decisively,
it is two views that agree rather than one view. Sharing the `TypeBytes` object makes the editor and the
runner identical *by construction*.

**One thing genuinely does need enumeration**, and only one: completion of types you have not imported.
That is a rename rather than a listing — `ScriptService.runtimeClassName` translates a single on-disk
name and `TypeIndex` applies it while scanning as it already did, so it costs one string operation per
entry and no extra I/O. Which is why exit criterion 8 now reads "a byte route, a path, a rename and two
data objects".

## 26.10 How each piece is tested

Following the project's own rule: test the spine — ownership, re-entrancy, codecs, announcements — never
pixel layout or cosmetics.

- **26.3** — a compile through the new path produces identical bytes to the `BatchCompiler` path for a
  fixture script. That is the regression net for replacing a working compiler.
- **26.4** — three cases, all headless with a fake `ByteSource`: a type resolves from bytes; a type with
  *no* bytes resolves from the reflection stub; **a member added to the returned bytes after a first
  compile is visible on the second**, which is the per-compile cache assertion and the mixin case in
  miniature.
- **26.5** — the file discipline, with no network: a truncated file is rejected and re-fetched, a wrong
  digest is rejected and the file deleted, a `.part` left behind does not become the real file, and two
  concurrent installs leave one valid result.
- **26.6** — parse the real `mcp_stable/12` CSVs and assert a handful of known pairs
  (`func_147439_a` ⇄ `getBlock`). Cheap, and it fails loudly if a format assumption is wrong.
- **26.7** — probe a readable fixture and an obfuscated one, assert `IDENTITY` and non-identity
  respectively.
- **In-client** — `-PcgAutoTest` opens a script that names a Minecraft type and captures. The mixin case
  is real rather than staged: `MixinMinecraft.cgMixinProbe()` is a public member CrystalGraphics' own
  mixins merge into `Minecraft`, and `-PcgScript` compiles and runs a call to it in both clients.
- **In-client, the EDITOR rather than the compiler** — `-PcgComplete` asks the live provider for the
  member list of four receiver shapes and logs the counts, the classpath they resolved against, and
  whether a named member is present. Added because the two paths *had* diverged silently: every script
  compiled and ran while every popup was empty. A row count says the receiver resolved; the `expect`
  field says the right rows are in it.
- **On a Java 8 JVM** — `smokeEngineBands` (now part of `check`) starts a JVM of each band's own era and
  makes JDT resolve a type *and enumerate its members* there. It is the only place in the build that runs
  on Java 8, which is what a 1.7.10 client is — and the difference between an archive class library and a
  jrt image is invisible everywhere else.
- **The lifetime invariant** — `LiveAnalysisTest.membersResolveThroughAJarAfterTheUnitIsBuilt`. A resolved
  unit resolves its bindings lazily, so the name environment must outlive it. Verified to fail against
  the defect by reintroducing it, which is the only thing that makes a regression test worth having.

## 26.11 What Phase 3 deliberately does not include

- **`mc1201`.** Every mechanism is shaped so the platform-specific part is one `ByteSource`, one
  `MappingCoordinates` and one `NamespaceProbe` — that is the point — but a second loader is its own
  phase.
- **The workspace over the wire.** Phase 4.
- **Sandbox policy for `net.minecraft.*`.** Scripts reaching Minecraft raises a real question about what
  a script may touch. It is a policy decision rather than a mechanism; note it, do not answer it here.
- **Parameter names.** `params.csv` is fetched and parsed, but surfacing parameter names in completion
  and hover is M13's, not this phase's.

## 26.12 Risks

1. ~~**Script classes are defined by our own loader, bypassing FML's deobfuscating transformer.**~~
   **SETTLED: the out direction is readable→SRG, and the CSV pair suffices.** No `SrgFormat`, no
   `packaged.srg`.

   The concern was that a compiled script is `defineClass`'d by `ScriptClassLoader` rather than by
   `LaunchClassLoader`, so FML's transformer never touches it. True, and it does not matter: what the
   transformer has to have touched is the class the script *refers to*, not the script itself.
   `ScriptClassLoader` is `super(parent)` over the **host** loader and its `loadClass` ends in
   `super.loadClass(name, resolve)` — parent-first — so `net.minecraft.world.World` is resolved by
   `LaunchClassLoader` with `FMLDeobfuscatingRemapper` already applied. The class a script links against
   therefore presents **SRG** members, which is exactly what `liveBytes()` reports through the same
   chain. Only the script's own classes are defined by our loader, and those contain no Minecraft names
   to translate — their references are resolved by the JVM against the parent-loaded, SRG-named types.

   Determined by reading `ScriptClassLoader` rather than by running a reobfuscated client, which is what
   made it cheap; 26.8 still confirms it end to end.
2. ~~**ECJ internal API across three bands**~~ — **MEASURED, and it is stabler than feared.**
   `CompilationUnitResolver`'s constructor, its instance `resolve`, the ten-argument `convert` and the
   three flag constants (`RESOLVE_BINDING`=1, `STATEMENT_RECOVERY`=4, `BINDING_RECOVERY`=16) are
   *identical* between 3.26.0 and 3.46.0. 3.46 adds an eleven-argument `convert` overload taking an
   `IJavaProject`, which is exactly why `DomResolution` selects by **arity** rather than by name.
   Compared with `javap` on the staged jars rather than assumed either way, while chasing a defect that
   turned out to be ours. `smokeEngineBands` runs all three bands and is now part of `check`.
3. ~~**Transformer order and exclusions.**~~ **SETTLED, and it cost exactly what the risk predicted.**
   `IClassNameTransformer` is private as a *field* and public as an ordinary entry in
   `getTransformers()`, so `LaunchWrapperBytes.renamerIn` finds it by type with no reflection into
   privates. It was prod-only and it did surface as a wrong answer rather than an exception — every
   Minecraft type simply looked absent, because `getClassBytes` takes the UNTRANSFORMED name and the two
   spellings are identical in dev. Found by `runObfClient` and by nothing else; see the list under
   26.13a.
4. **MCP licensing.** Downloading rather than bundling is what keeps this off the critical path; if the
   terms turn out to permit redistribution, bundling becomes a simplification rather than a requirement.
   It must not block 26.3 or 26.4, which need no mapping data at all.
5. ~~**Cache invalidation against mixins**~~ — **a member is now staged, so this is no longer
   hypothetical.** `MixinMinecraft.cgMixinProbe()` is a real public member merged into `Minecraft` by
   CrystalGraphics' mixins; a script calls it and the editor offers it in both clients. The per-compile
   cache assertion in 26.4 keeps its fake `ByteSource` (it can express "added between two compiles",
   which a live mixin cannot), but the end-to-end case it stood in for is now exercised for real.

## 26.13a Where each exit criterion actually stands

**Every step landed, both clients run the same script, and all eight criteria are met.** Recorded per
criterion rather than as a tick, because two of them carry caveats that are real and are not ours to
close — no upstream digests exist to pin, and nobody has yet run a first launch with the network
actually unplugged. A plan that says "done" without naming those is a plan that lies to whoever reads it
next.

*(Criterion 3 was in that list until 2026-08-19 and is now met in the letter; criterion 2's completion
half was a human check and is now machine-verified as well.)*

| # | Criterion | Status |
|---|---|---|
| 1 | shipped jar opens the editor with analysis working, no system property | **met** — `-PcgBundledEngines` withholds the property; band 8 extracts to `<cacheRoot>/engines/8` and a script compiles and runs |
| 2 | a Minecraft type resolves, completes and hovers in readable names | **was the gap, now addressed.** The runner always resolved; the EDITOR did not, and reported it as red names with no completion and no import quick-fix. Fixed by giving the analyser the same name environment (see the correction under §26.9) and the type index a rename. `LiveAnalysisTest` pins resolution against a class served only through `liveBytes()`; **completion is now machine-verified in the obfuscated client too** (2026-08-19): the `-PcgComplete` probe reports 110 members for `Minecraft.getMinecraft().` under `runObfClient`, in readable names (`displayGuiScreen`, `crashed`, `addGraphicsAndWorldToCrashReport`) on a jar that stores them obfuscated, and identical counts to the dev client for four JDK receivers. Only the *drawn* popup and hover remain a human check |
| 3 | a script referencing a mixin-added member compiles and links | **met, 2026-08-19.** `MixinMinecraft.cgMixinProbe()` is a PUBLIC member merged into `net.minecraft.client.Minecraft` by CrystalGraphics' existing mixin backend — CrystalGUI has none of its own and this phase was right not to add any. A script writing `Minecraft.getMinecraft().cgMixinProbe()` compiles and runs in the dev client and in the reobfuscated one, printing `cg-mixin-live 854x480` from a member that is in no jar, no source tree and nothing a resource lookup can return. **And the EDITOR offers it**: the completion probe reports 111 members for that receiver with `cgMixinProbe()` among them, confirmed independently by a human with the popup open. That distinction is the point — the compiler and the analyser reach the live bytes through different entry points, and a member the compiler accepts but the editor cannot show is one nobody could have written the call to. Previously: the hard half was met and the literal claim was not, because every member CrystalGraphics' mixins added was a private synthetic
| 4 | mappings absent on a clean install, acquired on first use, verified, installed atomically | **met, with one honest gap: no digests are pinned.** Upstream publishes no `.md5` beside `methods.csv`/`fields.csv`, so a pin has to be taken from a trusted fetch rather than invented; until then a corrupted download is caught by the parse rather than by the digest. The machinery is there and tested — `MappingCacheTest` covers corrupt-then-repair and reject-on-mismatch — it is the *data* that is missing |
| 5 | the dev/prod namespace choice is detected, never configured | **met** — dev reports `the runtime already speaks readable names`, obfuscated fetches. No setting selects it |
| 6 | an offline first run opens the editor, runs scripts, shows runtime names, and says why | **◐ partly exercised for real.** A cable has now been pulled: with wifi off the JDK-source fetch failed cleanly, reported *"could not reach api.adoptium.net — check your connection"* in the balloon and the timeline, left the client fully usable, and worked again when the network came back. That run also produced two fixes — the balloon had been showing `java.net.UnknownHostException` verbatim, and the retry loop was spending its backoff re-asking a DNS lookup that cannot change. **The other two fetches were not reached and the harness cannot reach them**: mappings short-circuit to `NOT_CONFIGURED` with no coordinates, and the bands are staged locally so they are always present. Those need `:mc1710:runClient` offline. Worth knowing before that run: **offline has two shapes** — wifi off is a DNS failure and is instant, while a network present but dead (cable in, no route, captive portal) hits the 15s connect timeout, and only the second one can stall a launch |
| 7 | the same script file, unchanged, runs in dev and in a reobfuscated client | **met** — `tile.stone` in both, from one source |
| 8 | `mc1710`'s `ScriptService` contains no logic beyond a byte route, a path and two data objects | **met, and it grew twice.** A `runtimeClassName` rename joined it so completion can offer a class the jar stores under another name — one line, delegating to the same `IClassNameTransformer` the byte route already finds. 33 non-comment lines in `Mc1710ScriptService` (a path and two constants) and 52 in `LaunchWrapperBytes`. The byte route is bigger than "a dozen lines" because production needs the name untransformed first — but it is still one route, and every line of it is about *obtaining bytes*. Nothing about mappings, caching, probing or compilation leaked down here |

### What the reobfuscated client found that nothing else could

Four defects, all invisible in dev and three of them silent. Recorded because the cost of §26.8 was
almost entirely in *reaching* the client, and the value was entirely in what it then showed within
minutes:

1. `runObfClient` could not load the mod at all — GTNH points it at the dev run directory while RFG
   stages into `run/obfuscated/mods`. It loaded three mods and said nothing.
2. `getClassBytes` was asked in the wrong namespace, so every Minecraft type looked absent.
3. `isPackage` could not answer for `net/minecraft/init`, stopping every script that named a
   Minecraft type.
4. The out direction was not applied — and then, once applied, renamed the script's own `run()`.

### What the DEV client found that nothing else could, either

The reobfuscated client earned its section above. The dev client then earned one of its own, and the
defect it found is the more instructive of the two because **every layer was green while it was
present**.

Reported as an empty completion popup. The analyser, the provider on a fresh analysis and on a stale
one, the whole services stack for a unit and for a bare snippet, and the harness through the identical
call — all correct, all answering 46 rows for `System.out.` The client answered 0.

`EcjSourceAnalyzer.live()` built a resolved unit and cleaned its name environment up in a `finally` one
statement later. A resolved unit does not hold its bindings; it resolves them **lazily**, so every member
list was read through a classpath that had already been closed. `FileSystem.cleanup()` closes each
classpath jar and nulls its handle, `ClasspathJar.getModulesDeclaringPackage` rebuilds its cache from
that null and throws, and JDT's DOM **catches it** — `getDeclaredMethods()` logs "Could not retrieve
declared methods" with no stack and returns an empty array. Binary classes reported no methods; their
fields were fine (already resolved) and their interfaces were fine (JDT synthesises those), so
`String.` offered `compareTo` alone and `Minecraft.` offered `IPlayerUsage`'s three.

**It needs a Java 8 host.** From 9 onward the JDK is a JRT filesystem rather than an archive and
`ClasspathJrt` survives the same cleanup, so no test JVM and no harness run on this hardware could
reach it. That is a standing blind spot rather than a solved one: **band 8 is what a 1.7.10 client
runs, and nothing but a 1.7.10 client runs band 8.** `BandDomResolutionTest` drives each band
explicitly and skips the ones whose ceiling is below the host's class library, which makes the gap
visible in the suite rather than merely absent from it.

The instrument that closed it is `-PcgComplete` on `runClient`/`runObfClient`, beside `-PcgScript` and
`-PcgBytes`. When something reproduces only in a client, add a gated probe and bisect with properties;
four layers of passing tests proved nothing.

### Two things this phase changed in the plan itself

- **Step 3 is not achievable as written** (§26.9) — `ASTParser` has no name-environment seam, and the
  internal route is closed by the jar-signing rule rather than by taste.
- **"No owner qualification is needed" holds in one direction only** (§26.6) — readable → runtime is
  ambiguous for about a fifth of the data, and the resolution is the owner rather than `packaged.srg`.

---

## 26.13 Exit criteria

1. A shipped (non-dev) mod jar opens the editor **with analysis working** — bands extracted from the jar,
   no system property set.
2. A script naming a Minecraft type resolves, completes and hovers in **readable names** in the dev
   client; completion never shows `func_147439_a`.
3. A script referencing a **mixin-added member** compiles and links — the claim no file-based classpath
   can satisfy, and the reason 26.4 exists.
4. Mappings are **absent on a clean install and acquired on first use**, verified against pinned digests
   and installed atomically. Deleting the cache and relaunching restores it; corrupting a file in it
   causes a re-download rather than a wrong answer.
5. The dev/prod namespace choice is **detected**, and no configuration selects it.
6. An **offline** first run still opens the editor and runs scripts, shows runtime names, and says why.
7. The same script file, unchanged, runs in dev and in a reobfuscated client.
8. `mc1710`'s `ScriptService` implementation contains **no logic** beyond a byte route, a path and two
   data objects — the test that this is reusable rather than 1.7.10-shaped.

---

## 26.14 The Phase 3 audit

Run before Phase 4 rather than after, and guided rather than swept: the exit criteria say what was
delivered, and an audit asks whether it was delivered in a shape the next milestone can build on. Findings
are recorded here as they land, including the ones that turn out to be nothing.

### Finding 1 — `ScriptServices` was a second platform registry ✅ fixed

Phase 3 gave the language stack a static holder with a setter, and wired it in `ClientProxy` beside
CrystalGraphics' own `CgPlatform.register`. That is a shape this project has deleted once already, and
`AGENTS.md` still carries the invariant: *"CrystalGUI has no platform registry … two registries let a
loader wire up one and not the other: a working GL backend and a dead keyboard, with nothing to report
it."* `CrystalGuiCore`'s four static fields went for exactly that reason; Phase 3 rebuilt the hazard one
layer out.

The obvious repair — a tenth method on `CgPlatformService` — is wrong twice over: it would put a type
only the language stack uses into CrystalGraphics' SPI, and the two registrations happen in **different
Forge mods**, so no single bundle object can carry both however the interface is shaped.

What shipped is `CgService<T>` in `platform/`: a **slot**, declared by whoever owns the contract, carrying
its own name and its own absent-value, filled and read through `CgPlatform.provide` / `get`. The design
turns on one thing — a `Map<Class<?>, Object>` returning an `Optional` puts the fallback at **every call
site**, so N consumers can disagree about what absence means; the fallback is part of the contract, so it
is stated once beside it. Three things then collapse into one object: declaring a slot *is* expecting it
(no separate registration to drift), the value arrives typed, and `CgPlatform.services()` can enumerate
what the platform is carrying — which nothing could answer before.

Absence announces itself **once, from the read**, rather than at a lifecycle checkpoint: a slot only
exists once its declaring class has loaded, so a checkpoint would silently skip exactly the service nobody
had touched. That is the same rule §15.5 already follows about live-versus-inert.

The nine core services deliberately stay a closed bundle with no defaults. Migrating them would trade
compiler enforcement for uniformity, and a graceful absent-value is precisely what `CgPlatformService`
must not have. **Closed for what the framework requires; slots for what its consumers require.**

Costs, stated: `language/` gains `compileOnly` on `com.crystalgraphics:platform` — it had no
CrystalGraphics dependency at all before. Defensible (pure SPI, `core` takes it the same way, present on
every host including a dedicated server) and deliberate rather than noticed later.

### Finding 2 — only band 8 is bundled, and 1.7.10 on Java 17 is a real configuration ◐ decided, not built

`EngineBand.detect()` keys purely on the host JVM and `forFeatureVersion` returns the highest band at or
below it, with **no fallback downward anywhere**. GTNH ships lwjgl3ify and players do run 1.7.10 on 17+.
On such a client `bundledSource()` looks for `assets/crystalgui/engines/17/`, finds no index, and returns
empty — one stderr line, and the whole language stack degrades to grammar-only colouring. The same class
of defect as the one that cost this phase an evening: invisible on the host that builds it.

Shipping all three bands is 41 MB and was rejected. The numbers say why it need not be: the irreducible
core (jdt.core + ecj + rhino) is 8.4–9.3 MB per band, and the rest is Eclipse platform closure we never
touch — **`jna` + `jna-platform` alone are 3.4 MB of band 17**, pulled in by `core.resources`, which is
the workspace layer this engine never opens.

Decided:

- ~~**Trim the closure**~~ — **MEASURED, and not worth doing.** `BandClosureReachabilityTest` walks the
  constant pools outward from the roots we actually load (`jdt.core`, `ecj`, `rhino`) and reports what
  nothing reaches. The answer is **two jars and about 200 KB per band** — `core.commands` and
  `core.expressions` — out of 11–16 MB. **The estimate above was wrong**: JNA and `core.resources` *are*
  referenced from the roots, so "half of it is dead weight" does not survive contact with the data.
  Dropping 1.7% from a signed, version-pinned closure is a bad trade against a reflective load the scan
  cannot see, so the closure stays whole. The test is kept as a report and fails no build.
- **Drop band 11 from the shipped set.** lwjgl3ify targets 17+, vanilla is 8; Java 11–16 on 1.7.10 is not
  a configuration anyone ships. Keep it for dev and tests.
- ✅ **Bundling is a build flag** — `-PcgBundleBands=8` (default), `8,17`, or `none`. `bundleEngineBands`
  and `checkEngineManifest` both work per selected band.
- **Download is the fallback, not the mechanism** — a third `EngineSource` behind
  `firstOf(configured, bundled, downloaded)`, which already returns the first non-empty answer and so
  needs no change. ✅ **Built and exercised end to end**: with `-PcgBundleBands=none` the dev client
  fetched all fifteen of band 8's jars from Maven Central, digest-verified against the shipped manifest,
  and opened the editor — which is the only way to reach that path on a host whose own band is the
  bundled one. Unlike the mapping data these can be digest-pinned properly — not by
  fetching Maven's published `.sha1`, but by **hashing the artifacts Gradle resolved** into a shipped
  resource, which pins the exact bytes the build was tested against and needs no network at build
  time. `CacheFiles.install` already verifies. See P6.1.13 D16.

The progress UI that download needs is planned in
[`CrystalGUI_P6.1.13_PROGRESS_PLAN.md`](CrystalGUI_P6.1.13_PROGRESS_PLAN.md), which also takes the MCP
mapping fetch off the bare `new Thread` it runs on today.

### Carried forward, not fixed

- **`cacheRoot()` is the only client-shaped member** of `ScriptService1710` — it reads
  `Minecraft.getMinecraft().mcDataDir`. The other four are installation-level. So registering in
  `ClientProxy` is *one method deep* rather than inherent to what a `ScriptPlatform` is.
- **`CommonProxy` is empty**, so nothing registers server-side. Not a live defect — `mc1710` runs no
  scripts on a server today — but the plan says a dedicated server runs scripts, and when that lands both
  the registration site and that one method move.

---

## Phase 4 — the network and server layer

**Scoped 2026-08-21.** Phase 4 is **everything network- and server-shaped** across
`CrystalGUI_TODO.md` and `CrystalGUI_P6_TODO.md`, gathered here because it is one subject that those
documents record in a dozen places. Phases 2 and 3 were sketched here and then designed; this is that
step for Phase 4.

> **The ordering is forced, not chosen.** The client/server connection has to be genuinely established
> before the workspace can move onto it. That is not a preference about sequencing — there is currently
> nothing to be remote *over*, and the workspace is the largest consumer of a transport that does not
> exist yet. Building the consumer first would mean designing against a transport whose framing, size
> limit and lifecycle are all still open.

### What is already true

Worth stating, because the protocol is much further along than the absence of networking suggests:

| | State |
|---|---|
| The protocol | **Real.** `net/` ships `UIPacket`, `UIPacketCodec`, `ServerUiSession`, `ClientUiSession`, `RpcRegistry`, `NetworkIds`, `SheetRef`, `UiEventKinds`, with `serialization/` under it |
| The workspace over it | **Real, and running in-game.** `Mc1710Workspace` drives `WorkspaceService` over a genuine session pair; every listing, read and write crosses a packet |
| `UITransport` implementations | **One: `InMemoryTransport`.** There is no Minecraft transport anywhere |
| mc1710 networking | **None.** No channel, no packet handler, no `SimpleNetworkWrapper` |
| `CommonProxy` | **Empty by design** — *"The server-side half: nothing … a dedicated server has no screens"* |

So the gap is not the protocol and not the filesystem. It is the **transport and the session
lifecycle**, plus a server that has never had to do anything.

---

### Stage A — establish the connection

Nothing else in Phase 4 can be validated until this exists.

| # | Item | From |
|---|---|---|
| **A1** | **Measure 1.7.10's custom-payload size limit** and freeze framing on it | P6.1.10 §Minecraft — *"its custom-payload size limit must be checked before chunk sizing freezes"*; also [`plan_prephase4.md`](plan_prephase4.md) item 2 |
| **A2** | **A Minecraft `UITransport`** — a channel plus the codec bridge, sized by A1 | The gap named above; `UITransport` has exactly one implementation today |
| **A3** | **Server-side registration.** `CommonProxy` stops being empty | M12 §26.14 *"carried forward"* — *"`CommonProxy` is empty, so nothing registers server-side … when that lands both the registration site and that one method move"* |
| **A4** | **Session lifecycle** — join opens, leave/disconnect/kick closes, server shutdown drains | New. Nothing models it: today both halves are constructed together and die together |
| **A5** | **`ScriptService1710.cacheRoot()` moves** — it is the one client-shaped member, reading `Minecraft.getMinecraft().mcDataDir` | M12 §26.14 *"carried forward"* |
| **A6** | **Two-session RPC soak** — real traffic over time, desync visible on screen | `CrystalGUI_TODO.md` P3.1, `TODO`. Written for `InMemoryTransport` and *"touches no Minecraft"*, so it can land before A2 and then be re-pointed at the real transport — which makes it Stage A's validation rather than a separate errand |

> **A6 is worth doing early rather than last.** It was deferred as validation that *"nothing downstream
> is waiting on"*, which was true when the transport was in-process. It stops being true here: it is the
> only thing that exercises two sessions over time, and Stage A is precisely where a framing or
> lifecycle bug hides.

### Stage B — move the workspace onto it

The code was built for this. `Mc1710Workspace`'s own javadoc:

> *Both halves of a real workspace, **in the client process** … Shortcutting that would make the later
> phase — the same client against a workspace on a dedicated server — **a rewrite rather than a
> transport swap**.*

| # | Item | From |
|---|---|---|
| **B1** | **Swap the transport.** Same client, same `WorkspaceService`, real connection | P6.1.10; the swap the javadoc above reserves |
| **B2** | **Server-hosted project directories** — the actual vision: files live on the server's machine, singleplayer is the same path because the integrated server *is* one | P6.1.10 §vision — *"This is VS Code Remote, not a file browser"* |
| **B3** | **D11 chunked transfer + manifest resolve.** Gated on A1 | P6.1.10 D11; P6.1.13 — *"Deferred; protocol shape reserved. Hard cap 100 MB"* |
| **B4** | **Permissions on a real server.** `WorkspacePermission.ALLOW_ALL` is what mc1710 passes today | `Mc1710Workspace`; fine for one local player, not for a server |

### Stage C — what only matters once it is remote

Every one of these is currently listed as a known gap and none of them bites while both halves share a
process. From `docs/CGUI_SERVER_AND_SERIALIZATION.md` §8 unless noted:

| # | Item | Note |
|---|---|---|
| **C1** | **No multi-viewer fan-out** — *"One session, one client"* | The first thing a real server invalidates |
| **C2** | **No `TreeDelta`** — a structural change means a new description and a re-open | Explicitly *"a real design problem, not an afternoon"*, because network ids are positional |
| **C3** | **`TabView` does not round-trip** — tabs and panes live in internal containers the description codec does not descend into | A dock over the wire needs this |
| **C4** | **Only seven widgets implement `writeState`/`readState`** | *"a new stateful widget must add them or it will silently arrive blank"* |
| **C5** | `fs.writeDelta`, client cache, `WatchService`, conflict dialog, `fs.rename`/`delete`, resume, multi-user presence | P6.1.10 §"Out — deferred, and each is purely additive" |
| **C6** | **No slots/inventory** — the Minecraft-specific half of a container GUI | §8. Note this is also what would revive the struck platform-tooltip item |

---

### Explicitly not Phase 4

- **mc1201, and the three per-loader filesystems.** All mc1201 work waits until mc1710 is finished —
  including **P3.2**, which remains `BLOCKED — needs a call from you`.
- **`Show Difference`** — needs a Myers diff ported from VS Code's `common/diff/`. Not networking.
- **Translatable text** — pre-Phase-4 item 1, and a platform seam rather than a network one.

### Two things to carry in

Both learned the expensive way in Phase 3 rather than reasoned about in advance:

- **A client is an environment no test reproduces.** Java 8, `rt.jar` instead of a jrt image, a classpath
  a launcher assembled, and a loader that transforms on the way in. Every defect that survived to a
  client in this phase was invisible to the suite *and* to the harness, and each was found in minutes
  once there was a gated probe inside the game. Build the probe early rather than reasoning from source.
  **For Phase 4 the equivalent is a dedicated server, not just a client** — `InMemoryTransport` and a
  singleplayer integrated server will both hide anything that only a real connection does.
- **The compiler and the editor are different consumers of the same seam, and they fail apart.** A script
  that runs is no evidence the popup works, and a popup that lists members is no evidence a script links.
  Anything Phase 4 adds to that seam wants asking twice.
