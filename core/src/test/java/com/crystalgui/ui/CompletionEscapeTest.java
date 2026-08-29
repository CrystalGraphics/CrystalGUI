package com.crystalgui.ui;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.lang.CompletionItem;
import com.crystalgui.text.lang.CompletionList;
import com.crystalgui.text.lang.CompletionProvider;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.lang.Versioned;
import com.crystalgui.ui.elements.editor.CompletionPopup;
import com.crystalgui.ui.elements.editor.CompletionSession;

import org.junit.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * A completion list can always be dismissed — <b>including when the editor no longer has focus.</b>
 *
 * <h3>The four keys it owns are intercepted on the EDITOR</h3>
 *
 * <p>{@code TextEditor} says why: the popup never holds focus, so it never receives a key at all, and
 * the interception has to live on the element that does. What went unrecorded is the consequence — the
 * moment anything else takes focus, the editor stops receiving keys and the list is stranded on screen
 * with nothing able to close it.</p>
 *
 * <p>It was reported as Escape working "most of the time": clicking another tab does it, and so does a
 * panel that restores focus to a row when diagnostics arrive — which is why it happened most on an
 * unresolved type, the one case that produces an empty list AND a fresh diagnostic in the same breath.</p>
 *
 * <p>So the popup is on the window's two stacks. A close watcher is asked for Escape by the WINDOW rather
 * than by whoever holds focus; an auto popover is dismissed by a press outside it.</p>
 */
public class CompletionEscapeTest extends UiTestBase {

    private static CompletionProvider offering(List<CompletionItem> items) {
        return new CompletionProvider() {
            @Override
            public void complete(Request request, Consumer<Versioned<CompletionList>> answer) {
                answer.accept(Versioned.of(0, CompletionList.complete(items)));
            }

            @Override
            public void resolveItem(CompletionItem item, Consumer<CompletionItem> answer) {
                answer.accept(item);
            }
        };
    }

    /** A window with a live list open in it. */
    private Object[] openList(List<CompletionItem> items) {
        UIElement root = new UIElement().layout(l -> l.width(400).height(600));
        UIWindow window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(400, 600);

        TextBuffer buffer = new TextBuffer("");
        CompletionSession session = CompletionSession.open(
                buffer, offering(items), 0, CompletionProvider.TriggerKind.EXPLICIT, null);
        assertNotNull("the stub provider must have produced a session", session);

        CompletionPopup popup = new CompletionPopup();
        popup.attach(window, session);
        for (int i = 0; i < 4; i++) window.updateWithoutPainting();
        return new Object[] { window, popup, session, root };
    }

    private static CompletionItem item(String name) {
        return CompletionItem.builder(name, SymbolKind.FIELD).build();
    }

    /**
     * <b>The window can find it to give it Escape.</b>
     *
     * <p>The whole fix in one assertion. Escape reaches the topmost close watcher regardless of which
     * element holds focus, which is what the editor's own interception cannot do.</p>
     */
    @Test
    public void anOpenListIsWhatEscapeReachesFirst() {
        Object[] open = openList(List.of(item("alpha"), item("beta")));
        UIWindow window = (UIWindow) open[0];
        CompletionPopup popup = (CompletionPopup) open[1];

        assertSame("Escape cannot reach the list at all", popup, window.getTopCloseWatcher());
        assertTrue("a press outside cannot dismiss it", window.getAutoPopovers().contains(popup));
    }

    /**
     * <b>An EMPTY list is dismissable too.</b>
     *
     * <p>The reported shape: an unresolved type answers with nothing, so the box opens with no rows in
     * it. A list with nothing to offer is exactly the one a person most wants gone, and registration must
     * not be conditional on having something to show.</p>
     */
    @Test
    public void anEmptyListIsDismissableToo() {
        Object[] open = openList(List.of());
        UIWindow window = (UIWindow) open[0];
        CompletionPopup popup = (CompletionPopup) open[1];
        CompletionSession session = (CompletionSession) open[2];

        assertSame(popup, window.getTopCloseWatcher());

        assertTrue("nothing claimed the close", popup.requestClose());
        assertTrue("the SESSION must close, not just the box -- it owns Enter, Tab and the arrows",
                session.isClosed());
    }

    /**
     * <b>A list with nothing in it takes up no space.</b>
     *
     * <p>An empty answer that is still flagged incomplete keeps the session alive on purpose — narrowing
     * may reach rows the provider never sent — but there is nothing to put on screen meanwhile. What
     * showed was a bare hint strip floating over the line being typed: a box offering nothing, sitting on
     * the text, that looked like the editor had stuck.</p>
     *
     * <p>Asserted on the box's measured size rather than on its {@code display}, because
     * {@code getComputed} answers null for a property nothing has written — so a test asking "what is its
     * display" passes by accident on a popup that was never styled at all.</p>
     */
    @Test
    public void anEmptyListTakesUpNoSpace() {
        Object[] open = openList(List.of());
        CompletionPopup popup = (CompletionPopup) open[1];

        assertEquals("an empty list still drew a box over the editor",
                0f, popup.getRuntimeCache().getHeight(), 0.5f);
    }

    /** ...and one with rows does, which is the counter-assertion. */
    @Test
    public void aListWithRowsStillTakesUpSpace() {
        Object[] open = openList(List.of(item("alpha"), item("beta")));
        CompletionPopup popup = (CompletionPopup) open[1];

        assertTrue("a list with rows drew nothing at all",
                popup.getRuntimeCache().getHeight() > 0f);
    }

    /**
     * <b>Closing takes it off both stacks.</b>
     *
     * <p>Or the window keeps asking a dead popup for Escape, and the next press outside dismisses
     * something that is not there — which is how a stack that is only ever pushed to fails.</p>
     */
    @Test
    public void closingTakesItOffBothStacks() {
        Object[] open = openList(List.of(item("alpha")));
        UIWindow window = (UIWindow) open[0];
        CompletionPopup popup = (CompletionPopup) open[1];

        popup.requestClose();
        for (int i = 0; i < 2; i++) window.updateWithoutPainting();

        assertFalse("still registered for Escape after closing",
                popup == window.getTopCloseWatcher());
        assertFalse("still light-dismissable after closing", window.getAutoPopovers().contains(popup));
    }

    /**
     * <b>A press outside closes it; a press INSIDE does not.</b>
     *
     * <p>The second half is what makes this safe to register at all: accepting a row is a press, and it
     * lands inside the popup. Light dismiss spares the popover the press was inside, which is the same
     * rule that lets a menu keep itself open while closing its submenus.</p>
     */
    @Test
    public void aPressInsideSurvivesAndAPressOutsideDismisses() {
        Object[] open = openList(List.of(item("alpha"), item("beta")));
        UIWindow window = (UIWindow) open[0];
        CompletionPopup popup = (CompletionPopup) open[1];
        CompletionSession session = (CompletionSession) open[2];
        UIElement root = (UIElement) open[3];

        window.lightDismiss(popup);
        assertFalse("clicking the list closed the list", session.isClosed());

        window.lightDismiss(root);
        assertTrue("a press outside the list left it open", session.isClosed());
    }
}
