# The UI rewrite — one plan over both audits

**Status: plan, 2026-08-28. Nothing implemented.** This knits `plan_ui_network_audit.md` (the wire,
sessions, contracts, presentation) and `plan_engine_core_audit.md` (the three-tree engine) into one
ordered set of milestones. Each audit stays the reference for *why*; this document is the reference
for *what, in which order, gated by what*. Where the two audits' step lists disagreed on order, this
resolves it, and says how.

---

## 0. The knitting problem, and the seam that solves it

The network audit's first step is "the mirror with stable ids, on today's engine". The engine audit's
ninth step is "the networking rewrite as the mirror over the new node tree". Taken literally that
writes the mirror twice, and the second time is after months of engine work — so either the live
networking defects (identity changes never sent, forged events, one viewer silencing all) wait for
the engine, or the mirror is thrown away.

The resolution is a **seam**: the mirror observes a *tree contract*, not a class. Define that
contract first — stable node identity, ordered children, attribute/inline-style/state change
notifications, a contract per node kind — implement it *minimally* on today's `UIElement` (which is
Tier 2's "networking off the node"), build the mirror against it once, and have the new `ui.dom`
node tree implement the same contract natively when it lands. The mirror is written once; the
engine swap underneath it is a port of the seam's implementation, not of the mirror. Everything
else in both plans orders itself around that seam.

Two more principles this plan enforces:

- **Every milestone ships something usable in game and deletes something.** The user's condition is
  no bloat; a rewrite that only adds until a distant cutover is how two engines end up living side by
  side forever. The deletion ledger (§4) is checked per milestone.
- **Prove the mechanism before the design.** Two spikes (M0) are cheap and each could change the
  plan: the Taffy measure-function fix and the Shadow-DOM-in-Java prototype.

---

## 1. Decisions — **taken 2026-08-28: every recommendation below was accepted as written.**

D3 remains contingent on spike S1 by its own terms. The table is kept as the record of the reasoning.

| # | Decision | Recommendation | Why it blocks |
|---|---|---|---|
| D1 | **Seam-first interleave** (this plan) vs network-first-then-engine vs engine-first | Seam-first | The other two write the mirror twice or delay the live fixes by months |
| D2 | **Package strategy**: new `com.crystalgui.dom` / `render2`-style packages beside the old `ui/` during the port, old deleted at the end (strangler) — vs in-place mutation of `UIElement` | Strangler, with the old engine runnable until M6 ends | 76k lines port; in-place would leave the engine unrunnable for the whole duration |
| D3 | **Layout engine**: vendor the Taffy Java port into the repo and fix its measure path — vs replace with our own flex/grid | Vendor and fix, **contingent on spike S1** | If the fix is not tractable, the box tree's measure protocol has no engine under it |
| D4 | **Taffy's five CSS-default divergences** (`flex-direction: column`, `flex-shrink: 0`, `min-size: 0`, `align-content: flex-start`; `border-box` matches Taffy and stays) | Adopt CSS defaults at M6 while every UA sheet is being ported anyway, with a governance test that flags every element relying on the old default | Deciding at M6 rather than M5 means the box tree is written CSS-correct and the port pays once; deciding never means the 13 flex-trap rows stay forever |
| D5 | **Element naming**: registered namespaced names (`crystalgui-machine`, derived from the `UiType` id; widgets `crystalgui-button`) vs today's simple class names | Namespaced, with a client-side type → class manifest | N1/N2; also what makes two mods' `EnginePanel` not collide |
| D6 | **Identity scheme on the wire**: described elements only, server counter after open, live snapshot carrying ids for late viewers | As stated (Appendix A) | Fixed before the seam is written, since the seam exposes it |
| D7 | **Contracts**: static `State<T>`/`Event<T>` constants on the widget class registered beside its name, engine derives read/write/report/validate — vs annotations vs codegen | Static constants + registry (no build step, Java 8 bytecode, readable in a class file) | Every widget touches it twice (M1 on old widgets, M6 on new) so the shape must be settled |
| D8 | **Close veto**: `requestClose` → client asks content → answer; eviction is `RETENTION`, never `CLIENT` | As stated | Changes `WindowMount` and `ServerWindow.CloseReason` — public surface |
| D9 | **Rename `layout(M)` → `build(M)`; collapse `bound()`+`client()`** | Yes, at M3, in one commit with the panel migration | Public authoring surface; do once |
| D10 | **Hosts in scope**: 1.7.10 only during the rewrite; `mc1201` stays out | 1.7.10 only | Every milestone's in-game check is `runClient`/`serverSmoke` |
| D11 | **Template language** over the description format | No — door left open, not in this plan | Scope |
| D12 | **Test policy**: behaviour tests survive and are the acceptance; structure-asserting tests (child lists, internal children, positional ids) are rewritten at the milestone that changes the structure | As stated | Sets expectations for the 162 + 176 + 87 tests |

