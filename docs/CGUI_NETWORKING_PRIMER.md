# CrystalGUI Networking — A Lecture

> **Who this is for.** Anyone about to touch `core/net/`, `core/fs/`, or a loader's networking, who
> wants to know what the words mean before reading the code. It is deliberately intuitive first and
> precise second. Every claim here was read out of the source; where the code and its own naming
> disagree, this says so rather than smoothing it over.
>
> **Companion reference:** `docs/CGUI_SERVER_AND_SERIALIZATION.md` (the same ground, written as a
> reference rather than a lecture). **Worked example:** `com.crystalgui.example.machine`, which is
> ~600 lines of runnable code doing everything below — `./gradlew :core:runExample`.

---

## 0. The one idea

A dedicated Minecraft server has **no OpenGL, no fonts, no textures**. It cannot draw anything. But
it is the only place that knows what is *true* — what's in the chest, who owns the machine, what the
file on disk says.

So we split it:

- The **server** builds a tree of ordinary widgets and **describes** it.
- The **client** rebuilds that description into its own tree and **draws** it.
- After that, the two halves talk about elements *by number*, and **neither ever sends a picture**.

Everything in this document is machinery in service of that sentence.

---

## 1. The five words, before anything else

Read these once. The rest of the lecture is these five things, elaborated.

| Word | ELI5 | In one line |
|---|---|---|
| **Frame** | One envelope that fits through the letterbox | A `byte[]` small enough for Minecraft to carry — about **32 KB** |
| **Message** | A whole letter, which may need several envelopes | One logical payload, split across frames and reassembled |
| **Envelope** *(the type)* | The four things a letter can *be* | ask / answer / tell / take-it-back — and nothing else, ever |
| **Connection** | One open phone line to one other person | `ProtocolConnection` — a router, a transport, and who's on the far end |
| **Session** | One *conversation* on that line | One GUI's worth of back-and-forth; several share one connection |

> **The single most common confusion**: **Frame ≠ Message ≠ Envelope.** A frame is a *transport*
> concern (how big a chunk the game will carry). A message is *one payload* that may span many
> frames. An `Envelope` is the *grammar* wrapper around a payload. Three different layers, and
> mixing them up is why the wire code looks more complicated than it is.

And one more, because it's the word that sounds like it means something bigger than it does:

| Word | ELI5 |
|---|---|
| **Peer** | Literally "the other end". On the server, a player. On the client, the server. That's all. |

---

## 2. The layer cake

Bottom is bytes, top is widgets. Read it upward — each layer only knows about the one directly
below it.

```
      ┌──────────────────────────────────────────────────────────────┐
  8   │  ServerWindows / ClientWindows    ServerWindow, WindowMount    │  a window's LIFETIME
      ├──────────────────────────────────────────────────────────────┤
  7   │  ServerUiSession / ClientUiSession   WorkspaceBinding / Workspace  │  what a message MEANS
      │  RemoteCommands                       (the "tenants")        │
      ├──────────────────────────────────────────────────────────────┤
  6   │  UiWindowMux                    (only the UI needs this)     │  which WINDOW
      ├──────────────────────────────────────────────────────────────┤
  5   │  ProtocolConnection  ── one per peer ──  Protocols registry  │  who + wiring
      ├──────────────────────────────────────────────────────────────┤
  4   │  MessageRouter        method name → handler, ids, timeouts   │  which HANDLER
      ├──────────────────────────────────────────────────────────────┤
  3   │  Envelope + EnvelopeCodec       q / r / n / x                │  the GRAMMAR
      ├──────────────────────────────────────────────────────────────┤
  2   │  UITransport   ├─ WireTransport ─ BinaryFormat (tree↔bytes)  │  encoding
      │                └─ InMemoryTransport  (tests: a queue)        │
      ├──────────────────────────────────────────────────────────────┤
  1   │  FrameMultiplexer   streams, fragmentation, flow control     │  chunking
      │  FrameCodec         [opcode][flags][streamId][payload]       │
      ├──────────────────────────────────────────────────────────────┤
  0   │  CgNetworkChannel   "carry this byte[]"   ← THE PLATFORM SEAM│  the road
      └──────────────────────────────────────────────────────────────┘
             ↑ a loader implements ONLY this line (≈4 methods)
```

**The important structural fact:** everything from layer 1 upward is in `core/` and is written
once. A new Minecraft version implements layer 0 and nothing else.

---

# PART ONE — THE BOTTOM: MOVING BYTES

## 3. `CgNetworkChannel` — the road

**ELI5:** the postal service. It knows how to carry a parcel and how big a parcel may be. It has no
idea what's inside.

Four methods and an availability check:

```java
void sendToServer(byte[] frame);
void sendToPlayer(Object player, byte[] frame);
void setInboundHandler(BiConsumer<Object, byte[]> handler);   // (sender, frame)
int  maxFrameBytes();
boolean isAvailable();
```

That is **the entire platform contribution to networking.** Framing, stream ids, fragmentation, flow
control, cancellation, what a message means — all of it lives above this line. A loader never sees
an `Envelope`, never learns a stream id, never picks a chunk size.

`player` is `Object` on purpose: `core/` may not name `EntityPlayerMP`. It's an opaque handle that
travels back down to the adapter untouched.

### What actually differs between Minecraft versions

| Differs | Absorbed by |
|---|---|
| Channel identity — a ≤20-char string on 1.7.10, a `ResourceLocation` on 1.20.x | Private to the adapter |
| Payload type — raw `ByteBuf` vs a registered `CustomPacketPayload` record | Private to the adapter |
| Player handle — `EntityPlayerMP` vs `ServerPlayer` | The `Object` above |
| Delivery thread — Netty on 1.7.10, main thread on Fabric/NeoForge | The inbound handler only *enqueues*, so either is fine |
| **Frame ceiling** — four different numbers | `maxFrameBytes()`, asked for and never assumed |

**Nothing else reaches `core`.** That's what makes "will this work on a version nobody wrote an
adapter for" a testable question: `FrameMultiplexerTest` runs the engine at every real ceiling plus
one below and one above them all.

### The numbers

| | client → server | server → client |
|---|---|---|
| **1.7.10** | 32,766 | 2,097,050 |
| **1.20.x** | 32,767 | 1,048,576 |

The client→server bound is **not a Forge decision and will not move**: vanilla writes the packet
length as a *signed short*. That's the direction carrying your file saves, which matters later.

---

## 4. `FrameCodec` — one frame on the wire

```
[u8 opcode][u8 flags][varint streamId][payload …]
```

