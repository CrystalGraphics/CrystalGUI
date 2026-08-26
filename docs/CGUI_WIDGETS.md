# CrystalGUI Widgets

> **Current-state reference** for `core/src/main/java/com/crystalgui/ui/elements/` — twelve classes.
>
> Companions: `CGUI_STYLE_RENDER_PIPELINE.md` (how the cascade and painting actually work) and
> `CGUI_SERVER_AND_SERIALIZATION.md` (how widget state travels to a client).
>
> Re-verify against the code before trusting a specific signature.

---

## 0. Conventions — read this before writing a widget

These rules are enforced in code and invisible from any single class. Every widget below obeys them.

### Composite widgets refuse public children

`acceptsPublicChildren()` returns `false` on `Button`, `Checkbox`, `Switch`, `Slider`, `SplitView`,
`Scroller`, `TabView` and `TextField` — `addChild` throws. Only elements *designed* to hold children
accept them:

| Accepts children | How you put content in it |
|---|---|
| `UIElement` | `addChild` |
| `ScrollerView` | `addChild` — it *is* the viewport |
| `SplitView` | `first()` / `second()`, or `first(content)` / `second(content)` |
| `TabView` | never directly — `addTab(label).content()` |

When adding a widget, give it a **named accessor** for its content rather than opening the tree.

### Structure is internal children

`markAsInternal()` / `addInternalChild()` build a widget's parts. They are skipped by public traversal
and each carries a `__double-underscore__` class that themes target. The full set in use today:

```
__pre-icon__  __post-icon__   (Button)
__mark__                      (Checkbox)
__spacer__  __knob__          (Switch)
__fill__  __thumb__  __spacer__          (Slider)
__track__  __thumb__  __head__  __tail__  __vertical__   (Scroller)
__v-scroller__  __h-scroller__  __corner__               (ScrollerView)
__first__  __divider__  __second__  __vertical__         (SplitView)
__strip__  __rail__  __strip-bar__  __panes__            (TabView)
__top__  __bottom__  __left__  __right__                 (TabView, on the root)
__pane__                      (Tab)
```

One class name appears in a *comment* but is not added by any code today — `__stepped__`
(`ore.css:208`, would let a theme style discrete sliders). Don't write CSS against it until something
actually adds it.

Every one is exposed as a `public static final String` constant on its widget — reference
`Slider.THUMB_CLASS`, not the literal.

### A widget's CSS identity is its tag, not its Java supertype

`Dropdown extends Button`, `MenuItem extends Button`, `Tab extends Button` — and **none of them are matched
by `button { … }`**, because a type selector matches the registered tag, exactly as in CSS. Java inheritance
is invisible to the cascade.

This is not theoretical: `Dropdown` shipped laying out at **zero height**, because `min-height` is where a
Button gets its box and no rule reached it. `default.css` now names `dropdown` alongside `button` throughout
(and so does `ore.css`, or a themed dropdown renders with no chrome at all).

> When you subclass a widget, decide explicitly: extend the existing selector list if it should look the
> same, or write it its own block if it should not. Doing neither gives you an invisible control.

### No sizes, no timings, no colours in Java

Widgets write structure and state. `default.css` gives every widget functional geometry so it works
with no theme loaded; `ore.css` gives it appearance. `Switch`'s knob animation is a `transition` on
`flex-grow` in CSS, not a Java tween. **If you find yourself typing a pixel value into a widget, it
belongs in `default.css`.**

Widget-authored baseline styling goes in at `DEFAULT` origin, the lowest priority, so any stylesheet
rule beats it without needing `!important`. The idiom every widget uses:

```java
StyleGroup.defaultPipeline(getStyle().getLayoutGroup(), l -> l.flexDirection(FlexDirection.ROW));
StyleGroup.importantPipeline(getStyle().getGeneralGroup(), g -> g.display(NONE));  // the rare opposite
```

`importantPipeline` is for state a stylesheet must *not* be able to override — `Tab` hides a
deselected pane that way. (`UIElement.moveInlineAsDefault()` also exists and does the same job
retroactively, but no widget uses it; prefer `defaultPipeline`.)

### Pseudo-classes come from getters

`PseudoClasses` binds each selector to a real `UIElement` method:

| Selector | Method |
|---|---|
| `:enabled` / `:disabled` | `isEnabled()` |
| `:checked` | `isChecked()` |
| `:blank` | `isBlank()` |
| `:invalid` | `isInvalid()` |
| `:hover` | `isHovered()` |
| `:active` | `isPressed()` |
| `:focus` | `isFocused()` |
| `:focus-visible` | `isFocusVisible()` |

A widget gets a pseudo-class for free by overriding the getter — `Tab.isChecked()` returning its
selected flag is the whole of how `tab:checked` works.

`:focus-visible` is the web's rule and the one a focus ring should hang off: true for keyboard and
programmatic focus, false after a mouse click — except on elements that take text input
(`consumesTextInput()`), which always ring. `UIInputHandler` decides it from `FocusSource`; see
`CGUI_STYLE_RENDER_PIPELINE.md` §2. Note hyphenated names resolve via `PseudoClasses.lookup`, not
`valueOf`.

### Listeners, tickers, tags

- **`attachListener(l, capture, bubble)` always subscribes the target phase.** The two booleans are
  additive, not a mode selector.
- **`UIFrameTicker`** — implement it and call `registerTicker(this)` on the window. Returning `false`
  from `tickFrame(delta)` drops the registration. The set is `HashSet`-backed so re-registering is
  idempotent, and there is deliberately no unregister. Used by `Scroller` (press-and-hold repeat),
  `ScrollerView` and `TextField` (caret blink).
