# The new engine — what replaced what

**Audience: someone who knows the old engine and needs to find the thing they used to call `X`.**

The M6 rewrite deleted one class that did four jobs and replaced it with three trees and four
services. Most of the confusion in review is not "how does this work" — it is "where did that go",
followed by an old habit that is now silently wrong. This document is the lookup table and the list
of habits.

> The widget layer is not covered here; that is `CGUI_WIDGETS.md`. This is the engine underneath it:
> input, focus, the frame, geometry, identity, the wire, lifecycle.

---

## The one paragraph you need first

`UIElement` was the DOM, the layout box, the paint surface and the hit-test target at once. It is now
**three trees**, each derived from the one above it:

| Tree | Package | Holds | Rebuilt |
|---|---|---|---|
| **Node tree** | `ui/dom` | identity, attributes, children, shadow roots, events | never — it is the source |
| **Box tree** | `ui/box` | geometry, transforms, scroll, hit-testing, paint order | on frames the node tree reports a structure change |
| **Style** | `style/` | the cascade, shared by both engines behind `Styleable` | per dirty element, per frame |

A `UINode` has **no geometry**. Ask its `Box`. A `Box` may not exist — see the traps.

The frame is **`animation → style → layout → paint → input`**, driven by
`UIDocument.frame(deltaSeconds, width, height)`.

---

## The lookup table

| You used to write | Now write | Notes |
|---|---|---|
| `UIElement` | `UINode` + its `Box` | identity and structure on the node; geometry on the box |
| `UIWindow` | `UIDocument` | the document **is** the root; there is no separate root element |
| `Ui.of(root)` | *(nothing)* | a two-field holder with no job left |
| `window.getStyleEngine()` | `document.styles()` | |
| `window.init(w, h)` | `document.frame(delta, w, h)` | the size arrives with the frame |
| `window.setUiScale(f)` | `document.boxes().setUiScale(f)` | one definition, on the root transform |
| `element.getRuntimeCache().getX()` | `node.box().x()` | **parent-relative now** — see traps |
| `element.screenToLocal(x, y)` | `node.toLocal(x, y)` | **origin moved** — see traps |
| `UIInputHandler` | `Input` + `Focus` + `Drag` + `Lifecycle` | `ui/service`, four objects on the document |
| `UIDragController` | `Drag` | an `InputMode` pushed on a stack |
| `UITreeTraversal` | `Focus` | `firstFocusableIn`, `nextTabbable`, … are methods on the service |
| `UIFrameTicker` + `registerTicker` | `document.animation().every(owner, hook)` | owned by a node, not a registry |
| `TopLayer` | `document.promote(node)` / `demote(node)` | recorded on the node; the box tree applies it |
| `element.markAsInternal()` | `node.attachShadow()` | a shadow tree, addressed by `::part()` |
| `ElementRegistry` | `UINodeRegistry` | **and a `NAME` on the class** — see traps |
| `UIDescriptionCodec` | `UINodeMirror` | describe/decode plus state, attributes and inline style |
| `ElementTreeSource` | `UINodeTreeSource` | the same `TreeSource` seam, over the node tree |
| `ElementNodeMirror` | `UINodeMirror` | the only mirror |
| `UIResizer` | `resize` in CSS | applied by the engine; nothing to install |
| `element.hide()` (detach) | `document.lifecycle().freeze(node)` | tree intact, boxes dropped, hooks dormant |
| `Disposer` as a tree | `document.lifecycle().destroy(node)` | |
| `UiThread.markCurrent()` | `document.markFrameThread()` | **per document**, so headless trees are free |
| `CgUiPaintContext.mirrored` | `document.boxes().mirror(subtree, host)` | a second box with its own matrices |

---

## Input, focus and gestures — `ui/service`

`UIInputHandler` was 962 lines doing dispatch, hover, focus, capture, drag and four hard-coded
gestures. It is four objects, reached from the document:

```java
document.input()      // platform sink, hit test, three-phase dispatch, capture, cursor
document.focus()      // one owner, traversal, focus scopes, modality
document.animation()  // timelines and per-frame hooks
document.lifecycle()  // freeze / thaw / destroy
```

**A live interaction is a mode, not an `if`.** The old handler opened `consumeKeyboardEvent` with a
ladder of four special cases — a live drag, the window switcher, keyboard move, a modal. Now each
pushes an `InputMode`:

```java
Drag.start(source, surfaceX, surfaceY, listener);   // pushes itself
input.pushMode(myGesture);                          // anything else
input.popMode(myGesture);
```

The service names no gesture; `ModeStackTest` reads its constant pool to prove it.

