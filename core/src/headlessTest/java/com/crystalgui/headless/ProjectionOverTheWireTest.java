package com.crystalgui.headless;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.crystalgui.example.machine.MachineModel;
import com.crystalgui.example.machine.ui.MachinePanel;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.net.window.ClientWindowContext;
import com.crystalgui.net.window.ClientWindows;
import com.crystalgui.net.window.ServerWindow;
import com.crystalgui.net.window.ServerWindows;
import com.crystalgui.net.window.WindowMount;
import com.crystalgui.net.window.WindowProtocol;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.ui.UIElement;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Projections through a real window — the half {@link ProjectionTest} cannot see.
 *
 * <p>The unit tests prove a projection writes a widget. These prove the three things that only matter
 * once the widget is on the far side of a wire, and each is the assertion a plausible-looking
 * implementation fails:</p>
 *
 * <ul>
 *   <li>the FIRST description already carries the model, rather than being corrected a tick later;</li>
 *   <li>a quiet tick sends <b>nothing</b> — asserted on traffic, never on state;</li>
 *   <li>a window nobody is watching does not evaluate its projections at all.</li>
 * </ul>
 */
public class ProjectionOverTheWireTest {

    private InMemoryTransport<Object>[] link;
    private ProtocolConnection<Object> serverEnd;
    private ProtocolConnection<Object> clientEnd;
    private final MachineModel machine = new MachineModel();
    private ServerWindow<MachinePanel> window;

