# CrystalGUI on a Server — Serialization, Sessions, RPC

> Reference for `serialization/`, `ui/contract/` and `net/`: the sessions at its root, `mirror` (the edit script,
> generic in the node type), `protocol` (`UiMethods`) and `window` (a window's lifetime and what a panel is handed).
> The codecs, envelopes, routing, connections and byte transport are CrystalGraphics' (`com.crystalgraphics.serialization`,
> `com.crystalgraphics.net`).
>
> Companions: `CGUI_BUILDING_UIS.md` (start there to **use** this), `CGUI_NETWORKING_PRIMER.md` (the same ground
> bottom-up), `CGUI_WIDGETS.md`, `CGUI_STYLE_RENDER_PIPELINE.md`.

## 0. The problem this solves

A dedicated server has no GL context, no fonts and no resource manager, yet decides what a GUI *is*: it builds the
tree, holds live state and reacts to input, while the client draws.

```
SERVER                                          CLIENT
build a UINode tree  ──── description ────►  rebuild the same tree
hold session state      ◄──── events ─────────  user clicks, types, drags
call client methods     ◄───── RPC ──────────►  call server methods
```

1. **The tree is describable without being renderable**: nothing on a server path touches `CgIO`, fonts or GL.
2. **Descriptions repeat**, so the open packet carries a hash and the bytes move only if the client lacks them.

## 1. Codecs

CrystalGraphics' `serialization/`: `CgCodec`, `CgDynamicOps`, `CgCodecs`, `CgPlainOps`; JSON is
`serialization/format/CgJsonOps`. DFU-shaped, with no dependency on DFU:

```java
public interface CgCodec<A> {
    <T> T encode(CgDynamicOps<T> ops, A input);
    <T> A decode(CgDynamicOps<T> ops, T input);
}
```

`CgDynamicOps<T>` is a tree interface (`createMap`, `createList`, `getMap`, …); every accessor throws
`CgCodecException` on a type mismatch, so a malformed packet fails at the wrong field.

| Ops | Backing | Used for |
|---|---|---|
| `CgJsonOps` | Gson `JsonElement` | debugging, tests, files (`CgFiles.JSON`) |
| `CgPlainOps` | plain `Map`/`List`/`String`/`Number`/`Boolean` | the default: no Gson on the server path |

`CgCodecs` holds `STRING`/`INT`/`FLOAT`/`DOUBLE`/`LONG`/`BOOL`, `xmap`, `enumOf` (**by constant name, never
ordinal**), and the record-shaped pair:

```java
CgCodecs.map(ops).field("t", Codecs.STRING, tag)
               .field("v", Codecs.FLOAT, value)
               .build();

var in = CgCodecs.read(ops, input);
String tag = in.field("t", Codecs.STRING);
float v    = in.optional("v", Codecs.FLOAT, 0f);
```

**Everything is `LinkedHashMap`-ordered**, so one tree encodes to the same bytes twice (§4).

## 2. Widget state — `CgStateMap` and the contract that fills it

`serialization/CgStateMap.java`, `ui/contract/`. A widget declares what it carries; the engine derives the encoding:

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

- **Declaration order is apply order**: a slider's range before its value, a dropdown's options before its index, a
  text field's mode before its text, a colour selector's mode, original, then colour.
- A slot at its default writes nothing, and an empty map is dropped. Every read has a fallback, so a slot added later
  decodes from an older sender with no version bump.
- **A slot that is settable and never written** is the defect to watch for: `ClientSmokeTest` round-trips every
  contracted widget over a loopback wire.
- 28 widgets carry a contract; the rest are on `WidgetCensus` with a reason each, and a coverage test fails on a class
  that is neither.

## 3. Descriptions — `UIElementMirror`

`net/mirror/UIElementMirror.java`, `ui/dom/UIElementRegistry.java`. Encodes a subtree: tag, id, classes, flags,
inline styles, per-widget state, children. The tag comes from `UIElementRegistry` (a `Name` to a factory and a
contract; `bootstrap()` is idempotent and driven by the `NodeKinds` services). A widget with no registered kind
cannot be described.

