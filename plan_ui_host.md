# The UI host — a lifecycle engine for networked windows

**Status: Parts I–V SHIPPED 2026-08-27**, in seven commits (P0 → P4, docs, and the naming pass).
**Part VI is LIVE DESIGN** for a second, larger rewrite — notes accumulated as they are decided
rather than written up at the end, so nothing is re-derived. See Part V for what implementation
changed about the shipped design and for the findings the build itself turned up.

Written 2026-08-27, from an aggressive audit of the Machine example stack
(`core/src/main/java/com/crystalgui/example/machine/`, `mc1710/src/main/java/com/crystalgui/mc/example/`)
and of every other consumer of the session layer. The protocol underneath — envelopes, the router,
sessions, the mux, the wire — is sound and is not what this plan touches. What is missing is the layer
**above** it: today every mod that wants a networked UI re-implements session creation, ticking,
teardown, connection binding and window mounting by hand, from tick handlers, per player, per UI.
That layer is what MC's own `Container` pipeline, LDLib2's holders and every GUI framework since
`IGuiHandler` provide, and ours provides nothing.

The test that the design passes: **the Machine example's two loader classes shrink from ~380 lines of
lifecycle plumbing to a window class, one `open(...)` call, and one client registration line** — and
none of the audit findings below can be re-committed by a mod that uses the host, because the code
that contained them no longer belongs to the mod.

---

## Part I — The audit

### What is sound and stays

- The **contracts** (`onCall`/`call`, `onNotify`/`notify`, `session.on(element, kind, handler)`) and
  the four-kind envelope. The example teaches them well; nothing here changes their shape.
- The **session layer's internals**: content-addressed descriptions, derived network ids, the
  dirty-set flush, `UiWindowMux`'s `(method, window)` routing, `ClientUiSessions` owning
  `ui/openWindow`. All correct, all keep working underneath the host.
- The **thread discipline** (everything on the tick that drained the connection) and the trace lines
  that make it visible.

### The findings

Ordered by how badly they bite, not by where they live. **F1–F5 are the structural ones the user's
complaint names; F6–F14 fell out of reading the same code closely.**

#### F1 — Session creation is the mod's job, polled per player per tick

`MachineExample.onServerTick` walks `playerEntityList` every server tick, checks a name-keyed map,
and opens a `MachineServer` for anyone missing (`MachineExample.java:85-108`). That is one tick
handler, one map, one poll loop and one leave handler **per mod**. Twenty mods with GUIs on a
20-player server is twenty handlers walking the player list twenty times a second to answer a
question — *"did a peer appear?"* — that `CgUiConnections` already answers exactly once, at
`PlayerLoggedInEvent`. The engine has an event for "a connection opened" (`Protocols.open` binds
contributors there) and the example cannot use it, because contributors bind at connection open and
a mod's *UI* opens later, on demand. There is no seat for "open a window for this player **now**" —
so every mod builds the poll.

#### F2 — Half the close matrix does not exist, and the shipped example resurrects its own window

The user's question — *"what happens if a player closes a UI without leaving?"* — has a damning
answer: **nothing reaches the server, because no client→server close message exists in the
vocabulary at all.** `UiMethods` has `ui/closeWindow` (server→client) and no counterpart. MC has had
`C0DPacketCloseWindow` since alpha (`NetHandlerPlayServer.processCloseWindow` →
`player.closeContainer()`); we have the direction missing entirely.

And the example does worse than leak: pressing the frame's X **resurrects the window**. The frame
defaults to `DESTROY_ON_CLOSE`, `frame.destroy()` runs — and `MachineExampleClient.placeOnDesktop`
runs every client tick with `if (frame == null || frame.state() == WindowState.DESTROYED)`
(`MachineExampleClient.java:207`), sees the destroyed frame, and re-wraps the still-live session
root in a **new frame** with an attention flash. The server session was never told, is still open,
still flushing deltas. Close, in the shipped example, means "blink".

`onPlayerLeave` (`MachineExample.java:111-119`) is the one close path that works, and it is again a
per-mod handler for an engine-level event.

#### F3 — `bindToConnection` is forty lines of engine logic every client mod must copy

`MachineExampleClient.bindToConnection` (`:150-188`) hand-implements: connection identity tracking
(`connection == boundTo`), teardown on disconnect (drop the client, destroy the frame, reset the
progress tracer), the `ClientUiSessions.forConnection(...).onSession(...)` install — with the
comment admitting the trap: *"a host that installs it late has already missed a window, and the miss
is silent."* Every one of those lines is a race or a leak when a mod writes it slightly differently,
and every mod must write it. `Mc1710Workspace.client()` is the same forty lines for the workspace,
written independently, with its own rebind subtleties. Two subsystems, two hand-rolled copies of
"track the wire" — the third mod makes three.

#### F4 — Sessions are never told the connection died

`ProtocolConnection.close(reason)` does exactly one thing: `router.failAllPending(reason)`
(`ProtocolConnection.java:178-180`). There is no close hook. So:

- A `ServerUiSession` riding a closed connection stays open, keeps observing its tree, keeps
  encoding state deltas into a dead wire every tick until someone external remembers to `close()` it
  — which is precisely why `MachineExample` needs its leave handler.
