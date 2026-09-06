package com.crystalgui.headless;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.crystalgui.ui.dom.UIElement;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.crystalgui.app.machine.ui.EnginePanel;
import com.crystalgui.app.machine.ui.MachinePanel;
import com.crystalgui.app.machine.MachineModel;
import com.crystalgui.net.window.ClientWindows;
import com.crystalgui.net.window.ClientWindowContext;
import com.crystalgui.net.window.ServerWindow;
import com.crystalgui.net.window.ServerWindows;
import com.crystalgui.net.window.WindowProtocol;
import com.crystalgui.net.window.WindowMount;
import com.crystalgui.app.machine.ui.MachineStyles;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.net.protocol.UiMethods;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.display.ProgressBar;
import com.crystalgui.widget.control.Switch;

/**
 * The worked example in {@code com.crystalgui.example}, run end to end.
 *
 * <h3>Why an example has a test</h3>
 *
 * <p>Because compiling is not the same as working, and this example's job is to be believed. A
 * signature change breaks the build and gets noticed; a behavioural one leaves six classes that
 * compile, read plausibly, and teach something that is no longer true — which is worse than having
 * no example, since a reader has no way to tell.</p>
 *
 * <p>It also runs here rather than in {@code src/test/} on purpose. This source set has
 * CrystalGraphics deliberately off the classpath, so the example passing <em>is</em> the assertion
 * that nothing in it reaches a font, a texture or {@code CgIO} — the property the whole
 * server-side story rests on, and one no amount of javadoc can establish.</p>
 *
 * <h3>The two loops</h3>
 *
 * <p>Both directions are asserted separately because they fail separately, and each looks like the
 * other from a screenshot. A client-to-server break is a control that does nothing; a
 * server-to-client break is a readout that freezes at whatever it showed when the window opened.
 * The second is what {@code ProgressBar.setFraction} did until this example was written: it had
 * {@code writeState} and never called {@code notifyStateChanged}, so the value travelled in the
 * opening description and never again.</p>
 */
public class MachineExampleTest {

    /** Server end, client end, and the two transports between them. */
    private static final class Loopback {
        final InMemoryTransport<Object>[] link;
        final ProtocolConnection<Object> serverEnd;
        final ProtocolConnection<Object> clientEnd;

        /** World state. Ticked by {@link #tickWorld}, never by the window. */
        final MachineModel machine = new MachineModel();

        /** The window the host serves, and the panel behind it — the SERVER's instance. */
        ServerWindow<MachinePanel> server;
        MachinePanel serverPanel;

        /**
         * The CLIENT's panel: the same class bound to the rebuilt tree, so its fields are the very
         * widgets the framework's own bound panel wired. A different object over a different tree,
         * which is the whole architecture in one line.
         */
        MachinePanel client;

        Loopback() {
            // The contributor is what puts a ServerWindows on one end and a ClientWindows on the other,
            // decided by whether the connection names a peer. Reset first, because a suite shares
            // statics and Protocols refuses a duplicate contributor outright.
            Protocols.resetForTesting();
            WindowProtocol.resetForTesting();
            WindowProtocol.register();

            link = InMemoryTransport.pair();
            serverEnd = Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "player");
            clientEnd = Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);

