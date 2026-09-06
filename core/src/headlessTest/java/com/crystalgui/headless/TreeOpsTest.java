package com.crystalgui.headless;

import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.ServerUiSession;
import com.crystalgui.net.protocol.Envelope;
import com.crystalgui.net.protocol.EnvelopeCodec;
import com.crystalgui.net.protocol.UiMethods;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.Slider;
import com.crystalgui.widget.text.UIText;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Phase 4 <b>C2</b> — the tree can change shape without re-opening the window.
 *
 * <p>The gap was recorded as <i>"a real design problem, not an afternoon, because network ids are
 * positional"</i>. The premise was right and the conclusion did not follow: an id is a depth-first
 * position, so an insertion renumbers everything after it — but ids do not have to be <b>stable</b>,
 * only <b>agreed</b>. Two peers applying the same delta to the same tree in the same order agree by
 * construction, so both simply renumber afterwards. Nothing carries an id table, and the description
 * stays a pure description, which were the two properties the original design was protecting.</p>
 */
public class TreeOpsTest {

    private UIElement root;
    private InMemoryTransport<Object> serverLink;
    private InMemoryTransport<Object> clientLink;
    private ServerUiSession<UIElement, Object> server;
    private ClientUiSession<UIElement, Object> client;

    @Before
    public void setUp() {
        UIElementRegistry.bootstrap();
        root = new UIElement();
        root.append(new UIText("first"));
        root.append(new Button("Press me"));

        InMemoryTransport<Object>[] pair = InMemoryTransport.pair();
        serverLink = pair[0];
        clientLink = pair[1];
        server = Sessions.serve(1, root, serverLink);
        client = Sessions.view(clientLink);
    }

    private void settle() {
        for (int i = 0; i < 12; i++) {
            clientLink.deliver();
            serverLink.deliver();
            client.tick();
            server.tick();
        }
    }

    private long countMethod(InMemoryTransport<Object> link, String method) {
        return link.sent().stream()
                .map(raw -> EnvelopeCodec.decode(PlainOps.INSTANCE, raw))
                .filter(e -> e instanceof Envelope.Notification<?> n && method.equals(n.method()))
                .count();
    }

    // ── The claim ───────────────────────────────────────────────────────────

    /** A child appended after open arrives without the window being re-opened. */
    @Test
    public void anAppendedChildArrives() {
        server.open();
        settle();
        assertEquals(2, client.root().children().size());
        serverLink.clearSent();

        root.append(new UIText("second"));
        settle();

        assertEquals("the new child must arrive", 3, client.root().children().size());
        assertEquals("second", ((UIText) client.root().children().get(2)).getText());
        assertEquals("as one treeOps message", 1, countMethod(serverLink, UiMethods.TREE_OPS));
        assertEquals("and NOT as a re-open", 0, countMethod(serverLink, UiMethods.OPEN_WINDOW));
    }

    /** A removed child goes. */
    @Test
    public void aRemovedChildGoes() {
        server.open();
        settle();

        root.remove(root.children().get(0));
        settle();

        assertEquals(1, client.root().children().size());
        assertTrue("the button is what is left",
                client.root().children().get(0) instanceof Button);
    }

    /**
     * State keeps working <em>after</em> a structural change — the assertion the whole design is for.
     *
     * <p>An insertion renumbers everything after it. If the two sides disagreed about the numbering by
     * even one, this update would land on a different element and silently change the wrong widget,
     * which is precisely why the naive version of this feature is dangerous rather than merely broken.</p>
     */
    @Test
    public void stateStillLandsOnTheRightElementAfterAReshape() {
        Slider slider = new Slider();
        slider.setRange(0f, 10f);
        root.append(slider);
        server.open();
        settle();

        // Insert BEFORE the slider, so its id shifts.
        root.insertAt(0, new UIText("inserted"));
        settle();

        slider.setValue(6f);
        settle();

        UIElement mirrored = client.root().children().get(3);
        assertTrue("the slider must still be the slider", mirrored instanceof Slider);
        assertEquals("and the update must have landed on it", 6f, ((Slider) mirrored).getValue(), 0.001f);
        assertEquals("inserted", ((UIText) client.root().children().get(0)).getText());
    }

    /** A whole subtree grafted in one go arrives, anchored at the nearest element the client knows. */
    @Test
    public void aGraftedSubtreeArrives() {
        server.open();
        settle();

        UIElement panel = new UIElement();
        panel.append(new UIText("inside"));
        UIElement nested = new UIElement();
        nested.append(new Button("deep"));
        panel.append(nested);
        root.append(panel);
        settle();

        UIElement arrived = client.root().children().get(2);
        assertEquals(2, arrived.children().size());
        assertEquals("inside", ((UIText) arrived.children().get(0)).getText());
        assertEquals("deep",
                ((Button) arrived.children().get(1).children().get(0)).getText());
    }

    /**
     * Several changes in one tick collapse into one delta at the shallowest anchor.
     *
     * <p>Adding a subtree dirties every parent inside it. Sending an entry per parent would be redundant
     * and actively wrong — the client replaces the anchor's children before it reaches the entry naming
     * something inside it, so the later entry would address an element that no longer exists.</p>
     */
    @Test
    public void manyChangesInOneTickCollapseToOneDelta() {
        server.open();
        settle();
        serverLink.clearSent();

        UIElement panel = new UIElement();
        panel.append(new UIText("a"));
        panel.append(new UIText("b"));
        root.append(panel);
        root.append(new UIText("c"));
        settle();

        assertEquals("one delta, not one per changed parent",
                1, countMethod(serverLink, UiMethods.TREE_OPS));
        assertEquals(4, client.root().children().size());
    }

    /** Events still reach their handler after a reshape — ids agree in that direction too. */
    @Test
    public void anEventStillReachesItsHandlerAfterAReshape() {
        Button button = (Button) root.children().get(1);
        AtomicInteger presses = new AtomicInteger();
        server.on(button, Button.ACTIVATE, ctx -> presses.incrementAndGet());
        server.open();
        settle();

        root.insertAt(0, new UIText("shifts everything"));
        settle();

        UIElement mirrored = client.root().children().get(2);
        assertTrue(mirrored instanceof Button);
        ((Button) mirrored).onPressed.emit();
        settle();

        assertEquals("the press must reach the server's lambda for the RIGHT button", 1, presses.get());
    }

    /** Nothing structural happened, nothing is sent. */
    @Test
    public void anUnchangedTreeSendsNoDelta() {
        server.open();
        settle();
        serverLink.clearSent();

        settle();

        assertEquals(0, countMethod(serverLink, UiMethods.TREE_OPS));
    }

    /** Opening does not immediately emit a delta restating the tree it just described. */
    @Test
    public void openingDoesNotEmitADeltaForItsOwnTree() {
        server.open();
        settle();

        assertNotNull(client.root());
        assertEquals("setObserver reports every element as attached; that must not become a delta",
                0, countMethod(serverLink, UiMethods.TREE_OPS));
    }
}
