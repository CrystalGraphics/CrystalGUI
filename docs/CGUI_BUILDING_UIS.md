# Building UIs with CrystalGUI

**The guide for using CrystalGUI**: a UI that lives on the client, and a UI a server owns and a client shows, and
how to choose. Nothing here assumes you have read the rest of `docs/`.

## Contents

0. [Setting up your build](#0-setting-up-your-build)
1. [Which kind of UI do I want?](#1-which-kind-of-ui-do-i-want)
2. [A client-only UI](#2-a-client-only-ui)
3. [Styling](#3-styling)
4. [A networked UI](#4-a-networked-ui)
5. [Reacting to the user](#5-reacting-to-the-user)
6. [Sending your own messages](#6-sending-your-own-messages)
7. [Nesting panels](#7-nesting-panels)
7b. [Long lists — inventories, logs and files](#7b-long-lists--inventories-logs-and-files)
7c. [Reading and changing the game](#7c-reading-and-changing-the-game)
8. [Opening and closing](#8-opening-and-closing)
9. [Remembering things](#9-remembering-things)
10. [Owning a file type](#10-owning-a-file-type)
11. [Writing your own widget](#11-writing-your-own-widget)
11b. [Drawing under or over the UI](#11b-drawing-under-or-over-the-ui)
12. [Cheat sheet](#12-cheat-sheet)

## 0. Setting up your build

[`CGUI_SETUP.md`](CGUI_SETUP.md): the `com.crystalgui` plugin for one Minecraft version, singlejar-logic's
`targets {}` for one jar across many, and building against a CrystalGUI checkout.

## 1. Which kind of UI do I want?

| You want… | Use | Runs on |
|---|---|---|
| A settings screen, a HUD, a tool panel: nothing another player needs to see | **client-only** | the client |
| A machine, a shop, a shared control panel: a server owns the truth | **networked** | both, from one class |
| The client has the data, but a button must reach the server | **client-only, plus your own messages** | the client: [§6](#6-sending-your-own-messages) |
| A file format of your own, opening in the editor | **a document kind** | the client, over the workspace: [§10](#10-owning-a-file-type) |

**Does the client already have the data?** A chest pauses before it opens, because the server must say what is
inside; your inventory opens at once. If a server owns the data, build a networked UI and let the round trip deliver
it; if the client has it, build a client-only one.

**Who owns the effect?** Your inventory opens locally and every click still goes to the server: the client shows, the
server does. That is the third row. In a networked UI the server holds the widgets and sanitizes their values for you;
in a client-only one **you validate by hand** against your model, so choose it when an action is easy to check alone
(a coordinate, an id, one of a fixed set).

The kinds mix freely: a networked panel can sit inside a client-only screen.

## 2. A client-only UI

A UI is a tree of `UINode`s under a `UIDocument`, shown by a `CgUiViewport`.

```java
UIDocument document = new UIDocument();
document.styles().addStylesheet(StyleSheet.DEFAULT);          // the widgets' own geometry: not applied for you

UINode root = new UINode();
root.layout(l -> l.paddingAll(16).gapAll(8));

UIText title = new UIText("Furnace");
Button light = new Button("Light it");
Checkbox keepLit = new Checkbox("Keep lit");
root.append(title, light, keepLit);
document.append(root);

light.attachListener(() -> System.out.println("lit!"));
keepLit.attachListener(on -> System.out.println("keep lit: " + on));

CgUiViewport viewport = CgUiViewport.open(document, CgUiViewport.Threading.INLINE, "mymod:furnace");
CgUiViewport.Content content = CgUiViewport.Content.whole(document);
```

Each GUI stage frame, and for each input event:

```java
viewport.frame(stage, content);               // at CgRenderStage.SCREEN
viewport.consumeMouseEvent(event);            // from CgInputStage.SCREEN
viewport.consumeKeyboardEvent(event);
viewport.close();                             // when the screen goes
```

**A `UIDocument` is not a Minecraft screen**, so you own the host: an engine screen (CrystalGraphics' `CgScreens`)
takes the cursor and keys. The desktop's `screen.CgUiScreen` is a working example. `SEQUENCED` threading records on
a sequence of its own while the render thread presents; `INLINE` runs on the host's thread.

### Widgets you have

`Button` `Checkbox` `Switch` `Slider` `TextField` `SearchField` `Dropdown` `ColorSelector` `ProgressBar` `UIText`
`Tooltip` `Dialog` `Menu` `MenuItem` `Popover` `Tab` `TabView` `SplitView` `ScrollerView` `ListView` `TableView`
`TreeView`, plus the workbench's own (`CGUI_WIDGETS.md`). Each reports through a signal:

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

Flexbox, through `layout(…)`:

```java
row.layout(l -> l
        .flexDirection(FlexDirection.ROW)
        .gapAll(8)
        .alignItems(AlignItems.CENTER)
        .paddingAll(6));
```

**The idiom for "why is my panel zero-high"**:

```java
content.layout(l -> l.widthPercent(100f).height(0).flexGrow(1f));   // fill the parent
```

`flex-shrink` defaults to `0` here, so a growing child needs a zero basis or it keeps its content size and
overflows. Do not set `flexShrink(1)`: it squashes content below its own size.

## 3. Styling

Structure goes in Java; sizes, colours and timings go in CSS.

```java
button.addClass("danger");
```

```css
button.danger {
    background-color: #AA3333FF;
    padding: 8px;
    transition: background-color 150ms;
}
button.danger:hover { background-color: #CC4444FF; }
```

```java
document.styles().addStylesheet(StyleSheet.parse(CSS));                 // inline text
document.styles().addStylesheet(StyleSheetRegistry.of("mymod:panel"));  // assets/mymod/ui/styles/panel.css
document.styles().addStylesheet(StyleSheet.DEFAULT);                    // the built-in widget geometry
```

- `StyleSheet.DEFAULT` is **not** applied for you; without it widgets have no default geometry.
- 8-digit hex is `#RRGGBBAA`, alpha last.
- Selectors: `tag`, `.class`, `#id`, `:hover` `:focus` `:focus-visible` `:active` `:checked` `:disabled` `:blank`
  `:invalid` `:enabled`, descendant and child combinators. Not supported: `:nth-child`, attribute selectors, `~`/`+`,
  `@media`, `@import`.
- **Sizes belong in CSS**: a pixel value typed into a widget wants to be a rule, which is what makes theming
  possible.

### Scale: a `px` is a pixel at 100 %

`font-size: 20px` is twenty real pixels on a display at 100 %. A document draws at three numbers multiplied, owned by
`UiScale`:

| Part | Set by | Stored as |
|---|---|---|
| The screen | the operating system's display scaling, per monitor (`CgDisplayScale`) | nothing: read each frame |
| The desktop | Settings › Appearance › Scale | `appearance.scale`, e.g. `"125%"` |
| The application | Ctrl + / Ctrl − / Ctrl 0 inside it, in browser steps | `appearance.zoom.<app id>` |

```java
Connection follow = UiScale.bind(document, "mymod:inventory");   // follows all three
float now = UiScale.of("mymod:inventory");
```

- Minecraft's GUI scale does not apply; only the HUD canvas keeps it.
- A zoom never makes the desktop smaller than 1024 × 640 logical pixels.
- `boxes().setUiScale` by hand stops a document following; a fixed-scale screen does that on purpose.
- Files written before this (`.cgui` format 1, graphs `v` 1, desktop sessions 2, workbench sessions 6) are read at twice
  their lengths once, then saved in the new form.

## 4. A networked UI

One class describes the whole thing. It **is** a `UINode`, so it nests anywhere and `furnacepanel { }` styles it, and
it is a `Networked<YourDataType>`: a view of your own object.

```java
public final class FurnacePanel extends UINode implements Networked<FurnaceData> {

    public static final UiType<FurnacePanel, FurnaceData> TYPE =
            UiType.of("mymod:furnace", FurnacePanel::new);

    // Every UINode field is a part of this panel. Declared = created and named for you.
    public Switch power;
    public Slider throughput;
    public ProgressBar burn;
    public UIText status = new UIText("");          // needs a constructor argument? just write it
    public Button purge = new Button("Purge");

    @Override
    public void build(FurnaceData model) {        // SERVER, once — the structure
        append(new UIText("Furnace"));
        append(row("Power", power));
        append(row("Rate", throughput));
        append(row("Burn", burn));
        append(status);
        append(purge);
    }

    @Override
    public void serve(FurnaceData model, ServerScope io) {   // SERVER, once — the behaviour
        // the user → the model
        io.on(power,      Switch.TOGGLE,        (ctx, on)    -> model.setRunning(on));
        io.on(throughput, Slider.VALUE_CHANGED, (ctx, value) -> model.setRate(value));
        io.on(purge, Button.ACTIVATE, ctx -> model.purge());

        // the model → the screen: stated once, kept true by the engine
        io.project(power, Switch.CHECKED,      model::isRunning);
        io.project(burn,  ProgressBar.FRACTION, model::burnFraction);
        io.project(status, () -> model.isRunning() ? "Running" : "Idle", UIText::setText);
    }
}
```

`io.on` is *the user changes the model*; `io.project` is *the model changes the screen*. There is no `tick` to write
for keeping the screen current.

### The model — what the panel is a view of

`FurnaceData` is **yours**, a plain class with no interface or annotations; CrystalGUI never looks inside it.

```java
public final class FurnaceData {              // no interface to implement, no annotations
    private boolean running;
    private float rate;

    public boolean isRunning()   { return running; }
    public void setRunning(boolean v) { running = v; }
    …
}
```

One model, one window, handed over at open:

```java
ServerWindows.of(connection).open(FurnacePanel.TYPE, furnaceAt(x, y, z));
```

A different model is a different window; the same model for two players is one furnace both see.

### Keeping the screen up to date — projections

```java
io.on(power, Switch.TOGGLE,  (ctx, on) -> model.setRunning(on));   // the user changes the model
io.project(power, Switch.CHECKED, model::isRunning);               // the model changes the screen
```

`project` says "this widget shows that", and the engine keeps it true. A hand-written per-tick copy is a list you
forget to add to; a projection has no list.

- **Nothing is written unless it changed**: an unchanged model writes no widget and sends zero bytes.
- **The first screen is already right**: projections run once before the window is described.
- **A window nobody is looking at costs nothing**: hidden or minimised, projections are not evaluated.
- **Your model is untouched**: `model::isRunning` is a getter it already has.

#### The two forms

**With a state constant**, checked at compile time (`Switch.CHECKED` takes a `Boolean`):

```java
io.project(power,      Switch.CHECKED,       model::isRunning);
io.project(burn,       ProgressBar.FRACTION, model::burnFraction);
io.project(throughput, Slider.VALUE,         model::rate);
```

**With a setter**, for a computed value or a widget with no constant:

```java
io.project(status,  () -> model.isRunning() ? "Running" : "Idle", UIText::setText);
io.project(coolant, () -> model.engine().coolant(), ProgressBar::setFraction);
```

A `null` anywhere in the chain means "nothing to show yet". Both forms start with the widget, so nothing is written
twice.

#### Lists

```java
io.projectEach(model::slots, slotList,      // where the items come from, and the container
        Slot::id,                           // each item's stable identity — NOT its index
        slot -> new SlotRow(),              // build a row the first time an item is seen
        (row, slot) -> ((SlotRow) row).show(slot));
```

A row whose key is unchanged keeps its element, so an added item is one insert and a moved one a move. The container
is the projection's alone, and **keys must be unique**: a duplicate is refused.

#### The shortcut: `autoProject`

A panel field `throughput` and a model accessor `throughput()`, `getThroughput()` or `isThroughput()` wire themselves:

```java
io.autoProject(model);
```

```
auto-projection: 1 wired, 0 skipped, 3 with no matching accessor
  wired   throughput <- throughput()
```

Anything projected by hand is left alone, in any order. A widget you wrote joins by declaring its slot with
`.primary(ANGLE)` on its contract ([§11](#11-writing-your-own-widget)); one without is reported, never guessed.

#### If the model is enormous

```java
io.projectWhen(model::revision);
```

An unchanged tick then costs one comparison, provided the revision moves for **every** change that matters.

#### One thing it will never do

It will not decide which widget shows which field: that is your screen's design, one line per widget (React's
`<Slider value={m.throughput}/>`). It decides which fields changed.

**It lives on the server only**, which is why the model is a parameter of the server hooks:

```java
void build(FurnaceData model)              // server — has one
void serve(FurnaceData model, ServerScope)  // server — has one
void tick(FurnaceData model)                // server — has one (and is usually EMPTY: see
                                            // projections, above)

void client(ClientScope io)                 // client — no model, and there never was one
```

The client shows a picture of your furnace and has no `FurnaceData`, so no logic can read the model on the wrong side.

- **`FurnaceData` may be a server-only type**: it appears only in erased signatures.
- **Need it in a helper?** Assign it to a field in `serve`.

### Those event constants

`Switch.TOGGLE` and `Slider.VALUE_CHANGED` are `public static final` fields on the widgets, each saying what the
widget can report and what it hands you:

```java
Switch.TOGGLE           // Event<Switch, Boolean>   — flipped, gives you the new state
Slider.VALUE_CHANGED    // Event<Slider, Float>     — dragged, gives you the value
Button.ACTIVATE         // Event<Button, Void>      — pressed, gives you nothing
```

The value arrives decoded; an event a widget does not have will not compile; and a widget's tempo is handled for you
(a slider throttles, a text field debounces, both deliver the value you ended on). [§5](#5-reacting-to-the-user) has
the full list.

### Opening it — where the connection comes from

A networked window needs a connection to one player: CrystalGraphics' `CgNetwork` holds one per player, found from
the player object or their profile UUID.

```java
public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, ...) {
    if (world.isRemote) return true;            // server decides; the client just gets the window
    CgProtocolConnection<Object> connection = CgNetwork.forPlayer(player);
    if (connection != null) {
        ServerWindows.of(connection).open(FurnacePanel.TYPE, furnaceAt(x, y, z));
    }
    return true;
}
```

**That is the entire wiring**: no window subclass, client registration or id strings. A connection exists while the
player is on the server; `forPlayer` answers `null` otherwise, and `CgNetwork.client()` is `null` out of a world.
Give the panel a `key(model)` and a second `open` brings the existing window forward.

> **Single player**: a `GuiScreen` hosting the window must return `false` from `doesGuiPauseGame()`. Pausing stops the
> integrated server, so the connection is never pumped and the panel never fills in, with nothing in the log.

### Letting the client ask for a window

**On the server, say what may be asked for**; nothing is client-openable until you do:

```java
ServerWindows.openable(FurnacePanel.TYPE, (viewer, args) -> {
    BlockPos pos = readPos(args);                        // a CLAIM from the client
    if (!world.isBlockLoaded(pos)) return null;          // null = "no"
    if (player(viewer).getDistanceSq(pos) > 64) return null;
    return furnaceAt(pos);                               // the model to open with
});
```

**On the client, ask:**

```java
CgStateMap<Object> args = new CgStateMap<>(connection.ops());
args.putInt("x", pos.getX());   // …y, z
ClientWindows.requestOpen(FurnacePanel.TYPE, args, granted -> {
    if (!granted) player.addChatMessage(new ChatComponentText("You are too far away."));
});
```

- No connection argument: a client has one. Asked while unconnected it answers `false`.
- **`granted` says only whether a window is coming**; the window arrives by the ordinary mount path.
- **`args` is untrusted**: re-derive the model from it (look up the position, resolve the id).
- Returning `null` is an ordinary refusal.
- Asking twice does not open twice, given a `key(model)`.
- The same single-player rule: a screen that asks must not pause the game.

### What runs where

| Hook | Runs | For |
|---|---|---|
| `build(model)` | server, once | structure: `append`, rows, classes |
| `serve(model, io)` | server, once | what the UI does |
| `tick(model)` | server, per world tick | your own logic; usually omitted (projections keep the screen current) |
| `stillValid(model, viewer)` | server, per tick | `false` closes the window |
| `title(model)` / `key(model)` | server | its name; `key` brings an open window forward |
| `bindWidgets()` | client, every bind | widget listeners |
| `client(io)` | client, on mount **and after every re-describe** | client listeners, wire methods |
| `closed(CloseReason)` | both | teardown, with the same reason on both sides |

- **Do not copy the model in `tick`**: project it in `serve`.
- **Set everything client-side up every time** `client(io)` runs: each run is a new panel over a new tree.
- **Every non-static `UINode` field is a part**: created and `setId(fieldName)` on the server, found by that name on the
  client.
- **Methods may be side-specific; fields may not**: a method body resolves when it runs, but a field's type resolves
  when the class loads, so a client-only field type breaks the class on a dedicated server.

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

An event is typed in its widget:

```java
Event<Slider, Float>            // on a Slider, carries a Float
Event<Dropdown, Integer>        // on a Dropdown, carries the chosen index
Event<Button, Void>             // on a Button, carries nothing — the lambda takes just (ctx)
```

`io.on(slider, TextField.COMMITTED, …)` does not compile. A `Void` event drops the second parameter:

```java
io.on(purge, Button.ACTIVATE, ctx -> model.purge());
```

### What `ctx` gives you

```java
ctx.viewer()        // WHO did it — the player, on 1.7.10 the EntityPlayer
ctx.element()       // which widget
ctx.payload()       // the raw payload, if you took the untyped `on`
ctx.session()       // the window's session

ctx.call("mymod/ask", args, ok -> …, err -> …);   // ask THE VIEWER THAT DID THIS
ctx.setVisible(false);                            // stop sending this viewer updates
```

`ctx.viewer()` lets a handler say who. In a handler use `ctx.call`, which asks the viewer who acted; `io.call` asks
the window and refuses when it has several viewers.

### The server does not take your client's word for it

- **A value a gesture could have produced is clamped and delivered**: a forged slider value of 9999 arrives as the
  maximum, `NaN` as the minimum, an overlong string cut to `setMaxLength`.
- **What no gesture could produce is refused**: an event on a disabled or inert element, or a kind you did not ask
  for. Refusals are counted per viewer, and a viewer that keeps sending them stops being heard.

Using the widget's own event constant is all it takes.

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

```java
io.on(enabled,   BooleanControl.CHANGED, (ctx, on)     -> model.setEnabled(on));
io.on(threshold, NumberControl.CHANGED,  (ctx, value)  -> model.setThreshold(value));
io.on(tint,      ColorControl.CHANGED,   (ctx, colour) -> model.setTint(colour));
```

### Choosing between the pairs

- `TEXT_CHANGED` per keystroke for a live preview; `COMMITTED` on Enter or blur for anything expensive or destructive.
- `ACTIVATE` is "I pressed this"; `SELECTION` is "the selection is now that". A `Dropdown` has both, a `TabView` only
  the second.

### If you need a kind that has no constant

```java
io.on(widget, "myCustomKind", ctx -> … ctx.payload().getInt("x", 0) …);
```

The untyped form, for a kind you declared yourself ([§11](#11-writing-your-own-widget)).

## 6. Sending your own messages

**Server answers a question:**

```java
@Override public void serve(FurnaceData model, ServerScope io) {
    io.onCall("history", (args, respond) -> {
        CgStateMap<Object> out = io.newMap();
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
            io.call("history", null, reply -> log.setText(reply.getInt("burns", 0) + " burns"), null));
}
```

**Server pushes something with no answer:**

```java
io.notify("flash", null);
```

You never namespace these: both sides derive the same prefix from the tree (`furnace/history` for a panel field named
`furnace`).

### If your UI is client-only

A client-only UI has no session, but the connection is public:

```java
CgProtocolConnection<Object> io = CgNetwork.client();                 // client side
CgProtocolConnection<Object> io = CgNetwork.forPlayer(serverPlayer);   // server side: the player, or their UUID

io.notify("mymod:setThroughput", args);          // fire and forget
io.onNotify("mymod:setThroughput", args -> ...); // the other end
io.call("mymod:history", null, reply -> ...);    // if you need an answer
```

**Rate-limit what a drag sends** with `CgRateGate` (CrystalGraphics' `net.rate`), the gate the session uses:

```java
CgRateGate<UIElement, Float> gate = new CgRateGate<>((widget, kind, value) -> {
    CgStateMap<Object> args = new CgStateMap<>(io.ops());
    args.putFloat("value", value);
    io.notify("mymod:setThroughput", args);
});

Slider.VALUE_CHANGED.attach(throughput, gate);   // takes the widget's own rate: 20/s while dragging
io.onTick(gate::flush);                          // a held value needs something to let it go
```

The flush matters: a debounce holds the last value until flushed, so without it the last thing typed is never sent.
The server has no widget to check against, so treat what arrives as a claim: look the subject up and clamp against
your model.

## 7. Nesting panels

A panel is an element: hold it as a field, build it with the **slice** of the model it may see, and attach it.

```java
public EnginePanel engine;

@Override public void build(FurnaceData model) {
    engine = EnginePanel.TYPE.build(model.engine());
    append(engine);
}

@Override public void serve(FurnaceData model, ServerScope io) {
    io.attach(engine, model.engine());        // the child is ticked and closed with this window
}
```

The child's hooks take `EngineModel`, so it cannot reach the furnace. A child talks back with a plain callback:

```java
engine.onRestarted(() -> status.setText("engine restarted"));
```

`title`, `key` and `stillValid` are asked of the root panel only.

## 7b. Long lists — inventories, logs and files

A panel's widgets are described in full, which is wrong for a collection: two hundred slots re-sent on every change,
with a screenful visible. **Three shapes**:

### The server holds it and only the server has it — stream it

```java
public void serve(Machine model, ServerScope io) {
    io.stream(inventory, new RowSource<Slot>() {
        public int count()                  { return model.slotCount(); }
        public List<Slot> rows(int a, int b) { return model.slots(a, b); }
        public Object keyOf(Slot slot)      { return slot.index(); }     // NOT its position
    }, slot -> slotRow(io, model), StreamsPanel::writeSlot);
}
```

`inventory` is a plain container and the rows become its children; only the window each viewer looks at exists, so ten
thousand rows cost a screenful. On the client, wrap a `RemoteRows` around it and say what is visible:

```java
RemoteRows rows = new RemoteRows(requester, Placeholder::new);
rows.showing(firstVisibleRow, lastVisibleRow + 1);
```

- **`keyOf` makes an insert an insert**: an unchanged key keeps its element.
- **A row is an ordinary described subtree**, so a `Button` in it reports like any other. Read what a row shows off
  the element, never a captured index: elements are reused.
- **A window at the last row follows it**, as a log wants.

### Someone else's protocol already serves it — read it there

```java
public void client(ClientScope io) {
    io.workspace().files().list(root).then(listing -> {
        for (FsMessages.Entry entry : listing.entries()) {
            io.addLocal(files, new UIText(entry.name()));
        }
    });
}
```

A file list is the workspace's (watches, etags, chunked reads, permissions), never UI state.

### It is small and fixed — describe it

Every other panel in this guide.

### Controls of the viewer's own — `io.addLocal`

```java
io.addLocal(row, new Button("Copy"));
```

Shown like any child and never described, numbered or counted. Appended by hand instead, it shifts the server's next
insert by one index, silently.

## 7c. Reading and changing the game

A window may run on a thread of its own, and the game's world, player and inventories live on the game's threads. UI
code asks a **`CgHostThread`** (CrystalGraphics' `com.crystalgraphics.job`) to do the work there and gets the answer
where the UI runs.

### Showing something that changes: `extract`

```java
public class HealthBar extends UIElement {
    private final ProgressBar bar = new ProgressBar();

    public HealthBar() {
        append(bar);
        extract(() -> player().getHealth() / player().getMaxHealth(), bar::setFraction);
    }
}
```

Each frame the read runs on the game's client thread; when its answer changes, `bar.setFraction` runs on the UI's. It
starts when the widget joins a window and stops when it leaves.

```java
extract(() -> List.copyOf(player().getInventory().items), slots::show);       // a copy, never the live list
extract(CgHostThread.SERVER, () -> level().getDayTime(), clock::setTime);       // the integrated server's world
extract(CgHostThread.RENDER, () -> camera().position(), compass::point);        // where frames are drawn
```

### Doing something, or asking once: `run` and `call`

```java
drop.onPressed.connect(() -> CgHostThread.CLIENT.run(() -> player().drop(stack, false)));

CgHostThread.SERVER.call(() -> level().getBlockState(pos))                       // runs on the server thread...
        .then(state -> label.setText(state.getBlock().getName().getString())); // ...answered on the UI's
```

`call` answers a `CgReply` (`onError`, `map`, `cancel`).

### The threads

| `CgHostThread` | What lives there | 1.7.10, Forge 1.8–1.12 | Modern |
|---|---|---|---|
| `CLIENT` (the default) | the client world, the player, inventories, screens | the main thread, which also draws | Minecraft's own task queue |
| `SERVER` | the authoritative world, in single player | the integrated server's thread | the integrated server's task queue |
| `RENDER` | the camera, interpolated positions, what is on screen | the main thread | the thread that draws |

### What to know

- **A read answers a snapshot** (`List.copyOf(items)`): the UI reads it later on its own thread.
- **Never touch a widget inside the work**: touch it in `then`, or in an extract's use.
- **It is instant where it can be**: where the named thread draws the frame, the read happens as the frame starts and a
  `call` is answered before it returns; otherwise at the next frame.
- **An extract's use runs only when the answer changes** (by `equals`).
- **`SERVER` is single player only**: connected to a server, `CgHostThread.SERVER.isAvailable()` is false, an extract
  reads nothing, `run` drops the work with a warning and `call` fails with `CgHostThread.UNAVAILABLE`. Use a
  networked UI for the server's truth.

## 8. Opening and closing

**Opening is the server's decision**, as Minecraft opens containers:

```java
ServerWindows.of(connection).open(FurnacePanel.TYPE, furnace);
```

A client that wants one asks ([§4](#letting-the-client-ask-for-a-window): `ServerWindows.openable` and
`ClientWindows.requestOpen`). `com.crystalgui.app.machine.MachineExample` is the whole thing on F8; each loader adds a
key binding and a tick (`MachineExample1710`, `MachineExampleModern`) and decides nothing else.

**Closing** comes from either end:

```java
window.close("the block was broken");           // server
```

```java
@Override public boolean stillValid(FurnaceData model, Object viewer) {
    return model.isStillThere();                // false closes it
}
```

The user pressing the X reaches `closed(CloseReason)` on both sides with the same value (`SERVER`, `CLIENT`,
`NOT_VALID`, `CONNECTION_LOST`). With a `key`, re-opening brings the window forward with its scroll and half-typed
text.

## 9. Remembering things

For a **client-only** UI, widget state survives a restart. Give the widget a stable id and opt in:

```java
divider.setId("furnace.split");
divider.setSessionPersistent(true);
```

What is remembered is the widget's own authored state (a divider's position, a field's text, a selection), for any
widget with a contract. View state (scroll, hover, focus) is neither remembered nor sent.

## 10. Owning a file type

A file format of your own that opens in the editor like any other: **one declaration**.

```java
public final class NotesKind {
    public static final DocumentKind KIND = DocumentKind.of("mymod:notes", "Notes")
            .files(FilePatterns.extension("notes"))     // a name, an extension or a glob
            .icon("mymod:notes")
            .model((resource, bytes) -> NotesModel.decode(bytes))   // what it IS
            .editor(NotesView::new);                                 // one way of looking at it

    public static Disposable register(DocumentKinds kinds, Owner owner) {
        return kinds.contribute(owner, KIND);
    }
}
```

From that call: `.notes` files open with your icon, Ctrl+Z reaches your edits, Ctrl+S encodes and writes with the
etag, a server change reloads a clean document and marks a dirty one, unsaved work survives a quit, and each person's
view comes back. Registration refuses a kind with no model, and returns a `Disposable`. The whole example is
`com.crystalgui.example.notes`, about two hundred lines.

### The model is what the document IS

```java
public final class NotesModel extends AbstractDocumentModel {

    static NotesModel decode(byte[] bytes) { … }

    @Override public byte[] encode() { … }          // what a save writes
    @Override public void adopt(byte[] bytes) { … }  // the file changed underneath you

    public void add(String text) { apply(new AddEdit(text)); }   // every change, through apply
}
```

- **`apply(Edit)` is the one door**: it runs the edit, moves the version and records it, so Ctrl+Z works and dirtiness
  is a version comparison.
- **`adopt` is not an edit**: a reload goes through `adopted()`, moving the version with nothing on the undo stack.
- **A model names no widget**, so the document can be analysed, saved and searched with no tab open.

### The view is only a view

```java
public final class NotesView implements DocumentEditor {
    public NotesView(Document document) {
        this.model = document.as(NotesModel.class);
        this.subscription = model.onItemsChanged.connect(this::rebuild);
    }

    @Override public UIElement view() { return root; }
    @Override public void disposeView() { subscription.disconnect(); }
}
```

Every gesture calls the model, and the view follows the **model's** signal, never its widgets, so split panes, previews
and diffs need no synchronisation and an undo or reload reaches every view alike. `disposeView` releases what the view
owns; the model outlives it.

### Where somebody was looking is theirs

```java
@Override public <T> void writeViewState(CgStateMap<T> out) { out.putFloat("zoom", zoom); }
@Override public <T> void readViewState(CgStateMap<T> in)   { zoom = in.getFloat("zoom", 1f); }
```

Caret, scroll, folds, pan, zoom, the open panel: stored per person by the session. **Never in `encode()`**: the
workspace is shared, so the last saver's camera would be everybody's.

### Reaching the workspace

```java
workspace.files().read(resource).then(answer -> …).onError(error -> …);
workspace.files().write(resource, bytes, etag).then(newEtag -> …);
workspace.files().batch("move files", batch -> batch.rename(from, to, false));

workspace.watch(folder, true).onChanged.connect(changes -> …);
workspace.presence().whoIsEditing(resource);
workspace.capabilities().isValidName(typed);
workspace.health().roundTripMillis();
```

Every answer is a `CgReply` (`then`, `onError`, `map`, `all`), its continuations on the frame thread. A failure is
structured: `error.code()`, `error.detail()`, and for a conflict the etag the file holds. The document layer reads and
writes for a file type; this is for a tree, a picker or a panel looking at the workspace itself.

### Content that is not on disk

```java
workspace.registerScheme("mymod-generated", new MyProvider());   // this server's
ContentProviders.contribute("mymod-generated", new MyProvider()); // the process's
```

`contribute` when you register at mod init with no workspace. A provider answers `read`, and optionally `symbolOf`
(what it is: a tab's glyph) and `locate`, each a `CgReply`. `openResource` then opens it as it opens a file.

### When it does not work

| What you see | What it is |
|---|---|
| Registration throws | the kind has no `.model(…)` |
| Your file opens as plain text | nothing claims the extension: check `.files(…)`; a leading dot means a whole name (`.gitignore`) |
| Ctrl+Z moves the camera instead of undoing | a view-state write went through `apply` |
| Ctrl+Z resurrects text a reload replaced | `adopt` went through `apply`: use `adopted()` |
| The tab is dirty the moment it opens | `encode()` does not round-trip what `decode` was given (a trailing newline, a field order) |
| Everyone sees the same scroll position | view state is in `encode()`: use `writeViewState` |
| A second split pane shows a stale copy | the view follows its widgets, not the model's signal |

## 11. Writing your own widget

For a widget of your own that **carries state** or **reports interactions**. One declaration is read by everything:
the description codec, the client's listeners, session persistence, and `io.on`'s type check. Without it a widget
works locally but cannot travel or be remembered.

```java
State<W, V>              // one piece of state: a wire name and a getter/setter pair
Event<W, P>              // one interaction: how to listen, and what it carries
WidgetContract<W>        // the two lists, registered under the widget's tag
```

### A worked example

A dial that holds an angle and reports when you turn it:

```java
public class Dial extends UINode {

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
                @Override public <T> void write(CgStateMap<T> out, Float v) {
                    out.putFloat("value", v);
                }
                @Override public <T> Float read(CgStateMap<T> in) {
                    return in.getFloat("value", 0f);
                }
            },
            CgRatePolicy.DRAGGING);                                // throttle the drag

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

Now, with no further code:

```java
io.on(dial, Dial.TURNED, (ctx, angle) -> model.setAngle(angle));   // typed, over the wire
dial.setSessionPersistent(true);                                   // remembered across restarts
```

### The parts, one at a time

**`State.of(name, type, getter, setter, fallback)`**: `name` is the wire key (renaming it is a format change).
`StateTypes` has `STRING` `INT` `FLOAT` `DOUBLE` `BOOL`, `enumOf(Class)`, `stringListUnder(key)`,
`doubleArrayUnder(key)`.

```java
State.of(...).omittedWhen("")             // write nothing when it is the default
State.of(...).sanitizedBy(v -> clamp(v))  // clean a value arriving from a PEER
```

`omittedWhen` keeps a default-valued widget's state absent, so identical trees hash the same.

**`.primary(slot)`**: which slot someone means when they want this widget to show a value; what
[`io.autoProject`](#the-shortcut-autoproject) writes, and nothing else. Leave it off when there is no honest answer
(`SplitView` has none; `Slider` declares `VALUE`). A widget with none is reported by `autoProject` and projected by
hand:

```java
io.project(divider, model::paneWeights, SplitView::setWeights);   // float[]
```

**`Event.of(kind, attach, payload, rate)`**: `attach` is how a client subscribes, `(widget, sink) -> …`, so networking
never names your class. A valueless event is `Event.signal`:

```java
public static final Event<Dial, Void> RESET =
        Event.signal("reset", (dial, sink) -> dial.onReset.connect(sink));
```

`kind` is yours, unique within the widget's own contract only. `rate` is `IMMEDIATE`, `TYPING` (debounce 150 ms) or
`DRAGGING` (throttle 50 ms); both of the latter deliver the final value.

### Four rules worth following

1. **Declaration order is apply order**: a range before the value it clamps, a list before an index into it.
2. **A slot needs a real getter**: a stub like `d -> 0f` makes it write-only and never sent.
3. **State is what was authored**, never hover, focus, caret or scroll: if reloading ought to give it back, it is state.
4. **Declare a `primary` only if one slot is what the widget is**: a wrong guess writes the wrong slot every tick.

Make setters **idempotent**, returning early when unchanged: a setter that always reports a change sends a packet per
tick forever.

### If your widget carries nothing

```java
WidgetContracts.localOnly(MyOverlay.class,
        "View state: a drag ghost positioned by the input layer, with no moment a peer could use it.");
```

A widget neither contracted nor marked fails `WidgetContractCoverageTest`.

## 11b. Drawing under or over the UI

Something that is not a widget (a HUD badge, a picture behind every screen) draws at one of CrystalGraphics' GUI
stages: `CgRenderStage.SCREEN` (over any screen) or `CgRenderStage.HUD` (over the in-game HUD). CrystalGUI's desktop
is a renderer on each at `CgUiScreen.ORDER`, so your order says which side of it you land on.

```java
CgMaterial material = CgMaterial.load("mymod:shaders/badge.shader");   // a #domain ui material

CgRenderStage.Registration badge = CgRenderStage.HUD.register(CgUiScreen.ORDER + 1, frame ->   // over the windows
        frame.canvas().box(8f, 8f, 32f, 32f).fill(0xFFFF8800).material(material).draw());
badge.close();   // and it stops
```

- **Coordinates** are the window's device pixels, origin top left (`frame.canvas()`); `frame.scaled()` uses
  Minecraft's GUI pixels; `frame.host().width()`/`height()` is the surface.
- **Once a frame each**, on the render thread. `SCREEN` fires whoever's screen is up; neither fires on a server, and
  Fabric below 1.16 has no `SCREEN`.
- **Below `COMPOSITOR` is under the pinned windows**, above it over them; ties draw in registration order.
  `COMPOSITOR` is `CgRenderStage.SCREEN_WIDGETS`. World-following widgets (damage numbers, name plates) register at
  `CgRenderStage.WORLD_WIDGETS`, under every window.
- **What you record executes at once**; nothing is kept, so record every frame.
- **`SCREEN` is rehearsed behind the loading screen** (`CgRehearsal`), your renderer included; `CgRehearsal.active()`
  says when.
- The stage canvas: CrystalGraphics' [`docs/UI.md`](../CrystalGraphics/docs/UI.md). A world-space effect belongs in
  CrystalGraphics' scene or world stages (`CgScene.client()`, `CgRenderStage.TRANSLUCENT`;
  [`docs/SETUP.md`](../CrystalGraphics/docs/SETUP.md) § *Drawing from your mod*).

## 12. Cheat sheet

```java
// ── client-only ────────────────────────────────────────────────────────────
UIDocument document = new UIDocument();
document.styles().addStylesheet(StyleSheet.DEFAULT);
document.append(new UINode().layout(l -> l.paddingAll(12).gapAll(6))
        .append(new Button("Go").attachListener(() -> …)));
CgUiViewport viewport = CgUiViewport.open(document, CgUiViewport.Threading.INLINE, "mymod:thing");
// per GUI frame: viewport.frame(stage, CgUiViewport.Content.whole(document));

// ── networked ──────────────────────────────────────────────────────────────
public static final UiType<MyPanel, MyModel> TYPE = UiType.of("mymod:thing", MyPanel::new);

build(m)       → append(...)                       server, once
serve(m, io)   → io.on(widget, Widget.EVENT, ...)    server, once
io.project(w, State, m::get)                         server, stated once, kept true
client(io)     → widget.attachListener(...)          client, on mount AND
               → io.onCall / io.onNotify                     every re-describe

ServerWindows.of(connection).open(TYPE, model);

// ── client-only, but the server does the work ──────────────────────────────
CgProtocolConnection<Object> io = CgNetwork.client();
CgRateGate<UIElement, Float> gate = new CgRateGate<>((w, kind, v) -> io.notify("mymod:set", args(v)));
Slider.VALUE_CHANGED.attach(slider, gate);       // the widget's own rate
io.onTick(gate::flush);                          // or a held value never leaves

// ── layout ─────────────────────────────────────────────────────────────────
l.widthPercent(100f).height(0).flexGrow(1f)      // fill the parent
l.flexDirection(FlexDirection.ROW).gapAll(8)     // a row
```

**Where to look when something is wrong:**

| Symptom | Usually |
|---|---|
| Nothing is styled | `StyleSheet.DEFAULT` was not added, or the document was never framed |
| A panel is zero-high | missing `height(0).flexGrow(1)`: `flex-shrink` is `0` here |
| One widget never updates while the rest do | no projection for it: `autoProject`'s log names what it could not wire |
| Nothing on screen ever updates | no projections at all |
| Listeners stop working after an update | they were attached outside `client(io)`, the only thing re-run when the tree is rebuilt |
| A widget arrives blank over the wire | it has no contract ([§11](#11-writing-your-own-widget)) |
| A client-only UI floods the server while you drag | no `CgRateGate` |
| The last thing typed never arrives | nothing drives `CgRateGate.flush()` |
