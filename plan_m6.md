# M6 — the port: every widget onto the M5 engine, audited before it starts

**Status: planned, not started. Written 2026-08-30, the day M5 shipped.** This is to M6 what
`plan_m5.md` was to M5 and what the two audits were to the whole rewrite: the master plan's M6 row is
one paragraph, and one paragraph cannot carry a port of 90,000 lines, 87 widget classes, 497 part
names and 2,454 tests. Everything below was **measured on the tree as it stands at `5c1fa09a`** — the
old engine's whole surface, every mechanism the widget layer reaches, every selector shape the sheets
use, every test and every scene that stands under each widget — and then turned into minor milestones
each of which can be accepted on its own.

The method is the audits': read the seams, count, classify, then decide. Where the master plan's row
turned out to be wrong or short, §1 corrects it first, because a plan built on the row as written
would have discovered each correction as a surprise mid-port. Nothing here is implemented; §2 lists
the machinery that has to exist before the first widget moves, and it does not exist today.

> **The one-sentence answer to "how is this orchestrated".** The unit of work is not a widget, it is a
> **closed tree** — a harness scene, or a test fixture — because a `UINode` cannot be a child of a
> `UIElement` and no adapter is built to pretend otherwise (§3). Scenes are already dependency-ordered
> by what they construct (§0.6), so the batches in §5 are the scene list, and the game stays on the old
> engine until the last batch flips `CgUiScreen`.

---

## 0. The measurement

### 0.1 What is being ported

| Area | Files | Lines | Notes |
|---|---|---|---|
| `ui/elements/` root | 28 | 12,326 | the leaf widgets, the popover family, `Dialog`, `SplitView`, `TabView`, `ColorSelector`, `MarkupView`, `DragGhost`, `InsertionMarker`, `SearchField`, `SymbolIcon` |
| `ui/elements/editor/` | 34 | 15,427 | `TextEditor` (6,166), ten view parts, `CompletionPopup`, `DocumentationPopup`, `SearchReplaceBar`, the editor features |
| `ui/elements/workbench/` (+decoration, document) | 43 | 14,856 | `Workbench` (3,212), tool windows, regions, explorer, diff/merge, sessions |
| `ui/elements/desktop/` | 28 | 10,662 | `WindowFrame` (2,424), `Desktop` (1,359), taskbar, switcher, previews, motion |
| `ui/elements/chrome/` | 24 | 6,862 | menu bar, palette, status bar, notifications, Problems, preferences |
| `ui/elements/dock/` | 29 | 5,495 | `DockArea` (1,192), `DockGroup`, `DockWindow`; the rest is a headless model |
| `ui/elements/graph/` | 15 | 5,465 | `GraphView` (1,758), `GraphNode`, `NodePort`, wires, creation menu |
| `ui/elements/config/` (+control) | 22 | 3,041 | the config kit: `ConfigControl` and thirteen controls |
| `ui/elements/{list,tree,table,canvas,inspector}/` | 24 | 5,995 | `ListView` (1,408), `TreeSearch` (1,141), `CanvasView`, `TableView`, `Inspector` |
| **`ui/elements/` total** | **247** | **80,129** | |
| `graph/shader/` | 17 | 6,456 | **the shader graph editor** — `ShaderGraphEditor` (1,309), `BlackboardPanel` (1,360), `MainPreviewPanel`, `PropertyPill`, `CategoryHeader`, `InlineRename`, `ShaderNodePreview` — seven `UIElement` subclasses the master plan's row never mentions |
| `language/…/run/view/` | 10 | 3,400 | **`RunPanel` and `RunRail` extend `UIElement`**; `ScriptWorkbench`, `RunConsoleView`, `RunDecorations`, `RunIndicators`, `TailFollow` build trees. Outside `core/` and not named anywhere in the rewrite plan |
| `net/window/` | 15 | 3,467 | `Networked`, `UiType`, `ServerScope`, `ClientWindows`, `ViewCommands` — typed on `UIElement` in nine files |
| `example/machine/` | 9 | 2,093 | `MachinePanel`, `EnginePanel` — the worked example every doc quotes |
| `editor/` | 2 | 594 | `CrystalEditor`, the application root |
| `ui/shadow/` | 3 | 347 | the S2 spike; **deleted**, not ported |
| **Port scope** | **303** | **~96,500** | against the audit's estimate of ~76,700 |

The audit under-counted by a fifth because it counted `ui/elements/` and stopped. Everything that
extends `UIElement` is port scope, and four packages outside that directory do.

### 0.2 What is being replaced

| | Lines | Public + protected members |
|---|---|---|
| `UIElement` | 3,604 | 166 |
| `UIWindow` | 1,707 | 67 |
| `UIInputHandler` | 962 | 21 |
| `UIDragController` | 566 | 19 |
| `TopLayer` | 237 | 5 |
| `UIResizer` | 215 | — (engine structure) |
| `AnchoredPlacement` | 173 | 8 |
| `UITreeTraversal` | 308 | — |
| `EventListenerGroup`, `ElementRegistry`, `UIFrameTicker`, `Ui`, `UITransform`, `input/*`, `keymap/*` | 1,633 | — |
| **Total** | **11,405** | |

The new engine that replaces it: `ui/dom` (13 files), `ui/box` (7), `ui/service` (7), and the cascade
shared behind `Styleable`. §4 maps every one of those 166 + 67 members to its counterpart, or to a gap.

### 0.3 What the widget layer reaches into the engine for

A census over the 308 files above. **Files / call sites.** The full per-file matrix is Appendix A.

| Mechanism | Files | Sites | What it becomes (§4) |
|---|---|---|---|
| `addInternalChild` / `markAsInternal` / `acceptsPublicChildren` | 84 / 55 / 58 | 210 / 65 / 64 | shadow root + `part=`, or a light-tree structure class (**D1**) |
| `getAttachedWindow()` | 73 | 194 | `document()`, and mostly for `getInputHandler()` |
| `setHitTest(false)` | 68 | 171 | `set(Attribute.HIT_TEST, false)` |
| `getInputHandler().X` | 51 | 106 | `document().input()` / `.focus()` — drag controller ×29, `requestFocus` ×21, `requestPointerFocus` ×20, `getFocusedElement` ×15, `pointerPosition` ×7 |
| pre-bound event fields (`.onMouseDown` ×53, `.onMouseUp` ×8, `.onKeyDown` ×7 …) | — | 82 | the same fields on `UINode` (a gap today; §2) |
| `stopPropagation()` | 43 | 80 | unchanged spelling, **DOM semantics** — each site re-read (§4.4) |
| `getRuntimeCache().X` | 43 | 185 | `box().width()` ×45, `.height()` ×31, `.x()` ×27, `.y()` ×24, `localToWorld` ×1, `getDepth` ×1 |
| `importantPipeline` / `StyleOrigin.IMPORTANT` | 42 / 4 | 117 / 7 | forbidden on the new engine — every site classified in §4.5 |
| `CommandRegistry` / `Command` | 36 | 242 | unchanged; `CommandContext.source` retyped |
| `registerTicker` / `implements UIFrameTicker` | 35 / 25 | 52 | `animation().every(node, hook)` |
| `requestFocus` / `requestPointerFocus` | 27 | 44 | `focus().requestFocus` / `requestPointerFocus` |
| `getDragController().X` | 27 | 30 | `Drag.start` ×19, `isDragging` ×4, `isDropAccepted` ×2, `setGhost` ×1 |
| `screenToLocal` / `containsScreenPoint` | 25 | 57 | `box().worldToLocal()` / `box().hitTest` |
| `DataProvider` / `DataContext` / `DataKey` | 24 | 92 | walk retyped over the composed chain (**D12**) |
| `startDrag(` | 22 | 22 | `Drag.start` — coordinates change space (§4.4) |
| `void onLayoutChanged` | 22 | 23 | the box tree's post-layout callback; **half of them are geometry feedback that becomes `Measurable`** |
| `Tooltip.attach` | 18 | 37 | unchanged, once `Tooltip` is ported |
| `invalidateStyleMatch()` | 18 | 21 | unchanged (public on `UINode`) |
| `setDisplayed(` | 17 | 74 | `Attribute.HIDDEN` + a UA rule (**D5**) |
| `insertInternalChildAt` / `removeInternalChild` | 17 | 40 | shadow-tree `insertAt` / `remove` — the **dynamic** restructure sites, each read |
| `swapPrefixedClass` | 7 | 18 | `toggleClass` pair; trivial |
| `setScrollExempt` | 11 | 16 | a 5.4 gap: the painter must honour it (§2) |
| scroll API (`setScroll*`, `getScrollWidth/Height`, `getClient*`, `getMaxScroll*`, `clampScroll`) | 12 + 11 | 37 + 69 | `Box.setScroll` + **scroll extents the box does not expose yet** (§2) |
| `TopLayer` / `addToTopLayer` | 10 | 23 | `box().setHost(document.topLayer())` |
| `overlayHost` / `addOverlay` | 10 | 15 | `UIDocument.overlayHost(near)` — the algorithm ported as is (**D8**) |
| `AnchoredPlacement.` | 10 | 25 | ported; writes INLINE, not IMPORTANT (**D4**) |
| `pushCloseWatcher` / `requestClose` | 10 | 27 | the `Dismiss` service + `UINode.requestClose()` (**D7**) |
| `preventDefault()` | 9 | 10 | unchanged |
| `Keymap` / `KeymapResolver` / `KeyBinding` | 9 | 15 | retyped walks (**D12**) |
| `ContextMenu.attach` | 8 | 13 | unchanged once ported |
| `UIResizer` + the seven resize hooks | 8 + 7 | 16 + 27 | `resize:` re-hosted (**D6**) |
| `Disposer` / `Disposable` | 8 | 18 | keyed on nodes; `Lifecycle.destroy` runs it (**D18**) |
| `DragGhost` | 8 | 29 | ported as a widget; the controller's ghost seam is a `Drag` field |
| `attachDefaultListener` | 7 | 9 | the group's `defaultEvents` — a helper on `UINode` |
| paint overrides (`paintSelf` / `paintOverlay` / `paintOutline` / `paintChildren`) | 7 | 14 | `paintContent` / `paintDecoration`; **`paintChildren` has no counterpart** — the three overriders are read in §4.4 |
| `querySelector` / `find` / `require` | 7 | 10 | a `UINode` query API (gap; §2) |
| `registerCommands` / `bindKeys` | 5 | 8 | the same hooks on `UINode`, with the instance-initialiser trap removed |
| `pushAutoPopover` / `lightDismiss` / `popoverInvoker` | 5 | 14 | the `Dismiss` service |
| `TransitionEngine` / `isAnimating` / `Easing` | 5 + 5 | 9 + 22 | `Animation` timelines; transitions become the cascade's client of the same clock |
| `ctx.mirroring` / `mirrored` / `WindowSnapshot` | 4 | 11 | `BoxTree.mirror`; `WindowSnapshot` **stays** (§4.4) |
| `pushModal` / `isModalBlocked` / `modalScopeOf` | 4 | 5 | `focus().pushModal` / `isInert` / `scopeOf` |
| `keymap()` / `settings()` | 4 / 3 | 4 / 5 | the same accessors on `UINode` |
| `HighlightRegistry` | 3 | 12 | `TextNode.highlights()`; `StyleEngine.highlightStyle(Styleable, name)` already takes the seam |
| `setPointerCapture` | 1 | 1 | `input().setPointerCapture` |
| `setInert(` | 1 | 1 | `set(Attribute.INERT, true)` |
| `measureFunc` | 1 | 1 | `Measurable` — and it is `UIText`, which never used it |

Read the first row again: **210 `addInternalChild` sites across 84 files** is the port. Everything
else is reachable by search-and-replace or by one seam; that row is the one where each site is a
decision (D1).

### 0.4 What the sheets say

Ten UA sheet parts, 11,921 lines, 1,067 rules, **497 distinct `__part__` names**:

| Sheet | Lines | Rules | Parts | Pseudo-classes and pseudo-elements used |
|---|---|---|---|---|
| `workbench.css` | 2,479 | 228 | 162 | `:hover` ×26, `:disabled` ×6, `:active` ×5, `:checked` ×4, `:focus-within` ×2, `:focus`, `::highlight` |
| `editor.css` | 1,940 | 179 | 81 | **`::highlight` ×60**, `:hover` ×12, `:active` ×3, `:disabled` ×3 |
| `desktop.css` | 1,488 | 108 | 74 | `:hover` ×12, `:focus-visible` |
| `panels.css` | 1,340 | 127 | 79 | `:hover` ×20, `:disabled` ×6, `:focus-within` ×3, `::highlight` ×3, `:checked` |
| `config-kit.css` | 1,213 | 103 | 64 | `:blank` ×4, `:hover` ×3, `::highlight` ×2, `:disabled` |
| `inspector.css` | 889 | 97 | 69 | `:hover` ×9, `:checked` ×5, `:active` ×2, `:disabled` |
| `widgets.css` | 879 | 84 | 56 | `:checked` ×15, `:hover` ×14, `::highlight` ×11, `:focus-within` ×9, `:disabled` ×8, `:active` ×3 |
| `overlays.css` | 782 | 69 | 34 | `:hover` ×6, `:checked` ×3, `:disabled` ×3, `:focus` ×3, `:focus-visible` ×3, `:blank` ×2, `:invalid` ×2 |
| `search.css` | 764 | 69 | 43 | `:hover` ×15, `::highlight` ×7, `:disabled` ×6, `:focus` ×2, `:focus-visible` ×2, `:focus-within`, `:active`, `:checked` |
| `core.css` | 147 | 3 | 2 | **`:focus-visible` ×15** — the focus ring and its carve-outs |

Plus `graph.css` (116 rules, **235** part references), `ore.css` (92 rules, **101**),
`decorations.css` (2) and `dark-plus.css` (1). The themes (`base.css`, `crystal-dark.css`,
`crystal-light.css`) name **no** parts — the token architecture kept them out, which is the one part of
this that is already right. Twelve `transition:` declarations exist across the UA sheets; every one is
on `opacity`, `background-color`, `color`, `border-color`, `outline-color`, `transform`, `flex-grow` or
`display`.

**The selector census — the finding that changes the plan.** Of 1,194 selectors, 1,048 name a part.
Classified by shape:

| Shape | Count | Example | Can `::part()` express it? |
|---|---|---|---|
| bare part, no host scope | 496 | `.__row__ { }` | only as `*::part(row)` — and the whole reason for shadow parts is that a bare `.__content__` reaches three unrelated widgets |
| **part under part** | **401** | `window > .__title-bar__ > .__icon__`, `graphnode .__control-row__ .__label__`, `taskbar .__entry__.__active__ .__indicator__` | **no.** `::part(a)::part(b)` is invalid CSS; a nested part is reachable only through `exportparts`, or not at all |
| two state classes on one part | 167 | `.__entry__.__hidden__` | as `::part(entry hidden)` — a part may carry several idents |
| **part, then a TAG descendant** | **99** | `colorselector .__channel-row__ slider .__fill__` | **no** — a rule reaching *through* a part into a widget inside it |
| part + pseudo-class | 93 | `.__thumb__:hover` | `::part(thumb):hover` — allowed by spec |
| part + `::highlight` | 69 | `.__line__ ::highlight(keyword)` | `::part(line)::highlight(keyword)` is not allowed; the highlight has to be on the originating text node's own cascade |
| part + ordinary class | 25 | `.__crumb__.filetype-java` | `::part(crumb).filetype-java` is invalid — the class has to become a part ident |

So the master plan's *"every `ua/*.css` `__part__` selector → `::part()`"* is not a rewrite, it is a
**classification** followed by three different rewrites, and 500 of the 1,048 rules cannot be
expressed as `::part` at all. §1.1 and D1 take this up; it is the single largest correction in this
document.

### 0.5 What stands under each widget

| Net | Size | Written against |
|---|---|---|
| Widget/UI tests | **214 files, 2,454 `@Test`** (`core/src/test` + `headlessTest`, `com.crystalgui.ui`) | 164 files construct a `UIWindow`; 127 drive frames; 102 read `getRuntimeCache()`; 79 reach `getInputHandler()`; 64 drive `consumeMouseEvent`/`consumeKeyboardEvent`; 56 read `getChildren()`; 29 query selectors; 18 use `sendInputEvent`; 14 gate on window animations; 13 name `TopLayer`; 6 use the internal-child API |
| Per-class coverage | 274 classes, 400 test files name at least one | **60 classes no test file names** — listed in Appendix B; two dozen are view parts and helpers covered through their owner, but `WindowAnimator`, `WindowMove`, `WindowRegistry`, `WindowSnapshot`, `WindowThumbnail`, `ScreenOverlay`, `ExplorerDragAndDrop`, `ExplorerEditing`, `PageStack`, `ProcessesPopover`, `SymbolIcon` and `Breadcrumbs` are genuinely unpinned |
| Widget contracts (M1) | 87 classes, 28 with a `CONTRACT` constant | `WidgetContractCoverageTest` walks the classes; **it walks `UIElement` subclasses**, so it goes blind on the first ported widget until re-pointed |
| Harness scenes | **23 at the checked-out pointer** (`b5a2219`, branch `crystalgui`) | §0.6. `cgui-desktop`, `cgui-snapshot-probe` and `cgui-gradient-probe`, which `AGENTS.md` documents, are **not at this pointer**; the submodule working tree is also dirty. Reconciling it is the user's, and it gates 6.6 |
| M5 acceptance | `:core:m5Acceptance`, 98 tests | the engine under the port; unchanged by it |
| Governance | `EngineBoundaryTest`, `StyleGovernanceTest`, `ElementStateCoverageTest`, `WidgetContractCoverageTest`, `ElementRegistryTest`, `UnnecessaryTagTest` | each enumerates old-engine classes or tags; each needs a new-engine twin (§2.4) |

The ten most-tested classes, by `@Test` methods in files naming them: `UIText` 1,066 · `Button` 829 ·
`Tab` 767 · `TextEditor` 620 · `ScrollerView` 426 · `TextField` 403 · `WindowFrame` 352 · `Workbench`
316 · `Slider` 307 · `GraphView` 293. The leaf widgets are the best-covered code in the repository,
which is what makes starting with them safe rather than merely convenient.

### 0.6 The scenes, and what each constructs

| Scene | Constructs | Batch |
|---|---|---|
| `cgui-button` | `Button` | 6.1 |
| `cgui-checkbox` | `Checkbox`, `CheckboxGroup` | 6.1 |
| `cgui-switch`, `cgui-slider`, `cgui-text`, `cgui-text-stress`, `cgui-textfield` | one leaf each + `UIText` | 6.1 |
| `cgui-scroller` | `ScrollerView`, `UIText` | 6.1 |
| `cgui-nineslice`, `cgui-styling`, `cgui-visual-layers`, `cgui-test`, `cgui-svg-icon`, `cgui-ore-theme` | drawables, sprites, one or two leaves | 6.1 |
| `cgui-shadow-parts` | `Button`, `ShadowButton` | **still on disk** — 6.1 owed this deletion and did not pay it; it is 6.2's first commit |
| `cgui-splitview`, `cgui-tabview` | `SplitView`; `TabView` + `Button`, `Checkbox`, `Slider` | 6.2 |
| `cgui-gallery` | everything: `ColorSelector`, `ConfiguratorGroup/Panel`, `Dialog`, `DialogManager`, `Dropdown`, `GraphNode`, `GraphView`, `ListView`, `Menu`, … | the running total — green only when 6.4 lands |
| `cgui-new-gallery` | every ported widget at once, in one scroller | **built at 6.1** — new-engine only; grows with each batch |
| `cgui-completion` | `TextEditor` | 6.5 |
| ~~`cgui-slot`~~ | `ScrollerView`, `ItemSlot`, `FluidSlot` | **deleted at 6.1** — the branch was never reconciled and `cgui-new-gallery` covers slots through `ScrollerView`, `Menu` and `Button` |
| `cgui-dock` | `CrystalEditor`, the workspace | 6.7 |
| `cgui-workspace` | `ServerUiSession`, `ClientUiSession`, `TextEditor`, `TreeView`, `SplitView`, `TabView` | 6.8 |
| `cgui-engine-parity` | `UIDocument`, `UINode`, `TextNode` beside `UIText` | the pattern every batch copies |
| `cgui-desktop` (documented, not at the pointer) | `Desktop`, `WindowFrame`, the taskbar, the switcher | 6.6 |

---

## 1. Correcting the master plan's row first

Five things the M6 row states or implies that the measurement contradicts. Each would have been found
in the middle of a batch.

### 1.1 "Every `__part__` → `::part()`" is a classification, not a rewrite

§0.4's census: 401 rules select a part *under* a part and 99 reach through a part into a tag. A
`GraphNode`'s control rows hold `ConfigControl`s that hold `Checkbox`es; a `Taskbar`'s entries hold an
icon, a badge and an indicator; a `WindowFrame`'s caption holds an adopted `MenuBarView`. None of that
is "the private structure of one widget" in the sense a shadow tree encapsulates — it is **light-tree
structure that other widgets legitimately live inside**, and the web puts exactly that in the light
DOM with ordinary classes. Forcing it into shadow roots means `exportparts` chains three deep and a
theme that cannot reach a checkbox's mark inside a node's row without every level having re-exported
it.

So `__x__` is three different things, and D1 gives the rule for telling them apart:

| Kind | What it is | Count (estimate from the census) | Becomes |
|---|---|---|---|
| **A — a true part** | a piece of ONE widget, built in its constructor, never holding a caller's content: `Button`'s label and icon slots, `Slider`'s thumb, `Scroller`'s track, `Checkbox`'s mark, `WindowFrame`'s caption controls | ~180 names, ~350 rules | shadow tree + `part=`; `::part(name)` in sheets; `exportparts` where a widget inside a part must stay themeable |
| **B — light structure** | a container's own layout that holds other widgets or a caller's content: `graphnode`'s rows and columns, `taskbar`'s entries, `listview`'s rows, `workbench`'s regions, `dockgroup`'s pane host, `markupview`'s blocks | ~200 names, ~550 rules | stays a **class**, in the light tree, with the underscores dropped; descendant selectors unchanged in shape |
| **C — a state flag** | `__active__`, `__collapsed__`, `__hidden__`, `__open__`, `__dragging__`, `__on__`, `__off__`, `__selected__`, `__focused__`, `__maximized__`, `__pinned__` | ~110 names, ~250 rules | stays a class, on the host (`window.active`) or as a second ident on a part (`::part(entry active)`) — the row *"state a widget flips from its own listener belongs on a CLASS"* is unchanged |

The 496 bare-part rules split the same way: a bare `.__row__` is kind B (and gets a host scope in
the rewrite, which is the bug the row about `.__content__` recorded three times); a bare
`.__thumb__` is kind A and gains `slider::part(thumb)`. Every one of the 1,048 is classified by hand
in 6.0's ledger before any sheet is edited, because the census can suggest a kind and only reading
the widget can confirm it.

### 1.2 The scope is not `ui/elements`

Four packages outside it extend `UIElement` (§0.1: `graph/shader`, `language/…/run/view`,
`example/machine`, `editor/`), and **fourteen seams** outside the element layer are typed on it:

| Seam | Typed how | Port |
|---|---|---|
| `core/command` — `CommandContext(UIElement source)`, `CommandRegistry` | the command system's *source* | D12 |
| `core/data` — `DataContext.from(UIElement)`, walks `getParent()`; `DataProvider` | the context walk every command resolves through | D12 |
| `core/undo` — `UndoScope.nearest(UIElement)` | the undo stack walk | D12 |
| `ui/input/keymap` — `Keymap.acceleratorFor(UIElement)`, `KeymapResolver.resolve(UIElement focused, …)` | the whole keymap resolution walk | D12; `Input.Chords` already takes a `UINode` and a host fills it |
| `ui/contract` — `State<W extends UIElement, V>`, `Event<W extends UIElement, P>`, `WidgetContract<W extends UIElement>`, `WidgetContracts.of(UIElement)`, `RateGate` | **every one of the 87 contracts** | D11 — the bound loosens; nothing in a contract needs the element type |
| `ui/projection` — `AutoProjection`, `Projections` | reflection walk stops at `UIElement` | retype the stop class |
| `serialization` — `UIDescriptionCodec` (`Codec<UIElement>`, `encodeLive`, `decodeLive`), `InlineStyleCodec` | the description format | `UINodeMirror` already describes a `UINode`; the codec is retired at 6.8 |
| `net` — `ServerUiSession` (1,457 lines, `UIElement root`), `ClientUiSession` (903), `ElementNodeMirror` | **hard-typed** to `UIElement` while the mirrors under them are generic | D11 — generic `<N>`; on M6's critical path, see 1.3 |
| `net/window` — nine files | `Networked` panels `extends UIElement`, `UiType<P extends UIElement & Networked<M>>` | retype at 6.8 |
| `style` — `StyleProperty.notifyListeners(UIElement, …)`, `Listener.onComputedChange(UIElement, …)` | the property listener seam | retype to `Styleable` (both engines implement it) |
| `render` — `CgUiPaintContext.warmGlyphs(UIWindow.DEFAULT_UI_SCALE)` | one constant | trivial |
| `lifecycle` — `CgUiLifecycle.onDestroy → UIWindow.shutdownAll()` | the shutdown sweep | `UIDocument` needs the registry |
| `core/async/UiThread` | per-tree owner asked of the attached window | `UIDocument.require` already exists |
| `mc1710` — eight files (`CgUiScreen` 720 lines, `CgUiWindowMount`, `CgUiHud`, `CgUiInput`, `CgUiOverlayInput`, `Mc1710Workspace`, two probes) | `UIWindow.init/paint/presentation/desktop/enterHudMode/suspendDesktop/openWindow`, `getInputHandler().consume*` | the host flip at 6.9 |

### 1.3 Networking is on M6's critical path, not M7's

The master plan puts "networking completeness on the new engine" in M7. The **cutover** in M6 flips
`CgUiScreen`, and `CgUiScreen` mounts networked windows (`CgUiWindowMount.mount` builds a `WindowFrame`
around `context.root()`), the Machine example is a networked window, and `cgui-workspace` constructs
both sessions. A new-engine host with old-engine sessions has nothing to mount. So the sessions'
retype — generic over `TreeSource<N>` and `NodeMirror<N,T>`, which is what M2 built the mirrors for —
is 6.8, before the cutover, and M7 keeps what it was always about: virtualised collections on the
wire, workbench citizenship, the provisional `LocalOnly` markers.

### 1.4 The tests are a port of their own

164 test files construct a `UIWindow`; 127 drive frames through `updateWithoutPainting`/`paintFrame`;
102 read `getRuntimeCache()`. That fixture layer — `UiTestBase`, `EditorTestBase`, the input stubs,
`allWithClass`/`renderedLines`/`countOf` — is what the 2,454 tests are written against, and every test
that follows a widget onto the new engine moves with it. §2.3 builds the twin fixture once; each batch
then re-points its tests as a mechanical step. **Tests that assert structure die**: `getChildren()`
over internal children (56 files read children; 6 use the internal API) will see a shadow root where
they saw a list, and the ones that were asserting "the third child is the label" are asserting a
mechanism the port removes. The memory rule applies — test the spine; delete edge tests that break on
a legitimate redesign.

