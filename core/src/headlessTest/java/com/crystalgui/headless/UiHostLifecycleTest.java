package com.crystalgui.headless;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.host.ClientUiHost;
import com.crystalgui.net.host.ClientWindowBehaviour;
import com.crystalgui.net.host.ClientWindowContext;
import com.crystalgui.net.host.ServerFragment;
import com.crystalgui.net.host.ServerUiHost;
import com.crystalgui.net.host.ServerWindow;
import com.crystalgui.net.host.SessionScope;
import com.crystalgui.net.host.UiHosts;
import com.crystalgui.net.host.UiWindows;
import com.crystalgui.net.host.WindowMount;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.ElementRegistry;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.Button;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The window lifecycle — {@code UiHosts}, and the four ways a window ends.
 *
 * <h3>What these are for</h3>
 *
 * <p>Not that a window opens. That is the easy half and passes against almost any implementation,
 * including the hand-rolled per-mod arrangement this replaces. What is asserted here is the part that
 * was <b>missing entirely</b> — a client could not tell a server it had closed a window, so a closed
 * window left a session open, observing and flushing into a destroyed frame — and the parts that were
 * races: adopting a session before the first window can arrive, and reconciling a window against a
 * place to put it when either can happen first.</p>
 *
 * <p>Every close test asserts <b>both sides</b>: that the reason reached the server exactly once, and
 * that the other end actually stopped. Asserting one alone passes against a teardown that only ever
 * runs on the side you looked at, which is precisely the shape of the bug being fixed.</p>
 */
public class UiHostLifecycleTest {

    private static final String TYPE = "test:panel";
    private static final String OTHER_TYPE = "test:other";

    private InMemoryTransport<Object>[] link;
    private ProtocolConnection<Object> serverSide;
    private ProtocolConnection<Object> clientSide;
    private ServerUiHost server;
    private ClientUiHost client;
    private RecordingMount mount;

