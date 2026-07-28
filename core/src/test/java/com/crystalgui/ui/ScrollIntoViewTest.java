package com.crystalgui.ui;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.input.CgUiInputAdapter;
import com.crystalgui.core.input.SystemInput;
import com.crystalgui.core.input.keyboard.CgUiKeyCodes;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.elements.ScrollerView;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.FlexDirection;
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
public class ScrollIntoViewTest {

    private static final float VIEWPORT = 100f;
    private static final float ROW_H = 40f;
    private static final int ROWS = 6;               // 240px of content in a 100px box

    private UIWindow window;
    private ScrollerView view;

    @Before
    public void registerStubAdapter() {
        CrystalGuiCore.setAdapter(new CgUiInputAdapter() {
            @Override public int getCurrentModifiers() { return 0; }
            @Override public int translateKeyboardCodes(int platformCode) { return platformCode; }
            @Override public boolean isKeyDown(int localKeyCode) { return false; }
            @Override public boolean isMouseDown(int localMouseCode) { return false; }
            @Override public int howManyMouseButtons() { return 3; }
        });
        build();
    }

    /** A scroll view of focusable rows, most of them below the fold. */
    private void build() {
        view = new ScrollerView();
        view.layout(l -> l.width(120).height(VIEWPORT).flexDirection(FlexDirection.COLUMN));
        for (int i = 0; i < ROWS; i++) {
            UIElement row = new UIElement().layout(l -> l.width(120).height(ROW_H));
            row.setFocusPolicy(FocusPolicy.FOCUSABLE);
            view.addChild(row);
        }

        UIElement root = new UIElement().layout(l -> l.width(400).height(300));
        root.addChild(view);

        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 600);
        frame();
        view.refreshScrollers();
        frame();
    }

    private void frame() {
        window.getStyleEngine().calculateStyle(0.016f);
        window.tickAnimations(0.016f);
        window.calculateLayout();
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
    }

    private UIElement row(int index) {
        return view.getChildren().stream()
                .filter(c -> !c.isScrollExempt())
                .toList().get(index);
    }

    /** Whether a row is fully inside the viewport, in the container's scroll space. */
    private boolean isFullyVisible(UIElement element) {
        float relTop = element.getRuntimeCache().getY() - view.getRuntimeCache().getY();
        float relBottom = relTop + element.getRuntimeCache().getHeight();
        return relTop >= view.getScrollTop() - 0.5f
                && relBottom <= view.getScrollTop() + view.getClientHeight() + 0.5f;
    }

    // ── scrollIntoView itself ───────────────────────────────────────────────

    @Test
    public void scrollsAnOffScreenElementIntoView() {
        UIElement last = row(ROWS - 1);
        assertFalse("precondition: the last row should start off-screen", isFullyVisible(last));

        last.scrollIntoView();
        assertTrue("scrollIntoView did not reveal the element", isFullyVisible(last));
    }

    /** Minimum distance: an element just below the fold comes flush to the bottom, not centred. */
    @Test
    public void scrollsTheMinimumDistance() {
        UIElement third = row(2);   // occupies 80..120 in a 0..100 viewport
        third.scrollIntoView();

        assertEquals("should scroll just far enough to expose the row's bottom edge",
                3 * ROW_H - VIEWPORT, view.getScrollTop(), 0.5f);
    }

    /** An element already visible must not move the view at all. */
    @Test
    public void alreadyVisibleElementDoesNotScroll() {
        view.setScrollImmediate(0f, 0f);
        row(0).scrollIntoView();
        assertEquals(0f, view.getScrollTop(), 0.5f);
    }

    /** Scrolling back up to something above the fold works too, not just downward. */
    @Test
    public void scrollsBackUpwardToAnElementAboveTheView() {
        view.setScrollImmediate(0f, view.getMaxScrollTop());
        assertFalse(isFullyVisible(row(0)));

        row(0).scrollIntoView();
        assertEquals("should land exactly at the top", 0f, view.getScrollTop(), 0.5f);
    }

    // ── Focus ───────────────────────────────────────────────────────────────

    /** Programmatic focus reveals its target — the headline behaviour. */
    @Test
    public void programmaticFocusScrollsTheTargetIntoView() {
        UIElement last = row(ROWS - 1);
        assertFalse(isFullyVisible(last));

        window.getInputHandler().requestFocus(last);

        assertSame(last, window.getInputHandler().getFocusedElement());
        assertTrue("focusing an off-screen element must reveal it", isFullyVisible(last));
    }

    /** And instantly — a smooth scroll would leave it invisible for the first few frames, which is
     * exactly what this must not do. */
    @Test
    public void focusScrollIsInstantNotEased() {
        UIElement last = row(ROWS - 1);
        window.getInputHandler().requestFocus(last);

        assertEquals("the scroll must already be at its destination, not easing toward it",
                view.getTargetScrollTop(), view.getScrollTop(), 0.5f);
        assertTrue(view.getScrollTop() > 0f);
    }

    /** Tab traversal is keyboard-driven, so it reveals its target too. */
    @Test
    public void tabTraversalScrollsTheTargetIntoView() {
        window.getInputHandler().requestFocus(row(0));
        view.setScrollImmediate(0f, 0f);

        // Tab down to a row past the fold.
        for (int i = 0; i < 4; i++) {
            window.getInputHandler().consumeKeyboardEvent(
                    new SystemInput.Keyboard.Event('\t', CgUiKeyCodes.KEY_TAB, true, false, 0L));
            window.getInputHandler().consumeKeyboardEvent(
                    new SystemInput.Keyboard.Event('\t', CgUiKeyCodes.KEY_TAB, false, false, 0L));
            frame();
        }

        UIElement focused = window.getInputHandler().getFocusedElement();
        assertNotNull(focused);
        assertTrue("tabbing below the fold must scroll the new target into view",
                isFullyVisible(focused));
    }

    /** Clicking must NOT scroll: you clicked what you could see, and moving the content would pull it
     * out from under the cursor. */
    @Test
    public void clickingDoesNotScroll() {
        view.setScrollImmediate(0f, 0f);
        UIElement first = row(0);
        first.setFocusPolicy(FocusPolicy.CLICK);
        frame();

        var c = first.getRuntimeCache();
        int px = Math.round((c.getX() + 10f) * 2f);
        int py = Math.round((c.getY() + 10f) * 2f);

        window.getInputHandler().consumeMouseEvent(
                new SystemInput.Mouse.Event(px, py, 0, 0, -1, false, 0f, -1L));
        frame();
        window.getInputHandler().consumeMouseEvent(
                new SystemInput.Mouse.Event(px, py, 0, 0, 0, true, 0f, System.currentTimeMillis()));
        frame();

        assertEquals("a click must never scroll the view", 0f, view.getScrollTop(), 0.5f);
    }

    /** Focusing something unfocusable is a no-op, so callers needn't check first. */
    @Test
    public void requestingFocusOnAnUnfocusableElementDoesNothing() {
        UIElement plain = row(1);
        plain.setFocusPolicy(FocusPolicy.NONE);
        view.setScrollImmediate(0f, 0f);

        window.getInputHandler().requestFocus(plain);

        assertNull(window.getInputHandler().getFocusedElement());
        assertEquals(0f, view.getScrollTop(), 0.5f);
    }
}