            ClientWindows.of(clientEnd).setMount(new SilentMount());
        }

        Loopback open() {
            server = ServerWindows.of(serverEnd).open(MachinePanel.TYPE, machine);
            serverPanel = server.panel();
            settle(6);
            client = (MachinePanel) shown().root();
            return this;
        }

        /** Moves everything both ways until the conversation runs out of things to say. */
        void settle(int rounds) {
            for (int i = 0; i < rounds; i++) {
                link[0].deliver();
                link[1].deliver();
                serverEnd.tick();
                clientEnd.tick();
            }
        }

        /** A world tick plus the delivery it produces. */
        void tickWorld(int times) {
            for (int i = 0; i < times; i++) {
                /*
                 * THREE STEPS THAT USED TO BE ONE CALL, and the split is the point of the rewrite.
                 *
                 * The model advances (world state, owned by nobody's window). The connection ticks,
                 * which is where the host runs window.tick() -- mirroring the model into widgets --
                 * and then flushes whatever that dirtied. Only then is there anything to deliver.
                 */
                machine.tick();
                serverEnd.tick();
                settle(1);
            }
        }

        /** The window on screen, for the tests that drive a close from the client's side. */
        ClientWindowContext shown() {
            return ClientWindows.of(clientEnd).windows().get(0);
        }

        /**
         * Says which rows of a streamed collection this client is looking at.
         *
         * <p>What {@code RemoteRows} sends from a scroll handler, written out here because the panel's
         * client half is a list this test drives rather than a view with a viewport.</p>
         */
        void showingRows(UIElement streamed, int from, int to) {
            StateMap<Object> args = new StateMap<>(PlainOps.INSTANCE);
            args.putInt(UiMethods.WINDOW, server.session().windowId());
            args.putInt("nid", server.session().idOf(streamed));
            args.putInt("from", from);
            args.putInt("to", to);
            clientEnd.call(UiMethods.ROWS, args, null, null);
            settle(8);
        }
    }

    /** A mount that draws nothing. What a platform implements, minus the platform. */
    private static final class SilentMount implements WindowMount {
        @Override
        public MountedWindow mount(ClientWindowContext context) {
            return new MountedWindow() {
                @Override
                public void closedByServer(String reason) {
                }

                @Override
                public void focus() {
                }

                @Override
                public void contentReplaced(UIElement newRoot) {
                }
            };
        }
    }

    // ── The handshake ───────────────────────────────────────────────────────

    @Test
    public void theClientRebuildsTheServersTree() {
        Loopback net = new Loopback().open();

        UIElement root = net.client;
        assertNotNull("the client never received a window", root);

        // Element COUNT, not a spot check. The two sides derive network ids from a document-order
        // walk and send none, so a structural disagreement of any size mis-addresses every element
        // after it -- which is why ClientUiSession refuses a tree whose count does not match.
        assertEquals("the rebuilt tree is a different shape from the described one",
                countElements(net.serverPanel), countElements(root));

        assertNotNull("the panel's switch did not survive the round trip", root.querySelector("#power"));
        assertNotNull(root.querySelector("#throughput"));
        assertNotNull(root.querySelector("#progress"));
    }

    @Test
    public void theThemeIsNamedRatherThanSent() {
        Loopback net = new Loopback().open();

        assertEquals(1, net.shown().sheets().size());
        assertEquals("the sheet's identity is its hash", MachineStyles.SHEET.hash(),
                net.shown().sheets().get(0).hash());
        assertTrue("the engine's own sheet has to go underneath, or nothing is styled",
                net.shown().useUserAgentSheet());
    }

    @Test
    public void reopeningTransfersNothing() {
        Loopback net = new Loopback().open();
        assertEquals("the description should be cached against its hash",
                1, net.shown().session().cacheSize());
        assertTrue(net.shown().session().hasCached(net.server.session().descHash()));
    }

    // ── Client to server: an interaction reaches the model ──────────────────

    @Test
    public void flippingTheClientsSwitchStartsTheServersMachine() {
        Loopback net = new Loopback().open();

        Switch power = net.client.power;
        power.setChecked(true);
        net.settle(2);

        assertTrue("the server's own handler never ran", net.machine.isRunning());
    }

    // ── Server to client: a model change reaches the widget ─────────────────

    /**
     * The regression this example was written by, and the one worth keeping.
     *
     * <p>It asserts a value that <b>moved after the window opened</b>, which is the only shape that
     * can see the defect: the opening description carried the correct fraction, so any assertion
     * taken at open time passes against a widget that will never update again.</p>
     */
    @Test
    public void theServersProgressReachesTheClientsBarAfterOpening() {
        Loopback net = new Loopback().open();

        ProgressBar bar = net.client.progress;
        assertEquals("nothing has run yet", 0f, bar.fraction(), 1e-4);

        net.machine.setRunning(true);
        net.tickWorld(10);

        assertTrue("the server did not advance", net.machine.progress() > 0f);
        assertEquals("the bar froze at the value it opened with",
                net.machine.progress(), bar.fraction(), 1e-4);
    }

    // ── The other two contract shapes ───────────────────────────────────────

    /**
     * A <b>notification</b> arrives, and nothing is sent back.
     *
     * <p>Asserted through an effect rather than through the wire, because that is the only thing a
     * notification leaves behind: there is no response to inspect. If this ever regresses it will be
     * because the handler was registered on the SESSION instead of the CONNECTION — the session has
     * no {@code onNotify}, so that version does not compile, which is the good kind of failure.</p>
     */
    @Test
    public void aNotificationReachesTheServerWithNothingComingBack() {
        Loopback net = new Loopback().open();

        net.client.heartbeat.onPressed.emit();
        net.settle(1);      // the notification crosses and the server's handler runs

        /*
         * AND THEN A WORLD TICK, WHICH IS NOT A FORMALITY. The handler above wrote into a widget,
         * which marked it dirty on the session -- and a dirty set is only ever flushed by
         * ServerUiSession.tick(). settle() pumps the CONNECTION, which is a different thing.
         *
         * The first version of this test omitted it and failed, with the server's handler having run
         * perfectly. That is the exact production failure mode of forgetting the server tick: a live
         * session that answers calls and never sends another state update.
         */
        net.tickWorld(1);

        // The server writes the count into its own readout, which travels back as an ordinary state
        // delta -- so seeing it on the CLIENT proves both halves of the round trip.
        assertTrue("the server never saw the heartbeat, or never flushed it",
                wireText(net).contains("heartbeat #1"));
    }

    /**
     * A <b>refused</b> request runs the error callback, not the result one.
     *
     * <p>The half a happy-path example never shows, and the reason a request takes two lambdas.
     * {@code respond.fail} is an ordinary answer that happens to say no — same envelope kind as a
     * success — so a caller must be able to tell "refused" from "never came back". Only one of those
     * is worth retrying.</p>
     */
    @Test
    public void aRefusedRequestRunsTheErrorCallbackWithACode() {
        Loopback net = new Loopback().open();

        String[] result = { null };
        String[] failure = { null };
        net.shown().session().call("machine/rename",
                new StateMap<Object>(PlainOps.INSTANCE).putString("name", "   "),
                ok -> result[0] = "accepted",
                error -> failure[0] = error);
        net.settle(2);

        assertNull("a refusal must not run the result callback", result[0]);
        // The CODE, not prose. A client branches on a value; it cannot branch on a message, and
        // matching message text is a coupling nobody remembers making.
        assertEquals("EMPTY_NAME", failure[0]);
    }

    /** And the same call succeeds when the argument is good — the positive control. */
    @Test
    public void thatSameCallSucceedsWithAUsableName() {
        Loopback net = new Loopback().open();

        String[] failure = { null };
        boolean[] accepted = { false };
        net.shown().session().call("machine/rename",
                new StateMap<Object>(PlainOps.INSTANCE).putString("name", "Furnace"),
                ok -> accepted[0] = true,
                error -> failure[0] = error);
        net.settle(2);

        assertNull(failure[0]);
        assertTrue("the result callback never ran", accepted[0]);
        assertEquals("and the model was actually renamed", "Furnace", net.machine.label());
    }

    /**
     * <b>Each side's line is written only by that side</b>, and the two say different things about
     * one exchange.
     *
     * <p>This is the assertion that would have caught the readout bug the two-line layout replaced.
     * There was one line with a badge naming its last author, and pressing Announce produced the
     * CLIENT badge above the SERVER's sentence — because {@code Property.set} returns early on an
     * equal value, so the server rewriting "SERVER" over "SERVER" marked nothing dirty and never
     * entered the delta, while the text beside it did.</p>
     *
     * <p>The rule it taught is worth more than the fix: <b>a state delta carries what CHANGED on the
     * server, never what DIFFERS between the two sides.</b> A client that writes into a server-owned
     * widget has desynchronised it, and the server cannot put it right, because from where the server
     * is standing nothing happened.</p>
     */
    @Test
    public void eachSideWritesItsOwnResultLineAndOnlyItsOwn() {
        Loopback net = new Loopback().open();

        net.client.heartbeat.onPressed.emit();     // the client says "sent", locally and immediately
        net.settle(1);
        net.tickWorld(1);               // the server says "received", and flushes it

        assertTrue("the client's line should say it SENT one, got: " + clientLine(net),
                clientLine(net).contains("NOTIFY sent"));
        assertTrue("the server's line should say it RECEIVED one, got: " + serverLine(net),
                serverLine(net).contains("NOTIFY received"));
        assertTrue(serverLine(net).contains("heartbeat #1"));
    }

    /**
     * And the server's own line survives a client-side write, because the client never touches it.
     *
     * <p>The negative control for the row above: if the two lines were ever collapsed back into one,
     * the client's write would land on the server's sentence and this fails.</p>
     */
    @Test
    public void aClientWriteDoesNotDisturbTheServersLine() {
        Loopback net = new Loopback().open();

        net.client.heartbeat.onPressed.emit();
        net.settle(1);
        net.tickWorld(1);
        String afterServerWrote = serverLine(net);

        net.client.askStats.onPressed.emit();      // a purely client-side write, no round trip completed yet
        assertEquals("the client must not be able to overwrite the server's line",
                afterServerWrote, serverLine(net));
    }

    /*
     * READ THROUGH THE PANEL, NOT THROUGH A SELECTOR.
     *
     * These were `textOf(net, "#result-client")` until the panel rewrite made a widget's FIELD NAME
     * its id -- at which point every one of these strings was silently wrong. `querySelector` answers
     * null for a miss and the helper turned that into "", so two tests failed on an empty readout with
     * nothing pointing at the selector. Going through the bound panel is the same read with the
     * compiler holding the name.
     */
    private static String serverLine(Loopback net) {
        return net.client.serverLine.getText();
    }

    private static String clientLine(Loopback net) {
        return net.client.clientLine.getText();
    }


    /** Reads the panel's protocol readout out of the CLIENT's tree. */
    private static String wireText(Loopback net) {
        return serverLine(net);
    }

    // ── Composition: a UI inside a UI ───────────────────────────────────────

    /*
     * MachinePanel holds an EnginePanel as an ordinary field. These assert the four things that
     * separate "it compiles" from "it works", and each of them fails in a way that reads like a
     * different feature being broken:
     *
     *   the child DECODES        -- or the client shows a bare <element> where a panel should be
     *   the child gets its SLICE -- or a child quietly holds the whole model
     *   the PREFIX agrees        -- or both halves are correct and the method never routes
     *   the child TICKS          -- or its readouts freeze at whatever they opened with
     */

    /**
     * The nested panel survives the round trip as <b>a panel</b>, not as a bare element.
     *
     * <p>Nothing on the client registered {@code EnginePanel}: {@code MachinePanel.TYPE}'s own
     * declaration walks its fields and registers the tag of every nested panel it finds, which is what
     * lets a description saying {@code <enginepanel>} decode into the class. Without that the tree
     * would still rebuild — as a {@link UIElement} — and every field on it would be null.</p>
     */
    @Test
    public void theClientRebuildsTheNestedPanelToo() {
        Loopback net = new Loopback().open();

        EnginePanel child = net.client.engine;
        assertNotNull("the nested panel did not survive the round trip", child);
        assertNotSame("the client must hold its OWN instance over its own tree",
                net.serverPanel.engine, child);

        // Bound out of the child's own subtree, by field name and type -- the same walk the root got.
        assertNotNull("the child's fields were never bound", child.load);
        assertSame("the bound field must BE the widget in the rebuilt tree",
                child.querySelector("#load"), child.load);
    }

    /**
     * The child is served {@code model.engine()} and <b>could not name the machine if it wanted to</b>.
     *
     * <p>Asserted from the outside, which is the only place it is visible: the child's slider reaches
     * the engine, and nothing else on the machine moves. The compiler is what actually enforces this —
     * every hook on {@code EnginePanel} takes an {@code EngineModel} — so what this test protects is
     * the wiring, {@code io.attach(engine, model.engine())}, where passing {@code model} instead would
     * compile on the day somebody widens the child's type parameter to make it "simpler".</p>
     */
    @Test
    public void theChildIsServedItsSliceAndNothingWider() {
        Loopback net = new Loopback().open();

        net.client.engine.load.setValue(0.9f);
        net.settle(2);

        assertEquals("the child's own handler never ran",
                0.9f, net.machine.engine().load(), 1e-4);
        assertEquals("the child reached past its slice",
                0.5f, net.machine.throughput(), 1e-4);
    }

    /**
     * <b>Both sides spell the method the same way, and neither one wrote the prefix.</b>
     *
     * <p>The child's source says {@code "tune"} once on each side. It is {@code engine/tune} on the
     * wire because the panel is the parent's field named {@code engine}, so its element id is
     * {@code engine}, so both scopes derive the same prefix from the tree the description already
     * synchronizes. That is the whole reason the prefix is an id path rather than a string somebody
     * declares: there is nothing to keep in step.</p>
     *
     * <p>Both halves are asserted because either alone passes against a broken build — a name that is
     * printed but does not route, or a call that routes to a name nobody can see.</p>
     */
    @Test
    public void bothSidesDeriveTheSameWireNameAndNeitherTypedIt() {
        Loopback net = new Loopback().open();

        assertEquals("the SERVER's scope did not qualify the child's method",
                "engine/tune", net.serverPanel.engine.serverWire.getText());
        assertEquals("the CLIENT derived a different prefix from the same tree",
                net.serverPanel.engine.serverWire.getText(), net.client.engine.clientWire.getText());

        // ...and it is not merely printed: the call has to arrive at the child's handler.
        net.client.engine.tune.onPressed.emit();
        net.settle(2);

        assertEquals("engine/tune never reached the child", 1f, net.machine.engine().load(), 1e-4);
        assertTrue("the answer never came back: " + net.client.engine.result.getText(),
                net.client.engine.result.getText().contains("REQUEST answered"));
    }

    /**
     * The child ticks with the window, and its writes flush through the one session.
     *
     * <p>Same shape as the progress-bar regression above and for the same reason: a value that
     * <b>moved after the window opened</b> is the only thing that can tell a live child from one whose
     * opening description happened to be right. A child that was attached but never ticked shows
     * correct numbers at open and freezes.</p>
     */
    @Test
    public void theChildTicksWithTheWindowItIsIn() {
        Loopback net = new Loopback().open();
        assertEquals("nothing has run yet", 0f, net.client.engine.heat.fraction(), 1e-4);

        net.machine.setRunning(true);
        net.tickWorld(10);

        assertTrue("the engine never heated", net.machine.engine().temperature() > 0f);
        assertEquals("the child's bar froze at what it opened with",
                net.machine.engine().temperature(), net.client.engine.heat.fraction(), 1e-4);
    }

    /**
     * The parent hears the child through <b>a plain Java callback</b>, and the child's own
     * element-keyed handler is what raises it.
     *
     * <p>Both server halves are objects in one process on one thread, so this direction is a method
     * call. Making it a session message would be a round trip to the room you are standing in — and
     * would invent a wire contract for something no client ever sees.</p>
     */
    @Test
    public void theParentHearsTheChildWithoutAMessage() {
        Loopback net = new Loopback().open();

        net.client.engine.restart.onPressed.emit();   // a SERVER-wired button, on the CHILD
        net.settle(1);
        net.tickWorld(1);                             // the parent's write flushes

        assertTrue("the parent never heard the child: " + serverLine(net),
                serverLine(net).contains("engine panel restarted"));
    }

    /**
     * Opening the section is <b>view state</b> and never reaches the server.
     *
     * <p>The same line this codebase draws everywhere else — document state goes through an edit, view
     * state is mutated directly — applied to a disclosure toggle. Sending it would make the server the
     * authority on something it cannot have an opinion about, and two players sharing one machine
     * would fold each other's panels.</p>
     *
     * <p>The negative half is the load-bearing one: a version that wrote the class on the SERVER would
     * pass every "the section opened" assertion and be wrong.</p>
     */
    @Test
    public void openingTheEngineSectionNeverReachesTheServer() {
        Loopback net = new Loopback().open();
        assertFalse("the section must start closed",
                net.client.engine.hasClass(MachineStyles.ENGINE_OPEN_CLASS));

        net.client.showEngine.onPressed.emit();
        net.settle(2);
        net.tickWorld(1);

        assertTrue("the client's own section never opened",
                net.client.engine.hasClass(MachineStyles.ENGINE_OPEN_CLASS));
        assertFalse("view state must not cross the wire",
                net.serverPanel.engine.hasClass(MachineStyles.ENGINE_OPEN_CLASS));
        assertEquals("the button should say what it will do next",
                "Hide engine", net.client.showEngine.getText());
    }

    /**
     * The domain half: an engine that trips stops the machine, and the child's readout says so.
     *
     * <p>Here because it is the only assertion that the slice is a real part of the model rather than
     * an object invented for the UI to have something to nest. The machine stopping is
     * {@link MachineModel}'s own decision, made in its tick; the panel finds out the way it finds out
     * about everything else.</p>
     */
    @Test
    public void aStalledEngineStopsTheMachineAndTheChildSaysSo() {
        Loopback net = new Loopback().open();
        net.machine.setRunning(true);
        net.machine.engine().setLoad(1f);

        for (int i = 0; i < 200 && !net.machine.engine().isStalled(); i++) net.tickWorld(1);
        net.tickWorld(1);   // and one more, so the readout it just wrote is delivered

        assertTrue("the engine never tripped", net.machine.engine().isStalled());
        assertFalse("a stalled engine must stop the machine", net.machine.isRunning());
        assertTrue("the child never reported it: " + net.client.engine.reading.getText(),
                net.client.engine.reading.getText().contains("STALLED"));
    }

    /**
     * <b>An idle window sends nothing, however often it is ticked.</b>
     *
     * <p>The property every "just mirror the model each tick" panel in this codebase is written
     * against, stated as a rule in {@code MachinePanel.mirror}: an unchanged value writes no candidate
     * and marks nothing dirty, so mirroring more often than necessary costs comparisons rather than
     * traffic.</p>
     *
     * <p>It was <b>false</b>, and this test is here because the false half was invisible.
     * {@code ProgressBar.setFraction} called {@code notifyStateChanged()} unconditionally, so a panel
     * that mirrored a bar every tick sent a {@code ui/stateDelta} per tick describing a value nobody
     * had moved. Every existing panel hid it behind a dirty flag of its own; {@code EnginePanel}, which
     * mirrors unconditionally on exactly the grounds quoted above, is what exposed it — as four state
     * deltas in the demo transcript, not as a failure anywhere.</p>
     *
     * <p>Asserted on <b>traffic</b> rather than on state, which is the only place it is visible: every
     * one of those deltas carried the correct value, so nothing about the window was ever wrong.</p>
     */
    @Test
    public void anIdleWindowSendsNothingHoweverOftenItTicks() {
        Loopback net = new Loopback().open();
        net.link[0].clearSent();

        net.tickWorld(5);   // the machine is stopped, so nothing about it moves

        assertEquals("an idle window put traffic on the wire", List.of(), methodsSent(net));
    }

    /** What the SERVER put on the wire since the last clear. A {@code PlainOps} envelope is a map. */
    private static List<String> methodsSent(Loopback net) {
        List<String> methods = new ArrayList<>();
        for (Object raw : net.link[0].sent()) {
            if (raw instanceof Map<?, ?> envelope && envelope.get("m") != null) {
                methods.add(String.valueOf(envelope.get("m")));
            }
        }
        return methods;
    }

    // ── The rule that is easiest to break ───────────────────────────────────

    /**
     * Registering behaviour after {@code open()} must throw.
     *
     * <p>The set of reported events is part of the description the client has already been rebuilt
     * from, so a handler added afterwards waits for an event no client will ever send. Allowing it
     * would produce a control that is wired, looks wired, and silently does nothing — the failure
     * this whole layer is least able to report.</p>
     */
    @Test
    public void behaviourCannotBeAddedOnceTheDescriptionHasGone() {
        Loopback net = new Loopback().open();
        try {
            net.server.session().on(net.serverPanel.purge, Button.ACTIVATE, ctx -> { });
            fail("expected a refusal");
        } catch (IllegalStateException expected) {
            assertTrue("the message should say why, not merely that",
                    expected.getMessage().contains("open()"));
        }
    }

    // ── The three collections ────────────────────────────────────────────────

    /**
     * <b>An inventory is streamed: a window of it exists, not all of it.</b>
     *
     * <p>Two hundred slots and a screenful described. The number is not the point — the point is that
     * it does not depend on the number, which is what makes the same code right for a chest and for a
     * warehouse.</p>
     */
    @Test
    public void theInventoryShipsAWindowRatherThanEveryslot() {
        Loopback net = new Loopback().open();
        assertEquals(200, net.machine.slotCount());
        assertEquals("nobody has said what they are looking at yet",
                0, net.client.streams.inventory.describedChildren().size());

        net.showingRows(net.serverPanel.streams.inventory, 0, 12);

        int described = net.client.streams.inventory.describedChildren().size();
        assertTrue("a window, not two hundred rows: " + described, described < 40);
        assertTrue("...and the rows are there", described >= 12);
    }

    /**
     * <b>A button in a streamed row reports like any other button.</b>
     *
     * <p>The difference between this and a display list, and the reason the rows go through the mirror
     * rather than beside it: a row is an ordinary described subtree, so everything that works on a
     * described widget works on one without anything being said about streams.</p>
     */
    @Test
    public void aTakeButtonInAStreamedRowReportsLikeAnyOther() {
        Loopback net = new Loopback().open();
        net.showingRows(net.serverPanel.streams.inventory, 0, 12);
        int before = net.machine.logSize();
        assertTrue("slot 3 has something in it to take", net.machine.slots(3, 4).get(0).count() > 0);

        // The CLIENT's button, in the row the client decoded -- not the server's.
        UIElement row = net.client.streams.inventory.describedChildren().get(3);
        ((Button) row.children().get(1)).onPressed.emit();
        net.settle(6);

        assertEquals("the server emptied the slot the row was showing",
                0, net.machine.slots(3, 4).get(0).count());
        assertEquals("...and wrote a line about it", before + 1, net.machine.logSize());
    }

    /**
     * <b>The workspace column is read through the fs protocol, not described.</b>
     *
     * <p>This connection carries no workspace at all, so the column says so — and the point is what it
     * cost the mirror to say it: nothing. The rows are local, so the server has no idea the column
     * exists.</p>
     */
    @Test
    public void theWorkspaceColumnNeverTouchesTheMirror() {
        Loopback net = new Loopback().open();

        assertEquals("nothing about the file list was described",
                0, net.serverPanel.streams.files.describedChildren().size());
        assertEquals(0, net.client.streams.files.describedChildren().size());
        assertTrue("...and the client built its own rows regardless",
                net.client.streams.files.children().size() > 0);
        for (UIElement row : net.client.streams.files.children()) {
            assertTrue("every one of them is the viewer's own", row.isLocal());
        }
    }

    /**
     * How many DESCRIBED elements a tree holds.
     *
     * <p>{@code describedChildren()}, never {@code children()}: since 7.2 a panel's {@code client(io)}
     * may add controls of the viewer's own — the workspace column's rows are exactly that — and those
     * are the one thing the two sides are meant to differ by. Counting the light tree would compare a
     * server's tree against a client's tree plus whatever the viewer added, which is the comparison the
     * integrity check itself deliberately does not make.</p>
     */
    private static int countElements(UIElement element) {
        int total = 1;
        for (UIElement child : element.describedChildren()) total += countElements(child);
        return total;
    }
}
