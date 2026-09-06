# CrystalGUI on a Server — Serialization, Sessions, RPC

> **Current-state reference** for `core/src/main/java/com/crystalgui/serialization/`,
> `core/src/main/java/com/crystalgui/ui/contract/` and `core/src/main/java/com/crystalgui/net/` —
> the last of which is five packages now: the sessions at its root, `mirror` (the edit script,
> generic in the node type), `protocol` (envelopes and routing), `wire` (the byte transport) and
> `window` (a window's lifetime, and what a panel is handed).
>
> Companions: `CGUI_BUILDING_UIS.md` (how to write one — start there if you are USING this),
> `CGUI_NETWORKING_PRIMER.md` (the same ground bottom-up), `CGUI_WIDGETS.md` (what the widgets are)
> and `CGUI_STYLE_RENDER_PIPELINE.md` (what the styles mean).
>
> Re-verify against the code before trusting a specific signature.

---

## 0. The problem this solves

A dedicated Minecraft server has **no CrystalGraphics**, no GL context, no fonts, no resource manager.
It still needs to be the thing that decides what a GUI *is* — build a tree, hold live state, react to
clicks — while the client does all the drawing.

So the split is:

```
SERVER                                          CLIENT
build a UINode tree  ──── description ────►  rebuild the same tree
hold session state      ◄──── events ─────────  user clicks, types, drags
call client methods     ◄───── RPC ──────────►  call server methods
```

Two consequences shape everything below:

1. **The tree must be describable without being renderable.** Hence codecs over `UINode`, and hence
   the rule that anything on a server path may not touch `CgIO`, fonts or GL.
2. **Descriptions repeat.** The same GUI opens hundreds of times. Hence content-addressing: the open
   packet carries a *hash*, and the bytes only move if the client has never seen them.

---

## 1. Codecs

`serialization/Codec.java`, `DynamicOps.java`, `Codecs.java`, `JsonOps.java`, `PlainOps.java`

A hand-rolled, DFU-shaped codec layer. Not Mojang's DataFixerUpper — no dependency on it — but the
same two-interface idea:

```java
public interface Codec<A> {
    <T> T encode(DynamicOps<T> ops, A input);
    <T> A decode(DynamicOps<T> ops, T input);
}
```

`DynamicOps<T>` is the format. It is a **tree** interface, not a streaming one: `createMap`,
`createList`, `createString`, `getMap`, `getList`, … Every accessor throws `CodecException` on a type
mismatch rather than returning null, so a malformed packet fails at the field that is wrong instead of
several layers later.

Two implementations ship:

| Ops | Backing | Used for |
|---|---|---|
| `JsonOps` | Gson `JsonElement` | debugging, tests, anything human-readable |
| `PlainOps` | plain `Map`/`List`/`String`/`Number`/`Boolean` | the default — no Gson on the server path |

`Codecs` holds the helpers: `STRING`/`INT`/`FLOAT`/`DOUBLE`/`LONG`/`BOOL`, `xmap` (transform a codec's
type), `enumOf` (**by constant name, never ordinal** — inserting a constant mid-enum must not re-point
an existing wire value), and the `MapCodecBuilder` / `MapCodecReader` pair for record-shaped encoding:

```java
Codecs.map(ops).field("t", Codecs.STRING, tag)
               .field("v", Codecs.FLOAT, value)
               .build();

var in = Codecs.read(ops, input);
String tag = in.field("t", Codecs.STRING);
float v    = in.optional("v", Codecs.FLOAT, 0f);
```

**Everything is `LinkedHashMap`-ordered on purpose.** Insertion order is what makes the same tree
encode to the same bytes twice, which is what makes hashing work at all (§4).

## 2. Widget state — `StateMap` and the contract that fills it

`serialization/StateMap.java`, `ui/contract/`

A `StateMap` is a small typed key/value bag over any `DynamicOps`. **What goes in it is declared, not
written.** A widget states what kind of thing it is once, and the engine derives the encoding:

```java
public static final State<Slider, Float> VALUE =
        State.of("value", StateTypes.FLOAT, Slider::getValue, Slider::setValue, 0f)
                .sanitizedBy(v -> Float.isNaN(v) ? 0f : v);

public static final WidgetContract<Slider> CONTRACT = WidgetContracts.register(
        WidgetContract.of(Slider.class, "slider")
                .state(MIN).state(MAX).state(STEP).primary(VALUE)
                .event(VALUE_CHANGED)
                .build());
```

**Declaration order is apply order**, and four widgets depend on it: a slider must take its range
before its value or the value is clamped against the old bounds; a dropdown must have its options
before an index into them means anything; a text field must have its mode before its text, or the text
is parsed by the old one; a colour selector must take mode, then original, then colour.

A slot omitted at its default writes nothing, so a default-valued widget produces an **empty** state
map, which is then dropped entirely. Every read takes a fallback, so a slot added later decodes against
an older sender with no version bump.

> **This replaced two protected hooks**, `writeState`/`readState`, which seven widgets implemented by
> hand. Three defects came out of the change and all three were the same shape: a slot that is
> **settable and never written**. A stub getter is declared in a way that reads as complete, so the
> state simply never travels and there is nothing to search for. `ClientSmokeTest` walks the whole
> registry over a loopback wire for exactly that, and found eighteen more — see §8.

**28 widgets carry a contract.** The rest are on a census (`WidgetCensus`) with a written reason each,
and a coverage test fails on a class that is neither.

## 3. Descriptions — `UIElementMirror`

`net/mirror/UIElementMirror.java`, `ui/dom/UIElementRegistry.java`

Encodes a whole `UIElement` subtree: tag, id, classes, flags, inline styles, per-widget state,
children. The tag comes from **`UIElementRegistry`**, which maps a `Name` to a factory and a contract
(`create`, `names()`, `bootstrap()` — idempotent, auto-called by every lookup, and driven by the
`NodeKinds` services on the classpath rather than a hand-written list). A widget with no registered
kind cannot be described at all.

**What counts as a child is `describedChildren()`**, and a widget whose own parts are light children
must override it. A part added with `appendStructural` is still a light child, so a widget that does
not override describes its own scaffolding — and the far side then builds those parts in its
constructor *and* adopts the described ones. Measured at 2n−1 elements per widget across the whole
config kit; for `Dialog` and `SplitView`, which refuse public children, decoding threw outright.
`Tab`, `TabView`, `Dialog`, `SplitView`, `ConfigControl` and `ColorSelector` each answer for
themselves.

Encoding is aggressively sparse — flags pack into one int (bit 0 enabled, bit 1 hit-testable, both
default on so an ordinary element omits the field), and every field with a default value is left out.

**Only `INLINE`-origin style values travel** (`serialization/style/`). `StyleValueCodecs` keys codecs
by *value type* rather than by property — there are ~96 registered properties but only about twenty
distinct types, so roughly eight codecs cover most of them (`LengthPercentageAuto` alone covers
twenty-five properties, and enums are handled generically). `InlineStyleCodec` walks an element's
inline slots and encodes each.

Consequences worth internalising:

- A widget's own `DEFAULT`-origin baseline styling **never goes over the wire** — the client's copy of
  the same widget class produces it locally.
- Stylesheet-origin values never travel either; the *sheet* travels (§6), not its computed results.
- `forProperty` returns `null` for a type with no codec, and callers must treat that as an **error
  naming the property**, never as "skip it". A silently dropped style is a UI that renders subtly
  wrong on one side only — the worst failure mode this layer has.
- `calc()` values are rejected outright: a calc expression is a tree with no parser to rebuild it, and
  encoding its numeric fallback would ship a different layout than the author wrote.

## 4. Content addressing — `ContentHash`

`serialization/ContentHash.java`

```java
String hash = ContentHash.of(ops, encodedDescription);   // lowercase hex SHA-256
byte[] canon = ContentHash.canonicalBytes(ops, value);   // for tests / equality
```

The canonical form writes a type tag and an element count before each container, so two structurally
different trees cannot collide by accident. Map keys are sorted so hashing does not depend on
insertion order even though encoding preserves it.

This is what makes re-opening cheap: `OpenWindow` carries the hash and the element count, not the
description. A client that already holds that hash rebuilds immediately with **zero** transfer,
however large the tree.

## 5. Network ids — allocated once, then owned

`ui/dom/UINodeTreeSource.java`

```java
int nid = ids.idOf(element);       // allocated on first sight, kept for the life of the source
UIElement el = ids.byId(nid);      // a map lookup
```

An id lives in a table the tree source owns, keyed by element identity. **It survives a sibling
insert, a reparent and a detach** — which is the whole point, because a message in flight names an
element and a name that moves is not a name.

Ids are still *derived* for the opening description and *stated* from then on, and the two cases
answer different questions:

| | Ids on the wire | Why |
|---|---|---|
| **Pristine description** (`open()`) | none — both sides run the same document-order walk | Nothing sent is what makes a description **content-addressed**: two windows showing the same thing hash the same, so re-opening costs one small packet however large the tree |
| **Live description** (a late viewer joining a reshaped window) | each element carries `nid` | After the first structural change a walk no longer reproduces the numbering the existing viewers hold, so a newcomer has to be told it — otherwise every id it derived would name a different element |

`UIElementMirror.describeLive`/`decodeLive` are the second form. A live description hashes to
something no pristine one matches, which is correct rather than unfortunate: a reshaped window was
never going to share another window's cache entry.

> **This section used to describe the opposite**, and the entry that replaced it is worth keeping:
> ids came from a walk on both sides, nothing was sent, and *"inserting an element renumbers
> everything after it"* was recorded as an accepted trade-off. It was the defect the whole rewrite
> came out of — a positional id is not an identity, and a structural delta cannot be written on top of
> one, because you cannot say "this one moved" without a name for "this one".

## 6. Stylesheets over the wire — `SheetRef`

`net/SheetRef.java`

```java
record SheetRef(String hash, @Nullable String id)
```

One shape covering four cases, which is why the id is optional rather than the identity:

| Case | Behaviour |
|---|---|
| Shipped theme, client has it | client resolves `id` through its own resource manager — **a resource pack still overrides it**, and nothing transfers |
| Version skew | hashes disagree → client fetches the server's copy rather than silently rendering a different theme |
| Server-only theme (datapack) | same fetch path; a bare id could not serve this at all, since datapacks never reach a client's resource manager |
| Generated sheet (`id == null`) | straight to fetch, hash as sole identity — two identical generated sheets transfer once |

A session also carries `useUserAgentSheet`, so the client knows whether to apply `StyleSheet.DEFAULT`
underneath.

## 7. The protocol, the transport, and sessions

`net/protocol/` (`Envelope`, `EnvelopeCodec`, `MessageRouter`, `Call`, `UiMethods`, `ProtocolErrors`),
`net/wire/` (`FrameCodec`, `FrameMultiplexer`, `WireTransport`, `CgNetworkChannel`),
`net/UITransport.java`, `ServerUiSession.java`, `ClientUiSession.java`

### A closed envelope over an open vocabulary

There is **no packet union**. `UIPacket`'s nine record types, `UIPacketCodec`'s two switches and
`RpcRegistry`'s parallel id space are gone; every message on the wire is one of **four** kinds, and what
it *means* is a string:

| Kind | Wire tag | Carries | Must be answered? |
|---|---|---|---|
| `Request<T>` | `q` | id, method, payload | **yes**, exactly once |
| `Response<T>` | `r` | id, ok, payload \| error | — it *is* the answer |
| `Notification<T>` | `n` | method, payload | **no** — and must not be |
| `Cancel` | `x` | id | no |

This is JSON-RPC's request/notification split and VS Code's `vs/base/parts/ipc` channel shape. The
value of it is that the two axes move independently: **adding a message is adding a string**, touching
one class, where it used to mean a record in the union, an arm in each of two codec switches, and an
`instanceof` branch in whichever session handled it.

`EnvelopeCodec.VERSION = 1`, carried in the `ui/openWindow` payload and checked on open.

Methods are namespaced with a slash, after LSP's `textDocument/hover` — `ui/*` here, `fs/*` for the
file protocol, `script/*` for a runtime in `language/` that `core` never learns about. `UiMethods`
lists the `ui/*` names as **a convenience, not a registry**: nothing enumerates them and nothing
validates against them.

> **`fs/*` is the one that is not like this.** Its names are in `FsMethods` and its payloads are
> records with codecs in `FsMessages`, so a field written on one side is provably the field read on the
> other — which is the difference between a protocol two ends implement and one two ends *agree* on.
> The `ui/*` side is deliberately looser because a widget tree's content is not a fixed vocabulary;
> a filesystem's twenty verbs are. See `com.crystalgui.fs.protocol`. A peer may send any string, and an unknown one is answered with
`ProtocolErrors.METHOD_NOT_FOUND` rather than dropped. The moment that file becomes the list of legal
methods it is `UIPacket` again with different syntax.

| Method | Kind | Direction |
|---|---|---|
| `ui/openWindow` | notification | S→C |
| `ui/description` | **request** | C→S, answered with the tree |
| `ui/stateDelta` | notification | S→C |
| `ui/closeWindow` | notification | S→C |
| `ui/event` | notification | C→S |
| anything from `onCall` | **request** | either |

> `ui/description` being a *request* is the one shape change worth noticing. `RequestDescription` and
> `Description` were two packet types spelling one ask-and-answer with the correlation left implicit —
> nothing tied a body to the request that wanted it. As a request it correlates by id for free, a client
> that asks twice cannot confuse the answers, and a server that no longer serves that window **refuses**
> instead of staying silent, so the client learns rather than waiting out a timeout.

> **A `ui/stateDelta` never comes back as a `ui/event`.** Applying a delta runs the widget's ordinary
> setter, which fires the widget's ordinary change signal — which is exactly what the client hung the
> event report on. So the server moving a slider used to make every client that received it report that
> the *user* had moved it: one `ui/event` per viewer, for a gesture nobody made. Harmless in the common
> case and only there — the echo carries the value the server just sent, so the handler writes the model
> back to what it already holds and `Property.set` returns early — and wrong the moment a handler counts
> anything or records who did it, which with two viewers attributes it to the wrong player.
> `ClientUiSession` suppresses reporting for the duration of a delta. `shouldSuppress`, which stops a
> delta landing on a focused text field and resetting the caret mid-word, is the narrow ancestor of the
> same loop and stays: it stops the *value* arriving, not the *report* leaving.

**Every `ui/*` payload carries `w`, the window id** — in the payload rather than the envelope, because it
is a fact about the UI protocol and the envelope is not allowed to know one. LDLib2 resolves incoming
packets against "whatever menu the player has open", so a packet in flight when a GUI closes lands on
the *next* one. Four bytes makes that impossible.

`MessageRouter<T>` owns correlation, the pending map, exactly-once responding (`OnceResponder`),
per-request deadlines, cancellation and `failAllPending` on a dropped link — for every method, rather
than for RPC alone as `RpcRegistry` did.

### Getting bytes across

`UITransport<T>` is unchanged: `send(T)` and `setReceiver(Consumer<T>)`, taking `T` rather than an
`Envelope` so every implementation exercises the real codec on every hop. `InMemoryTransport.pair()`
gives two ends wired to each other, with `deliver()`, `dropNext(n)` and `corruptNext(mutator)`.

`WireTransport` is the real one, and it is a stack:

```
session  →  Envelope  →  PlainOps tree  →  BinaryFormat bytes  →  FrameMultiplexer  →  CgNetworkChannel
```

`FrameMultiplexer` is HTTP/2's shape at Minecraft scale: many logical streams over one channel,
`[u8 opcode][u8 flags][varint streamId][payload]` frames, FIN-terminated fragmentation (no chunk index —
TCP already orders), `WINDOW_UPDATE` credit flow control, and `RESET` per stream. Credit is not
optional here: `NetworkManager.outboundPacketsQueue` is an **unbounded** `ConcurrentLinkedQueue` shared
with the whole game, so an unthrottled sender degrades the session it is not part of.

`onFrameReceived` is one add to a `ConcurrentLinkedQueue`; everything real happens in `pump()` on the
thread that owns the tree. Round-robin across streams means arrival order is guaranteed **within** a
stream and deliberately not **across** them — a small message overtakes a large one already in flight,
which is the point.

### Cross-version

`CgNetworkChannel` is four methods — send to server, send to a player, install an inbound sink, report a
frame ceiling — plus `isAvailable()`. **The frame ceiling is the only version-varying quantity that
reaches `core`**, which is what makes cross-version support testable rather than hopeful:
`FrameMultiplexerTest.everyPlatformCeilingCarriesTheSameMessagesIntact` runs the engine at 32,766
(1.7.10 C→S), 32,767 (1.20.x C→S), 1,048,576 (1.20.x S→C), 2,097,050 (1.7.10 S→C) and at 128 B and 4 MB,
which no platform imposes. Channel names, payload types, player handles and the delivery thread are all
private to an adapter. The full table, and the packaging step a new loader must not skip, are in
`CgNetworkChannel`'s own javadoc.

### The sessions

**`ServerUiSession<N, T>`** implements `TreeObserver<N>`, so it is told when elements are inserted,
removed, moved or go state-dirty, and coalesces those into one `ui/treeOps` and one `ui/stateDelta`
per `tick()`. It is generic in the node type: the mirror is authored once and a second engine supplies
a `TreeSource` and a `NodeMirror`.

```java
var session = new ServerUiSession<>(windowId, new UIElementTreeSource(root),
                new UIElementMirror<>(connection.ops()), connection)
        .addSheet(SheetRef.ofResource("crystalgui:ore", oreHash))
        .setUseUserAgentSheet(true);

session.on(myButton, Button.ACTIVATE, ctx -> …);
session.on(mySlider, Slider.VALUE_CHANGED, (ctx, value) -> model.set(value));
session.onCall("save", (args, responder) -> responder.ok(result));

session.open();
session.tick();     // every tick
```

**An event kind is a string an `Event` declares**, unique only within its own widget's contract, so a
third party mints one without editing anything of ours. `Button.ACTIVATE` and `Slider.VALUE_CHANGED`
are typed constants over those strings — the typed overload hands the handler a decoded payload rather
than a raw map.

> There is deliberately **no kind vocabulary class.** `UiEventKinds` was a closed set of four strings
> and it went at M3: two vocabularies for one thing is drift, and a closed set is a list a third party
> cannot add to.

**`ClientUiSession<N, T>`** is the mirror: a description cache (`hasCached(hash)`, `cacheSize()`),
rebuilds the tree on open, exposes `root()`/`type()`/`title()`/`key()`/`presentation()`/`sheets()`, and
the same symmetric `onCall`/`call` surface.

Most hosts never touch either. `ServerWindows`/`ClientWindows` own a window's whole lifetime and a
panel is handed a `ServerScope`/`ClientScope` — see `docs/CGUI_BUILDING_UIS.md`.

## 7b. Collections — `ui/rows`

A panel's widgets are described in full, which is right for a dozen controls and wrong for a
collection. So a collection is a **window**, not a list:

```java
io.stream(container, source, row -> new SlotRow(), SlotRow::show);
```

The server holds all of it and describes only the rows a viewer can see. `ui/rows` is a **request**
carrying `{nid, from, to}` and answering with the **count** — the viewer needs a scrollbar before it
needs rows — and the rows themselves arrive as ordinary described children, since a row may hold a
real `Button` that reports like any other.

Three properties make it safe, and each fails separately:

- **Rows are keyed, never positional.** A row whose key has not changed keeps its element, so an
  insert above the window is an insert rather than a rebuild of everything below it.
- **Every viewer sees the union.** Rows are structure and structure goes to every viewer — a tree delta
  renumbers both ends, so withholding one from a viewer scrolled elsewhere would leave it addressing
  elements by numbers the server has moved on from. Two viewers at the same place cost one window
  between them; two scrolled apart cost the **span**, which is what a contiguous child list can express.
- **A window at the end follows.** Appended rows are described without the viewer asking, which is what
  a log wants; a viewer reading the middle is left where they are.

## 7c. Where a window appears — `Presentation`

`ui/openWindow` carries `WINDOW`, `EDITOR_TAB` or `TOOL_WINDOW(region)`. Only the server knows what a
panel is *for*, and a client cannot tell a machine's controls from a live log by reading the tree.

It is a **hint**: a host with no workbench opens a window regardless, and an unrecognised placement
parses as `WINDOW` rather than failing — refusing to parse a placement is refusing the window. A client
may not name one; it is declared beside the resolver (`openable(type, resolver, presentation)`),
because where a panel belongs is the mod's statement about its own UI.

## 7d. Children the viewer added — `addLocal`

`ClientScope.addLocal(parent, child)` marks a node local: an ordinary child in every way that shows,
and invisible in every way that travels — never described, never numbered, never counted by the
integrity check. `insertAt` keeps locals as the **tail** of the light list and refuses to put a
described child past them, so index N means the same thing on both sides by construction.

Appending one by hand instead puts it in the described child list, and the server's next insert lands
one index off — silently, because an index is an int and every one of them still resolves to something.

## 8. Known gaps — stated honestly

- **No slots/inventory.** The Minecraft-specific half of a container GUI does not exist.
- **A table's columns and a tree's expansion do not travel.** The rows of both do — a served collection
  is a stream on a container (§7b) — but a `TableView`'s column set and a `TreeView`'s expanded nodes
  have no wire form. The expansion is the interesting one: it is view state for a local tree and would
  be the server's for a served one, and inventing a form for it before something needs it is how two
  mechanisms for one rule start disagreeing.
- **No text filter on a row source.** A `SORT` is one event the source answers; a filter is a search
  feature and waits for one.
- **`TextEditor` and `GraphView` are not on the wire.** A document is the filesystem's business
  (`plan/fs-rewrite.md`), and shipping one as a described tree would be a second format for it.

Four entries that used to stand here are gone, and what replaced each is worth knowing:

| Was | Now |
|---|---|
| *No `TreeDelta` — a structural change means a new description and a re-open* | `ui/treeOps` carries `insert`/`remove`/`move`. The entry named positional ids as what made the incremental version *"a real design problem, not an afternoon"*, and that was exactly right: the ids had to stop being positional first (§5) |
| *No multi-viewer fan-out — one session, one client* | Many viewers per window, each with its own visibility gate. `MultiViewerTest` |
| *Only seven widgets implement `writeState`/`readState`* | 28 widgets carry a `WidgetContract` and the engine derives the encoding (§2). The rest are on a census with a written reason each |
| *A collection widget sends no rows* | It still sends none, and that is now the right answer rather than a gap: a served collection is a `stream` on a **container** and the rows arrive as its described children. The `ListView` a client builds over a `RemoteRows` around them is the client's own view of them, which is why it is local-only for the same reason `Configurator` is |
| *`TabView`'s tabs and panes do not round-trip* | They do. `describedChildren`/`adoptDescribedChild` is the pair, and a described tab is **placed** — its button in the rail, its content in the panes — rather than appended |
| *Rate policy is declared and not yet applied* | Applied. A widget declares its own tempo because the right answer is a property of the interaction, and `commitOnRelease` is what makes throttling safe: dropping intermediate values is fine and dropping the last one is data loss. `RatePolicyTest`, `RateGateTest` |

## 9. The headless contract

`core/src/headlessTest/`, wired as its own Gradle source set in `core/build.gradle.kts` and attached
to `check`.

**CrystalGraphics is deliberately absent from this source set's classpath**, because it is absent on a
dedicated server — `compileOnly` in `core`, never shipped. If a class can be loaded here, it can run
on a server.

The trap, found the hard way: **`StyleSheet` is unloadable headlessly.** Its `DEFAULT` field is
`static final` and reads `default.css` through `CgIO` at class-init, so even `StyleSheet.parse()`
throws `NoClassDefFoundError` here. Anything needing CSS *text* belongs in `core/src/test/`, not
`headlessTest`. `HeadlessClasspathSanityTest` exists to make that boundary fail loudly rather than
drift.

```bash
./gradlew :core:headlessTest
```

| Test | Guards |
|---|---|
| `HeadlessClasspathSanityTest` | CrystalGraphics really is off the classpath |
| `HeadlessTreeSmokeTest` | build/attach/lay out a tree with no GL |
| `UIElementTreeTest`, `UIElementTreeSourceContractTest` | the node tree and the `ui.dom` seam, on their own terms — no session involved |
| `MirrorOverUIElementTreeTest` | **M2's acceptance** — a sibling insert keeps every other instance, a move keeps one, identity and inline style travel, an idle window is silent |
| `MirrorIsEngineAgnosticTest` | the mirror driven over a twelve-line node class that has never heard of a widget |
| `TreeOpsTest` | the `insert`/`remove`/`move` wire vocabulary |
| `WidgetContractRoundTripTest` | the named widgets, including the four whose slot ORDER is load-bearing |
| **`ClientSmokeTest`** | **every contracted widget, over a loopback wire, at once** — one of each in one tree, every slot set to a distinct value and read back off the client's own instance. A walk over the registry rather than a list, because the failure it exists for is a widget nobody remembered |
| `MultiViewerTest` | two viewers agree, a hidden one is not sent to, and one coming back is brought up to date |
| `RowStreamTest` | **7.0** — ten thousand rows cost a window, the union of two viewers, and a followed tail |
| `PresentationTest` | **7.1** — a placement survives the wire, a host with no workbench opens anyway, and a panel reads files through its scope |
| `LocalChildTest` | **7.2** — a viewer's own control: undescribed, uncounted, and never shifting an index |
| `ContentHashTest` | canonical form and collision resistance |
| `SessionHandshakeTest` | open → req-desc → desc, and the cache-hit path that transfers nothing |
| `EventValidationTest`, `RatePolicyTest`, `RateGateTest` | what a legal gesture could have produced is sanitized; what it could not is refused; and a throttle never drops the last value |
| `WindowLifecycleTest`, `CloseVetoTest`, `TwoWindowsOnOneConnectionTest` | the window layer's close matrix, its veto and its multiplexing |
| `MachineExampleTest` | the worked example end to end, on a classpath with no fonts |
| `TextStylePropertiesTest`, `TransformStylePropertiesTest` | those style properties at value level (the CSS half lives in `test/`) |