- **Register the tag** in `ElementRegistry.bootstrapBuiltins()` — one line, `register(tag, Class,
  factory)`. Without it the widget has no `tagName()`, cannot be a CSS type selector, and cannot be
  serialized. Current tags: `element`, `button`, `checkbox`, `scroller`, `scrollerview`, `slider`,
  `splitview`, `switch`, `tab`, `tabview`, `textfield`, `text`.
- **Override `writeState`/`readState`** if the widget carries state a server would want to send or a
  client would want to report back. See `CGUI_SERVER_AND_SERIALIZATION.md`.

---

## 1. `Button`

```java
Button b = new Button("Click me");
b.attachListener(() -> count++);        // or: b.onPressed
b.setText("Now this");
b.setPreIcon(icon);  b.setPostIcon(null);   // null clears
b.setEnabled(false);                        // inherited from UIElement
```

Activates on press-then-release **over the same element** (dragging off cancels), and on Space/Enter
when focused. Fires `CgPlatform.sound()` with `button_click`; silent unless the registered platform implements it.

- Tag `button` · internal `__pre-icon__`, `__post-icon__` · pseudo `:hover :active :focus :focus-visible :disabled`
- Scenes: `cgui-button`, `cgui-ore-theme` (forced-state matrix), `cgui-gallery`

## 2. `Checkbox` + `CheckboxGroup`

```java
Checkbox c = new Checkbox("Enable thing");
c.setChecked(true);
c.attachListener(checked -> …);         // or: c.onCheckedChanged

CheckboxGroup group = new CheckboxGroup().allowEmpty(false);
c.setGroup(group);                      // normal usage — not group.register(c)
Checkbox current = group.getCurrent();
```

`CheckboxGroup` is a plain object, **not a `UIElement`** — it has no place in the tree and no styling.
With `allowEmpty(false)` it refuses to let you un-check the last checked box, giving radio-button
semantics.

The mark is drawn entirely by CSS — `checkbox:checked .__mark__ { … }`. There is no Java-side
"draw a tick".

- Tag `checkbox` · internal `__mark__` · pseudo `:checked :hover :active :focus :focus-visible :disabled`
- Scenes: `cgui-checkbox`, `cgui-ore-theme`, `cgui-gallery`

## 3. `Switch`

```java
Switch s = new Switch();
s.setChecked(true);
s.attachListener(checked -> …);         // or: s.onCheckedChanged
```

The knob slides by animating an invisible `__spacer__`'s `flex-grow` between 0 and 1. **All timing is
CSS** — `switch .__spacer__ { transition: flex-grow 150ms ease-out; }`. There is no duration, easing or
size anywhere in `Switch.java`.

- Tag `switch` · internal `__spacer__`, `__knob__` · pseudo `:checked :hover :active :focus :focus-visible :disabled`
- Scenes: `cgui-switch` (captures four frames mid-slide to prove it interpolates), `cgui-gallery`

## 4. `Slider`

```java
Slider sl = new Slider();
sl.setRange(0, 100).setStep(5).setValue(40);   // step 0 = continuous
sl.attachListener(v -> …);                     // or: sl.onValueChanged
float f = sl.getFraction();                    // 0..1
```

Drag-by-delta, click-to-jump, Left/Right arrows, Home/End, and the wheel. Focus policy is `CLICK`.

- Tag `slider` · internal `__fill__`, `__thumb__`, `__spacer__` · pseudo `:hover :active :focus :focus-visible :disabled`
- Scenes: `cgui-slider`, `cgui-gallery`

## 5. `TextField`

The largest widget. Three layers worth keeping straight:

```java
TextField t = new TextField();

// 1. raw box contents
t.setText("abc");  t.getText();

// 2. published value — Property<String>, the thing you bind
t.value;  t.getValue();
t.bindValueTo(model);  t.bindValueBidirectional(model);
t.setUpdateMode(UpdateMode.IMMEDIATE);   // default is ON_COMMIT (Enter or blur)
t.onSubmit;                              // fired on Enter
boolean ok = t.commit();                 // clamps + reverts if invalid

// 3. validation, in two tiers
t.setMode(Mode.INTEGER).setRange(-50, 50).setStep(5);   // auto-derives a char filter
t.setCharPattern("[0-9]");               // per-keystroke: rejects outright
t.setCharFilter(ch -> …);
t.setPattern("\\w+");                    // whole-text: marks :invalid, still editable
t.setTextValidator(s -> …);
```

**The keystroke/whole-text split is deliberate.** A char filter refuses input that could never be
valid; a text validator flags input that is *not yet* valid but must be typable on the way to
something that is — you cannot reach `-5` without passing through a bare `-`.

`Mode` is `STRING | INTEGER | LONG | FLOAT | DOUBLE`, and setting a range narrows the auto-derived
filter: a `0..100` integer field silently drops `-`, a `-50..50` one admits it.

Also: caret blink (`setCaretBlinkSeconds`, `isCaretVisible`, via `UIFrameTicker`), selection
(`selectAll`, `clearSelection`, `getSelectedText`, `getSelectionStart/End`), clipboard through
`CgPlatform.input().getClipboard()`, wheel-steps-the-value (`stepBy(notches)`), and Escape to revert.

Six style properties drive its text: `color`, `font-size`, `font-family`, `line-height` (vertical
centring only), `caret-width`, `selection-color`.

`line-height` defaults to CSS's `normal` — the font's own line box. **Neither the caret nor the
selection band is taken from it**: both are `ascender + descender`, excluding the `lineGap` a line box
also carries, because that gap is leading *between* lines and this field is single-line. The selection
also paints only while focused — blurring hides the band but keeps the range, as browsers do. See
`CGUI_STYLE_RENDER_PIPELINE.md` §8c.

