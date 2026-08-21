# The wire engine — packet transport for Phase 4

**Research and design, 2026-08-21.** Phase 4 needs to move a lot of traffic — descriptions, state deltas,
RPC, and whole files — between a client and a server, across two Minecraft generations with different
networking APIs. This is the design decision for how, and it is a one-way door: every packet in the
system inherits it.

**The requirement, stated once:** packets are **owned by `core/` and defined once**. A loader must not be
able to re-implement, re-order, or re-frame them. Whatever a platform contributes has to be small enough
that getting it wrong is obvious.

---

## 1. What was measured, not assumed

### The size limits are asymmetric, and the asymmetry is structural

Read from the decompiled sources rather than from documentation:

| Direction | 1.7.10 | 1.20.1 | Why |
|---|---|---|---|
| **Server → client** | **2,097,050** (`S3FPacketCustomPayload`, `0x1FFF9A`) | **1,048,576** (`ClientboundCustomPayloadPacket`) | Forge extends 1.7.10's with `readVarShort` |
| **Client → server** | **32,766** (`C17PacketCustomPayload`, throws at `>= 32767`) | **32,767** (`ServerboundCustomPayloadPacket`) | Vanilla writes the length as a **signed short**, both eras |

Two conclusions fall straight out:

- **The client→server ceiling is effectively identical on both versions (~32 KB)** and is not a Forge
  decision — it is a signed short in the vanilla packet format. Nothing will raise it.
- **That is the direction that carries file saves.** The workspace is server-hosted, so the client sends
  edits *upward*. The tight limit is on the side that matters most.

### Nothing splits packets for us

FML's `FMLRuntimeCodec` does not fragment. (Its only `Splitter` is Guava splitting log lines — checked,
because it looks like exactly what you would hope for.) The wider modding ecosystem confirms it: the
Forge forums carry a steady stream of *"Payload may not be larger than N bytes"* crashes, and the
community answer is always the same — **split it yourself and reassemble**.

### The outbound queue is unbounded, and it is shared with the whole game

`NetworkManager.outboundPacketsQueue` is a `Queues.newConcurrentLinkedQueue()`. There is no bound and no
back-pressure. A naive sender that pushes a 10 MB file as 300 chunks in one tick will balloon heap and
**head-of-line block the entire connection** — chat, movement, chunk loading, everything, because it is
one TCP connection shared with the game.

> **This is the single most important finding.** It means flow control is not an optimisation for later;
> a transport without it is a mod that degrades the server it runs on.

---

## 2. The shape: three layers, and the platform gets the thinnest

```
core/net/            THE PROTOCOL   UIPacket, UIPacketCodec, Server/ClientUiSession, RpcRegistry   [exists]
core/net/wire/       THE ENGINE     framing · streams · fragmentation · flow control · cancel      [new]
platform SPI         THE PIPE       "send one byte[]" · "here is one byte[]" · "my max frame is N"  [~50 lines per loader]
```

**Everything interesting is in the middle layer, and the middle layer is `core/`.** That is the whole
answer to "packets owned by core": a loader never sees a `UIPacket`, never learns a stream id, never
decides a chunk size. It moves opaque byte arrays and reports its own ceiling.

### The platform SPI, in full

```java
public interface CgNetworkChannel {
    /** The platform's own per-frame ceiling. The engine sizes fragments from this, never a constant. */
    int maxOutboundFrameBytes();

    void sendToServer(byte[] frame);
    void sendToPlayer(Object player, byte[] frame);

    /** Called on the network thread. The engine queues; it never dispatches from here. */
    void setInboundHandler(BiConsumer<Object /*sender*/, byte[]> handler);
}
```

`maxOutboundFrameBytes()` being **reported rather than hardcoded** is what makes the measured asymmetry a
non-issue: 1.7.10 server-side answers ~2 MB, every client answers ~32 KB, and 1.20.1 answers 1 MB
server-side. The engine takes `min(reported, its own ceiling)` and never needs a version check.

### Yes — depend on the platform's networking handler

Use `SimpleNetworkWrapper` on 1.7.10 and `PayloadRegistrar` / `SimpleChannel` / Fabric's networking on
1.20.x, rather than attaching raw Netty handlers. Reasons, in order:

1. **The handshake and channel registration are already solved per loader**, including "is this player's
   client running our mod" — which is the thing that is genuinely painful to reimplement and easy to get
   subtly wrong.
2. **Compression, encryption and the login/play phase split** are handled below us.
3. **Raw Netty means fighting the loader's own pipeline**, and it differs by version anyway — so it costs
   *more* per-platform code, not less.

