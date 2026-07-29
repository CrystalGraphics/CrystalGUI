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
when focused. Fires `UISoundSystem` with `button_click` if a platform registered one.

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
`CrystalGuiCore.getClipboard()`, wheel-steps-the-value (`stepBy(notches)`), and Escape to revert.

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

- Tags `tabview`, `tab` · internal as listed in §0 · `tab:checked` works via the overridden `isChecked()`
- Scenes: `cgui-tabview` (four sides, strip overflow, focus exclusion), `cgui-gallery`
- Known gap: tabs and panes do **not** round-trip through the codec — they live in internal containers.

---

## 10. Harness scenes

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
