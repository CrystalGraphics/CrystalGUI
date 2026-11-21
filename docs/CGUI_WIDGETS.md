# CrystalGUI Widgets

> Reference for `widget/`, in three tiers a build checks (`LayeringTest`): `control`, `display`, `text` and `scroll`
> name nothing above them; `overlay`, `layout` and `dnd` name only those and each other; everything else
> (`collection`, `composite`, `config`, `canvas`, `surface`, `graph`, `texteditor`) may name any widget. **A widget's
> tier is what it composes**, not what it is: `Dropdown` holds a `Menu`, so it cannot be in `control`.
>
> Companions: `CGUI_STYLE_RENDER_PIPELINE.md` (the cascade and painting), `CGUI_SERVER_AND_SERIALIZATION.md` (how
> state travels).

## 0. Conventions — read this before writing a widget

### A widget declares that its structure is fixed

`refusePublicChildren()`, in the constructor every other one chains through. It is a promise, not derived from slots:
an unslotted light child is legal.

| Accepts children | How you put content in it |
|---|---|
| `UINode` | `append` |
| `ScrollerView` | `append`: it is the viewport, through its slot |
| `SplitView` | `first()` / `second()`, or `first(content)` / `second(content)` |
| `TabView` | never directly: `addTab(label).content()` |

Give a new widget a named accessor for its content rather than opening its tree.

### Structure is a shadow tree; a caller's content needs a slot

`attachShadow()` plus `appendStructural`, each part named for `::part(name)` (`mark`, `thumb`, …).

- **A widget that takes content gives its slot a home**: a light child of a shadow host with no default slot has no
  box and no paint, and nothing reports it.
- **The slot must be told to fill**: `align-items: stretch` on the host reaches the slot and stops there.

### A widget hosts a shadow tree only if nothing reaches through its structure

`::part()` cannot name a part under a part, a tag under a part, or a nested widget's part. A subclass cannot un-shadow
its parent (`Dropdown extends Button`), so decide the base class first. What a shadow tree buys is the slot: a
caller's content cannot land among the widget's parts.

### A pseudo-class after `::part()` describes the part, not the host

`dropdown::part(menu):open` asks whether the menu is open.

### A subclass inherits its parent's `Name` unless given its own

A widget meant to be extended takes a **protected `(Name, ...)` constructor**. Decide: pass the supertype's `NAME`
and add a class to share its look, or declare your own.

### A widget that paints its own content must be `Measurable`

`TextField` draws its glyphs and has no children, so without a measure it lays out zero-high under a theme that does
not size it.

### A widget states how small it can get

A window is never resized below its content's needs (Windows' `WM_GETMINMAXINFO`). `WindowFrame.minimumSize` is its
caption and borders plus `LayoutMinimum.of(content)`, never under the sheet's `min-width`/`min-height`; a drag, the
keyboard's Size mode, `resizeTo`, a snap and a restore all stop there. A smaller work area wins, and the content
scrolls.

```css
.my-app-root { min-width: 400px; min-height: 270px; }   /* the common case: stated in the sheet */
```

```java
class Board extends UIElement implements MinimumSize {  // a floor that depends on what is open inside
    public float minimumSize(boolean vertical) { return SplitView.minimumOf(inner, vertical); }
}
```

`LayoutMinimum` folds content that states neither: a flex container's children summed along its main axis with gaps,
the largest across it, inside padding and border; a child that neither grows nor shrinks at its laid-out size; text at
its line height and as wide as its longest word (its whole line under `white-space: nowrap`, nothing with
`text-overflow: ellipsis`). A box that scrolls or clips forces nothing.

A window's content fills its width as a page does (`ScrollerView.setFitsCrossAxis`), wrapping what may wrap. Overflow
under half a pixel is none (`Box.maxScrollLeft`).

### A listener on a shadow host can never see its own parts

`event.getTarget()` is retargeted to the host. Attach inside the shadow tree and `stopPropagation()` there.

### A widget's CSS identity is its tag, not its Java supertype

`Dropdown`, `MenuItem` and `Tab` extend `Button`, and `button { … }` matches none of them. When you subclass, extend
the selector list or give it its own block; doing neither gives an unstyled control.

### No sizes, no timings, no colours in Java

Widgets write structure and state; the user-agent sheet gives geometry, a theme gives appearance. `Switch`'s knob is a
`transition` in CSS, not a Java tween. Widget baseline styling goes in at `DEFAULT` origin:

```java
StyleGroup.defaultPipeline(getStyle().getLayoutGroup(), l -> l.flexDirection(FlexDirection.ROW));
StyleGroup.importantPipeline(getStyle().getGeneralGroup(), g -> g.display(NONE));  // the rare opposite
```

`importantPipeline` is for state no sheet may override (`Tab` hides a deselected pane so).

### Pseudo-classes come from getters

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

Override the getter to get the pseudo-class (`Tab.isChecked()` is all of `tab:checked`). `:focus-visible` is true for
keyboard and programmatic focus, false after a click, except on text inputs (`consumesTextInput()`). Names resolve
through `PseudoClasses.lookup`.

### A surface that owns a caret says so — `setRetainsFocus`

A press finding no click-focusable ancestor clears focus (Blink's `HandleMouseFocus`). For a widget built around one
field that leaves the window with no focus owner:

```java
popup.setRetainsFocus(true);   // a press on scenery in here leaves the caret where it is
```

Subtree-wide, deferring to anything click-focusable inside (a `ListView` row still takes its press). Focus is
assigned before dispatch, so `preventDefault()` cannot do this. Hand focus back with `requestPointerFocus`, never
`requestFocus`, which scrolls its target into view.

### Listeners, hooks, tags

- **`attachListener(l, capture, bubble)` always subscribes the target phase**; the booleans are additive.
- **Per-frame work is an `Animation` hook owned by the node** (`document.animation()`), stopping when it leaves the
  tree (`Scroller`'s press-and-hold, `ScrollerView`, `TextField`'s caret blink).
- **Anything reading geometry uses `afterLayout`**: an ordinary hook runs before this frame's layout.
- **Declare the tag** as a `NAME` constant and register it from a `NodeKinds` service (`UIElementRegistry.bootstrap()`
  runs them). Without it the widget has no `tagName()`, no type selector, and cannot be serialized.
- **Say what a tree calls it and draws for it** with a `KindInfo` at registration:

  ```java
  UIElementRegistry.register(MachineGauge.NAME, MachineGauge::new, CONTRACT,
          KindInfo.named("Machine Gauge").glyph(GlyphRole.CONTROL));   // ships mymod:nodes/ui/machinegauge.svg

  KindInfo.named("Heat Field").glyphOf(Slider.NAME);                   // draws the slider's glyph and role
  KindInfo.named("Reactor").glyph("mymod:machines/reactor", GlyphRole.COLLECTION);   // a file named otherwise
  ```

  | Your kind declares | The row draws |
  |---|---|
  | a glyph | it: a 16-unit SVG in `currentColor`, tinted by the role's `--kind-glyph-*` token |
  | nothing, and extends a kind that has one | that kind's glyph, under your class name split at capitals |
  | nothing, and lays out children | its layout from computed style (column, row, wrap, grid), or a frame while empty |
  | nothing else | a diamond tinted as an addon, saying *Your Name · yourmod* |

  Declaring a glyph without shipping its file draws nothing.
- **Say where the builder's Library files it, what its card shows and what placing inserts**, in the same `KindInfo`.
  A kind declaring nothing is listed under its namespace, its card drawing what the factory builds:

  ```java
  KindInfo.named("Machine Gauge").inCategory("Machines/Gauges")      // a path nests; mymod's folder otherwise
          .synonyms("dial", "meter").describedAs("A needle over a scale.")
          .starter(() -> new MachineGauge().setValue(40))             // what a drop or double-click inserts
          .preview(Preview.sample(() -> new MachineGauge().setValue(70)).width(80));   // what its card draws

  KindInfo.named("Reactor").preview(Preview.picture("icon(\"mymod:reactor\")"));   // cannot be shown live
  KindInfo.hidden();                                                  // a part, never listed
  ```

  A category is a `/`-separated path, as is a user's Library group (`Mine/Buttons`; **New Group Inside…**; renaming
  or deleting carries subgroups). The shipped categories follow Unity's UI Builder (`Forms` into Numeric, Text,
  Choice, Composite, Value and Structure; `Controls`; `Layout`; `Starters/<folder>`).

  | Supplier | Used by | Defaults to |
  |---|---|---|
  | the factory | a decode, and everything below when nothing else is declared | — |
  | `starter` | placing the kind: the Library, the Hierarchy's New ▸ | the factory |
  | `Preview.sample` | the card only, never placed | the starter |

  A sample's `width` is its logical layout width before the card scales it (a row-filling control needs one);
  `whole()` shrinks it whole; `atCardWidth()` lays it out at the card's width. A look needing a state nothing is in
  uses `presentInline()` (popovers, menus, dialogs, tooltips) or `presentFocused()` (a text field). The shipped
  samples are `widget.WidgetSamples`, styled by `ua/samples.css`.
