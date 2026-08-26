# Session handoff — `ItemSlot` / `FluidSlot` (native content slots)

**Purpose of this file.** A complete transfer of an in-flight session so it can be resumed from a
different account. Everything needed is here; there is no other context to recover.

**To resume:** open Claude Code in `C:\Users\somehussar\Desktop\Java\Minecraft\CrystalGUI`, check out
branch `native-content-slots`, and read this file first. Then read the "Do not redo" section before
touching anything — a lot of expensive dead ends are recorded there.

---

## 1. The original request (verbatim intent)

> Plan and implement a new `UIElement` connected to the main element registry, representing an
> `ItemSlot` (and potentially a `FluidSlot`). The logic may later be reused as the base for an
> "entity renderer" element — **do not implement that**.
>
> **0. General.** All work on a separate branch (branches, if CrystalGraphics must be touched).
> Elite-tier production-grade standards.
>
> **1. Element registration.** `core` should only register a mock element, or disable the requirement
> that these elements be registered, when running on a non-MC platform. On MC platforms a missing
> registration should cause a **fast failure (crash)** screaming that these elements are not
> registered *and not marked as unneeded for the platform*.
>
> **2. Native rendering.** UI rendering is shader/material driven, but other MC versions render
> items/entities with their own shaders or entirely on the fixed-function pipeline (1.7.10). The
> element needs an opt-in to the native part of rendering.
>
> **2.1 Viewport syncing.** The UI sets up viewport only for shaders; nothing syncs back to the
> fixed-function pipeline. `PoseStack` is handled, which matters. "This might just happen when we
> start rendering our UI — please research." Most relevant on 1.7.10 when the MC GUI scale differs
> from our default 2× UI scale.
>
> **2.2 Native tooltips.** The library has its own tooltip system but no arbitrary tooltip rendering
> strategy. Render the tooltip natively for the current platform **unless explicitly hijacked**.
>
> **3. Styling & rendering theory.** Minimum: render a basic `UIElement` as background (all styling
> rules applied) → render the required element over it → render overlays. This is a simplified
> example, **not** the current model. **The current render model must stay unchanged for all
> elements.**

### Clarifications the user gave during the session (all binding)

- **"in 3, the middle part of the 'logical hamburger' was the native rendering of the item/fluid."**
  Confirms the middle layer is the native draw, between styled background and overlays.
- **"No, just leave the fluid to native rendering — other mods render their fluids differently."**
  Fluid is *not* rendered by our own pipeline. Both go native.
- **"Maybe not 'one' escape! Both use different rendering logic."** → produced `NativeProfile`
  (`FLAT` vs `MODEL`). This was the user's correction and is load-bearing.
- **"What kind of new tokens do you even need! Everything already exists in the stylesheet doesn't
  it??? We do not style the actual native renderer, just the box it sits in."** → **zero new theme
  tokens.** Geometry only in the UA sheet; appearance in `ore.css` using existing sprites.
- **ore.css should look "close to minecraft".**
- **"using mixins we can define specific behaviour and apply them to each version's `Slot` equivalent
  as interfaces … I would rather have a less invasive approach though."** → mixins are permitted but
  the low-invasiveness path was chosen; none are used.
- LDLib2 is cloned locally at `C:\Users\somehussar\Desktop\Java\Minecraft\LDLib2` (990 java files).
  **`AGENTS.md` wrongly claims that path "does not exist"** and points at `research_repos/LDLib2`,
  which is gitignored and absent on this machine.

### Decisions already taken (do not relitigate)

Answered by the user via a decision prompt:

| Question | Answer |
|---|---|
| Depth strategy for 3D content | Small shared scratch FBO (later forced to screen-sized, see §7) |
| Scope | **Both** `ItemSlot` and `FluidSlot` in this branch |
| Loaders | **mc1710 only**. `mc1201` is commented out of `settings.gradle.kts` and unbuildable |
| Slot state model | Follow LDLib2 — a *binding*, not stored item data (see §4) |

