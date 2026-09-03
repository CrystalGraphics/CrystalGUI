package com.crystalgui.headless;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.net.window.ClientScope;
import com.crystalgui.net.window.ClientWindowContext;
import com.crystalgui.net.window.ClientWindows;
import com.crystalgui.net.window.Networked;
import com.crystalgui.net.window.ServerScope;
import com.crystalgui.net.window.ServerWindow;
import com.crystalgui.net.window.ServerWindows;
import com.crystalgui.net.window.UiType;
import com.crystalgui.net.window.WindowMount;
import com.crystalgui.net.window.WindowProtocol;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.text.UIText;

/**
 * <b>A control the viewer added, on a tree the server owns</b> — {@code plan_ui_rewrite.md} 7.2.
 *
 * <p>{@code client(io)} runs over every build of the tree, and its whole job is local extras. What it
 * could not do is <em>add</em> one: an appended child is an ordinary described child, so the next
 * {@code insert} the server sends lands one index off — silently, because an index is an int and every
 * one of them still resolves to something.</p>
 *
 * <p>So the three properties here are the three ways that fails. A local child must survive a
 * re-describe (or the panel that added it is holding a detached tree); it must never move a described
 * index (or every insert after a viewer adds a button is one place out); and it must not be counted by
 * the integrity check (or a client with one more child than the server described refuses the whole
 * window).</p>
 */
public class LocalChildTest {

    private InMemoryTransport<Object>[] link;
    private ProtocolConnection<Object> serverEnd;
    private ProtocolConnection<Object> clientEnd;

    private final List<ClientWindowContext> mounted = new ArrayList<>();

