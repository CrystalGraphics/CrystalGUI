package com.crystalgui.headless;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.SheetRef;
import com.crystalgui.net.window.SheetSupply;
import com.crystalgui.net.protocol.UiMethods;
import com.crystalgui.net.window.ClientWindows;
import com.crystalgui.net.window.ClientWindowBehaviour;
import com.crystalgui.net.window.ClientWindowContext;
import com.crystalgui.net.window.ServerFragment;
import com.crystalgui.net.window.ServerWindows;
import com.crystalgui.net.window.ServerWindow;
import com.crystalgui.net.window.WindowScope;
import com.crystalgui.net.window.WindowProtocol;
import com.crystalgui.net.window.WindowMount;
import com.crystalgui.net.window.WindowType;
import com.crystalgui.net.window.PanelType;
import com.crystalgui.ui.elements.Switch;
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
 * The window lifecycle — {@code WindowProtocol}, and the four ways a window ends.
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
public class WindowLifecycleTest {

    /** The shared descriptor both halves reference, so a mismatch cannot be spelled. */
    private static final WindowType<Panel> PANEL = WindowType.of("test:panel", Panel::bindTo);
    private static final WindowType<Panel> OTHER_PANEL = WindowType.of("test:other", Panel::bindTo);

    /** A third type, for the builder — it needs no behaviour, so binding is never asked for. */
    private static final WindowType<Panel> BUILT = WindowType.of("test:built", Panel::bindTo);

    private static final String TYPE = "test:panel";
    private static final String OTHER_TYPE = "test:other";

    private InMemoryTransport<Object>[] link;
    private ProtocolConnection<Object> serverSide;
    private ProtocolConnection<Object> clientSide;
    private ServerWindows server;
    private ClientWindows client;
    private RecordingMount mount;

