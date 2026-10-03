# `net` and `serialization` — the server layer

> Loads itself: Claude Code reads this file the first time an agent reads any file in this folder or below. Moved verbatim from [`AGENTS.md`](../../../../../../../AGENTS.md), which keeps the rules every session needs.

## Server layer — `serialization/` + `net/`

> **Full reference: `docs/CGUI_SERVER_AND_SERIALIZATION.md`.** Don't reverse-engineer it from classes.

A dedicated MC server builds a UI tree with **no CrystalGraphics present**, ships a description, and
talks to the client over RPC and bindings.

- **The engine under it is CrystalGraphics'** (`com.crystalgraphics.serialization` and `.net`, since
  net-migration N2): `CgCodec<A>`/`CgDynamicOps<T>`/`CgCodecs` (DFU-shaped), `CgPlainOps`, `CgStateMap`,
  `CgContentHash`, `CgBinaryFormat`; the transports `CgTransport`/`CgInMemoryTransport`/`CgWireTransport`;
  the four-kind `CgEnvelope`, `CgMessageRouter`, `CgCall`, `CgProtocolConnection` and `CgProtocols`; the
  multiplexed byte transport `CgFrameMultiplexer` over the `CgNetworkChannel` platform slot.
- **`serialization/`** — `JsonOps` (Gson, for debugging), and `serialization/style/`: `StyleValueCodecs`
  and `InlineStyleCodec`.
- **`net/`** — `ServerUiSession`, `ClientUiSession`, `UiWindowMux`, `SheetRef`; `net/mirror/` holds **the
  mirror** — `ServerTreeMirror`/`ClientTreeMirror` (generic in the node type, written against the `ui.dom`
  seam), the `NodeMirror` per-tree codec seam, `UIElementMirror` over today's tree, and `TreeOps` (the
  `insert`/`remove`/`move` vocabulary); `net/protocol/` holds the `UiMethods` vocabulary.

Three design facts worth knowing before you touch it:

1. **`ServerUiSession` holds no `UIDocument`.** That absence *is* the headless story, structurally
   rather than by flag: no window → no Taffy tree, no style engine, no layout → no path into text
   measurement, the one thing that genuinely needs a font stack.
2. **Descriptions are content-addressed.** `UIElementMirror` output must be byte-identical for the
   same tree, so field order is fixed, maps are insertion-ordered, and absent optionals are omitted
   rather than written null. `OpenWindow` carries the *hash*, not the description — re-opening a UI
   costs one small packet however large the tree.
3. **Every packet carries a window id.** Resolving against "whatever menu is open" lets a packet in
   flight when a GUI closes land on the *next* one; four bytes makes that impossible.

`UIElementMirror` encodes `{ tag, id?, class[]?, style{}?, flags?, focus?, state{}?, children[]? }`,
skips shadow parts (the constructor rebuilds them), and **throws on an unknown tag**.