**Focus is a service, not a field.** `focusable`, `tabbable`, `isInert`, `blockingModal`, `scopeOf`,
`pushModal`, `moveTabFocus`, `firstFocusableIn` and friends all live on `Focus`. The `FocusPolicy`
enum is the **same class** the old engine used — deliberately, because two copies of an enum whose
four values are documented at length is how two definitions drift.

**Programmatic focus rings; pointer focus does not.**

```java
focus.requestFocus(node);         // keyboard — draws the ring
focus.requestPointerFocus(node);  // a click — no ring, no scroll
```

---

## The frame and per-frame work

`UIFrameTicker` was an interface a widget implemented plus a one-way `HashSet` registry: registration
could not be undone, and a ticker was stopped only by returning `false`. That made "return false once
your element has left the tree" a rule every ticker had to remember, and the one that forgot was the
hidden editor that kept compiling.

```java
document.animation().every(this, this::tickFrame);        // boolean frame(float delta)
document.animation().afterLayout(this, this::place);      // for hooks that READ geometry
```

A hook is **owned by a node**. It is dropped when the node leaves the tree and **dormant while the
node is frozen** — so hiding a panel stops its work structurally rather than by convention.

Timelines are separate and take a curve:

```java
document.animation().start(0.4f, Easing.OUT_QUAD, p -> box.setOpacity(p), this::onDone);
```

---

## Geometry — `ui/box`

Everything positional moved off the node.

```java
Box box = node.box();          // NULLABLE — see traps
box.x(); box.y();              // parent-relative
box.width(); box.height();
box.worldX(); box.worldY();    // surface pixels
box.localToWorld();            // the ONE matrix; hit-testing inverts this same one
box.setScroll(left, top);
box.contentWidth();            // what is INSIDE — not the content box
```

Three widths, and they are not interchangeable: `width()` is the border box, `clientWidth()` the
padding box (what scrolls), `contentBoxWidth()` the content box (where text goes).

**Hit-testing needs no paint.** `boxes().hitTest(worldX, worldY)` walks the matrices layout composed,
so a click is correct before the first frame is drawn.

**Promotion is one field.** The old engine's top layer diverged from the DOM parent in four separate
places — Taffy parent, `getX/getY`, `localToWorld`, and the paint/hit entry — and fixing three of
four gave you a thing that drew correctly and was clicked somewhere else.

```java
document.promote(node);   // recorded on the node
document.demote(node);
```

The box tree re-applies the whole set on every sync, so a promotion survives a subtree being hidden
and rebuilt. Hosting also **implies out-of-flow** — you do not write `position: absolute` for it.

---

## Identity and the cascade

```java
public static final Name NAME = Name.of("mywidget");

public MyWidget() {
    super(NAME);
}
```

`UINodeRegistry.register(NAME, MyWidget::new, CONTRACT)` makes it decodable from a description.
`registerTag(NAME, contract)` registers a cascade-only kind — a tag a sheet may name that nothing
builds from the wire.

The cascade itself is **shared between both engines** behind `Styleable`, so a cascade bug is fixed
once. `StyleEngine.addStylesheet(sheet, root)` is native CSS `@scope`: only nodes at or under `root`
match the sheet, and a closer scope root wins ties.

---

## Structure — shadow trees instead of internal children

A composite's own parts go in a shadow tree, which makes them undescribed and unreachable by an outer
selector for free:

```java
ShadowRoot shadow = attachShadow();
shadow.append(label);
label.setPart("label");        // addressed as  mywidget::part(label)
shadow.append(new UISlot());   // where a caller's children land
```

**Not every widget may have one.** A widget may host a shadow tree only if nothing reaches *through*
its structure: `::part()` cannot express a part under a part, a tag under a part, or a nested
widget's part. `tools/port/classify.py` reports the verdict per widget — 23 can, 21 cannot. Those 21
keep light-tree structure and use `describedChildren()` / `adoptDescribedChild()` to stay off the
wire.

---

## The wire

`UIDescriptionCodec` was a `Codec<UIElement>`. `UINodeMirror` is the whole seam:

```java
mirror.describe(node);              // the full description
mirror.encodeState(node);           // from the widget's WidgetContract
mirror.encodeAttributes(node);
mirror.encodeInlineStyle(node);     // through the shared InlineStyleCodec
mirror.reportedEventsOf(node);
```

The sessions are generic over `TreeSource<N>` + `NodeMirror<N,T>` and bounded on `Styleable`, so one
implementation serves any tree. `MirrorIsEngineAgnosticTest` mirrors a twelve-line node class that has
never heard of a widget — that test is the assertion.

