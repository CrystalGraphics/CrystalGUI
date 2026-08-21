# Phase 5 — the remote workspace, made usable

**Scoped 2026-08-21**, immediately after Phase 4 closed and the first genuinely two-process test ran.

Phase 4 answered *can the workspace be served over a wire* — yes, verified on a dedicated server with the
files on the server's disk and nowhere near the client's. Phase 5 is the gap between that and **a person
being able to use it without losing work**, plus the things that only became reachable once there was a
real server.

> **Scope.** Networking, the workspace, and UI over the wire. The shader node graph (`P6.3`) is
> deliberately out — it is tracked in its own document and does not interact with any of this.

---

## Provenance, and what was actually checked

Every item below was verified against the code on 2026-08-21 rather than carried over from a plan. Where
something is a *decision* rather than a defect it says so, and where the evidence is a file and a line it
gives them.

| Established | How |
|---|---|
| The workspace really is served from the server's disk | `runServer` + `runClient -PcgJoin=localhost:25565`; `run/server/crystalgui/workspace/remote-probe.txt` exists and `run/client/…` does not |
| The editor works against it end to end | Browsed, edited (`README.md *`) and **ran** a script off the server's disk |
| The server-side surface is exactly 15 methods | `grep registry.register(WorkspaceProtocol.*` in `WorkspaceRpc` |
| The command layer has no side concept at all | `Command`, `CommandRegistry`, `CommandContext` — the only two occurrences of "server" are javadoc asides (`Command.java:90`, `CommandRegistry.java:25`) |

### The whole server-side surface

`WorkspaceRpc.installOn` registers these and nothing else. Each runs
`authorise(actor, path, operation)` **per call**, which is what makes `OperatorsMayWrite` work — the read
column passes for anyone, the write column does not.

| Read | Write | Trash | Watch |
|---|---|---|---|
| `fs.projects` | `fs.write` | `fs.delete` | `fs.watch` |
| `fs.manifest` | `fs.writeDelta` | `fs.restore` | `fs.unwatch` |
| `fs.read` | `fs.create` | `fs.purge` | `fs.changed` (server→client) |
| `fs.readChunk` | `fs.mkdir` | `fs.trashList` | |
| | `fs.rename` | | |

### How a command reaches the server, and what decides its side

```
palette / keybind / context menu
  → Command.execute(CommandContext)      client
  → workbench.files().delete(path, …)    client   (WorkspaceClient)
  → fs.delete                            ─── the wire ───
  → WorkspaceRpc handler                 server
  → WorkspaceService.authorise + delete  server
  → LocalFileSystem                      server disk
```

**Nothing marks a command's side, and nothing needs to.** The client physically cannot reach the
server's files: `Mc1710Workspace` holds a `WorkspaceClient` and no `CgFileSystem`, no path, no handle. A
command runs on the client because that is the only place it *can* run, and it reaches the server only by
making a call that crosses the seam. The split that exists is by **what a thing is** — workspace files are
the server's, config and session records are client-local under `config/crystalgui`, deliberately outside
the workspace so private state never becomes part of a project a resource pack could ship.

---

## Two corrections to carry in

Recorded so nobody re-derives them.

- ~~**The editor holds a stale `WorkspaceClient` across a reconnect.**~~ **Not reachable, struck
  2026-08-21.** It looked certain — five places capture the client at construction
  (`CrystalEditor:159`, `Workbench:141`, `ProjectFileTree:187`, `WorkspaceTreeSource:109`,
  `WorkspaceFileService:81`), and nothing rebinds any of them. But `CgUiScreen.onGuiClosed` nulls the
  editor, the window and the workspace, and Minecraft replaces any open screen with the disconnect
  screen — so **the editor cannot survive a disconnect** and the next open builds a fresh client. The
  teardown should have been checked before the capture was called a defect.

- **The C1 × C2 seam is fixed, and is the shape to expect more of.** A viewer joining *after* a reshape
  was told a description hash the session no longer served, and its window never appeared. Each feature
  was correct alone; neither's tests combined them. Found by running both in one client.
  `aViewerAddedAfterAReshapeStillGetsTheWindow` pins it.

---

## The items

### 5.1–5.2 Windowing · **moved to [`plan_windowing.md`](plan_windowing.md)**

Started here — a disconnect discards unsaved work — and outgrew the phase. It is **engine work, not
networking**: a lifecycle for `UIWindow` where hide, close and destroy are three different things, the
registry that owns the retained set, and the strip that makes a hidden window findable again. Every
window benefits, not just the editor, and it changes what a loader is for — `CgUiScreen` stops *owning* a
window and starts *attaching* to one.

