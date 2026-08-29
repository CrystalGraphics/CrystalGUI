# Building UIs with CrystalGUI

**This is the guide for people using CrystalGUI, not building it.** It covers the two things you can
make — a UI that lives entirely on the client, and a UI a server owns and a client shows — and how to
choose between them.

If you want to know *why* something works the way it does, that is `AGENTS.md` and the `docs/` set
beside this file. Nothing here assumes you have read them.

---

## Contents

1. [Which kind of UI do I want?](#1-which-kind-of-ui-do-i-want)
2. [A client-only UI](#2-a-client-only-ui)
3. [Styling](#3-styling)
4. [A networked UI](#4-a-networked-ui)
5. [Reacting to the user](#5-reacting-to-the-user)
6. [Sending your own messages](#6-sending-your-own-messages)
7. [Nesting panels](#7-nesting-panels)
8. [Opening and closing](#8-opening-and-closing)
9. [Remembering things](#9-remembering-things)
10. [Writing your own widget](#10-writing-your-own-widget)
11. [Cheat sheet](#11-cheat-sheet)

---

## 1. Which kind of UI do I want?

| You want… | Use | Runs on |
|---|---|---|
| A settings screen, a HUD, a tool panel — nothing another player needs to see | **client-only** | the client |
| A machine, a shop, a shared control panel — a server owns the truth | **networked** | both, from one class |

The rule of thumb: **who owns the data?** If the answer is "the client, and nobody else cares", build
a client-only UI — it is less machinery and there is no wire to think about. If a server owns it, or
two players must see the same thing, build a networked one.

You can mix them freely. A networked panel can sit inside a client-only screen.

---

## 2. A client-only UI

A UI is a tree of `UIElement`s. Build the tree, hand it to a `UIWindow`, and paint it every frame.

```java
UIElement root = new UIElement();
root.layout(l -> l.paddingAll(16).gapAll(8));

UIText title = new UIText("Furnace");
Button light = new Button("Light it");
Checkbox keepLit = new Checkbox("Keep lit");

root.addChildren(title, light, keepLit);

light.attachListener(() -> System.out.println("lit!"));
keepLit.attachListener(on -> System.out.println("keep lit: " + on));

UIWindow window = new UIWindow(Ui.of(root));
window.init(screenWidth, screenHeight);
```

Then, once per frame:

```java
window.paintFrame();
```

and forward input to it:

```java
window.getInputHandler().consumeMouseEvent(event);
window.getInputHandler().consumeKeyboardEvent(event);
```

That is the whole contract. **`UIWindow` is not a Minecraft screen** — it deliberately implements no
Minecraft interface, so you own the host. On 1.7.10 that is a `GuiScreen` whose `drawScreen` calls
`paintFrame` and whose input handlers forward events;
`mc1710/.../CgUiScreen` is a working example to copy.

> **`init` is required.** Selectors do not match on a detached tree, so without it your UI has no
> styling at all — everything lays out at its default size and nothing looks wrong enough to explain
> itself.

### Widgets you have

`Button` `Checkbox` `Switch` `Slider` `TextField` `SearchField` `Dropdown` `ColorSelector`
`ProgressBar` `UIText` `Tooltip` `Dialog` `Menu` `MenuItem` `Popover` `Tab` `TabView` `SplitView`
`ScrollerView` `ListView` `TableView` `TreeView`, plus the workbench's own (`ViewContainer`,
`ProjectFileTree`, the editor, the node graph).

Every one of them reports what it does through a signal:

```java
button.attachListener(() -> …);              // pressed
checkbox.attachListener(checked -> …);       // Boolean
slider.attachListener(value -> …);           // Float
field.attachListener(text -> …);             // String, per keystroke
field.onSubmit.connect(text -> …);           // String, on Enter or blur
dropdown.onSelectionChanged.connect(i -> …); // Integer
picker.onColorChanged.connect(argb -> …);    // Integer
```

### Layout

Layout is flexbox, through `layout(…)`:

```java
row.layout(l -> l
        .flexDirection(FlexDirection.ROW)
        .gapAll(8)
        .alignItems(AlignItems.CENTER)
        .paddingAll(6));
```

**One idiom is worth memorising**, because it is the answer to "why is my panel zero-high":

```java
content.layout(l -> l.widthPercent(100f).height(0).flexGrow(1f));   // fill the parent
```

`flex-shrink` defaults to `0` here (unlike CSS), so a growing child needs `height: 0` as its basis or
it keeps its content size and overflows. Do **not** set `flexShrink(1)` to "help" — that lets content
be squashed below its own size.

---

## 3. Styling

Structure goes in Java; sizes, colours and timings go in CSS.

```java
button.addClass("danger");
```

```css
button.danger {
    background-color: #FFAA3333;
    padding-all: 8px;
    transition: background-color 150ms;
}
button.danger:hover { background-color: #FFCC4444; }
```

Load a sheet:

```java
window.getStyleEngine().addStylesheet(StyleSheet.parse(CSS));                 // inline text
window.getStyleEngine().addStylesheet(StyleSheetRegistry.of("mymod:panel"));  // assets/mymod/ui/styles/panel.css
window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);                    // the built-in widget geometry
```

`StyleSheet.DEFAULT` is **not** applied for you. Add it, or your widgets have no default geometry.

Selectors: `tag`, `.class`, `#id`, `:hover` `:focus` `:active` `:checked` `:disabled` `:blank`
`:invalid` `:enabled`, descendant and child combinators. Not supported: `:nth-child`, attribute
selectors, `~`/`+`, `@media`, `@import`.

> **Sizes belong in CSS, not Java.** If you are typing a pixel value into a widget, it probably wants
> to be a rule instead — that is what makes a theme possible later.

---

## 4. A networked UI

One class describes the whole thing. It **is** a `UIElement`, so it nests anywhere an element does,
and `furnacepanel { }` styles it.

You write it as `Networked<YourDataType>` — a panel is always a **view of something**, and that
something is your own object.

```java
public final class FurnacePanel extends UIElement implements Networked<FurnaceData> {

    public static final UiType<FurnacePanel, FurnaceData> TYPE =
            UiType.of("mymod:furnace", FurnacePanel::new);

    // Every UIElement field is a part of this panel. Declared = created and named for you.
    public Switch power;
    public Slider throughput;
    public ProgressBar burn;
    public UIText status = new UIText("");          // needs a constructor argument? just write it
    public Button purge = new Button("Purge");

    @Override
    public void layout(FurnaceData model) {        // SERVER, once — the structure
        addChild(new UIText("Furnace"));
        addChild(row("Power", power));
        addChild(row("Rate", throughput));
        addChild(row("Burn", burn));
        addChild(status);
        addChild(purge);
    }

    @Override
    public void serve(FurnaceData model, ServerScope io) {   // SERVER, once — the behaviour
        // ── the user → the model ────────────────────────────────────────────
        // Switch.TOGGLE is an EVENT: a constant on the Switch class saying "this widget can report
        // being flipped, and it hands you a Boolean". You get `on` already typed. See below.
        // The user changes the model.
        io.on(power,      Switch.TOGGLE,        (ctx, on)    -> model.setRunning(on));
        io.on(throughput, Slider.VALUE_CHANGED, (ctx, value) -> model.setRate(value));
        io.on(purge, Button.ACTIVATE, ctx -> model.purge());

        // ── the model → the screen ──────────────────────────────────────────
        // The same shape pointing the other way. Stated once; the engine keeps it true.
        // The model changes the screen.
        io.project(power, Switch.CHECKED,      model::isRunning);
        io.project(burn,  ProgressBar.FRACTION, model::burnFraction);
        io.project(status, () -> model.isRunning() ? "Running" : "Idle", UIText::setText);
    }
}
```

Notice the two halves are the same shape. `io.on` is *the user changes the model*; `io.project` is
*the model changes the screen*. And there is **no `tick`** — you may still write one for logic of your
own, but keeping the screen up to date is not your job any more.

### The model — what the panel is a view of

`Networked<FurnaceData>` says *"this panel shows a `FurnaceData`"*. `FurnaceData` is **yours**: a
plain class holding whatever the thing being controlled actually is — a furnace's burn time and fuel,
a shop's stock, a reactor's rods. CrystalGUI never looks inside it.

```java
public final class FurnaceData {              // no interface to implement, no annotations
    private boolean running;
    private float rate;

    public boolean isRunning()   { return running; }
    public void setRunning(boolean v) { running = v; }
    …
}
```

**One model, one window, handed over at open:**

```java
ServerWindows.of(connection).open(FurnacePanel.TYPE, furnaceAt(x, y, z));
//                                                   ^^^^^^^^^^^^^^^^^^
//                                                   the FurnaceData this window is for
```

Open the same panel type with a different model and you get a different window showing a different
furnace. Open it with the *same* model for two players and both see one furnace — the server holds
one object and every viewer's window mirrors it.

### Keeping the screen up to date — projections

You already know half of this. Look at the two lines together:

```java
io.on(power, Switch.TOGGLE,  (ctx, on) -> model.setRunning(on));   // the user changes the model
io.project(power, Switch.CHECKED, model::isRunning);               // the model changes the screen
```

Same widget, same shape, opposite direction. `on` says *"when this widget is flipped, do that"*.
`project` says *"this widget shows that"* — and then the engine keeps it true, forever, without you
asking again.

That is the whole concept. The rest of this section is detail.

#### Why it exists

The tempting alternative is a method that copies everything, called every tick:

```java
public void tick(FurnaceData model) {                 // DON'T — this is the old way
    power.setChecked(model.isRunning());
    burn.setFraction(model.burnFraction());
}
```

It works, and it is a list you have to remember to add to. Add a `fuel` bar to the panel, forget this
method, and the bar shows whatever it was built with — **which is usually right**. So the panel looks
correct when it opens and then simply never moves. Nothing throws, nothing logs. That has shipped in
this codebase more than once.

A projection cannot be forgotten in a loop, because there is no loop.

#### What you get

- **Nothing is written unless it changed.** The engine compares first. An unchanged furnace writes no
  widget, marks nothing dirty, and puts **zero bytes** on the wire.
- **The first screen is already right.** Projections run once before the window is described, so the
  client's opening tree carries your model rather than being corrected a tick later.
- **A window nobody is looking at costs nothing** — minimised or hidden, projections are not evaluated
  at all.
- **Your model is untouched.** `model::isRunning` is a method reference to a getter it already has. No
  interface, no annotations, no fields to convert, no rewrite. That is the point: the case that matters
  is a big model you did not write.

#### The two forms

**With a state constant** — the same constants a widget's
[contract](#10-writing-your-own-widget) declares, so it is checked at compile time
(`Switch.CHECKED` takes a `Boolean`, so `model::isRunning` fits and `model::label` would not compile):

```java
io.project(power,      Switch.CHECKED,       model::isRunning);
io.project(burn,       ProgressBar.FRACTION, model::burnFraction);
io.project(throughput, Slider.VALUE,         model::rate);
```

**With a setter** — for a computed value, or a widget with no constant for what you want:

```java
io.project(status,  () -> model.isRunning() ? "Running" : "Idle", UIText::setText);
io.project(coolant, () -> model.engine().coolant(), ProgressBar::setFraction);
```

Nesting needs no special support — it is only a lambda — and a `null` anywhere in the chain means
"nothing to show yet", never an error.

Both start with the widget. That is not just for symmetry: it is how the engine knows what is already
covered, so nothing can end up written twice.

#### Lists

A list needs more than "copy a value", because rows come and go:

```java
io.projectEach(model::slots, slotList,      // where the items come from, and the container
        Slot::id,                           // each item's stable identity — NOT its index
        slot -> new SlotRow(),              // build a row the first time an item is seen
        (row, slot) -> ((SlotRow) row).show(slot));
```

The key is what makes it cheap: a row that did not change **keeps its element**, so adding one item
sends one insert instead of rebuilding the list, and moving one sends a move instead of a
destroy-and-rebuild. Two rules the engine enforces rather than trusting you with: the container is the
projection's alone (do not add children to it yourself), and **keys must be unique** — a duplicate is
refused loudly, because quietly collapsing two items onto one row makes a row disappear.

#### The shortcut: `autoProject`

If your panel field is called `throughput` and your model has `throughput()` — or `getThroughput()`,
or `isThroughput()` — you do not have to say anything:

```java
io.autoProject(model);
```

It wires what lines up by name and **logs what it could not**:

```
auto-projection: 1 wired, 0 skipped, 3 with no matching accessor
  wired   throughput <- throughput()
```

Anything you projected yourself is left alone, **in any order** — it recognises the widget, not the
name, so `autoProject` can come first or last. `power` is absent above because the model calls it
`isRunning()`, and inventing that mapping is not something a convention should do; you write that one.

It is a shortcut and nothing more. `io.project` covers every case; this covers the subset whose names
already match. If you find the naming rules more trouble than the typing, ignore it entirely.

**A widget you wrote yourself** joins in by declaring which of its slots it *is* —
`.primary(ANGLE)` on its contract. See
[Writing your own widget](#10-writing-your-own-widget); a widget without one is reported by
`autoProject` rather than guessed at, and is projected by hand like anything else.

#### If the model is enormous

If it can say when it last changed, gate the panel on that and an unchanged tick costs one comparison
instead of one per field:

```java
io.projectWhen(model::revision);
```

Only if the revision moves for **every** change that matters — one that misses a mutation makes the
panel miss it, silently.

#### One thing it will never do

It will not work out *which widget shows which field*. There is nothing to deduce: `throughput` could
just as easily be the label's text. Every UI framework makes you say this once per displayed field —
React writes `<Slider value={m.throughput}/>`, Blazor writes `@bind-Value` — because that statement
**is** the design of your screen. What is automated is the other question: *which fields changed*.

And it is one line per **widget**, not per model field. A five-hundred-field model behind a
twelve-control panel needs twelve projections, and you were writing those twelve `setValue` calls
anyway.

**It lives on the server and nowhere else.** That is why the model is a *parameter* of the
server-side hooks rather than a field on the panel:

```java
void layout(FurnaceData model)              // server — has one
void serve(FurnaceData model, ServerScope)  // server — has one
void tick(FurnaceData model)                // server — has one (and is usually EMPTY: see
                                            // projections, above)

void bound()                                // client — no model, and there never was one
void client(ClientScope io)                 // client — likewise
```

The client is showing a *picture* of your furnace, assembled from the description and kept up to date
by state deltas. It has no `FurnaceData` and cannot get one, which is exactly what you want: there is
no way to accidentally write logic that reads the model on the wrong side, because there is nothing
to read it from.

Two practical consequences:

- **`FurnaceData` may be a server-only type.** It appears only in method signatures, which erase, so
  it can safely name a `TileEntity`, a `World`, or anything else a client does not have.
- **Need the model in your own helper methods?** Assign it to a field in `serve` — one line,
  explicitly, on the side that has one.

### Those event constants

`Switch.TOGGLE` and `Slider.VALUE_CHANGED` are `public static final` fields on the widgets
themselves. Each one says *what the widget can report* and *what it hands you*:

```java
Switch.TOGGLE           // Event<Switch, Boolean>   — flipped, gives you the new state
Slider.VALUE_CHANGED    // Event<Slider, Float>     — dragged, gives you the value
Button.ACTIVATE         // Event<Button, Void>      — pressed, gives you nothing
```

So `io.on(power, Switch.TOGGLE, (ctx, on) -> …)` reads as *"when this switch is toggled, here is the
boolean"* — the value arrives decoded, and you never write a key or a default. Type your widget and a
dot, and your IDE lists what it can report.

Two consequences worth knowing now: you **cannot** subscribe to an event a widget does not have (it
will not compile), and a widget's tempo is handled for you — a slider throttles its drag, a text
field debounces its typing, and both always deliver the value you ended on.

[§5](#5-reacting-to-the-user) has the full list and the details.

### Opening it — where the connection comes from

A networked window needs a **connection to one player**. On 1.7.10 you get it from the player:

```java
// Server side — e.g. from a block's onBlockActivated, or a command, or a tick
ProtocolConnection<Object> connection = CgUiConnections.forPlayer(player);   // EntityPlayer
if (connection == null) return;                 // that player has no CrystalGUI channel

ServerWindows.of(connection).open(FurnacePanel.TYPE, myFurnace);
```

```java
public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, ...) {
    if (world.isRemote) return true;            // server decides; the client just gets the window
    ProtocolConnection<Object> connection = CgUiConnections.forPlayer(player);
    if (connection != null) {
        ServerWindows.of(connection).open(FurnacePanel.TYPE, furnaceAt(x, y, z)); // FurnaceData in world  at (x,y,z) 
    }
    return true;
}
```

**That is the entire wiring.** No window subclass, no client registration, no id strings. The open
names the panel class on the wire and the client builds it.

A connection exists **for as long as the player is on the server** — it is created when they join and
closed when they leave, so `forPlayer` answers `null` before and after. On the client, the mirror of
this is `CgUiConnections.client()`, which is `null` when you are not in a world.

> **One trap, and it only shows up in single-player.** If you open a `GuiScreen` to host the window,
> its `doesGuiPauseGame()` must return **`false`**. Pausing stops the integrated server ticking, which
> stops the connection being pumped — so every call dies at its timeout and the panel simply never
> fills in, with nothing in the log.

Opening two windows for the same subject is handled by `key(model)`: give the panel a key and a
second `open` brings the existing window forward instead of building another.

### What runs where

| Hook | Runs | For |
|---|---|---|
| `layout(model)` | server, once | structure — `addChild`, rows, classes |
| `serve(model, io)` | server, once | what the UI *does* |
| `tick(model)` | server, per world tick | copy the model into the widgets |
| `stillValid(model, viewer)` | server, per tick | `false` closes the window (player walked away) |
| `title(model)` / `key(model)` | server | what to call it; `key` makes re-opening bring the existing window forward |
| `bound()` | client, on mount **and after every re-describe** | widget listeners |
| `client(io)` | client, once | wire methods |
| `closed(reason)` | both | teardown |

Two rules that save real debugging:

- **Do not copy the model in `tick`.** That is what projections are for, and they are stated once in
  `serve` — see [Keeping the screen up to date](#keeping-the-screen-up-to-date--projections). `tick` is
  for logic of your own, and most panels leave it out entirely. (Earlier versions of this guide taught
  the copy-it-every-tick shape; it works and it is a list you can forget to add a field to, which fails
  by looking correct.)
- **Widget listeners go in `bound()`, not `client(io)`.** A re-describe replaces the tree, so
  listeners attached once would be attached to widgets that no longer exist. `bound()` runs again;
  `client(io)` does not.

### Why fields become widgets

Every non-static `UIElement` field is a part. On the server they are created and given
`setId(fieldName)`; on the client they are found again by that name. So the name is written once — as
the thing you were going to write anyway.

### Methods may be side-specific; fields may not

The same erasure that lets your model be a server-only type applies to method bodies: `serve` may
name server-only classes and `client` may name client-only ones, because a body resolves only when it
runs.

**Fields are the exception, and it is not a style rule.** A field's type resolves when the class
loads, so a panel with a field of a client-only type fails to load on a dedicated server — the whole
class, not just that field. Keep client-only things inside `client(io)` and `bound()`.

---

## 5. Reacting to the user

### What `io.on` takes

```java
io.on( widget , Widget.EVENT , (ctx, value) -> … );
//      ^          ^              ^     ^
//      |          |              |     the payload, already decoded
//      |          |              context: the session, the element, the raw payload
//      |          which interaction — a constant on the widget's own class
//      the widget you are listening to
```

**`Switch.TOGGLE` is a `public static final` field on `Switch`.** It is an `Event<Switch, Boolean>`,
meaning *"an event on a Switch that carries a Boolean"* — so the widget itself declares what it can
report and what it hands you. That is why the second lambda parameter is typed: no
`payload.getBool("checked", false)`, no key to remember, no default to guess.

The two type parameters are the whole story:

```java
Event<Slider, Float>            // on a Slider, carries a Float
Event<Dropdown, Integer>        // on a Dropdown, carries the chosen index
Event<Button, Void>             // on a Button, carries nothing — the lambda takes just (ctx)
```

Because the event is typed in its widget, `io.on(slider, TextField.COMMITTED, …)` **does not
compile**. You cannot subscribe to an event a widget does not have, and there is no string to
misspell.

For a `Void` event, drop the second parameter:

```java
io.on(purge, Button.ACTIVATE, ctx -> model.purge());
```

### What `ctx` gives you

`ctx` is a `UiEventContext` — `session()`, `element()` (the widget that reported), and `payload()`
(the raw `StateMap`, which you rarely want since the value is already decoded). Most handlers ignore
it entirely.

### Every event, by widget

| Widget | Event | Hands you | Fires when |
|---|---|---|---|
| `Button` | `ACTIVATE` | — | pressed (left button; Space/Enter count) |
| `MenuItem` | `ACTIVATE` | — | chosen |
| `Checkbox` | `TOGGLE` | `Boolean` | ticked or unticked |
| `Switch` | `TOGGLE` | `Boolean` | flipped |
| `Slider` | `VALUE_CHANGED` | `Float` | dragged |
| `TextField` | `TEXT_CHANGED` | `String` | every keystroke |
| `TextField` | `COMMITTED` | `String` | Enter, or focus leaves |
| `SearchField` | `QUERY` | `String` | the query changed |
| `Dropdown` | `SELECTION` | `Integer` | an option was chosen (its index) |
| `TabView` | `SELECTION` | `Integer` | a different tab is showing |
| `Tab` | `CLOSE_REQUESTED` | — | its close button was pressed |
| `Dialog` | `CLOSE_REQUESTED` | — | its close button was pressed |
| `ColorSelector` | `CHANGED` | `Integer` | the colour moved (ARGB) |
| `SplitView` | `RESIZED` | `float[]` | a divider was dragged (the new weights) |
| every config control | `CHANGED` | its own value type | the value changed |

The config controls follow one shape — `BooleanControl.CHANGED` is `Event<BooleanControl, Boolean>`,
`ColorControl.CHANGED` is `Event<ColorControl, Integer>`, `TextControl.CHANGED` is
`Event<TextControl, String>`, and so on:

```java
io.on(enabled,   BooleanControl.CHANGED, (ctx, on)     -> model.setEnabled(on));
io.on(threshold, NumberControl.CHANGED,  (ctx, value)  -> model.setThreshold(value));
io.on(tint,      ColorControl.CHANGED,   (ctx, colour) -> model.setTint(colour));
```

### Choosing between the pairs

- `TEXT_CHANGED` fires per keystroke, `COMMITTED` on Enter or blur. Use the first for a live preview,
  the second for anything expensive or destructive.
- `ACTIVATE` is "I pressed this"; `SELECTION` is "the selection is now that". A `Dropdown` has both;
  a `TabView` has only the second.

Rate is handled for you: a `Slider` throttles its drag and a `TextField` debounces its typing, and
each always delivers the value it ended on. You do not write that.

### If you need a kind that has no constant

The string form still exists as an escape hatch, and hands you the raw payload:

```java
io.on(widget, "myCustomKind", ctx -> … ctx.payload().getInt("x", 0) …);
```

Prefer the typed form. Reach for this only for a kind you have declared yourself (see §10).

---

## 6. Sending your own messages

For anything that is not a widget interaction, both sides have a small RPC surface.

**Server answers a question:**

```java
@Override public void serve(FurnaceData model, ServerScope io) {
    io.onCall("history", (args, respond) -> {
        StateMap<Object> out = io.newMap();
        out.putInt("burns", model.burnCount());
        respond.ok(out);
    });
}
```

**Client asks it:**

```java
@Override public void client(ClientScope io) {
    io.onNotify("flash", payload -> status.addClass("flashing"));

    history.attachListener(() ->
            io.call("history", null, reply -> log.setText(reply.getInt("burns", 0) + " burns")));
}
```

**Server pushes something with no answer:**

```java
io.notify("flash", null);
```

You never namespace these. `io.onCall("history", …)` and `io.call("history", …)` both become
`furnace/history` on the wire if this panel is a field named `furnace` — both sides derive the same
prefix from the same tree.

---

## 7. Nesting panels

A panel is an element, so it nests. Hold it as a field, build it with the **slice** of the model it
is allowed to see, and attach it:

```java
public EnginePanel engine;

@Override public void layout(FurnaceData model) {
    engine = EnginePanel.TYPE.build(model.engine());
    addChild(engine);
}

@Override public void serve(FurnaceData model, ServerScope io) {
    io.attach(engine, model.engine());        // the child is ticked and closed with this window
}
```

The child's hooks take `EngineModel`, so it *cannot* reach the furnace — the compiler says so. That
is the point of passing a slice rather than the whole model.

For the child to talk back to its parent, use a plain Java callback. Both halves are objects in the
same process:

```java
engine.onRestarted(() -> status.setText("engine restarted"));
```

`title`, `key` and `stillValid` are window-level and only ever asked of the root panel.

---

## 8. Opening and closing

**Opening is the server's job.** There is no client-side `open`:

```java
ServerWindows.of(connection).open(FurnacePanel.TYPE, furnace);
```

If you want the *client* to trigger it, send a message and let the server decide:

```java
// Server — once, at init
Protocols.server("furnace", wire ->
        wire.onNotify("furnace/open", payload ->
                ServerWindows.of(wire).open(FurnacePanel.TYPE, furnace)));

// Client — the player pressed a key, or clicked a block
ProtocolConnection<Object> connection = CgUiConnections.client();
if (connection != null) {
    connection.notify("furnace/open", null);      // nobody waits; the window arriving IS the answer
}
```

`mc/example/MachineExample` and `MachineExampleClient` are this pattern end to end, on F8.

The server always decides. That is deliberate — it is the same reason Minecraft opens containers
server-side.

**Closing** can come from either end:

```java
window.close("the block was broken");           // server
```

```java
@Override public boolean stillValid(FurnaceData model, Object viewer) {
    return model.isStillThere();                // false closes it
}
```

The user pressing the X reaches `closed(reason)` on both sides. Give the panel a `key` and re-opening
brings the existing window forward — keeping its scroll position and anything half-typed in it —
instead of building a new one.

---

## 9. Remembering things

For a **client-only** UI, widget state can survive a restart. Give the widget a stable id and opt in:

```java
divider.setId("furnace.split");
divider.setSessionPersistent(true);
```

That is all. What gets remembered is the widget's own authored state — a divider's position, a
field's text, a selection. It works for any widget with a contract (see below).

> **View state is deliberately not remembered**, and not sent over a wire either: scroll position,
> hover, focus. If reloading ought to give it back it is state; if it is only *how you are looking* at
> the thing, it is not.

---

## 10. Writing your own widget

Most of the time you compose the widgets you have and there is nothing to do here. Read this when you
write a real widget of your own that either **carries state** or **reports interactions**.

### The idea

A widget declares, once, what it carries and what it can report. That one declaration is read by
everything: the description codec writes its state, the client attaches its listeners, session
persistence remembers it, and `io.on` type-checks against it. Without it a widget still *works*
locally — it just cannot travel, and cannot be remembered.

Three pieces, all `public static final`, all at the top of the class:

```java
State<W, V>              // one piece of state: a wire name and a getter/setter pair
Event<W, P>              // one interaction: how to listen, and what it carries
WidgetContract<W>        // the two lists, registered under the widget's tag
```

### A worked example

A dial that holds an angle and reports when you turn it:

```java
public class Dial extends UIElement {

    // ── the contract, first thing in the class ──────────────────────────────

    /** The angle, 0..1. */
    public static final State<Dial, Float> ANGLE =
            State.of("angle",              // the name on the wire
                     StateTypes.FLOAT,     // how it is encoded
                     Dial::getAngle,       // read it
                     Dial::setAngle,       // write it
                     0f);                  // what an absent value means

    /** The dial was turned. */
    public static final Event<Dial, Float> TURNED = Event.of(
            "value",                                             // the kind, on the wire
            (dial, sink) -> dial.onTurned.connect(sink::accept), // HOW A CLIENT LISTENS
            new Event.Payload<Float>() {                         // how the value crosses
                @Override public <T> void write(StateMap<T> out, Float v) {
                    out.putFloat("value", v);
                }
                @Override public <T> Float read(StateMap<T> in) {
                    return in.getFloat("value", 0f);
                }
            },
            RatePolicy.DRAGGING);                                // throttle the drag

    public static final WidgetContract<Dial> CONTRACT = WidgetContracts.register(
            WidgetContract.of(Dial.class, "dial")
                    .state(ANGLE)
                    .event(TURNED)
                    .primary(ANGLE)        // "a Dial IS its angle" — see below
                    .build());

    // ── the widget itself ───────────────────────────────────────────────────

    public final Signal.Value<Float> onTurned = new Signal.Value<>();

    private float angle;

    public float getAngle() { return angle; }

    public Dial setAngle(float value) {
        if (value == angle) return this;      // idempotent — see below
        angle = value;
        notifyStateChanged();
        return this;
    }
}
```

That is all. Now this works, with no further code:

```java
io.on(dial, Dial.TURNED, (ctx, angle) -> model.setAngle(angle));   // typed, over the wire
dial.setSessionPersistent(true);                                   // remembered across restarts
```

### The parts, one at a time

**`State.of(name, type, getter, setter, fallback)`** — `name` is the wire key and changing it is a
format change. `StateTypes` has `STRING` `INT` `FLOAT` `DOUBLE` `BOOL`, plus `enumOf(Class)`,
`stringListUnder(key)` and `doubleArrayUnder(key)` for collections. Two optional refinements:

```java
State.of(...).omittedWhen("")             // write nothing when it is the default
State.of(...).sanitizedBy(v -> clamp(v))  // clean a value arriving from a PEER
```

`omittedWhen` matters more than it looks — it keeps a default-valued widget's state *absent* rather
than present-and-default, which is what lets two identical trees hash the same.

**`.primary(slot)`** — optional, and it answers one question: *if somebody has a value and wants this
widget to show it, which slot do they mean?* A `Dial` is its angle, so `ANGLE`. It is what
[`io.autoProject`](#the-shortcut-autoproject) writes to, and **only** that: everything else works
whether you declare it or not.

Leave it off when there is no honest answer. `Slider` carries `MIN`, `MAX`, `STEP` and `VALUE` — all
four floats — and declares `VALUE`, because that is what a slider *is*. `SplitView` carries its divider
weights and declares nothing, because "the widget's value" is not a thing a split view has. A widget
with no primary is **reported** by `autoProject` rather than guessed at, and the fix is to project it
by hand:

```java
io.project(divider, model::paneWeights, SplitView::setWeights);   // float[]
```

**`Event.of(kind, attach, payload, rate)`** — `attach` is the important one: it is how a *client*
subscribes, `(widget, sink) -> …`, so the widget says how to listen to itself and nothing in the
networking layer has to know your class. For an event with no value, use `Event.signal`:

```java
public static final Event<Dial, Void> RESET =
        Event.signal("reset", (dial, sink) -> dial.onReset.connect(sink));
```

`kind` is the name that travels on the wire, and it is **yours to choose**. It only has to be unique
within one widget's own contract — nothing central lists them, so `"scrub"` or `"reorder"` collides
with nothing and needs no entry anywhere. Reusing a familiar name where it fits (`"activate"`,
`"value"`, `"change"`) is a courtesy to anyone reading a packet, not a requirement.

`rate` is `IMMEDIATE`, `TYPING` (debounce 150ms) or `DRAGGING` (throttle 50ms). Both of the latter
always deliver the value you ended on.

### Three rules worth following

1. **Declaration order is apply order.** If one slot depends on another, declare it first — a range
   before the value it clamps, a list before an index into it. `Slider` declares `MIN`, `MAX`, `STEP`,
   then `VALUE` for exactly this reason.
2. **A slot needs a real getter.** A stub like `d -> 0f` makes the state *write-only*: settable by a
   server, never actually sent, and it looks completely finished. If your widget has a setter and no
   reader, add the reader.
3. **State is what was authored, never what the user is doing.** No hover, focus, caret or scroll.
4. **Declare a `primary` only if one slot really is "what the widget is".** Guessing on the user's
   behalf is worse than making them write one line: a wrong guess writes the wrong slot every tick and
   looks like the widget misbehaving.
   The test: if reloading ought to give it back, it is state.

Also make your setters **idempotent** — return early when the value is unchanged, as `setAngle` does
above. A projection compares before it writes, but it is the setter that decides whether a write
counts as a change, so a setter that reports a change
unconditionally sends a packet per tick forever, carrying a value nobody moved.

### If your widget carries nothing

Say so once, with a reason, and you are done:

```java
WidgetContracts.localOnly(MyOverlay.class,
        "View state: a drag ghost positioned by the input layer, with no moment a peer could use it.");
```

A widget that is neither contracted nor marked fails `WidgetContractCoverageTest` — on purpose, so
the question gets answered while it is still cheap.

---

## 11. Cheat sheet

```java
// ── client-only ────────────────────────────────────────────────────────────
UIElement root = new UIElement().layout(l -> l.paddingAll(12).gapAll(6));
root.addChild(new Button("Go").attachListener(() -> …));
UIWindow window = new UIWindow(Ui.of(root));
window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
window.init(w, h);
// per frame: window.paintFrame();

// ── networked ──────────────────────────────────────────────────────────────
public static final UiType<MyPanel, MyModel> TYPE = UiType.of("mymod:thing", MyPanel::new);

layout(m)      → addChild(...)                       server, once
serve(m, io)   → io.on(widget, Widget.EVENT, ...)    server, once
tick(m)        → widget.setX(m.getX())               server, per tick
bound()        → widget.attachListener(...)          client, every describe
client(io)     → io.onCall / io.onNotify             client, once

ServerWindows.of(connection).open(TYPE, model);

// ── layout ─────────────────────────────────────────────────────────────────
l.widthPercent(100f).height(0).flexGrow(1f)      // fill the parent
l.flexDirection(FlexDirection.ROW).gapAll(8)     // a row
```

**Where to look when something is wrong:**

| Symptom | Usually |
|---|---|
| Nothing is styled | `window.init(w, h)` was never called, or `StyleSheet.DEFAULT` was not added |
| A panel is zero-high | missing `height(0).flexGrow(1)` — `flex-shrink` is `0` here |
| One widget never updates while the rest do | no projection for it — check `autoProject`'s log, it names what it could not wire |
| Nothing on screen ever updates | no projections at all, or you wrote a `tick` that copies and expected the engine to call something else |
| Listeners stop working after an update | they were attached in `client(io)` instead of `bound()` |
| A widget arrives blank over the wire | it has no contract — see §10 |
