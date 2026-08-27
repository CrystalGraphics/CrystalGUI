package com.crystalgui.example.machine.session;

import java.util.List;
import java.util.Map;

import com.crystalgui.example.machine.MachineTrace;
import com.crystalgui.example.machine.ui.MachineStyles;
import com.crystalgui.example.machine.MachineModel;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.window.ClientWindows;
import com.crystalgui.net.window.ClientWindowContext;
import com.crystalgui.net.window.ServerWindows;
import com.crystalgui.net.window.WindowProtocol;
import com.crystalgui.net.window.WindowMount;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.Switch;

/**
 * <b>Step 6 — both halves in one process, with the wire printed.</b>
 *
 * <pre>
 *   ./gradlew :core:compileJava
 *   java -cp core/build/classes/java/main:&lt;deps&gt; com.crystalgui.example.machine.session.MachineDemo
 * </pre>
 *
 * <p>Or run it from the IDE — it needs no OpenGL, no Minecraft and no window, which is itself the
 * demonstration: the whole session layer is testable in a plain JVM, and
 * {@code core/src/headlessTest/} is a whole source set built on that fact.</p>
 *
 * <h3>What the output shows</h3>
 *
 * <p>Every line beginning {@code S->C} or {@code C->S} is a real envelope, read back out of the
 * transport by {@link #tap}. There are four kinds of them and no more — {@code q} request,
 * {@code r} response, {@code n} notification, {@code x} cancel — and what a message <em>means</em>
 * is the string beside it. That is the shape worth internalising: adding a message to this protocol
 * is adding a string, not a packet class, a codec arm and an {@code instanceof} branch.</p>
 *
 * <h3>The thread column, and why it is boring here</h3>
 *
 * <p>{@link MachineTrace} stamps every line with the thread it ran on, and in this demo every one of
 * them says {@code main} — one thread driving both halves, deliberately, because a loopback demo has
 * nothing to schedule. That is the contrast worth having: run the same panel in game
 * ({@code :mc1710:runClient}, F8) and the identical lines split into {@code Server thread} and
 * {@code Client thread}, from one process, in single player. The traces are here so the two runs are
 * comparable, not because this one has anything to reveal.</p>
 *
 * <h3>The one thing this cannot show you</h3>
 *
 * <p>Both ends are in one JVM sharing one heap, so nothing here would catch a class that only the
 * client can load, or a server path that touches a font. That is what {@code :core:headlessTest}
 * (CrystalGraphics deliberately off the classpath) and {@code :mc1710:serverSmoke} (a real dedicated
 * server) are for. A loopback demo proves the protocol; it does not prove the split.</p>
 */
public final class MachineDemo {

    private MachineDemo() {
    }

