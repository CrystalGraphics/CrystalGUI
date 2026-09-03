package com.crystalgui.headless;

import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.RowWindows;
import com.crystalgui.net.ServerUiSession;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.net.protocol.UiMethods;
import com.crystalgui.net.projection.Projections;
import com.crystalgui.net.window.RowSource;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.text.UIText;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * A collection the server holds and a viewer sees a <b>window</b> of.
 *
 * <p>The property that matters is bounded traffic: ten thousand rows must cost a screenful of
 * described elements, not ten thousand. Everything else here is what makes that safe — that an insert
 * above the window does not move the rows in it, that two viewers cost the union of what they are
 * looking at and nothing more, and that a viewer going away shrinks it again.</p>
 */
public class RowStreamTest {

    private static final int TOTAL = 10_000;

    /** The whole collection, server-side. Rows are their own index, which is a stable key here. */
    private final List<Integer> all = new ArrayList<>();

    private UIElement root;
    private UIElement list;

    private InMemoryTransport<Object>[] linkA;
    private InMemoryTransport<Object>[] linkB;
    private ProtocolConnection<Object> serverA;
    private ProtocolConnection<Object> serverB;
    private ProtocolConnection<Object> clientA;
    private ProtocolConnection<Object> clientB;
    private ServerUiSession<UIElement, Object> server;
    private ClientUiSession<UIElement, Object> viewerA;
    private ClientUiSession<UIElement, Object> viewerB;

    private RowWindows windows;
    private Projections projections;

    /** How many times the source was asked for a range — the read cost, which must not be per row. */
    private final AtomicInteger reads = new AtomicInteger();

    @Before
    public void setUp() {
        Protocols.resetForTesting();
        UIElementRegistry.bootstrap();
        for (int i = 0; i < TOTAL; i++) all.add(i);

        root = new UIElement();
        list = new UIElement();
        root.append(list);

        linkA = InMemoryTransport.pair();
        linkB = InMemoryTransport.pair();
        serverA = Protocols.open(linkA[0], PlainOps.INSTANCE, () -> { }, "alice");
        clientA = Protocols.open(linkA[1], PlainOps.INSTANCE, () -> { }, null);
        serverB = Protocols.open(linkB[0], PlainOps.INSTANCE, () -> { }, "bob");
        clientB = Protocols.open(linkB[1], PlainOps.INSTANCE, () -> { }, null);

        server = Sessions.serveOn(1, root, serverA);
        viewerA = Sessions.viewOn(clientA);
        viewerB = Sessions.viewOn(clientB);

        RowSource<Integer> source = new RowSource<>() {
            @Override
            public int count() {
                return all.size();
            }

            @Override
            public List<Integer> rows(int from, int to) {
                reads.incrementAndGet();
                return List.copyOf(all.subList(Math.min(from, all.size()), Math.min(to, all.size())));
            }

            @Override
            public Object keyOf(Integer item) {
                return item;
            }
        };

        // The mechanism ServerScope.stream is built from, driven directly so the test needs no panel.
        windows = server.streamRows(list, source::count);
        projections = new Projections().each(
                () -> {
                    RowWindows.Window required = windows.required(source.count());
                    return required.to() <= required.from()
                            ? List.<Integer>of() : source.rows(required.from(), required.to());
                },
                list, source::keyOf, item -> new UIText(String.valueOf(item)),
                (row, item) -> ((UIText) row).setText(String.valueOf(item)));
    }

    @After
    public void tearDown() {
        Protocols.resetForTesting();
    }