### 1.5 Thirty-two tags are unregistered and match by accident

The UA sheets and `graph.css`/`ore.css` name **55 tags**. `ElementRegistry.bootstrapBuiltins`
registers 23. The other 32 — `texteditor`, `graphnode`, `graphview`, `listview`, `treeview`,
`tableview`, `workbench`, `dockarea`, `dockgroup`, `viewcontainer`, `projectfiletree`, `problemspanel`,
`quickpick`, `navigatorview`, `markupview`, `completionpopup`, `documentationpopup`, `statusbarview`,
`notificationsview`, `breadcrumbs`, `pagestack`, `searchfield`, `canvasview`, `nodeport`,
`nodecreationmenu`, `crystaleditor`, `runpanel`, `shadergrapheditor`, `shadergraphinspector`,
`shadergraphsettingspanel`, `shadernodeinspector`, and the dead `floatingdock` — match through
`tagName()`'s **lowercased-class-name fallback**. The porting guide's *"⚠ `Name` must be declared and
REGISTERED"* therefore applies to 32 more classes than the guide's example suggests, and a port that
registers only the 23 turns thirty-two widgets unstyled in one commit. 6.0's sheet checker fails on
any tag a sheet names that no `UINodeRegistry` entry answers.

---

## 2. What has to exist before the first widget moves — the machinery (6.0)

Everything M5 deliberately left for M6, plus everything the census found the widgets reaching for that
`UINode`, `Box`, `UIDocument` and the services do not yet offer. **None of this is a widget**; all of it
is what a widget port would otherwise have to invent on the spot, and a thing invented at widget #12
is a thing widgets #1–11 did differently.

### 2.1 Service halves M5 named as not ported

| Missing | Old engine | New home | Notes |
|---|---|---|---|
| **Close watchers** | `UIWindow.pushCloseWatcher/popCloseWatcher/getTopCloseWatcher`, `UIElement.requestClose()` | `ui.service.Dismiss` + `UINode.requestClose()` (D7) | the cascade order is load-bearing and stays: live modes (drag, switcher, keyboard move) → the **active window's** watchers → the document's. A frame registers as its own last watcher |
| **Light dismiss** | `UIWindow.lightDismiss(target, shownBefore)`, `pushAutoPopover`, `popoverShowSeq`, `Popover.getLastShownSeq`, `UIElement.popoverInvoker` | `Dismiss` | after the mouse-down dispatch; considers the popovers open **before** the dispatch (the counter, not a snapshot); the invoker counts as part of its popover; `UINode` gets `popoverInvoker` |
| **`Dialog.pulse` on a blocked press** | `UIWindow.modalBlockingAt` → `Dialog.pulse()` | `Focus.blockingModal` already answers per scope; the pulse is `Dialog`'s | ask the hit's own scope — one modal makes every lookup look correct |
| **The drag ghost** | `UIDragController.setGhost(element, anchor)`, promoted per drag, dropped at drag end | `Drag` gains `ghost(UINode, Anchor)`; the ghost's box is hosted in the top layer for the drag's life | the three rules in `DragGhost`'s javadoc survive; rule 2 (write out-of-flow at IMPORTANT) becomes an attribute + UA rule |
| **`TransitionEngine` as the motion service's client** | its own `System.nanoTime()` clock | `Animation` hands transitions the frame delta | what makes a transition steppable in a test for the first time |
| **`scrollExempt` in the painter** | `UIElement.setScrollExempt` — a child that does not move with its parent's scroll (scrollbars, gutters, find bars, the editor's viewport layers; 11 files) | `Attribute.SCROLL_EXEMPT`, read by `BoxTree.compose` when composing the child's matrix | the 5.4 gap; a scrollbar scrolls away with its content until it is closed |

### 2.2 Gaps the census found

| Gap | Sites | What to add |
|---|---|---|
| pre-bound event groups on `UINode` | 82 | `onMouseDown` … `onBlur` fields, exactly as `UIElement` declares them — a field per group over `events` |
| `UINode` query API | 10 in widgets, 29 in tests | `querySelector` / `querySelectorAll` / `getElementById` / `getElementsByClassName` / `find` / `require` over the **light** tree; the selector engine already matches `Styleable`, so this is `UITreeTraversal` retyped |
| scroll extents on `Box` | 69 | `scrollWidth()`, `scrollHeight()`, `clientWidth()`, `clientHeight()`, `maxScrollLeft()`, `maxScrollTop()`; `ListView` and `TextEditor` **override** `getScrollHeight` to answer from a model — so the extent is a `UINode` question the box asks (`UINode.scrollExtent()`), not a box field |
| smooth scroll | `scroll-behavior: smooth`, `scroll-duration`, `UIWindow.tickScrollAnimations` | `UINode.scrollTo(left, top)` honouring the behaviour through an `Animation` timeline; `Box.setScroll` stays immediate; `scrollIntoView` stays immediate (D9). The exponential ease is frame-rate independent and retargets mid-flight — port the formula |
| `Attribute.HIDDEN` | 74 `setDisplayed` sites | plus `[hidden] { display: none }` in `core.css` — HTML's own answer, and what removes the largest single family of IMPORTANT writes (D5) |
| `Box.NONE` | 185 `getRuntimeCache()` reads, some before the first layout | a zero box a node answers before it has been laid out, so `box().width()` on a fresh node reads 0 rather than throwing — the same shape `UIWindow.EMPTY_LAYOUT` has today |
| `UINode.setOnlyChild`, `toggleClass`-pair for `swapPrefixedClass`, `attachDefaultListener` | 15 + 18 + 9 | trivial helpers; add them once |
| `UINode.registerCommands(CommandRegistry)` / `bindKeys()` | 5 | the same two hooks, **called from `connected()` on the first attach rather than from the instance initialiser**, which removes the row *"`registerCommands` runs from `UIElement`'s INSTANCE INITIALISER, so a per-instance registration placed there passes `null`"* |
| `UINode.keymap()` / `settings()` / `getData(DataKey)` | 4 + 5 + 92 | the accessors; the walks are D12 |
| reported events per instance | the M2 note *"still a field on the element only because the encoder … is a context-free `Codec<UIElement>`"* | `Attribute.REPORTS` (a `Set<String>`), carried by `UINodeMirror` like any attribute; the contract stays the answer to "can this report" |
| `UIDocument.overlayHost(near)` / `addOverlay` / a window-owned overlay layer | 15 | D8 — the algorithm ported verbatim, including the frame's overlay slot first |
| `UIDocument.desktop()` / `desktopIfPresent()` / `suspendDesktop` / `resumeDesktop` / `enterHudMode` / `exitHudMode` / `screenOverlay()` / `presentation(…)` / `paint(presentation, w, h)` / `openWindow` / `openWindowInBackground` / `hasPinnedWindows` / `isHudMode` / `addDataProvider` / `shutdownAll` / `sessionState` | the host surface | the band model as document-level hosts (D16); ported at 6.6 but the **hooks** exist at 6.0 so 6.1–6.5 can be accepted without a desktop |
| `UIDocument.setUiScale` and root centring | `UIWindow.getRootTransform`, `resolveRootAvailableSpace`, the centring offset (`RootPlacementTest`) | `BoxTree.setRootTransform` exists; the percentage-root / content-sized-root-centred rule is `UIDocument.layout`'s (D21) |
| `exportparts` | 401 nested part rules, the fraction that stays kind A | the selector engine gains it beside `::part` (D3); indexed under the host's keys exactly as `::part` is |
| `UINode.paintChildren` override | 3 overriders (`TextField`, `WindowFrame`, `WindowThumbnail`) | **no counterpart on purpose** — each of the three is read in §4.4 and has a different answer |

### 2.3 The test fixture twin

`UiTestBase` gains a `UIDocument`-backed sibling with the **same verbs**: build, layout, frame, move,
press, release, wheel, key, `at`, `allWithClass`, `renderedLines`, `countOf`, the clipboard and modifier
stubs, animations-off. `ServiceFixtures` (5.5) is the seed. A test moves engines by changing its base
class and its element types, and nothing else — which is the only shape under which 214 files can be
re-pointed batch by batch without each becoming a rewrite. Where the old base drove input through
`consumeMouseEvent` at a point (the row *"`sendInputEvent` cannot see any of this"*), the twin does the
same through `document.input().consumeMouseEvent`.

### 2.4 The governance twins

| Old | New-engine twin | What it fails on |
|---|---|---|
| `EngineBoundaryTest` | itself, **inverted per batch**: a ported class moves from the OLD list to the NEW list, and a NEW class naming any OLD class fails | a widget reaching back |
| `WidgetContractCoverageTest` (walks `UIElement` subclasses) | walks `UINode` subclasses too, then only them | a ported widget whose contract was dropped |
| `ElementStateCoverageTest` (23 tags) | `UINodeRegistry.names()` — **every** registered name answers the state question | a registered kind nobody classified |
| `ElementRegistryTest` | `UINodeRegistryTest` (bijective, no-arg factory, contract per name) | the `Button::new` trap the paper port found |
| — | **`SheetPortTest`**: every `__x__` still referenced by any sheet is in the ledger as A, B or C with the widget that owns it; every tag a sheet names is a registered `Name`; no rule contains `::part(a)::part(b)` | the 401 and the 32 |
| — | **`PortLedgerTest`**: a checked-in `port-ledger.txt` of `class → pending / ported / deleted`; a class extending `UIElement` listed as ported, or extending `UINode` listed as pending, fails | "how far are we" as a query |
| `cgui-engine-parity` (one hard-coded tree) | takes a **spec** — a scene supplies a builder for each engine and a stylesheet; adding a widget to the PNG diff is ~15 lines | the visual net every batch runs |

### 2.5 The ledger

A file, `port-ledger.txt`, checked in at 6.0 and read by `PortLedgerTest` and `SheetPortTest`: one row
per class (303) — status, **destination package** (§2.6) — and one per part name (497) with its kind
and its owner. It is written **before**
6.1, in full, from Appendix A and the census — which is the point: the classification is done once,
reviewed once, and every later batch executes it rather than re-deciding it.

### 2.6 The package map — where each copy lands

The strangler line forces this: the old `ui.elements.Button` runs the game until 6.9, so the new one
cannot share its package. Every ported class is therefore a **copy into a new package**, and since
the tree is being re-homed anyway it is re-homed properly — by *kind of thing* first and by *layer*
second. `ui.elements` had 28 files at its root spanning a `Button` and a `MarkupView`; `desktop`,
`workbench`, `editor` and `chrome` were flat at 24–38 files each.

```
com.crystalgui.ui                     THE ENGINE, unchanged: dom, box, service, event, input (+keymap), text, contract, projection
  .box                                + AnchoredPlacement (geometry over boxes; every overlay and the taskbar use it)

com.crystalgui.widget                 THE LIBRARY: general-purpose, knows nothing above it
  .control       Button, Checkbox, CheckboxGroup, Switch, Slider, ProgressBar, TextField, SearchField, Dropdown, ColorSelector, SymbolIcon
  .text          UIText (D15), MarkupView
  .scroll        Scroller, ScrollerView
  .layout        SplitView, TabView, Tab, PageStack
  .overlay       Popover, Menu, MenuItem, Tooltip, Dialog, DialogManager, InputDialog, ContextMenu, MenuBuilder
  .dnd           DragGhost, InsertionMarker
  .collection    .list (ListView, strategies, ListRenderer) · .tree (TreeView, TreeSearch, sources, TreeRenderer, TreeRow) · .table (TableView, TableColumn, TableCellRenderer, SortOrder)
  .form          ConfigControl, ValueControl, Configurator, ConfiguratorGroup, ConfiguratorPanel, SettingsConfigurator, descriptors
                 .field (the thirteen controls) · .inspector (Inspector, InspectorForm, InspectorRegistry, InspectorSection)
  .canvas        CanvasView, CanvasOverlayMove, WorldRect
  .graph         GraphView, GraphNode, NodePort, NodeWireLayer, PortDefaultEditor, NodeCreationMenu, GraphSelection, GraphCommands, port types, field binder/widgets
  .editor        TextEditor, EditorCommands, EditorFolding, EditorDiagnostics, EditorLanguageFeatures, DiagnosticActions, DiffDecorations
                 .view (EditorViewPart, the ten parts, DecorationPool) · .suggest (CompletionPopup, CompletionSession, CompletionRanking, CompletionRecency, EditorSuggest)
                 .doc (DocumentationPopup, HoverDocumentation) · .find (SearchReplaceBar, EditorFind)

com.crystalgui.chrome                 THE SHELL'S OWN WIDGETS: may use widget; may not use desktop or workbench
  .menu          MenuBarView, MainMenuCommands, ChromeCommands, Breadcrumbs
  .palette       CommandPalette, QuickPick, QuickPickItem, QuickPickEntry, QuickPickSource
  .status        StatusBarView, ProgressStatusItem, ProcessesPopover
  .notification  NotificationsView, NotificationCard, NotificationBalloons
  .problems      ProblemsPanel, ProblemNode, ProblemsCommands, ProblemsTreeSource
  .preferences   Preferences, NavigatorView

com.crystalgui.desktop                CRYSTALOS: may use widget and chrome
  (root)         Desktop, DesktopCommands, DesktopPresentation, DesktopSession, ScreenOverlay, WindowRegistry
  .window        WindowFrame, WindowState, WindowPolicy, WindowChrome, WindowIcon, WindowCommands, SystemMenu, WindowMove, WindowKeyboardMove, SnapZones
  .motion        WindowAnimator, WindowAnimation, WindowGeometryAnimation, WindowMotion
  .taskbar       Taskbar, TaskbarEntryMotion, TaskbarPreviews, TaskbarDesigner, WindowPreview, WindowThumbnail, WindowSnapshot
  .switcher      WindowSwitcher

com.crystalgui.workbench              THE PROJECT EDITOR: may use everything above
  (root)         Workbench, WorkbenchRegions, WorkbenchSession, WorkbenchSettings, WorkbenchMenus, RegionHost, RegionDropOverlay, RegionDropZones, SplitFill
  .dock          the 29, as they are
  .toolwindow    ToolWindowFrame, ToolWindowManager, ToolWindowState, ToolWindowLayout, ToolWindowType, ViewContainer, ViewContainerRegistry, StripeView, StripeRail
  .explorer      ProjectFileTree, FilesRenderer, ExplorerDragAndDrop, ExplorerEditing, ExplorerFind, ExplorerCommands, ExplorerClipboard, WorkspaceTreeSource, GoToFile, ProjectIndex, RecentFiles, QueryLocation
  .document      OpenDocuments, FileDocument, TextFileDocument, DocumentType, DocumentViewState, HeaderContributor
  .diff          DiffView, MergeView, ConflictDialog
  .decoration    as it is

com.crystalgui.graph                  THE GRAPH MODEL: headless, engine-neutral, named by BOTH engines
  (root)         GraphDocument, NodeType, NodeData, EdgeData, PortSpec, codecs, edits -- not in port scope
  .port          PortType, BasicPortType, PortTypeRegistry   (6.4 D25: the SPI, once its one
                 UIElement-returning method becomes a lookup in widget.graph)
  .shader        ShaderGraphBridge, ShaderPropertyForm, ShaderGraphSettings -- the shader MODEL, which
                 is what is left of `graph.shader` once its fourteen widgets leave

com.crystalgui.app                    THE APPLICATIONS: may use everything above, and nothing may use them
  .shadergraph   (root) ShaderInspectorSections, ShaderNodeLibrary, ShaderGraphEditor (6.7),
                 ShaderGraphContribution (6.7) · .blackboard · .preview · .field
  .editor        CrystalEditor, CrystalEditorCommands            (6.7)
  .machine       the Machine example                             (6.7)

com.crystalgui.language.run.view      as it is
```

> **`graph.shader` was an application inside a model package.** Seventeen files in one flat directory
> holding an editor, a properties panel, three previews, two field widgets, a compile bridge, a
> settings declaration, five inspector sections and a rename box — and importing `ui.elements.dock`
> and `ui.elements.workbench`, which a model package cannot. 6.4 splits the model out (three files
> stay) and the application into `app.shadergraph` with three sub-packages. `com.crystalgui.editor`
> and `com.crystalgui.example.machine` join it at 6.7, which is what turns *applications* from a word
> in the layering rule into one `LayeringTest` prefix.

**The layering rule**: engine < widget < chrome < desktop < workbench < applications, and inside
`widget`: `control`/`text`/`scroll` at the bottom, `overlay`/`layout`/`dnd` above them,
`collection`/`form`/`canvas`/`graph`/`editor` above those. Nothing references upward. `ui.*` means
the engine and nothing else, which is why the library sits beside it rather than under it.

**Copied or moved.** A class that extends `UIElement`, or reaches the old engine's API, is **copied**
by the codemod (§2.7) and the old file stays until 6.9. A class that is engine-neutral — the dock
model, the tree sources, `DragScrub`, the descriptors, the commands once D12 has retyped their
context — is **moved** in the IDE, whose Move refactor fixes both engines' imports at no cost.

### 2.7 The codemod — the port is copied and transformed, never written

The census is exact enough to script. `tools/port/codemod.py --batch 6.N` copies each of the
batch's files to its destination package, applies the transformations below, and prints what is left
as `file:line kind` — the reading list for that batch. It is written once at 6.0, tested on 6.1
(the best-covered code in the repository), and run per batch by whoever is porting.

| Transformation | Sites | Mechanical? |
|---|---|---|
| `extends UIElement` → `extends UINode`; `UIWindow` → `UIDocument` in signatures; imports | 87 classes | yes |
| `getRuntimeCache().getX/getY/getWidth/getHeight()` → `box().x/y/width/height()` | 127 | yes |
| `getAttachedWindow()` → `document()`; `.getInputHandler().requestFocus/requestPointerFocus/getFocusedElement/pointerPosition/onDidChangeFocus/blurIfFocused/setPointerCapture` → `.focus()…` / `.input()…` | 194 + 106 | yes |
| `getDragController().startDrag(…)` → `Drag.start(…)`; `isDragging/isActivated/isDropAccepted/setGhost` → the `Drag` accessors | 30 | yes |
| `addInternalChild(x)` whose `x` carries `addClass("__p__")` → `shadow().append(x)` + `x.set(Attribute.PART, "p")` for kind A, `append(x)` + `x.addClass("p")` for kind B — **driven by the ledger** | 210 | yes, per kind |
| `markAsInternal()` and `acceptsPublicChildren()` overrides deleted | 65 + 64 | yes |
| `"__x__"` constants → part names or plain class names per kind | 497 | yes |
| `registerTicker(this)` + `implements UIFrameTicker` → `document().animation().every(this, this::tickFrame)` | 52 | yes |
| `addToTopLayer/removeFromTopLayer/isInTopLayer` → `box().setHost(…)` | 23 | yes |
| `pushCloseWatcher/popCloseWatcher/pushAutoPopover/popAutoPopover/lightDismiss/popoverShowSeq` → `document().dismiss()…` | 41 | yes |
| `setScrollExempt(b)` → `set(Attribute.SCROLL_EXEMPT, b)` | 16 | yes |
| `onWindowChanged(prev, cur)` → `connected()` / `disconnected()` with the body split on the null test | 12 | yes |
| tests: base class → the twin, `new UIWindow(Ui.of(root))` → the fixture, the reads above | 164 files | yes |
| sheets: `.__x__` → `host::part(x)` / `.x` per kind, `exportparts` where the ledger says a nested part is exported | 1,048 selectors | yes, per kind |
| **`importantPipeline`** — a `Measurable`, a box override, an INLINE write or a class, per §4.5 | **117** | no |
| **`screenToLocal` / `containsScreenPoint`** — the origin subtraction changes (§4.4) | **57** | no |
| **`stopPropagation()`** — read for the pre-empt case; most stay | **80** | read |
| **`insertInternalChildAt` / `removeInternalChild`** — dynamic restructure | **40** | no |
| **the resize hooks** | **43** | no |
| **`onLayoutChanged` bodies** | **23** | no |
| **paint overrides** | **14** | no |
| **`ctx.mirroring` / `WindowSnapshot`** | **11** | no |

**`UINode` keeps every surviving name.** `setEnabled`, `setHitTest`, `setInert`, `setDisplayed`,
`addClass`/`removeClass`/`hasClass`/`swapPrefixedClass`, `requestClose`, `scrollIntoView`,
`setScrollTop`/`setScrollLeft`/`getScrollWidth`/`getScrollHeight`, `querySelector`/`find`/`require`,
the sixteen event fields, `attachDefaultListener`, `keymap()`, `settings()`, `getData`,
`isChecked`/`isBlank`/`isInvalid`, `consumesTextInput`, `setFocusPolicy`, `registerCommands`,
`bindKeys`, `tickFrame` — each is the right API *and* a hundred sites the script never touches. The
attribute is the storage; the method is the door.

**The budget, as the tool measures it** rather than as the census estimated it. Across 6.1–6.8:
**189 files copied, 100 moved, 3,249 mechanical rewrites and 432 hand sites** — per batch 35 to 85.
The residual is edited with targeted replacements, never by rewriting a file, and never by re-reading
a file the codemod has already located the line in. Written from scratch: 6.0's machinery and nothing
else. The one honest exception is 6.5's D22, budgeted as XL for that reason and not for
`TextEditor`'s length.

| Batch | copied | moved | mechanical | hand sites | |
|---|---|---|---|---|---|
| 6.1 | 18 | 0 | 364 | 80 | *(shipped: 22 classes, the four overlays pulled forward)* |
| 6.2 | 25 | 10 | 390 | 35 | *(shipped: 33 classes; 6 deferred to 6.3 on the command seam)* |
| 6.3 | 27 | 17 | 601 | 58 | *(re-measured after those 6 arrived)* |
| 6.4 | 26 | 9 | 445 | 85 | |
| 6.5 | 26 | 8 | 394 | 77 | |
| 6.6 | 19 | 8 | 409 | 49 | |
| 6.7 | 44 | 39 | 566 | 35 | |
| 6.8 | 9 | 6 | 91 | 0 | |

The estimate the census produced was 443 hand sites; the tool found 432, and the per-batch split moved
— 6.1 is 80 rather than 60 (the census did not count `getTaffyLayout` reads or internal children as
readings) and 6.7 is 35 rather than 38. **6.2's 47 became 35 when the four overlay widgets left the
batch for 6.1**, which is the number moving for the right reason: the work went with them. All of it
is close enough to have been worth estimating, and all of it is the reason the numbers are now
generated rather than written down: an estimate is a thing to check, and a check that is a command is
one nobody has to remember to run.

### 2.8 `LayeringTest`

A constant-pool scan beside `EngineBoundaryTest` and `RunShellIsEngineNeutralTest`: every class under
`widget.control`/`.text`/`.scroll` names nothing in the packages above it; `widget.*` names nothing in
`chrome`, `desktop` or `workbench`; `chrome` names no `desktop` or `workbench`; `desktop` names no
`workbench`. Written at 6.0, green trivially, and what stops a `Button` learning about a
`WindowFrame` again.

---

## 3. Ground rules for every batch

1. **The strangler line holds until 6.9.** `EngineBoundaryTest` runs on every commit and its two lists
   move per batch. A widget is either wholly on one engine or wholly on the other.
2. **No adapter.** A `UINode` is never hosted under a `UIElement` and a `UIElement` never under a
   `UINode`. The temptation arrives at the first composite whose leaves are ported and whose container is
   not; the answer is that the unit of work is a closed tree (§5), not a widget.
3. **The widget's public API survives the port.** Signals, `Property` bindings, accessors, `CONTRACT`
   constants, `DataKey`s, command ids, class-name constants for kinds B and C. Callers — the workbench,
   the shader graph, `RunPanel`, the tests — compile against the same names. The porting guide's own
   finding, and what makes each batch reviewable widget by widget.
4. **Nothing writes at IMPORTANT.** `EngineBoundaryTest` already reads the constant pool for
   `StyleOrigin.IMPORTANT` and `importantPipeline`. §4.5 gives every one of the 117 sites its answer.
5. **A batch is accepted on its scene(s) and its tests, on the new engine**, with the parity PNG
   within tolerance of the old engine's — and the old engine still green, because the game runs on it.
6. **Port, don't reinvent — and don't improve.** A port that fixes a behaviour is two changes in one
   commit, and the second is unattributable. Behaviour changes go in their own commits after the
   batch, with the invariant row they touch named.
7. **Each batch closes with the invariant rows it owns marked or rewritten** (§5 names them), the
   porting guide amended with what broke it, and `plan_m6.md`'s status row updated the way
   `plan_m5.md`'s were.
8. The memory rules: no worktrees, no inline FQNs, don't commit until asked, test the spine, never
   run the unfiltered suite.
9. **Copied and transformed, never written.** Every ported file is the codemod's copy of the old one
   (§2.7); the diff is the port. No ported file is written whole, and the only new code is 6.0's.
10. **The census is the reading list.** The codemod prints the residual as `file:line kind`; those
    sites are edited in place and nothing else in the file is read unless the site needs it. The
    per-batch budget line in §5 is the number to hold each batch to.
11. **`UINode` keeps the surviving names** (§2.7). A port that renames a method that still means the
    same thing has made a hundred sites cost tokens for nothing.

---

## 4. The inventory — every feature of the old engine, and where it goes

This is the section the request was for. It is organised by where a feature *lives* today, and for
each row says what it becomes, or names the gap and the decision (§4.6, §2).

### 4.1 `UIElement` — 166 members

