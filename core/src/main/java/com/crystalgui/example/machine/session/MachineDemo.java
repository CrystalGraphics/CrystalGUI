package com.crystalgui.example.machine.session;

import java.util.List;
import java.util.Map;

import com.crystalgui.example.machine.MachineTrace;
import com.crystalgui.example.machine.ui.MachineStyles;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.ui.UIElement;
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
        InMemoryTransport<Object>[] link = InMemoryTransport.pair();
        ProtocolConnection<Object> serverEnd =
                Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "a-player-handle");
        ProtocolConnection<Object> clientEnd =
                Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);

        MachineServer server = new MachineServer();
        MachineClient client = new MachineClient(clientEnd);

        client.onReady(root -> {
            System.out.println("  [client] window rebuilt: " + count(root) + " elements, "
                    + client.sheets().size() + " sheet(s), ua=" + client.useUserAgentSheet());
        });
        client.onClosed(reason -> System.out.println("  [client] window closed: " + reason));

        // ── 1. Open ─────────────────────────────────────────────────────────
        say("1. The server opens the window");
        server.open(serverEnd);
        pump(link, serverEnd, clientEnd, 4);

        // The handshake is two round trips at most, and the second one is skipped once the client
        // has the hash cached -- which is what makes re-opening a large GUI cost one small packet.
        say("   the client now holds " + client.session().cacheSize() + " cached description(s); "
                + "re-opening this window would transfer nothing");

        // ── 2. A user flips the switch ──────────────────────────────────────
        say("2. The user flips the power switch -- ON THE CLIENT'S COPY of the tree");
        Switch clientPower = (Switch) client.root().querySelector("#power");
        clientPower.setChecked(true);
        pump(link, serverEnd, clientEnd, 1);

        System.out.println("  [server] model.isRunning() == " + server.model().isRunning()
                + "   <- the server's lambda ran; the client never knew what the switch meant");

        // ── 3. The machine runs ─────────────────────────────────────────────
        say("3. Twenty world ticks. The model advances and the panel follows it");
        for (int i = 0; i < 20; i++) {
            server.tick();
            pumpQuietly(link, serverEnd, clientEnd);
        }
        System.out.printf("  [server] progress=%.2f cycles=%d%n",
                server.model().progress(), server.model().completedCycles());
        System.out.printf("  [client] progress=%.2f status=%s%n",
                ((com.crystalgui.ui.elements.ProgressBar) client.root().querySelector("#progress"))
                        .fraction(),
                ((com.crystalgui.ui.elements.UIText) client.root()
                        .querySelector("." + MachineStyles.STATUS_CLASS)).getText());

        // ── 4. A call, each way ─────────────────────────────────────────────
        say("4. The client asks the server a question, and is answered exactly once");
        client.requestStats(
                stats -> System.out.println("  [client] server says: cycles=" + stats.getInt("cycles", -1)
                        + " label=" + stats.getString("label", "?")),
                error -> System.out.println("  [client] refused: " + error));
        pump(link, serverEnd, clientEnd, 2);

        say("5. And the server asks the client one, which is the same machinery pointed the other way");
        server.session().call("machine/clientInfo", null,
                info -> System.out.println("  [server] client says: renderer="
                        + info.getString("renderer", "?")
                        + " cachedDescription=" + info.getBool("cachedDescription", false)),
                error -> System.out.println("  [server] refused: " + error));
        pump(link, serverEnd, clientEnd, 2);

        // ── 6. Close ────────────────────────────────────────────────────────
        say("6. The server puts the window away, and says why");
        server.close("the block was broken");
        pump(link, serverEnd, clientEnd, 1);

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
