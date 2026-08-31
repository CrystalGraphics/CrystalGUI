# Porting a widget to the new engine

**Status: current, and deliberately short-lived.** This describes how a `UIElement` composite becomes
a `ui.dom.UINode` on the M5 engine. It is written from the API that exists — every call below is
real as of 2026-08-31 — and it dies with M8, when the old engine goes and there is nothing left to port.

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
| `class Button extends UIElement` | `class Button extends UINode` |
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

## 2. Structure: a shadow tree is OPT-IN — ask first

> **⚠⚠ REVERSED AT M6.1, after sixteen widgets.** This section used to say every composite's parts
> become a shadow tree. That is the wrong default and it cost eleven silent defects in one batch.
> **Run `python tools/port/classify.py` before you port a widget.** It reads every shipped sheet and
> tells you, for that widget, whether a shadow tree is possible at all.

**A widget may host a shadow tree only if no rule reaches THROUGH its structure.** Measured across the
shipped sheets: 23 widgets can, 21 cannot, and 220 rules have no `::part()` spelling. The three shapes
that have none are a part under a part (`::part(a)::part(b)` is invalid CSS), a tag under a part
(nothing descends from a leaf), and a nested widget's part (the inner widget is inside the outer's
shadow tree).

So there are three answers, and the tool gives you the first bit:

| classify.py says | the widget takes caller content | do this |
|---|---|---|
| any through-rules | either | **LIGHT.** Keep `__x__` classes, keep children in the light tree. The sheet needs no edit. |
| none | yes | **SHADOW + SLOT.** Parts become `part=`, add the widget to `twins.py`'s `HOSTS`, run it. |
| none | no | Prefer **LIGHT** — smaller change, no sheet edit. Shadow is defensible but buys nothing. |

**What a shadow tree actually buys is the slot**, not the part naming: a caller's content cannot land
among a widget's own parts. That is what `.__content__` cost three times over, and it is why
`ScrollerView`, `Menu` and `Button` genuinely need one. A widget that takes no content has nothing to
protect.

**A subclass cannot un-shadow its parent.** `Dropdown extends Button`, so Button's decision is
Dropdown's. Decide the base class first.

### When it IS a shadow tree

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
public class Button extends UINode {
    public static final Name NAME = Name.of("button");
    public static final String LABEL_PART = "label";

    // NO static { register(...) } BLOCK -- see the warning below.
    public static final State<Button, String> TEXT = ...;
    public static final WidgetContract<Button> CONTRACT = ...;