| | |
|---|---|
| `OP_DATA` `0x01` | a fragment of a message |
| `OP_WINDOW_UPDATE` `0x02` | "you may send me N more bytes" |
| `OP_RESET` `0x03` | "abandon this stream" |
| `FLAG_FIN` `0x01` | this is the last fragment |
| stream `0` | means *the connection itself*; never carries `OP_DATA` |

**Two things deliberately absent, and both are the interesting part:**

- **No length field.** The platform hands over one discrete `byte[]` per frame — a Minecraft custom
  payload is *already* framed — so a length would restate `array.length`, and the two could
  disagree. This is the one piece of framing we get for free.
- **No sequence number.** Delivery is a single ordered, reliable TCP connection, so fragments cannot
  arrive out of order and cannot vanish without the connection dying. A sequence number would encode
  a guarantee the transport already makes, and buy a reordering buffer no test could ever exercise.
  *A datagram protocol couldn't make this choice; this one can.*

---

## 5. `FrameMultiplexer` — the sorting office

**ELI5:** you have one letterbox, a 32 KB limit, and several parcels to send. This class cuts them
into letterbox-sized pieces, **interleaves** them so a big parcel doesn't block a small one, and
refuses to shove more through than the receiver says it can handle.

Three mechanisms, **none of them invented here**:

### 5.1 Stream multiplexing — from HTTP/2

Every message gets a **stream id**. Frames from different streams interleave. So a five-megabyte
file read cannot sit in front of a two-hundred-byte RPC.

Without it, opening a large file visibly freezes the editor's own protocol behind it.

> **Odd/even ids.** The two ends allocate stream ids from different halves of the number space —
> the *initiator* takes one parity, the other end takes the other. HTTP/2's trick. It means both
> ends can open streams concurrently without agreeing on anything or asking permission. In
> `CgUiConnections`, the **client is the initiator and the server is not**.

### 5.2 FIN fragmentation — from WebSocket

A message is N frames on one stream, the last one flagged `FIN`. See §4 for why there's no chunk
index.

### 5.3 Credit flow control — from HTTP/2's `WINDOW_UPDATE`

The receiver advertises a byte budget; the sender spends it and **stops when it's gone**. Default
window: **256 KB** (`DEFAULT_WINDOW_BYTES`).

**This is the one that is not optional here.** RFC 9113 gives the general reason — *"a flow-control
scheme ensures that streams on the same connection do not destructively interfere with each other"*
— and our case is worse than the one it describes. `NetworkManager.outboundPacketsQueue` is an
**unbounded** `ConcurrentLinkedQueue` **shared with the entire game**. Our streams contend not only
with each other but with chat, movement and chunk loading, on the same TCP connection.

> A sender that pushes a large file as fast as it can encode **does not degrade this feature, it
> degrades the game.**

### 5.4 Threading — one concurrent queue and nothing else

```java
onFrameReceived(bytes)   // network thread. Does exactly ONE thing: enqueue.
pump()                   // your thread. Reassembly, delivery, credit, scheduling — all here.
```

Nothing is ever delivered spontaneously. `Property` and `SignalBase` are single-threaded by
documented contract, so delivering from the network thread wouldn't be a race to tune — it would be
a correctness bug.

Credit is therefore *single-threaded state* despite being replenished by the peer, because a
`WINDOW_UPDATE` is only ever **processed** during a pump.

### 5.5 `StreamRefused` — one stream died, the connection is fine

HTTP/2 draws exactly this line and the class is named for it: a **stream error** resets one stream
and the connection carries on; a **connection error** means the peer isn't speaking the protocol and
there's nothing to salvage.

The multiplexer had both conditions and one exception type, so it *couldn't act on the difference* —
and didn't: refusing a single oversized transfer threw out of `pump`, abandoning every frame queued
behind it and skipping that tick's credit replenishment, on a connection whose other streams were
healthy.

---

## 6. `BinaryFormat`, `WireTransport`, `UITransport`

### `UITransport<T>` — the interface everything above stands on

```java
void send(T encodedPacket);
void setReceiver(Consumer<T> receiver);
```

Two methods. Note it takes **`T`, not `Envelope`** — sessions encode *before* handing over, so
**every implementation, including the in-memory one used by tests, exercises the real codec on every
hop.** A transport passing object references would let a field somebody forgot to encode pass every
test and fail only in game.

> **"It is a mailbox, not a dispatcher."** The receiver may be called from any thread. What arrives
> is queued and processed from `tick()` on the thread that owns the tree.

### `BinaryFormat` — tree ⇄ bytes

`PlainOps` builds a tree of plain `Map`/`List`/`String`/numbers. `BinaryFormat` turns that tree into
bytes and back.

**Deliberately *not* a `DynamicOps`.** A binary `DynamicOps` was the obvious shape and is the wrong
one: `DynamicOps` *builds a tree*, and a tree of `byte[]` composes by concatenating its children, so
every nesting level recopies everything below it.

**Why not JSON?** `JsonOps` works and stays the readable path for debugging. It's a poor *wire*
format for one measured reason: **32,766 bytes per client→server packet**, and that's the direction
carrying file saves. Text encoding spends the budget that matters most.

**Number width is part of the value.** Every numeric box gets its own tag and decode restores the
same box type. Not tidiness — `PlainOps` holds `Object`, so a codec that reads a field back and
casts it sees the runtime class. Collapsing every integer to `Long` (what a JSON round trip does)
makes "the same tree" true of the values and false of the types, and it fails at the *reader*.

### `InMemoryTransport` — the test double

Two transports wired into each other with a queue instead of a socket. `pair()`, `deliver()`, plus
`dropNext(n)` and `corruptNext(fn)` for simulating a bad link. Every session test in the repository
runs against it — and because of the `T`-not-`Envelope` rule above, those tests exercise the same
codec production does.

---

# PART TWO — THE MIDDLE: MESSAGES

## 7. `Envelope` — the closed grammar

**Every message on the wire is one of exactly four things:**

| Kind | Wire tag | Carries | Must be answered? |
|---|---|---|---|
| **Request** | `q` | id, method, payload | **Yes, exactly once** |
| **Response** | `r` | id, ok/error, payload | — it *is* the answer |
| **Notification** | `n` | method, payload | **No — and must not be** |
| **Cancel** | `x` | id | No |

Wire field names: `k` kind, `i` id, `m` method, `p` payload, `e` error. `EnvelopeCodec.VERSION = 1`.

> **The envelope is closed; the vocabulary is open.** That distinction is the whole design.
>
> These four are the *grammar* — ask, answer, tell, take it back — and **a protocol does not grow new
> grammar.** Everything a message is *about* is a **method string**, and that set is open: adding one
> is a registration next to the code that owns it.