- **State a server sends or a client reports is declared on a `WidgetContract`**
  (`CGUI_BUILDING_UIS.md` § 11, `CGUI_SERVER_AND_SERIALIZATION.md` § 2).

## 1. `Button`

```java
Button b = new Button("Click me");
b.attachListener(() -> count++);        // or: b.onPressed
b.setText("Now this");
b.setPreIcon(icon);  b.setPostIcon(null);   // null clears
b.setEnabled(false);                        // inherited from UINode
```

Activates on press-then-release over the same element (dragging off cancels), and on Space/Enter when focused. Plays
`button_click` through `CgSounds.play2D`, silent where the host has none.

- Tag `button` · parts `pre-icon`, `post-icon` · pseudo `:hover :active :focus :focus-visible :disabled`
- Scene: `cgui-gallery` (Button page)

A button that opens a menu wears IntelliJ's dropdown gutter, drawn by the user-agent sheet, unhittable; the press is
still yours:

```java
eye.setDropdownMark(true);    // button::part(dropdown-gutter), and __dropdown-mark__ on the button
```

### `ActionButton` — a command or a menu, as an icon (`widget.composite`)

IntelliJ's `ActionButton`: a `Button` by tag carrying `__action-button__`, not a focus stop.

```java
ActionButton.command(TreeViewCommands.COLLAPSE_ALL)          // runs a command
        .icon("crystalgui:general/action/collapseAll")
        .context(tree);                                       // what it resolves against

ActionButton.menu("New File or Directory…", MenuId.EXPLORER_NEW)   // drops a menu down, gutter and all
        .icon("crystalgui:general/action/add")
        .context(tree);

expand.hint(TreeViewCommands.EXPAND_ALL, "Press {} to expand all nodes");   // second tooltip line

ActionButton.command(TOGGLE_ROWS).icon("crystalgui:general/action/viewRows")   // "Show as Rows"
        .whenToggled("crystalgui:general/action/viewCards", "Show as Cards");  // while the command is toggled
```

- The tooltip is IntelliJ's help tooltip: the command's label, its live chord (`Tooltip.setShortcut`) and the
  description or hint (`Tooltip.setDescription`), after the `tooltip.__wait__` delay. `tooltip()` is the observable.
- A command button greys while its command is disabled, re-asked four times a second in a tree.
- A menu button's second press closes its menu.
- `whenToggled` shows what a press switches to, flipping on the press.
- Name the `context` whenever the button sits away from what it acts on; `context(Supplier)` for content replaced
  under it.
- Consumers: every tool window's title line (`TitleActionsContributor`) and its ⋮.

### Drawn as a picture — for a Library card or a style guide

A widget whose look needs a state nothing is in draws it without entering it: nothing is promoted, focused or made
modal, so never `show` one presented this way. Samples are per kind in `widget.WidgetSamples`, styled by
`ua/samples.css`.

```java
new TextField().setPlaceholder("Type here").presentFocused();   // caret, placeholder and :focus, no focus
menu.presentInline();                                           // Popover, Menu, Dialog, Tooltip: open, in flow
```

### A search box over a tree — `SearchTree` (`widget.composite`)

A box that filters while the caret stays in it, a categorised tree for a blank query and a ranked flat list
otherwise, arrows and Enter driving the list. `CreateMenu` wraps one; the builder's Library embeds one.

```java
SearchTree<Row, Kind> search = new SearchTree<>();
search.setRows(rows);                       // roots(query), children, label, isCategory, payload
search.onChosen.connect(kind -> place(kind));
```

A row draws an icon when `Rows.icon(node)` names one, wearing `Rows.iconClass(node)` (`.__entry-icon__`); a category
row never does.

### A searchable popover — `CreateMenu` (`widget.composite`)

`SearchTree` under a draggable title, over a footer. Every one looks alike (Insert, Insert Node, Add property): the
look is keyed on the `__quick-input__` class its constructor adds.

```java
final class ThingMenu extends CreateMenu<Row, Thing> {
    ThingMenu() {
        super(NAME, "Add Thing");
        setRows(rows);
        setHints("↑↓ to choose · Enter to add · Esc to close");   // the footer's resting line
        treeView().onSelectionChanged.connect(i -> setFooterText(describeHighlighted()));
    }
}
```

`setFooterText(null)` restores the hints. Rows are 40 px, set from Java: a virtualised row's height is the view's, and
a sheet `height` on `.__entry__` breaks the tree.

### A surface's Add menu — `InsertMenu` (`widget.surface.insert`)

Every registered `InsertSource`'s offers, searchable, opened at a world point (Blender's Shift+A). Browsing groups by
`path()`; a query ranks label, synonyms and path and skips `browsingOnly()` offers. The footer describes the
highlighted offer.

```java
surface.registerInsertSource(() -> offers);          // Insertable: label, path, synonyms, description, icon, insert
InsertMenu menu = surface.openInsertMenu(worldX, worldY);
menu.header().append(placementLine);                 // a consumer's band above the search
menu.showHeader(true);
menu.onCycle.connect(step -> placement.cycle(step)); // Tab / Shift+Tab in the search box
```

- `onCycle` takes Tab only while something is connected; the header is hidden until `showHeader(true)`.
- The UI builder's consumer is `BuilderInsert`: Shift+Space under the selection, right-click on blank page, *Insert…*
  on a node's menu.

### A kind drawn as itself — `PreviewCard` (`app.uibuilder.library`)

