package com.crystalgui.headless;

import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.ServerUiSession;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.ElementRegistry;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.Slider;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Phase 4 <b>C1</b> — one window, several clients watching it.
 *
 * <p><i>"One session, one client"</i> was the first thing a real server invalidated. The tree belongs to
 * the session rather than to a viewer, so a fan-out is a list of <b>routers</b>, not a list of sessions
 * over one tree — {@code UIElement.setObserver} holds one observer, so the latter is not available and
 * making it a list would cost every mutation in the application to serve a case most windows never
 * have.</p>
 */
public class MultiViewerTest {

    private UIElement root;
    private Slider slider;
    private Button button;

    private InMemoryTransport<Object>[] linkA;
    private InMemoryTransport<Object>[] linkB;
    private ProtocolConnection<Object> serverA;
    private ProtocolConnection<Object> serverB;
    private ProtocolConnection<Object> clientA;
    private ProtocolConnection<Object> clientB;
    private ServerUiSession<Object> server;
    private ClientUiSession<Object> viewerA;
    private ClientUiSession<Object> viewerB;

    @Before
    public void setUp() {
        Protocols.resetForTesting();
        ElementRegistry.bootstrapBuiltins();

        root = new UIElement();
        button = new Button("Press me");
        slider = new Slider();
        slider.setRange(0f, 10f);
        root.addChild(button);
        root.addChild(slider);

        linkA = InMemoryTransport.pair();
        linkB = InMemoryTransport.pair();
        serverA = Protocols.open(linkA[0], PlainOps.INSTANCE, () -> { }, "alice");
        clientA = Protocols.open(linkA[1], PlainOps.INSTANCE, () -> { }, null);
        serverB = Protocols.open(linkB[0], PlainOps.INSTANCE, () -> { }, "bob");
        clientB = Protocols.open(linkB[1], PlainOps.INSTANCE, () -> { }, null);

        server = new ServerUiSession<>(1, root, serverA);
        viewerA = new ClientUiSession<>(clientA);
        viewerB = new ClientUiSession<>(clientB);
    }

    @After
    public void tearDown() {
        Protocols.resetForTesting();
    }

    private void settle() {
        for (int i = 0; i < 24; i++) {
            linkA[0].deliver();
            linkA[1].deliver();
            linkB[0].deliver();
            linkB[1].deliver();
            serverA.tick();
            serverB.tick();
            clientA.tick();
            clientB.tick();
            server.tick();
        }
    }

    // ── The claim ───────────────────────────────────────────────────────────

    /** Both clients rebuild the same tree from one session. */
    @Test
    public void twoViewersBothReceiveTheWindow() {
        server.addViewer(serverB);
        server.open();
        settle();

        assertEquals("two viewers", 2, server.viewerCount());
        assertNotNull("alice must have the tree", viewerA.root());
        assertNotNull("bob must have the tree", viewerB.root());
        assertEquals(2, viewerA.root().getChildren().size());
        assertEquals(2, viewerB.root().getChildren().size());
    }

    /** A mutation reaches everyone, from one dirty set and one encode. */
    @Test
    public void aStateChangeReachesEveryViewer() {
        server.addViewer(serverB);
        server.open();
        settle();

        slider.setValue(7f);
        settle();

        assertEquals(7f, ((Slider) viewerA.root().getChildren().get(1)).getValue(), 0.001f);
        assertEquals(7f, ((Slider) viewerB.root().getChildren().get(1)).getValue(), 0.001f);
    }

    /**
     * A viewer joining <em>after</em> the window opened still gets it.
     *
     * <p>The half that makes late joining work at all: without it, a second player opening a shared
     * window would wait for the next mutation to discover it exists — and on a quiet window, forever.</p>
     */
    @Test
    public void aLateViewerIsToldImmediately() {
        server.open();
        settle();
        assertNotNull(viewerA.root());

        server.addViewer(serverB);
        settle();

        assertNotNull("a viewer added after open must still receive the window", viewerB.root());
        assertEquals(2, viewerB.root().getChildren().size());
    }

    /**
     * A viewer joining <b>after a reshape</b> still gets the window — the C1 × C2 seam.
     *
     * <p>Each feature was right alone. C1 replays the payload {@code open()} built, so a late viewer sees
     * exactly what the first one saw; C2 renumbers and re-hashes on a structural change. Together the
     * replayed payload carried a hash the session no longer served, so the late viewer asked for it and
     * was refused with <i>"this session serves X, not Y"</i> — and the window simply never appeared.</p>
     *
     * <p><b>Found in game, not here.</b> Neither feature's own tests combine them, which is the whole
     * argument for running the thing rather than only its parts.</p>
     */
    @Test
    public void aViewerAddedAfterAReshapeStillGetsTheWindow() {
        server.open();
        settle();
        assertNotNull(viewerA.root());

        // Reshape: this renumbers and re-hashes the description.
        root.addChildAt(new com.crystalgui.ui.elements.UIText("inserted"), 0);
        settle();
        assertEquals(3, viewerA.root().getChildren().size());

        server.addViewer(serverB);
        settle();

        assertNotNull("a viewer added after a reshape must still receive the window", viewerB.root());
        assertEquals("and the CURRENT tree, not the one open() described",
                3, viewerB.root().getChildren().size());
    }

