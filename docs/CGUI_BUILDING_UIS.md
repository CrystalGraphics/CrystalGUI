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
and `machinepanel { }` styles it.

```java
public final class FurnacePanel extends UIElement implements Networked<FurnaceModel> {

    public static final UiType<FurnacePanel, FurnaceModel> TYPE =
            UiType.of("mymod:furnace", FurnacePanel::new);

    // Every UIElement field is a part of this panel. Declared = created and named for you.
    public Switch power;
    public Slider throughput;
    public ProgressBar burn;
    public UIText status = new UIText("");          // needs a constructor argument? just write it
    public Button purge = new Button("Purge");

    @Override
    public void layout(FurnaceModel model) {        // SERVER, once — the structure
        addChild(new UIText("Furnace"));
        addChild(row("Power", power));
        addChild(row("Rate", throughput));
        addChild(row("Burn", burn));
        addChild(status);
        addChild(purge);
    }

    @Override
    public void serve(FurnaceModel model, ServerScope io) {   // SERVER, once — the behaviour
        io.on(power,      Switch.TOGGLE,        (ctx, on)    -> model.setRunning(on));
        io.on(throughput, Slider.VALUE_CHANGED, (ctx, value) -> model.setRate(value));
        io.onActivate(purge, ctx -> model.purge());
    }

    @Override
    public void tick(FurnaceModel model) {          // SERVER, every world tick — mirror the model
        power.setChecked(model.isRunning());
        burn.setFraction(model.burnFraction());
        status.setText(model.isRunning() ? "Running" : "Idle");
    }
}
```

Open it:

```java
ServerWindows.of(connection).open(FurnacePanel.TYPE, myFurnace);
```

**That is the entire wiring.** No window subclass, no client registration, no id strings. The open
names the panel class on the wire and the client builds it.

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

- **`tick` just mirrors.** Write the model into the widgets unconditionally; an unchanged value sends
  nothing, so there is no dirty flag to maintain. *Every setter is idempotent* — if you write one
  yourself, make sure yours is too.
- **Widget listeners go in `bound()`, not `client(io)`.** A re-describe replaces the tree, so
  listeners attached once would be attached to widgets that no longer exist. `bound()` runs again;
  `client(io)` does not.

### Why fields become widgets

Every non-static `UIElement` field is a part. On the server they are created and given
`setId(fieldName)`; on the client they are found again by that name. So the name is written once — as
the thing you were going to write anyway.

### Methods may be side-specific; fields may not

`serve` may name server-only types and `client` may name client-only ones, because a method body
resolves lazily. A **field** of a client-only type would fail to load the class on a server.

---

## 5. Reacting to the user

Hand `io.on` the widget's own event. The payload arrives typed:

```java
io.on(power,      Switch.TOGGLE,           (ctx, on)     -> model.setRunning(on));
io.on(rate,       Slider.VALUE_CHANGED,    (ctx, value)  -> model.setRate(value));
io.on(name,       TextField.TEXT_CHANGED,  (ctx, typed)  -> model.setName(typed));
io.on(name,       TextField.COMMITTED,     (ctx, typed)  -> model.rename(typed));
io.on(mode,       Dropdown.SELECTION,      (ctx, index)  -> model.setMode(index));
io.on(tint,       ColorSelector.CHANGED,   (ctx, colour) -> model.setTint(colour));
io.onActivate(purge, ctx -> model.purge());
```

The events a widget offers are `public static final` on that widget — `Slider.VALUE_CHANGED`,
`Checkbox.TOGGLE`, `Dropdown.SELECTION`, `Tab.CLOSE_REQUESTED`, and so on. Your IDE will list them.

**You cannot ask for an event a widget does not have** — it will not compile. And you cannot
misspell a kind, because there is no string to misspell.

`TEXT_CHANGED` fires per keystroke; `COMMITTED` fires on Enter or blur. Use the first for a live
preview and the second for anything expensive.

---

## 6. Sending your own messages

For anything that is not a widget interaction, both sides have a small RPC surface.

**Server answers a question:**

```java
@Override public void serve(FurnaceModel model, ServerScope io) {
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
    io.onNotify("flash", payload -> flashTheScreen());

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

@Override public void layout(FurnaceModel model) {
    engine = EnginePanel.TYPE.build(model.engine());
    addChild(engine);
}

@Override public void serve(FurnaceModel model, ServerScope io) {
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
@Override public boolean stillValid(FurnaceModel model, Object viewer) {
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

Most of the time you compose existing widgets and there is nothing to do. If you write a real widget
that carries state or reports interactions, declare a **contract** at the top of the class:

```java
public class Dial extends UIElement {

    public static final State<Dial, Float> ANGLE =
            State.of("angle", StateTypes.FLOAT, Dial::getAngle, Dial::setAngle, 0f);

    public static final Event<Dial, Float> TURNED = Event.of(EventKind.VALUE,
            (dial, sink) -> dial.onTurned.connect(sink::accept),
            new Event.Payload<Float>() {
                @Override public <T> void write(StateMap<T> out, Float v) { out.putFloat("value", v); }
                @Override public <T> Float read(StateMap<T> in) { return in.getFloat("value", 0f); }
            },
            RatePolicy.DRAGGING);

    public static final WidgetContract<Dial> CONTRACT = WidgetContracts.register(
            WidgetContract.of(Dial.class, "dial")
                    .state(ANGLE)
                    .event(TURNED)
                    .build());

    public final Signal.Value<Float> onTurned = new Signal.Value<>();
    …
}
```

Now `io.on(dial, Dial.TURNED, (ctx, angle) -> …)` works, the dial's angle travels in a description,
and `setSessionPersistent` remembers it — none of which you write any further code for.

Three things to get right:

- **Declaration order is apply order.** If one slot depends on another (a range before a value, a
  list before an index into it), declare it first.
- **A slot needs a real getter.** A getter stub makes the state *write-only* — settable and never
  sent, which looks like it works.
- **State is what the author set, not what the user is doing.** Never declare a slot for hover,
  focus, caret or scroll.

If your widget carries nothing over a wire, say so once and you are done:

```java
WidgetContracts.localOnly(MyOverlay.class, "View state: a drag ghost positioned by the input layer.");
```

A widget that is neither contracted nor marked fails `WidgetContractCoverageTest`, on purpose.

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
| The server changes nothing on screen | you mirrored in `serve` instead of `tick` |
| Listeners stop working after an update | they were attached in `client(io)` instead of `bound()` |
| A widget arrives blank over the wire | it has no contract — see §10 |
