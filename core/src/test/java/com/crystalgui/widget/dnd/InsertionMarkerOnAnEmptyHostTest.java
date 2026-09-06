package com.crystalgui.widget.dnd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;

/**
 * <b>A list with nothing in it still shows where the drop would land.</b>
 *
 * <p>The marker reads everything about its placement off a NEIGHBOUR — the size, the cross-axis position,
 * the step past the last item — so with none it hid. Dragging a rail's only button therefore offered no
 * marker at all, and neither did dragging one onto an empty rail, which is the case where a person most
 * needs telling that the rail is a target: there is nothing else in it to suggest that it is one.
 * IntelliJ draws the placeholder on an empty stripe for that reason.</p>
 *
 * <p><b>Both modes are covered here, and that is the point of the file.</b> The first version tested
 * {@link InsertionMarker.Mode#OVERLAY} only — the class default — while the consumer that reported the
 * bug is {@link InsertionMarker.Mode#IN_FLOW}. It passed against a build where the rail still showed
 * nothing, because the two modes reached the empty case through different code and only one of them had
 * been repaired. A test whose fixture differs from the consumer in a mode flag is a test that can be
 * green about the wrong path.</p>
 */
public class InsertionMarkerOnAnEmptyHostTest extends UiDocumentTestBase {

    private static final float BUTTON = 16f;
    /** Wider than the buttons in it, exactly as an activity bar is — see {@link #theSlotIsNotTheHostsSize}. */
    private static final float RAIL = 20f;

    private UIElement rail;
    private InsertionMarker marker;

