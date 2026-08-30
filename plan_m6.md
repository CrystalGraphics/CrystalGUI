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
| `cgui-shadow-parts` | `Button`, `ShadowButton` | **deleted** with the spike at 6.1 |
| `cgui-splitview`, `cgui-tabview` | `SplitView`; `TabView` + `Button`, `Checkbox`, `Slider` | 6.2 |
| `cgui-gallery` | everything: `ColorSelector`, `ConfiguratorGroup/Panel`, `Dialog`, `DialogManager`, `Dropdown`, `GraphNode`, `GraphView`, `ListView`, `Menu`, … | the running total — green only when 6.4 lands |
| `cgui-completion` | `TextEditor` | 6.5 |
| `cgui-slot` | `ScrollerView`, `ItemSlot`, `FluidSlot` (from `native-content-slots`, not on master) | 6.2 — **needs the branch reconciled** |
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
`tagName()`'s **lowercased-class-name fallback**. The porting guide's *"⚠ `TagName` must be declared and
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
| — | **`SheetPortTest`**: every `__x__` still referenced by any sheet is in the ledger as A, B or C with the widget that owns it; every tag a sheet names is a registered `TagName`; no rule contains `::part(a)::part(b)` | the 401 and the 32 |
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

com.crystalgui.editor                 the application root, as it is
com.crystalgui.graph.shader           as it is; split into .panel / .preview / .property in its batch
com.crystalgui.language.run.view      as it is
```

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

**The budget.** 95,316 lines in the matrix (plus `language`'s 3,400), 2,670 engine sites of which
**2,227 are mechanical and 443 are hand-edited** — per batch, 38 to 87. The residual is edited with
targeted replacements, never by rewriting a file, and never by re-reading a file the census has
already located the line in. Written from scratch: 6.0's machinery and nothing else. The one
honest exception is 6.5's D22, budgeted as XL for that reason and not for `TextEditor`'s length.

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
| **Identity** | `setId`, `addClass`, `removeClass`, `hasClass`, `swapPrefixedClass`, `removeClassWithoutRematchingSubtree`, `tagName()` (exact-class lookup + lowercase fallback) | `setId`, `addClass`, `removeClass`, `toggleClass`, `hasClass`, `classes()`, `name()`, `tagName()` from the `TagName` | **the fallback is gone** — §1.5 |
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
| `ElementRegistry` — 23 tags, bijective, factory per tag, unknown tag THROWS on decode | `UINodeRegistry` — 55 names (§1.5), `register(TagName, Supplier, NodeContract)`, `plain(name, acceptsChildren)` | `UiType` and `PortTypeRegistry` also register tags |
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
| **D1** | How is a `__x__` classified as kind A, B or C? | **A** if the widget builds it in its constructor, it never holds a caller's node, and no sheet reaches through it to a TAG (§0.4's 99). **B** if any sheet selects a part or a tag beneath it, or a caller's content lands inside it. **C** if the widget toggles it from a listener. Applied per name in the ledger (§2.5), and a name used by two widgets as different kinds is renamed at one of them — which is the `.__content__` row's fix arriving as a rule |
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
| **D16** | Where the desktop lives | four document-owned kind-B layers under the document node, in band order — `content` (the application root), `windows`, `pinned`, `overlays` — each a registered `TagName` so `desktop { }` still matches, zero-sized until used (the desktop's own rule), hosted by `Box.setHost` so the top layer is a host and not a special case. `UIDocument.desktop()` builds the compositor into the `windows` layer on first use |
| **D17** | Thumbnails and snapshots | live: `BoxTree.mirror`; frozen: `WindowSnapshot` stays — a frozen subtree has no boxes |
| **D18** | `Disposer` | keep the API, key it on nodes: `Lifecycle.destroy(node)` runs `Disposer.dispose` over the composed subtree; non-node disposables (documents, analyses, GL snapshots) register against their owning node. The master plan's *"`Disposer` as a second ownership tree"* is deleted in the sense that the tree is the node tree |
| **D19** | Per-instance reported events | `Attribute.REPORTS` |
| **D20** | The S2 spike | deleted at 6.1 with `Button`, scene and all |
| **D21** | Root sizing and centring | `UIDocument.layout` keeps `UIWindow`'s rule (a percentage root gets definite space; a content-sized root is centred) — `RootPlacementTest` covers it and the parity scene compared relative-to-root because of it |
| **D22** | Virtualised rows (`ListView`, `TableView`, `TextEditor`, `TreeView`) | rows are positioned by **`box().setTransform(translate)`**, not by insets — a compositor-style placement that writes no style and re-runs no layout per scroll frame. The old engine placed them at IMPORTANT `top` and re-laid out; the one-pass metric would otherwise count a layout per scrolled frame |
| **D23** | Where `Networked` panels' `extends UIElement` goes | `extends UINode`; `UiType<P extends UINode & Networked<M>>`; the `mayClose`/`requestClose` name collision row is re-checked against `UINode`'s surface |
| **D24** | The 32 unregistered tags | every one registers a `TagName` in a static initializer beside its class, and `SheetPortTest` refuses a sheet tag no name answers |

---

## 5. The minor milestones

Sizes as in `plan_m5.md`: S ≤ a day, M a few days, L a week or two, XL more. Each row names what it
ports, what accepts it, which invariant rows it owns, and the hazards specific to it.

### 6.0 — Machinery, ledger, fixtures · **L** · after: M5

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

### 6.1 — Leaf widgets · **M** · after: 6.0

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

**Budget.** 17 files / 6,495 lines copied; 301 mechanical sites; **60 hand sites** (imp 30 · conv 9 ·
stopp 6 · idyn 8 · layout 3 · paint 4). Also the codemod's own test: one file per transformation is
diffed by eye before the rest of the batch runs.

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

### 6.2 — Composites and the overlay family · **L** · after: 6.1

**Ports.** `SplitView` (n-ary, `NestedSplitDividerTest`, `SplitPaneGrowChainTest`,
`SplitViewNAryTest`), `TabView` + `Tab` (`TabViewRailLeakTest`, `TabCloseAndRevealTest`),
`Popover`, `Menu`, `MenuItem`, `Dropdown`, `ContextMenu`, `MenuBuilder`, `Dialog`, `DialogManager`,
`InputDialog`, `ColorSelector`, `MarkupView`, the config kit (`ConfigControl`, `ValueControl`,
`Configurator`, `ConfiguratorGroup`, `ConfiguratorPanel`, `SettingsConfigurator`, thirteen
controls), `Inspector` + `InspectorForm/Registry/Section`, `PageStack`, `Breadcrumbs`,
`StatusBarView`, `ProgressStatusItem`, `NotificationsView/Card/Balloons`. Sheets: the rest of
`overlays.css`, `config-kit.css`, `inspector.css`, half of `panels.css`.

**Accepts.** `cgui-splitview`, `cgui-tabview`, `cgui-slot` (once the branch is reconciled); the
gallery's dialog, menus, config and inspector pages as parity specs; `Dialog` 211, `Menu` 199,
`MenuItem` 216, `Popover` 164, `SplitView` 200, `TabView` 185, `Inspector` 130, `ConfigKit`,
`ConfiguratorPanelLifetime`, `ColorSelector*`, `MarkupView`, `ModalDialog`, `DesktopModality`'s
non-desktop half, `PromotedPopoverHit`, `PickerInPromotedDialog`, `TopLayer*`, `AnchoredPlacement`,
`Resize`, `NumberControlScrub`, `ScrubUndo`, `StatusBarView`, `NotificationsView`, `ProgressChrome`.

**Destination.** `widget.layout`, `widget.overlay`, `widget.control` (Dropdown, ColorSelector),
`widget.text` (MarkupView), `widget.form` + `.field` + `.inspector`, `chrome.menu` (Breadcrumbs),
`chrome.status` (StatusBarView, ProgressStatusItem), `chrome.notification`.

**Budget.** 47 files / 11,798 lines; 402 mechanical; **68 hand sites** (imp 13 · conv 12 · stopp 17 ·
idyn 13 · resize 8 · layout 4 · paint 1).

**Rows it owns.** The popover and modality rows: *"the popover stack and the close-watcher stack are
separate"*; *"light dismiss runs after the mouse-down dispatch, and spares the invoker"*; *"light
dismiss considers the popovers open BEFORE the dispatch"*; *"Escape asks the topmost close
watcher"*; *"`inert` keeps its box"*; *"hit-testing an inert subtree FALLS THROUGH"*; *"a detached
modal must be popped"*; *"exactly one tab in a `TabView` strip is tabbable"*; *"click-focus tests
`focusesOnClick()`"*; *"a `SplitView` divider must clamp against the pane's CSS `min-width`"* and the
*"cannot go below two panes"* row; *"`flex-grow` summing to less than 1"*; the `Dropdown extends
Button` tag identity; *"a menu bar must REMEMBER the focus owner"* (6.3); *"transitioning INTO view
needs a resting value in the sheet"* (`Popover.OPEN_CLASS` — kind C, unchanged).

**Hazards.** `Dialog` is the first `resize:` consumer (D6), the first modal, the first
`attachOwned` target, and it moves by INLINE insets (D4) — four decisions land on one widget.
`Menu`'s keyboard pattern relies on bubble-phase listeners on the menu with a focused item CHILD
(`Menu`'s note) — under DOM propagation that still works, but re-read. `ColorSelector` (767 lines,
13 parts, 11 geometry reads) is the largest pure widget and a good proxy for the config kit.
`MarkupView`'s blocks are kind B by its own javadoc.

### 6.3 — Collections, search, the shell's chrome · **L** · after: 6.2

**Ports.** `ListView` + `ItemSizeStrategy`/`FixedHeightStrategy`/`VariableHeightStrategy`/
`ListRenderer` (retyped), `TreeView`, `TreeSearch`, `TreeRenderer`/`TreeRow`/the sources (headless,
retyped only), `TableView` + `TableColumn`/`TableCellRenderer`, `NavigatorView`, `Preferences`,
`QuickPick` + `QuickPickItem/Entry/Source`, `CommandPalette`, `MenuBarView`, `MainMenuCommands`,
`ChromeCommands`, `ProblemsPanel` + `ProblemNode/Commands/TreeSource`, `ProcessesPopover`. Sheets:
`search.css`, the rest of `panels.css`.

**Accepts.** The gallery's list, tree, table and palette pages as parity specs; `ListView` 275,
`TreeView` 115, `TableView`, `TreeSearchInstall`, `TreeQuery`, `VariableHeightStrategy`,
`CommandPalette`, `QuickPickQueryRetention`, `QuickPickResize`, `MenuBarView`, `ProblemsPanel`,
`ProblemsMenu`, `ProblemBandPrimary`, `Preferences`, `PreferencesKey`, `AppearanceSettings`,
`ContextMenu`, `ElementSettings`, `Keymap`, `ShippedKeymapDefaults`.

**Destination.** `widget.collection.list/.tree/.table`, `chrome.palette`, `chrome.menu` (MenuBarView,
MainMenuCommands, ChromeCommands), `chrome.problems`, `chrome.preferences`, `chrome.status`
(ProcessesPopover). The tree sources, strategies and renderers are **moved**, not copied.

**Budget.** 32 files / 9,061 lines; 209 mechanical; **46 hand sites** (imp 16 · stopp 19 · idyn 3 ·
resize 4 · layout 4).

**Rows it owns.** The whole list/tree/search cluster: *"a list restoring focus to a row must never
take it from a CONTROL INSIDE one"*; *"a blur raised by ROW RECYCLING is not a user gesture"*; *"a
row's inline editor is primed ONCE PER EDIT"*; *"a search box is a `TextField`"*; *"a tree's inline
editor is built in `createTemplate`"*; *"FILTERING REVEALS; HIGHLIGHTING DOES NOT"*; *"a tree that
restores selection BY ITEM must clear the index-based one first"*; *"a panel's FILTER and its SEARCH
must share one notion of matches"*; *"pass the `SearchQuery`, never the text"*; *"a search marks the
matched CHARACTERS"*; *"a `::highlight()` BAND must be cleared on the no-styles path"*; *"a shared
row component must reach the rows ITSELF"*; *"a search bar is either TRANSIENT or PERMANENT"*; *"a
pane MINIMUM measured from realised rows"* ×2; *"a `ListView` is the tab stop of its own composite"*;
*"a recycled row must SWAP its data-driven classes"*; *"a row's slots are built in
`createTemplate`"*; *"a menu bar resolves commands against the FOCUSED element; a context menu
against the element that was CLICKED"*; *"the registry carries `enabled`; it never filters"*;
*"`MenuBuilder` is the only thing that turns commands into menu rows"*; *"a `MenuId.submenu`
declaration is PERMANENT"*; *"a widget must never rebuild the elements it is being clicked or dragged
on"* (`TableView`'s header).

**Hazards.** `ListView` rows are kind B (a renderer's template is a caller's node) placed by D22;
`ListView.getScrollHeight` is the first model-derived scroll extent (§2.2). `MenuBarView`'s
`__menu-title__` mnemonics and *"a menu MNEMONIC must not fire while a text field has focus"* go
through `claimsChord` and the keymap's `allowWhileTyping`. `TreeSearch` (1,141 lines, 16 parts) is
mostly kind C.

### 6.4 — Canvas, graph, the shader graph · **M** · after: 6.3

**Ports.** `CanvasView`, `CanvasOverlayMove`, `WorldRect`, `GraphView`, `GraphNode`, `NodePort`,
`NodeWireLayer`, `PortDefaultEditor`, `NodeCreationMenu`, `GraphSelection`, `GraphCommands`,
`NodeFieldBinder/Widgets`, `NodeWidgetFactory`; `graph/shader/` — `ShaderGraphEditor`,
`BlackboardPanel`, `MainPreviewPanel`, `PropertyPill`, `CategoryHeader`, `InlineRename`,
`ShaderNodePreview`, `ShaderPropertyNodes`, `ShaderInspectorSections`, the field widgets. Sheets:
`graph.css` (235 part refs), the `graphnode`/`nodeport`/`nodecreationmenu` half of `config-kit.css`.

**Accepts.** The gallery's graph page; `GraphView` 293, `GraphNode` 168, `NodePort` 136,
`GraphEditing`, `GraphDocumentView`, `NodeCreationMenu`, `NodeField`, `NodeControlKit`,
`NodePortInlineEditor`, `CanvasView`, `CanvasOverlay`, `CanvasResize`, `ShaderGraphEditor`,
`ShaderGraphCommands`, `ShaderGraphBridge`, `MainPreviewPanel`, `SemanticOverlay`.

**Destination.** `widget.canvas`, `widget.graph`, `graph.shader.panel/.preview/.property`. The graph
model (`GraphDocument`, codecs, node types — 18 files, 2,577 lines) is **moved**.

**Budget.** 53 files / 15,532 lines of which ~13,000 port; 358 mechanical; **87 hand sites** (imp 13 ·
conv 14 · stopp 23 · idyn 11 · resize 15 · layout 5 · paint 6) — the highest of any batch, because the
shader graph's panels resize, anchor and drag.

**Rows it owns.** The canvas/graph rows: *"the canvas culls with `opacity: 0`, not `display:
none`"* (box opacity, D4/§4.5); *"the plane's `transform-origin` is pinned to `0 0`"*; *"a pan drag's
source is the viewport"*; *"a positive `Scroll` notch means the wheel rolled down"*; *"a drag ends
when the button that started it is released"*; *"a wire's colour is read back out of the cascade"*;
*"an input port takes ONE edge"*; *"`nodeport:blank` means unconnected"*; *"click-focus targets the
exact element hit"* (`GraphNode.requestFocus` — re-read under retargeting); *"a `graphnode` paints no
background of its own"*; *"a press on an already-selected node must not collapse the selection"*;
*"a marquee selects what it TOUCHES"*; *"selection is not undoable"*; *"a drag's own delta is the
truth at drag end"*.

**Hazards.** `CanvasView`'s zoom is the first non-desktop `box().setTransform` writer and the
`transform-origin` pin must survive as a UA rule. `NodeWireLayer.paintSelf` draws under the nodes
through `ctx.curve()` — `paintContent` runs before children, which is the same order. `GraphNode`'s
rows are the archetypal kind B (`graphnode .__control-row__ checkbox .__mark__`).

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

**Budget.** 34 files / 15,427 lines; 341 mechanical; **64 hand sites** (imp 22 · conv 6 · stopp 19 ·
idyn 1 · resize 12 · layout 2 · paint 2) — plus D22, which is the batch's real cost.

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

### 6.6 — The desktop · **XL** · after: 6.2 (`Dialog`, `Popover`), 6.0's D16 hooks

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

**Budget.** 27 files / 10,498 lines; 295 mechanical; **80 hand sites** (imp 17 · conv 7 · stopp 7 ·
idyn 2 · resize 21 · layout 2 · paint 12 · mirror 12) — every paint and mirror site in the port is
here or in 6.4.

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

**Budget.** 83 files / 23,038 lines of which ~11,000 port; 313 mechanical; **38 hand sites** (imp 13 ·
conv 12 · idyn 7 · layout 5 · stopp 1) — the largest batch by lines and the smallest by hand work,
because the workbench is mostly logic over widgets it does not draw.

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

**Budget.** 15 files / 3,467 lines; 8 mechanical; **0 hand sites in the matrix** — the work is the
generic retype of two files (`ServerUiSession` 1,457 lines, `ClientUiSession` 903), which is a
reading of thirty `UIElement` references rather than a codemod.

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
| Tags a sheet names without a registered `TagName` | 32 | 0 |
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
