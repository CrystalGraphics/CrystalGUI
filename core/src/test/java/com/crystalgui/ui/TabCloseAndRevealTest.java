package com.crystalgui.ui;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.Tab;
import com.crystalgui.ui.elements.TabView;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A tab's close affordance, and scrolling a selected tab into view.
 *
 * <p>The close half asserts <b>wiring</b> and the reveal half asserts <b>geometry</b>, and the split is
 * not arbitrary. Whether a press on the cross also selects the tab is a fact about event phases that no
 * amount of layout would make clearer; whether the strip moved is only answerable by laying it out. The
 * reveal fixture therefore runs real frames with the user-agent sheet installed, which {@code TabViewTest}
 * deliberately does not — it is the same widget asked a different kind of question.</p>
 */
public class TabCloseAndRevealTest extends UiTestBase {

    private TabView build() {
        TabView view = new TabView();
        UIElement root = new UIElement();
        root.addChild(view);
        UIWindow window = new UIWindow(Ui.of(root));
        window.init(400, 300);
        return view;
    }

    private UIElement closeButtonOf(Tab tab) {
        for (UIElement child : tab.getChildren()) {
            if (child.hasClass(Tab.CLOSE_CLASS)) return child;
        }
        return null;
    }

    // ── The close affordance ────────────────────────────────────────────────────────────────────

    /** It exists only where it was asked for: a tab that cannot be closed must not reserve the space. */
    @Test
    public void onlyAClosableTabHasACloseButton() {
        TabView view = build();
        Tab plain = view.addTab("plain");
        Tab closable = view.addTab("closable").setClosable(true);

        assertNull("a tab nobody made closable", closeButtonOf(plain));
        assertNotNull(closeButtonOf(closable));
        assertTrue(closable.isClosable());
        assertFalse(plain.isClosable());
    }

    /** ...and it can be taken away again, leaving nothing behind. */
    @Test
    public void closabilityCanBeRevoked() {
        TabView view = build();
        Tab tab = view.addTab("one").setClosable(true);
        assertNotNull(closeButtonOf(tab));

        tab.setClosable(false);

        assertNull(closeButtonOf(tab));
        assertFalse(tab.isClosable());
    }

    /**
     * <b>Pressing it asks to close and does NOT select the tab.</b>
     *
     * <p>The behavioural claim behind not writing a {@code stopPropagation} here. A {@code Button}'s
     * activation comes from its {@code defaultEvents}, which fire only in the TARGET phase — so a press
     * whose target is the close button reaches the tab in the BUBBLE phase, where the tab's own activation
     * does not run. If that ever stops being true, closing a background tab would select it on the way
     * out, and this is the test that says so.</p>
     */
    @Test
    public void pressingCloseAsksToCloseWithoutSelectingTheTab() {
        TabView view = build();
        Tab first = view.addTab("first");
        Tab second = view.addTab("second").setClosable(true);
        view.selectTab(first);

        List<String> closed = new ArrayList<>();
        second.onCloseRequested.connect(() -> closed.add("second"));

        // Through the button's own activation, which is what a press on it produces.
        ((com.crystalgui.ui.elements.Button) closeButtonOf(second)).onPressed.emit();

        assertEquals(List.of("second"), closed);
        assertEquals("the tab under the cross must not become the selected one",
                first, view.getSelectedTab());
    }

    /** A close is a REQUEST — the tab does not remove itself, because it does not know what it is for. */
    @Test
    public void aCloseRequestDoesNotRemoveTheTabByItself() {
        TabView view = build();
        Tab tab = view.addTab("one").setClosable(true);

        ((com.crystalgui.ui.elements.Button) closeButtonOf(tab)).onPressed.emit();

        assertTrue("whoever owns the document decides, and may want to ask first",
                view.getTabs().contains(tab));
    }

    // ── Revealing the selected tab ──────────────────────────────────────────────────────────────

    /**
     * <b>Selecting a tab off the end of a full strip scrolls it into view.</b>
     *
     * <p>The reported bug: with more tabs than fit, opening a file selected a tab nobody could see — the
     * editor changed and the strip did not move, so it read as the wrong file having opened.</p>
     *
     * <p>Driven through real frames rather than by inspecting the pending request, because the deferral is
     * the interesting part: a tab is selected the instant it is added, when it has never been laid out and
     * its width is zero, so the reveal has to survive until there is something to measure.</p>
     */
    @Test
    public void selectingATabOffTheEndScrollsItIntoView() {
        TabView view = new TabView();
        view.layout(l -> l.width(120).height(200));
        UIElement root = new UIElement().layout(l -> l.width(120).height(200));
        root.addChild(view);
        UIWindow window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(com.crystalgui.style.sheet.StyleSheet.DEFAULT);
        window.init(400, 300);

        List<Tab> tabs = new ArrayList<>();
        for (int i = 0; i < 20; i++) tabs.add(view.addTab("file" + i + ".java"));
        for (int i = 0; i < 12; i++) window.updateWithoutPainting();

        UIElement rail = railOf(view);
        assertTrue("the fixture has to overflow or there is nothing to reveal",
                rail.getMaxScrollLeft() > 0f);
        rail.setScrollImmediate(0f, 0f);
        for (int i = 0; i < 4; i++) window.updateWithoutPainting();

        view.selectTab(tabs.get(tabs.size() - 1));
        for (int i = 0; i < 12; i++) window.updateWithoutPainting();

        assertTrue("the strip must move to the tab that was selected",
                rail.getScrollLeft() > 0f);
    }

    /** ...and a tab already on screen does not move the strip, because scrollIntoView is minimal. */
    @Test
    public void selectingAVisibleTabDoesNotMoveTheStrip() {
        TabView view = new TabView();
        view.layout(l -> l.width(120).height(200));
        UIElement root = new UIElement().layout(l -> l.width(120).height(200));
        root.addChild(view);
        UIWindow window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(com.crystalgui.style.sheet.StyleSheet.DEFAULT);
        window.init(400, 300);

        List<Tab> tabs = new ArrayList<>();
        for (int i = 0; i < 20; i++) tabs.add(view.addTab("file" + i + ".java"));
        for (int i = 0; i < 12; i++) window.updateWithoutPainting();

        UIElement rail = railOf(view);
        rail.setScrollImmediate(0f, 0f);
        for (int i = 0; i < 4; i++) window.updateWithoutPainting();

        view.selectTab(tabs.get(0));
        for (int i = 0; i < 12; i++) window.updateWithoutPainting();

        assertEquals("already visible, so nothing to reveal", 0f, rail.getScrollLeft(), 0.01f);
    }

    private UIElement railOf(TabView view) {
        for (UIElement child : allUnder(view)) {
            if (child.hasClass(TabView.RAIL_CLASS)) return child;
        }
        throw new AssertionError("no rail");
    }

    private List<UIElement> allUnder(UIElement from) {
        List<UIElement> out = new ArrayList<>();
        collect(from, out);
        return out;
    }

    private void collect(UIElement from, List<UIElement> into) {
        for (UIElement child : from.getChildren()) {
            into.add(child);
            collect(child, into);
        }
    }
}
