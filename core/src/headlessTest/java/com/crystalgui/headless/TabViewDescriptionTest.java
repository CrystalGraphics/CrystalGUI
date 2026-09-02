package com.crystalgui.headless;

import com.crystalgui.net.mirror.UINodeMirror;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.UIDescriptionCodec;
import com.crystalgui.ui.ElementRegistry;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.widget.control.Slider;
import com.crystalgui.widget.layout.Tab;
import com.crystalgui.widget.layout.TabView;
import com.crystalgui.widget.text.UIText;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Phase 4 <b>C3</b> — a {@code TabView} survives being described.
 *
 * <p>It did not. Tabs and panes live in internal containers, the codec skipped internal children as
 * scaffolding a constructor rebuilds, and a TabView therefore arrived <b>with no tabs at all</b>. The
 * class javadoc had said so since it was written.</p>
 *
 * <p>The fix is a distinction the old code could not express: <i>internal</i> answered two questions at
 * once. A {@code Switch}'s knob really is scaffolding. A tab is <b>content</b> that happens to live in an
 * internal container because that is how the widget lays it out — skipping it does not avoid duplication,
 * it loses the window.</p>
 */
public class TabViewDescriptionTest {

    @BeforeClass
    public static void bootstrap() {
        ElementRegistry.bootstrapBuiltins();
    }

    private static UINode roundTrip(UINode source) {
        Object encoded = new UINodeMirror<>(PlainOps.INSTANCE).describe(source);
        return new UINodeMirror<>(PlainOps.INSTANCE).decode(encoded);
    }

    /** Three tabs in, three tabs out, labelled the same and in the same order. */
    @Test
    public void tabsSurviveWithTheirLabelsAndOrder() {
        TabView source = new TabView();
        source.addTab("First");
        source.addTab("Second");
        source.addTab("Third");

        TabView back = (TabView) roundTrip(source);

        assertEquals("all three tabs must arrive", 3, back.getTabs().size());
        assertEquals("First", back.getTabs().get(0).getText());
        assertEquals("Second", back.getTabs().get(1).getText());
        assertEquals("Third", back.getTabs().get(2).getText());
    }

    /**
     * A tab's <em>contents</em> arrive too — the half that makes it a window rather than a set of labels.
     *
     * <p>Asserted through the widget types rather than a count, because a pane that arrived holding the
     * wrong thing has the right count.</p>
     */
    @Test
    public void tabContentSurvives() {
        TabView source = new TabView();
        Tab first = source.addTab("Editor");
        first.content().append(new UIText("hello"));
        Tab second = source.addTab("Settings");
        second.content().append(new Slider());
        second.content().append(new UIText("volume"));

        TabView back = (TabView) roundTrip(source);

        assertEquals(1, back.getTabs().get(0).content().children().size());
        assertTrue("the first tab held a text",
                back.getTabs().get(0).content().children().get(0) instanceof UIText);
        assertEquals("hello", ((UIText) back.getTabs().get(0).content().children().get(0)).getText());

        assertEquals(2, back.getTabs().get(1).content().children().size());
        assertTrue("the second tab held a slider",
                back.getTabs().get(1).content().children().get(0) instanceof Slider);
    }

    /**
     * The selection survives — and this is what the codec's state-after-children ordering buys.
     *
     * <p>{@code selectIndex} refuses an out-of-range index, so applying the selection before the tabs
     * existed silently left tab 0 selected. The value was on the wire the whole time and simply could not
     * be applied, which is the least diagnosable shape a bug can take.</p>
     */
    @Test
    public void theSelectedTabSurvives() {
        TabView source = new TabView();
        source.addTab("One");
        source.addTab("Two");
        source.addTab("Three");
        source.selectIndex(2);
        assertEquals("precondition", 2, source.getSelectedIndex());

        TabView back = (TabView) roundTrip(source);

        assertEquals("the third tab must still be the selected one", 2, back.getSelectedIndex());
        assertEquals("Three", back.getSelectedTab().getText());
    }

    /** Nested: a TabView inside a tab of another TabView. Recursion, not a special case. */
    @Test
    public void aTabViewInsideATabSurvives() {
        TabView outer = new TabView();
        Tab host = outer.addTab("Outer");
        TabView inner = new TabView();
        inner.addTab("Inner A");
        inner.addTab("Inner B");
        inner.selectIndex(1);
        host.content().append(inner);

        TabView back = (TabView) roundTrip(outer);

        UINode nested = back.getTabs().get(0).content().children().get(0);
        assertTrue("the nested view must arrive as a TabView", nested instanceof TabView);
        assertEquals(2, ((TabView) nested).getTabs().size());
        assertEquals("and keep its own selection", 1, ((TabView) nested).getSelectedIndex());
    }

    /**
     * A description may not invent non-tab children for a TabView.
     *
     * <p>Opening the door to described children must not open it to <em>anything</em>: the guard that
     * catches an encoder which serialized internals is still there, just asked as "may a description
     * carry children for this" rather than "does this accept public children".</p>
     */
    @Test
    public void aTabViewRefusesANonTabChild() {
        TabView view = new TabView();
        try {
            view.adoptDescribedChild(new UIText("not a tab"));
            fail("a <tabview> must refuse a described child that is not a <tab>");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("tab"));
        }
    }

    /** An empty TabView is still empty afterwards — no phantom tab from the selection index. */
    @Test
    public void anEmptyTabViewStaysEmpty() {
        TabView back = (TabView) roundTrip(new TabView());
        assertEquals(0, back.getTabs().size());
        assertEquals(-1, back.getSelectedIndex());
    }
}