### What this replaced

`UIPacket` was a sealed union of nine records. Adding a message meant editing **four** places — the
union, `encode`, `decode`, and every session's `handle` chain — and all four were shared by every
subsystem, so the workspace, the UI and the script runtime edited the same three files and
conflicted there.

**Now: adding a message is adding a string.**

### Request vs Notification is *structural*, taken from LSP

> *"every processed request must send a response back … notifications don't require responses."*

`UIPacket` mixed the two with no way to tell them apart. Making it structural means the router can
answer an unknown **request** with an error (a caller waiting on a reply always gets one) while an
unknown **notification** is logged and dropped — the correct treatment for each, and impossible to
get right without the distinction.

### Payloads are not decoded here

The envelope codec routes an **opaque** payload. Only the handler registered for a method knows the
shape. So the envelope codec never grows a branch, a subsystem's wire format stays private to it,
and a large payload can be routed — or refused — without being parsed.

---

## 8. `MessageRouter` — the switchboard

**ELI5:** a phone switchboard. "Method name in, handler out." It also remembers who's waiting for an
answer.

```java
router.onRequest("fs/read",       (payload, respond) -> respond.ok(readFile(payload)));
router.onNotify ("ui/event",       payload -> dispatchToWidget(payload));

router.request  ("fs/read", path, onOk, onError);
router.notify   ("ui/stateDelta", delta);
```

**Nothing enumerates the set.** That's the property that makes adding a message one edit instead of
four. The workspace registers `fs.*`, a session registers `ui/*`, a script runtime in `language/`
registers `script/*` **without `core` ever learning it exists**.

It owns: correlation (request id ↔ response), the pending map, exactly-once responding, per-request
deadlines, cancellation, and `failAllPending` when the link drops.

### Unknown methods are answered, not dropped

| | |
|---|---|
| Unknown **request** | answered `protocol/methodNotFound` |
| Unknown **notification** | logged **once per method name**, then discarded |

They differ because the shapes differ: somebody is waiting on the first, nobody on the second.
Logging once rather than per message matters because the common cause is *a peer one version ahead
sending something at frame rate*.

The full error set: `protocol/methodNotFound`, `protocol/handlerFailed`, `protocol/timeout`,
`protocol/cancelled`.

### Replying is a callback, not a return value

```java
interface Responder<T> { void ok(T payload); void fail(String error); }
```

Because a handler may want to work off-thread and reply later. **Exactly one of these, exactly once**
— the router enforces it; later calls are ignored and logged.

---

## 9. `ProtocolConnection` — one peer's end

**ELI5:** the phone line to one specific person. A router, a transport underneath it, and a name for
who's on the other side.

```java
connection.peer();        // the platform's handle — EntityPlayerMP, or null on a client
connection.ops();         // PlainOps.INSTANCE, in practice
connection.router();      // the switchboard, for anything the conveniences don't cover
connection.tick();        // ← pump the wire, drain what arrived, expire what timed out
```

### Why per-connection and not a global singleton

CustomNPC+'s `PacketHandler` maps a packet type to a handler and *can* be a singleton, because a
packet carries everything needed to act on it. **A router cannot**: it holds pending requests
correlated to *one peer*, a stream-id space, credit, and timeouts. Two players sharing a router would
collide on ids the first time both had a call in flight.

So: the **registry** is global (§10), and what it registers are contributors invoked **once per
connection**.

### `tick()` does all three steps, deliberately

Pump the wire → drain the mailbox → expire timeouts. One call, because a subsystem that had to
remember to pump its transport separately **would receive nothing, silently** — the exact failure
shape this codebase keeps paying for elsewhere.

Default call timeout: **10 seconds**.

---

## 10. `Protocols` — where a subsystem says "I speak part of this"

**ELI5:** a sign-up sheet. Subsystems put their name on it once at startup. Every time a new phone
line opens, everyone on the sheet gets wired into it.

```java
// ONCE, at mod init -- sided at the call site, and a lambda:
Protocols.server("workspace", connection ->
        new WorkspaceBinding<>(service, hub, actorFor(connection.peer()), connection.peer(),
                connection.ops()).installOn(connection));

// PER CONNECTION, wherever a peer appears:
ProtocolConnection<Object> connection =
        Protocols.open(transport, PlainOps.INSTANCE, wire::pump, player);
```

**Adds, never replaces** — so registration order doesn't matter and two subsystems can't evict each
other. A subsystem registers exactly once, at init, and never thinks about connections again.

`Protocols.contributors()` returns what has registered — diagnostics, and the answer to "is my
subsystem actually wired".

### Method names are namespaced

`ui/*`, `command/*`, `script/*` — LSP's `textDocument/hover` convention.

> The workspace is **`fs/`** — `fs/read`, `fs/write`, `fs/list` — and reads the same way `ui/` does.
> It used to be `fs.` with a dot, which was an honest inconsistency and is now gone; the `Protocols`
> javadoc's `workspace/read` example names a method that has never existed and is the last trace of a
> third spelling. Nothing parses the separator, so this was only ever a grepping problem.

---

# PART THREE — HOW A CONNECTION IS ESTABLISHED

This is the part that's invisible from any single class. Nothing here is clever; it's just spread
across three files.

## 11. The sequence, start to finish

### Step 0 — registration, at mod init (once per process)

Order matters, and `CommonProxy.init()` states it:

```java
Mc1710NetworkChannel.register();   // 1. the channel exists and fills the CgPlatform slot
CgUiWorkspaceHost.register();      // 2. contributors sign the sheet
CgUiConnections.register();        // 3. the lifecycle starts watching for peers
```

**Contributors before connections.** Nothing depends on it *today* — no peer can exist at init, so
both orders bind the same set — but a contributor is only bound to connections opened **after** it
registers. This is the order that stays correct if anything ever opens one earlier.

> **Why `init` and not `preInit`:** the channel registered at preInit once and **no packet was ever
> delivered, in either direction**, with every gate reporting healthy. CustomNPC+ builds its handler
> at preInit and calls `registerChannels()` from `FMLInitializationEvent`. That was the one
> structural difference from a mod that demonstrably works.

`CgUiConnections.register()` then does two things:

```java
channel.setInboundHandler(CgUiConnections::route);   // frames now have somewhere to go
FMLCommonHandler.instance().bus().register(new Handler());   // watch for joins/leaves/ticks
```

### Step 1 — a peer appears

