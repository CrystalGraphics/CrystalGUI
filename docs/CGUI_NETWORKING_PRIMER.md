# CrystalGUI Networking — A Lecture

> **For** anyone about to touch `net/`, `fs/` or a loader's networking who wants the words before the code:
> intuitive first, precise second. The reference form is `CGUI_SERVER_AND_SERIALIZATION.md`; the worked example is
> `com.crystalgui.app.machine` (`./gradlew :core:runExample`).

## 0. The one idea

A dedicated server has no OpenGL, fonts or textures, but it is the only place that knows what is true. So:

- The **server** builds a tree of ordinary widgets and **describes** it.
- The **client** rebuilds the description into its own tree and **draws** it.
- From then on the two talk about elements **by number**, and neither sends a picture.

## 1. The five words, before anything else

| Word | ELI5 | In one line |
|---|---|---|
| **Frame** | one envelope that fits through the letterbox | a `byte[]` small enough for Minecraft to carry, about **32 KB** |
| **Message** | a whole letter, maybe several envelopes | one logical payload, split across frames and reassembled |
| **Envelope** *(the type)* | the four things a letter can be | ask / answer / tell / take it back, and nothing else |
| **Connection** | one open phone line to one person | `CgProtocolConnection`: a router, a transport, and who is at the far end |
| **Session** | one conversation on that line | one GUI's back-and-forth; several share a connection |
| **Peer** | the other end | on the server a player; on the client the server |

**Frame ≠ Message ≠ Envelope**: a frame is a transport chunk, a message one payload over many frames, a `CgEnvelope`
the grammar around a payload.

## 2. The layer cake

Bottom is bytes, top is widgets; each layer knows only the one below.

```
      ┌──────────────────────────────────────────────────────────────┐
  8   │  ServerWindows / ClientWindows    ServerWindow, WindowMount    │  a window's LIFETIME
      ├──────────────────────────────────────────────────────────────┤
  7   │  ServerUiSession / ClientUiSession   WorkspaceBinding / Workspace  │  what a message MEANS
      │  RemoteCommands                       (the "tenants")        │
      ├──────────────────────────────────────────────────────────────┤
  6   │  UiWindowMux                    (only the UI needs this)     │  which WINDOW
      ├──────────────────────────────────────────────────────────────┤
  5   │  CgProtocolConnection  ── one per peer ──  Protocols registry  │  who + wiring
      ├──────────────────────────────────────────────────────────────┤
  4   │  CgMessageRouter        method name → handler, ids, timeouts   │  which HANDLER
      ├──────────────────────────────────────────────────────────────┤
  3   │  Envelope + CgEnvelopeCodec       q / r / n / x                │  the GRAMMAR
      ├──────────────────────────────────────────────────────────────┤
  2   │  CgTransport   ├─ CgWireTransport ─ CgBinaryFormat (tree↔bytes)  │  encoding
      │                └─ CgInMemoryTransport  (tests: a queue)        │
      ├──────────────────────────────────────────────────────────────┤
  1   │  CgFrameMultiplexer   streams, fragmentation, flow control     │  chunking
      │  CgFrameCodec         [opcode][flags][streamId][payload]       │
      ├──────────────────────────────────────────────────────────────┤
  0   │  CgNetworkChannel   "carry this byte[]"   ← THE PLATFORM SEAM│  the road
      └──────────────────────────────────────────────────────────────┘
             ↑ a loader implements ONLY this line (≈4 methods)
```

Layers 0–5 and the codecs are CrystalGraphics' (`com.crystalgraphics.net`, `com.crystalgraphics.serialization`), so a
consumer with no UI networks too; 6–8 are CrystalGUI's. A new Minecraft version implements layer 0 only.

# PART ONE — THE BOTTOM: MOVING BYTES

## 3. `CgNetworkChannel` — the road

**ELI5:** the postal service: it carries a parcel and knows how big one may be, not what is inside.

```java
void sendToServer(byte[] frame);
void sendToPlayer(Object player, byte[] frame);
void setInboundHandler(BiConsumer<Object, byte[]> handler);   // (sender, frame)
int  maxFrameBytes();
boolean isAvailable();
```

That is the whole platform contribution. `player` is `Object` because `core/` may not name a Minecraft type.

### What actually differs between Minecraft versions

| Differs | Absorbed by |
|---|---|
| Channel identity: a ≤20-char string on 1.7.10, a `ResourceLocation` on 1.20.x | the adapter |
| Payload type: raw `ByteBuf` vs a registered `CustomPacketPayload` | the adapter |
| Player handle: `EntityPlayerMP` vs `ServerPlayer` | the `Object` above |
| Delivery thread: Netty on 1.7.10, the main thread on Fabric/NeoForge | the inbound handler only enqueues |
| **Frame ceiling** | `maxFrameBytes()`, asked, never assumed |

`FrameMultiplexerTest` runs the engine at every real ceiling plus one below and one above them all.

### The numbers

