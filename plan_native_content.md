# plan_native_content.md — NativeContent as a cross-version contract

*Written 2026-08-27, on branch `native-content-slots`, after the 1.7.10 renderer landed
(`0f7d4fe3`). Status: **steps 1–4 implemented** — the grammar (`NativeDescriptors`, pinned in
`headlessTest`), descriptor-only authoring in the probe and `serverSmoke`, the UV fractions on
`NativeTileGrid`, and the docs. One deviation from the written plan, for the better: an item id is
required to be **exactly** `ns:path` (a registry path cannot contain a colon on any version), so
`item:a:b:c:1:2` is refused rather than guessed at. Step "interaction" remains out of scope as
planned. In-game re-verification of the probe (pixel-identical to `0f7d4fe3`) is the open item.*

*Follow-on (same session): the seam grew the value crossing — `wrap(Object)` / `unwrap(NativeContent)`
on the service (a platform value the grammar cannot express crosses by reference; a binding unwraps
to its live occupant) — and the two-axis identity question, `kind()` (`ITEM`/`FLUID`/`NONE`, what is
displayed) + `isBinding()` (whether it reads through), both defaulted from the descriptor. The
loader's `draw()` now dispatches on kind rather than profile, freeing `profile()` to be purely the
GL contract. NBT display stacks are thereby authorable from generic glue: `bind(service.wrap(v))`.*

## Context

The goal is the Architectury property: a UI that names an item slot or a fluid tank is authored
**once**, in code that imports nothing platform-specific, and renders natively on every target —
1.7.10 fixed-function today, 1.20.x core shaders when `mc1201` rejoins the build, the harness
stand-in always.

The review's headline is that **the architecture already has this shape**, because the original
spec's requirement 1 forced it: `ItemSlot`/`FluidSlot` live in `core` and are registered in
`ElementRegistry` on every platform including a dedicated server; the only thing that varies is
whether anything filled `NativeContentService.SERVICE`. The GL bracket, the compositing, the depth
contract (`NativeProfile`), the fill anchoring (`NativeAnchor`) and the tile-grid arithmetic
(`NativeTileGrid`) are all `core`. The loader contributes one service class.

What remains are three **leaks** — places where knowledge that must agree across versions is
currently owned by one version's loader — plus one explicitly deferred seam. Fixing the leaks is
what this plan covers.

| # | Leak | Where it lives today | Why it bites |
|---|---|---|---|
| 1 | The **descriptor grammar** (`slot:`/`item:`/`fluid:` and their syntax) | `Mc1710Content`'s three private prefix constants | Nothing stops a future `Mc1201NativeContentService` parsing a different spelling — and then the *same serialized UI description* means different things on different clients, which defeats a content-addressed description outright |
| 2 | **Display-value authoring** goes through loader types | The probe constructs `Mc1710Content.DisplayItem(stack)` directly | A generic app cannot; the descriptor path exists (`setDescriptor`, lazily resolved) but is neither the demonstrated nor the enforced route |
| 3 | The **cut-tile UV arithmetic** (per-axis anchored shrink) | `Mc1710NativeContentService.addTile` | Pure arithmetic that was wrong twice on 1.7.10; the next loader would re-derive it from scratch, with the same two chances to get it wrong |

## What deliberately does NOT move

The seam is at the right altitude and these stay per-loader, because they are definitionally
per-version:

- **How items and fluids are actually drawn.** The glint coverage-rebuild is 1.7.10-specific —
  modern versions draw glint through render types, not the dst-alpha trick — so it could never have
  been shared. The fixed-function projection setup likewise.
- **Tooltips** (`GuiScreen.renderToolTip` vs whatever 1.20.x offers).
- **What a descriptor resolves *to*** (`ItemStack` vs `ItemStack`-the-other-one). Only the string is
  shared; the handle behind it is the loader's.

And one thing stays out of scope entirely: **interaction** (clicking a slot to move items). The
seam extends naturally — a `performSlotAction(descriptor, button, mode)` on the service or a sibling
— but it is its own piece of work with its own vanilla-semantics research, and
`docs/CGUI_WIDGETS.md` §12d already records it as a follow-up. Nothing in this plan forecloses it.

---

## Step 1 — `NativeDescriptors`: the grammar becomes a core contract

**New:** `core/src/main/java/com/crystalgui/ui/elements/slot/NativeDescriptors.java`

The three forms, specified once and pinned by test:

```
slot:<index>                                a container slot binding — a LOCATION, resolved live
item:<namespace>:<path>[:damage[:count]]    a display item, e.g. item:minecraft:stone:0:64
fluid:<name>:<amount>:<capacity>            a display fluid, e.g. fluid:water:620:1000
```

Shape: the prefix constants, `format` helpers (`slot(int)`, `item(String id, int damage, int
count)`, `fluid(String name, int amount, int capacity)`), and `parse` helpers returning small value
records (`SlotRef`/`ItemRef`/`FluidRef`) or **null** for anything malformed — never a throw, because
a descriptor is wire data and the service's contract for the unresolvable is already
`NativeContent.EMPTY`, not an exception.

Two parsing rules worth writing down because each is a trap:

- **The id itself contains a colon** (`minecraft:stone`), so segments are counted from the
  **right**: the trailing numeric segments are damage/count (item) or amount/capacity (fluid), and
  whatever remains is the id. This also absorbs the 1.7.10/modern split for fluids — 1.7.10
  `FluidRegistry` names are bare (`water`), modern ones are `ns:path` — without two grammars.