- Tag `textfield` · pseudo `:blank :invalid :hover :active :focus :focus-visible :disabled`
- Scenes: `cgui-textfield`, `cgui-gallery`

## 6. `UIText`

```java
UIText t = new UIText("hello");
t.setText("world");
t.bindTextTo(someProperty);             // or: t.text  (Property<String>)
```

**It sizes itself by writing width/height at `IMPORTANT` origin.** So a bare `UIText` cannot be given
a width by CSS — wrap it in a fixed-width slot element when you need aligned columns:

```java
UIElement slot = new UIElement().layout(l -> l.width(58));
slot.addChild(new UIText("label"));
```

Consumes `color`, `font-size`, `font-family`, plus `text-offset-x`/`text-offset-y` (paint-time glyph
nudge, applied *after* the wrap width is read so it can never affect geometry; percentages resolve
per-axis against this element's own box). Wraps at the font's own metrics and does **not** honour
`line-height` yet.

- Tag `text` · no internal children
- Scenes: `cgui-text` (wrapping, font fallback, live binding), `cgui-gallery`

## 7. `Scroller` and `ScrollerView`

Two different things, and the distinction is the point.

**Scrolling is an ordinary `UIElement` capability.** Any element with `overflow: hidden`/`auto`
scrolls — `setScrollTop`, `setScrollLeft`, `setScroll`, `setScrollImmediate`, `scrollIntoView`,
`getMaxScrollTop/Left`, `getScrollWidth/Height`, `getClientWidth/Height`, `setScrollExempt`. A bare
element scrolls **programmatically only**; it never listens to the wheel.

**`ScrollerView`** is that viewport plus two bars. It opts into the wheel explicitly.

```java
ScrollerView view = new ScrollerView();
view.addChild(content);                 // accepts public children — it IS the viewport
view.setScrollbarsVisible(false);       // scrollable, just no visible bars
view.refreshScrollers();                // cheap + idempotent; auto-called from onLayoutChanged
view.verticalScroller(); view.horizontalScroller(); view.corner();
```

Wheel is 40px per notch; Shift+wheel scrolls horizontally; a horizontal-only view takes the plain
wheel; at either end the event bubbles, giving scroll chaining.

**`Scroller`** is the bar widget by itself — usable standalone, e.g. as `TabView`'s strip bar.

```java
Scroller bar = new Scroller();
bar.setOrientation(Scroller.Orientation.VERTICAL);
bar.setValue(0.5f);                     // 0..1
bar.setVisibleRatio(0.3f);              // thumb length
bar.setStepFraction(0.1f);
bar.attachListener(v -> …);             // or: onValueChanged
bar.onScrollIntent;                     // relative nudges from the step buttons / track paging
```

Step buttons exist but are `display: none` by default — a theme enables them with
`.__head__, .__tail__ { display: flex; }`.

- Tags `scroller`, `scrollerview` · internal as listed in §0 · `Scroller` and `ScrollerView` are both
  `UIFrameTicker`s
- Scenes: `cgui-scroller`, `cgui-gallery`

## 8. `SplitView`

```java
SplitView sv = new SplitView();
sv.setOrientation(SplitView.Orientation.VERTICAL);   // adds __vertical__ to the root
sv.first(leftContent).second(rightContent);          // or sv.first().addChild(…)
sv.setPercentage(35f).setLimits(10f, 90f);           // 0..100, NOT 0..1
sv.attachListener(pct -> …);                         // or: onPercentageChanged
sv.divider();
```

**Percentages are 0..100**, defaulting to LDLib2's 5..95 limits. Passing `0.35f` is silently clamped
to the minimum and collapses the first pane, which looks like a layout bug and is not one.

The panes accept children; the `SplitView` itself does not. Splits nest.

- Tag `splitview` · internal `__first__`, `__divider__`, `__second__`; root gets `__vertical__`
- Scenes: `cgui-splitview` (both orientations, nesting, the oversized-content trap), `cgui-gallery`

## 9. `TabView` + `Tab`

```java
TabView tv = new TabView();
tv.setTabSide(TabView.TabSide.LEFT);        // TOP | BOTTOM | LEFT | RIGHT
Tab first = tv.addTab("General");           // the FIRST tab added is auto-selected
first.content().addChild(page);             // content() is the pane — an ordinary element
tv.addTabAt("Inserted", 1);
tv.selectIndex(2);  tv.getSelectedTab();  tv.getTabs();
tv.removeTab(first);  tv.clearTabs();
tv.attachListener(tab -> …);                // or: onTabSelected — emits null when the last tab goes
tv.strip(); tv.rail(); tv.bar(); tv.panes();   // for styling
```

Structure: `TabView → __strip__ { __rail__ (a ScrollerView), __strip-bar__ (a Scroller) } + __panes__ {
__pane__ … }`. The rail is a real `ScrollerView` with its bars hidden, so an overflowing tab strip pans
on the wheel and the separate `__strip-bar__` shows the position.

`setTabSide` writes `flex-direction` at `DEFAULT` origin and swaps a state class on the root, so CSS
can do `tabview.__left__ .__strip__ { … }`.

**Panes are not lazy.** `addTab` eagerly builds the `Tab` *and* its pane and inserts it immediately;
hiding is `display` toggling at `IMPORTANT` origin, not tree removal. That is what makes element
identity, listeners and scroll position survive a tab switch, and hit-testing plus Tab-order traversal
both skip hidden panes. If you want lazy population, do it yourself in an `onTabSelected` listener.

`Tab extends Button`, so it has `setText`/`setPreIcon`/`setPostIcon` and Space/Enter activation.
`Tab.setSelected` is package-private on purpose — go through `TabView.selectTab`.

Keyboard: Left/Right (Up/Down when vertical) step the selection, Home/End jump to the ends; focus
moves with the selection. Bubble-phase, so a focused widget inside a pane sees arrows first.

**A whole strip is one Tab stop** — the ARIA APG's *roving tabindex*. The selected tab is
`FocusPolicy.CLICK`; every other tab is `CLICK_NOT_TABBABLE` (the web's `tabindex="-1"`), so Tab enters
the strip once, arrows move within it, and Tab again leaves for whatever follows. Ten tabs cost one
press to skip, not ten.

`TabView.updateTabStops()` owns that invariant — not `Tab` — for the same reason `selectTab` owns
selection: "exactly one" is a strip-wide property, and a per-tab setter could leave **zero** stops,
which makes the entire tablist unreachable by keyboard. It runs on every selection *and* membership
change, falls back to the first tab when nothing is selected (`selectTab(null)` is public), and restores
a removed tab to ordinarily-tabbable on the way out.

- Tags `tabview`, `tab` · internal as listed in §0 · `tab:checked` works via the overridden `isChecked()`
- Scenes: `cgui-tabview` (four sides, strip overflow, focus exclusion), `cgui-gallery`
- Known gap: tabs and panes do **not** round-trip through the codec — they live in internal containers.

---

## 10. `Tooltip`

The first consumer of the **top layer** (CSS Position 4 §top-layer — the machinery behind
`<dialog>` and the Popover API), and the reason it was built.

```java
Tooltip tip = Tooltip.attach(anchor, "explain this");   // shown on hover, hidden on leave
tip.detach();                                            // remove it again
```

`Tooltip.attach` is a **static factory on the widget**, deliberately not `UIElement.setTooltip`.
`UIElement` is the core DOM node every widget builds on; a tooltip is a widget, so putting the wiring
there inverted the dependency (core importing `ui.elements`) and made every element in the tree carry
a field for a feature most never use. It was also where a real bug lived: a set/clear/set cycle
attached a second pair of hover listeners each time. Creating the tooltip and its listeners in one
call makes that unrepresentable.

**Why it needs the top layer.** A tooltip's whole job is to draw *outside* the thing it describes.
`drawSubtree` paints depth-first under every ancestor's scissor, so before promotion a tooltip on a
row inside an `overflow: hidden` scroller was clipped to the scroller. See `TopLayer`.

**Structure.** Internal child of the anchor — which keeps the cascade behaving like the web's, since
it inherits `color`/`font-family` from where it sits in the tree rather than from wherever it paints.
Its own internal `__label__` is a `UIText`.

**Closed ⇒ `display: none`**, exactly as a closed popover is on the web. Not cosmetic: an ordinary
child participates in its parent's flex flow, so a hidden tooltip would silently pad every element
that had one. One property covers layout, paint and input at once.

**Never eats the pointer** (`setHitTest(false)`). Otherwise the tooltip appearing under the cursor
counts as leaving the anchor → hide → un-hover → show: a flicker loop. The web's `pointer-events: none`
on tooltips exists for the same reason.

**Placement** is recomputed *every frame* from the anchor's `localToWorld` — not its layout box, which
knows nothing about scrolling — so it tracks a scrolling or animating anchor. Below the anchor by
default, **flips above** when there is no room below, then clamps horizontally. That is the useful
subset of CSS Anchor Positioning's `position-try-fallbacks`.

- Tag `tooltip` · internal `__label__` · Scene: `cgui-gallery` (Tooltip page)
- **No pixel values in Java**: the gap under the anchor is the tooltip's own `margin-top` in
  `default.css`, and the wrap width is `tooltip .__label__ { max-width }`. The max-width has to sit on
  the *label*, because `UIText` only wraps against a width it can see on itself.
- Known gaps: no show delay (a delay is a timing value and belongs in the cascade — that needs a real
  CSS property); no platform-delegated tooltips yet (an item's real MC tooltip must be drawn by the
  loader, which needs a new SPI).

---

## 11. `Dialog`

A floating, movable panel — the web's `<dialog>`, **modeless** form.

```java
Dialog panel = new Dialog("Inspector");
stage.addChild(panel);          // stage is the containing block: dialogs are position: absolute
panel.moveTo(20, 20).show();
panel.getContent().addChild(...);
```

**Named `Dialog`, not `UIWindow`** — that name already means the runtime/Document analogue, and
reusing it would be actively misleading.

**Modeless on purpose.** Only `showModal()` adds a dialog to the top layer; `show()` leaves it in
ordinary flow and ordinary stacking. That is what lets several editor panels coexist and order among
each other — and against page content — by `z-index`.

**`showModal()` is implemented**, and it is where `inert` earns its keep. It does three things `show()`
does not: joins the **top layer**, makes everything outside it **inert**, and **closes on Escape** via a
close watcher (a cancelable `onCancel`, then `close()`). *Focus trapping is not a fourth feature* — it
falls out of inertness, which is why there is no trap code anywhere. Nesting works and unwinds in order.

A modal also gets a `__backdrop__` scrim: an internal child promoted to the top layer just *before* the
dialog, so it paints behind it and covers the whole window (`100%` against the initial containing block,
same as the dialog's own offsets). Not a `::backdrop` pseudo-element — the style engine has none — but the
same idea via the substitute the widgets already use. It is `setHitTest(false)` and inert, because it is
decoration rather than a control.

> **Escape on a *modeless* dialog still does nothing**, and that is not an omission — only `showModal()`
> establishes a close watcher, so browsers behave the same way. A live drag also eats Escape ahead of the
> modal, because a drag is the innermost live interaction.

**Escape does not close it, and that is correct.** Only `showModal()` "establishes a close watcher",
the machinery that turns a close request into a `cancel` event and then a close — so browsers do not
close a modeless dialog on Escape either. The affordance is the `__close__` button instead, which
browsers leave to the author because their dialogs ship no chrome. (Escape *during a drag* cancels the
drag — `UIInputHandler` consumes it before focus routing, so the innermost live interaction wins.)

**Focus** follows the spec: the focus delegate (first focusable descendant) else the dialog itself on
`show()`, and on `close()` focus returns to whatever held it beforehand. Clicking the title bar also
focuses the dialog — without that the ring is only ever produced programmatically and looks like a
glitch, since `FocusPolicy.FOCUSABLE` excludes click.

**Moving is ours** — nothing in CSS or HTML moves an element by pointer. It runs on the P2 positional
drag from `__title-bar__`, and writes `left`/`top` at **`INLINE`** origin, matching what CSS `resize`
mandates for the size it writes. One rule covers both: user-driven geometry is inline, so an author's
`!important` can pin a dialog.

> **The position is a field, never re-read from the resolved box.** The re-clamp ticker runs during
> `advanceFrame`, *before* layout, so just after a reopen the box is still the zero-sized
> `display: none` one — deriving position from it snapped every reopened dialog to (0,0).

**The title ellipsizes, and it is the one place this engine truncates text by default.** CSS does not:
`text-overflow`'s initial value is `clip`, and no browser user-agent sheet ellipsizes generic text —
spilling content is the correct default, since the content is the author's. A title bar is chrome
though, with a close button pinned to its right edge that a long title paints straight over, and every
native window manager ellipsizes window titles for that reason. `default.css` therefore gives
`__title-bar__ .__label__` the web's canonical truncation recipe verbatim: **`flex: 1 1 0; min-width: 0;
white-space: nowrap; overflow: hidden; text-overflow: ellipsis`**. Override on `getTitleLabel()` if the
whole title matters more than the button.

> **`flex-grow`/`flex-basis: 0`, not `flex-shrink: 1`** — and the difference is not cosmetic. Shrinking
> from the label's intrinsic width makes its box depend on its own measured glyphs, so a title that fits
> by a fraction of a pixel truncates anyway and loses a whole real character to an ellipsis it never
> needed. (Observed: `panel one` rendering as `panel on`.) Growing from zero sizes the label to *what is
> left after the close button*, which is the actual intent and has no such edge. `min-width: 0` is what
> permits it at all — a flex item's automatic minimum is its min-content size, which for a `nowrap` line
> is the entire line.

- Tag `dialog` · internal `__title-bar__`, `__content__`, `__close__`, `__label__` on the title text,
  and `__backdrop__` when shown modally · Scenes: `cgui-gallery` (Dialog page, modal page)
- Combines with `resize` (§12): the gallery's second panel is both movable and resizable.

---

## 11b. `Popover`, `Menu`, `MenuItem`, `Dropdown`

The Popover API port, plus the two widgets on top of it. **A dropdown and a context menu are the same
class** — `Menu` — anchored to an element or to a point, which is how the web does it too.

```java
Menu menu = new Menu();
parent.addChild(menu);                    // must be in the tree to be promoted
menu.addItem("Cut").attachListener(...);
menu.showFor(button, button);             // dropdown-style: under an element
menu.showAt(x, y, null);                  // context menu: at the pointer

Dropdown quality = new Dropdown("Quality");
quality.addOptions("Low", "Medium", "High");
quality.attachSelectionListener(index -> ...);
```

`Popover.Mode.AUTO` gets light dismiss + Escape; `MANUAL` gets neither. Placement is
`setPreferredSide` + `setOffset`, resolved by `AnchoredPlacement` — **never set `left`/`top` yourself on
one**, it fights placement every frame.

`Menu` refuses public children (items only), focuses its first item on open, and handles Up/Down (wrapping,
per the ARIA pattern), Home/End; Enter/Space comes from `Button`. The whole menu is **one Tab stop** — its
items are `CLICK_NOT_TABBABLE`, and unlike `Tab` the stop does not rove, because an open menu holds focus
outright and Tab has nothing to do inside it.

**Submenus open on hover**, after `Menu.DEFAULT_SUBMENU_DELAY` (0.4s — Windows' own `MenuShowDelay` default;
`setSubmenuDelay` to change it). Instant opening makes submenus flash as the pointer sweeps past. `addSubmenu`
also adds a `>` indicator in the `__post-icon__` slot (`__submenu-arrow__`, replaceable via `setPostIcon`) and
marks the row `__has-submenu__`. Right arrow opens immediately — a keypress is never an accidental sweep;
Left closes back into the parent.

> **The fade-in hangs off a state class, not a starting style.** A popover opens out of `display: none`, so a
> `transition` on `opacity` has nothing to interpolate *from* — the wall `@starting-style` exists to solve.
> `Popover` toggles `__open__`, and `default.css` keeps a **closed** popover at `opacity: 0` and an open one
> at `1`, which gives the transition a real from-value. The duration lives in the sheet; drop that line and
> popovers snap in.
>
> An earlier attempt hand-rolled the starting style — one frame of `opacity: 0` at IMPORTANT origin, removed
> on the next tick — and **silently defeated itself**: that `1 → 0` write is a transitionable change too, so
> the engine eased *toward* zero and the removal retargeted it back before it arrived. Nothing visibly faded,
> and no test noticed. Don't reintroduce it.

**Choosing a leaf closes the whole chain; Escape peels one level.** `Popover.hideChain()` walks the invoker
chain (`parentPopover()`) and closes all of it — what the ARIA pattern means by "activates the item and closes
the menu". `hide()` alone closes a popover and its *descendants*, which leaves a submenu's parent standing.

> **Pass an invoker only for a *toggle*.** `showFor`/`showAt`'s invoker is excluded from light dismiss, which
> is what a dropdown button needs (its own press must not close the menu it just opened) and wrong for a
> context menu — naming its trigger surface makes that whole surface unable to dismiss the menu. Nothing is
> lost by passing `null`: a popover opened during a press is already protected from that press.

**Menus do focus-follows-hover.** Hovering a row focuses it via `UIInputHandler.requestPointerFocus`, so
exactly one row is ever highlighted and the keyboard continues from wherever the pointer left off.
`requestPointerFocus` is the no-ring, no-scroll variant — a focus ring trailing the mouse is what
`:focus-visible` exists to prevent, and `menuitem`'s row highlight is its focus affordance instead.

**Submenus go through `Menu.addSubmenu(label, child)`**, never `addItem` plus a listener. It wires the three
things that are easy to forget: the item does not close its parent (an ordinary item does), the child anchors
to the *row* rather than to the menu, and it prefers `Side.RIGHT` so it sits beside rather than on top.
`MenuItem.getSubmenu()`/`hasSubmenu()` expose the relationship. Closing a parent closes its submenus.

`Dropdown` is a `Button` that owns a `Menu` as an internal child and keeps its label in step with the
selection. Pressing it **toggles**. Its `writeState` records the **index, not the text** — the label is
derived, so restoring the text would put the right words on a control that still thinks nothing is selected.

- Tags `popover`, `menu`, `menuitem`, `dropdown` · internal `__items__` (Menu), `__menu__` (Dropdown)
- Scene: `cgui-gallery` (menus page — dropdown, context menu, submenu)
- Known gap: a decoded `menu` comes back **empty**, and a `dropdown` with no options — items and options
  live in internal containers, the same gap `TabView` has with its tabs.

---

## 12. `resize` — an element capability, not a widget

> **Leading-edge handles exist only for out-of-flow elements.** A top or left handle must move the origin
> so the opposite edge stays put, and `left`/`top` only *place* an absolutely positioned box — on an in-flow
> one they are a relative offset that slides it over the sibling above while everything below carries on as
> if nothing moved. So an in-flow element gets **right, bottom and the bottom-right corner**, which is
> exactly the set CSS offers (and CSS never moves the box at all); a positioned one like `Dialog` gets all
> eight. The set is rebuilt when `position` changes, since `position` and `resize` are independent.

CSS `resize` (CSS UI 4) is **ambient on any element**, exactly as `overflow` makes any element a
scroll container. Setting it adds an internal `__resizer__` grab handle; clearing it removes one.

```java
panel.generalStyle(g -> g.resize(Resize.BOTH));   // or in CSS: resize: both;
```

- The resulting size is written at **`INLINE`** origin — the spec says the UA replaces declarations in
  the style attribute *"without `!important`"*, so an author's `!important` still wins. Every other
  code-driven geometry write in this engine uses `IMPORTANT`; this is the deliberate exception.
- **No clamping in the resizer**: `min-width`/`max-width`/`min-height`/`max-height` are the spec's only
  constraints and Taffy already applies them.
- **Eight handles** — four edges, four corners. *Not* a divergence: the spec says only that the UA
  "presents a bidirectional resizing mechanism" and never prescribes one corner grabber. Which handles
  exist follows the resizable axes, so `horizontal` gets the two side edges and no corners.
- **A leading edge moves the box too** — growing leftwards keeps the right edge still, which CSS's
  single bottom-right grabber exists to avoid ever needing. `Dialog` overrides `applyResizeOrigin` so
  its own clamped position stays the source of truth rather than being overwritten each frame.
- **Divergences**: applies regardless of `overflow` (browsers restrict it to scroll containers, half a
  rendering artifact of the grabber living in the scrollbar gutter); and no `block`/`inline` values,
  which are writing-mode-relative and would be silent aliases here.
- **Anything resizable should normally also set `overflow`** — the other half of that restriction did
  real work, guaranteeing a resizable box contains its content.

The rule this establishes, worth reusing: **if the web expresses it as a CSS property, make it ambient
on `UIElement`; if the web expresses it as an element, make it a widget.**

---

## 12b. `CanvasView` — the pan/zoom plane

`com.crystalgui.ui.elements.canvas` · tag `canvasview` · `cgui-gallery` → **canvas** page · P6.2.2

An unbounded plane viewed through a fixed window: wheel-zoom about the cursor, middle- or
Space+left-drag to pan, `fitToContent()`, and off-screen culling. The substrate the node graph sits
on, and deliberately not graph-specific — nodes, ports and wires are 6.2.3's.

```java
CanvasView canvas = new CanvasView();
canvas.addNode(box, 120f, 80f);              // world coordinates
canvas.zoomAt(2f, event.getPosition().x(), event.getPosition().y());
canvas.fitToContent(30f);
Vector2f world = canvas.screenToWorld(rawX, rawY);
```

```
CanvasView            overflow: hidden — the window, and the element gestures are read on
  └── __content__     absolutely positioned, carries translate(pan) scale(zoom)
        └── nodes     the caller's elements, positioned in world coordinates
```

- **It is a viewport, so it refuses public children.** A child of the viewport would sit outside the
  transform and stay nailed to the screen while everything else panned. Use `content()` or `addNode`.
- **A positive wheel notch means the wheel rolled *down*, so it zooms out.** The sign is not
  guessable and the only thing in the repo that states it is `ScrollerView`, which grows `scrollTop`
  by a positive delta. Taking it at face value ships a canvas that zooms in when you scroll down —
  wrong in a way every user notices immediately and no test catches, because a test written from the
  implementation agrees with the implementation. Pinned by asserting both consumers in one test.
- **Zoom is CSS `transform`**, which is layout-free by construction — scaling the plane cannot reflow
  anything, and `UITransform.applyTo` is shared by the render pose and the hit-test chain, so clicks
  follow the picture with no code in the widget. That is why this is a few hundred lines.
- **`transform-origin` is pinned to `0 0` at IMPORTANT.** It defaults to 50% and every conversion here
  assumes the plane scales about its top-left; a theme setting it would offset the whole canvas by
  half a viewport, scaled. Pinned rather than compensated for, so there is one answer rather than two.
- **`pan` is measured after zoom** — a screen-space offset, not a world one. That is what makes a pan
  drag a plain addition of the pointer delta at any zoom. `centerOnWorld` is there for when you
  genuinely want world units.
- **A bare left-drag does not pan.** It is reserved for 6.2.4's marquee. Middle-drag always pans;
  Space+left is the escape hatch for a mouse with no usable middle button (Figma/Blender/Photoshop's
  answer). The gesture is read in the **capture** phase so it beats whatever is under the cursor.
- **Three coordinate spaces**: world (what you author), logical (what `RuntimeCache.getX()` and
  `screenToLocal` speak), physical (raw pointer pixels). `screenToWorld` crosses all three;
  `worldToViewport` returns *logical*, not physical, and says so.

### Culling skips paint, not layout

Off-screen nodes get `opacity: 0` at IMPORTANT origin, which `drawSubtree` early-returns on.
`display: none` is the obvious choice and is wrong here: a culled node's layout collapses, and its
layout rect is precisely the input the cull decision is computed from — so it could never be
un-culled without a cache of where it used to be, which goes stale the moment anything moves it.
Keeping layout live makes the decision self-correcting on every tick, and costs no relayout as nodes
cross the viewport edge, which panning does constantly. What is given up is layout cost for
off-screen nodes, which is the smaller half: layout recomputes only when dirty, paint happens every
frame.

The pass runs from a `UIFrameTicker` rather than only on view changes, because a node can move
without the view moving and the canvas gets no notification of a child's layout change. It is one
AABB test per node.

> **A node is culled by its box, so anything that paints outside its box must declare the region it
> draws in.** The gallery's wire layer is the case: it paints Béziers across the whole graph from an
> element whose natural size is `auto`, i.e. 0×0 at world origin — so it would be culled the instant
> you panned off that one point, taking every wire with it while every node stayed visible. Giving it
> the grid's extent fixes it *and* makes the cull correct: the wires now disappear exactly when no
> wire is on screen. 6.2.3's real wire layer inherits this.

---

## 12c. The node graph — `GraphView`, `GraphNode`, `NodePort`

`com.crystalgui.ui.elements.graph` · tags `graphview` / `graphnode` / `nodeport` ·
`cgui-gallery` → **graph** page · P6.2.3

Unity Shader Graph's construction, literally: a title bar, two port columns, controls, a preview slot,
and wires that take their colour from the ports they leave.

```java
GraphView graph = new GraphView();
GraphNode position = new GraphNode("Position");
NodePort out = position.addOutput(VEC3, "Out");
position.addControl("Space", spaceDropdown());
position.preview();                       // the slot is created on first ask
graph.addNode(position, 20f, 30f);
graph.connect(out, add.addInput(VEC3, "A"));
```

`GraphView extends CanvasView`, so it inherits pan, zoom, fit and culling — and **reports the tag
`graphview`, which matches no `canvasview` rule**. The viewport's structural styling is written from
Java at DEFAULT origin precisely so the subclass gets it for real.

### Ports

- **`PortType` is an interface, not an enum.** The types a shader graph carries are GLSL's, and the
  manifesto is explicit that the graph is a visual editor for the `.shader` format — so CrystalGUI ships
  the interface and the registry, and the consumer ships `float`/`vec3`/`sampler2D`. Compatibility is
  asked of the *source* type and is deliberately asymmetric: GLSL promotes a float to a vec3 and does
  not demote.
- **`type.cssClass()` is `type-<id>`**, carried by the port. The palette — dot and wire alike — is
  therefore a stylesheet's business. `NodePort.typeColor()` reads the dot's computed `border-color`
  back out of the cascade, which is what keeps the number's source in CSS even though
  `CgVectorRenderer` needs an int.
- **One edge per input, many per output.** Dropping onto an occupied input *replaces*; the displaced
  edge leaves through the same `disconnect()` as a manual one, so 6.2.4 can make the pair one undoable
  command.
- **`nodeport:blank` means unconnected** — `isBlank()` is overridden. It drives the hollow-vs-filled dot
  and whether the type's inline editor shows, so both are CSS rather than Java.
- **The dot is decorative; the port is the target.** The dot is 8px and a pointer is not, so the dot and
  label are `setHitTest(false)` and the port's padding is the hit area.

### Nodes

- **The node paints no background.** Each region paints its own and the port band paints *none*, so a
  wire — drawn under every node — shows through and reads as plugged into its dot rather than cut off
  at the border. The title bar and preview carry the corner radii the root no longer paints.
- **Collapse hides unconnected ports**, not just the body — a stylesheet rule over `nodeport:blank`.
- **Selection is `:checked`** via an overridden `isChecked()`. The flag on the node is view state;
  6.2.4 owns the model, and `GraphView.selectNode` is the smallest click behaviour that works until
  then.
- **Dragging moves it in place** — `left`/`top` are rewritten, nothing is rebuilt. The node is a safe
  drag source (unlike the plane in a pan) because layout is not in the transform matrix, so its own
  coordinate space stands still while it moves through it.
- **Clicking focuses it, by asking.** `FocusPolicy.CLICK` is not enough: the input handler focuses the
  exact element hit, which is the title bar. See the note in `GraphNode.focusOnPress`.

### Wires

One `NodeWireLayer` paints all of them in a single `ctx.curve()` batch — a trade: no Taffy node or
draw-call boundary per edge, at the cost of per-wire CSS state. It sits at world (0,0), which makes its
own origin the plane origin, and is **cull-exempt** (`CanvasView.setCullExempt`) because culling asks an
element's box where it is and a painter's box says nothing about where it draws. It culls per wire
instead. Stroke width is clamped against the canvas's zoom so a pose-scaled 2px wire does not vanish at
0.2×.

---

## 12d. Native content — `ItemSlot`, `FluidSlot`

**The one place a host draws inside a CrystalGUI element.** Everything else in this engine paints
through `CgUiPaintContext`; an item stack cannot, because on 1.7.10 it is fixed-function GL, on
1.20.1+ it is Minecraft's own core shaders, and on any version a mod may have replaced the renderer
for its own items. So the host draws it and we hand GL over.

### The element is ordinary; only the middle is not

```
super.paintSelf   → the styled box: background, border, radius, from the stylesheet
nativeContent(…)  → the host's renderer, inside the CONTENT box
paintOverlay      → CSS `overlay`, plus whatever the subclass adds as internal children
```

Nothing about `drawSubtree` changes to make that work — the three layers are hooks that already
existed. A slot is not a special kind of element; it is an element with an unusual middle.

Geometry is `ua/widgets.css` (18×18, 1px padding → the classic 16×16 content box) and appearance is a
theme's (`ore.css` reuses the atlas's inset `textfield` sprite). **Nothing about what the host draws
is styleable**, which is why the UA rule carries no colour at all and no theme token was added.

### `NativeProfile` — two contracts, not one escape

| Profile | Contract | Consumer |
|---|---|---|
| `FLAT` | blended, depth **off** | fluids: tiled atlas quads |
| `MODEL` | depth + lighting, isolated target | items: real 3D models. Later, entities |

They are genuinely different renderers — LDLib2's own fluid path explicitly *disables* depth while its
item path enables depth test and write — so one bracket would either overcharge the fluid or leave the
item without the depth buffer it cannot be correct without.

### `NativeContent` is a BINDING, never a value

`ItemSlot` holds a handle and re-reads it every frame; it never stores a stack. For a container UI
that means Minecraft's own synchronisation keeps it current and **no item data crosses CrystalGUI's
wire**. What serialises is the `descriptor()` — a location (`slot:12`), not its contents — so a
dedicated server can describe an inventory it has no way to draw.

This is the shape LDLib2 arrived at (`private Slot slot`; `getValue()` is `slot.getItem()`), and it is
read for shape only: LDLib2 is **LGPL-3.0** and nothing is ported from it.

> **Interaction is not built.** A slot renders and describes; it does not yet move items. LDLib2
> reaches that with a mixin, but only because it lets *vanilla* own the hit-test. `UIInputHandler`
> already has three-phase dispatch, pointer capture and a drag protocol, so the follow-up takes its own
> press and asks the platform to perform a slot action — no mixin, at the cost of owing vanilla's
> slot-click *semantics*. The binding handle is the seam that keeps it a follow-up.

### Three platform states, and the middle one is the point

| State | How | Result |
|---|---|---|
| Available | `CgPlatform.provide(SERVICE, impl)` | native draw |
| Declared unneeded | `CgPlatform.provide(SERVICE, UNSUPPORTED)` | `__unsupported__` face, no crash |
| Nobody said anything | — | **throws at first paint** |

At paint rather than construction, because a dedicated server legitimately builds a slot to describe
and has no renderer to want. The harness and `TestPlatformService` both declare `UNSUPPORTED`
explicitly — that is the feature working, not a workaround.

## 13. Harness scenes

```bash
./gradlew :gl-debug-harness:runHarness --args="--mode=cgui-gallery"
```

`cgui-gallery` is the front door: every widget, one page each, with a live Ore ⇄ default theme toggle.
The rest are focused regression scenes with staggered pixel captures — reach for those when you are
changing one widget's behaviour.

| Mode | Covers |
|---|---|
| `cgui-gallery` | all twelve widgets + theme toggle |
| `cgui-button` | `Button` — activation, drag-off cancel, keyboard, sound |
| `cgui-checkbox` | `Checkbox`, `CheckboxGroup` (allowEmpty vs required) |
| `cgui-switch` | `Switch` — captures mid-slide to prove interpolation |
| `cgui-slider` | `Slider` — drag/click/keys/wheel, forced states, stepped |
| `cgui-textfield` | `TextField` — nine fields covering both validation tiers, binding, clipboard |
| `cgui-text` | `UIText` — auto-size, wrap, font fallback, live binding |
| `cgui-text-stress` | many text nodes at once — shaping/layout cost, retained-shape reuse |
| `cgui-scroller` | `ScrollerView` vs programmatic-only element scrolling |
| `cgui-splitview` | `SplitView` — both orientations, nesting, oversized content |
| `cgui-tabview` | `TabView` — four sides, arrow nav, strip overflow |
| `cgui-ore-theme` | the Ore theme + forced hover/pressed/focus/checked/disabled matrices |
| `cgui-styling` | selectors, cascade, `!important`, transitions, background cross-fades |
| `cgui-visual-layers` | overflow masking, opacity isolation, scissor vs mask |
| `cgui-nineslice` | 9-slice tiling modes, CPU 9-quad path vs SDF shader path |
| `cgui-test` | the original raw-`UIElement` DOM smoke test |

All are `INTERACTIVE` and **stay open until you close the window** — they do not exit on their own.
Kill lingering `java.exe` processes matching `harness` after a run.
