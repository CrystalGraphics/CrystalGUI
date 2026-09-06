package com.crystalgui.widget.layout;

import com.crystalgui.ui.dom.UIElement;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * SplitView's percentage/limit maths and its child-slot contract.
 *
 * <p>Exercised through the real element rather than a helper: constructing a {@code SplitView}
 * touches only layout/style state (no GL), so the actual behaviour is under test rather than a
 * reimplementation that could drift. Dragging lives in {@code SplitViewDragTest}, which needs a real
 * window and input handler.</p>
 */
public class SplitViewTest {

    private static final float EPS = 0.0001f;

    @Test
    public void defaultsToAnEvenSplit() {
        assertEquals(50f, new SplitView().getPercentage(), EPS);
    }

    /** LDLib2's defaults — the divider can't be dragged fully off either end. */
    @Test
    public void percentageIsClampedToTheDefaultLimits() {
        SplitView sv = new SplitView();
        sv.setPercentage(0f);
        assertEquals(5f, sv.getPercentage(), EPS);
        sv.setPercentage(100f);
        assertEquals(95f, sv.getPercentage(), EPS);
    }

    @Test
    public void limitsAreConfigurableAndReclampTheCurrentSplit() {
        SplitView sv = new SplitView();
        sv.setPercentage(90f);
        sv.setLimits(20f, 60f);
        assertEquals(60f, sv.getPercentage(), EPS);
    }

    /** An inverted range must not produce a max below the min. */
    @Test
    public void invertedLimitsAreNormalised() {
        SplitView sv = new SplitView().setLimits(70f, 30f);
        assertTrue("max " + sv.getMaxPercentage() + " < min " + sv.getMinPercentage(),
                sv.getMaxPercentage() >= sv.getMinPercentage());
    }

    @Test
    public void signalsOnlyOnAnActualChange() {
        SplitView sv = new SplitView();
        int[] fired = {0};
        sv.attachListener(p -> fired[0]++);

        sv.setPercentage(30f);
        assertEquals(1, fired[0]);
        sv.setPercentage(30f);              // same value
        assertEquals(1, fired[0]);
        sv.setPercentage(200f);             // clamps to 95 — a real change
        assertEquals(2, fired[0]);
        sv.setPercentage(150f);             // clamps to 95 again — no change
        assertEquals(2, fired[0]);
    }

    // ── Child-slot contract ─────────────────────────────────────────────────

    /** The root owns a fixed three-child structure, so it must refuse arbitrary children — the
     * standing rule that only elements designed to host content may accept it. */
    @Test
    public void rootRejectsPublicChildren() {
        SplitView sv = new SplitView();
        assertThrows(UnsupportedOperationException.class, () -> sv.append(new UIElement()));
    }

    /** ...but the panes are ordinary elements and must accept content normally. That's the whole
     * point of the widget, and what distinguishes it from Button/Checkbox/Slider. */
    @Test
    public void panesAcceptChildren() {
        SplitView sv = new SplitView();
        UIElement a = new UIElement();
        UIElement b = new UIElement();

        sv.first().append(a);
        sv.second().append(b);

        assertTrue(sv.first().children().contains(a));
        assertTrue(sv.second().children().contains(b));
    }

    /** Content added to a pane after construction is NOT internal, so it stays publicly removable —
     * markAsInternal only covers the subtree present when the pane itself was adopted. */
    @Test
    public void paneContentRemainsRemovable() {
        SplitView sv = new SplitView();
        UIElement content = new UIElement();
        sv.first().append(content);

        assertTrue(sv.first().remove(content));
        assertFalse(sv.first().children().contains(content));
    }

    /** The panes themselves are internal, so clearing a pane's content must never remove the pane. */
    @Test
    public void clearingAPaneDoesNotDetachTheStructure() {
        SplitView sv = new SplitView();
        UIElement first = sv.first();
        sv.first().append(new UIElement());

        sv.first().removeAll();

        assertSame("the pane itself must survive", first, sv.first());
        assertTrue(sv.first().children().isEmpty());
    }

    @Test
    public void firstAndSecondReplaceRatherThanAppend() {
        SplitView sv = new SplitView();
        UIElement original = new UIElement();
        UIElement replacement = new UIElement();

        sv.first(original).first(replacement);

        assertEquals(1, sv.first().children().size());
        assertTrue(sv.first().children().contains(replacement));
        assertFalse(sv.first().children().contains(original));
    }

    // ── Orientation ─────────────────────────────────────────────────────────

    @Test
    public void defaultsToHorizontal() {
        SplitView sv = new SplitView();
        assertEquals(SplitView.Orientation.HORIZONTAL, sv.getOrientation());
        assertFalse(sv.hasClass(SplitView.VERTICAL_CLASS));
    }

    /** The marker class is the entire CSS-facing side of orientation, so it has to track both ways. */
    @Test
    public void verticalMarkerClassTracksTheOrientation() {
        SplitView sv = new SplitView();

        sv.setOrientation(SplitView.Orientation.VERTICAL);
        assertTrue(sv.hasClass(SplitView.VERTICAL_CLASS));

        sv.setOrientation(SplitView.Orientation.HORIZONTAL);
        assertFalse(sv.hasClass(SplitView.VERTICAL_CLASS));
    }

    /** Switching orientation must not disturb where the split sits. */
    @Test
    public void orientationChangePreservesThePercentage() {
        SplitView sv = new SplitView();
        sv.setPercentage(35f);
        sv.setOrientation(SplitView.Orientation.VERTICAL);
        assertEquals(35f, sv.getPercentage(), EPS);
    }
}