The numbers are left as they are rather than closing the gap, so anything citing 5.3–5.10 keeps meaning
what it meant.

**What stays in this phase** is the half that is genuinely about the wire:

- **Reconnect-on-restore** — retention resurrects the stale `WorkspaceClient` this document records as
  unreachable, and the fix is browsers': close connections on hide, re-establish on show, with the
  restore carrying the equivalent of `pageshow`'s `event.persisted`. Tracked as `plan_windowing.md` W6
  because it is a step of that build, and noted here because **this** is the document that struck the
  defect.
- **Persisting the retained set** — 5.3 below. Retention is in-memory and survives Escape, not a crash,
  a quit or a disconnect, so the two are complementary rather than alternatives.

### 5.3 Persist the retained set · **because retention is always best-effort**

Retention is in-memory: it survives Escape, and not a crash, not quitting the game, not a disconnect.
**Every system that retains also persists**, and says so — the Page Lifecycle API ships `wasDiscarded`
precisely so a page can tell it was dropped and needs a full reload, and Android's `onSaveInstanceState`
exists because being stopped may become being destroyed without warning.

So this does not merge into 5.1 and must not be skipped because 5.1 covers the common path.

**What is missing today.** `WorkbenchSession` persists **view state only** — its own words, *"a caret, a
scroll offset, a fold set — is applied when the file's content arrives."* It does not carry buffer
content. There *is* an unsaved-changes guard — `Workbench.java:495` sets
`dock.setCloseGuard(this::confirmClose)` — but it guards closing a **tab**, and
`CgUiScreen.onGuiClosed` does not consult it. Locally that was survivable because closing was the
user's choice; **on a server, disconnection is not**.

**Stored client-side, always.** The whole reason the buffers need saving is that the server may be
unreachable, so a recovery that requires the server is not one. `LocalConfigStorage` already exists for
this class of state, and writing them into the workspace would also put private in-progress work into a
project a resource pack could ship.

> With 5.1 in place this becomes *"persist the retained set"* rather than *"persist dirty buffers"* —
> a better-shaped problem, because the retained set is already the thing that knows what mattered.

### 5.4 `enabledWhen` is evaluated locally · **done 2026-08-21**

A command's `enabledWhen` runs on the client while a menu is being built, so it cannot ask the server
*may I?*. `explorer.delete` looked perfectly available to a non-operator and the refusal arrived as a
`NO_PERMISSIONS` failure after a round trip. Asking per menu open is worse — that is a round trip inside
a UI gesture.

So the answer is **cached and pushed**: VS Code's context-key model, where the far side volunteers what
it knows and the near side reads it synchronously. `fs.capabilities` is **one method name in both
directions** — a request the client makes once when the projects load, and a push the server sends when
the answer changes. `MessageRouter` keys request and notification handlers separately, so one name serves
both without ambiguity, and it should: they are the same question, asked and volunteered.

#### The three questions the item asked, answered

**What is cached** — per project, `read` and `write`, asked against the project's own root. That is a
**real coarsening**: `WorkspacePermission` takes a *path*, so a host may allow writes under `src/` and
refuse them under `config/`, and no per-project broadcast can say so. It is therefore a **hint for
enablement and never the authority** — every operation is still authorised server-side on its real path,
and a test pins that a client which ignores the hint is still refused.

**When it is invalidated** — never by the client. The server pushes, because an operator promoted
mid-session is not something a file listing reveals, and a client that only asked at connect would draw a
greyed-out Delete for the rest of the session.

**What a command shows while the answer is unknown** — **available**, and this is the decision the whole
design turns on. A wrongly-*greyed* command is a thing the user cannot do and cannot explain: no message,
no dialog, nothing to search for. A wrongly-*live* one fails with a reason the server wrote. Being wrong
in the second direction is recoverable and being wrong in the first is not. The same rule covers a server
too old to know the method — greying out every write against an otherwise working workspace, because one
method is missing, is far worse than offering a write that fails.

#### A trap worth recording

**A server → client push on this subsystem is a `call`, not a `notify`.** `WorkspaceClient` registers its
inbound methods through `WorkspaceRpc.Registrar`, which is `onRequest`, so `fs.changed` has always
arrived as a *request* the client answers with `respond.ok(null)`. The first draft of the tests used
`notify` and the two push cases failed while everything else passed — the router keys the two kinds
separately, so a notification simply found nobody home. Now stated on `notifyCapabilities` itself.

`WorkspaceCapabilitiesTest`, 9 tests, mutation-checked: making unknown mean *denied* fails 3, which are
exactly the three about being wrong in the safe direction. Wired into `explorer.delete`, `explorer.rename`
and both New commands.

### 5.5 The conflict dialog · **done 2026-08-21**