    public static final String LABEL_PART = "label";

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

> **⚠⚠ CORRECTED BY THE FIRST REAL PORT, and this is the one to read twice.** The guide said to
> register from a `static {}` block next to the `Name`. **That is wrong, and the old engine already
> knew it**: `ElementRegistry`'s own javadoc says a widget registering itself that way makes the
> registry's contents *"a function of which widgets a given JVM had happened to touch… harmless for a
> local UI and **actively wrong** for a serialized one: the same description would decode to a real
> `Slider` on a client that had shown one earlier and to a bare element on one that hadn't, with no
> error either way."* `Button` shipped with the block for exactly one commit.
>
> A kind is declared by its **LAYER**, through a `NodeKinds` service the registry discovers and runs
> once on the first question anybody asks it:
>
> ```java
> public final class Widgets implements NodeKinds {
>     @Override public void register() {
>         UINodeRegistry.register(Button.NAME, Button::new, Button.CONTRACT);
>     }
> }
> ```
>
> plus a line in `META-INF/services/com.crystalgui.ui.dom.NodeKinds`. **A service rather than the old
> engine's one central `bootstrapBuiltins()` because of the layering**: `ui.dom` is the engine and
> `widget`/`chrome`/`desktop`/`workbench` are above it, so a registry importing a `Button` is the
> upward reference `LayeringTest` refuses. `NodeKindsCoverageTest` fails on any class declaring a
> `NAME` that no service registers — a list is safe exactly as long as something checks it.

> **⚠ found by the paper port.** The registry's factory is a `Supplier<? extends UINode>`, so
> `Button::new` needs a **no-argument constructor** — a widget whose only constructor takes its text
> does not compile as a method reference, and the codec has nothing to build the node with when a
> description arrives. Give it the no-arg constructor delegating to the real one; the old
> `ElementRegistry` wanted the same thing and every widget already has one.

> **⚠ found by the paper port.** A subclass that wants its supertype's look must declare **no**
> `Name` of its own and let the supertype's registration stand — the `Dropdown extends Button`
> question, unchanged. The rule is the same; only the spelling moved.

### Slots, for content a caller supplies

`Tab.content()`, `SplitView`'s panes and `ScrollerView`'s viewport all exist because a composite
needed one place a caller may add to. That is a `UISlot`:

```java
UISlot content = new UISlot();      // the default slot
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

You do **not** have to invalidate your own parts. `invalidateStyleMatch()` marks the exposed nodes in
your shadow tree as well, because a `::part` rule is indexed under the *host* — `checkbox:checked::part(mark)`
is a rule about the mark whose every selectable input belongs to the checkbox. The old engine had no
such thing, and each widget that hit it repaired it locally by flipping a class of its own; that is
why several widgets you are porting carry a `__on__`-style class beside a perfectly good
pseudo-class. **Port the pseudo-class and drop the class**, unless the class is carrying something a
pseudo-class genuinely cannot say.

### Report the state, or it never leaves the process

A setter that changes what the widget's contract carries must end with `notifyStateChanged()`:

```java
public Checkbox setChecked(boolean value) {
    if (this.checked == value) return this;   // the guard is load-bearing -- see below
    this.checked = value;
    invalidateStyleMatch();
    notifyStateChanged();
    onCheckedChanged.emit(value);
    return this;
}
```

It walks out of every enclosing shadow tree, so a composite's label dirties the *composite* — whose
contract carries the text — rather than a node no peer has heard of. You get that for free when the
state lives in a `TextNode`, which reports its own text; you have to write it when the state is a
field of your own.

> **⚠ the guard is not an optimisation.** Every state setter must be idempotent, because "mirror the
> model each tick" is the shape every server-side panel is written in. The old `ProgressBar.setFraction`
> was the one setter for which this was false, and a panel following the documented shape sent a delta
> per tick carrying a value nobody had moved. Assert on the **traffic**, never on the state, or the
> test passes against exactly that bug.

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

1. **`Name` on the widget's own class** (`Name.of("button")` — the overload is the default
   namespace); a mod uses `Name.of(namespace, local)`. Never a constant on `Name`, which would be a
   second registry. Then add a line to the layer's `NodeKinds` service — **never a `static {}` block
   on the widget**, per the warning in §2.

   **Field order is `NAME`, then state/events/`CONTRACT`, then the `*_PART` strings.** The
   declaration order is the reading order: what this kind IS, then what it SAYS to a peer, then the
   pieces it is built from. A reader arriving at a widget wants the first two; the part names matter
   only once they are reading the constructor or writing a rule.
2. Constructor: parts into a shadow root, each with `part=`; `setFocusPolicy`.
3. The sheet: `.widget .__part__` → `widget::part(part)`.
4. Geometry: every `importantPipeline` call becomes a `Measurable`, a box call, or an animation.
5. Paint: `paintSelf`/`paintOverlay` bodies move to `paintContent`/`paintDecoration`, re-based to
   the box's own origin.
6. Tickers, drags and hide/show.
7. Delete `markAsInternal`, `addInternalChild`, `acceptsPublicChildren` and the `__part__` constants.

> **The destination package is the ledger's, not the old file's** — `plan_m6.md` §2.6. A ported class
> is a COPY into `widget.*`, `chrome.*`, `desktop.*` or `workbench.*`; the old file stays until M6.9,
> which is what keeps the game running on the old engine throughout. §2.7's codemod does the copy and
> the mechanical two thousand of the 2,670 sites; this guide is for the 443 that need a reading.