    public static void main(String[] args) {
        /*
         * Two transports wired into each other. The real one is WireTransport over a Minecraft
         * network channel; this is the same interface with a queue instead of a socket, which is why
         * every session test in the repository runs against it.
         *
         * A ProtocolConnection is a transport plus a router: correlation, per-request deadlines,
         * cancellation, and failing everything pending when the link drops. Several subsystems ride
         * one -- the UI here, plus the workspace file protocol and a script runtime on a real
         * server -- which is why a session takes a connection rather than a transport.
         */
        // ONE CALL, and it is what puts a host on every connection opened afterwards. In game this
        // sits beside CgUiWorkspaceHost.register() in CommonProxy.init().
        WindowProtocol.register();

        InMemoryTransport<Object>[] link = InMemoryTransport.pair();
        ProtocolConnection<Object> serverEnd =
                Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "a-player-handle");
        ProtocolConnection<Object> clientEnd =
                Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);

        // THE MACHINE, which is world state and belongs to no window. It is ticked below, in this
        // method, exactly as a TileEntity ticks with the world -- so closing the panel does not stop it
        // and opening one does not start it.
        MachineModel machine = new MachineModel();

        // What this client does about a window of that type, said once. Delete this line and the panel
        // still opens, still renders and still reports every event the server asked for; only the three
        // buttons THIS side drives would go quiet.
        MachineClient[] behaviour = new MachineClient[1];
        ClientWindows.register(MachineWindow.TYPE, context -> behaviour[0] = new MachineClient(context));

        // Where windows land. A real host wraps the tree in a WindowFrame on the desktop; here it is a
        // println. That is the whole platform surface for networked UI.
        ClientWindows.of(clientEnd).setMount(new PrintingMount());

        // ── 1. Open ─────────────────────────────────────────────────────────
        say("1. The server opens the window -- one call, and the host does the rest");
        MachineWindow server = ServerWindows.of(serverEnd).open(new MachineWindow(machine));
        pump(link, serverEnd, clientEnd, 4);
        MachineClient client = behaviour[0];

        // The handshake is two round trips at most, and the second one is skipped once the client
        // has the hash cached -- which is what makes re-opening a large GUI cost one small packet.
        say("   the client now holds " + client.session().cacheSize() + " cached description(s); "
                + "re-opening this window would transfer nothing");

        // ── 2. A user flips the switch ──────────────────────────────────────
        say("2. The user flips the power switch -- ON THE CLIENT'S COPY of the tree");
        Switch clientPower = (Switch) client.root().querySelector("#power");
        clientPower.setChecked(true);
        pump(link, serverEnd, clientEnd, 1);

        System.out.println("  [server] model.isRunning() == " + machine.isRunning()
                + "   <- the server's lambda ran; the client never knew what the switch meant");

        // ── 3. The machine runs ─────────────────────────────────────────────
        say("3. Twenty WORLD ticks. The machine advances; the window is only a view of it");
        for (int i = 0; i < 20; i++) {
            // THE MODEL, not the window. Nothing here ticks a session or flushes anything -- the
            // connection's own tick does that, through the host, after this. @see ServerWindows
            machine.tick();
            pumpQuietly(link, serverEnd, clientEnd);
        }
        System.out.printf("  [server] progress=%.2f cycles=%d%n",
                machine.progress(), machine.completedCycles());
        System.out.printf("  [client] progress=%.2f status=%s%n",
                ((com.crystalgui.ui.elements.ProgressBar) client.root().querySelector("#progress"))
                        .fraction(),
                ((com.crystalgui.ui.elements.UIText) client.root()
                        .querySelector("." + MachineStyles.STATUS_CLASS)).getText());

        /*
         * ── 4-7: THE FOUR DIRECTIONS ──────────────────────────────────────────
         *
         * Two message kinds times two directions. Watch the envelope tags in the output: a REQUEST is
         * [q] and is always followed by an [r]; a NOTIFICATION is [n] and is followed by nothing at
         * all. That difference is the whole distinction, visible on the wire.
         */

        say("4. C -> S REQUEST -- the client asks, the server answers exactly once  [q then r]");
        press(client, "#ask-stats");
        pump(link, serverEnd, clientEnd, 2);

        say("5. S -> C REQUEST -- the same machinery pointed the other way  [q then r]");
        press(client, "#ping-client");   // a SERVER-wired button: the press crosses first
        pump(link, serverEnd, clientEnd, 3);

        say("6. C -> S NOTIFICATION -- nothing comes back, and nothing is waiting  [n only]");
        press(client, "#heartbeat");
        pump(link, serverEnd, clientEnd, 2);

        say("7. S -> C NOTIFICATION  [n only]");
        press(client, "#announce");      // also server-wired
        pump(link, serverEnd, clientEnd, 3);

        /*
         * ── 8: THE HALF A HAPPY PATH NEVER SHOWS ──────────────────────────────
         *
         * respond.fail is an ordinary answer that happens to say no. Same envelope kind as a success
         * ([r]), same thread, same latency -- NOT an exception and NOT a timeout. A UI has to tell
         * "refused" apart from "never came back", because only one of those is worth retrying.
         */
        say("8. A REQUEST THE SERVER REFUSES -- still an [r], with an error code instead of a body");
        press(client, "#bad-rename");
        pump(link, serverEnd, clientEnd, 2);

        // ── 9. Close ────────────────────────────────────────────────────────
        say("9. The server puts the window away, and says why");
        ServerWindows.of(serverEnd).close(server, "the block was broken");
        pump(link, serverEnd, clientEnd, 1);

        /*
         * 10: THE DIRECTION THAT USED TO HAVE NO MESSAGE AT ALL.
         *
         * There was no ui/close, so a user closing a window told the server nothing: the session stayed
         * open, kept observing its tree, and kept flushing state deltas into a frame that had been
         * destroyed. The only close anything ever noticed was the player disconnecting.
         */
        say("10. THE USER closes a window, and the server hears about it  [n only]");
        ServerWindows.of(serverEnd).open(new MachineWindow(machine));
        pump(link, serverEnd, clientEnd, 4);
        ClientWindows.of(clientEnd).windows().get(0).userClosed();
        pump(link, serverEnd, clientEnd, 2);
        System.out.println("  [server] windows still open: "
                + ServerWindows.of(serverEnd).windowCount());

        System.out.println();
        System.out.println("Nothing above sent a pixel, a colour or a layout. The client drew a tree "
                + "it did not build,\nfrom widget classes it already had, styled by a sheet named "
                + "rather than sent.");
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    /**
     * Moves everything both ways, printing each envelope.
     *
     * <p>Several rounds because a handshake is several messages deep and each one is only produced
     * once its predecessor has been delivered <em>and</em> acted on. On a real server this is one
     * game tick per round, which is why an open takes a couple of ticks to appear rather than
     * arriving instantly — worth knowing before treating that as a bug.</p>
     */
    private static void pump(InMemoryTransport<Object>[] link, ProtocolConnection<Object> serverEnd,
            ProtocolConnection<Object> clientEnd, int rounds) {
        for (int i = 0; i < rounds; i++) {
            tap("S->C", link[0]);
            tap("C->S", link[1]);
            link[0].deliver();
            link[1].deliver();
            serverEnd.tick();
            clientEnd.tick();
        }
    }

    /** The same, without the commentary — for the twenty ticks in step 3. */
    private static void pumpQuietly(InMemoryTransport<Object>[] link,
            ProtocolConnection<Object> serverEnd, ProtocolConnection<Object> clientEnd) {
        link[0].clearSent();
        link[1].clearSent();
        link[0].deliver();
        link[1].deliver();
        serverEnd.tick();
        clientEnd.tick();
    }

    /**
     * Prints what one end put on the wire, then forgets it.
     *
     * <p>Under {@code PlainOps} an encoded envelope is an ordinary {@code Map}, so this needs no
     * decoder: {@code k} is the kind and {@code m} the method. There is real value in seeing that —
     * the protocol is legible without tooling, which is most of why the debugging story is
     * "print it".</p>
     */
    private static void tap(String direction, InMemoryTransport<Object> end) {
        List<Object> messages = end.sent();
        for (Object raw : messages) {
            if (!(raw instanceof Map)) continue;
            Map<?, ?> envelope = (Map<?, ?>) raw;
            Object kind = envelope.get("k");
            Object method = envelope.get("m");
            System.out.println("  " + direction + "  [" + kind + "] "
                    + (method == null ? "(response to #" + envelope.get("i") + ")" : method));
        }
        end.clearSent();
    }

    /**
     * Presses a button in the CLIENT's rebuilt tree, exactly as a user would.
     *
     * <p>Which side reacts depends on how that button was wired, and the demo deliberately mixes
     * both: {@code #ask-stats} has a purely local listener {@code MachineClient} attached, while
     * {@code #ping-client} was given a reported event by the server, so pressing it sends a
     * {@code ui/event} first and the server's lambda runs a tick later. <b>The button cannot tell the
     * difference</b>, which is the point.</p>
     */
    private static void press(MachineClient client, String selector) {
        UIElement found = client.root().querySelector(selector);
        // onPressed is what a real click ends in -- Button.emitActivation fires this signal after
        // checking isWasPressTarget() and that the LEFT button was the one released. Emitting it
        // directly is the honest way to simulate a press without a mouse: it runs every listener,
        // in order, on this thread, exactly as the input handler would have.
        if (found instanceof Button) ((Button) found).onPressed.emit();
        else System.out.println("  !! no button at " + selector);
    }

    /**
     * Where a window lands in a process with no screen.
     *
     * <p>A {@link WindowMount} is the one thing a platform implements. On 1.7.10 it wraps the tree in a
     * {@code WindowFrame} on the desktop; here it prints. Everything above it — sessions, ids, the
     * close matrix, dispatch by type — is the engine's and is written once.</p>
     */
    private static final class PrintingMount implements WindowMount {

        @Override
        public MountedWindow mount(ClientWindowContext context) {
            System.out.println("  [client] mounted <" + context.type() + "> \"" + context.title()
                    + "\": " + count(context.root()) + " elements, " + context.sheets().size()
                    + " sheet(s), ua=" + context.useUserAgentSheet());
            return new MountedWindow() {
                @Override
                public void closedByServer(String reason) {
                    System.out.println("  [client] window closed by the server: " + reason);
                }

                @Override
                public void focus() {
                    System.out.println("  [client] brought forward");
                }

                @Override
                public void contentReplaced(UIElement newRoot) {
                    System.out.println("  [client] the server re-described this window");
                }
            };
        }
    }

    private static int count(UIElement element) {
        int total = 1;
        for (UIElement child : element.getChildren()) total += count(child);
        return total;
    }

    private static void say(String line) {
        System.out.println();
        System.out.println(line);
    }
}