The protocol half was done deliberately and first: `Failure.isConflict()` carries the live etag, and a
delta against a file that moved is **refused rather than merged** — merging is a decision with a UI
attached and does not belong in a write path. `ConflictDialog` is that UI.

**What it replaces understated the problem twice.** A `Notification` with one action, *"Reopen to take
theirs"* — and the comment beside it already said the prose had named the fix and that it should be a
button. But a balloon **fades**, so a user who was not looking takes the default, and the default was
*"your save silently did not happen"*. And that one button **discards unsaved work in a click**, while the
opposite resolution — keep mine — was not offered at all, so anyone who wanted it had to know to copy
their buffer out first.

| Choice | What it destroys |
|---|---|
| Keep mine | Their changes on the server |
| Take theirs | Your unsaved edits |
| Cancel | Nothing — the file stays modified and unsaved |

A conflict is one of the few moments in an editor where every route loses something, which is exactly
what earns a modal: the argument against modals is that they interrupt, and here interrupting is the
point.

**Cancel is focused, not the first button.** `Dialog`'s focusing steps take the first focusable
descendant, which would be whichever button reads first — so focus is requested explicitly afterwards,
as `showModal()`'s own javadoc says a caller should. Escape reaches the same place through the close
watcher. Both destructive choices need a deliberate click and neither is one keystroke away.

**Built the plain way on purpose**, so window-scoped modality (`plan_windowing.md` W5) retargets it for
free: through `UIWindow.addOverlay` and `Dialog.showModal()`, with no `left`/`top` written by hand, no
direct promotion, and no assumption about what the backdrop covers. The same three rules are already
recorded for anchored popups.

### 5.6 Presence · **done 2026-08-21**

~~C1 gives a session its viewers and their peers, which **is** the data presence needs.~~ Close, and the
better answer was already on the wire: **`fs.watch` is sent for every file a client reads** and cleared
when it closes one, so the server has known who has what open since Phase 4. What was missing was a view
**across** peers — a `WorkspaceWatcher` belongs to one connection and can only ever answer about itself.

So `WorkspacePresence` lives on `WorkspaceService`, the one object every `WorkspaceRpc` already shares,
and it is **the first piece of workspace state that is per server rather than per connection**.
Presence rides the watch rather than adding a message: a second thing saying "this client has this file
open" is a second thing to keep in step, and the two would disagree the first time one was forgotten.

**A version counter, not a broadcast.** `core` cannot reach every connection — the loader holds the
connection map — so presence counts changes and each peer's existing per-tick poll notices when the
number has moved. One `int` compare per peer per tick, no new wiring, and it cannot deliver to a peer
that has gone, because a peer that is gone is not being polled.

**Three things it must not get wrong**, and each is a test:

- **Scoped to what the peer is watching.** Sending the whole server's presence would tell a client which
  files it cannot read are open and by whom — the same leak `fs.watch` is authorised against, arriving
  through a different door. Removing that filter fails a test.
- **A peer that vanishes is forgotten.** A client that crashes never sends the `fs.unwatch` that would
  clear it, so without `left()` on disconnect it is shown holding those files for the rest of the
  server's life. Wired into `CgUiWorkspaceHost.forget`.
- **Empty means "nothing has been said", never "nobody is there."** So it is only ever used to *add*
  information and never to decide anything, and the status entry is **removed** rather than reading zero
   — a permanent slot that usually says nothing is one the eye learns to skip.

**Displayed in two places**, which answers the original complaint that nothing did: the conflict dialog
names who else has the file (5.5 was indeed the consumer the plan predicted), and a status-bar entry
names them for the active file. Display names throughout — `WorkspaceActor.id()` is what permission
decisions are made on and `displayName()` is documented as being *"for logs and for the UI"*; an id
leaking into a presence line would be an identifier the user never chose, shown to other players.

`WorkspacePresenceTest`, 8 tests, mutation-checked on the scoping guard.

### 5.7 Two windows on one connection · **done 2026-08-21**

~~`ServerUiSession` enforces one UI session per connection by construction.~~ It did, and the failure was
the right one: `MessageRouter` keys handlers by method name and **refuses** a duplicate rather than
replacing it, because silently keeping the last registration means whichever subsystem initialised second
wins, which is unfindable. Lifted by dispatching on **window id as well as method** — `UiWindowMux`.

**Newly urgent rather than speculative.** A retained window registry (`plan_windowing.md` W2) makes
several live windows the normal case, and that is exactly when two UI sessions land on one wire.

#### What it is