The user also pre-authorised a fallback: *"We can add a depth buffer to the UI buffer if it is really
needed, and just only enable it for items."* **Not needed** — depth is resolved (§6) without touching
the shared targets.

---

## 2. Current git state

- Branch **`native-content-slots`**, created off `master` at **`71033332`**.
- **Committed** as `21395a62` on this branch; the harness submodule commit is `35f84d7`.
- **CrystalGraphics was not touched** — its working tree is clean. No second branch was needed.
- `git status` shows `M CrystalGraphics` and `M gl-debug-harness`. The **CrystalGraphics one is
  pre-existing gitlink drift** (checked-out `3881e60`, 3 commits ahead of what master records) and
  **must not be swept into a commit here**. The `gl-debug-harness` one is genuinely mine and needs its
  own submodule commit plus a deliberate gitlink bump.

### Files changed (15 modified, 3 new directories)

```
 M AGENTS.md                                      scene table, widget table, registry count,
                                                  package map, 5 new invariant rows
 M CrystalGUI_TODO.md                             §1.2 platform-delegated tooltips: un-struck
 M docs/CGUI_WIDGETS.md                           new §12d
 M core/.../render/CgUiPaintContext.java          nativeContent(), drawLayerRegion(), NATIVE_FORMAT,
                                                  nativeFbo, acquireNativeFbo(), teardown
 M core/.../ui/ElementRegistry.java               registers itemslot + fluidslot
 M core/.../ui/UIWindow.java                      native tooltip request + drain before endFrame
 M core/.../ui/elements/Tooltip.java              dragIsLive() private -> public (shared rule)
 M core/.../styles/ua/widgets.css                 slot GEOMETRY only (18x18, 1px padding)
 M core/.../styles/ore.css                        slot + __unsupported__ faces, existing sprites
 M core/src/headlessTest/.../ElementStateCoverageTest.java   STATEFUL + MUTATORS for both tags
 M core/src/test/.../testsupport/TestPlatformService.java    declares UNSUPPORTED once per JVM
 M mc1710/.../mc/ClientProxy.java                 registers the service (CLIENT ONLY)
 M mc1710/.../mc/client/CgUiScreen.java           drawNativeItemTooltip() static hook
?? core/src/main/java/com/crystalgui/ui/elements/slot/       7 new files
?? core/src/test/java/com/crystalgui/ui/elements/slot/       NativeContentSlotTest (14 tests)
?? mc1710/src/main/java/com/crystalgui/mc/platform/service/content/   2 new files
 (submodule) gl-debug-harness: CgUiSlotScene (new), SceneRegistry, PlatformServiceHarness
```

---

## 3. Architecture as built

### The seam

`core/.../ui/elements/slot/`:

| File | Role |
|---|---|
| `NativeContentService` | The contract + the `CgService` slot + `UNSUPPORTED` sentinel + `require()` |
| `NativeProfile` | `FLAT` (blended, no depth — fluids) vs `MODEL` (depth + lighting — items) |
| `NativeSurface` | The offscreen box handed to the host: pixel size, logical size, profile |
| `NativeContent` | Opaque platform handle: `descriptor()`, `profile()`, `isEmpty()`, `fillFraction()` |
| `NativeContentSlot` | Abstract base `UIElement` — paint, hover/tooltip, state |
| `ItemSlot`, `FluidSlot` | Concrete tags; `FluidSlot` adds `FillDirection` + `fillBox()` |

The slot is declared on the interface itself, following `CgNetworkChannel.SERVICE`:

```java
CgService<NativeContentService> SERVICE =
        CgService.of("crystalgui:native-content", /* absent-value */ …);
```

### Why both elements live in `core`