    /** An event from either client runs the server's one lambda. */
    @Test
    public void anEventFromEitherViewerReachesTheServer() {
        AtomicInteger presses = new AtomicInteger();
        server.onActivate(button, ctx -> presses.incrementAndGet());
        server.addViewer(serverB);
        server.open();
        settle();

        ((Button) viewerA.root().getChildren().get(0)).onPressed.emit();
        settle();
        assertEquals("alice's press", 1, presses.get());

        ((Button) viewerB.root().getChildren().get(0)).onPressed.emit();
        settle();
        assertEquals("and bob's", 2, presses.get());
    }

    /**
     * <b>A server-driven state delta must not come back as a user interaction.</b>
     *
     * <p>Applying a delta calls the widget's ordinary setter, which fires the widget's ordinary change
     * signal — and that signal is exactly what {@code wireReportedEvents} attached the report to. So
     * the server moving a slider made every client that received it tell the server the user had moved
     * it, one report per viewer, on a gesture nobody made.</p>
     *
     * <p>It survived because it is <b>harmless in the common case and only in the common case</b>: the
     * echo carries the value the server just sent, so the handler sets the model to what it already
     * holds and {@code Property.set} returns early. It stops being harmless the moment a handler
     * <em>counts</em> anything, logs who did it, or charges for it — and with two viewers it is
     * attributed to the wrong player, which is the version that cannot be shrugged off.</p>
     *
     * <p>{@code ClientUiSession.shouldSuppress} was the narrow ancestor of this fix: it stops a delta
     * landing on a focused text field and resetting the caret, which is the same loop noticed from the
     * one place it was visible.</p>
     */
    @Test
    public void aServerDrivenChangeIsNotReportedBackAsAnInteraction() {
        AtomicInteger reports = new AtomicInteger();
        // Before open(), because the set of reported events is part of the description.
        server.on(slider, Slider.VALUE_CHANGED, (ctx, value) -> reports.incrementAndGet());
        server.addViewer(serverB);
        server.open();
        settle();

        slider.setValue(7f);        // THE SERVER moved it. Nobody touched anything.
        settle();

        assertEquals("the delta never arrived", 7f, sliderOf(viewerA).getValue(), 1e-3f);
        assertEquals(7f, sliderOf(viewerB).getValue(), 1e-3f);
        assertEquals("a state delta was reported back as if a user had done it", 0, reports.get());

        // The positive control, and it is not a formality: a fix written as "never report" passes
        // every line above and makes every control in the application dead.
        sliderOf(viewerA).setValue(3f);
        settle();
        assertEquals("a real interaction must still be reported", 1, reports.get());
    }

    private static Slider sliderOf(ClientUiSession<Object> viewer) {
        return (Slider) viewer.root().getChildren().get(1);
    }

    /**
     * A server method registered before a viewer joined is still served to it.
     *
     * <p>The failure this prevents is the nastiest kind of fan-out bug: it works for whoever connected
     * first and answers METHOD_NOT_FOUND for everyone else, which is a difference nothing in the code
     * would explain.</p>
     */
    @Test
    public void aLateViewerGetsMethodsRegisteredBeforeItJoined() {
        server.onCall("probe/echo", (args, respond) -> {
            StateMap<Object> out = new StateMap<>(PlainOps.INSTANCE);
            out.putString("said", args.getString("say", ""));
            respond.ok(out);
        });
        server.open();
        settle();

        server.addViewer(serverB);
        settle();

        AtomicReference<String> answer = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        StateMap<Object> args = new StateMap<>(PlainOps.INSTANCE);
        args.putString("say", "hello");
        viewerB.call("probe/echo", args, result -> answer.set(result.getString("said", "")), error::set);
        settle();

        assertEquals("the late viewer must be served too (error: " + error.get() + ")",
                "hello", answer.get());
    }

    /**
     * {@code call} is refused when there is more than one viewer.
     *
     * <p>A request has exactly one answer, so "call the client" stops meaning anything the moment there
     * are several. Refusing beats picking one silently, which would deliver to whichever happened to be
     * first in a list.</p>
     */
    @Test
    public void callIsAmbiguousWithTwoViewersAndSaysSo() {
        server.addViewer(serverB);
        server.open();
        settle();

        try {
            server.call("probe/anything", null, result -> { }, error -> { });
            fail("call() must refuse to guess which of two viewers it meant");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("ambiguous"));
        }
    }

    /** …and naming one works. */
    @Test
    public void callViewerNamesItsTarget() {
        AtomicReference<String> whoAnswered = new AtomicReference<>();
        viewerA.onCall("probe/who", (args, respond) -> {
            whoAnswered.set("alice");
            respond.ok(null);
        });
        viewerB.onCall("probe/who", (args, respond) -> {
            whoAnswered.set("bob");
            respond.ok(null);
        });
        server.addViewer(serverB);
        server.open();
        settle();

        server.callViewer("bob", "probe/who", null, result -> { }, error -> { });
        settle();

        assertEquals("bob", whoAnswered.get());
    }

    /** Removing a viewer stops its updates and leaves the window alive for the rest. */
    @Test
    public void removingAViewerStopsItsUpdatesAndKeepsTheWindow() {
        server.addViewer(serverB);
        server.open();
        settle();

        assertTrue(server.removeViewer(serverB));
        assertEquals(1, server.viewerCount());

        slider.setValue(3f);
        settle();

        assertEquals("the remaining viewer still updates",
                3f, ((Slider) viewerA.root().getChildren().get(1)).getValue(), 0.001f);
        assertEquals("the removed one is frozen where it was",
                0f, ((Slider) viewerB.root().getChildren().get(1)).getValue(), 0.001f);
    }
}