`UiWindowMux` keys `(method, window)` and installs **one** handler per method name on the router, the
first time any window asks for it. Every message in the UI vocabulary already carried
`UiMethods.WINDOW`, and every session already re-checked it on the way in — so the id was being
*verified* by a handler that could only ever be one. This turns that check into the lookup it wanted to
be. The per-handler checks stay: they were the guard against a message still in flight when a window
closed, which is a different question from routing.

**Above the router, not inside it.** `MessageRouter` is the vocabulary every subsystem shares —
`WorkspaceRpc` and a future `script/*` bind to it and have no window to be keyed by — so teaching it one
payload's shape would put a UI concern in the layer under everything. Same split `FrameMultiplexer`
already makes a layer down: the generic thing carries ids, the thing that knows what an id *means* sits
on top.

#### The asymmetry that decided the client design

A `ServerUiSession` is **given** its window id, so it registers window-scoped handlers immediately and
needs nothing above it. A client **learns** an id from the wire, and the message carrying it —
`ui/openWindow` — is therefore the one thing in the vocabulary that *cannot* be window-scoped: it is what
announces the window. So the client gets a host, `ClientUiSessions`, which owns that message for the
connection and hands each announcement to the right session.

The tempting alternative — let each unbound session take whichever open arrives first — is not a design:
with two windows opening, which gets which is decided by registration order, and the symptom is two
windows rendering each other's trees. There is no id to check yet, so nothing downstream could detect it.

**The single-window shape is untouched.** `new ClientUiSession(connection)` still owns `ui/openWindow` and
registers straight on the router — no mux, no lookup — which is what every existing caller and the 1.7.10
client take. A plain session and the host are mutually exclusive on one connection, and the refusal comes
from the router's own duplicate check so there is one statement of the rule.

#### Two things that were nearly left out, and both are silent

- **`release` on close and on `removeViewer`.** Without it a closed window keeps its `(method, window)`
  pairs claimed, so reopening in that id throws *"window 1 already serves 'ui/description'"* — for a
  window nobody is watching, on a connection that has been up for hours. On a client that reopens the
  same editor that is **every second open**, and on a reconnect it is every re-add of the same viewer.
- **Session-scoped RPC is window-scoped too.** `onCall` collided exactly as `ui/description` did, and
  worse: an application naming `app/save` on its second window threw from inside the router about a
  method it had every right to name twice. The counterpart is that outgoing calls are **stamped** with
  the window on both sides. A method belonging to the *connection* rather than a window — a workspace, a
  script runtime — still registers on `ProtocolConnection` directly and is shared by every window, which
  is what it wants; the extra key is additive and such a handler correctly ignores it.

#### No fallback to "the only window"

A message with no window id gets a **refused request** or a **dropped notification with one warning**,
never delivery to the single open window. That fallback is correct with one window and silently wrong
with two — it fails exactly when the feature starts being used. And a request is *answered* rather than
dropped, or the caller waits out its deadline and reports a timeout: a slow peer and a closed window are
different problems and must not look alike.

#### Verified

`TwoWindowsOnOneConnectionTest`, 10 tests, and the whole headless suite (1165) green. **The tests were
mutation-checked**: making the mux ignore the window and deliver to whichever handler registered first —
i.e. a router that merely stopped complaining — fails **7 of the 10**. The three that survive are the ones
that should (the single-window regression, the mutual-exclusion refusal, and viewer re-add). Opening two
windows is the easy half and would pass against the broken version; what the suite actually pins is that
a delta, an event and an RPC each reach **exactly one** window, and that a closed window gives its id back.

### 5.8 Server-contributed commands · `command/*` · **done 2026-08-21**

Four messages, and the direction of each is the design. Everything *about* a command flows server →
client as a notification, because the server is the only one that knows: `command/contribute`,
`command/withdraw`, `command/setEnabled`. The single thing flowing the other way is **the user did it**,
and that is a **request**, because a command can fail and the person who pressed the key deserves to be
told — a command that fails silently is indistinguishable from a keybinding that was never wired up.

A contributed command is an ordinary `Command`: the palette enumerates it, a menu renders it, the keymap
resolves it, and nothing downstream knows that running it sends a packet. That is the point — a
server-driven action should not be a second kind of action with its own surface.

**Enablement is pushed, and the cache is read at ask time.** `isEnabled` is consulted while a menu is
being built, so it has to answer immediately; asking across the wire there would put a round trip inside
a UI gesture. Capturing the boolean when the command was contributed would have been the obvious bug —
`command/setEnabled` would then arrive, update nothing anybody reads, and the menu would render the first
answer forever. **An id nobody has said anything about is enabled**, which is the same call 5.4 has to
make and for the same reason: a wrongly-greyed command is a thing the user cannot do and cannot explain,
while a wrongly-live one fails with a message the server wrote.

#### The trust boundary, which is most of the work

