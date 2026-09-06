# CrystalGUI — Agent Knowledge Base

**Project type**: Platform-agnostic retained-mode UI engine, shaped like a lightweight web browser
(DOM + CSS cascade + Taffy layout + immediate-mode painting).
**Authored in**: Java 21 (Jabel-desugared toward Java 8 bytecode) · **Layout**: Taffy · **Backend**: CrystalGraphics
**Targets**: MC 1.7.10 (Forge/LWJGL2) · MC 1.20.1/1.20.4 (Forge/NeoForge/Fabric, LWJGL3) — *neither is in the build today; see [Module layout](#module-layout--what-actually-compiles)*

---

# ⚠️ AGENT EXECUTION RULES — READ BEFORE ANYTHING ELSE

**These rules apply to ALL agents operating in this repository, including subagents.**

## Required reading

Read the files relevant to your task scope **before** doing any work.

| When | Read |
|---|---|
| Always | `docs/CGUI_INVARIANTS.md` — **the section for whatever you are about to touch**, not the whole file |
| Touching style, CSS, painting, drawables, compositing | `docs/CGUI_STYLE_RENDER_PIPELINE.md` |
| Writing or modifying a widget | `docs/CGUI_WIDGETS.md` |
| Touching `serialization/` or `net/` | `docs/CGUI_SERVER_AND_SERIALIZATION.md` |
| Touching `dock/`, `workbench/` or `editor/` | `docs/CGUI_WORKBENCH_SERVICES.md` — **and add any new service API to it in the same commit** |
| Touching `fs/`, `document/` or `workbench/editor/` | `docs/CGUI_WORKBENCH_SERVICES.md` §Resources and §Opening things — the same rule applies |
| Touching any rendering/buffer/shader/VAO/mesh code | `CrystalGraphics/AGENTS.md` |
| Working inside a package | any `AGENTS.md` found in that package (none exist under `core/` today) |

## NO RE-DELEGATION

**Subagents MUST NOT delegate their assigned work to another agent.**

When you are assigned a task — by an orchestrator, a plan, or a user — you execute it yourself with
the tools you have (Read, Edit, Write, Bash, Glob, Grep). You do not spawn a child agent, fire a
background task, or hand off via `task()`.

**Absolute prohibition. No exceptions.** Complexity is not a reason to re-delegate. The only
cross-agent tool use permitted is asking the orchestrator a clarifying question, inline.

**If you are a subagent and find yourself writing a `task()` call: STOP. Do the work yourself.**

---

# What this was built for

CrystalGUI exists because of one product: a **node-based shader graph for Minecraft**, cross-version,
true to GLSL, on a modern GL 3.x+ pipeline with instancing as the default draw path — Unity's Shader
Graph without the lies about what the GPU is doing.

**That shipped.** The graph is `com.crystalgui.app.shadergraph`, it opens as a `DocumentKind`, and the
engine under it is general enough that it is now one application among several rather than the reason
for the rest. What the goal leaves behind is the standard: every widget here was built to survive a
node editor, which is why the box tree lays out once, why `transform` never reflows, and why a canvas
can hold ten thousand nodes.

📄 **[CrystalShader Manifesto](CrystalGraphics/docs/CRYSTALSHADER_MANIFESTO.md)** — the rendering
philosophy, which outlives the milestone.

# TO BUILD

```bash
./gradlew :taffy:test             # the VENDORED layout engine's own regression tests
./gradlew :core:compileJava       # the engine — enforces the MC/Forge/LWJGL import guard
./gradlew :core:test              # unit tests, CrystalGraphics ON the classpath
./gradlew :core:headlessTest      # server-side tests, CrystalGraphics CORE deliberately absent
./gradlew :core:check             # both test tasks
./gradlew :mc1710:compileJava     # the 1.7.10 loader — IS in the build, NOT in :core:check, and
                                  # therefore the one thing a deletion from core/ can break silently
```

> ~~There is **no in-game Minecraft integration reachable from this build.**~~ **False since Phase 4,
> corrected 2026-08-21.** This paragraph told three sessions in a row that only `core/` and the harness
> could be run end to end, and ended with *"do not claim otherwise"* — so it read as a rule rather than
> as a fact that had gone stale. `mc1710` is in `settings.gradle.kts` and carries the whole networking
> integration: `CgUiScreen`, `CgUiConnections`, `CgUiWorkspaceHost`, `Mc1710NetworkChannel` and four
> probes. **Every server-side defect found this week was found by running it**, and none of them was
> reachable from `core/` or the harness — see [Running Minecraft](#running-minecraft-mc1710-is-in-the-build).

## Running Minecraft — `mc1710` IS in the build

**For anything that crosses the loader seam — networking, the workspace over a wire, platform services,
class loading on a server — a LOADER MODULE is the only thing that can see it.** `headlessTest` asserts
by absence and reaches no loader; the GL harness is a client with a context by design.

> **`mc1201` answers the same seam on three loaders and is also in the build** — it was `mc1710` alone
> until 2026-09-05. Its own `serverSmoke` found one defect per loader, none of them alike and none
> reachable from `core/`, so the sentence above is now about a *kind* of module rather than about one.

```bash
./gradlew :mc1710:runClient                       # the dev client
./gradlew :mc1710:runServer                       # a dedicated server
./gradlew :mc1710:serverSmoke                     # boot a server, assert the stack came up, stop. ~48s
./gradlew :mc1710:runObfClient                    # SRG names — production, and the only run that is
./gradlew :mc1710:runClient -PcgJoin=localhost:25565   # join a server: TWO PROCESSES, ONE SOCKET
./gradlew :mc1710:runClient -PcgSessionProbe      # a real Server/ClientUiSession pair over the wire
./gradlew :mc1710:runClient -PcgNetProbe          # the raw transport, below the session layer
```

```bash
**1.20.x** — `<loader>` is `forge` | `neoforge` | `fabric`. `neoforge` is MC 1.20.4, the other two 1.20.1.
./gradlew :mc1201:<loader>:runClient
./gradlew :mc1201:<loader>:runServer
./gradlew :mc1201:<loader>:serverSmoke            # boots, asserts, stops. Needs -PcgAcceptEula once
                                                  # per run dir: the build detects Mojang's EULA and
                                                  # refuses to accept it for you.
```

> **`serverSmoke` is the one to reach for first.** Three fatal defects — CrystalGraphics building its
> platform services eagerly, `CgPlatform.register` demanding a GL backend, a client-only guard one level
> too high — shipped undetected because every one is a *runtime* property ("a client-only class is
> constructed on a server") that no test and no import scan can see. Booting a server found all three in
> one run. It also asserts that no client-only class was **loaded**, which is the contract
> `CommonProxy`'s javadoc has always stated and nothing checked.

## Render testing — the GL debug harness

For anything visual, **prefer the harness over Minecraft**: it boots in seconds, needs no Minecraft
context, and gives you a real GL surface. *(This used to say Minecraft "isn't wired up", which stopped
being true — `runClient` works. The advice survives the correction: the harness is faster and isolates
rendering from everything else. What it cannot see is anything that crosses the loader seam.)*

```bash
./gradlew :gl-debug-harness:runHarness --args="--mode=cgui-gallery"   # start here
./gradlew :gl-debug-harness:runHarness --args="--list"                # all scenes
```

| Mode | Scene class | Covers |
|---|---|---|
| `cgui-gallery` | `CgUiGalleryScene` | Every widget at once — the default smoke test |
| `cgui-test` | `CgUiTestScene` | General engine scratch scene |
| `cgui-button` | `CgUiButtonScene` | `Button`, activation semantics |
| `cgui-checkbox` | `CgUiCheckboxScene` | `Checkbox`, `CheckboxGroup` |
| `cgui-switch` | `CgUiSwitchScene` | `Switch` (CSS-driven knob transition) |
| `cgui-slider` | `CgUiSliderScene` | `Slider`, drag |
| `cgui-textfield` | `CgUiTextFieldScene` | `TextField`, caret, selection |
| `cgui-text` | `CgUiTextScene` | `UIText` wrapping/measurement |
| `cgui-text-stress` | `CgUiTextStressScene` | Many text nodes — shaping/layout cost |
| `cgui-scroller` | `CgUiScrollerScene` | `Scroller`, `ScrollerView`, overflow |
| `cgui-splitview` | `CgUiSplitViewScene` | `SplitView`, divider drag |
| `cgui-tabview` | `CgUiTabViewScene` | `TabView`, `Tab` |
| `cgui-styling` | `CgUiStylingScene` | Cascade, selectors, transitions |
| `cgui-shadow-parts` | `CgUiShadowPartsScene` | **SPIKE S2** — a shadow-rooted `Button` beside the stock one under ONE stylesheet. `text { color: red }` reaches the stock label and **cannot** reach the shadow one, which takes its colour from `::part(label)`; the status line reports what focus retargets to. What a headless test cannot show is that an encapsulated widget still draws and behaves like a widget |
| `cgui-nineslice` | `CgUiNineSliceScene` | `CgUiSprite` 9-slice |
| `cgui-ore-theme` | `CgUiOreThemeScene` | `ore.css` + sprite registry end-to-end |
| `cgui-visual-layers` | `CgUiVisualLayersScene` | FBO layer opacity + masking |
| `cgui-desktop` | `CgUiDesktopScene` | **CrystalOS** — stacking windows, drag, resize, clamp, cascade, taskbar, per-window modality, maximise, **the editor running as a window**, and **a tool window torn out into an owned float** (F3, or drag a rail button into the editor area). *Grows with `plan/shell-windowing.md`: every W with something visible adds its demonstration here in the same commit* |
| `cgui-snapshot-probe` | `CgUiSnapshotProbeScene` | **DIAGNOSTIC, exits on its own** — photographs a window (`WindowSnapshot`, the real minimise path) and draws the photograph 1:1 beside the live window; writes `live` and `snapshot` PNGs to `harness-output/cgui-snapshot-probe/`. The window holds every path a photograph has to survive: rounded islands with `overflow: hidden` (mask layer), `overflow: clip`, an `opacity` layer, a scroller (scissor), text. **Any difference between the two PNGs is the render target's, since one subtree drew both** — it found three target-size assumptions in one run that six screenshots had not |
| `cgui-gradient-probe` | `CgUiGradientProbeScene` | **DIAGNOSTIC, exits on its own** — every claim `gui_gradient.shader` makes on one screen: the taskbar's glow, a 16-level ramp across the width (the banding torture), a `to bottom right` on a rounded box (gradient line + corner mask), ten stops (two draws, one seam), a fade to `transparent` over white (premultiplied), a hard stop. One PNG in `harness-output/cgui-gradient-probe/`; the readback that verified it counted levels, run lengths and the fade's hue against the straight-lerp prediction |

Harness scenes live in `gl-debug-harness/src/main/java/.../harness/scene/ui/`; register new ones in
`SceneRegistry`. Harness authoring rules are in `gl-debug-harness/AGENTS.md` — never call raw GL.

---

# Start Here By Task

| I need to… | Section | Deep reference |
|---|---|---|
| Not repeat something already paid for | — | `docs/CGUI_INVARIANTS.md` |
| Add or change a widget | [Widgets](#widgets) | `docs/CGUI_WIDGETS.md` |
| Add a panel, a file type or a command to a workbench | — | `docs/CGUI_WORKBENCH_EXTENSIONS.md` |
| Add a CSS property | [Adding a CSS property](#adding-a-css-property) | `docs/CGUI_STYLE_RENDER_PIPELINE.md` |
| Change how something paints | [Render stack](#stack-4-render--immediate-mode) | `docs/CGUI_STYLE_RENDER_PIPELINE.md` §5–§8 |
| Work on layout / Taffy | [Style stack](#taffybridge--the-layout-seam) | — |
| Work on events, focus, hover, drag | [Input stack](#stack-3-events-input-focus) | — |
| Serialize a tree / send UI over a wire | [Server layer](#server-layer--serialization--net) | `docs/CGUI_SERVER_AND_SERIALIZATION.md` |
| Understand a frame | [Frame lifecycle](#frame-lifecycle) | — |
| Debug "my selector doesn't match" | [Load-bearing invariants](#load-bearing-invariants) | — |
| Debug "my layout is wrong by default" | [Taffy default divergences](#taffy-defaults-diverge-from-css-deliberately) | — |
| Add a rendering backend capability | [CrystalGraphics boundary](#crystalgraphics-ownership-boundary) | `CrystalGraphics/AGENTS.md` |

---

# Module layout — what actually compiles

`settings.gradle.kts` includes `taffy`, `core`, `language` and `gl-debug-harness` — of which `taffy` and
`gl-debug-harness` are **git submodules** that are ordinary Gradle subprojects (no settings file of their
own), while CrystalGraphics is a submodule that is a composite `includeBuild`. CrystalGraphics is an
`includeBuild` composite with three `dependencySubstitution` entries, which is how the
`compileOnly("com.crystalgraphics:core:1.0.0")` coordinates resolve to local source.

| Module | In build? | State |
|---|---|---|
| `core/` | ✅ | The engine. Java 21 → Java 8 bytecode. Everything below lives here. |
| `language/` | ✅ | The language stack — everything with a native or an engine behind it. Depends on `core/`; **`core/` must never depend on it**, which is what keeps tree-sitter's `.so`s and ECJ's ~13MB off a dedicated server. `.grammar` (six tree-sitter grammars), `.engine` (band selection, the ONE shared loader per band — `EngineHost` — the language-neutral `Analysis` answer and the `AnalysedLanguageServices` attachment every engine extends), `.java` (everything Java, split by what a class is FOR — `.ecj` the adapters, `.classpath` what a script compiles against, `.assist` completion and Quick Documentation, `.fix` the Alt+Enter catalog over `.fix.catalog`/`.fix.ast`/`.fix.edit`, `.exec` the `ScriptHost` runtime), `.js` (everything JavaScript, split by WHICH LOADER defines a class — `.host` may name `language.run`/`language.java` and never Rhino, `.rhino` is the reverse and holds `.rhino.resolve`/`.rhino.fix`/`.rhino.exec`), `.map` (the readable↔runtime boundary, on ASM), `.run` (the **engine-neutral** Run shell: `ScriptRuntime` SPI + `ScriptRuntimes` registry and `ScriptPolicy` at the root — which lives there because three of its four consumers are not JavaScript — over `.exec` (capture, stop, cache), `.console` (the transcript, UI-free) and `.view` (the only one that may import `com.crystalgui.ui`). `RunShellIsEngineNeutralTest` forbids the whole tree naming `.java`, `.js`, ECJ or Rhino, and still needs no change after the split because it matches by path PREFIX). `.resolve` is reserved.

> **The two `java`/`js` axes differ on purpose.** In `.java` the loader question is mechanical — a class that imports `org.eclipse.jdt` is child-side, and that is thirty-six of its fifty — so directories spend themselves on the axis that is *not* readable off the file. In `.js` it is the loader question that cannot be read: six classes import neither Rhino nor anything of ours and are child-side only because every one of their callers is. *(Was `syntax-treesitter/` until M4.)* |
| `taffy/` | ✅ | **The layout engine, VENDORED.** Git submodule ([`CrystalGraphics/taffy-java`](https://github.com/CrystalGraphics/taffy-java), branch `master`) — so `git clone --recursive`, like the other two. A fork of the published sources of `dev.vfyjxf:taffy:1.1.4` (MIT), carrying our own fixes to its measure path — see `taffy/MODIFICATIONS.md`, which is the statement of changes MIT requires, and `plan/engine-rewrite.md` D3. The package stays `dev.vfyjxf.taffy` because `mc1710` relocates it when shipping, so 165 call sites needed no edit and a stock copy in another mod cannot win a classloader race. Pulls **fastutil**, whose cost is recorded in `gradle.properties`. |
| `gl-debug-harness/` | ✅ | Git submodule (branch `crystalgui`). 17 CrystalGUI scenes. The only way to run the UI. |
| `CrystalGraphics/` | ✅ (composite) | The rendering backend. Consumed, never reimplemented. |
| `mc1710/` | ✅ | **In `settings.gradle.kts` and compiling** (`./gradlew :mc1710:compileJava`), whatever older notes here said. Holds the real 1.7.10 host, and since W3 that is a HOST rather than a product: `CgUiScreen` (the viewport the desktop attaches to), `CgUiInput`, `CgUiHud`, `CgUiOverlayInput`, and `CgUiWorkspaceHost` answering the `HostServices`/`WorkspaceHost` seams. `Mc1710Workspace` and `CgUiWindowMount` were **deleted** there; anything still naming them is describing history. **Verified by `serverSmoke` and by running the client**; a green compile was never the claim. |
| `mc1201/` | ✅ | **In the build and running**, whatever older notes here said. `common` holds the host — `CgUiScreen1201`, `CgUiInput1201`, `CgUiHud1201`, `Connections1201`, `WorkspaceHost1201` and `Lifecycle1201`, which is **the one class a loader talks to**; `forge`/`neoforge`/`fabric` are registration only and forward into it. All three compile, boot a dedicated server and pass `./gradlew :mc1201:<loader>:serverSmoke`. **`neoforge` is MC 1.20.4** — NeoForge published no 20.1.x series — so `common` is compiled against 1.20.1 and consumed by a 1.20.4 module; see `plan/platform-mc1201.md` §3.8.6. **All three boot a dedicated server and pass `serverSmoke`**; the desktop scene has been run on 1.20.1. |

`core/build.gradle.kts` runs an **import guard** as a `doLast` on `compileJava`: any source line
importing `net.minecraft.*`, `cpw.mods.fml.*`, `net.minecraftforge.*`, or `org.lwjgl.*` fails the
build. There are currently **no exemptions** — the guard is clean.

## Three test source sets, and they are not interchangeable

| Source set | CrystalGraphics on classpath? | What belongs there |
|---|---|---|
| `core/src/test/` | ✅ `testImplementation` | Anything needing `CgIO`, fonts, `StyleSheet`, sprites, drawables |
| `core/src/headlessTest/` | ❌ **core deliberately absent**, `platform` present | Everything a dedicated server must run: `serialization/`, `net/`, tree/state logic, and **`text.lang` — the language SPIs, which run here precisely because no engine and no grammar is on this classpath** |
| `language/src/test/` | ✅ (plus the tree-sitter natives) | Grammars, queries, the tokenizer. Skips cleanly when a native will not load on the running platform |
| harness scenes | ✅ full GL | Anything visual |

**The absence is the assertion.** On a dedicated Minecraft server there is no GL context and no fonts.
Anything in `core/` that reaches a CrystalGraphics **core** type *outside a paint-method body* fails in
`headlessTest` with `NoClassDefFoundError` rather than in production.

> **`com.crystalgraphics:platform` is the one CG module that stays**, and it is not an exception to the
> rule — it is what the rule is actually modelling. `platform` is pure SPI (interfaces, key-code
> constants, the `CgPlatform` registry) with no GL calls and no context requirement, and it ships inside
> every loader jar, so a dedicated server genuinely has it. Excluding it would assert something untrue of
> production, and `core/` reaches it for real: `Input` *implements* `CgSystemInput`, which is a
> supertype and therefore resolves at class load, not in a method body.

JOML and Taffy **must stay** on the headless classpath: `UINode` and `ElementStyle` have *fields*
of those types (`Matrix4f`, `NodeId`, `TaffyStyle`), and field descriptors resolve at class load —
unlike method-body references, which don't. Someone will eventually try to strip them; don't.

> **The trap, found the hard way:** `StyleSheet.DEFAULT` is a `static final` that reads `default.css`
> through `CgIO` at class-init, so the entire `StyleSheet` class is unloadable headlessly — even
> `StyleSheet.parse()`. **If a test needs CSS text, it belongs in `test`, not `headlessTest`.**

---

# Frame lifecycle

```
UIDocument.frame(delta, w, h):
  JobScheduler.drain()          // answers from off-thread work land HERE, on the frame thread
  input().beginFrame()          // invalidate the hover cache -- never READ it
  animation().tick(delta)       // timelines and per-frame hooks, on the DELTA the host passes
  calculateStyle(delta)         // drain dirty-match parents-first, cascade, tick transitions
  layout(w, h)                  // sync the box tree, compute ONCE, read boxes, compose matrices
  settleAfterLayout(...)        // afterLayout hooks -- may move a box, may not add one
  input().endFrame()            // hover diff + dispatch of the frame's accumulated mouse events
```

**Animation before style before layout is load-bearing**, and it is why an ordinary per-frame hook
cannot read geometry: at the moment it runs, this frame's layout has not happened. Anything positioned
FROM a measured box uses `Animation.afterLayout` instead.

**`update(w, h)`** runs style then layout — for a geometry assertion that should not need a frame.
**`layout(w, h)` is the BOX TREE ALONE and runs no cascade**, so a test that reaches for the obvious
name asserts against a tree no stylesheet has touched: every rule appears not to match, which reads as
a broken selector rather than as a skipped pass. It cost a session in RPG-Core's first layout test.

**`beginFrame()` only INVALIDATES the hover cache; it must never read it.** A mouse-move already
invalidated it before `beginFrame` ran, so reading there is an eager recompute against the NEW position
mislabelled as the old one. That was the original stuck-hover bug; the baseline is a plain field
snapshotted at the end of the dispatch.

**Settling is bounded** (`MAX_SETTLE_PASSES`), which is the whole difference from the old engine's
`while (isLayoutDirty())`: a post-layout pass that keeps dirtying layout terminates instead of
converging by luck.

---

# Stack 1: the node tree — `ui/dom`

**A node has identity, attributes, children, a shadow root and events. It has no layout, no paint and
no geometry** — those are the box tree's, one stack down. That split is the whole point of the three-tree
design: the class it replaced carried 166 public members and answered every question about a widget,
which is why a change to any one of them could break the others.

## `UINode`

Both leaf and container, like a DOM `Element`. Its surface, by the sections the class itself is
divided into:

| Concern | Surface |
|---|---|
| Identity | `name()` (a `Name`, declared as a `NAME` constant on the class), `setId`, `addClass`/`removeClass`/`hasClass` |
| Attributes | `get`/`set` over typed `Attribute`s, each with an initial |
| Light tree | `append`/`insertAt`/`remove`/`removeAll`/`children()`/`parent()`/`indexOf`, and `moveDescribedChildTo` for a REORDER |
| Shadow tree | `attachShadow(delegatesFocus)`, `shadowRoot()`, `part` names; `appendStructural`/`insertStructuralAt` for a widget's own parts |
| Composed tree | `composedChildren()`, `composedSubtree()` — what paint and hit-testing walk |
| Lifecycle hooks | `connected()`, `disconnected()`, `slotChanged()` — queued during a mutation, drained after it |
| Styleable | everything the cascade asks: id/classes/type, the light parent and the COMPOSED parent (two different questions), nine state predicates, the shadow host and part name |
| Interaction state | `setFocused`/`setHovered`/`setPressed` — the services write it, the cascade reads it |
| Focus | `setFocusPolicy`, `focusable()`, `tabbable()`, `delegatesFocus` |
| Querying | `querySelector`/`querySelectorAll`/`getElementById`/`getElementsByClassName` — the light tree, and they STOP at a shadow boundary, as on the web |
| Scroll | `scrollTo`/`scrollTop`/`scrollLeft`/`scrollExtent`/`setScrollExempt` — per NODE, not per box, so it survives a box being rebuilt |
| Coordinates | `toLocal` — puts the node's OWN origin at zero (see the invariants; the old method did not) |
| Commands and keys | `registerCommands` (once per class, from `connected()`), `bindKeys`, `keymap()`, `commandParent()` |
| Events | pre-bound `EventListenerGroup<T>` fields, dispatched by `Input` over the COMPOSED tree with per-listener retargeting |
| Painting | `paintContent`, `paintDecoration` — content only; the BOX model is the painter's |
| Measuring | implement `Measurable` to be asked for a size; a widget that draws its own content and has no child nodes MUST |

**Scrolling is an ordinary node capability** driven by `overflow`, not a widget feature — and `box()` is
**nullable**, because a node that is hidden, frozen, `display: none` or simply not in a document has no
box at all.

## `UIDocument`

The root, and the owner of everything per-surface: the frame thread (`require`, asserted at every
mutation entry, per document), the `StyleEngine`, the four services, the top layer, the id index, the
tree observer, and document-level `DataProvider`s.

- `frame(delta, w, h)` is one whole frame: animation → style → layout → paint → the input diff.
- `layout(w, h)` alone, for a geometry assertion that needs no motion.
- `promote`/`demote` record top-layer membership **on the node**, not on a box — a box is destroyed and
  rebuilt whenever its subtree is hidden or restructured, so a flag written onto one is lost.
- `addDataProvider` — document-level, because the consumer of a key is often not an ancestor of the
  thing that asks.

## `ShadowRoot` and `UISlot`

`attachShadow()` gives a widget a tree of its own. Its parts are addressable from outside only through
`::part(name)`; an ordinary selector cannot reach in, though an INHERITED value still does — that is the
DOM's behaviour and not a leak. A `UISlot` is where a caller's content lands, and **a widget that takes
content needs one**: a light child of a shadow-hosting node with no default slot is in no composed tree
at all — no box, no paint, no promotion, and nothing anywhere reporting a problem.

`ShadowRoot.parent()` is null by design; `host()` is the way up, and `commandParent()` answers the host
so a command invoked inside a composite can still resolve outward.

## `Name` and `UIElementRegistry`

A kind's tag is a `Name` declared as a `NAME` constant **on the class it names**, and a subclass
inherits its parent's unless given its own — so a widget meant to be extended takes a `(Name, ...)`
constructor. `UIElementRegistry.bootstrap()` runs every `NodeKinds` service once, which makes the
registry's contents a function of the classpath rather than of a hand-written list.

## `TreeObserver` — the edit script

Four callbacks over the seam in `ui/dom`: `inserted` (with an index), `removed`, **`moved`**,
`attributeChanged`, `inlineStyleChanged`, `stateChanged`. A move is ONE event and never a
remove-then-insert, because a receiver applying those in order deletes the node — losing the instance
and everything in it — and then has nothing left to move.

**A shadow tree is invisible to the observer by construction**: shadow content is never a light child,
so a move across the boundary reaches the mirror as what the light tree saw. State is the exception —
`notifyStateChanged` walks OUT of every enclosing shadow tree to the nearest node the far side has
heard of, because a peer cannot act on a part it was never told about.

# Stack 2: Style — the cascade

> **Full reference: `docs/CGUI_STYLE_RENDER_PIPELINE.md`.** This is the map, not the territory.
> 86 files — the largest stack in the engine.

## Origins

`StyleOrigin` is priority-ordered: `DEFAULT(0) < USER_AGENT(1) < STYLESHEET(2) < INLINE(3) <
IMPORTANT(4) < ANIMATION(5)`. Two are easy to get backwards and both are deliberate:

- **`USER_AGENT`** is `default.css`, sitting *below* author sheets so a theme always wins at any
  specificity — exactly how a browser's UA sheet behaves.
- **`ANIMATION`** sits *above* `IMPORTANT` because a transition must be able to override an
  `!important` value mid-flight. Matches CSS Cascade L4/5.

## `ElementStyle` — two winner maps, not one

```
candidates: Map<StyleProperty, List<StyleSlot>>   // every value ever set, at any origin
    ↓ computeCandidateSlot  (origin → specificity → source order)
computedSlots  — the DISPLAYED winner, INCLUDING any ANIMATION candidate  → getComputed()
realSlots      — the REAL winner, IGNORING ANIMATION  → what we're settling toward
```

**Why two.** An `ANIMATION` candidate always wins the priority comparison. If the per-pass diff
compared against the *displayed* value, an in-flight transition would always look unchanged (it'd see
its own last tick), silently defeating both mid-flight retargeting and cleanup. So the diff runs
against `realSlots`.

`StyleSlot<T>` is `record(property, origin, specificity, sourceOrder, value)` with full CSS-cascade
`compareTo`. `replaceOrPutCandidate` **no-ops when the pushed value is unchanged** — which is what
makes widget-driven geometry feedback loops settle instead of oscillating.

## Writing styles — the origin pipelines

`StyleGroup` is the fluent write surface, and every write carries an origin. The static pipelines are
the idiom widgets use:

```java
StyleGroup.importantPipeline(getStyle().getLayoutGroup(), l -> l.height(measured));
StyleGroup.inlinePipeline(group, g -> ...);
StyleGroup.defaultPipeline(group, g -> ...);
StyleGroup.pipeline(origin, group, g -> ...);
```

Two concrete groups:
- **`GeneralGroup`** — visual: `background`, `overlay` (+`-fit`/`-origin`/`-position`), `outline`
  (+per-edge offsets), `opacity`, `color`, `backgroundColor`, `fontSize`, `fontFamily`, `lineHeight`,
  `caretWidth`, `selectionColor`, `textOffsetX/Y`, `transform`, `transformOriginX/Y`, `zIndex`,
  `overflow`, `scrollBehavior`, `scrollDuration`, `mask`.
- **`LayoutGroup`** — ~150-method fluent CSS box-model/flex/grid API, feeding Taffy.

## `StyleProperty<T>`

Carries `name`, `type`, `initialValue`, a `ValueParser`, plus three configuration flags that decide
its cascade behaviour:

| Flag | Meaning |
|---|---|
| `inheritable` | No candidate at any origin → fall back to the **parent's computed value** instead of `initialValue` (e.g. `color`). Anything box-model/layout leaves this false. |
| `allowTransition` | Whether `transition:` may animate it |
| `interpolator` | `IValueInterpolator<T>`; defaults to `BINARY` (snap) |

Properties also carry change listeners — this is how `LayoutProperties.init()` wires every layout
property straight through to `TaffyBridge`.

**Registered CSS properties** (`StylePropertyRegistry`) — the full set, alphabetically, so a missing
entry is visible rather than merely absent: `background`, `background-color`, `border-bottom-color`,
`border-color`, `border-top-color`, `caret-color`, `caret-width`, `color`, `cursor`, `font-family`, `font-size`,
`font-style`, `font-weight`, `line-height`, `mask`, `mask-fit`,
`mask-offset`, `mask-origin`, `mask-position`, `opacity`, `outline`, `outline-color`,
`outline-offset-{top,right,bottom,left}`, `outline-width`, `overflow`, `overlay`, `overlay-fit`,
`overlay-origin`, `overlay-position`, `resize`, `scroll-behavior`, `scroll-duration`,
`selection-color`, `text-align`, `text-decoration-color`, `text-decoration-line`, `text-offset-x`, `text-offset-y`,
`text-overflow`, `text-shadow`, `tooltip-delay`, `transform`, `transform-origin-x`, `transform-origin-y`,
`transition`, `white-space`, `z-index` — plus the whole layout set from `LayoutProperties`.

> **This list goes stale silently.** Registering a property is a one-line addition in a 300-line file and
> nothing links the two, so three of the entries above (`text-align`, `white-space`, `text-overflow`) were
> missing for a full release cycle after 5.2 shipped them, and `text-decoration-line` nearly repeated it.
> If you add a property, add it here in the same edit. `grep -oE 'create\("[a-z-]+"' StylePropertyRegistry.java`
> regenerates the set in one command.

## Adding a CSS property

Every property is a **triple**: a `StyleValue` (parse), a `StyleProperty` (identity + cascade
behaviour), and a registry constant. This is why `style/property/**` is ~60 files of near-identical
`Foo`/`FooValue`/`FooProperty` sets — the shape is formulaic, so copy the closest existing family
rather than inventing a new one.

**1. `StyleValue<T>` — the parser.** One method, `doCompute(String) -> T`. Computed **lazily and
exactly once**, then cached; a thrown exception is caught, logged as a warning, and yields `null`
rather than propagating — a malformed declaration degrades, it never breaks the cascade.

```java
public class FloatValue extends StyleValue<Float> {
    public FloatValue(String rawValue) { super(rawValue); }
    @Override protected Float doCompute(String raw) { return Float.parseFloat(raw.trim()); }
}
```

**2. `StyleProperty<T>` — identity and cascade behaviour.** Takes `(name, type, initialValue,
ValueParser<T>)`, where `ValueParser<T>` is just `String -> StyleValue<T>` (so `FloatValue::new`).
Configure with `setInheritable`, `setAllowTransition`, `setInterpolator`, `addListener`.

Prefer an existing **specialized subclass** — it presets those flags correctly:

| Subclass | Adds |
|---|---|
| `FloatProperty` | `min`/`max`/`step`, `setRange`, linear interpolator, `allowTransition` **on** by default |
| `AutoFloatProperty` | as above, plus an `auto` keyword |
| `IntProperty` | integer equivalent |
| `ColorProperty` | ARGB parsing + colour interpolation |
| `EnumProperty` | keyword → enum constant |
| `DimensionProperty`, `LPAProperty`, `LPSizeProperty`, `LPARectProperty` | Taffy-shaped length/percent/auto types. `DimensionValue`/`LPAValue` also accept **`em`** — see `FontRelative` |
| `LengthPercentProperty` | `LengthPercent` (used by offsets, radii, transform origin) |
| `GridProperty`, `GridTemplateProperty`, `GridAutoProperty`, `GridTemplateAreasProperty` | grid types |
| `TextureProperty`, `TransformProperty` | drawable / `Transform` |

**3. Register it** as a `public static final` in `StylePropertyRegistry`, via `create(...)`:

```java
public static final StyleProperty<Float> OPACITY   = create("opacity", 1f).setRange(0f, 1f);
public static final StyleProperty<Integer> COLOR   = create(new ColorProperty("color", -1)).setInheritable(true);
public static final StyleProperty<Overflow> OVERFLOW = create("overflow", Overflow.class, Overflow.VISIBLE);
```

The string here **is** the CSS property name — registering it is what makes it parseable in a
stylesheet. A property parsed but not yet acted on should still be registered, so sheets can declare
it without a warning.

**4. Expose a fluent accessor** on `GeneralGroup` (visual) or `LayoutGroup` (layout) — a getter
returning `getValueSave(PROP)` and a setter calling `set(PROP, v)`.

**5. Layout properties only:** `LayoutProperties.init()` attaches a `TaffyBridge`-calling
`addListener` so the computed value reaches the live Taffy style. Without that step a layout property
cascades correctly and changes nothing on screen.

## `BoxStyle` — the layout seam

**Nothing announces itself any more.** `LayoutProperties.init()` used to attach a listener to every
layout property that carried its computed value into the live Taffy style — that mechanism was the old
cascade's only route into layout, and it went with the old engine. `BoxStyle` reads the whole
`ComputedStyle` on every sync instead, so a layout property arrives by being READ. `createSetter`
survives as a no-op because its sixty call sites still say which Taffy setter a property belongs to.

The two changes that genuinely need telling go through `UINode.computedChanged`: a `font-size` that
moves an `em` under it, and `resize` growing grab handles.

### Both engines' defaults, and they diverge from CSS deliberately

`BoxStyle` states the project's defaults for anything a sheet leaves unset. This was tried the other
way at M5 — CSS's initials, on the reasoning that the divergences are a standing source of surprise —
and the bill came due at M6.1: in a 6,200-line user-agent sheet nearly every rule leaves the direction
unstated, so flipping it turned every unstated column into a row, and the failure is silent. A menu
came out 166px tall with its rows in the top 43 and nothing errored.

| Property | CrystalGUI default | Real CSS |
|---|---|---|
| `flex-direction` | `COLUMN` | `row` |
| `flex-shrink` | `0` | `1` |
| `box-sizing` | `BORDER_BOX` | `content-box` |
| `align-content` | `FLEX_START` | `stretch` |
| `min-size` | `0` both axes | `auto` |

| Property | CrystalGUI default | Real CSS |
|---|---|---|
| `flex-direction` | `COLUMN` | `row` |
| `flex-shrink` | `0` | `1` |
| `box-sizing` | `BORDER_BOX` | `content-box` |
| `align-content` | `FLEX_START` | `stretch` |
| `min-size` | `0` both axes | `auto` |

`border-box` is a project choice matching the common UI-framework convention (Bootstrap et al.) where
a declared width already includes padding+border. It happens to match Taffy's own default too, but is
assigned explicitly so it stays self-documented rather than at the mercy of a future Taffy version.

## Selectors

`style/selector/` — `Selector`, `CompoundSelector`, `SelectorType` with real CSS specificity weights:
`UNIVERSAL(0)`, `TYPE(1)`, `PSEUDO_ELEMENT(1)`, `CLASS(10)`, `PSEUDO_CLASS(10)`, `ID(100)`. Descendant
and child combinators are supported.

**Two pseudo-elements exist, and they are not the same kind of thing.** `::part(name)` selects a
**real element** inside a shadow tree and contributes to that element's own cascade;
`::highlight(name)` selects a paint-time overlay on the originating element and is collected into a
side table that never touches any element's cascade. `CompoundSelector.selectsShadowPart()` is the
discriminator, and conflating the two is the one way to get this badly wrong — a `::part` rule routed
down the highlight path silently styles nothing, and a `::highlight` rule routed down the part path
paints the whole paragraph. `::part` arrived with spike S2 (`plan/engine-rewrite.md` M0); see
`ui/shadow/`.

**`::highlight(name)`** — the CSS Custom Highlight API, for styling text
ranges without wrapping them in elements. It never matches the originating element (that is what
`matchesOriginating` is for), and `StyleEngine` cascades it into a `HighlightStyle` kept apart from
`ElementStyle`. `::before`/`::after` are rejected at parse time — shadow parts are the substitute.

**Not supported:** `:nth-child`, attribute selectors, `~`/`+` sibling combinators, `@media`, `@import`.

`PseudoClasses` — `ENABLED`, `DISABLED`, `CHECKED`, `BLANK`, `INVALID`, `HOVER`, `ACTIVE`, `FOCUS` —
each bound to a real `UINode` getter. **A widget gets a pseudo-class for free by overriding the
getter**; `Tab.isChecked()` is the whole implementation of `tab:checked`.

## Stylesheets

- `StyleSheet.parse(String)` — inline CSS text.
- `StyleSheetRegistry.of("crystalgui:ore")` — loads `assets/{ns}/ui/styles/{path}.css`, lazily parsed
  and `ConcurrentHashMap`-cached, so repeated calls return the same instance.
- `DeclarationParser` — declaration-level parsing including `var(--x)` custom-property substitution.
- `StyleSheet.DEFAULT` — the user-agent sheet (see the headless trap above). **Not applied
  automatically** — `window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT)` is a real call a caller
  has to make. A test that asserts on `default.css` behaviour without it silently exercises no CSS and
  passes for the wrong reason.

## `StyleEngine` — the per-window driver

A flat ordered sheet list (`addStylesheet`/`removeStylesheet`/`getSheets` — there is **no**
`clearStylesheets`), a dirty-match set, and `calculateStyle(delta)` which re-matches, cascades, and
ticks transitions.

> Sheet order is registration order, and re-adding a sheet **appends** it — i.e. at the *highest*
> priority. This matters for any runtime theme switch.

## Transitions

`transition: <prop> <dur> <easing>` on any property with `allowTransition`. `TransitionEngine` writes
at `ANIMATION` origin via `startAnimationSlot`/`tickAnimationSlot`/`endAnimationSlot`. Easings:
`Linear`, `CubicBezier`, `LinearPiecewise`, `ConstantEasing`, plus `ProgressFunctions`.

## `transform`

`Transform` is an **ordered list of ops**, not translate/scale/rotate fields — because CSS composes
left-to-right as matrix multiplication, so `translate(10px) scale(2)` ≠ `scale(2) translate(10px)`,
and a field-per-function decomposition cannot represent the difference at all.

- Immutable; build from `IDENTITY` + `then(...)`, or the `translate`/`scale`/`rotate` shorthands.
- `isIdentity()` is the fast path every caller checks first, so an untransformed tree pays nothing.
- **Layout-free by construction** — Taffy never sees it, so transforming an element cannot reflow its
  siblings. That's what makes a zoomable canvas possible (scale one container, not the window).
- Origin is **not** stored here — `transform-origin-x`/`-y` are separate cascading properties, so they
  theme and transition independently. `applyTo(...)` takes the origin already resolved to pixels.
- `applyTo` is the **single definition** used by both the hit-test transform chain and the render
  `PoseStack`. They must produce an identical matrix or clicks land somewhere other than what the user
  sees.
- Divergences: `matrix()` unsupported; axis variants (`translateX`) **collapse** into their
  two-argument form at parse time, so they interpolate against each other instead of snapping.

---

# Stack 3: the box tree — `ui/box`

**Laid out ONCE, with no feedback into it.** The old engine looped `while (isLayoutDirty())` and let a
post-layout callback write style that dirtied it again; that is what made `UIText` settle in two or
three passes and what made a placement write land in the same frame. Here a pass that wants to move
something writes on the NEXT layout, which is why an unplaced popup is laid out off-screen rather than
drawn at its containing block's corner.

| Class | Owns |
|---|---|
| `BoxTree` | one `TaffyTree` per document, synced from the COMPOSED tree only on frames the node tree REPORTED a structure change, restyled by `ComputedStyle` identity, computed once, and composed top-down into world matrices |
| `Box` | geometry, hosting, mirrors, the ONE `localToWorld`, and `hitTest` — which inverts exactly that matrix, so a click lands on what will be drawn with no paint having happened |
| `BoxStyle` | `ComputedStyle` → Taffy, and the only place the project's defaults are stated |
| `Measurable` | `Constraints`/`Size`/`Fit` — the engine ASKS a node for a size instead of being told |
| `BoxPainter` | every box drawn in its OWN space, with the pose set from `localToWorld` |

## Three widths, and they are different questions

`width()` is the border box. `clientWidth()` is the padding box — what scrolls. `contentBoxWidth()` is
the content box, where text goes. And `contentWidth()` is the extent of what is INSIDE, which for a
widget with no child nodes is **zero** — a `TextField` that draws its own glyphs has none, so a scissor
taken from `contentWidth()` clips its text away entirely.

## `Box.x()` is PARENT-RELATIVE

The old runtime cache accumulated through every ancestor, so `a.getX() - b.getX()` was a legitimate way
to ask where `a` is relative to `b` for any pair. Here it is only meaningful when the two share a
parent, and it is silently wrong otherwise — wrong by an amount that depends on how deep in the tree
they are. `Box.centreIn(box, space)` and `Box.originIn(box, space)` are the conversion, through
`worldToLocal`, which also carries the intervening transforms and scrolls that a subtraction never did.

## The engine writes nothing into the cascade

`BoxStyle` READS `ComputedStyle`. Where the old engine pushed geometry back at `IMPORTANT` origin — 117
sites of it — the new one either asks (`Measurable`) or writes a compositor OVERRIDE on the box
(`setTransform`, `setOpacity`, `setTransformOrigin`), which the box tree reads and which is withdrawn
with a `null`. An animation slot is the cascade's channel and must be ENDED; an override is not and must
not be mixed with one.

# Stack 4: the services — `ui/service`

**One 962-line input handler became four services with one job each**, and a live interaction became a
mode pushed onto a stack rather than another `if` at the top of the key handler.

| Service | Owns |
|---|---|
| `Input` | the platform sink, hit testing, three-phase dispatch over the COMPOSED tree with per-listener retargeting, pointer capture, keyboard activation, the cursor's `auto` rule, and the `Chords` seam a host fills |
| `Focus` | one owner, one traversal, focus navigation scopes, modality, `delegatesFocus` |
| `Animation` | timelines whose clock is the host's DELTA — so "the clock starts on the first tick" is structural — plus per-frame hooks OWNED by a node, and `afterLayout` for anything positioned from measured geometry |
| `Lifecycle` | freeze / thaw / destroy — a frozen subtree keeps its scroll, its text and its listeners |
| `Dismiss` | the popover stack, light dismiss, close watchers, Escape |

## Three-phase dispatch

`ui/event/` is **shared and unchanged**: `UIEvent` (`target`, `bubbles`, `phase`, `stopPropagation` /
`stopImmediatePropagation` / `preventDefault`), `PropagationPhase`, and the concrete `MouseEvent`,
`KeyboardEvent`, `FocusEvent`, `DragEvent`, `CloseEvent` types.

**Propagation is the DOM's.** `stopPropagation` ends the walk and the same node's remaining listeners
still run; `stopImmediatePropagation` ends those too. The old engine conflated the two, which is why a
widget stopping propagation in its own constructor pre-empted every later subscriber to that group.

`Enter`/`Leave` still dispatch to every node in the entered/left chain — outermost-first on entry,
innermost-first on exit — even though they do not bubble. Firing only on the precise hit target means a
container with children never hears about the pointer at all.

**A listener on a shadow host can never see its own parts**: `event.getTarget()` is retargeted before
the listener runs, and a listener attached to the host is OUTSIDE its own shadow root. The idiom the old
engine used everywhere — one listener on the widget, an if-chain comparing the target against its
shadow parts — compiles, runs, and takes the wrong branch forever. Attach inside the shadow tree.

## `InputMode` — a live interaction is a mode, not a special case

Drag, the window switcher, keyboard move and a modal each push an `InputMode`. The ladder that was four
hard-coded `if`s at the top of `consumeKeyboardEvent` is push order now, and `ModeStackTest` reads
`Input`'s constant pool to prove the service names no gesture.

**A modified chord goes to the keymap BEFORE content unless the target `claimsChord`** — which inverts
the old yield lists a widget could forget an entry from, and which cost `TextEditor` its Ctrl+Tab.

## `Focus`

One owner, and `focusable()` / `tabbable()` are still different questions — the first is "may this hold
focus at all" (focus delegation, arrow keys inside a composite), the second is "is it in the Tab
sequence". Click-focus tests `focusesOnClick()`, never `== CLICK`.

**Inertness is ONE predicate asked by two readers**, where the old engine enforced it at four points and
pinned each with its own test. A modal blocks the SCOPE CONTAINING it — a dialog is a scope itself, so
asking `scopeOf(modal)` answers the dialog and blocks nothing; a skipped box is not a CANDIDATE but its
children still are, or the modal itself goes out of reach; and opening a modal changes what is hittable
with no pointer movement and no frame, so it invalidates the hover itself.

## `Animation`

Timelines advance on the delta the host passes, so an animation cannot complete before it has rendered
a frame. An ordinary per-frame hook runs BEFORE layout — the frame is animation → style → layout — so
anything positioned FROM measured geometry needs `afterLayout` instead. A post-layout hook may move a
box and read a box and **may not add one**: a structural change would need another layout, and there is
no second pass.

A hook is OWNED by a node and stops when the node leaves the tree, which is what the old
the old one-way ticker registration could never guarantee.

# GL context lifecycle — `lifecycle/`

CrystalGUI owns GL resources and caches of GL-derived objects, but is not part of CrystalGraphics and
so cannot be enumerated in `CgGraphicsLifecycle`'s own teardown. The seam is
`CgLifecycleListener` (CrystalGraphics, `gl/lifecycle/`) — `onInit(w,h)` / `onFrame(frame)` /
`onDestroy()`, all default no-ops, registered via `CgGraphicsLifecycle.addListener(...)`.

**CrystalGUI registers exactly one**: `CgUiLifecycle`.

| Moment | What CrystalGUI does |
|---|---|
| `onInit` | Nothing — every GL resource is lazily built on first paint, and forcing them here would defeat `CgUiPaintContext`'s deliberate laziness. It *does* fire though (see below), so work added here will run |
| `onFrame` | Nothing — per-frame work is per-`UIDocument` (`frame`, and the `Animation` hooks it ticks), not global |
| `onDestroy` | `CgUiPaintContext.destroy()`, and nothing else |

> **`onInit` reaches late registrants.** CrystalGUI registers from a class initializer on the *first
> paint*, which is always after `CgGraphicsLifecycle.initContext()` has run — so a fire-only-during-init
> design would mean `onInit` never fires for CrystalGUI at all. `addListener` therefore delivers
> `onInit` immediately when a context is already live. The guarantee is **exactly once per context,
> regardless of registration time**. Without it the miss would be invisible while the hook is empty
> and appear as silently-skipped setup the moment it isn't.

> **`destroyContext()` fires only at game shutdown.** There is no destroy-then-init cycle in a running
> process, so there is no "next context" to protect, and **nothing needs invalidating merely because
> CrystalGraphics is about to free it** — font families, cached stylesheets, sprite packs and shared
> materials all die with the process. Do not add cache-clearing or material-invalidation to
> `onDestroy`; it is ceremony, not correctness.

What `onDestroy` legitimately does is release the one thing nobody else frees: `CgUiPaintContext`'s
layer FBO pool is built with `CgFrameBuffer.createOwned`, which bypasses `CgFrameBufferRegistry`, so
`deleteAll()` never reaches it.

**`onDestroy` fires before CrystalGraphics frees anything** — the only window in which a listener can
release its own FBOs/renderers while the context is whole. Listeners dispatch in *reverse*
registration order.

> **Registration is automatic** — a `static` initializer in `CgUiPaintContext` calls
> `CgUiLifecycle.register()`, so CrystalGUI wires itself as soon as that class comes into play. Class
> init runs once per classloader, so registration cannot repeat across a destroy/recreate cycle. A
> process that never paints never touches the class, which keeps a dedicated server free of
> CrystalGraphics. `register()` is idempotent and public for explicit use.

---

# Stack 5: Render — immediate-mode

**The V3.1 draw-list design is gone.** `CgUiDrawList`, `CgUiDrawListExecutor`, `CgUiDrawState`,
`CgUiBatchSlots`, and `CgScissorRect` do not exist. Do not reference them.

## `CgUiPaintContext` — a singleton

Obtained via `CgUiPaintContext.getInstance()`, **not** owned per-`UIDocument`. Every `fillRect`/
`drawImage` call draws immediately; there is no recording phase and nothing to flush.

| Group | Methods |
|---|---|
| Frame | `beginFrame(w,h)` / `endFrame()` — save/restore GL via `CgGlScope`, ortho projection, bind `crystalgui:shaders/gui_quad.shader`, reset `ScissorStack` |
| Draw | `fillRect`, `drawImage`, `quad()` + `flush`, `curve()`, `bindTexture` (elides redundant rebinds), `text()` → a `CgTextRenderer` wired to this context's `PoseStack` |

> **`curve()` is `quad()`'s twin, and switching between them flushes.** Bézier strokes go through
> `CgVectorRenderer` with their own instance buffer and their own material (`gui_curve.shader`), and GL
> binds one program at a time — so the two cannot both be live. Every switch flushes the outgoing path,
> which is a **painter's-order requirement, not tidiness**: letting queued quads survive a switch would
> draw them after the curves regardless of submission order, so a stroke under a panel would jump on top
> — and only when the two happened to batch together, which reads as a z-order bug in the widget rather
> than a batching bug in the context. Because every switch flushes, at most one path ever holds pending
> work, which is what makes `CgUiRenderer.flush()` safe to run over both in any order. Alternating them
> per element costs a draw call each way; correctness never depends on batching.
>
> `curve()` applies the `PoseStack` exactly as `quad()` does — **never call `.pose(...)` on the result** —
> and stroke widths are scaled by the pose too, so a 2px stroke stays 2 *logical* px at any `uiScale`,
> the same as a 2px border.

> `quad()` returns `CgQuadRenderer.Quad` — `ctx.quad().at(x,y).size(w,h).uv(...).color(argb).submit()`,
> then `flush()` to draw (`submit()` only queues). **Never call `.pose(...)` on it**: `CgUiRenderer.quad()`
> is the single place the `PoseStack` is applied, and overwriting it silently drops `uiScale` and the
> element transform. It's re-applied per call because `CgQuadRenderer.quad()` resets the scratch
> instance's pose to null. The returned object is that shared scratch — build and `submit()` in one
> expression, never hold it.
| Clip | `pushScissor` / `popScissor` |
| Material | `withMaterial(material, body)` |
| Layers | `withLayerOpacity(opacity, body)`, `beginLayerFbo()` / `endLayerFbo()`, `blitLayer(fbo, opacity)`, `compositeMask(subtreeFbo, maskFbo)` |
| Lifecycle | `hasInstance()`, `destroy()` |

> **`destroy()` must be called on GL-context destruction.** The instance is `static`, so it outlives
> the context it was built against; without this the next context is handed an object whose material,
> VAO/VBO and FBO handles are all dead — which draws nothing or draws garbage rather than failing
> loudly. It frees only what the context genuinely owns (the layer FBO pool, the `CgUiRenderer`, the
> `CgTextRenderer`) and deliberately not what it borrows from CrystalGraphics registries (materials,
> the fallback white pixel, font atlases), since those are swept by `CgGraphicsLifecycle.destroyContext()`
> and freeing them here would be a double free. Use `hasInstance()` to check without *causing*
> construction.

> **Opacity isolation and masking go through an FBO layer pass, not a flat multiply.** The
> tint-vs-layer-opacity distinction is the thing most likely to be got wrong here — read
> `docs/CGUI_STYLE_RENDER_PIPELINE.md` §5 and §8 before touching it.

## Supporting classes

- **`CgUiRenderer`** — thin wrapper over CrystalGraphics' `CgQuadRenderer` **and `CgVectorRenderer`**:
  instanced unit quads whose per-instance record (`origin` + `right`/`up` edge vectors, UVs, colour)
  lives in a class-wide SSBO/TBO. The `PoseStack` matrix is baked in at `submit()` time by
  `Quad.pose(...)` — three transforms per quad rather than four corners, and affine-correct under
  `transform:`. **Material bind/unbind is owned by `CgQuadRenderer.useMaterial()`**, which must be
  called before any `submit()` and again every frame; never call `material.bind()` yourself. Text goes
  through the same renderer (CrystalGraphics' `CgTextRenderer` owns its own `CgQuadRenderer` instance).
  The curve half mirrors all of it — `curve()` applies the pose, `useCurveMaterial()` binds, and
  `flushQuads()`/`flushCurves()` exist so `CgUiPaintContext` can flush one path without the other when
  it switches between them.
- **`ScissorStack`** — allocation-free nested clip stack (`int[64]`, 16 levels × 4 ints), applied via
  CrystalGraphics' `CgGL` facade. **No LWJGL imports** — the old "V3.x legacy, raw GL11, scheduled for
  deletion" note is obsolete.
- **`FontFamilyCache`** — `(font-family stack, target px)` → `CgFontFamily`, cached. Reference
  equality on the result is therefore meaningful and is relied on by `UIText`.

## Drawables — `render/texture/`

`CgUiDrawable` is the pluggable "paint yourself into a rect" SPI:
`draw(ctx, mouseX, mouseY, x, y, w, h)`, plus `intrinsicWidth()`/`intrinsicHeight()` returning `-1`
when the drawable has no inherent size (solid colours, SDF shapes). One `draw` call is expected to
issue exactly one GPU draw call, or zero for a fully transparent tint.

| Class | Role |
|---|---|
| `CgUiQuad` | Flat solid-colour fill; `CgUiDrawable.EMPTY` is one |
| `CgUiSprite` | Full 9-slice textured sprite (`setTexture`/`setSprite`/`setBorder`, lazy UV cache) |
| `CgUiRoundedRect` | SDF path — per-corner radii, morphing |
| `CgUiCrossFade` | Blends two drawables, for `background` transitions |
| `CgUiLayerBox` | Composites a stack; resolves `overlay-fit` via `intrinsicWidth()` |
| `CgUiRepeat` | Tiling modes |
| `ArgbMath` | Shared colour maths |
| `CgUiSvg` | Draws an `SvgDocument` into a rect — fitted and centred, never stretched. See below |
| `CgUiGradient` | `linear-gradient(direction, stops…)` — CSS's, at **any angle** (`deg`/`turn`/`rad`, `to <side>`, `to <corner>` resolved per box): ONE draw through `gui_gradient.shader` evaluating up to eight stops per fragment along CSS's gradient line (Skia's unrolled shape; more stops are more draws, each owning a window of *t*), interpolated **premultiplied** (CSS Images 3 — `transparent` is transparent black, and a straight lerp toward it passes through a dark half-colour), **dithered** ±0.5/255 (per-fragment mixing removes the strip edges the first version had, not the level edges), and `CornerRadiusAware`, so it masks itself under a `border-radius` (the self-clipping gap applies: no `border-width` stroke). Measured on `cgui-gradient-probe`: a 16-level ramp's column means step 0.23 levels at most; the fade to transparent matches the premultiplied prediction to 0.1 level. First consumer: the taskbar's accent glow |
| `CgUiTransformDrawable` | Empty marker class — not implemented |

`render/texture/asset/CgUiSpriteRegistry` resolves `"namespace:name"` → sprite lazily from
`assets/{ns}/ui/sprites/{file}.json`. **A resource pack ships a theme by shipping JSON + PNG** — no
registration call. This is what `background: asset("crystalgui:ore", "button")` goes through.

`render/texture/svg/` is a **full SVG renderer** — scanner, path grammar, transforms, colour, inheritance,
scanline fills with holes cut, and real linear/radial gradients — parsing an `.svg` once into a cached list
of draw ops and submitting them through `ctx.curve()`/`ctx.triangle()`. **No atlas, no bake, no texture, one
instanced draw call for every icon on screen.** Full account in `ICONS.md`, including the nine things it
deliberately does not implement and why none of them matters for icons.

> **Two seams here are easy to get backwards, and both exist for the same reason: a document is shared.**
> `currentColor` is left unresolved in the cached ops and bound at draw time, so one parsed icon backs a
> selected row and an unselected one in the same frame. And `CgUiSvg` is a separate class rather than
> `SvgDocument implements CgUiDrawable`, because `draw()` has no tint parameter — a document implementing
> it would need a mutable tint field, and the two rows above would be writing to the same one.

`render/texture/geometry/` holds `Position` and `Size`, small Lombok `@Data(staticConstructor="of")`
int value types.

---

# Widgets

> **Full reference: `docs/CGUI_WIDGETS.md`** — per-widget API, `::part()` names,
> pseudo-classes, and covering harness scene. Read it before writing a new widget.

| Widget | Tag | Harness scene |
|---|---|---|
| `Button` | `button` | `cgui-button` |
| `Checkbox` | `checkbox` | `cgui-checkbox` |
| `CheckboxGroup` | — (not a `UINode`) | `cgui-checkbox` |
| `Switch` | `switch` | `cgui-switch` |
| `Slider` | `slider` | `cgui-slider` |
| `TextField` | `textfield` | `cgui-textfield` |
| `UIText` | `text` | `cgui-text`, `cgui-text-stress` |
| `Tooltip` | `tooltip` | `cgui-gallery` (Tooltip page) |
| `Dialog` | `dialog` | `cgui-gallery` (Dialog page, modal page) |
| `Popover` | `popover` | `cgui-gallery` (menus page) |
| `Menu` | `menu` | `cgui-gallery` (menus page) |
| `MenuItem` | `menuitem` | `cgui-gallery` (menus page) |
| `Dropdown` | `dropdown` | `cgui-gallery` (menus page) |
| `CanvasView` | `canvasview` | `cgui-gallery` (graph page) |
| `GraphView` | `graphview` | `cgui-gallery` (graph page) |
| `GraphNode` | `graphnode` | `cgui-gallery` (graph page) |
| `NodePort` | `nodeport` | `cgui-gallery` (graph page) |
| `Scroller` | `scroller` | `cgui-scroller` |
| `ScrollerView` | `scrollerview` | `cgui-scroller` |
| `SplitView` | `splitview` | `cgui-splitview` |
| `TabView` | `tabview` | `cgui-tabview` |
| `Tab` | `tab` | `cgui-tabview` |
| `Desktop` | `desktop` | `cgui-desktop` — **nobody constructs one**; `UIDocument.desktop()` owns it |
| `WindowFrame` | `window` | `cgui-desktop` — opened with `UIDocument.openWindow(frame)` |
| `Taskbar` | `taskbar` | `cgui-desktop` — the `WindowRegistry`, rendered; built by `Desktop` |
| `WindowSwitcher` | — (not registered) | `cgui-desktop` — `Mod+Tab`; built by `Desktop`, nobody constructs one |

## Conventions — all enforced in code

- **A widget that says its structure is fixed refuses public children.** `refusePublicChildren()` in
  the constructor every other one CHAINS THROUGH — on the no-arg one a `new Dialog("title")` never makes
  the declaration at all. It is a promise a widget makes about itself, NOT something derived from
  whether it has a default slot: an unslotted light child is the web's ordinary state and is legal,
  which three tests pin outright. Give a widget a named accessor for its content instead of opening the
  tree.
- **Structure is a SHADOW TREE, and a caller's content needs a SLOT.** `attachShadow()` plus
  `appendStructural`, with each part carrying a `part` name a theme reaches through `::part(name)`.
  A widget that takes content must give its slot a home, or a light child of it is in no composed tree
  at all — no box, no paint, no promotion, and nothing anywhere reporting a problem.

  **A widget may host a shadow tree only if nothing reaches THROUGH its structure**, because `::part()`
  has no spelling for a part under a part, a tag under a part, or a nested widget's part. Measured over
  the shipped sheets: **23 widgets can, 21 cannot, and 220 rules have no `::part()` spelling at all** —
  `colorselector` alone has 51. A subclass cannot un-shadow its parent, so decide the base class first.

  The part names in use are the old `__double-underscore__` classes with the wrapper removed —
  `__mark__` became `mark`, `__thumb__` became `thumb`. The sheets still carry both spellings: a class
  rule for what is still a light child, and a `::part()` twin for what is not.
- **No sizes, no timings, no colours in Java.** Widgets write structure and state; `default.css` gives
  functional geometry, `ore.css` gives appearance. `Switch`'s knob animation is a CSS `transition` on
  `flex-grow`, not a Java tween. **If you are typing a pixel value into a widget, it belongs in
  `default.css`.**
- **Per-frame work is an `Animation` hook OWNED by a node**, not a ticker a widget registers and can
  never unregister. It stops when the node leaves the tree, which is what the old one-way registration
  could not guarantee — a hidden window's ticker carried on invisibly. Anything that reads GEOMETRY
  uses `afterLayout` instead: an ordinary hook runs BEFORE this frame's layout.
- **New pseudo-class = override a getter.** See `PseudoClasses` above.

## `UIText` — asked, not told

**It implements `Measurable`.** Given a width, how tall are you: one pass, nothing written back.

That is worth stating because the class it replaced could not be asked, and **about four hundred of its
lines existed only because of that** — each a defect with a real invariant behind it. `selfSizesWidth`
was latched once from whether the box measured zero on the first post-attachment pass, which is a race
against an ancestor's not-yet-converged layout, held for the element's life: it latched `false` on a
graph node's title against a placeholder width and truncated it for good. `forceSelfSizeWidth()` and
`neverSelfSizeWidth()` were the two escape hatches for callers who knew the answer and had no way to
state it. And `invalidateMeasurement()` carried the deadlock it is named for, where withdrawing the
pushed size made the box resolve to zero, and zero-in-zero-out is not a geometry change, so nothing
ever asked again.

Min-content and max-content are questions the engine asks per layout now, so there is no latch to
pre-empt and no loop to make terminate. **Taffy asks for BOTH**, and answering the minimum with one
unbroken line pins a text leaf's minimum at its whole line — `Measurable.Fit` carries the question and
the minimum wraps at 1px.

What replaces the old engine's four static property listeners is one `computedChanged` hook, and it
must watch `font-weight` and `font-style` as well as size and family: synthesis is per SPAN, so a bold
label resolves the same `CgFontFamily` instance a regular one does and the paragraph's own "has the
family changed" check answers no.

`text-overflow: ellipsis` truncates the **string** and re-shapes, never drops glyphs from the shaped run
— shaping is not a per-character mapping, so cutting the glyph array splits clusters. The ellipsis is
`…` when the font stack can draw U+2026 and `...` when it cannot, which is WebKit/Blink's own rule and
not hypothetical: the bundled `MinecraftRegular.otf` has no U+2026, and without the fallback a
truncated label draws a blank advance and is indistinguishable from `clip`. `displayedText()` returns
what will actually be painted — the only observable evidence that truncation fired.

It retains a `CgShapedParagraph`, rebuilt only when the text or the resolved `CgFontFamily` instance
actually changes — never on a resize. Reference equality on the family is correct because
`FontFamilyCache.resolve` caches by `(paths, targetPx)`.

---

# Server layer — `serialization/` + `net/`

> **Full reference: `docs/CGUI_SERVER_AND_SERIALIZATION.md`.** Don't reverse-engineer it from classes.

A dedicated MC server builds a UI tree with **no CrystalGraphics present**, ships a description, and
talks to the client over RPC and bindings.

- **`serialization/`** — `Codec<A>`/`DynamicOps<T>`/`Codecs` (DFU-shaped), `JsonOps`, `PlainOps`,
  `StateMap` (widget state), `UIElementMirror`, `ContentHash`; `serialization/style/` holds
  `StyleValueCodecs` and `InlineStyleCodec`.
- **`net/`** — `UITransport`, `InMemoryTransport`, `ServerUiSession`, `ClientUiSession`, `SheetRef`;
  `net/mirror/` holds **the mirror** — `ServerTreeMirror`/`ClientTreeMirror` (generic in the node type,
  written against the `ui.dom` seam), the `NodeMirror` per-tree codec seam, `UIElementMirror` over
  today's tree, and `TreeOps` (the `insert`/`remove`/`move` vocabulary); `net/protocol/` holds the
  four-kind `Envelope`,
  `EnvelopeCodec`, `MessageRouter`, `Call` and the `UiMethods` vocabulary; `net/wire/` holds the
  multiplexed byte transport (`FrameCodec`, `FrameMultiplexer`, `WireTransport`) over the
  four-method `CgNetworkChannel` platform seam.

Three design facts worth knowing before you touch it:

1. **`ServerUiSession` holds no `UIDocument`.** That absence *is* the headless story, structurally
   rather than by flag: no window → no Taffy tree, no style engine, no layout → no path into text
   measurement, the one thing that genuinely needs a font stack.
2. **Descriptions are content-addressed.** `UIElementMirror` output must be byte-identical for the
   same tree, so field order is fixed, maps are insertion-ordered, and absent optionals are omitted
   rather than written null. `OpenWindow` carries the *hash*, not the description — re-opening a UI
   costs one small packet however large the tree.
3. **Every packet carries a window id.** Resolving against "whatever menu is open" lets a packet in
   flight when a GUI closes land on the *next* one; four bytes makes that impossible.

`UIElementMirror` encodes `{ tag, id?, class[]?, style{}?, flags?, focus?, state{}?, children[]? }`,
skips shadow parts (the constructor rebuilds them), and **throws on an unknown tag**.

---

# Load-bearing invariants

**Moved out: [`docs/CGUI_INVARIANTS.md`](docs/CGUI_INVARIANTS.md).** 298 rows, grouped by subsystem —
threading, coordinates, the cascade, dispatch, GL, widgets, the workbench, the wire, the editor, the
language stack, the build. It was a 560-line section here, a quarter of a file that is read at the start
of every session, and most of it was one bug in one class rather than a rule. **Read it when you are
about to touch one of those areas; do not read it front to back.**

Eight that bite most often, and each is one line because the full row is in that file:

| | |
|---|---|
| `box()` is **nullable** | A node that is hidden, frozen, `display: none` or not in a document has no box at all |
| `Box.x()` is **parent-relative** | `a.x() - b.x()` means nothing unless they share a parent. Use `Box.centreIn`/`originIn` |
| `toLocal` puts the box's **own origin at zero** | So a caller wanting an absolute coordinate adds `box().x()` deliberately |
| The **frame thread owns the tree**, per tree | Anything touching a node runs there; anything that is a pure function of a snapshot must not |
| `flex-shrink` defaults to **0** here | A `flex-grow: 1` child overflows its parent rather than shrinking. The fill idiom is `width: 100%; height: 0; flex-grow: 1` |
| `font-size` does **not** effectively inherit | `default.css` opens with `* { font-size: 10 }`, which is a candidate on every element |
| A listener on a shadow host can **never see its own parts** | `getTarget()` is retargeted before it runs. Attach inside the shadow tree |
| What GL permits is **not** what the host tolerates | Blaze3D models twelve texture units; binding above it corrupts unit 0 for whoever samples it next |

# Global coding rules

## Port, don't reinvent

**Anything that has been solved thousands of times — text editing, cursor movement, click and drag
selection, undo coalescing, layout — is ported from a battle-tested source and fine-tuned for this
codebase. It is not derived from first principles.**

These behaviours are *conventions, not derivable answers*. Each is one line, each is invisible when
wrong, and each was learned by shipping to millions of users. Four from `text/cursor/` alone:

| Rule | What happens without it |
|---|---|
| Auto-close fires on an **allowlist** (`;:.,=}])> \n\t`), never a denylist | "suppress before a letter" still opens a pair before `$foo` and `#define` |
| A plain arrow collapses a selection to its **edge**, regardless of which way the gesture went | Left-then-right on a backwards selection walks the caret instead of collapsing |
| A partly-commented block **comments out**, it does not half-toggle | Selecting a block with one commented line inverts half of it |
| A backwards word-drag **unions with the anchor word** | Word-granularity drag eats into the word it started on and stops feeling like words |

### Licences are load-bearing here

| Source | Licence | What you may do |
|---|---|---|
| VS Code / Monaco, CodeMirror 6 | **MIT** | **Port the code.** Attribute in the class javadoc, naming the source file. |
| **Chromium** | **BSD-3-Clause** | **Port the code.** Attribute in the class javadoc and in `THIRD-PARTY.md`. `RateEstimator` is one. |
| **Zed**, **wget** | **GPL** | **Read for shape only.** Copying would impose GPL on this repository. `Rope`/`TextSummary` take `SumTree`'s *design*; the progress channel takes wget's *refresh the ETA about once a second" and not a line of its code. |

### Port the module boundaries too

`com.crystalgui.text.cursor` takes its boundaries from VS Code, though **not file-for-file** — the
mapping is worth stating because two of the five come from elsewhere in that tree:

| Ours | VS Code |
|---|---|
| `CursorColumns` | `common/core/cursorColumns.ts` |
| `MoveOperations` | `common/cursor/cursorMoveOperations.ts` |
| `TypeOperations` | `common/cursor/cursorTypeOperations.ts` |
| `LineOperations` | `contrib/linesOperations/browser/linesOperations.ts` — **not** `common/cursor/` |
| `MouseSelection` | `browser/controller/mouseHandler.ts` — **not** `common/cursor/` |
| `ColumnSelection` | `common/cursor/cursorColumnSelection.ts` |

Monaco's `common/cursor/` holds twelve files; the six we have no counterpart for are its orchestration
layer (`cursor.ts`, `cursorCollection.ts`, `oneCursor.ts`, `cursorContext.ts`, `cursorMoveCommands.ts`)
plus **one** remaining feature gap: `cursorAtomicMoveOperations.ts` — `editor.useTabStops` for *arrows*,
where a left or right arrow steps a whole indent unit through leading whitespace. Backspace already does
(`TypeOperations.backspaceFrom` counts visual columns), which is the half that was reported; the arrows
still move by one character.

> **Every column question in this package is a VISUAL one**, and that is the single most portable thing
> about it. A box selection computed from character offsets is not a box — two rows whose indentation
> differs in tabs have the same character column at different places, so the "rectangle" comes out as a
> ragged edge following the text. The same rule is why Backspace takes one tab rather than an
> indent's worth of characters, and why a paste is re-indented by columns rather than by string length.

> This is not tidiness. The same logic first went in as private methods on `TextEditor`, which reached
> **2556 lines, larger than the entire `com.crystalgui.text` package combined**, and could only be
> reached through a `UIDocument` with fonts, a style engine and an input handler. Extracting it exposed a
> real bug within minutes — deleting the *last* line left a blank line, because the last row has no
> trailing newline to take and must swallow the one before it instead. The widget test never caught it:
> it only ever deleted a middle line. **Porting the algorithms without the boundaries keeps the
> algorithms and throws away the testability that keeps them correct.**

## CrystalGraphics ownership boundary

**CrystalGraphics owns the rendering backend. CrystalGUI consumes it.**

- CrystalGUI may define renderer-facing abstractions and UI draw orchestration.
- Fonts, shaders, framebuffers, VAO/VBO, draw submission, GPU resource ownership, and modern GL
  pipeline capability belong in **CrystalGraphics**.
- Never write raw GL, raw `float[]` vertex packing, or a hand-rolled buffer in `core/`.

Because CrystalGraphics lives in this repo and is directly writable, if CrystalGUI needs a new backend
capability we **add it to CrystalGraphics** and integrate against the new API — we do not reimplement
the backend here.

> The full infrastructure ownership map (`CgStreamBuffer`, `CgStagingBuffer`, `CgVertexWriter`,
> `CgInstanceWriter`, `CgBufferWriter`, `CgShaderBuffer`, `CgMesh`, `CgVertexArray`,
> `CgAbstractShaderProgram`, and the decision tree for picking between them) lives in
> **`CrystalGraphics/AGENTS.md`**, which `CLAUDE.md` already loads every session. Read it there;
> it is not duplicated here.

## Platform-agnostic core

`core/` must stay fully cross-platform — the import guard enforces no `net.minecraft.*`,
`cpw.mods.fml.*`, `net.minecraftforge.*`, `org.lwjgl.*`.

**The platform seam is CrystalGraphics'.** CrystalGUI has no registry of its own — it reads everything
through `CgPlatform`, and a loader registers exactly one `CgPlatformService` bundle:

| Need | Reached via | Lives in |
|---|---|---|
| Key/mouse codes, modifier state, **and the clipboard** | `CgPlatform.input()` | `platform/service/CgInputService` |
| UI sounds | `CgPlatform.sound()` | `platform/service/CgSoundService` |
| Presenting a cursor | `CgPlatform.cursor()` | `platform/service/CgCursorService` |
| Raw event sink (`Input` implements it) | — | `platform/input/CgSystemInput` |
| Code constants, cursor enum, cursor artwork | — | `platform/input/CgKeyCodes`, `CgMouseCodes`, `CgModifiers`, `CgCursor`, `CgCursorBitmaps` |

> **The clipboard is on `CgInputService`, not a service of its own.** It is not conceptually input, but it
> is reached the same way and needed by exactly the code that handles keys — two methods do not earn a
> registration slot. Both default to a no-op pair.

**No method in this SPI has a default, and neither sound nor cursor ships a `NOOP` constant.** A default is
an answer chosen for someone who never saw the question: a new platform compiles cleanly while silently
inheriting "no sound, no cursor, no clipboard", and inheriting a no-op is indistinguishable from deciding
on one. Abstract methods make the compiler the reminder — and a platform with nothing to offer still says
so, with an empty body in its own source.

> **Why this stopped being CrystalGUI's own registry.** `CrystalGuiCore` used to hold four static fields
> with setters. CrystalGraphics is the parent project and is always present, so two registries meant a
> loader had to find both — and could wire up one, leaving a UI with a working GL backend and no keyboard.
> One bundle makes a platform either registered or not. `CrystalGuiCore` now holds only `LOGGER`.

## Lombok

Prioritize Lombok to eliminate handwritten accessor boilerplate. It generates Java 8-compatible
bytecode and is `compileOnly` — no runtime dependency.

| Annotation | Use when |
|---|---|
| `@Data` | Simple POJOs, all fields in equals/hashCode/toString |
| `@Getter` / `@Setter` | Selective access — apply at field level when only some fields need accessors |
| `@RequiredArgsConstructor` | Immutable classes — pairs with `@Getter` only |
| `@Builder` | 4+ constructor parameters, or many optional ones |
| `@Value` | Fully immutable data carriers |
| `@ToString` / `@EqualsAndHashCode` | When you need one without full `@Data` |
| `@Accessors(chain = true)` | Fluent setters — used widely here |

Do **not** use `@Data` on classes with inheritance — use explicit annotations and always
`@EqualsAndHashCode(callSuper = true)` on subclasses.

---

# Package map

```
com.crystalgui.core            CrystalGuiCore — the global LOGGER, and nothing else. The platform
                               registry it used to hold now lives in CrystalGraphics; see below.
  .data                        CacheCell / IntCacheCell / LongCacheCell (dirty-flag memoization),
                               ReadOnlyVec2f (immutable view over a mutable JOML Vector2f), Transform2D
  .dispose                     Disposable, Disposable.Gl, Disposer — the ownership tree. NOT a
                               replacement for CgGraphicsLifecycle's registry sweep; it exists to
                               release on CLOSE rather than on exit, and to reach createOwned GL
                               objects no registry can see. docs/CGUI_WORKBENCH_SERVICES.md
  .property                    Property<T> (binding, equality-suppressing set), ObservableList<T>
  .signal                      Signal.Action/Value/Pair, SignalBase, Connection, ConnectionGroup
  .command                     Command (a named invocable action), CommandContext, CommandRegistry —
                               what a key binding, a menu item and the palette all point at. Plus the
                               MENU MODEL: MenuId (a named place a menu is drawn, interned, with nested
                               submenus), MenuSection (a group + its rows — what a separator is drawn
                               from), MenuEntry (Item/Submenu, sealed; an Item carries enabled/checkable/
                               checked so the RENDERER decides), MenuContributor (rows computed at open
                               time — the Window menu's editor list). CommandRegistry.sections() is the
                               one query every menu renderer reads; menu() is its deprecated flat view
  .undo                        Edit (one undoable change), CompositeEdit, UndoStack — one history per
                               DOCUMENT, never per window
  .window                      WindowState, WindowPolicy, DesktopPresentation — three types BOTH engines
                               name, so a package both may name. The D27 rule that gave 6.3
                               `core.collection` and 6.4 `graph.port`; a pure enum, a policy record and a
                               presentation enum, none of them naming an engine type at all. ScreenOverlay
                               was the fourth candidate and does NOT qualify: it holds a document and reads
                               its focus owner, so it is a facade OVER the engine rather than an SPI a host
                               implements, and it lives in `desktop.host`. plan/engine-port.md 6.6

com.crystalgui.widget.texteditor  THE EDITOR ON THE NEW ENGINE (M6.5) — TextEditor and
                               EditorCommands (its named actions). TWO files at the root; everything
                               else is in a sub-package below.
  .part                    VS Code's VIEW-PART decomposition: EditorViewPart (the base + Monaco's
                             shouldRender protocol), DecorationPool (the pool/hide idiom), and one
                             part each -- LineNumbersPart, ViewCursorsPart, SelectionsPart,
                             CurrentLinePart, IndentGuidesPart, WhitespacePart, RulersPart,
                             GutterEdgePart, FoldingDecorationsPart, ZoomIndicatorPart,
                             ErrorStripePart, SquigglesPart, QuickFixBulbPart, InspectionWidgetPart,
                             DiffBandsPart, DiffChevronPart.
                             `render(int, int)` is PUBLIC here, which is the boundary's whole cost and
                             an honest statement of what it always was: the contract between an editor
                             and the things that draw it
  .fold                    EditorFolding — folding is VIEW STATE by the engine's own rule, and this
                           is the package that says so
  .diff                    DiffDecorations — the diff model the two diff view parts read
  .suggest                 CompletionPopup, CompletionSession, CompletionRanking, EditorSuggest
  .doc                     DocumentationPopup, HoverDocumentation
  .find                    SearchReplaceBar, EditorFind
  .lang                    EditorLanguageFeatures, EditorDiagnostics, DiagnosticActions

com.crystalgui.desktop         CRYSTALOS ON THE NEW ENGINE (M6.6) — Desktop (the compositor, found with
                               `Desktop.of(document)` and never built by a caller: the engine may not
                               name a compositor, so the compositor names the document), DesktopCommands,
                               DesktopSession, DesktopKinds (the layer's NodeKinds service). FOUR classes
                               at the root, which is the whole of what a layer root is for.
  .window                      WindowFrame and everything a window IS: WindowChrome, WindowCommands,
                               WindowRegistry, WindowIcon, WindowMove, WindowKeyboardMove, SnapZones,
                               SystemMenu, WindowSnapshot
  .motion                      WindowAnimator over WindowAnimation (transform + opacity, what a
                               compositor does) and WindowGeometryAnimation (layout, because a size
                               change REFLOWS), behind WindowMotion. Writes through Box's compositor
                               overrides, never the cascade
  .taskbar                     Taskbar (the registry RENDERED), TaskbarEntryMotion, TaskbarPreviews,
                               TaskbarDesigner, WindowPreview, WindowThumbnail
  .switcher                    WindowSwitcher — Mod+Tab, MRU order, live thumbnails
  .host                        ScreenOverlay, HostServices, DesktopHost, DesktopWindowMount — what a
                               LOADER talks to. Three questions (where private files go, how big a pixel
                               is, is there a connection) and it gets a desktop, a workspace that
                               follows the wire, and somewhere for a server's windows to land
  .app                         WHAT AN APPLICATION IS, and it names no workbench: ApplicationKinds (the
                               ServiceLoader SPI a layer declares its products through -- nothing
                               installs one, the way nothing registers a widget tag), ApplicationKind (the
                               manifest — freedesktop's .desktop entry, macOS's Info.plist: id, name,
                               icon, keywords, the files it opens, singleInstance, and a launch
                               factory), Application (one running instance: kind, mainWindow, open,
                               activate, dispose — where dispose is QUITTING and closing the window is
                               not), ApplicationRegistry (per Desktop: install/installed/launch/running/
                               handlerFor — a launcher, "open with" and taskbar grouping all answerable
                               with nothing running; runs the ApplicationKinds services once per
                               DESKTOP, since discovery is per process and installing is per shell),
                               LaunchContext (the Exec line's arguments)

com.crystalgraphics.platform   NOT CrystalGUI's code — CrystalGraphics' platform SPI, which CrystalGUI
                               consumes. Listed here because the engine's input, sound, clipboard and
                               cursor seams all live in it.
  (root)                       CgPlatform (the registry), CgPlatformService (the bundle a loader registers)
  .input                       CgSystemInput (raw Mouse/Keyboard event sink + event types),
                               CgKeyCodes (LWJGL2-shaped, no LWJGL import), CgMouseCodes,
                               CgModifiers (bitmask), CgCursor (the cursor keyword set),
                               CgCursorBitmaps (procedural 32x32 cursor art)
  .service                     CgInputService (codes, modifier/key/button state, AND the clipboard),
                               CgSoundService, CgCursorService — plus CrystalGraphics' own six

com.crystalgui.lifecycle       CgUiLifecycle — the ONE CgLifecycleListener CrystalGUI registers with
                               CrystalGraphics; drives paint-context teardown + cache invalidation

com.crystalgui.render          CgUiPaintContext (singleton), CgUiRenderer, ScissorStack,
                               CgUiBackdrop — the backdrop primitive under glass(): capture the region
                               behind an element, blur it (separable Gaussian at 1/4 res), hand back the
                               sharp and blurred textures with UVs. Sits BESIDE the paint context and
                               reaches it through package-private members, as TextEditor's view parts do
  .text                        FontFamilyCache — (font stack, px) -> CgFontFamily
  .texture                     CgUiDrawable (SPI), CgUiQuad, CgUiSprite (9-slice), CgUiRoundedRect (SDF),
                               CgUiCrossFade, CgUiLayerBox, CgUiRepeat, ArgbMath, CgUiSvg,
                               CgUiGlass (liquid glass — blur, luminosity blend, refraction, specular, noise, over a live
                               backdrop), CornerRadiusAware (the seam that stops a self-clipping drawable
                               being wrapped in a CgUiRoundedRect it cannot survive),
                               CgUiTransformDrawable (stub)
    .svg                       A full SVG renderer, parse to cached draw ops: SvgScanner (nested tags),
                               SvgPath (the d grammar), SvgTransform, SvgColor, SvgStyle (inheritance),
                               SvgGradient, SvgTriangulator (scanline fills, holes cut), SvgDocument
    .asset                     CgUiSpriteRegistry — lazy "ns:name" -> sprite from ui/sprites/*.json;
                               FileIconTheme — VS Code's file-icon-theme model, ui/icons/*.json
    .geometry                  Position, Size

com.crystalgui.style           ElementStyle, StyleGroup, GeneralGroup, LayoutGroup, StyleOrigin,
                               Styleable (what the cascade MATCHES) and StyleScope (what a sheet
                               can be SCOPED to -- a strictly larger set, because a scope root is
                               only walked to; a ShadowRoot is the difference),
                               TaffyBridge, PseudoClasses, StyleEngine, CssParsingUtil, CssAngle
  .sheet                       StyleSheet (+DEFAULT), StyleRule, DeclarationParser (var(--x)),
                               StyleSheetRegistry
  .selector                    Selector, CompoundSelector, SelectorType
  .transition                  TransitionEngine, TransitionSpec, ActiveTransition, TransitionValue
  .easing                      Easing, Linear, CubicBezier, LinearPiecewise, ConstantEasing,
                               ProgressFunctions
  .property                    StyleProperty<T>, StylePropertyRegistry, StyleSlot, StyleValue,
                               IValueInterpolator
    .general.{bools,enums,floats,ints,strings}   scalar StyleValue/StyleProperty flavors
    .layout                    LayoutProperties, BoxEdgeShorthands
    .layout.{dimension,grid,length}              Taffy-shaped value types (LPA*, LPSize, Grid*)
    .visual                    Overflow, OverflowClip, ScrollBehavior, BoxOrigin, DrawableAlign,
                               DrawableFit, OutlineShorthand, OutlineOffsetShorthand (per-edge,
                               unlike CSS)
    .visual.border             BorderRadiusProperties, BorderRadiusShorthand, LengthPercent(+Property/Value)
    .visual.color              ColorProperty, ColorValue
    .visual.text               FontFamilyValue
    .visual.texture            TextureProperty, TextureValue
    .visual.transform          Transform (the ORDERED LIST of ops -- not translate/scale/rotate
                               fields, because CSS composes left-to-right as matrix multiplication;
                               was `ui.Transform`, and the prefix went with the move: `style` is
                               written against `Styleable` so a cascade bug is fixed once, and a UI
                               prefix in it asserts an engine dependency the file does not have),
                               TransformProperty, TransformValue, TransformOriginShorthand

com.crystalgui.ui.contract     WHAT A KIND OF WIDGET IS — one declaration, four readers.
                               WidgetContract (name + ordered State slots + Event slots),
                               State<W,V> (a wire key, a getter/setter pair, an omitted-when value and
                               an optional sanitizer — DECLARATION ORDER IS APPLY ORDER, which several
                               widgets depend on), Event<W,P> (a kind, a payload codec, and HOW A
                               CLIENT LISTENS — which is what deleted the instanceof switch),
                               StateType/StateTypes, RatePolicy (immediate/typing/dragging; a widget
                               knows its own tempo and a handler cannot), WidgetContracts (the
                               registry, and the local-only list with reasons). NOTE there is no kind
                               vocabulary class: a kind is a string an Event declares, unique only
                               within its own widget's contract, so a third party mints one without
                               editing anything of ours. plan/engine-rewrite.md M1

com.crystalgui.ui              A NAMESPACE, with nothing at its root. It held four files that had
                               survived the old engine and shared no theme, and each named something
                               it did not belong to: EventListenerGroup imported ui.event and nothing
                               else; Transform is a CSS value, so it went to its own family and
                               dropped the prefix (see `Transform` below); ClipboardActions imports
                               NOTHING and is a data-context SPI; UiDataKeys names only `core`.
                               A root with no shared subject is where things land when nobody decides.
  .dom                         THE NODE TREE, split as the DOM splits it. UINode (Node: the two
                               trees, lifecycle, the observer wiring, the walks out) and UIElement
                               extends UINode (Element: attributes, classes, shadow tree, style,
                               geometry, state, events). UIDocument and UISlot are elements;
                               ShadowRoot is the only bare node. Plus Name, Attribute,
                               UIElementRegistry, UIElementTreeSource, and the SEAM both the mirror
                               and any future engine are written against: TreeSource<N>,
                               TreeObserver<N>, NodeContract.
  .box                         THE BOX TREE. Box (geometry, hosts, mirrors, the ONE localToWorld,
                               hitTest before any paint), BoxTree (one TaffyTree per document,
                               one-pass layout), BoxStyle (ComputedStyle -> Taffy, and the only place
                               the project's defaults are stated), Measurable (the engine ASKS instead
                               of being told), BoxPainter (every box drawn in its own space).
  .service                     THE FOUR SERVICES. Input (platform sink, hit test, three-phase DOM
                               dispatch over the composed tree, capture, the cursor's `auto` rule, a
                               Chords seam a host fills) with InputMode + the mode stack and Drag on
                               it; Focus (one owner, one traversal, ONE inertness predicate);
                               Animation (timelines on the host's DELTA, per-frame hooks owned by a
                               node, afterLayout); Lifecycle (freeze/thaw/destroy); Dismiss (the
                               popover stack, light dismiss, close watchers); AnchoredPlacement.
  .data                        UiDataKeys -- the UI layer's DataKey vocabulary. Names only `core`,
                               which is why the SPI it points at (ClipboardActions) went to
                               `core.data` beside DataProvider while the KEYS stayed here. Projections (declare a read/write pair once; the engine
                               compares and writes only on change, runs the set before the flush, and
                               skips it entirely while no viewer is watching) + `each` for KEYED lists,
                               where an untouched row keeps its element so an insert is an insert;
                               AutoProjection (panel field name -> model accessor) and its Report,
                               which names what it could NOT wire because a convention that skips
                               silently leaves a widget at a value that usually looks right.
                               MOVED to `net.projection`: it names only `ui.contract` and `ui.dom`,
                               and `net.window` is its ONLY consumer -- packaged with what uses it
                               rather than with what it reads.
  .contract                    WHAT A KIND OF WIDGET IS -- one declaration, four readers. See its own
                               entry above.
  .event                       UIEvent, PropagationPhase, CloseEvent, DOMEvent, DragEvent, FocusEvent,
                               KeyboardEvent, MouseEvent -- SHARED, and dispatched by `service.Input`.
  .input                       FocusPolicy, ButtonState, and the keymap (`.keymap`). What is left of
                               the old input package once the handler and the drag controller went.
  .text                        TextRange, HighlightRegistry -- CSS Custom Highlight API (ranges in
                               Java, styling in CSS via ::highlight(name)).

com.crystalgui.widget          THE WIDGETS, layered so a build fails when a layer reaches upward
                               (LayeringTest): .control/.display/.text/.scroll < .overlay/.layout/.dnd
                               < .collection < .composite < .config < .canvas < .graph < .texteditor.
                               `.display` is ProgressBar and SymbolIcon -- Qt's "Display Widgets", and
                               a bottom tier not by assertion but because between them they import
                               `ui` and `style` and NOT ONE widget. `.composite` was `.form`, a name
                               that recorded a TIER rather than a subject: ColorSelector and
                               SearchField are controls in every sense and sit above `.overlay` only
                               because they are assembled from it. And `.scroll` is a SIBLING of
                               `.layout`, never a child -- nesting it there cannot be expressed at
                               all, since a prefix rule makes the package fail against its own
                               parent.
com.crystalgui.desktop         CRYSTALOS. Desktop (found with Desktop.of(document), never built by a
                               caller: the engine may not name a compositor, so the compositor names
                               the document), .window, .motion, .taskbar, .switcher, .host.
com.crystalgui.workbench       The shell, and THE ROOT IS THE HUB ONLY. Everything whose imports
                               point at ONE sub-package now lives in it; what is left at the top is
                               Workbench plus what genuinely coordinates several of them, which is the
                               honest reading of a hub's own package.
                               At the root: Workbench (the engine, implementing WorkbenchContext — the
                               surface an extension is written against, which stays here because it is
                               the engine's own contract), WorkbenchSession (the arrangement record —
                               the engine owns the bytes), WorkbenchSettings (the settings the whole
                               engine resolves), WorkbenchKinds, WorkbenchMenus, and the three that
                               COORDINATE rather than serve — DocumentTabs (dock+editor+explorer+
                               decoration), SaveActions (dock+diff+status), NetworkedPanels.
  .app                         WorkbenchApplication — the runtime EVERY workbench-shaped product
                               shares: the workbench, its window, preferences, session and initial
                               focus, built from a manifest's list of extension ids — and
                               WorkbenchApplicationCommands (Save File, Save/Restore Layout: the
                               ENGINE's, resolved from the data context so two applications on one
                               desktop each save their own)
  .extension                   THE SEAM AND EVERY PANEL THAT SHIPS ON IT: WorkbenchExtension,
                               WorkbenchExtensions (ServiceLoader — a jar on the classpath offers its
                               features), SessionSlice (an extension's corner of the session record),
                               and the engine's own five — ProjectExtension, ProblemsExtension,
                               NotificationsExtension, PresenceExtension, InspectorExtension.
                               `new Workbench(workspace, List.of())` has NO tool windows, which is
                               asserted: the built-ins are the only real test of the seam, and a
                               first-party path more capable than the public one is how an extension
                               API rots
  .chrome .dock .explorer .region .stripe .toolwindow .view .decoration .diff .search
                               ...and each owns the binding that serves it: ProblemsBinding and
                               PresenceBinding are `.chrome.status` (both produce a STATUS ENTRY, not
                               a panel, whatever their names suggest), ExplorerBinding and
                               ProjectSourcesIndex are `.explorer`, WorkbenchOpener is `.dock`
  .editor                      EditorService — ONE lane for opening anything — TextEditorView (a
                               TextEditor as a DocumentEditor) and TextFileKind
com.crystalgui.app             The MANIFESTS, and what each product declares about itself:
                               .crystaleditor (CrystalEditor — an ApplicationKind and three choices,
                               and no longer an element at all), .shadergraph, .machine.

com.crystalgui.text            Rope, TextBuffer, TextSummary, Change/ChangeSet, Selection,
                               SelectionModel, TextPoint, TextRange, WordClassifier, WordOperations,
                               LineEnding — the document model, all headless. Plus the two utilities
                               BOTH engines need and neither may own: SimilarNames (how close is close
                               enough for a "did you mean") and DerivedNames (a name for something the
                               author has not named). Each was in an engine until the second engine
                               wanted it — a child-side class may not be imported across, so a shared
                               utility is MOVED here rather than referenced in place
  .cursor                      CursorColumns, MoveOperations, TypeOperations, LineOperations,
                               MouseSelection, ColumnSelection — VS Code's boundaries, but NOT
                               file-for-file: MouseSelection and LineOperations come from
                               browser/controller/ and contrib/linesOperations/. See "Port the module
                               boundaries too" for the full mapping and the ONE unimplemented gap left
                               (atomic tab moves for the ARROW keys; Backspace already steps by column)
  .syntax                      Language, LanguageRegistry, SyntaxToken, SyntaxTokenizer (SPI),
                               KeywordTokenizer — the ENGINELESS tier, and what a dedicated server has —
                               plus LanguageKinds, the ServiceLoader seam a jar declares its languages
                               through. `LanguageRegistry.bootstrap()` runs it on the FIRST READ, so the
                               grammars and engines are in front of the built-in lexers before anything
                               is classified, and no host calls anything; a host that calls it anyway is
                               WARMING it (443ms, measured — see the invariant row)
  .lang                        The semantic layer's contracts, INTERFACES ONLY: LanguageServices (the
                               per-DOCUMENT facade), SemanticTokenProvider, Resolver, CompletionProvider
                               + CompletionItem/CompletionList, SymbolInfo/SymbolKind/SymbolModifier,
                               TypeRef, DeclarationSite, Versioned, and TypeSearch + TypeSearchRegistry
                               ("which types are on the classpath" — what Go to File asks, inverted for
                               the same reason as the rest: the INDEX lives in language/ and core/ may
                               never name it). Every engine lives in language/; this package is the whole
                               footprint in core/, and its absence at runtime is the only feature flag.
                               docs/CGUI_WORKBENCH_SERVICES.md
  .diagnostic                  Diagnostic, DiagnosticSet, DiagnosticSeverity, DiagnosticTag, Markers,
                               RelatedInformation — LSP-shaped, per-owner. NOT duplicated in .lang
  .wrap                        LineProjection, ProjectedLines, LineBreaksComputer (SPI),
                               MonospaceLineBreaks, ShapedLineBreaks, BreakOpportunities, WrapIndent —
                               soft wrap, and the model/view coordinate seam the whole editor rests on
  .view                        IndentLevels, WhitespaceMarkers, RenderWhitespace
  .fold                        FoldingRegions (+Region), FoldingModel, FoldingRangeProvider (SPI),
                               IndentRangeProvider — folding. INDENT-based by default, which is Monaco's
                               default too and deliberately not brackets; see the class javadoc for why

com.crystalgui.document        WHAT AN OPEN DOCUMENT IS, headless and below `widget`. Document (the
                               identity — a rename MOVES it, `onDidChangeResource` is the one event a
                               store subscribes to), DocumentModel (the SPI: encode/adopt/version/
                               history/onChanged, and `version() != savedVersion` IS dirtiness),
                               AbstractDocumentModel (`apply(Edit)` as the one door), TextDocumentModel
                               (a TextBuffer plus the language, the tokenizer and the services — which
                               are the MODEL's, so two split panes share one parse tree),
                               BytesDocumentModel, DocumentKind + DocumentKinds (one declaration: model,
                               editor, icon, status; at most one `.fallback()`), DocumentEditor (the ONE
                               type here that names an element, and it names UIElement), Documents +
                               DocumentReference (open by Resource, disposed by the LAST holder — never
                               by a tab closing, which is the "Parser is closed" defect inverted),
                               DocumentState, EditorInput, RecentFiles

com.crystalgui.fs              FOUR classes, and each is vocabulary every tier below names: Resource
                               (a tab's input, whether or not it is a file — the project scheme keeps
                               CgPath's exact text, so every saved document and session keeps parsing),
                               CgPath, CgFileError and CgFileSystemException. They import nothing from
                               `fs` at all, which is what makes the root a root rather than a drawer —
                               and is why `LayeringTest` needs no entry for it. Everything else moved
                               into a tier below; twenty top-level files became four.
  .project                     ProjectRegistry, ProjectInfo, WorkspaceProject, SourceRoots, Excludes,
                               ProjectProvider. The bottom tier.
  .provider                    A FILESYSTEM AND NOTHING ABOUT A WORKSPACE: CgFileSystem (the SPI),
                               LocalFileSystem, InMemoryFileSystem (a complete one, on a monotonic
                               clock, which is what makes an etag reproducible), CgFileEntry (+Type),
                               CgFileCapability, CgFileEvent (+Source) + NioFileEventSource. It names
                               `.project` and NOT the reverse — a project is a named root and a
                               filesystem is what resolves one to a directory
  .protocol                    THE WIRE, shared by both halves and naming neither: FsMethods (the method
                               names), FsMessages (every payload as a record with a codec, so a field
                               written on one side is provably the field read on the other), FsError (a
                               code and fields — a conflict carries the etag the file actually holds),
                               FsHello (the greeting: case rule, reserved names, size tiers)
  .server                      WorkspaceService (the server's own filesystem: authorise, etag, cap,
                               trash), WorkspacePermission + WorkspaceActor + WorkspaceOperation,
                               WorkspaceTrash, WorkspacePresence, WorkspaceConflictException,
                               ServerWorkspace (the service with the actor already decided — what a
                               panel is handed), WorkspaceBinding (one CONNECTION's end — decode, ask the service, encode;
                               owns this actor's audit, its idempotency table and its entry in the hub),
                               WatchHub (ONE subscription table for the whole server: a path is stat-ed
                               once per tick however many peers watch it, a save's several events
                               coalesce into one change, and a delete plus a create carrying one etag
                               pair into a RENAME), WorkspaceAudit, RecentOperations
  .client                      Workspace (the entry point; facades by noun — files(), presence(),
                               capabilities(), health() — and the ONE door a provider is reached
                               through, which is where UiBudget times it), FileOperations (every answer
                               a Reply, serialised per resource, undoable), FsCall (coalescing, cancel,
                               one failure-parse site), WorkspaceDocuments (where the headless document
                               model meets the wire: open, save, and what a change on the server means
                               depends on whether the document is dirty), ContentProvider +
                               ContentProviders (where a NON-project scheme's content comes from — a
                               decompiler, a generator; contributed statically, drained per workspace),
                               Backup (hot exit), LocalHistory (per-save, and the merge base), Health

com.crystalgui.serialization   Codec<A>, DynamicOps<T>, Codecs, CodecException, JsonOps, PlainOps,
                               StateMap, UIElementMirror, ContentHash
  .style                       StyleValueCodecs, InlineStyleCodec

com.crystalgui.net             UITransport, InMemoryTransport,
                               ServerUiSession, ClientUiSession, ClientUiSessions, UiWindowMux,
                               SheetRef  -- ids live in ui.dom.UIElementTreeSource, not here
  .mirror                      THE MIRROR, and it names no widget, no session and no transport:
                               ServerTreeMirror<N,T> (observes a TreeSource, records insert/remove/
                               move with coalescing, allocates ids, PRODUCES payloads rather than
                               sending them -- which is what lets one window fan out to viewers with
                               different visibility without this class knowing viewers exist),
                               ClientTreeMirror<N,T> (applies them), NodeMirror<N,T> (the per-tree
                               seam: how a node is described and reconstructed -- BOTH halves on one
                               interface, because an encode with no matching apply is exactly the
                               defect that made identity changes silently never travel, and on one
                               interface that omission is a compile error), UIElementMirror over
                               today's UINode tree, TreeOps (the wire vocabulary). A second engine
                               supplies a TreeSource and a NodeMirror and nothing else
  .window                      A WINDOW'S LIFETIME — the layer above the sessions, and the one a mod
                               uses. Networked<M> (ONE class per UI: a UINode whose widgets are
                               FIELDS — the field name becomes the id — with layout/serve/tick/
                               stillValid/title/key on the server, bound/client on the client, closed
                               on both; the panel IS the tree's root on both sides, so the mounted
                               root is the panel and `machinepanel { }` styles it by tag), UiType (the
                               identity both sides reference AND the engine's customElements.define —
                               registers the panel's tag so a description decodes into the class;
                               build() on the server, bind() on the client, nested field types
                               registered recursively), ServerScope / ClientScope (what serve() and
                               client() are handed — a VIEW of the window's one session, prefixed by
                               the panel's element-id path, so a nested panel's "save" is
                               "engines/save" on both sides with nobody writing the string;
                               composition is ServerScope.attach(child, slice) — props down, the id
                               is the namespace, and the child panel is built by the PARENT's layout
                               with the slice only it knows), ServerWindow<P> (the final HANDLE
                               open() returns — session, key dedup, close matrix; NOT an authoring
                               surface any more), ServerWindows.open(TYPE, model) — the WHOLE
                               wiring: the open names the panel class on the wire
                               (UiMethods.UI_CLASS) and the client initialises it, GUARDED (loaded
                               without running anything, must be Networked, only then initialised),
                               so there is no client registration at all — WindowMount +
                               ClientWindowContext (the platform seam), SheetSupply (local resolver →
                               cache → ui/sheet), Presentation (WINDOW | EDITOR_TAB |
                               TOOL_WINDOW(region) — a HINT on the open, carried on ui/openWindow and
                               declared beside the resolver so a client cannot name one), RowSource +
                               RemoteRows (a collection the server holds and a viewer sees a WINDOW
                               of). ClientScope.workspace() answers fs.client's Workspace and
                               ServerScope.workspace() answers a ServerWorkspace, both from the
                               connection — so a panel that shows files reads them through the fs
                               protocol and never re-ships a listing through the mirror.
                               plan/net-window-host.md

                               NOTE the three senses of "window", which is why these live in their own
                               package: UIDocument is the ENGINE for one surface (and is the one genuine
                               misnomer — it plays the DOM's Document role and would be UIDocument if
                               it were named today), WindowFrame is the CHROME on a desktop, and
                               ServerWindow is the NETWORKED UNIT. The protocol has said "window" in
                               that third sense since windowId existed.
```

**Naming corrections vs. older notes:** `render/` is top-level (`com.crystalgui.render`), *not* nested
under `core/`. There is **no `core/input/` or `core/sound/` package any more** — the raw platform I/O
layer moved wholesale to `com.crystalgraphics.platform`, and `com.crystalgui.core` is now the logger plus
three small utility packages. Dispatch and focus were always in `ui/input/` and stayed there. The
three-phase event types are in `ui/event/` — there is no `core/event/` package.

---

# Shipped assets

`core/src/main/resources/assets/crystalgui/`

| Path | Notes |
|---|---|
| `ui/styles/ua/*.css` | **User-agent sheet, in ten domain parts** (core, widgets, editor, overlays, config-kit, inspector, workbench, panels, search, desktop) concatenated in `StyleSheetRegistry.DEFAULT_SHEET_PARTS` order into `StyleSheet.DEFAULT` — one sheet, one parse, one variable scope, and cross-part order is as load-bearing as order within a file. Functional geometry for every widget with no theme loaded; every colour is `var(--token, #fallback)`. Was a single 6,200-line `default.css` until plan/style-overhaul.md step 8. |
| `ui/themes/base.css`, `ui/themes/crystal-dark.css` | The token tables: component→system derivations, and the default theme (pins today's look exactly). See `docs/CGUI_THEMING.md`. |
| `ui/schemes/dark-plus.css` | The default editor colour scheme — the second, independently-selectable axis. |
| `ui/styles/ore.css` | Minecraft Ore UI theme, ported from LDLib2's `ore.lss`. |
| `ui/styles/graph.css` | Node-graph theme — Unity Shader Graph's look, including the per-type port palette every wire reads its colour from. |
| `ui/styles/filetypes.css` | Per-file-type colour palette, keyed on the `.filetype-*` class `FileIconTheme.classFor` returns. **Not** in `default.css` — that is the UA sheet and carries geometry only. |
| `ui/styles/decorations.css` | Decoration palette, keyed on the `decoration-*` class a `FileDecoration` names. Same split, same reason. |
| `ui/icons/*.svg` | Feather icons (MIT), stroked `currentColor` chrome marks. `icon("crystalgui:folder")` in CSS. |
| `ui/icons/filetypes/*.svg` | 50 IntelliJ Platform icons (Apache 2.0), filled, 16px, carrying their own palette. What the file tree draws. |
| `ui/icons/default.json` | The default file-icon theme: extension/name → icon. Colour is deliberately not in it. |
| `ui/icons/ATTRIBUTION.md` | **An obligation, not documentation** — MIT and Apache 2.0 both require notices to travel with the distribution. Indexed from the repo-root `THIRD-PARTY.md`. |
| `sources/**` | **Not in `src/main/resources` — injected by `tasks.jar`** (M13 §25.4), so it exists only in the built jar. 601 `.java` files, 1.84 MB, read by `SourceArchives.ResourceArchive` so the documentation popup quotes an author's real declaration and javadoc instead of reassembling one from the binding. **The prefix is a convention any mod can use and nothing registers**: `BundledSources` SCANS the classpath for `assets/<namespace>/sources/`, so a mod makes its own API quotable by shipping its sources and nothing else. One namespace per project rather than one shared directory, because CrystalGraphics — which ships its own the same way — is used by mods with no CrystalGUI in the pack. |
| `ui/sprites/ore.json` | Sprite definitions backing `ore.css`. |
| `textures/gui/ore_styles.png` | Ore theme atlas. |
| `textures/gui/gdp_styles.png` | **Unreferenced by any code today.** |
| `textures/gui/Spritesheet_UI_Flat.png` | Unreferenced by any stylesheet today. |
| `ui/fonts/Minecraft.otf`, `MinecraftRegular.otf` | Public-domain MC fonts. |
| `shaders/gui_quad.shader` | Default material bound by `beginFrame`. |
| `shaders/gui_rounded_rect.shader` | SDF rounded rects. |
| `shaders/gui_layer_blit.shader` | Visual-layer FBO composite. |
| `shaders/gui_curve.shader` | Bézier strokes, via `ctx.curve()`. Declares `#pragma cg_use curve`, not `quad`. |
| `shaders/gui_gradient.shader` | A whole `linear-gradient()` in one draw: eight premultiplied stops as properties, the unrolled ramp per fragment along `_Axis` (CSS's gradient line), a `_Window` of *t* so a longer gradient's extra draws never write a fragment twice, `WITH_MASK` for the rounded-box SDF, and half a level of `hash12` dither as the LAST thing before the target quantises. `Blend ONE ONE_MINUS_SRC_ALPHA` — premultiplied out, like the layer blit and unlike `gui_quad`. |
| `shaders/gui_downsample.shader` | The box prefilter behind `glass()`: reduces the captured sub-rect 2x or 4x before it is blurred (four bilinear taps cover the block behind each output texel). Without it the Gaussian read a full-resolution source at a stride and was a comb — text came through as vertical streaks. |
| `shaders/gui_blur.shader` | One axis of the separable Gaussian behind `glass()`, **kernel derived from sigma**: taps one source texel apart, `ceil(3σ)` of them per side, weights by the incremental recurrence and renormalised. `CgUiBackdrop` picks the working scale (1/2/4) from σ — Skia's scale-then-blur — so the loop stays short. **Helpers go ABOVE `void vertex`** or they never reach the fragment stage. |
| `shaders/gui_glass.shader` | Liquid glass: refract → pick blurred/sharp → saturate → **luminosity** (W3C SetLum toward the tint's brightness — the layer WinUI's acrylic and Mica are mostly made of) → tint → specular → noise → SDF mask. Every optional layer is a `#pragma cg_feature`. |

> **`gui_curve.shader` holds no stroke maths** — it `#include`s `crystalgraphics:shaders/lib/stroke.glsl`,
> which is shared verbatim with the engine's own `curve.shader`. The two materials differ in exactly
> three things: `DepthTest ALWAYS` (UI paints in painter's order over whatever the world left in the
> depth buffer — `LEQUAL` is right for a 3D stroke and wrong here), the `_LayerOpacity` property, and the
> one line that multiplies it in. **A Pass's `RenderState` is fixed at author time and cannot vary per
> keyword variant**, which is why this cannot collapse into one material with a `#pragma cg_feature` —
> the same constraint `text.shader` documents about its own depth state.
>
> It was briefly a full copy of the fragment body, which is worth recording because the cap logic in
> there was wrong three separate times: two copies means the fourth fix lands in one file and the other
> keeps the bug, silently, while still rendering something plausible.

> **All three declare `#pragma cg_use quad`, and any new CrystalGUI shader must too.** Everything
> here draws through `CgQuadRenderer`, whose per-instance buffer supplies `CG_QUAD_WORLD_POS` /
> `CG_QUAD_UV` / `CG_QUAD_COLOR` — the pragma is what wires it, during parsing, before anything can
> compile. Omitting it is a parse error naming the missing line (it used to be a GLSL error about an
> undefined `QUAD_DATA`, reported four layers up as an unrelated `#pragma cg_feature` complaint).
> Never attach the buffer from Java. See `CrystalGraphics/AGENTS.md` § *Engine Buffers*.

> **A `#include` in a `.shader` is compiled into the vertex stage as well as the fragment stage.**
> The material compiler hoists every material-scope `#`-line into both. `gui_rounded_rect.shader` is
> the only shipped shader with an include, and its `sdf.glsl` needed `#ifndef CG_VERTEX_STAGE` around
> `sdf_coverage` — `fwidth` is fragment-only, NVIDIA accepted it anyway, and AMD's refusal made the
> whole gallery unlaunchable on that hardware. Guard fragment-only code inside the lib, with
> `#ifndef CG_VERTEX_STAGE` and never `#ifdef CG_FRAGMENT_STAGE` (raw `.vert`/`.frag` get neither
> define). `ShippedShaderStagePurityTest` enforces it GL-free; `--mode=shader-compile-audit` checks it
> against a real driver. See `CrystalGraphics/AGENTS.md` § *Stage defines*.

---

# Documentation index

> **Plans live in `plan/`, a separate private repository that is not part of this checkout.** Absent is
> normal and nothing here depends on it — the build, the tests and every doc below work without it. When
> it is present, a citation like `plan/engine-port.md` §2.6 resolves inside it at whatever depth the plan
> sits, and `python plan/tools/verify.py --repo .` checks that every one of them still does.
>
> **Status is the plan's own**, in its front matter, which is why the table below no longer carries a
> column for it. Six vocabularies were in use when that column existed and it was the copy that went
> stale.


| Doc | Status | Contents |
|---|---|---|
| **`docs/CGUI_BUILDING_UIS.md`** | **current** | **THE USER-FACING GUIDE, and the only doc in this table written for somebody USING CrystalGUI rather than building it.** §4 now carries *Keeping the screen up to date — projections*, written ELI5 from the failure rather than from the API: the per-tick `mirror()` shape it used to teach, why forgetting a field in it looks correct, and the three ways to state a projection instead. How to make a client-only UI and how to make a networked one, which to choose, and the whole `Networked` authoring surface by example — hooks and what runs where, typed events (`io.on(slider, Slider.VALUE_CHANGED, …)`), wire methods and the prefix nobody types, nesting with a model slice, opening and closing, session persistence, and writing a widget with its own contract. Ends with a symptom→cause table for the failures that are silent (no `init` so nothing is styled; a panel at zero height because `flex-shrink` is 0 here; listeners in `client(io)` instead of `bound()`). **Keep the examples compiling in your head against the real API** — three of them were wrong on the first pass (`width(100f, true)`, `session().newMap()`, a `wire` referenced outside its lambda) and were caught only by reading the signatures back |
| **`docs/CGUI_WORKBENCH_EXTENSIONS.md`** | **current** | **THE SECOND USER-FACING GUIDE**, and the counterpart to `CGUI_BUILDING_UIS.md`: that one is how to make a UI, this is how to get one into somebody else's workbench. `WorkbenchExtension` and the two things a `ServiceLoader` entry and a manifest id mean (available is not enabled); `ToolWindowKind` for an activity-bar panel; `DocumentKind` for a file type; commands, menus and accelerators; a status entry; explorer decorations; diagnostics; `SessionSlice`; which signal actually means "the tab in front changed"; settings; and `ApplicationKind` for a whole product. Ends with the five things that are silent when wrong — a lazily built panel view the dock caches for the session, a process-wide registration with no handle, following `onDidOpenDocument` alone, `activate` asking for a window that does not exist yet, and an id nothing ships. **Every example was checked against the real signatures**, which caught five: `Kind.NORMAL`, `DiagnosticSet.replace`, a five-argument `Diagnostic.onRow`, `Language.JSON` and `TextPoint.of` — none of which exists |
| **`docs/CGUI_INVARIANTS.md`** | **current** | **298 rows: the things invisible from any single class and expensive to rediscover**, grouped by subsystem — threading, coordinates, the cascade, layout, dispatch, GL, widgets, the workbench, windows, documents and the wire, the editor, the language stack, the build, testing. It was a 560-line section of this file, a quarter of what is read at the start of every session, and 244 rows were cut when it moved: the discriminator was tense rather than subject, so a row stating a constraint stayed and a row narrating one fixed bug in one class went. **Read the section for what you are touching; do not read it front to back** — and add a row only for something TRUE NOW that a stack section above does not already say |
| `docs/CGUI_STYLE_RENDER_PIPELINE.md` | **current** | Cascade, selectors, stylesheets, transitions, frame lifecycle, drawables & compositing channels, `background:` grammar, border-radius layer, visual layers (opacity + masking), `transform`/`transform-origin`, known gaps vs. the web, file map |
| `docs/CGUI_WIDGETS.md` | **current** | All thirteen widgets: API, internal-child class hooks, pseudo-classes, covering harness scene |
| `docs/CGUI_SERVER_AND_SERIALIZATION.md` | **current** | Codecs, `StateMap`, descriptions, content hashing, network ids, `SheetRef`, packets/sessions/RPC, known gaps, the headless contract |
| **`docs/CGUI_NEW_ENGINE.md`** | **current** | **What replaced what, kept as the record.** The old engine is deleted; this is where its shapes are written down, for reading a commit or a comment that still names one: the lookup table (`Input` → the four services, `UIFrameTicker` → an owned hook, `UIDocument.promote` → `promote`, `UIElementRegistry` → a `NAME` on the class, `UIElementMirror` → `UIElementMirror`, shadow parts → shadow trees), then what each replacement actually offers. Ends with **the habits that are now wrong** — `box()` is nullable, `toLocal`'s origin moved, `Box.x()` is parent-relative, there is no tag fallback, a shadow host never sees its own parts, a hook runs before layout. Those six are where the review time goes |
| `docs/CGUI_WORKBENCH_SERVICES.md` | **current** | The service layer under the dock/workbench/editor — what a widget may *ask* rather than reach through the application for. `Disposer`, `DataContext`, service events, `Resource` and its providers, the document layer (`DocumentKind`, `Documents`, `DocumentReference`), `Workspace` and `EditorService`. **Every new service API is added here in the same commit**, which is a rule the filesystem cutover broke and then repaired: a section can go from stale to actively misleading in one commit, and this one is loaded every session |
| `docs/CGUI_THEMING.md` | **current** | Themes, editor colour schemes, the token vocabulary and the anti-rot rules. Its token table is **generated and machine-checked** (`StyleGovernanceTest.theDocumentedTokenTableIsCurrent`) — regenerate from the failing test's output, never hand-edit |
| `docs/CGUI_MODERN_UI_RENDERING_RESEARCH.md` | **current** | The primary sources behind the taskbar, glass, blur and gradient work, with their exact numbers: WinUI's acrylic effect graph (blur 30, saturation 1.25, luminosity blend, noise 0.02) and brush values, Fluent's fill/text tokens, Windows 11 taskbar geometry, IntelliJ's project-colour header, Apple's Liquid Glass usage rules, Skia's scale-then-blur Gaussian, the CSS backdrop **mirror** edge mode, and gradient dithering. **Read the relevant section before touching `gui_glass`, `gui_blur`, `gui_gradient`, `CgUiBackdrop` or the taskbar sheet** — every one of those was first built from memory and was wrong in a way only the source showed |
| `docs/CGUI_NETWORKING_PRIMER.md` | **current** | **The lecture** — networking from the bottom up, ELI5 first. What a frame, a message, an envelope, a connection, a session and a peer each are and how they differ; the layer cake from `CgNetworkChannel` to the sessions; **how a `ProtocolConnection` is established** (registration order, the FML events, `open()`, odd/even stream ids, routing, ticking, closing); **how to define a packet contract on both halves**, with `fs.read` shown server-side and client-side together; and the mc1710 wiring — `Mc1710NetworkChannel`, `CgUiConnections`, `CgUiWorkspaceHost`. Read it before `CGUI_SERVER_AND_SERIALIZATION.md`, which is the same ground as a reference |
| `plan/style-overhaul.md` | — | The styling overhaul plan: audit, reference research, token architecture, governance, the step-by-step migration and its recorded revisions |
| `plan/shell-architecture-audit.md` | — | The architecture review this layer was rebuilt from: audit, VS Code/IntelliJ research, the six-step port, and what each step deliberately does not do |
| `plan/shell-windowing.md` | — | CrystalOS — the window compositor: multiple visible, resizable, stacking windows as element subtrees under one `UIDocument` (the desktop), a taskbar, a switcher, and the hide/close/destroy lifecycle with window-scoped modality. Researched against Win32, X11, Cocoa, Swing MDI, the Page Lifecycle API and bfcache — a window is an element, close is a *request* everywhere, and a hidden thing must stop working |
| `plan/fs-remote-workspace.md` | — | Networking, the workspace and UI over the wire, after Phase 4 shipped and was verified on a real dedicated server. The whole 15-method server surface, what decides a command's side (nothing — the client has no filesystem to misuse), and and the ten items between "served over a socket" and "usable without losing work" — led by a window lifecycle (hide is not close, close is not destroy) and the strip that makes minimise safe |
| `plan/style-glass.md` | — | Liquid glass — a backdrop material: capture what is behind an element, blur it (separable Gaussian, after a dual-Kawase pyramid was tried and withdrawn — see the plan's revision log), refract it through a surface-height profile (Snell), tint and light it. Researched against Apple's own effect and the reimplementations that picked it apart; the finding that shaped it is that the displacement comes from a HEIGHT PROFILE across the bezel and not from the SDF distance. Written for the taskbar island, opt-in per element because the cost is a mid-frame target switch per frame |
| `plan/shell-pinned-windows.md` | — | Pinned windows everywhere — the flicker when the desktop closes, and interactive pinned windows over OTHER Minecraft GUIs (click into a pinned window while chat is open). Both have one root: the paint path is chosen by WHICH SCREEN IS OPEN rather than by what state the desktop is in, so every handoff is a frame nobody paints. The reframing is that display-only was never about "not our screen" — it was about the CURSOR being grabbed, and any GuiScreen ungrabs it. Carries the per-version mechanism table (1.7.10 / 1.12.2 / 1.20.1) behind one `CgScreenOverlay` SPI, and the finding that 1.7.10 has NO GuiScreenEvent input events — verified in-tree — so the mixin is that version's exception rather than the pattern |
| `plan/fs-remote-file.md` | — | The remote file made honest: the wire's speed, external change, and disagreement. A real OS filesystem watcher (and why the etag poll survives as its reconciliation — OVERFLOW loses events by design and macOS's `WatchService` is a poll wearing an interface), the client end of a change nobody wired up, pipelining the serial chunked read, a histogram differ as the substrate under both a diff viewer and delta reads, and a probe that runs with the editor OPEN — argued for by a bug every existing probe missed because they all close the GUI |
| `plan/net-window-host.md` | — | The UI host — a lifecycle engine for networked windows, from an audit of the Machine example's setup. **Seventeen findings**, of which the sharpest are: per-mod tick polls opening sessions; **no client→server close message at all**, so the shipped example resurrected its own closed window on the next tick; the peer map keyed on a mortal `EntityPlayerMP`, so one respawn silently drops every inbound frame *while outbound keeps working*; no window type or title on the wire, so one mod's behaviour adopted another mod's tree; notifications unscopeable per window, so a second window of one application threw at open; `session.on` silently replacing a duplicate handler; and a **state delta that races the description being dropped permanently**. MC's own `Container`/`openMenu` pipeline and LDLib2's holders are the port sources. Shipped as `com.crystalgui.net.window` — `ServerWindow`/`ServerFragment`/`ServerWindows`/`ClientWindows` + a `WindowMount` SPI — with the full close matrix, and the example collapsed to a window class plus one registration line. **Part VI designed and Part VII shipped the second rewrite (2026-08-28)**: `Networked<M>` — the panel IS the element, one interface per UI, model handed to the server hooks as a parameter so the side boundary is visible in the signatures — with `UiType` (identity + tag registration), `ServerScope`/`ClientScope` (the id-path-prefixed views that make nesting compose), and SIX classes deleted: `Panel`, `PanelType`, `WindowType`, `ClientWindowBehaviour`, `ServerFragment`, `WindowScope`. The rule that settles every "should this be generic" question — does the framework hand it to you, or do you already hold it — decided all of it. **Closed at M8**: VI.7's four open forks were all settled by Part VII (field binding IS the declare-once base class it was leaning away from), and what M7 added on top — `Presentation`, `addLocal`, `stream` — is written up in `plan/engine-rewrite.md` |
| **`plan/fs-rewrite.md`** | — | **THE FILESYSTEM, RESOURCE AND DOCUMENT MODEL**, interleaved with the UI rewrite's M7 into one flow. `Reply`/`Stream` as the one async shape; a headless document layer where `version() != savedVersion` IS dirtiness and a document is disposed by its LAST holder; the `fs/*` protocol as typed records with paged answers; `WorkspaceBinding` and one `WatchHub` per SERVER; `Workspace` and its facades on the client; `EditorService` as the ONE lane for opening anything. Fifteen classes deleted. Its §9 is the per-milestone deletion ledger |
| **`plan/engine-rewrite.md`** | — | **THE MASTER PLAN for the networked-UI + engine-core rewrite**, knitting the two audits below into one ordered set of milestones M0–M8 with a deletion ledger checked per milestone. Its §0 is the thing to read first: the two rewrites each wanted to be first and would have written the document mirror twice, and the resolution is a **seam** — the mirror observes a tree CONTRACT (`ui.dom.TreeSource`) rather than a class, so it is authored once and the engine swap underneath it is a port of one file. Decisions D1–D12 were taken as recommended. **M0–M2 are done**: the Taffy fork (S1), the Shadow-DOM prototype (S2) and the seam (M0); contracts on all 87 widget classes (M1); the mirror — stable ids, `insert`/`remove`/`move`, and `net/mirror/` generic over the node type (M2). **§M3.P — projections — SHIPPED 2026-08-30 ahead of the rest of M3**: the model→view direction none of the three plans covered: `mirror()` is hand-written per panel today, so a field nobody remembered to write never updates and the first value is right, which is how a frozen `ProgressBar` shipped. Three tiers over an UNMODIFIED model, prior art weighed against Fabric, Blazor, LiveView, Unreal, NGO and Godot, and the finding that decides the shape: "which fields changed" is automatable and "which widget shows which field" is not, by anyone |
| **`plan/shell-workbench-rewrite.md`** | — | **THE APPLICATION, THE ENGINE UNDER IT, AND THE HOST UNDER THAT.** What `plan/net-audit.md` was to the wire, this is to the shell — and it supersedes the v1 audit at `6af157ce`, which asked only whether `ScriptWorkbench` should exist. Measured on `rewrite` @ `6af157ce`: `Workbench` is **3,378 lines whose 391-line constructor IS the application** (A4); the product is written three times over, in `CgUiScreen`, the harness and the tests (§1.2); the loader decides the window's title, key, icon, close policy and first-run geometry (§1.3); **125 mutable statics across 76 files** are what a second application would have to share (§1.4); and **the retention chain is nine links long** — six static signals and four `Workspace` signals never disconnected, with `CgUiScreen.disposeAll()` having no caller — so nothing in this tree has ever actually been closed (§1.5, A8). Fifteen findings **A1–A15**; ten steps **W0–W8**; two series, deliberately different letters. The design is **five tiers** (§4.1): a **host** supplying `HostServices` and nothing else, a **shell** (`Desktop`), an **application** (`ApplicationKind` — a manifest shaped like `.desktop`/`Info.plist`), an **engine** (`Workbench implements WorkbenchContext`), and an **extension** seam (`WorkbenchExtension.activate(ctx) → Disposable`), with `ToolWindowKind` declaring a panel exactly as `DocumentKind` declares a file type (§4.4). §4.12 is the rule that settles every ownership question — *if two applications on the same server would disagree about it, it is theirs; if they would merely duplicate it, it is the workspace's*. §4.13 makes projects many-per-source with the GLOBAL/WORLD scope a choice only in single-player; §4.14 makes scripting a capability a **server grants**, never a feature a client has. **W0 is a leak test that is red on the current tree**, written before anything is moved |
| `plan/engine-core.md` | — | **M5 broken into minor milestones 5.0–5.6**, each with contents, the tests that accept it, what it proves, whether it touches the old engine, and its size. §2 is the ground rules (the strangler line as a bytecode-scan test; the old engine touched in exactly three named seams; the engine writes nothing into the cascade); §3 the ten decisions the M5 row left open, with recommendations; §7 the metrics "done" is measured by (one-pass layout on the gallery's trees, hit-test before paint, one coordinate chain, zero engine writes into the cascade, the seam suite unchanged on the new tree). Read it before touching `ui/dom`, `ui/box` or `ui/service` |
| **`plan/engine-port.md`** | — | **THE PORT, audited before it starts.** What `plan/engine-audit.md` was to M5, this is to M6: the whole old engine measured on the tree at `5c1fa09a` — 303 files / ~96,500 lines of port scope (a fifth more than the audit counted, because `graph/shader`, `language/…/run/view`, `net/window`, the Machine example and `CrystalEditor` all extend `UINode`), every one of `UINode`'s 166 and `UIDocument`'s 67 members mapped to its counterpart or a named gap (§4), a census of every mechanism the widget layer reaches (210 `appendStructural` sites, 117 IMPORTANT writes classified one by one, 185 geometry reads, 80 `stopPropagation` sites each of which is a READING), and a census of the sheets that changes the plan: of 1,048 part selectors, **401 select a part under a part and 99 reach through a part into a tag — `::part()` cannot express either**, so `__x__` is three kinds (a shadow part, light-tree structure, a state flag) and the master plan's one-line rewrite is a classification first (§1.1, D1). Fourteen seams outside the element layer are typed on `UINode` (§1.2); the networking sessions are on M6's critical path, not M7's (§1.3); 164 test files construct a `UIDocument` and move with the widgets (§1.4); 32 tags a sheet names are unregistered and match by the lowercase fallback (§1.5). §2 is the machinery that must exist before the first widget moves — `Dismiss`, the ghost, `scrollExempt`, `HIDDEN`, `exportparts`, the fixture twin, the governance twins and the ledger; §4.6 the 24 decisions with recommendations; §5 the minor milestones 6.0–6.9, each with the widgets, the scenes and tests that accept it, the invariant rows it owns, and its hazards; §8 what done measures; Appendix A the per-file port matrix for all 303 files. **The unit of work is a closed tree — a scene — never a widget**, because a `UINode` cannot be a child of a `UINode` and no adapter is built; **the port is COPIED and transformed by a codemod, never written** — 2,227 mechanical sites, ≈443 hand-edited, into a new package map by kind and layer (§2.6–2.8: `widget.*` < `chrome` < `desktop` < `workbench`, enforced by `LayeringTest`). Read it before porting anything |
| `plan/net-audit.md` | — | The WHY for the wire half. A complete audit of `net/`, `net/window/`, `net/protocol/`, `serialization/` and how they meet the widget, desktop and workbench stacks — against Chrome DevTools Protocol, React Native Fabric, Blazor, Phoenix LiveView, Unity NGO, Unreal and Godot. Findings W1–W4, P1–P4, S1–S14, E1–E6, Y1–Y4, N1–N9, K1–K9, Z1–Z5, plus abuse paths, the rewritten authoring surface (§4.11) and the `layout(M)` → `build(M)` argument (§4.12). **Appendix A** is the tear-out diagnosis: a `Detached` prototype was built, measured and reverted, because it taught the client to lie about where a subtree was rather than fixing identity-by-position |
| `plan/engine-audit.md` | — | The WHY for the layer under it — one tree doing DOM, layout, paint and hit-test at once; the cascade as the engine's only mutable box model; encapsulation by a boolean flag; five motion mechanisms; a thread marker that asserted nothing (fixed at M0). The invariants in `docs/CGUI_INVARIANTS.md` are its receipts — most of that file is this audit's findings, kept as rules. §12 is the three-tree design; §13 the ten-step port |

**The table above is the index; do not count it here.** This paragraph has carried a number three
times and been wrong all three — it said *four* while five were listed, was corrected to *five*, and
then grew a second opening sentence saying *six* while the first still said five, with nine files on
disk. `ls docs/*.md` is the answer and always was; a row is easy to add and a count below it is easy
to miss, which is the whole reason a derived number does not belong in prose. The first three were
audited against the code on 2026-07-29, `CGUI_THEMING.md`'s token table is machine-checked against
the CSS on every test run, and `CGUI_NETWORKING_PRIMER.md` was read out of the source class by class. `CRYSTALGUI_OVERHAUL_V4.md` (the historical decision record for why CrystalGUI stopped
owning rendering infrastructure) was deleted; its one durable conclusion — CrystalGraphics owns the
backend, CrystalGUI is a thin immediate-mode paint surface — is recorded in
[CrystalGraphics ownership boundary](#crystalgraphics-ownership-boundary) above.

## External references

- **LDLib2** — pattern prior art for widgets and the Ore theme. An **in-repo checkout** at
  `research_repos/LDLib2`, never a dependency. Stylesheets at
  `research_repos/LDLib2/src/main/resources/assets/ldlib2/lss/` (`gdp.lss`, `mc.lss`, `modern.lss`).
  Java sources under `src/main/java/com/lowdragmc/lowdraglib2/`; note `bin/` also holds compiled
  `.class` files, so search `src/` explicitly. *(Was documented as a sibling checkout at `../LDLib2`,
  which does not exist.)*
- **Taffy** — consumed as the Gradle artifact `dev.vfyjxf:taffy` (version in `gradle.properties`), but
  **extracted Java sources are checked in** at `research_repos/taffy/dev/vfyjxf/taffy/`. Read them
  directly — there is no need to decompile through the IDE, and no need to guess at layout semantics
  (containing blocks, absolute positioning, flex-wrap cross-sizing) that the engine's own behaviour
  depends on. *(Previously documented as "no source checkout"; it exists.)*
- **Monaco** — an in-repo checkout at `research_repos/monaco`, which is where every "VS Code does
  X" claim in `com.crystalgui.text.cursor` was read rather than remembered. See *Port, don't reinvent*.
- **Minecraft sources** — not extracted at the paths the MC modules would produce
  (`mc1201/*/build/mc-src/`, `build/rfg/minecraft-src/java`), since neither MC module is in the build.
  **But an extracted 1.20.1 tree is checked in** at `research_repos/mc1201_sources/`
  (`com/`, `mcp/`, `net/`). Cite that path, not the build ones.

---

# For future reference

- **Cg** → CrystalGraphics
- **Cgui** → CrystalGUI