**State is applied after children**, because some of it indexes into them (a `TabView`'s selection, a
`Dropdown`'s index). Within one state map the contract's declaration order governs — a `Slider` takes
its range before its value or the value is clamped against the range it is replacing.

---

## Lifecycle

| | Old | New |
|---|---|---|
| Hide | detach from the parent | `lifecycle().freeze(node)` |
| Show | re-attach | `lifecycle().thaw(node)` |
| Close | `Disposer` walking a second tree | `lifecycle().destroy(node)` |

A frozen subtree keeps its scroll, its text, its listeners and its place in the tree. It has no boxes,
matches no selector, paints nothing, and its hooks do not run.

**Thread ownership is per document.** `document.markFrameThread()` claims it, `document.require(what)`
asserts it. A document nothing paints has no owner, so headless tests, dedicated servers and
background builds are free — where the old process-wide marker failed a whole suite because JUnit
runs `@Before` and a timed method body on different threads.

---

## Habits that are now wrong

These are the ones that cost real time. Each is a thing that compiles, runs, and does the wrong
thing.

**`box()` is nullable.** A node has no box while disconnected, frozen, `hidden`, or `display: none` —
and **on its first frame**, because a per-frame hook runs *before* layout. Guard at the call site,
where you know what "not laid out yet" should mean; do not add a `boxWidth()` that answers 0, because
"zero-sized" and "never laid out" are different facts. Never let a null become a divisor.

**`toLocal` puts the node's own origin at zero.** The old `screenToLocal` did *not* subtract the
element's position, so its answer was an absolute layout coordinate. Ported code that adds the origin
back is now double-counting — and it is wrong by a different amount depending on where the node sits,
which reads as a bad constant rather than a wrong frame of reference.

**`Box.x()` is parent-relative.** `getRuntimeCache().getX()` accumulated through every ancestor, so
`a.getX() - b.getX()` was a legitimate way to ask "where is a relative to b" for any pair. That
subtraction is now meaningless unless the two share a parent. Use `Box.originIn(box, space)` or
`Box.centreIn(box, space)`, which go through `worldToLocal` and carry the intervening transforms and
scrolls that a subtraction never did.

**There is no tag fallback.** The old `tagName()` fell back to the class's lowercased simple name, so
a widget that registered nothing still answered `runpanel` and its rules matched. A kind is now a
`NAME` constant, **inherited when absent** — so a class that declares none answers
`crystalgui:element` and every rule written for its tag matches nothing. The widget builds, lays out,
takes input and works; it is simply unstyled, which reads as a missing stylesheet rather than a
missing constant.

**A subclass inherits its parent's kind.** `Dropdown extends Button` reported `button` until it was
given its own `NAME`. Decide deliberately: pass the supertype's `NAME` and add a modifier class when
you want everything the supertype has, or declare your own when you want your own look.

**A listener on a shadow host never sees its own parts.** `event.getTarget()` is retargeted to the
host before your listener runs, so the old idiom — one listener on the widget, an if-chain comparing
the target against its internal children — takes the wrong branch forever. Attach inside the shadow
tree and `stopPropagation()` there.

**`stopPropagation()` is immediate within a phase.** A widget that stops propagation pre-empts every
later subscriber on the same element — and a widget always subscribes first, in its own constructor.
Anything that must run before a widget's own handler uses the **capture** phase on an ancestor.

**A hook runs before layout.** Use `afterLayout` for anything that measures. An ordinary hook sees the
previous frame's boxes, and on the frame a node first gets one it is measuring nothing.

**Defaults are the project's, not CSS's.** `flex-direction: column`, `flex-shrink: 0`,
`box-sizing: border-box`, `min-size: 0`. Both engines answer the same way, so a geometry difference
between them is a defect rather than a default — but a rule written against CSS's defaults will
surprise you.

---

## Where things live

```
ui/dom/        UINode, UIDocument, ShadowRoot, UISlot, Name, Attribute, UINodeRegistry,
               UINodeTreeSource — and the generic seam: TreeSource, TreeObserver, NodeContract
ui/box/        Box, BoxTree, BoxStyle, Measurable, BoxPainter, TextNode
ui/service/    Input, InputMode, Drag, Focus, Animation, Lifecycle
ui/contract/   WidgetContract, State, Event, RatePolicy — what a KIND of widget carries
ui/projection/ Projections, AutoProjection — the model reaching the widgets
net/mirror/    UINodeMirror, ServerTreeMirror, ClientTreeMirror, TreeOps
style/         the cascade, shared behind Styleable
widget/        the widget layer      desktop/  the compositor      workbench/  the shell
```

---

## Further reading

- `AGENTS.md` — the invariants table. The rows are the receipts for most of the above.
- `plan_m6.md` — the port, milestone by milestone, with the decisions and their reasons.
- `plan_m5.md` — why three trees, and what each of the four services owns.
- `docs/CGUI_WIDGETS.md` — the widget layer.
- `docs/CGUI_STYLE_RENDER_PIPELINE.md` — the cascade and the render stack.