~~**Watch for:** this is the first thing that would let a server change what a client's palette *does*.~~
Correct, and the mechanism is worse than that framing suggests: `CommandRegistry.register` **replaces by
id**, on purpose (that is how a theme or a mod overrides a built-in). So without a rule a server could
claim `edit.save` and the client's own Save would quietly become a packet.

`ScriptPolicy` is the precedent and settled two things worth reusing. *A control nobody will configure is
worse than a leaky one that gets used*, so the default is safe and needs no host to think about it. And
*a filter its subject can switch off is not a filter*, which is why `ALWAYS_REFUSED` is a **floor**
checked ahead of everything rather than a default a host may edit away.

Here the floor is a **namespace**: a server may only claim ids under `server.`. Strictly stronger than a
denylist of built-ins and needs no maintenance — a built-in added next year is protected by
construction, whereas a list of protected ids is a list somebody forgets to add to. It costs a server
nothing real, since `server.restart` is a fine id and the **label** is what a user reads.

Three more limits, each about a broken peer rather than a malicious one: a **count cap** (a palette
listing ten thousand rows is unusable), **label sanitisation** (a control character becomes a space
rather than being stripped, so two words cannot be run together into a third nobody wrote; overlong is
**cut, not refused** — a clumsy label beats a missing command), and **withdrawal only of what this
connection contributed**, so the floor is true a second way rather than one typo away from failing.

**Refusal is per command, never per message** — one bad entry in a batch of twenty must not cost the
other nineteen, the same rule a state delta already follows.

#### Two smaller things worth recording

`CommandProtocolBinding` decides its side by `peer() == null`, exactly as `CgUiWorkspaceHost` does.
Without that a single-player process binds both ends of its own wire and whichever registered first wins
— not hypothetical there, which is why it was copied rather than re-derived.

And `setPolicy` **throws** after registration rather than applying. A contributor only binds connections
opened *after* it, so a change made later reaches nobody currently connected and everybody connected
afterwards — the kind of half-applied setting that reads as the setting not working.

`ServerContributedCommandsTest`, 15 tests, of which nine are the boundary. Mutation-checked: removing the
namespace floor fails 4, including the one that matters — a server registering `edit.save` and the
client's own Save being what still runs.

### 5.9 The wire under real conditions · **the concurrency half done 2026-08-21**

Everything so far is **localhost with tiny payloads**: the in-game probe moved a 44-byte file, and the
1.25 MB chunked test was in-memory over an `InMemoryTransport`. Credit flow control, fragmentation and
the 8 MB reassembly bound have never met latency, loss, or a genuinely large file over a socket.

#### ~~Watch for: several large transfers in flight together~~ — **ran it. Worse than that.**

The suspicion was right and aimed at the wrong workload. `flush` round-robins across **every** queued
message, so all of them fragment simultaneously and the receiver must buffer all of them at once:
reassembly demand is the **sum** of what is in flight, not the largest of it. Measured on an in-memory
pair at the 1.7.10 client frame size:

| Sent | Before | After |
|---|---|---|
| one 7 MB | delivered | delivered |
| one 9 MB (over the cap) | refused at the cap | refused at the cap |
| three 4 MB (12 MB) | **delivered 0** | delivered 3 |
| eight 2 MB (16 MB) | **delivered 0** | delivered 8 |
| forty 512 KB (20 MB) | **delivered 0** | delivered 40 |

**Forty half-megabyte messages is a workspace listing, not an attack**, and not one of them arrived. So
this was never a large-file problem: it is *many ordinary ones*, which is far likelier to happen and
reads as the connection dying under load rather than as a transfer being refused.

#### The fix: admission, denominated in bytes

A message begins fragmenting only while what is already in flight leaves room for it — HTTP/2's
`SETTINGS_MAX_CONCURRENT_STREAMS`, counted in **bytes** rather than in streams, because bytes is what the
receiver's bound is denominated in and a count cannot tell forty 512 KB messages from forty 5 MB ones.

Three properties, each deliberate:

- **A message that fits in one frame is never gated.** The receiver delivers it straight out of the frame
  without touching a reassembly buffer, so there is nothing to ration — which means every UI packet,
  event and RPC on this wire is completely unaffected. Pinned on `fragmentingBytes`, since a throughput
  assertion would pass whether or not the exemption exists.
- **The first message is always admitted, however large.** Otherwise one bigger than the budget would
  never be sent at all; instead it goes and is refused at the documented cap, which is a far easier
  failure to diagnose than a silent stall.
- **Round-robin is untouched among admitted messages**, so the small-behind-large property it exists for
  still holds. Admission bounds the interleaving; it does not remove it.

