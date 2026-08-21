# Pre-Phase 4 — the platform-deferred backlog

Phase 4 is named in [`plan_m12.md`](plan_m12.md) and is **sketch only, not designed**. Before it can be
scoped there is a backlog to settle: work across the P6 family that was deferred *because there was no
Minecraft platform in the build*. There is one now (M12 Phases 1–3), so those deferrals are due.

**This file is that backlog, and nothing else.** It does not decide Phase 4. It exists so that when Phase
4 is decided, it is decided against what is actually true rather than against what the plans say.

> **Scope rule, set 2026-08-21:** everything here is **mc1710 only**. mc1201 work waits until mc1710 is
> finished and then comes with that platform. Items that are inherently LWJGL3/mc1201 are listed in
> [the parking lot](#parking-lot--mc1201-only) rather than silently reordered into the queue.

> **Ordering rule:** least trivial first. The substantial items are at the top because they are the ones
> whose answers change what Phase 4 can be; the small ones do not.

---

## Provenance, and what survived contact with the code

Every row below was traced to the plan that deferred it **and** checked against the tree, because two of
the four claims turned out to be stale. That check is the point of this document — the plans are older
than the platform they were waiting for.

| Claim, as written in the plans | Verified state |
|---|---|
| §5.1: the seam shape is "proven four times over (`CgUiInputAdapter`, `UIClipboard`, `UISoundSystem`, `UICursorService`)" | ❌ **stale — all four classes are gone.** The shape is now `CgPlatform` plus the seven interfaces in `platform/service/`, and for consumer-owned contracts the `CgService<T>` slot M12's audit added |
| §5.1: "interface in `core/`, `NOOP` default, loader registers the real one" | ❌ **stale twice.** The SPI lives in CrystalGraphics' `platform/`, not `core/`; and `AGENTS.md` is explicit that **no method in that SPI has a default** and neither sound nor cursor ships a `NOOP` — *"inheriting a no-op is indistinguishable from deciding on one"* |
| P6_TODO: "**The per-loader `CgFileSystem` is the gap that matters**: until it exists none of this runs in-game" | ❌ **stale.** `Mc1710Workspace` runs `WorkspaceService` over `LocalFileSystem` with a real `ServerUiSession`/`ClientUiSession` pair. The protocol already runs in-game. The gap is the **transport**, not the filesystem |
| P6.1.10: "**1.7.10 unverified** … its custom-payload size limit must be checked before chunk sizing freezes" | ✅ **still true, and now answerable.** No payload constant or chunking exists anywhere in `net/` |
| `CrystalGUI_TODO.md` §1.2 → 5.1: platform-delegated tooltips "not designed yet, deliberately — there was no loader to validate against" | ⚠️ **true but moot — struck.** No hook exists, and no *consumer* does either: zero `ItemStack` references in `core/src/main` or `mc1710/src`, so there are no item slots for it to serve. See item 1 |
| `CrystalGUI_TODO.md`: "no window-focus-loss cancel — there is no platform hook for it in this build" | ✅ **still true.** No focus-loss hook in `ui/input/` or in `platform/` |

---

## The queue — mc1710, least trivial first

### 1. Re-base and execute §5.1, the platform seam sweep

**From:** `CrystalGUI_TODO.md` §5.1, `DEFERRED (2026-07-29)` alongside P3.

Deferred for one stated reason, and it no longer holds:

> *none of it can be verified without a loader in the build … Designing a seam blind is how you get an
> interface nobody can implement, so the honest sequencing is either "accept unverified" or "do P3.2
> first".*

**Re-base first.** Two of §5.1's three premises are stale (see the table above). The section cannot be
executed as written — it names four classes that do not exist and a package the SPI does not live in.
Rewriting its shape paragraph against `CgPlatform` / `CgService<T>` is the first task, not a footnote.

Then **one** mc1710-reachable item, not two — see the strike below.

- **`ChatComponent`-equivalent, translatable text.** The original reason §5.1 existed, and it has a real
  consumer: **there is no i18n mechanism in the tree at all**, and the chrome is hardcoded English
  (`"File"`, `"Delete"`, and the rest). Verified — the only `translat*` matches in `core/` are geometry.

- ~~**Platform-delegated tooltips**~~ — **struck 2026-08-21, and not because it duplicates `Tooltip`.**
  It genuinely does not: ours is a CSS-styled widget, while this is *an item's real MC tooltip* — rarity
  colour, enchantments, lore, durability, and the lines other mods inject through `ItemTooltipEvent`.
  That is platform data drawn by platform code with third-party hooks in it, and no widget reproduces it.

  It is struck because **it has no consumer**. Its own deferral note explains why — *"item slots are
  platform-unique"* — and there is no item-slot widget anywhere: zero `ItemStack` references across
  `core/src/main` and `mc1710/src`, and none in a near plan. Building it now would be designing an
  interface for a caller that does not exist, which is the same mistake §5.1 was deferred to avoid,
  approached from the other end. **Revive it when something renders an item, not before.**

**Watch for:** translatable text is the first new seam since `CgService<T>` shipped. Whether it is a slot
or a tenth bundle method is a real decision, and M12's Finding 1 already worked out the rule — *"closed
for what the framework requires; slots for what its consumers require."* A host with no translation table
must also be able to say so, and the SPI has no defaults by design.

### 2. ~~Measure the 1.7.10 custom-payload limit, and freeze chunk sizing on it~~ ✅ **DONE (2026-08-21)**

> **32,766 client→server**, read from `C17PacketCustomPayload` (vanilla throws at `>= 32767`) and
> `S3FPacketCustomPayload` (2,097,050 the other way, which Forge widens with `readVarShort`). Frozen
> not as a constant but as `CgNetworkChannel.maxFrameBytes()` — **asked for, never assumed** — because
> the four real limits across two eras differ and a hardcoded number is wrong on three of them.
> `FrameMultiplexerTest.everyPlatformCeilingCarriesTheSameMessagesIntact` runs the engine at all four
> plus 128 B and 4 MB. This unblocks D11 chunked transfer (Phase 4 B3).

**From:** `CrystalGUI_P6.1.10_FILESYSTEM_PLAN.md` §Minecraft; repeated in `CrystalGUI_P6_TODO.md`.

> *All read from `research_repos/mc1201_sources/`. Its equivalents and — more importantly — its
> custom-payload size limit must be checked before chunk sizing freezes.*

Every number in the file transfer design is currently derived from **1.20.1 sources**. On a real 1.7.10
client this is now a measurement rather than a research question.

**Gates:** D11 chunked transfer + manifest resolve, which `CrystalGUI_P6.1.13_PROGRESS_PLAN.md` lists as
*"Deferred; protocol shape reserved. Hard cap 100 MB"*. It is also a direct input to whatever Phase 4
turns out to be, which is why it sits above the small items rather than with them.

**Note:** the *protocol shape* is reserved, so this is a number to establish and a sizing decision to
freeze — not a redesign.

### 3. Window-focus-loss cancel

**From:** `CrystalGUI_TODO.md` — *"no window-focus-loss cancel — there is no platform hook for it in this
build."*

Small: one signal from the loader, consumed by whatever should abandon a drag or dismiss a popover when
the game loses focus. Listed last of the live items because it is genuinely small, not because it is
unimportant — a drag that survives alt-tab is a real defect.

### 4. Correct the stale claims in the source plans

Bookkeeping, and cheap. The three ❌ rows above should be struck through in the plans that carry them,
the way M12's audit strikes its own rejected item, so the next reader does not re-derive a gap that has
been closed. Specifically: P6_TODO's "the per-loader `CgFileSystem` is the gap that matters", and §5.1's
shape paragraph.

---

## Explicitly not in this queue

Deferred for reasons that were **never** the platform, and listed here so they are not swept in by
association:

| Item | Why it was deferred |
|---|---|
| P3.1 two-session RPC soak | Validation, not enablement — and *"touches no Minecraft"* by its own description |
| `Show Difference` | Needs a Myers diff ported from VS Code's `common/diff/` |
| `fs.writeDelta`, `WatchService`, client cache, `fs.rename` / `fs.delete` | Additive protocol work; MVP whole-file writes are *correct*, not merely simple |
| `GradientControl` (P6.1.8 step 9) | Expensive, and no node in the near set needs it |

---

## Parking lot — mc1201 only

Not to be started until mc1710 is finished, per the scope rule.

| Item | From | Note |
|---|---|---|
| **LWJGL3/GLFW cursor service** | §5.1 | *"Much shorter than the LWJGL2 one: `glfwCreateStandardCursor` covers the resize set with no bitmaps."* Inherently LWJGL3 |
| **P3.2 — decide the mc1201 question** | `CrystalGUI_TODO.md` P3.2, `BLOCKED — needs a call from you` | Still a product call. Multi-day, with the `mods{}` / `shadowJar` double-declaration minefield in `CrystalGraphics/AGENTS.md`. It is the only thing that ever validates the distribution story end to end |
| Per-loader `CgFileSystem` ×3 (Forge / NeoForge / Fabric) | P6.1.10 | Follows whatever mc1710's transport settles on |

---

## What this queue tells us about Phase 4

Not a decision — an observation to carry into one. `Mc1710Workspace`'s own javadoc already names the next
step and its intended cost:

> *Both halves of a real workspace, **in the client process**. … every listing, read and write crosses
> `InMemoryTransport` as a real packet. Shortcutting that would make the later phase — the same client
> against a workspace on a dedicated server — **a rewrite rather than a transport swap**.*

So the in-game workspace is real today and runs the full protocol; what is absent is a Minecraft
custom-payload transport and a server-side host. Item 2 above is the measurement that transport's sizing
depends on, which is the one hard ordering constraint between this backlog and Phase 4.

Two lessons M12 recorded from Phase 3 apply to all of it, and both were earned rather than reasoned:

- **A client is an environment no test reproduces.** Build the gated in-game probe early rather than
  reasoning from source.
- **The compiler and the editor are different consumers of the same seam, and they fail apart.** Anything
  added to that seam wants asking twice.
