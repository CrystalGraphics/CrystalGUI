package com.crystalgui.headless;

import com.crystalgraphics.net.CgInMemoryTransport;
import com.crystalgraphics.net.protocol.CgProtocolConnection;
import com.crystalgraphics.net.protocol.CgProtocols;
import com.crystalgraphics.serialization.CgPlainOps;
import com.crystalgraphics.serialization.CgStateMap;
import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.ServerUiSession;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.Button;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;

/**
 * A UI window and a non-UI subsystem on one connection. Contribution itself is CrystalGraphics'
 * {@code CgProtocolContributionTest}.
 */
public class UiSessionSharesTheWireTest {

    private CgInMemoryTransport<Object>[] pair;
    private CgProtocolConnection<Object> a;
    private CgProtocolConnection<Object> b;

    @Before
    public void setUp() {
        CgProtocols.resetForTesting();
        pair = CgInMemoryTransport.pair();
    }

    @After
    public void tearDown() {
        CgProtocols.resetForTesting();
    }

    /** Opens both ends after whatever the test contributed. */
    private void connect() {
        a = CgProtocols.open(pair[0], CgPlainOps.INSTANCE, () -> { }, "peer-b");
        b = CgProtocols.open(pair[1], CgPlainOps.INSTANCE, () -> { }, null);
    }

    private void settle() {
        for (int i = 0; i < 8; i++) {
            pair[0].deliver();
            pair[1].deliver();
            a.tick();
            b.tick();
        }
    }

    /**
     * A UI window and a non-UI subsystem on ONE wire — the whole point, end to end.
     *
     * <p>The sessions no longer build a router; they take the connection's. So a workspace request and a
     * window's description handshake interleave on the same connection, correlate independently, and
     * neither knows the other exists. That is what "general enough for everything, not just UI" means
     * concretely, and before this the sessions and {@link CgProtocols} were parallel rather than composed.</p>
     */
    @Test
    public void aUiSessionAndANonUiSubsystemShareOneWire() {
        CgProtocols.contribute("workspace", connection ->
                connection.onRequest("workspace/read", (args, respond) -> {
                    CgStateMap<Object> out = new CgStateMap<>(connection.ops());
                    out.putString("body", "contents of " + args.getString("path", "?"));
                    respond.ok(out);
                }));
        connect();

        UIElement root = new UIElement();
        root.append(new Button("Press me"));
        ServerUiSession<UIElement, Object> server = Sessions.serveOn(1, root, a);
        ClientUiSession<UIElement, Object> client = Sessions.viewOn(b);

        AtomicReference<UIElement> arrived = new AtomicReference<>();
        client.onWindowOpened(arrived::set);

        AtomicReference<String> body = new AtomicReference<>();
        CgStateMap<Object> read = new CgStateMap<>(CgPlainOps.INSTANCE);
        read.putString("path", "src/Main.java");
        b.call("workspace/read", read, result -> body.set(result.getString("body", "")), null);

        server.open();
        // Only the CONNECTIONS are ticked for dispatch -- a session riding one does not drain. The
        // server session is still ticked so it flushes what its tree changed.
        for (int i = 0; i < 8; i++) {
            pair[0].deliver();
            pair[1].deliver();
            a.tick();
            b.tick();
            server.tick();
        }

        assertEquals("the workspace answered on the shared wire",
                "contents of src/Main.java", body.get());
        assertEquals("and the window arrived on the same one",
                1, arrived.get() == null ? -1 : arrived.get().children().size());
    }
}