| | client → server | server → client |
|---|---|---|
| **1.7.10** | 32,766 | 2,097,050 |
| **1.20.x** | 32,767 | 1,048,576 |

The client→server bound is vanilla's signed-short packet length and will not move, and that is the direction file
saves take.

## 4. `CgFrameCodec` — one frame on the wire

```
[u8 opcode][u8 flags][varint streamId][payload …]
```

| | |
|---|---|
| `OP_DATA` `0x01` | a fragment of a message |
| `OP_WINDOW_UPDATE` `0x02` | "you may send me N more bytes" |
| `OP_RESET` `0x03` | "abandon this stream" |
| `FLAG_FIN` `0x01` | the last fragment |
| stream `0` | the connection itself; never carries `OP_DATA` |

- **No length field**: a custom payload is already one discrete `byte[]`.
- **No sequence number**: one ordered, reliable TCP connection cannot reorder or drop fragments.

## 5. `CgFrameMultiplexer` — the sorting office

**ELI5:** one letterbox, a 32 KB limit and several parcels: cut them into letterbox pieces, interleave them so a big
one does not block a small one, and never push more than the receiver said it can take. Three borrowed mechanisms:

### 5.1 Stream multiplexing — from HTTP/2

Every message gets a stream id and frames from different streams interleave, so a 5 MB file read cannot sit in front
of a 200-byte call. The two ends allocate ids from opposite parities, so both open streams without coordinating; the
client is the initiator.

### 5.2 FIN fragmentation — from WebSocket

A message is N frames on one stream, the last flagged `FIN`.

### 5.3 Credit flow control — from HTTP/2's `WINDOW_UPDATE`

The receiver advertises a budget (`DEFAULT_WINDOW_BYTES`, 256 KB); the sender stops when it is spent. **Required
here**: `NetworkManager.outboundPacketsQueue` is unbounded and shared with the whole game, so an unthrottled sender
degrades chat, movement and chunk loading.

### 5.4 Threading — one concurrent queue and nothing else

```java
onFrameReceived(bytes)   // network thread. Does exactly ONE thing: enqueue.
pump()                   // your thread. Reassembly, delivery, credit, scheduling — all here.
```

Nothing is delivered spontaneously: `Property` and `SignalBase` are single-threaded. A `WINDOW_UPDATE` is processed
only in a pump, so credit is single-threaded too.

### 5.5 `CgStreamRefused` — one stream died, the connection is fine

HTTP/2's line: a **stream error** resets one stream and the connection carries on; a **connection error** means the
peer is not speaking the protocol.

## 6. `CgBinaryFormat`, `CgWireTransport`, `CgTransport`

### `CgTransport<T>` — the interface everything above stands on

```java
void send(T encodedPacket);
void setReceiver(Consumer<T> receiver);
```

It takes `T`, not `CgEnvelope`: sessions encode first, so every implementation, the test double included, runs the real
codec. The receiver may be called from any thread; what arrives is processed in `tick()` on the tree's thread.

### `CgBinaryFormat` — tree ⇄ bytes

`CgPlainOps` builds a tree of plain values; `CgBinaryFormat` turns it into bytes and back.

- Not a `CgDynamicOps`: a binary ops would recopy every level on nesting.
- Not JSON on the wire: `CgJsonOps` stays the readable path, but text spends the 32,766-byte client→server budget.
- **Number width is part of the value**: each numeric box has its own tag and decodes to the same type, since a reader
  casts the runtime class.

### `CgInMemoryTransport` — the test double

Two transports joined by a queue: `pair()`, `deliver()`, `dropNext(n)`, `corruptNext(fn)`. Every session test runs on
it, through the real codec.

# PART TWO — THE MIDDLE: MESSAGES

## 7. `CgEnvelope` — the closed grammar

| Kind | Wire tag | Carries | Must be answered? |
|---|---|---|---|
| **Request** | `q` | id, method, payload | **yes, exactly once** |
| **Response** | `r` | id, ok/error, payload | it is the answer |
| **Notification** | `n` | method, payload | **no, and must not be** |
| **Cancel** | `x` | id | no |

Wire fields: `k` kind, `i` id, `m` method, `p` payload, `e` error. `CgEnvelopeCodec.VERSION = 1`.

**The envelope is closed; the vocabulary is open**: these four are the grammar, and everything a message is about is a
method string, registered beside the code that owns it.