`MAX_REASSEMBLY_BYTES` now has two jobs — the receiver's guard against a hostile peer, and the sender's
self-imposed budget. Both sides share the constant rather than negotiating it; a negotiated limit is the
right shape eventually (HTTP/2 sends one in SETTINGS) and needs a handshake this protocol does not have.

`ConcurrentTransferAdmissionTest`, 7 tests, mutation-checked: making `admits` return `true`
unconditionally fails **3 of the 7**, and the four survivors are the ones that should (the single-message
cases and the two invariants).

#### Still open, and unchanged by the above

~~**Latency, loss and a real socket.**~~ **Latency done; the socket is still open.**

**Loss is not a hazard here, and listing it was a mistake.** Every supported platform's channel rides
the loader's own networking — `SimpleNetworkWrapper`, `SimpleChannel`, Fabric — which is Minecraft's
Netty pipeline, which is TCP. A frame that is sent is delivered, in order. Simulating loss would have
tested something that cannot happen, and would have invited retransmission logic whose only effect is to
duplicate TCP. What is real is **latency** and, more importantly, **tick granularity**: `pump()` runs
once per game tick on each end, so a round trip costs two ticks — 100 ms at 20 tps — before any network.

#### What measuring it found

**`flush` made a single round-robin pass per call**, so the connection was capped at **one frame per
message per tick** regardless of how much credit the peer had granted. A lone 1 MB message is 32 frames,
so it took **34 ticks — 1.7 seconds at zero latency** — with seven-eighths of the window unspent.

That also made `DEFAULT_WINDOW_BYTES`' own justification false. It said *"256 KB is eight client→server
frames, which is enough to keep the pipe busy"*; exactly one of those eight was ever in flight. **Tuning
the constant would have changed nothing**, which is the tell that it was never the limit.

Repeating the pass while credit lasts (the inner pass still gives every message one turn, so
anti-head-of-line-blocking is untouched):

| | before | after |
|---|---|---|
| 256 KB, no latency | 10 ticks | **2 ticks** |
| 1 MB, no latency | 34 ticks (1.70s) | **8 ticks (0.40s)** |
| 1 MB, 400 ms RTT | 58 ticks | **29 ticks** |
| 16 MB burst, 400 ms RTT | 686 ticks | **509 ticks** |

The limit is now the credit window, which is where it belongs and is genuinely tunable. **Cold credit
plus a burst does not deadlock** — the reconnect shape, where admission holds messages back waiting on
one in flight while that one waits on credit a round trip away.

#### A test had to be rewritten, and why that was legitimate

`aSmallMessageIsNotBlockedBehindALargeOne` pumped four times and asserted the receiver held exactly one
message — using *"the large one has not finished yet"* as a proxy for *"we are still early"*. The proxy
stopped holding the moment the wire got four times faster, so it failed against a version where the
property it names was intact. **The property is ordering, not timing.** It now asserts the small message
arrives first, with the large one sized from `DEFAULT_WINDOW_BYTES` so raising the window cannot turn it
back into a test of how fast the wire happens to be.

`WireUnderLatencyTest`, 5 tests, mutation-checked: restoring the single pass fails 2.

#### Still open

**A real socket, on two machines.** Everything above is a simulated link in one JVM: the delay is exact,
there is no jitter, no MTU, no Nagle, and no competing traffic from the game itself — which on a busy
server is the thing most likely to matter. And `DEFAULT_WINDOW_BYTES` still wants measuring under real
load; it is now a knob that would actually move something, which it was not before.

~~**A `CodecException` from `accept` aborts the whole pump.**~~ **Fixed.** It did, skipping `replenish`
and `flush` for that tick while `handleData`'s comment claimed the opposite (*"refuse the stream rather
than the connection"*) — true only because `CgUiConnections.tickSafely` catches two layers up, so true
by accident rather than by construction, and not true at all for the harness or anything pumping
directly.

Split the way HTTP/2 splits it, which is where the rest of this class comes from: a **stream** error
(RFC 9113 §5.4.2) resets one stream and the connection carries on; a **connection** error (§5.4.1) means
the peer is not speaking this protocol. `StreamRefused` is caught per frame and counted; an unknown
opcode or DATA on stream 0 still propagates.

**And the other half of it was corruption, not waste.** An inbound RESET dropped the reassembly buffer
and *did not cancel the outbound message*. So the peer refuses a stream, drops its buffer and RESETs;
the sender ignores that and sends the remainder; the peer opens a **fresh** buffer for the same stream
id, and the sender's last frame carries FIN — so it reassembles the **tail** and delivers it as a whole
message. A refused 9 MB transfer arrives as a ~1 MB one that looks complete, with nothing reporting a
problem anywhere.