| | Opens on | Closes on | Ticks on |
|---|---|---|---|
| **Server** | `PlayerLoggedInEvent` | `PlayerLoggedOutEvent`, `FMLServerStoppingEvent` | `ServerTickEvent` |
| **Client** | `ClientConnectedToServerEvent` | `ClientDisconnectionFromServerEvent` | `ClientTickEvent` |

> A kick and a disconnect are the same event as a quit. FML doesn't distinguish them at this level
> and neither should we: what matters is that the peer is gone and **every caller waiting on a reply
> is told**, rather than waiting out a ten-second timeout for something that's never coming.

### Step 2 — `open()` builds the stack

```java
private static Peer open(CgNetworkChannel channel, boolean initiator, Object player) {
    FrameMultiplexer frames = new FrameMultiplexer(
            channel.maxFrameBytes(),
            initiator,
            player == null ? channel::sendToServer : frame -> channel.sendToPlayer(player, frame));

    WireTransport transport = new WireTransport(frames);

    ProtocolConnection<Object> connection =
            Protocols.open(transport, PlainOps.INSTANCE, transport::pump, player);

    return new Peer(frames, connection);
}
```

Four lines, and each one is a layer of §2 being stacked:

1. the multiplexer learns the ceiling, its stream-id parity, and *where to put a finished frame*;
2. the transport wraps it with the tree⇄bytes codec;
3. `Protocols.open` builds the router **and binds every contributor onto it**;
4. `Peer` keeps the two together, so closing one closes all of it.

> **The pump goes *in* here** rather than being left to a caller. That's what makes `tick()` the one
> call — a subsystem that forgot to pump would receive nothing, silently.

> **`initiator`** is `true` on the client, `false` on the server — the odd/even stream ids of §5.1.

### Step 3 — `Peer` is what the loader actually keeps

```java
private static final class Peer {
    final FrameMultiplexer frames;         // frames go IN here, from the Netty thread
    final ProtocolConnection<Object> connection;   // everything else comes OUT of here
}

private static final Map<Object, Peer> SERVER = new ConcurrentHashMap<>();  // keyed by player
private static volatile Peer client;                                        // exactly one
```

**Three threads, and the map is the only thing they share.** Frames arrive on Netty's thread; server
connections open/close/tick on the server thread; the client does all three on the client thread. So
the map is concurrent and *nothing else is* — `ProtocolConnection.tick()` is the only thing that
dispatches, and it's always called from the thread that owns whatever the handlers touch.

### Step 4 — inbound frames get routed

```java
private static void route(Object sender, byte[] frame) {     // NETTY THREAD
    Peer peer = (sender == null) ? client : SERVER.get(sender);
    if (peer != null) peer.frames.onFrameReceived(frame);    // enqueue only
}
```

> **A frame for a peer that has already gone is dropped, never used to open one.** A connection is
> created by a *lifecycle event*, never by traffic. Creating one here would resurrect a player who
> has left, and make a disconnect racy against whatever was still in flight.

### Step 5 — ticking

```java
@SubscribeEvent public void onServerTick(TickEvent.ServerTickEvent event) {
    if (event.phase != TickEvent.Phase.START) return;
    for (Peer peer : SERVER.values()) peer.connection.tick();
}
```

**On `Phase.START`**, so a message that arrived since the last tick is applied *before* the world
runs on it rather than a tick later. The UI's own `calculateStyle`-before-layout ordering is the
same rule one layer up: state that arrived this frame must reach its consumer before the consumer
runs.

### Step 6 — closing

```java
peer.connection.close(reason);   // fails EVERYTHING outstanding, immediately
```

Rather than letting each caller wait out its own timeout. *A peer that is gone is knowable now; ten
seconds of silence per pending call is not information.*

### How you get hold of one

```java
CgUiConnections.forPlayer(player);   // server side — null if they have none
CgUiConnections.client();            // client side — null when not in a world
CgUiConnections.openConnections();   // diagnostics; what a leak shows up in
CgUiConnections.isRegistered();      // did the lifecycle actually install?
```

> `isRegistered()` exists because **both** failure paths in `register()` are a `warn` and a `return`
> rather than a throw — an unavailable channel, or the raw transport probe owning it. A server with
> no networking at all boots perfectly happily and looks healthy. `CgUiServerSmoke` is what turns
> that into an exit code.

---

# PART FOUR — DEFINING A PACKET CONTRACT

This is the part you'll actually do. **There is no packet class, no discriminator byte, no registry
of ids.** A contract is: *a name, a payload shape, a handler on one side, a caller on the other.*

## 12. The recipe

### 1. Put the names in one file

```java
public final class FsMethods {
    public static final String READ = "fs/read";
    public static final String WRITE = "fs/write";
}
```

> **Both ends read these constants rather than spelling the strings twice.** A protocol whose two
> halves each type `"etag"` by hand is one typo away from a silent mismatch that presents as a
> *conflict loop* — which looks like a filesystem bug, not a networking one.

### 2. Put each payload in one record, with a codec

```java
public record ReadRequest(String path, String ifNoneMatch) { }

public record ReadResponse(String etag, byte[] content, boolean unchanged,
                           String transfer, long size) { }

public static Codec<ReadRequest> readRequest() { … }
```

> **This is the step the workspace added and `ui/*` deliberately did not.** Keys written by hand on
> both sides are the same typo risk one level down, and a record plus a codec makes a field written on
> one side provably the field read on the other. `ui/*` stays untyped because it is an **open**
> vocabulary a mod extends — a codec per mod message is a registration this stack exists to remove.

### 3. Server side — register a handler

```java
registry.register(FsMethods.READ, (args, respond) -> {
    ReadRequest ask = FsMessages.readRequest().decode(ops, args.encode());
    ...
    respond.ok(FsMessages.readResponse().encode(ops, answer));
});
```

### 4. Client side — call it, and get a `Reply`

```java
workspace.files().read(resource)
        .then(content -> { … })
        .onError(failure -> { … });     // a code and a detail, never a String alone
```

> **`Reply<T>` is the one async shape**, and its continuation runs on the **frame thread** — which is
> what lets a `then` touch the tree without a hop. A `Stream<T>` is its many-answers twin, for a paged
> listing or a chunked read.

That's the whole contract. **No fourth file to edit.**

### `StateMap` — the payload's readable face

A small typed key/value bag over any `DynamicOps`:

```java
out.putString("k", v);  out.putInt(...);  putFloat  putBool  putBytes  putEnum  putList
in.getString("k", fallback);              // every getter takes a fallback
out.putStringIfNot("k", v, "");           // omitted when it equals the default
```

