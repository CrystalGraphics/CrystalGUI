package com.crystalgui.ui.dom;

import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A class change re-matches the descendants a rule reaches through that class, and only those.
 *
 * <p>Toggling {@code .active} on a window re-matched all of its elements; the sheets name a handful that
 * can depend on it. Both halves are pinned: a keyed descendant must still restyle, and one no rule keys on
 * must not be re-matched.</p>
 */
public class ClassInvalidationTest extends UiDocumentTestBase {

    private static final String SHEET = ""
            + ".__title__ { background-color: #00FF00; }\n"
            + ".__win__.__active__ .__title__ { background-color: #FF0000; }\n"
            + ".__body__ { background-color: #0000FF; }\n"
            + ".__win__.__active__ { color: #FF0000; }\n"
            + ".__all__ * { background-color: #FFFFFF; }\n";

    private UIElement window;
    private UIElement title;
    private UIElement body;
    private UIElement label;

    private void build() {
        window = new UIElement().addClass("__win__");
        title = new UIElement().addClass("__title__");
        body = new UIElement().addClass("__body__");
        label = new UIElement();
        body.append(label);
        window.append(title);
        window.append(body);

        UIElement root = new UIElement().layout(l -> l.width(200).height(200));
        root.append(window);
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.parse(SHEET));
        frame();
    }

    private static int background(UIElement element) {
        Integer value = element.getStyle().getComputed(StylePropertyRegistry.BACKGROUND_COLOR);
        return value == null ? 0 : value;
    }

    @Test
    public void aDescendantKeyedThroughTheClassStillRestyles() {
        build();
        assertEquals(0xFF00FF00, background(title));

        window.addClass("__active__");
        frame();
        assertEquals("activating did not reach the title -- the invalidation set is too narrow",
                0xFFFF0000, background(title));

        window.removeClass("__active__");
        frame();
        assertEquals("deactivating did not reach it either", 0xFF00FF00, background(title));
    }

    @Test
    public void aDescendantNoRuleKeysOnIsNotReMatched() {
        build();
        document.styleEngine().resetRematchCountForTesting();

        window.addClass("__active__");
        frame();

        assertTrue(document.styleEngine().rematchedForTesting().contains(window));
        assertTrue(document.styleEngine().rematchedForTesting().contains(title));
        assertFalse("the body is keyed by no rule through __active__", document.styleEngine().rematchedForTesting().contains(body));
    }

    @Test
    public void anInheritedValueStillReachesAnUnmarkedDescendant() {
        build();
        window.addClass("__active__");
        frame();
        assertEquals("the label inherits the window's colour without being re-matched",
                0xFFFF0000, (int) label.getStyle().getComputed(StylePropertyRegistry.COLOR));
    }

    @Test
    public void aJoiningSubtreeIsMatchedOnce() {
        build();
        document.styleEngine().addStylesheet(StyleSheet.parse("* { font-size: 10; }\n"));
        frame();
        UIElement popup = new UIElement();
        for (int i = 0; i < 20; i++) popup.append(new UIElement().addClass("__row__"));
        document.styleEngine().resetRematchCountForTesting();

        document.append(popup);
        frame();

        // Its first font-size resolution re-matched the whole subtree a second time.
        assertEquals("each joining element is matched once", 21,
                document.styleEngine().rematchedForTesting().stream().filter(e -> e == popup || popup.children().contains(e)).count());
    }

    @Test
    public void aFontSizeChangeStillReachesItsSubtree() {
        build();
        document.styleEngine().addStylesheet(StyleSheet.parse("* { font-size: 10; }\n.__big__ { font-size: 20; }\n"));
        frame();
        document.styleEngine().resetRematchCountForTesting();

        window.addClass("__big__");
        frame();

        assertTrue("an em below the window was resolved against the old size",
                document.styleEngine().rematchedForTesting().contains(label));
    }

    @Test
    public void aClassReachingAnUnkeyedSubjectReMatchesEverything() {
        build();
        document.styleEngine().resetRematchCountForTesting();

        window.addClass("__all__");
        frame();

        assertTrue(document.styleEngine().rematchedForTesting().contains(label));
        assertEquals(0xFFFFFFFF, background(body));
    }
}