**What counts as a child is `describedChildren()`**: a widget whose own parts are light children overrides it, or the
far side builds its parts twice (`Tab`, `TabView`, `Dialog`, `SplitView`, `ConfigControl`, `ColorSelector` do).
Encoding is sparse: flags pack into one int (bit 0 enabled, bit 1 hit-testable) and defaults are omitted.

**Only `INLINE`-origin style values travel** (`serialization/style/`). `StyleValueCodecs` keys codecs by value type;
`InlineStyleCodec` walks an element's inline slots.

- A declaration switched off inline travels as its comment (`"color": "/* #FFF8CB */"`); `ElementStyle.inlineText`
  keeps the written text beside the live value.
- A widget's `DEFAULT`-origin styling never travels: the client's widget makes it. Sheets travel (§6), not their
  computed results.
- `forProperty` returning `null` is an **error naming the property**, never "skip it".
- **Engine classes (`__like_this__`) are not written to a document**: `Keys.DOCUMENT` encodes authored classes only
  (`ClassNames.isEngine`). The wire dialect carries every class.
- `calc()` values are rejected: there is no parser to rebuild one.

## 4. Content addressing — `CgContentHash`

```java
String hash = CgContentHash.of(ops, encodedDescription);   // lowercase hex SHA-256
byte[] canon = CgContentHash.canonicalBytes(ops, value);   // for tests / equality
```

The canonical form tags each container with its type and count, and sorts map keys. `ui/openWindow` carries the hash
and element count; a client holding that hash rebuilds with zero transfer.

## 5. Network ids — allocated once, then owned

`ui/dom/UIElementTreeSource.java`

```java
int nid = ids.idOf(element);       // allocated on first sight, kept for the life of the source
UIElement el = ids.byId(nid);      // a map lookup
```

An id survives a sibling insert, a reparent and a detach: a message in flight names an element.

| | Ids on the wire | Why |
|---|---|---|
| **Pristine description** (`open()`) | none: both sides run the same document-order walk | sending nothing keeps the description content-addressed |
| **Live description** (a late viewer of a reshaped window) | each element carries `nid` | after a structural change the walk no longer reproduces the numbering (`describeLive`/`decodeLive`) |

## 6. Stylesheets over the wire — `SheetRef`

```java
record SheetRef(String hash, @Nullable String id)
```

| Case | Behaviour |
|---|---|
| Shipped theme, client has it | resolved by `id` through the client's resource manager (a resource pack still overrides it); nothing transfers |
| Version skew | hashes disagree → the client fetches the server's copy |
| Server-only theme (datapack) | the same fetch |
| Generated sheet (`id == null`) | fetched by hash alone |

A session also carries `useUserAgentSheet`: whether to apply `StyleSheet.DEFAULT` underneath.

## 7. The protocol, the transport, and sessions

CrystalGraphics' `net/protocol/` (`CgEnvelope`, `CgEnvelopeCodec`, `CgMessageRouter`, `CgCall`, `CgProtocolErrors`)
and `net/wire/` (`CgFrameCodec`, `CgFrameMultiplexer`, `CgWireTransport`, `CgNetworkChannel`); CrystalGUI's
`UiMethods`, `ServerUiSession`, `ClientUiSession`.

### A closed envelope over an open vocabulary

Every message is one of four kinds, and what it means is a string (JSON-RPC's split, VS Code's IPC channels):

| Kind | Wire tag | Carries | Must be answered? |
|---|---|---|---|
| `Request<T>` | `q` | id, method, payload | **yes**, exactly once |
| `Response<T>` | `r` | id, ok, payload \| error | it is the answer |
| `Notification<T>` | `n` | method, payload | **no** |
| `Cancel` | `x` | id | no |