    @Before
    public void setUp() {
        Protocols.resetForTesting();
        WindowProtocol.resetForTesting();
        WindowProtocol.register();
        ServerWindows.resetOpenableForTesting();
        RowPanel.copies.clear();

        link = InMemoryTransport.pair();
        serverEnd = Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "player");
        clientEnd = Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);
        ClientWindows.of(clientEnd).setMount(new SilentMount());
    }

    @After
    public void tearDown() {
        Protocols.resetForTesting();
        WindowProtocol.resetForTesting();
        ServerWindows.resetOpenableForTesting();
    }

    private void settle() {
        for (int i = 0; i < 10; i++) {
            link[0].deliver();
            link[1].deliver();
            serverEnd.tick();
            clientEnd.tick();
        }
    }

    private final class SilentMount implements WindowMount {
        @Override
        public MountedWindow mount(ClientWindowContext context) {
            mounted.add(context);
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

    private RowPanel client() {
        assertFalse("a window was mounted", mounted.isEmpty());
        return (RowPanel) mounted.get(mounted.size() - 1).root();
    }

    // ── The three properties ────────────────────────────────────────────────────────────────────

    /**
     * <b>A local child survives a re-describe.</b>
     *
     * <p>Survives by being <em>re-added</em>, which is the honest form: a re-describe builds a fresh
     * tree and fresh panels, so the old one went with the old tree. {@code client(io)} running on every
     * bind is what makes that automatic rather than something each panel remembers.</p>
     */
    @Test
    public void aLocalChildSurvivesAReDescribe() {
        ServerWindow<RowPanel> served = ServerWindows.of(serverEnd).open(RowPanel.TYPE, null);
        settle();
        RowPanel before = client();
        assertNotNull("the local button is there", localIn(before.rows));

        // A VIEWER COMING BACK, which is the real re-describe path: a hidden viewer misses deltas, so
        // the window is described afresh rather than caught up. The client decodes a whole new tree.
        ClientWindowContext context = mounted.get(0);
        context.visibilityChanged(false);
        settle();
        // A STATE change while nobody is looking is what a returning viewer has MISSED, and missing one
        // is what makes the way back a re-describe rather than a catch-up.
        served.panel().retitle("first (edited)");
        settle();
        context.visibilityChanged(true);
        settle();

        RowPanel after = client();
        // THE COUNTER-CONTROL: the server's edit landed, so the resync genuinely ran. Without it this
        // would pass against a viewer that never went away and never came back.
        assertEquals(List.of("first (edited)"), describedTexts(after.rows));
        assertNotNull("...and the local button is still there", localIn(after.rows));
        assertEquals("exactly one, never doubled by client(io) running again",
                1, localCount(after.rows));
    }

    /**
     * <b>A local child never shifts a described index.</b>
     *
     * <p>The one that is silent when broken. With the button appended as an ordinary child, the
     * server's next {@code insert at 1} lands after it and the rows come out in the wrong order — a
     * wrong picture rather than a failure.</p>
     */
    @Test
    public void aLocalChildNeverShiftsADescribedIndex() {
        ServerWindow<RowPanel> served = ServerWindows.of(serverEnd).open(RowPanel.TYPE, null);
        settle();
        assertNotNull(localIn(client().rows));

        served.panel().addRowAt(0, "inserted-first");
        served.panel().addRow("appended-last");
        settle();

        assertEquals("the server's order, unmoved by the viewer's own control",
                List.of("inserted-first", "first", "appended-last"), describedTexts(client().rows));
        // ...and the local one is still there, after all of them.
        assertNotNull(localIn(client().rows));
        assertEquals("last in the light tree, which is what makes the above true",
                4, client().rows.children().size());
    }

    /**
     * <b>A local child is not counted by the integrity check.</b>
     *
     * <p>{@code ClientTreeMirror} refuses an insert whose described element count disagrees with what
     * it decoded, and rightly — that really would mean the two sides are building different structure.
     * A viewer's own button is not that, and counting it would make the window tear itself down the
     * first time anybody added one.</p>
     */
    @Test
    public void aLocalChildIsNotCountedByTheIntegrityCheck() {
        ServerWindow<RowPanel> served = ServerWindows.of(serverEnd).open(RowPanel.TYPE, null);
        settle();
        UIElement rows = client().rows;
        assertEquals("described: the server's rows only", 1, rows.describedChildren().size());
        assertEquals("light: those, plus the viewer's button", 2, rows.children().size());

        // The insert below carries a count; a mismatch tears the window down.
        served.panel().addRow("second");
        settle();

        assertTrue("the window is still open", served.isOpen());
        assertEquals(2, client().rows.describedChildren().size());
    }

    /**
     * <b>A local copy button on a served row needs no round trip.</b>
     *
     * <p>What the whole mechanism is for. Pressing it runs on the client and the server is not
     * involved — which is checked by counting what crossed the wire, not by trusting the handler.</p>
     */
    @Test
    public void aLocalCopyButtonOnAServedRowNeedsNoRoundTrip() {
        ServerWindows.of(serverEnd).open(RowPanel.TYPE, null);
        settle();
        Button copy = (Button) localIn(client().rows);
        assertNotNull(copy);

        link[1].clearSent();
        copy.onPressed.emit();
        settle();

        assertEquals("the viewer's own control, pressed", List.of("first"), RowPanel.copies);
        assertTrue("and nothing left the client: " + link[1].sent(), link[1].sent().isEmpty());
    }

    private static int localCount(UIElement parent) {
        int count = 0;
        for (UIElement child : parent.children()) {
            if (child.isLocal()) count++;
        }
        return count;
    }

    /**
     * <b>A local child is one viewer's, and the other viewer never sees it.</b>
     *
     * <p>The clearest statement of what "local" means, and the one a single-client fixture cannot make:
     * two people looking at one window each add their own controls, neither travels, and the server has
     * no idea either exists. A local child that reached the mirror would reach <em>every</em> viewer —
     * one person's copy button appearing on everybody's screen.</p>
     */
    @Test
    public void aLocalChildBelongsToOneViewerAndNotTheOther() {
        InMemoryTransport<Object>[] linkB = InMemoryTransport.pair();
        ProtocolConnection<Object> serverB = Protocols.open(linkB[0], PlainOps.INSTANCE, () -> { }, "bob");
        ProtocolConnection<Object> clientB = Protocols.open(linkB[1], PlainOps.INSTANCE, () -> { }, null);
        List<ClientWindowContext> mountedB = new ArrayList<>();
        ClientWindows.of(clientB).setMount(context -> {
            mountedB.add(context);
            return new WindowMount.MountedWindow() {
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
        });

        ServerWindow<RowPanel> served = ServerWindows.of(serverEnd).open(RowPanel.TYPE, null);
        served.session().addViewer(serverB);
        for (int i = 0; i < 12; i++) {
            link[0].deliver();
            link[1].deliver();
            linkB[0].deliver();
            linkB[1].deliver();
            serverEnd.tick();
            clientEnd.tick();
            serverB.tick();
            clientB.tick();
        }

        assertEquals(2, served.session().viewerCount());
        assertFalse("bob got the window too", mountedB.isEmpty());
        RowPanel alice = client();
        RowPanel bob = (RowPanel) mountedB.get(mountedB.size() - 1).root();

        assertEquals("each viewer added its own", 1, localCount(alice.rows));
        assertEquals(1, localCount(bob.rows));
        assertNotSame("...and they are different elements, on different trees",
                localIn(alice.rows), localIn(bob.rows));
        assertEquals("the server knows about neither", 1,
                served.panel().rows.describedChildren().size());
        assertEquals("...and describes neither to either of them",
                1, alice.rows.describedChildren().size());
        assertEquals(1, bob.rows.describedChildren().size());
    }

    /** The one local child under {@code parent}, or null. */
    @Nullable
    private static UIElement localIn(UIElement parent) {
        for (UIElement child : parent.children()) {
            if (child.isLocal()) return child;
        }
        return null;
    }

    /** What each described row says, in order. */
    private static List<String> describedTexts(UIElement rows) {
        List<String> out = new ArrayList<>();
        for (UIElement row : rows.describedChildren()) out.add(((UIText) row).getText());
        return out;
    }

    /** A list of served rows, with one control the viewer adds for itself. */
    public static class RowPanel extends UIElement implements Networked<Void> {

        public static final Name NAME = Name.of("rowpanel");

        public static final UiType<RowPanel, Void> TYPE = UiType.of("test:rows", RowPanel::new);

        /** What the local button copied, so the test can see it ran client-side. */
        static final List<String> copies = new ArrayList<>();

        public UIElement rows = new UIElement();

        public RowPanel() {
            super(NAME);
        }

        @Override
        public void build(Void model) {
            append(rows);
            rows.append(new UIText("first"));
        }

        void retitle(String text) {
            ((UIText) rows.describedChildren().get(0)).setText(text);
        }

        void addRow(String text) {
            rows.append(new UIText(text));
        }

        void addRowAt(int index, String text) {
            rows.insertAt(index, new UIText(text));
        }

        @Override
        public void serve(Void model, ServerScope io) {
        }

        @Override
        public void client(ClientScope io) {
            Button copy = new Button("Copy");
            copy.onPressed.connect(() -> {
                UIElement first = rows.describedChildren().isEmpty()
                        ? null : rows.describedChildren().get(0);
                if (first instanceof UIText text) copies.add(text.getText());
            });
            io.addLocal(rows, copy);
        }
    }
}
