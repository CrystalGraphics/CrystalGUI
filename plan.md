# Workbench / Dock / Editor — architecture review and port plan

**Scope:** `ui/elements/dock/`, `ui/elements/workbench/`, `editor/`.
**References:** VS Code (MIT — port the code) and IntelliJ Platform (Apache 2.0 — port the design).

## Progress

| Step | | Status |
|---|---|---|
| 1 | `Disposable` / `Disposer`, GL-aware (§14) | **DONE** |
| 2 | `DataContext` + context keys (§15) | **DONE** |
| 2.5 | Commands: global registration, context-resolved (§15b) | **DONE** — all three owner-capturing classes migrated; `install` is gone |
| 3 | Typed service events; delete the polling loops (§16) | **DONE** — 6 of 7 landings; loadProjects deferred to step 4, see §16.8 |
| 4 | `Resource`: schemes, virtual documents (§17) | **DONE** — incl. `FileDocument.resource()`; `OpenDocuments` deliberately stays `CgPath`-keyed, see §17.6 note |
| 5 | `DockPane`: retargetable views (§18) | **DONE** — wired into `DockGroup` via per-panel hosts; see the §18.4 note for why that shape sidesteps the re-parent hazard |
| 6 | `DockService.open` + `DockPlacement` (§19) | **DONE** — `DockPlacement`, `groupOf`/`leafOf`, and `Workbench.open(input, placement, options)` replacing all three `openPanel*`. No `DockService` interface: the insertion logic lives with the workbench, and a second name for it would be indirection |

