# M12 — Platform integration: 1.7.10

Detail for the M12 row in `plan_syntax.md` §20. This file covers **Phase 1 only** — getting the
environment breathing. Phases 2+ are sketched at the end and deliberately not designed yet.

> **Phase 1 goal, in one sentence:** press a key in a real 1.7.10 client and `CrystalEditor` opens,
> paints, and can be typed in.

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

## Phases 2+ — sketch only, not designed

- **Phase 2 — the language stack in-game.** `:language` into the jar, native extraction, staged engine
  bands, and the second `enableModernJavaSyntax` fight.
- **Phase 3 — §15.5 A and C.** The live name environment reading post-transform bytes from
  `LaunchClassLoader`, and the 1.7.10 SRG↔MCP mapping data with `params.csv` and its licences settled.
  This is what closes **M6**.
- **Phase 4 — the workspace over the wire**, and `mc1201` after it.
