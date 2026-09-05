# plan_mc1201 — the 1.20.x platform seam

**Status**: plan, written 2026-09-05. Nothing here is implemented.
**Scope**: both repositories. CrystalGUI's `mc1201/` and CrystalGraphics' `mc1201/`, together, because
neither compiles without the other — CrystalGUI's loader `compileOnly` list names four CrystalGraphics
coordinates and one of them is `crystalgraphics-mc1201-common`.
**Supersedes**: `CrystalGUI_TODO.md` §3.2 *"Decide the mc1201 question · BLOCKED — needs a call from
you"*. The call has been made; this is the work.

> **The `plan_cgdevice*` family's D4 recommendation to delete `mc1201/` does not apply and is not
> weighed here.** That plan schedules a device seam *after* the current shipping target is on it, and
> its recommendation rests on "1.20.x is not a shipping target" — which is the premise this plan
> changes, not a finding it has to answer. What survives from that family is factual and is used below:
> `plan_mc26_diagnosis.md` §2.2's host table and §2.3's three gaps were read off the tree and are
> correct.

---

## 0. The conclusion first

1. **The engine side is done and nobody has noticed.** `plan_workbench_rewrite.md` W3 shipped
   `HostServices`, `DesktopHost`, `Connections`, `WorkspaceHost` and `DesktopWindowMount` into `core/`.
   `HostServices` is **four methods**. A second host is now an adapter, not a port of mc1710's 5,361
   lines — the single fact that makes this weeks rather than a quarter.

2. **CrystalGUI's `mc1201/` is a shell, not stale code.** Eleven Java files, of which one is a
   constants class and one is a two-method no-op service; the demo hooks are empty lambdas under a
   `// demo removed;` comment. No screen, no input, no networking, no workspace host. Nothing has to be
   *un*done.

3. **CrystalGraphics' `mc1201/` is real (2,312 lines) and has two services stubbed to zero.**
   `PlatformService1201`'s inline `CgInputService` answers `getCurrentModifiers() == 0`,
   `isKeyDown == false`, `getClipboard() == ""`, and `translateKeyboardCodes` is **identity**. On
   LWJGL3 identity is not a placeholder, it is *wrong*: `GLFW_KEY_A` is 65 and `CgKeyCodes.KEY_A` is
   `0x1E`. Every key would arrive as a different key, and most as no key at all.

4. **The key-code table is the largest mechanical item and it exists nowhere.** `CgKeyCodes` is 131
   constants in LWJGL2/DirectInput scancode numbering — its own javadoc says *"based directly off of
   LWJGL2 keycodes"* — which is exactly why mc1710's `translateKeyboardCodes` is identity. A GLFW host
   must supply the real map, in both directions for the keys `isKeyDown` is asked about.

5. **The input model inverts, and that is the easy direction.** 1.7.10 is a poll loop
   (`while (Mouse.next())`) whose drain ordering cost `MixinGuiScreen` its existence. 1.20.1 is
   `GuiEventListener`'s eight callbacks, each returning "consumed" — verified in
   `research_repos/mc1201_sources/net/minecraft/client/gui/components/events/GuiEventListener.java`.
   The engine already answers exactly that boolean. **No mixin is needed on 1.20.x**, which
   `plan_m16.md` §26.4 predicted and is the whole reason `ScreenOverlay` lives in `core/`.

6. **One coordinate trap will be hit, and it is not the expected one.** There is no Y flip on GLFW —
   `MouseHandler.ypos` is already top-left. The trap is that **`Screen`'s callbacks receive GUI-SCALED
   coordinates**: `MouseHandler` line 89 computes `xpos * getGuiScaledWidth() / getScreenWidth()`
   before dispatching, while `CgSystemInput.Mouse.Event` and `ScreenOverlay.offerMouse` both want
   **raw surface pixels**. Read `mc.mouseHandler.xpos()/ypos()` and ignore the callback's doubles.

7. **The build is closer than the source.** Root `gradle.properties` already carries every `mc1201.*`
   and `mc1204.*` pin; `composite.settings.gradle.kts` already declares `mc1201CompileDeps`;
   `integration.gradle.kts` already branches on `:mc1201`; `mc1201/build-logic/` has three convention
   plugins; the loader scripts already bundle `:core` and `:mc1201:common`. Four `include` lines per
   repo plus a `pluginManagement` block and it configures.

8. **But the loader bundles four things too few, and one is fatal.** mc1710's shadow jar carries
   `:core`, `:language`, `:taffy`, the three engine bands, ASM (relocated) and JOML. mc1201's carries
   `:core` and `:mc1201:common`. **Without `:taffy` there is no layout engine**, and it fails at class
   load rather than at a call site — `UIElement` holds a Taffy `NodeId` as a *field*, and field
   descriptors resolve when the class is defined.

9. **`isPauseScreen()` must return `false`, and it is a deadlock rather than a preference.** Already
   paid for once on 1.7.10: pausing stops `MinecraftServer.tick`, so the integrated server never pumps
   the connection, so every `fs/*` call dies at its 10 s timeout and the workspace is empty with
   nothing in the log. Same mechanism, version-independent cause, and invisible on a dedicated server —
   which is the configuration nobody tests the wire in.

10. **Nothing here needs a new engine capability.** Every item is a loader-side adapter, a translation
    table, a build line, or a decision already recorded in another plan. The one genuinely open
    question is scripting (§3.7), and its honest answer is "degrade, and say so".

---

## 1. What exists today — measured

Read 2026-09-05 from both working trees on `rewrite` @ `ec09c288`.

### 1.1 The modules

| | CrystalGUI | CrystalGraphics |
|---|---|---|
| `mc1201/build-logic` | `cg-java17`, `cg-mc1201-common`, `cg-mc1201-loader` + `ShadowUtils.kt` | its own equivalent |
| `mc1201/common` | 2 files, 0 real | **10 files, ~1,400 lines** — `PlatformService1201`, `GL1201Backend`, `GL1201Context`, five services, `Blaze3DStateProvider` |
| `mc1201/forge` | 3 files (entrypoint + 2 event classes, one gutted) | 3 files, same shape |
| `mc1201/neoforge` | 3 files — **targets MC 1.20.4**; NeoForge 20.1.x was never published | 3 files, same |
| `mc1201/fabric` | 3 files | 3 files |
| In `settings.gradle.kts` | `//includeBuild("mc1201")` — **wrong form, could never work** | `//include(":mc1201:common")` ×4 — right form, commented |
| Last substantive commit | `2a10724c`, 2026-07-10, *"might not build, srry for pushing W.I.P"* | — |

**`//includeBuild("mc1201")` is the mc1710 trap repeating.** `plan_m12.md` §25.2 recorded it in full:
`includeBuild` requires a standalone Gradle build with its own settings file, and
`mc1201/settings.gradle.kts` does not exist and never has. The loader scripts already say
`project(":mc1201:common")` and `integration.gradle.kts` already branches on
`project.path.startsWith(":mc1201")` — both only true of **plain subprojects**. The commented line is
aspirational, exactly as `//includeBuild("mc1710")` was.

### 1.2 CrystalGUI's `mc1201/` in full

| File | Lines | What it is |
|---|---|---|
| `common/…/CrystalGUI1201.java` | 26 | `MODID`, `NAME`, `VERSION` constants |
| `common/…/CgPlatformService1201.java` | 47 | Singleton with `onReload()` and `onContextDestroy()` — **both empty, both TODO** |
| `{forge,neoforge,fabric}/…/CrystalGUI1201*.java` | ~20 each | Entrypoint: `getInstance()`, register the two event classes, log |
| `…/CgEngine*Events.java` | ~70 each | Reload listener → `onReload()`; CrystalGraphics' two render passes; shutdown → `onContextDestroy()` |
| `…/CgDemo*Events.java` | ~46 each | **Gutted** — `// demo removed;`, an empty HUD lambda, an empty scroll callback |

There is no CrystalGUI *UI* anywhere in it. It registers CrystalGraphics' render lifecycle and nothing
else. That is the entire module, and it is why this plan is mostly "write", not "port".

### 1.3 CrystalGraphics' `mc1201/`, and its two holes

`PlatformService1201` implements all nine `CgPlatformService` methods with lazy, interface-typed fields
— the shape `plan_phase5.md` §5.10 imposed after finding that the eager version dies on a dedicated
server at `GL1201Context`'s `private volatile GLCapabilities caps` field descriptor. **That fix has
never been compiled.** §5.10 says so outright: the only check available was a parse against the
`platform` sourcepath. L0 is what finally proves it.

| Service | mc1201 state |
|---|---|
| `gl()` | `GL1201Backend` — real; 3-tier `RenderSystem` › `GlStateManager` › raw `GL*C`; alpha test and matrix stack **throw** |
| `capabilities()` | `GL1201Context` — real |
| `resources()`, `rendering()`, `lifecycle()`, `reload()`, `cursor()` | real |
| **`input()`** | **stub** — zeros, `false`, `""`, identity translation |
| **`sound()`** | **stub** |

And, from `plan_mc26_diagnosis.md` §2.2–2.3 and confirmed here: `Blaze3DStateProvider` is **written and
never installed**; `onResize` and `onFrameRendered` are **not wired**; all three mixin JSONs have empty
`mixins`/`client` arrays.

### 1.4 What the plan docs already decided

Every mc1201 statement in either tree, and what it is worth now.

| Document | Says | Standing |
|---|---|---|
| `CrystalGraphics/docs/BUILD_SETUP.md` §mc1201 | The toolchain: fabric-loom 1.16.2 (and why not architectury-loom), `moddev.legacyforge` 2.0.141 for Forge, `moddev` 2.0.141 for NeoForge-on-1.20.4, mandatory `configuration-cache=false`, ≥3 GB heap, `extractMcSources` | **Authoritative.** §4 only adds what CrystalGUI needs on top |
| `CrystalGraphics/AGENTS.md` | The `mods{}` + `shadowJar` double-declaration rule; *"Fabric is NOT exempt"*; cross-platform checklist steps 5–11 | **Authoritative** |
| `plan_m12.md` §25.2 | The mc1710 build revival — include form, `pluginManagement`, the two-`includeBuild` conflict | **The template**; §4.1 applies it verbatim |
| `plan_m12.md` §25.9 + root `gradle.properties` | `--max-workers=1` for anything building an RFG project; the intermittent `mergeVanillaSidedJars` race | Applies to any run that touches `:mc1710` **and** `:CrystalGraphics:mc1710` |
| `plan_m16.md` §26.4 | The per-version screen/overlay mechanism table, incl. 1.20.1 Forge/NeoForge `ScreenEvent.*` and Fabric `ScreenEvents.*`; *"adding them is writing a handler per row of that table"* | **The design for L4**, unchanged |
| `plan_phase5.md` §5.10 | `PlatformService1201`'s eager-construction fix, explicitly unverified | **Debt discharged at L0** |
| `plan_wire.md` §1 | The 1.7.10 / 1.20.1 packet-ceiling table — 1.20.1 answers ~1 MB | Input to `maxFrameBytes()` in L5 |
| `plan_ui_host.md` §II | 1.7.10 vs 1.20.1 container/menu comparison | Background for L5 |
| `plan_workbench_rewrite.md` §4.8 | `HostServices` + `DesktopHost`; *"a loader implements `HostServices` and nothing else"* | **The seam this plan implements against** |
| `plan_prephase4.md`, `plan_ui_rewrite.md` D10, `plan_m16.md`, `plan_phase5.md` | "mc1201 waits", "1.7.10 only", "not this milestone" | **Lifted by this document** |
| `CrystalGUI_TODO.md` §3.2 | "BLOCKED — needs a call from you" | **Closed by this document** |