**Every getter taking a fallback is the versioning story.** A field added later decodes against an
older sender without a version bump, and a default-valued record writes an *empty* map that gets
dropped entirely.

---

## 13. A real contract, both halves side by side — `fs/read`

This one is worth reading in full because it shows every technique at once.

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
        respond.ok(new StateMap<T>(args.ops())
                .putBool(UNCHANGED, true).putString(ETAG, entry.etag()));
        return;
    }

    // 3. SMALL ENOUGH — send it inline.
    WorkspaceService.FileContent content = service.read(actor, target);
    if (content.content().length <= INLINE_MAX_BYTES) {
        respond.ok(new StateMap<T>(args.ops())
                .putBytes(CONTENT, content.content()).putString(ETAG, content.etag()));
        return;
    }

    // 4. TOO BIG — open a chunked transfer and tell the client to pull it.
    ...
}));
```

### The client half (`FileOperations`)

```java
public Reply<FileContent> read(Resource file) {
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

**Four lessons in one method:**

1. **The reply says what shape it is.** `unchanged` / inline / `chunked` are flags on the response,
   not three different methods. A caller cannot tell which happened, and *that is the point* — the
   threshold is the server's, so a client must never assume one.
2. **Chunked reads are client-driven (pull, not push).** A push would work — the transport has
   credit flow control. Pull was chosen because the client decides the pace (so a UI can pause or
   abandon a download with no cancel protocol) and **a resume is the same request with a different
   offset**, rather than a new mechanism. HTTP range requests are the same shape for the same reason.
3. **"Too big for one message" is a fact, not a policy.** The transport bounds a single reassembled
   message, so a large file *cannot* arrive whole however patient anyone is.
4. **The client keeps the etag so the UI doesn't have to.** Forgetting that is how an editor ends up
   either never detecting a conflict or reporting one on every save.

### Errors are codes, never stack traces

```java
// server
catch (CgFileSystemException e)      -> respond.fail(FsError.of(e.error().name(), …));
catch (WorkspaceConflictException e) -> respond.fail(FsError.conflict(actualEtag));
catch (RuntimeException e)           -> respond.fail(FsError.of("UNKNOWN", …));
```

So a client **branches on a value** rather than matching message text — and an unexpected exception
is reported as `UNKNOWN` rather than leaking a server-side message that may name a directory.

> **An `FsError` carries fields, not only a code**, and the conflict is why: a stale write is refused
> with **the etag the file actually holds**, so the client can re-read, merge and offer the user a
> choice rather than a dead end. A code alone would leave it with nothing to act on.

### Who you are is bound at construction, never sent

```java
new WorkspaceBinding<>(service, hub, actorFor(peer), peer, ops)
```

> One connection is one player, so the actor is bound at bind time rather than travelling in each
> call. **A client that could name its own actor could name somebody else's.**

---

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

> **It is a notification now**, and it used to be a request. The interface was called `Notifier` and
> its method `notify`, and it was wired to `connection.call(...)` — a **Request** (`q`), which the
> client dutifully answered `ok(null)`. So every change cost a round trip's worth of envelopes for an
> answer nobody read. It worked correctly and the naming said something the wire did not; `fs/changed`
> is an `n` today. `PushIsANotificationTest` is the standing form of that.

Note the ordering inside the handler: **the cache is dropped before the callback**, so a handler that
re-reads the file isn't served the stale bytes.

---

# PART FIVE — THE TENANTS

Three subsystems ride a `ProtocolConnection`. They're peers of each other; none is privileged.

## 15. The UI — `ui/*`

| Method | Kind | Direction |
|---|---|---|
| `ui/openWindow` | notification | S→C |
| `ui/description` | **request** | C→S — answered with the tree |
| `ui/stateDelta` | notification | S→C |
| `ui/treeOps` | notification | S→C — an ordered edit script: `insert` / `remove` / `move` |
| `ui/closeWindow` | notification | S→C — "this window is finished" |
| `ui/close` | notification | **C→S — "the user closed it"** |
| `ui/requestClose` | **request** | S→C — "may I?", answered by the content |
| `ui/requestOpen` | **request** | C→S — "give me a window of this type"; the answer is only *whether* |
| `ui/focusWindow` | notification | S→C — "bring it forward" |
| `ui/visibility` | notification | C→S — "it is / is not on screen" |
| `ui/view` | notification | S→C — a command about the *window* rather than the tree (title, icon, notify) |
| `ui/sheet` | **request** | C→S — answered with a stylesheet, by hash |
| `ui/event` | notification | C→S |
| `ui/rows` | **request** | C→S — "I am looking at rows `[from, to)`"; the answer is the **count** |

> **`ui/close` is the newest of these by years, and its absence was not a missing feature.** Minecraft
> has had the equivalent since alpha — `C0DPacketCloseWindow` → `processCloseWindow` →
> `closeContainer`, and `ServerboundContainerClosePacket` → `doCloseContainer` on 1.20 — because a
> server holding a window's model needs to know when nobody is looking at it. Without it a closed
> window left its session open, observing its tree and flushing state deltas into a frame that had
> been destroyed, and the only close anything ever noticed was the player disconnecting.

**Every `ui/*` payload carries `w`, the window id** — in the *payload*, not the envelope, because
it's a fact about the UI protocol and the envelope isn't allowed to know one.

> LDLib2 resolves incoming packets against *"whatever menu the player has open"*, so a packet in
> flight when a GUI closes lands on the **next** one. Four bytes makes that impossible.

### `ServerWindows` / `ClientWindows` — a window's lifetime

**The layer above the sessions, and the one you actually use.** A session is the *protocol* for one
window; a host is what opens one, ticks it, and ends it — the id allocation, the `bind`-then-`open`
ordering, the per-tick validity sweep and flush, and all four ways a window can end.

```java
WindowProtocol.register();                                  // once, at init
ServerWindows.of(connection).open(new MyWindow(model));   // whenever you have something to show
```

It is ported from `ServerPlayer.openMenu` (both MC versions): allocate the next id, construct, tell
the client with the **type** and the **title**, start observing, tick with a `stillValid` check. Two
divergences, both deliberate:

- **Many windows per connection.** `openMenu` force-closes the previous container; CrystalOS is built
  for several at once. Uniqueness is per **key** instead — opening under a key that is already open
  brings the existing window forward, which is Minecraft's rule narrowed from "any window" to "the
  same subject", and keeps its scroll position and whatever is half-typed in it.
- **Close is a request.** The frame asks, the client decides, then tells the server via `ui/close`.

On the client, `ClientWindows` adopts each session as it arrives and hands the rebuilt tree to a
**`WindowMount`** — the one thing a platform implements (on 1.7.10, a `WindowFrame` on the desktop).
Local behaviour is looked up **by window type**, which is `MenuScreens.register` — with one
improvement over it: **a window whose type nothing registered still mounts and still works**, because
a description is self-sufficient where a `MenuType` is only a key to code.

### `ServerUiSession` — owns the tree, never lays it out

**There is no `UIDocument` anywhere in it.** That absence *is* the headless story, structurally rather
than by flag: no window → no Taffy tree → no style engine → no layout → **no path into text
measurement**, which is the one thing that genuinely needs a font stack.

It implements `UITreeObserver`, so it's told when elements attach, detach or go state-dirty, and
coalesces those into **one `ui/stateDelta` per `tick()`**.

### `ClientUiSession` — rebuilds, and caches

Keyed by **content hash**, so cache invalidation doesn't exist as a problem: a changed UI is simply a
different key, and a stale entry can never be served. **A UI the client has opened before costs one
`ui/openWindow` packet and nothing else, whatever its size.**

### `ClientUiSessions` vs a bare `ClientUiSession`

One window → construct a `ClientUiSession` directly. More than one → `ClientUiSessions.forConnection`.

**They're mutually exclusive**, and for a precise reason: both bind `ui/openWindow`, and `ui/openWindow`
is the one message that **cannot be routed by window id — it is what announces the id**. So a single
owner per connection has to hand out the per-window sessions. The router refuses the second binder.

### `UiWindowMux` — dispatch by window *as well as* method

`MessageRouter` keys handlers by method name alone and **refuses a duplicate** — which made one UI
session per connection a structural fact. Every UI message already carried the window id and every
session already *re-checked* it on the way in, so the id was being **verified by a handler that could
only ever be one**. The mux turns that check into the lookup it always wanted to be.

> It's a layer *above* the router rather than a change *to* it, because `MessageRouter` is the
> generic vocabulary — the workspace and a future `script/*` bind to it and have **no window to be
> keyed by**. Teaching the router about `w` would put one subsystem's payload shape into the layer
> every subsystem shares. Same split `FrameMultiplexer` already makes a layer down: *the generic
> thing carries ids, and the thing that knows what an id means sits on top.*

**A UI message with no window id is refused (request) or dropped with one warning (notification) —
never delivered to "the only window".** A fallback that's correct with one window and silently wrong
with two is worse than the limit it replaces, because it fails exactly when the feature starts being
used.

### The UI's supporting cast

| | |
|---|---|
| `UIElementTreeSource` | Element ids live in a table the source owns, allocated on first sight and **kept for the life of the source** — so an id survives a sibling insert, a reparent and a detach. An id that moves is not a name, and a message in flight names an element. |
| `ServerTreeMirror` / `ClientTreeMirror` | The edit script, **generic in the node type** and naming no widget, no session and no transport. The server half *produces* payloads rather than sending them, which is what lets one window fan out to viewers with different visibility without this class knowing viewers exist. |
| `ContentHash` | SHA-256 of a canonical encoding. Map keys sorted, type tags and counts written before each container, so two structurally different trees can't collide. This is what makes re-opening free. |
| `UIElementMirror` | `{tag, id?, class[]?, style{}?, flags?, focus?, state{}?, children[]?}`. Children are `describedChildren()`, which a widget with structural light children overrides. **Throws on an unknown tag** — a styleless div where a slider should be is worse than a refusal. |
| `SheetRef` | `(hash, id?)` — one shape covering four cases: client has the theme (nothing transfers), version skew (fetch), datapack-only theme (fetch), generated sheet (hash is the whole identity). |
| `WidgetContract` | What a KIND of widget is: its state slots **in apply order**, the events it can report, and whether a description may carry children. One declaration, four readers — and there is deliberately **no kind vocabulary class**, because a closed set of four strings is a list a third party cannot add to. |

> **Two entries here used to say the opposite, and both are worth knowing.** Ids were *"a depth-first
> position, computed on both sides, never transmitted"* — which made inserting an element renumber
> everything after it, and is the defect the whole mirror came out of. And `UiEventKinds` was that
> closed set; it went at M3 when events became typed constants a widget declares for itself.

## 16. The workspace — `fs/*`

`FsMethods` (names) · `FsMessages` (every payload, as a record with a codec) · `FsError` ·
`WorkspaceBinding` (server) · `Workspace` (client).

`WorkspaceBinding.installOn` takes either a `ProtocolConnection` — which registers every method **and**
attaches the binding, so `ServerScope.workspace()` can find it — or a bare `Registrar`, a functional
interface satisfied by both `MessageRouter::onRequest` and `ServerUiSession::onCall`. So binding the
workspace does not depend on which of them a host happens to hold, and a test can install onto a bare
registry without standing up a session.

The vocabulary: `fs/hello`, `fs/projects`, `fs/capabilities`, `fs/list`, `fs/stat`, `fs/read`,
`fs/readChunk`, `fs/write`, `fs/writeDelta`, `fs/create`, `fs/mkdir`, `fs/rename`, `fs/copy`,
`fs/delete`, `fs/watch`, `fs/unwatch`, `fs/changed`, `fs/presence`, `fs/trashList`, `fs/restore`,
`fs/purge`.

> **Every payload is a record with a codec** (`FsMessages`), which is the difference from `ui/*` and is
> deliberate: a file protocol is a fixed vocabulary two halves have to agree on field by field, so a
> field written on one side is provably the field read on the other. `ui/*` is the opposite — an open
> vocabulary a mod extends — and typing it would mean a codec per mod message.

> **`fs/list` is paged**, answering `{entries, cursor}`: a listing of a large directory arrives in
> pages rather than as one message that may not fit, which is what the transport's reassembly cap is
> there to refuse.

> The primer used to say there was deliberately **no `fs/list` or `fs/stat`** — that a manifest *is* a
> listing carrying the etags, and a second method would be one query answered twice. The reasoning was
> sound and the conclusion did not survive paging: `fs/list` IS that manifest, renamed, with a cursor.

## 17. Commands — `command/*`

`command/contribute`, `command/withdraw`, `command/setEnabled` (all S→C notifications) and
`command/invoke` (C→S **request**).

> **The direction of each is the design.** Everything *about* a command flows server→client, because
> the server is the only one that knows. The single thing flowing back is **"the user did it"** — and
> that's a request, because a command can fail and the person who pressed the key deserves to be told.
>
> Enablement is **pushed, not asked**. A client that had to ask "may I?" as the menu opened would put
> a round trip inside a UI gesture.

---

# PART SIX — THE 1.7.10 WIRING

## 18. `Mc1710NetworkChannel` — the whole of 1.7.10 networking

```java
private static final String CHANNEL = "crystalgui";      // ≤20 chars — a hard ceiling
private static final int MAX_FRAME_BYTES = 32_766;
```

**An event-driven channel, not `SimpleNetworkWrapper`.** The first version used
`SimpleNetworkWrapper` with an `IMessage` and a discriminator. It registered without error and
dispatched through a live `NetworkDispatcher` — everything on the send path verified in a running
client — and the handler **never fired, silently, in either direction.**

`newEventDrivenChannel` is what CustomNPC+ and most long-lived 1.7.10 mods use, and it's the better
fit regardless: `SimpleNetworkWrapper` exists to marshal *typed* messages, matching a discriminator
byte to a class and instantiating it reflectively — **and we want none of that.** The frame's
structure is decided in `core` and every byte is ours. An event channel hands over a raw `ByteBuf` in
both directions, which is exactly this contract.

### Three traps this class is a monument to

**1. `setTarget` is not optional.**

```java
FMLProxyPacket packet = new FMLProxyPacket(Unpooled.copiedBuffer(frame), CHANNEL);
packet.setTarget(target);     // ← without this, single player silently drops everything
```

`new FMLProxyPacket(ByteBuf, String)` leaves `target` null.

- **Remote connection** — the packet is serialised and the receiver rebuilds it with a constructor
  that sets `target` itself. A null target never survives the wire, so it never matters.
- **Single player** — the connection is local and the *same object* arrives by reference.
  `processPacket` does `getChannel(channel, this.target)` with a null side, finds no channel, and
  **drops the packet with no log and no exception.**

Every send succeeded, the dispatcher was live, the channel was registered on both sides, and nothing
was ever received. That cost a day.

**2. `copiedBuffer`, not `wrappedBuffer`.** FML keeps the buffer past the call; wrapping would alias
an array the caller is free to reuse.

**3. Report the *smaller* ceiling.** One channel serves both directions, and over-reporting fails
inside Forge mid-send with the connection already committed.

> **FML does not fragment for us.** `FrameMultiplexer` does it instead, once, for every platform.

## 19. `CgUiConnections` — the lifecycle

Covered in full in §11. In one line: **it owns the `Peer` map, opens one per player on join, routes
inbound frames to the right multiplexer, ticks every connection on `Phase.START`, and closes them on
leave.**

## 20. `CgUiWorkspaceHost` — the server actually serving files

```java
Protocols.server("workspace", CgUiWorkspaceHost::bindWorkspace);
```

> **`Contributor` is a lambda over `ProtocolConnection<Object>`.** It used to be a generic method —
> `<T> void bind(ProtocolConnection<T>)` — and every contributor that ever existed immediately cast
> to `Object` with a `@SuppressWarnings`, so the genericity bought an anonymous class per mod and
> nothing else. The one unchecked cast now lives inside `Protocols.open`, sound by the ops
> discipline: every `StateMap` takes its ops from `connection.ops()`. And `Protocols.server` /
> `Protocols.client` put the side in the method name instead of a `peer() == null` guard every
> contributor had to open with.

**Files live on the server's machine, and single player is not a special case.** The root is
`<serverdir>/crystalgui/workspace` via `MinecraftServer.getFile(...)` — the server directory on a
dedicated server, the game directory in single player, **because the integrated server is a server.**

> One code path, and single player is the remote case with a very short wire. That's what makes it
> testable at all: a bug that only appears when the two halves are genuinely apart would otherwise
> wait for a dedicated server to find it.

**One `WorkspaceBinding` per connection**, because the actor is per player — sharing one would
authorise every request as whoever connected first. It holds that peer's audit and its idempotency
table, and its entry in the **one** `WatchHub`: the hub is per SERVER, so a path four peers are
watching is stat-ed once a tick rather than four times.

**A client connection must not host a workspace:**

```java
if (connection.peer() == null) return;   // a client is the CONSUMER
```

Without this, a single-player process would serve itself **from its own client end as well** — both
ends answering `fs.*` on one wire, with whichever registered first winning.

---

# PART SEVEN — PUTTING IT TOGETHER

## 21. Follow one packet all the way down and back

A user clicks a button in a server-built GUI.

```
CLIENT                                                                    SERVER
──────                                                                    ──────
Button.attachListener fires
  ↓
ClientUiSession.report(element, "activate")
  builds StateMap { w: 7001, nid: 3, kind: "activate" }
  ↓
router.notify("ui/event", payload)          [7] which subsystem
  ↓
EnvelopeCodec.encode → { k:"n", m:"ui/event", p:{…} }   [3] the grammar
  ↓
WireTransport.send → BinaryFormat.encode → byte[]        [2] encoding
  ↓
FrameMultiplexer.send                                    [1] chunking
  · picks an odd stream id (the client is the initiator)
  · splits into ≤32,766-byte frames, last one FLAG_FIN
  · spends credit; queues the rest if the window is empty
  ↓  (next pump)
CgNetworkChannel.sendToServer(frame)                     [0] the road
  ↓
    ═══════════ FMLProxyPacket, target=SERVER, channel "crystalgui" ═══════════
                                                                            ↓
                                    Mc1710NetworkChannel.onServerPacket   [0]
                                      (NETTY THREAD — extracts byte[])
                                                                            ↓
                                    CgUiConnections.route(player, frame)
                                      SERVER.get(player).frames.onFrameReceived
                                      ↳ ENQUEUE ONLY. Nothing else happens here.
                                                                            ↓
                                    ─── tick boundary ────────────────────────
                                    ServerTickEvent, Phase.START, SERVER THREAD
                                                                            ↓
                                    connection.tick()                      [5]
                                      1. transport::pump  → FrameMultiplexer.pump
                                         · reassembles frames until FIN     [1]
                                         · BinaryFormat.decode → tree       [2]
                                      2. drain mailbox → router.accept      [4]
                                      3. expire timeouts
                                                                            ↓
                                    MessageRouter: "ui/event" → handler    [4]
                                                                            ↓
                                    UiWindowMux: w=7001 → this session     [6]
                                                                            ↓
                                    ServerUiSession: nid=3 → NetworkIds.find
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

**Note what never happened:** no pixel, no colour, no layout crossed. The client drew a tree it did
not build, from widget classes it already had.

## 22. Who ticks what, and on which thread

| Thing | Called by | Thread | If you forget |
|---|---|---|---|
| `channel.setInboundHandler` | once, at init | — | Frames arrive and go nowhere |
| `FrameMultiplexer.onFrameReceived` | the channel adapter | **Netty** | — (it only enqueues) |
| `ProtocolConnection.tick()` | the loader, per tick | server / client thread | **Silence.** Nothing arrives, nothing sends, nothing times out |
| `ProtocolConnection.onTick` hooks | `tick()`, after the drain | server / client thread | Whatever registered one stops running. `ServerWindows` is one |
| `ServerUiSession.tick()` | **`ServerWindows`**, per tick | server thread | Session stays live, answers calls, **never sends another state update** |
| `ClientUiSession.tick()` | nobody needs to | client thread | Nothing — it's a genuine no-op while riding a connection |

> ⚠️ **The two session `tick()`s are not symmetric, and they read alike.** `ClientUiSession.tick()`
> returns immediately when riding a connection — the connection already drained the mailbox for every
> subsystem on it. But `ServerUiSession.tick()` **still flushes**, because it is the observer holding
> that tick's dirty set and *nothing else knows the set exists*.
>
> **You no longer call either.** `ServerWindows` ticks every window on a connection from that
> connection's own `onTick` hook, which is most of the reason a mod stopped needing a tick handler at
> all. Forgetting the server's used to be a live session that answered calls and silently never sent
> another state update; it is now unforgettable.

**The one rule underneath all of this:** everything above `onFrameReceived` runs on the thread that
owns the tree. `Property` and `SignalBase` are single-threaded by documented contract, so touching an
element from the network thread isn't a race to tune — it's a correctness bug.

## 23. Words people mix up

| These look alike | …and are not |
|---|---|
| **Frame** / **Message** / **Envelope** | Transport chunk / one payload / the grammar wrapper. Three layers |
| **Transport** / **Connection** | A pipe / a pipe with a router, an identity, and pending state on it |
| **Connection** / **Session** | One phone line / one conversation on it. Several sessions share a connection |
| **`ProtocolConnection.tick()`** / **`Session.tick()`** | Moves bytes and dispatches / flushes this subsystem's own pending work |
| **Request** / **Notification** | Somebody is waiting / nobody is. Decides how an unknown method is treated |
| **Network id** / **CSS id** | A depth-first position the protocol addresses by / a string the cascade matches. **Unrelated** |
| **`ClientUiSession`** / **`ClientUiSessions`** | One window / the owner that hands them out. **Mutually exclusive on one connection** |
| **`ServerUiSession`** / **`ServerWindow`** | The protocol for one window / the thing you *write*. A window has a session; you rarely touch it |
| **`ui/closeWindow`** / **`ui/close`** | Server→client, "this is finished" / client→server, "the user closed it". **Different directions, and the second is newer than the first by years** |
| **hidden** / **closed** | Retained, detached, `ui/visibility false`, comes back / gone, session ended, `onClosed` fired |
| **`window.key()`** / **`window.type()`** | *Which* window (dedup + geometry) / *what kind* (client behaviour lookup) |
| **Stream error** / **Connection error** | Reset one stream, carry on / the peer isn't speaking the protocol |
| **`ui/`** / **`fs.`** | Same idea, different separator. An inconsistency, not a rule |

## 24. Cheat sheet

```java
// ── Speak a new protocol ──────────────────────────────────────────────────
Protocols.contribute("mything", new Protocols.Contributor() {
    @Override public <T> void bind(ProtocolConnection<T> c) {
        c.onRequest("mything.doIt", (args, respond) -> respond.ok(result));
        c.onNotify ("mything/tick", args -> apply(args));
    }
});

// ── Get a connection ──────────────────────────────────────────────────────
ProtocolConnection<Object> c = CgUiConnections.forPlayer(player);   // server
ProtocolConnection<Object> c = CgUiConnections.client();            // client

// ── Talk ──────────────────────────────────────────────────────────────────
c.call("mything.doIt", args, onResult, onError);      // request — you get an answer
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

    @Override public void bound()                { /* widget listeners */ }  // CLIENT, every bind
    @Override public void client(ClientScope io) { /* onCall/onNotify  */ }  // CLIENT, once
    @Override public void closed(String why)     { }   // both sides: SERVER/CLIENT/NOT_VALID/CONNECTION_LOST
}