---

## 2. Milestones

Dependencies are listed as `after:`. Sizes are relative (S/M/L/XL), not dates.

### M0 — Spikes and the seam contract · S · after: D1–D7

Two throwaway probes and one interface.

- **S1 Taffy measure fix.** In the checked-in port, fix the flex-wrap cross-size `NaN` path
  (`FlexboxComputer.java:1469` per `UIText`'s note) and prove it with `UIText` on a real measure
  function under a `flex-wrap: wrap` ancestor in the harness. Outcome decides D3. If it fails, the
  box tree (M5) needs a different engine and the plan's size changes.
- **S2 Shadow-DOM-in-Java.** One composite (`Button`: label + icons) as node + shadow root + `::part`
  styling, rendered in the harness beside the old one, with focus retargeting and a `::part(label)`
  rule applied. Outcome decides whether the M5/M6 shape is right before 54 composites are ported.
- **The seam.** `ui.dom.TreeSource` (name provisional): stable node identity (`nodeId`, allocated by
  the observer, not stored positionally), light-DOM child iteration, an observer with
  `inserted/removed/moved/attributeChanged/inlineStyleChanged/stateChanged`, and `contractOf(node)`.
  Implemented on `UIElement` by moving `networkId`, `reportedEvents`, `observer`,
  `describedChildren*` **off the node** into an adapter — Tier 2 (a). Thread assertion at the same
  entry points — Tier 2 (c).

**Ships:** nothing user-visible. **Deletes:** the three networking fields on `UIElement`.
**Accepts:** S1 and S2 measured in the harness; the seam has a headless test suite that the old
engine passes and the new one (M5) must pass unchanged.

### M1 — Contracts on the widgets · M · after: M0

`WidgetContract`: `State<T>` and `Event<T>` constants with type, default, validation and default
rate policy, registered with the element's namespaced name (D5, D7). The engine derives
`writeState`/`readState`, the description fields, report wiring and server-side validation from it.
All twelve state-carrying widgets get contracts; the five that could not report get their events
(`Dropdown.SELECTED`, `TabView.SELECTED`, `ColorSelector.CHANGED`, `SplitView.RESIZED`,
`Tab.SELECTED`); the missing kinds (`key`, `pointer`, `wheel`, `focus`/`blur`, `drag`/`drop`,
`contextMenu`, `closeRequested`) are defined, wired on the widgets that opt in.

**Ships:** no wire change yet; the old sessions read contracts through a shim so nothing breaks.
**Deletes:** every hand-written `writeState`/`readState` on the twelve widgets; `addReportedEvent`.
**Accepts:** a coverage test enumerates every `ui.elements.**` class and fails on one that is neither
contracted nor marked `LocalOnly` with a reason; contract round-trip tests per widget.

### M2 — The mirror · L · after: M0, M1

Appendix A of the network audit, built once against the seam: stable ids (D6), `insert/remove/move`
ops from the observer, attribute and inline-style deltas, state deltas from contracts, per-op
described-count integrity, live snapshot with ids for late viewers and hidden re-delivery, deltas
queued per viewer while hidden, `ui/treeOps` replacing `ui/treeDelta`, `EnvelopeCodec.VERSION`
bump. `ServerUiSession`/`ClientUiSession` are replaced by `ServerMirror`/`ClientMirror` (names
provisional) with the public method names kept where they were right.

**Ships:** the live defects fixed in game — disabling a button after open works; adding a row keeps
every sibling instance; hidden windows keep their instances; identity and inline style travel.
**Deletes:** `NetworkIds` (positional), `ui/treeDelta`, `dirtyIdentity`, `expectedElementCount` as a
global check, the `instanceof` switch in `wireReportedEvents`, `shouldSuppress`'s reason to exist
(rate policy replaces it).
**Accepts:** `assertSame` on the engine panel across a sibling insert; a server move keeps the
client instance; two viewers agree on every id after a reshape; a hidden-then-reshaped window comes
back with its instances; an idle window is silent; the mutation-checked count test.

### M3 — Events, validation, authoring surface · M · after: M2

Typed events on `ServerScope` (`io.on(el, Slider.VALUE, (viewer, v) -> …)`), rate policy in the
description, server-side validation by the widget's contract (disabled/inert/kind/payload), viewer
in every context, per-connection refusal counter with a threshold, per-viewer visibility, `call()`
→ `callViewer` only. The authoring rename (D9): `layout` → `build`, `bound()`+`client()` → one
`client(io)`, `closed(CloseReason)`. `MachinePanel`/`EnginePanel` migrated as the worked example.

**Ships:** forged events refused in game; two players on one machine attributed correctly.
**Deletes:** `UiEventKinds`, the string-kind `on(...)`, `UiEventContext`'s viewer-less shape,
`bound()`.
**Accepts:** NaN and out-of-range payloads clamped by the widget, disabled-element events refused
and counted, two-viewer attribution, the seven tear-out behaviours re-pinned as plain reparent
tests (they pass because M2 made reparenting free — no `Detached`).

### M4 — Windows, view commands, sheets, limits · M · after: M2, M3

View commands (`focus`, `scrollIntoView`, `showDialog`/`hideDialog`, `openMenu`, `tooltip`,
`setTitle`, `setIcon`, `geometryHint`, `notify`, `requestClose`), the close veto wired to
`WindowFrame.discardGuard` (D8), eviction as `RETENTION`, visibility reported on `suspendDesktop`,
`openedBy` → `setOwnerWindow`, `WindowMount` shrunk. Sheets scoped by **selector rewrite** (the
interim until M5's native scopes) and refcounted by hash across windows. Caps: windows per
connection, elements and bytes per description and per sheet, cache sizes; rate limits per method;
capability negotiation at open.

**Ships:** a server can focus, title, dialog, notify and ask-before-close; sheets no longer leak or
bleed; a hostile server or client cannot take the other side down.
**Deletes:** `CgUiWindowMount.sheetSupply`'s global `addStylesheet`, `contentReplaced`, the version
refusal as the only skew handling.
**Accepts:** hostile-description fuzzing, skew fixtures (client missing a tag / a field), the sheet
refcount test (open twice, close twice, engine sheet list unchanged), eviction reason test.

### M5 — The three-tree engine core · XL · after: M0 (S1, S2), D2–D4

`ui.dom` node tree (identity, attributes, children, shadow roots, slots, composed iteration,
retargeting, lifecycle callbacks, thread assertion) implementing the M0 seam natively; the style pass
(cascade kept, `@scope` semantics, `font-size` inherits, rule-level invalidation on a bad selector,
immutable `ComputedStyle`); the `ui.render` box tree (Taffy under the box per D3, hosts, measure
protocol, one-pass layout, world matrices computed never painted, paint and hit-test over boxes);
services: input with a mode stack and DOM `stopPropagation`, one focus algorithm over navigation
scopes with `delegatesFocus`, one animation service, freeze-not-detach lifecycle. Built in new
packages beside the old engine (D2), driven by the seam's test suite and a headless `UIWindow`
equivalent, with the harness able to run either engine.

**Ships:** nothing user-visible until M6 — this is the one milestone that adds without shipping,
and it is why S1/S2 gate it.
**Deletes:** nothing yet (D2).
**Accepts:** the seam suite passes on the new tree unchanged; the 38 focus rows and the 20
hit-test rows become acceptance tests against the new services; layout runs in one pass on the
gallery's trees.

### M6 — The port · XL · after: M5

In dependency order, each step keeping the game runnable on the old engine until the last:
leaf widgets (Button, Checkbox, Switch, Slider, ProgressBar, UIText, TextField) as node + skin;
composites (ScrollerView, SplitView, TabView, Dialog, Popover/Menu/Dropdown, Tooltip,
ListView/TreeView/TableView, GraphView, CanvasView, the editor's host); the desktop (`WindowFrame` as
shadow + content slot, hosts for owned/pinned/top, freeze instead of detach, the animation service);
workbench, dock, chrome, editor widgets; every `ua/*.css` `__part__` selector → `::part()`; D4 applied
with its governance test; native `@scope` replaces M4's selector rewrite. Then the cutover: the
harness and `CgUiScreen` on the new engine, and the deletion.

**Ships:** the whole application on the new engine.
**Deletes:** old `ui/` core, `TopLayer`, `mirrored`, the internal-child flag and its 208 sites'
old form, `UIFrameTicker` as a widget interface, `importantPipeline` writes from the engine,
`WindowChrome`'s move-and-remember-the-flag, the four promotion special cases, the two coordinate
chains, `Disposer` as a second ownership tree, `UiThread` as a marker.
**Accepts:** every surviving behaviour test green; the invariants table pruned of every row that
no longer describes anything (the count is the metric).

### M7 — Networking completeness on the new engine · M · after: M4, M6

Virtualised collections on the wire (`ListView`/`TreeView`/`TableView` rows as a stream with
`rows{from,to}`), workbench citizenship (a `DockPanelDescriptor` kind mounting a networked root into a
`ViewContainer`, session restore by key), contracts for the element classes M1 marked local-only
where a wire form now makes sense, client-local children in `client(io)`.

**Ships:** inventories, logs and file lists over the wire; a server panel as an editor tab or tool
window.
**Deletes:** the `LocalOnly` markers that were provisional.
**Accepts:** a 10,000-row list over loopback with bounded traffic; a networked panel docked, undocked,
restored across a reconnect.

### M8 — Hardening and docs · S · after: M7

Two-viewer fixtures for every server-side feature, `clientSmoke` (every contracted widget round-trips
over loopback), the networking primer and `CGUI_SERVER_AND_SERIALIZATION.md` rewritten to the new
protocol, `AGENTS.md`'s invariants table reduced to what still holds, `plan_ui_host.md` closed out.

---

## 3. Dependency view

```
D1–D7 ──► M0 (S1, S2, seam) ──► M1 (contracts) ──► M2 (mirror) ──► M3 (events, authoring) ──► M4 (windows, sheets, limits)
                  │                                                                                     │
                  └──────────────────────────► M5 (engine core) ──► M6 (port) ──────────────────────────┴──► M7 ──► M8
```

M1–M4 run on the **old** engine through the seam and ship live fixes early. M5 starts as soon as the
spikes pass and can proceed in parallel with M2–M4 (different packages by D2). M6 is the single
cutover; M7 needs both lines.

---

## 4. Deletion ledger

The user's condition, checked per milestone. Nothing in this table survives the milestone that
names it.

| Milestone | Deleted |
|---|---|
| M0 | `UIElement.networkId`, `reportedEvents`, `observer`, `describedChildren*` (moved to the seam adapter) |
| M1 | hand-written `writeState`/`readState` ×12, `addReportedEvent`/`getReportedEvents` |
| M2 | positional `NetworkIds`, `ui/treeDelta`, `dirtyIdentity`, global count check, `wireReportedEvents`' switch, `ServerUiSession`/`ClientUiSession` internals |
| M3 | `UiEventKinds`, string-kind `on`, viewer-less `UiEventContext`, `bound()`, `layout(M)` |
| M4 | global sheet application, `contentReplaced`, VERSION-only skew handling |
| M6 | old `ui/` core, `TopLayer`, `mirrored`, the internal flag, `UIFrameTicker` interface, engine `importantPipeline` writes, `WindowChrome` flag juggling, promotion special cases, the second coordinate chain, `Disposer` as a tree, `UiThread` marker |
| M7 | provisional `LocalOnly` markers |
| M8 | invariants rows that describe nothing |

---

## 5. What "ready for implementation" means, concretely

Implementation can start the day D1–D7 are answered, with M0. M0 is deliberately small and
partly disposable: two spikes whose outcome can still change D3 and the shape of M5/M6, and one
interface that both lines depend on. Nothing after M0 should start before S1 and S2 have been run,
because the two things this plan cannot know from reading — whether the layout engine can be fixed
under us, and whether Shadow DOM in a Java 8-bytecode engine costs what the audit assumed — are
exactly what they measure.
