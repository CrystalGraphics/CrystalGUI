package com.crystalgui.widget.scroll;

import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UINode;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.widget.scroll.ScrollerView;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.FlexDirection;
import com.crystalgui.testsupport.UiDocumentTestBase;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * {@code scrollIntoView}, and focus revealing its target.
 *
 * <p>Focus that lands somewhere invisible is focus the user can't see, so anything that isn't a click
 * scrolls its target into view — and always instantly, even under {@code scroll-behavior: smooth},
 * because the element needs to be there by the time they look rather than gliding in afterwards.
 * Clicking is excluded: you clicked what you could already see, and scrolling then would drag the
 * content out from under the cursor.</p>
 */
public class ScrollIntoViewTest extends UiDocumentTestBase {

    private static final float VIEWPORT = 100f;
    private static final float ROW_H = 40f;
    private static final int ROWS = 6;               // 240px of content in a 100px box

    private ScrollerView view;

    @Before
    public void registerStubAdapter() {
        build();
    }

    /** A scroll view of focusable rows, most of them below the fold. */
    private void build() {
        view = new ScrollerView();
        view.layout(l -> l.width(120).height(VIEWPORT).flexDirection(FlexDirection.COLUMN));
        for (int i = 0; i < ROWS; i++) {
            UINode row = new UINode().layout(l -> l.width(120).height(ROW_H));
            row.setFocusPolicy(FocusPolicy.FOCUSABLE);
            view.append(row);
        }

        UINode root = new UINode().layout(l -> l.width(400).height(300));
        root.append(view);

        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        frame();
        view.refreshScrollers();
        frame();
    }


    private UINode row(int index) {
        return view.children().stream()
                .filter(c -> !c.isScrollExempt())
                .toList().get(index);
    }

    /** Whether a row is fully inside the viewport, in the container's scroll space. */
    private boolean isFullyVisible(UINode element) {
        float relTop = element.box().y() - view.box().y();
        float relBottom = relTop + element.box().height();
        return relTop >= view.scrollTop() - 0.5f
                && relBottom <= view.scrollTop() + view.box().clientHeight() + 0.5f;
    }

    // ── scrollIntoView itself ───────────────────────────────────────────────

    @Test
    public void scrollsAnOffScreenElementIntoView() {
        UINode last = row(ROWS - 1);
        assertFalse("precondition: the last row should start off-screen", isFullyVisible(last));

        last.box().scrollIntoView();
        assertTrue("scrollIntoView did not reveal the element", isFullyVisible(last));
    }

    /** Minimum distance: an element just below the fold comes flush to the bottom, not centred. */
    @Test
    public void scrollsTheMinimumDistance() {
        UINode third = row(2);   // occupies 80..120 in a 0..100 viewport
        third.box().scrollIntoView();

        assertEquals("should scroll just far enough to expose the row's bottom edge",
                3 * ROW_H - VIEWPORT, view.scrollTop(), 0.5f);
    }

    /** An element already visible must not move the view at all. */
    @Test
    public void alreadyVisibleElementDoesNotScroll() {
        view.box().setScroll(0f, 0f);
        row(0).box().scrollIntoView();
        assertEquals(0f, view.scrollTop(), 0.5f);
    }

    /** Scrolling back up to something above the fold works too, not just downward. */
    @Test
    public void scrollsBackUpwardToAnElementAboveTheView() {
        view.box().setScroll(0f, view.box().maxScrollTop());
        assertFalse(isFullyVisible(row(0)));

        row(0).box().scrollIntoView();
        assertEquals("should land exactly at the top", 0f, view.scrollTop(), 0.5f);
    }

    // ── Focus ───────────────────────────────────────────────────────────────

    /** Programmatic focus reveals its target — the headline behaviour. */
    @Test
    public void programmaticFocusScrollsTheTargetIntoView() {
        UINode last = row(ROWS - 1);
        assertFalse(isFullyVisible(last));

        document.focus().requestFocus(last);

        assertSame(last, document.focus().focused());
        assertTrue("focusing an off-screen element must reveal it", isFullyVisible(last));
    }

    /** And instantly — a smooth scroll would leave it invisible for the first few frames, which is
     * exactly what this must not do. */
    @Test
    public void focusScrollIsInstantNotEased() {
        UINode last = row(ROWS - 1);
        document.focus().requestFocus(last);

        assertEquals("the scroll must already be at its destination, not easing toward it",
                view.getTargetScrollTop(), view.scrollTop(), 0.5f);
        assertTrue(view.scrollTop() > 0f);
    }

    /** Tab traversal is keyboard-driven, so it reveals its target too. */
    @Test
    public void tabTraversalScrollsTheTargetIntoView() {
        document.focus().requestFocus(row(0));
        view.box().setScroll(0f, 0f);

        // Tab down to a row past the fold.
        for (int i = 0; i < 4; i++) {
            document.input().consumeKeyboardEvent(
                    new CgSystemInput.Keyboard.Event('\t', CgKeyCodes.KEY_TAB, true, false, 0L));
            document.input().consumeKeyboardEvent(
                    new CgSystemInput.Keyboard.Event('\t', CgKeyCodes.KEY_TAB, false, false, 0L));
            frame();
        }

        UINode focused = document.focus().focused();
        assertNotNull(focused);
        assertTrue("tabbing below the fold must scroll the new target into view",
                isFullyVisible(focused));
    }

    /** Clicking must NOT scroll: you clicked what you could see, and moving the content would pull it
     * out from under the cursor. */
    @Test
    public void clickingDoesNotScroll() {
        view.box().setScroll(0f, 0f);
        UINode first = row(0);
        first.setFocusPolicy(FocusPolicy.CLICK);
        frame();

        var c = first.box();
        int px = Math.round((c.x() + 10f) * 2f);
        int py = Math.round((c.y() + 10f) * 2f);

        document.input().consumeMouseEvent(
                new CgSystemInput.Mouse.Event(px, py, 0, 0, -1, false, 0f, -1L));
        frame();
        document.input().consumeMouseEvent(
                new CgSystemInput.Mouse.Event(px, py, 0, 0, 0, true, 0f, System.currentTimeMillis()));
        frame();

        assertEquals("a click must never scroll the view", 0f, view.scrollTop(), 0.5f);
    }

    /** Focusing something unfocusable is a no-op, so callers needn't check first. */
    @Test
    public void requestingFocusOnAnUnfocusableElementDoesNothing() {
        UINode plain = row(1);
        plain.setFocusPolicy(FocusPolicy.NONE);
        view.box().setScroll(0f, 0f);

        document.focus().requestFocus(plain);

        assertNull(document.focus().focused());
        assertEquals(0f, view.scrollTop(), 0.5f);
    }
}