- A `ClientUiSession` keeps its `root`, and `onWindowClosed` never fires; the mod polls
  `CgUiConnections.client() == null` to find out (F3's null branch).
- `ClientUiSessions`' per-window map, the mux's `(method, window)` claims, and every
  `WeakHashMap`-attached companion die only by GC, not by lifecycle.

The connection knows the one fact everything above it needs, and has no way to say it.

#### F5 — The peer is keyed by a mortal object: dying breaks every UI, silently

`CgUiConnections.SERVER` is keyed by the `EntityPlayerMP` **instance** (`CgUiConnections.java:193`),
and inbound routing looks the sender up by instance too (`Mc1710NetworkChannel.java:169` reads
`((NetHandlerPlayServer) event.handler).playerEntity`). But 1.7.10 **constructs a new
`EntityPlayerMP` on respawn**: `ServerConfigurationManager.respawnPlayer:482` builds
`entityplayermp1`, and `NetHandlerPlayServer.processClientStatus` re-points the handler at it
(`NetHandlerPlayServer.java:901,924`). After one death:

- **Inbound**: `route(newEntity, frame)` → `SERVER.get(newEntity)` → null → *every frame from that
  client is dropped*, forever. Every click in every CrystalGUI window goes dead.
- **Outbound still works** (the old entity retains its `playerNetServerHandler` reference), so state
  deltas keep arriving and the panel keeps animating — which makes it read as an input bug in the
  widget, not an identity bug in the map.
- `forPlayer(newEntity)` answers null, so `MachineExample`'s poll opens a **second** `MachineServer`
  for the same player the moment a fresh connection… no — worse: it never does, because the map is
  keyed by *name* there, so the stale session is retained and pointed at the old entity.

A dimension change via `respawnPlayer(…, dimension, conqueredEnd=true)` (the End exit) hits the same
path. The identity of a player-for-networking purposes is their **`GameProfile` UUID** (or the
`NetHandlerPlayServer`, which survives both) — never the entity.

Two aggravations, found while verifying:

- **Even cleanup leaks.** `onPlayerLeave` does `SERVER.remove(event.player)` and
  `CgUiWorkspaceHost.forget(event.player)` — but after a respawn the logout event carries the *new*
  entity while both maps hold the join-time one. Both removals miss silently: the connection, its
  multiplexer, the workspace binding and the presence entry all live for the rest of the server, and
  `openConnections()` climbs by one per died-then-quit player.
- **Outbound survives respawn only by accident.** FML's `PLAYER` target resolves
  `player.playerNetServerHandler.netManager` **at send time** (`FMLOutboundHandler:109`), and the
  stale entity happens to retain its handler reference. `CgUiConnections.open` captures the entity
  in the send closure (`frame -> channel.sendToPlayer(player, frame)`), so the fix must not merely
  re-key the map — the send path has to resolve the *live* target too.

#### F6 — `WINDOW_ID = 7001`, "any number both peers agree on", teaches a falsehood and invites a collision

The client never agrees on anything: it **learns** the id from `ui/openWindow`
(`ClientUiSessions.accept`). The constant only has to be unique *per connection* — and a hard-coded
static is exactly how two mods pick 7001 and the second `open()` throws
`"window 7001 already serves 'ui/description'"` out of `UiWindowMux` on a live server. MC allocates
(`nextContainerCounter`, `currentWindowId % 100 + 1` per player); nothing in our stack allocates,
so every mod invents a magic number and prays.

#### F7 — The client wraps **every** window in `MachineClient`, because nothing says what a window *is*

`ClientUiSessions.onSession` fires for every window on the connection, and `MachineExampleClient`
wraps each one (`:181-186`). The day a second mod opens a window on the same connection, its tree is
handed to `MachineClient`, which runs `querySelector("#ask-stats")` against a foreign tree — a
silent no-op if the ids miss, a **listener attached to somebody else's button** if they collide.
The protocol carries no window *type*: MC's open packet has carried one since 1.7.10
(`S2DPacketOpenWindow`'s inventory type; `ClientboundOpenScreenPacket`'s `MenuType`, which
`MenuScreens` dispatches on), LDLib2 routes on `MenuType` + registry id. We send a hash and a count.

#### F8 — Notifications cannot be window-scoped, so the example's own pattern breaks at two windows

The mux already routes notifications by `(method, window)` — `ServerUiSession.bindNotify` uses it
internally — but neither session exposes `notify`/`onNotify`. So the example teaches registering
`machine/announce` and `machine/heartbeat` on the **connection** (`MachineServer.java:249`,
`MachineClient.java:141`), and `MessageRouter` refuses duplicates
(`MessageRouter.java:97`) — so the second Machine window on one connection **throws at open**. The
"three contracts" table sends developers to the one registration surface that cannot survive the
multi-window world 5.7 built.

#### F9 — The server cannot title its own window

`"Machine control"` is hard-coded in `MachineExampleClient.java:224`. The server defines the whole
UI and cannot name the frame it lands in, set its icon, or give it a persistence key —
`WINDOW_KEY` is also client-side. MC's open packet carries the display name for a reason: the side
that opens the window is the side that knows what it is.

#### F10 — Sheet resolution is fictional, and an installed sheet is global

Two halves:

- `SheetRef` crosses the wire, and there is **no way to fetch the sheet behind it**. No `ui/sheet`
  request exists. The example resolves the ref from a constant in its own jar and its comment admits
  it: *"the one place this class is not the shape a mod would use."* A real host confronted with an
  unknown hash has nothing to call. (For mod-shipped UIs the jar is on both sides so this holds up;
  for anything server-authored it is a wall.)
- The sheet is added to **the one style engine** (`MachineExampleClient.java:220`), unscoped. The
  Machine sheet is politely class-namespaced; a server sheet with a bare `button { background: red }`
  restyles the editor, the taskbar and every other window on the desktop. There is no per-subtree
  sheet scoping in the engine.

Also latent: `sheetInstalled` is a static boolean that survives disconnects — correct only while
the sheet is a constant, wrong the moment refs differ per server.

#### F11 — "One X per connection" is spelled as three static `WeakHashMap`s

`UiWindowMux.of`, `ClientUiSessions.forConnection`, `WorkspaceClient.forConnection` — three
independent statics, each with the same javadoc explaining the same singleton-per-connection reason,
each relying on GC (not lifecycle) for cleanup, each a place a fourth copy will be pasted from. The
connection is the natural owner of things scoped to it.

#### F12 — The example conflates the model's tick with the session's

`MachineServer.tick()` advances the model, mirrors it, and flushes (`MachineServer.java:339-365`).
That teaches *"your machine runs inside your session"* — so the machine stops existing when the
viewer leaves, which is the opposite of the lesson the class javadoc claims (*"the machine has been
running whether or not anybody had the window open"*). A machine is world state (a TileEntity's
tick); a window is a view of it with a lifetime bounded by a viewer. The example fuses them because
there was nowhere else to put the per-tick flush — which is F1 again from the other side.

#### F13 — Hidden windows keep paying full freight

A minimised/hidden server frame is detached (hide-is-detach), and the server keeps streaming
`ui/stateDelta` into it every change. Harmless at Machine scale; real cost for a busy UI. MC has no
notion of this either (a container closes instead). Noted, not urgent — see Phase 4.

#### F14 — The wiring is hand-rolled in four more places

`MachineDemo`, `CgUiSessionProbe`, `CgUiWorkspaceScene`/`HarnessWorkspace`, and every headless test
fixture each rebuild the transport-pair + session + tick-loop arrangement by hand. Not bugs — but
each is a copy that drifts, and each becomes three lines once the host exists.

---

## Part II — Prior art (port, don't reinvent)

### Minecraft's own container pipeline — the primary source

Read from `mc1710/build/rfg/minecraft-src/` and `research_repos/mc1201_sources/`. Both versions,
same architecture, ~15 years in production:

| Concern | 1.7.10 | 1.20.1 | Ours today | Ours after |
|---|---|---|---|---|
| Open | `EntityPlayerMP.displayGUIChest` et al: `getNextWindowId()`, send `S2DPacketOpenWindow(id, **type**, **title**, …)`, set `openContainer`, `addCraftingToCrafters` | `ServerPlayer.openMenu(MenuProvider)`: close previous, `nextContainerCounter()`, send `ClientboundOpenScreenPacket(id, **MenuType**, **title**)`, `initMenu` (listener + synchronizer) | mod hand-constructs `ServerUiSession(7001, …)` from a tick poll | `host.open(window)` — id allocated, session built, `ui/openWindow` carries type/title/key |
| Id allocation | `currentWindowId % 100 + 1`, per player | `containerCounter % 100 + 1`, per player | a static constant | per-connection counter |
| Tick | `EntityPlayerMP.onUpdate`: `openContainer.detectAndSendChanges()`; `if (!canInteractWith) closeScreen()` | `ServerPlayer.tick`: `broadcastChanges()`; `if (!stillValid) closeContainer()` | mod calls `machine.tick()` from its own handler | host, from `connection.tick()`: `stillValid` sweep → `window.tick()` → `session.tick()` |
| Client closes | `C0DPacketCloseWindow` → `processCloseWindow` → `closeContainer()` → `onContainerClosed` | `ServerboundContainerClosePacket` → `doCloseContainer()` → `removed(player)` | **no message exists** | `ui/close` (C→S notification) → `window.onClosed(CLIENT)` |
| Server closes | `closeScreen()`: send `S2EPacketCloseWindow`, then `closeContainer()` | `closeContainer()`: send packet, then `removed` | `session.close(reason)` — works | `host.close(window, reason)` / `stillValid` false |
| Disconnect | logout path closes the container | `removed(player)` distinguishes `hasDisconnected()` | per-mod leave handler | `connection.onClosed` cascade closes every window both sides |
| Client construction | inventory type → hardcoded `GuiScreen` switch / `IGuiHandler` | `MenuType` → `MenuScreens` registry | every window wrapped by whoever subscribed | type id → `ClientWindows` registry; **unregistered types still mount** (the description is self-sufficient — our one genuine improvement over MC) |
| Validity | `Container.canInteractWith(player)` — abstract, usually a distance check | `stillValid(player)` | nothing | `ServerWindow.stillValid(viewer)`, default `true` |

Two deliberate divergences from MC, both already decided elsewhere in this repo:

1. **Many windows per connection.** MC force-closes the previous container on open; our desktop is
   built for several (5.7, the mux). Uniqueness is per **key**, not global: `open()` with a key
   already open re-delivers to the existing window instead of duplicating — MC's close-previous
   rule, narrowed to "the same subject".
2. **Close is a request** (`plan_windowing.md`): server frames default `DESTROY_ON_CLOSE` (a chest
   does not retain), and the content hash makes reopening one small packet — already recorded in
   `WindowPolicy`'s javadoc.

### LDLib2 — the holder pattern

`research_repos/LDLib2/.../gui/factory/`: `IContainerUIHolder { createUI(player); isStillValid(player) }`,
with per-context factories (`BlockUI`, `HeldItemUI`, `PlayerUIMenuType`'s registry) and a
`writeClientSideData`/`create` pair so the client rebuilds the holder deterministically. What we
take: the **holder as the unit a developer writes** — context in the constructor, UI from a method,
validity as a predicate. What we drop: the client-side reconstruction pair entirely — our client
rebuilds from the *description*, not from context + shared code, which is the whole point of the
description architecture (an old client renders a new panel).

---

## Part III — The design: `WindowProtocol`

> **The mental model in one sentence:** `Protocols` gave subsystems a seat at the connection;
> `WindowProtocol` gives *windows* a lifecycle on it. A mod writes a `ServerWindow`, calls
> `ServerWindows.of(connection).open(window)`, and registers (optionally) a client factory for its
> type. Everything else — the id, the session, the tick, the mount, every row of the close matrix —
> is the engine's.

All of it lives in `core/src/main/java/com/crystalgui/net/host/` and is headless-safe: no
`UIWindow`, no stylesheet parsing, no CrystalGraphics type outside the client mount SPI's
*implementations* (which live in loaders and the harness).

### III.0 — Three small additions to `ProtocolConnection` (prerequisites)

```java
// 1. A per-connection attachment registry — retires the three static WeakHashMaps (F11).
//    Created on first ask, dies with the connection, no GC dependence.
public <A> A attachment(Class<A> type, Function<ProtocolConnection<T>, A> factory);

// 2. A tick hook, run at the END of tick() after drain + timeouts — what drives the hosts.
//    "After drain" is load-bearing: a window handler runs against fully delivered input.
public void onTick(Runnable hook);

// 3. A close hook — the fact everything above the router needs and cannot get today (F4).
//    Runs once; a hook added after close runs immediately (same late-registrant rule
//    CgUiLifecycle.onInit already follows, for the same reason: the miss is silent).
public void onClosed(Consumer<String> hook);
```

`close(reason)` becomes: fail pending (as today), mark closed, run hooks. `tick()` on a closed
connection returns 0 and runs nothing. `UiWindowMux.of` and `ClientUiSessions.forConnection`
migrate to `attachment(...)` — mechanical, and each deletes a static.
**`WorkspaceClient.forConnection` deliberately does not migrate**: its memo is entangled with
`rebind`, which *moves* the client between connections and re-keys the map by hand — W11's working
reconnect machinery, not worth churning for symmetry. If it ever migrates, `attachment()` needs a
`putAttachment` hand-off for movers; deferred until something else needs one.

### III.1 — `ServerWindow`: the thing a developer writes (the holder, ported)

```java
/**
 * One networked window: the server-side unit a mod authors. Everything MC's Container and
 * LDLib2's IContainerUIHolder are, against a described tree instead of slots.
 *
 * The engine owns the lifecycle: construction of the ServerUiSession, the window id, open,
 * the per-tick sweep, and every close path. Subclasses own the tree and the behaviour.
 */
public abstract class ServerWindow {

    /** Namespaced type id — what the client's factory registry dispatches on. "crystalgui:machine". */
    public abstract String type();

    /** The frame's title, decided by the side that knows what the window is (F9). */
    public String title() { return type(); }

    /**
     * Uniqueness + persistence key for this viewer, or null for "always a new window".
     * open() with a key already open re-delivers to the existing window instead of duplicating —
     * MC's close-previous rule, narrowed to the same subject. Also the client frame's
     * WindowFrame.setKey, so the desktop restores its geometry.
     */
    @Nullable public String key() { return null; }

    /** Build the tree and register behaviour. Called once, by the host, before the session opens. */
    protected abstract void bind(ServerUiSession<Object> session);

    /** One world tick while open. Mirror model → widgets here; the host flushes after. */
    protected void tick() { }

    /**
     * MC's canInteractWith / LDLib2's isStillValid. Checked every tick by the host;
     * false closes the window with reason "no longer valid". Distance checks, block-still-there
     * checks. Default true: a window that is valid until closed.
     */
    protected boolean stillValid(@Nullable Object viewer) { return true; }

    /** Every way out funnels here, exactly once. */
    protected void onClosed(CloseReason reason) { }

    public enum CloseReason { SERVER, CLIENT, NOT_VALID, CONNECTION_LOST }

    // Provided by the host after open — session(), viewer(), notify()/onNotify() shorthands.
}
```

Notes:

- `bind(session)` replaces `MachineServer.open()`'s middle 150 lines; the handlers-before-open rule
  is enforced by construction, because the host calls `bind` before `session.open()`.
- **`notify`/`onNotify` land on the session**, window-scoped through the mux (F8). The connection
  pair stays for genuinely connection-scoped subsystems (workspace, script) — the docs teach:
  *window things on the session, connection things on the connection*, and now both pairs exist on
  both.
- The model does **not** live here (F12). A `ServerWindow` holds a reference to world state and
  mirrors it in `tick()`; the world state ticks wherever world state ticks.

### III.1b — No class required: the builder shape

The abstract class is right for anything with state worth naming. It must not be the *entry price* —
LDLib2's `BlockUI` is a `@FunctionalInterface` for exactly this reason. So `ServerWindow.of(…)`
builds one from lambdas, typed on the panel so the wiring lambda gets real fields rather than
`querySelector` strings:

```java
ServerWindows.of(connection).open(
    ServerWindow.of("crystalgui:machine", MachinePanel::new, panel -> panel.root)
        .key("crystalgui:machine")
        .title(panel -> "Machine control")
        .wire((panel, io) -> {                             // io = the session; before open, enforced by order
            io.onActivate(panel.purge, ctx -> machine.purge());
            io.on(panel.power, UiEventKinds.TOGGLE, ctx -> machine.setRunning(ctx.payload().getBool("checked", false)));
            io.onCall("machine/rename", (args, respond) -> { ... });
        })
        .tick((panel, io) -> mirror(machine, panel))       // per world tick, host flushes after
        .stillValid(viewer -> machine.exists())
        .onClosed(reason -> { ... }));
```

The builder *is* a `ServerWindow` underneath — same lifecycle, same close matrix, nothing forked.
The rule of thumb the docs carry: a window that is one screenful of handlers is a builder call
where it is opened; a window with a model reference, fragments, or state across ticks earns the
class. The client side is already classless: `ClientWindows.register(type, factory)` takes a lambda,
and a window whose local behaviour is a couple of listeners writes it inline there.

### III.2 — `ServerWindows`: one per connection, owns every window on it

```java
public final class ServerWindows {
    public static ServerWindows of(ProtocolConnection<Object> connection);  // attachment()

    /** Allocates the id, builds the session, binds, opens. Same key open → re-deliver + return existing. */
    public <W extends ServerWindow> W open(W window);

    public void close(ServerWindow window, String reason);
    @Nullable public ServerWindow byKey(String key);
    public List<ServerWindow> windows();
}
```

Internals, each pinned to a finding:

- **Id allocation** (F6): a per-connection `int nextWindowId = 1; nextWindowId++`. No mod ever
  names an id again; `MachineServer.WINDOW_ID` is deleted, and its "any number both peers agree on"
  javadoc — which taught a falsehood — with it.
- **Tick** (F1): installed once via `connection.onTick`. Per tick: sweep `stillValid` (close the
  failures with `NOT_VALID`), then `window.tick()` per window, then `session.tick()` per window —
  the order MC uses (`broadcastChanges` then validity in 1.20; ours checks validity first so a
  dead window's last tick is not flushed). `CgUiConnections`' existing per-connection tick loop is
  now the **only** server tick handler in the whole UI stack.
- **`ui/close`** (F2): the host registers the new C→S notification through the mux, per window:
  payload `{w}` → `window.onClosed(CLIENT)`, session closed *without* echoing `ui/closeWindow`
  back (the client initiated; an echo is harmless — the client host guards — but pointless).
- **Connection death** (F4): `connection.onClosed` → every window `onClosed(CONNECTION_LOST)`,
  sessions closed locally (nothing sent — the wire is gone). `MachineExample.onPlayerLeave` and
  `CgUiWorkspaceHost.forget`'s manual choreography both collapse into this hook.

### III.3 — `ClientWindows`: the mount, the registry, the mirror-image lifecycle

```java
public final class ClientWindows {
    public static ClientWindows of(ProtocolConnection<Object> connection);

    /** MenuScreens.register, for behaviour only. Unregistered types still mount — the
     *  description is self-sufficient; a factory only adds local behaviour (F7). */
    public static void register(String type, Function<ClientWindowContext, ClientWindowBehaviour> factory);

    /** Installed once by the platform host. Windows arriving before a mount exists queue;
     *  the queue drains when the mount arrives (retires placeOnDesktop's poll). */
    public void setMount(WindowMount mount);
}

/** What a platform implements once — CgUiScreen, a harness scene, a test. */
public interface WindowMount {
    /** Put a rebuilt tree on screen. Returns the handle the host closes/focuses through. */
    MountedWindow mount(ClientWindowContext ctx);   // ctx: root, type, title, key, sheets, ua-flag, session

    interface MountedWindow {
        void closedByServer(String reason);  // take it off screen; do NOT report back
        void focus();                        // a re-open of an existing key
        void contentReplaced(UIElement newRoot);  // a re-delivered open rebuilt the tree — swap it in
    }
}
```

`contentReplaced` is not speculative: `ClientUiSessions.accept` **deliberately re-delivers** an
`ui/openWindow` to an existing session (a reshape reaching a client that missed the delta), and
`ClientUiSession.buildFrom` then decodes a **fresh tree** and fires `onWindowOpened` again — so the
mounted frame's old root is stale the moment a re-open lands. The mc1710 mount implements it in one
call (`WindowFrame.setContent` swaps in place); a key-dedup re-open is `contentReplaced` + `focus()`.

- The **type registry** is `MenuScreens` ported (F7): `ClientWindowBehaviour` is the
  `attachLocalBehaviour` + `onCall` half of today's `MachineClient`, constructed per window of its
  type, told `onClosed`. A window of an unknown type mounts bare — correctly rendered, fully
  interactive for server-wired events, no local extras. This is strictly better than MC, where an
  unknown `MenuType` is a broken screen.
- **The mount reports user-closes** (F2's other half): the platform's `MountedWindow` wires
  `WindowFrame.onDestroyed` → host → `ui/close` → server. Guarded by "did the server close first",
  so the two directions cannot echo.
- **`ui/openWindow` grows three additive fields**: `type`, `title`, `key` (F7, F9). `StateMap`
  getters take fallbacks, so old clients ignore them and old servers send none — no version bump.
- **Connection death** (F4): `connection.onClosed` → every mounted window `closedByServer("connection
  closed")`, behaviours told, queue cleared. `bindToConnection`'s null-branch (F3) is deleted.
- **Sheets** (F10): the mount receives the `SheetRef` list and resolves it through a
  `SheetResolver` SPI installed beside the mount. Phase 2 ships the resolver interface with the two
  honest implementations that exist today (a registry lookup by id; a jar-constant); the `ui/sheet`
  fetch-by-hash request is Phase 3, modelled exactly on `ui/description` (request by hash, refuse
  a hash not served, content-addressed cache). Scoping stays an open question (below) — the design
  requires server sheets to be class-namespaced until the engine has scoped sheets, and says so
  where mods will read it.

### III.4 — Wiring: who installs the hosts

A contributor named `"ui"`, registered by `WindowProtocol.register()` (idempotent, core):

```java
Protocols.contribute("ui", new Protocols.Contributor() {
    @Override public <T> void bind(ProtocolConnection<T> connection) {
        if (connection.peer() != null) ServerWindows.install(connection);
        else                           ClientWindows.install(connection);
    }
});
```

Called from `CgUiConnections.register()` (both sides need it, same place the workspace contributes),
from the harness, and from test fixtures. It shows up in the existing
`"connection lifecycle installed; contributors: [...]"` log line, which is the diagnostic that
already exists for "is my subsystem actually wired".

**Deprecated by this:** the plain `new ClientUiSession<>(connection)` single-window constructor.
The host always speaks `ClientUiSessions`, and the mutual-exclusion trap between the two shapes
(both binding `ui/openWindow`, second one throwing) disappears with the deprecated shape. Migration
is mechanical for its three consumers (`MachineClient(connection)` convenience ctor,
`CgUiSessionProbe`, a handful of tests); the transport-owning constructors are untouched.

### III.5 — The platform half (mc1710)

1. **Re-key the peer** (F5 — a bug fix independent of everything else, do it first):
   `CgUiConnections.SERVER` keyed by `UUID` (from `GameProfile`), `Mc1710NetworkChannel`'s inbound
   handler translates entity → UUID at the seam, `forPlayer(EntityPlayer)` resolves by profile id.
   Respawn and dimension change then need no handling at all — the UUID never changes. (The
   alternative key, `NetHandlerPlayServer`, also survives both, but the UUID is readable in logs
   and is what `actorFor` already effectively uses.) **The send closure re-keys with it**: the Peer
   captures the `NetHandlerPlayServer` (the thing FML resolves through anyway, `FMLOutboundHandler:109`)
   rather than the entity, so outbound stops depending on a stale entity happening to keep its
   handler reference. `connection.peer()` stays the platform's *current* player — resolved, not
   captured — because `CgUiWorkspaceHost.actorFor` and every `viewer` callback read it.
2. **`CgUiScreen` installs the `WindowMount` once**, in `buildDesktop()`: create a `WindowFrame`,
   `setKey(ctx.key())`, `setTitle(ctx.title())`, `setContent(ctx.root())`,
   `openWindowInBackground` (the no-steal rule is the mount's, not each mod's), apply resolved
   sheets once per ref-set per engine, wire `onDestroyed` → `ctx.userClosed()`. The
   window-before-screen / screen-before-window race lives in the host's queue now, not in a poll.
3. **`CgUi.open(EntityPlayer, ServerWindow)`** — the one-line mc1710 convenience:
   `ServerWindows.of(CgUiConnections.forPlayer(player)).open(window)`, null-safe with a log line
   naming which of the four "nothing happened" causes applies (the F2-style diagnostic the example
   already models for F8).

### III.6 — The close matrix, complete

The answer to audit question 2, as a table the docs carry:

| Trigger | First noticed by | Server side | Client side |
|---|---|---|---|
| Mod calls `host.close(w, reason)` / `stillValid` false | server host | `onClosed(SERVER/NOT_VALID)`, session closed | `ui/closeWindow(reason)` → mount `closedByServer` → frame destroyed |
| User closes the frame (X, Escape, taskbar) | client mount (`onDestroyed`) | `ui/close` → `onClosed(CLIENT)`, session closed, no echo | frame already gone |
| Player leaves / kicked / client disconnects | `CgUiConnections` → `connection.close()` | `onClosed(CONNECTION_LOST)` per window, nothing sent | every frame `closedByServer("connection closed")` |
| Server stopping | `closeAll` → same as above | same | same |
| Player dies / changes dimension | nobody — the UUID key survives (F5 fixed) | nothing; `stillValid` (e.g. a distance check) decides, exactly as MC's `canInteractWith` does | — |
| Desktop suspended (screen closed), window hidden/minimised | nobody — **not a close** | session stays open, deltas keep flowing to the detached tree | frame retained by the desktop, comes back as it was |

The last row is deliberate (hide is not close — `plan_windowing.md`'s whole thesis) and its cost is
F13, addressed in Phase 4 if it ever matters.

### III.7 — What the Machine example becomes

**Server half** — `MachineExample` (120 lines, tick poll, leave handler, name map) becomes:

```java
// The machines are world state and tick with the world — one registry, one handler, no sessions.
// The window is opened on demand: F8 on the client asks, or a block right-click would.
Protocols.contribute("machine", new Protocols.Contributor() {
    @Override public <T> void bind(ProtocolConnection<T> c) {
        if (c.peer() == null) return;
        c.onNotify("machine/open", p ->
                ServerWindows.of((ProtocolConnection<Object>) c).open(new MachineWindow(MACHINES.forPeer(c.peer()))));
    }
});
```

`MachineServer` becomes `MachineWindow extends ServerWindow` — same `bind()` body it has today
minus `open()`'s ceremony, `type() = "crystalgui:machine"`, `key() = "crystalgui:machine"`,
`title() = model.label()`, `tick()` = mirror-model-into-panel only. The heartbeat/announce pair
moves from the connection to the session (F8).

**Client half** — `MachineExampleClient` (260 lines) becomes the F8 key binding plus:

```java
ClientWindows.register("crystalgui:machine", MachineClient::new);
```

`bindToConnection`, `placeOnDesktop`, `sheetInstalled`, the DESTROYED-frame resurrection, the
disconnect teardown: all deleted, because the code they lived in is deleted. F8's handler is one
line — `connection.notify("machine/open", null)` — and demonstrates the one shape MC's model lacks
(a client *asking* for a UI), which is a better lesson than open-on-login anyway.

**The demo, the probe, the harness, the fixtures** (F14): `Loopback` grows nothing; the fixtures
call `WindowProtocol.register()` in setup and drive `host.open(...)` like production does — so the tests
finally exercise the same wiring the game runs, which none of them do today.

### III.8 — Threading

Nothing new, stated once: every host callback (`bind`, `tick`, `stillValid`, `onClosed`, mount,
behaviour) runs on the thread that ticked the connection — server thread on the server, client
thread on the client, `main` in loopback. The host adds no thread, no lock beyond what
`attachment()` needs, and no callback from the Netty thread; `MachineTrace`'s columns stay the
proof.

### III.9 — Phases

| Phase | Contents | Proves |
|---|---|---|
| **P0** | F5 alone: UUID re-key in `CgUiConnections` + `Mc1710NetworkChannel`. Independent bug fix, ships first. | die → respawn → click still works (manual; `serverSmoke` can't see it) |
| **P1** | `ProtocolConnection` hooks + attachments; `ServerWindow`/`ServerWindows`/`ClientWindows`/mount SPI; `ui/close`; `ui/openWindow` +type/title/key; session `notify`/`onNotify`; migrate the three WeakHashMaps; headless tests | the close matrix, in `core`, against loopback |
| **P2** | mc1710: mount in `CgUiScreen`, `CgUi.open`, example rewritten per III.7; deprecate the plain riding constructor; primer + AGENTS.md rows updated | F8 in game; X actually closes; two windows of one type |
| **P3** | `SheetResolver` + `ui/sheet` fetch-by-hash | a server-authored theme reaches a client that never shipped it |
| **P4** (optional) | `ui/visibility` throttle for hidden frames | only if a real UI shows the cost |

### III.10 — Testing (the spine, per the standing rule)

Ownership, re-entrancy and announcements — never cosmetics:

- **Close matrix**: one test per row of III.6's table, each asserting `onClosed` fired exactly once
  with the right reason **and** the other side's observable (frame gone / session closed). The
  mutation check: break the `ui/close` guard so both directions echo, and the exactly-once
  assertions must fail.
- **Key dedup**: `open()` twice with one key → one window, second call returns the first, client
  mounts once and `focus()` fires.
- **Late mount**: window arrives, then the mount — queue drains; mount first, window second —
  direct. (The race `placeOnDesktop` polled around, now deterministic and therefore testable.)
- **Connection death cascade**: close the connection with two windows open on each side; every
  `onClosed(CONNECTION_LOST)`, every mux slot released (re-open in the same ids must not throw).
- **Unknown type mounts bare**: no factory registered → mounted, interactive, no behaviour.
- **Session-scoped notify**: two windows of one type on one connection, both register
  `machine/announce` — no throw, each hears only its own. (The test F8 makes impossible today.)
- **F5** is not headless-testable (it lives in FML's respawn path); it gets `serverSmoke`'s
  loading assertion plus a manual gesture note in the example's javadoc.

### III.11 — Open questions

1. **Sheet scoping** (F10's second half). Options: require class-namespacing by convention
   (P2's answer, documented loudly); or engine-level scoped sheets
   (`StyleEngine.addStylesheet(sheet, scopeRoot)` — CSS `@scope` exists as prior art). The second
   is real engine work and touches the cascade; not decided here, only prevented from being
   forgotten.
2. **Multi-viewer through the host.** `ServerUiSession.addViewer` (C1) is per-session; the host's
   `open()` is per-connection. A shared window (two players, one tree) would want
   `host.openShared(...)` handing an existing session a second viewer — deferred until something
   needs it, but `ServerWindow` deliberately never assumes one viewer (`stillValid(viewer)`,
   `callViewer` already exists underneath).
3. **`HIDE_ON_CLOSE` server windows.** `plan_windowing.md` decided server frames destroy on close;
   a server window holding half-typed work might want hide + `ui/visibility` instead. Deferred with
   Phase 4, and the policy stays a `ServerWindow` accessor away if it lands.

---

## Part IV — Composition: sub-UIs, fragments, and where the model comes from

A window is not the unit of reuse — an inventory strip, a machine status block, a config group each
want to be authored once and dropped into several windows, *with their behaviour attached*. Today
that is impossible to do safely, and checking what would actually happen exposed two more session
defects.

### IV.0 — Two defects found by asking "would A override X?"

- **F15 — `session.on` silently replaces on a duplicate `(element, kind)`.**
  `ServerUiSession.java:430` is a bare `Map.put`: a parent that registers on an element a child
  already wired wins silently, and which lambda runs is decided by registration order — the exact
  failure `MessageRouter`'s duplicate refusal exists to prevent one layer down. **Fix regardless of
  this plan: throw**, one handler per `(element, kind)`. A parent has no business re-wiring a
  child's internals; if it wants to observe, the child exposes a plain Java callback (IV.3).
- **F16 — the register-after-open rule is broader than its own reason.** `on()` after `open()`
  throws unconditionally (`ServerUiSession.java:426`), justified as *"the set of reported events is
  part of the description the client has already been sent"* — but a **tree delta re-describes its
  subtree**, reported events included, and `ClientUiSession`'s delta handler already calls
  `wireReportedEvents` on every new element. So the honest rule is narrower: refuse `on()` only for
  an element the client has already been described (`networkId >= 0`); an element not yet in the
  numbered tree may be wired at any time, because its description has not left the building.
  Without this relaxation, no fragment can ever be attached to a live window.

### IV.1 — `ServerFragment` + `WindowScope`

```java
/** A reusable piece of a window: a subtree plus the behaviour that makes it work. */
public abstract class ServerFragment {
    public abstract UIElement root();
    protected abstract void bind(WindowScope io);
    protected void tick() { }
}
```

Attachment is one call, from a window's `bind` (or another fragment's — scopes nest):

```java
InventoryFragment inv = new InventoryFragment(model.inventory());
panel.body.addChild(inv.root());
io.attach(inv, "inventory");     // binds now (or via tree delta on a live window), ticks after the window
```

`WindowScope` is a *view* of the session, and the isolation rules fall out of what each surface is
keyed by:

| Surface | Keying | Collision story |
|---|---|---|
| `on(element, kind, handler)` | the element itself | **Structural isolation.** X's handlers sit on X's elements; A cannot override them (F15 makes the attempt throw), and A never needs to name them. |
| `onCall` / `call` / `onNotify` / `notify` | method string, window-scoped | **Prefixed by scope path**: a fragment attached as `"inventory"` inside window methods sees `"save"` become `"inventory/save"`; nested attachment concatenates (`"machine/inventory/save"`). Two *instances* of one fragment class attach under two names, so their methods cannot collide; a duplicate scope name under one parent throws at `attach`. The window's own methods are unprefixed, exactly as today. |
| `tick` | attach order | The host ticks the window, then its fragments, deterministically. |

**So the direct answer to "would Parent A override Child X's contracts?" is: it cannot.** Widget
handlers are element-keyed (and duplicate registration becomes a refusal, F15); wire methods are
scope-prefixed so A and X are in different namespaces by construction; and the one genuinely shared
resource — the window id and its mux slots — is owned by the host, which neither of them touches.

The client needs **no fragment concept at all**: a fragment arrives as ordinary described elements
with ordinary reported-event names and calls methods like any other — which is the point of the
description architecture. A window whose fragment needs client-local behaviour wires it from the
window's own `ClientWindowBehaviour`; if that ever gets heavy, a client-side scope registry is
additive later.

Deferred deliberately: **detaching** a fragment from a live window (releasing its scoped methods
needs a per-`(method, window)` release on `UiWindowMux`, which today releases whole windows only).
Fragments attach for the window's life in P1/P2; dynamic detach is real work and waits for a
consumer.

### IV.2 — Where X gets the model: props down, events up

**Not from the session.** The session is the wire to the *client*; two server objects in one
process talk Java. The model reaches a fragment the way React passes props and Swing passes
constructor args — **the parent hands the child the slice it owns, at construction**:

```java
// A owns the model…
MachineModel model = machine.model();
// …and X is constructed WITH the part it edits. X never asks anyone for it.
InventoryFragment inv = new InventoryFragment(model.inventory());
```

X's handlers then mutate that slice directly, and nothing special happens next — the mutation
reaches widgets on the next mirror, the observer marks them dirty, the host's flush ships one
delta. The dirty-set does not care which object mutated a widget; that is what makes composition
free.

**Events up** is the mirror image: when A must *react* to something X did (not merely render its
result), X exposes a plain callback field — `inv.onPurged(Runnable)` — and A subscribes at
construction. Never a session message: routing a server-to-itself notification over the wire
machinery to cross two Java objects is a round trip to the room you are standing in.

For the rare genuinely cross-cutting case — a deeply nested fragment needing something no
intermediate parent should have to thread through — the engine already owns the pattern:
`DataProvider`/`DataContext`'s outward walk (what commands use). A fragment could resolve
`MachineModel` from the nearest ancestor element that provides it. Documented as the escape hatch,
recommended never as the default: injection keeps the dependency visible in the one place a reader
looks, the constructor.

### IV.3 — Phase placement

F15 and F16 are session-layer fixes and land in **P1** (F15 is a candidate to land even earlier —
it is a bug today, fragments or not). `ServerFragment`/`WindowScope` and the `ServerWindow.of(…)`
builder land in **P2** alongside the example rewrite, which becomes their first consumer: the Machine
panel's demo strip is the natural fragment to extract, and the example then teaches composition in
the same breath as contracts.


---

## Part V — What shipping it changed

Recorded because the corrections are the useful part of a plan once it is done.

### F17, found by the first run of the first test — and it predates all of this

Both delta handlers in `ClientUiSession` began `if (… || root == null) return;`. `ui/openWindow`
carries a hash, so unless the description is cached the client has to **ask** for it — and nothing
tells the server the far side is not ready, so it goes on flushing meanwhile. Everything sent in that
window was dropped, in silence.

**Permanently**, which is what makes it worse than a dropped frame: `Property.set` returns early on
an unchanged value, so a widget the server has already written is never marked dirty again. The
client shows the description's value forever, for exactly the fields that changed early. And the
first tick is when a window mirrors its model, so this is the common case rather than a corner.

Deltas are now queued and replayed **in arrival order** — a state delta computed after a renumber
must be applied after the tree delta that caused it — and drained *before* `onWindowOpened`, so a
host mounting the tree sees state that has already been sent rather than one frame of catching up.

### Two design changes forced by the code

- **`ClientWindows` keys mounted windows by the SESSION, not by the window id.** `ClientUiSession`
  calls `release()` — which sets the id back to `-1` — *before* it emits `onWindowClosed`, so a
  lookup by id at close time misses every time and the window is never taken off screen. Cost four
  failing tests to find; the session object is the one identity stable for a window's whole life.
- **`ServerWindows.open` rolls back a failed `bind`.** Binding is exactly where a wiring mistake is
  raised (a duplicate handler, two fragments under one name), so a half-opened window left in the map
  holding an id and its mux slots is the *ordinary* path for a mistake rather than a theoretical one.

### What the plan got right and did not have to revisit

The close matrix (III.6) shipped exactly as written, including the deliberate asymmetry that a
user-driven close does not echo `ui/closeWindow` back at a frame that has already gone. The
composition design (Part IV) needed no changes: element-keyed handlers plus scope-prefixed methods is
what makes "can a parent override a child?" answerable with *no*.

`SheetSupply` and `ui/visibility` were planned as P3 and P4 and landed inside P1 instead, because the
host needs them — a mount has to style what it shows, and `WindowFrame` already emits the hide/show
signals the throttle keys on. Their phases became their covering tests, which is where the value was.

### Verification

- `WindowLifecycleTest`, 26 tests: the four close reasons, key dedup and focus, the mount queue in
  both orderings, the connection-death cascade, an unknown type mounting bare, two windows naming one
  notification, fragment namespacing and the two boundaries that throw, a fragment attached to a live
  window, the delta race, the sheet tiers, and the hidden-reshape numbering.
- **Mutation-checked** where the assertion is subtle: moving the visibility gate below
  `flushStructure` — the obvious spelling, which reads as equivalent — fails
  `aWindowReshapedWhileHiddenComesBackWithItsNumberingIntact` and nothing else.
- The full headless suite (1400+), `:core:runExample` end to end with the wire tapped, and
  `:mc1710:serverSmoke`, which reports contributors `[workspace, ui, machine]` and no client-only
  class loaded on the server.

### The names changed after it shipped

Recorded because every commit message and every earlier revision of this document uses the old ones.

The problem was two overloads, both mine. **"host"** already meant the *platform* (`CgUiScreen` owns
a `UIWindow`) and a *server-side service* (`CgUiWorkspaceHost`); a third sense read circularly, since
the platform host installed a mount on the `ClientUiHost`. And **"window"** already meant the
*engine* (`UIWindow`) and the *chrome* (`WindowFrame`).

| Was | Is | Why |
|---|---|---|
| `com.crystalgui.net.host` | `com.crystalgui.net.window` | the package now does the disambiguating: `ui.UIWindow` / `ui.elements.desktop.WindowFrame` / `net.window.ServerWindow` |
| `ServerUiHost` | `ServerWindows` | the codebase's existing plural-owner convention — `ClientUiSessions`:`ClientUiSession`, `Protocols`, `ScriptRuntimes` |
| `ClientUiHost` | `ClientWindows` | same |
| `UiHosts` | `WindowProtocol` | it contributes the window half of the protocol, and `X.register()` is the init convention |
| `UiWindows` | `ServerWindow.of(…)` → `ServerWindow.Builder` | the builder belongs to what it builds, and a top-level name one letter from `ServerWindows` was a trap |
| `SessionScope` | `WindowScope` | "session" was triple-booked (`ServerUiSession`, `WorkbenchSession`, `SessionState`) |
| `UiHostLifecycleTest` | `WindowLifecycleTest` | follows |

**`UIWindow` is the one genuine misnomer and was deliberately left alone.** It is the only thing
called a window that is not one — it plays the DOM's `Document` role, which this codebase's own
invariants already say outright (*"`UIWindow` owns the modal stack because the spec hangs it off the
Document"*), and `UIDocument` is free. It is also **1,069 Java references across 308 files plus 195
in docs**, several inside load-bearing invariants that would need re-reading rather than
find-replacing, and the ambiguity it caused is fully resolved by the package split above. Left as a
known wart with the right name recorded.

### Still open

- **F5 has no automated cover**, and cannot: it lives in FML's respawn path. The gesture is manual —
  join, die, respawn, press something.
- **Sheet scoping** (III.11 §1) is unchanged: a server sheet is applied to the one style engine, so
  it must be class-namespaced by convention until the engine has scoped sheets.
- **Detaching a fragment** from a live window still needs a per-`(method, window)` release on
  `UiWindowMux`, which releases whole windows only. Fragments attach for a window's life.


---

# Part VI — The next rewrite: ownership, binding, and typed access

**Live.** Parts I–V shipped a lifecycle. What follows is the next question, which the shipped code
raised rather than answered: **who owns the tree, and how does each side get typed hold of it?**

Nothing here is built. Entries are added as they are settled, with the evidence that settled them.

---

## VI.1 — The inversion: a window is *attached to* a panel, it does not own one

**Decided: the direction is right.** What shipped has `ServerWindow` create and hold its panel; the
example does `private final MachinePanel panel = new MachinePanel()`. That reads as ownership and it
is the wrong way round.

The panel should be the primary artefact — created, owned and stored by the application — with
`ServerWindow` attached to it as **the networking aspect**: what the buttons actually do across a
wire. Three arguments, and they converge:

- **It is how the rest of the engine already thinks.** A tree is the thing and behaviour attaches to
  it: `session.on(element, kind, handler)` attaches, `UITreeObserver` attaches, and `ServerUiSession`
  is *given* a root and observes it — it has never owned one.
- **Minecraft agrees.** `ContainerChest(inventory, chest)` is handed its state; a menu is an aspect
  of an inventory rather than its owner. `AbstractContainerMenu` takes a `ContainerLevelAccess`.
- **Reuse.** A panel you own is a panel you can build without deciding it is networked — the same
  tree then serves the harness, a local-only screen, and a server session.

### Most of it already works, and the example is what misleads

`ServerWindow.root()` is abstract and returns *any* `UIElement` (`ServerWindow.java:103` — that plus
`type()` is the entire contract with a subclass). Nothing requires a window to build its tree:

```java
public final class MachineWindow extends ServerWindow {
    private final MachinePanel panel;                    // GIVEN, not created
    public MachineWindow(MachinePanel panel, MachineModel model) { … }
    @Override public UIElement root() { return panel.root; }
}
```

That compiles against what shipped. **The example teaches ownership because it was written that way**,
which is worth correcting regardless of how far the rest of this goes.

**The builder is the part that genuinely blocks it.** `ServerWindow.of(type, Supplier<P> contents,
rootOf)` takes a *factory* — ownership by construction. It needs an overload taking a tree that
already exists.

---

## VI.2 — Three constraints, two of them hard engine limits

Verified in the source, not assumed. All three are silent when violated.

### One observer per tree, so one window per panel — and it must throw

`UIElement.observer` is a **single field** (`UIElement.java:810`), and `setObserver` replaces it and
cascades over the whole subtree (`:824-829`). Two `ServerWindow`s over one panel therefore means the
second silently steals the first's change notifications, and the first stops sending state updates
**for good**.

Nothing currently prevents it. Attaching to a tree that already has a window has to be refused, with
a message naming the window that holds it. (This is the same fact that made `ServerUiSession` grow
*viewers* rather than allowing several sessions over one tree — see C1.)

### Reported events are add-only, which breaks re-attachment

`addReportedEvent` exists (`UIElement.java:795`) and there is **no remove and no clear** —
`getReportedEvents` hands back an unmodifiable view (`:803`). So with a panel that outlives its
window:

1. Window A binds `purge` → the element is stamped `report: activate`.
2. A closes. The panel survives — which is the whole point of the inversion.
3. Window B attaches and does *not* bind `purge`.
4. The element still advertises the event, the client still wires a listener, and every press
   produces `no handler for 'activate' on element N`.

Noisy rather than fatal, but it is once again the *wired, looks wired, does nothing* shape.

**And there is a second-order cost that is easy to miss:** reported events are part of the
description, so stale ones change the **content hash** — a re-attach would make the client refetch a
tree it already has, throwing away the one-packet-reopen win that content addressing exists for.

Needs `UIElement.clearReportedEvents()`, called when a window releases its tree.

### The lifetime inversion is the actual design decision

The window becomes transient and the panel persistent. That is *good* — it is the server-side
analogue of hide-is-not-close — but it splits a word that is currently one word. `onClosed(reason)`
today implies both **this window is finished** and **this panel is finished**, and after the
inversion those are different events with different audiences.

---

## VI.3 — The client's typed panel: a **binding**, not the object

### The problem is worse than verbosity

```java
if (ask instanceof Button) ((Button) ask).attachListener(this::requestStats);
```

If that id moves, or the widget type changes, **the line silently does nothing**. `MachineClient.wire`
has three of them in a row, and this is currently the *recommended* pattern for every client
behaviour anybody writes.

It is forced by the engine rather than chosen: **`UIElement` has no typed lookup at all.** All four
accessors (`querySelector`, `querySelectorAll`, `getElementById`, `getElementsByClassName`,
`UIElement.java:1982-1997`) return `UIElement` or `List<UIElement>`. Meanwhile `MachinePanel` has
fourteen typed public fields that the client throws away and re-derives by string.

### The constraint, stated honestly

**There is no `MachinePanel` on the client and there cannot be.** The tree is *decoded* —
`UIDescriptionCodec` builds plain widgets from tags through `ElementRegistry`. The description
carries tags, not classes, and that is exactly what lets an old client draw a new panel.

### What is available instead is standard

A **binding**: the same class, bound to the rebuilt tree, with the same field names. Android's View
Binding and JavaFX's `@FXML` injection exist for precisely this problem, and both are worth reading
before this is built.

```java
new MachinePanel()            // server: builds the tree
MachinePanel.bindTo(root)     // client: resolves the rebuilt tree by id
```

Told to the registry, so a behaviour receives it typed:

```java
ClientWindows.register(TYPE, MachinePanel::bindTo, MachineClient::new);
```

…and the three silent lines become three loud ones:

```java
private void wire(MachinePanel panel) {
    panel.askStats.attachListener(this::requestStats);
    panel.heartbeat.attachListener(this::sendHeartbeat);
    panel.badRename.attachListener(() -> rename("   "));
}
```

A missing id then fails **at bind time, loudly**, instead of at press time, silently — and it is
contained, because `ClientWindows` already catches a failing behaviour and keeps the window.

### It completes the symmetry VI.1 is reaching for

The application owns `MachinePanel`; the server attaches a `ServerWindow` to it; the client binds a
`MachinePanel` to the rebuilt tree. **One class, one set of field names, both sides.**

Worth stating in the docs when it lands, because it invites exactly one misreading: they are
*different instances over different trees*. A client-side `panel.power.setChecked(…)` is a local
write that the next state delta overwrites — the preview-not-a-fact rule `MachineClient.show` already
records.

### Required vs optional is the version-skew story

An older client binding against a newer tree must not explode over a widget it has never heard of, so
a binding needs both:

- `require(id, Type.class)` — throws, naming the id and the type. For anything the behaviour cannot
  work without.
- `find(id, Type.class)` — null or `Optional`, for anything added since.

Getting this wrong in either direction is bad: throw on everything and one new server-side button
takes down every old client's local behaviour; throw on nothing and the silent-skip failure is back
with more ceremony.

---

## VI.4 — The two halves are paired by a **string**, and breaking it is silent · **SHIPPED**

> **Shipped with VI.3's binding and VI.6's typed lookup**, which it depends on: `WindowType<P>`'s
> value is the type parameter, and without a way for the client to produce a `P` it degrades to an id
> holder the example already had. Built bottom-up — typed lookup, then binding, then the descriptor.
>
> **One decision changed on contact.** The sketch below has `WindowType` carrying four things
> (`id`, `create`, `rootOf`, `bind`). It ships with **two** — `id` and `bind` — because *how the
> server constructs its panel is not part of the contract between the two halves*: that is the
> builder's business, and an application that already owns its panel (VI.1) has no supplier to give
> at all. Putting construction in the shared descriptor would have baked in the ownership model VI.1
> exists to invert.
>
> **VI.6's fork was settled as (A)**, ids as constants used by both the build and the bind path, and
> it does not foreclose (B): a declare-once base class would not change `WindowType` at all.
>
> **And one thing was finished a commit later.** The first cut left exactly one place where a mod
> still named its own type back at the framework — `MachinePanel.TYPE.bind(context.root())`, in every
> behaviour's `onContentReplaced`, which is the binding done by hand in the one place the host already
> knows how to do it. `ClientWindowBehaviour<P>` fixes it: the panel is handed over on a re-describe
> exactly as it is to the factory. **The parameter is earned by VI.5's own test** — the framework
> hands you the thing — and it costs nothing outward, because unlike `ServerWindow` a behaviour is
> never held in a public heterogeneous collection, so the wildcard stays on one private field and one
> `@SuppressWarnings` inside `ClientWindows`. One suppression in the engine is the price of none in
> every mod.
>
> **And a second pass removed the re-wiring entirely.** Even handed the panel, a behaviour still had
> to attach its widget listeners in *two* places — the constructor and `onContentReplaced` — and
> forgetting the second was silent: every button dead, the window otherwise perfect. The cause is that
> a behaviour holds **two lifetimes that look like one**. Things registered on the *session*
> (`onCall`, `onNotify`) are keyed by method and survive a re-describe untouched; things attached to
> *elements* die with the tree that carried them. `ClientWindowBehaviour.onPanelBound(P)` is now the
> **only** place a panel arrives — called at mount and again after every re-describe — so the choice
> is gone rather than documented, and the factory drops to `Function<ClientWindowContext, …>` with the
> pairing checked by the behaviour's own declared type instead of by a constructor parameter.

`ClientWindows.register(String type, factory)` takes a raw string, and dispatch is
`FACTORIES.get(fresh.type())` with the miss handled as:

```java
if (factory == null) return;   // an unknown type is a window with no local extras, not a failure
```

That line is a deliberate feature — an unregistered type still mounts, renders and reports every
event, which is the one respect this beats `MenuScreens`. **It is also, exactly, what a typo looks
like.** Rename the type on one side, misspell it, or move the constant, and the window opens
normally with no behaviour, no error and no log line. The good outcome and the broken one are
pixel-identical.

Three things must agree and **nothing checks any of them**: the server window's `type()`, the type
the client registered, and — once VI.3 lands — the ids the panel builds against and binds against.

### Minecraft does not have this problem, and the reason is the fix

`MenuType<T>` is a **registered object**. `player.openMenu` and `MenuScreens.register` reference the
same value rather than two copies of a string, so a mismatch cannot be spelled. This plan ported the
pipeline (Part II) and left the descriptor behind.

### What to couple: a `WindowType<P>` declared on the panel

The panel is the artefact both sides genuinely share, so the descriptor belongs on it — which is
also what makes it loader-safe (every reference in the initialiser is to the panel itself):

```java
public final class MachinePanel {
    public static final WindowType<MachinePanel> TYPE =
            WindowType.of("crystalgui:machine", MachinePanel::new, MachinePanel::bindTo);
    …
}
```

One declaration carries the id, how the server **builds** it, and how the client **binds** it (VI.3).
Both sides then reference the value rather than a string:

```java
ServerWindows.of(c).open(MachinePanel.TYPE.serve(panel)…);      // server
ClientWindows.register(MachinePanel.TYPE, MachineClient::new);  // client
```

**The win is the signature, not the tidiness.** `register(WindowType<P>, BiFunction<P,
ClientWindowContext, ClientWindowBehaviour>)` is type-checked: `MachineClient`'s constructor *must*
take a `MachinePanel`. A mismatched pair becomes a compile error instead of a runtime no-op — the
string-typo class of bug removed rather than documented.

It also lets `ServerWindow.type()` return a `WindowType<P>` instead of a `String`, so the server
half cannot name a type that does not exist either.

### What cannot be coupled, and the reason is hard

**The behaviour registration has to stay in client code.** Not style — the loader seam.

A shared descriptor holding `MachineClient::new` would be a `static final` field. Its initialiser
runs at class init, the `invokedynamic` bootstrap resolves the constructor, and **`MachineClient`
loads on a dedicated server** — a `NoClassDefFoundError` at panel class-load for any behaviour that
reaches a client-only type, and precisely what `:mc1710:serverSmoke` asserts against.

> **The distinction to keep:** a **method-body** reference to a client-only class is lazy and safe; a
> **static field** holding one is not. Same rule as the `EntityPlayerMP` field that split
> `MachineExample` from `MachineExampleClient` in the first place — field descriptors and static
> initialisers resolve eagerly, method bodies do not.

*(The Machine example happens to dodge this: `MachineClient` imports only `core` types, so it is
**protocol**-client rather than **loader**-client and would load on a server perfectly well. That is
the exception, and designing around it would be designing around an accident.)*

### The shape, then

Couple **the type and the panel** into one shared value; leave **the behaviour** registered from
client code, but type-checked against that value instead of matched by string. The coupling lands
where the silent failures actually are, and the seam stays intact.

### A re-describe is currently **unreachable**, and that is worth knowing

`onPanelBound`'s second call, `WindowMount.contentReplaced`, and `ClientUiSessions`' re-delivery
branch are all defensive: **nothing triggers them today.** `ServerUiSession.sendOpenTo` early-returns
once `viewer.opened` is set (`ServerUiSession.java:450`) and nothing ever resets it, so an existing
viewer receives exactly one `ui/openWindow` and never a second.

That is not an argument for deleting them — it is an argument for noticing what they point at. The
case they were written for is *"a reshape reaches a client that missed the delta"*, and the client
**does** have a state it cannot recover from: on a tree-delta count mismatch it sets `root = null`
and releases the window, which leaves a dead window and no way back. Re-sending `ui/openWindow` is
exactly the recovery, and it is currently the only thing that would make these paths fire.

So: either a client that refuses a delta asks to be re-described, or the paths stay dead code with a
comment saying so. Deciding that is a separate item; what must not happen is the current state, where
the machinery exists, reads as live, and is reachable by nothing.

### Still open here

- **Should an unregistered-but-*declared* type warn once?** With `WindowType` the client can tell
  "a type nobody has ever heard of" from "a type this installation declares and did not register",
  and the second is far more likely to be a mistake. Risk: it is noisy for a window that is
  deliberately bare, so it probably needs the descriptor to say which it is.
- **Where does `WindowType` live** — on the panel as above, or in a registry keyed by id? A registry
  buys enumeration (diagnostics: "what window types does this installation know?") at the cost of
  registration order mattering again.

---

## VI.5 — Where a type parameter is earned, and where it is not

The test that settles it, and it has now come up three times: **does the framework hand you the thing,
or do you already hold it?**

| | Handed to you? | Verdict |
|---|---|---|
| `ServerWindow<P>` (the class) | No — the subclass holds a typed field, whether it created the panel or was given one in its constructor | **No parameter.** It would be a promise nobody collects |
| `ServerWindow.Builder<P>` | Yes — `wire((p, io) -> …)`, `title(p -> …)` receive the panel | **Parameter earned**, and already there |
| `ClientWindowBehaviour` | Yes — the factory receives the bound panel | **Pass it as a constructor argument**, not as a generic on the context |

The third row is the one with a trap in it. Generifying `ClientWindowContext<P>` would leak `<?>`
into everything that holds a window generically — the same cost measured for the server side, where
**thirteen sites in `ServerWindows` would need a wildcard** (the `Map`, the `List`, `byKey`, `close`,
`finish`, and three loops).

And it buys nothing where it looks like it should. The one place a typed panel is genuinely wanted is
a lookup —

```java
ServerWindow w = ServerWindows.of(c).byKey("mymod:machine");
```

— and generics **cannot** help there, because the registry is heterogeneous: `ServerWindow<?>` hands
back a capture, so `w.panel()` is `Object` with extra steps. You cast either way. The parameter is
absent exactly where you would reach for it and present everywhere you would not.

There is a consistency argument too. `WindowProtocol`'s javadoc already records why this layer is
non-generic over the wire representation — *"making `ServerWindow` generic would put a type parameter
in every mod's class declaration and every handler signature to serve a case no wire in this engine
has."* Angle brackets staying out of what a mod author writes is a decision, not an accident.

---

## VI.6 — Engine gaps this needs

Small, and each is useful well beyond this plan.

| Gap | Why | Blast radius |
|---|---|---|
| `UIElement.require(sel, Class<T>)` / `find(sel, Class<T>)` | There is no typed lookup anywhere; every consumer in the codebase pays the `instanceof`-and-cast tax, and every one of them degrades silently | Additive |
| `UIElement.clearReportedEvents()` | VI.2 — without it a re-attached panel advertises events nothing handles, and its content hash drifts | Additive |
| Refuse a second `ServerWindow` on one tree | VI.2 — the alternative is one window silently going deaf | One check in the attach path |
| `ServerWindow` builder overload taking an existing tree | VI.1 — the `Supplier` is what forces ownership | Additive overload |
| ~~`WindowType<P>` + type-checked `ClientWindows.register`~~ | VI.4 — **shipped** | — |
| ~~`UIElement.require` / `find`~~ | VI.6 — **shipped**, and the first consumer is the binding | — |

---

## VI.7 — Open forks

**How panel ids get declared.** Both modes need to agree on them, and drift between them is a silent
failure.

- **(A) Two factories, ids as constants.** `MachinePanel` gains `bindTo`; ids are `static final
  String` used by both paths. Plain Java, no framework, roughly thirty lines in the panel, ships
  immediately.
- **(B) Declare-once base class.** `public final Switch power = require("power", Switch.class);`
  where `require` *creates* in build mode and *resolves* in bind mode, with a separate `layout()` for
  arrangement. The id genuinely appears once and every future panel gets it free — but it is a real
  framework: field-initialiser ordering against the mode, a widget factory per class (`ElementRegistry`
  already holds one per *tag*, which may or may not be the right hook), and layout split from
  declaration.

Leaning **(A) first, (B) once three panels prove the shape** — but (B) is the one that stops this
recurring, and this session's premise is rewriting until the model is right rather than shipping the
cheap version.

**Whether `ServerWindow` stays a class you extend.** Once the panel is passed in, the class *is* an
attachment with a nice constructor. Whether it should instead be `ServerWindows.attach(tree)…` is
worth revisiting only after VI.1 lands, because the answer is much clearer once the ownership is the
right way round.

**What `onClosed` means after the split** — VI.2's third constraint. Probably two callbacks, but the
audience for each wants naming before the shape is chosen.

**Whether a declared-but-unregistered type warns** — VI.4. With `WindowType` the client can finally
tell "never heard of it" from "this installation declares it and did not register a behaviour", and
only the second is likely to be a mistake. The risk is noise for a deliberately bare window.

**Where `WindowType` lives** — VI.4, **settled: on the panel.** Loader-safe (every reference in the
initialiser points at the panel itself) and zero extra classes. A registry keyed by id is still the
alternative if enumeration is ever wanted for diagnostics, at the cost of registration order
mattering again.

**Whether a declared-but-unregistered type warns** — VI.4, still open, and now cheaper to answer:
with `WindowType` the client can tell "never heard of it" from "this installation declares it and
registered nothing", and only the second is likely to be a mistake.

---

## VI.8 — Should the **panel** declare its own networking? · **Amended: yes, as METHODS**

> **This entry was originally a flat "no", and that was too strong.** The objection below is sound
> against *declarations in a constructor body* and dissolves against *base-driven lifecycle methods*,
> which is what VI.9's `Panel` base introduces. Read the original reasoning for the constraint it
> correctly identifies — the panel is two objects — and then VI.9 for the mechanism that satisfies it.
> The corrected rule is one line: **methods may be side-specific; fields may not.**

The proposal: put the `call`/`notify`/`on` contracts inside `MachinePanel`, next to the widgets they
belong to, the way `panel.askStats.attachListener(…)` already sits there.

**The instinct is right and the vehicle is wrong.** Locality is a genuine good — reading what Purge
does today means three files — and every web framework converged on co-locating markup with
handlers. What follows is why the panel class specifically cannot be the home, so nobody re-derives
it.

### The panel is two objects, and the boundary runs through it

`new MachinePanel()` on the server; `MachinePanel.bindTo(tree)` on the client. Two instances, two
constructors, potentially two JVMs. A declaration in the class body has to survive **both** paths,
and `ctx -> model.purge()` cannot: there is no model on the client, and naming one would put a
server-only type into a class the client loads.

React co-locates because there is **one** instance in **one** process and the boundary is a fetch.
The real analogue is React Server Components — and note what they had to invent: `"use client"` /
`"use server"` directives **enforced by the bundler**, because the boundary cannot be inferred from
the code. That is the tax, and it is a build-system feature rather than a flag.

### "Which side am I on" is not a property of a tree

Not merely hard — **false**. The same panel is legitimately used in the harness with no networking at
all, in a purely local screen, and on both ends of a wire. Side is a property of the *context*, which
is why `WindowScope` carries it.

Worth stating as a fact rather than a principle: **`core` contains not one side check today** — no
`isClient`, no `Side.CLIENT`, nothing. That is not an accident to spend.

### And it fights VI.1

VI.1's payoff is *"a panel you own is a panel you can build without deciding it is networked."* A
panel with baked-in contracts can only ever be networked, only with one model, **captured at
construction** — so it can never be re-pointed at a different machine.

### The version that almost works, recorded so it is not re-discovered as new

The panel *does* already know its mode: `new` and `bindTo` are different constructors, so
declarations placed in the build constructor are skipped on the client for free. **No side flag is
needed.** Buffer them and replay when a scope arrives.

It is genuinely viable. It costs: the panel now **requires** a model to construct (the harness needs
a dummy), it captures that model forever, it adds a second registration mechanism with its own
ordering questions — and it saves **one file**, because `MachineClient` must still exist. The loader
seam does not move.

### What to do instead — superseded by VI.9

The original conclusion was "make `ServerFragment` the normal unit". That is still a reasonable
shape, and VI.9 is a better one: the mechanism it needs turned out to be the same mechanism the
field-declaration work needed anyway.

---

## VI.9 — The panel as the **whole** component · **live**

The destination this section has been converging on: **one class per UI**, with structure, server
behaviour and client behaviour as methods on it, and no `ServerWindow` or `ClientWindowBehaviour` in
between.

```java
public final class MachinePanel extends Panel<MachineModel> {

    public Switch power;
    public Button purge    = new Button("Purge");     // ctor args? just write them
    public Button askStats = new Button("Ask stats");

    @Override protected void layout() {                          // build only
        add(row("Power", power));
        add(purge);
    }

    @Override protected void serve(WindowScope io) {             // SERVER only
        io.on(power, TOGGLE, ctx -> model().setRunning(ctx.payload().getBool("checked", false)));
        io.onActivate(purge, ctx -> model().purge());
        io.onCall("stats", (args, respond) -> respond.ok(stats()));
    }

    @Override protected void client(ClientWindowContext window) {  // CLIENT only
        askStats.attachListener(() -> window.session().call("stats", null, this::show, this::fail));
    }
}
```

Opening it: `ServerWindows.of(connection).open(MachinePanel.TYPE.serve(machine))`.

### Why the fields need no strings

The **field declaration is the declaration**. The base walks declared fields: in build mode it
creates anything left null and stamps `setId(fieldName)`, then calls `layout()`; in bind mode it
resolves each field from the rebuilt tree by name and type and never calls `layout()`. Field names
survive compilation and nothing obfuscates our own classes, so the name *is* the id — written once,
as the thing you were going to write anyway.

A widget needing constructor arguments gets an ordinary initializer; the base fills nulls and leaves
the rest alone. No supplier, no class token, no engine change to `Button`/`UIText`.

**Four touches per widget become one.** At 100 panels that is roughly 5,000 lines of ceremony against
1,300 lines that are all actual UI.

### The rule that makes one class safe on both sides

**Methods may be side-specific. Fields may not.**

A field descriptor resolves at **class load**; a method body does not. That is the same rule that
split `MachineExample` from `MachineExampleClient` — a `static KeyBinding` field killed a dedicated
server while every guarded line of code was unreachable.

So `serve()` may reference a server-only model and `client()` may reference `Minecraft`: both are
method bodies, both lazy, both invoked only on their own side. A concrete `MachineModel model` field
would not be, which is why the base is `Panel<M>` — **a generic field erases to `Object`** in the
descriptor, so the type never appears there, and `model()` casts inside a method body.

### Measured, not assumed — **and it holds**

Whether HotSpot forces class loading for types named only in **method bodies** is the one thing this
design rests on, so it was run rather than reasoned about.

A scratch `VerifierProbe` was loaded on a real dedicated server (`:mc1710:serverSmoke`) while naming
`org.lwjgl.input.Keyboard` in both a method **body** and a method **signature**. It loaded, its other
method ran, and the smoke test still reported *no client-only class loaded*.

**The first attempt was worthless and is worth recording as a trap.** It named
`net.minecraft.client.Minecraft` — and a dev dedicated server has Minecraft's client classes on the
classpath, so resolution could not have failed and the experiment proved nothing. LWJGL is
*genuinely* absent there, and the same run says so out loud:
`NoClassDefFoundError: org/lwjgl/LWJGLException` from `CgPlatform.register`. **An experiment against
a class that is present is not an experiment.**

So the rule is confirmed on the actual target JVM:

| | Resolves | So |
|---|---|---|
| field descriptor | at **class load** | a side-specific field breaks the other side |
| method body | lazily, on first execution | `serve()` may name a server type, `client()` a client one |
| method signature / return type | lazily | same |

### Costs, accepted

- **Fields cannot be `final`** — the base assigns them in both modes. The alternative is a holder
  (`panel.power.get()`), which is worse at every use site.
- **Reflection over declared fields**, once per construction, cacheable per class. JavaFX's `@FXML`
  does exactly this; Android generates code instead because it has a build step and we do not.
- **Renaming a field changes its CSS id.** Network addressing is positional so the wire is unaffected,
  but a stylesheet targeting `#power` would silently stop matching.

### Opt-in

A panel that does not extend `Panel` keeps working exactly as it does today, so nothing in the engine
or the example breaks on the way in — and `ServerWindow` stays the right shape for a window whose
content is not a single panel.

### What still does not collapse, and is not a middle man

The **trigger** — a block's `onBlockActivated`, a key, a command. It belongs to the game object
rather than to the panel, and for anything the server already observes it is one line with no message
and no contributor at all.

---