    /** A vertical rail holding {@code count} buttons, with the marker parked in it. */
    private List<UIElement> railOf(InsertionMarker.Mode mode, int count) {
        rail = new UIElement().layout(l -> l.width(RAIL).height(400f));
        List<UIElement> buttons = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UIElement button = new UIElement().layout(l -> l.width(BUTTON).height(BUTTON));
            rail.append(button);
            buttons.add(button);
        }
        marker = new InsertionMarker(InsertionMarker.Axis.VERTICAL).mode(mode);
        marker.parkIn(rail);
        document.append(rail);
        frame();
        return buttons;
    }

    private void assertSquare(String what, float expected) {
        assertNotNull(what + ": the marker has no box, so there is nothing on screen", marker.box());
        assertEquals(what + ": wrong width", expected, marker.box().width(), 0.5f);
        assertEquals(what + ": wrong height", expected, marker.box().height(), 0.5f);
    }

    // ── IN_FLOW — what a stripe rail actually uses ──────────────────────────────────────────────

    /**
     * Dragging a rail's only button leaves a slot where it was.
     *
     * <p>The list the rail hands over is its own minus the button being carried, so a group of one arrives
     * <b>empty</b>. {@code showAt} used to hide on that ahead of everything else.</p>
     */
    @Test
    public void draggingTheOnlyButtonStillShowsASlot() {
        List<UIElement> buttons = railOf(InsertionMarker.Mode.IN_FLOW, 1);
        marker.withdraw(rail, buttons, buttons.get(0));
        frame();

        marker.showAt(rail, List.of(), 0);
        frame();

        assertEquals("the slot is not where the button would sit", 0, marker.index());
        assertSquare("a group whose only member is being carried", BUTTON);
    }

    /**
     * ...and a drag from ANOTHER rail shows one too, at the size that rail states.
     *
     * <p>The target rail withdrew nothing — the source did — so there is neither a carried item nor a
     * neighbour to measure here. The size travels instead: {@code emptySlotSize} is what the rail passes
     * on from whichever rail is carrying the button.</p>
     */
    @Test
    public void aDragFromElsewhereShowsASlotAtTheStatedSize() {
        railOf(InsertionMarker.Mode.IN_FLOW, 0);
        marker.emptySlotSize(BUTTON, BUTTON);

        marker.showAt(rail, List.of(), 0);
        frame();

        assertEquals("the slot is not where the first button would sit", 0, marker.index());
        assertSquare("an empty rail told what is being carried", BUTTON);
    }

    /**
     * <b>The slot is the size of the BUTTON, never of the rail holding it.</b>
     *
     * <p>The host's own cross size is the tempting substitute and it is wrong by the padding that centres
     * the buttons — an activity bar is 20px wide and its buttons are 16, so a slot taken from the
     * container reads as a larger object than the one being carried. Reported exactly that way: "the
     * insertion marker is bigger than it's supposed to be".</p>
     */
    @Test
    public void theSlotIsNotTheHostsSize() {
        railOf(InsertionMarker.Mode.IN_FLOW, 0);
        marker.emptySlotSize(BUTTON, BUTTON);
        marker.showAt(rail, List.of(), 0);
        frame();

        assertTrue("the slot took the rail's width (" + RAIL + ") rather than the button's",
                marker.box().width() < RAIL);
    }

    /**
     * A group that is not at the top of its container puts its slot where the group is.
     *
     * <p>A rail's children are not all buttons: a stretch sits between its two groups, so the bottom
     * group's first button belongs after it. Only the caller knows which of its own children mark that
     * boundary, which is why {@code emptySlotAfter} exists at all — with no items there is no neighbour to
     * read it from, and the start of the container is the wrong end of the rail.</p>
     */
    @Test
    public void anEmptyGroupSitsWhereItsCallerSaysItDoes() {
        railOf(InsertionMarker.Mode.IN_FLOW, 0);
        UIElement stretch = new UIElement().layout(l -> l.width(RAIL).height(200f));
        rail.append(stretch);
        frame();

        marker.emptySlotAfter(stretch).emptySlotSize(BUTTON, BUTTON);
        marker.showAt(rail, List.of(), 0);
        frame();

        assertEquals("the slot did not land after the stretch, so the bottom group's placeholder is at "
                + "the top of the rail", rail.indexOf(stretch) + 1, rail.indexOf(marker));
        assertTrue("the slot is above the stretch it was told to follow",
                marker.box().y() >= stretch.box().y() + stretch.box().height() - 0.5f);
    }

    /**
     * The counter-control: with nothing carried and no size stated there is nothing to describe.
     *
     * <p>Without it a marker that drew a slot on any empty host would satisfy every case above, and every
     * list in the application would grow a phantom cell the moment it emptied.</p>
     */
    @Test
    public void anEmptyHostWithNothingCarriedShowsNoSlot() {
        railOf(InsertionMarker.Mode.IN_FLOW, 0);
        marker.showAt(rail, List.of(), 0);
        frame();

        assertEquals("a marker with nothing to describe claimed an insertion index", -1, marker.index());
        assertTrue("a slot was drawn for a drag that is carrying nothing",
                marker.box() == null || marker.box().height() <= 0f);
    }

    // ── OVERLAY — the tab strip's mode, through the same door ───────────────────────────────────

    /** The same repair on the absolutely-positioned path, which reaches it from the other branch. */
    @Test
    public void theOverlayPathAlsoDrawsASlotOnAnEmptyList() {
        List<UIElement> buttons = railOf(InsertionMarker.Mode.OVERLAY, 1);
        marker.withdraw(rail, buttons, buttons.get(0));
        frame();

        // The real list, with the carried item still in it -- `ordered` is what empties it here.
        marker.showAt(rail, buttons, 0);
        frame();

        assertEquals("the slot is not where the item would sit", 0, marker.index());
        assertNotNull("the marker has no box, so there is nothing on screen", marker.box());
        assertEquals("the slot is not the width of the item being carried", BUTTON,
                marker.box().width(), 0.5f);
        // Height minus the gap the marker leaves around itself, which the neighbour path applies too.
        assertTrue("the slot is not about the height of the item being carried: "
                + marker.box().height(), Math.abs(marker.box().height() - BUTTON) <= 4f);
    }
}