    @Before
    public void setUp() {
        Protocols.resetForTesting();
        WindowProtocol.resetForTesting();
        WindowProtocol.register();

        link = InMemoryTransport.pair();
        serverEnd = Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "player");
        clientEnd = Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);
        ClientWindows.of(clientEnd).setMount(new SilentMount());
    }

    @After
    public void tearDown() {
        Protocols.resetForTesting();
        WindowProtocol.resetForTesting();
    }

    private void settle(int rounds) {
        for (int i = 0; i < rounds; i++) {
            link[0].deliver();
            link[1].deliver();
            serverEnd.tick();
            clientEnd.tick();
        }
    }

    private MachinePanel clientPanel() {
        ClientWindowContext shown = ClientWindows.of(clientEnd).windows().get(0);
        return (MachinePanel) shown.root();
    }

    /**
     * <b>The opening tree already carries the model.</b>
     *
     * <p>Projections are seeded before {@code open()} encodes the description. Without that the first
     * tree is whatever the panel's constructor built and every projected field arrives one state delta
     * later — a window that opens visibly wrong and corrects itself. It is exactly why {@code serve()}
     * used to end with a hand-written {@code mirror(model)}, and moving that into the engine is what
     * let the call be deleted rather than renamed.</p>
     */
    @Test
    public void theOpeningDescriptionAlreadyCarriesTheModel() {
        machine.setLabel("Furnace 12");
        machine.setThroughput(0.75f);
        machine.setRunning(true);

        window = ServerWindows.of(serverEnd).open(MachinePanel.TYPE, machine);
        settle(6);

        MachinePanel client = clientPanel();
        assertEquals("Furnace 12", client.label.getText());
        assertEquals(0.75f, client.throughput.getValue(), 1e-6f);
        assertTrue("the switch must arrive already on", client.power.isChecked());
    }

    /** A model change reaches every viewer through the ordinary tick, with no mirror() anywhere. */
    @Test
    public void aModelChangeReachesTheClient() {
        window = ServerWindows.of(serverEnd).open(MachinePanel.TYPE, machine);
        settle(6);
        String opening = machine.label();
        assertEquals(opening, clientPanel().label.getText());

        machine.setLabel("renamed");
        settle(4);

        assertEquals("renamed", clientPanel().label.getText());
    }

    /**
     * <b>A quiet tick sends nothing.</b>
     *
     * <p>Asserted on TRAFFIC, and that is not a stylistic preference: `ProgressBar.setFraction` once
     * called `notifyStateChanged()` unconditionally, so a panel mirroring every tick sent a state delta
     * per tick forever, carrying values nobody had moved — and every one of those deltas was
     * <em>correct</em>, so an assertion on state passes against the bug.</p>
     */
    @Test
    public void aQuietTickSendsNothing() {
        window = ServerWindows.of(serverEnd).open(MachinePanel.TYPE, machine);
        settle(8);
        link[0].clearSent();

        // Ticks with a model nobody touched. The projections run and every comparison answers "same".
        for (int i = 0; i < 5; i++) {
            serverEnd.tick();
            settle(1);
        }

        assertEquals("a settled model must not put a byte on the wire", 0, link[0].sent().size());
    }

    /**
     * A window nobody is watching does not evaluate its projections.
     *
     * <p>Something a hand-written {@code mirror()} in {@code tick()} structurally could not be: a
     * minimised window went on walking its whole model to write values no one could see.</p>
     */
    @Test
    public void aHiddenWindowDoesNotEvaluateItsProjections() {
        window = ServerWindows.of(serverEnd).open(MachinePanel.TYPE, machine);
        settle(6);
        String opening = machine.label();
        assertEquals(opening, clientPanel().label.getText());

        window.session().setViewerVisible(false);
        machine.setLabel("changed while nobody looked");
        for (int i = 0; i < 5; i++) {
            serverEnd.tick();
            settle(1);
        }

        // The SERVER's own widget is untouched, which is the observable that separates "not evaluated"
        // from "evaluated and not sent" -- suppressing only the send would have written it here.
        assertEquals("the projection must not have run at all",
                opening, window.panel().label.getText());

        // And it catches up the moment somebody is looking again, rather than staying stale for good.
        window.session().setViewerVisible(true);
        for (int i = 0; i < 3; i++) {
            serverEnd.tick();
            settle(1);
        }
        assertEquals("changed while nobody looked", clientPanel().label.getText());
    }

    /**
     * The auto-projection report names what it could not wire, and the misses are the real ones.
     *
     * <p>{@code MachinePanel} is a good specimen precisely because it is mixed: three fields whose names
     * meet an accessor, one whose model accessor is called something else entirely
     * ({@code isRunning()}), one composed from two fields, and several buttons that carry no state at
     * all. Anything that silently wired the wrong slot, or silently wired nothing, shows up here.</p>
     */
    @Test
    public void theAutoProjectionReportIsHonestAboutTheMachinePanel() {
        window = ServerWindows.of(serverEnd).open(MachinePanel.TYPE, machine);
        settle(6);

        MachinePanel panel = window.panel();
        // Wired by convention: field name meets accessor name, contract names the slot.
        machine.setThroughput(0.5f);
        machine.setLabel("wired by name");
        for (int i = 0; i < 3; i++) {
            serverEnd.tick();
            settle(1);
        }
        assertEquals(0.5f, panel.throughput.getValue(), 1e-6f);
        assertEquals("wired by name", panel.label.getText());

        // Wired explicitly, because no accessor is called `power`.
        machine.setRunning(true);
        for (int i = 0; i < 3; i++) {
            serverEnd.tick();
            settle(1);
        }
        assertTrue(panel.power.isChecked());
        assertTrue("the composed readout is projected too", panel.status.getText().contains("running"));
    }

    /**
     * <b>Ten players on one model.</b> One of them acts; the rest see it.
     *
     * <p>Two separate players means two connections, two windows and two panel instances sharing one
     * {@code MachineModel} — the shape a mod actually opens. A's event reaches A's session, which
     * updates the shared model; on the next tick <b>every</b> window's projections read that model, see
     * a value that differs from what they last wrote, and each sends its own delta to its own viewer.</p>
     *
     * <p>Nothing coordinates them, and nothing needs to: a projection compares against what IT last
     * wrote, so a window that was never told about the change still notices it.</p>
     */
    @Test
    public void oneViewersActionReachesEveryOtherViewer() {
        InMemoryTransport<Object>[] second = InMemoryTransport.pair();
        ProtocolConnection<Object> serverB = Protocols.open(second[0], PlainOps.INSTANCE, () -> { }, "other");
        ProtocolConnection<Object> clientB = Protocols.open(second[1], PlainOps.INSTANCE, () -> { }, null);
        ClientWindows.of(clientB).setMount(new SilentMount());

        // The SAME model object, opened for two different players.
        window = ServerWindows.of(serverEnd).open(MachinePanel.TYPE, machine);
        ServerWindow<MachinePanel> windowB = ServerWindows.of(serverB).open(MachinePanel.TYPE, machine);
        for (int i = 0; i < 8; i++) {
            link[0].deliver(); link[1].deliver(); serverEnd.tick(); clientEnd.tick();
            second[0].deliver(); second[1].deliver(); serverB.tick(); clientB.tick();
        }

        MachinePanel a = clientPanel();
        MachinePanel b = (MachinePanel) ClientWindows.of(clientB).windows().get(0).root();
        assertFalse(a.power.isChecked());
        assertFalse("both start from the same model", b.power.isChecked());

        // PLAYER A flips their switch, for real: the client's own signal, which is what the reported
        // event rides on.
        a.power.setChecked(true);
        for (int i = 0; i < 8; i++) {
            link[0].deliver(); link[1].deliver(); serverEnd.tick(); clientEnd.tick();
            second[0].deliver(); second[1].deliver(); serverB.tick(); clientB.tick();
        }

        assertTrue("the model must have moved", machine.isRunning());
        assertTrue("...and player B's screen must follow, with nothing wiring the two windows together",
                b.power.isChecked());
        assertTrue(windowB.panel().power.isChecked());
    }

    private static final class SilentMount implements WindowMount {
        @Override
        public MountedWindow mount(ClientWindowContext context) {
            return new MountedWindow() {
                @Override public void closedByServer(String reason) { }
                @Override public void focus() { }
                @Override public void contentReplaced(UIElement newRoot) { }
            };
        }
    }
}