- `CgEnvelopeCodec.VERSION = 1`, carried and checked in `ui/openWindow`.
- Methods are namespaced (`ui/*`, `fs/*`, `script/*`). `UiMethods` lists the `ui/*` names as a convenience, not a
  registry; an unknown method is answered with `CgProtocolErrors.METHOD_NOT_FOUND`.
- `fs/*` is the exception: its names are in `FsMethods` and its payloads are records with codecs in `FsMessages`
  (`com.crystalgui.fs.protocol`), since a filesystem's verbs are a fixed vocabulary.

| Method | Kind | Direction |
|---|---|---|
| `ui/openWindow` | notification | S→C |
| `ui/description` | **request** | C→S, answered with the tree, or refused when the window is gone |
| `ui/stateDelta` | notification | S→C |
| `ui/closeWindow` | notification | S→C |
| `ui/event` | notification | C→S |
| anything from `onCall` | **request** | either |

- **A `ui/stateDelta` never comes back as a `ui/event`**: `ClientUiSession` suppresses reporting while it applies a
  delta. `shouldSuppress` separately keeps a delta off a focused text field.
- **Every `ui/*` payload carries `w`, the window id**, so a packet in flight when a window closes cannot land on the
  next one.
- `CgMessageRouter<T>` owns correlation, the pending map, exactly-once responding (`OnceResponder`), deadlines,
  cancellation and `failAllPending` on a dropped link.

### Getting bytes across

`CgTransport<T>`: `send(T)` and `setReceiver(Consumer<T>)`, so every implementation runs the real codec.
`CgInMemoryTransport.pair()` gives two wired ends with `deliver()`, `dropNext(n)` and `corruptNext(mutator)`.

`CgWireTransport` is a stack:

```
session  →  Envelope  →  CgPlainOps tree  →  CgBinaryFormat bytes  →  CgFrameMultiplexer  →  CgNetworkChannel
```

`CgFrameMultiplexer` is HTTP/2's shape: many streams over one channel, `[u8 opcode][u8 flags][varint streamId][payload]`
frames, FIN-terminated fragmentation, `WINDOW_UPDATE` credit and per-stream `RESET`. Credit is required: Minecraft's
outbound queue is unbounded and shared with the game. `onFrameReceived` only enqueues; `pump()` runs on the tree's
thread, round-robin across streams, so order holds within a stream and not across them.

### Cross-version

`CgNetworkChannel` is send to server, send to a player, install an inbound sink, a frame ceiling, and `isAvailable()`.
**The frame ceiling is the only version-varying quantity that reaches `core`**:
`FrameMultiplexerTest.everyPlatformCeilingCarriesTheSameMessagesIntact` runs it at 32,766, 32,767, 1,048,576 and
2,097,050 bytes, and at 128 B and 4 MB. The full table and the packaging step a new loader needs are in
`CgNetworkChannel`'s javadoc.

### The sessions

**`ServerUiSession<N, T>`** implements `TreeObserver<N>` and coalesces inserts, removals, moves and state into one
`ui/treeOps` and one `ui/stateDelta` per `tick()`. A second engine supplies a `TreeSource` and a `NodeMirror`.

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

An event kind is a string an `Event` declares, unique within its widget's contract, so a third party mints one
freely; `Button.ACTIVATE` and `Slider.VALUE_CHANGED` are typed constants over them.

**`ClientUiSession<N, T>`** mirrors it: a description cache (`hasCached(hash)`, `cacheSize()`), the rebuilt tree
(`root()`, `type()`, `title()`, `key()`, `presentation()`, `sheets()`), and the same `onCall`/`call` surface.

Most hosts use neither directly: `ServerWindows`/`ClientWindows` own a window's lifetime and hand a panel a
`ServerScope`/`ClientScope` (`CGUI_BUILDING_UIS.md`).

## 7b. Collections — `ui/rows`

A collection is a **window**, not a list:

```java
io.stream(container, source, row -> new SlotRow(), SlotRow::show);
```

The server holds every row and describes only those a viewer can see. `ui/rows` is a request carrying
`{nid, from, to}` and answered with the **count**; the rows arrive as ordinary described children.

- **Rows are keyed**, so an insert above the window is an insert, not a rebuild.
- **Every viewer sees the union**: rows are structure, which goes to every viewer.
- **A window at the end follows** appended rows; one in the middle stays put.

## 7c. Where a window appears — `Presentation`

`ui/openWindow` carries `WINDOW`, `EDITOR_TAB`, `TOOL_WINDOW(region)` or `SCREEN`, declared beside the resolver
(`openable(type, resolver, presentation)`), never by the client. It is a hint: a host with no workbench opens a window,
and an unrecognised placement parses as `WINDOW`.

`SCREEN` fills the game window over the world with no desktop, closed by Escape; the tree stays in the connection's
document and shows through a portal (`DesktopWindowMount.Screens`, given by `CgUiScreen`). The player closing it
reaches the server as `userClosed`. One screen at a time: a second replaces the first. Without a host that has
screens, it opens as a window.

## 7d. Children the viewer added — `addLocal`

`ClientScope.addLocal(parent, child)` marks a node local: shown like any child, never described, numbered or counted.
`insertAt` keeps locals as the tail of the light list, so index N means the same on both sides. A child appended by
hand instead shifts every server insert by one.

## 8. Known gaps

- **No slots/inventory**: the Minecraft half of a container GUI does not exist.
- **A table's columns and a tree's expansion do not travel**; their rows do (§7b).
- **No text filter on a row source**; `SORT` is one event the source answers.
- **`TextEditor` and `GraphView` are not on the wire**: a document is the filesystem's business.

## 9. The headless contract

`core/src/headlessTest/`, its own source set in `core/build.gradle.kts`, attached to `check`. Its classpath is a
dedicated server's: CrystalGraphics `core` and `platform`, no GL context or fonts. If a class runs here, it runs on a
server.

```bash
./gradlew :core:headlessTest
```

| Test | Guards |
|---|---|
| `HeadlessClasspathSanityTest` | what must be on the classpath, `StyleSheet` included |
| `HeadlessTreeSmokeTest` | build, attach and lay out a tree with no GL |
| `UIElementTreeTest`, `UIElementTreeSourceContractTest` | the node tree and the `ui.dom` seam |
| `MirrorOverUIElementTreeTest` | a sibling insert keeps every other instance, a move keeps one, identity and inline style travel, an idle window is silent |
| `MirrorIsEngineAgnosticTest` | the mirror over a twelve-line node class that knows no widget |
| `TreeOpsTest` | the `insert`/`remove`/`move` vocabulary |
| `WidgetContractRoundTripTest` | the named widgets, including the four whose slot order matters |
| **`ClientSmokeTest`** | every contracted widget over a loopback wire, every slot set and read back on the client |
| `MultiViewerTest` | two viewers agree, a hidden one is not sent to, a returning one is brought up to date |
| `RowStreamTest` | ten thousand rows cost a window, the union of two viewers, a followed tail |
| `PresentationTest` | a placement survives the wire; a host with no workbench opens anyway |
| `LocalChildTest` | a viewer's own control: undescribed, uncounted, never shifting an index |
| `ContentHashTest` | canonical form and collision resistance |
| `SessionHandshakeTest` | open → describe, and the cache hit that transfers nothing |
| `EventValidationTest`, `RatePolicyTest`, `RateGateTest` | sanitize what a gesture could make, refuse what it could not, never drop the last value |
| `WindowLifecycleTest`, `CloseVetoTest`, `TwoWindowsOnOneConnectionTest` | the close matrix, its veto, multiplexing |
| `MachineExampleTest` | the worked example end to end, with no fonts |
| `TextStylePropertiesTest`, `TransformStylePropertiesTest` | those properties at value level |
