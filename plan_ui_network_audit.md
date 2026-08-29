# CrystalGUI networked UI — full-stack audit and the rewrite it argues for

**Status: research, 2026-08-28. Nothing here is implemented.** This is the critical audit asked for
after the mirror design in Appendix A — the whole networked-UI path, from the `CgNetworkChannel` frame
up through `ServerWindows`/`ClientWindows`/`UiType`, and every place that path meets the widget
framework: the Desktop compositor, the Workbench, `Dialog`, `Tab`, focus, sheets, commands, the
1.7.10 host. The target is not a patch list. It is a **rewrite whose replacement must support
everything a CrystalGUI UI can do**, and the audit is structured to say what that is.

Method: every claim below was read out of the code or measured in a loopback; file references are
to `core/src/main/java/com/crystalgui/` unless noted. Production references are cited per finding
and consolidated in §7. The mirror design this builds on (stable identity, operational deltas, presentation as the
client's) is Appendix A.

---

## 0. The headline

Read as a whole, the stack is **a good wire and a good envelope under a session layer that models a
UI as a snapshot plus positional patches**. That model is why every hard problem of the last two
addenda existed, and it is also why the following are true today, none of them exotic:

| A user does… | What happens |
|---|---|
| The server disables a button after the window opened | **Nothing reaches the client.** `setEnabled`, `addClass`, `removeClass`, `setId`, `setFocusPolicy`, `setHitTest`, `setInert` all route to `notifyIdentityChanged()` → `dirtyIdentity`, which `ServerUiSession.flush` **clears without ever sending** (`ServerUiSession.java`: `dirtyIdentity` is added to and cleared, never read into an entry). The button stays live on screen |
| The server writes an inline style after open — highlight a row red | Never sent: `onStyleChanged` is deliberately unobserved, and inline style travels only in the description |
| The server sets an inline `background:` on anything, ever | `InlineStyleCodec.encodeSlot` **throws** for a property whose value type has no codec (drawables have none), inside `open()` or inside a flush — the window never opens, or every server tick logs an error and the window freezes |
| A player picks a dropdown option, switches a tab, picks a colour, drags a split | The server is **never told**. `wireReportedEvents` reports four kinds — `activate`, `toggle`, `value`, `text` — on five widgets. `Dropdown`, `TabView`, `ColorSelector`, `SplitView`, `Tab` carry state the server can write and the client cannot report |
| A player types in a text field, or drags a slider | One `ui/event` **per keystroke** and **per slider change** — `TextField.attachListener` connects to `value.changed`; `Slider` to `onValueChanged`. No debounce, no throttle, no commit-on-release |
| A forged client packet sends `{"value": NaN}` for the throughput slider | `MachineModel.setThroughput`: `Math.max(0, Math.min(1, NaN))` is `NaN`, the `==` guard is false, the model is poisoned — cycle progress becomes `NaN` and never completes; every viewer's bars show `NaN`. Nothing validates an event payload against the widget that declared it |
| A forged client packet sends `activate` for a disabled button | Dispatched. `ServerUiSession` checks that a handler exists and nothing else: not `isEnabled`, not `isInert`, not whether the kind belongs to the element's type |
| Two players watch one machine; one minimises their window | **Deltas stop for both.** `viewerVisible` is one flag on the session; any viewer's `ui/visibility=false` suppresses the flush to every viewer |
| Two players watch one machine; one presses Purge | The handler cannot tell who. `UiEventContext(session, element, payload)` carries no viewer |
| The player closes the desktop screen with the machine window open | `CgUiScreen.onGuiClosed` → `suspendDesktop` detaches the compositor without `hide()`, so no `onHidden`, so no `ui/visibility` — the server keeps flushing into a screen nobody can see |
| The desktop evicts a hidden networked window from its retention cap | `WindowRegistry.evictIfNeeded` → `destroy` → `onDestroyed` → `CgUiWindowMount.onFrameDestroyed` → `context.userClosed()` — the server is told **"closed by the user"**, which it was not |
| The server adds one row to the machine panel | Every sibling on the client is **recreated** (full re-description of the anchor), the nested engine panel loses its local state and is never bound again — Appendix A |
| The server changes the window title after open | No message exists for it |
| A mod's server sheet uses `#label { … }` | Applied to the **one** style engine, unscoped, never removed: it styles the editor's `#label` too, and every open appends a fresh parsed copy at highest priority |
| Anything wants a list of 10,000 rows | There is no virtualised widget on the wire; `ListView`, `TreeView`, `TableView`, `GraphView`, `TextEditor` and everything under `elements/{canvas,chrome,config,dock,editor,graph,inspector,list,table,tree,workbench}` is **not registered** — 23 tags describe, ~60 element classes exist |

None of these is a slip in a handler. They are what the model cannot express, and they are why the
answer is a rewrite rather than a list of fixes.

---

## 1. What a CrystalGUI UI can do — and what the wire can say

The requirement is that the replacement supports *all* of it. This is the inventory the design is
checked against. ✔ expressible today · ◐ partly · ✘ not at all.

### 1.1 Structure and identity

| Capability | Wire today | Notes |
|---|---|---|
| Build a tree of widgets | ✔ | 23 registered tags only |
| Composite widgets with internal children | ✔ | internals rebuilt by constructors, never described |
| Add/remove/move elements after open | ◐ | full re-description of the anchor's children; no move; siblings recreated |
| Set/change `id`, classes | ✘ after open | description only; `dirtyIdentity` never flushed |
| Enable/disable, `inert`, hit-test, focus policy | ✘ after open | same |
| Nested `Networked` panels, id-path scopes | ✔ | Part VII |
| Every element class in `ui.elements.**` | ✘ | ListView, TreeView, TableView, GraphView/GraphNode/NodePort, CanvasView, TextEditor, SearchField, MarkupView, InputDialog, the whole `chrome`, `config`, `inspector`, `dock`, `workbench` packages are undescribable |

### 1.2 State and events

| Capability | Wire today | Notes |
|---|---|---|
| Server-driven widget state | ◐ | 12 widgets implement `writeState`: Button, Checkbox, ColorSelector, Dropdown, ProgressBar, Slider, SplitView, Switch, Tab, TabView, TextField, UIText |
| Client interaction reported | ◐ | 4 kinds on 5 widgets. Dropdown/TabView/ColorSelector/SplitView/Tab report nothing |
| Rate policy on reports | ✘ | per keystroke, per slider tick |
| Typed, validated payloads | ✘ | handlers read raw floats/strings; nothing consults the widget's range/step/length |
| Keyboard events (keys, chords, Enter/Escape semantics) | ✘ | |
| Mouse: right-click, double-click, hover, pointer position, wheel | ✘ | `activate` is the only gesture; a server canvas cannot know where it was clicked |
| Focus/blur, focus-visible | ✘ | focus policy travels once; focus itself never |
| Scroll position, selection | ✘ (view state) | correctly local — but a server cannot *ask* for `scrollIntoView` |
| Drag and drop (source, payload, drop target) | ✘ | `UIDragController` is local-only |
| Close request / veto ("unsaved changes") | ✘ | `WindowFrame.setDiscardGuard` exists locally; the wire has no `canClose` |
| Undo/redo | ✘ | `UndoStack` is per document; no networked document |

### 1.3 Server-driven view commands

| Capability | Wire today |
|---|---|
| Focus an element | ✘ |
| Scroll an element into view | ✘ |
| Show/hide a `Dialog` (modal or not) | ✘ — `dialog` decodes, `show()` is client-only, no state |
| Open a `Menu`/`ContextMenu` at an anchor, a `Popover`, a `Tooltip` | ✘ — `Tooltip.attach` is a Java API with no state |
| Change window title / icon | ✘ after open |
| Request window geometry / size / minimum size | ✘ — the frame sizes to content |
| Bring window forward | ✔ `ui/focusWindow` |
| Close window with reason | ✔ |
| Notifications / toasts | ✘ (the chrome has `NotificationsView`; nothing reaches it) |

### 1.4 Styling and assets

| Capability | Wire today | Notes |
|---|---|---|
| Inline styles in the description | ◐ | only value types in `StyleValueCodecs.BY_TYPE`; a drawable throws |
| Inline style changes after open | ✘ | |
| Named sheets (hash + optional resource id), fetch on miss | ✔ | good design |
| Sheet lifetime / scoping | ✘ | global engine, never removed, ids unscoped |
| Themes, `var(--token)`, transitions, transforms | ✔ via sheets | the cascade is the client's |
| Icons/sprites/SVG referenced from a server sheet | ✔ | resolved against the client's resource packs — also means a hostile sheet can reference any client asset |

### 1.5 Windows, desktop, workbench

| Capability | Wire today | Notes |
|---|---|---|
| Open as a desktop window with title/key | ✔ | |
| Minimise → suppress traffic; restore → flush | ✔ | but session-wide, not per viewer |
| Screen closed → suppress | ✘ | `suspendDesktop` emits no `onHidden` |
| Retention eviction | ◐ | reported as a user close |
| Owner/owned window relation | ✘ | no `openedBy` on the wire |
| Tear-out into own frame / Dialog / Tab | ◐ | a `Detached` prototype was built, measured and reverted; Appendix A is the design |
| Networked panel as a **workbench tool window / dock panel** | ✘ | `DockPanelRegistry.register(descriptor, factory)` needs a local factory producing content; nothing bridges a described root into a `ViewContainer` |
| Multi-viewer (one session, many players) | ◐ | works; no viewer identity in events, one visibility flag, `call()` ambiguous |
| Re-open of a hidden window with the same key | ✔ | |

### 1.6 Transport and robustness

| Property | Today |
|---|---|
| Fragmentation, credit window, reassembly cap (8 MB), RESET both ways | ✔ good (`FrameMultiplexer`) |
| Varint, depth (512) and length checks on decode | ✔ good (`BinaryFormat`, `FrameCodec`) |
| Per-message error isolation in the router, once-only responders, unknown-method warn-once | ✔ good (`MessageRouter`) |
| Cap on windows per connection | ✘ | a server can open frames until the client dies |
| Cap on elements per description / bytes per description | ✘ | 8 MB of description ≈ hundreds of thousands of `UIElement`s + Taffy nodes on the client thread |
| Cap on description/sheet cache | ✘ | `descriptionCache`, `SheetSupply.cache` unbounded |
| Rate limiting of `ui/event`, `ui/description`, `ui/sheet` | ✘ | |
| Hash verification of a received description | ✘ | cached under the hash the server *said* |
| Client → server handler leak on element removal | ✘ | `ServerUiSession.handlers` keyed by element, never removed on detach |

---

## 2. Findings by layer

Severity: **S** security/abuse · **C** correctness · **F** missing feature · **P** performance ·
**D** design smell. Each carries the production reference that settles it.

### 2.1 Wire — `net.wire`, `Mc1710NetworkChannel`, `CgUiConnections`

Keep this layer. It is the best-built part of the stack: round-robin fragmentation under a credit
window, reassembly cap with RESET in both directions (with the measured corruption the one-way
version caused), varint/length/depth validation that refuses rather than crashes.

| # | Finding | Sev | Reference |
|---|---|---|---|
| W1 | **Join race.** `ClientConnectedToServerEvent` fires before the server's `PlayerLoggedInEvent`; `CgUiConnections.route` drops frames from an unknown sender **silently**. Anything a client contributor sends at open is lost. Today nothing does; the first contributor that does will debug it for a day | C | X11 and Wayland both make the *server* allocate the connection before the client may speak; Minecraft's login sequence gates play packets on `LOGIN` completing. Fix: the server opens the peer at `PlayerLoggedInEvent` *and* sends a hello; the client's connection is not "open" until the hello arrives |
| W2 | `maxFrameBytes` 32,766 is the 1.7.10 custom-payload limit and correctly propagated | ✔ | |
| W3 | No inbound byte budget beyond credit; `arrived` is unbounded between ticks. Credit is only replenished after `pump()` delivers, so a well-behaved peer is bounded by 256 KB — but a peer ignoring credit is bounded only by the reassembly cap | P | Netty `LengthFieldBasedFrameDecoder` + channel read watermarks; Blazor `MaximumReceiveMessageSize` (32 KB default) |
| W4 | Netty thread → `ConcurrentLinkedQueue` → tick thread: correct | ✔ | |

### 2.2 Protocol — `net.protocol`

Keep the envelope (four kinds, LSP-shaped, string methods) and `Protocols` (lambda contributors,
sided registration). Findings are about limits, not shape.

| # | Finding | Sev | Reference |
|---|---|---|---|
| P1 | No rate limit on any inbound method; a client can request `ui/description` or `ui/sheet` in a loop and the server re-encodes on each | S/P | LiveView `phx-throttle`; Minecraft kicks on packet flood; Chromium `ReportBadMessage` kills the sender |
| P2 | `serving` grows for any request a handler forgets to answer | P minor | |
| P3 | Method-level payload size caps do not exist; the only cap is the 8 MB reassembly ceiling | S | Blazor per-message cap; CDP has none but owns both ends |
| P4 | `Cancel` is best-effort by design — fine | ✔ | |

### 2.3 Sessions — `ServerUiSession`, `ClientUiSession`, `ClientUiSessions`, `UiWindowMux`, `NetworkIds`

This is where the rewrite lives.

| # | Finding | Sev | Reference |
|---|---|---|---|
| S1 | **Positional identity.** Ids are a document-order walk re-derived after every structural change; every consequence is in Appendix A (recreated siblings, unbound nested panels, global renumber, global count check, the `Detached` shadow tree) | D/C | Fabric tags, Unreal `FNetworkGUID`, CDP `nodeId`/`backendNodeId`, X11 XIDs — identity allocated once, hierarchy as data |
| S2 | **Identity changes never flush.** `dirtyIdentity` is collected and dropped. `setEnabled(false)` after open is invisible | C | CDP `DOM.attributeModified`; Turbo `update` |
| S3 | **Inline style is snapshot-only** by design ("computed styles are churn") — right for computed, wrong for *authored inline*, which is the one origin the server owns | F | CDP `CSS.styleSheetChanged`; LiveView diffs attributes |
| S4 | `InlineStyleCodec` **throws** for a property with no codec, and the throw happens inside `open()` or `flush()` — a whole window fails because one element set `background` inline. Loud is right; *where* it is loud is wrong (author time, not open time) | C | A schema check belongs at `UiType.build`/panel validation, before anything is sent |
| S5 | **Event dispatch validates nothing** — not `isEnabled`, not `isInert`, not that the kind belongs to the element's class, not the payload's range/type. NaN poisons a model; a disabled button activates | S | Minecraft `AbstractContainerMenu.clicked` validates slot index, button and click type server-side and `stillValid` each tick; Chromium: "the browser process must be maximally suspicious of its IPC inputs" |
| S6 | `UiEventContext` carries no **viewer**; per-viewer permissions and attribution are impossible | F | Minecraft's container handlers receive the `ServerPlayer`; Unreal RPCs carry the owning connection |
| S7 | `viewerVisible` is **one flag for all viewers** — any viewer minimising suppresses everyone's deltas | C | per-connection state in every netcode system |
| S8 | `handlers` map is keyed by `UIElement` and never pruned on detach — a churned list leaks a closure per row for the session's life | P | |
| S9 | No cap on **elements per description**, **bytes per description**, **windows per connection**, **cache size**; the client builds whatever arrives on its frame thread | S | Blazor `MaximumReceiveMessageSize`, `DisconnectedCircuitMaxRetained`, `MaxBufferedUnacknowledgedRenderBatches`; protobuf recursion limit; Minecraft: one container at a time |
| S10 | The client caches a description under the hash the server **stated** rather than the hash it **computed** — harmless per session, wrong the moment the cache is shared across servers (a future disk cache, like a browser's) | S minor | Content-addressed stores hash what they store (git, Nix) |
| S11 | `expectedElementCount` skew refuses the window — correct — but the count includes internal children, so a client whose `Button` has one more internal label than the server's is refused although nothing addresses internals (Appendix A) | C | Count *described* elements |
| S12 | Hidden-window suppression gates the **whole** flush because renumbering is global; correct under S1 and moot without it | D | |
| S13 | `title`, `key`, `sheets`, `ua` are fixed at open; `setTitle` after open re-sends only if a structure flush happens to rebuild the payload — and then only to *late* viewers | F | X11 `WM_NAME` property change; every toolkit's `setTitle` |
| S14 | `ClientUiSessions` accepts any window id the server names, unbounded; `UiWindowMux` warns once and drops for unknown windows — fine | ◐ | |

### 2.4 Widgets meeting the wire — `wireReportedEvents`, `writeState`, `UiEventKinds`

| # | Finding | Sev | Reference |
|---|---|---|---|
| E1 | **Four event kinds**, hard-coded per widget class in `ClientUiSession.wireReportedEvents` with a `switch` on `instanceof`. Adding a widget means editing the session | D | Fabric: each component declares its event emitters; RFW: events are named per widget in the library |
| E2 | Dropdown, TabView, ColorSelector, SplitView, Tab: writable, **unreportable** | F | |
| E3 | Text per keystroke, slider per change; no `commit`, `debounce`, `throttle` policy; the server's echo is what `shouldSuppress` exists to fight | P/C | LiveView `phx-debounce="blur"`/`phx-throttle`; Blazor `@bind:event="onchange"` vs `oninput`; Minecraft sends slider-like values (e.g. `ServerboundSetBeaconPacket`) on **commit** |
| E4 | No keyboard, pointer position, wheel, hover, right/double click, drag & drop, focus, close-veto | F | CDP `Input` domain; RFW event args; LiveView `phx-keydown`, `phx-focus`, `phx-blur`, `phx-window-keydown` |
| E5 | State is untyped `StateMap` read back by hand in every `readState`; there is no schema a validator or a tool could read | D | Fabric `Props` are typed C++ structs generated from a spec (codegen); Unreal replicated properties are declared with `UPROPERTY(Replicated)`; Minecraft `EntityDataAccessor<T>` is typed |
| E6 | The description carries `events` (which kinds to report) but the *policy* (rate, commit) is unexpressible | F | LiveView attributes carry the policy in the markup |

### 2.5 Styling — `InlineStyleCodec`, `SheetSupply`, `CgUiWindowMount.sheetSupply`

| # | Finding | Sev | Reference |
|---|---|---|---|
| Y1 | Server sheets are parsed and **appended to the one global `StyleEngine`** on every open, **never removed**, and re-adding a sheet appends at highest priority (AGENTS.md's own rule). A session of opening/closing a window is a monotonic leak of parsed sheets and a priority creep | C/P | Blazor CSS isolation rewrites selectors with a `[b-xxxx]` attribute per component; Shadow DOM scopes by tree; CSS `@scope` |
| Y2 | Ids and classes in a server sheet are **document-wide**: `#label` from mod A styles mod B's `#label` and the editor's; a hostile server restyles the whole desktop | S/C | same |
| Y3 | A server sheet may reference any client asset (`icon("crystalgui:…")`) — acceptable (the client owns its assets) but worth a rule: a sheet may not reference a *filesystem* path | S minor | browsers: `file://` refused from network origins |
| Y4 | The sheet model itself (hash + optional resource id, fetch on miss, `useUserAgentSheet`) is right and stays | ✔ | HTTP caching by digest; subresource integrity |

### 2.6 Window layer — `Networked`, `UiType`, `ServerScope`/`ClientScope`, `ServerWindow(s)`, `ClientWindows`, `WindowMount`

Keep the **authoring surface**: `Networked<M>` (panel is an element, model as parameter,
side-specific methods), `UiType.of` as `customElements.define`, id-path scopes, `Protocols.server/
client`. It is one session old and it is right. Findings are underneath it.

| # | Finding | Sev | Reference |
|---|---|---|---|
| N1 | **Tag = lowercased simple class name.** Two mods with an `EnginePanel` collide at registration; a panel's cascade identity is a Java class name rather than its namespaced type id | D/C | Custom elements *require* a hyphen and the convention is a namespace prefix; Minecraft registries are `ResourceLocation`s. Derive the tag from the `UiType` id: `crystalgui:machine` → `crystalgui-machine` |
| N2 | `ensureUiClass` initialises a **server-named** class, guarded to `Networked` implementors — any mod's client panel can be driven by any server with an arbitrary description; the client never checks that the class's own `TYPE.id()` equals the wire `type` | S | Chromium: content from the network is untrusted by construction; a **type → class** binding registered by the *client* mod (a manifest), or at minimum the class asserting its id, is the boundary |
| N3 | `bindPanels` runs only from `present()`; a nested panel arriving in a tree delta is never bound; a re-describe rebinds `bound()` but not `client()` (stale closures) | C | Fabric mounts per mutation |
| N4 | No close **veto**: `WindowFrame.setDiscardGuard` exists and is never wired to the wire; eviction and the X both end as "closed by the user"; the server cannot say "ask before closing, there are unsaved changes" | F/C | ICCCM `WM_DELETE_WINDOW`: the WM *asks*, the client decides; browsers' `beforeunload` |
| N5 | No owner relation on the wire (`openedBy`); a server cannot open a child window that floats above and minimises with its parent | F | Win32 owner/owned; X11 `WM_TRANSIENT_FOR` |
| N6 | No window geometry hints (preferred size, min size, resizable, position class) — the frame sizes to content and remembers by key | F | X11 `WM_NORMAL_HINTS`; xdg-shell `set_min_size`/`set_max_size` |
| N7 | `Detached`/`DetachedWindow`: a prototype built and reverted this session; Appendix A replaces it | D | |
| N8 | `ClientWindows.waiting` queues windows that arrive before a mount exists — right; but a window that arrives while the desktop is *suspended* mounts into a detached compositor with no visibility report (§2.7) | C | |
| N9 | `ServerWindows.tick` runs every window's `stillValid` and `tick` per connection per server tick, serially, with per-window exception isolation — right. `open` on a Netty thread is impossible because contributors run in `tick` — right | ✔ | |

### 2.7 Desktop and Workbench meeting the wire

| # | Finding | Sev | Reference |
|---|---|---|---|
| K1 | `onGuiClosed` → `suspendDesktop` detaches without `hide()`: no `onHidden`, no `ui/visibility`. The server flushes into a screen that is closed | C | Page Visibility API: `visibilitychange` fires on tab hide, not only on minimise |
| K2 | Retention eviction reports `userClosed` — the server-side `closed("CLIENT")` reason is a lie, and a content-authored close policy (N4) cannot distinguish | C | bfcache eviction is not `unload`; Blazor `DisconnectedCircuitRetentionPeriod` expiry is distinct from a user leaving |
| K3 | `Dialog` inside a described tree: decodes, finds its owning `WindowFrame` (`WindowFrame.of(this)` → `attachOwned`), modal scoping works — **locally**. The server cannot show it, and a client-shown one reports nothing | F | |
| K4 | `TabView` inside a described tree: server can select (`writeState selected`), client selection unreported (E2) | F | |
| K5 | A networked panel **cannot be a workbench citizen**: `DockPanelRegistry.register(descriptor, factory)` builds content locally from a `DockPanelRef`; there is no descriptor kind "described by a server session". So "open the machine as an editor tab" or "as a tool window" has no path | F | IntelliJ tool windows are declared by descriptor with a factory; VS Code webview panels are exactly a *remote* panel type registered into the workbench (`WebviewPanel`, `WebviewView`) — the model to port |
| K6 | Tear-out/Dialog/Tab presentation of a fragment — Appendix A: reparenting under stable ids, placement record in the presentation layer | D | |
| K7 | Focus: `FocusPolicy` travels once; `requestFocus` is local; the desktop's click-focus, modality and Tab rules all work on described trees because they are ordinary trees — right | ✔ | |
| K8 | Drag & drop within a described tree works **locally** (it is the client's input handler) but nothing is reported and a server cannot declare a drop target | F | |
| K9 | Context menus / commands: `RemoteCommands` lets a server contribute commands under `server.*` with a policy, caps and label sanitising — the one part of the stack with an explicit **security policy object**. Keep; extend the model to events and sheets | ✔ | |

### 2.8 Serialization — `serialization/*`

| # | Finding | Sev | Reference |
|---|---|---|---|
| Z1 | `BinaryFormat`: depth 512, length-vs-available check, varint bounds — good | ✔ | protobuf/Netty |
| Z2 | `ContentHash` is SHA-256 over a canonical form — good | ✔ | |
| Z3 | `UIDescriptionCodec` throws on an unknown tag — right — but has **no element count limit**, and decode allocates a `UIElement` + Taffy node per element on the frame thread | S | S9 |
| Z4 | Description encoding sorts inline properties by name and omits absent optionals — hash stable — good | ✔ | |
| Z5 | No **schema/version per widget**: a client whose `Slider.readState` expects `"value"` and a server writing `"v"` disagree silently | D | Fabric codegen; Minecraft `StreamCodec`s per packet |

---

## 3. Abuse paths, stated as attacks

Because "the client is untrusted" is the rule in every reference and the stack does not yet act on
it.

| Vector | Today | Rewrite |
|---|---|---|
| Client forges `ui/event` for a disabled/inert/hidden element | dispatched | server checks the element's own `isEnabled()`/`isInert()`/attached-and-numbered before dispatch; refusal is **counted** and a threshold kicks (Chromium `ReportBadMessage`) |
| Client forges an out-of-range or `NaN` payload | model poisoned | the **widget** decodes its own event (`Slider.decodeEvent` clamps via `clampAndSnap`, `TextField` enforces max length); handlers receive typed values |
| Client floods `ui/event`, `ui/description`, `ui/sheet` | server re-encodes per request | per-connection token bucket per method; description/sheet answered from the cached encoding (already true for description) |
| Server opens unbounded windows / elements / sheets | client OOM on the frame thread | caps: windows per connection, elements per window, bytes per description and sheet, cache entries; refusal closes the window, never the game |
| Server names an arbitrary `Networked` class | initialised and driven | client-side manifest of `type id → class`; the class asserts its id; unknown type opens as a bare tree with no client half |
| Server sheet restyles the desktop | yes | scoped sheets (§4.5); a sheet may not escape its window's root |
| Deep or huge description | depth 512 ok; count unbounded | count cap |

---

## 4. The rewrite

The shape follows from §1–§3, and from one sentence that every reference agrees on: **a networked UI
is a document mirrored over a wire with stable node identity, typed per-widget contracts, and an
explicit presentation layer on the client.** CDP's DOM domain is the closest published protocol to
what this must be; Fabric and RFW are the closest runtimes; Minecraft's container pipeline is the
closest host.

### 4.1 What survives unchanged

- `net.wire` in full (the frame codec, the multiplexer, the channel SPI, the 1.7.10 channel).
- `net.protocol` in full: the four-kind envelope, `MessageRouter`, `ProtocolConnection`,
  `Protocols` with lambda contributors and sided registration, `UiMethods`' namespacing rule.
- The **authoring model**: `Networked<M>`, `UiType.of`, `ServerScope`/`ClientScope`, id-path
  namespacing, `ServerWindows.of(wire).open(TYPE, model)`, `ClientWindows` as the mount host —
  the *model*; five points of its API change with the structure beneath them, itemised in §4.11.
- `BinaryFormat`, `ContentHash`, `StateMap`'s API, `SheetRef`, `RemoteCommands` and its policy.
- The headless-test discipline and the loopback fixtures.

### 4.2 What is scrapped

- `NetworkIds` as a positional walk; `expectedElementCount` as the integrity check.
- `ui/treeDelta` (full re-description) and the anchor logic.
- The `dirtyIdentity` set and the idea that identity is snapshot-only.
- `wireReportedEvents`' `switch` and the four-kind `UiEventKinds`.
- `Detached`, `Detachment`, `DetachedWindow`, the logical splice.
- Global sheet application in `CgUiWindowMount.sheetSupply`.
- `ServerUiSession`/`ClientUiSession` as they stand — replaced by the mirror below. Their public
  method names can stay where they are right (`on`, `onActivate`, `onCall`, `call`, `notify`).

### 4.3 The document mirror — L2

One concept replaces sessions' tree handling: a **mirror** of a document over a wire, CDP-shaped.

- **Identity.** Every described element gets an id from the server at the moment it joins the
  described tree — walk-derived at open (content-addressed description unchanged), counter-allocated
  after. Internal children are not numbered. Lookup is a map on both sides. (Appendix A.)
- **Ops, not snapshots.** `UITreeObserver` already yields the edit script. The wire carries
  `insert{parent, index, subtree, base}`, `remove{id}`, `move{id, parent, index}` — Turbo Streams'
  `before/after/remove`, CDP's `childNodeInserted/Removed`, Fabric's `Insert/Remove`. A late viewer or
  a re-delivery gets a **live snapshot** (description with ids).
- **Attributes are deltas.** `setAttr{id, name, value}` for id, classes, enabled, inert, hitTest,
  focusPolicy — CDP `attributeModified`. `dirtyIdentity` becomes a real queue.
- **Inline style is a delta.** `setStyle{id, prop, value}` at INLINE origin only; computed styles stay
  unobserved. A property with no codec is refused at **author time** (`UiType.build` validates, and
  the `LayoutGroup`/`GeneralGroup` write path can refuse on a networked element) rather than at open.
- **State is a delta with a schema.** Each widget declares its state fields once (name, type,
  default) and its event kinds once (name, payload type, rate policy) in a `WidgetContract` the class
  registers beside its tag. `writeState`/`readState` are generated from it or checked against it;
  `wireReportedEvents` becomes a lookup, not a `switch`. Fabric's codegen'd `Props` and Minecraft's
  `EntityDataAccessor<T>` are the shape. Unknown fields are ignored, missing fields default — which is
  what makes **version skew** degrade instead of refuse.
- **Integrity** per op: described-element count of the inserted subtree; refusal is per op and closes
  the window with a reason, never the connection.
- **Visibility per viewer**; deltas queue per viewer while hidden and replay on show (keeping the
  client's instances), with a per-viewer byte cap after which a live snapshot replaces the queue.

### 4.4 Widget contracts and events — L3

- Every registered element carries a `WidgetContract`: tag (namespaced), state schema, event
  schema, **default rate policy per event** (`text: debounce 150ms + commit on blur/Enter`;
  `value: throttle 50ms + commit on release`; `activate: none`), and whether the element is
  **user-interactive** (so a disabled/inert one refuses events server-side by contract, not by
  handler).
- The server may override the rate policy per element in the description (LiveView
  `phx-debounce`/`phx-throttle` are markup attributes for this reason).
- Event payloads are **decoded by the widget class on the server** (`Slider.decodeEvent` clamps and
  snaps; `TextField` enforces its max length; `Dropdown` checks the index) and delivered typed.
- New kinds, each optional per element and each declared in the description so a client reports
  only what was asked for: `select` (Dropdown/TabView/ListView), `commit`, `change`, `key`
  (with modifiers, on elements that opted in), `pointer` (button, position in element space — what
  a canvas needs), `wheel`, `focus`/`blur`, `drag`/`drop` (payload as a typed `StateMap`; drop targets
  declared per element), `closeRequested` (the veto path, N4), `contextMenu`.
- Every event carries the **viewer**; handlers get `UiEventContext(session, viewer, element, typed
  payload)`.
- **Every element class in `ui.elements.**` gets a contract or is explicitly marked local-only**
  (`TextEditor`, `GraphView`, the workbench chrome) with the reason. That is the coverage requirement
  made checkable: a test enumerates `ElementRegistry` and every class under `ui.elements` and fails
  on one that is neither contracted nor marked.
- **Virtualised collections** are a wire feature, not a widget: a `ListView`/`TreeView`/`TableView`
  contract whose rows are a *stream* — the server sends a row count and a row template, the client
  requests `rows{from, to}` as it scrolls, rows arrive as ordinary described subtrees keyed by a
  stable row key (LiveView streams, RN `FlatList`, VS Code's tree data provider). Inventories, logs
  and file lists are impossible without it.

### 4.5 Server-driven view commands — L4

A small, closed vocabulary of **client-side effects** a server may request, each answered
(`ok`/`refused`) so a server can tell "done" from "ignored": `focus{id}`, `scrollIntoView{id}`,
`showDialog{id, modal}`/`hideDialog{id}`, `openMenu{id, anchorId}`, `tooltip{id, text}`,
`setTitle{text}`, `setIcon{ref}`, `geometryHint{minW, minH, prefW, prefH, resizable}`,
`notify{severity, text}` (into `NotificationsView`), `requestClose{reason}` (which runs the client's
veto path). LiveView's `push_event`/`JS` commands and Blazor's `ElementReference.FocusAsync` are the
precedents; CDP's `DOM.focus`/`DOM.scrollIntoViewIfNeeded` the protocol shape.

### 4.6 Styling — L6

- A server sheet is **scoped to its window** before it enters the engine: every selector is
  rewritten to be rooted at the window's root element (Blazor's `[b-xxxx]` attribute rewrite; CSS
  `@scope`), so `#label` means *this window's* `#label`. The rewrite happens at parse, once per hash.
- Sheets are **refcounted by hash** across windows and **removed** when the last window using one
  closes. Re-adding is impossible by construction, so priority creep goes away.
- The UA sheet, themes and schemes are the client's and untouched.
- A sheet may reference client assets by resource id only.

### 4.7 Windows, viewers, presentation — L5/L7

- `ServerWindow` keeps its handle shape; adds `setTitle` (live), `owner` (`openedBy` on the wire →
  `setOwnerWindow` on the client), `geometryHint`, and the **close matrix gains a veto**:
  `closeRequested` → client asks the content (`WindowFrame.discardGuard` wired to the panel's
  `canClose()`), answers; eviction is a distinct reason (`RETENTION`), never "CLIENT".
- Visibility is reported for **every** way a window stops being seen: minimise, screen suspend
  (`suspendDesktop` emits it), and the frame being covered is *not* one (as browsers do not report
  occlusion).
- Presentation is the client's and needs no networking vocabulary: a mount puts the root in a
  `WindowFrame`; a fragment is reparented into a frame/`Dialog`/`Tab` by ordinary engine calls with a
  placement record in the presentation layer (Appendix A §A.3).
- **Workbench citizenship**: a `DockPanelDescriptor` kind whose factory mounts a networked root into
  a `ViewContainer` — VS Code's `WebviewPanel` — so a server can open "as an editor tab" or "as a tool
  window", and the workbench's own undock/dock/session-restore machinery applies. Session restore of
  a networked panel = re-asking the server by key on the next connection.
- Multi-viewer: per-viewer visibility, viewer in every event, `call()` → `callViewer` only.

### 4.8 Security posture, made structural

- **Server side is authoritative and suspicious**: every inbound `ui/*` message is validated against
  the mirror (element exists, is numbered, is interactive, kind declared for it, payload decoded by
  the widget), refusals are counted per connection, a threshold closes the connection with a logged
  reason. Chromium's `ReportBadMessage` and Minecraft's "kicked for bad packet" both do exactly this.
- **Client side treats the server as a content source, not a peer**: caps on windows, elements,
  bytes, sheets, cache; a client-side **manifest** binds type ids to panel classes so a server cannot
  pick classes; sheets cannot escape their window; assets by resource id only.
- Limits are **configuration with defaults**, logged when hit, never silent.

### 4.9 Versioning

Replace the single `EnvelopeCodec.VERSION` refusal with **capability negotiation at open**: the
client's hello lists the tags and event kinds it supports and its contract versions; the server
describes only what the client can decode and marks the rest as absent — an older client renders a
simpler window rather than none. Unknown state fields ignored, missing fields defaulted (§4.3).
That is how LiveView, Blazor and every browser survive skew.

### 4.10 Testing the rewrite

Keep the headless suite as the spine; add what the audit found missing:
- **Hostile-description fuzzing**: random tags, depths, counts, NaNs, huge strings — the client must
  refuse or degrade, never throw on the frame thread.
- **Traffic assertions** as the default for every server-side behaviour (idle = silent; keystroke =
  one debounced event; slider drag = commit).
- **Two-viewer fixtures** for every server-side feature (visibility, attribution, permissions).
- **Contract coverage**: every element class contracted or marked local-only.
- **Version-skew fixtures**: a client missing a tag or a field.
- `serverSmoke` stays the loader-seam check; add a `clientSmoke` that opens every registered
  contract's widget over a loopback and asserts round-trips.


### 4.11 The authoring surface — what changes for the person writing a panel

§4.1 said the authoring surface "does not change". That was too blunt. The *model* does not change:
a panel is an element, the model is a parameter, a field declaration is the declaration, scopes
compose by id path, one `open` call. Those survived five rewrites because they are right. But five
points of the API sit directly on structure the audits condemn, and an API that keeps its shape while
its meaning changes underneath is the worse outcome. Keep / change, with the reason:

| Surface | Verdict | Why |
|---|---|---|
| `Networked<M>` as an interface on an element; `layout(M)`, `serve(M, io)`, `tick(M)`, `stillValid`, `key` | **Keep** | The side boundary is in the signatures and the panel is a node; the three-tree engine changes what a node *is* underneath, not what a panel author writes |
| `bound()` **and** `client(io)` as two hooks | **Collapse into one `client(io)`** | The split exists only because a re-describe replaced every instance, so widget listeners died and session registrations must not re-run. Under stable ids and ops (§4.3) an instance is never replaced. One hook, once, at mount |
| `io.on(element, "value", ctx -> ctx.payload().getFloat("value", 0))` | **Replace with typed events** — `io.on(throughput, Slider.VALUE, (viewer, value) -> …)` | The string kind and hand-parsed `StateMap` are the untyped, unvalidated, viewer-less events of §2.3 S5/S6 and §2.4 E1/E5. `Slider.VALUE` is an `Event<Float>` the widget contract declares; the value arrives clamped by the widget, and the viewer arrives with it |
| Rate policy | **New**, on the element in `layout`: `throughput.report(Slider.VALUE, Rate.commitOnRelease())` | §2.4 E3/E6 — the description carries it, LiveView-style |
| View commands | **New** on `ServerScope`: `io.focus(el)`, `io.scrollIntoView(el)`, `io.showDialog(d)`, `io.openMenu(m, anchor)`, `io.setTitle(…)`, `io.notify(severity, text)`, `io.requestClose()` | §4.5 — today a server has no way to ask the client to do anything with the view |
| `closed(String reason)` with `CloseReason.name()` stuffed into it | **Typed**: `closed(CloseReason reason)` with `RETENTION`/`EVICTED` distinct from `CLIENT` | §2.7 K2 — the string was a smell and the eviction lie needs a value to be honest with |
| `title(M)` read once at open | **Keep**, plus `io.setTitle` for changes | §2.3 S13 |
| `UiType.of(id, ctor)`; the field walk; `build`/`bind` | **Keep the walk and the build**; **tag from the id** (`crystalgui:machine` → `crystalgui-machine`); the class **asserts its id** and the client resolves type → class through a manifest | §2.6 N1/N2 — a Java simple name is not an element name and the server must not pick client classes |
| `ServerScope.attach(child, slice)`, id-path prefixes, `qualify` | **Keep** | Stable ids would allow scoping by node id, but the path is what appears in logs and tests; readability wins |
| `io.sheet(ref, css)` | **Keep the call, change the meaning**: the sheet is scoped to the window and refcounted | §4.6 |
| `ServerWindows.of(wire).open(TYPE, model)`; `ServerWindow` handle | **Keep**; handle gains `setTitle`, `owner`, `geometryHint`, `viewers()` | §4.7 |
| `ClientScope.call/notify/onCall/onNotify`, `io.window()` | **Keep**; `io.window()` loses `tearOut`/`detached` and exposes the desktop's own presentation calls (undock, owned dialog) as plain engine operations | Appendix A §A.3 |
| `WindowMount` | **Shrinks**: `mount`, `closedByServer`, `focus`; no `contentReplaced` (instances are never replaced), no `detach` | §4.3, Appendix A |
| `Protocols.server/client/contribute`, the envelope, `ProtocolConnection` | **Keep** | §4.1 |
| **Writing a widget**: `writeState`/`readState` by hand, `addReportedEvent`, a `case` in `wireReportedEvents` | **Replace with a declared contract** — `State` fields and `Event<T>` constants on the class; the engine derives read/write/report | §4.4 — this is the biggest authoring change and it lands on widget authors, not panel authors |
| Inline geometry from Java (`layout(l -> l.width(90))`) | **Keep** as authored inline style | Engine-core §3 removes the *engine's* `IMPORTANT` writes, not an author's inline declarations |

The same panel, on the other side. Every line marked `//*` is one of the changes above; every
unmarked line is today's code:

```java
public final class MachinePanel extends UIElement implements Networked<MachineModel> {

    public static final UiType<MachinePanel, MachineModel> TYPE =
            UiType.of("crystalgui:machine", MachinePanel::new);           // tag: crystalgui-machine  //*

    public Switch power;
    public Slider throughput;
    public TextField label;
    public ProgressBar progress;
    public Button purge = new Button("Purge");
    public EnginePanel engine;

    @Override public String title(MachineModel m) { return "Machine control"; }
    @Override public String key(MachineModel m)   { return "crystalgui:machine"; }

    @Override public void layout(MachineModel m) {
        addChild(MachineRows.row("Power", power));
        addChild(MachineRows.row("Throughput", throughput));
        throughput.report(Slider.VALUE, Rate.commitOnRelease());               //*
        label.report(TextField.TEXT, Rate.debounce(150).andOnBlur());          //*
        addChild(MachineRows.row("Label", label));
        addChild(MachineRows.row("Cycle", progress));
        addChild(purge);
        engine = EnginePanel.TYPE.build(m.engine());
        addChild(engine);
    }

    @Override public void serve(MachineModel m, ServerScope io) {
        io.sheet(MachineStyles.SHEET, MachineStyles.CSS);                       // now scoped to the window
        unsubscribe = m.onChanged(() -> dirty = true);

        io.on(power, Switch.TOGGLED,   (viewer, on)    -> m.setRunning(on));    //* typed, clamped, attributed
        io.on(throughput, Slider.VALUE,(viewer, value) -> m.setThroughput(value));
        io.on(label, TextField.TEXT,   (viewer, text)  -> m.setLabel(text));
        io.on(purge, Button.PRESSED,   (viewer)        -> m.purge());

        io.onCall("rename", (viewer, args, respond) -> {                        //* viewer in every context
            String name = args.getString("name", "");
            if (name.isBlank()) { respond.fail("EMPTY_NAME"); return; }
            m.setLabel(name);
            io.setTitle(name);                                                  //* a view command
            respond.ok(null);
        });

        engine.onRestarted(() -> io.notify(Severity.INFO, "engine restarted"));  //* toast, not a readout
        io.attach(engine, m.engine());
        mirror(m);
    }

    @Override public void tick(MachineModel m) { if (dirty) { mirror(m); dirty = false; } }

    @Override public void client(ClientScope io) {                              //* the one client hook
        this.io = io;
        askStats.attachListener(this::requestStats);
        undockEngine.attachListener(() -> io.window().undock(engine, "Engine")); //* a desktop op, no wire
        io.onNotify("announce", payload -> show(payload.getString("text", "")));
    }

    @Override public void closed(CloseReason reason) {                          //* typed; RETENTION ≠ CLIENT
        if (unsubscribe != null) unsubscribe.run();
    }
}
```

And the widget author's side, which is where the real change lands — `Slider` today writes
`writeState`/`readState` by hand and is one `case` in `ClientUiSession.wireReportedEvents`; under
§4.4 it declares:

```java
public static final State<Float> VALUE_STATE = State.of("value", FLOAT, 0f);
public static final State<Float> MIN = …, MAX = …, STEP = …;
public static final Event<Float> VALUE = Event.of("value", FLOAT)
        .validate((slider, v) -> slider.clampAndSnap(v))    // the server clamps with the widget's own rule
        .defaultRate(Rate.throttle(50).commitOnRelease());
```

and nothing else: the engine derives the description fields, the state deltas, the report wiring and
the server-side validation from the declaration. That is the one place the rewrite asks more of an
author than today — one declaration per state field and per event — and it is what makes every
untyped, unvalidated, unreportable case in §1.2 impossible to write.

### 4.12 `layout(M)` — parts in the constructor, content never

Everything else in the mod builds its structure in the constructor; a `Networked` panel builds in a
hook. That is not an inconsistency, and the rule behind it should be stated once:

**A networked element has two construction paths.** The server constructs and builds; the client
constructs *bare* and the decoder supplies its children. A constructor that built structure would
build it on the client as well, and the decoder would then add the described children on top. The
Custom Elements spec forbids exactly this — a constructor "must not gain any attributes or children",
because the parser, `cloneNode` and upgrade all construct and *then* supply light DOM — while allowing
`attachShadow` in the constructor. So: **parts in the constructor; content from whoever composes.**
The widgets that build in constructors today build *parts* (a `Button`'s label, a frame's caption —
the future shadow tree). A panel's widgets are *content* (server-authored, described, styled by the
window's sheet, mirrored), so they belong in the hook. WPF's `OnApplyTemplate` +
`GetTemplateChild("PART_x")` and Android's inflate + `findViewById` are the same shape as the field walk.

Three things to change; four to keep:

- **Rename it `build(M)`.** `UIElement.layout(Consumer<LayoutGroup>)` is the *style* API on the same
  hierarchy; one word for two unrelated things is a trap this codebase's own rules exist to catch.
  Flutter, Compose and Minecraft's menus all call the hook that produces structure `build`.
- **State the rule on `Networked` and test it**: constructing a panel bare yields no children.
- **Client-local structure goes in `client(io)`** — the `connectedCallback`. Today a locally added
  child is numbered by the next tree delta and the window is refused; under stable ids a local node
  has no id and the mirror ignores it. Name the capability, because the rewrite creates it.
- Keep **run-once-then-mutate** (structure and state are separate channels; a re-render-and-diff
  model would need keyed reconciliation and fight the imperative widget model), keep **`M` as the
  parameter** (structure that depends on the model — a row per slot — is why Minecraft passes extra
  data to the client menu constructor), keep **the description** rather than Minecraft's "both sides
  run the same constructor" (the class is on the client already, so the description is partly
  redundant on open, but it is what makes model-dependent layout, post-open ops, capability
  negotiation and class-less bare trees one mechanism), and leave **a template language** (HEEx,
  Razor, Android XML, RFW) as the door open above the same description format — not part of this.

---

## 5. What this costs, honestly

- The mirror (§4.3) is a rewrite of both sessions and the codec's tree handling: roughly the 2,000
  lines that are `ServerUiSession` + `ClientUiSession` + `NetworkIds` + the delta paths, replaced by
  something of similar size with a schema layer beside it.
- Contracts (§4.4) touch every state-carrying widget once, and add the missing kinds — mostly new
  code, little deletion.
- Scoped sheets (§4.6) need a selector rewriter in `style.sheet` — new, contained.
- View commands (§4.5) are a vocabulary plus client handlers — new, contained.
- Workbench citizenship (§4.7) is a new descriptor kind — contained, and the one item whose design
  should be checked against `plan_windowing.md` before it starts.
- The authoring surface a mod author sees — `Networked`, `UiType`, the scopes, `Protocols` — does
  **not** change. That is the point of having rewritten it last: the layer below it can now be
  replaced without touching a panel.

---

## 6. Order of work, if it is taken up

1. Mirror with stable ids, ops, attribute and inline-style deltas, per-op integrity (§4.3). This is
   Appendix A plus S2–S4. Everything else stands on it.
2. Contracts: schema, typed event decoding, rate policies, server-side validation, viewer in
   context, the missing kinds for the widgets that already carry state (§4.4, S5–S7, E1–E5).
3. Scoped, refcounted sheets (§4.6, Y1–Y2).
4. View commands and the close veto; visibility for every hide path; eviction reason (§4.5, §4.7,
   N4, K1–K2).
5. Caps and rate limits; capability negotiation (§4.8, §4.9, S9, P1, P3).
6. Virtualised collections and the remaining contracts; workbench citizenship (§4.4, §4.7, K5).
7. Delete `Detached`; tear-out as reparenting (Appendix A §A.3).

---

## 7. References

**Remote and mirrored UI**
- Chrome DevTools Protocol, DOM domain — `nodeId`/`backendNodeId`, `childNodeInserted`,
  `childNodeRemoved`, `attributeModified`, `setChildNodes`, `requestChildNodes` depth:
  https://chromedevtools.github.io/devtools-protocol/tot/DOM/
- React Native Fabric render pipeline — tree diffing to atomic `create/update/insert/remove/delete`
  mutations on host views by tag: https://reactnative.dev/architecture/render-pipeline
- React `createPortal` — logical tree vs DOM tree, events bubble through the component tree:
  https://react.dev/reference/react-dom/createPortal
- Hotwire Turbo Streams — `append/prepend/before/after/replace/update/remove` against a target id:
  https://turbo.hotwired.dev/reference/streams
- Flutter Remote Flutter Widgets (`rfw`) — widget library + `DynamicContent` + named events:
  https://pub.dev/packages/rfw
- Android RemoteViews / Jetpack Glance — a constrained cross-process widget set with declared
  actions: https://developer.android.com/develop/ui/compose/glance/interoperability
- Phoenix LiveView bindings — `phx-debounce`, `phx-throttle`, `phx-keydown`, `phx-focus`,
  `push_event`, JS commands, streams for large collections:
  https://hexdocs.pm/phoenix_live_view/bindings.html ·
  https://hexdocs.pm/phoenix_live_view/Phoenix.LiveView.JS.html ·
  https://hexdocs.pm/phoenix_live_view/0.20.4/dom-patching.html
- Blazor Server — `RenderTreeEdit`, the rule against modifying Blazor-rendered DOM, SignalR limits
  (`MaximumReceiveMessageSize` 32 KB, `DisconnectedCircuitMaxRetained`,
  `MaxBufferedUnacknowledgedRenderBatches`), threat mitigation:
  https://learn.microsoft.com/en-us/dotnet/api/microsoft.aspnetcore.components.rendertree.rendertreeedit?view=aspnetcore-7.0 ·
  https://learn.microsoft.com/en-us/aspnet/core/blazor/javascript-interoperability/?view=aspnetcore-10.0 ·
  https://learn.microsoft.com/en-us/aspnet/core/blazor/fundamentals/signalr?view=aspnetcore-10.0 ·
  https://learn.microsoft.com/en-us/aspnet/core/blazor/security/interactive-server-side-rendering?view=aspnetcore-9.0

**Identity and hierarchy in netcode**
- Unreal — `FNetworkGUID` object references; `FRepAttachment` (attachment as a replicated property):
  https://docs.unrealengine.com/4.26/en-US/InteractiveExperiences/Networking/Actors/Properties/ObjectReferences ·
  https://api.unrealengine.com/INT/API/Runtime/Engine/Engine/FRepAttachment/index.html
- Unity Netcode for GameObjects — `NetworkObjectId`, `ParentSyncMessage`, `TrySetParent`:
  https://docs-multiplayer.unity3d.com/netcode/1.0.0/advanced-topics/networkobject-parenting/
- Godot `MultiplayerSynchronizer` — path identity breaks on reparent (the cautionary case):
  https://github.com/godotengine/godot/issues/86501
- Kleppmann et al., *A highly-available move operation for replicated trees*:
  https://martin.kleppmann.com/papers/move-op.pdf
- Wayland protocol — object id ranges, `new_id`: https://wayland.freedesktop.org/docs/html/ch04.html

**Server authority and validation**
- Minecraft container menus — `stillValid` per tick, `clicked` validation, `broadcastChanges`
  dirty-diff to listeners: https://docs.minecraftforge.net/en/latest/gui/menus/ ·
  https://docs.fabricmc.net/develop/blocks/container-menus
- Chromium — compromised-renderer threat model, Mojo validation, `ReportBadMessage`, the Rule of 2:
  https://chromium.googlesource.com/chromium/src/+/main/docs/security/compromised-renderers.md ·
  https://chromium.googlesource.com/chromium/src/+/main/docs/security/mojo.md ·
  https://chromium.googlesource.com/chromium/src/+/main/docs/security/rule-of-2.md


---

## 8. Beyond the wire — is this everything?

No. §1–§7 audited the networked path and every place it *meets* the engine. It did not audit the
engine's own core — the tree model, the style engine, the layout seam, the input handler, the
compositor — with the same rigor. This section records what a measurement of that core says, what it
implies for how aggressive the rewrite should be, and what would have to be audited before committing
to the aggressive tier.

### 8.1 The measurement

`AGENTS.md`'s *Load-bearing invariants* table is the engine's own record of what it holds by
discipline rather than by structure: each row is a rule learned from a shipped defect.

| | |
|---|---|
| Invariant rows learned from bugs | **292** |
| …about focus | 38 |
| …about detach / reparent / `removeChild` / `addChild` | 22 |
| …about sheets, specificity, pseudo-classes | 21 |
| …about hit-testing, `localToWorld`, `screenToLocal` | 20 |
| …about animation / transition | 20 |
| …about flex sizing traps (`flex-shrink`, `width: 0`, `min-height`, `gap-all`) | 13 |
| …about thread affinity | 10 |
| …about the internal-children flag | 10 |
| …about top-layer promotion | 8 |
| …about geometry written at `IMPORTANT`/`INLINE` origin | 7 |
| `UIElement.java` | **3,308 lines, 22 concern sections** (core state, events, identity, state, tree, serializable state, **networking**, tree observation, focus, scrolling, resize, keymap, settings, top layer, queries, hit-testing, layout, style, paint, window attachment/Taffy, runtime cache) |
| Networking references inside the DOM element class | 35 |
| `WindowFrame.java` / `UIWindow.java` / `Desktop.java` / `UIInputHandler.java` | 2,157 / 1,494 / 1,321 / 962 lines |
| Files that push geometry **through the cascade** at `IMPORTANT` origin | **46** |
| Places that special-case a promoted element (Taffy parent, position, transform, paint/hit) | 13, across 4 files |
| Uses of a thread-affinity guard (`UiThread` exists as a marker) | **0** |

Two hundred and ninety-two rules is not a documentation problem. It is what a design produces when
one structure is asked to do several jobs and every disagreement between the jobs is resolved by a
rule instead of a boundary.

### 8.2 The structural causes

Each of the categories above traces to one of these, and each has a known production answer.

| Cause | What it produces | Production answer |
|---|---|---|
| **One tree does four jobs.** The DOM tree is also the layout tree, the paint order and the hit-test order. Where those must disagree — promotion, owned dialogs, tear-out — the engine special-cases the divergence in every consumer (13 sites), and every "fix three of the four" bug in the table is that | promotion rows, hit-test rows, the tear-out saga, no portals | Chromium: DOM → style → **LayoutObject tree** → paint layers → compositor. Flutter: Widget → Element → **RenderObject**. Three trees, each owning one job, with the DOM never consulted for geometry |
| **Encapsulation by a flag.** A composite's private structure sits in the same tree as user content, marked `internal`; `markAsInternal` recurses, `removeChild` silently refuses, the codec skips, focus and hit-test walk through it | the 10 internal-flag rows, and the `addInternalChild(container)` trap that has bitten three times | **Shadow DOM**: a composite's parts live in a separate shadow tree; light-DOM children are slotted; the flat tree is derived for layout and events. Nothing has to be "skipped" because it is not there |
| **The cascade is used as a geometry channel.** `UIText` pushes its measured size back as an `IMPORTANT` candidate; `ProgressBar`'s fill width, `Switch`'s knob and 43 other files do likewise. Layout results become style inputs and "settle in 2–3 passes" | the `IMPORTANT`/`INLINE` rows, the *no sizes in Java* rule that has to be re-stated every session, a theme that cannot beat a widget | Intrinsic sizing belongs to the **layout engine** (Taffy measure functions, Flutter `performLayout`, Blink `LayoutObject::ComputeIntrinsicLogicalWidths`). `UIText` avoided Taffy's measure function because of a Taffy 1.1.4 flex-wrap `NaN` bug — a dependency defect turned into an architecture |
| **Networking lives on the DOM node.** `networkId`, `reportedEvents`, `observer`, `writeState`/`readState`, `describedChildren` are `UIElement` members; identity-by-position was reachable only because the id is a field the walk rewrites | the whole of Appendix A, the `Detached` band-aid | A **mirror** that reflects the node tree from outside (CDP's DOM agent is a separate object that *observes* Blink's DOM; Unreal's replication system is not a member of `AActor`'s hierarchy code) |
| **One flat, global, ordered sheet list** per window; re-adding appends at highest priority; nothing scopes | the 21 sheet rows, Y1–Y2 | Scoped style (`@scope`, shadow-tree style scoping, Blazor's per-component rewrite), refcounted sheet ownership |
| **Tag = Java class name; `tagName()` is an exact-class lookup** | the `ToolWindowFrame` matched-nothing row, N1 | Registered, namespaced element names (custom elements; Minecraft `ResourceLocation`) |
| **No thread affinity guard.** The frame thread owns the tree by rule; a worker thread reaching a setter corrupts a `HashSet` mid-iteration with no stack frame naming the culprit (the 10 thread rows) | silent corruption reported as an unrelated exception in `advanceFrame` | Swing `checkThread`/`SwingUtilities.isEventDispatchThread`, Android `checkThread()` → `CalledFromWrongThreadException`, Chromium `DCHECK_CALLED_ON_VALID_SEQUENCE`. `UiThread.markCurrent` exists; nothing asserts against it |
| **Hide is detach.** Retention by removing subtrees from the tree, then re-adding — which is what makes the `markAsInternal`-on-resume trap, the ticker contract, the stylesheet-candidate-outlives-reparent bug and the "capture geometry before detach" rule all exist | the 22 detach/reparent rows | Page Visibility / bfcache freeze the *document*, they do not detach it; Chromium's "frozen" lifecycle state |
| **Focus is 38 rows** — click-focus lands on the frame before dispatch, `FocusPolicy` has four values two of which look alike, focus delegation vs tab traversal are two walker families, restore-on-recycle, modal scoping at four enforcement points | | Browsers have one `focus()` algorithm, one *focus navigation scope* concept (Shadow DOM + dialog), and `tabindex`. Most of the 38 are the cost of enforcing modality and containment by walking rather than by scope |

### 8.3 What that implies for the rewrite

The §4 rewrite is **correct on the current engine** and does not need any of §8 to ship. But it
would inherit the toothpicks: a mirror bolted onto a node class that still owns networking fields,
sheets scoped by convention because the style engine cannot scope, presentation still special-cased
because there is no render tree, composites still skipped rather than encapsulated. Three tiers,
honestly priced:

| Tier | Scope | What it buys | What it costs |
|---|---|---|---|
| **1 — Wire and sessions** | §4 as written, on today's engine | Everything in §1's matrix; stable ids; contracts; scoped sheets by rewrite; veto; caps | ~the 2,000 lines of the sessions and codec tree paths, plus contracts per widget; the authoring surface unchanged |
| **2 — Tier 1 plus three engine prerequisites** | (a) networking off `UIElement` — the mirror observes a node tree that has no `networkId`; (b) **scoping in `StyleEngine`** proper, not a selector rewrite; (c) **thread affinity asserted** on every tree mutation | The mirror stops being a bolt-on; sheets stop being a convention; the 10 thread rows become one exception with a stack trace | Touches `UIElement`, `ElementStyle`/`StyleEngine`, every widget setter (a one-line guard). Behaviour-preserving; the 1,400 headless + widget tests are the net |
| **3 — Three-tree engine** | A **node tree** (identity, attributes, children, shadow roots — no geometry, no paint), a **style pass** producing computed style per node, a **render tree** (layout box + paint order + hit order) built from the flat tree, **Shadow DOM** for composites, intrinsic sizing in the layout engine, hide-as-freeze rather than detach, one focus-scope concept | Promotion, owned dialogs, tear-out, portals, thumbnails all become *render-tree placement* with the node tree untouched — the four-place divergence disappears as a category; internal children disappear as a category; the cascade stops carrying geometry; the mirror reflects a node tree that was designed to be reflected | A rewrite of `ui/UIElement`, `ui/UIWindow`, `style/`, `render/`'s glue and `TaffyBridge`, and a **port** of every widget, the desktop and the workbench onto the new node/render split. The text, editor, language, serialization and CrystalGraphics stacks are untouched. Months, not weeks; the tests that assert behaviour (not structure) survive |

### 8.4 The recommendation, and what must be audited before it

Tier 3 is what "not built on toothpicks" actually means; §8.1 is the argument for it and §8.2 says
where the boundaries go. But this document has **not** earned the right to commit to it: the style
engine internals, `TaffyBridge`, `UIInputHandler`, the transition engine and the compositor were read
for their seams, not audited. The 292 rows say the structure is wrong; they do not say which of the
three trees' boundaries each row falls on, and that classification is what a Tier-3 design is built
from.

So the next deliverable, if the aggressive tier is wanted, is an **engine-core audit** of the same
shape as this one: `ui/`, `style/`, `render/`, `ui/input/`, `ui/elements/desktop/` — each invariant
row traced to the boundary it substitutes for, the three-tree design drawn against Chromium's and
Flutter's, the Shadow DOM model for composites, intrinsic sizing against Taffy's measure functions
(and whether the 1.1.4 `NaN` bug still holds), and a port plan for the desktop and workbench. Tier 1
can start now regardless; Tier 2's three prerequisites are cheap enough to fold into it and make
the eventual Tier 3 a port rather than a second rewrite of the wire.

**References for §8:** Chromium *Life of a pixel* / rendering pipeline (DOM → style → layout →
paint → compositing) — https://chromium.googlesource.com/chromium/src/+/main/docs/life_of_a_pixel.md ·
Flutter architectural overview (three trees) — https://docs.flutter.dev/resources/architectural-overview ·
Shadow DOM — https://developer.mozilla.org/en-US/docs/Web/API/Web_components/Using_shadow_DOM ·
CSS `@scope` — https://developer.mozilla.org/en-US/docs/Web/CSS/@scope ·
Page Lifecycle API (frozen, not detached) — https://developer.chrome.com/docs/web-platform/page-lifecycle-api ·
Taffy measure functions — https://docs.rs/taffy/latest/taffy/ (MeasureFunc) ·
Swing single-thread rule / `SwingUtilities.isEventDispatchThread` — https://docs.oracle.com/javase/tutorial/uiswing/concurrency/dispatch.html ·
Android `View.checkThread` / `CalledFromWrongThreadException` — https://developer.android.com/reference/android/view/ViewRootImpl

---
## Appendix A — Identity is not position (the mirror design)

*"A UI that's docked, ripped, or turned into a WindowFrame or Dialog or Tab shouldn't affect its
networked handling at all — all of those changes are client-sided. It's supposed to retain the same
session it would normally have."* A `Detached` prototype (built, measured and reverted) made that true, and it was a
band-aid: it rebuilds a shadow of the logical tree on the client so that a **positional** id scheme
can keep pretending nothing moved. This part is the diagnosis of what it was covering, a survey of how
production systems solve the same problem, and the rewrite that makes presentation genuinely free.

### A.1 — The diagnosis

Ids are positions in a document-order walk, derived on both sides and never sent. Every difficulty of
the last two addenda is a consequence of that one sentence, and most of them predate tear-out:

| Symptom | Cause |
|---|---|
| A subtree reparented on the client **half-works** — controls send, readouts freeze (measured: slider drove the server to 0.7, heat bar froze at 0 while the server read 0.18) | `report()` names the id *stored on the element*; `find()` walks from the mounted root. The id was stable; only the lookup was positional |
| **Adding one row to the machine panel recreates every sibling on the client** — a fresh `EnginePanel`, its local `result` line gone, its listeners gone, its nested panel never bound again (the gap recorded in the third addendum) | `flushStructure` re-describes the anchor's children **in full**, so the client decodes fresh instances for elements that never changed. Tear-out did not cause this; it made it visible |
| Hidden-window suppression must gate the **whole** flush, never only the send (AGENTS.md row) | Renumbering is global: a structural change anywhere shifts every id after it on both sides |
| A late viewer needs a full re-hash and re-describe after any reshape | Same: there is no way to say "the tree, with the ids everyone already holds" |
| The `count` check is the only guard, and it is global | Skew anywhere shifts everything after it, so only the total can be checked |
| `Detached` needed a logical parent, a logical index, a splice function, a stale-record rule ordered before the renumber, and an id-path replacement lookup | All of it exists to keep a positional walk consistent with a tree that is no longer the physical one — a **shadow logical tree**, which is what stable ids give for free |

The engine's own contradiction: the DOM layer has **object identity** (`UIElement`, and `networkId`
is a field on it), the protocol re-derives identity from **position** after every change. Godot's
`MultiplayerSynchronizer` is this design, and its failure is ours exactly — "Reparenting node with
MultiplayerSynchronizer breaks synchronization" ([godot#86501](https://github.com/godotengine/godot/issues/86501)):
identity by `NodePath`, so a move is a different node.

### A.2 — What production systems do

Every system that permits reparenting has stable object identity. Every positional system forbids
client reparenting outright. There is no third option in the field.

| System | Identity | Hierarchy | May the presenting side reparent? |
|---|---|---|---|
| **X11** | XID, allocated at creation | `XReparentWindow` is a first-class request | Yes — the compositor moves windows freely |
| **Wayland** | Object id, allocated from a client range `[2, 0xfeffffff]` or a server range ([spec](https://wayland.freedesktop.org/docs/html/ch04.html)) | `wl_subsurface` parent is a property | Yes |
| **React Native Fabric** | ShadowNode **tag** | Diff → atomic mutations `Create/Delete/Insert/Remove/Update` applied to host views by tag ([render pipeline](https://reactnative.dev/architecture/render-pipeline)) | The host tree *is* the mutation target; identity never derives from position |
| **React DOM portals** | Fiber identity | Logical tree ≠ DOM tree: "events dispatched from inside a portal still bubble up through the React component tree, not through the DOM tree" ([createPortal](https://react.dev/reference/react-dom/createPortal)) | Yes — that is what a portal is |
| **Flutter** | `Element` keyed; `GlobalKey` preserves `State` across a move; `OverlayPortal` | Widget tree vs render tree | Yes |
| **Unreal** | `FNetworkGUID` from the server's `GuidCache` ([object references](https://docs.unrealengine.com/4.26/en-US/InteractiveExperiences/Networking/Actors/Properties/ObjectReferences)) | Attachment is a **replicated property** — `FRepAttachment { AttachParent, AttachSocket }` with `OnRep_AttachmentReplication` ([FRepAttachment](https://api.unrealengine.com/INT/API/Runtime/Engine/Engine/FRepAttachment/index.html)) | Hierarchy is *data about* an object, never its identity |
| **Unity NGO** | `NetworkObjectId`, server-assigned | `ParentSyncMessage`; `TrySetParent` ([parenting](https://docs-multiplayer.unity3d.com/netcode/1.0.0/advanced-topics/networkobject-parenting/)) | Same principle |
| **Blazor Server** | Component id; positional `RenderTreeEdit`s (`PrependFrame`, `RemoveFrame`, `StepIn`…) within a component ([RenderTreeEdit](https://learn.microsoft.com/en-us/dotnet/api/microsoft.aspnetcore.components.rendertree.rendertreeedit?view=aspnetcore-7.0)) | Positional | **No** — "if an element rendered by Blazor is modified externally… the DOM may no longer match Blazor's internal representation, which can result in undefined behavior" ([JS interop](https://learn.microsoft.com/en-us/aspnet/core/blazor/javascript-interoperability/?view=aspnetcore-10.0)) |
| **Phoenix LiveView** | DOM id required on any container the client owns | Positional statics/dynamics diff | Only by opting a subtree out: `phx-update="ignore"` ([DOM patching](https://hexdocs.pm/phoenix_live_view/0.20.4/dom-patching.html)) |
| **Godot** | `NodePath` | Path *is* identity | **No** — reparenting breaks sync ([#86501](https://github.com/godotengine/godot/issues/86501)) |
| **Minecraft** | Entity id, server-allocated; container slots by index | — | Vanilla never reparents a slot |
| **Kleppmann et al., tree move CRDT** | Unique id per node | Move is an operation *on an id* ([paper](https://martin.kleppmann.com/papers/move-op.pdf)) | The theoretical statement: a move is only expressible when identity is independent of position. Ours is single-writer, so none of the CRDT is needed — only the identity |

Blazor and LiveView are the honest comparison: they are our current design, and both **document the
prohibition** we are trying to lift. The systems that lift it — X11, Fabric, React, Flutter, Unreal,
Unity — all made the same move: identity is allocated once and hierarchy becomes data.

### A.3 — The rewrite, in three parts

#### 1. Stable ids, allocated once

- An id is assigned when an element **joins the described tree**, by the server, and is never
  re-derived. At `open()` the ids are walk-derived from the description exactly as now — pristine
  descriptions stay id-free and content-addressed, the cache is untouched. After `open()` the server
  allocates from a counter (`nextId`, starting at the open count); an inserted subtree takes a
  contiguous block and the insert carries its base.
- **Internal children are not numbered.** Nothing addresses them and nothing ever has:
  `notifyStateChanged` walks to the nearest non-internal ancestor, `flushState` skips anything
  unnumbered, the codec skips them, and reported events exist only on described elements. Numbering
  them bought nothing and coupled the id space to widget constructors — a client whose `Button` grew
  an extra internal label would today mis-address the whole window. The count check becomes a count of
  **described** elements per inserted subtree, which still catches registry and codec skew, and
  constructor skew becomes what it should be: invisible and harmless.
- Lookup is a **map** on both sessions, `id → element`. No walks anywhere — not on the server for an
  event's target, not on the client for a delta's. `NetworkIds` shrinks to "assign a block to a
  described subtree". `networkId` stays a field on the element, which it already is.
- Two description encodings: **pristine** (no ids; what `open()` sends and the cache keys on) and
  **live** (each described element carries `nid`; what a late viewer or a re-delivered hidden window
  gets after a reshape). A live snapshot hashes separately and is cache-shareable only with itself,
  which is fine — a reshaped window was never going to hit another window's cache.
- On removal the server clears the element's id (`-1`, which already means "never numbered"), so an
  element the server keeps a reference to and re-adds later is a new insert with a fresh block. The
  one-tick `removeChild` + `addChild` of the same object is coalesced into a **move** (below).

#### 2. Operational structure deltas — the observer *is* the edit script

`flushStructure`'s javadoc refuses an edit script because "a minimal edit script would have to be
computed against what the client has, which the server does not keep". Nothing has to be computed.
`UITreeObserver` reports every change as it happens — `onAttached` with the parent link set,
`onDetached` "before the parent link is cleared" — which is precisely a mutation log. It is already
Fabric's list, and the DOM's `MutationRecord`:

| Op | Payload | Client |
|---|---|---|
| `insert` | parent id, index, pristine description of the subtree, base id, described count | decode, assign `base..base+n-1` in document order over described elements, wire events, **bind nested panels**, place under parent at index — physically, unless the parent is presented elsewhere (§3) |
| `remove` | id | drop from the map, remove physically **wherever it is** |
| `move` | id, new parent id, index | reparent the existing instance — the DOM's adoption semantic; local state and listeners survive. Coalesced from a detach and a re-attach of one object inside one tick |
| `update` | — | not needed: identity changes already travel as `onIdentityDirty` state |

Consequences: adding a row to the machine panel sends one `insert` and **recreates nothing**. The old
shallowest-anchor logic, the full re-description, the client-side wholesale `clearDescribedChildrenFor`,
the per-delta renumber, and the global count check all go. Hidden-window suppression becomes bandwidth
rather than correctness: ops can queue while hidden and replay on show (keeping the client's instances),
or a live snapshot can be sent — queue-on-hidden is preferred because it preserves exactly the instances
a torn-out fragment is holding. A late viewer gets the live snapshot. Two viewers after any reshape hold
identical ids by construction, which is stronger than today.

#### 3. Presentation is the client's, and the net layer is blind to it

With 1 and 2 the session never needs to know where an element is drawn: a torn-out engine receives
deltas through the map and reports events with its stored id. `Detached`, `Detachment`,
`onDetachmentsInvalidated`, the logical splice, the stale-record ordering, the id-path lookup and
`WindowMount.DetachedWindow` are **all deleted**. Tearing out is `frame.setContent(engine)`; docking is
putting it back; a Dialog is `dialog.content().addChild(engine)`; a Tab is `tab.content()`. Plain
engine calls with no networking vocabulary, which is what the requirement said.

Two questions remain, and both belong to the presentation layer:

- **Portals or reparenting?** React's answer is a portal: the logical parent stays the parent, only the
  render location changes, and events bubble through the *logical* tree. This engine already has one
  portal — top-layer promotion, which diverges from the DOM parent in four places and keeps the cascade.
  Generalising it to "present under an arbitrary host" is the React shape, and it is the **wrong one
  here**, for the reason the workbench already chose reparenting: the desktop's rules resolve *the
  nearest `WindowFrame` ancestor* — click-focus, modality scope, raise, `DataContext` — and a portaled
  fragment would answer the machine's frame while sitting in its own. Reparenting is what
  `ToolWindowFrame` and `DockArea.tearOutToWindow` do, and stable ids make it free.
- **Where does a fragment go back to?** An `insert` under the fragment's logical parent, or a `move`
  of the fragment itself, is the only place server structure and client presentation can disagree. The
  rule is the workbench's: *the host is the truth about where it IS; the record is where it BELONGS*.
  Whoever tore the fragment out keeps a **placement record** — logical parent and index, updated by
  ops that move it — and consults it to dock. It is a presentation-layer object (the mount's, or a
  small `Undock` helper any element can use), never read by addressing, which is the difference from
  `Detached` in one sentence.

`bindPanels` also stops walking from the root: nested panels are bound **per inserted subtree**, at the
op, which closes the recorded gap where a panel arriving through a tree delta was never bound.

### A.4 — On the wire

| Message | Change |
|---|---|
| `ui/openWindow` | Unchanged for a pristine open. `count` becomes the described count |
| `ui/description` | Answers a pristine description at open; a **live** one (with `nid`) after any reshape, for late viewers and re-delivery |
| `ui/treeDelta` | **Replaced** by `ui/treeOps` — an ordered list of `insert`/`remove`/`move`. `EnvelopeCodec.VERSION` bumps; the existing version check already refuses a mismatched peer rather than misreading it |
| `ui/stateDelta`, `ui/event` | Unchanged — both were always id-keyed; only the lookups behind them change |
| `UIDescriptionCodec` | Optional `nid` field, omitted on pristine encodes so their hash is unchanged |

### A.5 — Steps, each shippable, each with the test that cannot pass today

1. **Described-only numbering + id maps.** No wire change beyond the count's meaning. Test: a client
   whose `Button` carries an extra internal child still routes every delta and event.
2. **Stable ids after open + `ui/treeOps`.** Server counter, insert carries base, client never
   renumbers. Test: adding a row on the server leaves the client's `EnginePanel` **the same instance**
   (`assertSame` before/after) with its local `result` line intact — false today, and the assertion
   the whole part turns on. Second test: a server `removeChild`+`addChild` elsewhere arrives as one
   `move` and the instance survives.
3. **Live snapshot.** Test: two viewers after a reshape agree on every id; a hidden window reshaped
   and shown again keeps its instances (queue-on-hidden).
4. **Delete `Detached` and the mount's `detach`; tear-out becomes reparenting** through ordinary
   engine calls, with a placement record in the presentation layer. The seven tear-out tests keep their
   claims and lose every line that touches the session; "survives a re-describe" becomes "survives a
   sibling insert with the same instance", which is the stronger statement.
5. **Bind per inserted subtree**, closing the tree-delta binding gap.

The `Detached` prototype was reverted rather than built on; the seven behaviours its tests pinned
(live both ways, survives a sibling insert, replaced on an ancestor re-describe, docks to the exact
index, honest caption, root/no-id refused, closes with the window) are the specification of step 4.

### A.6 — Risks and open questions

- **Move coalescing** relies on the observer ordering (detach fires before the parent link clears;
  re-attach fires after) and on both landing in one flush. A re-attach in a later tick is a fresh
  insert with a new id — correct, and exactly what the DOM does when a node is held and re-added.
- **Ids are per session and monotonic.** An `int` is enough by a few orders of magnitude.
- **Described-count skew** still refuses a delta; internal skew is now silent by design. Worth one line
  in the networking primer, because the old guarantee is stated there.
- **Styling follows the physical tree**, so `.machine-panel > .machine-engine` stops matching a torn-out
  engine — the intended reading (a fragment in its own frame is not "docked and closed"), and the
  reason the child combinator was used.
- **`WindowMount` loses `detach`**; a platform that wants an undock affordance uses the desktop's own
  API, which it already has. Whether a generic `Undock` helper belongs in `ui.elements.desktop` or
  stays in the workbench is a naming question for step 4.