`ElementRegistry.register` is **bijective** — one class ↔ one tag, enforced in both directions with a
rollback. A loader therefore *cannot* register its own subclass under `itemslot`; the second
registration throws. So the elements must be in `core`, and the platform question moves to the service
slot. This is not a preference; the alternative does not compile.

### The three-state policy (requirement 1)

| State | How | Result |
|---|---|---|
| Available | `CgPlatform.provide(SERVICE, impl)` | native draw |
| Declared unneeded | `CgPlatform.provide(SERVICE, UNSUPPORTED)` | `__unsupported__` class, placeholder, no crash |
| Nobody said anything | — | **`require()` throws** at first paint, naming the slot and *both* remedies |

Thrown at **first paint, not construction**: a dedicated server legitimately builds a slot in order to
describe it and has no renderer to want. The harness and `TestPlatformService` both install
`UNSUPPORTED` explicitly — that is the feature working, not a workaround.

### Requirement 2.1 — the answer turned out to be matrices, not viewport

Researched and settled. The viewport is already correct (`CgUiScreen` inherits Minecraft's; both draw
to the same surface). **The mismatch is the matrices**, and it does *not* resolve itself:

- Our ortho lives in a **shader uniform** (`CgFrameData.projMatrix`), our `uiScale` lives in the
  **`PoseStack`** — neither visible to a fixed-function renderer.
- `GL_PROJECTION` holds whatever Minecraft left, and `CgUiScreen` **deliberately ignores MC's GUI
  scale** (uses raw `mc.displayWidth/Height` with its own scale).
- Repo-wide there is **no `glMatrixMode` / `glOrtho` / `glFrustum` at all**; `CgGLBackend` offers only
  push/pop/loadMatrix, on purpose.

**Resolution:** the host is never given CrystalGUI's coordinate space. It gets a small offscreen box
and its size, sets up one ortho for it, and the result is composited through our pose — so `uiScale`,
CSS `transform` and the ambient scissor all apply for free and the two scales never have to be
reconciled. All fixed-function matrix work lives loader-side in `mc1710` with raw LWJGL2, exactly as
`CgUiScreen:518-538` already does.

### Requirement 2.2 — tooltips

`Tooltip` has no renderer seam (`attach` takes a `String`, always builds a `UIText`). Rather than
duplicate it: **triggering stays where it is, rendering is delegated.** `tooltip-delay`, the region
logic and `Tooltip.dragIsLive` are the tooltip's rules and remain one definition — `dragIsLive` was
made `public` for this.

**Ordering is the constraint.** A native tooltip is an immediate GL draw with no element to promote,
so `UIWindow` holds **one** pending request (one pointer, one tooltip) and drains it **after
`drawSubtree` and the top layer, before `endFrame`**. Re-asked every frame and cleared as drawn, so it
needs no hide path. Coordinates are **raw surface pixels**; the loader converts to MC's GUI-scaled
space.

### Requirement 3 — styling, with the paint model unchanged

Maps onto hooks that already exist; `drawSubtree` is untouched:

```
super.paintSelf   -> styled box (background, border, radius) — no new code
nativeContent(…)  -> the host's renderer, in the CONTENT box (padding-aware)
paintOverlay      -> CSS `overlay`, already painted after children
```

`ua/widgets.css` gives geometry only (18×18, 1px padding → 16×16 content box) and **no colour**, so no
theme token was added. `ore.css` reuses the atlas's inset `textfield` sprite for the well and
`button-disabled` for `__unsupported__`.

---

## 4. Data model — a binding, never a value

**This is the LDLib2 answer and the reason it was chosen.** Their `ItemSlot` holds
`private Slot slot` and `getValue()` is `return slot.getItem()` — it never stores a stack. A
`SlotAccessor` mixin pushes widget position back so **vanilla** handles click/drag/carry *and*
container sync. Display-only slots get a `LocalSlot`.

So ours binds too: `NativeContent` is re-read every frame, and what serialises is
`descriptor()` — a *location* (`slot:12`), not contents. Consequences:

- Container UIs ride Minecraft's own synchronisation; **no item data crosses CrystalGUI's wire**.
- A dedicated server can describe an inventory it has no way to draw.
- `STATEFUL` is `true` for both tags, and the round-trip is descriptor-only.

**Licence:** LDLib2 is **LGPL-3.0**. `THIRD-PARTY.md:18` records "nothing is copied from it". It was
read for **shape only** (same standing as Zed/wget). No line is ported. Keep it that way.

### Explicitly out of scope

**Interaction** — click to pick up, drag to distribute, carried-stack rendering. The slot renders and
describes; it does not move items. LDLib2 needs a mixin only because it lets *vanilla* own the
hit-test; `UIInputHandler` already has three-phase dispatch, pointer capture and a drag protocol, so
the follow-up can take its own press and ask the platform to perform a slot action — no mixin, at the
cost of owing vanilla's slot-click *semantics* (shift-move, drag-distribute, double-click-collect).
The binding handle is the seam that keeps this a follow-up rather than a rewrite.

`mc1710` has `usesMixins = false`, empty `mixinPlugin`/`mixinsPackage`/`coreModClass`, and **zero
mixin files**. Adding one is a build + loading-layer change, not a local decision.

---

## 5. What is verified

Harness screenshot at `gl-debug-harness/harness-output/cgui-slot/cgui-slot-startup.png`.

- **`:core:headlessTest` passes** — 1363 tests, including `ElementStateCoverageTest` with both new tags
  (write → read into a fresh instance → write → compare).
- **`NativeContentSlotTest` — 14 tests, all pass.** Covers all three platform states, the counter-
  assertion that an opted-out platform is *accepted*, tag/class registration, `tagName()`, refusal of
  public children, descriptor round-trip, retention of an unresolvable descriptor, late resolution when
  a renderer arrives after the description, and all four fluid fill directions.
- **Mutation-checked**: replacing `if (!CgPlatform.isProvided(SERVICE))` with `if (false)` fails exactly
  one test. The guard is real.
- **`:mc1710:compileJava` green.**
- **In the harness, visually confirmed**: composite orientation (magenta marker top-left — a V-flip
  would put it bottom-left), per-size resolution at 16/18/32px, clipping inside a scroller, the
  `opacity: 0.45` layer-FBO path, all four fill directions, the empty well, and the live `U` swap
  between the stand-in renderer and `UNSUPPORTED`.

### The harness scene

`cgui-slot` → `CgUiSlotScene`. It installs a **stand-in fixed-function renderer** (raw `GL11`
immediate mode — deliberate, it impersonates the foreign renderer the seam exists to host) and restores
`UNSUPPORTED` on dispose. Press **U** to swap platform states live. Its javadoc carries the full manual
verification checklist.

---

## 6. RESOLVED — depth testing

**Fixed.** The cause was `GL_DEPTH_FUNC`, not the depth buffer.

The UI runs with the depth function at `GL_ALWAYS` on purpose -- it paints in painter's order over
whatever the world left behind, and `gui_curve.shader` says so in its own `RenderState`. A host
renderer inherits that, so enabling depth testing on top of it still lets every fragment pass, and a
depth-tested model silently degrades to submission order: the far face wins and a block item is drawn
inside-out.

**Nothing about it read as a depth problem.** At draw time `glIsEnabled(GL_DEPTH_TEST)` was true,
`GL_DEPTH_BITS` was 24, the write mask was on, and the framebuffer was complete. The only wrong value
in the entire state was the function, reading `0x0207`.