    @Before
    public void setUp() {
        ElementRegistry.bootstrapBuiltins();
        Protocols.resetForTesting();
        UiHosts.resetForTesting();
        UiHosts.register();

        link = InMemoryTransport.pair();
        // A peer that is non-null is what makes one end the SERVER; null is what makes the other the
        // client. Same discriminator the workspace contributor reads, and the reason single player
        // ends up with one of each rather than two of either.
        serverSide = Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "a-player");
        clientSide = Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);
        server = ServerUiHost.of(serverSide);
        client = ClientUiHost.of(clientSide);
        mount = new RecordingMount();
        client.setMount(mount);
    }

    @After
    public void tearDown() {
        ClientUiHost.unregister(TYPE);
        ClientUiHost.unregister(OTHER_TYPE);
        Protocols.resetForTesting();
        UiHosts.resetForTesting();
    }

    /**
     * Moves everything both ways until the conversation runs out of things to say.
     *
     * <p>{@code deliver()} is what an {@code InMemoryTransport} needs in place of a socket — the pump
     * handed to {@code Protocols.open} is a no-op here, so nothing crosses without it.</p>
     */
    private void settle() {
        for (int i = 0; i < 8; i++) {
            link[0].deliver();
            link[1].deliver();
            serverSide.tick();
            clientSide.tick();
        }
    }

    // ── Opening ─────────────────────────────────────────────────────────────

    @Test
    public void aWindowOpensAndTheClientMountsItWithItsTypeAndTitle() {
        server.open(new TestWindow());
        settle();

        assertEquals("one window on screen", 1, mount.mounted.size());
        ClientWindowContext shown = mount.mounted.get(0);
        assertEquals(TYPE, shown.type());
        // The title travels. Before this the client hard-coded one, so the side that defined the whole
        // UI could not name the frame it landed in.
        assertEquals("Test panel", shown.title());
        assertNotNull(shown.root());
    }

    @Test
    public void theHostAllocatesWindowIdsSoNobodyPicksAConstant() {
        ServerWindow first = server.open(new TestWindow());
        ServerWindow second = server.open(new OtherWindow());
        settle();

        assertNotSame(first.windowId(), second.windowId());
        assertEquals("both are being served", 2, server.windowCount());
        assertEquals("both are on screen", 2, mount.mounted.size());
    }

    // ── The close matrix ────────────────────────────────────────────────────

    @Test
    public void theServerClosingAWindowEndsBothSides() {
        TestWindow window = server.open(new TestWindow());
        settle();

        server.close(window, "the block was broken");
        settle();

        assertEquals(1, window.closes.size());
        assertEquals(ServerWindow.CloseReason.SERVER, window.closes.get(0));
        assertFalse("the session stopped serving", window.isOpen());
        assertEquals("the client took it off screen", 1, mount.closedByServer.size());
        assertEquals("the block was broken", mount.closedByServer.get(0));
        assertEquals(0, server.windowCount());
        assertEquals(0, client.windowCount());
    }

    /**
     * The row that did not exist. There was no {@code ui/close}, so a user closing a window told the
     * server nothing at all and the session stayed open forever.
     */
    @Test
    public void theUserClosingAWindowReachesTheServerAndEndsTheSession() {
        TestWindow window = server.open(new TestWindow());
        settle();

        mount.mounted.get(0).userClosed();
        settle();

        assertEquals("the server heard about it exactly once", 1, window.closes.size());
        assertEquals(ServerWindow.CloseReason.CLIENT, window.closes.get(0));
        assertFalse(window.isOpen());
        assertEquals("the server stopped serving it", 0, server.windowCount());
        assertEquals("and did not echo the close back at a frame that has gone",
                0, mount.closedByServer.size());
        assertEquals(0, client.windowCount());
    }

    @Test
    public void aWindowThatStopsBeingValidClosesItself() {
        TestWindow window = new TestWindow();
        server.open(window);
        settle();

        window.valid = false;
        settle();

        assertEquals(1, window.closes.size());
        assertEquals(ServerWindow.CloseReason.NOT_VALID, window.closes.get(0));
        assertEquals("the client was told", 1, mount.closedByServer.size());
    }

    /**
     * What every mod used to write for itself as a logout handler — and what a mod that forgot left
     * behind: a session still observing its tree and encoding deltas into a dead wire.
     */
    @Test
    public void losingTheConnectionEndsEveryWindowOnBothSidesAndSendsNothing() {
        TestWindow one = server.open(new TestWindow());
        OtherWindow two = server.open(new OtherWindow());
        settle();
        int before = link[0].sent().size();

        serverSide.close("player left");

        assertEquals(1, one.closes.size());
        assertEquals(ServerWindow.CloseReason.CONNECTION_LOST, one.closes.get(0));
        assertEquals(ServerWindow.CloseReason.CONNECTION_LOST, two.closes.get(0));
        assertEquals("nothing is sent to a peer that has gone", before, link[0].sent().size());
        assertEquals(0, server.windowCount());

        clientSide.close("disconnected");
        assertEquals("the client dropped its windows too", 0, client.windowCount());
        assertEquals("and took them off screen", 2, mount.closedByServer.size());
    }

    /**
     * A window ends once, whatever happens next. The double-close path is the one a real host reaches
     * routinely — a frame destroyed by a server close still runs its own teardown.
     */
    @Test
    public void aWindowEndsExactlyOnceHoweverManyTimesItIsAsked() {
        TestWindow window = server.open(new TestWindow());
        settle();
        ClientWindowContext shown = mount.mounted.get(0);

        server.close(window, "first");
        server.close(window, "again");
        window.close("and again");
        // The double-close path a real host reaches routinely: a frame destroyed by a server close
        // still runs its own teardown, which calls back here.
        shown.userClosed();
        settle();

        assertEquals(1, window.closes.size());
    }

    // ── Keys ────────────────────────────────────────────────────────────────

    @Test
    public void openingUnderAKeyThatIsAlreadyOpenBringsTheExistingWindowForward() {
        TestWindow first = server.open(new TestWindow().withKey("test:one"));
        settle();
        UIElement firstRoot = mount.mounted.get(0).root();

        ServerWindow again = server.open(new TestWindow().withKey("test:one"));
        settle();

        assertSame("the same window, not a second one", first, again);
        assertEquals(1, server.windowCount());
        assertEquals("nothing was mounted twice", 1, mount.mounted.size());
        assertEquals("it was brought forward", 1, mount.focused.size());
        assertSame("and its tree was left alone -- that is the whole point of not reopening",
                firstRoot, mount.mounted.get(0).root());
    }

    @Test
    public void aKeyHeldByADifferentTypeIsAWiringMistakeAndSaysSo() {
        server.open(new TestWindow().withKey("test:shared"));
        settle();
        try {
            server.open(new OtherWindow().withKey("test:shared"));
            fail("expected the clash to be refused");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("test:shared"));
        }
    }

    @Test
    public void aWindowWithNoKeyOpensAsManyTimesAsItIsAsked() {
        server.open(new TestWindow());
        server.open(new TestWindow());
        settle();
        assertEquals(2, server.windowCount());
        assertEquals(2, mount.mounted.size());
    }

    // ── The mount ───────────────────────────────────────────────────────────

    /**
     * A window can arrive before the screen has ever been opened, and the screen can be opened before
     * any window arrives. Whichever happens second completes the mount — which is what the per-tick
     * poll in every client host was doing by hand.
     */
    @Test
    public void aWindowThatArrivesBeforeTheMountIsQueuedAndDrainedWhenOneAppears() {
        client.setMount(null);
        RecordingMount later = new RecordingMount();

        server.open(new TestWindow());
        settle();
        assertEquals("nowhere to put it yet", 0, later.mounted.size());
        assertEquals(1, client.waitingCount());

        client.setMount(later);
        assertEquals("and it lands the moment there is", 1, later.mounted.size());
        assertEquals(0, client.waitingCount());
    }

    // ── Behaviour by type ───────────────────────────────────────────────────

    @Test
    public void behaviourIsBuiltForTheRegisteredTypeAndToldWhenTheWindowEnds() {
        List<String> closed = new ArrayList<>();
        AtomicReference<ClientWindowContext> got = new AtomicReference<>();
        ClientUiHost.register(TYPE, context -> {
            got.set(context);
            return new ClientWindowBehaviour() {
                @Override
                public void onClosed(String reason) {
                    closed.add(reason);
                }
            };
        });

        TestWindow window = server.open(new TestWindow());
        settle();
        assertNotNull("the behaviour was built", got.get());
        assertEquals(TYPE, got.get().type());

        server.close(window, "done");
        settle();
        assertEquals(1, closed.size());
        assertEquals("done", closed.get(0));
    }

    /**
     * The improvement over {@code MenuScreens}, where an unregistered type is a broken screen. A
     * description is self-sufficient, so an unknown window renders and interacts and merely has no
     * local extras.
     */
    @Test
    public void aWindowOfAnUnknownTypeStillMountsAndStillReportsItsEvents() {
        TestWindow window = server.open(new TestWindow());
        settle();

        assertEquals("it is on screen", 1, mount.mounted.size());

        Button pressMe = (Button) mount.mounted.get(0).root().querySelector("#press");
        assertNotNull("and its widgets came through", pressMe);
        pressMe.onPressed.emit();
        settle();

        assertEquals("and the server heard the press", 1, window.presses.get());
    }

    /**
     * The window's type is what stops one mod's behaviour adopting another mod's tree — which is what
     * happened when every client host took every window on the connection.
     */
    @Test
    public void behaviourRegisteredForOneTypeNeverSeesAnother() {
        List<String> seen = new ArrayList<>();
        ClientUiHost.register(TYPE, context -> {
            seen.add(context.type());
            return new ClientWindowBehaviour() { };
        });

        server.open(new OtherWindow());
        settle();
        assertTrue("a window of another type is none of its business", seen.isEmpty());

        server.open(new TestWindow());
        settle();
        assertEquals(1, seen.size());
    }

    // ── Window-scoped notifications ─────────────────────────────────────────

    /**
     * Two windows of one type, both listening for the same notification. On
     * {@code ProtocolConnection.onNotify} — where the example taught this — the second registration is
     * refused by the router and the second window <b>throws at open</b>.
     */
    @Test
    public void twoWindowsMayNameTheSameNotificationAndEachHearsOnlyItsOwn() {
        NotifyingWindow one = server.open(new NotifyingWindow("one"));
        NotifyingWindow two = server.open(new NotifyingWindow("two"));
        settle();

        StateMap<Object> payload = new StateMap<>(PlainOps.INSTANCE);
        payload.putString("from", "the client");
        mount.mounted.get(1).session().notify("ping", payload);
        settle();

        assertEquals("only the window it was addressed to", 0, one.heard.size());
        assertEquals(1, two.heard.size());
        assertEquals("the client", two.heard.get(0));
    }

    // ── Fragments ───────────────────────────────────────────────────────────

    @Test
    public void aFragmentsMethodsAreNamespacedByItsScope() {
        AtomicReference<String> answered = new AtomicReference<>();
        FragmentWindow window = server.open(new FragmentWindow());
        settle();

        // The QUALIFIED name is what crosses the wire -- "save" belongs to the fragment's namespace,
        // not to the window's.
        mount.mounted.get(0).session().call("panel/save", null,
                result -> answered.set(result.getString("by", "")), error -> answered.set("!" + error));
        settle();
        assertEquals("the fragment", answered.get());

        // ...and the bare name is not served, which is what makes two fragments unable to collide.
        mount.mounted.get(0).session().call("save", null,
                result -> answered.set("unexpected"), error -> answered.set("refused"));
        settle();
        assertEquals("refused", answered.get());
        assertEquals(1, window.fragment.saves.get());
    }

    @Test
    public void twoFragmentsUnderOneScopeIsAWiringMistake() {
        try {
            server.open(new DoubleAttachWindow());
            fail("expected the second attach to be refused");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("panel"));
        }
    }

    /**
     * The boundary a parent must not cross. It used to be a bare {@code Map.put}: the parent silently
     * won and which handler ran depended on registration order.
     */
    @Test
    public void aParentCannotOverrideAHandlerItsChildAlreadyRegistered() {
        try {
            server.open(new OverridingWindow());
            fail("expected the duplicate handler to be refused");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("already handled"));
        }
    }

    /**
     * The relaxation F16 needed. The old rule refused every registration after {@code open()}, which
     * would have made a fragment attached to a live window impossible — and a tree delta re-describes
     * the new elements' reported events, so the client wires them exactly as it would at open.
     */
    @Test
    public void aFragmentMayBeAttachedToAWindowThatIsAlreadyOpen() {
        LateFragmentWindow window = server.open(new LateFragmentWindow());
        settle();
        assertEquals("nothing extra yet", 0, window.fragment.saves.get());

        window.attachNow();
        settle();

        AtomicReference<String> answered = new AtomicReference<>();
        mount.mounted.get(0).session().call("late/save", null,
                result -> answered.set("ok"), error -> answered.set("!" + error));
        settle();
        assertEquals("ok", answered.get());

        // The new element came across in a tree delta, WITH its reported event, so it reports back.
        Button added = (Button) mount.mounted.get(0).root().querySelector("#late");
        assertNotNull("the delta brought the fragment's tree", added);
        added.onPressed.emit();
        settle();
        assertEquals(1, window.fragment.presses.get());
    }

    // ── Visibility ──────────────────────────────────────────────────────────

    /**
     * Hiding is not closing: a hidden window is retained and detached, and the server should stop
     * describing a tree nobody is drawing. The gate is above the whole flush, so the two sides cannot
     * end up numbering the tree differently.
     */
    @Test
    public void aHiddenWindowStopsCostingTraffic() {
        TestWindow window = server.open(new TestWindow());
        settle();

        mount.mounted.get(0).visibilityChanged(false);
        settle();

        int quiet = link[0].sent().size();
        window.panel.label.setText("changed while nobody was looking");
        settle();
        assertEquals("nothing was sent", quiet, link[0].sent().size());

        mount.mounted.get(0).visibilityChanged(true);
        settle();
        assertTrue("and it catches up the moment it comes back", link[0].sent().size() > quiet);
        assertEquals("changed while nobody was looking",
                textOf(mount.mounted.get(0).root().querySelector("#label")));
    }

    /**
     * A state change made <b>before the client has finished fetching the description</b> must still
     * arrive.
     *
     * <p>Both delta handlers used to begin {@code if (… || root == null) return;}, so everything sent
     * between {@code session.open()} and the description round trip completing was silently dropped —
     * and permanently, because {@code Property.set} returns early on an unchanged value, so the server
     * never marks that widget dirty again. A window whose first tick writes a status line lost that
     * line for the life of the window.</p>
     *
     * <p>The first tick is exactly when a window mirrors its model, so this is the common case rather
     * than a corner: it is what {@code ServerWindow.tick} is for.</p>
     */
    @Test
    public void aStateChangeMadeWhileTheDescriptionIsStillInFlightIsNotLost() {
        TestWindow window = new TestWindow();
        server.open(window);

        // BEFORE a single message has crossed: the client has not seen ui/openWindow, let alone asked
        // for the tree behind its hash.
        window.panel.label.setText("written before the client could hear it");
        serverSide.tick();

        settle();

        assertEquals("written before the client could hear it",
                textOf(mount.mounted.get(0).root().querySelector("#label")));
    }

    // ── The builder ─────────────────────────────────────────────────────────

    /**
     * The same lifecycle without a class. A window that is one screenful of handlers should not have to
     * declare a type to hold them.
     */
    @Test
    public void aWindowBuiltFromLambdasBehavesExactlyLikeOne() {
        AtomicInteger pressed = new AtomicInteger();
        List<ServerWindow.CloseReason> closes = new ArrayList<>();

        ServerWindow window = server.open(UiWindows.window("test:built", Panel::new, panel -> panel.root)
                .key("test:built")
                .title(panel -> "Built")
                .wire((panel, io) -> io.onActivate(panel.press, ctx -> pressed.incrementAndGet()))
                .tick((panel, io) -> panel.label.setText("tick"))
                .onClosed(closes::add));
        settle();

        assertEquals("Built", mount.mounted.get(0).title());
        Button pressMe = (Button) mount.mounted.get(0).root().querySelector("#press");
        pressMe.onPressed.emit();
        settle();
        assertEquals(1, pressed.get());
        assertEquals("tick", textOf(mount.mounted.get(0).root().querySelector("#label")));

        mount.mounted.get(0).userClosed();
        settle();
        assertEquals(1, closes.size());
        assertEquals(ServerWindow.CloseReason.CLIENT, closes.get(0));
        assertFalse(window.isOpen());
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private static String textOf(@Nullable UIElement element) {
        return element instanceof com.crystalgui.ui.elements.UIText
                ? ((com.crystalgui.ui.elements.UIText) element).getText() : null;
    }

    /** A tree with one button and one label, which is all any of these need. */
    private static final class Panel {
        final UIElement root = new UIElement();
        final Button press = new Button("press");
        final com.crystalgui.ui.elements.UIText label = new com.crystalgui.ui.elements.UIText("");

        Panel() {
            press.setId("press");
            label.setId("label");
            root.addChild(press);
            root.addChild(label);
        }
    }

    private static class TestWindow extends ServerWindow {
        final Panel panel = new Panel();
        final AtomicInteger presses = new AtomicInteger();
        final List<CloseReason> closes = new ArrayList<>();
        boolean valid = true;
        @Nullable
        String key;

        TestWindow withKey(String key) {
            this.key = key;
            return this;
        }

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public String title() {
            return "Test panel";
        }

        @Nullable
        @Override
        public String key() {
            return key;
        }

        @Override
        public UIElement root() {
            return panel.root;
        }

        @Override
        protected void bind(SessionScope io) {
            io.onActivate(panel.press, ctx -> presses.incrementAndGet());
        }

        @Override
        protected boolean stillValid(@Nullable Object viewer) {
            return valid;
        }

        @Override
        protected void onClosed(CloseReason reason) {
            closes.add(reason);
        }
    }

    private static final class OtherWindow extends TestWindow {
        @Override
        public String type() {
            return OTHER_TYPE;
        }
    }

    private static final class NotifyingWindow extends ServerWindow {
        private final Panel panel = new Panel();
        private final String name;
        final List<String> heard = new ArrayList<>();

        NotifyingWindow(String name) {
            this.name = name;
        }

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public UIElement root() {
            return panel.root;
        }

        @Override
        protected void bind(SessionScope io) {
            io.onNotify("ping", payload -> heard.add(payload.getString("from", "?")));
        }
    }

    /** A fragment: its own subtree, its own handlers, its own namespace. */
    private static final class SaveFragment extends ServerFragment {
        final UIElement root = new UIElement();
        final Button press = new Button("late");
        final AtomicInteger saves = new AtomicInteger();
        final AtomicInteger presses = new AtomicInteger();

        SaveFragment() {
            press.setId("late");
            root.addChild(press);
        }

        @Override
        public UIElement root() {
            return root;
        }

        @Override
        protected void bind(SessionScope io) {
            io.onCall("save", (args, respond) -> {
                saves.incrementAndGet();
                StateMap<Object> out = io.newMap();
                out.putString("by", "the fragment");
                respond.ok(out);
            });
            io.onActivate(press, ctx -> presses.incrementAndGet());
        }
    }

    private static final class FragmentWindow extends ServerWindow {
        private final Panel panel = new Panel();
        final SaveFragment fragment = new SaveFragment();

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public UIElement root() {
            return panel.root;
        }

        @Override
        protected void bind(SessionScope io) {
            panel.root.addChild(fragment.root());
            io.attach(fragment, "panel");
        }
    }

    private static final class DoubleAttachWindow extends ServerWindow {
        private final Panel panel = new Panel();

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public UIElement root() {
            return panel.root;
        }

        @Override
        protected void bind(SessionScope io) {
            SaveFragment one = new SaveFragment();
            SaveFragment two = new SaveFragment();
            panel.root.addChild(one.root());
            panel.root.addChild(two.root());
            io.attach(one, "panel");
            io.attach(two, "panel");
        }
    }

    /** A parent reaching into a child's element — the boundary that used to be silently crossed. */
    private static final class OverridingWindow extends ServerWindow {
        private final Panel panel = new Panel();

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public UIElement root() {
            return panel.root;
        }

        @Override
        protected void bind(SessionScope io) {
            SaveFragment fragment = new SaveFragment();
            panel.root.addChild(fragment.root());
            io.attach(fragment, "panel");
            io.onActivate(fragment.press, ctx -> { });
        }
    }

    private static final class LateFragmentWindow extends ServerWindow {
        private final Panel panel = new Panel();
        final SaveFragment fragment = new SaveFragment();
        @Nullable
        private SessionScope scope;

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public UIElement root() {
            return panel.root;
        }

        @Override
        protected void bind(SessionScope io) {
            this.scope = io;
        }

        void attachNow() {
            if (scope == null) throw new IllegalStateException("not bound yet");
            panel.root.addChild(fragment.root());
            scope.attach(fragment, "late");
        }
    }

    /** A mount that remembers rather than draws. */
    private static final class RecordingMount implements WindowMount {
        final List<ClientWindowContext> mounted = new ArrayList<>();
        final List<String> closedByServer = new ArrayList<>();
        final List<ClientWindowContext> focused = new ArrayList<>();

        @Override
        public MountedWindow mount(ClientWindowContext context) {
            mounted.add(context);
            return new MountedWindow() {
                @Override
                public void closedByServer(String reason) {
                    closedByServer.add(reason);
                    mounted.remove(context);
                }

                @Override
                public void focus() {
                    focused.add(context);
                }

                @Override
                public void contentReplaced(UIElement newRoot) {
                }
            };
        }
    }
}
