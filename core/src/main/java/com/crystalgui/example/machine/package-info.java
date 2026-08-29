/**
 * <h1>A worked example: one UI, built on a server, drawn on a client.</h1>
 *
 * <p>A domain model, a {@link com.crystalgui.net.window.Networked} panel that is the whole UI —
 * widgets, structure, server half and client half in a single class — a second panel <em>nested
 * inside</em> the first with its own slice of that model, a theme, and a {@code main()}
 * that wires the two ends together over a loopback transport and prints what crossed — plus the Minecraft loader code that
 * puts the same panel in a real world, which is now about forty lines because the lifecycle stopped
 * being each mod's problem. Nothing here is used by the engine. It exists to be read, and to be
 * run.</p>
 *
 * <h2>Read them in this order</h2>
 *
 * <table>
 *   <tr><th>#</th><th>Class</th><th>What it teaches</th></tr>
 *   <tr><td>1</td><td>{@link com.crystalgui.example.machine.MachineModel}</td>
 *       <td>The truth the server owns. <b>Not a single UI import.</b></td></tr>
 *   <tr><td>1b</td><td>{@link com.crystalgui.example.machine.EngineModel}</td>
 *       <td>A <b>slice</b> of that truth, owned by the machine — and why carving one is a modelling
 *           decision rather than a UI convenience.</td></tr>
 *   <tr><td>2</td><td>{@link com.crystalgui.example.machine.ui.MachinePanel}</td>
 *       <td><b>The whole UI, in one class.</b> Widgets as fields, {@code layout()} to arrange them,
 *           {@code serve()} for the server half and {@code wire()}/{@code client()} for the client
 *           half. Read it in that order — the file is written in it.</td></tr>
 *   <tr><td>2b</td><td>{@link com.crystalgui.example.machine.ui.EnginePanel}</td>
 *       <td><b>A UI inside a UI.</b> A nested panel with its own slice, its own wire method and a
 *           plain callback back to its parent — see the section below.</td></tr>
 *   <tr><td>3</td><td>{@link com.crystalgui.example.machine.ui.MachineStyles}</td>
 *       <td>Where the sizes and colours went, and why they travel separately.</td></tr>
 *   <tr><td>4</td><td>{@link com.crystalgui.example.machine.MachineDemo}</td>
 *       <td>Both ends in one process, with the wire printed.
 *           {@code ./gradlew :core:runExample}</td></tr>
 *   <tr><td>5</td><td>{@code mc1710/…/mc/example/MachineExample}
 *                     + {@code MachineExampleClient}</td>
 *       <td>The same thing in game, on a real socket. Press <b>F8</b>.</td></tr>
 * </table>
 *
 * <p>{@link com.crystalgui.example.machine.MachineTrace} runs through all of them: every line the example
 * prints is stamped with the thread it happened on, because the server/client split is the part that
 * a single-player world hides.</p>
 *
 * <h2>The mental model, in one paragraph</h2>
 *
 * <p>A dedicated Minecraft server has no OpenGL, no fonts and no textures, so it cannot draw
 * anything — but it is the only place that knows what is <em>true</em>. So the server builds an
 * ordinary tree of ordinary widgets and <b>describes</b> it; the client rebuilds that description
 * into its own tree of the same widget classes and draws it. From then on the two halves talk about
 * elements by number, and neither one ever sends a picture.</p>
 *
 * <h2>The loop</h2>
 *
 * <pre>
 *   SERVER                                                       CLIENT
 *   ------                                                       ------
 *   new Switch(), new Slider(), …          build a tree
 *   io.on(power, TOGGLE, handler)          record the lambda HERE,
 *                                          stamp the event NAME on the element
 *   host.open(window)       ── ui/openWindow (type, hash) ──►    never seen this hash?
 *                           ◄──── ui/description (request) ───   ask for the bytes
 *                           ───── ui/description (answer) ───►   rebuild the same tree,
 *                                                                attach listeners for exactly
 *                                                                the events the description asked for
 *
 *                                                                user flips the switch
 *   handler runs, model changes  ◄──────── ui/event ─────────    {nid: 3, kind: "toggle"}
 *   widget state written
 *   (the host flushes)      ───────── ui/stateDelta ─────────►   apply it to element 3
 *
 *                                                                user closes the frame
 *   window.onClosed(CLIENT)      ◄──────── ui/close ─────────    the direction that used to
 *                                                                have no message at all
 * </pre>
 *
 * <h2>The three contract shapes — the part worth memorising</h2>
 *
 * <p>There are only two message kinds, and each has a register-side and a send-side. <b>They are on
 * different classes, and that is the thing everybody trips on once:</b></p>
 *
 * <table>
 *   <tr><th>Shape</th><th>Register</th><th>Send</th><th>Lives on</th></tr>
 *   <tr><td>Request → Response</td><td>{@code onCall(method, handler)}</td>
 *       <td>{@code call(method, args, onResult, onError)}</td>
 *       <td>the <b>session</b></td></tr>
 *   <tr><td>Notification</td><td>{@code onNotify(method, handler)}</td>
 *       <td>{@code notify(method, payload)}</td>
 *       <td>the <b>session</b> — see below</td></tr>
 *   <tr><td>Widget event</td><td>{@code io.on(element, kind, handler)}</td>
 *       <td>— the client sends it for you</td><td>the <b>session</b></td></tr>
 * </table>
 *
 * <p><b>Both pairs are on the session now, and the notification pair used to be somewhere else.</b> It
 * lived on the {@code ProtocolConnection}, which keys handlers by method name alone — so a second
 * window of the same application registering the same notification <em>threw at open</em>, and this
 * example taught exactly that pattern. Through {@link com.crystalgui.net.window.ServerScope} and
 * {@link com.crystalgui.net.window.ClientScope} both pairs are window-scoped and two windows may each name the same method. A notification that genuinely
 * belongs to the <em>connection</em> rather than to a window — a workspace, a script runtime — still
 * registers on {@code ProtocolConnection} directly, which is what it wants.</p>
 *
 * <p><b>Choosing between them has one question behind it: is anybody waiting?</b> If the caller needs
 * an answer, or needs to know it <em>failed</em>, it is a request. If it is "here is a thing that
 * happened", it is a notification, and making it a request buys a round trip for a reply nobody
 * reads.</p>
 *
 * <p>The panel has a <b>five-button demo strip</b> so all four directions are reachable by pressing
 * something, plus a request the server refuses:</p>
 *
 * <table>
 *   <tr><th></th><th>Server → Client</th><th>Client → Server</th></tr>
 *   <tr><td><b>Request</b></td><td>{@code Ping client}</td><td>{@code Ask stats}</td></tr>
 *   <tr><td><b>Notification</b></td><td>{@code Announce}</td><td>{@code Heartbeat}</td></tr>
 * </table>
 *
 * <p>Two of those buttons are wired by the <b>server</b> ({@code session.on}, so a press crosses the
 * wire as {@code ui/event}) and three by the <b>client</b> ({@code attachListener}, so the listener
 * is purely local and the server never learns it exists). The button cannot tell the difference, and
 * neither can the stylesheet — a described tree is an ordinary tree once it has been rebuilt.</p>
 *
 * <h2>Composing: a UI inside a UI</h2>
 *
 * <p>{@link com.crystalgui.example.machine.ui.EnginePanel} is a second {@code Networked} panel living
 * inside the first one as an ordinary field. On the parent that is <b>three lines</b>, and there is no
 * fourth anywhere:</p>
 *
 * <pre>{@code
 * public EnginePanel engine;                                  // a panel is an element
 *
 * public void layout(MachineModel m) {
 *     engine = EnginePanel.TYPE.build(m.engine());            // built WITH its slice
 *     addChild(engine);
 * }
 * public void serve(MachineModel m, ServerScope io) {
 *     engine.onRestarted(() -> …);                            // events UP: a plain Java callback
 *     io.attach(engine, m.engine());                          // props DOWN: the SLICE, not the model
 * }
 * }</pre>
 *
 * <p><b>Nothing is added on the client.</b> No registration, no id string, no wire contract. The
 * client's copy of the panel is decoded, bound and given its own {@code client()} scope by the same
 * walk that handled the root.</p>
 *
 * <table>
 *   <tr><th>The rule</th><th>Why, and what it looks like when it is broken</th></tr>
 *   <tr><td>The child is handed <b>a slice</b>, and its hooks take that type</td>
 *       <td>{@code EnginePanel} takes an {@code EngineModel} everywhere, so it <em>cannot name</em> the
 *           machine around it — the compiler is the boundary. Handing over the whole model compiles,
 *           works, and quietly makes the child a second place that knows everything.</td></tr>
 *   <tr><td>Wire methods are prefixed by the child's <b>element id</b></td>
 *       <td>The child registers {@code "tune"}; it is {@code engine/tune} on both sides, because the
 *           field name became the id and the description already carries ids. Nobody types the prefix,
 *           so nothing can drift — and two instances of one child class are two namespaces.</td></tr>
 *   <tr><td>Widget events are <b>not</b> prefixed</td>
 *       <td>{@code io.on(element, kind, …)} is keyed by the element, and a panel's elements are its
 *           own. Only strings need a namespace.</td></tr>
 *   <tr><td>The parent hears the child through <b>a plain callback</b></td>
 *       <td>Both server halves are objects in one process on one thread. A session message here is a
 *           round trip to the room you are standing in, plus a wire contract no client ever sees.</td></tr>
 *   <tr><td>Ids are <b>document-wide</b>; the scope does not namespace them</td>
 *       <td>{@code #load} in the sheet matches the child's slider wherever it is nested. Binding is
 *           unaffected — each panel resolves its fields from its own subtree — but a child field named
 *           after a parent one is one selector matching two widgets.</td></tr>
 *   <tr><td>Whether the section is <b>open</b> never crosses the wire</td>
 *       <td>Expansion is view state, like a scroll position: the client adds a class, the server is
 *           never told, and two players sharing one machine cannot fold each other's panels.</td></tr>
 * </table>
 *
 * <h2>The five things people get wrong first</h2>
 *
 * <ol>
 *   <li><b>Handlers are registered in {@code bind()}, before the client is told anything.</b> The set
 *       of reported events is part of the description, so a handler added for an element the client
 *       has <em>already been described</em> throws. The host calls {@code bind} and then opens, so
 *       there is no way to get the ordering wrong from inside a window — and an element added
 *       <em>later</em> (a fragment, a new row) may be wired at any time, because the {@code insert} op
 *       carries its reported events too.</li>
 *   <li><b>An element's id is allocated once and then belongs to it.</b> A pristine description sends
 *       no ids at all — both sides derive them from one document-order walk, which is what keeps a
 *       description content-addressed, so re-opening the same UI costs one small packet. From the
 *       first structural change onward the server owns the numbering and says so: {@code ui/treeOps}
 *       carries {@code insert}/{@code remove}/{@code move} naming the ids involved, and a viewer
 *       joining afterwards is handed a <em>live</em> description with each element's id written into
 *       it, because it can no longer compute them. <b>An insert renumbers nothing</b> — which is the
 *       whole point, since a message in flight names an element and a name that moves is not a
 *       name.</li>
 *   <li><b>The description carries only {@code INLINE}-origin styles.</b> A widget's own baseline
 *       look is produced locally by the client's copy of the same class, and a theme travels as a
 *       {@link com.crystalgui.net.SheetRef} — usually as a hash the client already has, so nothing
 *       transfers at all.</li>
 *   <li><b>A server path may not touch {@code CgIO}, fonts or GL.</b> Read
 *       {@link com.crystalgui.example.machine.ui.MachineStyles} for the one that catches everybody:
 *       {@code StyleSheet} is unloadable on a server.</li>
 *   <li><b>A window is a VIEW of world state, not the state itself.</b> {@link
 *       com.crystalgui.example.machine.MachineModel} ticks with the world, in {@code MachineExample};
 *       {@code MachinePanel.tick} mirrors it into widgets and stops. Fusing the two is invisible
 *       until you ask what happens when the last viewer leaves — and the answer was that the machine
 *       stopped existing, which is the opposite of what a server-authoritative UI is for.</li>
 *   <li><b>Nothing here calls {@code session.tick()}.</b> It used to, and forgetting to was a live
 *       session that answered calls and never sent another state update. {@link
 *       com.crystalgui.net.window.ServerWindows} does it now, from the connection's own tick, for every
 *       window on it — which is most of why this example lost a tick handler, a player map and a
 *       logout hook.</li>
 * </ol>
 *
 * <h2>Why this lives in {@code src/main} and not in a docs folder</h2>
 *
 * <p>Because {@code ./gradlew :core:compileJava} checks it. Prose about an API drifts silently the
 * first time a signature moves; an example that stops compiling is documentation that fails loudly.
 * The cost is a handful of classes in the jar that nothing calls, which is the cheaper half of that
 * trade. {@code MachineExampleTest} in {@code headlessTest} runs the whole loop, so the example also
 * cannot rot into something that compiles and no longer works.</p>
 *
 * <h2>Running it in game</h2>
 *
 * <p>{@code ./gradlew :mc1710:runClient}, join a world, press <b>F8</b>. The panel opens as a window
 * on the desktop {@code CgUiScreen} already owns — beside the editor, in the same taskbar. It is
 * emphatically <b>not</b> a second {@code GuiScreen}: there is one, and a second would be a second
 * claim on the input pump, the GL state handoff, the desktop's persistence and the modal stack, with
 * only one of them able to be in front.</p>
 *
 * <p><b>Press F8 twice</b> and nothing stacks: the window names a key, so the server brings the
 * existing panel forward rather than building a second one — which keeps its scroll position and
 * whatever is half-typed in it. <b>Close it with the X</b> and the server is told, ends the session
 * and stops describing a tree nobody is drawing. Both of those are new: before {@code ui/close}
 * existed, closing the panel destroyed the frame and the client's per-tick poll re-wrapped the
 * still-live session's tree in a fresh one on the very next tick. Close meant blink.</p>
 *
 * <p>What to watch is the console, not the screen. Every line names its thread, and the two columns
 * must never cross — the machine ticks on {@code Server thread} and the window redraws on
 * {@code Client thread}, from one process, in single player, which is the one configuration where
 * getting that wrong still appears to work.</p>
 *
 * <p>F8 <b>asks</b> for the panel — {@code machine/open}, a notification, because nobody is waiting
 * and the window arriving <em>is</em> the answer. A real mod would open on a block being right-clicked,
 * which is the same one line from a different trigger. That direction is the one Minecraft's own model
 * has no message for, and it is what every "right-click to open" actually needs.</p>
 *
 * <p>And the machine has been running the whole time, whether or not anybody had the window open,
 * because the model is not the UI.</p>
 *
 * <h2>Where to go next</h2>
 *
 * <ul>
 *   <li>{@code docs/CGUI_SERVER_AND_SERIALIZATION.md} — the reference this example is the tutorial
 *       for: codecs, content addressing, the four-kind envelope, the wire.</li>
 *   <li>{@code docs/CGUI_WIDGETS.md} — every widget, and which ones can carry state over a wire.</li>
 *   <li>{@code mc1710/…/mc/net/CgUiSessionProbe.java} — the same shape against a real Minecraft
 *       connection, with a ten-point checklist instead of a narrative.</li>
 * </ul>
 */
package com.crystalgui.example.machine;
