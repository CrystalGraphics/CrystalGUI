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

## 6. Open questions, for decision before building

1. **Initial window sizes and fragment size.** Needs measuring against a real server under load, not
   picking. HTTP/2's 65,535 is a starting reference, not an answer.
2. **One channel or several?** MC channel names are capped at 20 characters and one channel is
   conventional. A second channel is a coarse priority lane — worth considering only if measurement shows
   stream priority inside one channel is not enough.
3. **Login-phase negotiation.** `UIPacketCodec.PROTOCOL_VERSION` exists (`1`) but nothing negotiates it.
   The loaders each offer a login/handshake phase; deciding whether version agreement happens there or in
   the first play-phase exchange affects what a mismatched client sees.
4. **Does a dedicated server ever need to *push* a large payload unprompted**, or is everything
   request-response? Affects whether the server needs its own credit accounting per client.

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
