# CrystalGUI on a Server — Serialization, Sessions, RPC

> **Current-state reference** for `core/src/main/java/com/crystalgui/serialization/` (11 classes) and
> `core/src/main/java/com/crystalgui/net/` (10 classes), plus the `core/src/headlessTest/` source set
> that guards them.
>
> Companions: `CGUI_WIDGETS.md` (what the widgets are) and `CGUI_STYLE_RENDER_PIPELINE.md` (what the
> styles mean).
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
build a UIElement tree  ──── description ────►  rebuild the same tree
hold session state      ◄──── events ─────────  user clicks, types, drags
call client methods     ◄───── RPC ──────────►  call server methods
```

Two consequences shape everything below:

1. **The tree must be describable without being renderable.** Hence codecs over `UIElement`, and hence
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

## 2. Widget state — `StateMap`

`serialization/StateMap.java`

A small typed key/value bag over any `DynamicOps`. Widgets read and write it through two protected
hooks on `UIElement`:

```java
@Override protected <T> void writeState(StateMap<T> out) {
    out.putStringIfNot("text", text, "");        // omitted when it equals the default
    out.putEnum("mode", mode);
}
@Override protected <T> void readState(StateMap<T> in) {
    setText(in.getString("text", ""));
    setMode(in.getEnum("mode", Mode.class, Mode.STRING));
}
```

`putXIfNot(key, value, omitWhen)` is the workhorse: a default-valued widget writes an **empty** state
map, which then gets dropped entirely. Every getter takes a fallback, so a field added later decodes
against an older sender without a version bump.

Seven widgets implement these — `Button`, `Checkbox`, `Switch`, `Slider`, `TextField`, `UIText`,
`Tab` — plus `UIElement` itself for the generic parts.

## 3. Descriptions — `UIDescriptionCodec`

`serialization/UIDescriptionCodec.java`, `ui/ElementRegistry.java`

Encodes a whole `UIElement` subtree: tag, id, classes, flags, inline styles, per-widget state,
children. The tag comes from **`ElementRegistry`**, which maps `"button" ↔ Button.class` plus a
no-arg factory in both directions (`tagOf`, `tags()`, `bootstrapBuiltins()` — idempotent and
auto-called by every lookup). A widget with no registered tag cannot be serialized at all.

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

## 5. Network ids — derived, not transmitted

`net/NetworkIds.java`

```java
int count = NetworkIds.assign(root);        // document-order walk, stamps element.networkId
UIElement el = NetworkIds.find(root, nid);
```

Ids come from a deterministic document-order walk on **both** sides. Nothing is sent. Both sides
rebuilt the same tree from the same description, so both walks produce the same numbering.

The trade-off is explicit: this is why there is no structural delta yet (§8). Inserting an element
renumbers everything after it.

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

Methods are namespaced with a slash, after LSP's `textDocument/hover` — `ui/*` here, `workspace/*` for
the file protocol, `script/*` for a runtime in `language/` that `core` never learns about. `UiMethods`
lists the `ui/*` names as **a convenience, not a registry**: nothing enumerates them and nothing
validates against them. A peer may send any string, and an unknown one is answered with
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

**`ServerUiSession<T>`** implements `UITreeObserver`, so it is told when elements attach, detach or go
state-dirty, and coalesces those into one `ui/stateDelta` per `tick()`.

```java
var session = new ServerUiSession<>(windowId, root, transport, PlainOps.INSTANCE)
        .addSheet(SheetRef.ofResource("crystalgui:ore", oreHash))
        .setUseUserAgentSheet(true);

session.onActivate(myButton, ctx -> ctx.session().call("client:toast", null, …));
session.on(mySlider, UiEventKinds.VALUE, ctx -> model.set(ctx.payload().getFloat("v", 0)));
session.onCall("server:save", (args, responder) -> responder.ok(result));

session.open();
// every tick:
session.tick();
```

Event kinds are the small closed set in `UiEventKinds`: `activate`, `toggle`, `value`, `text`. The
handler receives a `UiEventContext<T>` of `(session, element, payload)`.

**`ClientUiSession<T>`** is the mirror: description cache (`hasCached(hash)`, `cacheSize()`), rebuilds
the tree on open, exposes `root()`/`sheets()`/`useUserAgentSheet()`, and the same symmetric
`onCall`/`call` surface.

> **`onCall`'s signature did not change.** `Call.Handler<T>`/`Call.Responder<T>` are `RpcRegistry`'s two
> interfaces under a new roof — they were what a *caller* writes against, and the point of the rewrite
> was that callers do not move. An RPC is now an ordinary request, so its correlation and timeout are the
> router's; the per-session patience is a `callTimeoutMillis` field, defaulting to the 10s
> `RpcRegistry` used.

## 8. Known gaps — stated honestly

- **No `TreeDelta`.** A structural add/remove means a *new description* and a re-open, which the
  content-addressed cache makes cheap but which is not a delta. Positional network ids (§5) are what
  make an incremental version a real design problem, not an afternoon.
- **No slots/inventory.** The Minecraft-specific half of a container GUI does not exist.
- **No multi-viewer fan-out.** One session, one client.
- **`TabView`'s tabs and panes do not round-trip** — they live in internal containers, which the
  description codec does not descend into.
- **Only seven widgets implement `writeState`/`readState`**; the rest carry no state worth sending
  today, but a new stateful widget must add them or it will silently arrive blank.

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
| `UIDescriptionCodecTest` | tree round-trip, both ops |
| `WidgetStateRoundTripTest` | per-widget `writeState`/`readState` |
| `UITreeObserverTest` | attach/detach/state-dirty notifications |
| `ContentHashTest` | canonical form and collision resistance |
| `SessionHandshakeTest` | open → req-desc → desc, and the cache-hit path that transfers nothing |
| `ServerBehaviourLoopTest` | event in, handler runs, state update out |
| `TextStylePropertiesTest` | the text style properties at value level (the CSS half lives in `test/`) |
| `TransformStylePropertiesTest` | `transform`/`transform-origin` at value level (the CSS half lives in `test/`) |