A Library card: the kind's sample in a shadow root wearing the window's sheets, fitted into a tile under a square
scissor, its name below. Samples build a few per frame through `PreviewBuilds`; until then a card shows the glyph.

A **starter** is a snippet of several nodes, first under *Starters*; `LibraryStarters` declares each as a
`LibraryCatalog.Entry` over a shipped `.cgui`:

```java
LibraryCatalog.Entry card = LibraryStarters.CARD;   // id "starter:crystalgui:uibuilder/starters/card"
card.kind();                                        // null: a starter is no one kind, so it joins no user group
card.build();                                       // a fresh tree of the snippet each call
```

### Keeping rows realised — `ListView.setOverscan`

A list realises the viewport plus two rows each side. One with few, costly rows keeps them: `tree.setOverscan(64)`.
Off-screen rows are culled from paint.

## 2. `Checkbox` + `CheckboxGroup`

```java
Checkbox c = new Checkbox("Enable thing");
c.setChecked(true);
c.attachListener(checked -> …);         // or: c.onCheckedChanged

CheckboxGroup group = new CheckboxGroup().allowEmpty(false);
c.setGroup(group);                      // normal usage — not group.register(c)
Checkbox current = group.getCurrent();
```

`CheckboxGroup` is a plain object, not a node. `allowEmpty(false)` gives radio semantics. The mark is drawn by CSS
(`checkbox:checked::part(mark)`).

- Tag `checkbox` · part `mark` · pseudo `:checked :hover :active :focus :focus-visible :disabled`
- Scene: `cgui-gallery` (Checkbox page)

## 3. `Switch`

```java
Switch s = new Switch();
s.setChecked(true);
s.attachListener(checked -> …);         // or: s.onCheckedChanged
```

The knob slides by animating an invisible spacer's `flex-grow`; all timing is CSS.

- Tag `switch` · parts `spacer`, `knob` · pseudo `:checked :hover :active :focus :focus-visible :disabled`
- Scene: `cgui-gallery` (Switch page)

## 4. `Slider`

```java
Slider sl = new Slider();
sl.setRange(0, 100).setStep(5).setValue(40);   // step 0 = continuous
sl.attachListener(v -> …);                     // or: sl.onValueChanged
float f = sl.getFraction();                    // 0..1
```

Drag by delta, click to jump, Left/Right, Home/End, the wheel. Focus policy `CLICK`.

- Tag `slider` · parts `fill`, `thumb`, `spacer` · pseudo `:hover :active :focus :focus-visible :disabled`
- Scene: `cgui-gallery` (Slider page)

## 5. `TextField`

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

- **A char filter refuses what could never be valid; a text validator flags what is not yet valid** but must be
  typable on the way (`-` before `-5`).
- `Mode` is `STRING | INTEGER | LONG | FLOAT | DOUBLE`; a range narrows the derived filter (`0..100` drops `-`).
- Caret blink (`setCaretBlinkSeconds`, `isCaretVisible`), selection (`selectAll`, `clearSelection`,
  `getSelectedText`, `getSelectionStart/End`), paste from the client thread's clipboard, wheel steps
  (`stepBy(notches)`), Escape reverts.
- Styled by `color`, `font-size`, `font-family`, `line-height` (vertical centring only), `caret-width`,
  `selection-color`. The caret and selection band are `ascender + descender` tall; the selection paints only while
  focused (`CGUI_STYLE_RENDER_PIPELINE.md` §8c).

**Input-method composition** (Japanese, Chinese, Korean): a host reports a run in progress through
`Input.consumeComposition(text, caret)`; the field shows it underlined, replacing it on each update, and the committed
characters arrive as ordinary typing. `textInputArea()` answers the caret's box in surface pixels for the candidate
list. `TextEditor` does the same through `::highlight(ime-composition)`. Only Minecraft 26.3 (SDL3) reports a
composition; elsewhere the OS draws it.

```java
// a host with a composing input method -- Minecraft 26.3's, from CrystalGraphics' screen (ScreensModern)
document.input().consumeComposition(preedit.fullText(), preedit.caretPosition());
float[] caret = document.input().textInputArea();   // {x, y, w, h}, or null
```

- Tag `textfield` · pseudo `:blank :invalid :hover :active :focus :focus-visible :disabled`
- Scene: `cgui-gallery` (TextField page)

## 6. `UIText`

```java
UIText t = new UIText("hello");
t.setText("world");
t.bindTextTo(someProperty);             // or: t.text  (Property<String>)
```

**It sizes itself at `IMPORTANT` origin**, so CSS cannot give a bare `UIText` a width: wrap it in a fixed-width slot
for aligned columns.

```java
UINode slot = new UINode().layout(l -> l.width(116));
slot.append(new UIText("label"));
```

- Reads `color`, `font-size`, `font-family`, `text-align`, and `text-offset` (a paint-time nudge, after the wrap width
  is read; percentages per axis of its own box). Wraps at the font's own metrics, not a set `line-height`.
- **Draws text outlines**: `text-stroke`, `stroke-align`, `paint-order` (`CGUI_STYLE_RENDER_PIPELINE.md` §8d).

  ```css
  text { text-stroke: 2px #0B5D8F; }                        /* outline outside the letterform */
  text { text-stroke: 1px #0B5D8F; stroke-align: inset; }   /* an inline, inside it */
  text { text-stroke: 2px #FFF; color: transparent; }   /* hollow: the stroke names its own colour */
  ```

  The width caps at **0.131em** for most faces and **0.056em** for a dense-script face, clamped silently
  (`UIText.maxStrokeWidthEm()` says which). A stroked label stays on the distance-field tier down to 7 px (wide band)
  or 15 px (narrow), below which the outline is dropped. An `inset` or `center` stroke closes thin joints: geometry.
- **`font-size` on a wrapper does not reach a widget's label**: the user-agent `* { font-size: 10 }` is a candidate on
  every element. Put it on the element that draws, or the `::part()`.

- Tag `text` · no shadow parts
- Scenes: `cgui-text` (wrapping, font fallback, live binding), `cgui-gallery`

## 6b. `EmptyState` — what a vacant panel says (`widget.display`)