- **Request and notification are structural** (LSP's rule): an unknown request is answered with an error, an unknown
  notification logged and dropped.
- **Payloads are not decoded here**: the codec routes an opaque payload, so it never grows a branch and a large
  payload can be refused unparsed.

## 8. `CgMessageRouter` — the switchboard

**ELI5:** method name in, handler out, and a memory of who is waiting for an answer.

```java
router.onRequest("fs/read",       (payload, respond) -> respond.ok(readFile(payload)));
router.onNotify ("ui/event",       payload -> dispatchToWidget(payload));

router.request  ("fs/read", path, onOk, onError);
router.notify   ("ui/stateDelta", delta);
```

Nothing enumerates the methods: the workspace registers `fs/*`, a session `ui/*`, a runtime in `language/` `script/*`,
without `core` knowing. It owns correlation, the pending map, exactly-once responding, deadlines, cancellation and
`failAllPending` when the link drops.

### Unknown methods are answered, not dropped

| | |
|---|---|
| Unknown **request** | answered `protocol/methodNotFound` |
| Unknown **notification** | logged **once per method name**, then discarded (a newer peer may send it at frame rate) |

Errors: `protocol/methodNotFound`, `protocol/handlerFailed`, `protocol/timeout`, `protocol/cancelled`.

### Replying is a callback, not a return value

```java
interface Responder<T> { void ok(T payload); void fail(String error); }
```

A handler may reply later from elsewhere. Exactly one call, exactly once; later calls are ignored and logged.

## 9. `CgProtocolConnection` — one peer's end

**ELI5:** the phone line to one person: a router, a transport under it, and who is at the other end.

```java
connection.peer();        // the platform's handle — EntityPlayerMP, or null on a client
connection.ops();         // CgPlainOps.INSTANCE, in practice
connection.router();      // the switchboard, for anything the conveniences don't cover
connection.tick();        // ← pump the wire, drain what arrived, expire what timed out
```

- **Per connection, never a singleton**: a router holds pending requests, a stream-id space, credit and timeouts for
  one peer. The registry (§10) is global; what it registers is invoked once per connection.
- **`tick()` does all three steps** (pump, drain, expire), so no subsystem can forget to pump and receive nothing.
- Default call timeout: **10 seconds**.

## 10. `CgProtocols` — where a subsystem says "I speak part of this"

**ELI5:** a sign-up sheet: sign once at startup, and every new line is wired to everyone on it.

```java
// ONCE, at mod init -- sided at the call site, and a lambda:
CgProtocols.server("workspace", connection ->
        new WorkspaceBinding<>(service, hub, actorFor(connection.peer()), connection.peer(),
                connection.ops()).installOn(connection));

// PER CONNECTION, wherever a peer appears:
CgProtocolConnection<Object> connection =
        CgProtocols.open(transport, CgPlainOps.INSTANCE, wire::pump, player);
```

Registration adds, never replaces, so order does not matter. `CgProtocols.contributors()` lists what registered.
Method names are namespaced, LSP style: `ui/*`, `fs/*`, `command/*`, `script/*`.

# PART THREE — HOW A CONNECTION IS ESTABLISHED

The connections are CrystalGraphics' (`com.crystalgraphics.net.CgNetwork`), present for any mod on it; CrystalGUI
contributes protocols.

## 11. The sequence, start to finish

### Step 0 — registration, at mod init (once per process)

CrystalGraphics' host, every era, both sides:

```java
CgNetwork.install(channel, player -> profileIdOf(player));   // the channel fills the CgPlatform slot,
                                                              // and both tables are built
```

CrystalGUI's, after it:

```java
CgUiWorkspaceHost.register();                          // contributors sign the sheet
WindowProtocol.register();
CgNetwork.onPeerClosed(CgUiWorkspaceHost::forget);     // per-peer state dropped when a peer goes
```

- **Contributors before connections**: a contributor binds only to connections opened after it registers.
- **`init`, not `preInit`, on 1.7.10 and legacy Forge**: a channel registered at preInit delivers nothing.
- `install` hands the channel `CgNetwork::route`, and the host subscribes its join, leave, tick and connection events.

### Step 1 — a peer appears

1.7.10's events (modern loaders have their own, in the same places):

| | Opens on | Closes on | Ticks on |
|---|---|---|---|
| **Server** | `PlayerLoggedInEvent` → `playerJoined` | `PlayerLoggedOutEvent` → `playerLeft`, `FMLServerStoppingEvent` → `closeAll` | `ServerTickEvent` → `serverTick` |
| **Client** | `ClientConnectedToServerEvent` → `clientConnected` | `ClientDisconnectionFromServerEvent` → `clientDisconnected` | `ClientTickEvent` → `clientTick` |

A kick, a disconnect and a quit are one event: the peer is gone and every caller waiting on a reply is told.

### Step 2 — `open()` builds the stack

`CgConnections.open`, per peer:

```java
CgFrameMultiplexer frames = new CgFrameMultiplexer(maxFrameBytes, initiator, outbound::send);
CgWireTransport transport = new CgWireTransport(frames);
CgProtocolConnection<Object> connection =
        CgProtocols.open(transport, CgPlainOps.INSTANCE, transport::pump, peer);
peers.put(key, new Held(frames, connection, peer));
```

The multiplexer learns the ceiling, its stream parity (`initiator`: true on the client) and where finished frames go;
the transport adds the codec; `CgProtocols.open` builds the router and binds every contributor; `Held` closes it all
together. The pump goes in here, so `tick()` is the one call.

### Step 3 — keyed by profile, resolved at send time

The server table is keyed by the player's **profile UUID**, never the entity, which respawn and dimension changes
replace. A `CgPeer` carries the id, the name at join, and a supplier for the live player, asked on every send:

```java
CgNetwork.playerJoined(id, name, () -> handler.playerEntity);   // the handler is re-pointed at the new body
```

Frames arrive on Netty's thread; server connections open, close and tick on the server thread, the client's on the
client thread. The map is concurrent and nothing else is: `CgProtocolConnection.tick()` is the only dispatcher.

### Step 4 — inbound frames get routed

```java
private static void route(Object sender, byte[] frame) {     // NETTY THREAD
    if (sender == null) client.route(CLIENT, frame);           // enqueue only
    else server.route(idOf.apply(sender), frame);
}
```

A frame for a peer that has gone is dropped: only a lifecycle event creates a connection.

### Step 5 — ticking

On `Phase.START` on 1.7.10 and legacy Forge, so a message is applied before the world runs on it; the modern hosts
tick at the end. One peer's exception never stops the others.

### Step 6 — closing

```java
connection.close(reason);   // fails EVERYTHING outstanding, immediately
```

### How you get hold of one

```java
CgNetwork.forPlayer(serverPlayer); // server side, the player object (or its profile UUID) — null if they have none
CgNetwork.client();                // client side — null when not in a world
CgNetwork.openConnections();       // diagnostics; what a leak shows up in
CgNetwork.isInstalled();           // did the host install the connections?
```

An unavailable channel warns and returns rather than throwing, so a server with no networking looks healthy;
`isInstalled()` and the gauntlet's `crystalgraphics:server` catch it.

# PART FOUR — DEFINING A PACKET CONTRACT

**There is no packet class, discriminator byte or id registry.** A contract is a name, a payload shape, a handler on
one side and a caller on the other.

## 12. The recipe

### 1. Put the names in one file

```java
public final class FsMethods {
    public static final String READ = "fs/read";
    public static final String WRITE = "fs/write";
}
```

Both ends read the constants, never the spelled strings.

### 2. Put each payload in one record, with a codec

```java
public record ReadRequest(String path, String ifNoneMatch) { }

public record ReadResponse(String etag, byte[] content, boolean unchanged,
                           String transfer, long size) { }

public static CgCodec<ReadRequest> readRequest() { … }
```

A record plus a codec makes a field written on one side provably the field read on the other. `ui/*` stays untyped
because a mod extends its vocabulary.

### 3. Server side — register a handler

```java
registry.register(FsMethods.READ, (args, respond) -> {
    ReadRequest ask = FsMessages.readRequest().decode(ops, args.encode());
    ...
    respond.ok(FsMessages.readResponse().encode(ops, answer));
});
```

### 4. Client side — call it, and get a `CgReply`

```java
workspace.files().read(resource)
        .then(content -> { … })
        .onError(failure -> { … });     // a code and a detail, never a String alone
```

`CgReply<T>` is the one async shape, its continuation run on the frame thread; a `Stream<T>` is its many-answers twin
(a paged listing, a chunked read).

### `CgStateMap` — the payload's readable face

A typed key/value bag over any `CgDynamicOps`:

```java
out.putString("k", v);  out.putInt(...);  putFloat  putBool  putBytes  putEnum  putList
in.getString("k", fallback);              // every getter takes a fallback
out.putStringIfNot("k", v, "");           // omitted when it equals the default
```

Every getter has a fallback, which is the versioning story: a field added later decodes from an older sender with no
version bump, and a default-valued record writes an empty map that is dropped.

## 13. A real contract, both halves side by side — `fs/read`

### The server half (`WorkspaceBinding`)

```java
registry.register(FsMethods.READ, (args, respond) -> guard(respond, () -> {
    CgPath target = path(args);

    // 1. STAT FIRST — enforces the cap before an allocation, and decides inline-vs-chunked
    //    without reading a byte the caller may not be able to receive.
    CgFileEntry entry = service.stat(actor, target);
    if (entry.size() > WorkspaceService.MAX_FILE_BYTES) {
        throw CgFileSystemException.tooLarge(target, entry.size(), WorkspaceService.MAX_FILE_BYTES);
    }

    // 2. CONDITIONAL — if the client already holds this revision, say so and send NOTHING.
    //    Checked against the stat, so it costs no read at all.
    String known = args.has(IF_NONE_MATCH) ? args.getString(IF_NONE_MATCH, null) : null;
    if (known != null && known.equals(entry.etag())) {
        respond.ok(new CgStateMap<T>(args.ops())
                .putBool(UNCHANGED, true).putString(ETAG, entry.etag()));
        return;
    }

    // 3. SMALL ENOUGH — send it inline.
    WorkspaceService.FileContent content = service.read(actor, target);
    if (content.content().length <= INLINE_MAX_BYTES) {
        respond.ok(new CgStateMap<T>(args.ops())
                .putBytes(CONTENT, content.content()).putString(ETAG, content.etag()));
        return;
    }

    // 4. TOO BIG — open a chunked transfer and tell the client to pull it.
    ...
}));
```

### The client half (`FileOperations`)

```java
public CgReply<FileContent> read(Resource file) {
    // THE CONDITION, from the etag this client already holds. Costs the server a stat and no read.
    ReadRequest ask = new ReadRequest(file.toString(), etags.getOrDefault(file, ""));

    return call(FsMethods.READ, FsMessages.readRequest(), ask, FsMessages.readResponse())
            .map(answer -> {
                if (answer.unchanged()) return cached(file);          // served from cache
                if (answer.transfer().isEmpty()) return inline(answer);
                return pull(answer.transfer(), answer.size());        // chunks, client-driven
            });
}
```

1. **The reply says what shape it is**: unchanged, inline or chunked are flags on one response. The threshold is the
   server's; a client never assumes one.
2. **Chunked reads are pulled by the client**: the client sets the pace, and a resume is the same request at another
   offset (HTTP ranges).
3. **"Too big for one message" is a fact**: the transport bounds a reassembled message.
4. **The client keeps the etag**, so the UI does not have to.

### Errors are codes, never stack traces

```java
// server
catch (CgFileSystemException e)      -> respond.fail(FsError.of(e.error().name(), …));
catch (WorkspaceConflictException e) -> respond.fail(FsError.conflict(actualEtag));
catch (RuntimeException e)           -> respond.fail(FsError.of("UNKNOWN", …));
```

A client branches on a value; an unexpected exception is `UNKNOWN`, leaking no server path. An `FsError` carries
fields: a conflict returns the etag the file actually holds, so the client can re-read and merge.

### Who you are is bound at construction, never sent

```java
new WorkspaceBinding<>(service, hub, actorFor(peer), peer, ops)
```

One connection is one player, so the actor is bound once. A client that could name its own actor could name somebody
else's.

## 14. The other direction — server pushing to a client

```java
// server, every tick — ONE hub for the whole server, not one watcher per peer:
hub.tick();     // stats each watched path once, coalesces a save's several events into one change,
                // pairs a delete and a create carrying one etag into a RENAME, and notifies each peer

// client, once at bind:
connection.onNotify(FsMethods.CHANGED, payload -> {
    cache.forget(path);                  // BEFORE the handler runs
    onChanged.emit(new FileChanged(path, kind, etag));
});
```

`fs/changed` is a notification (`PushIsANotificationTest`), and the cache is dropped before the callback, so a handler
that re-reads gets fresh bytes.

# PART FIVE — THE TENANTS

Three peer subsystems ride a `CgProtocolConnection`.

## 15. The UI — `ui/*`

| Method | Kind | Direction |
|---|---|---|
| `ui/openWindow` | notification | S→C |
| `ui/description` | **request** | C→S, answered with the tree |
| `ui/stateDelta` | notification | S→C |
| `ui/treeOps` | notification | S→C, an ordered edit script: `insert` / `remove` / `move` |
| `ui/closeWindow` | notification | S→C, "this window is finished" |
| `ui/close` | notification | **C→S, "the user closed it"** (Minecraft's close-container packet) |
| `ui/requestClose` | **request** | S→C, "may I?", answered by the content |
| `ui/requestOpen` | **request** | C→S, "give me a window of this type"; the answer is only whether |
| `ui/focusWindow` | notification | S→C, "bring it forward" |
| `ui/visibility` | notification | C→S, "it is / is not on screen" |
| `ui/view` | notification | S→C, about the window rather than the tree (title, icon, notify) |
| `ui/sheet` | **request** | C→S, answered with a stylesheet, by hash |
| `ui/event` | notification | C→S |
| `ui/rows` | **request** | C→S, "I am looking at rows `[from, to)`"; the answer is the **count** |

**Every `ui/*` payload carries `w`, the window id**, in the payload: a packet in flight when a window closes cannot land
on the next one.

### `ServerWindows` / `ClientWindows` — a window's lifetime

**The layer you use**: it allocates ids, binds then opens, sweeps validity and flushes each tick, and ends a window
all four ways.

```java
WindowProtocol.register();                                  // once, at init
ServerWindows.of(connection).open(new MyWindow(model));   // whenever you have something to show
```

Ported from `ServerPlayer.openMenu`: next id, construct, tell the client the type and title, observe, tick with a
`stillValid` check. Two deliberate differences:

- **Many windows per connection**, unique per **key**: opening an open key brings that window forward with its state.
- **Close is a request**: the frame asks, the client decides, then sends `ui/close`.

On the client, `ClientWindows` adopts each session and hands the tree to a **`WindowMount`**, the one thing a platform
implements. Local behaviour is looked up by window type (`MenuScreens.register`), and a type nothing registered still
mounts and works.

### `ServerUiSession` — owns the tree, never lays it out

No `UIDocument`: no Taffy, no style engine, no layout, so no path into text measurement. It implements
`TreeObserver` and coalesces changes into one `ui/stateDelta` per `tick()`.

### `ClientUiSession` — rebuilds, and caches

Keyed by content hash, so a stale entry cannot be served. A UI opened before costs one `ui/openWindow` packet.

### `ClientUiSessions` vs a bare `ClientUiSession`

One window: a `ClientUiSession`. More: `ClientUiSessions.forConnection`. They are exclusive, since both bind
`ui/openWindow`, the one message that cannot be routed by window id; the router refuses a second binder.

### `UiWindowMux` — dispatch by window *as well as* method

`CgMessageRouter` keys by method alone and refuses duplicates; the mux sits above it and dispatches on `w` too. A UI
message with no window id is refused (request) or dropped with one warning (notification), never delivered to "the
only window".

### The UI's supporting cast

| | |
|---|---|
| `UIElementTreeSource` | element ids owned by the source, allocated on first sight and kept for its life |
| `ServerTreeMirror` / `ClientTreeMirror` | the edit script, generic in the node type; the server half produces payloads, so one window fans out to viewers with different visibility |
| `CgContentHash` | SHA-256 of a canonical encoding (sorted keys, type tags and counts) |
| `UIElementMirror` | `{tag, id?, class[]?, style{}?, flags?, focus?, state{}?, children[]?}`; children are `describedChildren()`. **Throws on an unknown tag** |
| `SheetRef` | `(hash, id?)`: theme present, version skew, datapack theme, generated sheet |
| `WidgetContract` | what a kind of widget is: state slots in apply order, events, whether it takes children |

## 16. The workspace — `fs/*`

`FsMethods` · `FsMessages` (every payload a record with a codec) · `FsError` · `WorkspaceBinding` (server) ·
`Workspace` (client).

`WorkspaceBinding.installOn` takes a `CgProtocolConnection` (registering every method and attaching the binding, so
`ServerScope.workspace()` finds it) or a bare `Registrar` (`CgMessageRouter::onRequest`, `ServerUiSession::onCall`),
so a test installs onto a bare registry.

The vocabulary: `fs/hello`, `fs/projects`, `fs/capabilities`, `fs/list` (paged: `{entries, cursor}`), `fs/stat`,
`fs/read`, `fs/readChunk`, `fs/write`, `fs/writeDelta`, `fs/create`, `fs/mkdir`, `fs/rename`, `fs/copy`, `fs/delete`,
`fs/watch`, `fs/unwatch`, `fs/changed`, `fs/presence`, `fs/trashList`, `fs/restore`, `fs/purge`.

## 17. Commands — `command/*`

`command/contribute`, `command/withdraw`, `command/setEnabled` (S→C notifications) and `command/invoke` (C→S
**request**). Everything about a command flows from the server, which alone knows; "the user did it" flows back as a
request, since it can fail. Enablement is pushed, so no round trip sits inside a menu opening.

# PART SIX — THE 1.7.10 WIRING

## 18. `NetworkChannel1710` — the whole of 1.7.10 networking

CrystalGraphics' (`com.crystalgraphics.mc.v1710.platform.net`); legacy Forge's `NetworkChannelLegacy` is its twin.

```java
private static final String CHANNEL = "crystalgraphics"; // ≤20 chars — a hard ceiling
private static final int MAX_FRAME_BYTES = 32_766;
```

**An event-driven channel** (`newEventDrivenChannel`), handing over a raw `ByteBuf` both ways. `SimpleNetworkWrapper`
marshals typed messages, which we do not want, and its handler never fired here. Three traps:

1. **`setTarget` is required**:

   ```java
   FMLProxyPacket packet = new FMLProxyPacket(Unpooled.copiedBuffer(frame), CHANNEL);
   packet.setTarget(target);     // ← without this, single player silently drops everything
   ```

   In single player the packet arrives by reference, and `processPacket` finds no channel for a null side and drops it
   with no log.
2. **`copiedBuffer`, not `wrappedBuffer`**: FML keeps the buffer past the call.
3. **Report the smaller ceiling**: one channel serves both directions, and over-reporting fails mid-send.

FML does not fragment for us; `CgFrameMultiplexer` does.

## 19. `Network1710` — the lifecycle

CrystalGraphics' FML subscriptions (§11): it forwards join, leave, both ticks at `Phase.START`, and the client's
connect and disconnect into `CgNetwork`, which owns the tables.

## 20. `CgUiWorkspaceHost` — the server actually serving files

```java
CgProtocols.server("workspace", CgUiWorkspaceHost::bindWorkspace);
```

- A contributor is a lambda over `CgProtocolConnection<Object>`; `CgProtocols.server` / `client` put the side in the
  method name.
- **Files live on the server's machine, and single player is not a special case**: the root is
  `<serverdir>/crystalgui/workspace` through `MinecraftServer.getFile(...)`, the game directory in single player
  because the integrated server is a server.
- **One `WorkspaceBinding` per connection**, the actor being per player, with that peer's audit, idempotency table
  and entry in the server's one `WatchHub`.
- **A client connection never hosts a workspace** (`if (connection.peer() == null) return;`), or single player would
  serve itself from both ends.

# PART SEVEN — PUTTING IT TOGETHER

## 21. Follow one packet all the way down and back

A user clicks a button in a server-built GUI.

```
CLIENT                                                                    SERVER
──────                                                                    ──────
Button.attachListener fires
  ↓
ClientUiSession.report(element, "activate")
  builds CgStateMap { w: 7001, nid: 3, kind: "activate" }
  ↓
router.notify("ui/event", payload)          [7] which subsystem
  ↓
CgEnvelopeCodec.encode → { k:"n", m:"ui/event", p:{…} }   [3] the grammar
  ↓
CgWireTransport.send → CgBinaryFormat.encode → byte[]        [2] encoding
  ↓
CgFrameMultiplexer.send                                    [1] chunking
  · picks an odd stream id (the client is the initiator)
  · splits into ≤32,766-byte frames, last one FLAG_FIN
  · spends credit; queues the rest if the window is empty
  ↓  (next pump)
CgNetworkChannel.sendToServer(frame)                     [0] the road
  ↓
    ═════════ FMLProxyPacket, target=SERVER, channel "crystalgraphics" ═════════
                                                                            ↓
                                    NetworkChannel1710.onServerPacket     [0]
                                      (NETTY THREAD — extracts byte[])
                                                                            ↓
                                    CgNetwork.route(player, frame)
                                      server.route(profileId, frame)
                                      ↳ ENQUEUE ONLY. Nothing else happens here.
                                                                            ↓
                                    ─── tick boundary ────────────────────────
                                    ServerTickEvent, Phase.START, SERVER THREAD
                                                                            ↓
                                    connection.tick()                      [5]
                                      1. transport::pump  → CgFrameMultiplexer.pump
                                         · reassembles frames until FIN     [1]
                                         · CgBinaryFormat.decode → tree       [2]
                                      2. drain mailbox → router.accept      [4]
                                      3. expire timeouts
                                                                            ↓
                                    CgMessageRouter: "ui/event" → handler    [4]
                                                                            ↓
                                    UiWindowMux: w=7001 → this session     [6]
                                                                            ↓
                                    ServerUiSession: nid=3 → the element
                                      → runs YOUR lambda                   [7]
                                                                            ↓
                                    model.setRunning(true)
                                    panel.progress.setFraction(…)
                                      → notifyStateChanged()
                                      → session's dirty set
                                                                            ↓
                                    …same tick: session.tick() → flush
                                      ONE ui/stateDelta for every change
                                                                            ↓
    ◄══════════════════════════════ all the way back down and up ═══════════
```

No pixel, colour or layout crossed: the client drew a tree it did not build, from widget classes it already had.

## 22. Who ticks what, and on which thread

| Thing | Called by | Thread | If you forget |
|---|---|---|---|
| `channel.setInboundHandler` | once, at init | — | frames arrive and go nowhere |
| `CgFrameMultiplexer.onFrameReceived` | the channel adapter | **Netty** | — (it only enqueues) |
| `CgProtocolConnection.tick()` | the loader, per tick | server / client thread | **silence**: nothing arrives, sends or times out |
| `CgProtocolConnection.onTick` hooks | `tick()`, after the drain | server / client thread | what registered one stops; `ServerWindows` is one |
| `ServerUiSession.tick()` | **`ServerWindows`**, per tick | server thread | the session answers calls and never sends another state update |
| `ClientUiSession.tick()` | nobody | client thread | nothing: a no-op while riding a connection |

You call neither session's `tick()`: `ServerWindows` ticks every window from its connection's `onTick`. Everything
above `onFrameReceived` runs on the thread that owns the tree.

## 23. Words people mix up

| These look alike | …and are not |
|---|---|
| **Frame** / **Message** / **Envelope** | transport chunk / one payload / the grammar wrapper |
| **Transport** / **Connection** | a pipe / a pipe with a router, an identity and pending state |
| **Connection** / **Session** | one line / one conversation on it |
| **`CgProtocolConnection.tick()`** / **`Session.tick()`** | moves bytes and dispatches / flushes one subsystem's work |
| **Request** / **Notification** | somebody waits / nobody does |
| **Network id** / **CSS id** | an int the tree source owns and the protocol addresses / a string the cascade matches |
| **`ClientUiSession`** / **`ClientUiSessions`** | one window / the owner that hands them out; exclusive on a connection |
| **`ServerUiSession`** / **`ServerWindow`** | the protocol for one window / the thing you write |
| **`ui/closeWindow`** / **`ui/close`** | server→client, "finished" / client→server, "the user closed it" |
| **hidden** / **closed** | retained, `ui/visibility false`, comes back / gone, session ended, `onClosed` fired |
| **`window.key()`** / **`window.type()`** | which window (dedup and geometry) / what kind (client behaviour) |
| **Stream error** / **Connection error** | reset one stream / the peer is not speaking the protocol |

## 24. Cheat sheet

```java
// ── Speak a new protocol ──────────────────────────────────────────────────
CgProtocols.contribute("mything", c -> {
    c.onRequest("mything/doIt", (args, respond) -> respond.ok(result));
    c.onNotify ("mything/tick", args -> apply(args));
});

// ── Get a connection ──────────────────────────────────────────────────────
CgProtocolConnection<Object> c = CgNetwork.forPlayer(serverPlayer);   // server: the player, or their UUID
CgProtocolConnection<Object> c = CgNetwork.client();                  // client

// ── Talk ──────────────────────────────────────────────────────────────────
c.call("mything/doIt", args, onResult, onError);      // request — you get an answer
c.notify("mything/tick", args);                       // notification — you don't

// ── Serve a UI ────────────────────────────────────────────────────────────
// once, at init, beside the contributor above:
WindowProtocol.register();

// wherever you have something to show. No id, no session, no tick, no teardown.
ServerWindows.of(c).open(MyPanel.TYPE, model);

// ONE class is the whole UI — widgets as fields, both halves as methods:
public final class MyPanel extends UINode implements Networked<MyModel> {
    public static final UiType<MyPanel, MyModel> TYPE = UiType.of("mymod:panel", MyPanel::new);

    public Button save;                      // created and NAMED for you: the field name is the id
    public UIText status = new UIText("");   // ctor arguments? just write the initializer
    public EnginePanel engines;              // a nested Networked panel — composition is nesting

    @Override public void layout(MyModel m) {                    // structure. SERVER, once
        append(save); append(status);
        engines = EnginePanel.TYPE.build(m.engines());           // the parent knows the slice
        append(engines);
    }
    @Override public void serve(MyModel m, ServerScope io) {     // before open(), enforced
        io.sheet(SheetRef.ofResource("mymod:theme", hash), css);
        io.on(save, Button.ACTIVATE, ctx -> m.save());
        io.onCall  ("save",  (args, respond) -> respond.ok(null)); // window-scoped
        io.onNotify("ping",  payload -> noted());                  // window-scoped
        io.attach(engines, m.engines());     // child's "save" becomes "engines/save", both sides
    }
    @Override public void tick(MyModel m) { status.setText(m.summary()); }  // mirror; host flushes
    @Override public boolean stillValid(MyModel m, Object viewer) { return m.exists(); }
    @Override public String title(MyModel m) { return "My panel"; }
    @Override public String key(MyModel m)   { return "mymod:panel"; } // dedup + remembered geometry

    @Override public void bindWidgets()          { /* widget listeners */ }  // CLIENT, every bind
    @Override public void client(ClientScope io) { /* onCall/onNotify  */ }  // CLIENT, once
    @Override public void closed(String why)     { }   // both sides: SERVER/CLIENT/NOT_VALID/CONNECTION_LOST
}

// ── Receive a UI ──────────────────────────────────────────────────────────
// Nothing. The open names the panel class; the engine initialises it (it must be a Networked) and runs the client
// half. A window whose binding fails still renders and reports its events; only the local extras go quiet, logged.

// once per client, not per mod — the desktop's DesktopWindowMount does this on every host:
ClientWindows.of(c).setMount(myWindowMount);
```

## 25. Where to look next

| | |
|---|---|
| `com.crystalgui.app.machine` | all of this, runnable (`./gradlew :core:runExample` runs `MachineDemo` on the headless classpath); `CGUI_BUILDING_UIS.md` is its written half |
| `CGUI_SERVER_AND_SERIALIZATION.md` | the same ground as a reference |
| `core/src/headlessTest/` | a server's classpath: if it runs here, it runs on a server |
| `com.crystalgui.gauntlet.ConnectionTest` | the whole stack against a real connection: handshake, deltas, events, calls, fan-out, the workspace. Topology is a parameter; a check meaningless in it is reported SKIPPED |
| `plan/net-window-host.md` | why the layer above the sessions exists |
| `./gradlew :runtime:mc:1710:serverSmoke` | boots a dedicated server, asserts the stack came up, stops |

## Appendix — the constants, in one place

| | |
|---|---|
| Frame layout | `[u8 opcode][u8 flags][varint streamId][payload]` |
| Opcodes | `DATA 0x01` · `WINDOW_UPDATE 0x02` · `RESET 0x03` |
| Flags | `FIN 0x01` |
| Stream 0 | the connection itself; never carries `DATA` |
| Envelope kinds | `q` request · `r` response · `n` notification · `x` cancel |
| Envelope fields | `k` kind · `i` id · `m` method · `p` payload · `e` error |
| `CgEnvelopeCodec.VERSION` | `1` |
| Default flow-control window | 256 KB |
| Default call timeout | 10 s |
| 1.7.10 and legacy channel name | `crystalgraphics` (≤20 chars, hard limit); modern `crystalgraphics:wire` |
| Frame ceilings | 32,766 / 2,097,050 (1.7.10) · 32,767 / 1,048,576 (1.20.x) |
| Protocol errors | `protocol/methodNotFound` · `handlerFailed` · `timeout` · `cancelled` |
| UI window-id key | `w`, in the payload, never the envelope |
