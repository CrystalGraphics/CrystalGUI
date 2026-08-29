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
import com.crystalgui.net.window.ClientScope;
import com.crystalgui.net.window.ClientWindowContext;
import com.crystalgui.net.window.ClientWindows;
import com.crystalgui.net.window.Networked;
import com.crystalgui.net.window.ServerScope;
import com.crystalgui.net.window.ServerWindow;
import com.crystalgui.net.window.ServerWindows;
import com.crystalgui.net.window.UiType;
import com.crystalgui.net.window.WindowProtocol;
import com.crystalgui.net.window.WindowMount;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.ElementRegistry;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.Switch;
import com.crystalgui.ui.elements.UIText;

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
 *
 * <p>Every fixture is a {@link Networked} element — one class per UI, with per-<b>instance</b>
 * counters, because the server's panel and the client's are different instances over different trees
 * and which one a counter moved on is usually the thing under test.</p>
 */
public class WindowLifecycleTest {

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
        server.open(TestPanel.TYPE, null);
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
        ServerWindow<TestPanel> first = server.open(TestPanel.TYPE, null);
        ServerWindow<OtherPanel> second = server.open(OtherPanel.TYPE, null);
        settle();

        assertNotSame(first.windowId(), second.windowId());
        assertEquals("both are being served", 2, server.windowCount());
        assertEquals("both are on screen", 2, mount.mounted.size());
    }

    /** The decoded root IS the panel — the parallel object beside the tree is gone. */
    @Test
    public void theMountedRootIsAnInstanceOfThePanelClass() {
        server.open(TestPanel.TYPE, null);
        settle();

        assertTrue("the tree's root decoded as the panel class itself",
                mount.mounted.get(0).root() instanceof TestPanel);
    }

    // ── The close matrix ────────────────────────────────────────────────────

    @Test
    public void theServerClosingAWindowEndsBothSides() {
        ServerWindow<TestPanel> window = server.open(TestPanel.TYPE, null);
        settle();

        server.close(window, "the block was broken");
        settle();

        assertEquals(1, window.panel().closes.size());
        assertEquals("SERVER", window.panel().closes.get(0));
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
        ServerWindow<TestPanel> window = server.open(TestPanel.TYPE, null);
        settle();

        mount.mounted.get(0).userClosed();
        settle();

        assertEquals("the server heard about it exactly once", 1, window.panel().closes.size());
        assertEquals("CLIENT", window.panel().closes.get(0));
        assertFalse(window.isOpen());
        assertEquals("the server stopped serving it", 0, server.windowCount());
        assertEquals("and did not echo the close back at a frame that has gone",
                0, mount.closedByServer.size());
        assertEquals(0, client.windowCount());
    }

    @Test
    public void aWindowThatStopsBeingValidClosesItself() {
        ServerWindow<TestPanel> window = server.open(TestPanel.TYPE, null);
        settle();

        window.panel().valid = false;
        settle();

        assertEquals(1, window.panel().closes.size());
        assertEquals("NOT_VALID", window.panel().closes.get(0));
        assertEquals("the client was told", 1, mount.closedByServer.size());
    }

    /**
     * What every mod used to write for itself as a logout handler — and what a mod that forgot left
     * behind: a session still observing its tree and encoding deltas into a dead wire.
     */
    @Test
    public void losingTheConnectionEndsEveryWindowOnBothSidesAndSendsNothing() {
        ServerWindow<TestPanel> one = server.open(TestPanel.TYPE, null);
        ServerWindow<OtherPanel> two = server.open(OtherPanel.TYPE, null);
        settle();
        int before = link[0].sent().size();

        serverSide.close("player left");

        assertEquals(1, one.panel().closes.size());
        assertEquals("CONNECTION_LOST", one.panel().closes.get(0));
        assertEquals("CONNECTION_LOST", two.panel().closes.get(0));
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
        ServerWindow<TestPanel> window = server.open(TestPanel.TYPE, null);
        settle();
        ClientWindowContext shown = mount.mounted.get(0);

        server.close(window, "first");
        server.close(window, "again");
        window.close("and again");
        // The double-close path a real host reaches routinely: a frame destroyed by a server close
        // still runs its own teardown, which calls back here.
        shown.userClosed();
        settle();

        assertEquals(1, window.panel().closes.size());
    }

    // ── Keys ────────────────────────────────────────────────────────────────
    //
    // TestPanel answers key(model) with the MODEL, which is the honest general shape anyway: a key
    // names the window's SUBJECT, and the subject is what the model is.

    @Test
    public void openingUnderAKeyThatIsAlreadyOpenBringsTheExistingWindowForward() {
        ServerWindow<TestPanel> first = server.open(TestPanel.TYPE, "test:one");
        settle();
        UIElement firstRoot = mount.mounted.get(0).root();

        ServerWindow<TestPanel> again = server.open(TestPanel.TYPE, "test:one");
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
        server.open(TestPanel.TYPE, "test:shared");
        settle();
        try {
            server.open(OtherPanel.TYPE, "test:shared");
            fail("expected the clash to be refused");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("test:shared"));
        }
    }

    @Test
    public void aWindowWithNoKeyOpensAsManyTimesAsItIsAsked() {
        server.open(TestPanel.TYPE, null);
        server.open(TestPanel.TYPE, null);
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

        server.open(TestPanel.TYPE, null);
        settle();
        assertEquals("nowhere to put it yet", 0, later.mounted.size());
        assertEquals(1, client.waitingCount());

        client.setMount(later);
        assertEquals("and it lands the moment there is", 1, later.mounted.size());
        assertEquals(0, client.waitingCount());
    }

    // ── The client half, by type ────────────────────────────────────────────

    /**
     * The client half runs with <b>no registration anywhere</b>: the open message named the panel
     * class, the engine initialised it, and the decoded root being {@code Networked} is the whole
     * opt-in — fields resolved out of the panel's own tree, {@code bound()} for the widget listeners,
     * {@code client()} handed its scope, {@code closed()} told at the end — on the CLIENT's instance,
     * which is not the server's.
     */
    @Test
    public void aPanelsClientHalfRunsWithNoRegistrationAnywhere() {
        ServerWindow<TestPanel> window = server.open(TestPanel.TYPE, null);
        settle();

        TestPanel shown = (TestPanel) mount.mounted.get(0).root();
        assertNotSame("two instances over two trees", window.panel(), shown);
        assertEquals("bound() ran at mount", 1, shown.bounds.get());
        assertNotNull("client() was handed its scope", shown.net);
        assertEquals("and neither ran on the SERVER's instance", 0, window.panel().bounds.get());
        assertNull(window.panel().net);

        server.close(window, "done");
        settle();
        assertEquals(1, shown.closes.size());
        assertEquals("done", shown.closes.get(0));
    }

    /**
     * The binding hands the panel its <b>own tree's elements</b> as fields — what used to be three
     * lines of {@code querySelector} guarded by {@code instanceof}, silently doing nothing when an id
     * moved. A press through the field reaches the server exactly as the searched version did.
     */
    @Test
    public void aBoundPanelsFieldsAreTheTreesOwnElements() {
        ServerWindow<TestPanel> window = server.open(TestPanel.TYPE, null);
        settle();

        TestPanel shown = (TestPanel) mount.mounted.get(0).root();
        assertSame("the field IS the queried element", shown.querySelector("#press"), shown.press);
        assertNotSame("bound to the CLIENT's rebuilt tree, not the server's object",
                window.panel().press, shown.press);

        shown.press.onPressed.emit();
        settle();
        assertEquals(1, window.panel().presses.get());
    }

    /**
     * A binding fails <b>loudly</b> when the tree is not the shape it expects.
     *
     * <p>The whole point of binding over searching: a declared part missing from the tree is an error
     * at mount rather than a control that looks wired and does nothing. Contained, too — the window
     * itself still mounts, because a broken binding must not take the server's UI down with it.</p>
     */
    @Test
    public void aBindingThatCannotFindItsPartsFailsAtMountRatherThanAtPressTime() {
        ServerWindow<SabotagedPanel> window = server.open(SabotagedPanel.TYPE, null);
        settle();

        assertEquals("the window is still on screen", 1, mount.mounted.size());
        assertEquals("but the binding never completed, so bound() never ran",
                0, ((SabotagedPanel) mount.mounted.get(0).root()).bounds.get());

        // A failed binding takes only the LOCAL extras with it: the description already wired the
        // reported events, so the widgets that did come through still reach the server.
        ((Button) mount.mounted.get(0).root().querySelector("#press")).onPressed.emit();
        settle();
        assertEquals(1, window.panel().presses.get());
    }

    // ── Window-scoped notifications ─────────────────────────────────────────

    /**
     * Two windows of one type, both listening for the same notification. On
     * {@code ProtocolConnection.onNotify} — where the example taught this — the second registration is
     * refused by the router and the second window <b>throws at open</b>.
     */
    @Test
    public void twoWindowsMayNameTheSameNotificationAndEachHearsOnlyItsOwn() {
        ServerWindow<NotifyingPanel> one = server.open(NotifyingPanel.TYPE, null);
        ServerWindow<NotifyingPanel> two = server.open(NotifyingPanel.TYPE, null);
        settle();

        StateMap<Object> payload = new StateMap<>(PlainOps.INSTANCE);
        payload.putString("from", "the client");
        mount.mounted.get(1).session().notify("ping", payload);
        settle();

        assertEquals("only the window it was addressed to", 0, one.panel().heard.size());
        assertEquals(1, two.panel().heard.size());
        assertEquals("the client", two.panel().heard.get(0));
    }

    // ── Nested panels ───────────────────────────────────────────────────────

    /**
     * Composition is nesting: a child panel is a field, its id is the field name, and its wire
     * methods are qualified by that id — {@code "save"} inside the child is {@code "save/save"} on
     * the wire, with nobody having written the string on either side.
     */
    @Test
    public void aNestedPanelsMethodsAreNamespacedByItsId() {
        AtomicReference<String> answered = new AtomicReference<>();
        ServerWindow<ParentPanel> window = server.open(ParentPanel.TYPE, null);
        settle();

        // The QUALIFIED name is what crosses the wire -- "save" belongs to the child's namespace,
        // not to the window's.
        mount.mounted.get(0).session().call("save/save", null,
                result -> answered.set(result.getString("by", "")), error -> answered.set("!" + error));
        settle();
        assertEquals("the child", answered.get());

        // ...and the bare name is not served, which is what makes two children unable to collide.
        mount.mounted.get(0).session().call("save", null,
                result -> answered.set("unexpected"), error -> answered.set("refused"));
        settle();
        assertEquals("refused", answered.get());
        assertEquals(1, window.panel().save.saves.get());
    }

    /** The child's server half sees its SLICE, handed down at attach — never the parent's model. */
    @Test
    public void aNestedPanelIsServedItsSliceAndTickedWithIt() {
        ServerWindow<ParentPanel> window = server.open(ParentPanel.TYPE, "the-slice");
        settle();

        assertEquals("serve() got the slice the parent handed down",
                "the-slice", window.panel().save.servedWith);
        assertTrue("and the host ticks the child with the same slice",
                window.panel().save.ticks.get() > 0);
    }

    /** A registered parent's client walk reaches the nested panel too: fields, bound(), scope. */
    @Test
    public void aNestedPanelIsBoundOnTheClientUnderItsOwnScope() {
        AtomicReference<String> answered = new AtomicReference<>();
        ServerWindow<ParentPanel> window = server.open(ParentPanel.TYPE, null);
        settle();

        SavePanel shownChild = (SavePanel) mount.mounted.get(0).root().querySelector("#save");
        assertNotNull(shownChild);
        assertEquals("the child's bound() ran", 1, shownChild.bounds.get());
        assertSame("its fields resolved out of its own subtree",
                shownChild.querySelector("#late"), shownChild.late);
        assertNotNull("client() handed it a scope", shownChild.net);

        // The child's ClientScope speaks the SAME qualified name the server registered.
        shownChild.net.call("save", null,
                result -> answered.set(result.getString("by", "")), error -> answered.set("!" + error));
        settle();
        assertEquals("the child", answered.get());
        assertEquals(1, window.panel().save.saves.get());
    }

    @Test
    public void twoChildrenUnderOneIdIsAWiringMistake() {
        try {
            server.open(DoubleAttachPanel.TYPE, null);
            fail("expected the second attach to be refused");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("save"));
        }
    }

    /**
     * The boundary a parent must not cross. It used to be a bare {@code Map.put}: the parent silently
     * won and which handler ran depended on registration order.
     */
    @Test
    public void aParentCannotOverrideAHandlerItsChildAlreadyRegistered() {
        try {
            server.open(OverridingPanel.TYPE, null);
            fail("expected the duplicate handler to be refused");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("already handled"));
        }
    }

    /**
     * The relaxation F16 needed. The old rule refused every registration after {@code open()}, which
     * would have made attaching to a live window impossible — and a tree delta re-describes the new
     * elements' reported events, so the client wires them exactly as it would at open.
     */
    @Test
    public void aChildMayBeAttachedToAWindowThatIsAlreadyOpen() {
        ServerWindow<LateChildPanel> window = server.open(LateChildPanel.TYPE, null);
        settle();
        assertNull("nothing extra yet", window.panel().child);

        window.panel().attachNow();
        settle();

        AtomicReference<String> answered = new AtomicReference<>();
        mount.mounted.get(0).session().call("child/save", null,
                result -> answered.set("ok"), error -> answered.set("!" + error));
        settle();
        assertEquals("ok", answered.get());

        // The new element came across in a tree delta, WITH its reported event, so it reports back.
        Button added = (Button) mount.mounted.get(0).root().querySelector("#late");
        assertNotNull("the delta brought the child's tree", added);
        added.onPressed.emit();
        settle();
        assertEquals(1, window.panel().child.presses.get());
    }

    // ── Visibility ──────────────────────────────────────────────────────────

    /**
     * Hiding is not closing: a hidden window is retained and detached, and the server should stop
     * describing a tree nobody is drawing. The gate is above the whole flush, so the two sides cannot
     * end up numbering the tree differently.
     */
    @Test
    public void aHiddenWindowStopsCostingTraffic() {
        ServerWindow<TestPanel> window = server.open(TestPanel.TYPE, null);
        settle();

        mount.mounted.get(0).visibilityChanged(false);
        settle();

        int quiet = link[0].sent().size();
        window.panel().label.setText("changed while nobody was looking");
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
     */
    @Test
    public void aStateChangeMadeWhileTheDescriptionIsStillInFlightIsNotLost() {
        ServerWindow<TestPanel> window = server.open(TestPanel.TYPE, null);

        // BEFORE a single message has crossed: the client has not seen ui/openWindow, let alone asked
        // for the tree behind its hash.
        window.panel().label.setText("written before the client could hear it");
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

        server.open(StyledPanel.TYPE, null);
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

        server.open(StyledPanel.TYPE, null);
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

        server.open(HalfStyledPanel.TYPE, null);
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
     */
    @Test
    public void aWindowReshapedWhileHiddenComesBackWithItsNumberingIntact() {
        ServerWindow<LateChildPanel> window = server.open(LateChildPanel.TYPE, null);
        settle();
        ClientWindowContext shown = mount.mounted.get(0);

        shown.visibilityChanged(false);
        settle();

        // THE SHAPE CHANGES while nobody is looking, and then the state does.
        window.panel().attachNow();
        window.panel().label.setText("changed while hidden");
        settle();

        assertNull("nothing was re-described", shown.root().querySelector("#late"));

        shown.visibilityChanged(true);
        settle();

        assertNotNull("the new subtree arrived", shown.root().querySelector("#late"));
        assertEquals("and the state landed on the RIGHT element, not on whatever took its number",
                "changed while hidden", textOf(shown.root().querySelector("#label")));

        // ...and the delta that brought the child carried its reported events, so the new widget
        // reports back exactly as one described at open would.
        ((Button) shown.root().querySelector("#late")).onPressed.emit();
        settle();
        assertEquals(1, window.panel().child.presses.get());
    }

    // ── The field walk ──────────────────────────────────────────────────────

    /** A widget whose constructor takes arguments keeps its initializer; the framework fills nulls. */
    @Test
    public void aFieldWithAnInitializerIsKeptAndStillNamed() {
        DeclaredPanel panel = DeclaredPanel.TYPE.build(null);
        assertEquals("press", panel.press.getId());
        assertEquals("the initializer's own label survived", "press-label", panel.press.getText());
        assertNotNull("and the null field was created for us", panel.power);
        assertEquals("power", panel.power.getId());
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private static String textOf(@Nullable UIElement element) {
        return element instanceof UIText ? ((UIText) element).getText() : null;
    }

    /**
     * The workhorse: one class, both sides, per-instance counters. Its MODEL is its key — which is
     * the honest general shape anyway, a key naming the window's subject.
     */
    public static class TestPanel extends UIElement implements Networked<String> {

        static final UiType<TestPanel, String> TYPE = UiType.of("test:panel", TestPanel::new);

        public Button press = new Button("press");
        public UIText label = new UIText("");

        final AtomicInteger presses = new AtomicInteger();
        final AtomicInteger bounds = new AtomicInteger();
        final List<String> closes = new ArrayList<>();
        boolean valid = true;

        @Nullable
        ClientScope net;

        @Override
        public void layout(String key) {
            addChild(press);
            addChild(label);
        }

        @Override
        public void serve(String key, ServerScope io) {
            io.on(press, Button.ACTIVATE, ctx -> presses.incrementAndGet());
        }

        @Override
        public boolean stillValid(String key, @Nullable Object viewer) {
            return valid;
        }

        @Override
        public String title(String key) {
            return "Test panel";
        }

        @Override
        public String key(String key) {
            return key;
        }

        @Override
        public void bound() {
            bounds.incrementAndGet();
        }

        @Override
        public void client(ClientScope io) {
            net = io;
        }

        @Override
        public void closed(String reason) {
            closes.add(reason);
        }
    }

    /** A second TYPE over the same shape, for the dispatch tests. Its own tag, its own id. */
    public static class OtherPanel extends TestPanel {
        static final UiType<OtherPanel, String> TYPE = UiType.of("test:other", OtherPanel::new);
    }

    /** The auto-creation case: a null field the framework instantiates and names. */
    public static class DeclaredPanel extends UIElement implements Networked<String> {

        static final UiType<DeclaredPanel, String> TYPE = UiType.of("test:declared", DeclaredPanel::new);

        public Switch power;                                  // created and named for us
        public Button press = new Button("press-label");      // ctor argument, so we write it

        @Override
        public void layout(String model) {
            addChild(power);
            addChild(press);
        }
    }

    /** A declared part its layout forgot to add — the shape a binding must refuse loudly. */
    public static class SabotagedPanel extends UIElement implements Networked<String> {

        static final UiType<SabotagedPanel, String> TYPE = UiType.of("test:sabotaged", SabotagedPanel::new);

        public Button press = new Button("press");
        public Button orphan = new Button("orphan");   // declared, never added to the tree

        final AtomicInteger bounds = new AtomicInteger();
        final AtomicInteger presses = new AtomicInteger();

        @Override
        public void layout(String model) {
            addChild(press);   // orphan forgotten
        }

        @Override
        public void serve(String model, ServerScope io) {
            io.on(press, Button.ACTIVATE, ctx -> presses.incrementAndGet());
        }

        @Override
        public void bound() {
            bounds.incrementAndGet();
        }
    }

    public static class NotifyingPanel extends UIElement implements Networked<String> {

        static final UiType<NotifyingPanel, String> TYPE = UiType.of("test:notifying", NotifyingPanel::new);

        public Button press = new Button("press");

        final List<String> heard = new ArrayList<>();

        @Override
        public void layout(String model) {
            addChild(press);
        }

        @Override
        public void serve(String model, ServerScope io) {
            io.onNotify("ping", payload -> heard.add(payload.getString("from", "?")));
        }
    }

    /**
     * A nested panel: its own subtree, its own handlers, its own namespace — attached as a field of
     * whoever holds it. No {@code UiType} of its own needed for the field case: the parent's
     * registration registers its tag.
     */
    public static class SavePanel extends UIElement implements Networked<String> {

        public Button late = new Button("late");

        final AtomicInteger saves = new AtomicInteger();
        final AtomicInteger presses = new AtomicInteger();
        final AtomicInteger ticks = new AtomicInteger();
        final AtomicInteger bounds = new AtomicInteger();

        @Nullable
        String servedWith;

        @Nullable
        ClientScope net;

        @Override
        public void layout(String slice) {
            addChild(late);
        }

        @Override
        public void serve(String slice, ServerScope io) {
            servedWith = slice;
            io.onCall("save", (args, respond) -> {
                saves.incrementAndGet();
                StateMap<Object> out = io.newMap();
                out.putString("by", "the child");
                respond.ok(out);
            });
            io.on(late, Button.ACTIVATE, ctx -> presses.incrementAndGet());
        }

        @Override
        public void tick(String slice) {
            ticks.incrementAndGet();
        }

        @Override
        public void bound() {
            bounds.incrementAndGet();
        }

        @Override
        public void client(ClientScope io) {
            net = io;
        }
    }

    /** A parent with a nested panel. The child is BUILT in layout, with the slice only it knows. */
    public static class ParentPanel extends UIElement implements Networked<String> {

        static final UiType<ParentPanel, String> TYPE = UiType.of("test:parent", ParentPanel::new);
        static final UiType<SavePanel, String> CHILD = UiType.of("test:save", SavePanel::new);

        public Button press = new Button("press");
        public SavePanel save;

        @Override
        public void layout(String model) {
            addChild(press);
            save = CHILD.build(sliceOf(model));
            addChild(save);   // the field name becomes its id, after layout, by the same rule
        }

        @Override
        public void serve(String model, ServerScope io) {
            io.attach(save, sliceOf(model));
        }

        private static String sliceOf(String model) {
            return model == null ? null : model;   // the whole model IS the slice, in this fixture
        }
    }

    /** Attaches the same child id twice — the collision the scope set exists to refuse. */
    public static class DoubleAttachPanel extends UIElement implements Networked<String> {

        static final UiType<DoubleAttachPanel, String> TYPE =
                UiType.of("test:double", DoubleAttachPanel::new);

        public SavePanel save;

        @Override
        public void layout(String model) {
            save = ParentPanel.CHILD.build(model);
            addChild(save);
        }

        @Override
        public void serve(String model, ServerScope io) {
            io.attach(save, model);
            io.attach(save, model);
        }
    }

    /** A parent reaching into a child's element — the boundary that used to be silently crossed. */
    public static class OverridingPanel extends UIElement implements Networked<String> {

        static final UiType<OverridingPanel, String> TYPE =
                UiType.of("test:overriding", OverridingPanel::new);

        public SavePanel save;

        @Override
        public void layout(String model) {
            save = ParentPanel.CHILD.build(model);
            addChild(save);
        }

        @Override
        public void serve(String model, ServerScope io) {
            io.attach(save, model);
            io.on(save.late, Button.ACTIVATE, ctx -> { });
        }
    }

    /** A child that arrives AFTER the window opened — dynamic content, named by hand. */
    public static class LateChildPanel extends UIElement implements Networked<String> {

        static final UiType<LateChildPanel, String> TYPE =
                UiType.of("test:late-parent", LateChildPanel::new);

        public Button press = new Button("press");
        public UIText label = new UIText("");

        /** Not a declared part: null until {@link #attachNow}, so the client binding never asks for it. */
        @Nullable
        SavePanel child;

        @Nullable
        private ServerScope scope;

        @Override
        public void layout(String model) {
            addChild(press);
            addChild(label);
        }

        @Override
        public void serve(String model, ServerScope io) {
            this.scope = io;
        }

        void attachNow() {
            if (scope == null) throw new IllegalStateException("not served yet");
            child = ParentPanel.CHILD.build("late-slice");
            child.setId("child");   // dynamic content names itself; a field would have been named for it
            addChild(child);
            scope.attach(child, "late-slice");
        }
    }

    /** Two sheets, both offered with their text — what a server-authored theme looks like. */
    public static class StyledPanel extends TestPanel {

        static final UiType<StyledPanel, String> TYPE = UiType.of("test:styled", StyledPanel::new);

        @Override
        public void serve(String key, ServerScope io) {
            io.sheet(SheetRef.ofResource("test:a", "hash-a"), ".a { color: #111111; }");
            io.sheet(SheetRef.ofResource("test:b", "hash-b"), ".b { color: #222222; }");
        }
    }

    /** One sheet offered with its text, one NAMED only — a theme the client is expected to ship. */
    public static class HalfStyledPanel extends TestPanel {

        static final UiType<HalfStyledPanel, String> TYPE =
                UiType.of("test:half-styled", HalfStyledPanel::new);

        @Override
        public void serve(String key, ServerScope io) {
            io.sheet(SheetRef.ofResource("test:a", "hash-a"), ".a { color: #111111; }");
            io.sheet(SheetRef.ofResource("test:missing", "hash-missing"));
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
