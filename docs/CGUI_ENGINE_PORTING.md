# Porting a widget to the new engine

**Status: current, and deliberately short-lived.** This describes how a `UIElement` composite becomes
a `ui.dom.Node` on the M5 engine. It is written from the API that exists — every call below is real
as of M5 5.6 — and it dies with M8, when the old engine goes and there is nothing left to port.

> **Why it is not a section of `docs/CGUI_WIDGETS.md`**, which `plan_m5.md` originally suggested:
> that document describes widgets that still exist and will outlive the port. A guide whose whole
> subject is the difference between two engines rots the moment one of them goes, so it is indexed
> from `AGENTS.md` as its own file and deleted whole rather than picked apart.

The guide was written by porting **`Button`** on paper against it, then fixing *the guide* wherever
the port hit something the guide did not say. Five things changed that way; each is marked
**⚠ found by the paper port**. M6's first step is to port the same widget for real.

---

## 1. The shape of the change

| Old engine | New engine |
|---|---|
| `class Button extends UIElement` | `class Button extends Node` |
| a private field per part, added with `addInternalChild` | the same fields, added to a **shadow root** |
| `markAsInternal()` / `acceptsPublicChildren() == false` | nothing — a shadow root IS the encapsulation |
| `__label__` class on the part | `part="label"` on the part |
| `.button .__label__ { }` in a sheet | `button::part(label) { }` |
| a sheet reaching a part by accident | impossible: an outer rule cannot enter a shadow tree |
| `paintSelf` / `paintOverlay` / `paintOutline` | `paintContent` / `paintDecoration` — the box model is the **painter's** |
| `getRuntimeCache().getX()` etc. | `box().x()`, `box().worldX()`, `box().width()` |
| `StyleGroup.importantPipeline(...)` to pin geometry | a **`Measurable`**, a box call, or an `Animation` — never the cascade |
| `registerTicker(this)` + `tickFrame` | `document.animation().every(node, delta -> …)` |
| `UIDragController.startDrag(...)` | `Drag.start(node, x, y, listener)` |
| `setFocusPolicy(CLICK)` | unchanged — `FocusPolicy` is the same enum, deliberately |
| a focusable container that swallows focus | `attachShadow(true)` — it delegates instead |

---

## 2. Structure: internal children become a shadow tree

The old rule was *"structure is internal children"*: `markAsInternal()`, `addInternalChild()`, a
`__double-underscore__` class per part, and `acceptsPublicChildren()` returning `false` so
`addChild` throws. Four mechanisms, and every one of them is a convention the cascade cannot see —
which is why `.__content__` was named by three unrelated widgets and a descendant selector reached
all of them.

```java
// Old
public class Button extends UIElement {
    private final UIText label;

    public Button(String text) {
        markAsInternal();
        this.label = new UIText(text);
        label.addClass("__label__");
        addInternalChild(label);
        setFocusPolicy(FocusPolicy.CLICK);
    }

    @Override public boolean acceptsPublicChildren() { return false; }
}

// New
public class Button extends Node {
    public static final Name NAME = Name.of("crystalgui", "button");
    public static final String LABEL_PART = "label";

    static { NodeRegistry.register(NAME, Button::new, NodeRegistry.plain(NAME, false)); }

    private final TextNode label;

    public Button() {
        this("");
    }

    public Button(String text) {
        super(NAME);
        setFocusPolicy(FocusPolicy.CLICK);
        this.label = new TextNode(text);
        label.set(Attribute.PART, LABEL_PART);
        attachShadow().append(label);
    }
}
```

Everything the four old mechanisms bought falls out of one: light children a caller adds are not the
shadow tree, so they cannot collide with the parts; an outer rule cannot match a part at all; and the
part is styleable from outside **only** through the name the widget chose to expose.

> **⚠ found by the paper port.** `Name` must be declared and REGISTERED, or the node's tag is the
> lowercased class name and no sheet rule matches it — the old engine's *"`tagName()` is an
> EXACT-CLASS lookup"* row, which cost a tool window its entire appearance. Register in a static
> initializer next to the `Name`, the way `TextNode` does, so a class that exists is a class the
> codec can decode.

> **⚠ found by the paper port.** The registry's factory is a `Supplier<? extends Node>`, so
> `Button::new` needs a **no-argument constructor** — a widget whose only constructor takes its text
> does not compile as a method reference, and the codec has nothing to build the node with when a
> description arrives. Give it the no-arg constructor delegating to the real one; the old
> `ElementRegistry` wanted the same thing and every widget already has one.

> **⚠ found by the paper port.** A subclass that wants its supertype's look must declare **no**
> `Name` of its own and let the supertype's registration stand — the `Dropdown extends Button`
> question, unchanged. The rule is the same; only the spelling moved.

### Slots, for content a caller supplies

`Tab.content()`, `SplitView`'s panes and `ScrollerView`'s viewport all exist because a composite
needed one place a caller may add to. That is a `Slot`:

```java
Slot content = new Slot();          // the default slot
attachShadow().append(chrome).append(content);
// a caller's addChild(x) now lands in `content`, composed, without the widget writing a method
```

---

## 3. State: the pseudo-classes, unchanged

`:hover`, `:active`, `:focus`, `:disabled` and `:focus-visible` are written by the services and read
by the cascade; a widget touches none of them. What a widget still overrides is its own:

```java
@Override public boolean isChecked() { return checked; }   // gives `button:checked` for free
```

`enabled`, `inert` and `hit-test` are **attributes** now rather than fields:

```java
node.set(Attribute.ENABLED, false);     // and `isEnabled()` reads it
node.set(Attribute.INERT, true);        // subtree-wide, as before
node.set(Attribute.HIT_TEST, false);    // `pointer-events: none`, subtree-wide
```

> **⚠ found by the paper port.** A state change that a selector depends on must call
> `invalidateStyleMatch()`, exactly as the old engine's did. `set(Attribute…)` does it for you;
> a widget's own field (the `checked` above) does not, so a setter that flips one must say so.

---

## 4. Geometry: nothing writes into the cascade

The old engine's fifteen `IMPORTANT`-write shapes collapse into three answers.

| What it was doing | Now |
|---|---|
| `UIText` pushing its measured height back so layout runs again | implement **`Measurable`** — the layout engine asks *inside* the pass |
| a widget pinning a size it computed (a taskbar entry, a preview panel) | it does not: size the CONTENT, or write a `max-width` cap through the animation service |
| a compositor moving a window (transform, opacity, scroll) | `box.setTransform` / `box.setOpacity` / `box.setScroll`, driven by an `Animation.Timeline` |
| `scrollTop`/`scrollLeft` | `box.setScroll(left, top)` — clamped against the content the box laid out, and the offset lives on the **node**, so it survives a freeze |
| reading a settled box | `box().x()`, `.width()`, `.contentWidth()`, `.worldX()` |

```java
// Old: a measured leaf pushes its height into the cascade and re-dirties layout.
StyleGroup.importantPipeline(getStyle().getLayoutGroup(), l -> l.height(measured));

// New: the leaf answers the question layout is already asking.
@Override
public Size measure(Constraints c) {
    float width = c.wrapWidth();
    return new Size(w, h);
}
```

`EngineBoundaryTest` reads the constant pool of every new-engine class for `StyleOrigin.IMPORTANT`
and `StyleGroup.importantPipeline`, so this is not a convention — a port that reaches for the old
shape fails the build.

> **⚠ found by the paper port.** `Constraints` carries a **`Fit`** as well as the sizes: the engine
> asks for min-content as well as max-content, and answering both with one unbroken line pins a
> leaf's minimum at its whole line so it can never shrink. `wantsMinContentWidth()` is the question.

---

## 5. Painting: the box model is not yours

`paintSelf`'s background, border, radii, overlay, outline, clipping and opacity are `BoxPainter`'s
now, resolved from `ComputedStyle`. A widget overrides only what the box model cannot express:

```java
// Old
@Override protected void paintOverlay(CgUiPaintContext ctx) {
    float x = getRuntimeCache().getX(), y = getRuntimeCache().getY();
    ctx.text().draw().at(x + pad, y + pad)…;
}

// New -- the pose is already this box's own space, so the origin is (0, 0).
@Override public void paintContent(CgUiPaintContext ctx, Box box) {
    float x = box.border().left + box.padding().left;
    ctx.text().draw().at(x, y)…;
}
```

`paintContent` runs after the background and before the children; `paintDecoration` runs after them.
There is no `mirrored` flag and nothing to reconcile: a thumbnail is a second `Box`, painted like any
other, and hit-testing walks the matrix layout composed.

---

## 6. Events, focus, motion, lifetime

```java
// Events: the same groups, one difference in what stopPropagation means.
node.events.getGroup(MouseEvent.Up.class).attachListener((n, e) -> {
    if (e.getButtonId() != CgMouseCodes.LEFT_BUTTON) return;   // unchanged, and still load-bearing
    if (e.isWasPressTarget() && isEnabled()) onPressed.emit();
}, false, true);
```

- **`stopPropagation()` now ends the WALK only.** The same node's remaining listeners still run;
  `stopImmediatePropagation()` ends those. A widget that used the old behaviour to pre-empt later
  subscribers must say so explicitly.
- **A modified chord goes to the keymap first** unless the node overrides `claimsChord(key,
  modifiers)`. Delete the yield list; state what the widget *wants*.
- **A ticker becomes an owned hook**: `document.animation().every(this, delta -> …)`, dropped when
  the node is frozen or disconnected. Returning `false` still stops it.
- **A drag is a mode**: `Drag.start(this, x, y, listener)` or `Drag.startWithPayload(...)`. The
  listener's coordinates are in the **source box's own space** (origin at its top-left), which is not
  what `screenToLocal` answered — read §6 of the invariants ledger before assuming either.
- **Hiding is freezing**: `document.lifecycle().freeze(node)`. There is nothing to capture and
  nothing to restore, so a widget that wrote session state on the way out and re-applied it on the
  way back deletes both halves.

> **⚠ found by the paper port.** `Signal.Action onPressed` and the widget's public API survive
> unchanged. The port is of the ENGINE seam, not of the widget's contract — which is what makes it
> reviewable widget by widget rather than as one commit.

---

## 7. The order to do it in

1. `Name` + `NodeRegistry.register` in a static initializer.
2. Constructor: parts into a shadow root, each with `part=`; `setFocusPolicy`.
3. The sheet: `.widget .__part__` → `widget::part(part)`.
4. Geometry: every `importantPipeline` call becomes a `Measurable`, a box call, or an animation.
5. Paint: `paintSelf`/`paintOverlay` bodies move to `paintContent`/`paintDecoration`, re-based to
   the box's own origin.
6. Tickers, drags and hide/show.
7. Delete `markAsInternal`, `addInternalChild`, `acceptsPublicChildren` and the `__part__` constants.