The cost of depending on them is that each has a different registration call. That is exactly what a
~50-line adapter absorbs, and it is the only thing per-loader networking code should contain.

---

## 3. The engine — ported patterns, not invented ones

Each of the four mechanisms below is taken from a protocol that solved this in production. None of it is
novel, and that is deliberate.

### 3.1 Message framing — length-prefix + opcode

MC already delivers one custom payload as one discrete `byte[]`, so **transport framing is free** — we do
not need Netty's `LengthFieldBasedFrameDecoder`. What we need is a *message* header inside that frame:

```
[u8 opcode][u32 streamId][u8 flags][payload…]
```

Nine bytes of overhead per frame. `flags` carries `FIN` (see 3.3) and little else at v1.

### 3.2 Stream multiplexing — HTTP/2's core idea

**This is the mechanism that makes the whole thing usable.** Without it, one 5 MB file read blocks every
UI event and RPC behind it, and the editor visibly freezes while a file opens. With stream ids, a large
transfer and a 200-byte RPC interleave.

RFC 9113 states the rationale exactly, and it is our situation:

> *Using streams for multiplexing introduces contention over use of the TCP connection, resulting in
> blocked streams. A flow-control scheme ensures that streams on the same connection do not destructively
> interfere with each other.*

**Our case is worse than HTTP/2's**, and worth stating plainly: HTTP/2 streams contend only with each
other. Ours contend with **Minecraft** — chat, movement, block updates, chunk loads — on the same
connection. A greedy transfer does not degrade our feature, it degrades the game.

### 3.3 Fragmentation — WebSocket's FIN + continuation, not chunk indices

A large message becomes N frames on one stream, each carrying the same `streamId`, with `FIN` set on the
last.

**No chunk index and no chunk count**, which is the simplification MC's transport hands us for free:
delivery is a single ordered, reliable TCP connection, so fragments cannot arrive out of order and cannot
go missing without the connection dying. Indices would encode information that is already guaranteed. A
UDP protocol could not make this choice; we can, and should, because the alternative invites a reassembly
buffer that reorders — code that can never be exercised and therefore never be correct.

> The receiver still needs a **cap on in-flight reassembly per stream and in total**, or a hostile or
> buggy peer pins the heap by opening streams and never finishing them.

### 3.4 Flow control — credit windows, because the queue is unbounded

Per-stream and per-connection windows, HTTP/2's `WINDOW_UPDATE` shape: the receiver advertises a byte
budget, the sender decrements as it emits, the receiver replenishes as it consumes. HTTP/2 starts streams
at 65,535 bytes; our numbers should start smaller and be tuned against a real server.

This is the direct answer to the unbounded `outboundPacketsQueue`. It is also the piece most likely to be
skipped as "we can add it later" — and later means after a user has reported the game stuttering when
someone saves a file, which will not read as a networking bug.

### 3.5 Cancellation — `RST_STREAM`

Close a tab or navigate away mid-transfer and the stream is abandoned, both sides releasing reassembly
state. Cheap to add now, structurally awkward to retrofit, because every buffer needs an owner that knows
how to drop it.

---

## 4. What goes on the wire

`UITransport<T>` is generic over the session's `DynamicOps` representation — it carries `T`, **not bytes**.
`Mc1710Workspace` runs `InMemoryTransport<Object>` over `PlainOps`, a live Java object tree. The two
`DynamicOps` that exist are `PlainOps` (objects) and `JsonOps` (text). **Neither is a wire format.**

**Recommendation: add a binary `DynamicOps`.** `DynamicOps` already declares `createBytes` / `getBytesValue`,
so byte payloads were designed in — P6.1.10 lists them among its one-way doors, already shipped. JSON
would work and is tempting for debuggability, but an IDE protocol carrying file contents and syntax data
is exactly the payload where a text encoding costs most, and it interacts badly with a 32 KB
client→server ceiling.

Keep `JsonOps` as the inspection path. It is what makes a capture readable, and `ContentHash` already
depends on canonical encoding being ops-independent.

### The seam barely changes

The existing session-facing contract stays as it is:

- Sessions keep `UITransport<T>` — unchanged, still encoding before handing over, so *"every implementation
  exercises the real codec on every hop"* remains true.
- The wire engine sits **below** it and presents `UITransport<byte[]>`.
- `InMemoryTransport<Object>` keeps working for existing tests; a byte-flavoured in-memory transport lets
  the engine itself be tested headlessly.

That last point matters more than it looks: **the entire engine — framing, multiplexing, fragmentation,
flow control, cancellation — sits above the platform SPI and is therefore testable with no Minecraft and
no GL.** P6.1.10 already called `InMemoryTransport` *"the workhorse … the single biggest reason this
design is worth its ceremony."* This extends that property to the hardest code in Phase 4.

