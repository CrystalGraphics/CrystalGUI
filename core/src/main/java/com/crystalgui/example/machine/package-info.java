/**
 * <h1>A worked example: one UI, built on a server, drawn on a client.</h1>
 *
 * <p>A domain model, a widget tree, a theme, a {@link com.crystalgui.net.ServerUiSession}, a
 * {@link com.crystalgui.net.ClientUiSession}, and a {@code main()} that wires the two together over a
 * loopback transport and prints what crossed — plus fifty lines of Minecraft loader that put the same
 * panel in a real world. Nothing here is used by the engine. It exists to be read, and to be run.</p>
 *
 * <h2>Read them in this order</h2>
 *
 * <table>
 *   <tr><th>#</th><th>Class</th><th>What it teaches</th></tr>
 *   <tr><td>1</td><td>{@link com.crystalgui.example.machine.MachineModel}</td>
 *       <td>The truth the server owns. <b>Not a single UI import.</b></td></tr>
 *   <tr><td>2</td><td>{@link com.crystalgui.example.machine.ui.MachinePanel}</td>
 *       <td>The widget tree. Structure and names — no sizes, no colours.</td></tr>
 *   <tr><td>3</td><td>{@link com.crystalgui.example.machine.ui.MachineStyles}</td>
 *       <td>Where the sizes and colours went, and why they travel separately.</td></tr>
 *   <tr><td>4</td><td>{@link com.crystalgui.example.machine.session.MachineServer}</td>
 *       <td>Opening a session, holding the behaviour, pushing state.</td></tr>
 *   <tr><td>5</td><td>{@link com.crystalgui.example.machine.session.MachineClient}</td>
 *       <td>Receiving a tree that was never built here, and drawing it.</td></tr>
 *   <tr><td>6</td><td>{@link com.crystalgui.example.machine.session.MachineDemo}</td>
 *       <td>Both ends in one process, with the wire printed.
 *           {@code ./gradlew :core:runExample}</td></tr>
 *   <tr><td>7</td><td>{@code mc1710/…/mc/example/MachineExample}
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
 *   session.on(power, TOGGLE, handler)     record the lambda HERE,
 *                                          stamp the event NAME on the element
 *   session.open()          ── ui/openWindow (hash, count) ──►   never seen this hash?
 *                           ◄──── ui/description (request) ───   ask for the bytes
 *                           ───── ui/description (answer) ───►   rebuild the same tree,
 *                                                                attach listeners for exactly
 *                                                                the events the description asked for
 *
 *                                                                user flips the switch
 *   handler runs, model changes  ◄──────── ui/event ─────────    {nid: 3, kind: "toggle"}
 *   widget state written
 *   session.tick()          ───────── ui/stateDelta ─────────►   apply it to element 3
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
 *       <td>the <b>connection</b></td></tr>
 *   <tr><td>Widget event</td><td>{@code session.on(element, kind, handler)}</td>
 *       <td>— the client sends it for you</td><td>the <b>session</b></td></tr>
 * </table>
 *
 * <p>A session gives you {@code onCall}/{@code call} and nothing else. Notifications are one layer
 * down on the {@code ProtocolConnection}, where every subsystem sharing the wire meets. Same wire,
 * different class — so anything that wants to send something unanswered holds both.</p>
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
 * <h2>The five things people get wrong first</h2>
 *
 * <ol>
 *   <li><b>Handlers must be registered before {@code open()}.</b> The set of reported events is part
 *       of the description the client has already been sent — registering one afterwards throws,
 *       because the client would never report it.</li>
 *   <li><b>Nothing sends element ids.</b> Both sides derive them from a document-order walk of the
 *       same tree. That is why a structural change must go through {@code ui/treeDelta} rather than
 *       an ad-hoc mutation: everything after an insertion renumbers.</li>
 *   <li><b>The description carries only {@code INLINE}-origin styles.</b> A widget's own baseline
 *       look is produced locally by the client's copy of the same class, and a theme travels as a
 *       {@link com.crystalgui.net.SheetRef} — usually as a hash the client already has, so nothing
 *       transfers at all.</li>
 *   <li><b>A server path may not touch {@code CgIO}, fonts or GL.</b> Read
 *       {@link com.crystalgui.example.machine.ui.MachineStyles} for the one that catches everybody:
 *       {@code StyleSheet} is unloadable on a server.</li>
 *   <li><b>The two sessions' {@code tick()} methods are not symmetric, and they read alike.</b>
 *       {@code ClientUiSession.tick()} genuinely does nothing while it rides a connection — the
 *       connection has already drained the mailbox for every subsystem on it. But
 *       {@code ServerUiSession.tick()} still <em>flushes</em>, because it is the observer holding
 *       that tick's dirty set and nothing else knows the set exists. Stop calling the server's and
 *       you keep a live session that answers calls and never sends another state update.</li>
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
 * <p>What to watch is the console, not the screen. Every line names its thread, and the two columns
 * must never cross — the machine ticks on {@code Server thread} and the window redraws on
 * {@code Client thread}, from one process, in single player, which is the one configuration where
 * getting that wrong still appears to work.</p>
 *
 * <p>The server opens the panel on login rather than waiting to be asked, which makes F8 a pure client
 * action and demonstrates the point of the whole arrangement: the machine has been running whether or
 * not anybody had the window open, because the model is not the UI.</p>
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