- **NBT is deliberately outside the grammar**, as `Mc1710Content.DisplayItem.descriptor()` already
  documents: it has no bounded text form, and a description is content-addressed, so a large tag
  would be re-hashed on every change of a value nothing reads. An NBT-carrying display stack is
  authored through a loader handle, and that is a feature boundary, not a gap.

**Migrate:** `Mc1710Content` deletes its three private prefixes and parses/formats through the core
helpers. `PlatformServiceHarness`'s stand-in service (gl-debug-harness) does the same — which makes
the harness a **second independent implementation of the grammar**, exactly the drift detector a
contract wants.

**Pin:** `NativeDescriptorsTest` in `core/src/test/` — round-trips for all three forms, the
right-to-left segment rule (an id with a namespace, an id without), defaults (damage 0, count 1),
and the malformed set (`item:`, `slot:x`, empty, wrong prefix) all answering null.

## Step 2 — descriptor-only authoring, demonstrated end to end

The point of step 1 is that this becomes possible with zero loader imports; this step makes it the
*proven* path rather than the theoretical one.

- **`CgUiSlotScreen`** (the `-PcgSlotProbe` screen): every display slot that carries no NBT switches
  from `new Mc1710Content.DisplayItem(stack(...))` to
  `slot.setDescriptor(NativeDescriptors.item(...))`, and the fluids likewise. The enchanted
  fixtures **keep** the direct handle — they are the NBT case the grammar excludes, and keeping both
  routes in one probe demonstrates both halves of the boundary. Rendered output must be
  pixel-identical to today; the probe is its own regression test for that.
- **`CgUiServerSmoke`**: the server-side slot it already builds gains a
  `setDescriptor(NativeDescriptors.slot(12))` and asserts the descriptor round-trips through
  `writeStateTo`/`readStateFrom` — a dedicated server authoring a container UI it has no way to
  draw, which is the whole multi-version story in one headless assertion.
- The lazy-resolution machinery this rides (`descriptorResolved`, re-ask on next access) already
  exists in `NativeContentSlot` and needs no change.

## Step 3 — cut-tile UV fractions move to core

**Extend** `NativeTileGrid` with the UV half of what it already does for geometry:

```java
/** The [0..1] slice of the sprite a tile of this size fraction shows, per axis. */
static float uvLo(float fraction, boolean fromFar);   // fromFar ? 1 - fraction : 0
static float uvHi(float fraction, boolean fromFar);   // fromFar ? 1 : fraction
```

Trivial-looking — which is the point. The rule it encodes ("the sprite aligns to the anchored end:
repeat from the anchor, clip at the far end") is what keeps every join seamless, it is **not** the
obvious reading (a tile truncated at its bottom edge "naturally" keeps its top slice — that was the
first bug), and Tinkers' Construct itself gets one axis of it wrong. The loader's `addTile` keeps
only the mechanical map from fractions into the sprite's `getMinU()`/`getMaxU()` interval and the
quad emit; the two `if (fromRight)`/`if (fromBottom)` UV branches are deleted.

**Pin:** cases in `NativeTileGridTest` — a full tile is `[0,1]` regardless of anchor, a cut tile's
span sits against the anchored end, and the two anchors of the same fraction are mirror images.

## Step 4 — docs, same commit

- `docs/CGUI_WIDGETS.md` §12d: a "Descriptors" subsection stating the grammar as the cross-version
  contract, the NBT boundary, and that the harness parses the same grammar.
- `AGENTS.md`: the `.slot` package-map entry gains `NativeDescriptors`; one invariant row — *a
  descriptor's grammar is core's, and a loader that invents its own spelling makes the same
  serialized description mean different things on different clients*.

---

## The payoff, stated as the acceptance shape

When `mc1201` rejoins the build, its native-content service is one class of roughly this size:

| Its job | Against |
|---|---|
| Parse nothing — call `NativeDescriptors.parse*` | step 1 |
| Resolve refs to its own `ItemStack`/fluid handles | its own version's API |
| Draw an item into the surface's box | `GuiGraphics`/`ItemRenderer`, per-version by design |
| Tile a fluid | `NativeTileGrid` for geometry **and** UVs — nothing left to re-derive |
| Draw a tooltip | per-version by design |

Everything it could get *silently* wrong — grammar drift, anchor direction, the cut-tile slice —
is already decided and pinned in `core` by then.

## Verification

```bash
./gradlew :core:test --tests "com.crystalgui.ui.elements.slot.*"   # new + existing pins
./gradlew :core:check                                              # headless incl. serverSmoke prereqs
./gradlew :gl-debug-harness:runHarness --args="--mode=cgui-slot"   # stand-in service on the shared grammar
./gradlew :mc1710:serverSmoke                                      # descriptor round-trip, no client classes
./gradlew :mc1710:runClient -PcgSlotProbe                          # F8 — pixel-identical to 0f7d4fe3
```

The probe comparison is the real gate for step 2: the descriptor switch must change **nothing** on
screen. Known pre-existing failures that are not this plan's problem:
`ResourceTest.theProjectSchemeRefusesAProvider` (headless), and the `:core:test` worker OOM
(512m default heap — spun off separately).