// ── Receive a UI ──────────────────────────────────────────────────────────
// NOTHING. The open names the panel class on the wire; the engine initialises
// it (guarded: it must be a Networked) and runs the client half. A window whose
// binding fails still renders and reports its events -- only the local extras
// go quiet, loudly, in the log.

// once per platform, not per mod — CgUiScreen does this on 1.7.10:
ClientWindows.of(c).setMount(myWindowMount);
```

## 25. Where to look next

| | |
|---|---|
| `com.crystalgui.app.machine` | All of this, runnable. **Start here** — and `docs/CGUI_BUILDING_UIS.md` is its written half |
| `docs/CGUI_SERVER_AND_SERIALIZATION.md` | The same ground as a reference: codecs, hashing, the headless contract |
| `core/src/headlessTest/` | CrystalGraphics deliberately absent. If it loads here, it runs on a server |
| `mc1710/…/CgUiSessionProbe` | The whole stack against a real MC connection, as a ten-point checklist |
| `mc1710/…/CgUiTwoClientProbe` | Two clients on one dedicated server: a writer edits, a watcher reports what reached it. Everything the watcher, presence and the conflict path exist for is a claim about a SECOND client |
| `plan_ui_host.md` | Why the layer above the sessions exists: the audit that produced it, and the seventeen findings |
| `./gradlew :mc1710:serverSmoke` | Boots a dedicated server, asserts the stack came up, stops. ~48s |

---

## Appendix — the constants, in one place

| | |
|---|---|
| Frame layout | `[u8 opcode][u8 flags][varint streamId][payload]` |
| Opcodes | `DATA 0x01` · `WINDOW_UPDATE 0x02` · `RESET 0x03` |
| Flags | `FIN 0x01` |
| Stream 0 | the connection itself; never carries `DATA` |
| Envelope kinds | `q` request · `r` response · `n` notification · `x` cancel |
| Envelope fields | `k` kind · `i` id · `m` method · `p` payload · `e` error |
| `EnvelopeCodec.VERSION` | `1` |
| Default flow-control window | 256 KB |
| Default call timeout | 10 s |
| 1.7.10 channel name | `crystalgui` (≤20 chars, hard limit) |
| Frame ceilings | 32,766 / 2,097,050 (1.7.10) · 32,767 / 1,048,576 (1.20.x) |
| Protocol errors | `protocol/methodNotFound` · `handlerFailed` · `timeout` · `cancelled` |
| UI window-id key | `w`, in the payload — never the envelope |
