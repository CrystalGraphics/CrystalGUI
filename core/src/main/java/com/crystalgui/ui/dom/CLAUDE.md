# `ui/dom` — the node tree

> Loads itself: Claude Code reads this file the first time an agent reads any file in this folder or below. Moved verbatim from [`AGENTS.md`](../../../../../../../../AGENTS.md), which keeps the rules every session needs.

## Stack 1: the node tree — `ui/dom`

**A node has identity, attributes, children, a shadow root and events. It has no layout, no paint and
no geometry** — those are the box tree's, one stack down. That split is the whole point of the three-tree
design: the class it replaced carried 166 public members and answered every question about a widget,
which is why a change to any one of them could break the others.

### `UINode`

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
| Subscriptions | `whileConnected(supplier)` and `onConnected(runnable)` — **declare them in the constructor**; the engine re-subscribes on every attach and drops them on every detach |
| Styleable | everything the cascade asks: id/classes/type, the light parent and the COMPOSED parent (two different questions), nine state predicates, the shadow host and part name |
| Interaction state | `setFocused`/`setHovered`/`setPressed` — the services write it, the cascade reads it |
| Focus | `setFocusPolicy`, `focusable()`, `tabbable()`, `delegatesFocus`, and `setRetainsFocus` — a subtree a press may not take focus OUT of. The engine's default is the web's: a press that finds no click-focusable ancestor CLEARS the owner, so pressing a dialog's caption left the window with no focus at all. It still defers to anything click-focusable inside, so a list row keeps taking its own press |
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

### `UIDocument`

The root, and the owner of everything per-surface: the frame thread (`require`, asserted at every
mutation entry, per document), the `StyleEngine`, the four services, the top layer, the id index, the
tree observer, and document-level `DataProvider`s.

- `frame(delta, w, h)` is one whole frame: animation → style → layout → paint → the input diff.
- `layout(w, h)` alone, for a geometry assertion that needs no motion.
- `promote`/`demote` record top-layer membership **on the node**, not on a box — a box is destroyed and
  rebuilt whenever its subtree is hidden or restructured, so a flag written onto one is lost.
- `addDataProvider` — document-level, because the consumer of a key is often not an ancestor of the
  thing that asks.

**`DocumentDriver` is how a host runs one** — on its own thread, on a sequence in lockstep
(`-Dcrystalgui.ui.sequence=true`), or recording on a sequence while the render thread presents
(`-Dcrystalgui.ui.async=true`). Threading is the engine's: a host attaches the driver to an empty
document, builds the tree through `driver.run`, and sends its frame (`driver.frame(delta, w, h, painter)`),
its input and anything else that touches the tree through it, running the same code in every mode.
`HostSession` and the harness scenes are its hosts, and **the harness runs async by default**
(`-Dcrystalgui.ui.async=false` for the old path). Four scenes pin `Mode.INLINE` because they measure or
photograph exact frames: `cgui-text-stress`, `cgui-timeline`, `cgui-text-gamma`, `cgui-visual-layers`. A
capture or script counts `driver.presentedFrames()`, not frame numbers: async presents nothing until the
first frame is recorded.

**UI code reaches the game through `HostThread`** (`core.async`), never directly: `HostThread.CLIENT.run(...)`,
`HostThread.SERVER.call(...)` answering a `Reply` on the UI's thread, and `UINode.extract(read, use)` for a value read
every frame and used when it changes. Instant where the named thread is the one framing the document; otherwise one
hop, the answer landing at the next frame. Each loader answers the threads through `HostServices`; the guide is
`docs/CGUI_BUILDING_UIS.md` §7c.

### `ShadowRoot` and `UISlot`

`attachShadow()` gives a widget a tree of its own. Its parts are addressable from outside only through
`::part(name)`; an ordinary selector cannot reach in, though an INHERITED value still does — that is the
DOM's behaviour and not a leak. A `UISlot` is where a caller's content lands, and **a widget that takes
content needs one**: a light child of a shadow-hosting node with no default slot is in no composed tree
at all — no box, no paint, no promotion, and nothing anywhere reporting a problem.

`ShadowRoot.parent()` is null by design; `host()` is the way up, and `commandParent()` answers the host
so a command invoked inside a composite can still resolve outward.

### `Name` and `UIElementRegistry`

A kind's tag is a `Name` declared as a `NAME` constant **on the class it names**, and a subclass
inherits its parent's unless given its own — so a widget meant to be extended takes a `(Name, ...)`
constructor. `UIElementRegistry.bootstrap()` runs every `NodeKinds` service once, which makes the
registry's contents a function of the classpath rather than of a hand-written list.

### `TreeObserver` — the edit script

Four callbacks over the seam in `ui/dom`: `inserted` (with an index), `removed`, **`moved`**,
`attributeChanged`, `inlineStyleChanged`, `stateChanged`. A move is ONE event and never a
remove-then-insert, because a receiver applying those in order deletes the node — losing the instance
and everything in it — and then has nothing left to move.

**A shadow tree is invisible to the observer by construction**: shadow content is never a light child,
so a move across the boundary reaches the mirror as what the light tree saw. State is the exception —
`notifyStateChanged` walks OUT of every enclosing shadow tree to the nearest node the far side has
heard of, because a peer cannot act on a part it was never told about.