---

## 2. The seam: everything a host must supply

Two reference implementations exist and they disagree usefully. **mc1710** is the complete one. **The
harness** is the one that shows which parts are Minecraft's and which are merely assembly: it builds
`new UIDocument().markFrameThread()`, `Desktop.of(document)`, a `LocalConfigStorage` and a
`HarnessWorkspace` over an `InMemoryTransport` by hand, because it predates W3 and does not use
`DesktopHost` at all. **Anything the harness does by hand is engine assembly and belongs to
`DesktopHost`, not to a loader.**

### 2.1 `HostServices` — four methods, and the measure is what it does not ask

```java
Path   configDirectory();                            // arrangement, session, backups — beside the workspace, never inside
float  uiScale();                                    // device pixels per logical pixel
String desktopId();                                  // which desktop, for the arrangement record
@Nullable ProtocolConnection<Object> connection();   // re-asked EVERY FRAME; null is a supported state
```

Not asked: the window's title, key, icon, close policy, first-run geometry, which application to open,
when to request the project list. Every one is the same answer on every host, so the engine decides it.
mc1710's implementation is twenty lines.

**`connection()` is re-asked, never pushed** — a reconnect is a new object carrying the same workspace,
and `DesktopHost.frame` rebinds rather than rebuilds so every retained window keeps pointing at a live
client. A 1201 host returns its `Connections.client()`.

### 2.2 `DesktopHost` — the lifecycle a host drives

```java
static DesktopHost create(HostServices);   // once, with a GL context live
void shown();                              // the screen opened   → Screen.init()
void frame(float deltaSeconds);            // every frame         → Screen.render(...)
void hidden();                             // the screen closed   → Screen.removed()
void dispose();                            // game shutdown
```

Plus `document()`, `desktop()`, `config()`, `workspace()`, `windowMount()`, `setWindowMount(Supplier)`.

**`hidden()` is not `dispose()`.** The desktop, its windows and every unsaved document survive a screen
close and come back exactly as they were; only `dispose()` takes them down. That is what lets a game
screen be closed and reopened without losing work, and it is why a 1201 `Screen` — which Minecraft
constructs fresh on every display — must keep its `DesktopHost` in a `static`.

> **A static "do this on open" flag must be CONSUMED.** `Screen.init()` re-runs on **every window
> resize** exactly as 1.7.10's `initGui` does, and `CgUiScreen`'s F6 flag stayed true for the rest of
> the session — so every later resize re-ran "bring the editor forward", and windows the player had
> closed came back. The invariant is version-independent; the 1201 screen inherits it.

### 2.3 `ScreenOverlay` — pinned windows over a foreign screen

Already in `core/`, already version-independent, already carrying the arbitration: an active pointer
capture outranks the hit test; a press outside returns the keyboard, blurs the focus owner and
light-dismisses; a move is always delivered and never consumed; the button is passed through, never
clamped (`Math.max(0, button)` turns every move into a left-button *release* and cancels every drag on
its first pixel). A loader supplies primitives and honours the boolean:

```java
boolean offerMouse(int xPx, int yPx, int button, boolean pressed, float wheel); // top-down Y, RAW pixels
boolean offerKey(int keyCode, char typed, boolean pressed);
void    onForeignScreenChanged(boolean open);
```

### 2.4 The nine CrystalGraphics platform services

`CgPlatformService` has **no defaults, deliberately** — its javadoc explains that a default is an answer
chosen for someone who never saw the question, and that a bundle silently inheriting "no sound, no
cursor" is indistinguishable from one that decided on it. Seven are done on mc1201; `input()` and
`sound()` are stubs (§1.3).

`CgInputService` is the one CrystalGUI leans on hardest, and it is also the **clipboard** — on this
interface rather than one of its own because two methods do not earn a registration slot:

```java
int     getCurrentModifiers();              int  translateKeyboardCodes(int platformCode);
boolean isKeyDown(int localKeyCode);        int  translateMouseCodes(int platformCode);
boolean isMouseDown(int localMouseCode);    int  howManyMouseButtons();
String  getClipboard();                     void setClipboard(String text);
```

### 2.5 `CgNetworkChannel` — five methods

```java
int     maxFrameBytes();
void    sendToServer(byte[] frame);
void    sendToPlayer(Object player, byte[] frame);
void    setInboundHandler(BiConsumer<Object, byte[]> handler);
boolean isAvailable();
```

### 2.6 `ScriptService` — the language platform seam

`com.crystalgui.language.platform.ScriptService`, implemented on 1.7.10 by `ScriptService1710`:
`liveBytes()` (a `ReadableView.ByteSource`), `runtimeClassName(String)`, `cacheRoot()`, `mappings()`
(`MappingCoordinates`), `namespaceProbe()`. See §3.7 — the one item with no clean answer.

### 2.7 The complete checklist

| Seam | mc1710 | Harness | mc1201 today | Milestone |
|---|---|---|---|---|
| `CgPlatformService` bundle | `PlatformService1710` (CG) | `PlatformServiceHarness` | `PlatformService1201` (CG), 2 stubs | L0/L1 |
| `CgGLBackend` / `CgGLContext` | `Lwjgl2GLBackend` | `Lwjgl2GLBackend` | `GL1201Backend`/`Context` ✅ | L0 (compile only) |
| `CgInputService` | `InputService1710` | harness impl | **stub** | **L1** |
| `CgSoundService` | `SoundService1710` | harness impl | **stub** | L1 |
| `CgCursorService` | `CursorService1710` | harness impl | `CursorService1201` ✅ | — |
| Key-code translation | identity (LWJGL2 *is* the vocabulary) | identity | identity — **wrong** | **L2** |
| Raw event → `CgSystemInput.*.Event` | `CgUiInput.pumpMouse/pumpKeyboard` | `InteractiveSceneRunner.pollInput` | absent | **L2** |
| Open-desktop keybinds (F6/F7) | `CgUiInput.Handler`, FML bus | scene keys | absent | L2 |
| `HostServices` | `CgUiScreen.Mc1710Host` | **none** — assembles by hand | absent | **L3** |
| The screen | `CgUiScreen extends GuiScreen`, 692 lines | `CgUiDesktopScene` | absent | **L3** |
| GL handoff around paint | `CgGlState.invalidateAllIfPresent()` either side | runner | absent | L3 |
| Frame clock for node previews | `CgRenderPipeline…timeSecs` | scene | absent | L3 |
| HUD / pinned windows | `CgUiHud` + `RenderGameOverlayEvent` | n/a | absent | **L4** |
| Overlay input over a foreign screen | `MixinGuiScreen` + `CgUiOverlayInput` | n/a | **not needed** — events exist | L4 |
| `DesktopPresentation` decision | `CgUiHud.presentation()` | n/a | absent | L4 |
| `CgNetworkChannel` | `Mc1710NetworkChannel` (`FMLProxyPacket`) | `InMemoryTransport` | absent | **L5** |
| Peer identity | `Mc1710Peer` (GameProfile UUID) | n/a | absent | L5 |
| Connection lifecycle | `CgUiConnections` — 5 events + 2 ticks | n/a | absent | L5 |
| Server workspace host | `CgUiWorkspaceHost` — root, permission, `actorFor` | `HarnessWorkspace` | absent | L5 |
| `ScriptService` | `ScriptService1710` + `LaunchWrapperBytes` | none | absent | **L6** |
| Mod entrypoint / proxies | `CrystalGUI`, `ClientProxy`, `CommonProxy` | `main()` | 3 entrypoints, inert | L7 |
| Probes / smoke | 7 probes + `CgUiServerSmoke` | scenes | absent | L7 |
| Example content | `MachineExample(+Client)` | scenes | absent | L7, optional |

---

## 3. The version deltas that decide the work

Everything below was verified against `research_repos/mc1201_sources/` (checked in) rather than
recalled. Where a loader API is named and not verified, it is marked **[verify]** and the milestone
that owns it must read the extracted sources in that module's `build/mc-src/` first.

### 3.1 Input: a poll loop becomes callbacks, and that is a simplification

1.7.10 delivers input through `GuiScreen.handleInput()`, called from `Minecraft.runTick` — which is
driven by `new Timer(20.0F)`, so **a screen's input is pumped at 20 Hz while it renders at 60+**.
`CgUiScreen` works around it by draining the same queue from `drawScreen` instead, and `MixinGuiScreen`
exists solely because whoever drains the LWJGL queue first is the only one who sees it.

1.20.1 has none of that. `GuiEventListener` declares, all returning consumed:

```java
void    mouseMoved(double x, double y);
boolean mouseClicked(double x, double y, int button);
boolean mouseReleased(double x, double y, int button);
boolean mouseDragged(double x, double y, int button, double dx, double dy);
boolean mouseScrolled(double x, double y, double delta);
boolean keyPressed(int keyCode, int scanCode, int modifiers);
boolean keyReleased(int keyCode, int scanCode, int modifiers);
boolean charTyped(char codePoint, int modifiers);
```

These are dispatched per event, at the frequency GLFW delivers them, and the return value is exactly
what `Input.consumeMouseEvent` / `consumeKeyboardEvent` already answers. **So the 1201 screen has no
pump, no mixin, and no tick-rate problem** — three of mc1710's hardest-won pieces are simply absent.

