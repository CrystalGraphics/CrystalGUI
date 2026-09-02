package com.crystalgui.headless;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.ServerUiSession;
import com.crystalgui.net.ViewCommand;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.net.protocol.UiMethods;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.ElementRegistry;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.TextField;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * View commands: what a server can ask a client's view to <em>do</em>.
 *
 * <p>Everything else a server sends says what the UI <b>is</b>. These say what should <b>happen</b>,
 * and the tests below are aimed at the three consequences of that distinction rather than at the happy
 * path: they are never replayed, they are dropped when nobody is watching, and the vocabulary is
 * closed.</p>
 */
public class ViewCommandTest {

    private InMemoryTransport<Object>[] link;
    private ProtocolConnection<Object> serverEnd;
    private ProtocolConnection<Object> clientEnd;
    private ServerUiSession<UINode, Object> server;
    private ClientUiSession<UINode, Object> client;
    private UINode root;
    private TextField field;
    private Button button;

    private final List<String> applied = new ArrayList<>();

    @Before
    public void setUp() {
        Protocols.resetForTesting();
        ElementRegistry.bootstrapBuiltins();

        root = new UINode();
        field = new TextField();
        button = new Button("Press");
        root.append(field);
        root.append(button);

        link = InMemoryTransport.pair();
        serverEnd = Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "alice");
        clientEnd = Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);
        server = Sessions.serveOn(1, root, serverEnd);
        client = Sessions.viewOn(clientEnd);
        // Stands in for ClientWindows' applier: this test is about what CROSSES, and applying a focus
        // needs a UIDocument, fonts and a style engine -- none of which exist here by design.
        client.onViewCommand((command, args) -> applied.add(command + ":" + args.getInt("nid", -1)));
        server.open();
        settle();
    }

    @After
    public void tearDown() {
        Protocols.resetForTesting();
    }

    private void settle() {
        for (int i = 0; i < 8; i++) {
            link[0].deliver();
            link[1].deliver();
            serverEnd.tick();
            clientEnd.tick();
        }
    }

    @Test
    public void anElementCommandNamesItsElement() {
        server.viewOn(ViewCommand.FOCUS, field, null);
        settle();
        assertEquals(1, applied.size());
        assertEquals("focus:" + server.idOf(field), applied.get(0));
    }

    @Test
    public void aWindowCommandNamesNoElement() {
        StateMap<Object> args = new StateMap<>(PlainOps.INSTANCE);
        args.putString(ViewCommand.TEXT, "Furnace");
        server.view(ViewCommand.SET_TITLE, args);
        settle();
        assertEquals(1, applied.size());
        assertEquals("setTitle:-1", applied.get(0));
    }

    /**
     * <b>The vocabulary is closed</b>, and this is where that is enforced.
     *
     * <p>A server naming a method the client would then look up is the shape that turns a remote UI
     * into a remote-code surface. Anything off the list is dropped before it reaches an applier.</p>
     */
    @Test
    public void anUnknownCommandNeverReachesTheApplier() {
        StateMap<Object> out = new StateMap<>(PlainOps.INSTANCE);
        out.putInt(UiMethods.WINDOW, 1);
        out.putString(ViewCommand.CMD, "runArbitraryThing");
        serverEnd.router().notify(UiMethods.VIEW, out.encode());
        settle();

        assertTrue("an invented command must be refused, not attempted", applied.isEmpty());
    }

    /**
     * Dropped for a viewer that is not watching, <b>never queued</b>.
     *
     * <p>A focus request held while a window was minimised and delivered on the way back would move the
     * caret out from under whoever had since started typing somewhere else. It is asking about a moment
     * that has passed, so the answer is to let it pass.</p>
     */
    @Test
    public void aCommandForAViewerWhoIsNotWatchingIsDroppedRatherThanHeld() {
        server.setViewerVisible("alice", false);
        server.viewOn(ViewCommand.FOCUS, field, null);
        settle();
        assertTrue(applied.isEmpty());

        server.setViewerVisible("alice", true);
        settle();
        assertTrue("and it must NOT arrive late", applied.isEmpty());
    }

    /** An element the client has never been described cannot be named. */
    @Test
    public void aCommandAboutAnUndescribedElementIsNotSent() {
        UINode stranger = new UINode();
        server.viewOn(ViewCommand.FOCUS, stranger, null);
        settle();
        assertTrue(applied.isEmpty());
    }

    /** They are not state: nothing about them survives into a description. */
    @Test
    public void aViewCommandLeavesNoTraceInTheDescription() {
        server.viewOn(ViewCommand.FOCUS, field, null);
        settle();
        String before = server.descHash();

        server.viewOn(ViewCommand.SCROLL_INTO_VIEW, button, null);
        settle();

        assertEquals("a view command is not part of what the window IS", before, server.descHash());
        assertEquals(2, applied.size());
    }
}
