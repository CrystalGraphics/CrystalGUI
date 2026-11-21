# The new engine — what replaced what

**For reading a commit, a comment or an old branch that names a class the M6 rewrite deleted.** The widget layer is
`CGUI_WIDGETS.md`; this is the engine under it.

## The one paragraph you need first

`UIElement` was the DOM, the layout box, the paint surface and the hit-test target at once. It is now **three
trees**, each derived from the one above:

| Tree | Package | Holds | Rebuilt |
|---|---|---|---|
| **Node tree** | `ui/dom` | identity, attributes, children, shadow roots, events | never: it is the source |
| **Box tree** | `ui/box` | geometry, transforms, scroll, hit-testing, paint order | when the node tree reports a structure change |
| **Style** | `style/` | the cascade, behind `Styleable` | per dirty element, per frame |

A `UINode` has no geometry: ask its `Box`, which may not exist. The frame is `UIDocument.frame(delta, width, height)`:
animation, style, layout, paint, input.

## The lookup table

| You used to write | Now write | Notes |
|---|---|---|
| `UIElement` | `UINode` + its `Box` | structure on the node, geometry on the box |
| `UIWindow` | `UIDocument` | the document is the root |
| `Ui.of(root)` | *(nothing)* | |
| `window.getStyleEngine()` | `document.styles()` | |
| `window.init(w, h)` | `document.frame(delta, w, h)` | the size arrives with the frame |
| `window.setUiScale(f)` | `UiScale.bind(document, app)` | the owner of the scale; `boxes().setUiScale` stops it following |
| `element.getRuntimeCache().getX()` | `node.box().x()` | **parent-relative** |
| `element.screenToLocal(x, y)` | `node.toLocal(x, y)` | **the node's own origin is zero** |
| `UIInputHandler` | `Input` + `Focus` + `Drag` + `Lifecycle` | `ui/service`, on the document |
| `UIDragController` | `Drag` | an `InputMode` on a stack |
| `UITreeTraversal` | `Focus` | `firstFocusableIn`, `nextTabbable` and the rest |
| `UIFrameTicker` + `registerTicker` | `document.animation().every(owner, hook)` | owned by a node |
| `TopLayer` | `document.promote(node)` / `demote(node)` | implies out-of-flow |
| `element.markAsInternal()` | `node.attachShadow()` | parts addressed by `::part()` |
| `ElementRegistry` | `UIElementRegistry` | and a `NAME` on the class |
| `UIDescriptionCodec`, `ElementNodeMirror` | `UIElementMirror` | describe and decode, state, attributes, inline style |
| `ElementTreeSource` | `UIElementTreeSource` | |
| `UIResizer` | `resize` in CSS | |
| `element.hide()` | `document.lifecycle().freeze(node)` | tree intact, boxes dropped, hooks dormant |
| `Disposer` | `document.lifecycle().destroy(node)` | |
| `UiThread.markCurrent()` | `document.markFrameThread()` | per document |
| `CgUiPaintContext.mirrored` | `document.boxes().mirror(subtree, host)` | |

## Input, focus and gestures — `ui/service`

```java
document.input()      // platform sink, hit test, three-phase dispatch, capture, cursor
document.focus()      // one owner, traversal, focus scopes, modality
document.animation()  // timelines and per-frame hooks
document.lifecycle()  // freeze / thaw / destroy

Drag.start(source, surfaceX, surfaceY, listener);   // a live gesture is a mode: it pushes itself
input.pushMode(myGesture); input.popMode(myGesture);

focus.requestFocus(node);         // keyboard: draws the ring
focus.requestPointerFocus(node);  // a click: no ring, no scroll
```

## The frame and per-frame work

```java
document.animation().every(this, this::tickFrame);        // boolean frame(float delta)
document.animation().afterLayout(this, this::place);      // a hook that reads geometry
document.animation().start(0.4f, CgEasings.OUT_QUAD, p -> box.setOpacity(p), this::onDone);
```