`NativeProfile.MODEL` now means *the engine establishes a depth-tested context* -- enabled and
`LEQUAL` (what Minecraft's GUI item path expects, and what `CgDepthState.TEST_WRITE` spells) -- set in
`nativeContent` rather than by each host, since no host should have to know what CrystalGUI left the
function at.

### Eliminated on the way, worth not repeating

| Hypothesis | Result |
|---|---|
| Wrong depth format -- `DEPTH24_STENCIL8` vs plain `DEPTH24` | neither; not the format |
| Lazy mid-frame FBO creation | pre-creating in `warmUp()` changed nothing |
| Harness scene declared `needsDepthBuffer(false)` | flipping it changed nothing -- the FBO carries its own attachment |
| Test fixture sign convention | **was** wrong: in this GUI ortho higher z is NEARER, matching `RenderItem.zLevel`. Fixed |
| `glClear(GL_DEPTH_BUFFER_BIT)` gated by the write mask | **a real bug, fixed** (`glDepthMask(true)` before the clear) -- but not the cause |


---

## 7. Engine findings (pre-existing, NOT introduced here)

### 7a. A small `createOwned` FBO's colour texture samples black

Cost many iterations; the bisection is worth preserving:

| Probe | Result |
|---|---|
| `glReadPixels` inside the FBO after the host draw | `(0,255,0,255)` — **green, correct** |
| Composite parameters logged | tex id valid, `uMax=vMax=1.0`, `color=ffffffff`, `layerOpacity=1.0` — identical to the working `drawLayer` |
| Composite replaced with `fillRect` magenta | **magenta shows** ⇒ quad path and rect are correct |
| Composite texture swapped to `whitePixel` | **white shows** ⇒ material, sampler and texture unit are correct |
| Depth attachment removed entirely | still black ⇒ not the format |
| FBO pre-created in `warmUp()` instead of lazily | still black ⇒ not lazy mid-frame creation |
| Composite from a **pooled screen-sized** layer FBO | **GREEN** |
| Own FBO created at **screen size** | **GREEN** |

**Conclusion: size is the discriminator.** A small `CgFrameBuffer.createOwned` target reads back
correct and samples black. `WindowSnapshot` never hit this because it sizes to a window.

**Workaround in place:** `acquireNativeFbo` sizes the native target to the screen and the clear is
**scissored to the used region**, so a 54-slot inventory does not pay 54 full-screen clears.

### 7b. Harness scenes render 8-digit hex as red

The **untouched** `cgui-checkbox` scene renders its blue-greys (`#FF2A2A3A`, `#FF3C3C50`) as red too.
Not caused by this branch, but it is why the `cgui-slot` screenshot looks maroon. Worth a separate
investigation.

### 7c. Two stale-doc defects found while reading

- `ScissorStack.clearScissorIfNeeded()` is inverted — `if (!hasScissor()) glDisable(...)` — so it does
  nothing precisely when a scissor *is* active, the case it exists for. `WindowSnapshot.renderInto:152`
  calls it to escape an ancestor's clip and is therefore still clipped.
- `CgUiPaintContext` javadoc has `@see UIElement#paintAsSurface`; no such method exists anywhere.
- `CgPlatform.resources()` javadoc claims it returns `null` before registration; it calls
  `ensureCreated()` and throws.

---

## 8. OPEN — `:core:test` executor failure, no baseline established

`:core:test` fails with the **test executor process dying**, not an assertion: 2255 tests across 185
classes, **zero `<failure>` or `<error>` entries in any result XML**. Run time swings between ~33s and
>10min, which suggests a hang.

**One cause was mine and is fixed:** `CgService.provide` logs to stderr on every install, and it had
been placed in `TestPlatformService.install()`, which runs from a per-test `@Before` — ~2255 stderr
lines killed the Gradle worker (the failure surfaced as a broken socket in
`SocketConnection$SocketOutputStream.flush`). Now guarded to once per JVM via
`contentServiceDeclared`.

**It still fails after that fix, and no `master` baseline was ever taken.** First step for whoever
picks this up: run `:core:test` on a clean `master` worktree and compare. Do that before assuming this
branch caused it.

Separately, `com.crystalgui.fs.ResourceTest.theProjectSchemeRefusesAProvider` **fails on master** —
confirmed pre-existing (`git diff master -- core/.../fs/` is empty). `ResourceRegistry`'s own javadoc
documents that the guard was *deliberately moved* out of `register` into the read path without
updating the test that asserts the old behaviour.

---

## 9. Commands

```bash
# compile
./gradlew :core:compileJava --max-workers=1
./gradlew :mc1710:compileJava --max-workers=1

# tests
./gradlew :core:headlessTest --max-workers=1                     # PASSES (1363)
./gradlew :core:test --tests "*NativeContentSlotTest*" --max-workers=1   # PASSES (14)
./gradlew :core:test --max-workers=1                             # executor dies, see §8

# the scene (ALWAYS pass --seconds, per the harness rules)
./gradlew :gl-debug-harness:runHarness --max-workers=1 --args="--mode=cgui-slot --seconds=6"
# artifact: gl-debug-harness/harness-output/cgui-slot/cgui-slot-startup.png

# in-game -- the ONLY way to test the real renderer
./gradlew :mc1710:runClient --max-workers=1 -PcgSlotProbe    # opens a window of real slots
./gradlew :mc1710:serverSmoke --max-workers=1   # BLOCKED: needs eula=true in mc1710/run/eula.txt
```

`--max-workers=1` matters for anything touching `:mc1710` — the root `gradle.properties` warns that
included-build tasks otherwise get separate workers and RFG throws
`ConcurrentModificationException`.

### In-game checks still owed

1. A **3D block** item — the depth case. A flat sprite proves nothing.
2. A slot inside a scroller (scissor) and inside an `opacity < 1` parent (layer FBO).
3. A slot at an **MC GUI scale that differs from our 2× uiScale** — the case §2.1 exists for.
4. The UI still renders after the native draw — a missing `invalidateAllIfPresent` shows as a *missing
   GL call*: wrong rendering, no exception, nowhere near the cause.
5. The world still renders after the screen closes — `CgUiScreen:518-538` documents that leaving the
   active texture unit wrong yields a pure white window while `glReadPixels` looks perfect.
6. `serverSmoke` — asserts no client-only class loads on a dedicated server. Real risk here, because
   the elements are in `core` while the service is client-only.

---

## 10. Do not redo

- **Do not** try to make a loader register its own `itemslot` subclass. `ElementRegistry` is bijective;
  it throws.
- **Do not** re-derive the viewport question. It is matrices, not viewport, and the resolution is in
  §2.1 — the host is never given our coordinate space.
- **Do not** add theme tokens for slots. The user was explicit: only the box is styled, and the box is
  an ordinary `UIElement` that already has everything.
- **Do not** re-bisect the black-composite bug. §7a has the full log; the workaround is in place.
- **Do not** re-try the eliminated depth hypotheses in §6. Depth is fixed; the cause was `GL_DEPTH_FUNC`.
- **Do not** hand-roll target binding in `nativeContent`. It was tried; use the engine's primitives.
- **Do not** put `CgPlatform.provide` in a per-test `@Before` (§8).
- **Do not** port code from LDLib2. LGPL-3.0, shape only.
- **Do not** commit the `CrystalGraphics` gitlink — that `M` is pre-existing drift from three unrelated
  commits (§2).

## 11. Related files

- Plan for this work (account-scoped, will **not** transfer):
  `C:\Users\somehussar\.claude\plans\please-quickly-familiarize-yourself-enchanted-origami.md`.
  Everything from it that still matters has been folded into this file.
- `AGENTS.md` — updated by this branch: scene table, widget table, registry count and bijectivity note,
  the `.slot` package map entry, and five new load-bearing invariant rows.
- `docs/CGUI_WIDGETS.md` §12d — the widget reference for these two elements.
- `CrystalGUI_TODO.md` §1.2 — platform-delegated tooltips, un-struck and marked shipped.