    private void settle() {
        for (int i = 0; i < 24; i++) {
            projections.run();
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

    /** A viewer says which rows it is looking at, exactly as {@code RemoteRows} does. */
    private void showing(ProtocolConnection<Object> client, int from, int to) {
        StateMap<Object> args = new StateMap<>(PlainOps.INSTANCE);
        args.putInt(UiMethods.WINDOW, 1);
        args.putInt("nid", 1);
        args.putInt("from", from);
        args.putInt("to", to);
        client.call(UiMethods.ROWS, args, null, null);
    }

    private int describedRows() {
        return list.describedChildren().size();
    }

    // ── The claim ───────────────────────────────────────────────────────────────────────────────

    /**
     * <b>The headline.</b> Ten thousand rows, and what exists is a window.
     */
    @Test
    public void aTenThousandRowListShipsOnlyTheWindow() {
        server.open();
        settle();
        assertEquals("nobody is looking yet, so nothing is described", 0, describedRows());

        showing(clientA, 0, 40);
        settle();

        assertEquals(TOTAL, all.size());
        assertTrue("a window plus overscan, not ten thousand rows",
                describedRows() <= 40 + 2 * RowWindows.OVERSCAN);
        assertTrue("and the rows are actually there", describedRows() >= 40);
    }

    /** Scrolling slides the window and lets go of what is behind it. */
    @Test
    public void scrollingSlidesTheWindowAndReleasesRowsBehindIt() {
        server.open();
        showing(clientA, 0, 40);
        settle();
        UIElement firstRow = list.describedChildren().get(0);

        showing(clientA, 5000, 5040);
        settle();

        assertTrue("still a window", describedRows() <= 40 + 2 * RowWindows.OVERSCAN);
        assertTrue("and the rows it moved away from are gone",
                !list.describedChildren().contains(firstRow));
        assertEquals("5000", ((UIText) list.describedChildren()
                .get(RowWindows.OVERSCAN)).getText());
    }

    /**
     * <b>An insert above the window rebuilds no row in it.</b>
     *
     * <p>A window is positional, so an insert above it genuinely changes which ITEMS it covers — that
     * is what scrolling means. What must not happen is a rebuild: an item still in the window keeps the
     * element it already had, so the mirror sees one insert rather than a cleared child list, and
     * everything a viewer had done to those rows survives.</p>
     *
     * <p>Keyed by index instead, every element below the insert is a different row and the viewer is
     * handed a rebuild of a list that mostly did not change.</p>
     */
    @Test
    public void anInsertAboveTheWindowRebuildsNoRow() {
        server.open();
        showing(clientA, 5000, 5040);
        settle();
        UIElement wasShowing5020 = rowShowing("5020");
        assertNotNull("the fixture must actually be showing it", wasShowing5020);

        all.add(0, -1);
        settle();

        assertSame("item 5020 is still in the window and must keep its element",
                wasShowing5020, rowShowing("5020"));
    }

    /** The element currently displaying {@code text}, or null when no row does. */
    private UIElement rowShowing(String text) {
        for (UIElement row : list.describedChildren()) {
            if (text.equals(((UIText) row).getText())) return row;
        }
        return null;
    }

    /** Two viewers at the same place cost one window between them. */
    @Test
    public void twoViewersLookingAtTheSameRowsCostOneWindow() {
        server.addViewer(serverB);
        server.open();
        showing(clientA, 0, 40);
        showing(clientB, 0, 40);
        settle();

        assertEquals(2, server.viewerCount());
        assertTrue("one window, not two", describedRows() <= 40 + 2 * RowWindows.OVERSCAN);
    }

    /**
     * <b>...and two scrolled apart cost the union and nothing more.</b> Rows are structure and
     * structure goes to every viewer, so the described set is the span between them — the honest bound,
     * and the one a sparse child list could not express at all.
     */
    @Test
    public void twoViewersScrolledApartCostTheUnionAndNothingMore() {
        server.addViewer(serverB);
        server.open();
        showing(clientA, 0, 20);
        showing(clientB, 100, 120);
        settle();

        int union = 120 + RowWindows.OVERSCAN;
        assertTrue("the span between them, plus overscan: " + describedRows(),
                describedRows() <= union);
        assertTrue("and it does cover both", describedRows() >= 120);
    }

    /** A viewer that goes away takes its window with it, so the union shrinks again. */
    @Test
    public void aViewerLeavingShrinksTheUnion() {
        server.addViewer(serverB);
        server.open();
        showing(clientA, 0, 20);
        showing(clientB, 5000, 5020);
        settle();
        int both = describedRows();

        server.removeViewer(serverB);
        settle();

        assertTrue("the union was wide while both were looking", both > 1000);
        assertTrue("and narrow once one left: " + describedRows(), describedRows() < 100);
    }

    /**
     * <b>A followed tail receives appends without asking.</b> What a log wants: without it every
     * appended line is a round trip to discover that the line after it exists too.
     */
    @Test
    public void aFollowedTailReceivesAppendsWithoutAsking() {
        server.open();
        // A WINDOW THAT REACHES THE END is taken to be following.
        showing(clientA, TOTAL - 20, TOTAL);
        settle();
        assertEquals(String.valueOf(TOTAL - 1),
                ((UIText) list.describedChildren().get(describedRows() - 1)).getText());

        all.add(TOTAL);
        settle();

        assertEquals("the appended row is described, with nobody having asked",
                String.valueOf(TOTAL),
                ((UIText) list.describedChildren().get(describedRows() - 1)).getText());
    }

    /**
     * The counter-control for it: a window that is NOT at the end does not follow.
     *
     * <p>Without this, "following" written as "always slide to the end" passes the test above and drags
     * every viewer to the bottom of a log the moment anything is appended — which is the opposite of
     * what somebody reading the middle of one wants.</p>
     */
    @Test
    public void aWindowInTheMiddleDoesNotFollow() {
        server.open();
        showing(clientA, 100, 140);
        settle();
        List<UIElement> before = List.copyOf(list.describedChildren());

        all.add(TOTAL);
        settle();

        assertEquals("a reader in the middle stays where they were",
                before, List.copyOf(list.describedChildren()));
    }
}