**What it costs instead is that key and character are two events.** LWJGL2 delivers
`(character, key, state)` together; GLFW splits `keyPressed` from `charTyped`. `CgSystemInput.Keyboard.
Event(char character, int key, boolean pressed, boolean repeat, long millis)` wants both. The rule:
`keyPressed`/`keyReleased` send the event with `character = 0`, `charTyped` sends one with
`key = CgKeyCodes.KEY_NONE` (`0x00`, which the constant exists for: *"what a character-only event
carries"*). A widget that types text reads the character; a keymap reads the key. **Do not synthesise a
character on `keyPressed`** — it is layout-dependent and GLFW has already done it correctly.

**Key repeat needs no flag.** `Keyboard.enableRepeatEvents(true)` has no 1.20.1 equivalent —
`setSendRepeatsToGui` does not exist anywhere in `net/minecraft/client/` in the extracted tree — because
repeats are delivered to screens unconditionally. GLFW's repeat action maps to the event's `repeat`
flag. **[verify]** which action constant arrives, at L2, by logging one held key.

### 3.2 Key codes: the table nobody has written

`CgKeyCodes` is 131 `public static final int` constants in DirectInput/LWJGL2 scancode order
(`KEY_ESCAPE = 0x01`, `KEY_1 = 0x02`, … `KEY_Q = 0x10`). GLFW uses USB-HID-ish values with printable
ASCII for letters and digits. They agree on **nothing**.

`translateKeyboardCodes(int platformCode)` is the seam and it is currently identity on mc1201. It needs:

- a `GLFW_KEY_* → CgKeyCodes.KEY_*` map for every one of the 131 constants that has a GLFW counterpart;
- an inverse for `isKeyDown(int localKeyCode)`, which is asked in *our* vocabulary and must call
  `InputConstants.isKeyDown(window, glfwCode)`;
- `getCurrentModifiers()` from the live key state, matching `CgModifiers`' bitmask — mc1710 reads
  LSHIFT/RSHIFT, LCONTROL/RCONTROL, LMENU/RMENU and ors three bits;
- `translateMouseCodes` — identity is very likely correct (GLFW left/right/middle are 0/1/2, as LWJGL2)
  but **[verify]**, because `howManyMouseButtons()` has no GLFW equivalent and must return a constant.

**Write it as a table, not a `switch`, and test it in `headlessTest`.** It is pure arithmetic over two
int vocabularies with no MC and no GL — the one part of this whole plan that is unit-testable, and the
part where a silent single-row error costs a day. A round-trip assertion (`inverse(translate(k)) == k`
for every mapped key) is the counter-control that a hand-written table needs.

**Do not put the table in `mc1201/common`.** `CgKeyCodes` is CrystalGraphics `platform`, GLFW is
LWJGL3, and every loader needs the same map — it belongs beside `InputService1201` in CrystalGraphics'
`mc1201/common`, where `PlatformService1201` already is.

### 3.3 Coordinates, wheel units, and the trap

| | 1.7.10 (LWJGL2) | 1.20.1 (GLFW) |
|---|---|---|
| Mouse origin | **bottom-left** — `CgUiInput` flips every Y and dY | **top-left already** — no flip |
| Coordinate space available | `Mouse.getEventX/Y()` = raw device pixels | `MouseHandler.xpos()/ypos()` = raw; **`Screen` callbacks = GUI-scaled** |
| Wheel | notches ×120; `CgUiInput` divides by 120 **and negates** | GLFW `yoffset`, ±1.0 per notch; `mouseScrolled` passes it through |
| Surface size | `mc.displayWidth/Height` | `window.getScreenWidth()/getScreenHeight()` — **not** `getWidth/getHeight`, and never `getGuiScaledWidth/Height` |

**The trap.** `MouseHandler` line 89 (and 150, and 237) computes
`xpos * getGuiScaledWidth() / getScreenWidth()` before calling into the screen. So `mouseClicked`'s
doubles are in GUI-scale space at whatever the player's GUI Scale setting is, while the engine wants raw
pixels and applies its own `uiScale` on the box tree's root transform. Multiplying back is lossy and
wrong at fractional scales. **Read `mc.mouseHandler.xpos()/ypos()` inside the callback** and use the
callback's doubles for nothing but ordering.

This is the same class of error as the 1.7.10 note that a pump must use `mc.displayHeight` and never
`GuiScreen.height`, which is "the scaled GUI size and would put the pointer off by the scale factor" —
and it is worse here, because at the default GUI Scale of 2 a click lands at half the distance and
*looks* like a plausible UI bug rather than a coordinate one.

**The wheel sign is a separate decision and must be re-derived, not copied.** A *positive*
`MouseEvent.Scroll` means the wheel rolled **down** — the engine's only statement of that is
`ScrollerView.setScrollTop(before + delta)`, and `CanvasView` once shipped zooming the wrong way
because a test was written from the implementation. mc1710 reaches "positive means down" by
`1/120f × -1`. GLFW's `yoffset` is **positive when scrolling up**, so the 1201 factor is `-1.0f` with
no divisor — **[verify] at L2 by scrolling a `ScrollerView` and watching which way it goes**, not by
reasoning.

### 3.4 `uiScale()` is a real question on 1.20.x and was a constant on 1.7.10

mc1710 returns `DEFAULT_UI_SCALE`, a constant. On 1.20.x the player has a GUI Scale setting and
`window.getGuiScale()` reports it as a double. Two defensible answers:

- **Return `(float) window.getGuiScale()`** — the desktop matches the rest of the game's chrome, and the
  player's accessibility setting is honoured. Costs a re-layout when it changes.
- **Return a constant** — the desktop is its own environment, like a real OS on a HiDPI display.

**Recommendation: follow `getGuiScale()`.** The engine already applies it once at the box tree's root
transform, hit-testing inverts the same matrix, and `HostServices.uiScale()` is re-read by
`DesktopHost`, so a change is a re-layout and not a rebuild. A constant that disagrees with every other
GUI in the game reads as a bug. **The one thing that must not happen is scaling the paint pose by hand**
— that is `BoxTree.setRootTransform`'s job, and pushing it onto the pose gives a perfect picture whose
clicks land at half the distance.

### 3.5 Screens: `GuiScreen` becomes `Screen`, and the Escape cascade survives

| Concern | 1.7.10 | 1.20.1 (verified) |
|---|---|---|
| Base | `GuiScreen` | `Screen extends AbstractContainerEventHandler implements Renderable` |
| Open | `initGui()` | `init()` |
| Paint | `drawScreen(int, int, float)` | `render(GuiGraphics, int, int, float)` |
| Close | `onGuiClosed()` | `removed()` |
| Pause | `doesGuiPauseGame()` | `isPauseScreen()` — **must return `false`**, see §0.9 |
| Escape | vanilla `keyTyped` closes unconditionally; `CgUiScreen` overrides it empty | `shouldCloseOnEsc()` — **return `false`** and let Escape reach the window |
| Display | `mc.displayGuiScreen(x)` | `mc.setScreen(x)` **[verify]** |

**`shouldCloseOnEsc()` returning `false` is the clean version of mc1710's empty `keyTyped` override.**
Escape is a cascade in this engine — a live drag eats it, then the topmost popover, then a modal, then a
close watcher — and vanilla closing the screen sits above all of it. The 1201 screen returns `false` and
closes itself only on an Escape that `consumeKeyboardEvent` reported as **not** consumed. The sense was
inverted once on 1.7.10 and the symptom was precise: Escape on bare desktop did nothing, Escape in the
editor closed a popup *and* the whole screen.

**Closing must hand the mouse back.** mc1710 calls `mc.setIngameFocus()` explicitly because
`displayGuiScreen(null)` only does so down a branch that also requires a live world and player, and the
close happens mid-frame from inside the screen being closed. **[verify]** whether `setScreen(null)` on
1.20.1 has the same conditional; if it does, the same explicit call is needed.

### 3.6 The render handoff

mc1710 brackets `desktop.paint(...)` with `CgGlState.invalidateAllIfPresent()` on both sides and then
explicitly restores `GL_TEXTURE0`, program 0 and `GL_TEXTURE_2D`, because Minecraft drew with
fixed-function state and will assume the same next frame.

On 1.20.x there is no fixed-function state to restore, `GL1201Backend`'s alpha-test and matrix-stack
paths **throw**, and `Blaze3DStateProvider` exists precisely to tell `CgGlStateManager` what Blaze3D
believes. Three consequences:

1. **Install `Blaze3DStateProvider`.** It is written and never installed; without it the state manager's
   shadow takes its truth from `glGet` defaults and will elide calls Blaze3D has changed underneath it.
   That produces a *missing GL call* — wrong rendering, no exception.
2. **The alpha-test invariant does not apply and must not be ported.** `AGENTS.md` records that 1.7.10's
   host leaves `GL_ALPHA_TEST` on with a 0.1 reference, which cuts every UI fragment under 10 % alpha,
   and that CrystalGUI disables it twice per frame. On a core profile there is no such state; the two
   disables would throw through `GL1201Backend`. **This is a real code difference, not a no-op.**
3. `CgGlState.invalidateAllIfPresent()` still brackets the paint, for the same reason it does in the
   harness: something outside `CgGL` wrote GL state.

**`onResize` and `onFrameRendered` must be wired** (§1.3). Without `onResize` the screen-sized FBO
registry never recreates its targets and the UI is rendered at the previous window size after any
resize — which reads as a scaling bug.

### 3.7 Scripting: `LaunchWrapper` has no 1.20.x analogue

`ScriptService1710` gives the language stack three things: the **live bytes** of a runtime class
(through `LaunchWrapperBytes`, which reaches `LaunchClassLoader.getClassBytes` and the private
`IClassNameTransformer`), the **runtime name** for an on-disk internal name, and the **mapping
coordinates**. All three are LaunchWrapper-shaped, and 1.20.x has ModLauncher (Forge/NeoForge) or Knot
(Fabric) instead.

Two of the three are genuinely harder there and the third is easier:

- **Live bytes.** ModLauncher's `TransformingClassLoader` does not expose a "give me the transformed
  bytes for this name" call the way LaunchWrapper does. Knot exposes even less.
- **Runtime names.** 1.20.x dev runs are already deobfuscated (official Mojang mappings, Parchment
  parameter names), and production is SRG/intermediary. The mapping direction differs per loader.
- **The mapping data.** Official Mojang mappings are downloadable and unambiguous, which is *easier*
  than 1.7.10's MCP problem — `plan_m12.md` §26 spends pages on the 357 method and 329 field names that
  are ambiguous in `mcp_stable/12`, and none of that recurs.

**Recommendation: L6 registers no `ScriptService` on 1201 at first, and says so.** The language stack is
designed to degrade in three independent tiers — no engine → grammar colouring, no grammar →
`KeywordTokenizer`, neither → plain text — and *absence is a supported configuration*. What is not
supported is silence: the standing rule is that *"live and inert look identical, so a capability that
can be silently skipped must SAY it is on"*, and the same applies to it being off. One line at
registration naming the reason. Then §15.5-style live resolution is a follow-up with its own plan.

**And `LanguageStack` must still be bootstrapped**, even with no `ScriptService` — it is a
`LanguageKinds` service found by `LanguageRegistry.bootstrap()`, and a host calls it explicitly only to
pay its 443 ms where nobody is waiting. A 1201 loader with a loading screen should call it there.

### 3.8 One platform implementation for all three loaders

**Yes — and the part that cannot be shared is smaller than it looks.** Everything that *decides*
anything goes in `mc1201/common`; what stays per loader is registration, and registration only.

#### 3.8.1 Why this works here and did not on 1.7.10

mc1710's platform code is unshareable because FML *is* the API: `CgUiConnections` reaches
`FMLNetworkEvent`, `CgUiInput` reaches `ClientRegistry` and the FML bus, `CgUiScreen` extends
`GuiScreen`, and there is no second loader to share with anyway.

On 1.20.x almost the whole surface this plan needs is **vanilla**. `Screen`, `GuiGraphics`,
`GuiEventListener`, `MouseHandler`, `Window`, `KeyboardHandler`, `Minecraft`, `ServerPlayer`,
`MinecraftServer`, `GameProfile` — every one is `net.minecraft.*` or `com.mojang.*`, identical on
Forge, NeoForge and Fabric, and `mc1201/common` already has them on its compileOnly classpath. The
loader only appears at the **edges**: how you are told a thing happened, and how you send bytes.

#### 3.8.2 The five things that genuinely cannot be shared

| # | What | Forge 1.20.1 | NeoForge 1.20.4 | Fabric 1.20.1 |
|---|---|---|---|---|
| 1 | **Entrypoint** | `@Mod` class | `@Mod` class | `ClientModInitializer` / `ModInitializer` |
| 2 | **Event subscription** | `MinecraftForge.EVENT_BUS`, `@SubscribeEvent` | `NeoForge.EVENT_BUS.addListener` | Fabric API callbacks + GLFW chaining |
| 3 | **Networking transport** | `SimpleChannel` | payload registrar | `Client/ServerPlayNetworking` |
| 4 | **Keybind registration** | `RegisterKeyMappingsEvent` **[verify]** | equivalent | `KeyBindingHelper` |
| 5 | **The current server** | `ServerLifecycleHooks.getCurrentServer()` | same | captured from a lifecycle event |

That is the whole list. Note what is *not* on it: the screen, input translation, the key table,
`HostServices`, the paint bracket, the presentation decision, `ScreenOverlay` plumbing, peer identity,
the connection table, the workspace host, frame codecs, and every rule this plan spends §3.1–§3.7 on.

#### 3.8.3 The shape — an SPI in `common`, one implementation per loader

The same inversion CrystalGraphics already uses for `CgPlatformService`, applied one level up. `common`
declares what it needs and never names a loader; each loader implements it and registers itself.

```java
// mc1201/common — names net.minecraft.* and nothing from any loader
public interface LoaderBridge {
    String loaderName();                                   // for the log line, and only that
    CgNetworkChannel channel();                            // the five methods of §2.5
    @Nullable MinecraftServer currentServer();
    void openScreen(Screen screen);                        // setScreen, wherever it lives
    boolean isDedicatedServer();
}
```

…found the way every other capability in this codebase is found:

```java
// common
LoaderBridge bridge = ServiceLoader.load(LoaderBridge.class).findFirst().orElseThrow();
```

`ServiceLoader` rather than a `register()` call or reflection, for the reason `NodeKinds`,
`LanguageKinds`, `ApplicationKinds` and `WorkbenchExtension` all use it: **the contents become a
function of the classpath rather than of a hand-written list**, and a loader jar that forgets to
register fails loudly at startup instead of silently doing without. It works on all three — the service
file and its implementation are in the same jar, so Knot's classloader isolation never comes into it.

Everything else flows the other way, as a plain call into common:

```java
// forge — the WHOLE client event class, in shape
@SubscribeEvent static void onRenderGuiOverlay(RenderGuiOverlayEvent.Post e) { Mc1201Client.paintHud(); }
@SubscribeEvent static void onScreenOpen(ScreenEvent.Opening e)              { Mc1201Client.foreignScreenChanged(true); }
@SubscribeEvent static void onScreenMouse(ScreenEvent.MouseButtonPressed.Pre e) {
    if (Mc1201Client.offerMouse(...)) e.setCanceled(true);
}
```

The handler bodies live in `Mc1201Client`; the loader class is a list of one-line forwards. That is
what `plan_m16.md` §26.4 means by *"writing a handler per row of that table"*.

#### 3.8.4 The enabling piece, and it is missing today

**`mc1201/common` applies `net.neoforged.moddev.legacyforge` with `version = "1.20.1-<forge>"`, so
MinecraftForge is on its compileOnly classpath — and there is no import guard.** So today a class in
`common` can import `net.minecraftforge.*`, compile green, ship in the Fabric and NeoForge jars, and
throw `NoClassDefFoundError` on two loaders out of three. Its own build script even says *"the
Forge-specific classes are compileOnly; they never appear in the common JAR or runtime"* — true of the
jar, and no protection at all against a source file naming one.

That is the exact shape `core/`'s guard exists to prevent, and the fix is the same mechanism: a
`doLast` on `compileJava` that fails the build on any line importing `net.minecraftforge.`,
`net.neoforged.` or `net.fabricmc.` — while **permitting** `net.minecraft.` and `com.mojang.`, which is
the whole difference from `core/`'s list. Twenty lines, copied from `core/build.gradle.kts`.

**Without the guard, "one platform package" is a convention nobody can check; with it, it is a
property the build enforces.** It goes in L0, before a line of platform code is written — the same
ordering argument as the import guard `core/` already has, and the reason `plan_phase5.md` §5.10 could
only *parse* its fix rather than prove it.

#### 3.8.5 The measured split

| | `common` | per loader (×3) |
|---|---|---|
| Screen, input, key table, `HostServices`, paint | ~900 | 0 |
| HUD / overlay / presentation | ~250 | ~60 each |
| Connections, peer, workspace host | ~350 | ~50 each |
| Network channel | 0 (the SPI) | ~90 each |
| Entrypoint, keybinds, reload listener, server accessor | 0 | ~65 each |
| **Total** | **≈1,500** | **≈265 each, ≈800 for three** |

Roughly **two-thirds in `common`, and the remaining third is three copies of the same thin list of
forwards.** For comparison, mc1710 is 3,400 production lines with no sharing available at all.

#### 3.8.6 The one thing that threatens it, and it is worth checking first

**The three modules are not on one Minecraft version.** `mc1201/neoforge` targets **1.20.4**, because
this repository determined — twice, in `BUILD_SETUP.md` and in `mc1201/common/build.gradle.kts` — that
NeoForge never published a 20.1.x series. So `common` is compiled against 1.20.1 and consumed by a
1.20.4 module, and *the more that lives in `common`, the more load that assumption carries*.

For the surface this plan uses it should hold — `Screen`, `GuiGraphics`, `MouseHandler`, `Window`,
`ServerPlayer` are stable across 1.20.1→1.20.4 — but it is an assumption with a deadline rather than a
guarantee, and it is the only reason not to put everything in `common`.

> **CHECKED 2026-09-05 — the straddle stays.** `maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml`
> lists **zero** `20.1.x` versions; the earliest series is `20.2.12-beta` (MC 1.20.2). The repository's
> note is correct and is not stale. The pinned `mc1204.neoforge = 20.4.251` exists and is the last of
> the 20.4.x line, so the current pin is both valid and the newest 1.20.4 build.
>
> Retargeting at **1.20.2** was considered and rejected: it is one version closer and still a straddle,
> and the whole 20.2.x series is `-beta`. 1.20.4 stays.

So the straddle is permanent for as long as NeoForge is a target, and §3.8.5's split is adopted **with**
the mitigation rather than without it. In order of preference: (a) keep any divergent call behind
`LoaderBridge`, which is what the SPI is for; (b) if a whole class genuinely cannot be written once,
compile `common` twice as two variants; (c) **never** fork `common` into two source trees — two copies
of the input translation is exactly the drift `ScreenOverlay` was moved into `core/` to prevent, and the
symptom would be a click that works on one loader and not another.

**The practical consequence for L3–L5**: anything in `common` that touches an API which moved between
1.20.1 and 1.20.4 must be found *now*, not at L7. The surface this plan uses (`Screen`, `GuiGraphics`,
`GuiEventListener`, `MouseHandler`, `Window`, `ServerPlayer`, `MinecraftServer`) is believed stable
across those two versions, and L0's accept criteria now include **compiling `:mc1201:neoforge` against
the 1.20.4 artifacts**, which is the cheapest possible test of that belief and runs before any of it is
written.

---

## 4. The build

### 4.1 The include form, and the substitution ordering rule

**CrystalGraphics** — uncomment the four lines that are already correct:

```kotlin
include(":mc1201:common")
include(":mc1201:neoforge")
include(":mc1201:fabric")
include(":mc1201:forge")
```

…and add `includeBuild("mc1201/build-logic")` to `pluginManagement` if it is not already implied by
that repo's own `build-logic` wiring. **[verify]** by configuring.

**CrystalGUI** — delete `//includeBuild("mc1201")` and add plain subprojects:

```kotlin
include(":mc1201:common")
include(":mc1201:forge")
include(":mc1201:neoforge")
include(":mc1201:fabric")
```

**In the same edit**, restore the substitution `composite.settings.gradle.kts` deliberately withholds:

```kotlin
mapOf("module" to "com.crystalgraphics:crystalgraphics-mc1201-common",
      "projectPath" to ":mc1201:common"),
```

Its comment states the constraint exactly: *"a dependencySubstitution naming a project that does not
exist in the target build fails CONFIGURATION outright … for every task, including ones that have
nothing to do with Minecraft. Restore it in the same edit that uncomments mc1201 there."* The two edits
are one commit or the tree is broken in both directions.

**Do not touch the two-`includeBuild` reconciliation** — `plan_m12.md` §25.2 already resolved it in
favour of `composite.settings.gradle.kts`, and that file is where `submoduleMods` is set.

### 4.2 `pluginManagement`

CrystalGUI's `settings.gradle.kts` pins only the two GTNH plugins. The mc1201 loader scripts additionally
declare `com.gradleup.shadow`, `net.neoforged.moddev.legacyforge`, `net.neoforged.moddev` and
`fabric-loom`, none with a version. Take CrystalGraphics' block as the model — it pins
`com.gradleup.shadow` at 9.2.2 and adds the Fabric, Sponge, Forge and NeoForge repositories **without
`exclusiveContent`**, which its own comment explains: loader plugins add their own
`buildscript.repositories` at configuration time, which Gradle 9 forbids while `exclusiveContent` is
active in `pluginManagement.repositories`.

The three `net.neoforged.moddev.*` plugins inherit one version from the
`net.neoforged.moddev.repositories` **settings** plugin — apply it rather than pinning each.

### 4.3 What must be bundled, and the four things missing

mc1710's shadow jar carries `:core`, `:language`, the three engine bands as resources, `:taffy`, ASM
(relocated), JOML (minus its Kotlin and Java 9 files), and its own classes. mc1201's `ShadowUtils.kt`
bundles `:core` and `:mc1201:common`.

| Missing | Consequence |
|---|---|
| **`:taffy`** | **Fatal and silent.** `UIElement`/`ElementStyle` hold `NodeId`/`TaffyStyle` as *fields*; a field descriptor resolves at class definition, so the UI classes do not load at all |
| **fastutil** | Taffy's own dependency, for seven types. Same class of failure |
| **JOML** | `Matrix4f` is a field type on the same classes. Same failure |
| **`:language`** + engine bands | The editor colours from the built-in lexers and the Run panel is absent — a *supported* configuration (§3.7) but it must be a decision, not an omission |
| ASM, **relocated** | `:language` declares ASM `api` and three classes on every script path need it. Relocation is required wherever the host ships an older copy; **[verify]** what ModLauncher and Knot put on the classpath before deciding |

**Taffy's package stays `dev.vfyjxf.taffy` and must be relocated when shipping**, exactly as mc1710 does
— so a stock copy in another mod cannot win a classloader race. The JOML rule governs what may be
relocated: no type of the relocated library may appear in a signature another jar defines. Taffy and ASM
pass; **JOML does not** and must be shaded unrelocated.

**Taffy is a project dependency and publishes several variants**, which broke `mc1710:shadowJar` before
it started (*"we cannot choose between the following variants of project :taffy"*). The fix is naming
`LibraryElements.JAR` plus an **explicit `dependsOn(":taffy:jar")`**, because Gradle cannot infer the
producing task through an attribute override. Copy both lines.

### 4.4 The `mods{}` / `shadowJar` double declaration

`CrystalGraphics/AGENTS.md`'s standing rule, and it is already half-applied: `mc1201/neoforge` and
`mc1201/forge` declare `:core` and `:mc1201:common` source sets in `mods { create("crystalgui") { … } }`
*and* bundle them in `shadowJar`. Every module added by §4.3 must be added in **both** places.

**Fabric is not exempt.** Knot does not delegate `com.crystalgui.*` to the system classloader, so
`runtimeOnly` is invisible to it; Fabric bundles into `tasks.jar` **and** `shadowJar`, which
`mc1201/fabric/build.gradle.kts` already does for the two it has. And **do not** use
`loom.mods { sourceSet(crossProject) }` — Loom 1.16.2 tries to apply `fabric-loom-companion` to the
cross-project and fails for non-Loom projects.

### 4.5 Heap, parallelism, configuration cache

- `org.gradle.configuration-cache = false` — already set, mandatory, ModDevGradle does not support it.
- **`org.gradle.jvmargs` is wrong today.** It reads `-Xmx2g` while its own comment says 4 GB and
  `BUILD_SETUP.md` says never below 3 GB for Forge/NeoForge deobfuscation. **Raise it to `-Xmx4g` in
  L0**; it will OOM otherwise, and an OOM in the middle of a five-minute decompile reads as a hang.
- `org.gradle.parallel = true` stays, and `--max-workers=1` is still required for any invocation that
  builds `:mc1710` **and** `:CrystalGraphics:mc1710` — the RFG `mergeVanillaSidedJars` race. Adding
  four subprojects gives Gradle more to run concurrently, so this gets *more* likely, not less.

### 4.6 Traps carried in from `BUILD_SETUP.md`

- **Fabric `runClient` fails from IntelliJ** — the Kotlin coroutine debug init script evaluates loom's
  lazy `jvmArguments` outside an exclusive lock. `build.gradle.kts` has a `configureEach` filter for
  `idea.active`; the fallback is unchecking *Kotlin — Attach coroutine agent*. CLI is never affected.
- **First `createMinecraftArtifacts` / `genSourcesWithVineflower` takes 2–5 minutes.** Do not kill it.
- **`extractMcSources`** is already wired on `classes` for Forge/NeoForge and on IntelliJ sync only for
  Fabric. `./gradlew extractAllMcSources` after a fresh checkout.

---

## 5. Milestones

Nine, `L0`–`L8`. The letter is deliberately not `M`, `W`, `D` or `F` — those are taken by the engine
rewrite, the workbench rewrite, the device plan and the filesystem rewrite respectively.

Each states its contents, what accepts it, and its hazards. **Every milestone below L3 is accepted by a
build; L3 onward needs a running client.**

---

### L0 — Build revival · *no Java is written*

**Goal**: both repositories configure and `compileJava` green on all four mc1201 subprojects, with the
sources exactly as they are today.

**Contents**
0. ~~Read the NeoForge Maven listing and settle §3.8.6.~~ **Done 2026-09-05: no 20.1.x series exists,
   earliest is `20.2.12-beta`. The straddle stays, `mc1204.neoforge = 20.4.251` is valid and current,
   and §3.8.6's mitigation is adopted.** The consequence lands in this milestone's accept criteria:
   `:mc1201:neoforge:compileJava` against 1.20.4 artifacts is now a required step rather than a
   formality, because it is the cheapest test of the one assumption `common` rests on.
1. CrystalGraphics: uncomment the four `include` lines (§4.1).
2. CrystalGUI: replace `//includeBuild("mc1201")` with four `include`s; restore the
   `crystalgraphics-mc1201-common` substitution **in the same commit** (§4.1).
3. CrystalGUI `pluginManagement`: the shadow pin, `net.neoforged.moddev.repositories`, and the four
   loader repositories without `exclusiveContent` (§4.2).
4. Raise `org.gradle.jvmargs` to `-Xmx4g` (§4.5).
5. **The `mc1201/common` import guard** (§3.8.4) — a `doLast` on `compileJava` refusing
   `net.minecraftforge.`, `net.neoforged.` and `net.fabricmc.`, permitting `net.minecraft.` and
   `com.mojang.`. Copied from `core/build.gradle.kts`. **Before any platform code is written**, or the
   one-package-for-three-loaders decision is unenforceable for as long as it takes somebody to import
   the wrong thing. Verify it fires by compiling a deliberate violation.
6. `./gradlew extractAllMcSources` in both repos.

**Accept**
```
./gradlew :mc1201:common:compileJava :mc1201:forge:compileJava :mc1201:neoforge:compileJava :mc1201:fabric:compileJava
./gradlew :CrystalGraphics:mc1201:common:compileJava     # (or from that build)
./gradlew :core:compileJava :mc1710:compileJava          # nothing regressed
```

**What it proves that nothing else can**: `plan_phase5.md` §5.10's `PlatformService1201` rewrite, which
has never been through a compiler. Expect it to be *nearly* right and to name one or two absent imports.

**Hazards**: the substitution ordering (fails **every** task, including ones with no Minecraft in them);
the shadow/moddev plugin pins; the first decompile's five minutes reading as a hang; `--max-workers=1`.

**Size**: ~30 lines of Gradle across two repos. No Java.

#### What building it turned up — **L0 COMPLETE 2026-09-05**

All four subprojects compile in both repositories, and `:core`, `:mc1710`, `:gl-debug-harness` and
`:CrystalGraphics:mc1710` are unchanged. Eleven things were wrong; **none of them was in the plan**, and
every one was invisible until something compiled the module.

| # | Found | Why it was invisible |
|---|---|---|
| 1 | **CrystalGraphics' root `gradle.properties` had every `mc1201.*` pin commented out.** The modules read them through `rootProject.properties[...]`, which answers **null** rather than failing, so Fabric Loom reported *"Failed to find minecraft version: null"* | A commented pin is not an absent module — it is a module that resolves nothing and blames its plugin |
| 2 | **Neither settings file did `includeBuild("mc1201/build-logic")`**, so `id("cg-mc1201-loader")` could never resolve in either repo | Reads as a missing dependency rather than as a build nobody included |
| 3 | **`net.neoforged.moddev*` was pinned nowhere.** `BUILD_SETUP.md` says it is "pinned via the `net.neoforged.moddev.repositories` settings plugin" — **nothing applies that plugin in either repository** | The sentence described an intention. Corrected in both settings files; the doc itself is L8 |
| 4 | **`mc1201.neoforge = 20.1.84` named a version that has never existed** (see §3.8.6), and `mc1201.neoform` likewise. Both read by nothing | A dead pin beside live ones is only found by someone trying to use it |
| 5 | **`org.gradle.jvmargs` said `-Xmx2g` while its own comment said 4 GB** and `BUILD_SETUP.md` says never below 3 | An OOM inside a five-minute decompile reads as a hang |
| 6 | **An IntelliJ sync could not run at all**: gtnhgradle and ModDevGradle request the JetBrains `idea-ext` plugin under **different Maven coordinates**, so Gradle loads both classes, and moddev's `if (!rootProject.getPlugins().hasPlugin(IdeaExtPlugin.class))` guard names its own — it applies a second copy and the `settings` extension collides | **A CLI build constructs no IDEA model**, so `compileJava` was green throughout and the failure existed only in the IDE. Fixed by applying idea-ext once at each root, pinned, so parent-first loading gives both plugins one class |
| 7 | …and that fix collided with CrystalGraphics' **unconditional `tasks.register("processIdeaSettings")`**, a no-op added back when nothing supplied the real one. Now guarded on `findByName` | Only reachable once idea-ext is applied at that root, i.e. only after fix 6 |
| 8 | **The harness stopped building**: CrystalGraphics gates its loaders on `startParameter.currentDir` matching its own directory **or its parent, by EQUALITY** — and IntelliJ runs a task with the working directory set to the **subproject**, so `:gl-debug-harness:runHarness` arrived with `<CrystalGUI>/gl-debug-harness` and matched neither. The loaders were dropped there while CrystalGUI went on substituting `com.crystalgraphics:crystalgraphics → :mc1710`, failing every task with *"Project with path ':mc1710' not found in build ':CrystalGraphics'"* | Names a Minecraft module for a harness run that wants nothing to do with one. Reproducible from a shell with `cd gl-debug-harness && ../gradlew :gl-debug-harness:tasks`. Now a containment test |
| 9 | **`Blaze3DStateProvider` (394 lines) did not compile** — written against `CgStateGroup`/`CgViewportState`/`gl.state`, all deleted by the July GL-state rewrite; today's SPI is one method. **Deleted**; L1 writes a fresh one | Installed by nothing, so it had never run. `plan_mc26_diagnosis.md` §2.2 had already recorded it as "written, never installed" — it was also uncompilable |
| 10 | **`GL1201Backend` was missing all six `CgGLBackend` timer-query methods** and `GL1201Context` was missing `GL_ARB_timer_query()`. Added | **javac reports only the FIRST missing method per class**, so six absences present as one. An abstract method added to an SPI is only as loud as the builds that compile it |
| 11 | `plan_phase5.md` §5.10's lazy-service rewrite of `PlatformService1201`, never compiled and verified only by a sourcepath parse, **is correct** — it compiles unchanged | Its own note said the check available "corroborates the finding without proving the fix". Now proved |
| 12 | **`:CrystalGraphics:platform` declared `log4j-api` as `implementation`** where `:core` beside it has always said `compileOnly`. `dep.log4j` is `2.0-beta9` — a 1.7.10 fact, exported at runtime from the one module that is meant to know no Minecraft version — and NeoForge declares `{strictly 2.19.0}`, which it cannot satisfy | See below. Now `compileOnly`; nothing loses a logger, since every host ships one and the harness declares its own 2.26.1 |
| 13 | **`:core`'s `runtimeOnly` log4j-core 2.26.1 and gson 2.11.0 reached the loaders.** They exist for `:core`'s tests and the harness, which have no Minecraft to take them from, and they propagate to every consumer. Excluded from the mc1201 project dependencies | Same mechanism as 12 |

> **12 and 13 are one finding and it is worth stating on its own: a STRICT-VERSION CONFLICT presents as
> a dozen MISSING ARTIFACTS.** NeoForge's metadata pins `log4j-api:{strictly 2.19.0}` and
> `gson:{strictly 2.10.1}`; when an irreconcilable version arrives, Gradle fails the whole runtime graph
> and marks **every candidate** FAILED — twelve coordinates across six versions, each reported as
> *"Could not resolve … Possible solution: Declare repository providing the artifact"*. So the message
> names a repository problem and the cause is a version one, and the fix is in neither place the error
> points at.
>
> **Three things made it hard to place.** It survived a green `compileJava` — only `runtimeClasspath`
> resolves it. **`:mc1201:forge` was completely clean**, because MinecraftForge 1.20.1 publishes no
> `strictly` constraints and conflict-resolved the same edge silently, so it looked like a NeoForge
> toolchain fault. And **CrystalGraphics' own `:mc1201:neoforge` was clean too**, which is what proves
> it was ours: it does not take CrystalGUI's `:core`.
>
> The discriminator that cracked it: **only `log4j` and `gson` failed** while asm, slf4j, jline, antlr
> and commons-io resolved from the same repositories. A missing repository does not choose two groups
> out of eight.
>
> The exclusions are on **our project dependencies**, never on the configuration — an exclude on
> `runtimeClasspath` would take Minecraft's own log4j with it, which Loom's dev run reads even though
> ModDevGradle's does not.

**The general shape**: nine of the eleven are *stale build configuration*, not code, and the two code
faults were both an SPI growing while the only module implementing it was out of the build. Everything
`plan_phase5.md` §5.10 said about being "unverifiable from here" was true, and the cost of that was
paid here in one sitting rather than at a user.

**Cost of having the modules in the build**, measured: a warm no-op `:gl-debug-harness:compileJava` is
**3.4 s** with everything configured, against 2.4 s with `--configure-on-demand` (which stops
CrystalGUI's loaders configuring, but not CrystalGraphics' — an included build configures all its
projects once anything in it is needed). Not adopted: one second is not worth CoD's fragility during a
bring-up, and it is available as a flag whenever the noise matters.

---

### L1 — The platform bundle is honest · *CrystalGraphics*

**Goal**: nine real services. `CgPlatform.register` on 1.20.x answers every question mc1710 answers.

**Contents** — **SHIPPED 2026-09-05**
1. `InputService1201 implements CgInputService` — modifiers from live key state, `isKeyDown` /
   `isMouseDown` through `InputConstants` / `glfwGetMouseButton`, `howManyMouseButtons()` a constant
   (GLFW has no runtime query), clipboard through `Minecraft.keyboardHandler`. All verified against
   `research_repos/mc1201_sources/`.
2. `SoundService1201 implements CgSoundService` — `SimpleSoundInstance.forUI` through
   `BuiltInRegistries.SOUND_EVENT`, swallowing a bad id like `SoundService1710` does.
3. `PlatformService1201` returns both, lazily and interface-typed (§1.3's rule).
4. **`CgGlfwKeyCodes` moved here from L2**, because a translation service that is knowingly wrong is
   worse than none — L1 could not ship a correct `InputService1201` without it. **It went in
   `platform`, not `mc1201/common`**: `CgKeyCodes`, `CgMouseCodes` and `CgModifiers` are already there,
   `platform` has a test source set and `mc1201/common` does not, and writing GLFW's values as literals
   rather than importing `org.lwjgl.glfw.GLFW` keeps `platform` free of LWJGL. **L2 is now the test.**
5. `FrameHooks1201.endFrame()`, called by all three loaders after `onTransparentPass` — `tickFrame()`
   plus a **polled** resize check, since 1.20.1 Forge has no window-resize event and polling two ints
   beats a mixin per loader. Neither had any caller before.

> **`Blaze3DStateProvider` is NOT reinstated, and the reason in §3.6 was wrong.** That section said the
> shadow "will elide calls Blaze3D has changed underneath it" without a provider. It will not:
> `CgGlGetProvider` — the default — reads every domain **from the driver**, which already reflects
> whatever Blaze3D did. It is correct and slow. A Blaze3D provider is a *performance* optimisation that
> reads `GlStateManager`'s own tracked state instead, and on 1.20.x that state is private, so it needs
> reflection (as `AngelicaStateProvider` does). Not worth writing before anything renders. Revisit when
> there is a frame time to measure.

**Accept**: `:CrystalGraphics:mc1201:*:compileJava` green — met. A `runClient` boot check waits for L3,
which is the first milestone with anything to look at.

**Hazards**: the eager-construction rule — every field stays interface-typed and lazily built, or a
dedicated server dies at class load with a `GLCapabilities` field descriptor. `serverSmoke`'s
1201 equivalent (L7) is what actually catches a regression here; until then, read the diff.

**Size**: ~370 lines.

---

### L2 — Input translation, and the table · *CrystalGraphics `common` + a headless test*

**Goal**: a GLFW key event becomes the right `CgSystemInput.Keyboard.Event`, provably.

**Contents** — **SHIPPED 2026-09-05**. Items 1–4 moved into L1 (see there); L2 is the proof.

`CgGlfwKeyCodesTest`, in `platform/src/test` — six cases, no Minecraft and no GL:

1. **Spot checks against literals on both sides** — `GLFW_KEY_A(65) → 0x1E`, `ESCAPE(256) → 0x01`,
   `LEFT_CONTROL(341) → 0x1D`, and five more. Asserting `CgKeyCodes.KEY_A` alone would pass against a
   table that had renumbered both.
2. **Digits are not off by one** — the row most likely to be wrong, because ASCII runs 0–9 and
   DirectInput runs 1–9 then 0.
3. **Round trip over every mapped key**, which is also the injectivity check: two GLFW keys sharing one
   Cg value fail it.
4. **Every `CgKeyCodes` constant is either mapped or in `UNMAPPED_CG`**, by reflection over the class.
   This is what stops the table going stale — an unlisted, unmapped key just stops working.
5. **Counter-control**: nothing is both mapped and declared unmappable, so the exemption list cannot be
   grown to silence case 4.
6. Out-of-range and unknown inputs answer `KEY_NONE` / `GLFW_KEY_UNKNOWN` rather than throwing.

**Accept**: 6 tests, 0 skipped, 0 failures — met, and **mutation-checked**: changing one row
(`65 → KEY_B`) fails three of the six (spot check, round trip, completeness). A green run here is
evidence the table is right, not merely that it parses.

**Hazards, as met**: the unmappable set is not empty — nineteen DirectInput-era constants (Japanese IME,
OEM, `KEY_NONE`) have no GLFW key. The inverse is built *from* the forward pairs in a static block, so it
cannot be typed wrong separately.

**Size**: ~130 lines of test.

---

### L3 — The screen · *CrystalGUI `mc1201/common`*

**Goal**: F6 opens a desktop in a 1.20.1 client, and it can be clicked and typed in.

**Contents**
1. `CgUiScreen1201 extends Screen`, in `mc1201/common`:
   - `init()` → `host == null ? buildDesktop() : host.shown()`, then bring the editor forward;
   - `render(GuiGraphics, …)` → frame clock, `host.frame(delta)`,
     `CgGlState.invalidateAllIfPresent()`, `desktop().paint(presentation, delta, w, h)`, invalidate
     again;
   - the eight `GuiEventListener` callbacks → `CgUiInput1201` → `document.input()`, **returning what the
     engine returned**;
   - `isPauseScreen() → false`; `shouldCloseOnEsc() → false`, closing on an unconsumed Escape;
   - `removed()` → `host.hidden()`;
   - `host`, `editor` and friends in **statics**, because Minecraft builds a fresh `Screen` per display.
2. `Mc1201Host implements HostServices` — config dir under the game directory, `uiScale()` from
   `window.getGuiScale()` (§3.4), a `desktopId`, and `connection()` returning null until L5.
3. `CgUiInput1201` — raw coordinates from `MouseHandler` (§3.3), the key/char split (§3.1), the wheel
   sign verified by scrolling (§3.3).
4. Per-loader: an F6/F7 keybind and a `setScreen` call.

**Built 2026-09-05** — `CgUiScreen1201`, `CgUiInput1201`, `CgUiKeybinds1201` in `common`; F6/F7
registration per loader (`CgUiForgeEvents`, `CgUiNeoForgeEvents`, `CgUiFabricEvents`). All four modules
compile.

> **The Java version seam, which the plan did not see coming.** `:core` emits **Java 21 bytecode
> (v65)** — its Jabel processor is commented out, so nothing desugars it — and the mc1201 modules
> targeted 17 with `options.release.set(17)`, which refuses to *read* anything newer:
> *"bad class file … class file has wrong version 65.0, should be 61.0"*. Two separate fixes were
> needed and only together do they work:
>
> - **Compiling**: `cg-java17` now uses a **21 toolchain** with `sourceCompatibility`/`targetCompatibility`
>   17 rather than `release`. `--release` constrains what can be read; source/target does not.
> - **Loading**: MC 1.20.1 ships a Java 17 runtime, so v65 classes would not load on a player's JVM.
>   **jvmDowngrader** rewrites the shadow jar's classes to 17 — the same mechanism mc1710 uses to reach
>   Java 8. Verified the way mc1710's own comment prescribes: `UIDocument` is major **65** in
>   `-all.jar` and **61** in `-java17.jar`, on all three loaders.
>
> Also found here: `:core` declares Taffy and JOML `compileOnly`, so they reach no consumer, and
> `UIElement` holds a `NodeId` and a `Matrix4f` as **fields** — which resolve at class load. Taffy is
> now on the mc1201 classpath; **JOML deliberately is not**, because Minecraft ships it and NeoForge
> pins `{strictly 1.10.5}`, so our 1.10.8 failed resolution exactly as log4j did at L0.

**Accept**: compile — met. The rest needs a client and is unverified: `runClient` on **one** loader
(Forge first — ModDevGradle is the least fussy); F6 opens the desktop; a window can be dragged by its
caption, a `Scroller` scrolls the right way, a `TextField` accepts text and Ctrl+C/Ctrl+V move it
through the clipboard; Escape inside a dropdown closes the dropdown and not the screen; resizing the
window re-lays out; F6 after a resize does **not** resurrect a closed window.

**Hazards, in the order they will be hit**
- GUI-scaled callback coordinates (§3.3) — presents as clicks landing at half the distance.
- The wheel sign — presents as a canvas zooming backwards.
- The static-flag-not-consumed bug (§2.2) — presents as uncloseable windows after any resize.
- `uiScale` pushed onto the paint pose instead of the root transform — perfect picture, wrong clicks.
- A `NullPointerException` from `box()` — it is nullable and 185 ported chains once assumed otherwise;
  none of that code is being ported here, but a new host reading geometry can reintroduce it.

**Size**: ~600 lines. The single largest milestone.

---

### L4 — Pinned windows over a foreign screen

**Goal**: `plan_m16.md` §26.4's table, one handler per row.

**Contents**
1. `CgUiHud1201` in `common` — `presentation()` turning "is a foreign screen up" into a
   `DesktopPresentation`, noticing the transition and calling `ScreenOverlay.onForeignScreenChanged`.
2. The paint bracket, with the same `try/catch (RuntimeException | LinkageError) → exitHudMode` rule:
   a fault in a per-frame hook the player cannot close must not take the game down.
3. Per loader:

| | Forge 1.20.1 | NeoForge 1.20.4 | Fabric 1.20.1 |
|---|---|---|---|
| HUD paint | `RenderGuiOverlayEvent.Post` **[verify]** | equivalent **[verify]** | `HudRenderCallback` |
| Foreign screen opened | `ScreenEvent.Opening` | same | `ScreenEvents.BEFORE_INIT` |
| Foreign screen painted | `ScreenEvent.Render.Post` | same | `ScreenEvents.afterRender` |
| Mouse into the overlay | `ScreenEvent.MouseButtonPressed.Pre`, `MouseScrolled.Pre` | same | `ScreenMouseEvents.allowMouseClick` |
| Keys into the overlay | `ScreenEvent.KeyPressed.Pre`, `CharacterTyped.Pre` | same | `ScreenKeyboardEvents.allowKeyPress` |

**No mixin.** The 1.7.10 module needs `MixinGuiScreen` because that version's Forge has no screen input
event at all; every version from 1.8 has a cancellable one. `ScreenOverlay` already holds the decision,
so each row above is *cancel iff `offerMouse`/`offerKey` returned true*.

**Built 2026-09-05** — `CgUiHud1201` in `common` (the presentation decision, the transition notice, the
paint bracket with its `exitHudMode` fallback, and the two `offerMouse`/`offerKey` forwards), plus the
rows above on all three loaders. **No mixin was needed**, as §3.5 predicted: NeoForge's `ScreenEvent`
family matches Forge's name for name, and Fabric's `allow*` events cancel by returning `false`.

**Accept**: compile — met. The rest needs a client: pin a window, open chat, click into the pinned
window and type — chat does not receive it; click on chat and type — the window does not. Open the
**inventory** and do the same (1.7.10's `allowUserInput` trap has no 1.20.x analogue, but the inventory
is still the case that exercises a screen with its own drag handling).

**Hazards**: `ScreenEvent.Opening` fires for screens that render no world; the transition must be
noticed on a path every caller runs, or ownership survives into the next screen. Cancelling the event
without also *delivering* it is the mirror of the 1.7.10 mixin's "cancel without replacing" failure.

**Size**: ~400 lines across four modules.

---

### L5 — The wire

**Goal**: a 1.20.1 client talks to a 1.20.1 server, and the editor opens a file from it.

**Contents** — **SHIPPED 2026-09-05**

> **`LoaderBridge` was not built, and §3.8.3 was over-designed.** The SPI existed to let `common` reach
> a loader, and once written out, L5 needed it for nothing. The transport already has a seam —
> `CgPlatform.provide(CgNetworkChannel.SERVICE, …)`, the mechanism mc1710 uses — so a loader *provides*
> its channel rather than being asked for it. The only other loader-specific value is the current
> `MinecraftServer`, and that is **pushed** from the start/stop events, not pulled. A pull-style SPI
> would have been three implementations answering one field. The engine's own rule applies: a generic
> seam nothing generic is written against is not a seam.
>
> **What replaced it is the opposite shape and is better**: `Lifecycle1201` in `common` is the one class
> a loader talks to — one method per lifecycle moment, and `bootstrap(CgNetworkChannel)` takes the one
> thing only a loader can build. Each loader's event class is now nothing but forwards. The reason is
> the same as `ScreenOverlay`'s: wired per loader, "what a server tick does" is written three times and
> drifts, and the drift surfaces as a feature that works on one loader and not another.
1. `Mc1201NetworkChannel implements CgNetworkChannel`, **per loader**, behind `LoaderBridge.channel()` —
   Forge `SimpleChannel` / NeoForge payload registrar / Fabric `C2S`+`S2C` play networking, all
   **[verify]** against each module's `build/mc-src`. `maxFrameBytes()` from `plan_wire.md`'s ~1 MB
   ceiling, measured rather than assumed. ~90 lines each; the framing, multiplexing and routing above it
   are already in `core/` (`net.wire.FrameCodec`, `FrameMultiplexer`, `net.protocol.Connections`).
2. `Mc1201Peer` — **keyed on the `GameProfile` UUID, never on the entity.** `ServerPlayer` is rebuilt on
   respawn and on every dimension change, exactly as `EntityPlayerMP` is; an entity-keyed map is
   orphaned by a respawn, inbound dies **permanently** while outbound keeps working, and the logout
   event carries the *new* body so the cleanup misses too. This is mc1710's most expensive networking
   bug and it transfers unchanged.
3. `Mc1201Connections` in `common` over `net.protocol.Connections`, with per-loader join / leave /
   client-connect / client-disconnect / server-tick / client-tick forwarding.
4. `Mc1201WorkspaceHost` over `fs.server.WorkspaceHost` — root path, `OperatorsMayWrite`, `actorFor`,
   tick forward. `MinecraftServer.isDedicatedServer()` decides the permission model, as on 1.7.10.
5. `Mc1201Host.connection()` returns `Mc1201Connections.client()`.

**Accept**: `runClient` single-player opens the project tree and a file; `runServer` + `runClient`
joined over a socket does the same; a **respawn and a dimension change** leave the workspace working —
which is the one thing a single-player smoke test will not show.

**Hazards**: the peer-identity bug (silent, half-working, and it will look like an input bug); the
integrated-server deadlock if `isPauseScreen()` was ever changed back; three different networking APIs
with three different registration orders.

**Size**: ~800 lines across four modules.

---

### L6 — The language stack, decided

**Goal**: the editor's language capabilities are in a known, stated configuration.

**Contents**
1. Bundle `:language` and the three engine bands, or do not — and either way **log which** at
   registration (§3.7).
2. `LanguageStack` bootstrap on the loading screen if bundled.
3. **No `ScriptService`.** One line naming the reason. `ScriptRuntimes.open` answers empty, the Run
   panel is absent, and that is the documented degradation rather than a fault.

**Accept**: open a `.java` file in-game; colouring is either grammar-level or engine-level and the log
says which; nothing throws.

**Hazards**: a stale `language/build/classes` presents as "the engine band loaded but the adapter could
not be instantiated" — `:language:clean` after `--stop`, not a code fault.

**Size**: ~60 lines and a build decision.

---

### L7 — Three loaders, and the things that only a run can see

**Goal**: all three loaders start; the server-side class-loading contract is checked.

**Contents**
1. Forge, NeoForge and Fabric entrypoints registering the real event sets (§3.8).
2. `Mc1201ServerSmoke` — the 1201 twin of `CgUiServerSmoke`: boot a dedicated server, assert the
   platform bundle registered, the channel is available, the connection lifecycle installed, a UI
   description round-trips and content-hashes with no GL, and **no client-only class has been loaded**.
   That last assertion is the whole point; it is what found three fatal defects on 1.7.10, every one a
   *runtime* property no import scan can see.
3. Port the probes that pay for themselves: the session probe and the remote-workspace probe. The wire
   probe's ceilings are worth re-measuring on this version.

**Accept**: `runClient` green on all three; `runServer` green; the smoke task exits 0.

**Hazards**: NeoForge is on 1.20.4 and `common` is compiled against 1.20.1 (§3.8) — this is where that
assumption is tested. The Fabric IntelliJ `runClient` issue (§4.6).

**Size**: ~500 lines.

---

### L8 — Documentation sweep

**Contents**
1. `AGENTS.md`: the module table (`mc1201/ ❌ commented out` is false); *"Running Minecraft — `mc1710`
   IS in the build"* gains its 1201 half; the invariant rows that name a 1.7.10 mechanism and are
   really version-independent get the second version named (the alpha test row is the opposite case and
   must say 1.7.10 **only** — §3.6).
2. `CrystalGraphics/docs/BUILD_SETUP.md`: confirm every claim survived, correct what did not.
3. `CrystalGUI_TODO.md` §3.2 → closed, pointing here.
4. `plan_prephase4.md`'s parking lot, `plan_ui_rewrite.md` D10, `plan_phase5.md`'s "not in this phase",
   `plan_m16.md`'s "ship the other loaders" — each gets a line saying it was lifted and where.
5. Every new invariant learned in L0–L7 gets a row in `AGENTS.md`, in the same commit that learned it —
   not here. This step is for what the sweep finds stale, not a place to defer writing them down.

---

## 6. What this deliberately does not do

- **No 1.12.2 module.** `plan_m16.md` notes there is none; adding one is a third row of §3's tables and
  a separate decision.
- **No device seam, no Vulkan, no 26.x.** That is `plan_cgdevice*`, and it runs after this.
- **No live scripting on 1.20.x** (§3.7). Absence is supported; silence is not.
- **No production/obfuscated validation.** 1.7.10 has `runObfClient` and `stageObfMods` because RFG
  reobfuscates; 1.20.x dev runs are already deobfuscated and the production path is the loader's
  business. Whether a reobfuscated equivalent is needed is an L7+ question, deliberately not answered
  here.
- **No `MachineExample` port.** It is a worked example of `Networked<M>`, not a platform seam. Port it
  after L5 if the wire wants a demonstration.
- **No harness change.** The harness still assembles by hand and predates `DesktopHost`; converting it
  to a `HarnessHost implements HostServices` is `plan_workbench_rewrite.md`'s W-series, not this.
- **No engine changes at all.** If a milestone here needs one, that is a finding worth writing down —
  the claim of §0.10 is that it will not happen, and the first counterexample is more interesting than
  the fix.

---

## 7. Risks, ranked

| # | Risk | Why it is ranked here | Mitigation |
|---|---|---|---|
| 1 | **The key table is wrong in one row** | Silent, permanent, and indistinguishable from a widget bug | L2's round-trip test plus literal spot-checks; the unmappable set listed explicitly |
| 2 | **GUI-scaled coordinates** (§3.3) | Looks like a UI bug, not a coordinate one; at the default scale of 2 it is a factor of exactly 2, which reads as a bad constant | Read `MouseHandler.xpos()/ypos()`; a probe that logs both spaces on the first click |
| 3 | **Peer identity keyed on the entity** (§L5) | Half-works — outbound keeps going, so it reads as an input bug | Key on the `GameProfile` UUID from the first line; test a respawn |
| 4 | **`isPauseScreen()` returns true** | Deadlocks the integrated server with nothing in the log, and is invisible on a dedicated server | Assert it in the 1201 smoke test |
| 5 | **`:taffy` not bundled** | Fails at class load, so the stack trace names a UI class rather than the build | L0 reads the shadow jar's entries before L3 runs |
| 6 | **A loader import lands in `common`** (§3.8.4) | Compiles green — Forge is on `common`'s classpath — and throws on the two loaders nobody ran | The import guard, in L0, before any platform code exists |
| 7 | **NeoForge-on-1.20.4 consuming 1.20.1-compiled `common`** (§3.8.6) | Compiles until it does not, and the more that lives in `common` the more it carries | Settle it at L0 by reading the Maven listing; if the straddle stays, divergence goes behind `LoaderBridge` and `common` is never forked |
| 8 | **Three networking APIs** | Three chances to get registration order wrong | Do Forge first and completely; the other two are then a translation of ~90 lines |
| 9 | **The state manager's shadow disagreeing with Blaze3D** | Produces a *missing GL call* — wrong rendering, no exception | Install `Blaze3DStateProvider` at L1; `-Dcrystalgraphics.state.noDedup=true` to bisect |
| 10 | **`--max-workers=1` forgotten** | Intermittent, names `mergeVanillaSidedJars`, and succeeds when run alone | It is already documented in `gradle.properties`; four new subprojects make it likelier |

---

## 8. Exit criteria

1. `./gradlew :mc1201:forge:compileJava :mc1201:neoforge:compileJava :mc1201:fabric:compileJava` green
   in both repositories, and `:core:check` and `:mc1710:compileJava` unchanged.
2. `runClient` on **all three** loaders opens the desktop with F6, and the editor opens a file from the
   integrated server.
3. A joined `runServer` + `runClient` pair does the same over a socket, and survives a respawn.
4. A pinned window takes input over chat and over the inventory, and gives the keyboard back.
5. `Mc1201ServerSmoke` exits 0, including its "no client-only class was loaded" assertion.
6. The key-code table's test is green, including the round trip and the explicit unmappable list.
7. `AGENTS.md`'s module table and build section describe the tree; no plan doc still says mc1201 is out
   of the build.

---

## Appendix A — the port matrix

Every mc1710 file, and its 1201 disposition. "New" means written against a different API, not ported.

| mc1710 | Lines | 1201 | Where | Milestone |
|---|---|---|---|---|
| `CrystalGUI.java` (`@Mod`) | 176 | **New** ×3 | per loader | L7 |
| `ClientProxy` / `CommonProxy` | 118 | **Dropped** — 1.20.x has no proxy idiom; use `DistExecutor`/`EnvType` | — | L7 |
| `CgUiScreen` | 692 | **New**, ~350 | `common` | L3 |
| `CgUiInput` | 159 | **New**, ~180 | `common` | L2/L3 |
| `CgUiHud` | 177 | **New**, ~120 | `common` + per loader | L4 |
| `CgUiOverlayInput` | 124 | **Dropped** — its whole job is draining a queue the mixin cancelled | — | L4 |
| `MixinGuiScreen` | 72 | **Dropped** — 1.20.x has cancellable screen input events | — | L4 |
| `CgUiConnections` | 242 | **Port**, ~90 platform + `net.protocol.Connections` | `common` + per loader | L5 |
| `Mc1710NetworkChannel` | 191 | **New** ×3 | per loader | L5 |
| `Mc1710Peer` | 110 | **Port**, near-verbatim (`ServerPlayer`/`GameProfile`) | `common` | L5 |
| `CgUiWorkspaceHost` | 193 | **Port**, ~90 platform | `common` | L5 |
| `ScriptService1710` | 124 | **Deferred** (§3.7) | — | L6 |
| `LaunchWrapperBytes` | 167 | **Deferred** — no analogue | — | L6 |
| `CgUiServerSmoke` | 416 | **Port**, adapted | per loader (server) | L7 |
| `CgUiSessionProbe` | 491 | **Port** | `common` | L7 |
| `CgUiRemoteWorkspaceProbe` | 231 | **Port** | `common` | L7 |
| `CgUiNetProbe`, `CgUiWireProbe`, `CgUiEditorOpenProbe`, `CgUiTwoClientProbe` | 943 | **Optional** | `common` | L7+ |
| `CgUiAutoTest` | 594 | **Optional** | `common` | later |
| `MachineExample`, `MachineExampleClient` | 144 | **Optional** | per loader | later |

**New on 1201 with no mc1710 ancestor**: `LoaderBridge` (the SPI, `common`, ~25 lines) and its three
implementations (~265 lines each, §3.8.5), plus the `mc1201/common` import guard that makes the boundary
checkable (§3.8.4). Nothing in mc1710 corresponds to either, because 1.7.10 has one loader.

CrystalGraphics side:

| CG mc1710 | 1201 | Milestone |
|---|---|---|
| `PlatformService1710` | `PlatformService1201` — exists, 2 stubs | L1 |
| `Lwjgl2GLBackend` / `Lwjgl2GLContext` | `GL1201Backend` / `GL1201Context` — exist | — |
| `InputService1710` | **`InputService1201` — missing** | L1/L2 |
| `SoundService1710` | **`SoundService1201` — missing** | L1 |
| `CursorService1710`, `LifecycleService1710`, `ReloadService1710`, `RenderingService1710`, `ResourceService1710` | all exist | — |
| `AngelicaStateProvider` | `Blaze3DStateProvider` — **written, never installed** | L1 |
| — | `CgGlfwKeyCodes` — **does not exist** | L2 |

---

## Appendix B — the sweep

Every document in either repository that mentions mc1201, 1.20.1, 1.20.4 or NeoForge, and whether it
carries anything this plan needs. Run `grep -Ein 'mc1201|neoforge|1\.20\.[14]'` to reproduce — note
that **multiple `-e` flags silently match nothing in this environment's grep**; use `-E` with
alternation.

| Document | Hits | Carries |
|---|---|---|
| `CrystalGraphics/AGENTS.md` | 64 | **The multi-loader structure, the checklist, the classpath rule.** Load-bearing |
| `CrystalGraphics/docs/BUILD_SETUP.md` | 22 | **The toolchain recipe.** Load-bearing |
| `CrystalGraphics/docs_research/CGGLSTATEMANAGER_PLAN.md` | 24 | The state manager's design; context for §3.6 |
| `CrystalGraphics/plan_cgdevice_milestones.md` | 11 | D4's delete-or-keep item — **superseded here** |
| `CrystalGraphics/plan_mc26_diagnosis.md` | 9 | §2.2 host table and §2.3 gaps — **used in §1.3** |
| `CrystalGraphics/plan_harness_lwjgl3.md` | 7 | One line: `CgGLBackend` is `GL1201Backend` minus Blaze3D routing |
| `CrystalGraphics/plan_cgdevice.md`, `plan_cgvulkan.md`, `docs/CRYSTALSHADER_MANIFESTO.md` | 7 | Nothing for this plan |
| `CrystalGUI_TODO.md` | 12 | §3.2 — **closed by this plan** |
| `plan_m12.md` | 10 | **§25.2 build revival — the template.** §25.9's RFG race |
| `plan_prephase4.md` | 9 | The parking lot; the deferral rule — **lifted** |
| `AGENTS.md` | 7 | The module table (now false); the import guard; the alpha-test row (1.7.10 only) |
| `plan_m16.md` | 5 | **§26.4's per-version table — the design for L4** |
| `plan_syntax.md` | 4 | Nothing platform-shaped |
| `plan_phase5.md` | 4 | **§5.10's unverified `PlatformService1201` fix** |
| `plan_wire.md` | 3 | The packet-ceiling table |
| `plan_windowing.md` | 2 | That CG's 1201 loaders register HUD hooks — confirms L4's shape |
| `plan_ui_rewrite.md` | 2 | **D10** — 1.7.10 only during the rewrite; the rewrite is done |
| `plan_ui_host.md` | 2 | The 1.7.10 / 1.20.1 container comparison |
| `docs_research/VAO_VBO_MINECRAFT_1201_*.md` | 15 | Rendering research, predates the platform split |
| `docs_research/CRYSTALGUI_BOOTSTRAP_ARCHITECTURE.md` | 2 | 2026-era design research; **nothing current** |
| `plan_m5.md`, `plan_m6.md`, `THIRD-PARTY.md`, `README.md`, the `P6.*` docs | 9 | Passing mentions only |

**What the sweep did not find, and it is the important half.** No document anywhere describes what a
CrystalGUI *host* must implement on 1.20.x. The nearest thing is `plan_workbench_rewrite.md` §4.8, which
never says "mc1201" — it describes `HostServices` and `DesktopHost` in the abstract, which is why no
grep for a version number reaches it. That section is the foundation of this plan, and it is the
document to read first.