    @Before
    public void setUp() {
        ElementRegistry.bootstrapBuiltins();
        Protocols.resetForTesting();
        WindowProtocol.resetForTesting();
        WindowProtocol.register();

        link = InMemoryTransport.pair();
        // A peer that is non-null is what makes one end the SERVER; null is what makes the other the
        // client. Same discriminator the workspace contributor reads, and the reason single player
        // ends up with one of each rather than two of either.
        serverSide = Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "a-player");
        clientSide = Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);
        server = ServerWindows.of(serverSide);
        client = ClientWindows.of(clientSide);
        mount = new RecordingMount();
        client.setMount(mount);
    }

    @After
    public void tearDown() {
        ClientWindows.unregister(TYPE);
        ClientWindows.unregister(OTHER_TYPE);
        Protocols.resetForTesting();
        WindowProtocol.resetForTesting();
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
        ClientWindows.register(TYPE, context -> {
            got.set(context);
            return new ClientWindowBehaviour<UIElement>() {
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
        ClientWindows.register(TYPE, context -> {
            seen.add(context.type());
            return new ClientWindowBehaviour<UIElement>() { };
        });

        server.open(new OtherWindow());
        settle();
        assertTrue("a window of another type is none of its business", seen.isEmpty());

        server.open(new TestWindow());
        settle();
        assertEquals(1, seen.size());
    }

    /**
     * The typed registration hands a behaviour its <b>bound panel</b>, not a tree to search.
     *
     * <p>What it replaces is three lines of {@code querySelector} guarded by {@code instanceof} —
     * which silently do nothing when an id moves, and are indistinguishable from a window that is
     * deliberately inert. Here the parts are resolved once, up front, and a press reaches the server
     * through a field.</p>
     */
    @Test
    public void aTypedRegistrationHandsTheBehaviourThePanelRatherThanTheTree() {
        AtomicReference<Panel> bound = new AtomicReference<>();
        ClientWindows.register(PANEL, context -> new ClientWindowBehaviour<Panel>() {
            @Override
            public void onPanelBound(Panel panel) {
                bound.set(panel);
            }
        });

        TestWindow window = server.open(new TestWindow());
        settle();

        assertNotNull("the behaviour was handed a panel", bound.get());
        assertNotSame("bound to the CLIENT's rebuilt tree, not the server's object",
                window.panel.root, bound.get().root);

        // A field, not a lookup -- and it reaches the server exactly as the searched version did.
        bound.get().press.onPressed.emit();
        settle();
        assertEquals(1, window.presses.get());
    }

    /**
     * A binding fails <b>loudly</b> when the tree is not the shape it expects.
     *
     * <p>The whole point of binding over searching: a missing part is an error at mount rather than a
     * control that looks wired and does nothing. Contained, too — the window itself still mounts,
     * because a broken behaviour must not take the server's UI down with it.</p>
     */
    @Test
    public void aBindingThatCannotFindItsPartsFailsAtMountRatherThanAtPressTime() {
        WindowType<Panel> wrong = WindowType.of(TYPE, root -> {
            root.require("#nothing-like-this", Button.class);
            throw new AssertionError("require should have thrown first");
        });
        ClientWindows.register(wrong, context -> new ClientWindowBehaviour<Panel>() { });

        server.open(new TestWindow());
        settle();

        assertEquals("the window is still on screen", 1, mount.mounted.size());
        assertNull("but it has no local behaviour", clientBehaviourOf(mount.mounted.get(0)));
    }

    /** There is no accessor for it, and there should not be — this is the test peeking. */
    @Nullable
    private static ClientWindowBehaviour clientBehaviourOf(ClientWindowContext context) {
        try {
            java.lang.reflect.Field field = context.getClass().getDeclaredField("behaviour");
            field.setAccessible(true);
            return (ClientWindowBehaviour) field.get(context);
        } catch (ReflectiveOperationException impossible) {
            throw new AssertionError(impossible);
        }
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

    // ── Sheets ──────────────────────────────────────────────────────────────

    /**
     * A theme the client has <b>never seen</b> reaches it over the wire.
     *
     * <p>{@code SheetRef} crossed the wire from the day sheets did, and there was no way to
     * <em>fetch</em> the sheet behind one — so every host resolved refs from a constant in its own jar
     * and said so in a comment. Fine for a UI whose mod is installed on both sides; a wall for anything
     * a server authors.</p>
     */
    @Test
    public void aSheetTheClientHasNeverSeenIsFetchedByHash() {
        List<List<String>> applied = new ArrayList<>();
        client.setSheetSupply(new SheetSupply((window, css) -> applied.add(css)));

        server.open(new StyledWindow());
        settle();

        assertEquals("the sheets arrived as one batch", 1, applied.size());
        assertEquals(2, applied.get(0).size());
        // IN THE ORDER THE SERVER NAMED THEM. Order is load-bearing -- the engine's sheet list is flat
        // and a later sheet wins ties -- so the batch is collected before any of it is applied rather
        // than applied as each answer arrives.
        assertEquals(".a { color: #111111; }", applied.get(0).get(0));
        assertEquals(".b { color: #222222; }", applied.get(0).get(1));
    }

    /**
     * A local resolver answers without asking, and the wire is not touched.
     *
     * <p>The right answer for a shared theme: sending bytes both sides already hold is waste, which is
     * why a {@code SheetRef} carries an id as well as a hash.</p>
     */
    @Test
    public void aSheetTheClientAlreadyHasCostsNoTraffic() {
        List<List<String>> applied = new ArrayList<>();
        client.setSheetSupply(new SheetSupply((window, css) -> applied.add(css))
                .addResolver(ref -> "local:" + ref.id()));

        server.open(new StyledWindow());
        settle();

        assertEquals(1, applied.size());
        assertEquals("local:test:a", applied.get(0).get(0));
        for (Object raw : link[1].sent()) {
            if (raw instanceof java.util.Map) {
                assertFalse("nothing asked for a sheet",
                        UiMethods.SHEET.equals(((java.util.Map<?, ?>) raw).get("m")));
            }
        }
    }

    /** A theme that cannot be fetched is a plain window, never a missing one. */
    @Test
    public void aSheetTheServerCannotProduceLeavesTheOthersAlone() {
        List<List<String>> applied = new ArrayList<>();
        client.setSheetSupply(new SheetSupply((window, css) -> applied.add(css)));

        server.open(new HalfStyledWindow());
        settle();

        assertEquals(1, applied.size());
        assertEquals("the one that could be resolved, and only it", 1, applied.get(0).size());
        assertEquals(".a { color: #111111; }", applied.get(0).get(0));
    }

    /**
     * The correctness argument behind suppressing the <b>whole</b> flush rather than only the send.
     *
     * <p>A tree delta renumbers both sides. Suppress the send while still renumbering here and the two
     * peers disagree about every id past the change, so the next state delta lands on the wrong
     * elements — silently, because an id is just an int and every one of them still resolves to
     * something. Gating above both means the tree is simply not re-described while nobody is looking,
     * and the client's numbering stays the one it was last told.</p>
     *
     * <p>Reshaping a hidden window is not a corner case: it is a background job adding a row to a panel
     * somebody has minimised.</p>
     */
    @Test
    public void aWindowReshapedWhileHiddenComesBackWithItsNumberingIntact() {
        LateFragmentWindow window = server.open(new LateFragmentWindow());
        settle();
        ClientWindowContext shown = mount.mounted.get(0);

        shown.visibilityChanged(false);
        settle();

        // THE SHAPE CHANGES while nobody is looking, and then the state does.
        window.attachNow();
        window.panel.label.setText("changed while hidden");
        settle();

        assertNull("nothing was re-described", shown.root().querySelector("#late"));

        shown.visibilityChanged(true);
        settle();

        assertNotNull("the new subtree arrived", shown.root().querySelector("#late"));
        assertEquals("and the state landed on the RIGHT element, not on whatever took its number",
                "changed while hidden", textOf(shown.root().querySelector("#label")));

        // ...and the delta that brought the fragment carried its reported events, so the new widget
        // reports back exactly as one described at open would.
        ((Button) shown.root().querySelector("#late")).onPressed.emit();
        settle();
        assertEquals(1, window.fragment.presses.get());
    }

    // ── Panel: one class, both sides ────────────────────────────────────────

    /**
     * The whole of a networked UI in one class — no {@code ServerWindow}, no
     * {@code ClientWindowBehaviour}, no id strings, and no {@code bindTo}.
     *
     * <p>What is asserted is that the <b>three lifetimes land on the right sides</b>: the fields are
     * created and named from their own declarations, the server's handlers reach the server's panel,
     * and the client's listeners reach the client's — which are different objects over different
     * trees, and the easiest thing in this design to get subtly wrong.</p>
     */
    @Test
    public void aPanelDeclaresItsWidgetsItsServerHalfAndItsClientHalfInOneClass() {
        TestPanel.served.set(0);
        TestPanel.clicked.set(0);
        ClientWindows.register(TestPanel.TYPE);

        ServerWindow window = server.open(TestPanel.TYPE.serve("a-model"));
        settle();

        // The field NAME became the id, on both sides, without anybody writing the string.
        UIElement shown = mount.mounted.get(0).root();
        assertNotNull("the field name is the id", shown.querySelector("#power"));
        assertNotNull(shown.querySelector("#press"));

        // serve() ran on the server: a press crosses the wire and reaches its handler.
        ((Button) shown.querySelector("#press")).onPressed.emit();
        settle();
        assertEquals("serve() wired the server half", 1, TestPanel.served.get());

        // client() ran on the client: a local listener, attached to the REBUILT tree.
        ((Switch) shown.querySelector("#power")).setChecked(true);
        settle();
        assertEquals("client() wired the client half", 1, TestPanel.clicked.get());

        // And the two panels are genuinely different objects over different trees.
        TestPanel served = TestPanel.TYPE.panelOf(window);
        assertNotNull(served);
        assertNotSame("the server's tree is not the client's", served.root(), shown);
        assertEquals("only the server sees the model", "a-model", served.modelForTest());
    }

    /** A widget whose constructor takes arguments keeps its initializer; the base only fills nulls. */
    @Test
    public void aFieldWithAnInitializerIsKeptAndStillNamed() {
        TestPanel panel = TestPanel.TYPE.build("m");
        assertEquals("press", panel.press.getId());
        assertEquals("the initializer's own label survived", "press-label", panel.press.getText());
        assertNotNull("and the null field was created for us", panel.power);
        assertEquals("power", panel.power.getId());
    }

    /** Binding resolves every declared field out of the rebuilt tree, by name and by type. */
    @Test
    public void aBoundPanelResolvesItsFieldsFromTheRebuiltTree() {
        server.open(TestPanel.TYPE.serve("m"));
        settle();

        TestPanel bound = TestPanel.TYPE.windowType().bind(mount.mounted.get(0).root());
        assertNotNull(bound.power);
        assertNotNull(bound.press);
        assertSame("resolved from the tree, not created afresh",
                mount.mounted.get(0).root().querySelector("#power"), bound.power);
        assertNull("a bound panel has no model", bound.modelForTest());
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

        ServerWindow window = server.open(ServerWindow.of(BUILT, Panel::new, panel -> panel.root)
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
        final UIElement root;
        final Button press;
        final com.crystalgui.ui.elements.UIText label;

        Panel() {
            root = new UIElement();
            press = new Button("press");
            press.setId("press");
            label = new com.crystalgui.ui.elements.UIText("");
            label.setId("label");
            root.addChild(press);
            root.addChild(label);
        }

        /** The client's half: typed hold of a tree that was rebuilt from a description. */
        private Panel(UIElement rebuilt) {
            root = rebuilt;
            press = rebuilt.require("#press", Button.class);
            label = rebuilt.require("#label", com.crystalgui.ui.elements.UIText.class);
        }

        static Panel bindTo(UIElement rebuilt) {
            return new Panel(rebuilt);
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
        public WindowType<Panel> type() {
            return PANEL;
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
        protected void bind(WindowScope io) {
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

    /** Two sheets, both offered with their text — what a server-authored theme looks like. */
    private static final class StyledWindow extends ServerWindow {
        private final Panel panel = new Panel();

        @Override
        public WindowType<Panel> type() {
            return PANEL;
        }

        @Override
        public UIElement root() {
            return panel.root;
        }

        @Override
        protected void bind(WindowScope io) {
            io.sheet(SheetRef.ofResource("test:a", "hash-a"), ".a { color: #111111; }");
            io.sheet(SheetRef.ofResource("test:b", "hash-b"), ".b { color: #222222; }");
        }
    }

    /** One sheet offered with its text, one NAMED only — a theme the client is expected to ship. */
    private static final class HalfStyledWindow extends ServerWindow {
        private final Panel panel = new Panel();

        @Override
        public WindowType<Panel> type() {
            return PANEL;
        }

        @Override
        public UIElement root() {
            return panel.root;
        }

        @Override
        protected void bind(WindowScope io) {
            io.sheet(SheetRef.ofResource("test:a", "hash-a"), ".a { color: #111111; }");
            io.sheet(SheetRef.ofResource("test:missing", "hash-missing"));
        }
    }

    private static final class OtherWindow extends TestWindow {
        @Override
        public WindowType<Panel> type() {
            return OTHER_PANEL;
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
        public WindowType<Panel> type() {
            return PANEL;
        }

        @Override
        public UIElement root() {
            return panel.root;
        }

        @Override
        protected void bind(WindowScope io) {
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
        protected void bind(WindowScope io) {
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
        public WindowType<Panel> type() {
            return PANEL;
        }

        @Override
        public UIElement root() {
            return panel.root;
        }

        @Override
        protected void bind(WindowScope io) {
            panel.root.addChild(fragment.root());
            io.attach(fragment, "panel");
        }
    }

    private static final class DoubleAttachWindow extends ServerWindow {
        private final Panel panel = new Panel();

        @Override
        public WindowType<Panel> type() {
            return PANEL;
        }

        @Override
        public UIElement root() {
            return panel.root;
        }

        @Override
        protected void bind(WindowScope io) {
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
        public WindowType<Panel> type() {
            return PANEL;
        }

        @Override
        public UIElement root() {
            return panel.root;
        }

        @Override
        protected void bind(WindowScope io) {
            SaveFragment fragment = new SaveFragment();
            panel.root.addChild(fragment.root());
            io.attach(fragment, "panel");
            io.onActivate(fragment.press, ctx -> { });
        }
    }

    private static final class LateFragmentWindow extends ServerWindow {
        final Panel panel = new Panel();
        final SaveFragment fragment = new SaveFragment();
        @Nullable
        private WindowScope scope;

        @Override
        public WindowType<Panel> type() {
            return PANEL;
        }

        @Override
        public UIElement root() {
            return panel.root;
        }

        @Override
        protected void bind(WindowScope io) {
            this.scope = io;
        }

        void attachNow() {
            if (scope == null) throw new IllegalStateException("not bound yet");
            panel.root.addChild(fragment.root());
            scope.attach(fragment, "late");
        }
    }

    /**
     * One class: widgets, layout, server half, client half.
     *
     * <p>Fully qualified because this test already has a nested {@code Panel} fixture of its own from
     * the earlier cases, and a nested type shadows an import.</p>
     *
     * @see com.crystalgui.net.window.Panel
     */
    public static final class TestPanel extends com.crystalgui.net.window.Panel<String> {

        static final PanelType<TestPanel, String> TYPE =
                PanelType.of("test:panel-base", TestPanel::new);

        /** Static because the two sides are different INSTANCES, which is the point being asserted. */
        static final AtomicInteger served = new AtomicInteger();
        static final AtomicInteger clicked = new AtomicInteger();

        public Switch power;                                  // created and named for us
        public Button press = new Button("press-label");      // ctor argument, so we write it

        @Override
        protected void layout() {
            add(power);
            add(press);
        }

        @Override
        protected void serve(WindowScope io) {
            io.onActivate(press, ctx -> served.incrementAndGet());
        }

        @Override
        protected void client(ClientWindowContext window) {
            power.attachListener(checked -> clicked.incrementAndGet());
        }

        @Nullable
        String modelForTest() {
            return model();
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