---

## 5. Threading

Unchanged from what `UITransport` already documents, and worth restating because it is the easiest thing
to break:

> *The receiver may be called from any thread. It is a mailbox, not a dispatcher: a session queues what
> arrives and processes it from `tick()` on the thread that owns the tree.*

Inbound lands on the network thread → enqueue → drain on the game thread. `Property` and `SignalBase` are
single-threaded by documented contract, so touching elements from a network thread *"is not a race to be
tuned but a correctness bug."* This session already paid for that lesson once elsewhere: a signal emitted
from a worker reached `invalidateStyleMatch()` and threw out of `advanceFrame` with nothing about the
originating feature in the stack trace.

Flow-control accounting is the one thing that must be thread-safe rather than game-thread-only, since
credits are consumed by the sender and replenished by the drain.

---

## 6. Open questions — answered by the build

Kept with their answers rather than deleted, because three of the four were decided by *choosing* and
one was decided by *measuring*, and which is which is the useful part.

1. **Initial window and fragment size.** `DEFAULT_WINDOW_BYTES = 256 KB`; a fragment is
   `maxFrameBytes − headerSize(Integer.MAX_VALUE)`, reserving the **worst-case** varint header so no
   frame can exceed the ceiling whatever stream id it lands on. **Chosen, not measured** — the question
   asked for measurement against a real server under load and that has not happened. 256 KB is four
   times HTTP/2's 65,535 because a description is a single burst rather than a stream, and the credit
   window is what stops it reaching an unbounded queue shared with the whole game. Revisit with numbers,
   not with taste.
2. **One channel.** `"crystalgui"`, registered by the adapter. No second lane: stream round-robin inside
   one connection already prevents head-of-line blocking between messages, which was the only thing a
   second channel would have bought. `aSmallMessageIsNotBlockedBehindALargeOne` is the property.
3. **Version agreement rides the first play-phase exchange, not login.** `EnvelopeCodec.VERSION` travels
   in the `ui/openWindow` payload and the client refuses a window whose protocol it does not speak,
   logging both numbers. Login-phase negotiation was not taken because it differs per loader — it is one
   of the few things `CgNetworkChannel` would have had to grow a method for, and the whole design of that
   seam is that it does not learn what a message means. The cost is that a mismatch is discovered per
   window rather than per connection, which for a UI is the same moment.
4. **Yes, the server pushes unprompted** — `ui/openWindow` and every `ui/stateDelta` are server-initiated
   with nothing asking for them. So the server needs its own credit accounting per client, and has it:
   `FrameMultiplexer` is symmetric, one instance per peer, each with its own window. The `initiator` flag
   splits the stream-id space (odd/even, as HTTP/2 does) and changes nothing else.

---

## Sources