A heading and short lines over the middle of a panel (IntelliJ's `StatusText`). It never wraps: a narrow panel clips
each line evenly at both ends. `setCentred(false)` aligns a list of dashes on one edge. **The copy is a direction**:
"To <do the thing>:" and the steps, naming the kind of document needed with one example.

A workbench panel declares one and says when it is vacant; its `ViewContainer` swaps it in:

```java
private final EmptyState empty = EmptyState.of(this, "To see a document's structure:",
        "— Open one that has a structure, such as a .cgui file");

private void show(@Nullable Thing thing) {
    empty.setVacant(thing == null);
}
```

`of` records the note on the panel (`EmptyState.NOTE`) and starts vacant; a changing line is rewritten in place.
Outside a container, `new EmptyState(...)`, append it and `setDisplayed` it: it fills its parent absolutely and takes
no pointer.

**A line may end in a key chord**, dimmed against the words; pass it separately, and `null` when there is no binding:

```java
KeyChord chord = Keymap.acceleratorFor(this, ExplorerCommands.GO_TO_FILE);
empty.setLine(0, "— Go to File", chord == null ? null : chord.toString());
```

- Tag `emptystate` · parts `lines`, `heading`, `line`, `chord` · tokens `--empty-state-fg`, `--empty-state-chord-fg`
- Declared by `RunPanel`, `HierarchyToolWindow`, `LibraryToolWindow`, `Inspector`, and `DockGroup` for the empty
  editor's watermark, whose copy is set by the dock's composer (`DockArea.setWatermark`) and reads larger
  (`dockgroup emptystate::part(line)`, `--editor-watermark-fg`)
- Scenes: `cgui-desktop` (the Run, Hierarchy or Library panel with no `.cgui` in front; every editor tab closed)

## 7. `Scroller` and `ScrollerView`

**Scrolling is an ordinary `UINode` capability**: an element with `overflow: hidden`/`auto` scrolls
programmatically (`setScrollTop`, `setScrollLeft`, `setScroll`, `setScrollImmediate`, `scrollIntoView`,
`getMaxScrollTop/Left`, `getScrollWidth/Height`, `getClientWidth/Height`, `setScrollExempt`) and never listens to the
wheel. **`ScrollerView`** is that viewport plus two bars, and takes the wheel.

```java
ScrollerView view = new ScrollerView();
view.append(content);                 // accepts public children — it IS the viewport
view.setScrollbarsVisible(false);       // scrollable, just no visible bars
view.refreshScrollers();                // cheap + idempotent; auto-called from onLayoutChanged
view.verticalScroller(); view.horizontalScroller(); view.corner();
```

The wheel scrolls 40 px a notch; Shift+wheel scrolls horizontally; a horizontal-only view takes the plain wheel; at
either end the event bubbles (scroll chaining).

**`Scroller`** is the bar alone, usable standalone (as `TabView`'s strip bar):

```java
Scroller bar = new Scroller();
bar.setOrientation(Scroller.Orientation.VERTICAL);
bar.setValue(0.5f);                     // 0..1
bar.setVisibleRatio(0.3f);              // thumb length
bar.setStepFraction(0.1f);
bar.attachListener(v -> …);             // or: onValueChanged
bar.onScrollIntent;                     // relative nudges from the step buttons / track paging
```

Step buttons are `display: none` by default; a theme shows them.

- Tags `scroller`, `scrollerview`
- Scene: `cgui-gallery` (Scroller page)

## 8. `SplitView`

```java
SplitView sv = new SplitView();
sv.setOrientation(SplitView.Orientation.VERTICAL);   // adds __vertical__ to the root
sv.first(leftContent).second(rightContent);          // or sv.first().append(…)
sv.setPercentage(35f).setLimits(10f, 90f);           // 0..100, NOT 0..1
sv.attachListener(pct -> …);                         // or: onPercentageChanged
sv.divider();
```

**Percentages are 0..100** (limits default to 5..95): `0.35f` clamps to the minimum and collapses the first pane. The
panes take children; the split does not. Splits nest.

- Tag `splitview` · parts `first`, `divider`, `second`; root gets `__vertical__`
- Scene: `cgui-gallery` (SplitView page)

## 9. `TabView` + `Tab`

```java
TabView tv = new TabView();
tv.setTabSide(TabView.TabSide.LEFT);        // TOP | BOTTOM | LEFT | RIGHT
Tab first = tv.addTab("General");           // the FIRST tab added is auto-selected
first.content().append(page);             // content() is the pane — an ordinary element
tv.addTabAt("Inserted", 1);
tv.selectIndex(2);  tv.getSelectedTab();  tv.getTabs();
tv.removeTab(first);  tv.clearTabs();
tv.attachListener(tab -> …);                // or: onTabSelected — emits null when the last tab goes
tv.strip(); tv.rail(); tv.bar(); tv.panes();   // for styling
tv.setTabOverflow(TabView.TabOverflow.WRAP);    // SCROLL (default) | SQUEEZE | WRAP
tv.stripActions().append(menuButton);          // controls at the strip's end, never scrolled under
```

- **When tabs do not fit** (IntelliJ's "Show tabs in"): `SCROLL` pans the rail and fades the overflowing end;
  `SQUEEZE` shares the width equally, capped at natural width, cutting labels; `WRAP` breaks into rows. The mode is a
  class on the view (`__tabs-squeeze__`, `__tabs-wrap__`). `stripActions()` sits over the strip's end; a strip that
  fills it reserves the width with `padding-right`.
- Structure: a strip holding a rail (a `ScrollerView` with hidden bars, so it pans on the wheel) and a strip bar (a
  `Scroller`), then the panes. `setTabSide` writes `flex-direction` at `DEFAULT` origin and a side class on the root.
- **Panes are not lazy**: `addTab` builds the tab and pane at once, and a hidden pane is `display: none` at
  `IMPORTANT` origin, so identity, listeners and scroll survive a switch. Populate lazily in `onTabSelected` if needed.
- `Tab extends Button`. `Tab.setSelected` is package-private: go through `TabView.selectTab`.
- Keyboard: Left/Right (Up/Down when vertical) step, Home/End jump, focus follows; bubble phase, so a focused widget in
  a pane sees arrows first.
- **A strip is one Tab stop** (ARIA's roving tabindex): the selected tab is `CLICK`, the rest `CLICK_NOT_TABBABLE`.
  `TabView.updateTabStops()` owns that on every selection and membership change, falling back to the first tab.

- Tags `tabview`, `tab` · `tab:checked` through `isChecked()`
- Scene: `cgui-gallery` (TabView page)
- Gap: tabs and panes do not round-trip through the codec.

## 9b. `ContextToolbar`

One toolbar row whose page follows what is going on (Photoshop's options bar, Blender's tool header).

```java
ContextToolbar bar = new ContextToolbar(documentToolbar);   // the base: shown while nothing claims the row
column.append(bar, canvas);

Disposable claim = bar.claim(transformOptions);             // shown in the toolbar's place
claim.dispose();                                            // and back; disposing twice is harmless
bar.shown();                                                // the page on show
```

A surface wires every tool in one call, each tool's `options()` page coming and going with it:

```java
surface.modes().showOptionsIn(bar);

// on the Tool: build once, return the same element every time
public UIElement options() {
    if (options == null) options = new BrushOptions(ctx);
    return options;
}
```

- **The row's height is the sheet's**, never a page's: it clips, so a swap cannot move what is under it.
- **Claims stack**: the newest live claim shows; releasing it reveals the one under it.
- **A page is added on its first claim and kept**, hidden between claims, so its fields keep their values.
- **What does not fit folds from the end** behind a », opening in a popover; put the least-used items last. Folded
  items are hidden, so Tab cannot land on them.
- **`ContextToolbar.separator()`** is a hairline between groups; the fold never leaves one at the row's end.
- **A field on the row is a stop, not a home**: Enter lands it and Escape drops the typing, either handing the keyboard
  back. A page hidden while holding focus gives focus back to whatever had it before.
- Pages arrive through `claim` and `setBase`; a child appended directly is not a page. Fields come from a
  `ToolbarForm` (§12d).

- Tag `contexttoolbar` · pages carry `__context-page__`
- Consumer: the UI builder's toolbar row

## 10. `Tooltip`

```java
Tooltip tip = Tooltip.attach(anchor, "explain this");   // shown on hover, hidden on leave
tip.detach();                                            // remove it again
```

- **A static factory on the widget**, not `UINode.setTooltip`: the core node names no widget, and one call creates the
  tooltip and its listeners together.
- **It is promoted to the top layer** while shown, so no ancestor's clip cuts it (`UIDocument.promote`). It stays a
  child of the anchor, inheriting `color` and `font-family` from there.
- **Closed is `display: none`**, so a hidden tooltip takes no room in its parent's flow.
- **It never takes the pointer** (`setHitTest(false)`), or appearing under the cursor would hide it again.
- **Placement** is recomputed each frame from the anchor's `localToWorld`: below by default, flipping above when there
  is no room, clamped horizontally.
- The gap is the tooltip's `margin-top` and the wrap width is `max-width` on its label part, both in the sheet.

- Tag `tooltip` · part `label` · Scene: `cgui-gallery` (Tooltip page)

## 11. `Dialog`

A floating, movable panel: the web's `<dialog>`.

```java
Dialog panel = new Dialog("Inspector");
stage.append(panel);          // stage is the containing block: dialogs are position: absolute
panel.moveTo(20, 20).show();
panel.getContent().append(...);
```

- **`show()` is modeless**: ordinary flow and stacking, ordered among others by `z-index`.
- **`showModal()`** joins the top layer, makes everything outside it inert (which is the whole of focus trapping), adds
  a `backdrop` scrim behind it, and closes on Escape through a cancelable `onCancel`. Nesting unwinds in order.
- **Escape does nothing on a modeless dialog**, as in browsers; its close button is the affordance. A live drag takes
  Escape first.
- **Focus**: the first focusable descendant, else the dialog, on show; restored on `close()`. Clicking the title bar
  focuses the dialog.
- **Moving** drags the title bar and writes `left`/`top` at **`INLINE`** origin, as `resize` writes size, so an author's
  `!important` pins it. The position is a field, never re-read from the box (a reopened dialog's box is still the
  zero-sized hidden one).
- **The title ellipsizes**, the one place this engine truncates by default, because the close button would paint over
  it: `flex: 1 1 0; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis` on the label. Grow
  from zero rather than shrink from intrinsic width, or a title fitting by a fraction loses a character. Override on
  `getTitleLabel()`.

- Tag `dialog` · parts `title-bar`, `content`, `close`, `label`, and `backdrop` when modal · Scenes: `cgui-gallery`
  (Dialog page, modal page)
- Combines with `resize` (§12).

## 11b. `Popover`, `Menu`, `MenuItem`, `Dropdown`

The Popover API, and the two widgets on it. **A dropdown and a context menu are the same class**, `Menu`, anchored to
an element or a point.

```java
Menu menu = new Menu();
parent.append(menu);                    // must be in the tree to be promoted
menu.addItem("Cut").attachListener(...);
menu.showFor(button, button);             // dropdown-style: under an element
menu.showAt(x, y, null);                  // context menu: at the pointer

Dropdown quality = new Dropdown("Quality");
quality.addOptions("Low", "Medium", "High");
quality.attachSelectionListener(index -> ...);
```

**A popover built per opening removes itself** with `removeWhenHidden()`, after the close fade; `hide()` alone keeps
it parented.

```java
Popover palette = new Popover().removeWhenHidden();
window.topLayerNode().append(palette);   // not beside the button, or presses inside bubble back to it
palette.showFor(button, button);
```

- `Popover.Mode.AUTO` gets light dismiss and Escape; `MANUAL` neither. Placement is `setPreferredSide` + `setOffset`
  through `AnchoredPlacement`: **never set `left`/`top` on one**.
- **Pass an invoker only for a toggle**: it is excluded from light dismiss, right for a dropdown button and wrong for a
  context menu's surface. A popover opened during a press is already protected from it.
- The fade-in hangs off the `__open__` class (closed at `opacity: 0`, open at `1` in the sheet), giving the transition
  a from-value out of `display: none`.
- `Menu` takes items only, focuses its first on open, and handles Up/Down (wrapping), Home/End; Enter/Space come from
  `Button`. **One Tab stop**: items are `CLICK_NOT_TABBABLE`.
- **A menu is never taller than the window**: its rows scroll inside `::part(scroller)`, following the keyboard.
- **Menus focus on hover** through `Input.requestPointerFocus` (no ring, no scroll), so one row is highlighted and the
  keyboard continues from the pointer.
- **An icon before a row's label** comes from its command; a menu with any such row reserves the column on all
  (`__has-icons__`). A checkable row draws its check there instead.

  ```java
  Command.of("new.button", "Button").icon("crystalgui:nodes/ui/button", GlyphRole.CONTROL.cssClass());
  item.setIcon("crystalgui:general/action/add", null);   // on a MenuItem built by hand
  ```

- **Submenus go through `Menu.addSubmenu(label, child)`**: the row does not close its parent, the child anchors to the
  row and prefers `Side.RIGHT`, and a `>` arrow is added (`__submenu-arrow__`, `__has-submenu__`). They open on hover
  after `Menu.DEFAULT_SUBMENU_DELAY` (0.4 s, Windows' `MenuShowDelay`; `setSubmenuDelay`); Right opens at once, Left
  closes back. Closing a parent closes its submenus.
- **Choosing a leaf closes the whole chain** (`Popover.hideChain()`), stopping at a popover that only contains the
  menu; Escape peels one level.
- `Dropdown` is a `Button` owning a `Menu` as a part, its label following the selection; pressing toggles. Its state
  records the index, not the text.

- Tags `popover`, `menu`, `menuitem`, `dropdown` · parts `items`/`scroller` (Menu), `menu` (Dropdown)
- Scene: `cgui-gallery` (menus page: dropdown, context menu, submenu)
- Gap: a decoded `menu` comes back empty, and a `dropdown` without options.

## 12. `resize` — an element capability, not a widget

CSS `resize` (CSS UI 4) is **ambient on any element**, as `overflow` is: setting it adds a `resizer` grab handle.

```java
panel.generalStyle(g -> g.resize(Resize.BOTH));   // or in CSS: resize: both;
```

- The size is written at **`INLINE`** origin, so an author's `!important` wins.
- It clamps to the element's `min-*`/`max-*` and its `MinimumSize` (§0).
- **Handles follow the resizable axes**: eight for `both` on a positioned element. **Leading edges exist only out of
  flow**, since only there does `left`/`top` place the box; an in-flow element gets right, bottom and the corner.
  `Dialog` overrides `applyResizeOrigin` so its own position stays the truth.
- Divergences: applies regardless of `overflow`, and no `block`/`inline` values. A resizable box should normally set
  `overflow` too.

**If the web expresses it as a CSS property, make it ambient on `UINode`; if as an element, make it a widget.**

## 12b. `CanvasView` — the pan/zoom plane

`widget.canvas` · tag `canvasview` · `cgui-gallery` → canvas page

An unbounded plane through a fixed window: wheel-zoom about the cursor, middle- or Space+left-drag to pan,
`fitToContent()`, off-screen culling.

```java
CanvasView canvas = new CanvasView();
canvas.addNode(box, 120f, 80f);              // world coordinates
canvas.zoomAt(2f, event.getPosition().x(), event.getPosition().y());
canvas.fitToContent(30f);
Vector2f world = canvas.screenToWorld(rawX, rawY);
```

```
CanvasView            overflow: hidden — the window, and the element gestures are read on
  └── content         absolutely positioned, carries translate(pan) scale(zoom)
        └── nodes     the caller's elements, positioned in world coordinates
```

- **It refuses public children**: a child outside the transform would stay nailed to the screen. Use `content()` or
  `addNode`.
- **A positive wheel notch means rolled down, so it zooms out** (the sign `ScrollerView` uses for `scrollTop`).
- **Zoom is CSS `transform`**, layout-free, and `Transform.applyTo` serves both the pose and hit testing.
- **`transform-origin` is pinned to `0 0` at `IMPORTANT`**.
- **`pan` is screen-space**, after zoom; `centerOnWorld` for world units.
- **A bare left-drag does not pan** (it is the marquee's). Gestures are read in the capture phase.
- **Three spaces**: world, logical (`Box.x()`, `toLocal`), physical (raw pointer). `screenToWorld` crosses all three;
  `worldToViewport` returns logical.

**Culling skips paint, not layout**: an off-screen node gets `opacity: 0` at `IMPORTANT` origin, keeping its layout so
the decision (made from that layout each frame, one AABB test a node) can reverse. **A node is culled by its box**, so
anything painting outside its box declares its region or is cull-exempt (`setCullExempt`).

## 12c. The node graph — `GraphView`, `GraphNode`, `NodePort`

`widget.graph` · tags `graphview` / `graphnode` / `nodeport` · `cgui-gallery` → graph page

Unity Shader Graph's construction: a title bar, two port columns, controls, a preview slot, and wires coloured by
their ports.

```java
GraphView graph = new GraphView();
GraphNode position = new GraphNode("Position");
NodePort out = position.addOutput(VEC3, "Out");
position.addControl("Space", spaceDropdown());
position.preview();                       // the slot is created on first ask
graph.addNode(position, 20f, 30f);
graph.connect(out, add.addInput(VEC3, "A"));
```

`GraphView extends CanvasView` (pan, zoom, fit, culling) and reports `graphview`, which matches no `canvasview` rule;
the viewport's structural styling is written from Java at `DEFAULT` origin so the subclass has it.

**Ports**

- **`PortType` is an interface**: CrystalGUI ships the registry, a consumer the types. Compatibility is asked of the
  source type and is asymmetric (a float promotes to a vec3, not back).
- **`type.cssClass()` is `type-<id>`**, so the palette is the sheet's; `NodePort.typeColor()` reads the dot's computed
  `border-color` for the wire.
- **One edge per input, many per output**: dropping on an occupied input replaces, through the same `disconnect()`.
- **`nodeport:blank` means unconnected** (hollow dot, inline editor shown).
- **The port's padding is the hit area**; the dot and label are `setHitTest(false)`.

**Nodes**

- **The node paints no background**: each region paints its own and the port band none, so wires show through.
- **Collapse hides unconnected ports** (a rule over `nodeport:blank`).
- **Selection is `:checked`**; `GraphView.selectNode` is the click behaviour.
- **Dragging moves it in place**, rewriting `left`/`top`.
- **Clicking focuses it** through `GraphNode.focusOnPress`, since a press focuses the exact element hit.

**Wires**: one `NodeWireLayer` paints all of them in one `ctx.curve()` batch, at world (0,0), cull-exempt and culling
per wire. Stroke width is clamped against zoom so a wire never vanishes zoomed out.

## 12d. The config kit — `ConfigForm` over `Property`

`widget.config` · tags `configuratorpanel` / `configurator` / `configuratorgroup` and one per control
(`numbercontrol`, `slidercontrol`, `booleancontrol`, `selectcontrol`, `textcontrol`, `colorcontrol`, `vectorcontrol`,
`matrixcontrol`, `maskcontrol`, `assetcontrol`, `arraycontrol`, `anchorcontrol`, `infocontrol`, `notecontrol`,
`headercontrol`) · `cgui-new-gallery` → config kit

Blender's `layout.prop`, drawn as Unity's inspector. A `ConfigDescriptor` says what a field is, a `Property` where its
value lives, and a form builds the control and binds it: an inspector tab, a preferences page, a tuning window and a
tool's toolbar page alike.

```java
// An inspector section
public void build(ConfigForm form, DataContext ctx) {
    Node node = ctx.get(NODE);
    form.header("Transform");
    form.prop(ConfigDescriptor.number("rotation", "Rotation").unit("°"),
            Property.derived(node::rotation, node::setRotation).editedIn(document.undoStack()));

    ConfigForm about = form.group("About", true);
    about.prop(ConfigDescriptor.info("id", "Id"), Property.derived(node::id));   // read-only
}
```

```java
// A tool's page in a ContextToolbar: a letter per number, the full name on hover
ToolbarForm form = ToolbarForm.into(this);
form.prop(ConfigDescriptor.anchor("pivot", "Pivot"), pivot);
form.separator();
form.prop(ConfigDescriptor.number("w", "Width").shortLabel("W").unit("%"), scaleX)
        .describeWith(Property.derived(() -> "Width: " + Math.round(box.width()) + " px"));
form.prop(ConfigDescriptor.bool("link", "Maintain aspect ratio").toggle(true).shortLabel(""), linked);
```

```java
// A panel of its own
ConfiguratorPanel panel = new ConfiguratorPanel();
PanelForm form = panel.form();
form.note("Rim is the hairline at the boundary; glow is the broad falloff.");
form.prop(ConfigDescriptor.number("blur", "Blur").range(0f, 40f),
        Property.derived(() -> (double) glass.getBlurRadius(), v -> glass.setBlurRadius(v.floatValue())));
```

A value stored in another shape is mapped, never converted in the control:
`stored.map(ShaderColorFieldWidget::parseVec4, ShaderColorFieldWidget::formatVec4)`.

| Kind | Property type |
|---|---|
| `NUMBER` — a slider when it has a `range` | `Double` |
| `TEXT`, `SELECT`, `ASSET`, `INFO`, `NOTE` | `String` |
| `BOOLEAN` | `Boolean` |
| `COLOR` | `Integer` ARGB |
| `VECTOR`, `MATRIX`, `ANCHOR` | `double[]` — an anchor is `[x, y]` as fractions of the box |
| `MASK` | `Set<String>` |
| `ARRAY` | `List<Object>` |

- **A bound field needs nothing wired**: an edit writes the property; a change elsewhere shows at once for a stored or
  `announcedBy` property, after the next layout for a polled one, and only while the field is in a tree. A host reacts
  in the property's writer.
- **A field holding a typed edit is never overwritten** (Dear ImGui, Unity).
- **A scrub or slider drag is one step** of the property's `editedIn` history.
- **`setValue` on a bound control writes the model** and emits nothing; a user edit emits `changed`.
- **The label is the scrub handle**: a pixel is a hundredth of the `range`, or one unit; Shift ×10, Ctrl ÷10;
  `scrubRate` overrides.
- **Prefer `prop` to `row`**: `row` reads a value once and follows nothing.
- **A toolbar cell shows the `shortLabel`**, the full label on hover; `group` in a toolbar starts a cluster behind a
  separator.
- **A kind with no registered control throws.**
- A `PanelForm` group remembers being closed across a refill.
- Geometry is `ua/inspector.css`.

#### Refilling — a panel that follows a selection

`refill` keeps what the last fill placed wherever this one places the same thing, so the next node of the same kind
costs its values, not a new subtree. The `Inspector` fills its tabs this way.

```java
panel.refill(form -> {
    form.header("Transform");
    form.prop(ConfigDescriptor.number("x", "X"), node.x());   // last fill's row, bound to this node
    Configurator left = form.prop(ConfigDescriptor.text("left", "Left"), node.left());
    left.addClass(SET_CLASS);                                  // reset for you before the next fill
    left.decorations().add(scrub.attach());                    // taken off by the next fill
});
```

- **A `prop` row is kept when its descriptor has the same shape** (`ConfigDescriptor.sameShape`); its control adopts
  the new descriptor and binds the new property. State the whole descriptor before placing it.
- **A group is kept by its title**, a `separator` by position.
- **A `custom` element or a `control` row's control is kept only when `Refillable`**; otherwise replaced.
- **Use what a placement returns**: it is the element on screen.
- **A kept row forgets what the last fill did to it** (classes, hidden or inert, `editedIn`). A listener, watch or hook
  goes in `row.decorations()`, or it acts on the previous selection.
- **A control reads its descriptor when it asks**, through `descriptor()`; derived state re-derives in
  `descriptorAdopted`.

A descriptor also says how a field reads:

```java
ConfigDescriptor.text("id", "id").placeholder("none").description("Unique in the document.")
ConfigDescriptor.array("sizes", "Sizes").inlineList(true).emptyText("No sizes")
ConfigDescriptor.number("left", "Left").scrubRate(() -> 1d / zoom)   // units per pixel, asked per drag
```

- **`placeholder`** draws in an empty field; `description` is the row's hover text.
- **`inlineList`** lays an `ARRAY` out as rows in the field column; `emptyText` shows when empty. A new row is
  committed only once typed into.
- **`scrubRate`**'s supplier form is asked each step, for a canvas that zooms.

A number's **range, unit and step can follow another value**, asked while shown:

```java
form.prop(ConfigDescriptor.number("stroke", "Stroke")
        .range(() -> new ConfigDescriptor.Range(0f, em() ? 13f : cap * fontSize()))
        .unit(() -> em() ? "%" : "px")
        .step(0.1f), stroke);
```

- **A control built of numbers copies its descriptor with `part(id, label)`**, carrying every attribute and supplier.
- **A row's Ctrl+Z reaches the property's `editedIn` history.**

#### A control of your own

Anything that edits a value is a `ValueControl<T>`:

```java
public final class OffsetPad extends ValueControl<double[]> {
    public OffsetPad(String id) {
        super(Name.of("offsetpad"), ConfigDescriptor.vector(id, "", 2), new double[2]);
        StyleGizmos.drag(this, this::beginInteraction, (dx, dy) -> commit(offsetBy(dx, dy)), this::endInteraction);
    }
    @Override protected void writeToWidgets(double[] at) { /* draw the value; never emit */ }
}

panel.append(new OffsetPad("offset").bind(shadow.map(Shadow::offset, at -> shadow.get().withOffset(at))));
```

- **Draw in `writeToWidgets`, report with `commit`**, and bracket a drag with `beginInteraction` / `endInteraction` for
  one undo step. Keep no copy of the value.
- **A view that is not a control** follows with `PropertyWatch.follow(element, property, show)`, or
  `LiveEdits.follow(element, styleProperty, css)` for a declaration.
- **Children redrawn from the value use `ChildList`** (`ChildList.Keyed` for identities), never `removeAll` and
  append, which destroys the row being pressed.
- **A list of rows in a panel** builds each with `panel.row(descriptor, value)` and hands a removed one to
  `panel.forget(row)`.
- **Parts of a composite value are `Property.map`s** of the whole. Write `v -> whole.get().withX(v)`, never
  `whole.get()::withX`, which reads the whole once.

### Chips for a list of names — `ClassChips`

`widget.config.control` · tag `classchips`

```java
ClassChips chips = new ClassChips(ConfigDescriptor.of("classes", "classes", Kind.ARRAY), List.of("title"));
chips.setSuggestions(() -> sheetClassNames());                    // what the prompt completes from
chips.setFlagged(name -> !sheetClassNames().contains(name));      // drawn as a warning
chips.setAccepts(name -> !ClassNames.isEngine(name));             // never added, typed or suggested
chips.bind(fields.classes(node));
```

Chromium DevTools' `.cls` pane with Unity UI Builder's removal: prefix completion, several space-separated names at
once, Enter or Tab takes the highlighted suggestion or the typed text, a chip's cross removes it, Backspace in an empty
prompt takes the last. A name present is not added twice. Its pieces are classes (`__chips__`, `__chip__`,
`__chip-label__`, `__chip-remove__`, `__flagged__`, `__chip-prompt__`, `__chip-suggestions__`, `__chip-suggestion__`,
`__active__`, `__chip-hint__`).

## 12e. Editing a tree — `TreeEditing` over `TreeEditModel`

`widget.collection.tree` · consumers: the Project panel (`ExplorerEditModel`) and the Hierarchy panel
(`HierarchyEditModel`)

A file manager's editing for any `TreeView`: drag to move (a modifier copies), cut, copy, paste, duplicate, F2 rename
and delete, over the selection, from keys, the Edit menu and a right-click. The kit decides what a gesture means; the
model performs it.

```java
private static final TreeClipboard<Node> CLIPBOARD = new TreeClipboard<>();   // one per KIND of tree

editing = new TreeEditing<>(tree, this, this::itemForRow, tree::refresh, CLIPBOARD);
editing.setModel(new NodeModel());
editing.attachContextMenu(Commands.process(), () -> ContextMenu.of(NODE_MENU));
TreeEditing.contributeMenu(Commands.process(), NODE_MENU);   // once, by the feature; dispose it with the feature

// the renderer
public UIElement createTemplate() { ... editing.installRow(row, field); ... }
public void bind(Node node, TreeRow<Node> row, int i, UIElement template) {
    editing.bindRow(template, label, field, node);
}
```

```java
final class NodeModel implements TreeEditModel<Node> {
    public boolean isOrdered()                   { return true; }   // a document's order: before / into / after
    public Node parentOf(Node node)              { return node.parent(); }
    public int indexOf(Node node)                { return node.parent().children().indexOf(node); }
    public boolean isContainer(Node node)        { return node.takesChildren(); }
    public boolean canEdit(Node node)            { return node.parent() != null; }
    public String nameOf(Node node)              { return node.name(); }
    public void move(List<Node> nodes, Target<Node> to) { document.applyAll("move", moves(nodes, to)); }
    public void copy(List<Node> nodes, Target<Node> to) { document.applyAll("copy", copies(nodes, to)); }
    public void delete(List<Node> nodes)         { document.applyAll("delete", removals(nodes)); }
    public void rename(Node node, String name)   { document.apply(new Rename(node, name)); }
}
```

| | Ordered (`isOrdered() == true`) | Unordered — a sorted listing |
|---|---|---|
| A drop on a row | top quarter before it, bottom quarter after, middle into it when it is a container; a leaf has only halves; after an open container is its first child — VS Code's `listView.ts` | into it when it is a container, else into its parent |
| Paste | after the last selected item, in its parent | into a selected container, else beside the selected leaf |
| Duplicate | offered, just after the last selected item | not offered |
| Row mark | `__drop-before__`, `__drop-target__`, `__drop-after__` | `__drop-target__` |

- **Every verb acts on the selection**, outermost items only, in tree order.
- **Cut is performed at paste**; until then the rows carry `__cut__`. A copy stays for the next paste; a cut is spent
  by one.
- **Keys are on the tree's own keymap** (Mod+X/C/V, Mod+D, F2, Delete). Every tree also answers `TreeViewCommands`:
  Expand Selected (Mod+=), Expand All (Mod+Shift+=), Collapse All (Mod+-). The Edit menu reaches cut, copy and paste
  through `ClipboardActions`.
- **A right-click inside the selection keeps it**; outside, the clicked row becomes the selection.
- **A drag rests to open and pushes to scroll** (VS Code): 500 ms on a closed branch opens it; within 24 px of an edge
  scrolls, faster the deeper.
- **`canEdit` gates** cut, delete, rename and dragging; **`canDrop`** where a paste or drop may land.
- `copyModifier` is Ctrl by default; a design tool's model answers Alt.
- `labelOf` is what a ghost and the clipboard text say when a name can be empty.
- The commands resolve `TreeEditing.KEY`, answered by the list for its rows. A document-level data provider must not
  answer it, or a palette Delete elsewhere reaches the tree.

## 12f. The frame readout — `FrameStatsOverlay` over `FrameStats`

`widget.display` / `core.trace` · tag `framestats` · `cgui-desktop` (F7 shows, F8 expands)

How fast the last few seconds ran, drawn over what is measured: a harness scene, the editor or a Minecraft screen.

```java
FrameStatsOverlay.toggleOn(document);                         // F7 — find-or-attach, then show/hide
FrameStatsOverlay.expandOn(document);                         // F8 — show, and expand the phases
FrameStatsOverlay hud = FrameStatsOverlay.attach(document);   // top-right, promoted, hit-tests nothing
FrameStatsOverlay.of(document);                               // the one already there, or null
hud.stats().setBudgetMs(1000f / 144f).setWindowSeconds(5f);   // this host's refresh rate
```

Every desktop has it: `desktop.frameStats` (F7) and `desktop.frameStatsDetail` (F8), on keys and in the command
palette, with no menu entry.

```
116 fps   8.3ms wall   8.1ms cpu                 <- the rate, and what it cost to produce
3s: best 5.7  p50 8.1  p95 9.6  worst 35.1       <- the spread behind that average
1% low 29 fps   over 16.7ms: 6/348   GC 4ms      <- the stutter, the misses, the collector
▁▁▂▁▁█▁▂▁▁▁▃▁▁▁▂▁▁▁▁▁▁▂▁▁▁▁▁▁▂▁▁▁▁▁▁▁▂▁▁▁▁▁▁    <- the shape: oldest left, one bar per column
paint:tree 6.31ms  frame:layout 1.01ms           <- where it went; expands to a row each
jobs-busy=0 drawcalls=31 segments-replayed=153
```

Expanded (`toggleDetail`):

```
slowest 13.1ms cpu: phases 12.9ms
  style                 6.20ms  48%
  layout                3.11ms  24%
  paint                 2.40ms  19%
  gl:draw               1.18ms   9%
```

- **Wall is the interval between frame starts**, vsync included; **CPU is the frame's work**, the headroom.
- **A frame's sample is committed at the next frame's start**, its wall time being that interval.
- **Colours judge the typical case**: the rate row the average, the spread row its p95, the miss row the share that
  missed. The worst frame is shown, not judged. A row that only states (the phases) has no colour (`Health.NONE`).
- **A bar's height is its share of the window's worst frame; its colour is `healthOf` against the budget.** A column is
  its bucket's worst frame, never the mean. `FrameStats.spark(columns)` returns both; the overlay colours runs through
  `::highlight(framestats-warn|bad)`.
- **Expanded, the breakdown is the slowest frame's by CPU in the window**, decaying with it; the collapsed hint is the
  live frame's.
- Thresholds are the engine's, colours the sheet's (`__good__` / `__warn__` / `__bad__` in `ua/overlays.css`).
- **Collecting is held, not switched**: `hold()`/`release()` are counted; a hold enables `crystalgui.frame` and
  `crystalgui.flow`, never `crystalgui.blame`. With no holder it costs one mask test a frame.
- **`FrameStats` is a view over `CgTrace`'s frame ring**; phase rows are zones on `UiTrace.FRAME`, cross-frame chains
  spans on `UiTrace.FLOW`. `-Dcrystalgui.frameprofile=true` adds per-slow-frame logging only.
- **A share is of the phases' own total**, not the frame's.
- Monospaced so rewrites do not shift the line; the bars are U+2581–U+2588, which `JetBrainsMono-Regular.ttf` carries.

### Reading the counts

The count rows are `CgTrace.add(UiTrace.FRAME, ...)` per frame; `drawcalls` is the sealed frame's draw calls after
batching (`CgFrame.batches()`). The layer counts account for every layer:

| Count | Means |
|---|---|
| `layers-opacity` · `layers-mask` | What the layer is **for**. A mask layer is two targets and a composite; an opacity layer is one, and the two are removed by different things. `layers-mask` counts only the masks that could not be a rounded clip |
| `layers-elided` · `masks-elided` | A layer that was **not** opened — an opacity folded into the draw, a rounded box with no children to clip |
| `clips-rounded` | A rounded `overflow: hidden` clipped with no layer at all: a `CgClipTree` entry its children's draws carry |
| `segments-replayed` · `segments-changed` | A box's own paint recorded again from what it kept, against painted anew |
| `segments-dynamic` | Never kept, because the node repaints itself (`backdrop-filter`, or a node overriding a paint hook without `paintsDynamically()` false). A readout with many of these and few replayed usually means this, not a broken cache |
| `surfaces-kept` · `surfaces-painted` | A window composited from its picture, against walked again |
| `surface-miss-revision` · `-region` · `-epoch` · `-degraded` | Why a window was walked: something in it changed, it moved within its node or was resized, the text gamma or a glyph page moved, or its text drew before its glyphs landed |
| `segments-unkept-<reason>` | A painted segment the canvas would not keep, by `CgPaintChunk.Refusal`: `DEGRADED` is text drawn before its glyphs landed, `FRAME_TEXTURE` a draw of a texture that lives one frame |

## 13. Harness scenes

```bash
./gradlew :gl-debug-harness:runHarness --args="--mode=cgui-gallery"
```

| Mode | Covers |
|---|---|
| `cgui-gallery` | every widget, a page each, + theme toggle |
| `cgui-new-gallery` | the config kit and newer widgets |
| `cgui-text` | `UIText`: auto-size, wrap, font fallback, live binding |
| `cgui-text-stress` | many text nodes at once: shaping and layout cost, retained-shape reuse |
| `cgui-styling` | selectors, cascade, `!important`, transitions, background cross-fades |
| `cgui-visual-layers` | overflow masking, opacity isolation, scissor vs mask |

Every scene, with its switches: `harness-scenes/CLAUDE.md`. They stay open until the window is closed.