| Concern | Old | New | Notes |
|---|---|---|---|
| **Tree** | `addChild`, `addChildAt`, `addChildren`, `removeChild`, `removeSelf`, `clearAllChildren`, `hasChild`, `getSiblingIndex`, `setOnlyChild` | `append`, `insertAt`, `remove`, `removeSelf`, `removeAll`, `contains`, `indexOf`, `moveTo`; `setOnlyChild` added | `removeChild` **refusing an internal child** and `clearAllChildren` **skipping** them have no counterpart: a shadow child is not in `children()` at all. `MarkupView`'s note ("blocks are PUBLIC children because `clearAllChildren` skips internal ones") is exactly a kind-B case |
| **Internal children** | `markAsInternal`, `addInternalChild`, `insertInternalChildAt`, `removeInternalChild`, `isInternalUI`, `describedChildren`, `acceptsPublicChildren`, `acceptsDescribedChildren` | `attachShadow()`, `shadowRoot().append/insertAt/remove`; light children ARE the described children; `NodeContract.acceptsDescribedChildren` | the recursion trap, the re-add trap and the selector-subject trap all go — three rows marked at 5.6 |
| **Slots** | `Tab.content()`, `SplitView.first()/second()`, `ScrollerView` "your children are direct children" | `UISlot`, `UISlot.of(node)`, `assignedSlot()` | a composite that accepted public children keeps doing so through a default slot |
| **Identity** | `setId`, `addClass`, `removeClass`, `hasClass`, `swapPrefixedClass`, `removeClassWithoutRematchingSubtree`, `tagName()` (exact-class lookup + lowercase fallback) | `setId`, `addClass`, `removeClass`, `toggleClass`, `hasClass`, `classes()`, `name()`, `tagName()` from the `Name` | **the fallback is gone** — §1.5 |
| **State flags** | `setEnabled`, `setPressed`, `setFocused(_, visible)`, `setHovered`, `isFocusWithin`, `isChecked`/`isBlank`/`isInvalid` (overridable), `consumesTextInput` | `set(Attribute.ENABLED)`, `setPressed`, `setFocused(_, visible)`, `setHovered`, `setFocusWithin`, `setFocusVisible`, the same three overridable getters, `consumesTextInput`, `claimsChord` | `isFocusWithin` is written by `Focus` now rather than derived |
| **Focus** | `setFocusPolicy`, `focusable()`, `tabbable()`, `invalidateFocusableChain`, `requestPointerFocus` | `setFocusPolicy`/`focusPolicy()` (an attribute), `focus().focusable(node)`/`tabbable(node)`, `focus().requestPointerFocus` | `hasFocusableDescendant` cache has no counterpart; `Focus.order(scope)` walks the composed subtree — **measure on the gallery before assuming it is free** |
| **Hit testing** | `setHitTest`, `isHitTest`, `containsScreenPoint`, `screenToLocal` | `set(Attribute.HIT_TEST)`, `box().hitTest(x, y)`, `box().worldToLocal()` | `screenToLocal` answered an ABSOLUTE layout coordinate (the row *"a drag callback's coordinates are ALREADY CONVERTED"*); `worldToLocal` answers the box's OWN space with origin at its top-left — **every one of the 57 sites changes meaning by the box's origin** (§4.4) |
| **Inertness** | `setInert`, `isInertAttribute`, `isInert()` (attribute OR modal-blocked) | `set(Attribute.INERT)`, `focus().isInert(node)` | one predicate |
| **Close** | `requestClose()` (close-watcher hook, returns *handled*) | `UINode.requestClose()` via `Dismiss` (D7) | the row about `Networked.mayClose` colliding with this name stands — check the name |
| **Transform** | `getTransform`, `setTransform`, `invalidatePoseCachesRecursively` | the cascaded `transform` property, or `box().setTransform` for a compositor override | `CanvasView` (zoom) and `WindowAnimation` are the two writers; both become box overrides driven by `Animation` |
| **User sizing / `resize:`** | `isUserSizedWidth/Height`, `clearUserSizing`, `USER_SIZED_*_CLASS`, `RESIZER_CLASS`, `onResizeModeChanged`, `canMoveResizeOrigin`, `resizeContainingBlock`, `onPositionModeChanged`, `resizeOriginLeft/Top`, `applyResizeOrigin`, `onUserResize` | D6 | seven overriders (`Dialog`, `Popover`, `CanvasOverlayMove`, `QuickPick`, `WindowFrame`, `NodeCreationMenu`, `NodePort`) |
| **Keymap / settings / commands** | `keymap()`, `keymapOrNull()`, `settings()`, `settingsOrNull()`, `settingsParent()`, `registerCommands`, `bindKeys` | same accessors on `UINode`; hooks from `connected()` | D12 |
| **Top layer** | `isInTopLayer`, `addToTopLayer`, `removeFromTopLayer` | `box().setHost(document.topLayer())` / `setHost(null)` | insertion-order stacking in the top layer is the HOST's child order; `z-index` irrelevant there (per spec) — `Box.children()` sorts by z, so the top-layer host box **must ignore z** (a gap: `Box.setHost` needs a host that orders by insertion) |
| **Scrolling** | `setScrollExempt`, `isScrollContainer`, `allowsUserScrolling`, `getScrollWidth/Height`, `getClientWidth/Height`, `getMaxScrollLeft/Top`, `setScrollLeft/Top/Scroll`, `setScrollImmediate`, `clampScroll`, `scrollIntoView`, `targetScrollLeft/Top` | `UINode.scrollLeft/Top/setScrollOffsets`, `Box.setScroll`, `Box.scrollIntoView` + §2.2's extents and smooth scroll | `allowsUserScrolling` (wheel opts in only for a scroll VIEW — `ListView`'s note) is a `UINode` predicate the wheel default consults |
| **Querying** | `querySelector`, `querySelectorAll`, `getElementById`, `getElementsByClassName`, `find`, `require` | §2.2 | light tree only, as on the web |
| **Events** | `events`, sixteen pre-bound groups, `attachDefaultListener` | `events`, the same groups (§2.2) | `stopPropagation` semantics differ — §4.4 |
| **Style** | `style(…)`, `layout(…)`, `generalStyle(…)`, `moveInlineAsDefault`, `onStyleChanged`, `invalidateStateMatch`, `invalidateStyleMatch`, `shadowHost`, `partName`, `styleEngine`, `computedChanged`, `setHasFontRelativeStyles`, `invalidateFontRelativeStyles` | `getStyle()`, `computedStyle()`, `onStyleChanged`, `invalidateStyleMatch`, `shadowHost`, `partName`, `styleEngine`, `computedChanged`, `setHasFontRelativeStyles` | `style(…)`/`layout(…)` fluent writers at INLINE are legitimate author writes and stay; `moveInlineAsDefault` has no callers in the widget layer |
| **Layout hooks** | `initScreen`, `clearLayoutCache`, `onLayoutChanged(boolean)`, `onLayoutChanged()`, `getTaffyLayout`, `getWindowX/Y`, `getLayoutX/Y`, `measureFunc`, `getTaffyTree`, `markTreeDirty` | `Measurable.measure(Constraints)`, a post-layout callback on `UINode` (needed: 22 overriders, §4.4), `box().x()/y()/worldX()/worldY()`, `markTreeDirty` | the two coordinate chains collapse into `Box.localToWorld`; `getWindowX/Y` (3 files) and `localToWorld` (6) both become `worldX/Y` |
| **Painting** | `drawSubtree` (final), `paintSelf`, `paintOverlay`, `paintOutline`, `paintChildren` | `paintContent`, `paintDecoration`; the box model is `BoxPainter`'s | §4.4 for the seven overriders |
| **Serialization** | `writeState`/`readState`, `writeStateTo`/`readStateFrom`, `describedChildrenFor`, `addDescribedChildFrom/At`, `moveDescribedChildTo`, `clearDescribedChildrenFor`, `getReportableEvents`, `addReportedEvent`, `getReportedEvents`, `getDomObserver`/`setDomObserver`, `notifyStateChanged`, `notifyInlineStyleChanged` | the contract (state), `UINodeTreeSource` + `UIDocument`'s observer (structure), `Attribute.REPORTS` | `moveDescribedChildTo` — the `move` op — is `UINode.moveTo` |
| **Window** | `getAttachedWindow`, `setAttachedWindow`, `onWindowChanged` | `document()`, `connected()`/`disconnected()` | the 12 `onWindowChanged`/`onAttached`/`onDetached` overriders become `connected`/`disconnected`; **the row *"an attach is not a moment to build things in"* is now structural** — `UIDocument` queues lifecycle callbacks until the mutation ends |
| **`RuntimeCache`** | `sortedChildren`, `localToWorld`, `worldToLocal`, `depth`, `hasFocusableDescendant`, `getX/Y/Width/Height`, `resetPoseCache` | `Box` | the user's earlier question — the reasoning stands: a box is rebuilt from a one-pass layout, so there is no dirty flag to memoise against |

### 4.2 `UIWindow` — 67 members

| Concern | Old | New |
|---|---|---|
| lifecycle | `init(w,h)`, `shutdown`, `shutdownAll`, `frameThread`, `updateWithoutPainting`, `paintFrame`, `paint(presentation, w, h)` | `UIDocument.layout/update/frame/paint`, `markFrameThread`, `require`; `shutdownAll` and the per-document registry are a gap (§2.2) |
| registration | `registerElement`, `unregisterElement`, `getElements` | `connected`/`disconnected`, `allNodes()` |
| layout | `isLayoutDirty`, `MAX_LAYOUT_PASSES`, the settle loop | one pass — `BoxTree.layoutPasses()` is the acceptance metric |
| scale | `getRootTransform`, `setUiScale`, `DEFAULT_UI_SCALE = 2f`, root centring | `BoxTree.setRootTransform`; D21 |
| motion | `registerTicker`, `tickAnimations`, `tickScrollAnimations`, `registerScrollAnimation` | `Animation.every`, `tick`; smooth scroll §2.2 |
| queries | `querySelector*`, `getElementById`, `getElementsByClassName` (root included) | `UIDocument.getElementById` exists; the rest §2.2 |
| hit test | `getHoveredElement`, `overlayHitTest`, `modalBlockingAt` | `Input.hoverTarget`, `BoxTree.hitTest`, `Focus.blockingModal` |
| modality | `getActiveModal`, `getActiveModal(scope)`, `modalScopeOf`, `isModalBlocked`, `pushModal`, `popModal` | `Focus.modals/scopeOf/isInert/pushModal/popModal` |
| close watchers, popovers, key routing | `pushCloseWatcher`, `popCloseWatcher`, `getTopCloseWatcher`, `routeKeyToWindowSwitcher`, `routeKeyToKeyboardMove`, `getAutoPopovers`, `pushAutoPopover`, `popAutoPopover`, `lightDismiss` ×2, `popoverShowSeq`, `nextPopoverShowSeq` | `Dismiss` (D7); the two key routes are `InputMode`s the desktop pushes |
| overlays | `overlayHost`, `addOverlay`, `windowOverlayLayer` | D8 |
| desktop and host | `desktop`, `desktopIfPresent`, `suspendDesktop`, `resumeDesktop`, `isDesktopSuspended`, `onDesktopSuspendedChanged`, `enterHudMode`, `exitHudMode`, `isHudMode`, `screenOverlay`, `hasPinnedWindows`, `presentation`, `openWindow`, `openWindowInBackground`, `getDataProviders`, `addDataProvider`, `removeDataProvider`, `sessionState` | D16; ported at 6.6 |

### 4.3 The input layer, the top layer, the resizer, placement, traversal, keymap

| Old | New | What must not be lost |
|---|---|---|
| `UIInputHandler` — `beginFrame`/`endFrame`, `forgetElement`, `clearHoverIfHovered`, `sendInputEvent`, `pointerPosition`, `currentCursor`, pointer capture ×4, `consumeKeyboardEvent`, `consumeMouseEvent`, `requestFocus`, `requestPointerFocus`, `onDidChangeFocus`, `blurIfFocused`, `resetHandler`, `KEYBOARD_DETAIL`, `multiClickInterval` | `Input` + `Focus` (5.5), 58 tests | the accumulate-then-dispatch model; `beginFrame` only invalidates the hover; multi-click `detail`; the `auto` cursor rule; keyboard activation synthesising button 0; **consumption reported to the host** (the row *"`consumeKeyboardEvent` must REPORT consumption"*) — `Input.consumeKeyboardEvent` returns it |
| `UIDragController` — `DragListener`, `setGhost` ×2, `getGhost`, `startDrag` ×4, `isDragging`, `isActivated`, `getSource`, `getButton`, `getPayload`, `getDropTarget`, `isDropAccepted`, `cancelDrag`, `DEFAULT_THRESHOLD_PX` | `Drag` (5.5) + the ghost (§2.1) | rejection-by-default re-read every frame; the source excluded; ends on its own button; Escape cancels; **the listener's coordinate space changed** (§4.4) |
| `DragScrub` — `PRECISION`, `COARSE_MULTIPLIER`, `FINE_MULTIPLIER`, `Spec`, `dominantDelta`, `unitsPerPixel`, `value`, `decimalsFor`, `passesThreshold` | unchanged — pure maths, no element type | `NumberControl` and `PortDefaultEditor` |
| `TopLayer` — `add` (re-add == raise), `remove`, `elements`, `isEmpty`, paint after the main tree, hit-test first and backwards, Taffy reparent, `position: absolute` at IMPORTANT, containing block = root | `Box.setHost(topLayerHost)`; the host box's children in **insertion order** (§4.1's gap); `position: absolute` from the UA sheet on the promoted kinds (`popover`, `tooltip`, `dialog[modal]`) | `TopLayerSiblingIndexTest`, `TopLayerTest`, `PromotedPopoverHitTest`, `PickerInPromotedDialogTest` are the acceptance |
| `UIResizer` — eight `Handle`s, `appliesTo(mode)`, `isLeading`, drag accumulating from the start box, leading edges moving the origin, containing-block clamp for out-of-flow, `min-width`/`min-height` respected | D6 | `ResizeTest`, `CanvasResizeTest`, `JointResizeTest`, `QuickPickResizeTest` |
| `AnchoredPlacement` — `Side`, `Rect`, `place`, `placeAtPoint`, `placeInRect`, `resolve` (flip on the main axis, clamp on the cross), `anchorRectInRoot` (from the TRANSFORM chain), `pointerToRoot` | ported; reads `box().localToWorld()`; writes INLINE `left`/`top` (D4) | *"only `AnchoredPlacement` writes `left`/`top` on an anchored popup"*; **left-aligns, never centres** — the taskbar preview centres itself |
| `UITreeTraversal` — `pathToRoot`, `commonAncestor` (null, never throws), `first/lastFocusableIn`, `first/lastTabbableIn`, `next/previousTabbable`, `querySelector*` | `Focus` (traversal) + §2.2 (queries) | Tab wraps at both ends |
| `keymap/` — `Keymap.bind/bindAll/unbind/load/clear/bindings/chordFor/acceleratorFor/acceleratorsFrom/conflicts`, `KeyBinding.on/allowWhileTyping/withArgs`, `KeyChord`, `KeyStroke` (wheel strokes, `hasNonShiftModifier`, `isFunctionKey`, `isBareModifier`), `KeymapResolver.resolve/pending/cancelPending/onPendingChanged` (chords, typing, release), `KeymapSheet.parse/load/applyTo` | unchanged except the two walks (D12); `Input.Chords` is the seam a host wires `KeymapResolver` into | `allowWhileTyping` and *"a menu MNEMONIC must not fire while a text field has focus"* |
| `ElementRegistry` — 23 tags, bijective, factory per tag, unknown tag THROWS on decode | `UINodeRegistry` — 55 names (§1.5), `register(Name, Supplier, NodeContract)`, `plain(name, acceptsChildren)` | `UiType` and `PortTypeRegistry` also register tags |
| `EventListenerGroup` — `attachListener(l, capture, bubble)` additive, `defaultEvents` fire in TARGET only if not default-prevented, `emitTarget` vs `emitTargetDom` | `emitTargetDom` becomes the only path at 6.9 | the old `emitTarget` is deleted with the old engine |
| `UIFrameTicker` — one-way registration, `HashSet`-backed, return `false` to drop | `Animation.every(node, hook)` — owned, dropped on freeze/disconnect | D10 |
| `UITransform` — ordered op list, `IDENTITY` (empty list — snaps against anything), `applyTo`, no `matrix()` | unchanged | the row *"`UITransform.IDENTITY` … SNAPS at the halfway point"* survives untouched |

### 4.4 Mechanisms whose port is a *reading*, not a replace

These are the sites where the new engine's semantics differ and each occurrence has to be read.

**`stopPropagation()` — 80 sites in 43 files.** On the old engine it is `stopImmediatePropagation`
within a phase (marked row). Each site is one of: (a) *"stop the walk"* — unchanged; (b) *"pre-empt my
own later listeners"* — becomes `stopImmediatePropagation()`; (c) the `TextEditor` Down-handler
defect, where the Run console's links could never see a press — becomes (a) and is a behaviour fix
worth its own commit. Read all 80; the guide already warns.

**`screenToLocal` / `containsScreenPoint` — 57 sites in 25 files.** `screenToLocal` converts surface
pixels into the element's layout space and does **not** subtract the element's origin (the row that
cost `snapZoneAt` a second bug). `Box.worldToLocal` **does** — its space has the box's top-left at
`(0,0)`. Every `local.x() - getRuntimeCache().getX()` pair (`TextEditor` ×3, `WindowMove`,
`CanvasOverlayMove`, `InsertionMarker`, `ColorSelector` …) loses its subtraction; every site that used
the absolute answer directly (`isMouseOverElement`, `restoreUnderPointer`) gains the box origin back.
The drag listener's coordinates are in the source box's own space too (`Drag.toLocal`). The
`DragPayloadTest`, `WindowGesturesTest`, `SnapZonesTest`, `ScrollerDragTest`, `SliderDragTest`,
`SplitViewDragTest`, `NumberControlScrubTest` are the net.

**`onLayoutChanged` — 22 overriders.** Split three ways:

- **Geometry feedback** — `UIText.recompute` (width/height pushed back at IMPORTANT), `ScrollerView`
  (bar geometry), `TabView` (strip overflow), `MarkupView` (min-width), `SearchReplaceBar`,
  `NavigatorView` (sidebar minimum), `PortDefaultEditor`. Each becomes a `Measurable` or a box read
  during the *next* pass; none may write layout. `UIText` is the largest and is D15.
- **Placement** — `Tooltip`, `Popover`, `WindowFrame` (`placeByCascade`, clamp), `Desktop` (work-area
  callback that places what is unplaced). These read the settled box and write INLINE insets (D4); on
  the new engine the callback fires after the one pass, so *"the first window on an empty desktop
  cannot be placed on the pass that adds it"* becomes *placed on the next frame* rather than *never*.
- **Windowing** — `ListView.updateWindow`, `TableView`, `TextEditor.onLayoutChanged`, `DockArea`,
  `Workbench`, `GraphView`, `CanvasView` (cull), `DiffView`/`MergeView`, `ProjectFileTree`,
  `MenuBarView`. These re-realise rows or re-place children; they stay callbacks, and they must not
  re-dirty layout in the same frame (the one-pass metric catches it).

**The seven paint overriders.**

| Class | Overrides | On the new engine |
|---|---|---|
| `UIText` | `paintSelf` (shaped paragraph, highlight bands, ellipsis) | `TextNode.paintContent` — D15 merges the two |
| `TextField` | `paintOverlay` + **`paintChildren`** (caret, selection, scissored text) | `paintContent` for the text/selection/caret; the scissor is the box's padding clip |
| `MarkupView` | `paintSelf` (quote rules, table column rules per row) | `paintDecoration` on the block nodes |
| `GraphNode` | `paintSelf`/`paintOverlay` (nothing of its own — regions paint, the port band does not) | `paintContent`; the *"a `graphnode` paints NO background of its own"* rule is now a sheet fact |
| `NodeWireLayer` | `paintSelf` (`ctx.curve()` batch, per-wire cull, gradient between types) | `paintContent`; the layer's box at world `(0,0)`, exempt from the canvas cull (a kind-B class) |
| `WindowFrame` | `paintOverlay` (the attention/modal pulse) + **`paintChildren`** (the owned-window slot, the snapshot on the way out) | `paintDecoration`; owned windows are hosted boxes in the overlay slot; the snapshot is taken by `WindowAnimator` through `BoxTree.mirror` into an FBO |
| `WindowThumbnail` | `paintOverlay` + **`paintChildren`** (the mirrored second draw of a live window, or the `WindowSnapshot` texture) | the live path is `BoxTree.mirror(frame, thumbnailBox)` — a second box, no `mirrored` flag; the frozen path is the snapshot texture, unchanged, because **a frozen subtree has no boxes to mirror** |