A hook is owned by a node: dropped when it leaves the tree, dormant while it is frozen.

## Geometry — `ui/box`

```java
Box box = node.box();          // nullable
box.x(); box.y();              // parent-relative
box.worldX(); box.worldY();    // surface pixels
box.localToWorld();            // the one matrix; hit-testing inverts it
box.contentWidth();            // what is inside, not the content box
```

`width()` is the border box, `clientWidth()` the padding box (what scrolls), `contentBoxWidth()` the content box.
`boxes().hitTest(x, y)` needs no paint.

## Identity and the cascade

```java
public static final Name NAME = Name.of("mywidget");
public MyWidget() { super(NAME); }
```

`UIElementRegistry.register(NAME, MyWidget::new, CONTRACT)` makes a kind decodable; `registerTag(NAME, contract)` makes a
tag a sheet may name. `StyleEngine.addStylesheet(sheet, root)` is CSS `@scope`.

## Structure — shadow trees instead of internal children

```java
ShadowRoot shadow = attachShadow();
shadow.append(label);
label.setPart("label");        // mywidget::part(label)
shadow.append(new UISlot());   // where a caller's children land
```

A widget may have a shadow tree only if nothing reaches through its structure: `::part()` cannot name a part under a
part. The rest keep light-tree structure and stay off the wire with `describedChildren()` /
`adoptDescribedChild()`.

## The wire

```java
mirror.describe(node);
mirror.encodeState(node);           // from the widget's WidgetContract
mirror.encodeAttributes(node);
mirror.encodeInlineStyle(node);
```

State is applied after children (a `TabView`'s selection indexes into them), and in the contract's declaration order (a
`Slider` takes its range before its value).

## Lifecycle

| | Old | New |
|---|---|---|
| Hide | detach | `lifecycle().freeze(node)` |
| Show | re-attach | `lifecycle().thaw(node)` |
| Close | `Disposer` | `lifecycle().destroy(node)` |

A frozen subtree keeps its scroll, text, listeners and place, and has no boxes, matches no selector, paints nothing.
Thread ownership is per document: `markFrameThread()` claims it, `require(what)` asserts it.

## Habits that are now wrong

- **`box()` is nullable**: disconnected, frozen, hidden, `display: none`, and on a node's first frame. Guard at the
  call site; never let a null become a zero.
- **`toLocal` puts the node's own origin at zero.** Ported code that adds the origin back counts it twice.
- **`Box.x()` is parent-relative.** Subtracting two means nothing unless they share a parent: `Box.originIn` /
  `Box.centreIn`.
- **No tag fallback.** A class with no `NAME` answers its parent's, and rules for its own tag match nothing.
- **A subclass inherits its parent's kind** (`Dropdown` answered `button` until it had its own `NAME`).
- **A listener on a shadow host never sees its parts**: the target is retargeted. Attach inside the shadow tree.
- **`stopPropagation()` is immediate within a phase**, and a widget subscribes first. Run before it with capture on an
  ancestor.
- **A hook runs before layout**: use `afterLayout` to measure.
- **The defaults are the project's**: `flex-direction: column`, `flex-shrink: 0`, `box-sizing: border-box`.

## Where things live

```
ui/dom/        UINode, UIDocument, ShadowRoot, UISlot, Name, Attribute, UIElementRegistry, UIElementTreeSource
ui/box/        Box, BoxTree, BoxStyle, Measurable, BoxPainter, TextNode
ui/service/    Input, InputMode, Drag, Focus, Animation, Lifecycle
ui/contract/   WidgetContract, State, Event
ui/projection/ Projections, AutoProjection
net/mirror/    UIElementMirror, ServerTreeMirror, ClientTreeMirror, TreeOps
style/         the cascade        widget/  widgets        desktop/  the compositor        workbench/  the shell
```

## Further reading

`plan/engine-port.md` (the port, with its decisions) and `plan/engine-core.md` (why three trees).