> Found by mutation rather than by reading. The first version of that comment said the fault was wasted
> bytes and a repeated warning — which is what it looks like from the code. Removing the cancel makes
> three tests fail with `expected:<1> but was:<2>`, and the second message is the tail.

`StreamErrorIsolationTest`, 6 tests. Mutation-checked twice: rethrowing the refusal fails 5, and removing
the outbound cancel fails 4.

### 5.10 Platform hygiene · **the class of bug that cost three fixes today**

- ~~**`PlatformService1201` has the same eager-construction shape**~~ — **checked and fixed
  2026-08-21.** It did, and worse. Eight services were `private final X = new X()` inside a
  `static final INSTANCE`, so they were built at **class init**, a step earlier than 1710's preInit, and
  the `@Mod` constructor calling `getInstance()` runs on **both sides** on Forge and NeoForge. (Fabric is
  exempt by construction — `CrystalGraphics1201Fabric` is a `ClientModInitializer`.)

  **The concrete hazard is `GL1201Context`**, which holds `private volatile GLCapabilities caps` — an
  `org.lwjgl.opengl` **field descriptor**, and a dedicated 1.20.x server has no LWJGL on its classpath.
  `CursorService1201`, `ResourceService1201` and `RenderingService1201` name `net.minecraft.client` types
  that a server distribution does not ship either; those are method-body references today, so they
  survive *loading* — but only by luck, and nothing stops the next edit adding a field.

  Fixed by applying the 1710 shape ahead of the failure rather than after it: every service is now built
  on demand and every field is **typed as its SPI interface**, so the LWJGL-touching class is named only
  inside a method body.

  **Two things are worth keeping from this, because neither is obvious.**

  The class javadoc asserted *"No GL calls are made in the constructor or static initializer."* That was
  true, and it is about what the code **does** — whereas the failure is about whether a class can be
  **loaded**. It is the same trap `PlatformService1710.onInit` already records about
  `processAllRequirements()` being documented as *"a no-op on dedicated server"*: true of its behaviour,
  irrelevant to its loadability. A safety claim phrased in terms of execution cannot cover a
  class-loading fault, and reads exactly as though it does.

  And **whether a dev `runServer` would catch it is unknown.** This first read as a flat assertion that
  ModDevGradle's joined artifact carries client classes *and* LWJGL, so the eager version would boot
  happily. The joined artifact does carry client classes; the LWJGL half was a guess, and the equivalent
  guess about 1.7.10 turned out **wrong in the reassuring direction** — see below, where the smoke check
  measured RFG's server run and found no LWJGL at all. A guess that was wrong once is not a basis for the
  other loader family, and mc1201 compiles from no build we have, so this stays open.

  **Unverified, and unverifiable from here.** `mc1201` is commented out of `settings.gradle.kts` in
  *both* repos, so nothing compiles it. The check that was available is a parse against the `platform`
  sourcepath: zero syntax errors, all remaining errors being the absent MC/LWJGL/Lombok packages —
  which corroborates the finding without proving the fix. That is why the shape was made unable to fail
  rather than argued about.
- ~~**CrystalGraphics' import guard is disabled**, and is what would have caught today's failures.~~
  **Half right, corrected 2026-08-21 while enabling it.** The guard *was* disabled — its own `AGENTS.md`
  warns against assuming otherwise by analogy with CrystalGUI's active one — and it is now **on**, with
  `org.lwjgl` added, which the commented-out original omitted. Both `core/` and `platform/` were already
  clean, so it cost nothing and only prevents a regression; it is verified to fire by compiling a
  deliberate violation.

  **But it would not have caught any of the three.** All three were in `mc1710/`, where `org.lwjgl` is a
  **legal** import, or in `CgPlatform`, which imports nothing offending and simply *called*
  `platform.gl()`. The class of bug is *"a client-only class is constructed on a server"* — a **runtime**
  property that no import scan can see.