| 7 | Editor-type resolution as a contribution (§21) | **DONE** — `DocumentType` + `Workbench.contribute`; `com.crystalgui.editor` imports one name from `com.crystalgui.graph` |
| 8 | The Inspector as a contribution surface (§22) | **DONE** — `Inspector` + `InspectorSection`/`InspectorRegistry`; `ShaderGraphInspector` deleted |
| 9 | Notifications + status, and the last hand-written menu | **DONE** — `com.crystalgui.core.notify`; `register(workbench)` takes nothing else; `BlackboardPanel`'s row menu is a `MenuId` query |
| 10 | Editor banners, and the last per-frame poll | **DONE** — `DockBannerProvider` (§11 Tier 2's one kept item); `GraphView.discoverPortEditors` is push-based |
| 11 | The Parts model, and the six foundations it needs (§23) | **DONE** — F1, F3–F6 landed; **F2b landed with §24.3's region shell**, not after it. `ToolWindowState` is now `{typeId, visible, region, side, weight, sideWeight, order, active, showStripeButton}` — `path` and `groupedWith` are gone and `showPanel` is a lookup. The §23.6 correction was right that the deletion had to follow the regions; it then happened *in the same step* rather than a later one, and this table was left saying otherwise for several sessions |
| 12 | The Parts stack — regions, containers, stripes, toolbar, status bar (§24) | **IN PROGRESS** — §24.3 regions, §24.4 `ViewContainer`/`View`, §24.5 stripes and §24.7 status bar are in. **§24.6 — the main toolbar and burger menu — is the only unbuilt part**, and §25 below is its research |

Steps 7 and 8 came from reading the result of 1–6: the six steps made the *framework* extensible and left
`CrystalEditor` naming one application's file types, and left the Inspector a graph-shaped class. Neither
needs new infrastructure — see §21.2 and §22.2.

Phase two — Parts/ViewContainers, menu contributions, the `when` parser, the model registry, and §11
Tier 2 — is deliberately unscheduled until phase one has been lived with (§20.2).

### Parked, with the reasoning already written down

| Item | Where it is described | Why it is parked |
|---|---|---|
| ~~**Context-scoped preview pool**~~ **DONE** | `docs/CGUI_WORKBENCH_SERVICES.md` → *The disposal protocol for GL resources* | Shipped as `CgPreviewPool`: one pool per `(size, samples)`, scoped per renderer. Closing a graph releases keys and deletes nothing; the context frees the targets at teardown. Un-parked because making a closed tab release its document turned the retention advice into a real cost — a close/reopen cycle was deleting and re-creating framebuffers. |

---

## 0. Why this document exists

The dock, the workbench and the editor were each built to answer the question in front of us at the
time. Every individual answer is defensible; the accumulation is not. The symptoms we have actually
paid for, in order:

| Symptom | Root cause |
|---|---|
| Tool window reopened at the wrong size | placement derived from the tree instead of stored |
| …then at the wrong wall | anchor was static, not updated on drag |
| …then in the wrong pane | an anchor cannot express a nested position |
| …then behind another tab | `openPanelWith` restores the previous selection |
| Inspector blank after restore | `activeGroup` is null until something is clicked |
| Inspector blank on reopen | a memo of a side effect something else could undo |
| Two Inspectors stacked | `clearAllChildren()` silently skips internal children |
| `showCompiled` in the application | the engine cannot open a panel "next to me" |

**Every one of these is the same failure**: state that should be owned and stored by the framework is
instead *derived*, *memoised*, or *hand-rolled at a call site*. We have been fixing instances. This
document is about fixing the class.

The test of the target design is not "does it work today". It is: **can a widget open, close, retarget,
place and persist a panel without the application knowing anything about it.**

---

## 1. Current state — an honest audit

### 1.1 `Workbench` is five services in a trench coat

> **PARTLY DONE.** The *consequence* is gone: `openPanel`/`openPanelWith`/`openPanelBeside` are one
> `Workbench.open(input, placement, options)` (step 6), and `CrystalEditor.showCompiled` with them.
> Editor-type resolution is a contribution (step 7) — `bindEditor*`/`registerDocumentType` survive as the
> primitives `DocumentType` is built on, but nothing calls them directly any more.
> **Still open:** tool windows (`showPanel`/`hidePanel`/`togglePanel`) remain on `Workbench`. That is
> §12.4 Parts, deliberately unscheduled.

1181 lines, and its public surface splits cleanly into five unrelated jobs:

| Job | Methods | VS Code equivalent | IntelliJ equivalent |
|---|---|---|---|
| Dock hosting | `dock`, `panels`, `registerPanel`, `openPanel`, `openPanelWith`, `openPanelBeside` | `IEditorGroupsService` | `FileEditorManagerEx` |
| Tool windows | `isPanelOpen`, `showPanel`, `hidePanel`, `togglePanel`, `toolWindows` | `IViewsService` / `IPaneCompositePartService` | `ToolWindowManager` |
| Documents | `openFile`, `documentFor`, `editorFor`, `refFor`, `saveActiveFile`, `saveAll`, `isDirty`, `unsavedFiles`, `openPaths`, `onDocumentLoaded` | `IEditorService` + `ITextFileService` | `FileDocumentManager` |
| Editor-type resolution | `bindEditorExtensions`, `bindEditorNames`, `bindEditorGlobs`, `registerDocumentType` | `IEditorResolverService` | `FileEditorProvider` EP |
| Explorer | `fileTree`, `setAutoReveal` | `IExplorerService` | `ProjectView` |

**Consequence:** anything that wants to open a panel must reach the whole workbench, and therefore the
application. That is the direct cause of `CrystalEditor.showCompiled`.

**The user's question — is `Workbench` owning `showPanel`/`hidePanel` a legitimate boundary?** No.
In both references, showing a tool window is the *tool-window manager's* job, and it is a different
service from the one that opens documents. Ours conflates them, which is why `togglePanel` and
`openPanel` have overlapping, subtly different placement logic.

### 1.2 There is no pane lifecycle — the root of `assertOnlyChild`

> **DONE** (step 5). `DockPane` retargets rather than rebuilds; `assertOnlyChild` is gone, promoted to
> `UIElement.setOnlyChild` as a general capability. `contentFor` still caches per panel, which is correct
> — a pane-backed panel gets a stable empty host and the pane's view moves between hosts.

```java
// DockPanelRegistry
public C create(DockPanelRef ref) { return factories.get(ref.typeId()).create(ref); }

// DockGroup
private UIElement contentFor(DockPanelRef panel) {
    return content.computeIfAbsent(panel, ref -> area.registry().create(ref));
}
```

A panel is **built once from a ref and cached**. There is:

- no way to give an existing panel a *new* input,
- no lifecycle callback (`onVisible`, `onHidden`, `onClosed`, `dispose`),
- no view-state hook on the panel itself,
- no notion of a panel *refusing* an input.

So a panel that must follow something else — an Inspector — cannot be retargeted, and the only escape
is what `CrystalEditor` did: keep a stable host element and swap its child by hand. `assertOnlyChild`
is not bad code. **It is a correct workaround for a missing framework capability**, which is worse: it
means every future "panel that follows something" will invent its own copy.

Both references have exactly the missing piece:

- **VS Code**: `EditorPane.setInput(input, options, context, token)` — the pane is *reused* and
  retargeted. `clearInput()` when it goes away.
- **IntelliJ**: tool windows hold `Content` objects in a `ContentManager`; the content is replaced, the
  window is not rebuilt.

### 1.3 A document does not know its own resource

> **DONE** (step 4). `FileDocument.resource()`; `graphPaths` is deleted. Derived resources carry their
> origin in the text, which is what lets the generated-source tab resolve its graph without a side map.

`FileDocument` is `view()`, `encode()`, `adopt()`, and nothing else. So:

- `CrystalEditor` keeps `Map<String, ShaderGraphEditor> graphPaths` — a hand-rolled `getFile()`.
- Anything wanting "the file behind this widget" has to ask the workbench and search.

Both references consider this fundamental: **`EditorInput.resource`** and **`FileEditor.getFile()`**.

### 1.4 `DockArea` has no `open()`

> **DONE** (step 6). `DockPlacement` (`ACTIVE`/`SIDE`/`with(x)`), `groupOf(UIElement)`/`leafOf`, and one
> entry point. No `DockService` interface: the insertion logic lives with the workbench and a second name
> for it would be indirection.

It has `performDrop`, `closePanel`, `closePanelDiscarding`, `toggleMaximize`, `setActiveGroup` — but
opening lives on `Workbench` in three overloads with different placement rules. There is no:

- placement token (`ACTIVE` / `SIDE` / `WITH(x)`),
- `groupOf(UIElement)` — so a widget cannot say "the group I am in",
- single entry point that all three overloads and `togglePanel` funnel through.

### 1.5 Smaller flags, all the same shape

> **MIXED.** `PATH_STATE` is now an alias of `DockPanelRef.PATH`, so the convention lives in the dock
> layer where it belongs. The polling row is **DONE** — step 3 replaced all four loops with announcements
> (`onDidChangeActivePanel`, `onDidChangeDirty`, `onDidRegister`, `onDidLoadListing`, and later
> `onDidChangeFocus`). **Still true:** `refFor` and `DockGroup.tabFor` are public, `activeFilePath()` is
> still four dereferences, and `DockPanelRef` equality still includes state — the last of which the row
> already calls correct.

| Flag | Note |
|---|---|
| `Workbench.refFor(path)` is public and the *identity* of a tab | An input's identity is framework business; a caller reconstructing it by hand is how two refs drift |
| `DockPanelRef` equality includes state | Correct, but it makes adding a state key a breaking change (bit us with `ICON`) |
| `activeFilePath()` reads `activeGroup().leaf().activePanel()` | Four dereferences through three layers to answer "what am I looking at" |
| `DockGroup.tabFor(panel)` is public | Lets callers reach widgets they do not own; only exists because presentation was pushed |
| `Workbench.PATH_STATE` is a workbench constant used by dock-layer code | Layering inversion; the dock should not know about paths, and does not — but the *convention* leaks |
| No `onDidChangeActiveEditor` style signals | Everything polls: `followActiveGraph`, `refreshDirtyMarkers`, `ActivityBar.refresh` |

---

## 2. Reference research — VS Code

### 2.1 The service layer

| Service | Responsibility |
|---|---|
| `IEditorService` | Open/close/save editors. **`openEditor(input, options, group)`** |
| `IEditorGroupsService` | The grid of groups: `groups`, `activeGroup`, `addGroup`, `removeGroup`, `moveGroup`, `mergeGroup`, `arrangeGroups` |
| `IEditorGroup` | One tab strip: `editors`, `activeEditor`, `openEditor`, `closeEditor`, `moveEditor`, `isPinned`, `isSticky`, `lock`, `focus` |
| `IEditorResolverService` | Which editor opens which resource — `registerEditor(glob, info, options, factories)` |
| `IViewsService` / `IViewDescriptorService` | Tool windows (views) and which container/location they live in |
| `IWorkbenchLayoutService` | The Parts: TITLEBAR, BANNER, ACTIVITYBAR, SIDEBAR, EDITOR, PANEL, AUXILIARYBAR, STATUSBAR |
| `IHistoryService` | Back/forward navigation, reopen-closed |
| `IWorkingCopyService` | Dirty tracking across all editor kinds; hot-exit backups |
| `IContextKeyService` | `activeEditor`, `resourceExtname`, `editorIsOpen` — what greys a command |

### 2.2 The three-part separation (the key idea)

```
EditorInput            what to open — serializable descriptor, NOT a widget
   ↓ resolved by
EditorPaneRegistry     which pane class renders this input type
   ↓ instantiates / reuses
EditorPane             the widget. setInput(input) — RETARGETED, not rebuilt
```

`EditorInput` carries: `resource: URI`, `typeId`, `editorId`, `getName()`, `getDescription()`,
`isDirty()`, `isReadonly()`, `matches(other)`, `capabilities` (a bitmask —
`Singleton`, `RequiresTrust`, `CanSplitInGroup`, `Untitled`, `Readonly`, `Scratchpad`).

`EditorPane` lifecycle: `createEditor` → `setInput(input, options, context, token)` →
`setEditorVisible(bool)` → `layout(dimension)` → `clearInput()` → `dispose()`.
`AbstractEditorWithViewState` adds `getViewState()`/`saveState()` keyed by resource.

**This is the piece we are missing entirely.**

### 2.3 Placement — `PreferredGroup`

```ts
type PreferredGroup = IEditorGroup | GroupIdentifier | SIDE_GROUP | ACTIVE_GROUP | AUX_WINDOW_GROUP;
const ACTIVE_GROUP = -1;
const SIDE_GROUP   = -2;
```

Placement is a **value**, not a computation at the call site. `openEditor(input, ACTIVE_GROUP)` is how
"in my own panel" is spelled.

### 2.4 Features we do not have and will want

| Feature | What it is | Why it matters |
|---|---|---|
| **Preview editors** | Single-click opens in *preview* (italic tab); the next preview replaces it. Double-click or edit pins it. | The single most-noticed tab behaviour in VS Code |
| **Sticky/pinned tabs** | Pinned tabs move to the front, shrink to the icon, resist close-others | |
| **`EditorsOrder.MOST_RECENTLY_ACTIVE`** | MRU order distinct from tab order | Ctrl+Tab |
| **Locked groups** | A group that refuses new editors (terminal/output panes) | |
| **`SideBySideEditorInput` / `DiffEditorInput`** | One tab holding two inputs | Diff, and our "graph + generated" pairing |
| **Untitled editors** | A document with no resource yet | "New shader graph" before first save |
| **Auxiliary windows** | Editors torn out into an OS window | We have `FloatingDock`; unintegrated |
| **`IEditorSerializer`** | Per-input-type restore | Ours restores refs blindly and hopes the factory copes |
| **Editor capabilities bitmask** | Declarative per-input constraints | Replaces our scattered boolean fields |
| **Watermark on empty group** | Keyboard shortcuts shown in an empty work area | We have `EMPTY_CLASS` and nothing in it |
| **`onDidChangeActiveEditor`** etc. | Push notifications | We poll for all of it |

---

## 3. Reference research — IntelliJ Platform

### 3.1 The service layer

| Service | Responsibility |
|---|---|
| `FileEditorManager` / `Ex` | Open/close/select editors. `openFile(file, focus)`, `getSelectedEditor()`, `currentWindow`, `getSplitters()` |
| `FileEditorProvider` (EP) | `accept(project, file)` / `createEditor(project, file)` / `getPolicy()` |
| `FileDocumentManager` | Document ↔ file, `saveAllDocuments`, `getUnsavedDocuments` |
| `ToolWindowManager` | Tool windows, backed by `DesktopLayout` of `WindowInfoImpl` |
| `ContentManager` / `Content` | What is *inside* a tool window; replaceable without rebuilding the window |
| `DockManager` / `DockContainer` | Drag-out-to-float framework |
| `IdeDocumentHistory` | Back/forward, last edit location |
| `DataContext` / `DataKey` | How a command finds its subject — pull, not push |

### 3.2 The equivalent three-part separation

```
VirtualFile           what to open. LightVirtualFile for generated/in-memory content
   ↓ accepted by
FileEditorProvider    registered per type; accept(file) decides
   ↓ creates
FileEditor            getFile(), getState()/setState(), selectNotify()/deselectNotify()
```

**`LightVirtualFile` is the direct answer to our generated-shader question.** IntelliJ's decompiled
`.class` view, "Show Kotlin Bytecode", and diff panes are all a virtual file plus a provider. No
application code special-cases them.

### 3.3 `FileEditor` lifecycle — richer than ours

`getComponent()`, `getPreferredFocusedComponent()`, `getName()`, `getFile()`,
`setState(FileEditorState)` / `getState(level)`, `isModified()`, `isValid()`,
**`selectNotify()` / `deselectNotify()`** (tab became/stopped being visible),
`addPropertyChangeListener`, `getStructureViewBuilder()`, `dispose()`.

`selectNotify`/`deselectNotify` is what lets a panel do expensive work only while visible — we have no
equivalent, so every panel ticks forever.

### 3.4 Features we do not have and will want

| Feature | Why |
|---|---|
| **`FileEditorPolicy`** (`HIDE_DEFAULT_EDITOR`, `PLACE_BEFORE/AFTER`) | Several providers can claim one file; a graph file could offer both a graph and a raw-JSON tab |
| **Multiple editors per file, as sub-tabs** | Design/Text/Split at the bottom of the editor |
| **`EditorTabTitleProvider` / `EditorTabColorProvider`** | We ported the title half already |
| **Tab limit + close-least-recently-used** | `UISettings.editorTabLimit` |
| **`OpenFileDescriptor`** (file + line + column) | Navigation with a target, not just "open it" |
| **`Navigatable`** | Uniform "go to this thing" |
| **Split-and-move / open-in-right-split** | `openInRightSplit(file)` |
| **Per-editor `FileEditorState` serialization** | Ours is bolted on via `DocumentViewState` |

---

## 4. Synthesis — what both agree on

1. **An input is data, not a widget.** Serializable, comparable, carries its resource.
2. **A registry maps input → pane type**, registered once. Call sites never construct widgets.
3. **A pane is retargetable.** `setInput` / `Content` replacement. Panes are reused.
4. **Placement is a named value**, not logic at the call site.
5. **Tool windows and documents are different services** with different lifecycles and different state.
6. **A tool window's placement lives outside the layout tree**, keyed by id. *(Already ported.)*
7. **Everything is push, not poll** — `onDidChangeActiveEditor`, `FileEditorManagerListener`.
8. **Commands find their subject by pulling from context**, not by being handed it.

---

## 5. Target architecture

### 5.1 Layering

```
com.crystalgui.ui.elements.dock        PURE MECHANISM. Knows tabs, splits, groups, drag, placement.
                                       Knows nothing about files, documents or projects.
   ↑
com.crystalgui.ui.elements.workbench   FILES. Documents, resources, dirty state, explorer,
                                       tool windows, type resolution.
   ↑
com.crystalgui.editor                  PRODUCT. Which panels exist and what the default layout is.
                                       NO panel-opening logic. NO per-widget glue.
```

### 5.2 New types in `dock/`

| Type | Port of | Responsibility |
|---|---|---|
| `DockInput` | `EditorInput` / `VirtualFile` | Replaces raw `DockPanelRef` at the API surface: `typeId`, `resource`, `state`, `title`, `icon`, `capabilities`, `matches(other)` |
| `DockCapability` | VS Code `EditorInputCapabilities` | `SINGLETON`, `READONLY`, `UNTITLED`, `CAN_SPLIT`, `DERIVED` |
| `DockPane` | `EditorPane` / `FileEditor` | **The missing piece.** `UIElement view()`, `setInput(DockInput)`, `clearInput()`, `onVisible()`/`onHidden()`, `getViewState()`/`setViewState()`, `dispose()` |
| `DockPaneProvider` | `EditorPaneDescriptor` / `FileEditorProvider` | `accepts(DockInput)`, `create()`, `policy()` |
| `DockPlacement` | `PreferredGroup` | `ACTIVE`, `SIDE`, `CENTRAL`, `WITH(element)`, `GROUP(id)`, `NEW_WINDOW` |
| `DockService` | `IEditorService` + `IEditorGroupsService` | **`open(DockInput, DockPlacement)`**, `close`, `activate`, `groupOf(UIElement)`, `groups()`, `activeGroup()` |
| `DockEvents` | `onDidChangeActiveEditor` etc. | `onDidOpen`, `onDidClose`, `onDidChangeActive`, `onDidChangeDirty`, `onDidChangeLayout` |

### 5.3 Changes to existing types

| Type | Change |
|---|---|
| `DockPanelRegistry` | Becomes provider-based: `register(DockPaneProvider)`. `create` returns a `DockPane`, not a `UIElement` |
| `DockGroup` | Caches `DockPane` per input; calls `setInput`/`onVisible`/`onHidden`. `tabFor` becomes package-private |
| `DockArea` | Implements `DockService`. Gains `open`, `groupOf`. Keeps drag/drop internals |
| `Workbench` | **Splits into four**: `WorkbenchDocuments`, `ToolWindowManager`, `EditorTypeRegistry`, and a thin `Workbench` that wires them |
| `FileDocument` | Gains `CgPath path()` — a document knows its resource |
| `CrystalEditor` | Loses `showCompiled`, `graphPaths`, `pathOf`, `graphForPath`, `assertOnlyChild`, `inspectorHost`, `fillingHost`, `followActiveGraph`, `show`, `followed` |

### 5.4 What this makes possible (the acceptance tests for the design)

> **DONE** — all three, and `CrystalEditor` did end up as "register panel types, state the default
> layout, install commands" plus one `ShaderGraphContribution.register(workbench)` call. The one line
> §5.3 got wrong is `Workbench` **splitting into four**: it did not, and nothing has yet needed it to.
> Its jobs were decoupled from their callers instead, which is what the coupling complaint was actually
> about. `DockGroup.tabFor` also stayed public.

```java
// A widget opens a derived document next to itself. No application involved.
dock().open(GeneratedShaderInput.of(path), DockPlacement.with(this));

// A tool window retargets rather than being rebuilt.
class ShaderInspectorPane implements DockPane {
    public void setInput(DockInput input) { bindTo(resolveGraph(input)); }
}

// Placement, dirty state and restore all come from the framework.
```

`CrystalEditor` should end up as: register panel types, state the default layout, install commands.
**Nothing else.**

---

## 6. Feature backlog surfaced by this research

Ordered by how much they cost to add *later* rather than now.

### Must be in the foundation (retrofitting is expensive)

> **ALL DONE.** `DockPane` (step 5), `DockInput` + `Resource` (steps 4–5), `DockPlacement` +
> `Workbench.open` (step 6), push events (step 3, plus `onDidChangeFocus` later). Per-pane view state
> ships with `DockPane`.
- `DockPane` lifecycle (`setInput`, `onVisible`/`onHidden`, view state) — everything depends on it
- `DockInput` with `resource` + `capabilities`
- `DockPlacement` and `DockService.open`
- Push events instead of polling
- Per-pane view-state serialization (replaces `DocumentViewState`)

### Should be designed for, implemented later

> **Still open, all seven** — and deliberately. None has been asked for by using the thing, which is the
> bar §20.2 sets for phase two.
- **Preview (italic) tabs** — needs a per-tab flag in the group and a replace rule
- **Pinned/sticky tabs** — needs tab ordering to be group state, not layout state
- **MRU order** — needs the group to keep an access list
- **Multiple providers per input + policy** — needs `accepts()` to be a contest, not a lookup
- **Side-by-side / diff inputs** — needs an input that composes two inputs
- **Untitled inputs** — needs a resource that is not yet a path
- **Locked groups** — one boolean on the group, but it changes `open` routing

### Nice, and genuinely independent

> **Still open.** `FloatingDock` exists; the aux-window integration it needs is §11 Tier 2's
> *Window / focus* row.
- Tab overflow menu, tab limit + LRU close
- Watermark in an empty central group
- Back/forward navigation history
- `Navigatable` / open-at-line
- Aux-window integration for `FloatingDock`

---

## 7. Migration plan

> **Stages 1–5 and 7: DONE.** No deprecated delegates survived — `openPanel*` were deleted outright
> rather than kept, and stage 3's medium risk did not materialise.
>
> **Stage 6 (split `Workbench`): NOT DONE, and superseded.** It was the one marked **High** risk, and
> the reason for it — callers reaching the whole workbench — was removed by decoupling instead. What it
> would still buy is the tool-window half, which is §12.4 Parts.
>
> **Stage 8: PARTLY.** `DockPane` carries per-pane view state, but `DocumentViewState` still exists and
> `WorkbenchSession` still uses it. Retiring it is genuinely optional now rather than blocking.

Staged so the suite stays green at every step. Each stage is independently revertable.

| Stage | Work | Risk |
|---|---|---|
| **1** | Introduce `DockInput` as a wrapper over `DockPanelRef`; add `resource`/`capabilities`. Keep `DockPanelRef` internal. | Low |
| **2** | Add `DockPane` + `DockPaneProvider`. Adapt the existing `Function<ref, UIElement>` registry as a legacy provider so nothing breaks. | Low |
| **3** | Add `DockPlacement` + `DockService.open` + `groupOf`. Reimplement `openPanel*` on top; keep them as deprecated delegates. | Medium |
| **4** | `FileDocument.path()`. Delete `CrystalEditor.graphPaths`/`pathOf`/`graphForPath`. | Low |
| **5** | Port the Inspector and generated-shader panels to `DockPane.setInput`. **Delete `assertOnlyChild`, `inspectorHost`, `show`, `followed`, `showCompiled`.** | Medium |
| **6** | Split `Workbench`: extract `ToolWindowManager` (owns `showPanel`/`hidePanel`/`ToolWindowLayout`), `WorkbenchDocuments`, `EditorTypeRegistry`. | **High** |
| **7** | Replace polling with events (`onDidChangeActive*`). Remove `followActiveGraph`, `refreshDirtyMarkers` polling, `ActivityBar.refresh` polling. | Medium |
| **8** | Per-pane view state through `DockPane`; retire `DocumentViewState`. Session codec v4. | Medium |

Stages 1–5 remove the reported pain. Stage 6 is the one that makes the boundaries real, and is the
one worth doing carefully.

---

## 8. Test strategy

The current tests pin *behaviours*; the port needs tests that pin *contracts*.

- **`DockPane` lifecycle** — `setInput` called exactly once per input change; `onHidden` before
  `onVisible` of the next; `clearInput` on close; `dispose` exactly once.
- **Placement** — each `DockPlacement` value resolves to the documented group, including when the
  referenced element has been detached.
- **Retarget vs rebuild** — a pane instance must be the *same object* across an input change
  (identity assertion, like `DockTabPresentationTest` already does for tabs).
- **No polling** — assert that an event fires, not that a frame later the value changed.
- **Session round-trip end to end** — currently missing; write `session.json`, restart a workbench,
  compare the layout, tool-window placements, and per-pane view state.
- **Mutation-check the important ones.** This has caught three real defects in this stack already
  (`syncGroups` vs `requestRebuild`, tab activation, the icon/command split).

---

## 9. Open decisions

> **1 — SETTLED: wrap.** `DockInput` wraps `DockPanelRef`, and the stage-6 replacement never happened,
> so the codec was never bumped for it. Two identity types remain and have not hurt.
> **2 — SETTLED as recommended:** the uniform tree stayed, and no second class of bug appeared.
> **3 — SETTLED by not splitting:** `Workbench` is still one object rather than a facade over four.
> **4 — OPEN**, and now paired with badges: an `ActivityBar` that carries per-view state is most of
> what a `ViewContainer` is, so the two are one piece of work.
> **5 — OPEN.** Still untouched, still cheap-now/expensive-later.

1. **Does `DockInput` replace `DockPanelRef` or wrap it?** Replacing is cleaner and breaks the session
   codec; wrapping keeps compatibility and leaves two identity types. *Recommendation: wrap for stages
   1–5, replace at stage 6 with a codec bump.*
2. **Do tool windows leave the layout tree?** Both references keep them out of the editor grid
   entirely. We deliberately chose a uniform tree, and `ToolWindowLayout` already recovers most of the
   benefit. *Recommendation: keep the uniform tree; revisit only if a second class of bug appears.*
3. **Is `Workbench` kept as a facade after stage 6?** VS Code has no such object; IntelliJ has
   `Project` as the service locator. *Recommendation: keep it as a thin wiring facade, with the real
   services reachable and independently testable.*
4. **Where does `ActivityBar` live** once `ToolWindowManager` exists? It is a view over that manager.
   *Recommendation: it moves with the manager.*
5. **Preview tabs — do we want them?** They are VS Code's most distinctive tab behaviour and some
   people dislike them. Cheap if designed for now, expensive to retrofit.

---

## 10. What is already correct and must not be lost

> **All held.** Nothing in this list was discarded across steps 1–10. `DockLayout` is still pure data,
> the central leaf is still uncloseable, and the widget-owns-its-own-commands rule got *stronger* — it is
> now automatic through `UIElement.registerCommands`/`bindKeys` rather than something a host installs.

Worth stating so the rewrite does not discard hard-won things:

- `DockLayout` being **pure data with no `UIElement`** — this is why the tree is testable at all.
- **n-ary branches** rather than nested binary splits — matches every real IDE's divider feel.
- Weights rather than pixels, with the viewport recorded for reconstruction.
- `ToolWindowState` / `ToolWindowLayout` — the placement port is done and is right.
- Title/icon **providers** on the registry — pulled at build time, so a rebuilt strip is correct.
- `DockPath` + `insertAt` — and its documented limit (a path does not survive a branch collapse).
- The central leaf being uncloseable.
- `swapPrefixedClass`, the internal-child rules, and the "widget owns its own commands/theme" rule.

---

## 11. The full service inventory — everything both references have

Written after a systematic pass over both platforms' service catalogues rather than over our own
symptoms, because the point is to find what we do not know we are missing. Each row says **what breaks
without it**, which is the only column that matters when deciding order.

Verified against the codebase, not assumed: `Disposable`, `MessageBus`, `IProgressService`,
`StatusBar`, `ILifecycleService`, `MenuService` and context keys return **zero** matches anywhere in
`core/`.

### Tier 0 — absent, load-bearing, and expensive to retrofit

These are not features. They are the substrate the reference implementations assume everywhere, and
each one is currently hand-rolled per call site.

#### 0.1 `Disposer` — the disposal tree *(IntelliJ)*

> **DONE** (step 1). GL-aware, with the disposal-protocol rule that a pooled resource *releases* rather
> than deletes — see `CgPreviewPool`.

Every IntelliJ object registers against a **parent `Disposable`**; disposing a parent cascades to
children in reverse registration order. `Disposer.register(parent, child)`.

- **We have:** hand-written `delete()` methods called explicitly. `CrystalEditor.delete()` loops
  `graphs` to free preview FBOs. A panel closed by the dock is never told anything.
- **What breaks:** GL resources leak on any path the author did not think of — and every path added
  later. A `DockPane` that is closed has no hook at all today. We already have a documented instance
  of this class of bug: "a drag ghost is registered per drag" exists precisely because something
  outlived its owner.
- **Why now:** `DockPane.dispose()` in §5.2 is meaningless without a tree to hang it on. Retrofitting
  ownership after the fact means auditing every allocation in the engine.

#### 0.2 Lifecycle phases and shutdown veto *(VS Code `ILifecycleService`)*

> **NOT DONE.** `WorkbenchSession.tick`'s 600-frame retry is gone — it is driven by `onDidLoadListing`
> now — so the *symptom* that motivated this is fixed, but there are still no phases and no shutdown
> veto, and "unsaved changes, really quit?" still has nowhere to live.

Phases `Starting → Ready → Restored → Eventually`, and `onBeforeShutdown` which can **veto**.
IntelliJ: `ProjectManagerListener.canCloseProject`, `SaveAndSyncHandler`, `ShutDownTracker`.

- **We have:** nothing. `WorkbenchSession.tick()` retries expansion for 600 frames and then gives up —
  that is a hand-rolled `Restored` phase with a timeout standing in for a signal.
- **What breaks:** "You have unsaved changes, really quit?" has nowhere to live. Anything wanting to
  run "after restore" must poll. Autosave-on-focus-loss (IntelliJ's `SaveAndSyncHandler`) is
  unimplementable.
- **Note:** we already close a *panel* through a guard (`DockArea.setCloseGuard`). Same idea at the
  wrong scale — which is evidence the shape is needed, not evidence it is covered.

#### 0.3 `MessageBus` and typed topics *(IntelliJ)* — or VS Code's service events

> **DONE** (step 3), and deliberately *not* as a `MessageBus`. Typed signals are public final fields on
> the service that owns the fact — a topic is a global constant anything may publish to, so "who fires
> this" becomes a repo-wide search. All four polls named below are deleted.

`Topic<L>` plus `MessageBusConnection` whose subscription lifetime is tied to a `Disposable`.

- **We have:** `Signal` — per-object, so a listener must already hold the emitter. There is no way to
  express "tell me when *any* document opens".
- **What breaks:** this is *why* everything polls. `followActiveGraph`, `refreshDirtyMarkers`,
  `ActivityBar.refresh` and `WorkbenchSession.tick` are four independent per-frame scans that exist
  because there is nowhere to announce. §7 stage 7 cannot be done without this.

#### 0.4 URI schemes and virtual filesystem providers *(both)*

> **DONE** (step 4) as `Resource` + `ResourceRegistry`. The project scheme keeps `CgPath`'s exact text,
> and a derived resource carries its origin in the text — which is what lets the generated-source tab
> find its graph without a side map.

VS Code: `IFileService.registerProvider(scheme, provider)` — `file:`, `untitled:`, `git:`, `output:`,
`vscode-userdata:`. IntelliJ: `VirtualFileSystem` implementations, `NonPhysicalFileSystem`, and
**`LightVirtualFile`** for in-memory content.

- **We have:** `CgPath` is `project:path`, and its own javadoc says *"the project id plays the
  scheme's role"*. **There is no room for a second scheme.**
- **What breaks:** every derived or virtual document. The generated shader should be a resource under
  its own scheme with a provider that produces its content; instead it is a `DockPanelRef` with a
  `path` state key pointing at the *graph*, plus an application-side map to find the editor. Untitled
  (new file before first save) has the same problem, as would a diff view, an output pane, or a
  read-only decompiled view.
- **This is the most important structural gap after `DockPane`**, because it is what turns "derived
  document" from an application special case into a framework concept.

#### 0.5 A model registry with reference counting *(VS Code `ITextModelService`)*

> **CUT** — see §13.4. Its motivating feature is the same document in two splits, which we do not have.
> The half that was needed (content providers for virtual schemes) landed with step 4.

`createModelReference(uri)` returns a ref-counted handle; `ITextModelContentProvider` supplies content
for schemes that are not files. One model per URI, N editors.

- **We have:** `OpenDocuments` keyed by path. No ref counting, no content provider, no notion of two
  views on one model.
- **What breaks:** the same file open in two split groups. A preview pane and an editor on one
  document. Closing one tab either disposing a model another still uses, or never disposing it.

#### 0.6 Undo grouping across documents *(IntelliJ `CommandProcessor`)*

> **NOT DONE**, and not currently painful: an `UndoStack` is per document, which is the invariant worth
> keeping. Cross-document grouping only matters once one command edits two documents.

`CommandProcessor.executeCommand` wraps a user gesture so everything it touched — possibly several
documents — undoes as **one** step. `UndoManager` tracks the `DocumentReference`s involved.

- **We have:** `UndoStack` with nested transactions, which is good, but scoped per *document*. There is
  no application-level "this gesture was one command".
- **What breaks:** any operation spanning two documents — renaming a shader property that also rewrites
  a generated file, moving a node between graphs — takes two Ctrl+Z presses and can be left half
  undone.

#### 0.7 Actions and menus as contributions with placement and conditions *(both)*

> **DONE** (steps 2.5 and 9). Commands register globally and resolve their subject from `DataContext`;
> `MenuId` + `Command.menu(group, order)` + `ContextMenu.of` place them, and `enabledWhen` /
> `enabledWhereData` are the conditions. The `when`-**expression parser** stays cut — see §13.4.

VS Code `IMenuService` + `MenuId` (`EditorTitle`, `EditorTitleContext`, `ExplorerContext`,
`ViewTitle`, …) with `group`/`order` (`navigation@1`) and `when` clauses evaluated against
`IContextKeyService`. IntelliJ: `ActionManager`, `ActionGroup`, `ActionPlaces`, `Presentation`,
`DataContext`.

- **We have:** `CommandRegistry` plus `Command.enabledWhen(Predicate<CommandContext>)`, and menus built
  by hand (`fileTree.setContextMenu(registry, ExplorerCommands::menu)`). **Zero context keys.**
- **What breaks:** a widget cannot *contribute* to another widget's menu. A shader graph wanting an
  item in the editor-tab context menu has to reach the code that builds that menu — the same coupling
  as `showCompiled`, one layer up.

#### 0.8 A scheduling seam *(both)*

> **NOT DONE.** Still the prerequisite for the Progress service, and still nothing needs it.

`invokeLater` with `ModalityState`, `Alarm` for debounce (IntelliJ); scheduler primitives behind
`IProgressService` (VS Code).

- **We have:** frame tickers and a couple of ad-hoc debounces. Zero `ModalityState`.
- **What breaks:** anything that must run "after this frame", "not while a modal is up", or "300 ms
  after the user stops typing" grows its own bespoke counter. Several already exist.

### Tier 1 — present but ad-hoc; these will fight the port

> **One row is DONE: Status.** `onStatus` is deleted, replaced by `Notifications` (events, with severity,
> actions and a bounded history) and `StatusBar` (ambient text, **keyed per writer**, so two writers no
> longer overwrite each other). Priority and persistence are still absent.
>
> **Command context** moved without closing: keys are still not published, but `DataContext` means a
> command resolves its own subject, so the practical need for `when` clauses is much smaller —
> `enabledWhereData` states the condition in Java. See §13.4 for why the parser stays cut.
>
> The other six rows stand as written.

| Concern | Reference | Ours | What is actually missing |
|---|---|---|---|
| Settings | `IStorageService` (APPLICATION/PROFILE/WORKSPACE × USER/MACHINE); `PersistentStateComponent` + `RoamingType` | `SettingsLayer`, hand-written codecs | **Scopes.** No way to say per-machine vs shared vs synced |
| Command context | `IContextKeyService` — `activeEditor`, `resourceExtname`, `editorIsOpen` | `Predicate<CommandContext>` | Keys are not *published*, so nothing else can key off them, and `when` clauses in keymaps are impossible |
| Keymap | `IKeybindingService` with when-clauses and chords | `Keymap.bind(spec, id)` | No conditions, so one key cannot mean two things in two contexts |
| Problems | `IMarkerService` — anything publishes markers under an owner id | `ProblemsPanel` + editor diagnostics | Not a service: only the editor can publish, so a shader-graph compile error has no route in |
| File events | `AsyncFileListener` with *before* and *after* phases | `client.onFileChanged` | No before-phase, so nothing can react ahead of a change or veto it |
| File types | `FileTypeManager` + `FileNameMatcher` | `FilePatternMap`, `LanguageRegistry`, `FileIconTheme` | Three parallel registries keyed the same way, none aware of the others |
| Status | `IStatusbarService` / `StatusBarWidgetFactory` | `Signal.Value<String> onStatus` | One string. No widgets, no ownership, no priority, no persistence |
| Session persistence | `PersistentStateComponent<T>`, declarative | Hand-written codec per class, one version int | Every newly persisted thing is bespoke; §7 stage 8 will feel this |

### Tier 2 — real cogs we simply do not have

| Cog | Reference | What it buys | Cost if added later |
|---|---|---|---|
| **Progress** | `IProgressService`, `Task.Backgroundable` | Long operations (compile, index, workspace crawl) get a home, cancellation, and a location | Medium — wants the scheduling seam first |
| **Notifications** | `INotificationService`, `NotificationGroup` | Severity, actions, "don't show again", history. Retires `onStatus` | Low |
| **Dialogs** | `IDialogService` | Save / Don't Save / Cancel, checkboxes, "always do this" | Low — `InputDialog.confirm` is the seed |
| **Quick input** | `IQuickInputService` — quick pick, input box, multi-step | `GoToFile` and the command palette are bespoke widgets that should each be two calls | Medium |
| **Editor notifications** | `EditorNotificationProvider` | The banner above an editor: "read-only", "generated file — edits will be lost on the next compile". **The generated shader needs exactly this today** | Low |
| **Activity badges** | `IActivityService.showActivity` | Problem count on the activity bar; flagged while building the rail | Low |
| **Label service** | `ILabelService` | One place deciding how a resource is displayed (name / relative / full). We hardcode `path.name()` in several places | Low |
| **History / navigation** | `IHistoryService`, `IdeDocumentHistory` | Ctrl+Tab MRU, back/forward, reopen closed tab | Medium — needs per-group MRU |
| **Readiness (dumb mode)** | `DumbService`, `DumbAware` | The workspace crawl has no readiness signal, so Go-To-File silently searches a partial index | Medium |
| **Local history** | `LocalHistory` | Recover an unsaved or overwritten file | High |
| **Search** | `ISearchService` | Find-in-files as a service others can call | High |
| **Opener** | `IOpenerService` | Clicking a resource anywhere resolves through one place | Low |
| **Context menus** | `IContextViewService` / `IContextMenuService` | Menus shown, positioned and dismissed by one service | Low |
| **Window / focus** | `IHostService`, `IdeFocusManager` | Focus arbitration, new windows, auxiliary windows. `FloatingDock` needs this to be finishable | Medium |
| **Multi-root workspaces** | `IWorkspaceContextService` | Several projects open at once; `ProjectRegistry` is close | Medium |
| **Instrumentation seam** | `ITelemetryService` | Not for shipping data — a seam `CgProfiler`-style counters can hang off | Low |

### Tier 3 — deliberately out of scope, recorded so the omission reads as a decision

Extension host and plugin isolation, workspace trust, remote/`ClientId` sessions, the accessibility
tree, localisation, a PSI-equivalent language model, run/debug configurations, and VCS integration
beyond the decoration provider already ported.

### 11.1 The three that change the plan in §7

> **All three DONE, and all three inserted where this said to.** `Disposer` went first; the generated
> shader landed as a `Resource` with a scheme and a carried origin rather than a path state key — which
> is exactly the mistake #2 warned about, avoided; and the polling replacement was scheduled as a
> substrate rather than a refactor, which is why steps 5–6 were as smooth as predicted.
>
> The revised order was followed except for its last two entries: the workbench split did not happen
> (§7), and view state came with `DockPane` rather than after it.

Most of the above is additive and can land whenever. Three are not:

1. **`Disposer`** — `DockPane.dispose()` (stage 2) is a lie without a tree to hang it on.
   **Insert before stage 2.**
2. **URI schemes** — the generated shader is our first derived document and stage 5 ports it. If it
   lands as "a `path` state key pointing at the graph", the current mistake is baked into the new API.
   **Insert before stage 5.**
3. **`MessageBus`** — stage 7 (replace polling) *is* this. Naming it a substrate rather than a
   refactor makes it schedulable earlier, and stages 5–6 get simpler if it already exists.

**Revised order:** `Disposer` → `DockInput` + schemes → `DockPane` → `MessageBus` →
`DockPlacement`/`DockService` → pane ports → workbench split → view state.

### 11.2 The honest summary

We have built, competently, the parts of an IDE that you can see: a dock, tabs, drag and drop, a tree,
a text editor, a node graph. What is missing is nearly everything an IDE *rests on* — ownership,
lifecycle, addressing, messaging, and contribution. None of it appears in a screenshot, which is
exactly why none of it got built, and it is the reason each new feature has cost more than the one
before it.

---

## 12. The spine — what actually makes them extensible

§11 is a catalogue. This section is the thesis: **strip both platforms of every feature and the same
skeleton is left underneath**, and that skeleton is what lets them add the thousandth feature as
cheaply as the tenth. We do not want their features. We want this.

### 12.1 Six primitives and two composites

Everything in either IDE — every panel, every editor, every menu item, every gutter icon — is built
out of six things. Nothing else is irreducible.

| # | Primitive | The one sentence | Ours |
|---|---|---|---|
| 1 | **Resource** | Every *thing* has a stable, serialisable identity that is not an object reference | `CgPath`, single-scheme |
| 2 | **Disposable** | Every object has an owner, and disposing an owner cascades | none |
| 3 | **Contribution** | Capabilities are *declared against a type*, never constructed at a call site | partial: registries exist, keyed ad-hoc |
| 4 | **MessageBus** | Anything can announce; anyone can listen, for a bounded lifetime | none (`Signal` is per-object) |
| 5 | **DataContext** | "What am I acting on?" is answered by pulling outward from focus | none |
| 6 | **Command** | Every capability is a named, addressable, conditionally-enabled action | yes, without conditions |

| Composite | Built from | Purpose |
|---|---|---|
| **Model registry** | Resource + Disposable + Contribution | One model per resource, ref-counted, content supplied by a provider |
| **View framework** | Model registry + Command + DataContext | Retargetable views with persisted view state |

**The claim being made:** a feature that needs a seventh primitive is a feature that will be bolted on
badly. Every time we have bolted something on — `showCompiled`, `graphPaths`, `assertOnlyChild`,
`followActiveGraph` — it was a missing one of these six, hand-rolled locally.

### 12.2 Why each is irreducible

**1. Resource — addressing.** A stable identity is the precondition for persistence, comparison,
routing, lazy resolution and caching. The moment a thing is identified by an object reference,
it cannot be saved, cannot be restored, cannot be found by another subsystem, and cannot exist before
it is loaded. *Everything else in the list keys off this.* Our `DockPanelRef` is nearly it; our
`CgPath` is nearly it; neither is quite a URI and the two are unrelated to each other.

**2. Disposable — lifetime.** Without an ownership tree, "register X with Y" is a leak unless the
author remembers to unregister. That means every subsystem must be defensive, and the defensive code is
the code nobody writes. **This is what makes it safe for anything to register anything anywhere**, and
therefore it is the precondition for extensibility itself.

**3. Contribution — declaration over construction.** `FileEditorProvider.accept(file)` /
`registerEditor(glob, …)`. The call site says *what* it wants, never *which class* renders it. This is
the difference between a feature being additive (register a provider) and invasive (edit a switch, a
factory, or an application class). Our registries do this for panel types and stop there.

**4. MessageBus — decoupled announcement.** Push, with subscriptions scoped to a `Disposable`. The
alternative is polling, and polling is what we do in four places. Note the dependency: **a bus is
unsafe without #2**, because a subscription that outlives its subscriber is a leak that fires.

**5. DataContext — the pull.** IntelliJ's `DataKey`/`DataProvider`: a command asks "give me the
`VIRTUAL_FILE` here", and the answer is assembled by walking outward from the focused component, each
level contributing what it knows. VS Code's `IContextKeyService` is the same idea inverted — the
current state *publishes* facts (`resourceExtname`, `editorIsOpen`) that conditions read.
**This is what lets one command work everywhere.** Delete works in the tree, the graph and the editor
because each supplies a different subject, not because Delete knows about three widgets.

**6. Command — the addressable capability.** Once every capability is named, every UI affordance is a
*view* over the command set: menus, keymaps, palette, toolbar buttons, context menus, gutter actions.
Add a command and it appears in all of them. We have this and it is genuinely good — it is why the
activity bar was cheap. What is missing is #5 to condition it.

### 12.3 The four ways elements talk — the "fluency" question

Two elements that do not know each other need exactly four communication shapes. Every IDE
interaction is one of them, and having all four is what makes the system feel *wired* rather than
assembled.

| Shape | Question | Mechanism | Direction | We have |
|---|---|---|---|---|
| **Ask upward** | "What am I inside? What is the subject here?" | `DataContext` / context keys | focus → outward | ✗ |
| **Ask sideways** | "Who does this job?" | Service locator / DI | anywhere → well-known address | partial (`CgPlatform` for platform only) |
| **Announce** | "This happened." | `MessageBus` topic | one → many, decoupled | ✗ |
| **Declare** | "I can do X, when Y." | Contribution registry | plugin → framework | partial |

Our current answers to the same four questions:

- Ask upward → **walk `getParent()` by hand** (`GraphCommands.graphFor`, `ShaderGraphEditor.editorFor`,
  `UndoScope.nearest`). Three hand-rolled copies of `DataContext`, each knowing one type.
- Ask sideways → **reach through the application** (`CrystalEditor.workbench().dock()`). This is
  `showCompiled`.
- Announce → **poll every frame**.
- Declare → registries for panels and commands; nothing for menus, decorations beyond files, markers,
  editors-per-type, or view containers.

**Fixing "ask upward" is disproportionately valuable.** It converts three bespoke walks into one
mechanism, and it is the thing that makes a command written once work in a widget written later.

### 12.4 The Parts model — what docking is actually missing

We treat the workbench as *one dock tree plus an activity bar beside it*. Neither reference does.

VS Code has a fixed set of **Parts** — TITLEBAR, BANNER, ACTIVITYBAR, SIDEBAR, EDITOR, PANEL,
AUXILIARYBAR, STATUSBAR — each independently **visible, sizeable, positionable and persisted**, with
the editor grid living *inside* the EDITOR part. IntelliJ has the same split: tool window stripes and
anchors around a `EditorsSplitters` in the middle.

And inside that, a second distinction we do not have at all:

```
ViewContainer   ("Explorer", "Source Control")  ← what an activity bar button toggles
   └─ View      ("Folders", "Outline", "Timeline")  ← draggable BETWEEN containers
```

**Our activity bar lists panels directly.** So there is no way to group two tool windows into one
sidebar pane, no way for a user to drag a view from the sidebar to the bottom panel, and nowhere for
"toggle the sidebar" (Ctrl+B) to live — because the sidebar is not a thing, it is wherever the Project
panel happens to be docked.

This is the structural reason tool-window placement has been so painful. We made tool windows into
*layout tree nodes* and then had to reconstruct their identity from tree position. `ToolWindowLayout`
recovered most of it; a Parts model would have meant never losing it.

| Concept | VS Code | IntelliJ | Ours |
|---|---|---|---|
| Fixed layout regions | Parts | anchors + editor area | ✗ (one uniform tree) |
| Group of views sharing a region | `ViewContainer` | tool window with `ContentManager` | ✗ |
| Individual view, movable between regions | `ViewPane` | `Content` | = a dock panel |
| Region visibility toggle | `IWorkbenchLayoutService.setPartHidden` | `ToolWindow.hide()` | ✗ |
| Region size, persisted independently | `workbench.sidebar.width` | `WindowInfo.weight` | in the tree |

**Recommendation:** keep the uniform dock tree for the *editor area* — it is good, and it is what makes
arbitrary splits work — and introduce Parts around it. That is exactly the shape both references have,
and it retires the entire class of "where does a closed tool window belong" problem rather than
managing it.

### 12.5 How it knits — one feature traced through the spine

The test of a skeleton is that a new feature touches only the seams. Trace **"Find Usages"** — a
feature we do not have and are not building, chosen because it is unremarkable in both IDEs:

1. **Resource** — results are addressed (`usages:` scheme over a query), so the panel can be restored.
2. **Contribution** — register a `ViewContainer` + a command `usages.find`; register a pane provider
   for the `usages:` scheme.
3. **DataContext** — the command reads the subject from context. It works in the editor, the tree and
   the graph without naming any of them.
4. **Command** — `enabledWhen` a subject exists. Appears in the palette, the context menu and a keymap
   for free.
5. **MessageBus** — subscribes to document changes to invalidate results.
6. **Disposable** — the subscription and the results model die with the panel.
7. **Model registry** — the results model is ref-counted, so two panels on one query share it.
8. **View framework** — the pane is retargetable, so re-running the search reuses it (VS Code's
   preview behaviour) and its scroll position is view state.

**Zero application code.** No `CrystalEditor` method, no map on the side, no host swapping children.
Compare with what adding the generated-shader view cost us, which was: an application method, an
application map, a stable host, a child-swapping helper, and four bugs.

That contrast *is* the argument.

### 12.6 What we already have that slots straight in

The rewrite is smaller than §11 makes it look, because several load-bearing pieces are already right
and simply need connecting:

| Ours | Slots in as |
|---|---|
| `Command` + `CommandRegistry` + `Keymap` | Primitive #6, complete except conditions |
| `DockLayout` (pure data, n-ary, weights) | The editor-area grid. Keep exactly as is |
| `ToolWindowState` / `ToolWindowLayout` | Per-view persisted placement — already the right shape |
| `DockPanelRef` | Becomes `DockInput`; already serialisable and value-equal |
| `Signal` | Stays for object-local events; the bus is for cross-cutting ones |
| `UndoStack` + transactions + `UndoScope.nearest` | Undo, needing only the cross-document grouping layer |
| `StateMap` / `Codec` / `DynamicOps` | The serialisation substrate a declarative state framework needs |
| `FileDecorations` (merge + bubble) | Already a faithful contribution registry — **use it as the template for the others** |
| `CgPlatform` | Proof the service-locator pattern already works here |
| `FilePatternMap` | The matcher half of a file-type service |

`FileDecorations` is worth singling out: independent providers, merged per field, bubbling to
ancestors, refreshed through a deferred pass. **That is exactly the contribution-registry shape every
other subsystem needs**, and we built it once and did not generalise it.

### 12.7 The minimal spine, in build order

> **1–7 DONE, 10 DONE, 8 CUT, 9 OPEN.**
>
> | # | Outcome |
> |---|---|
> | 1 `Disposer` | **DONE**, GL-aware |
> | 2 `Resource` | **DONE** — `graphPaths` deleted as predicted |
> | 3 `MessageBus` | **DONE as typed service signals**, not a bus — see §0.3's note. All four polls gone |
> | 4 `DataContext` | **DONE** — and it turned out to matter more than expected: the Inspector's subject comes from it |
> | 5 Context keys + `when` | **PARTLY** — `enabledWhen`/`enabledWhereData` are the conditions; keys are not published and the expression parser is cut (§13.4) |
> | 6 `DockPane` | **DONE** — `assertOnlyChild` gone, and `UIElement.setOnlyChild` is the general form |
> | 7 `open(input, placement)` | **DONE** as `Workbench.open`; `showCompiled` and all three overloads gone |
> | 8 Model registry | **CUT** (§13.4) |
> | 9 Parts + ViewContainers | **OPEN** — the only large item left, and the one §20.2 says to decide after living with phase one |
> | 10 Menu contributions | **DONE** — cheaper than costed; the mechanism existed and only lacked users |
>
> The closing prediction held. A notification service and an editor banner were each about a day, and
> each was "a small contribution registry plus a view" exactly as written — `Notifications`/`StatusBar`
> and `DockBannerProvider`. Activity badges are the remaining one, and they are the case where the
> skeleton is **not** enough on its own: they want per-view state on the rail, which is most of what
> step 9 is.

Not the full §11 catalogue — the smallest set that makes everything after it cheap. Each step is
useful alone and unlocks the next.

| # | Build | Unlocks | Rough size |
|---|---|---|---|
| 1 | **`Disposable` + `Disposer`** | Safe registration anywhere. Precondition for 2, 4, 7 | small |
| 2 | **`Resource`** — real URIs with schemes; `CgPath` becomes one scheme | Derived/virtual/untitled documents. Kills `graphPaths` | medium |
| 3 | **`MessageBus`** — typed topics, `Disposable`-scoped | Retires all four polling loops | small |
| 4 | **`DataContext`** — keys + providers, pulled from focus | One command working everywhere. Retires three hand-rolled walks | medium |
| 5 | **Context keys + `when`** on commands and keymaps | Conditional UI everywhere; menus become contributions | small |
| 6 | **`DockPane`** — retargetable view + lifecycle + view state | Kills `assertOnlyChild` and every future copy of it | medium |
| 7 | **`DockService.open(input, placement)`** + `groupOf` | Kills `showCompiled` and the three `openPanel*` overloads | medium |
| 8 | **Model registry**, ref-counted, provider-backed | Same document in two panes; content for virtual schemes | medium |
| 9 | **Parts + ViewContainers** | Sidebar/panel/auxbar as real things; Ctrl+B; views movable between regions | large |
| 10 | **Menu contributions** (`MenuId` + group/order + `when`) | Any widget contributing to any menu | medium |

Steps 1–7 are the heart. **8–10 are the spine that lets features stack.** Everything in §11 Tier 2
becomes ordinary work once 1–10 exist — a notification service, a progress service, an editor banner,
activity badges are each a small contribution registry plus a view, which is a day's work each *given
the skeleton* and a redesign without it.

### 12.8 The one-paragraph version

**Give every thing an address, every object an owner, every capability a name, every event a bus, and
every question a way to be asked of its surroundings — then make views retargetable shells over
ref-counted models, hosted in named regions.** That is the whole of both IDEs' architecture. Their
feature lists are a consequence of it, not an achievement beside it. We have three of those six
sentences and half of the seventh; the gap is not size, it is that the missing ones are the ones that
make the others compose.

---

## 13. Judgement — what I actually think, and where this goes wrong

§12 is the skeleton. This section is the engineering opinion on building it, including the parts I
would argue against and the risks I would not discover until halfway.

### 13.1 The encouraging fact, stated precisely

**Most of both platforms is features, not substrate.** The substrate is small — startlingly so:

| Piece | Approximate size in the reference |
|---|---|
| IntelliJ `Disposer` + `Disposable` | ~300 lines of real logic |
| IntelliJ `MessageBus` + `Topic` + connections | ~600 lines |
| IntelliJ `DataContext` / `DataKey` / `DataProvider` | ~200 lines of mechanism (the *keys* are the bulk, and keys are trivial) |
| VS Code `IEditorService.openEditor` + `PreferredGroup` resolution | a few hundred lines |
| VS Code `EditorPane` base + lifecycle | a few hundred lines |
| VS Code `ContextKeyService` + expression parser | ~1000 lines, and the parser is most of it |

The spine in §12.7 is realistically **3,000–5,000 lines of production code**, plus comparable test
code, plus touching perhaps thirty existing files. That is a large piece of work and it is *not* a
multi-year one. The reason IntelliJ is millions of lines is inspections, refactorings, language
support, debuggers and VCS — none of which we want.

**So the honest answer to "can CrystalGUI be on their level architecturally" is yes, and the number is
about a month of focused work, not a year.** What makes it feel impossible is that the substrate is
invisible, so it never gets prioritised against something you can screenshot.

### 13.2 What I would build differently from the references

Three places where porting faithfully would be a mistake for us.

**1. Service-owned typed events, not a global `MessageBus`.**
IntelliJ's `MessageBus` with global `Topic`s is powerful and makes control flow genuinely hard to
follow — you cannot find who reacts to something without a global search, and ordering between
subscribers is unspecified. VS Code's model (each service exposes `onDidChangeX` events) is more
traceable and composes just as well. **With a small team and no third-party plugins, traceability beats
decoupling.** Build typed events on the services from §5.2, not a topic bus. If a plugin API ever
arrives, a bus can be layered on top; the reverse is not true.

**2. Keep the uniform dock tree; add Parts only around it.**
VS Code's fixed Parts exist partly for historical DOM reasons and their editor grid is *also* a tree.
Our uniform tree is genuinely good and is why arbitrary splits work. The synthesis in §12.4 — Parts for
chrome, tree inside the editor Part — is the right target, but **I would defer it to last**. It is the
largest single item, and its payoff (Ctrl+B, view containers, drag a view from sidebar to bottom) is
real but smaller than the payoff of steps 1–7.

**3. Do not change `CgPath`. Wrap it.**
`CgPath`'s own javadoc: *"These are written into saved documents, so `toString()` and `parse(String)`
must round trip exactly and forever."* Changing its grammar is a **document format break**, and every
saved graph contains them. The correct move is a `Resource` abstraction *above* it where `CgPath` is
the `project:` scheme and new schemes sit beside it. This is strictly better than what I wrote in §12.7
step 2 and supersedes it.

### 13.3 The risks, ranked by what actually kills projects

**1. Building the substrate speculatively.**
Both references grew theirs *under feature pressure* over a decade. Abstractions invented ahead of
their second consumer are usually wrong. **Mitigation, and it is the reason I am reasonably confident
here:** every step in §12.7 retires a *specific bug we already paid for*, listed in §0. None of it is
speculative. If a step cannot name the concrete pain it removes, cut it — which is precisely why I
would cut the model registry (§13.4).

**2. Doing all ten steps before shipping anything.**
A four-week rewrite with nothing usable in between is how this stalls. Each step must land, be tested,
and leave the product working. Steps 1–5 are individually shippable. Step 6 (`DockPane`) is the first
one that forces a migration of existing panels, and it should be done with adapters so old and new
coexist.

**3. Two agents in one working tree.**
We hit "the fork is mid-write" roughly six times in one session, twice producing spurious build
failures I had to distinguish from real ones. A rewrite touching thirty shared files with concurrent
editors is a genuine hazard, not a nuisance. **This needs a coordination answer before step 1** —
separate branches, or file-level ownership, or serialised phases.

**4. The `DockPanelRef` identity change.**
Its equality includes state, so adding a field breaks saved sessions — this already bit us with `ICON`.
Migrating it to `DockInput` will break every persisted layout at least once. That is acceptable *if
planned*: one codec bump, one discard. It is not acceptable discovered late.

**5. Losing the documented invariants.**
This codebase's real asset is that the hard-won rules are written down at the point they matter —
`sortedChildren` ordering, the internal-child recursion trap, `flex-shrink` overflow, `replaceOrPutCandidate`
no-op, seam ownership in the SVG fills. **A rewrite is exactly how those get deleted by someone who
does not know why the line was there.** §10 exists for this; it should be treated as a checklist during
the port, not a footnote.

### 13.4 What I would cut from my own plan

- **Model registry with ref-counting (§12.7 step 8).** Its motivating feature is the same document in
  two splits. We do not have that, may never want it, and `OpenDocuments` keyed by path already gives
  one-model-per-resource. **Cut until something needs it.** What *is* needed from it — content
  providers for virtual schemes — is small and belongs with step 2.
- ~~**Menu contributions (step 10).**~~ **DONE, and cheaper than costed.** The mechanism (`MenuId`,
  `Command.menu`, `ContextMenu.of`, submenus, dimmed-disabled) already existed and only lacked users; the
  remaining work was migrating `BlackboardPanel`'s row menu, which was the last hand-written one. The
  `when`-expression *parser* stays cut: `enabledWhen`/`enabledWhereData` already state the condition in
  Java, and a string language buys nothing until keymaps and menus are authored outside Java.
- **Everything in §11 Tier 2** except activity badges (a few lines once the bar exists),
  `EditorNotificationProvider` — **now done** as `DockBannerProvider`, and asked with a `DockPanelRef`
  rather than a document, because the tab that needed it is not one — and **Notifications, now done** —
  it was promoted because it is what a contribution needed in order to stop being handed a status sink.
- **§11 Tier 3 entirely**, permanently.

### 13.5 The one thing I would add that neither reference needs

**GL resource lifetime is a first-class concern for us and is not for them.** Neither IDE owns FBOs,
textures or shader programs — their `Disposable` tree is about listeners and caches.

**Stated precisely, because the obvious objection is right.** `CgGraphicsLifecycle.destroyContext()`
already sweeps materials, textures, meshes, VAOs, VBOs, shader buffers and registry-created FBOs in a
documented order. Nothing escapes the process. So `Disposer` is **not** about shutdown. It is about the
two things registries structurally cannot do:

1. **Freeing on close rather than on exit.** `DockArea.closePanel` calls `layout.closePanel` and
   `requestRebuild()`; **the content is never told it was closed**, and `pruneStaleGroups` merely drops
   the group from a map. `ShaderGraphEditor.delete()` is called from exactly one place —
   `CrystalEditor.delete()`, at application exit. So closing a graph tab frees nothing, and twenty
   open-and-close cycles hold twenty preview pools.
2. **Reaching resources no registry can see.** `CgPreviewRenderer.delete()` says it outright: *"the
   pool's targets are `createOwned`, so no registry sweeps them."* `CgUiPaintContext`'s layer FBO pool
   is the same, and AGENTS.md records that `deleteAll()` never reaches it. For this class, freeing
   depends entirely on someone remembering — which is the thing an ownership tree exists to stop
   depending on.

There is a live instance of both: `CrystalEditor.graphs` is appended to on creation and read only in
`delete()`. It is never pruned, so **every graph ever opened is retained for the session** — and that
retention is the only reason its GL pool is freed at exit at all.

So: registries give **shutdown correctness**; `Disposer` gives **lifetime correctness**. That is a
smaller claim than "it stops GPU memory leaking" and a more accurate one, and it still makes `Disposer`
step 1 — because every later step adds objects with owners, and adding them before there is an
ownership tree means retrofitting one later over a larger surface.

Its design does diverge from a faithful port in one way: disposal must be able to run on the GL thread,
because freeing a GL object off-thread is silent corruption rather than a crash. Neither reference's
implementation has that requirement.

### 13.6 What "on their level" honestly means here

Not feature parity — you said that. Concretely, it means passing these:

1. **A new panel type is one registration and one class.** No application edit.
2. **A widget can open, close and place another panel** without naming the application.
3. **A command written once works in every widget** that can supply its subject.
4. **Closing anything frees everything it owned**, including GPU memory, with no explicit teardown code.
5. **Anything can react to anything** without polling.
6. **The whole workbench state round-trips** — layout, placements, view state, open documents — and a
   restore is indistinguishable from never having quit.
7. **Adding a feature does not require understanding the workbench**, only the seam it plugs into.

We currently pass **one** of these (3, partially — commands work, conditions do not). After §12.7 steps
1–7 we would pass 1, 2, 3, 4, 5 and most of 6. That is the whole argument for the sequence.

### 13.7 What I would actually do, in order, if it were my call

> **1–6 DONE, in exactly this order. 7 not done and no longer wanted** — see §7's note. The
> "then reassess" happened: menu contributions turned out to be nearly built already, and the second
> phase is now just Parts/ViewContainers plus activity badges, which are one piece of work.

1. **`Disposable`/`Disposer`, GL-aware.** ~2 days. Highest value, lowest risk, immediately retires a
   real leak class. Nothing else depends on being done first, and everything is safer after it.
2. **`DataContext` + context keys.** ~3 days. Retires three hand-rolled parent walks, makes commands
   conditional, and is the precondition for menus later. Small, self-contained, high leverage.
3. **Typed service events; delete the four polling loops.** ~3 days. Immediately visible as reduced
   per-frame work, and it makes every subsequent step easier to reason about.
4. **`Resource` wrapping `CgPath`, plus virtual schemes and content providers.** ~4 days. Unblocks the
   generated shader properly and kills `graphPaths`.
5. **`DockPane` + lifecycle + view state.** ~5 days. Kills `assertOnlyChild` and every future copy.
6. **`DockService.open` + `DockPlacement` + `groupOf`.** ~3 days. Kills `showCompiled` and the three
   `openPanel*` overloads.
7. **Split `Workbench`.** ~5 days, highest regression risk, do it last of the core set and with the
   contract tests from §8 already in place.

Then reassess. Parts/ViewContainers and menu contributions are a second phase, and by then we will know
whether they are worth it from having lived with the first.

### 13.8 The thing I am least sure about

**Whether step 7 (splitting `Workbench`) is worth its risk.** The boundary is intellectually right and
both references have it. But `Workbench` is 1181 lines of code that *works*, with heavy test coverage,
and the split buys architectural cleanliness rather than a capability. Steps 1–6 remove every concrete
bug in §0; step 7 removes the *shape* that produced them.

My honest read: **do steps 1–6, live with them for a while, and only split `Workbench` if the seams
start hurting again.** If steps 1–6 are done well, `Workbench` becomes a thin facade over them almost
by attrition — the methods stop having logic in them — and the split becomes trivial rather than risky.
That is a better path to the same place than a big-bang refactor, and it is roughly how VS Code's own
services got extracted.

---

# Part II — Implementation specifications

One section per step of §13.7. Each is written to be executable without re-deriving the research:
what the reference does, the concrete API, the files, the migration, the tests, and the traps
specific to *this* codebase.

**Ground rules for all six.**

- Every step lands green and leaves the product working. No step depends on a later one.
- Every new type gets the same documentation density as the rest of the codebase: state the decision
  and the failure it prevents, not the mechanics.
- Adapters over rewrites. Old API keeps working until its last caller is gone, then is deleted in the
  same step that removes it.
- Contract tests, not behaviour tests (§8). Mutation-check the load-bearing ones.

---

## 14. Step 1 — `Disposable` / `Disposer`, GL-aware — **DONE**

> **Shipped.** `core/dispose/{Disposable,Disposer}.java`, 14 contract tests in `headlessTest`, and
> adoption in `CgUiLifecycle`, `OpenDocuments`, `ShaderGraphEditor` and `CrystalEditor`. Both suites
> green. Two deviations from the spec below, each recorded where it happened:
> **§14.4a** (what moved to step 3) and **§14.4b** (what was cut as speculative).


**Why first:** it is the precondition for safe registration anywhere, and `DockPane.dispose()` is
meaningless without it. 39 files already have a hand-written `delete()` or `dispose()`.

**What it is not.** It does not replace `CgGraphicsLifecycle.destroyContext()`, which already sweeps
every registry in a documented order and is correct. See §13.5: this is about **lifetime**, not
shutdown — freeing when a panel closes rather than when the process exits, and reaching the
`createOwned` resources that no registry can see.

**The concrete target for this step**, chosen because it is a live defect rather than a hypothetical:

| Today | After |
|---|---|
| `DockArea.closePanel` tells the content nothing | closing a panel disposes it |
| `ShaderGraphEditor.delete()` reachable only from `CrystalEditor.delete()` | reached when its tab closes |
| `CgPreviewRenderer`'s `createOwned` pool freed only at exit | freed with its graph |
| `CrystalEditor.graphs` never pruned — every graph ever opened is retained | list deleted; ownership is the tree |

### 14.1 What IntelliJ does

`com.intellij.openapi.util.Disposer` over an `ObjectTree`:

- `Disposable` is one method: `void dispose()`.
- `Disposer.register(parent, child)` — parent→children, and object→node maps.
- `Disposer.dispose(d)` disposes **children first, in reverse registration order**, then `d` itself,
  then unlinks from its parent. Reverse order matters: a later child may depend on an earlier one.
- **Idempotent.** Double dispose is a no-op; `isDisposed` is queryable.
- Registering against an already-disposed parent is an error — IntelliJ logs and disposes the child
  immediately rather than leaking it.
- `Disposer.newDisposable(name)` for an anonymous lifetime owner.
- Exceptions in one child's `dispose()` must not prevent siblings from being disposed.

### 14.2 What we add that IntelliJ does not need

GL objects must be freed on the GL thread; freeing off-thread is silent corruption, not a crash.
So disposal is **gated**, not immediate, for anything that owns GPU memory.

### 14.3 API

```java
package com.crystalgui.core.dispose;

public interface Disposable {
    void dispose();

    /**
     * A disposable that owns GPU resources. Disposal is deferred to the GL thread rather than run
     * wherever the request came from — freeing a GL object off-thread corrupts silently.
     */
    interface Gl extends Disposable { }
}

public final class Disposer {
    public static void register(Disposable parent, Disposable child);
    public static boolean tryRegister(Disposable parent, Disposable child);  // false if parent disposed
    public static void dispose(Disposable target);
    public static boolean isDisposed(Disposable target);
    public static Disposable newDisposable(String debugName);

    /** How a Disposable.Gl reaches the GL thread. Default runs immediately — the headless case. */
    public static void setGlGate(BooleanSupplier onGlThread, Consumer<Runnable> deferToGlThread);

    /** Drains deferred GL disposals. Called once a frame by CgUiLifecycle.onFrame. */
    public static void drainGlQueue();

    /** Debug: live node count and the roots, for a leak assertion in tests. */
    public static int liveCount();
}
```

### 14.4 Files

**New**
- `core/.../core/dispose/Disposable.java`
- `core/.../core/dispose/Disposer.java`
- `core/src/test/.../core/dispose/DisposerTest.java`

**Modified**
- `render/CgUiPaintContext.java` — `destroy()` becomes `dispose()`; register the layer FBO pool,
  `CgUiRenderer` and `CgTextRenderer` as children.
- `lifecycle/CgUiLifecycle.java` — `onFrame` drains the GL queue; `onDestroy` disposes the root.
- `graph/shader/ShaderGraphPreviews.java`, `MainPreviewPanel.java` — register per-node FBOs against
  the graph editor rather than relying on `CrystalEditor.delete()` looping `graphs`.
- `editor/CrystalEditor.java` — `delete()` becomes `Disposer.dispose(this)`; the manual `graphs` loop
  and the never-pruned `graphs` list both go away.
- `ui/elements/dock/DockArea.java` — `closePanelDiscarding` disposes the closed panel's content;
  `pruneStaleGroups` disposes the groups it drops. **This is the change that makes the step matter**,
  and it is the first time anything in the dock has told a panel it is gone.

**Deliberately not modified yet:** the other ~35 `delete()` methods. They keep working. Migrating them
is opportunistic, one per subsequent step, so this step stays small and reviewable.

### 14.4a What moved to step 3, and why

The spec above named `DockArea.closePanelDiscarding` disposing the closed panel's content as "the
change that makes the step matter". **It cannot be done here, and finding out why is worth recording.**

Panel content is not owned by the dock. `DockGroup.contentFor` caches whatever the registry factory
returns, and those factories hand back **shared instances**: `ref -> fileTree`, `ref -> problems`,
`ref -> inspectorHost`, and for documents an editor cached in `OpenDocuments`. Disposing on close would
destroy the file tree the first time somebody closes the Project panel.

Worse, closing a tab does not even reach the document: `Workbench`'s `close(path)` — the one call that
drops an entry from `OpenDocuments` — is invoked when a file is **deleted or moved**, never by
`DockArea.closePanel`. So a closed tab keeps its document, and its GL pool, for the session.

The fix needs two halves and only one of them is this step:

1. the dock announcing a close, so the workbench can drop the document — **step 3**, which is where the
   dock gains events;
2. dropping a document releasing it — **done here**, in `OpenDocuments.close`.

Half 2 is already correct for the paths that reach it today, and becomes the whole fix the moment
half 1 lands. Forcing half 1 into this step would have meant the dock deciding ownership it does not
have, which is the mistake this plan exists to stop making.

### 14.4b What was cut as speculative

The spec listed registering `CgUiPaintContext`'s renderers and layer FBO pool as children. **Cut.**
Its `destroy()` already frees exactly those, is called from `onDestroy`, and nothing registers against
it — so the change would add a tree with one node and no members. §13.3's own rule applies: if a step
cannot name the concrete pain it removes, cut it. It becomes worthwhile the moment something else
wants to be owned by the paint context, and not before.

### 14.5 Tests (contract)

1. Children dispose before the parent, in **reverse registration order** — assert the exact sequence.
2. Double dispose is a no-op; `isDisposed` flips exactly once.
3. A throwing child does not prevent its siblings or the parent from disposing.
4. Registering against a disposed parent returns false from `tryRegister` and disposes the child.
5. Disposing a child unlinks it — the parent's later disposal does not touch it again.
6. A `Disposable.Gl` disposed off-thread is **queued, not run**; `drainGlQueue` runs it.
7. `liveCount()` returns to its baseline after a root is disposed — the leak assertion.
8. Deep tree (1000 nodes) disposes without recursion depth failure.
9. **Closing a dock panel disposes its content**, and a panel that is merely hidden by a tab switch
   does not — the distinction the whole step exists for.
10. Opening and closing the same panel type 50 times leaves `liveCount()` flat. This is the
    session-growth assertion, and it fails today.

### 14.6 Traps

- **Do not make `UIElement implements Disposable` in this step.** It is the obvious move and it would
  touch everything; element lifetime is tied to tree attachment, which is a separate question. Revisit
  after step 5.
- `CgGraphicsLifecycle.destroyContext()` has a **documented teardown order** (VAOs before VBOs, etc).
  The disposal tree must not reorder it — register CrystalGUI's root as one listener, keep the
  existing order inside CrystalGraphics.
- `CgUiPaintContext` is a **static singleton** that outlives contexts; its `dispose()` must leave it
  reconstructible, exactly as `destroy()` does today.

### 14.7 Done when

`CrystalEditor.delete()` is one line, no GL resource is freed by a hand-written loop, and
`DisposerTest` passes including the leak assertion.

---

## 15. Step 2 — `DataContext` and context keys — **DONE**

> **Shipped.** `core/data/{DataKey,DataProvider,DataContext}.java`, `ui/UiDataKeys.java`, 13 contract
> tests in `headlessTest`. `UIElement` is now a `DataProvider`; `GraphView`, `ShaderGraphEditor` and
> `UndoScope` answer keys; `GraphCommands.graphFor` and `ShaderGraphEditor.editorFor` are one line each
> over the shared walk. The `when` parser stays deferred (§15.6).


**Why second:** it retires three hand-rolled parent walks, makes commands conditional, and is the
precondition for menu contributions. Self-contained and high leverage.

### 15.1 What the references do

**IntelliJ — pull.** `DataKey<T>` is a typed name. A component implements `DataProvider`
(`Object getData(String dataId)`). `DataContext.getData(key)` walks **outward from the focused
component**, first non-null wins. Standard keys: `CommonDataKeys.VIRTUAL_FILE`, `PROJECT`, `EDITOR`;
`PlatformDataKeys.FILE_EDITOR`, `SELECTED_ITEMS`, `CONTEXT_COMPONENT`. An action's `update()` reads
the context and sets `Presentation.enabled`.

**VS Code — push.** `IContextKeyService.createKey(name, default)` returns an `IContextKey` a component
`set()`s. `when` clauses are expressions (`editorFocus && resourceExtname == .java`) parsed by
`ContextKeyExpr` and evaluated against the current key set. Services are **scoped per DOM node**, so a
key set inside a widget only applies within it.

**Both halves are needed and they answer different questions.** Pull gives a command its *subject*;
push gives the UI its *conditions*. We build pull first, and expose conditions as predicates over the
same context — deferring the expression parser until keymaps need it (§15.6).

### 15.2 API

```java
package com.crystalgui.core.data;

public final class DataKey<T> {
    public static <T> DataKey<T> create(String name, Class<T> type);
    public String name();
    public Class<T> type();
}

/** Implemented by any UIElement that can answer questions about itself or its subject. */
public interface DataProvider {
    @Nullable Object getData(DataKey<?> key);
}

public interface DataContext {
    @Nullable <T> T get(DataKey<T> key);
    default <T> boolean has(DataKey<T> key) { return get(key) != null; }
    default <T> T require(DataKey<T> key) { … }   // throws with the key name

    /** Walks outward from an element, first non-null wins. Caches for the duration of one lookup pass. */
    static DataContext from(@Nullable UIElement source);
}
```

Standard keys — `com.crystalgui.ui.UiDataKeys`:

| Key | Type | Supplied by |
|---|---|---|
| `ELEMENT` | `UIElement` | the walk itself |
| `RESOURCE` | `Resource` (step 4; `CgPath` until then) | a document panel, a tree row |
| `DOCUMENT` | `FileDocument` | a document panel |
| `DOCK_INPUT` | `DockInput` | `DockGroup` |
| `DOCK_GROUP` | `DockGroup` | `DockGroup` |
| `SELECTION` | `List<?>` | tree, graph, editor |
| `UNDO_SCOPE` | `UndoStack` | anything with a stack |
| `GRAPH_VIEW` | `GraphView` | `GraphView` |

### 15.3 Files

**New**
- `core/.../core/data/DataKey.java`, `DataProvider.java`, `DataContext.java`
- `core/.../ui/UiDataKeys.java`
- tests: `DataContextTest.java`

**Modified**
- `core/command/CommandContext.java` — gains `DataContext data()`, derived from `source()`.
- `ui/elements/graph/GraphCommands.java` — `graphFor(context)` becomes `context.data().get(GRAPH_VIEW)`.
- `graph/shader/ShaderGraphEditor.java` — `editorFor(context)` likewise; implements `DataProvider`.
- `core/undo/UndoScope.java` — `nearest()` becomes a `DataProvider` contribution.
- `ProjectFileTree`, `GraphView`, `TextEditor`, `DockGroup` — implement `DataProvider`.
- `Workbench.activeFilePath()` — reimplemented over `DataContext` from the focused element, with the
  active-group fallback preserved.

### 15.4 Tests (contract)

1. The walk stops at the **first** provider that answers, even if an outer one also would.
2. A detached element yields an empty context rather than throwing.
3. `require` throws naming the key.
4. Two providers for the same key at different depths: inner wins.
5. `GraphCommands` behaviour is unchanged — the existing suite is the regression net; assert
   specifically that a command invoked from a *nested* widget still finds the graph.
6. A command's `enabledWhen` reading the context greys correctly with no focus at all.

### 15.5 Traps

- **The walk must use the same "nearest" semantics `UndoScope` already has**, or Ctrl+Z and a command
  will disagree about which scope they are in. Port `UndoScope.nearest` onto this and delete the
  original rather than leaving two.
- Click-focus targets the **exact element hit** here (documented invariant), so the source element may
  be an internal part. The walk must therefore traverse *internal* parents too, not just public ones.
- Do not cache a `DataContext` across frames. Cache within one lookup pass only; the tree moves.

### 15.6 Explicitly deferred

The `when`-expression **parser** (`editorFocus && resourceExtname == .java`). Predicates over
`DataContext` cover commands today. The parser is needed only when keymaps want conditions, and it is
~1000 lines in VS Code, most of it parsing.

---

---

## 15b. Step 2.5 — Commands: global registration, context-resolved — **DONE**

**Why here:** it is the completion of step 2, not a new idea. `DataContext` gave a widget the ability to
*supply* a subject; this removes its obligation to *install* the commands that consume one. Doing it
before step 3 matters because every later step registers commands — doing it after means writing them
twice.

### 15b.1 What is wrong today, measured

| Finding | Evidence |
|---|---|
| **`CommandRegistry` is per-`UIWindow`** | `UIWindow:57 private final CommandRegistry commands` |
| **Commands are installed from a frame ticker** | `ShaderGraphEditor.attachPreviews` calls `installCommands()` every frame until previews attach — so a graph's commands **do not exist until a frame after it attaches**, and the palette opened before that is missing them |
| **Three incompatible install shapes** | static `install(registry)`, static `install(window, owner)`, instance `installCommands()` + a `commandsInstalled` guard |
| **25 install-shaped call sites** | across 6 command classes and 2 widget-owned installers |
| **A command bound to a specific element** | `UndoCommands.install(registry, fileTree)` — even though `UndoScope` already resolves the right stack from focus. Step 2 made that wiring redundant |

The single sentence: **commands are global, context is local — and we made both local.**

### 15b.2 What the references do

**VS Code.** One declaration site carries id, title, precondition, keybinding *and* menu placement,
registered at module load:

```ts
registerAction2(class extends Action2 {
  constructor() { super({
    id: 'editor.action.deleteLines',
    precondition: EditorContextKeys.writable,
    keybinding: { primary: KeyMod.CtrlCmd | KeyCode.KeyK, weight: KeybindingWeight.EditorContrib },
    menu: [{ id: MenuId.EditorContext, group: '1_modification', order: 2 }]
  }); }
  run(accessor: ServicesAccessor, ...args) { … }
});
```

`CommandsRegistry` is a **singleton**; `IContextKeyService` is what is scoped. `MenuRegistry` holds
menu contributions keyed by `MenuId`.

**IntelliJ.** `AnAction` registered once (via `plugin.xml` or `ActionManager.registerAction`).
`update(AnActionEvent)` reads `e.getDataContext()` and sets `Presentation.enabled`;
`actionPerformed(e)` reads the same context. Placement is `<add-to-group group-id="EditorPopupMenu">`.

**Neither has a widget that installs commands.** A widget answers data keys; that is its whole
contribution.

### 15b.3 API

```java
package com.crystalgui.core.command;

public final class Action {
    public static Builder of(String id, String title);

    public static final class Builder {
        public Builder enabledWhen(Predicate<DataContext> precondition);
        public Builder run(Consumer<DataContext> body);
        public Builder binding(String... keySpecs);          // declared WITH the action
        public Builder menu(MenuId menu, String group, int order);
        public Action build();
    }

    public String id();
    public String title();
    public boolean isEnabled(DataContext context);
    public boolean run(DataContext context);                 // false when disabled
    public List<String> bindings();
    public List<MenuPlacement> menus();
}

/** The one registry. Static, populated at class init, never per-window. */
public final class ActionRegistry {
    public static void register(Action action);
    @Nullable public static Action get(String id);
    public static Collection<Action> all();
    public static boolean run(String id, DataContext context);
    /** Every action placed in {@code menu}, in group then order, filtered by enablement. */
    public static List<Action> menu(MenuId menu, DataContext context);
}
```

`MenuId` is an interned name (`GRAPH_CONTEXT`, `EXPLORER_CONTEXT`, `EDITOR_TAB_CONTEXT`, `PALETTE`),
mirroring VS Code's. Placement is `(group, order)` so unrelated contributors interleave predictably —
`navigation@1` in VS Code's spelling.

### 15b.4 Migration strategy — adapters, then deletion

`CommandRegistry` stays and becomes a **view over `ActionRegistry` plus this window's context**:

```java
// UIWindow.getCommands() keeps working
public boolean run(String id) { return ActionRegistry.run(id, DataContext.from(focused())); }
```

So every existing caller, keymap binding and palette entry keeps working while actions migrate one
class at a time. The per-window registry is deleted when its last direct registration is gone.

**Order:** `UndoCommands` first (it is the clearest win and the smallest), then `GraphCommands`,
`ChromeCommands`, `DockCommands`, `ExplorerCommands`, `EditorCommands`, `CrystalEditorCommands`, then
the two widget-owned installers (`ShaderGraphEditor`, `BlackboardPanel`) — which is where
`installCommands()`, `commandsInstalled` and the ticker call all disappear.

### 15b.5 What this affects beyond commands

| Framework | Today | After |
|---|---|---|
| **Keymap** | `keymap().bind(spec, id)` called from inside widgets, after install | declared with the action; the keymap reads the registry and stays the place a *user* remaps |
| **Menus** | `ExplorerCommands::menu` builds a list by hand | `MenuId` + group/order. **This is plan §11's Tier-0 item 0.7**, and half its value is here |
| **ContextMenu** | `setContextMenu(registry, builder)` per widget | one call, filtered by context |
| **Command palette** | enumerates one window's registry | enumerates globally; enablement per context |
| **`UndoCommands`** | installed against `fileTree` specifically | global, resolved through `UNDO_STACK`. The wiring step disappears |
| **`CommandContext`** | `source` + `args` | a thin wrapper over `DataContext`; `args` survives as binding payload |

### 15b.6 Tests (contract)

1. An action is registered **at class init** — available before any window exists, and before any
   widget has been built. This is the one that fails today.
2. Registering the same id twice is refused, naming the id.
3. `isEnabled` is evaluated against the *passed* context, so one action reports differently for two
   different focus positions.
4. `run` on a disabled action does nothing and returns false.
5. `menu(id, context)` returns contributions in group-then-order, and **omits disabled ones**.
6. Two actions in the same group from unrelated registrations interleave by order, not by
   registration sequence.
7. A binding declared with an action is discoverable from the keymap without the widget existing.
8. **Acceptance:** a graph command runs correctly when invoked from a nested widget inside the graph,
   with no `installCommands` anywhere in the call path.

### 15b.7 Deliberately not in this step

- The **`when`-expression parser**. `Predicate<DataContext>` covers preconditions; the string form is
  ~1000 lines in VS Code and is only needed when *users* write conditions in a keymap file.
- **Menu rendering.** This step gives menus a source of truth; making `ContextMenu` read it is a
  follow-on, and the two widgets that build menus by hand keep working until then.
- **Action groups / submenus.** `MenuId` + group covers everything we currently draw.

---

## 16. Step 3 — Typed service events; delete the polling loops

**Why third:** it immediately reduces per-frame work, and every later step is easier to reason about when
state changes announce themselves. Steps 4–6 all add services; adding them *after* the event convention
exists means they are born with it rather than retrofitted.

**Prerequisite met by step 2.5:** `UIElement.onWindowChanged` now exists, so "do this once the element has
a window" is no longer a reason to own a ticker. Two of the loops below were exactly that and are already
gone; what remains is the harder half — loops that poll for *change*.

---

### 16.1 The decision, restated

**Service-owned typed signals, not a global topic bus** (§13.2).

| | VS Code | IntelliJ | Ours |
|---|---|---|---|
| Shape | `Event<T>` + `Emitter<T>` on the service | `MessageBus` + `Topic<L>` | `Signal.Value<T>` field on the service |
| Subscribe | `svc.onDidChangeX(fn, this, disposables)` | `bus.connect(disposable).subscribe(TOPIC, l)` | `svc.onDidChangeX.connect(fn)` |
| Lifetime | returns `IDisposable` | connection registered on a `Disposable` | `Connection` → **must become** `Disposable` (16.4) |
| Discovery | the field is on the service | a `Topic` constant, anywhere | the field is on the service |

**Why not IntelliJ's bus.** A `Topic` is a global constant that any code may publish to, so "who fires
this" is a repo-wide search and "what does this service announce" has no answer at all. It buys
broadcast-direction semantics (`PARENT→CHILD`) we have no use for: we have no project/module hierarchy
of buses. A field on the service is discoverable by autocomplete and impossible to publish to from
outside, which is the property that keeps the announcement honest.

**Why not one `EventBus` of our own.** Same objection, plus string or class keys and a cast at every
listener. `Signal` is already typed, already tested, already used (`onStatus`, `onDocumentLoaded`,
`onItemActivated`). This step is mostly *applying* it, not building it.

**Naming: VS Code's, adopted wholesale.** `onDidChangeActivePanel` — past tense, "did", the change has
already happened. `onWill…` is reserved for a pre-change signal a listener may veto; we have none yet and
should not invent one speculatively. Existing names get corrected in the same commit: `onDocumentLoaded`
→ `onDidOpenDocument`, `onStatus` stays (it is a message, not a change).

---

### 16.2 The real inventory

The stub listed four loops. The audit finds **eight** things happening per frame, and three of them
should not be touched. Line references are to the state at commit `fcdda57`.

#### `Workbench.tick(delta)` — runs every frame while attached

| # | What | Cost per frame | Verdict |
|---|---|---|---|
| 1 | `activityBar.sync(commands)` | walks every registered descriptor, map lookup each | **replace** — `panels().onDidRegister` |
| 2 | `fileTree.source().indexStep(BUDGET)` | bounded background crawl | **keep** (16.3) |
| 3 | `refreshDirtyMarkers()` | builds a `List<CgPath>` from every open document, then `equals` | **replace** — `onDidChangeDirty` |
| 4 | `revealActiveFile()` | derives `activeFilePath()` from the dock | **replace** — `onDidChangeActivePanel` |
| 5 | `fileTree.loadProjects()` | latched by `projectsRequested`, ~free | **move** to `onWindowChanged` |
| 6 | `activeEditor()` + `problems.bindTo` | derives the active editor from the dock | **replace** — `onDidChangeActivePanel` |

#### `CrystalEditor`'s ticker — registered from `onLayoutChanged`, latched by `ticking`

| # | What | Verdict |
|---|---|---|
| 7 | `followActiveGraph()` → `activeGraph()` + `show(followed)` | **replace** — `onDidChangeActivePanel` |
| 8 | `session.tick()` (restore retry) | **replace** — `WorkspaceTreeSource.onDidLoadListing` |

Once 7 and 8 are gone this ticker disappears entirely, and with it the `onLayoutChanged` override and
the `ticking` latch that exists only to register it.

#### `ActivityBar.ItemButton`

Each button polls `workbench.isPanelOpen(typeId)` against a `lastKnownOpen` field to drive its
checked state. **Replace** — `onDidOpenPanel` / `onDidClosePanel`. The class javadoc already records why
this matters: *"the work per refresh was building a drawable, which is not free, and the change had an
owner who knew when."* That comment is a note-to-self that this step exists.

> **Three of the six `Workbench.tick` entries derive their answer from the dock** (4, 6, and — in the
> editor — 7). That is not three problems; it is one missing signal used three times. `onDidChangeActivePanel`
> is the highest-value single addition in this step.

---

### 16.3 What must **not** become an event

The distinction the migration has to keep straight, because the two look identical from inside `tick`:

- **Polling for change** — recomputing a value every frame to notice it moved. Every entry above except
  one. This is what step 3 deletes.
- **Incremental work** — doing a bounded slice of a long job each frame. `indexStep` walks a few
  directories per frame until the workspace is mapped. There is no "change" to announce; the frame *is*
  the schedule. **Keep it, and keep the ticker it runs in.**

`Workbench.tick` therefore survives step 3 with one line in it. Deleting the ticker outright is the
mistake this paragraph exists to prevent — the crawl's own comment already warns that latching it broke
the index once.

---

### 16.4 Infrastructure `Signal` needs first

Three gaps, found by reading `SignalBase` rather than by assuming. All are small, and all are load-bearing
for a step whose whole point is that events chain.

#### (a) Re-entrant emit corrupts the iteration — **must fix before migrating**

`SignalBase.emitting` is a **boolean**, and `endEmit()` unconditionally clears it and flushes
`pendingDisconnect`:

```java
protected final void beginEmit() { emitting = true; }
protected final void endEmit() {
    emitting = false;
    if (!pendingDisconnect.isEmpty()) { /* slots.remove(...) */ }
}
```

Every `emit` caches `int n = slots.size()` and indexes to `n`. So a listener that emits a *second* signal
whose listener disconnects will have the inner `endEmit()` shrink `slots` while the outer loop still holds
the old `n` → **`IndexOutOfBoundsException`**, in a listener, at a depth nobody was looking at.

This does not fire today because signals are used as leaves. Step 3 makes them chain by design —
`onDidChangeDirty` → tab title refresh → `onDidChangeLayout` is the intended shape. Fix:
`emitting` becomes an `int depth`, `endEmit()` flushes only at zero.

> Connect-*during*-emit is already safe and should be documented rather than changed: `n` is cached before
> the loop, so a listener added during an emission simply does not receive that emission. That is VS Code's
> behaviour too, and the alternative (a listener receiving the event that caused it to subscribe) is worse.

#### (b) `Connection` is not `Disposable` — the step-1 payoff is unclaimed

`Disposer` takes `Disposable`; `Signal.connect` returns `Connection`. Nothing bridges them, so every
subscription in this step would be manually disconnected — which is exactly the bookkeeping step 1
exists to remove, and exactly what leaks when a panel closes.

```java
public interface Connection extends Disposable {
    void disconnect();
    @Override default void dispose() { disconnect(); }
}
```

One line, and `Disposer.register(this, signal.connect(...))` then works everywhere. `ConnectionGroup`
becomes `Disposable` the same way.

> Check the module direction before writing it: `core.signal` gaining a dependency on `core.dispose` is
> fine (both are `core`, neither touches GL), but the reverse would not be.

#### (c) `Signal.Value` does **not** suppress equal values

The stub's trap list says it does, "(`Property.set` semantics)". It does not — `Property` suppresses;
`Signal.Value.emit` forwards unconditionally. **The trap is real but points the other way:** every emitter
in this step must decide for itself whether to fire on a no-op change, because nothing will do it for
them. `refreshDirtyMarkers` already does this by hand (`if (!dirty.equals(lastDirty))`) and that guard has
to survive into the emitter rather than being dropped as "the signal handles it".

---

### 16.5 The deferral rule — events are immediate, rebuilds are not

**The single most likely way to break this step.** Replacing a poll with an event changes *when* the
work runs: from "next frame" to "inside whatever call mutated the state". The engine has a hard rule
against exactly that:

> *A widget must never rebuild the elements it is being clicked or dragged on — update them in place.*
> The table header froze this way: sort once and no header could be clicked or resized again.

`DockArea` already obeys it (`rebuildPending` + tick), and `ProjectFileTree` already routes decoration
changes through `pendingRefresh` *"because a provider may fire from inside a click handler on a row"*.

So the shape is **not** `event → rebuild`. It is:

```
event  →  set a dirty flag  →  next frame's tick rebuilds once
```

That is still a complete win over polling — the flag is set O(changes) instead of the value being
recomputed O(frames) — and it is what both references do (VS Code's `Emitter` + microtask-scheduled
view updates, IntelliJ's `EditorNotifications.updateAllNotifications` posting to the EDT).

**In-place updates may be immediate.** Setting a tab's title text or a button's checked class touches no
tree structure and is safe from inside a listener. Only *structural* changes defer.

---

### 16.6 API additions

```java
// DockArea — the highest-value additions; three current polls collapse into the first
public final Signal.Value<DockPanelRef> onDidChangeActivePanel;   // null when nothing is active
public final Signal.Value<DockPanelRef> onDidOpenPanel;
public final Signal.Value<DockPanelRef> onDidClosePanel;
public final Signal.Value<DockGroup>    onDidChangeActiveGroup;
public final Signal.Action              onDidChangeLayout;        // STRUCTURAL only — see traps

// DockPanelRegistry
public final Signal.Value<DockPanelDescriptor> onDidRegister;

// Workbench
public final Signal.Value<CgPath> onDidChangeDirty;               // one path, not the whole set
public final Signal.Value<CgPath> onDidOpenDocument;              // renamed from onDocumentLoaded
public final Signal.Value<CgPath> onDidCloseDocument;

// WorkspaceTreeSource
public final Signal.Value<CgPath> onDidLoadListing;

// FileDocument (16.7)
Signal.Action onDidChange();
```

**`onDidChangeDirty` carries one path, not the set.** The set is what the poll computed; a path is what
the change *is*. A listener that wants the set asks `unsavedFiles()`, which it can now do once per change
instead of once per frame.

---

### 16.7 The hard one — dirty

A document goes dirty by being typed into, and `FileDocument` has no change signal.

- **A.** `FileDocument` gains `Signal.Action onDidChange()`. `TextFileDocument` forwards its editor's
  change event; `ShaderGraphEditor` forwards graph edits.
- **B.** Keep polling `unsavedFiles()`, but guard it with a modification counter so the walk only runs
  when *something* edited anything.

**A, and B is not as illegitimate as the stub implied.** IntelliJ really does use
`ModificationTracker`/`SimpleModificationTracker` with `CachedValue` — but for *derived caches*, where the
consumer is already asking and the counter only decides whether the cached answer is stale. It is not how
IntelliJ notices a document became dirty; that is `DocumentListener`. Our case is the second one: nobody
is asking, the frame is asking on their behalf, and that is the poll.

The tell is that each document **already knows** — `isDirty()` is answered from its own state, and
`encode()` is called on it. A is one signal per implementation; B is a counter, a cache field, and a
staleness rule, to preserve a loop.

---

### 16.8 Migration order

Deliberately not "all signals, then all consumers" — each row lands and is green on its own.

1. **`Signal` fixes** (16.4 a/b/c). No behaviour change; the depth-counter fix gets its own test.
2. **`DockArea.onDidChangeActivePanel`** + the three consumers (`revealActiveFile`, `problems.bindTo`,
   `followActiveGraph`). Biggest single reduction; also removes `CrystalEditor`'s ticker's first half.
3. **`WorkspaceTreeSource.onDidLoadListing`** → `WorkbenchSession.tick` retry. `CrystalEditor`'s ticker
   is now empty and the `onLayoutChanged` override goes with it.
4. **`DockPanelRegistry.onDidRegister`** → `ActivityBar.sync` becomes `add one button`.
5. **`onDidOpenPanel`/`onDidClosePanel`** → `ItemButton`'s `lastKnownOpen` poll.
6. **`FileDocument.onDidChange`** → `onDidChangeDirty` → `refreshDirtyMarkers`. Last because it touches
   every document implementation.
7. ~~**`loadProjects()`** moves to `onWindowChanged`.~~ **Attempted and reverted — it belongs to step 4.**
   It looks like a one-shot dressed as a loop, since `ProjectFileTree` latches it on `projectsRequested`.
   It is really a *retry*: a client's window id is not valid until its session has opened, and the server
   discards a packet addressed to another window with no error at all — `WorkspaceTreeSource.loadProjects`
   says exactly that. Attach happens earlier, and because the latch is set on the **attempt** rather than
   on success, one early call poisons it permanently. Twelve explorer tests came up with no project roots.
   Needs a session-opened announcement, which is step 4's territory.

---

### 16.9 Tests (contract)

1. **The event fires**, with the right payload — never "a later frame shows the new value". Asserting the
   frame is asserting the poll we are deleting.
2. **Exactly once per change.** The dock rebuilds on many operations; a signal that fires per rebuild is
   the loop again, wearing a callback.
3. **Nothing fires on a settled frame.** The direct inverse of the loops being deleted, and the assertion
   that would have caught them.
4. **A subscription registered on a `Disposable` stops firing after disposal** — 16.4(b)'s contract.
5. **Re-entrant emit** — a listener that emits another signal whose listener disconnects. Fails today
   with `IndexOutOfBoundsException`; this is the regression test for 16.4(a).
6. **Ticker count.** `CrystalEditor` registers no ticker at all; `Workbench` registers one. Assert the
   number, because "the poll came back" is otherwise invisible.
7. **A structural rebuild triggered from a listener does not detach the element under the pointer** —
   16.5. Drive it through a real press on a dock tab.

---

### 16.10 Traps

- **`Signal.Value` does not suppress equal values.** Every emitter decides for itself. See 16.4(c) — the
  earlier draft of this section had this backwards.
- **`onDidChangeLayout` must fire on *structural* change only.** The dock rebuilds for reasons that are
  not layout changes (a presentation refresh, a tab title). Fire it from the same place `requestRebuild`
  decides something moved, not from the rebuild itself, or it is a per-frame callback.
- **`onDidChangeActivePanel` legitimately emits `null`** — focusing chrome means no panel is active. The
  `followed` latch in `CrystalEditor` exists precisely because of this and must be **kept**: the gesture
  that reopens the Inspector is the one that reports no active graph. IntelliJ answers this the same way
  (`selectionChanged` fires on editor selection, so clicking a tool window never clears it). Moving from a
  poll to an event does not change this; it makes it easier to get wrong, because the latch now looks
  redundant.
- **Do not delete `Workbench.tick`** — the crawl lives there (16.3).
- **Renaming `onDocumentLoaded`** touches `WorkbenchSession` and any harness scene; grep before renaming.
- **Emitting during teardown.** `close(path)` disposes the document *and* should emit
  `onDidCloseDocument`. Emit **before** disposing, or listeners receive a path whose document is already
  dead — and one of them will ask it something.

---

### 16.11 What this step does not do

- **No `Event.debounce`/`filter`/`any` combinators.** VS Code has them; we would be writing them for one
  or two uses. Revisit when a third appears.
- **No listener-leak detection.** VS Code warns past N listeners on one emitter. Worth having eventually;
  `connectionCount()` already exposes what it would read.
- **No cross-window events.** Every signal here is owned by an element or a service inside one window.
- **No `onWill…` (vetoable) signals.** Nothing needs one yet; the shutdown-veto case is step 6's, if ever.

---

## 17. Step 4 — `Resource`: schemes, virtual documents, content providers

**Why fourth:** it is what makes "derived document" a framework concept. It unblocks the generated
shader properly and kills `CrystalEditor.graphPaths`.

### 17.1 What the references do

**VS Code.** `URI` with `scheme`, `authority`, `path`, `query`, `fragment`.
`IFileService.registerProvider(scheme, IFileSystemProvider)` — `file:`, `untitled:`, `git:`,
`output:`, `vscode-userdata:`. A `ITextModelContentProvider` supplies content for read-only schemes.
`IUriIdentityService` decides when two URIs are the same resource (case sensitivity, normalisation).

**IntelliJ.** `VirtualFile` + `VirtualFileSystem`; `NonPhysicalFileSystem` for things with no disk
presence; **`LightVirtualFile`** carries its content in memory. The decompiled-class view, "Show Kotlin
Bytecode" and diff panes are all this. `VirtualFileManager.findFileByUrl(url)` with `protocol://path`.

### 17.2 The decision from §13.2 — wrap `CgPath`, do not change it

`CgPath`'s javadoc: *"written into saved documents, so `toString()`/`parse()` must round trip exactly
and forever."* Its grammar is `project:path`. Therefore:

- The **project scheme keeps its exact current text form**, `mymod.proj:src/Main.java`.
- Every other scheme uses `scheme://path`, which is unambiguous because a project id cannot contain
  `/` (it is the path separator) and the `//` marker cannot appear in the current form.
- `Resource.parse` checks for `://` first; if absent, it is a `CgPath`.

This preserves every saved document and every saved session byte-for-byte.

### 17.3 API

```java
package com.crystalgui.fs;

public final class Resource {
    public static final String SCHEME_PROJECT = "project";

    public static Resource of(CgPath path);                        // project scheme
    public static Resource of(String scheme, String path);
    public static Resource derived(String scheme, Resource origin); // generated FROM something
    public static Resource parse(String text);

    public String scheme();
    public String path();
    public String name();
    public String extension();
    public boolean isProject();
    @Nullable public CgPath asPath();       // non-null iff isProject()
    @Nullable public Resource origin();     // non-null for derived resources
    public String toString();               // round-trips parse()
}

public interface ResourceContentProvider {
    byte[] read(Resource resource);
    default boolean isReadOnly(Resource resource) { return true; }
    /** Fires when the content behind a resource changed, so open views can refresh. */
    default void onDidChange(Consumer<Resource> listener) { }
}

public final class ResourceRegistry {
    public static void register(String scheme, ResourceContentProvider provider);
    @Nullable public static ResourceContentProvider providerFor(String scheme);
    public static boolean isReadOnly(Resource resource);
}
```

### 17.4 The generated shader, done properly

```java
// registered by the shader graph package, not by the application
ResourceRegistry.register("shadergraph-generated", resource -> {
    ShaderGraphEditor graph = documents.openFor(resource.origin());   // via FileDocument.path()
    return graph.emittedSource().getBytes(UTF_8);
});
```

The tab's input is `Resource.derived("shadergraph-generated", Resource.of(graphPath))`. Its title comes
from a `ILabelService`-style rule (`fire_compiled.shader`); it is read-only because the provider says
so; it restores from a session because it is just a string.

`CrystalEditor.graphPaths`, `pathOf`, `graphForPath` and `compiledTitleFor` all disappear.

### 17.5 Also in this step — `FileDocument.path()`

```java
public interface FileDocument {
    Resource resource();   // was: nothing. IntelliJ FileEditor.getFile(), VS Code EditorInput.resource
    …
}
```

This is what lets the content provider above find its graph without an application-side map, and it is
listed as its own step in §7 of Part I; it belongs here.

### 17.6 Files

**New:** `fs/Resource.java`, `fs/ResourceContentProvider.java`, `fs/ResourceRegistry.java`,
tests `ResourceTest.java`, `ResourceRegistryTest.java`.

**Modified:** `FileDocument` (+`resource()`), `TextFileDocument`, `ShaderGraphEditor`,
`Workbench`, `CrystalEditor` (deletions above).

> **`OpenDocuments` was deliberately NOT re-keyed by `Resource`.** It is the *disk* store — `onDisk`
> bytes, `unreadable`, `requested`, `markSaved` — and a derived resource has no disk presence at all, so
> re-keying it would give every entry fields half of them can never use. That is the flag-shaped design
> the scheme system exists to replace. A derived document needs a provider and a view, not a slot in the
> file store.
>
> The session codec was **not** version-bumped either, against §17.8. The generated tab's state used to be
> the graph's bare path, which parses as a project resource with no origin; reading it as the origin
> itself is one line, and the two forms are unambiguous — a derived resource always has an origin, a bare
> path never does. A bump would have invalidated every saved layout that had the tab open, to avoid
> writing that line.

### 17.7 Tests (contract)

1. **`Resource.of(cgPath).toString()` equals `cgPath.toString()` exactly** — the compatibility
   guarantee, asserted directly.
2. `parse` round-trips every scheme, including derived ones with an origin.
3. A project path containing a colon in the *path* segment still parses as a project resource.
4. `derived(...).origin()` returns the original, and survives `parse(toString())`.
5. An unregistered scheme yields a null provider rather than throwing.
6. A read-only resource reports so through the registry.
7. **Every existing saved session and document in the repo still parses.** Load
   `session.harness.scratch.json` and every `.shadergraph` under test resources as a regression net.

### 17.8 Traps

- `CgPath` **validates and confines** (`..` is resolved, escaping is refused). `Resource` must not
  weaken that for the project scheme — delegate, never reimplement.
- `OpenDocuments` re-keying from `CgPath` to `Resource` touches the session codec. Bump the version;
  do not attempt a silent migration.
- A derived resource's origin may be **closed or deleted**. `read` must have a defined answer — return
  empty and let the pane show a banner (§11 Tier 2, `EditorNotificationProvider`), never throw into a
  paint path.

---

## 18. Step 5 — `DockPane`: retargetable views with lifecycle and view state

**Why fifth:** kills `assertOnlyChild` and every future copy of it. This is the single most valuable
step for the symptoms in §0.

### 18.1 What the references do

**VS Code `EditorPane`.** Lifecycle: `createEditor()` → `setInput(input, options, context, token)` →
`setEditorVisible(bool)` → `layout(dim)` → `clearInput()` → `dispose()`.
`AbstractEditorWithViewState` adds `getViewState()` / `saveState()` keyed by resource.
**One pane instance per (group, editor type)** — switching between two tabs of the same type calls
`setInput` on the *same* pane. That is why tab switching is fast, and it is the mechanism we lack.

**IntelliJ `FileEditor`.** `getComponent()`, `getFile()`, `getState(level)`/`setState(state)`,
**`selectNotify()` / `deselectNotify()`** (became / stopped being the visible tab), `isModified()`,
`isValid()`, `dispose()`. `FileEditorProvider.accept(project, file)` decides, with a `FileEditorPolicy`
when several providers claim one file.

### 18.2 API

```java
package com.crystalgui.ui.elements.dock;

public interface DockPane extends Disposable {
    UIElement view();

    /** Point this pane at an input. Called on first show and on every retarget. */
    void setInput(DockInput input);

    /** The pane is no longer showing anything; release per-input state, keep the pane. */
    default void clearInput() { }

    /** Became / stopped being the visible tab. IntelliJ's selectNotify/deselectNotify. */
    default void onVisible() { }
    default void onHidden() { }

    /** Per-input view state — caret, scroll, folds. Keyed by input by the framework, not by the pane. */
    default void writeViewState(StateMap<?> out) { }
    default void readViewState(StateMap<?> in) { }

    @Override default void dispose() { }
}

public interface DockPaneProvider {
    boolean accepts(DockInput input);
    DockPane create();
    /** Higher wins when several providers accept. IntelliJ's FileEditorPolicy. */
    default int priority() { return 0; }
}
```

### 18.3 `DockInput`

Introduced here rather than earlier because this is its first real consumer. It **wraps**
`DockPanelRef` (§9 decision 1) so the session codec is untouched in this step.

```java
public final class DockInput {
    public static DockInput of(DockPanelRef ref);
    public static DockInput of(String typeId, Resource resource);
    public String typeId();
    @Nullable public Resource resource();
    public DockPanelRef ref();               // the persisted form
    public boolean matches(DockInput other);
    public EnumSet<DockCapability> capabilities();
}

public enum DockCapability { SINGLETON, READONLY, UNTITLED, DERIVED, CAN_SPLIT }
```

### 18.4 The retargeting rule — the important design decision

> **Implementation note — the hazard, and the shape that avoids it.** A pane is one instance per
> **type** while `DockGroup.content` is keyed per **panel**, so returning the pane's view from
> `contentFor` hands the same element to two tabs and `rebuildStrip` parents it twice: the *"cannot add
> the same child twice"* bug this package has paid for before.
>
> **Every pane-backed panel therefore keeps its own stable, empty host**, and only the host of the
> *active* panel holds the view. `rebuildStrip` is untouched, nothing is shared, and moving the view is
> one `setOnlyChild` — which re-parents correctly, including out of an internal parent.
>
> This was held back once on the grounds that `sync()` runs during a tab click and a widget must not
> re-parent what it is being clicked on. Re-read against this shape, the rule does not bite: the click
> target is the `Tab` in the **strip**, and what moves is the view inside the tab's **content**. That is
> the reason to prefer per-panel hosts over moving one shared view between tabs.

`DockGroup` caches **one pane per (group, typeId)**, not per input:

- Opening a second `file` tab in a group reuses the pane and calls `setInput`.
- Before retargeting, the framework calls `writeViewState` and stores it against the *outgoing* input;
  after, it calls `readViewState` with the incoming input's stored state.
- `onHidden` on the outgoing tab, `onVisible` on the incoming.

That is exactly VS Code's behaviour and it is what makes the Inspector work without any host swapping:
the Inspector is a pane whose `setInput` binds it to a graph.

**Consequence to accept deliberately:** two tabs of the same type in one group share a pane, so a pane
must be stateless between inputs except through view state. That is the contract, and it is why
`writeViewState`/`readViewState` are framework-driven rather than pane-driven.

### 18.5 Migration

`DockPanelRegistry.register(descriptor, Function<ref, UIElement>)` stays and is wrapped by a
`LegacyPaneProvider` that creates a `DockPane` whose `setInput` is a no-op. Every existing panel keeps
working unchanged. Panels migrate one at a time; the legacy overload is deleted when the last one goes.

**Migrated in this step:** the Inspector (deleting `inspectorHost`, `fillingHost`, `assertOnlyChild`,
`show`, `followed`, `followActiveGraph`) and the generated shader (deleting `showCompiled`).

### 18.6 Files

**New:** `DockPane.java`, `DockPaneProvider.java`, `DockInput.java`, `DockCapability.java`,
`LegacyPaneProvider.java`; `graph/shader/ShaderInspectorPane.java`, `GeneratedShaderPane.java`;
tests `DockPaneLifecycleTest.java`, `DockPaneViewStateTest.java`.

**Modified:** `DockPanelRegistry` (provider-based, keeping the legacy overload), `DockGroup`
(pane cache, lifecycle calls, view-state handoff), `CrystalEditor` (large deletions),
`WorkbenchSession` (view state moves to panes; codec bump).

### 18.7 Tests (contract)

1. **`setInput` is called exactly once per input change**, and not at all when the same input is
   re-activated.
2. The pane instance is the **same object** across a retarget — identity assertion.
3. `onHidden` of the outgoing precedes `onVisible` of the incoming.
4. `writeViewState` runs before the retarget, `readViewState` after — assert ordering, and assert the
   state lands against the *right* input when three tabs rotate.
5. `clearInput` on close; `dispose` exactly once, and only when the pane leaves the group.
6. A provider that refuses an input is not asked to create.
7. Two providers accepting one input: higher `priority` wins.
8. **Mutation check:** break the retarget so a new pane is built each time; test 2 must fail.

### 18.8 Traps

- `ShaderGraphInspector` **marks itself internal** (the cause of the stacked-Inspector bug). A pane's
  `view()` must be added by the framework with the matching API — this is the bug that motivated the
  step, and the new code must not reproduce it.
- A pane created during `bind`/rebuild lands after that frame's layout pass (documented trap). Panes
  must be created in the group's build, never lazily during paint.
- Do not let the pane own the mapping from input to view state. The framework keys it, or two panes of
  one type will overwrite each other's state — which is the same class of bug as the stacked inspectors.

---

## 19. Step 6 — `DockService.open`, `DockPlacement`, `groupOf`

**Why last of the core set:** it is small once panes exist, and it is what finally deletes
`showCompiled` and the three `openPanel*` overloads.

### 19.1 What the references do

**VS Code.** `IEditorService.openEditor(input, options, group)` where `group: PreferredGroup` is
`IEditorGroup | GroupIdentifier | SIDE_GROUP (-2) | ACTIVE_GROUP (-1) | AUX_WINDOW_GROUP`.
**Placement is a value, resolved by the service.** `IEditorGroupsService` owns the grid:
`groups`, `activeGroup`, `addGroup`, `moveGroup`, `mergeGroup`, `arrangeGroups`.

**IntelliJ.** `FileEditorManager.openFile(file, focus)` targets `currentWindow`;
`openFileInNewWindow`, `openInRightSplit(file)`. `FileEditorManagerEx.getSplitters()` for the tree.

### 19.2 API

```java
package com.crystalgui.ui.elements.dock;

public sealed interface DockPlacement {
    /** The group commands resolve against. VS Code's ACTIVE_GROUP. */
    static DockPlacement active();
    /** Split beside the active group. VS Code's SIDE_GROUP. */
    static DockPlacement side(DockDropZone zone);
    /** The group containing this element — "next to me". The one both references make trivial. */
    static DockPlacement with(UIElement element);
    /** The central work area, whatever is active. */
    static DockPlacement central();
    /** A named group, for restore. */
    static DockPlacement group(DockLeaf leaf);
    /** A tool window's remembered placement — routes to ToolWindowLayout (already built). */
    static DockPlacement remembered(String typeId);
}

public interface DockService {
    DockLeaf open(DockInput input, DockPlacement placement);
    boolean close(DockInput input);
    void activate(DockInput input);
    @Nullable DockGroup groupOf(UIElement element);
    @Nullable DockGroup activeGroup();
    List<DockGroup> groups();
    @Nullable DockLeaf leafOf(DockInput input);
}
```

`DockArea implements DockService`. `groupOf` walks `getParent()` — including internal parents, same
rule as §15.5.

### 19.3 What this deletes

| Deleted | Replaced by |
|---|---|
| `Workbench.openPanel(ref)` | `dock.open(input, DockPlacement.central())` |
| `Workbench.openPanelWith(sibling, ref)` | `dock.open(input, DockPlacement.with(siblingView))` |
| `Workbench.openPanelBeside(ref, zone, share)` | `dock.open(input, DockPlacement.side(zone))` |
| `CrystalEditor.showCompiled(graph)` | `dock.open(generatedInput, DockPlacement.with(this))` **inside `ShaderGraphEditor`** |
| `Workbench.showPanel`/`hidePanel` placement logic | `dock.open(input, DockPlacement.remembered(typeId))` |

`ShaderGraphEditor.installCommands()` then reads, in full:

```java
registry.register(Command.of(VIEW_GENERATED_COMMAND, "View Generated Shader")
        .run(ctx -> {
            ShaderGraphEditor graph = ctx.data().get(UiDataKeys.SHADER_GRAPH);
            if (graph != null) graph.dock().open(graph.generatedInput(), DockPlacement.with(graph));
        })
        .enabledWhen(ctx -> ctx.data().has(UiDataKeys.SHADER_GRAPH)));
```

No signal, no application listener, no `CrystalEditor` involvement. **That is the acceptance test for
the whole plan.**

### 19.4 Files

**New:** `DockPlacement.java`, `DockService.java`; tests `DockPlacementTest.java`,
`DockServiceOpenTest.java`.

**Modified:** `DockArea` (implements the service), `Workbench` (the three overloads become deprecated
delegates, then are deleted in this step), `ShaderGraphEditor`, `CrystalEditor` (final deletions),
`ActivityBar`, `WorkbenchSession`.

### 19.5 Tests (contract)

1. Each placement resolves to the documented group, asserted on the **built widgets**, not the layout
   model — the mistake that let the toggle bug through.
2. `with(element)` on a **detached** element falls back to `active()` rather than throwing.
3. `open` on an already-open input **activates** rather than opening a second — including bringing it
   to the front, which is the bug we already shipped once.
4. `remembered(typeId)` honours `ToolWindowLayout`'s four tiers in order (already tested; re-point).
5. `groupOf` finds the group from an element nested several levels inside a pane, including through
   internal children.
6. **The acceptance test:** a widget with no reference to `Workbench` or `CrystalEditor` opens a panel
   beside itself. Written against a bare `DockArea` in a test, no workbench at all.

### 19.6 Traps

- `open` must funnel **everything**, including `togglePanel`'s show path. Two entry points is how the
  current placement inconsistencies arose.
- Structural changes need `requestRebuild()`, not `syncGroups()` — the asymmetric bug from §0. The
  service must decide this once, centrally, rather than at each call site.
- `activate` after `openPanelWith` semantics: the existing `openPanelWith` deliberately preserves the
  previous selection. `DockService.open` must **not** — a caller asking to open something wants to see
  it. Keep the old behaviour available as an option flag if any caller still needs it.

---

## 20. Sequencing, and what to do first

| Step | §  | Est. | Depends on | Deletes |
|---|---|---|---|---|
| 1 | 14 | ~2 d | — | hand-written GL teardown loops |
| 2 | 15 | ~3 d | — | 3 hand-rolled parent walks |
| 3 | 16 | ~3 d | 1 (subscription lifetime) | 4 polling loops |
| 4 | 17 | ~4 d | — | `graphPaths`, `pathOf`, `graphForPath` |
| 5 | 18 | ~5 d | 1, 4 | `assertOnlyChild`, `inspectorHost`, `show`, `followed`, `followActiveGraph` |
| 6 | 19 | ~3 d | 5 | `showCompiled`, 3 × `openPanel*` |

Steps 1, 2 and 4 are independent of each other and could be done in any order. 3 wants 1. 5 wants 1
and 4. 6 wants 5.

**Start with step 1.** It is the smallest, has no dependencies, has zero design risk (the reference
implementation is well understood and simple), and every later step is safer with it in place.

### 20.1 Definition of done for the whole set

The seven tests in §13.6. After step 6 we should pass 1, 2, 3, 4, 5 and most of 6, with the seventh
(adding a feature without understanding the workbench) demonstrable by the §19.5 acceptance test.

### 20.2 What is explicitly NOT in these six steps

Parts and ViewContainers (§12.4), menu contributions, the `when`-expression parser, the ref-counted
model registry, and everything in §11 Tier 2 except the editor-notification banner. Those are phase
two, and the decision to do them should be taken after living with phase one.

---

## 21. Step 7 — Editor-type resolution as a contribution

**The complaint.** `CrystalEditor` names `.shadergraph`, `ShaderGraphEditor` and `ShaderGraphInspector`.
It is the *general* editor and it hardcodes one application's file types:

```java
workbench.registerDocumentType(SHADER_GRAPH_FILE_TYPE, "Shader Graph", path -> { … new ShaderGraphEditor() … });
workbench.bindEditorExtensions(SHADER_GRAPH_FILE_TYPE, "shadergraph");
workbench.registerPanel(DockPanelDescriptor.singleton(INSPECTOR_TYPE, "Inspector"), ref -> inspector);
```

This is §13's third principle stated as a defect: *"declaration over construction — the call site says what
it wants, never which class renders it. This is the difference between a feature being **additive**
(register a provider) and **invasive** (edit a switch, a factory, or an application class)."* Adding a
`.material` editor today means editing `CrystalEditor`.

### 21.1 What the references do

**IntelliJ.** `FileEditorProvider` is an *extension point*. A plugin declares it in `plugin.xml`; the
platform asks every registered provider `accept(project, file)` and builds with the winner, `getPolicy()`
breaking ties. `FileEditorManager` never learns a file type — it asks.

**VS Code.** `contributes.customEditors` in the extension manifest, resolved by
`IEditorResolverService.registerEditor(glob, info, options, factories)`. Same shape: a glob and a factory,
declared by whoever owns the type.

**The shared property.** The registry is asked, never told. Neither platform has a class that enumerates
file types, because the set is open by construction.

### 21.2 The decision — the extension point already exists

Step 5 shipped `DockPaneProvider.accepts(DockInput)` + `priority()`, which **is** `FileEditorProvider`'s
`accept` + `FileEditorPolicy`. What is missing is not a mechanism but its use: the shader-graph package
should register itself, and `CrystalEditor` should never name it.

So this step adds no new concept. It moves three registrations across a package boundary and deletes the
knowledge that made them possible.

```java
// com.crystalgui.graph.shader — the package that owns the type registers it.
public final class ShaderGraphContribution {
    public static void register(Workbench workbench) {
        workbench.contribute(DocumentType.of(SHADER_GRAPH_TYPE, "Shader Graph")
                .forExtensions("shadergraph")
                .document(path -> new ShaderGraphEditor().setResource(Resource.of(path))));
    }
}
```

**One call, from one place, naming one package.** `CrystalEditor` keeps a list of contributions it
enables — which *is* an application decision, and the only one it should be making about file types.

> **`DocumentType` merges `registerDocumentType` + `bindEditor*`.** Today those are two calls that are
> meaningless apart: a document factory with no binding never opens, and a binding with no factory throws
> at open time (`"No document factory for panel type"`). Two calls that must both happen are one fact.

### 21.3 What this deletes

| Deleted from `CrystalEditor` | Replaced by |
|---|---|
| `registerDocumentType(SHADER_GRAPH_FILE_TYPE, …)` | `ShaderGraphContribution.register` |
| `bindEditorExtensions(SHADER_GRAPH_FILE_TYPE, "shadergraph")` | `DocumentType.forExtensions` |
| `SHADER_GRAPH_FILE_TYPE`, `SHADER_SOURCE_TYPE`, `SHADER_SOURCE_SCHEME` constants | the shader package's own |
| the generated-source panel factory and `graphFor` | a provider in the shader package |
| every `import com.crystalgui.graph.shader.*` | — |

**The test of done: `com.crystalgui.editor` imports nothing from `com.crystalgui.graph`.** That is
mechanically checkable and is the acceptance criterion.

### 21.4 Traps

- **A contribution must be idempotent and explicit**, like every registration here. No static
  initialisers — a file type whose existence depends on class-loading order is worse than one hardcoded,
  because it is hardcoded *and* unpredictable.
- **The `Workbench` must stay type-agnostic too.** Moving the knowledge from `CrystalEditor` into
  `Workbench` is not progress; `FilePatternMap` is already the matcher half and should stay generic.
- **Ordering when two contributions claim one extension** is `priority()`, and it must be *stated* rather
  than left to registration order — that is what `FileEditorPolicy` exists for.

---

## 22. Step 8 — The Inspector as a contribution surface

**The complaint.** `ShaderGraphInspector` should not exist. An inspector is a *general* tool that any
element can hook into and supply data for — Blender's Properties editor, DaVinci Resolve's Inspector.

**This plan previously got it wrong**, and the record should say so plainly. §12.3 proposed:

```java
class ShaderInspectorPane implements DockPane {
    public void setInput(DockInput input) { bindTo(resolveGraph(input)); }
}
```

and §18.6 listed `graph/shader/ShaderInspectorPane.java` as a new file. That fixes the *retargeting* —
which was the symptom — and never asks why a general tool has a graph-shaped type at all.

### 22.1 How Blender actually does it

The reason its Properties editor works for a mesh, a light, a camera, a material, a keyframe and an
add-on's own datablock is **three mechanisms**, none of which is "an inspector per type":

**1. One editor, many contributed panels.** A `Panel` is a registered class, not something the editor
knows about. It declares where it belongs and when it applies:

| Attribute | Meaning |
|---|---|
| `bl_space_type` / `bl_region_type` | which editor it appears in |
| `bl_context` | which **tab** of the Properties editor (`object`, `modifier`, `material`, `data`, …) |
| `poll(cls, context)` | **whether it applies at all right now** — the whole extensibility hinge |
| `bl_order`, `bl_parent_id` | ordering, and nesting as a sub-panel |

The editor draws every registered panel whose `poll(context)` returns true. A mesh object shows the
Modifiers tab; a light does not — not because the editor knows what a light is, but because the modifier
panels' `poll` returns false. **An add-on registering a panel is indistinguishable from a built-in one.**

**2. The subject comes from `context`.** `poll` and `draw` both receive it, and read `context.object`,
`context.material`, `context.active_pose_bone`. That is a `DataContext`: the editor supplies no subject
of its own, it asks.

**3. Properties are drawn reflectively, not hand-built.** `layout.prop(data, "location")` — RNA describes
the property's type, range, subtype and units, and *Blender picks the widget*. This is what makes "every
object" tractable: nobody writes a form per type, they declare properties and the UI is derived. RNA is
described by Blender's own docs as "a reflection system and high-level data access at runtime", with much
of the Python API auto-generated from it.

> **The third is the one that is easy to miss and is doing most of the work.** With contributions but
> without reflection you still hand-write a form per type — you have only moved where it lives. Blender
> gets "works with everything" from *declared properties*, not from a big registry of forms.

### 22.2 What we already have

All three, unusually.

| Blender | Ours | State |
|---|---|---|
| `context.object` etc. | `DataContext` + `DataKey` | **shipped** (step 2) |
| `Panel.poll(context)` | a provider's `accepts(DataContext)` | the shape exists in `DockPaneProvider`; not yet for inspection |
| `layout.prop(data, "x")` — RNA | `ConfigDescriptor` + `SettingsConfigurator.build(...)` | **shipped**, and already used by `ShaderGraphSettingsPanel` |
| contribution registry, merged per field | `FileDecorations` | **shipped** — §12.6 already names it *"a faithful contribution registry — use it as the template for the others"* |

`SettingsConfigurator` is our RNA-lite: it builds a form from `ShaderGraphSettings.all()` descriptors
rather than from hand-placed widgets. The inspector's job is to find the descriptors, not to draw.

### 22.3 The decision

```java
package com.crystalgui.ui.elements.inspector;

/** Describes some subject to the inspector. Blender's Panel + poll(). */
public interface InspectorSection {
    /** Which tab this belongs in — Blender's bl_context. */
    String tab();
    /** Whether this applies to what is currently selected. Blender's poll(). */
    boolean accepts(DataContext context);
    /** Build the form. Prefer descriptors over hand-placed widgets. */
    void build(InspectorForm form, DataContext context);
    default int order() { return 0; }
}

public final class InspectorRegistry {
    public static void register(InspectorSection section);
    public static List<InspectorSection> sectionsFor(DataContext context);   // poll + order
}
```

`Inspector` is then one widget with no knowledge of anything: it resolves the subject from
`DataContext`, asks the registry which sections apply, and builds their forms into tabs.

**`ShaderGraphInspector` becomes registrations**, in the shader package:

| Was | Becomes |
|---|---|
| `ShaderNodeInspector` (the Node tab) | an `InspectorSection` accepting a `GraphNode` subject |
| `ShaderGraphSettingsPanel` (the Graph tab) | an `InspectorSection` accepting a `GraphDocument` subject |

And the property board, the file tree and the text editor can each contribute a section without the
inspector changing — which is the actual test of the design.

### 22.4 Why this also fixes the retargeting problem for free

An inspector built this way **has no per-graph state to retarget**. It rebuilds its sections from the
current `DataContext` whenever the subject changes, which is one code path rather than `setEditor`, a
`shown` field, a subscription group and a latch. The whole apparatus of §18 exists because the inspector
was bound to a graph; a section is bound to nothing.

> This is the second time this shape has appeared. `Delete` works in the tree, the graph and the editor
> because each supplies a different subject — §13.5 — not because Delete knows about three widgets. The
> inspector is that sentence with the verb replaced by a form.

### 22.5 Traps

- **Sections must not hold their subject.** `build(form, context)` receives it; a section that caches one
  is a per-type inspector again, with the same lifetime bug that made the old map leak.
- **A section that applies to nothing must render nothing**, not an empty framed panel. Blender hides the
  whole panel when `poll` fails; an empty box reads as a broken inspector.
- **Ordering must be declared** (`order()`), not registration order, or two features interleave
  differently depending on class-loading — the same rule `MenuId.Placement` already follows.
- **Do not build the tab set from a fixed list.** Tabs come from the sections that applied; a hardcoded
  `Node`/`Graph` pair is the current design wearing a registry.
- **Reflection is the point, not the registry.** If sections end up hand-placing widgets, this has moved
  the problem rather than solved it. Extending `ConfigDescriptor` coverage is part of this step, not a
  follow-up.

#### Found while living with it

Four things the design above does not prevent, all of which shipped and all of which look correct in
review. Each is one line to get wrong and produces no error.

- **The panel must outlive the build that fills it.** `Inspector` keeps one `ConfiguratorPanel` per tab
  and calls `clearRows()`; it must never construct a new one per rebuild. Everything a panel remembers is
  *view state* — which foldouts are open, where it is scrolled — and `ConfiguratorPanel` already
  implements both halves and documents them (`groupCollapsed` is written to outlive `clearRows()`, and
  `clearRows()` exists "for a panel that is rebuilt rather than merely updated"). A panel discarded per
  rebuild orphans both **while still looking correct**: the mechanism is there, it is called, and it
  remembers nothing. Symptom was a group the user opened closing itself on the next click.

- **Tab selection is `new > remembered > first`, not `remembered > first`.** The obvious two-case version
  leaves you on the tab you were already on at exactly the moment a *new* one appears — so selecting a
  node built the `Node` tab correctly and left you looking at `Graph`. A tab exists only because a section
  polled true, so a new one is the engine's own evidence that the subject gained something it could not
  describe a moment ago. Self-limiting: it can fire at most once per appearance, so switching between two
  nodes never steals focus. Lives on `Inspector`, not in any section.

- **Sections that are alternatives must arbitrate in one place.** Sections in a tab are *additive* — that
  is the design, and it is what lets `Graph` stack Shader + Preview + Compile. But four sections answering
  the one question "what is selected" are not additions to each other, and the engine cannot know that. Ours
  each decided alone: three re-derived the same exclusion by hand and none looked at *how many* things were
  selected, so a marquee catching a wire made two of them accept (two stacked panels) and a marquee catching
  a property node demoted a ten-node selection to one property. Fix is one `subject(context)` returning the
  kind, with **plural outranking singular** — the multi view is the only one that can describe more than
  one thing. Adding a fifth kind is then a branch, not an audit of four polls kept exclusive by hand.

- **Do not add a section to stop the panel looking empty.** Most focused things are not inspectable and
  never will be — an inspector is for structured, non-linear data whose editing surface genuinely *is* a
  property list (a graph node, a canvas item, a mesh, a scene object). A text buffer is edited in place,
  and its metadata (encoding, line endings, language, indent) belongs in a **status bar** — VS Code puts
  all four there and IntelliJ has no inspector at all. The engine's answer is the retention rule (keep the
  last describable subject), and that is the permanent steady state, not a stopgap for missing sections.

- **A bound control must own its subscription to the store.** A control follows `settings.onChanged` /
  `document.onChanged` so an edit made elsewhere reaches the widget, and those stores outlive it by the
  life of the application or the file — while an inspector rebuilds every control on every click. Nothing
  disconnected them, so the store grew one dead listener per row per rebuild, each holding a widget that
  had left the tree. **Invisible from both ends**: the host subscribed, the store notified, nothing failed.
  **The engine owns when, not the owner.** `ConfigControl.follows(...)` takes a supplier and connects on
  attach, disconnects on detach, re-establishes on re-attach. Having each *owner* release instead needs a
  remembered call in four places that share no supertype — a panel replacing rows, a node being deleted, a
  graph clearing nodes, a port editor unmounting — and a fifth owner would not know to. Release-on-detach
  alone is also a trap: a control taken out and put back comes back permanently deaf, in exactly the cases
  nobody tests. Pinned by `ConfiguratorPanelLifetimeTest`, which asserts all three halves — the count stops
  growing, the live rows still hear the store, and a re-attached row re-reads a value that moved while it
  was away.

### 22.6 Sequencing

7 before 8: once a package can contribute its own document type, contributing its own inspector sections
is the same act against a second registry, and the two land in one commit per package.

Neither step needs new infrastructure. `DataContext` (step 2), service events (step 3) and
`DockPaneProvider` (step 5) are the substrate, and `FileDecorations` is the template.

---

## 23. Step 11 — the Parts model, and the six foundations it needs

> **Status: NOT STARTED.** This section is the audit and the decision record; no code has moved.

### 23.1 Why now — the trigger §9 decision 2 set

Decision 2 said: *keep the uniform tree; revisit only if a second class of bug appears.* That bar has
been met, and it is worth naming the evidence rather than calling this a preference.

- **`Workbench.showPanel` is a four-tier restoration heuristic.** Strip-mate, then structural path, then
  surviving neighbour, then anchor — with a javadoc explaining that the order is load-bearing in both
  directions. It is careful, correct, well-tested code, and **every tier of it exists because closing a
  tool window collapses the branch that held it.**
- **`ToolWindowState` carries two fields IntelliJ does not need** — `path` and `groupedWith` — and says so
  in its own javadoc: *"IntelliJ needs no equivalent because its tool windows are never in a tree."*
- **`ToolWindowLayout` exists at all** to keep placement *beside* the tree, because placement cannot be
  recovered *from* the tree once a branch collapses.
- **The activity bar lists panel types, not containers**, so two tool windows cannot share a region and a
  view cannot be dragged from the sidebar to the bottom panel.
- **Ctrl+B has nowhere to live**, because the sidebar is not a thing — it is wherever the Project panel
  happens to be docked.

That is one shape of bug, found five ways. The heuristic is the tell: **we built a recovery mechanism for
information we should never have destroyed.**

### 23.2 Reference research

#### VS Code — parts, then containers, then views

A fixed set of `Part`s, each independently visible, sizeable, positionable and persisted: `TITLEBAR`,
`BANNER`, `ACTIVITYBAR`, `SIDEBAR`, `EDITOR`, `PANEL`, `AUXILIARYBAR`, `STATUSBAR`. The workbench itself is
a grid *of parts*; the editor's own splittable grid is nested **inside** `EDITOR_PART` and knows nothing
about the rest.

`IWorkbenchLayoutService` owns the region questions — `setPartHidden(hidden, part)`, `isVisible(part)`,
`getSize(part)`, `resizePart`, `setPanelPosition`, `toggleMaximizedPanel`. None of those is expressible
against a uniform tree, because none of them names a *position*; they name a **region**.

Inside that sits a second split we have no counterpart for at all:

```
ViewContainer   "Explorer", "Source Control"      <- what an activity bar button toggles
   \-- View     "Folders", "Outline", "Timeline"  <- draggable BETWEEN containers
```

A view declares a `containerId`; a container declares a location (`Sidebar` / `Panel` / `AuxiliaryBar`).
A user dragging a view to the bottom panel rewrites **the view's container**, not the layout tree.
Persisted as `workbench.views.state` and `workbench.activity.pinnedViewlets2`, separately from the editor
grid.

Badges live on the *container*: `IActivityService.showViewContainerActivity(containerId, { badge })`. That
is why badges and Parts are one piece of work — a badge has no home until containers exist.

#### IntelliJ — anchors, `WindowInfo`, and `ContentManager`

The same split under different names. `ToolWindowManager` owns tool windows; `ToolWindowAnchor` is
`LEFT`/`RIGHT`/`TOP`/`BOTTOM`; `WindowInfoImpl` persists `anchor`, `weight`, `sideWeight`, `order`,
`visible`, `active`, `type`. The editor area is `EditorsSplitters` and **never contains a tool window**.

The container/view split is `ToolWindow` — holding `Content`s in a `ContentManager` — over `Content`. A
tool window is not one panel; it is a host for several.

#### What both agree on, and where we sit

| Concept | VS Code | IntelliJ | Ours today |
|---|---|---|---|
| Fixed regions | `Part` | anchors + editor area | absent — one uniform tree |
| Group of views in a region | `ViewContainer` | `ToolWindow` + `ContentManager` | absent |
| A movable view | `ViewPane` | `Content` | a dock panel |
| Region visibility | `setPartHidden` | `ToolWindow.hide()` | absent |
| Region size, persisted apart | grid serialization | `WindowInfo.weight` | inside the tree |
| Editor area | nested grid | `EditorsSplitters` | **the same tree as everything else** |

The last row is the whole difference. **Both references keep a splittable tree for documents only.**
§12.4's recommendation stands: keep our dock tree for the editor area — it is good, and it is what makes
arbitrary splits work — and introduce Parts around it.

### 23.3 The six foundations

Ordered as they must land, not by size.

---

#### F1 — The tool-window manager leaves `Workbench`

**Now.** `Workbench` is 1324 lines and owns `isPanelOpen`, `showPanel`, `hidePanel`, `togglePanel`,
`toolWindows()`, `placementOf`, `withRelativePosition` and `outerEdgeOf` — plus the four-tier heuristic.
This is §7 stage 6, the one marked **High** risk and never done, which §7's own note calls superseded.

**Parts un-supersedes it.** Stage 6 was skipped because the *coupling* complaint behind it was answered by
decoupling callers instead — correct at the time. But Parts is a rewrite of precisely this surface, and
doing it inside `Workbench` would grow it past 1400 lines and re-entangle what steps 1–10 separated.

**Do:** extract `PartService` (regions: visibility, size, which container is showing) and
`ViewContainerRegistry` (containers, their views, their location). **Not** the four-way split §5.3
imagined — that was wrong and is recorded as wrong. One extraction, the one Parts needs.

**Size:** medium. The risk §7 assigned was for a four-way split; a single extraction, with the contract
tests from §8 already in place, is materially smaller.

---

#### F2 — `ToolWindowState` sheds its tree fields, and the heuristic dies with them

**Now.** `path` and `groupedWith` exist only because tool windows live in the tree. `showPanel`'s four
tiers consume them.

**After Parts** a tool window has a **region**, an **order within it** and a **size**. Restoration becomes
a lookup. Tiers 1–3 have nothing left to be about: there is no strip to rejoin, no branch to have
collapsed, and no neighbour to be relative to.

**Do:** reduce `ToolWindowState` to IntelliJ's actual field set — anchor becomes a region, plus `order`,
`weight`, `visible`, `active` — and delete the heuristic along with the fields that fed it.

**This is the largest deletion in the step, and it is the point.** Keep the tests: they describe behaviour
that must still hold (hide-then-show is exact), and they should pass against a lookup.

**Size:** small once F1 lands, and it removes more than it adds.

---

#### F3 — View identity, settled before any codec is written

**Now.** `DockPanelRef.equals` includes `state`, and §1.5 already flags it: *"correct, but it makes adding
a state key a breaking change (bit us with `ICON`)."*

**What breaks.** A view needs identity, a container membership and per-view state. If membership lands as
ref state, two refs for the same view in different containers are **different refs** — so
`leafContaining`, `content.computeIfAbsent`, every `Map<DockPanelRef, …>` and every saved layout change
meaning at once. Dragging a view between containers would destroy and rebuild it rather than move it.

**Do:** decide **before** touching persistence. The recommendation is a separate `ViewId` — an interned
string like `DataKey`/`MenuId` — with container membership held **by the container**, never by the view.
That is both references' arrangement: a VS Code view declares a `containerId` and the registry owns the
mapping; an IntelliJ `Content` does not know its `ContentManager`'s identity.

**Size:** small in code, large in consequence. **This is the one to get right first.**

---

#### F4 — The session version: discard once, deliberately

**Now.** `WorkbenchSession.VERSION = 3`, and the class states that an unknown version is *"discarded,
never guessed at"*. Parts adds at least four newly persisted facts: region visibility, region size,
container membership, and view order within a container.

§11 Tier 1's last row predicted this exactly — *"every newly persisted thing is bespoke; §7 stage 8 will
feel this."* This is stage 8 arriving.

**Do:** pick one, in writing, before the first field is added.

| Option | Cost | Verdict |
|---|---|---|
| Bump to 4, discard old sessions | one lost layout per user, once | **Recommended.** The shape change is total, and a migration would mean translating a tree position into a region — which is exactly the information the tree does not preserve |
| Write a migration | real work, and it cannot be faithful | Rejected. It would have to guess, and guessing is what the version field exists to refuse |
| A declarative state framework first | large | Deferred. Worth doing when a *fifth* bespoke codec appears, not to unblock this |

**Say it in the version comment**, so the discard reads as a decision rather than a regression.

---

#### F5 — `DockPlacement` grows a region

**Now.** `DockPlacement` is `Active` / `Side(zone)` / `With(element)` / `Leaf(leaf)`, and every variant
resolves against **one** dock. It is a sealed interface, so adding a variant is a compile-time-checked
change rather than a silent one — which is the good news.

**After Parts,** `open()` answers *which region* first and *where within it* second. A document goes to
`EDITOR`; a view goes to its container's region.

**Do:** add an `InRegion(part)` variant (or a `PartPlacement` wrapper) and route `open` through region
resolution before tree resolution. Design it now — retrofitting a dimension through every call site later
is the expensive version.

**Size:** small, if done before F6 puts new callers on it.

---

#### F6 — `isSingleton()` becomes a three-way kind

**Now.** `DockPanelDescriptor.singleton` is the "is this a tool window" test, and `ActivityBar` is a view
over exactly that predicate: *"one button per singleton panel type. That filter is the whole
definition."*

**What breaks.** Parts needs **three** kinds — a **document** (many, in the editor region), a **view**
(one, inside a container) and a **container** (a group of views, the thing a rail button toggles). A
boolean cannot carry a third, and the rail must list containers rather than panel types.

**Do:** replace the boolean with `DockPanelKind { DOCUMENT, VIEW, CONTAINER }`. `isSingleton()` becomes
`kind() == VIEW`, so existing call sites are mechanical. Then `ActivityBar` is rewritten as a view over
containers — which is also where badges finally have a home, since a badge belongs to a container.

**Size:** medium, and it is the one users see.

---

### 23.4 What deliberately does not change

Worth stating, because the list is longer than the change:

- **`DockLayout`, `DockArea`, `DockGroup`, `DockPane`, `DockLeaf`, `DockPath`** — the editor grid is right,
  and both references keep one. It stops holding tool windows; it is not otherwise touched.
- **`Workbench.open(input, placement, options)`** — gains a region step, keeps its shape.
- **Documents** — `DocumentType`, `FileDocument`, `OpenDocuments`, `Resource` are unaffected.
- **The contribution surfaces** — `InspectorSection`, `MenuId`, `DockBannerProvider`, `Notifications`. A
  view container is a fifth surface of the same shape, not a new mechanism.
- **The `when` parser, the model registry, lifecycle phases, cross-document undo** — all still cut or
  deferred, and none of them blocks this.

### 23.5 Traps

- **Do not let container membership become ref state.** F3 exists for this. Dragging a view between
  containers must *move* it, and a state-keyed identity makes that a destroy-and-rebuild.
- **Do not migrate the session.** A tree position does not carry a region, so the migration would guess —
  and the version field exists to refuse guessing.
- **Do not port `sideWeight`, floating or windowed tool windows.** `ToolWindowState` already records that
  decision and the reasons still hold.
- **Do not split `Workbench` four ways.** §5.3 predicted it, it did not happen, and nothing has needed it.
  Extract the one service Parts requires.
- **Keep the hide-then-show-is-exact tests.** They describe a behaviour that must survive the mechanism
  changing underneath it, which is what makes them the safety net for F2.
- **A region with no visible container still exists.** The uncloseable central leaf already states the
  general form: a region that vanishes when empty cannot be reopened.

### 23.6 Sequencing

State shape first, behaviour second, the visible layer last — so that if the result is wrong, which half
is wrong is answerable.

| # | Work | Depends on | Why here |
|---|---|---|---|
| 1 | **F3** view identity | — | Everything persisted or keyed depends on the answer |
| 2 | **F4** version decision | F3 | Written down before the first new field |
| 3 | **F1** extract the manager | — | Can run beside 1–2; it is the surface Parts rewrites |
| 4 | **F5** region in `DockPlacement` | F1 | Before F6 puts callers on it |
| 5 | **F2** shed the tree fields | F1, F5 | The deletion, once a region exists to hold placement |
| 6 | **F6** three-way kind | F5 | Introduces containers |
| 7 | Parts proper — regions, visibility, sizes, Ctrl+B | F1–F6 | |
| 8 | `ActivityBar` rewrite + badges | 7 | A view over containers; badges have a home only now |

**F3 and F4 are the two that must be shot down before anything else moves.** They are small, and both are
decisions rather than code — which is exactly why they are easy to skip and expensive to revisit.

#### Correction, found while implementing: F2 cannot fully precede step 7

The table above puts F2 (delete the tree fields and the four-tier heuristic) at 5 and Parts proper at 7.
**That is backwards for the deletion half**, and the mistake is worth keeping rather than quietly fixing:
tiers 1–3 are a *fallback*, and a fallback cannot be deleted before the thing that replaces it exists.
Regions are not elements until step 7, so deleting the tiers first would leave a tool window nested
mid-tree reopening at a wall — a real regression, in exactly the arrangement the heuristic was written for.

**What landed instead** is F2's *shape*: `ToolWindowState.region()` derived from the anchor, and tier 4
asking for a region rather than a wall. Derived rather than stored on purpose — it is the same fact said
the durable way, so it costs no persisted field and therefore no version bump. The field, the bump and the
deletion all land together at step 7, which is when they stop being separable.

**Revised:** F2 splits into **F2a — state it as a region** (done) and **F2b — delete the tiers** (step 7).

---

## 24. Step 12 — the Parts stack, in full

> **Status: PLANNED.** The foundations (§23 F1, F3–F6, F2a) are in. This is the shell they were for.
>
> **Goal, stated by the user and taken literally: the same visual output and the same functionality as
> IntelliJ's New UI.** Where VS Code differs it is noted, but IntelliJ is the target.

### 24.1 The target

```
+--------------------------------------------------------------+
| MAIN TOOLBAR   burger | project | branch | run | search | gear |
+---+--------------+--------------------------------+------+---+
| L |              |  EDITOR                        |      | R |
| E |   SIDEBAR    |  +--------------------------+  | AUX  | S |
| F |  (container) |  | tabs                     |  |(cont)| T |
| T |              |  | gutter | text | stripe   |  |      | R |
|   |              |  +--------------------------+  |      | I |
| S +--------------+--------------------------------+------+ P |
| T | PANEL   (container: Problems > File | Project | ...)  | E |
+---+-------------------------------------------------------+---+
| STATUS BAR   breadcrumb path            51:39 CRLF UTF-8     |
+--------------------------------------------------------------+
```

**Two families, and the split is the design.** Content regions hold movable panels and are already spelled
by {@link DockRegion}; chrome parts hold fixed furniture and deliberately are not — a panel cannot be put
in the status bar, and an enum that lets you ask needs an answer.

| Content region | VS Code | IntelliJ | Have |
|---|---|---|---|
| `EDITOR` | `EDITOR_PART` | `EditorsSplitters` | **yes** — `DockArea`, unchanged |
| `SIDEBAR` | `SIDEBAR_PART` | `LEFT` anchor | no |
| `PANEL` | `PANEL_PART` | `BOTTOM` anchor | no |
| `AUXILIARY` | `AUXILIARYBAR_PART` | `RIGHT` anchor | no |

| Chrome part | Contents | Have |
|---|---|---|
| Main toolbar | burger menu, project, branch, run config + run/debug, search, settings, bell | no |
| Left stripe | two groups: top (Project, Commit, Structure…) and bottom (Terminal, Services…) | `ActivityBar`, flat, lists panels |
| Right stripe | Notifications, Database, Gradle | no |
| Status bar | breadcrumb left; caret, line ending, encoding, indent right | no |

### 24.2 What this sits on — the reason it is mostly assembly

The point of steps 1–11 was that this step should be small. It is worth listing, because every row is a
piece of the Parts stack that already exists and is already tested:

| Have | Does the work of |
|---|---|
| `SplitView` — n panes, weights, **and real min/max** (`setPaneSizeLimits`) | Region sizing. A weight cannot say "the sidebar is at least 150px"; this already can, and it is what VS Code's grid does |
| `DockArea` / `DockLayout` / `DockPane` | The `EDITOR` region, entire. Untouched |
| `TabView` | A container's view tabs — the `File | Project Errors | Qodana` strip |
| `Popover` (`AUTO`) + top layer + light dismiss + close watchers | The burger menu overlay and every dropdown in it, including Escape and press-outside |
| `Menu` / `MenuItem` / `ContextMenu` / `MenuId` (+ `nestedIn`) | The whole main menu: items, submenus, separators from group boundaries, accelerators read from the live keymap, dimming from `enabledWhen` |
| `DockRegion`, `DockPanelKind`, `ViewId` | The vocabulary (§23 F5, F6, F3) |
| `ToolWindowManager` | Visibility and placement, already extracted (§23 F1) |
| `Notifications` / `StatusBar` service | The status bar's model, already keyed per writer |
| `Breadcrumbs` | The status bar's left half |
| `ErrorStripePart`, `InspectionWidgetPart`, `SquigglesPart`, `GutterEdgePart`, `LineNumbersPart` | Every editor-internal part in the reference shot. **Already done** |
| `Disposer`, `DataContext`, `CommandRegistry`, `Keymap` | Lifetimes, subjects, every toolbar button's action |

**Genuinely new mechanisms: three.** Region visibility/sizing persistence, menu mnemonics, and menubar
hover-switching. Everything else is composition.

### 24.3 The region shell

```
WorkbenchShell (column)
  +-- MainToolbar                      chrome, fixed height
  +-- body (row)
  |     +-- StripeView LEFT            chrome, fixed width
  |     +-- SplitView (horizontal)
  |     |     +-- RegionHost SIDEBAR
  |     |     +-- SplitView (vertical)
  |     |     |     +-- DockArea            <- the EDITOR region
  |     |     |     +-- RegionHost PANEL
  |     |     +-- RegionHost AUXILIARY
  |     +-- StripeView RIGHT            chrome, fixed width
  +-- StatusBarView                     chrome, fixed height
```

**Regions are a fixed frame, not a dock tree — and that is the whole point.** You cannot drag the sidebar
into the panel; you drag a *container* between them. Using `DockLayout` here would reintroduce exactly the
problem §23 removed: a region whose identity has to be recovered from tree position.

So `SplitView` rather than `DockLayout`, and the nesting above is deliberate: the panel spans the editor
**and** the auxiliary bar in VS Code's default and IntelliJ's alike, which the vertical split inside the
horizontal one gives for free.

**`RegionHost`** is one element per region: it shows **one container at a time** (or two — see 24.4), owns
the region's visibility class, and is the drop target for a container dragged from a stripe.

**Persistence.** Region visibility and size are `ToolWindowState`'s job extended to regions, or a small
`RegionState` beside it. This is where §23 F4's version 4 finally lands: the field, the bump and F2b's
deletion of the restoration tiers all happen together, because they stop being separable once a region can
hold a placement.

### 24.4 `ViewContainer` and `View` — the split that was missing

```
ViewContainer  "Problems"   region = PANEL, order = 1
   +-- View    "File"        <- TabView tab
   +-- View    "Project Errors"
   +-- View    "Qodana"
```

The reference shot shows this literally: **Problems is one container with four views as tabs.** Ours is one
flat `ProblemsPanel`, which is why it is the right first container to build.

- **`ViewContainerRegistry`** — container → its views, container → its region and order. A view **never**
  names its container (§23 F3): membership is the container's, so moving a view is one write.
- **The header** — title, chevron, kebab (`MenuId.VIEW_CONTAINER_CONTEXT`, so a container's menu is
  contributable like every other), hide button.
- **The tab strip** — a `TabView`, shown only when the container has more than one view. One view means the
  header alone, which is what IntelliJ does for Project.
- **Badges** — `IActivityService.showViewContainerActivity` is on the *container*, which is why badges could
  not exist before this and why they are the same piece of work.

### 24.5 The stripes, and the `sideWeight` reversal

**`StripeView`** replaces `ActivityBar`. It lists **containers**, not panel types, and has **two groups** —
top-anchored and bottom-anchored — which is what the left rail's split in the reference shot is.

Dragging a container from one stripe to another **is** changing its region. That is the whole interaction,
and it is a one-field write because the container owns its region.

> **§23.5 said "do not port `sideWeight`". That is reversed here, and the reversal is the honest part.**
>
> The reason given was that stacking two tool windows on one wall is *"a feature of IntelliJ's tool-window
> host rather than of a dock tree"*. True — and irrelevant once we are building a tool-window host. IntelliJ
> splits a stripe: Project top-left, Structure bottom-left, one region divided along its cross axis. It is
> visible behaviour in the target, so ruling it out on the old grounds would be keeping a decision after its
> premise expired.
>
> **Port it, with IntelliJ's own limit: at most two containers per region**, split by a `SplitView`. That
> limit is what keeps it a region rather than a second dock tree.

### 24.6 The main toolbar, and the burger main menu

The toolbar is one chrome part with two states of the same row, so its height never changes and no region
re-measures when the menu opens.

**Collapsed:** burger, project widget, branch widget, run configuration + run/debug/stop, search, settings,
notifications bell.

**Expanded (`Alt+\`, or clicking the burger):** the classic bar — File, Edit, View, Navigate, Code,
Refactor, Build, Run, Tools, Git, Window, Help — **overlaid** across the row, collapsing when it stops being
hovered or active.

| Piece | How |
|---|---|
| The bar's items | `MenuId.MAIN_FILE`, `MAIN_EDIT`, … Each opens `ContextMenu.of(id)` — the same call `BlackboardPanel` already makes |
| Submenus (`New >`, `Recent Projects >`) | `MenuId.nestedIn`, already working |
| Accelerators, dimming, separators | Free: live keymap, `enabledWhen`, group boundaries |
| Contribution | **Any package can add `File > New > Shader Graph` with one `.menu(...)` call and no reference to the bar** |
| The overlay itself | `Popover` in `AUTO` mode — light dismiss and Escape come with it |

**Two new mechanisms, and only two:**

1. **Mnemonics.** `Alt+F` while the bar is showing, with the underline drawn *only while Alt is held*. This
   is not an accelerator: it is scoped to the open menu, single-letter, and has a rendering half. `Keymap`
   has no concept of it.
2. **Menubar hover-switching.** With File open, moving onto Edit switches without a click. `Menu` has
   `SubmenuTicker` for parent-to-child; sibling-to-sibling is the bar's own state and is the thing people
   notice instantly when it is missing.

### 24.7 The status bar

**A name collision to settle first.** `com.crystalgui.core.notify.StatusBar` is the *service* (keyed items,
no view). The widget is `StatusBarView` in `ui/elements/chrome`. Model and view, named so, rather than two
`StatusBar`s in two packages — which compiles and reads as a mistake forever after.

The service already does the hard half: items keyed per writer, replaced not accumulated, silent when
unchanged. `StatusBarView` renders them, plus:

- **Left:** the breadcrumb path — `Breadcrumbs` exists.
- **Right:** caret `51:39`, line ending `CRLF`, encoding `UTF-8`, indent `4 spaces` — each a status item
  written by whoever owns the fact, which is what the keying was for.

This also retires the harness scene drawing `onStatus` into a hand-placed label.

### 24.8 Traps

- **Do not build regions out of `DockLayout`.** A region's identity would again be a tree position, which is
  the exact problem §23 exists to remove. `SplitView` has weights *and minimums*, which is what a region
  needs and a dock leaf cannot express.
- **A hidden region still exists.** The uncloseable central leaf already states the general form: a region
  that vanishes when empty cannot be reopened, and Ctrl+B has nothing to toggle.
- **Region size is persisted per region, never derived from the split.** The same lesson as
  `ToolWindowState` — a size read back out of the layout is a size that a collapse destroys.
- **The toolbar's height must not change between its two states**, or opening the menu reflows every region
  below it.
- **A container with one view shows no tab strip.** IntelliJ does not draw a one-tab strip, and a strip that
  appears when a second view is added is correct rather than inconsistent.
- **Two containers per region, and no more.** The limit is what keeps a region a region.
- **`StatusBarView` renders; it does not compute.** Anything it shows is a status item somebody else wrote,
  or the keying is pointless.
- **The burger bar overlays; it does not push.** Pushing makes every region re-measure on a hover.

### 24.10 IntelliJ's actual tool-window machinery — read from the source

Written after the stripe drag was built twice from screenshots and got the *shape* right and the *seams*
wrong. Every claim below is from `JetBrains/intellij-community` at `master`, path given, so the next person
argues with the source rather than with a memory of it.

#### The model: four anchors x one boolean

`ToolWindowDescriptor` carries **`anchor: ToolWindowAnchor`** (`TOP`, `LEFT`, `BOTTOM`, `RIGHT`),
**`isSplit: Boolean`**, and **`sideWeight: Float = 0.5f`**, alongside `weight`, `order`, `type`,
`isVisible`, `isAutoHide`, `contentUiType`.

That is `DockRegion` + `RegionSide` + one field we do **not** have: `sideWeight` is the divider *between the
two halves of one anchor*, distinct from `weight`, which is the anchor's share of the whole. We store which
half a tool window is in and have nothing to say how the two halves divide.

#### The rails — confirmed exactly right

`toolWindow/ToolWindowLeftToolbar.kt` and `ToolWindowRightToolbar.kt`:

| Toolbar | topStripe | bottomStripe |
|---|---|---|
| Left | `StripeV2(LEFT)` | `StripeV2(BOTTOM)` |
| Right | `StripeV2(RIGHT)` | `StripeV2(BOTTOM, split = true)` |

`StripeRail.of(region, side)` reproduces this row for row, including the asymmetry that only `BOTTOM`'s
split changes which rail its button is on. **Nothing here needs to change.**

#### The divider inside a stripe is `isSplit`, not a third area

`openapi/wm/impl/AbstractDroppableStripe.kt` — a **single** stripe holds both halves of its anchor and
separates them visually:

```kotlin
if (useStripeButtonSeparator) { /* StripeButtonSeparator groups the split buttons */ }
else { /* useSplitGap: split buttons pushed to the far end with a Classic-style gap */ }
```

So the line between Project/Commit/Structure and the Debug beetle is **not** a third stripe — it is
`LEFT` vs `LEFT+split` inside one stripe, drawn with a separator in the new UI and a gap in the classic one.
Our top group runs the two halves together with nothing between them; that is the whole visual difference.

#### Drop targeting is per-stripe, and the split comes from halving the STRIPE

`toolWindow/ToolWindowDragHelper.kt`. `getTargetStripeByDropLocation()` finds *"the stripe whose drop area
contains the screen point"*, and — the detail that matters — *"prioritises the initial anchor to avoid
overlaps"*, so a drag starting on the left rail resolves to the left rail while the areas overlap. Then:

```kotlin
if (dropToSide != null) { val half = if (targetStripe.anchor.isHorizontal) bounds.width / 2
                                     else bounds.height / 2
```

**The half is of the stripe's bounds, not of the window or of the region.** Feedback is two things: a
semi-transparent rectangle on the glass pane *"with bounds calculated from stripe geometry"*, and, new UI
only, a tooltip reading `UIBundle.message("tool.window.move.to.action.group.name") + " " + anchor`.

`RegionDropZones` bands the whole window instead. That is a real divergence and arguably the friendlier
rule — it is what made the drag land at all — but it is **ours**, and the "Move to …" wording is IntelliJ's
around a target computed differently. Say so rather than implying a port.

#### The outer splitter is not fixed — it follows a setting

`toolWindow/ToolWindowPane.kt`:

```kotlin
if (isWideScreen) { horizontalSplitter.innerComponent = verticalSplitter }
else { verticalSplitter.innerComponent = horizontalSplitter }
```

Two `ThreeComponentsSplitter`s, each `first/inner/last` = `TOP|doc|BOTTOM` and `LEFT|doc|RIGHT`. **Widescreen
mode makes the horizontal splitter outer**, so `LEFT`/`RIGHT` run the full height and `BOTTOM` stops between
them; otherwise `BOTTOM` spans the full width. `WorkbenchRegions` hardcodes the non-widescreen arrangement,
which is the default and which its javadoc already argues for — but it is a *setting* there, not a law.

`isSplit` decides component order within a splitter (`false` -> first, `true` -> last) and `sideWeight` sets
the proportion between them.

#### What we do not have, and whether it matters

| IntelliJ | Us | Verdict |
|---|---|---|
| `TOP` anchor | absent | A real gap. Rarely used; no rail slot exists for it either |
| `sideWeight` | absent | **Needed** the moment two containers share a region — §24.9 step 5. `RegionSide` (which half) shipped in step 4; the ratio between the halves did not |
| Split/non-split separator in a stripe | ✅ **done** | `StripeView.SEPARATOR_CLASS`, shown only when both halves are populated |
| Per-stripe drop areas | whole-window bands | Deliberate divergence, now recorded |
| Widescreen layout | fixed vertical-outer | Fine as a default; note it is a choice |
| `type` (floating/windowed), `autoHide` | absent | Still deliberately out — §23.5 |

### 24.9 Sequencing

| # | Work | Why here |
|---|---|---|
| 1 | **Region shell** — `WorkbenchShell`, `RegionHost` x3, `SplitView` frame, `DockArea` into `EDITOR` | Structure only; nothing moves between regions yet |
| 2 | **Region visibility + size + persistence**, session v4, and **F2b** — delete the four restoration tiers | They stop being separable here, which is what §23's correction records |
| 3 | **`ViewContainer` + header + tab strip**; `ProblemsPanel` becomes the first container with its views | Where F6 stops being a type and becomes visible |
| 4 | **`StripeView`** — containers, two groups, drag between stripes; **badges** | The `ActivityBar` rewrite | ✅ **DONE** |
| 5 | **Stripe splitting** — two containers per region | The `sideWeight` reversal, once regions and containers both exist | *model landed in step 4* |
| 6 | **`StatusBarView`** | Cheap, self-contained, retires the harness's hand-drawn line | ✅ **DONE** — plus `StatusBar.Align`, the one thing the service was missing: the plan wanted a left half and a right half and `text()` composed a single flat line |
| 7 | **Main toolbar** — widgets first, then the burger bar, then mnemonics and hover-switching | Largest, least structural, and every button is a command that already exists |

Steps 1–2 are the risky pair, because they move persisted state and layout together. Everything after is
additive and independently revertable.

---

# Part III — The chrome stacks, as ported

> Added after the status bar / notifications / diagnostics overhaul. Steps 9 and 10 marked these "DONE"
> when the *seams* existed; this part records what the substrate actually became once it was ported
> properly against the references, and — more usefully — what is still missing and why.

## 25. Status bar, notifications, diagnostics — the ports that landed

### 25.1 Status bar — VS Code's IStatusbarService

Ported from `vs/workbench/services/statusbar/browser/statusbar.ts` and `statusbarModel.ts`.

| Piece | Shape |
|---|---|
| `StatusBar.addEntry(entry, id, alignment, priority)` | returns a `StatusBarEntryAccessor` — the entry's identity **and** its lifetime |
| `StatusBarEntryAccessor` | `update(entry)`, `entry()`, `dispose()`; a `Disposable` |
| `StatusBarEntry` | record: `name`, `text`, `tooltip`, `command`, `kind` |
| `StatusBarAlignment` | `LEFT` / `RIGHT` |
| `StatusBar.setHidden(id, boolean)` / `allEntries()` / `idOf(accessor)` | the hide menu's model |

**The three rules that are easy to get backwards.**

1. **A handle, not a string key.** `set(id, text)` made withdrawal etiquette a writer had to remember and
   made two writers on one id a silent collision — the same failure `Workbench.onStatus` had, narrowed
   from "one slot for everyone" to "one slot per string". Two registrations are two entries whatever they
   are called.
2. **Higher priority is further LEFT, in both groups.** Not "closer to the outer edge", which sounds right
   and gets the right-hand group backwards. VS Code's own entries are selection 100, indentation 99,
   encoding 98, eol 97 and render in that sequence left to right.
3. **`name` is not `text`.** `text` is what the bar shows and changes constantly; `name` is what the entry
   *is*. A hide menu lists entries by `name`, because you cannot offer "hide 51:39" as a checkbox. The
   split looks redundant until something enumerates the bar.

`onDidChange` carries **nothing**: it used to carry the composed line, so `text()` walked every entry on
every write — including the caret readout on every selection change.

### 25.2 Notifications — VS Code's NotificationsModel plus IntelliJ's groups

| Piece | Shape |
|---|---|
| `Notifications.onDidChange` | one `Signal.Value<NotificationEvent>`, replacing four ad-hoc signals |
| `NotificationEvent.Kind` | ADDED / CHANGED / REMOVED / CLEARED |
| `Notifications.show(n)` | returns a `NotificationHandle` (`close`, `updateMessage`, `isOpen`, `onDidClose`) |
| `NotificationGroups` + `NotificationDisplay` | IntelliJ's per-group BALLOON / STICKY_BALLOON / LOG_ONLY / NONE, user-overridable |
| `Notification.withNeverShowAgain(id)` | keyed by *kind* of message, not instance |
| `Notification.withSecondaryAction` | VS Code's secondary actions, rendered quieter rather than hidden |

**The kind that was missing was REMOVED, and its absence was a live bug.** The history is bounded, so the
oldest is dropped once it is full — but with only "something arrived" and "everything went" to announce,
an eviction was *unannouncable*, and the panel's column grew past the history it was showing with no way
to find out. **Any new list-shaped model here should be checked against that: can it express every way an
entry can leave?**

CLEARED is one event rather than N REMOVEDs so a view rebuilds once instead of splicing a hundred times.

### 25.3 Diagnostics — VS Code's IMarkerService, both dimensions

| Piece | Shape |
|---|---|
| `DiagnosticSet.changeOne(owner, list)` | the **owner** dimension: an owner replaces only itself |
| `DiagnosticSet.changeAll(map)` | every owner at once, announcing once |
| `DiagnosticSet.remove(owner)` / `read(owner)` / `owners()` | |
| `Markers` | the **resource** dimension: `attach` / `detach` / `read` / `count` / `worst` / `resourcesWithProblems` |
| `Diagnostic.tags` / `related` | LSP's DiagnosticTag and DiagnosticRelatedInformation |

**Two things a future agent will be tempted to undo.**

- **`Markers` is an instance, never static.** It holds a listener on every set it indexes, so nothing it
  indexes can be collected; as a process global that is forever, and a suite that opens files without
  closing them accumulates every document it ever opened — it killed the test worker with a non-zero exit
  and no failing assertion. VS Code injects its marker service per window for the same reason.
- **`setAll` is kept and `add` was deleted**, deliberately. `setAll` replaces the *default owner's* slice,
  which its name understates; it stays because deleting it makes every single-producer document write
  `changeOne(DEFAULT_OWNER, ...)`, and a producer forced to name an owner it does not have will invent
  one — scattering keys that never collide and never merge. `add` went because "add" said nothing about
  replacing and it had no callers.

### 25.4 The Problems view — VS Code's markers view over the index

Two-level tree (file then problems) via `ProblemsTreeSource implements TreeDataSource<ProblemNode>`.
**Filtering lives in the source, not the view**, because it changes the tree's *shape*: a file whose only
error is filtered out has to stop being a row, and a view hiding rows afterwards would need to know that a
parent's visibility depends on its children's.

Scope is **IntelliJ's two tabs** (File with a count, Project Errors), not VS Code's Show Active File Only
menu row — the current scope has to be readable without opening anything, since an empty Problems panel
means two different things depending which scope you are in. The tabs sit on the container's title line
through a new seam:

    public interface HeaderContributor {   // com.crystalgui.ui.elements.workbench
        UIElement headerContent();          // asked once when mounted, for a LONE view only
    }

`ViewContainer` places it after the title. A view cannot reach its container and should not: it does not
know whether it is alone in one, sharing it, or in a container at all.

## 26. Bugs this overhaul found that outlive it

Engine-level, each invisible from every direction except the one that found it.

| Fault | Where | Why it matters beyond its fix |
|---|---|---|
| **`ListView` disposed its model subscription on detach**, and dispose is one-way | `ListView.tickFrame` | Every list and tree came back **deaf** after a dock panel was closed and reopened. A detach must be reversible; an explicit `dispose()` must not be |
| **`font-size: 7px` computed to null** | `FloatValue.doCompute` | A thrown parse is caught, logged and turned into null, so a declaration *degrades to nothing rather than failing*. **31 declarations across the shipped sheets were dead.** Check any new `StyleValue` against the units the sheets actually use |
| **A nested `contribute(getClass(), ...)` registers nothing** | `Workbench.registerCommands` | `UIElement` already reaches `registerCommands` through it, so the class is *already* in the contributor set. Register on the handed registry |
| **`CommandRegistry.run(id)` builds an EMPTY context** | any command with `enabledWhereData` | The guard evaluates against nothing and returns false. Always `run(id, CommandContext.of(element))` |
| **Folding from inside a press recycles the row being pressed** | `TreeView` | Now a **queue drained from the tick `ListView` already owns** (`requestToggle`). Three hand-rolled deferrals got it wrong three ways: a single-slot field drops a second click, `onLayoutChanged` never fires for a press that moves no geometry, and a private ticker's flag stops re-registering after a detach |
| **A child combinator silently stops matching when you nest** | `problemspanel > .__content__` | The tree lost flex-grow and the panel rendered empty *with a correct count above it*. The combinator is still deliberate — `.__content__` is a name three widgets share |

## 27. What is deliberately NOT built, and why

**Missing surfaces — the mechanism works and nothing reaches it.** This is the honest debt.

| Gap | API that exists | What it needs |
|---|---|---|
| **Problems text filter** | `ProblemsPanel.setTextFilter`, `ProblemsTreeSource.textFilter()` | a filter box in the view |
| **Notification group settings** | `NotificationGroups.register` / `setDisplay` / `registered()` | **zero production callers** — no group is declared, so every group silently takes the BALLOON default and the user override that justifies the design is unreachable. IntelliJ's Settings → Appearance → Notifications; `Preferences` is the natural host |
| **Un-silencing** | `Notifications.unsuppress` / `isSuppressed` / `suppressed()` | "Don't show again" calls `suppress` and **nothing calls the inverse** — a user can silence a message permanently with no way back. Ship this before more polish: one-way suppression is a trap |

**Decided against, with reasons — do not "fix" these without reading them.**

- **Persistence** for suppression and hidden entries. Session-only, by the user's decision.
- **Progress notifications** — nothing here runs long enough to report on: the file service is synchronous
  and the compiler finishes within a frame.
- **code-as-link** (LSP's codeDescription.href) — needs something that can open a URL; the engine has none,
  so the field would be data nothing could act on.
- **Debounced marker change** — VS Code debounces because its service is global and written from many
  places. `changeAll` already coalesces where it matters, and a *time-based* debounce needs a scheduler
  `core` deliberately does not have (`DiagnosticSet` is headless; a frame ticker needs a window).
- **Hide Excluded Files** in the view menu — filters against workspace exclude globs, which do not exist.
- **Sort By / Group by Inspection** from IntelliJ's eye menu — VS Code's filter set was ported instead.
  A knowing divergence, recorded so it reads as a choice.

---

# Part IV — §28. The menu bar: research and substrate plan

> **Status: RESEARCHED, NOT STARTED.** This is §24.6's other half. The toolbar is a row of buttons; the
> menu bar is a *contribution surface*, and almost all of the work is in the substrate rather than the
> widget. Read §28.3 before writing any UI.

## 28.1 The thesis

**A menu bar is not a widget with a list in it.** In both references it is a *query over a registry*: a
menu id is a well-known location, anything may contribute to it, and the bar is a renderer that asks
"what is in `MenubarFileMenu` right now, for this context?" The widget is the last and smallest part.

That matters here because the engine already has most of the registry. Building the bar as a widget with
a hard-coded item list would work, look identical on day one, and be the thing every future contribution
has to be threaded through by hand — which is precisely what steps 7–10 were spent undoing for editor
types, the Inspector and notifications.

**The test for "seamless rather than parallel": can a contribution add a File-menu item without the menu
bar knowing it exists?** Today: partly. See §28.3.

## 28.2 Reference research

### VS Code — `vs/platform/actions/common/actions.ts`

The menubar is `MenuId` constants queried through `MenuRegistry`:

`MenubarMainMenu`, `MenubarFileMenu`, `MenubarEditMenu`, `MenubarViewMenu`, `MenubarGoMenu`,
`MenubarSelectionMenu`, `MenubarTerminalMenu`, `MenubarDebugMenu`, `MenubarHelpMenu`,
`MenubarAppearanceMenu`, `MenubarLayoutMenu`, `MenubarPreferencesMenu`.

`MenuRegistry.appendMenuItem(MenuId, IMenuItem | ISubmenuItem)` appends to a linked list per id.

- `IMenuItem`: `command`, `alt` (the Alt-key variant), `when` (a context-key expression), `group`,
  `order`, `isHiddenByDefault`.
- `ISubmenuItem`: references another `MenuId` via `submenu`, wrapped by `SubmenuItemAction`.
- Sorted by **group first, then `order`** within it.
- **Group names carry their own sort key**: `1_new`, `2_open`, … The number is the section's position and
  the word is what the section means. Separators are drawn *between groups*, never declared.

### IntelliJ — `ActionManager` / `ActionGroup`

Same idea, different vocabulary: actions are registered against group ids (`MainMenu`, `EditMenu`,
`ViewMenu`…) with `<add-to-group group-id="…" anchor="after" relative-to-action="…"/>`. A `DefaultActionGroup`
holds children and `Separator` is an actual action. `ActionGroup.getChildren` may be **computed per
invocation**, which is how Recent Files and the Window list exist at all.

**In the New UI the bar collapses to a burger** when the window is narrow or the toolbar is in compact
mode — the same menus, a different presentation. That is the target the plan names.

## 28.3 What CrystalGUI already has — and the six gaps

**Already present, and genuinely the hard part:**

| Piece | Where |
|---|---|
| `MenuId` — interned, well-known locations | `core.command.MenuId` |
| `MenuId.Placement` with `group` + `order` | contributed via `Command.menu(menu, group, order)` |
| **Contributed submenus** | `MenuId.submenu(child, title, group, order)` and `nestedIn(parent, …)` |
| Group-then-order sorting | `CommandRegistry.menu(MenuId, CommandContext)` |
| Context-resolved enablement | `Command.enabledWhen` / `enabledWhereData` over `DataContext` |
| A real menu widget with checkable rows and submenus | `ui.elements.Menu` / `MenuItem` (`addCheckableItem`, `addSubmenu`) |
| Keymap + accelerator display | `Command.binding`, `MenuItem.setAccelerator` |
| Popover placement, light dismiss, Escape, focus trap | `Popover` / `AnchoredPlacement` |

**The gaps, in the order they will bite:**

- **G1 — `CommandRegistry.menu()` returns a flat `List<Command>`, so group boundaries are lost.**
  It sorts by group then order and then throws the grouping away, so a builder cannot draw a separator
  between `1_new` and `2_open`. Every menu in the app is currently one undivided run of rows.
  *Fix:* return a grouped structure (`List<MenuSection>` or `Map<String, List<Command>>` in sorted order).
  This is the single most load-bearing change and everything else composes over it.

- **G2 — a disabled command is OMITTED from a menu, not greyed.**
  `menu()` skips `!command.isEnabled(context)`. That is right for a context menu — both references hide
  inapplicable rows there — and **wrong for a menu bar**, where File → Save must be present and grey so
  the menu has a stable shape. A menu whose rows appear and vanish cannot be learned.
  *Fix:* return the placement with an `enabled` flag and let the renderer decide; a context menu keeps
  filtering, a menu bar greys.

- **G3 — a contributed command cannot declare that it is a TOGGLE.**
  `MenuItem.setCheckable`/`setSelected` exist on the widget, but nothing on `Command` says "this is
  checkable and here is its current state". VS Code has `toggled` on the command; IntelliJ has
  `ToggleAction`. Without it, View → Show Problems cannot show a checkmark, and every toggle in the bar
  has to be built by hand outside the contribution system.
  *Fix:* `Command.toggledWhen(Predicate<DataContext>)`, read by the renderer.

- **G4 — no mnemonics.** Alt+F opening File, and `F` underlined in the label. `KeyChord` handles chords;
  a mnemonic is a different thing (a letter within a label, active only while the bar has focus or Alt is
  held). Needs a mnemonic notion on the menu title and an Alt-driven focus mode.

- **G5 — no `when` expression parser.** Visibility is a Java lambda, which is fine for in-repo
  contributions and impossible for a *serialised* one. Already named as phase-two work in §11/§20.2. Not
  a blocker for the bar; it is the blocker for a data-driven bar.

- **G6 — no dynamic / computed groups.** Recent Files, and a Window list of open editors, are N rows
  generated at open time. `MenuId` placements are static per command. IntelliJ's `ActionGroup.getChildren`
  is computed per invocation for exactly this.
  *Fix:* a `MenuContributor` SPI — `List<Command> itemsFor(MenuId, DataContext)` — queried alongside the
  static registry. Also closes `ContextMenu`'s ad-hoc-item gap (§27) for free.

**A note on what is NOT missing:** the `Menu` widget, popover behaviour, submenu opening, accelerators and
checkable rows are all done. It is tempting to read "no menu bar" as "no menu machinery"; the opposite is
true, and the work is almost entirely in `core.command`.

## 28.4 The shape to build

```
MenuBarView (a Part, mounted in the toolbar row — §24.6)
  └── one MenuBarTitle per top-level MenuId       "File"  "Edit"  "View"  ...
        └── opens a Menu built by MenuBuilder.build(menuId, context)
              └── sections from CommandRegistry.sections(menuId, context)   [G1]
                    ├── rows: enabled or greyed                            [G2]
                    ├── checkable rows                                     [G3]
                    ├── submenu rows (already expressible)
                    └── rows from MenuContributor                          [G6]
```

`MenuBuilder` is the piece worth extracting: **`ContextMenu` should become one caller of it**, not a
parallel implementation. Today `ContextMenu` builds rows from `MenuId` itself; if the bar grows a second
builder, the two will disagree about separators and greying within a release.

**Hover-switching**: with one menu open, hovering another title switches to it without a click. This needs
the bar to own "which title is open" rather than each title owning its own popover — the same reason
`Menu` owns submenu opening rather than each `MenuItem`.

## 28.5 The menus this project actually needs

Six top-level menus. Not VS Code's twelve: Terminal, Debug and Go have no subject here, and folding their
few relevant items into others is better than shipping empty menus.

| Menu | Sections (group ids follow VS Code's `N_name` convention) | Items available *today* |
|---|---|---|
| **File** | `1_new`, `2_open`, `3_save`, `4_close`, `9_exit` | New File / New Folder (`explorer.*`), Open, Save, Save All, Close Tab (`dock.closePanel`), Recent Files **[G6]** |
| **Edit** | `1_undo`, `2_clipboard`, `3_find` | Undo / Redo (`UndoCommands`), Cut / Copy / Paste, Find, Find Next / Previous (`editor.*`), Copy Path (`explorer.*`) |
| **View** | `1_appearance`, `2_toolwindows`, `3_editor` | Show Problems / Notifications (`workbench.show*`) **needs [G3] for checkmarks**, Project, Toggle Maximize (`dock.toggleMaximize`), Split Right / Down (`dock.split*`), Command Palette (`workbench.showCommands`) |
| **Graph** | `1_nodes`, `2_layout`, `3_compile` | Add Node, Frame Selection, Delete, Group (`GraphCommands`), Recompile. The one menu specific to this application, and the reason a menu bar is worth having |
| **Window** | `1_panes`, `2_editors` | Next / Previous Tab, Focus Next / Previous Group (`dock.*`), then a **computed list of open editors** **[G6]** |
| **Help** | `1_about` | About, Documentation. Thin, and honest about it |

**Sizing:** ~35–40 items across six menus. That is small enough that the temptation to hard-code it is
real, and large enough that hard-coding it would be the wrong call — the Graph menu alone will grow with
every node feature, and it is contributed from `com.crystalgui.graph`, which the shell must not import.

## 28.6 Sequencing

1. **G1 — sections** (`CommandRegistry.sections`). Pure model, testable headlessly, unblocks everything.
2. **G2 — greying** (placement carries `enabled`; `ContextMenu` keeps filtering, the bar greys).
3. **G3 — `Command.toggledWhen`**, so View's toggles are contributions rather than special cases.
4. **`MenuBuilder`**, with `ContextMenu` refactored to call it. **Do this before the bar exists**, or there
   will be two builders.
5. **`MenuBarView`** + hover-switching, mounted per §24.6.
6. **G6 — `MenuContributor`** for Recent Files and the Window list; also closes §27's ad-hoc-item gap.
7. **G4 — mnemonics.** Last: it is polish, and it needs the bar to have focus behaviour first.

**G5 (`when` parser) stays parked.** Nothing above needs it, and it only pays for itself once menus are
declared outside Java.