**`ctx.mirroring` / `mirrored` — 11 sites in 4 files.** `WindowAnimator` guards the snapshot capture
with it (*"the capture re-enters `drawSubtree`, so without a `ctx.mirroring()` guard it photographs
its own empty target"*). On the new engine the capture paints a **mirror box** into the FBO and the
live box is untouched, so the guard, the flag and the counter all go.

**The dynamic-restructure sites — `insertInternalChildAt` / `removeInternalChild`, 40 sites in 17
files.** `Button` (icon slots appearing), `SplitView` (n-ary panes), `Tab` (close button),
`TabView`, `Tooltip`, `NotificationBalloons`, `PageStack`, `StatusBarView`, `ConfiguratorPanel`,
`WindowFrame` (chrome adoption), `TextEditor` (viewport layers), `GraphNode`, `PortDefaultEditor`,
`TableView`, `TreeSearch`, `ProjectFileTree`, `StripeView`. Each becomes a shadow-tree mutation
(kind A) or a light one (kind B). **`WindowChrome`'s move-and-remember-the-internal-flag is a kind-B
`moveTo` between two slots** — the master plan's deletion row — because the caption is a slot and the
menu bar is a light node the workbench owns.

### 4.5 The 117 IMPORTANT writes, classified

By property, from the census (41 sites parse to a single property; the rest are multi-property
lambdas in `InsertionMarker` ×10, `TextEditor` ×10, `RegionDropOverlay` ×6, `QuickPick` ×6,
`CompletionPopup` ×6, `UIText` ×6, `TaskbarDesigner` ×5, `DragGhost` ×3):

| Shape | Sites | Answer |
|---|---|---|
| `opacity` — culling (`CanvasView`), fades (`InsertionMarker`, `ViewCursorsPart` caret blink, `TaskbarPreviews`, `RegionDropOverlay`, `ViewContainer`, `ListView`, `DockGroup`, `ProcessesPopover`, `InputDialog`) | 15 | `box().setOpacity(…)` for a transient (caret blink, a fade the widget drives per frame); a **class** for a state (`__dimmed__`, `__exiting__`) the sheet fades |
| `z-index` — `Desktop.raise`, `GraphView` (selected node on top), `PortDefaultEditor`, `WindowPreview` | 4 | `box().setZIndex(…)` — the compositor's own fact, which is what the override exists for |
| `left`/`top` — `AnchoredPlacement`, `ProgressBar` (indeterminate sweep), `TextEditor` (line rows, caret), `SearchReplaceBar`, `QuickPick` | 4 + | INLINE for placement (D4); **transform** for virtualised rows and a sweep (D22) |
| `flex-grow` — `Tab`, `SplitView` (pane weights), `Slider` (fill) | 3 | `SplitView`'s weights are author state → INLINE; `Slider`'s fill ratio and `Tab`'s are `Measurable`/geometry |
| `display` — `NodeCreationMenu`, `CompletionPopup`, `InsertionMarker` | 3 | `Attribute.HIDDEN` |
| `min-width` — `MarkupView`, `SearchReplaceBar` | 2 | `Measurable` (the widget answers its own min-content) |
| `width%`/`width`/`height` — `Taskbar`, `WindowSwitcher`, `CompletionPopup` | 3 | INLINE (author geometry) or `Measurable` |
| `transform` — `MenuBarView` | 1 | `box().setTransform` |
| `padding-top`, `font-size` — `SearchReplaceBar`, `TextEditor` (zoom) | 2 | zoom is author state → INLINE `font-size` on the editor; the `em` re-resolve listener stays |
| `overlay`, `background`, `background-color` — `DragGhost`, `TaskbarDesigner`, `PortDefaultEditor` | 3 | the designer is a debugging tool and writes INLINE; the ghost's icon is a part with a class |
| `position: absolute` + `left/top/width/height 0` — `DragGhost` rule 2, `UIWindow.windowOverlayLayer`, `Desktop`'s geometry ("must not be movable by a stylesheet") | — | a UA rule on the kind, in `core.css`; *"must not be movable by a stylesheet"* is what `@scope` and the UA origin are for |
| `UIText` width/height feedback | 6 | D15 — `Measurable` |
| `TextEditor` line placement, gutter width, scroller inset, zoom | 10 | D22 for rows; INLINE for gutter/scroller geometry the editor computes; `Measurable` where it is a minimum |
| `InsertionMarker` gap geometry | 10 | the gap is a kind-B node whose size is INLINE (it is author geometry for the duration of a gesture) |
| `RegionDropOverlay`, `QuickPick`, `CompletionPopup`, `TaskbarDesigner` | 23 | INLINE placement (D4), `HIDDEN`, box opacity |

`EngineBoundaryTest` keeps `IMPORTANT` and `importantPipeline` forbidden; nothing here needs either.

### 4.6 Decisions M6 leaves open, with recommendations

| # | Question | Recommendation |
|---|---|---|
| **D1** | ~~How is a `__x__` classified as kind A, B or C?~~ **Which widgets may host a shadow tree at all?** | **REVERSED at M6.1 — see §4.7 below.** The original answer classified a NAME; the batch showed the question belongs to the WIDGET, and that the default was the wrong way round. A widget hosts a shadow tree only when **no shipped rule reaches through its structure**; measured, that is 23 of 44, and 21 must keep their structure light. `tools/port/classify.py` is the measurement and it is per widget, not per opinion |
| **D2** | Part naming | strip the underscores: `part="label"`, `slider::part(thumb)`; kind-B and C classes lose theirs too (`.row`, `.active`) — the underscore convention existed to mark "engine-owned, don't reach in", and the shadow root now says that |
| **D3** | `exportparts` | implement it in the selector engine beside `::part` — it is what lets `window::part(close)` reach a `Button`'s label without the caption being a kind-B tree; S2 measured `::part` alone |
| **D4** | Persistent geometry (a window's `left`/`top`/size, a popup's placement, a resized panel) | **INLINE author style on the node**, exactly what a browser does with `element.style.left` — it is state, it is what the desktop record persists, and an author's `!important` can still pin it. The boundary test forbids IMPORTANT only. Transient motion is a `Box` override driven by `Animation` |
| **D5** | Visibility toggled from Java | `Attribute.HIDDEN` + `[hidden] { display: none }` in `core.css` (HTML's `hidden`); `display` in a sheet stays for sheet-driven cases. Removes 74 IMPORTANT writes and the *"a closed `Dialog` is `display: none`, so every box in it measures 0"* row is unchanged |
| **D6** | `resize:` | a **`Resize` mode over an edge band**: the box's border band is hit-tested by the mode, the affordance is drawn by `BoxPainter` from the `resize` value, no handle nodes exist. It is how every window manager resizes and it removes eight internal children per resizable element. Cost: per-handle CSS (`.__resizer-top-left__`) is gone — the eight handles' geometry becomes two UA lengths (`--resize-band`, `--resize-corner`). The alternative (handles in a UA-owned shadow root) collides with any widget that has a shadow root of its own |
| **D7** | Where the close-watcher / light-dismiss / popover stack lives | `ui.service.Dismiss`, named for what it does and naming no widget: `pushCloseWatcher(node)`, `popCloseWatcher`, `topCloseWatcher(activeScope)`, `pushAutoPopover`, `lightDismiss(target, shownBefore)`, `showSeq()`; `UINode.requestClose()` is the hook. `ModeStackTest`'s constant-pool assertion extends to it |
| **D8** | Overlay hosting | `UIDocument.overlayHost(near)` with the same algorithm: the nearest `WindowFrame`'s overlay slot first, then the nearest ancestor accepting children, then the document's own overlay layer (a kind-B node the document owns, zero-sized, absolute). `OverlayHostTest` is the acceptance and it already forbids `root.addChild` for overlays |
| **D9** | Smooth scroll | `UINode.scrollTo(left, top)` honours `scroll-behavior` via an `Animation` timeline with the exponential ease; `Box.setScroll` is immediate; `scrollIntoView` immediate; a scrollbar thumb drag calls the immediate one (the `Slider`/`SplitView` refusal to animate a drag) |
| **D10** | Hooks after a thaw | the widget re-registers in `thawed()`; `Animation` does not remember. The freeze contract is *"a hidden thing must stop working"* and a hook that came back by itself is the hidden editor compiling again |
| **D11** | The sessions and contracts | `ServerUiSession<N,T>` / `ClientUiSession<N,T>` generic over `TreeSource<N>` + `NodeMirror<N,T>`; `State<W,V>`, `Event<W,P>`, `WidgetContract<W>` lose the `UIElement` bound (nothing in a contract needs it — `WidgetContracts.of` is a class lookup); `RateGate` keys on `Object`. Generic rather than retyped because **both engines run during M6** and the game is on the old one |
| **D12** | The context walks (`DataContext`, `Keymap`, `KeymapResolver`, `UndoScope`, `Settings`, `CommandContext.source`) | over the **composed** chain (`Styleable.inheritsFrom()`), starting from the retargeted focus — S2's finding that retargeting *"stops a `DataContext` walk starting inside a widget's internals"* is only true if the walk crosses the shadow boundary to the host. `DataProvider`, `UndoScope`, `SettingsScope` and the keymap holder become interfaces a `UINode` may implement; the walkers take `Styleable` |
| **D13** | The test fixture | one twin base class (§2.3); tests move by base class and element types, per batch |
| **D14** | Reads before layout | `Box.NONE` answering zeros |
| **D15** | `UIText` vs `TextNode` | **merge into `UIText`** — the old one is deleted in the same batch, so the merged class takes its name and its `text` tag — ellipsis (`…` or `...` by font), `displayedText()`, `HighlightRegistry` + `::highlight` bands, `text-overflow`, `white-space`, `text-align`, `forceSelfSizeWidth`, `__syntax__` (kind C) — with `Measurable` answering min/max content honestly. The *"a `width: 0` basis on a `UIText` latches it as does-not-size-itself, permanently"* row and `DragGhost`'s rule 2 both describe the latch, and the latch is deleted: min-content is a question the engine asks, not a flag the first layout sets |
| **D16** | Where the desktop lives | four document-owned kind-B layers under the document node, in band order — `content` (the application root), `windows`, `pinned`, `overlays` — each a registered `Name` so `desktop { }` still matches, zero-sized until used (the desktop's own rule), hosted by `Box.setHost` so the top layer is a host and not a special case. `UIDocument.desktop()` builds the compositor into the `windows` layer on first use |
| **D17** | Thumbnails and snapshots | live: `BoxTree.mirror`; frozen: `WindowSnapshot` stays — a frozen subtree has no boxes |
| **D18** | `Disposer` | keep the API, key it on nodes: `Lifecycle.destroy(node)` runs `Disposer.dispose` over the composed subtree; non-node disposables (documents, analyses, GL snapshots) register against their owning node. The master plan's *"`Disposer` as a second ownership tree"* is deleted in the sense that the tree is the node tree |
| **D19** | Per-instance reported events | `Attribute.REPORTS` |
| **D20** | The S2 spike | deleted at 6.1 with `Button`, scene and all |
| **D21** | Root sizing and centring | `UIDocument.layout` keeps `UIWindow`'s rule (a percentage root gets definite space; a content-sized root is centred) — `RootPlacementTest` covers it and the parity scene compared relative-to-root because of it |
| **D22** | Virtualised rows (`ListView`, `TableView`, `TextEditor`, `TreeView`) | rows are positioned by **`box().setTransform(translate)`**, not by insets — a compositor-style placement that writes no style and re-runs no layout per scroll frame. The old engine placed them at IMPORTANT `top` and re-laid out; the one-pass metric would otherwise count a layout per scrolled frame |
| **D23** | Where `Networked` panels' `extends UIElement` goes | `extends UINode`; `UiType<P extends UINode & Networked<M>>`; the `mayClose`/`requestClose` name collision row is re-checked against `UINode`'s surface |
| **D25** | The engine's type names — **taken 2026-08-31, before 6.0** | `UINode`, `UIDocument`, `UISlot`, `InputMode`, `UINodeRegistry`, `UINodeTreeSource`, and `UINodeMirror` for the node tree's mirror. `Node` was twenty classes in this workspace and `Document` already means a FILE document here (`FileDocument`, `OpenDocuments`, `DocumentType`, `GraphDocument`, `MarkupDocument`) — the workbench is about to hold both. The prefix is what `UIElement`, `UIWindow`, `UIEvent`, `UIText`, `UITransform` and `UIInputHandler` already carry. **`Name` keeps its name** (`TagName` was no better). **The generic seam does NOT take the prefix** — `NodeMirror<N,T>`, `TreeSource<N>`, `TreeObserver<N>` and `NodeContract` mean a node of ANY tree, which `MirrorIsEngineAgnosticTest` asserts over a twelve-line class that has never heard of a widget; prefixing them contradicts the one thing they exist for. Done before 6.0 because it is one IDE rename over 5,700 lines now and churn in every ported file later |
| **D26** | Where a kind's `Name` is declared | **on the class it names**, as a `public static final Name NAME`, and never as a constant on `Name` — that was a second registry, and only one of the two could be extended from outside the module. `Name.of(local)` is the default-namespace overload; it is NOT `parse` and refuses a colon, so `of("mymod:machine")` fails rather than silently landing in the wrong namespace. **The built-ins are still registered BY the registry**, not from their own initialisers: `create` is the decode path, and a class nothing has touched has not initialised, so a client decoding a description before constructing anything would find `element` unregistered. A widget is the opposite case and self-registers, which is why `UINodeRegistry.ENTRIES` must stay declared ABOVE its static block |
| **D24** | The 32 unregistered tags | every one registers a `Name` in a static initializer beside its class, and `SheetPortTest` refuses a sheet tag no name answers |

---

### 4.7 D1 REVERSED — a shadow tree is opt-in, and the sheets decide

**Status: decided at M6.1, from the batch rather than from first principles.**

D1 asked how to classify a `__x__` NAME. That was the wrong unit. Encapsulation is a property of a
WIDGET — a node either has a shadow root or it does not, and every one of its parts follows — so the
question is which widgets may have one, and the sheets already answer it.

#### The rule

> A widget may host a shadow tree **only if no shipped rule reaches through its structure.**
> Everything else keeps its structure in the light tree with `__x__` classes, unchanged from the old
> engine.

`::part()` has no spelling for a rule that reaches through:

| shape | example | why it cannot be written |
|---|---|---|
| a part under a part | `colorselector .__channel-row__ slider .__thumb__` | `::part(a)::part(b)` is invalid CSS |
| a tag under a part | `dialog .__title-bar__ .__close__ text` | nothing descends from a leaf |
| a nested widget's part | `.__side__ dropdown .__menu__` | the inner widget is inside the outer's shadow tree |

#### The measurement

`tools/port/classify.py`, over every shipped sheet. `through` counts rules with no `::part()`
spelling; `ending` counts rules that twin cleanly.

**23 widgets can host a shadow tree. 21 must stay light. 220 rules have no spelling at all.**

Must stay LIGHT, worst first:

| widget | through-rules | | widget | through-rules |
|---|---|---|---|---|
| `colorselector` | 51 | | `window` | 24 |
| `graphnode` | 23 | | `runpanel` | 21 |
| `problemspanel` | 17 | | `projectfiletree` | 13 |
| `graphview` | 11 | | `taskbar` | 9 |
| `nodecreationmenu` | 8 | | `quickpick` | 7 |
| `workbench` | 6 | | `dialog` | 5 |
| `dropdown` | 5 | | `navigatorview` | 5 |
| `tabview` | 5 | | `dockgroup` | 3 |
| `notificationsview` | 2 | | `statusbarview` | 2 |
| `documentationpopup` | 1 | | `markupview` | 1 |
| `menu` | 1 | | | |

Shadow is safe for: `breadcrumbs`, `checkbox`, `crystaleditor`, `desktop`, `listview`, `menuitem`, `nodeport`, `pagestack`, `popover`, `progressbar`, `scroller`, `scrollerview`, `searchfield`, `shadergrapheditor`, `slider`, `splitview`, `switch`, `tab`, `tableview`, `texteditor`, `tooltip`, `treeview`, `viewcontainer`.

#### Why this is not a retreat

What a shadow tree buys is exactly one thing: **a caller's content cannot collide with a widget's own
parts.** That is what made `.__content__` mean three different things and zero a panel's height, and
it is real. What delivers it is the **slot**, not the part naming — and a slot is available to any
widget that takes content, whichever way this decision goes.

What it costs is every rule that reaches through. The batch paid that cost eleven times in sixteen
widgets, each one silent, each one found by eye.

So the migration keeps its value where the value is — `ScrollerView`, `Menu`, `Button`, `Dialog`,
`Tab`, `SplitView`: widgets that take caller content, where the batch's real defects were content
landing among parts — and stops paying for it where there is nothing to buy. A `ColorSelector` accepts
no content; a boundary there protects its parts from its own theme and from nothing else.

#### The constraint inheritance imposes

**A subclass cannot un-shadow its parent.** `Dropdown extends Button`, so Button's shadow root is
Dropdown's, and Dropdown's five through-rules cannot be answered by making Dropdown light — the whole
chain has to agree. Where a superclass is a shadow host and a subclass has through-rules, the choices
are: expose the needed parts on the superclass and twin them (what `dropdown::part(label)` does for
the label a Button owns), or take the superclass light. **Decide the base class first**, and record
the answer on it, because every subclass inherits the consequence.

#### What changes for the widgets already ported

Nothing is reverted beyond `ColorSelector`, which is done. The other fifteen are all in the "shadow
ok" column or are content-takers whose through-rules are answered by twins that now exist. The
per-widget verdict lives in the ledger's PART rows and is regenerated by `classify.py`.

#### Enforcement

- `tools/port/classify.py` — the verdict, per widget, from the sheets. Run it when porting a widget.
- `tools/port/twins.py` — writes the `::part()` twins for a widget that IS a shadow host. Idempotent
  and generalised over the whole selector, including the tag spelling of a part (`dropdown text`).
- `SheetPortTest.noRuleTargetsAShadowPartByItsClass` — fails when a rule targets a shadow part with
  no twin beside it in the same rule. This is the guard that makes the tool's absence loud.
- `SheetPortTest.noSheetSpellsAPartInsideAPart` — the invalid-CSS shape a mechanical rewrite of the
  218 would produce.

#### The porting step, restated

1. Run `classify.py`. If the widget has any through-rules, it stays **light**: keep its `__x__`
   classes, keep its children in the light tree, and its sheet needs no edit at all.
2. If it has none and it **takes caller content**, give it a shadow root **and a slot**, move its
   parts to `part=`, add it to `twins.py`'s `HOSTS`, and run the tool.
3. If it has none and takes no content, either is correct; prefer **light**, because it is the
   smaller change and the sheet needs no edit.

## 5. The minor milestones

Sizes as in `plan_m5.md`: S ≤ a day, M a few days, L a week or two, XL more. Each row names what it
ports, what accepts it, which invariant rows it owns, and the hazards specific to it.

### 6.0 — Machinery, ledger, fixtures · **L** · after: M5 · **SHIPPED 2026-08-31**

**Contents.** Everything in §2: `Dismiss`, the ghost, `scrollExempt`, transitions on the frame
clock, the pre-bound groups, the query API, scroll extents and smooth scroll, `HIDDEN`, `Box.NONE`,
the helpers, the hooks from `connected()`, `Attribute.REPORTS`, `overlayHost`, the document-level host
hooks for D16, `exportparts`, the fixture twin, the six governance twins, the generalised parity
scene, and **the ledger — written in full**. Plus `UINodeRegistry` registrations for the 55 names,
each beside its (still old-engine) class so the names exist before the classes move.

**Touches the old engine:** no. **Acceptance:** the ledger complete with every one of 303 classes and
497 parts classified; `SheetPortTest` and `PortLedgerTest` green against a ledger in which everything
is *pending*; the M5 acceptance run unchanged; `ModeStackTest`'s constant-pool assertion covering
`Dismiss`. **Proves:** a widget can be ported without inventing anything.

**Budget.** The one batch written from scratch: the services and helpers in §2, the codemod, the
fixture twin, the governance twins, the ledger.

**Hazards.** The ledger is the whole risk: a part misclassified as A that is really B costs a
rewrite of every rule under it when the mistake is found. The census's 401 and 99 are the cross-check
— any name that appears under another part, or above a tag, is B until read.

#### 6.0 — what shipped, and the four things it found

Shipped in three commits: the engine gaps (§2.1, §2.2), the ledger and its two governance tests
(§2.4, §2.5), the codemod (§2.7), plus `LayeringTest` (§2.8) and the fixture twin (§2.3).
`tools/port/ledger.py` and `tools/port/codemod.py` are the two commands; headless went 1691 → 1720.

**Four things the work found, none of them visible from the plan:**

- **`Attribute.HIDDEN` cannot be a stylesheet rule.** D5 assumed HTML's own
  `[hidden] { display: none }`, and **this selector engine has no attribute selectors** —
  `AGENTS.md` lists them with `:nth-child` and the sibling combinators as deliberately absent. So
  hiding is STRUCTURAL: the box tree gives a hidden node no box, exactly as it gives none to
  `display: none`. Smaller than teaching the parser a selector kind for one rule, and closer to what
  the old engine did anyway, whose `IMPORTANT`-origin `display` no theme could override either.
- **Promotion has to be recorded on the NODE, not written onto a box.** A box is destroyed and
  rebuilt whenever its subtree is hidden, frozen or restructured, so `box.setHost(topLayer)` is lost
  on the next sync — a popup hidden and reshown would come back UNPROMOTED, clipped by its scroller
  again, and only ever after having been closed once. `UIDocument.promote` records it and the box
  tree re-applies it per sync; the pass owns BOTH directions, because applying promotions alone
  leaves a demoted box hosted where the last sync put it.
- **A 5.3 bug the first test found: `TaffyTree.remove` marks nothing dirty.** It takes the node out
  of its parent's child list — and because the list is then already correct, `sameChildren` skips the
  `setChildren` that would have marked the parent. So a subtree that goes away leaves its former
  siblings exactly where they were until something unrelated dirties layout: a removed row leaves a
  gap, a hidden panel keeps its space, both correcting themselves later. True of any `remove()` since
  5.3; nothing had removed a node between two layouts. `destroy()` dirties the host now.
- **`EngineBoundaryTest`'s class list was inverted**, having broken twice in one session and silently
  both times — a rename does not touch a string literal, and adding one class to `ui.dom` failed it
  again. It lists the SEAM now (four types that exist to be stable) and treats everything else in the
  package as new engine. The half that does not grow is the half that does not rot.

**And two the ledger's first run found**, which is what the confirmation column is for: the obvious
part heuristic — B if a name is selected UNDER another part — called `thumb`, `mark`, `label`,
`track` and `fill` light structure. Those are the archetypal parts of Slider, Checkbox, Button and
Scroller, wrong because a sheet SCOPES them through a container
(`colorselector .__channel-row__ slider .__thumb__`). Being scoped BY an ancestor says nothing about
whether you hold anything; being selected THROUGH says you do. With that one signal the split is
**354 A / 114 B / 42 C** and every spot check is right.

**Deliberately not built.** `exportparts` (nothing needs it before 6.2's nested parts), the
generalised parity spec (6.1's first widget is what shapes it), and D16's four desktop bands — the
top layer alone is what 6.1 and 6.2 need, and the windows/pinned bands are 6.6's.

### 6.1 — Leaf widgets · **M** · after: 6.0 · **SHIPPED 2026-08-31**

**Ports.** `Button` (the paper port made real; `ShadowButton` and `cgui-shadow-parts` deleted),
`Checkbox` + `CheckboxGroup`, `Switch`, `Slider`, `ProgressBar`, **`TextNode` → `UIText`** (D15),
`TextField`, `Scroller`, `ScrollerView`, `Tooltip`, `SymbolIcon`, `DragGhost`, `InsertionMarker`,
`SearchField`, `WindowIcon`. Sheets: `widgets.css`, `core.css`, the leaf half of `overlays.css` and
`search.css`; `ore.css`'s 101 part references.

**Accepts.** Scenes `cgui-button`, `-checkbox`, `-switch`, `-slider`, `-text`, `-text-stress`,
`-textfield`, `-scroller`, `-nineslice`, `-styling`, `-visual-layers`, `-ore-theme`, `-svg-icon` on
`--engine=new` with parity PNGs; the tests naming these classes re-pointed (`UIText` 1,066 · `Button`
829 · `TextField` 403 · `ScrollerView` 426 · `Slider` 307 · `Checkbox` 208 · `Switch` 205 ·
`Tooltip`, `ScrollerDrag`, `SliderDrag`, `TextFieldScroll`, `TextLayoutProperties`, `UITextMaxWidth`,
`Highlight`, `FontFace`, `FontStackFallback`, `MeasureFuncUnderFlexWrap`, `FlexShrinkOverflow`,
`OverflowLayout`, `ScrollCapability`, `ScrollIntoView`, `Cursor`, `FocusVisible`, `HoverChain`,
`Inert`, `PointerCapture`, `StateInvalidation`, `KeyConsumptionReported`, `CompositeTabStop`);
`WidgetContractCoverageTest` walking both hierarchies.

**Destination.** `widget.control` (Button, Checkbox, CheckboxGroup, Switch, Slider, ProgressBar,
TextField, SearchField, SymbolIcon), `widget.text` (UIText), `widget.scroll` (Scroller, ScrollerView),
`widget.overlay` (Tooltip), `widget.dnd` (DragGhost, InsertionMarker), `desktop.window` (WindowIcon).

**Budget, measured** (`python tools/port/codemod.py --batch 6.1 --dry-run`): **18 copied, 0 moved,
364 mechanical rewrites, 80 hand sites** — IMPORTANT writes 29, internal children 12, layout
internals 10, coordinate conversions 8, dynamic restructures 7, `stopPropagation` 7, paint
overrides 3, post-layout callbacks 3, the drag ghost 1. Also the codemod's own test: one file per
transformation is diffed by eye before the rest of the batch runs.

**Rows it owns.** `UIText`'s whole section; *"`text-overflow` does not inherit"*; the ellipsis
fallback; *"`font-size` does not inherit"* and the two `em` rows (unchanged — cascade facts); *"a
`TextField` must refuse ALT chords"* (becomes `claimsChord`); *"a `Button` activates on the LEFT
button only"*; *"restore focus on the mouse-DOWN"*; *"a paint method may skip the DRAW, never the
METHOD"* (the scissor is the painter's now — mark); *"a flex item with `flex-shrink: 1` contributes
ZERO … a `width: 0` basis latches"* (mark — D15); *"`getScrollWidth()`/`getScrollHeight()` measure
direct children only"* (re-state for `UINode.scrollExtent`); `Scroller`'s percentage thumb;
`ScrollerView`'s bars as scroll-exempt children.

**Hazards.** `TextField.paintChildren` is the first paint override with no counterpart — the scissor
was the widget's and is the box's. `Tooltip` is the first top-layer widget and the first
`AnchoredPlacement` consumer; `TooltipTest` and *"a tooltip must never show while a drag is live"*
are its net. `Scroller`'s `setScrollExempt` is the 5.4 gap's first real consumer.

#### 6.1 — what shipped, and the one lesson underneath all of it

**Shipped.** 22 classes: the 18 the batch named, plus `Menu`, `MenuItem`, `Popover` and `Tooltip`
pulled forward out of 6.2 because `Dropdown` and `SearchField` compose them. 7,676 old-engine lines
became a 24-file, ~9,960-line `widget.*` + `desktop.window` tree. Every scene the row named is on
`--engine=new`, and `cgui-new-gallery` — one scroller holding every ported widget at once — was
built to see them together, which is what found most of what follows.

**Two decisions were reversed, both by contact with a real sheet.**

**D5.8** said `BoxStyle` should write CSS's initial values for anything unset, on the argument that
the old bridge's five divergences are a standing source of surprise. The bill came due here and
could not be paid: in a 6,200-line user-agent sheet nearly every rule leaves `flex-direction`
unstated, so flipping it to `row` turns every unstated column into a row — and the failure is
silent. `menu` states no direction, so its item column became a row, `align-items: stretch`
stretched the items container across the menu's height, and a three-row menu drew **166px tall with
its rows in the top 43**. The gallery met the same divergence three times in one sitting and each
read as a different bug. Both engines answer the same question the same way now, which is what makes
a geometry difference between them a defect rather than a default.

**D1** asked how to classify a `__x__` NAME. Wrong unit: encapsulation belongs to a WIDGET, so the
question is which widgets may host a shadow root, and the shipped sheets already answer it.
`tools/port/classify.py` measures it — **23 can, 21 cannot, 220 rules have no `::part()` spelling at
all**, `colorselector` alone accounting for 51. §4.7 is the full argument. The practical effect is
that the master plan's one-line "every `__part__` → `::part()`" is a per-widget reading, and the
batch paid for it eleven times in sixteen widgets before the tool existed.

**Three services were finished and had no caller.** `Dismiss` was written at 6.0 with the whole spec
algorithm — the popover stack, the invoker carve-out, the `shownBefore` counter, the close-watcher
cascade — and `Input`, written at 5.5, invoked **none** of it: light dismiss and Escape were both
complete and unreachable, so a menu could be opened and closed by nothing. The detach path was the
same shape from the other end: the old `unregisterElement` told five things a node had gone, and the
new tree told nobody. And `topWatcherIn` compared a watcher's scope against a bare `null` while
`Focus.scopeOf` answers the DOCUMENT, so every watcher outside a window frame was unreachable —
which is all of them in an application with no desktop. None of this is visible to a unit test of the
service, which passes whether or not anything calls it, nor to a widget test, which drives the
widget's API rather than the platform's. `ServiceWiringTest` now asserts each seam through the entry
points a HOST calls, and a new seam gets a case there in the same commit.

**Seven engine gaps the widgets found, each silent.** Hosting did not imply out-of-flow, so a
promoted popover arrived in the top layer as an ordinary flex item and was compressed to nothing —
measured at a `60x4` parent around a `56x52` child. `Box.contentWidth()` and Taffy's
`contentBoxWidth()` are different questions and the codemod mapped one onto the other, giving
`TextField` a zero-width scissor that clipped its own text away. A widget that paints its own content
must be `Measurable` or it measures zero — `TextField` laid out `215x0`, present and styled and
unclickable. A `::part` rule is indexed under the HOST, so a host's re-match must re-match its
exposed parts, or a checkbox toggles with its mark still drawing unchecked. `notifyStateChanged` had
to walk out of every enclosing shadow tree — nested, in a loop — or a composite's state change is
attributed to a node no peer has heard of. `AnchoredPlacement` read an anchor as `worldX() -
root.worldX()`, which is right at `uiScale: 1` and wrong by exactly the scale everywhere else.
And a tooltip can no longer be a child of its anchor, because a light child of a shadow-rooted node
with no slot is never composed at all.

**D15 shipped with the batch.** `ui.box.TextNode` and `ui.elements.UIText` merged into
`widget.text.UIText`: 777 lines for the 1,244 it replaces plus TextNode's. It implements
`Measurable`, so ~400 lines of workaround for a question the engine could not be asked went with it
— `selfSizesWidth` and its latch, the two escape hatches, `invalidateMeasurement()` and the deadlock
it is named for, and four static property listeners in place of one `computedChanged` hook.

**Two tooling findings.** The twin generator has to be comment-safe and idempotent: the sheets put a
comment on the line above the rule it explains, so a scan splitting on `}` reads
`/* … */ slider .__fill__` as one selector and skips the rule — 94 rules were silently passed over,
and the identical bug is already an invariant row for `ScopedSheets.scope`. And `classify.py` is
**tag-keyed**, which is a gap 6.2 walks straight into (below).

**The lesson underneath all of it is an ordering one.** Every defect in this batch was an engine gap,
not a widget one; the widgets were copied faithfully and what they landed on was unfinished, and each
gap was found by eye, one screenshot at a time. The fix is not to port more carefully — the codemod
was fine. It is that **a milestone which builds machinery must run something real on it before
calling itself done**, and `cgui-engine-parity` (one tree, both engines, two PNGs, diffed) was built
at 5.4 and never extended to the widgets, which is the mechanical check every one of these would have
failed.

**Left owing.** The row said `ShadowButton` and `cgui-shadow-parts` are deleted with `Button`'s port
and they are not: `ui/shadow/` and `CgUiShadowPartsScene` are still on disk. Deleting them is 6.2's
first commit, not a loose end to carry to 6.9.

### 6.2 — Dialogs, the layout composites, and the config kit · **L** · after: 6.1 · **SHIPPED 2026-08-31**

*Retitled. The overlay family — `Popover`, `Menu`, `MenuItem`, `Tooltip` — shipped in 6.1, pulled
forward because `Dropdown` and `SearchField` compose them; `ColorSelector` and `MarkupView` were 6.1
rows in the ledger all along and the old prose here listed them by mistake. What is left is three
unrelated things that happen to share a batch: the dialogs, the two layout composites, and the config
kit with the inspector over it. `Breadcrumbs`, `StatusBarView`, `ProgressStatusItem` and the
notifications are **6.3** rows and were never 6.2's — the old prose listed those too.*

**Scope, from the ledger:** 35 files, **7,847 old-engine lines** — the largest batch so far by line
count and by file count both.

| Group | The files | Files | Lines | Destination |
|---|---|---:|---:|---|
| Dialogs | `Dialog` 605 · `DialogManager` 144 · `InputDialog` 178 | 3 | 927 | `widget.overlay` |
| Menus over the 6.1 base | `ContextMenu` 309 · `MenuBuilder` 231 | 2 | 540 | `widget.overlay` |
| Layout composites | `SplitView` 877 · `TabView` 605 · `Tab` 247 · `PageStack` 149 | 4 | 1,878 | `widget.layout` |
| The config kit | `ConfigDescriptor` 262 · `ConfigControl` 242 · `ConfiguratorPanel` 194 · `SettingsConfigurator` 172 · `ConfiguratorGroup` 125 · `ConfigControlContracts` 93 · `ValueControl` 84 · `ConfigControls` 82 · `Configurator` 81 | 9 | 1,335 | `widget.form` |
| The thirteen controls | `NumberControl` 295 · `MaskControl` 159 · `ArrayControl` 155 · `ColorControl` 151 · `VectorControl` 123 · `MatrixControl` 109 · `SliderControl` 92 · `TextControl` 87 · `AssetControl` 80 · `SelectControl` 68 · `InfoControl` 69 · `HeaderControl` 55 · `BooleanControl` 54 | 13 | 1,497 | `widget.form.field` |
| The inspector | `Inspector` 447 · `InspectorForm` 129 · `InspectorSection` 92 · `InspectorRegistry` 75 | 4 | 743 | `widget.form.inspector` |

**Budget, measured** (`python tools/port/codemod.py --batch 6.2 --dry-run`): **25 copied, 10 moved,
390 mechanical rewrites, 35 hand sites.** The ten moves are engine-neutral — the descriptors, the
contracts, the registry, and the six controls that hold no geometry — so the IDE does them.

Mechanical, by kind: element type 151 · tree 56 · import 37 · window type 26 · document receiver 24 ·
text node 17 · focus 12 · geometry 10 · identity 10 · internal flag 10 · scroll 9 · base class 8 ·
`acceptsPublicChildren` 7 · top layer 4 · drag receiver 3 · ticker 3 · input receiver 1.

Hand sites, by kind: dynamic restructure 10 · `stopPropagation` 8 · IMPORTANT write 7 · resize hook 4
· coordinate conversion 4 · post-layout callback 2.

**Ratio, and what it says.** 390 mechanical to 35 hand is the cleanest of any batch measured —
6.1 was 364 to 80. Two things drive that and both are worth knowing before the batch starts: there
are **zero paint overrides** in the whole of 6.2 (6.1 had three, and `TextField.paintChildren` was
one of the batch's named hazards), and the config kit is 22 of the 35 files while accounting for
only 6 of the hand sites. **The work is concentrated in four files** — `Dialog`, `SplitView`,
`TabView` and `NumberControl` carry 27 of the 35.

#### D1 — and the tool cannot answer for two thirds of this batch

`classify.py` reads the sheets and answers per widget. For 6.2's tags:

| Tag | through | ending | verdict |
|---|---:|---:|---|
| `splitview` | 0 | 12 | shadow ok |
| `tab` | 0 | 5 | shadow ok |
| `pagestack` | 0 | 2 | shadow ok |
| `dialog` | **5** | 8 | **LIGHT (kind B)** |
| `tabview` | **5** | 12 | **LIGHT (kind B)** |

**And the config kit and the inspector do not appear, because the sheets never name them as tags.**
`config-kit.css` names `button`, `colorselector`, `graphnode`, `graphview`, `nodecreationmenu`,
`nodeport`, `searchfield` and `texteditor` — every one of them a widget the kit *contains* —
and `inspector.css` names exactly one, `projectfiletree`. Between them that is **200 rules keyed
entirely on classes**, and `classify.py` is tag-keyed, so it is blind to all of it.

Measured by hand over the two sheets, counting multi-compound selectors:

| Sheet | part-under-part | tag-under-part | clean leaf |
|---|---:|---:|---:|
| `config-kit.css` | 20 | 33 | 50 |
| `inspector.css` | **74** | **27** | **1** |

`inspector.css` is the sharpest number in the whole census: of 102 multi-compound selectors, **one**
is a shape `::part()` can express. `.__configurator__ > .__label__`, `.__configurator__ >
.__inline__ > .__config-control__`, `.__config-control__ textfield` — the sheet is built out of
exactly the two shapes a part cannot spell.

**So the verdict for the config kit and the inspector is not close: all 22 files are kind B, light
tree, no shadow roots, no `part` attributes, no twins.** The port there is a retype plus its handful
of hand sites, and `tools/port/twins.py` must not be run over those two sheets at all.

That leaves a machinery gap to close first, exactly as §2 did for 6.1: **`classify.py` needs a
class-keyed mode** — `.__x__ …` hosts as well as `tag …` hosts — or the batch after this one asks a
question the tool silently answers "no data" to. It is the same failure the tag census already has a
row for (§1.5: 32 tags matching only through the old engine's lowercased-class-name fallback), one
axis over.

**One thing this batch does not have to worry about.** Every config-kit and inspector class is
already `WidgetContracts.localOnly` with a reason (`WidgetCensus` lines 114–126, 218), so a kind
registered for them would decode nothing and describe nothing. Light-tree structure being *described*
— which is the one cost of dropping the internal flag — is therefore free here: nothing describes
them.

#### The 57 internal-child sites, and the 10 that are dynamic

`addInternalChild` / `insertInternalChildAt` / `markAsInternal` appear **57 times** across the 35
files — more than any batch so far, and for a boring reason: a config control is nothing but internal
children. 47 of them are build-once structure in a constructor and are a pure retype on a light tree.

The 10 the codemod flags are the ones that mutate a live tree, and they split cleanly:

- **`SplitView` (6).** `insertInternalChildAt(element, 2 * index)` and its siblings — panes and
  dividers interleaved at computed indices, so the child list *is* the data structure. On a light
  tree those become `insertAt`, and the arithmetic is unchanged. What must be re-read is the removal
  pair: `removeInternalChild` on the old engine is the one call that could detach an internal child,
  and `removeChild` silently refused them; with no flag, `remove` is simply `remove` and the
  asymmetry that invariant row documents disappears. **Check the boolean returns** — the old code
  could rely on `removeInternalChild` succeeding where `removeChild` would not.
- **`PageStack` (2), `Tab` (1), `ConfiguratorPanel` (1).** Single-node swaps — a placeholder, a close
  button, a page. Ordinary.

#### The seven IMPORTANT writes, one by one

Per §4.5, each becomes a `Measurable`, a box call, an INLINE write or a class.

| Site | What it writes | Answer |
|---|---|---|
| `Dialog:418` | the backdrop's layout | **INLINE** — nothing else writes the backdrop |
| `Dialog:477` | the dialog's own insets while moving | **INLINE** (D4: a moved dialog is positioned by insets) |
| `InputDialog:165` | `opacity(0)` on the popup before it opens | **class** — the resting value belongs in the sheet; this is the *"transitioning INTO view needs a resting value in the sheet"* row exactly |
| `InputDialog:174` | reads `slot.origin() == IMPORTANT` to undo the above | **deleted with it** — the read exists only to withdraw the write |
| `SplitView:779` | `flexGrow(weight)` per pane | **INLINE** — the split weight is the widget's own state, and the sheet states no `flex-grow` for a pane |
| `Tab:227` | the content pane's layout | **INLINE** |
| `TabView:582` | the strip bar's layout | **INLINE** |

Six INLINE, one class, one deletion — and no `Measurable` among them, which is the difference
between this batch and 6.1: nothing in 6.2 pushes a *measured* size back into the cascade. The
feedback loops were all in the text layer.

#### The four files that carry the batch

**`Dialog` (605 lines) — four decisions land on one widget.** It is the first `resize:` consumer
(D6: the resize mode over an edge band, no handle nodes — `applyResizeOrigin`,
`resizeOriginLeft/Top`, `resizeContainingBlock`, four sites), the first modal, the first
`attachOwned` target, and it moves by INLINE insets (D4). It is also `LIGHT (kind B)` on five
through-rules, so its backdrop, its content slot and its buttons stay classes. Its
`stopPropagation` at line 233 is `if (requestClose()) event.stopPropagation()` — under DOM semantics
that is "end the walk", which is what it meant.

**`SplitView` (877 lines) — the largest file in the batch and the most positional.** Six dynamic
insert/remove sites at computed indices, one IMPORTANT write per pane, one `stopPropagation` on the
divider drag. It is `shadow ok` (0 through-rules, 12 clean leaves), so its dividers and panes *can*
be parts — but read §4.7's constraint first: a `SplitView` pane holds a caller's content, so it needs
a **slot**, and the panes are what the caller addresses. The two standing invariant rows are its net:
*"a `SplitView` divider must clamp against the pane's CSS `min-width`"* (and the clamp reads the
pane's CONTENT, not the pane) and *"cannot go below two panes"*.

**`TabView` (605) + `Tab` (247) — the only post-layout callbacks in the batch.** Two
`onLayoutChanged` overrides, which per §4.4 become `Animation.afterLayout` hooks registered from
`connected()`. Three `stopPropagation` sites in the strip. `tabview` is LIGHT on five through-rules
while `tab` is `shadow ok`, which is the asymmetry to get right: the strip and the rail are reached
through, a tab's own parts are not. Its rows: *"exactly one tab in a `TabView` strip is tabbable"*
and *"click-focus tests `focusesOnClick()`, never `== CLICK`"*.

**`NumberControl` (295) — three of the four coordinate conversions.** The scrub gesture reads
`handle.screenToLocal(0f, 0f)` for an origin and `handle.screenToLocal(probe, 0f).x()` for a scale,
and `containsScreenPoint(rawX, rawY)` beside them. This is the single most dangerous file in the
batch, because **`toLocal`'s origin moved** (M6.1): `screenToLocal` did not subtract the element's
own origin and `toLocal` does, so `screenToLocal(0f, 0f)` — which used to answer "where is my box in
layout space" — now answers zero by construction. The two derived quantities are an origin and a
span; the span is a difference and survives, the origin does not. `ColorSelector.withinX/withinY`
paid exactly this in 6.1 and its javadoc names the bug from the other side.

#### Accepts

Scenes `cgui-splitview` and `cgui-tabview` on `--engine=new` with parity PNGs, plus the gallery's
dialog, config and inspector pages as parity specs. `cgui-slot` is **gone** — `CgUiSlotScene` was
deleted with the 6.1 gallery work, and the slot behaviour it demonstrated is covered by
`ScrollerView`, `Menu` and `Button` in `cgui-new-gallery`.

**54 test files** name a 6.2 class and move with it — `Tab` 25, `SplitView` 13, `TabView` 10,
`Dialog` 10, `Inspector` 9, `NumberControl` 5, `ContextMenu` 5, `ConfiguratorPanel` 4. Named
explicitly: `NestedSplitDividerTest`, `SplitPaneGrowChainTest`, `SplitViewNAryTest`,
`TabViewRailLeakTest`, `TabCloseAndRevealTest`, `ModalDialogTest`, `DesktopModalityTest`'s
non-desktop half, `PromotedPopoverHitTest`, `PickerInPromotedDialogTest`, `TopLayer*`,
`AnchoredPlacementTest`, `ResizeTest`, `NumberControlScrubTest`, `ScrubUndoTest`, `ConfigKitTest`,
`ConfiguratorPanelLifetimeTest`, `InspectorTest`.

**A dialog fixture must `show()` first** — a closed `Dialog` is `display: none`, so every box in it
measures 0 and any "does it fit?" assertion passes against `0 <= 0`. That is a standing invariant
row and it is this batch's most likely green-against-nothing.

#### Rows it owns

The modality and inertness rows: *"`inert` keeps its box"*; *"hit-testing an inert subtree FALLS
THROUGH"*; *"a detached modal must be popped from the modal stack"*; *"modal inertness is enforced at
four points, and `focusable()` is deliberately not one of them"* — which M5 5.5 already restated as
one predicate over focus navigation scopes, so this batch is where that restatement is first *used*.
The layout rows: the `SplitView` clamp and two-pane floor; *"`flex-grow` summing to less than 1
leaves the remainder undistributed"*; *"a `SplitView` pane is a flex COLUMN regardless of the split's
orientation"*. And *"transitioning INTO view needs a resting value in the sheet, never a one-frame
write from Java"*, which `InputDialog:165` is a live instance of.

#### Hazards, in the order they would be found

1. **`classify.py` is blind to the config kit.** Close the class-keyed gap before the batch, or 22
   files get ported on a guess. This is the batch's §2.
2. **`NumberControl`'s scrub origin.** A wrong answer here is a scrub that runs at the wrong speed or
   from the wrong place, and it is wrong by a different amount every time — the shape that reads as a
   bad constant.
3. **`Dialog` carrying four first-uses at once.** If it slips, split it: the modal half is what 6.6
   depends on, the resize half is not.
4. **`SplitView`'s index arithmetic.** Six sites, all positional, none of which fails loudly.
5. **The 47 quiet internal-child sites.** They are a retype, and the risk is exactly that: nobody
   reads them, and a control whose parts were internal is now describable. Free here only because
   every one of these classes is already `localOnly` — check that stays true.

**First commit of the batch:** delete `ui/shadow/` and `CgUiShadowPartsScene`. 6.1 owed it.

#### 6.2 — what shipped, and the two things it found

**Shipped.** 33 classes: the dialogs, the four layout composites, and the whole config kit — 22 files
into `widget.config` and `widget.config.control`, plus `ConfigDescriptor` into `core.config`. **Six
did not**: `ContextMenu`, `MenuBuilder` and the inspector's four are blocked on `CommandContext` and
`DataContext`, both `record …(UIElement source, …)`, and they moved to 6.3 with the seam.

**The ledger's copy/move split was wrong twelve times out of thirteen.** Its heuristic reads a file's
own imports, and a same-package reference has none: `ValueControl extends ConfigControl` (a node),
`ConfigControls` is a factory RETURNING one, `InspectorSection` takes an `InspectorForm`,
`InspectorRegistry` types `List<InspectorSection>`. Every one transitively bound; moving them would
have taken them from the old engine, which ships until 6.9. **Audit a move by TYPE NAME, never by
imports** — which is why 6.3's seventeen were re-audited that way before it was planned.

And the one genuine move could not go where the ledger said. `ConfigDescriptor` in `widget.config`
made `EngineBoundaryTest` fail with seven old-engine classes reaching into the new tree —
correctly. **A neutral class belongs in a package BOTH engines may name**, which is the conclusion
`SimilarNames` reached when it moved to `com.crystalgui.text`. 6.3 has sixteen of these.

**Five codemod rules were silently dead**, and the cause is worth stating exactly: the file carried
nine stray backspace bytes, because `""` in a Python string is a BACKSPACE and not a regex word
boundary. `screenToLocal(`, `containsScreenPoint(` and the `Drag.` receiver matched nothing — which
is precisely what the first tranche hand-fixed without noticing the tool should have. Repaired, the
batch's reading list fell from 6 sites to 3. Four further gaps closed with it, each of which would
have hit every later batch: an inline FQN the import rules cannot see, a wildcard import, `.getId()`
firing on a non-node receiver, and ported-import rewriting that only fired for rows ALREADY marked
ported — so a batch's own copies imported each other's OLD classes, which resolves perfectly and
fails at every call.

**And the gallery found a cascade regression latent since 5.2.** `invalidateStyleMatch` marked the
node and its exposed shadow parts and nothing else, so a rule keyed on an ancestor's class never
re-matched its descendants. The old engine walked its children and its comment named the case; M6.1
added the shadow half for `::part` and REPLACED the light walk rather than joining it. Nothing could
see it until a widget's LAYOUT depended on such a rule — a `ConfiguratorGroup` folds by adding a
class and letting the sheet set `display: none`, and it would not fold, with every observable
correct.

### 6.3 — Collections, the shell's chrome, and the seam two batches deferred to it · **XL** · after: 6.2 · **SHIPPED 2026-08-31**

*Re-sized from **L**. It absorbed six classes 6.2 could not port — `ContextMenu`, `MenuBuilder` and
the inspector's four — and the reason they came here is also the batch's first piece of work: the
command and data layer is typed on `UIElement`, and 6.3 is where it has to stop being.*

**Scope, from the ledger:** 44 files, **11,883 old-engine lines** — the largest batch in M6 by both
measures, half again the size of 6.2.

| Group | The files | Files | Lines | Destination |
|---|---|---:|---:|---|
| **The seam** | `Keymap` 235 · `KeymapResolver` 297 · `DataContext` 162 · `CommandContext` 41 · `DataProvider` 36 | 5 | 771 | *retyped in place* |
| Lists | `ListView` 1,408 · `VariableHeightStrategy` 189 · `ListRenderer` 74 · `FixedHeightStrategy` 50 · `ItemSizeStrategy` 28 · `SelectionMode` 15 | 6 | 1,764 | `widget.collection.list` |
| Trees | `TreeSearch` 1,141 · `TreeView` 449 · `PathTreeSource` 156 · `FilteredTreeSource` 106 · `TreeRenderer` 40 · `TreeDataSource` 32 · `TreeRow` 21 | 7 | 1,945 | `widget.collection.tree` |
| Tables | `TableView` 519 · `TableColumn` 156 · `SortOrder` 25 · `TableCellRenderer` 18 | 4 | 718 | `widget.collection.table` |
| The palette | `QuickPick` 791 · `QuickPickSource` 294 · `QuickPickItem` 179 · `CommandPalette` 151 · `QuickPickEntry` 41 | 5 | 1,456 | `chrome.palette` |
| The menu bar | `MenuBarView` 645 · `MainMenuCommands` 89 · `ChromeCommands` 63 | 3 | 797 | `chrome.menu` |
| Problems | `ProblemsPanel` 929 · `ProblemsTreeSource` 210 · `ProblemsCommands` 83 · `ProblemNode` 38 | 4 | 1,260 | `chrome.problems` |
| Notifications | `NotificationBalloons` 314 · `NotificationCard` 217 · `NotificationsView` 200 | 3 | 731 | `chrome.notification` |
| Navigation | `NavigatorView` 620 · `Preferences` 287 | 2 | 907 | `chrome.preferences` |
| The status bar | `StatusBarView` 427 · `ProgressStatusItem` 215 · `ProcessesPopover` 214 · `Breadcrumbs` 166 | 4 | 1,022 | `chrome.status` |
| **Deferred from 6.2** | `Inspector` 447 · `MenuBuilder` 231 · `ContextMenu` 309 · `InspectorForm` 129 · `InspectorSection` 92 · `InspectorRegistry` 75 | 6 | 1,283 | `widget.config.inspector`, `widget.overlay` |

> **The ledger said `chrome` flat for all twenty-one chrome files**, and the batch landed that way
> before being split. The six sub-packages are in the ledger now. What the note got wrong is the
> mechanism: they need NO entry in `LayeringTest`'s tier list, because `chrome/` is a prefix and one
> layer — adding them there made each a layer above the layer root and the layer's own registrar a
> layer reaching upward. See *what shipped* below.

**Budget, measured** (`python tools/port/codemod.py --batch 6.3 --dry-run`): **27 copied, 17 moved,
601 mechanical rewrites, 58 hand sites.**

Mechanical, by kind: element type 237 · tree 102 · ported import 65 · import 44 · document receiver
37 · window type 35 · focus 14 · geometry 14 · base class 12 · `acceptsPublicChildren` 12 · identity
5 · ticker interface 4 · drag receiver 4 · internal flag 3 · internal child 3 · the rest 10.

Hand sites, by kind: **`stopPropagation` 27** · **IMPORTANT write 18** · post-layout callback 4 ·
dynamic restructure 4 · internal child 3 · layout internals 1 · resize hook 1.

**What the numbers say.** 58 hand sites is 6.1's 80 and 6.2's 35 — but the shape is different from
both. Twenty-seven of them are `stopPropagation`, which is a *reading* rather than a rewrite (§4.4:
is this "end the walk" or "pre-empt my own later listeners"), and they cluster: `MenuBarView` has
four, `ProblemsPanel` three, `QuickPick` two. The eighteen IMPORTANT writes are the real work and are
the most any batch has had — 6.2 had seven and every one became an INLINE write, which will not hold
here, because a virtualised list computes geometry it has to hand back.

#### 2 — the seam, and it is not optional

**`ContextMenu`, `MenuBuilder` and the inspector's four are already blocked on it**, which is why
they are in this batch and not the last one. `CommandContext` is `record CommandContext(UIElement
source, …)`; `DataContext` is the same shape; `Keymap.acceleratorFor` takes one; `KeymapResolver`
names it nine times. 771 lines across five files, and **55 files across the repo call into them**.

This is the first of the fourteen non-element seams §1.2 counted, and it cannot be deferred again:
`MenuBarView` and `CommandPalette` are the batch's own, and both are built on it end to end.

The retype is not a port — these classes are engine-neutral apart from the type they name — so the
question is only which type replaces `UIElement`. `Styleable` is too narrow (the walk needs
`parent()`), and `UINode` alone would break the old engine, which still resolves commands. **A
generic parameter or a small `CommandTarget` seam is the shape**, decided the same way `ui.dom`'s
`TreeSource` was: the walk is `parent()` and `getData`, and both engines can supply it.

#### A MOVE INTO A `widget/` PACKAGE IS ILLEGAL, and sixteen of the seventeen are

6.2 learned this the expensive way and 6.3 has it sixteen times over. `EngineBoundaryTest`'s
`theOldEngineNamesNothingOfTheNew` is what enforces it: a class the OLD engine still names cannot
live in the new engine's tree. `ConfigDescriptor` was moved to `widget.config`, seven old-engine
classes reached into it, and the answer was a package **both** may name — `com.crystalgui.core.config`.

Every one of 6.3's moves lands in `widget.collection.*` or `chrome`, and every one is still named by
the old engine's `ui.elements.list/tree/table`. So the destinations above are wrong for exactly the
rows marked *move*, and each needs a neutral home instead:

| What | Why it is neutral | Neutral home |
|---|---|---|
| `TreeDataSource`, `TreeRow`, `FilteredTreeSource`, `PathTreeSource` | a pull-based model, no node in it | `core.collection.tree` |
| `ItemSizeStrategy`, `FixedHeightStrategy`, `VariableHeightStrategy`, `SelectionMode` | row arithmetic and an enum | `core.collection.list` |
| `SortOrder`, `TableColumn` | a column definition and an enum | `core.collection.table` |
| `ProblemNode`, `ProblemsTreeSource` | a diagnostic tree over the model above | `core.collection.tree` or beside `text.diagnostic` |
| `QuickPickItem`, `QuickPickEntry`, `QuickPickSource` | what a picker offers, not how it draws | `core.collection` |
| `MainMenuCommands` | command registrations, no widget | `core.command` |

**Audited by TYPE NAME, not by imports** — which is the check 6.2's ledger got wrong twelve times out
of thirteen, because a same-package reference has no import to find. Of 6.3's seventeen, **sixteen
are genuinely neutral** and the single hits in eight of them are javadoc prose. The exception is
**`ProblemsCommands`**, which resolves a `ProblemsPanel` out of a `CommandContext` six times over: it
is a copy.

#### D1 — the tool answers for nine of seventeen widgets

| Tag | through | ending | verdict |
|---|---:|---:|---|
| `listview` | 0 | 1 | shadow ok |
| `treeview` | 0 | 1 | shadow ok |
| `tableview` | 0 | 6 | shadow ok |
| `breadcrumbs` | 0 | 4 | shadow ok |
| `statusbarview` | **2** | 5 | **LIGHT (kind B)** |
| `notificationsview` | **2** | 5 | **LIGHT (kind B)** |
| `navigatorview` | **5** | 11 | **LIGHT (kind B)** |
| `quickpick` | **7** | 22 | **LIGHT (kind B)** |
| `problemspanel` | **17** | 14 | **LIGHT (kind B)** |

`problemspanel` is the most reached-through widget measured anywhere — seventeen rules select through
its structure against fourteen clean leaves.

**And eight name no tag at all**, so the tool has no answer for them: `menubarview`,
`commandpalette`, `notificationcard`, `preferences`, `processespopover`, `progressstatusitem`,
`treesearch`, `inspector`. That is the class-keyed gap 6.2's section already asks for, unclosed —
and 6.3 is where it stops being a nicety, because `TreeSearch` alone is 1,141 lines and sixteen
part names.

**A row is the exception to all of it.** `ListView` and `TreeView` are `shadow ok` by the sheets and
must still keep their rows LIGHT: a renderer's template is a *caller's* node, so it can no more live
in the widget's shadow tree than a `SplitView` pane can. Same reasoning, and the same conclusion 6.2
reached for panes and pages.

#### Accepts

The gallery's list, tree, table and palette pages as parity specs, and the new-engine gallery grows
the same five. **36 test files** name a 6.3 class — `ListView` 275 assertions, `TreeView` 115 — plus
`TreeSearchInstall`, `TreeQuery`, `VariableHeightStrategy`, `CommandPalette`,
`QuickPickQueryRetention`, `QuickPickResize`, `MenuBarView`, `ProblemsPanel`, `ProblemsMenu`,
`ProblemBandPrimary`, `Preferences`, `PreferencesKey`, `AppearanceSettings`, `ContextMenu`,
`ElementSettings`, `Keymap`, `ShippedKeymapDefaults`.

**Two of those cannot pass through `sendInputEvent`** and the invariant rows say so: *"a menu bar
must REMEMBER the focus owner"* (sixteen passing tests shipped that bug) and *"a `ListView` is the
tab stop of its own composite"*. Both need `consumeMouseEvent` at a POINT.

#### Rows it owns

The whole list/tree/search cluster — *"a list restoring focus to a row must never take it from a
CONTROL INSIDE one"*, *"a blur raised by ROW RECYCLING is not a user gesture"*, *"a row's inline
editor is primed ONCE PER EDIT"*, *"a tree's inline editor is built in `createTemplate`"*,
*"FILTERING REVEALS; HIGHLIGHTING DOES NOT"*, *"a tree that restores selection BY ITEM must clear the
index-based one first"*, *"a panel's FILTER and its SEARCH must share one notion of matches"*,
*"pass the `SearchQuery`, never the text"*, *"a search marks the matched CHARACTERS"*, *"a
`::highlight()` BAND must be cleared on the no-styles path"*, *"a shared row component must reach the
rows ITSELF"*, *"a search bar is either TRANSIENT or PERMANENT"*, *"a pane MINIMUM measured from
realised rows"* ×2, *"a recycled row must SWAP its data-driven classes"*, *"a row's slots are built in
`createTemplate`"* — plus the menu rows: *"a menu bar resolves commands against the FOCUSED element;
a context menu against the element that was CLICKED"*, *"the registry carries `enabled`; it never
filters"*, *"`MenuBuilder` is the only thing that turns commands into menu rows"*, *"a `MenuId.submenu`
declaration is PERMANENT"*, *"a menu MNEMONIC must not fire while a text field has focus"*.

#### Hazards, in the order they would be found

1. **The seam blocks six classes and 55 call sites.** Do it first, alone, and land it before a single
   widget moves — it is this batch's 6.0.
2. **Sixteen illegal moves.** Decide each neutral home before the codemod runs; a move applied and
   then reverted is what cost 6.2 an afternoon, and here it is sixteen times over.
3. **`ListView` (1,408 lines) is the first model-derived scroll extent.** `scrollExtent` exists for
   exactly this and has never had a consumer — its javadoc names a list overriding it with
   `model.size() * rowHeight` as the case it was written for. 6.2 mistook it for a content-size
   accessor and got `-1`; this batch is where it stops answering `-1`.
4. **`TreeSearch` (1,141 lines, 16 part names) with no D1 answer.** Close the class-keyed gap or read
   all sixteen by hand.
5. **`ProblemsPanel` (929 lines, 17 through-rules).** The most reached-through widget in the census;
   kind B all the way down, and nothing about it can be a part.
6. **Eighteen IMPORTANT writes, the most of any batch**, and unlike 6.2's they will not all be INLINE:
   a virtualised list computes geometry and hands it back, which is the `Measurable`/box-call half of
   §4.5 that 6.2 never had to exercise.

#### 6.3 — what shipped, and the four things it found

**Shipped.** All 44 classes, and the seam first: `CommandTarget` in `core.data` plus `KeymapScope` in
`ui.input.keymap`, which both engines implement — `UIElement` answers `getParent()` and the window's
providers, `UINode` answers `parent()` and nothing. That unblocked the six classes 6.2 could not
port, and `UIDocument` gained a `CommandRegistry` of its own to unblock the seventh
(`CommandPalette`). Then the collections into `widget.collection.{list,tree,table}`, the chrome into
six sub-packages, and the sixteen neutral models into `core.collection.{list,tree,table,pick}` and
`text.diagnostic`.

**Two interfaces, not one, and the reason is the package graph.** `core.data` may not name
`ui.input.keymap`, so the walk (`commandParent`, `scopeProviders`) is `CommandTarget` and everything
about keys (`keymapOrNull`, `consumesTextInput`) is `KeymapScope extends CommandTarget`. Collapsing
them either drags the keymap into `core.data` or leaves the command layer unable to walk.

**The chrome landed flat, and splitting it hit `LayeringTest` from an angle the test was right
about.** Twelve classes in one `chrome/` package is what the ledger's destination said, and a flat
package puts a palette, a menu bar, a problems tree, a notification stack, a preferences navigator
and a status bar side by side on no principle but the batch they arrived in. Adding the six
sub-packages to `LAYERS` — the ORDERED tier list — then made each one a layer *above* `chrome/`, and
`ChromeKinds`, the layer's own `NodeKinds` registrar, must name every widget in the layer by
construction: it came back as a layer reaching upward, naming ten things at once. `chrome/` is one
layer and a prefix already covers everything under it. **Ordering WITHIN a layer is a separate
question with its own list**, and `widget` remains the only layer that has ever needed one.

**`scrollExtent` got its first consumer, three milestones after it was written.** It shipped at 6.0
with none, 6.2 mistook it for a content-size accessor and got back the `-1` that means "ask the
boxes", and `ListView` is what its javadoc always named: a list realises a dozen rows of ten
thousand, so the boxes under it describe the WINDOW and the children genuinely cannot be asked. It
answers `model.size() * rowHeight` vertically and `-1` horizontally unless the list scrolls sideways
— the contract's own way of saying the children already know.

**Fifty-two inline fully-qualified names came across in the port**, across twenty files, and none was
a collision: they are simply what a codemod leaves behind when the class it rewrites was reached by
its full name rather than through an import. Worth a rule for 6.4 onward — the codemod should emit an
import rather than a qualified name, since the qualified form compiles perfectly and only shows up by
being read.

**Ten covering tests, and both of the two the invariant rows say cannot be written any other way.**
`aListIsTheTabStopOfItsOwnComposite` and `aMenuBarRemembersTheFocusOwnerThePressDestroys` are each
driven at a POINT, and each was checked against a deliberately broken build before being believed —
`FocusPolicy.NONE` on the list, the removed fallback on the bar. Both failed there. The other eight
are what is new about running on this engine rather than a port of the old suite, which still runs
against the old widgets and moves wholesale at 6.9.

**All sixteen newly-ported owners are light structure.** Not one hosts a shadow root, because every
one is a container a shipped rule reaches *into* — which is D1's answer read off the sheets rather
than guessed, and it settles the eight widgets `tools/port/classify.py` had no tag-keyed answer for
(`menubarview`, `commandpalette`, `notificationcard`, `preferences`, `processespopover`,
`progressstatusitem`, `treesearch`, `inspector`) without the class-keyed pass the section asked for.
Seventeen state adjectives stay kind C, each verified against the class that flips it.

### 6.4 — Canvas, graph, the shader graph · **XL** · after: 6.3

*Re-sized from **L**, and the stub's numbers were stale in both directions. Its two trees hold 35
files and 12,955 lines — more than any other batch — but two classes defer to 6.7, which leaves
**33 files and 11,479 lines**, a hair under 6.3. What makes it tractable rather than merely big is
that it is one vertical slice with exactly two seams to the outside, both small and both already
known.*

**Scope, from the ledger:** 33 files, **11,479 old-engine lines**, plus 2 files and 1,476 lines
deferred to 6.7.

| Group | The files | Files | Lines | Destination |
|---|---|---:|---:|---|
| The canvas | `CanvasView` 725 · `CanvasOverlayMove` 232 · `WorldRect` 77 | 3 | 1,034 | `widget.canvas` |
| The graph widget | `GraphView` 1,758 · `GraphNode` 633 · `NodePort` 488 · `NodeCreationMenu` 639 · `PortDefaultEditor` 395 · `NodeWireLayer` 285 · `GraphCommands` 275 · `NodeFieldBinder` 241 · `GraphSelection` 198 · `NodeFieldWidgets` 191 · `NodeWidgetFactory` 141 · `GraphConnection` 30 | 12 | 5,274 | `widget.graph` |
| The port types | `PortType` 95 · `PortTypeRegistry` 67 · `BasicPortType` 29 | 3 | 191 | **`graph.port`** — see D25 |
| The Blackboard | `BlackboardPanel` 1,360 · `PropertyPill` 296 · `ShaderPropertyNodes` 241 · `InlineRename` 166 · `CategoryHeader` 134 | 5 | 2,197 | `app.shadergraph.blackboard` |
| The previews | `MainPreviewPanel` 466 · `ShaderGraphPreviews` 247 · `ShaderNodePreview` 82 | 3 | 795 | `app.shadergraph.preview` |
| The field widgets | `ShaderPortArity` 284 · `ShaderColorFieldWidget` 112 · `ShaderVectorFieldWidget` 89 | 3 | 485 | `app.shadergraph.field` |
| The app root | `ShaderInspectorSections` 668 | 1 | 668 | `app.shadergraph` |
| The shader model | `ShaderGraphBridge` 494 · `ShaderPropertyForm` 246 · `ShaderGraphSettings` 95 | 3 | 835 | `graph.shader` — stays |
| **Deferred to 6.7** | `ShaderGraphEditor` 1,309 · `ShaderGraphContribution` 167 | 2 | 1,476 | `app.shadergraph` |

**Budget, measured** (`python tools/port/codemod.py --batch 6.4 --dry-run`): **24 copied, 9 moved,
~575 mechanical rewrites, 65 hand sites.**

Hand sites, by kind: **`stopPropagation` 21** · **internal child 19** · dynamic restructure 9 · resize
hook 6 · post-layout callback 5 · paint override 4 · drag ghost 1.

They cluster the way 6.3's did and in the same places: `BlackboardPanel` has four
`stopPropagation` sites, `NodeCreationMenu` and `MainPreviewPanel` three each. The nineteen internal
children are the batch's real work, and §D24 is what decides each one.

#### 1 — the two seams out, and why only two classes wait for them

**Fifteen of the seventeen shader classes port in this batch.** The exceptions are structural, not
incidental:

- **`ShaderGraphEditor` `implements FileDocument` and holds a `TextEditor source`** — 6.7 and 6.5
  respectively, and neither is a reference that can be stubbed: one is a supertype and the other a
  field.
- **`ShaderGraphContribution` names seven dock types plus `Workbench`, `DocumentType` and
  `FileDocument`** — it is the feature's registration with the shell, so it is by definition the
  class that knows about the shell.

Everything else — the whole Blackboard, all three previews, the field widgets, the inspector sections
— reaches nothing above `widget` and `chrome`. **Measured by comment-stripped import scan**, not by
grep: every other apparent reference to the dock, the workbench or the editor from `graph/shader` is
javadoc.

This is 6.2's shape repeated (six classes held back for 6.3's seam) and it is worth stating as the
general rule: **a batch defers the class that knows about the shell, never the feature.**

#### 2 — the package map, and the nausea it is fixing

The old tree has three flat packages and one of them is an application living inside a model:

```
com.crystalgui.graph            18 files — the MODEL. Correct where it is.
com.crystalgui.graph.shader     17 files, FLAT — an application, inside the model's package.
com.crystalgui.ui.elements.canvas    3 files
com.crystalgui.ui.elements.graph    15 files, FLAT
```

`graph.shader` is the offender twice over. It is flat — one directory holding an editor, a properties
panel, three previews, two field widgets, a compile bridge, a settings declaration, five inspector
sections and a rename box — and it is **inside the model package**, which reads as "the shader part of
the graph model" and is really "the application that edits shaders with the graph model". It imports
`ui.elements.dock` and `ui.elements.workbench`; a model package cannot.

```
com.crystalgui.graph                    THE MODEL — headless, engine-neutral, named by BOTH engines
  (root)        GraphDocument, GraphChangeset, GraphCodecs, GraphIds, GraphProperty, NodeBuilder,
                NodeData, NodeField, NodeMenuTree, NodeType, NodeTypeRegistry, EdgeData, PortRef,
                PortSpec, PortDirection, PropertyEdits, SetNodeFieldEdit, TypeCompatibility
                — unchanged, and NOT in port scope: nothing in it names the engine
  .port         PortType, BasicPortType, PortTypeRegistry                          ← NEW (D25)
  .shader       ShaderGraphBridge, ShaderPropertyForm, ShaderGraphSettings         ← what is left
                once the widgets leave, and it is exactly the shader MODEL

com.crystalgui.widget.canvas            CanvasView, CanvasOverlayMove, WorldRect
com.crystalgui.widget.graph             GraphView, GraphNode, NodePort, NodeWireLayer,
                                        GraphConnection, GraphSelection, GraphCommands,
                                        NodeCreationMenu, PortDefaultEditor, PortEditors (new),
                                        NodeWidgetFactory, NodeFieldBinder, NodeFieldWidgets

com.crystalgui.app                      APPLICATIONS — the layer the doctrine already names and
                                        nothing has enforced
  .shadergraph  (root)      ShaderInspectorSections, ShaderNodeLibrary (new);
                            ShaderGraphEditor + ShaderGraphContribution at 6.7
                .blackboard BlackboardPanel, PropertyPill, CategoryHeader, InlineRename,
                            ShaderPropertyNodes
                .preview    MainPreviewPanel, ShaderNodePreview, ShaderGraphPreviews
                .field      ShaderColorFieldWidget, ShaderVectorFieldWidget, ShaderPortArity
```

**`widget.graph` stays ONE package, and that is a finding rather than a shrug.** Read out of the
constant pool, nine of its twelve classes are a single mutually recursive cluster — `GraphView`
names `GraphNode`, `NodePort`, `GraphSelection`, `GraphConnection`, `NodeWireLayer`,
`GraphCommands`, `NodeWidgetFactory`, `PortDefaultEditor` and `NodeCreationMenu`, and six of those
name `GraphView` back. A sub-package split would have to cut one of those cycles, and
`WIDGET_TIERS` forbids mutual naming across tiers. Inventing `.core`/`.field`/`.create` here would
produce three packages that must all name each other, which is a directory listing pretending to be
a layering. Twelve cohesive files is `widget.overlay`'s size and `desktop.window`'s.

**`app.shadergraph`'s three sub-packages are cycle-free, and were checked that way.** `.field` <
`.preview` (`ShaderGraphPreviews` names `ShaderPortArity`); `.blackboard` names neither; the root
names all three. Two edges had to be cut to make that true, and both are D-decisions below, because
both are cases of a lower layer holding something that belongs to a higher one.

**`app/` gets one `LayeringTest` entry above `workbench`**, which closes the governance gap for every
application at once — `graph.shader` is named by nothing today because nothing in `LAYERS` covers it,
so a leaf widget importing the shader graph would pass. 6.7's `editor` and `example/machine` land in
the same prefix (`app.editor`, `app.machine`) rather than needing an entry each.

> **Not `chrome/`'s mistake again.** `app/` is ONE layer and its sub-packages are organisational;
> they get no entries of their own, for exactly the reason 6.3 recorded — a prefix already covers
> what is under it, and listing a sub-package makes the layer's own root a layer reaching upward.

#### D24 — `NodePort` is `shadow ok` and its editor slot is still a SLOT

`tools/port/classify.py`, on the shipped sheets:

| Tag | through | ending | verdict |
|---|---:|---:|---|
| `nodeport` | 0 | 54 | shadow ok |
| `shadergrapheditor` | 0 | 2 | shadow ok |
| `nodecreationmenu` | **8** | 12 | **LIGHT (kind B)** |
| `graphview` | **11** | 7 | **LIGHT (kind B)** |
| `graphnode` | **23** | 42 | **LIGHT (kind B)** |

**`graphnode` at 23 through-rules is the most reached-through widget measured anywhere in the
census**, past `problemspanel`'s 17, which 6.3's section called the record. Sixteen part names, and a
sheet reaches through most of them: kind B all the way down and nothing about it can be a part.

`nodeport` is the interesting one, and the standing exception applies to it. **A row is a caller's
node** — 6.3's rule for `ListView`, 6.2's for a `SplitView` pane — and a port's default editor is
exactly that: it comes from `PortType.createInlineEditor()`, which a *consumer* implements. So
`nodeport` may host a shadow tree for its dot and its label and **must expose a slot** for the
editor, or the one thing about a port that a third party contributes lands in a tree no rule can
reach. The same holds for `GraphNode`'s field widgets, which `NodeWidgetFactory` builds from a
caller's registration — but `graphnode` is kind B anyway, so it costs nothing there.

`canvasview`, `blackboardpanel`, `propertypill`, `categoryheader`, `mainpreviewpanel` and
`inlinerename` name no tag the sheets recognise, so the tool has no answer. 6.3 settled its eight the
same way and the answer was the same every time: a container a rule reaches into is light. Read them
by hand before the copy, and expect the same verdict for the four that are panels.

#### D25 — `PortType` is two things, and one method is what stops it being neutral

`PortType` has seven members. Six are facts about a type — `id()`, `label()`, `arity()`,
`arityLabel()`, `isCompatibleWith()`, `cssClass()` — and one, `createInlineEditor()`, returns a
`UIElement`. That single method is the whole reason the SPI cannot move to a package both engines
name, and it drags `BasicPortType` and `PortTypeRegistry` with it: the registry is neutral, the
record is neutral, and the interface they are written against is not.

**`createInlineEditor()` has exactly one caller** — `NodePort.setDefaultEditor(...)`. So the split is
a small one: the type moves to `graph.port` with its registry and its record, and the editor lookup
becomes `PortEditors` in `widget.graph`, keyed by port-type id, which is the shape `NodeWidgetFactory`
already has for node fields. Each engine registers its own, which is what makes the port types
nameable by both.

**Recommendation: split it.** It is the difference between three neutral files in the model and three
files that have to be copied into the widget layer and copied again at 6.9.

#### D26 — two edges point from the model up into the widgets, and both are registrations

`ShaderGraphBridge` is otherwise the cleanest class in the batch — a pure map from `GraphDocument`
onto CrystalGraphics' `CgShaderGraph`, no engine anywhere in it — and it contains two lines:

```java
ShaderColorFieldWidget.install();
ShaderVectorFieldWidget.install();
```

Its comment defends the *timing* and the defence is good ("building a shader node library is the
moment the shader domain's vocabulary has to exist… a colour field silently falling back to a GLSL
text box is the kind of miss nobody reports as a bug"). The timing is right and the placement is what
binds a model class to two widgets. **Keep the timing, move the knowledge**: a `ShaderNodeLibrary` in
the app root installs the field widgets and then calls `ShaderGraphBridge.asNodeLibrary(...)`.

`ShaderPropertyForm` — "the one place that knows the literal form" of a property's default — reads
`BlackboardPanel.KIND_COLOR` and `BlackboardPanel.KIND_OPTION` in three places. The *kind of a
property* is a model fact that a panel happens to declare. **Move the constants to
`ShaderPropertyForm`** and let the panel read them, which is the direction they already flow in
meaning.

Both are two-line changes and both are load-bearing: without them `.field` and `.blackboard` cannot
sit above the model, and `graph.shader` cannot be the neutral package the map above says it is.

#### D27 — all nine `move` rows are illegal as written, and the ledger is wrong in the other direction too

6.3's most expensive lesson, and 6.4 has it nine times. **`EngineBoundaryTest.theOldEngineNamesNothingOfTheNew`
scans everything that is not new-engine**, which includes `ui/elements/graph`, `ui/elements/canvas`
and `graph/shader` — all of which stay until 6.9. So:

| Row | Named by, and it stays until 6.9 |
|---|---|
| `GraphConnection`, `GraphSelection`, `NodeWidgetFactory` | old `GraphView` |
| `BasicPortType`, `PortTypeRegistry` | old `NodeWidgetFactory` |
| `WorldRect` | old `CanvasView` |
| `ShaderGraphBridge`, `ShaderGraphSettings`, `ShaderPropertyForm` | old `ShaderGraphEditor`, and five more |

**Nothing outside the batch names any of them in code** — every apparent external reference is
javadoc, checked with comments stripped — so the only obstacle is the old copy of the batch itself.
Which is the whole obstacle: a move into `widget.*` or `app.*` fails the boundary scan on the day it
lands. **Every one of the nine is either a copy, or a move into a package both engines may
name**, and the map above chooses the latter for the five that deserve it (`graph.port` ×2 —
`BasicPortType` and `PortTypeRegistry`, with `PortType` a copy until D25 lands; `graph.shader` ×3)
and the former for the four that are genuinely widgets (`WorldRect`, `GraphConnection`,
`GraphSelection`, `NodeWidgetFactory` — all four named by old widget code, three of them under 200
lines).

**And the heuristic is wrong here in the direction 6.2 did not see.** `ShaderPropertyForm` is marked
a MOVE, and by every test 6.2 devised it is one — it names no engine type, imports nothing from
`ui.*`, and reads as pure model. It reads `BlackboardPanel.KIND_COLOR` in three places, so it is
transitively a widget and can go nowhere the Blackboard cannot follow. 6.2's rule (*audit by TYPE
NAME, never by imports*) catches a class other classes are BOUND BY; this is a class bound TO
others, and only walking what it names finds it. **Both questions, both with comments stripped** —
and D26 is what makes this particular answer a move after all.

The mirror case is `ShaderGraphPreviews`, marked a copy for exactly one line —
`view.getRuntimeCache()`, which the new engine spells `box()`. One mechanical rewrite decides a whole
file's destination, which is worth knowing before reading `copy` as a statement about how entangled
something is.

#### Accepts

The gallery's graph page as a parity spec, and `cgui-new-gallery` grows a canvas/graph page. Test
files naming a 6.4 class: `GraphView` 293 assertions, `GraphNode` 168, `NodePort` 136, plus
`GraphEditing`, `GraphDocumentView`, `NodeCreationMenu`, `NodeField`, `NodeControlKit`,
`NodePortInlineEditor`, `CanvasView`, `CanvasOverlay`, `CanvasResize`, `ShaderGraphEditor`,
`ShaderGraphCommands`, `ShaderGraphBridge`, `MainPreviewPanel`, `SemanticOverlay`.

The batch's own tests, in the shape 6.3 settled — what is NEW about running on this engine:

- **A pan drag's source is the viewport, never the transformed plane** — the invariant row, and the
  first thing `toLocal`'s changed origin can break, since the plane moves under the drag. 6.2's
  `aDialogDragTracksThePointerOneForOne` is the same defect one widget earlier.
- **A positive scroll notch means the wheel rolled DOWN** — `CanvasView` shipped zooming the wrong
  way on the old engine and no test caught it, because a test written from the implementation agrees
  with it. Assert against `ScrollerView`'s direction, which is the engine's one statement of the sign.
- **The plane's `transform-origin` is pinned to `0 0`** — every world↔screen conversion is off by half
  a viewport times the zoom without it, and the picture stays internally consistent, so it looks
  plausible.
- **A wire's colour is read back out of the cascade** — `NodePort.typeColor()` returns the dot's
  computed `border-color`, which is how `graph.css` keeps the per-type palette out of Java. It is
  also the first thing in the port to read a computed style back, so it is the first that can silently
  answer the initial value.
- **An input port takes one edge and connecting to an occupied one REPLACES** — through the same
  `disconnect` a manual one takes, or undo will not know it happened.
- **A press on an already-selected node must not collapse the selection**, and a marquee selects what
  it TOUCHES.

#### Rows it owns

The canvas and graph cluster — *"a pan drag's source is the viewport, never the transformed plane"*,
*"a POSITIVE `MouseEvent.Scroll` notch means the wheel rolled DOWN"*, *"the canvas culls with
`opacity: 0`, not `display: none`"*, *"the plane's `transform-origin` is pinned to `0 0` at
IMPORTANT"*, *"a drag ends when THE BUTTON THAT STARTED IT is released"*, *"a press on an
already-selected node must not collapse the selection"*, *"a marquee selects what it TOUCHES"*,
*"selection is NOT undoable"*, *"a drag's own delta is the truth at drag end"*, *"a wire's colour is
read back out of the cascade"*, *"an input port takes ONE edge, an output MANY"*, *"`nodeport:blank`
means unconnected"*, *"a `graphnode` paints NO background of its own"*, *"click-focus targets the
exact element hit"*, *"a flex item with `flex-shrink: 1` contributes ZERO to its row's min-content
width"*, *"`getScrollWidth`/`getScrollHeight` measure DIRECT CHILDREN only"*, *"`resizeOriginLeft()`
reads the LIVE Taffy inset"*.

#### Hazards, in the order they would be found

1. **D25 and D26 first, before any copy.** Three edges decide five files' destinations, and a copy
   made against the wrong destination is a copy made twice — which is what cost 6.2 an afternoon at a
   third this scale.
2. **`GraphView` is 1,758 lines and names nine of its own package.** It is the largest single class
   in M6 outside the editor, and its whole neighbourhood is mutually recursive, so it ports as a unit
   or not at all. Copy the twelve together.
3. **`graphnode`'s 23 through-rules.** Kind B everywhere, sixteen part names, and 6.1's lesson says
   the failure is silent: a rule that stops matching produces an unstyled widget, not an error.
4. **Nineteen internal children and nine dynamic restructures**, the most of any batch relative to
   its file count, and every one is a D24 reading rather than a rewrite.
5. **`nodeport`'s slot.** It is `shadow ok` by the sheets and would be wrong shadowed whole — the
   default editor is a caller's node. The failure mode is the one M6.1 met four times: a light child
   of a shadow-rooted node is never composed, gets no box, and reports nothing.
6. **The two deferred classes leave the app rootless until 6.7.** Everything ported is reachable and
   testable on its own, but there is no *running* shader graph on the new engine until 6.7 — so 6.4's
   gallery page is the canvas and the graph widget, not the application.

### 6.5 — The editor · **XL** · after: 6.3

**Ports.** `TextEditor` (6,166 lines, 194 public members, 31 parts, 35 scroll sites, 13 geometry
reads, 10 IMPORTANT writes, 8 `setHitTest`, 7 `HighlightRegistry` uses, the one `setPointerCapture`),
`EditorViewPart` + ten parts + `DecorationPool`, `CompletionPopup`, `CompletionSession/Ranking/Recency`,
`DocumentationPopup` (1,516 lines, 21 parts, 29 `setDisplayed`), `SearchReplaceBar`, `EditorFind`,
`EditorFolding`, `EditorSuggest`, `EditorDiagnostics`, `EditorLanguageFeatures`, `HoverDocumentation`,
`DiagnosticActions`, `DiffDecorations`, `EditorCommands`. Sheet: `editor.css` (179 rules, 81 parts,
60 `::highlight`).

**Accepts.** `cgui-completion`, `cgui-text`; `TextEditor` 620 and every `Editor*` test
(`EditorFind`, `EditorFindReplace`, `EditorFolding`, `EditorView`, `EditorReveal`,
`EditorIndefiniteHeight`, `EditorTypingHighlight`, `EditorHighlightCache`, `EditorFrameCost`,
`EditorSelfSave`, `Squiggles`, `ErrorStripe`, `QuickFixBulb`, `InspectionWidget`,
`HoverDocumentation`, `DocumentationPopup`, `Completion*`, `CodeActionApply`, `GoToDefinition`,
`DiagnosticTracking`, `SyntaxColours`, `UnnecessaryTag`, `MirroredGutter`, `HoverActionBand`,
`SemanticOverGrammar`); **`EditorFrameCostTest` must not regress** — the 151ms open-frame targets in
memory are the standing goal.

**Destination.** `widget.editor` + `.view` + `.suggest` + `.doc` + `.find`.

**Budget, measured:** **26 copied, 8 moved, 394 mechanical, 77 hand sites** — plus D22, which is the
batch's real cost and appears in no count.

**Rows it owns.** Every `TextEditor` row: *"the ERROR STRIPE is the one part that is honestly
O(document)"*; *"a NaN poisons a whole layout silently"*; *"semantic tokens REPLACE grammar
tokens"*; *"a recovered parse re-colours the rows it SWALLOWS"*; *"a pooled gutter arrow's row is read
per frame"*; *"`TextEditor.getScrollWidth` is a pure accessor; the scan is once a frame"*; *"a view
part is a piece of the editor, not a client of it"*; *"`measuredRows` may be invalidated one row at a
time only when the line count is unchanged"*; *"a completion session is about a WORD"*; *"position a
popup from `getWindowX/Y`, never `localToWorld`"* — **marked**: there is one chain now; *"an
absolute child of a scroller still SCROLLS"* (`scrollExempt`); *"a widget that eats a chord it has no
use for"* (marked at 5.6; `claimsChord` states the want list); *"`stopPropagation()` is
`stopImmediatePropagation` WITHIN a phase"* (marked; the Down handler is fixed in its own commit);
*"a paint method may skip the DRAW, never the METHOD"*.

**Hazards.** The largest single risk in M6. The view parts place rows and decorations with IMPORTANT
`left`/`top` and re-run layout; D22 moves rows to transforms, which is a behaviour-preserving change
to the hottest path in the application and the one place the port may honestly become a rewrite of a
view layer. `textViewport`, `gutter`, `foldColumn` and the layers are `scrollExempt` and
`setHitTest(false)` in a specific arrangement (*"not a child of the gutter, and it cannot be one"*).
Pointer capture, the caret ticker, `HighlightRegistry` per view line (*"a `HighlightRegistry` belongs
to a `UIText`, not to a document"*) all move. Do this batch after 6.3, never before — the list
machinery it duplicates by design (`TextEditor`'s own note) is the rehearsal.

### 6.6 — The desktop · **XL** · after: 6.2 (`Dialog`; `Popover` landed early in 6.1), 6.0's D16 hooks

**Ports.** `Desktop`, `WindowFrame` (106 members: chrome, content slot, `adoptChrome`/
`releaseChrome`, the overlay slot, `attachOwned` ×2, `releaseOwned`, `setOwnerWindow`, tool-window
flag, `WINDOW_FRAME` data key, attention/badge/progress, pin, `focusDelegate`, state/policy/key/discard
guard, `requestClose`/`hide`/`show(persisted)`/`destroy`/`dispose`, recorded size, maximise/restore/
fullscreen, `moveTo`/`resizeTo`/`snapTo`, wanted vs placed geometry, `isAnimating`, `minimize`,
`isPlaced`, `pressedInContent`, `hidingWithOwner`), `WindowRegistry` (open order, MRU, bounded
retention with the dirty exemption), `WindowState`, `WindowPolicy`, `Taskbar`, `TaskbarEntryMotion`,
`TaskbarPreviews`, `TaskbarDesigner`, `WindowPreview`, `WindowThumbnail`, `WindowSnapshot`,
`WindowSwitcher`, `WindowAnimator` + `WindowAnimation` + `WindowGeometryAnimation` + `WindowMotion`,
`WindowMove`, `WindowKeyboardMove`, `SnapZones`, `SystemMenu`, `WindowChrome`, `WindowCommands`,
`DesktopCommands`, `DesktopPresentation`, `DesktopSession`, `ScreenOverlay`; and `UIWindow`'s host
surface onto `UIDocument` (§4.2's last row). Sheet: `desktop.css` (108 rules, 74 parts).

**Accepts.** `cgui-desktop` (**needs the harness pointer reconciled** — it is not at `b5a2219`),
`cgui-snapshot-probe`; the 20 desktop tests plus `DesktopActivation`, `DesktopLifecycle`,
`DesktopMaximise`, `DesktopModality`, `DesktopTaskbar`, `DesktopWindow`, `WindowAnimation`,
`WindowCaptionChrome`, `ToolWindowFloat`, `ToolWindowIsNotACitizen`, `ToolWindowPlacement`
(`WindowFrame` 352, `Desktop` 246, `WindowState` 192).

**Destination.** `desktop`, `desktop.window`, `desktop.motion`, `desktop.taskbar`, `desktop.switcher`.

**Budget, measured:** **19 copied, 8 moved, 409 mechanical, 49 hand sites** — every paint and mirror
site in the port is here or in 6.4, and freeze-replacing-detach appears in none of them.

**Rows it owns.** The ~65 compositor rows. Structural ones that change meaning: **hide is freeze**
(*"HIDE IS DETACH"* in every row that says it — `WindowState.HIDDEN`'s javadoc is rewritten:
retained AND frozen, with no detach; the row *"a window's geometry must be captured BEFORE it leaves
the tree"* is marked — nothing leaves); *"a raise is a `z-index` assignment and NEVER a child-list
move"* (`box().setZIndex` — the row's reason, un/registerElement over the subtree, no longer exists,
but the rule holds because a move still fires observers); *"a window's modal is OWNED by it, never
promoted"* (a hosted box in the frame's overlay slot); *"`WindowFrame.hide()` may only delegate to a
WINDOW LAYER"* (marked — hide is `lifecycle().freeze`); *"a STYLESHEET candidate outlives the element
being reparented"* (unchanged — the cascade is shared); *"`markAsInternal()` RECURSES, so RE-ADDING a
container"* (marked at 5.6); *"a window is drawn whether or not it has been placed"* (placed on the
frame after the one pass); *"a freshly allocated FBO loses the first draw"* (unchanged —
`WindowSnapshot`); *"drawing a subtree a SECOND time corrupts hit-testing"* (marked — mirrors);
*"an animation's clock starts on its first tick"* (structural in `Animation`); *"a window animates its
LIVE contents, not a photograph"* (`BoxTree.mirror` makes the photograph unnecessary for the
animation exactly as the row found); the four `WindowAnimation` rows about `IDENTITY` snapping, peak
velocity, GNOME's timings, one mechanism per motion — **all unchanged**, re-hosted on `Animation`;
*"a gesture's STATE changes on the press; only the picture waits"*; *"an owner takes its owned windows
down with it"*; *"a cascade written in `hide()` runs at the END of the owner's animation"*; *"a
maximised window restores on the first MOVEMENT"*; *"a snap zone is read from the POINTER"*;
*"fullscreen needs NO geometry of its own"*; *"a taskbar entry is not INSIDE the window it stands
for"*; *"a taskbar route must open into the ENTRY's window"* (a frozen frame is still IN the tree
now — the row's `getAttachedWindow() == null` cause is gone, mark it); *"Restore and Maximize are
TWO rows"*; *"a window command resolves its subject from the CONTEXT"*; *"'is this panel on screen'
has ONE answer"*; *"CLICK-FOCUS LANDS ON THE FRAME BEFORE ANYTHING IS DISPATCHED"*; *"a PRESS IN A
WINDOW'S CONTENT HAS ALREADY DECIDED WHERE FOCUS GOES"*; *"a desktop-scoped close watcher is
UNREACHABLE while any window is active"* (the `Dismiss` cascade order); *"a held-modifier gesture
polls the modifier"*; *"a MODE that intercepts keys must take only the keys it ACTS on"* (modes);
*"a full-size overlay is safe to make HITTABLE exactly when it is `display: none`"*; *"a modal
gesture that is not an ELEMENT gets no keys"* (modes); *"a STATIC 'do this on open' flag on a
`GuiScreen` must be CONSUMED"* (6.9); *"a first `openWindow` does not go through `show()`"*;
*"every live window and every window worth SHOWING are different questions"*; *"a caption shows
whatever the taskbar shows"*; *"a window's content slot is a `ScrollerView`"*; *"a window nobody
placed opens centred"*; *"a frame's content slot is not the fill idiom"*.

**Hazards.** Freeze replaces detach, and **every consumer that read "hidden" as "not in the tree"
must be found**: `DesktopSession` (*"recordedWidth, never the measured box: a HIDDEN window is
detached, so its box is zero"* — a frozen frame has no box either, so `recordedWidth` stays);
`Taskbar` (*"a taskbar route must open into the ENTRY's window … HIDE IS DETACH"*);
`ToolWindowManager` (*"asking the frame about a closed panel asks a corpse"* — destroy still
destroys); `WorkspaceClient`'s drain ticker (*"a drain ticker returns false when its element leaves
the tree"* — becomes the hook dropped by freeze, re-registered on thaw per D10); `UIWindow.enterHudMode`
(hides unpinned windows — freezes them). `WindowFrame.paintChildren` (the owned slot and the
snapshot) has no counterpart — §4.4. The `Desktop`'s geometry written at IMPORTANT (*"must not be
movable by a stylesheet"*) becomes UA-origin rules on the D16 layers.

### 6.7 — Workbench, dock, the shell, the applications · **XL** · after: 6.3, 6.5, 6.6

**Ports.** `Workbench` (3,212 lines, 76 members), `WorkbenchRegions`, `RegionHost`,
`RegionDropOverlay`, `RegionDropZones`, `SplitFill`, `StripeView`, `StripeRail`, `ViewContainer`,
`ViewContainerRegistry`, `ToolWindowFrame`, `ToolWindowManager`, `ToolWindowState`,
`ToolWindowLayout`, `ToolWindowType`, `ProjectFileTree`, `FilesRenderer`, `ExplorerDragAndDrop`,
`ExplorerEditing`, `ExplorerFind`, `ExplorerCommands`, `ExplorerClipboard`, `WorkspaceTreeSource`,
`GoToFile`, `DiffView`, `MergeView`, `ConflictDialog`, `OpenDocuments`, `FileDocument`,
`TextFileDocument`, `DocumentType`, `RecentFiles`, `WorkbenchSession`, `WorkbenchSettings`,
`WorkbenchMenus`, the decorations; `dock/` — `DockArea`, `DockGroup`, `DockWindow`, `DockBannerBar`,
`DockInput`, `DockCommands` (the model — `DockLayout`, `DockNode/Branch/Leaf`, `DockPath`,
`DockPlacement`, `DockDropZones`, `DockPanelRegistry/Descriptor/Ref`, the codec — is headless and
only retypes `DockPane.view()`); `editor/CrystalEditor` + commands; `language/…/run/view/`
(`RunPanel`, `RunRail`, `ScriptWorkbench`, `RunConsoleView`, `RunDecorations`, `RunIndicators`,
`TailFollow`, `MappingCommands`, `RunPanels`); `example/machine/ui/`. Sheets: `workbench.css` (228
rules, 162 parts — the largest), `runpanel`'s rules wherever they live.

**Accepts.** `cgui-dock`; `Workbench` 316, `DockArea` 184, `DockLayout` 180, `DockGroup` 118,
`ProjectFileTree` 116, `DockPlacement`, `DockTearOut`, `DockLazyTab`, `DockPaneLifecycle`,
`DockTabPresentation`, `DockActivePanelEvent`, `DockBanner`, `DockedEditorFace`, `StripeView`,
`StripeTearOut`, `ExplorerCommands`, `ExplorerInteraction`, `ProjectTree*`, `RegionDropZones`,
`Workbench*` (`CloseReleases`, `FileTab`, `ProjectSources`, `Reconnect`, `RegionWeight`, `Session`,
`Viewer`), `CrystalEditorPanels`, `DiffView`, `MergeView`, `GoToFile`, `ProjectIndex`, `ExternalChange`,
`UndoWiring`, `FrameThreadOwnership`, `StyleSettlesWithinTheFrame`, the `language/` run-view tests.

**Destination.** `workbench` + `.dock` + `.toolwindow` + `.explorer` + `.document` + `.diff` +
`.decoration`; `editor` and `example.machine` and `language.run.view` as they are. The dock model
(25 files) and the sessions/settings/tree-source classes are **moved**.

**Budget, measured:** **44 copied, 39 moved, 566 mechanical, 35 hand sites** — the largest batch by
lines and the smallest by hand work, because the workbench is mostly logic over widgets it does not
draw, and half of it is engine-neutral enough to move rather than copy.

**Rows it owns.** The ~45 workbench/dock rows: *"the HOST is the truth about which region half holds
a panel"*; *"a tool window's MODE lives on its placement record"*; *"the tear-out zone is the
workbench's MIDDLE"*; *"content with its own top bar goes in a window's CAPTION"* (`WindowChrome` as a
slot move — D-§4.4); *"a drop must activate and focus what it received, in `performDrop`"*; *"a drag
never completes a click, so the thing being dragged is never SELECTED"*; *"the thing being dragged
leaves the list … `InsertionMarker.withdraw`/`restore`"*; *"a band derived from a hideable element
vanishes with it"*; *"going into a FRAME is leaving the region"*; *"a windowed thing asked for before
the tree HAS a window fails silently"* (`UIDocument` exists from construction — re-read);
*"a rebind nothing RE-ASKS FOR"*; *"a client-side memo of what the SERVER was told"*; *"a reconnect
repairs the PROTOCOL immediately and the VIEW on the frame the view comes back"* (freeze/thaw);
*"a focusable CONTAINER is a wall"* (marked — `delegatesFocus`); *"a window is activated a frame
BEFORE content that builds on a deferred rebuild exists"*; *"adding a child from inside
`onWindowChanged` … inserts a Taffy node into a parent whose children are still being registered"*
(structural now — `UIDocument` queues); *"`registerCommands` runs from the INSTANCE INITIALISER"*
(gone — §2.2); *"a bound method reference on a MUTABLE field captures the value"*; *"a torn-out editor
window is in NEITHER record"*; *"a window that is not a descendant of anything cannot be attributed
by WALKING"*; *"a tool window's frame is built per show and DESTROYED per hide"*; *"ONE FACT, ONE
RECORD"*; *"never name a class `__content__` in a descendant selector"* (D1 — the three `__content__`s
become three names); *"the fill idiom"* rows (unchanged — CSS facts); *"a `SplitView` pane is a flex
COLUMN"*.

**Hazards.** `Workbench` (3,212) and `ToolWindowManager` are where hide-as-detach was most relied
on. The `ViewContainer` header moving into a caption and back (*"came home still carrying the
caption's `padding-left: 0`"*) is a slot move now and the `ADOPTED_CHROME_CLASS` workaround can go —
but only after the cascade-shared retention of `appliedByElement` is confirmed to cover a
`moveTo`. `DockArea`'s *"rebuilds are deferred by a frame, always"* stays. `RunPanel` is in another
module and its tests run under `:language:test` with natives — skip cleanly there.

### 6.8 — Networking on the new engine · **L** · after: 6.7

**Ports.** `ServerUiSession<N,T>`, `ClientUiSession<N,T>`, `ClientUiSessions`, `UiWindowMux` over
`TreeSource<N>` + `NodeMirror<N,T>` (D11); `UINodeMirror` as the only mirror; `ElementNodeMirror`,
`ElementTreeSource` and `UIDescriptionCodec` retired; `net/window/` retyped (D23); `ScopedSheets`'s
selector rewrite replaced by native `@scope` (M4's own note); `SheetSupply` unchanged;
`Attribute.REPORTS` on the wire; the contracts' bounds loosened; `ui/projection` retyped;
`CgUiWindowMount` retyped; `ViewCommands` retyped. D4 (the master plan's row) applied with its
governance test.

**Accepts.** `cgui-workspace`; the seam suite unchanged on `UINodeTreeSource`;
`MirrorIsEngineAgnosticTest`; every `net/` and `net/window/` test; `WidgetContractRoundTripTest`;
`ScopedSheetParseTest` (now asserting `@scope`); the two-viewer fixtures; `serverSmoke`.

**Destination.** `net.window` as it is; the sessions in `net` as they are.

**Budget, measured:** **9 copied, 6 moved, 91 mechanical, 0 hand sites** — and the count is
misleading, because the work is the generic retype of two files the codemod does not touch
(`ServerUiSession` 1,457 lines, `ClientUiSession` 903), which is a reading of thirty `UIElement`
references rather than a rewrite.

**Rows it owns.** The networking rows are untouched in substance — they are about the mirror, which
M2 made engine-agnostic for exactly this day. Three change spelling: *"a SCOPING PREFIX is a
descendant combinator"* (native `@scope` has "this element or below" — the row's whole problem
disappears, mark it); *"a COMMENT between two rules is not the next rule's selector"* (the rewrite
that mis-parsed it is deleted); *"a NETWORKED ELEMENT'S IDENTITY IS NO LONGER ITS POSITION"*
(unchanged — `UINodeTreeSource` allocates the same way).

**Hazards.** The description format changes from `UIDescriptionCodec`'s to `UINodeMirror`'s;
`ContentHash` keys change; no mixed-engine wire exists (one jar) so nothing has to interoperate, but
every recorded fixture in `net/` tests that embeds a description is regenerated. `Networked`'s
`mayClose` vs `UINode.requestClose` — D23.

### 6.9 — Cutover and deletion · **M** · after: 6.8

**Contents.** The harness defaults to `--engine=new`; `CgUiScreen`, `CgUiHud`, `CgUiInput`,
`CgUiOverlayInput`, `Mc1710Workspace` and the probes on `UIDocument` (the F6 flag row, the pause row
and the two-process rows are the in-game acceptance); `serverSmoke`. Then the deletion, in the order
the master plan's ledger names: the old `ui/` core (`UIElement`, `UIWindow`, `TopLayer`, `Ui`,
`UIResizer`, `AnchoredPlacement`'s old half, `UIInputHandler`, `UIDragController`,
`UITreeTraversal`, `ElementRegistry`, `UIFrameTicker`), `CgUiPaintContext.mirrored`, the internal
flag and its 210 sites' old form, `importantPipeline` (the method stays for authors; the boundary
test's forbid list becomes *the whole tree*), `WindowChrome`'s flag juggling, the four promotion
special cases, the second coordinate chain, `Disposer` as a second tree, `UiThread` as a marker,
`ui/shadow`, `ElementTreeSource`, `ElementNodeMirror`, `UIDescriptionCodec`, `EventListenerGroup.emitTarget`,
the `M5: no counterpart` rows' subjects. `EngineBoundaryTest` becomes a no-old-engine assertion.
`AGENTS.md`: the invariants table pruned of every marked row and every row that now describes
nothing (**the count is the metric**); the Stack 1–4 sections rewritten for the three trees;
`CGUI_WIDGETS.md` §0 rewritten (internal children → shadow parts; the class list → the ledger);
`CGUI_STYLE_RENDER_PIPELINE.md` for `::part`/`exportparts`/`@scope`; `CGUI_ENGINE_PORTING.md`
deleted (M8's job, done here because there is nothing left to port).

**Accepts.** Every surviving behaviour test green; every scene green; the game runs
(`runClient`, `serverSmoke`, the session probe); `PortLedgerTest` reports no *pending*;
`SheetPortTest` reports no `__` anywhere in a sheet.

---

## 6. Dependency view

```
M5 ──► 6.0 machinery + ledger + fixtures
         ├─► 6.1 leaves ───────────► 6.2 composites + overlays ──► 6.3 collections + chrome
         │                                  │                            ├─► 6.4 canvas + graph (+ shader graph)
         │                                  │                            └─► 6.5 editor
         │                                  └─► 6.6 desktop (needs 6.0's D16 hooks, Dialog, Popover)
         └───────────────────────────────────────────────────────────────────┐
                                    6.3 + 6.5 + 6.6 ──► 6.7 workbench + dock + apps ──► 6.8 networking ──► 6.9 cutover
```

6.4 and 6.5 can be worked in either order after 6.3; 6.6 can start after 6.2 and run beside 6.3–6.5.
Nothing in 6.7 starts before the desktop is on the new engine, because the workbench's tool windows
are windows. The game is on the old engine until 6.9.

---

## 7. What M6 does not do

`mc1201` (D10). Virtualised collections on the wire, workbench citizenship for networked panels, the
`LocalOnly` markers (M7). The display-list model. A rewrite of `TextEditor`'s view layer beyond what
D22 forces — if D22 turns out to need one, it is its own plan. Any behaviour change that is not
named as a fix in a batch's row list. The `taffy/` fastutil replacement. Anything in `text/`,
`language/` beyond `run/view`, or CrystalGraphics.

---

## 8. What "done" measures

| Metric | Today | M6 end |
|---|---|---|
| Classes extending `UIElement` | 87 widgets + 7 shader-graph + 2 run-view + 2 example + 1 app | **0** |
| `addInternalChild` sites | 210 | 0 |
| `__part__` names in sheets | 497 | 0 (kind A → `::part`, B and C → plain classes) |
| `importantPipeline` sites in the widget layer | 117 | 0 |
| `getRuntimeCache()` sites | 185 | 0 |
| Tags a sheet names without a registered `Name` | 32 | 0 |
| Selectors of the form `a::part(x)::part(y)` | — | 0, asserted |
| Test files on the old fixture | 164 | 0 |
| Scenes green on `--engine=new` | 1 (parity) | all, including `cgui-desktop` |
| `EngineBoundaryTest` OLD list | 14 entries | empty; the test asserts the old engine is gone |
| Invariant rows marked or describing nothing | 12 | pruned to 0 (the count of remaining rows is reported) |
| `computeLayout` per scrolled frame in the editor | 1 + (a re-place per scroll) | 0 re-places (D22) |
| The game | old engine | new engine, `serverSmoke` green |
| Ported files written from scratch | — | **0** — every one is the codemod's copy; 6.0's machinery is the only new code |
| Hand-edited sites | — | ≈443 (§2.7), held per batch to the budget line in §5 |
| Upward references across the layering (§2.6) | untested | 0, asserted by `LayeringTest` |

---

## 9. Risks, in the order they would be found

1. **The ledger is wrong about a kind.** Found at the first sheet rewrite under that name. The
   census cross-check (§0.4) catches most; reading the widget catches the rest. Cheap to fix at 6.0,
   expensive at 6.7 — which is why the ledger is complete before 6.1.
2. **`Focus.order(scope)` over the composed subtree is too slow on the gallery.** The old engine
   memoised `hasFocusableDescendant`; the new one walks. Measured at 6.1 on the gallery's node count;
   the answer is a per-scope cache invalidated by structure changes, which `UIDocument` already reports.
3. **The top-layer host box orders by z.** `Box.children()` sorts by z-index; the top layer stacks by
   insertion. Found by `TopLayerTest` at 6.1 (`Tooltip`). The fix is a host flag, at 6.0.
4. **D22 changes editor row placement and something reads a row's `top`.** `EditorViewTest` and
   `EditorFrameCostTest` at 6.5. If the view parts genuinely need inset placement, the fallback is
   INLINE `top` per row and one layout per scroll frame — the old cost, honestly stated.
5. **Freeze does not cover a consumer that meant detach.** Found in the game, not in a test, the way
   every hide-is-detach bug was. The list in 6.6's hazards is the audit; `runClient` after 6.9 is the
   check.
6. **`exportparts` chains deeper than two.** A caption's adopted menu bar's button's label. Found at
   6.6/6.7. The answer is D1: an adopted menu bar is kind B by definition (a caller's content), so
   the chain is one deep.
7. **The harness pointer.** `cgui-desktop` cannot be run until the submodule is reconciled with core's
   `native-content-slots`. It gates 6.6's visual acceptance and nothing else; the tests do not wait on
   it.
8. **A test that asserted structure was asserting behaviour.** 56 files read `getChildren()`; some
   of them were checking that a part exists in a position because that position was load-bearing
   (`describedChildren()` indices on the wire). Each deletion is reviewed against the row it might
   have pinned.
9. **The session retype leaks the old engine back in.** `ServerUiSession` at 1,457 lines has thirty
   `UIElement` references; a generic `N` that accidentally requires `getChildren()` from `N` is a
   `UIElement` bound by another name. `MirrorIsEngineAgnosticTest`'s twelve-line `UINode` is the
   counter-fixture — the sessions must compile against it.
10. **A codemod that is wrong once is wrong two hundred times.** Found on the first batch, which
    is why 6.1 is the best-covered code in the repository and why one file per transformation is
    diffed by eye before the rest runs. The structural mistakes — a copy reaching the old engine,
    an upward reference — are caught by `EngineBoundaryTest` and `LayeringTest` on the same commit.

---

## Appendix A — the per-file port matrix

One row per file in port scope: lines, supertype, part count, and the engine mechanisms it reaches
(the census keys from §0.3 — `tick` ticker, `imp` IMPORTANT writes, `inl` INLINE pipeline, `top`
top layer, `hit` hit-test flag, `close`/`pop`/`modal` the dismiss and modality stacks, `anchor`
placement, `resize`, `disp` Disposer, `data` context, `sexempt` scroll-exempt, `paint` overrides,
`layout` post-layout callback, `window` attach callback, `contract`, `drag`, `ghost`, `mirror`,
`idyn` dynamic internal restructure, `intern` internal children, `display` `setDisplayed`, `ovl`
overlay host, `cmd` command/keybinding hooks, `keymap`, `settings`, `hl` highlights, `job`
scheduler, `trans` transitions, `ctx` paint context, `text` text stack, `draw` drawables, `scroll`,
`focus`, `geom` geometry reads, `stopp` `stopPropagation`, `query`, `transform`, `capture`,
`state` observer notifications, `undo`, `signal`, `prop`). Counts are call sites.

```
ui/elements/Button                                 208  ext=UIElement          parts= 3  hit:3 contract:1 idyn:5 intern:5 signal:2
ui/elements/Checkbox                               156  ext=UIElement          parts= 1  hit:2 contract:1 intern:3 state:2 signal:3
ui/elements/CheckboxGroup                           58  ext=                   parts= 0
ui/elements/ColorSelector                          767  ext=UIElement          parts=13  hit:5 contract:1 drag:1 intern:14 draw:1 geom:11 signal:2 prop:2
ui/elements/Dialog                                 605  ext=UIElement          parts= 7  tick:4 imp:2 inl:1 top:4 hit:2 inert:1 modal:2 close:8 resize:5 contract:1 drag:1 intern:4 focus:4 geom:4 stopp:1 signal:2
ui/elements/DialogManager                          144  ext=                   parts= 0
ui/elements/DragGhost                              248  ext=UIElement          parts= 5  imp:3 hit:2 ghost:11 intern:5 draw:9
ui/elements/Dropdown                               212  ext=Button             parts= 2  pop:1 contract:1 intern:1 state:4 signal:3
ui/elements/InputDialog                            178  ext=                   parts= 2  tick:1 imp:2 ovl:1 focus:3 geom:2 undo:1
ui/elements/InsertionMarker                        468  ext=UIElement          parts= 1  imp:10 hit:1 ghost:1 idyn:2 intern:3 geom:12
ui/elements/MarkupView                            1034  ext=UIElement          parts=19  tick:3 imp:2 paint:1 layout:1 ctx:3 geom:9 stopp:1 signal:2
ui/elements/Menu                                   572  ext=Popover            parts= 3  tick:3 hit:3 pop:1 anchor:2 contract:1 intern:3 focus:4 stopp:1 signal:2
ui/elements/MenuItem                               235  ext=Button             parts= 5  hit:2 contract:1 keymap:1
ui/elements/Popover                                519  ext=UIElement          parts= 1  tick:3 imp:2 top:4 close:3 pop:9 anchor:6 resize:3 layout:1 contract:1 intern:1 ovl:1 focus:3 signal:2
ui/elements/ProgressBar                            229  ext=UIElement          parts= 2  tick:6 imp:2 hit:2 window:1 contract:1 intern:2 state:2
ui/elements/Scroller                               385  ext=UIElement          parts= 5  tick:2 imp:1 drag:1 intern:3 geom:5 signal:5
ui/elements/ScrollerView                           307  ext=UIElement          parts= 3  tick:2 imp:4 sexempt:2 layout:1 intern:4 trans:3 scroll:22 geom:2 stopp:1
ui/elements/SearchField                            284  ext=UIElement          parts= 9  imp:1 hit:1 contract:1 intern:7 focus:1 stopp:1 signal:2
ui/elements/Slider                                 316  ext=UIElement          parts= 3  imp:2 hit:1 contract:1 drag:1 intern:2 geom:6 stopp:1 state:3 signal:3
ui/elements/SplitView                              877  ext=UIElement          parts= 6  imp:1 contract:1 drag:1 idyn:6 intern:2 focus:1 geom:2 stopp:1 signal:3 prop:2
ui/elements/Switch                                 135  ext=UIElement          parts= 2  hit:2 contract:1 intern:3 state:1 signal:3
ui/elements/SymbolIcon                             221  ext=UIElement          parts= 3  hit:3 intern:3 display:4
ui/elements/Tab                                    247  ext=Button             parts= 2  imp:1 contract:1 idyn:1 intern:2 signal:2
ui/elements/TabView                                605  ext=UIElement          parts= 9  imp:1 layout:2 contract:1 intern:6 scroll:19 focus:1 geom:1 stopp:3 signal:3
ui/elements/TextField                             1240  ext=UIElement          parts= 0  tick:4 paint:1 contract:1 drag:1 intern:1 ctx:2 text:4 geom:4 stopp:3 state:1 signal:3 prop:4
ui/elements/Tooltip                                605  ext=UIElement          parts= 1  tick:5 imp:1 top:5 hit:3 anchor:6 paint:2 layout:1 contract:1 idyn:1 intern:3 geom:3
ui/elements/UIText                                1244  ext=UIElement          parts= 1  imp:6 paint:1 layout:1 contract:1 hl:5 ctx:7 text:33 draw:3 geom:10 state:2 prop:3
ui/elements/WidgetCensus                           227  ext=                   parts= 0  ghost:2
ui/elements/canvas/CanvasOverlayMove               232  ext=                   parts= 0  inl:1 resize:5 drag:1 geom:14 stopp:1
ui/elements/canvas/CanvasView                      725  ext=UIElement          parts= 2  tick:3 imp:4 inl:2 top:1 sexempt:1 paint:1 layout:1 drag:1 intern:4 ovl:1 scroll:1 geom:12 stopp:3 query:1 signal:2
ui/elements/canvas/WorldRect                        77  ext=                   parts= 0
ui/elements/chrome/Breadcrumbs                     166  ext=UIElement          parts= 4  hit:2 intern:5 display:4 draw:2 stopp:1 signal:2
ui/elements/chrome/ChromeCommands                   63  ext=                   parts= 0  data:4 undo:1
ui/elements/chrome/CommandPalette                  151  ext=                   parts= 0  data:1 keymap:1 focus:1
ui/elements/chrome/ContextMenu                     309  ext=                   parts= 0  data:1 focus:1 geom:2 stopp:1 undo:2
ui/elements/chrome/MainMenuCommands                 89  ext=                   parts= 0  data:4
ui/elements/chrome/MenuBarView                     645  ext=UIElement          parts= 6  tick:4 imp:2 hit:2 layout:1 window:1 intern:7 hl:1 focus:3 stopp:4 signal:2
ui/elements/chrome/MenuBuilder                     231  ext=                   parts= 0  ovl:2 keymap:1
ui/elements/chrome/NavigatorView                   620  ext=UIElement          parts= 9  tick:1 hit:1 layout:1 intern:3 focus:1 geom:4 stopp:2 query:1 signal:2
ui/elements/chrome/NotificationBalloons            314  ext=UIElement          parts= 3  tick:4 hit:1 window:1 idyn:1 intern:2
ui/elements/chrome/NotificationCard                217  ext=UIElement          parts= 0  hit:4 stopp:2 query:1
ui/elements/chrome/NotificationsView               200  ext=UIElement          parts=16  hit:2 window:1 intern:4 display:2 stopp:1
ui/elements/chrome/PageStack                       149  ext=UIElement          parts= 2  idyn:2 intern:4 display:3
ui/elements/chrome/Preferences                     287  ext=                   parts= 2  tick:1 imp:2 top:2 ovl:1 settings:1 geom:2 undo:1
ui/elements/chrome/ProblemNode                      38  ext=                   parts= 0
ui/elements/chrome/ProblemsCommands                 83  ext=                   parts= 0
ui/elements/chrome/ProblemsPanel                   929  ext=UIElement          parts=19  hit:8 anchor:1 data:5 intern:4 display:4 draw:3 stopp:3 signal:4
ui/elements/chrome/ProblemsTreeSource              210  ext=                   parts= 0
ui/elements/chrome/ProcessesPopover                214  ext=Popover            parts= 8  imp:1 hit:2 anchor:1 job:3 stopp:1
ui/elements/chrome/ProgressStatusItem              215  ext=UIElement          parts= 4  tick:5 imp:2 window:1 intern:5 job:9 stopp:1
ui/elements/chrome/QuickPick                       791  ext=Popover            parts=18  imp:6 hit:9 resize:3 drag:1 intern:3 ovl:1 draw:6 scroll:1 focus:1 geom:6 stopp:2 signal:2 prop:3
ui/elements/chrome/QuickPickEntry                   41  ext=                   parts= 0
ui/elements/chrome/QuickPickItem                   179  ext=                   parts= 0
ui/elements/chrome/QuickPickSource                 294  ext=                   parts= 0
ui/elements/chrome/StatusBarView                   427  ext=UIElement          parts= 8  hit:3 anchor:1 window:1 idyn:1 intern:6 display:1 stopp:2
ui/elements/config/ConfigControl                   242  ext=UIElement          parts= 1  window:1 undo:3 signal:4
ui/elements/config/ConfigControlContracts           93  ext=                   parts= 0
ui/elements/config/ConfigControls                   82  ext=                   parts= 0
ui/elements/config/ConfigDescriptor                262  ext=                   parts= 0
ui/elements/config/Configurator                     81  ext=UIElement          parts= 3  hit:1 intern:4
ui/elements/config/ConfiguratorGroup               125  ext=UIElement          parts= 6  hit:2 intern:4 signal:2
ui/elements/config/ConfiguratorPanel               194  ext=ScrollerView       parts= 1  idyn:2 intern:1 signal:2
ui/elements/config/SettingsConfigurator            172  ext=                   parts= 0  undo:5
ui/elements/config/ValueControl                     84  ext=ConfigControl      parts= 0
ui/elements/config/control/ArrayControl            155  ext=ValueControl       parts= 8  hit:2 intern:4
ui/elements/config/control/AssetControl             80  ext=ValueControl       parts= 3  contract:1 intern:3 signal:2
ui/elements/config/control/BooleanControl           54  ext=ValueControl       parts= 1  contract:1 intern:2
ui/elements/config/control/ColorControl            151  ext=ValueControl       parts= 6  top:1 hit:2 contract:1 intern:2 stopp:1
ui/elements/config/control/HeaderControl            55  ext=ConfigControl      parts= 2  hit:1 intern:3
ui/elements/config/control/InfoControl              69  ext=ConfigControl      parts= 2  hit:1 intern:3
ui/elements/config/control/MaskControl             159  ext=ValueControl       parts= 5  contract:1 intern:3
ui/elements/config/control/MatrixControl           109  ext=ValueControl       parts= 3  contract:1 intern:2
ui/elements/config/control/NumberControl           295  ext=ValueControl       parts= 3  hit:1 contract:1 drag:1 intern:2 focus:1 geom:4 stopp:1 undo:1
ui/elements/config/control/SelectControl            68  ext=ValueControl       parts= 1  contract:1 intern:2
ui/elements/config/control/SliderControl            92  ext=ValueControl       parts= 1  contract:1 intern:3
ui/elements/config/control/TextControl              87  ext=ValueControl       parts= 1  contract:1 intern:2
ui/elements/config/control/VectorControl           123  ext=ValueControl       parts= 3  hit:1 contract:1 intern:2
ui/elements/desktop/Desktop                       1359  ext=UIElement          parts= 2  tick:2 imp:3 inl:1 hit:1 data:7 layout:1 window:1 ghost:1 intern:6 display:3 keymap:2 trans:2 focus:1 geom:11
ui/elements/desktop/DesktopCommands                158  ext=                   parts= 0  close:1
ui/elements/desktop/DesktopPresentation             75  ext=                   parts= 0  top:1
ui/elements/desktop/DesktopSession                 244  ext=                   parts= 0
ui/elements/desktop/ScreenOverlay                  156  ext=                   parts= 0  pop:1 focus:2
ui/elements/desktop/SnapZones                      257  ext=                   parts= 0  geom:1
ui/elements/desktop/SystemMenu                     251  ext=                   parts= 2  tick:1 anchor:3 data:1 geom:4
ui/elements/desktop/Taskbar                        565  ext=UIElement          parts=14  imp:2 hit:4 close:2 data:5 window:1 intern:5 display:4 stopp:3
ui/elements/desktop/TaskbarDesigner                586  ext=                   parts=12  imp:5 drag:1 draw:15 geom:3 stopp:1 transform:1
ui/elements/desktop/TaskbarEntryMotion             268  ext=                   parts= 0  tick:3 inl:4 hit:2 trans:2 geom:2 prop:2
ui/elements/desktop/TaskbarPreviews                539  ext=                   parts= 0  tick:5 imp:3 top:2 hit:3 anchor:4 ghost:1 intern:1 geom:4
ui/elements/desktop/WindowAnimation                291  ext=                   parts= 0  trans:13 prop:1
ui/elements/desktop/WindowAnimator                 514  ext=                   parts= 0  tick:3 close:1 mirror:1 trans:4 geom:2
ui/elements/desktop/WindowChrome                    55  ext=                   parts= 0
ui/elements/desktop/WindowCommands                 283  ext=                   parts= 0  close:2 data:1
ui/elements/desktop/WindowFrame                   2424  ext=UIElement          parts=17  imp:2 inl:5 hit:1 close:4 resize:18 disp:5 data:6 paint:7 layout:1 mirror:6 idyn:2 intern:4 display:5 trans:2 ctx:3 draw:3 scroll:1 focus:4 geom:20 stopp:2 signal:6
ui/elements/desktop/WindowGeometryAnimation        156  ext=                   parts= 0  inl:1 resize:3 trans:3
ui/elements/desktop/WindowIcon                     164  ext=UIElement          parts= 4  hit:2 intern:2 display:3 draw:6
ui/elements/desktop/WindowKeyboardMove             182  ext=                   parts= 1
ui/elements/desktop/WindowMotion                    21  ext=                   parts= 0  tick:2
ui/elements/desktop/WindowMove                     411  ext=                   parts= 0  drag:1 geom:15 stopp:1
ui/elements/desktop/WindowPolicy                    36  ext=                   parts= 0
ui/elements/desktop/WindowPreview                  236  ext=UIElement          parts= 5  imp:1 hit:3 close:1 intern:4 geom:2 signal:4
ui/elements/desktop/WindowRegistry                 213  ext=                   parts= 0  signal:2
ui/elements/desktop/WindowSnapshot                 210  ext=                   parts= 0  paint:3 mirror:2 ctx:6 text:1 geom:1
ui/elements/desktop/WindowState                     45  ext=                   parts= 0
ui/elements/desktop/WindowSwitcher                 627  ext=UIElement          parts= 8  tick:3 imp:1 top:2 hit:1 modal:1 close:2 intern:2 keymap:1 geom:2
ui/elements/desktop/WindowThumbnail                336  ext=UIElement          parts= 2  inl:1 hit:1 paint:2 mirror:3 intern:2 display:2 ctx:3 geom:8
ui/elements/dock/DockArea                         1192  ext=UIElement          parts= 2  tick:4 data:1 layout:1 drag:1 ghost:7 intern:3 cmd:1 focus:1 geom:7 signal:7
ui/elements/dock/DockBannerBar                      56  ext=UIElement          parts= 3  hit:1 intern:4
ui/elements/dock/DockGroup                         863  ext=UIElement          parts= 6  imp:4 hit:3 disp:1 intern:3 draw:3 geom:6
ui/elements/dock/DockWindow                        199  ext=WindowFrame        parts= 1  tick:1 focus:2
ui/elements/dock/(model: DockLayout 521, DockLayoutCodec 213, DockPanelRegistry 368, DockPanelDescriptor 202, DockLeaf 182, DockDropZones 159, DockPlacement 141, DockPath 131, DockPanelRef 119, DockBranch 104, DockInput 103, DockNode 101, DockCommands 208, DockRegion 74, DockPane 66, DockOpenOptions 60, ViewId 84, RegionSide 58, DockDropZone 46, DockBanners 48, DockBannerProvider 42, DockPanelKind 50, DockPaneProvider 23, DockOrientation 21, DockDragPayload 61)  -- headless; retype DockPane.view() only
ui/elements/editor/CompletionPopup                 897  ext=Popover            parts=13  imp:6 hit:7 close:3 pop:2 resize:8 drag:1 intern:4 ovl:2 keymap:1 scroll:1 geom:8 stopp:2 signal:2 prop:3
ui/elements/editor/CompletionSession               519  ext=                   parts= 0  signal:4
ui/elements/editor/DecorationPool                  121  ext=                   parts= 0  hit:1 intern:2 display:2
ui/elements/editor/DiffChevronPart                 135  ext=EditorViewPart     parts= 0  hit:2 sexempt:1 intern:2 stopp:1
ui/elements/editor/DocumentationPopup             1516  ext=Popover            parts=21  tick:3 hit:4 anchor:3 resize:4 drag:1 intern:17 display:29 ovl:3 keymap:2 text:1 scroll:1 geom:5 stopp:3 signal:8
ui/elements/editor/EditorCommands                  534  ext=                   parts= 0  data:1 keymap:3 undo:10
ui/elements/editor/EditorFind                      312  ext=                   parts= 1  sexempt:2 intern:1 display:1 focus:1
ui/elements/editor/EditorFolding                   374  ext=                   parts= 0  job:3 scroll:1 undo:1
ui/elements/editor/EditorSuggest                   250  ext=                   parts= 0  focus:1 geom:1
ui/elements/editor/ErrorStripePart                 269  ext=EditorViewPart     parts= 4  intern:2 geom:4 stopp:2
ui/elements/editor/FoldingDecorationsPart          306  ext=EditorViewPart     parts= 0  hit:2 sexempt:1 intern:4 scroll:1 stopp:2
ui/elements/editor/GutterEdgePart                   60  ext=EditorViewPart     parts= 0  sexempt:1
ui/elements/editor/InspectionWidgetPart            220  ext=EditorViewPart     parts=10  hit:4 sexempt:1 intern:2 display:2
ui/elements/editor/LineNumbersPart                 135  ext=EditorViewPart     parts= 0  scroll:1
ui/elements/editor/QuickFixBulbPart                182  ext=EditorViewPart     parts= 1  intern:2 display:2 stopp:1
ui/elements/editor/SearchReplaceBar                685  ext=UIElement          parts=17  tick:1 imp:3 hit:1 layout:1 intern:4 display:3 keymap:3 focus:3 geom:2 stopp:2 query:2 signal:2
ui/elements/editor/SquigglesPart                   176  ext=EditorViewPart     parts= 4  hit:1 intern:2
ui/elements/editor/TextEditor                     6166  ext=ScrollerView       parts=31  tick:1 imp:10 hit:8 data:6 sexempt:4 paint:2 layout:1 idyn:1 intern:16 cmd:2 keymap:1 hl:7 trans:1 text:8 scroll:35 focus:1 geom:13 stopp:6 query:2 transform:3 capture:1 undo:7 signal:8 prop:3
ui/elements/editor/ViewCursorsPart                 163  ext=EditorViewPart     parts= 0  imp:3
ui/elements/editor/ZoomIndicatorPart               181  ext=EditorViewPart     parts= 0  hit:3 sexempt:1 intern:2 text:4 scroll:2
ui/elements/editor/(the rest: CompletionRanking 173, CompletionRecency 106, CurrentLinePart 76, DiagnosticActions 95, DiffBandsPart 134, DiffDecorations 113, EditorDiagnostics 221, EditorLanguageFeatures 514, EditorViewPart 109, HoverDocumentation 281, IndentGuidesPart 132, RulersPart 55, SelectionsPart 124, WhitespacePart 93)  -- touch the editor's package-private accessors only
ui/elements/graph/GraphCommands                    275  ext=                   parts= 0  data:3 geom:2 undo:1
ui/elements/graph/GraphNode                        633  ext=UIElement          parts=16  hit:3 paint:2 drag:1 idyn:1 intern:12 ctx:2 focus:1 stopp:2 state:1 signal:2
ui/elements/graph/GraphView                       1758  ext=CanvasView         parts= 1  tick:3 imp:3 hit:1 data:7 layout:1 drag:1 intern:2 cmd:2 keymap:1 settings:2 ctx:2 focus:1 geom:6 stopp:1 undo:14 signal:2
ui/elements/graph/NodeCreationMenu                 639  ext=Popover            parts=12  imp:3 hit:6 resize:3 drag:1 intern:5 scroll:2 focus:1 stopp:3 signal:2
ui/elements/graph/NodeFieldBinder                  241  ext=                   parts= 0  undo:9
ui/elements/graph/NodePort                         488  ext=UIElement          parts=10  tick:1 hit:3 resize:1 drag:1 intern:6 geom:3 stopp:1 state:1 signal:4
ui/elements/graph/NodeWireLayer                    285  ext=UIElement          parts= 1  hit:1 paint:1 ctx:3 geom:3
ui/elements/graph/PortDefaultEditor                395  ext=UIElement          parts= 0  tick:1 imp:3 hit:4 layout:1 idyn:5 intern:8 ctx:2 geom:4
ui/elements/graph/(the rest: BasicPortType 29, GraphConnection 30, GraphSelection 198, NodeFieldWidgets 191, NodeWidgetFactory 141, PortType 95, PortTypeRegistry 67)
ui/elements/inspector/Inspector                    447  ext=UIElement          parts= 3  tick:3 data:7 window:1 intern:3 focus:1 geom:1
ui/elements/inspector/(InspectorForm 129, InspectorRegistry 75, InspectorSection 92)  data:2..5
ui/elements/list/ListView                         1408  ext=ScrollerView       parts= 4  tick:1 imp:2 data:6 sexempt:1 layout:1 intern:3 scroll:23 focus:5 stopp:1 query:2 signal:4 prop:7
ui/elements/list/(FixedHeightStrategy 50, ItemSizeStrategy 28, ListRenderer 74, SelectionMode 15, VariableHeightStrategy 189)  -- retype the renderer
ui/elements/table/TableView                        519  ext=ListView           parts= 6  hit:3 resize:1 sexempt:1 layout:1 drag:1 idyn:2 intern:3 scroll:6 signal:2 prop:7
ui/elements/table/(SortOrder 25, TableCellRenderer 18, TableColumn 156)
ui/elements/tree/TreeSearch                       1141  ext=                   parts=16  imp:3 hit:3 idyn:1 focus:2 stopp:6 signal:2
ui/elements/tree/TreeView                          449  ext=ListView           parts= 3  signal:2 prop:3
ui/elements/tree/(FilteredTreeSource 106, PathTreeSource 156, TreeDataSource 32, TreeRenderer 40, TreeRow 21)
ui/elements/workbench/ConflictDialog               167  ext=                   parts= 4  ovl:2 focus:1
ui/elements/workbench/DiffView                     303  ext=UIElement          parts= 8  tick:3 hit:1 layout:1 intern:3 scroll:1 signal:2
ui/elements/workbench/ExplorerCommands             578  ext=                   parts= 0  data:3 settings:2
ui/elements/workbench/ExplorerDragAndDrop          195  ext=                   parts= 0  drag:1 ghost:5 draw:1
ui/elements/workbench/ExplorerEditing              247  ext=                   parts= 0  imp:2 focus:2 stopp:1
ui/elements/workbench/FilesRenderer                300  ext=                   parts= 0  hit:4 draw:5 query:1
ui/elements/workbench/MergeView                    531  ext=UIElement          parts= 9  tick:4 hit:1 layout:1 intern:4 scroll:1 signal:2
ui/elements/workbench/ProjectFileTree              837  ext=UIElement          parts=10  tick:1 data:7 layout:1 window:1 idyn:1 intern:4 cmd:1 keymap:1 undo:12 signal:6
ui/elements/workbench/RegionDropOverlay            367  ext=UIElement          parts= 2  imp:6 hit:3 intern:3 focus:1 geom:13 signal:2
ui/elements/workbench/RegionHost                   213  ext=UIElement          parts= 5  intern:3
ui/elements/workbench/StripeView                   962  ext=UIElement          parts= 9  tick:1 hit:4 modal:1 anchor:2 disp:1 drag:1 ghost:5 idyn:6 intern:7 draw:5 focus:3 geom:4
ui/elements/workbench/ToolWindowFrame              258  ext=WindowFrame        parts= 4  top:1 geom:2 signal:2
ui/elements/workbench/ToolWindowManager            650  ext=                   parts= 0  modal:2 focus:2 signal:2
ui/elements/workbench/ViewContainer                245  ext=UIElement          parts= 7  imp:1 hit:1 intern:4 signal:2
ui/elements/workbench/Workbench                   3212  ext=UIElement          parts= 6  tick:2 hit:1 disp:1 data:9 layout:1 window:1 intern:7 ovl:1 cmd:1 job:10 focus:7 undo:5 signal:7
ui/elements/workbench/WorkbenchRegions             270  ext=                   parts= 3
ui/elements/workbench/(the rest: DocumentType 101, DocumentViewState 48, ExplorerClipboard 95, ExplorerFind 139, FileDocument 178 disp:1, GoToFile 352, HeaderContributor 30, OpenDocuments 266 disp:4, ProjectIndex 382, QueryLocation 115, RecentFiles 94, RegionDropZones 193, SplitFill 76, StripeRail 63, ToolWindowLayout 212, ToolWindowState 266, ToolWindowType 81, ViewContainerRegistry 151, WorkbenchMenus 137, WorkbenchSession 749, WorkbenchSettings 312, WorkspaceTreeSource 860, decoration/* 349, document/TextFileDocument 272 disp:3 scroll:3)
graph/shader/BlackboardPanel                      1360  ext=UIElement          parts=10  tick:6 inl:1 hit:5 anchor:2 resize:3 data:4 layout:1 idyn:4 intern:10 cmd:1 keymap:2 focus:1 geom:9 stopp:3 undo:3 signal:2
graph/shader/CategoryHeader                        134  ext=UIElement          parts= 4  hit:2 intern:4 stopp:1 signal:6
graph/shader/InlineRename                          166  ext=                   parts= 0  idyn:1 intern:1 focus:1 stopp:2 signal:5
graph/shader/MainPreviewPanel                      466  ext=UIElement          parts= 5  tick:3 hit:1 anchor:1 resize:3 disp:4 paint:1 drag:1 intern:4 ctx:2 geom:7 stopp:3 undo:1
graph/shader/PropertyPill                          296  ext=UIElement          parts= 9  hit:5 drag:1 intern:5 stopp:2 signal:6
graph/shader/ShaderGraphEditor                    1309  ext=UIElement          parts= 4  tick:3 inl:1 disp:5 data:4 layout:1 intern:4 ovl:2 cmd:1 settings:2 geom:2 stopp:1 signal:3
graph/shader/ShaderGraphPreviews                   247  ext=                   parts= 0  tick:3 geom:1 signal:2
graph/shader/ShaderInspectorSections               668  ext=                   parts= 0  data:24 settings:1 undo:3
graph/shader/ShaderNodePreview                      82  ext=UIElement          parts= 1  hit:1 paint:1 ctx:2 geom:4
graph/shader/ShaderPropertyNodes                   241  ext=                   parts= 3  hit:1
graph/shader/(the rest: ShaderColorFieldWidget 112, ShaderGraphBridge 494, ShaderGraphContribution 167, ShaderGraphSettings 95, ShaderPortArity 284, ShaderPropertyForm 246, ShaderVectorFieldWidget 89)
editor/CrystalEditor                               500  ext=UIElement          parts= 2  disp:2 data:10 window:1 intern:4 cmd:1 settings:1 focus:2 signal:3
editor/CrystalEditorCommands                        94  ext=                   parts= 0  data:4
example/machine/ui/EnginePanel                     259  ext=UIElement          parts= 0
example/machine/ui/MachinePanel                    736  ext=UIElement          parts= 0  query:1
example/machine/(EngineModel 111, MachineDemo 330, MachineModel 130, MachineTrace 54, ui/MachineRows 70, ui/MachineStyles 154)
language/run/view/RunPanel, RunRail (extend UIElement); ScriptWorkbench, RunConsoleView, RunDecorations, RunIndicators, TailFollow, MappingCommands, RunPanels (build trees)  -- 10 files, 3,400 lines, measured only by file
net/window/Networked                               257  panels extend UIElement  close:2
net/window/(ClientScope 91, ClientWindowContext 96, ClientWindows 591, CloseReason 53, OpenResolver 58, ScopedSheets 249, ServerScope 514 scroll:1 prop:1, ServerWindow 265 close:1, ServerWindows 515, SheetSupply 230, UiType 298 query:1, ViewCommands 119 scroll:1 focus:1, WindowMount 74, WindowProtocol 57)
```

## Appendix B — classes no test file names

`AssetControl` `Breadcrumbs` `CategoryHeader` `CheckboxGroup` `ConfigControlContracts`
`CurrentLinePart` `DecorationPool` `DiagnosticActions` `DiagnosticDecorations` `DiffBandsPart`
`DiffChevronPart` `DockPanelKind` `DocumentViewState` `EditorDiagnostics` `EditorSuggest`
`EditorViewPart` `ExplorerDragAndDrop` `ExplorerEditing` `ExplorerFind` `FoldingDecorationsPart`
`GraphSelection` `GutterEdgePart` `HeaderContributor` `HeaderControl` `HoverDocumentation`
`InfoControl` `InlineRename` `InspectionWidgetPart` `ItemSizeStrategy` `LineNumbersPart`
`MachineDemo` `MachineRows` `MachineTrace` `MatrixControl` `PageStack` `ProblemsTreeSource`
`ProcessesPopover` `QuickFixBulbPart` `RecentFiles` `RulersPart` `ScreenOverlay`
`ShaderInspectorSections` `ShaderNodePreview` `SliderControl` `SymbolIcon` `TableCellRenderer`
`TextControl` `ValueControl` `ViewCursorsPart` `ViewId` `WhitespacePart` `WindowAnimation`
`WindowAnimator` `WindowMotion` `WindowMove` `WindowRegistry` `WindowSnapshot` `WindowThumbnail`
`WorkbenchMenus` `ZoomIndicatorPart`

Most are covered through their owner (a view part through `TextEditor`, a control through
`ConfigKitTest`, the motion classes through `WindowAnimationTest`'s `isAnimating` gate). The ones a
port could break silently, and which get a parity spec or a test **before** their batch:
`WindowMove` (the caption drag and its clamp), `WindowRegistry` (eviction with the dirty
exemption), `WindowThumbnail` and `WindowSnapshot` (the two thumbnail paths), `ScreenOverlay`
(M16), `ExplorerDragAndDrop` and `ExplorerEditing` (three of the explorer rows), `PageStack`,
`ProcessesPopover`, `Breadcrumbs`, `SymbolIcon`.

## Appendix C — the sheet part census, per file

Part names per UA sheet, so a batch knows how much sheet it owns: `workbench.css` 162 ·
`editor.css` 81 · `panels.css` 79 · `desktop.css` 74 · `inspector.css` 69 · `config-kit.css` 64 ·
`widgets.css` 56 · `search.css` 43 · `overlays.css` 34 · `core.css` 2 — 497 distinct names across
them after de-duplication (a name like `__label__`, `__content__`, `__header__`, `__close__`,
`__title__`, `__icon__`, `__badge__` is claimed by several widgets, and D1 renames each claim). Plus
`graph.css` 235 references and `ore.css` 101, both themes reaching parts — which is what `::part`
exists for, and the reason kind A must include everything a theme legitimately restyles.

The 401 part-under-part selectors and the 99 part-then-tag selectors are listed by
`SheetPortTest`'s first failing run rather than here: the list is derived, and a derived list in prose
rots the way every count in `AGENTS.md`'s doc paragraph did.