- ~~**What does catch it is booting a dedicated server.**~~ **Built and run, 2026-08-21:**
  `./gradlew :mc1710:serverSmoke` — boots, asserts, stops, ~48s, exit 0 on pass and 1 on fail.

  It asserts more than the one log line the item asked for, because a line only proves a line was
  printed: the platform bundle registered, the network channel is available, the connection lifecycle
  installed (both its failure paths are a `warn` and a `return`, so a server with **no networking at all**
  boots looking healthy), the protocol contributors bound, a UI description round-trips and
  content-hashes stably with no GL anywhere, and — the one that targets the original bug class — that
  **no client-only class has been loaded**. `CommonProxy`'s javadoc has always stated that contract
  ("a static reference from a common class is enough to fail class loading there") and nothing checked it.

  **Three things came out of running it that could not have come out of writing it.**

  1. **The first run was green having executed nothing.** Port 25565 was in use, the bind failed, FML
     forced `SERVER_STOPPED`, `FMLServerStartedEvent` never fired, not one assertion ran — and Gradle
     said `BUILD SUCCESSFUL`. *A check that is green when it did not run is worse than no check, because
     it is now also a claim.* Fixed in two places: the run takes **its own port** (25599, `-PcgSmokePort`
     to override — 1.7.10's `MinecraftServer.main` parses `--port`, so no code of ours), and the mod
     writes a **report file** the task deletes beforehand and requires afterwards, so *"never ran"* is a
     failure with a message. The mod's `halt(1)` only ever covered *"ran and failed"*.
  2. **A dev server is closer to production than claimed.** The write-up asserted LWJGL is on RFG's
     server classpath. It is not: `CgPlatform.register` takes its `NoClassDefFoundError` fallback and the
     check reports *"GL backend is not installed on this server — matching production"*. So on 1.7.10
     the dev server is production-shaped for exactly the failure that prompted this. Corrected in the
     class, in `PlatformService1201`, and in the bullet above.
  3. **`net.minecraft.client.Minecraft` and `CgUiScreen` are present and unloaded**, which is what makes
     that assertion falsifiable rather than a tautology — on a real server the classes are simply absent.

  The guard was the consolation prize; this is the item.

  > The same reasoning `core/src/headlessTest/` already embodies — *"the absence is the assertion"*.
  > A server is simply a second environment no test reproduces, and until today nothing had ever run one.

> **Today's three, for the record**, because they are one shape: eager construction of client-only
> services (`NoClassDefFoundError: org/lwjgl/LWJGLException`), `CgPlatform.register` pulling GL itself,
> and `PlatformService1710.onInit` calling two client-only things *before* the caller's side guard. Each
> was fatal at mod load, each invisible to every test and to the harness, because **neither is a
> server**. CrystalGraphics had never run on one.

---

## Sources — read 2026-08-21

> The windowing research (Win32, X11, Cocoa, Page Lifecycle, bfcache) moved with §5.1–5.2 to
> [`plan_windowing.md`](plan_windowing.md). What remains below is what the rest of this phase cites.

- [Page Lifecycle API](https://developer.chrome.com/docs/web-platform/page-lifecycle-api) and the
  [WICG spec](https://wicg.github.io/page-lifecycle/) — the six states, `freeze`/`resume`, and
  `wasDiscarded`
- [Back/forward cache](https://web.dev/articles/bfcache) — eligibility, `pageshow` + `event.persisted`,
  and the connection rule this phase adopts wholesale
- [Disconnect WebSockets on BFCache entry](https://groups.google.com/a/chromium.org/g/blink-dev/c/52nlr8z3Png)
  — the change from *"an open connection blocks retention"* to *"close it on entry, reconnect on
  restore"*, which is the shape 5.1 takes
- [Opening and Closing Windows](https://developer.apple.com/library/mac/documentation/Cocoa/Conceptual/WinPanel/Tasks/OpeningClosingWindows.html)
  — `orderOut:` vs `close`, `releasedWhenClosed`, and that it is **ignored under a window controller**
- [Using Window Notifications and Delegate Methods](https://developer.apple.com/library/archive/documentation/Cocoa/Conceptual/WinPanel/Tasks/UsingWindowNotDel.html)
  — `windowShouldClose:` as a veto

## Explicitly not in this phase

- **`WatchService`.** Not a gap. `WorkspaceWatcher`'s own javadoc rejects it — *"quirky per platform and
  unreliable on network mounts"* — and notes a faster path can be layered under it without changing
  anything above. An optimisation behind an intact seam.
- **Slots and inventory** (Phase 4 C6). Refused on the plan family's own rule: no consumer, no
  `ItemStack` anywhere in `core/src/main` or `mc1710/src`. Revive when something renders an item.
- **mc1201 itself.** Still a product call, not a technical block. 5.10's first bullet is the part worth
  knowing *before* that call is made.
- **The shader node graph.** Its own document.

---

## What one client cannot prove

Two things stay covered headlessly on purpose, because a single-player world has one connection and a
probe cannot stand up a second client:

- **Multi-viewer fan-out** with two real players. `MultiViewerTest` covers it; the in-game probe adds a
  real `addViewer` against the live session whose far end is an `InMemoryTransport`.
- **Anything about latency**, which is 5.9.

And a standing note from Phase 4 that the dedicated-server work reinforced: **a client is an environment
no test reproduces** — and a *server* is a second one. Every defect found today was invisible to 1090
headless tests and to the harness.