- [RFC 9113 — HTTP/2](https://httpwg.org/specs/rfc9113.html) — stream multiplexing, `WINDOW_UPDATE`
  credit flow control, and the contention rationale quoted in §3.2
- [High Performance Browser Networking — HTTP/2](https://hpbn.co/http2/) — flow-control window defaults
- [Bigger Packets, Please! — Minecraft Forum](https://www.minecraftforum.net/forums/mapping-and-modding-java-edition/minecraft-mods/2941319-bigger-packets-please-fighting-2097152-as-a-limit)
- [Forge forums — "Payload may not be larger than 1048576 bytes"](https://forums.minecraftforge.net/topic/119432-payload-may-not-be-larger-than-1048576-bytes/)
- [YABBA #119 — "Payload may not be larger than 32767 bytes"](https://github.com/LatvianModder/YABBA/issues/119)
- [WCF chunking channel sample](https://learn.microsoft.com/dotnet/framework/wcf/samples/chunking-channel) —
  the "split, send, reconstitute, either in the app or in a custom channel" framing
- In-repo: `build/rfg/minecraft-src/java/net/minecraft/network/**` (1.7.10) and
  `research_repos/mc1201_sources/net/minecraft/network/**` (1.20.1)

---

# Part 2 — the protocol layer

**Researched and designed 2026-08-21.** Part 1 moves bytes. This is the layer above it: what a message
*is*, how it is addressed, and who handles it. The prototype works end to end in a client, and its
protocol layer is the part that will not survive contact with a real feature set.

## The problem, measured rather than felt

Adding one message type today means editing **four** places:

| # | File | What you add |
|---|---|---|
| 1 | `UIPacket.java` | a record in the sealed union |
| 2 | `UIPacketCodec.encode` | a branch |
| 3 | `UIPacketCodec.decode` | a branch |
| 4 | every session's `handle(UIPacket)` | an `instanceof` arm |

That is textbook shotgun surgery, and it gets worse rather than better: the union and both switches are
shared by every subsystem, so the workspace, the script runtime and the UI all edit the same three files
and conflict with each other. `ServerUiSession.handle` is already an `instanceof` chain over five types
and it has exactly one feature in it.

**The codebase already knows the answer**, one layer up: `RpcRegistry.register(method, handler)` and
`ServerUiSession.on(element, kind, handler)` are both open registries keyed by a string. Nothing central
enumerates the methods or the event kinds, and adding one touches only the code that owns it. The packet
layer is the only place that is still a closed union — and it is the layer where a closed union hurts
most, because it is shared by everything.

## What the references actually do

### VS Code — `vs/base/parts/ipc` (MIT, portable)

Two interfaces and a name registry:

```ts
IChannel        call<T>(command: string, arg?, cancellationToken?): Promise<T>
                listen<T>(event: string, arg?): Event<T>
IServerChannel  // the same, plus a context parameter
```

**There is no message union.** There are four request kinds and five response kinds — `Promise`,
`PromiseCancel`, `EventListen`, `EventDispose`; `Initialize`, `PromiseSuccess`, `PromiseError`,
`PromiseErrorObj`, `EventFire` — and everything else is a **command name**. Correlation is a numeric
request id against a `Map<number, IHandler>`.

Three details worth stealing:

- **`ChannelServer` holds `Map<string, IServerChannel>`**, and a request names its channel. A subsystem
  registers one channel; nothing central lists them.
- **A request for a channel that has not registered yet is held in `pendingRequests` and retried**, with
  a timeout. That is a registration race solved once, in the framework, rather than by every caller
  ordering its startup carefully.
- **The transport is genuinely swappable** — the docs state the abstraction directly: *"in web or remote
  scenarios the transport layer changes to WebSocket or MessagePort, but the channel abstraction stays
  identical."* Which is the same seam `CgNetworkChannel` already is.

`ProxyChannel.fromService` / `toService` go further, turning a plain interface into a channel by
reflection. **Deliberately not copied** — see "what we are not taking".

### LSP / JSON-RPC 2.0 — the closest match to what we carry

VS Code's own IDE protocol, and shaped like ours: an editor talking to a peer about documents.

- **Request vs notification is a first-class distinction.** *"Every processed request must send a response
  back … notifications don't require responses."* Our `UIPacket` mixes both with no way to tell: a
  `StateDelta` is a notification and an `RpcCall` is a request, and only the handler knows which.
- **Methods are namespaced strings** — `textDocument/hover`, `workspace/symbol`. A prefix is all the
  "channel" an IDE protocol needs.
- **Progress rides a token separate from the request id**, which *"allows reporting progress out of band
  and also for notifications"*. Directly relevant: D11 chunked transfer and
  `CrystalGUI_P6.1.13_PROGRESS_PLAN.md` both need progress on work that is not one request/response pair.
- **Partial results on cancellation** — a cancelled request may still have produced something useful.

### IntelliJ — RD, and why it is the wrong model here

JetBrains' Rider protocol is not RPC at all: it is a reactive graph of distributed properties and signals
that synchronise across the boundary. Elegant, and a poor fit — it assumes both ends share a generated
model and a code generator to keep them in step. Our two ends are a mod jar and the same mod jar, but the
protocol has to stay legible without a codegen step, and a server must be able to talk to a client one
version behind it. Recorded because "IntelliJ does it differently" is worth knowing, not because it is a
lead.

## The design

### The envelope is closed. The vocabulary is open.

That distinction is the whole change. A closed set is right for the **grammar** and wrong for the
**words**:

```
REQUEST       id, method, payload
RESPONSE      id, ok, payload | error
NOTIFICATION      method, payload
CANCEL        id
```

Four kinds, and they are meant to stay four. Everything that is today a `UIPacket` subtype becomes a
**method name** on a REQUEST or a NOTIFICATION:

| today | becomes |
|---|---|
| `OpenWindow`, `Description`, `CloseWindow` | `ui/openWindow`, `ui/description`, `ui/closeWindow` — notifications |
| `RequestDescription` | `ui/description` — a **request**, which is what it always was |
| `StateDelta` | `ui/stateDelta` — a notification |
| `UiEvent` | `ui/event` — a notification |
| `RpcCall` / `RpcResult` | the REQUEST/RESPONSE envelope itself, which is what they were re-implementing |

`RpcCall`/`RpcResult` disappearing is the tell that this is the right shape: they exist today because the
union has no general request/response, so RPC had to build its own correlation on top. With the envelope
carrying it, `RpcRegistry`'s id bookkeeping is the framework's job and its handler map is the router's.

### One router, keyed by method

```java
router.onRequest("workspace/read",   (ctx, payload) -> ...);   // must answer
router.onNotify ("ui/event",         (ctx, payload) -> ...);   // must not
```

Registration lives beside the code that owns the method. The workspace registers `workspace/*` from
`WorkspaceService`; the UI registers `ui/*` from the session; a script runtime registers `script/*` from
`language/` without `core` learning it exists. **Nothing enumerates the set** — which is the property
`RpcRegistry` already has and the packet union does not.

An unknown method is answered, not dropped: a REQUEST gets a `METHOD_NOT_FOUND` response and a
NOTIFICATION is logged once. Today an unrecognised packet falls off the end of an `instanceof` chain.

### Payloads stay opaque until a handler claims them

The envelope codec reads `id`, `method`, `kind` and hands the payload over **undecoded**. Only the
registered handler knows the shape, and it decodes with its own `Codec`. So:

- `UIPacketCodec`'s two switches collapse into one envelope codec that never grows.
- A subsystem's wire format is private to that subsystem.
- A large payload can be skipped without being parsed — which matters when the router is deciding whether
  to route a 10 MB file body at all.

### What this deletes

| gone | replaced by |
|---|---|
| `UIPacket` (9 records, sealed) | a 4-kind envelope |
| `UIPacketCodec` encode/decode switches | one envelope codec + per-handler codecs |
| `ServerUiSession.handle` instanceof chain | `router.dispatch(envelope)` |
| `ClientUiSession.handle` instanceof chain | the same router |
| `RpcRegistry`'s correlation | the envelope's `id` |

Adding a message goes from four edits to one registration.

## What we are not taking, and why

- **`ProxyChannel`'s reflective interface-to-channel mapping.** It is the most impressive part of VS
  Code's design and the least portable: it leans on JS `Proxy` and duck-typed `on[A-Z]` property naming.
  The Java equivalent is dynamic proxies plus an annotation, which trades an explicit registration line
  for a reflection layer that is worse to debug and hostile to the Java 8 bytecode target. Explicit
  registration is three words longer and always readable.
- **A code generator.** RD needs one; LSP does not, and neither do we.
- **Events as a separate primitive** (`listen`/`EventFire`/`EventDispose`). VS Code needs it because a
  renderer subscribes to main-process events. Ours are all fan-out from the server to one client, which a
  NOTIFICATION already is. Adding a subscription lifecycle before anything needs one is speculative.

## Sequencing

The wire layer is finished and proven; **none of this touches it.** `FrameMultiplexer` moves opaque byte
arrays and does not know an envelope from a packet, which is exactly why this can be rewritten under the
sessions without re-testing the transport.

1. The envelope + its codec, headless.
2. The router, headless.
3. Port `ServerUiSession` / `ClientUiSession` onto it, deleting both `instanceof` chains.
4. Retire `UIPacket` and `RpcRegistry`.
5. Re-run the in-game probe — the transport is unchanged, so this confirms rather than explores.

## Open questions

1. **Do notifications need ordering guarantees across methods?** The transport gives per-stream ordering,
   but a `ui/stateDelta` and a `ui/closeWindow` on different streams can be reordered. Probably wants
   one stream per logical channel rather than per message.
2. **Where does progress live?** LSP's out-of-band token is the right shape, but it interacts with
   D11 chunked transfer and P6.1.13's progress UI, and those should be designed together.
3. **Version negotiation.** `UIPacketCodec.PROTOCOL_VERSION` exists and nothing negotiates it. With
   method names, a mismatch degrades naturally to `METHOD_NOT_FOUND` — which may be better than a
   version gate, and is worth deciding rather than inheriting.

## Sources

- [VS Code `vs/base/parts/ipc/common/ipc.ts`](https://github.com/microsoft/vscode/blob/main/src/vs/base/parts/ipc/common/ipc.ts) — `IChannel`/`IServerChannel`, request/response kinds, channel registry, `ProxyChannel`
- [LSP 3.17 specification](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/) — request vs notification, namespaced methods, progress tokens, partial results
- [vscode-languageserver-node](https://github.com/microsoft/vscode-languageserver-node) — the reference handler-registration implementation
- [Understanding VS Code's IPC Architecture](https://www.besthub.dev/articles/understanding-vs-code-s-ipc-architecture-and-channel-mechanism-69a50eb94a89) and [IPC Decoded](https://roopik.com/blog/vscode-internals-advanced-ipc) — the transport-swappability point
