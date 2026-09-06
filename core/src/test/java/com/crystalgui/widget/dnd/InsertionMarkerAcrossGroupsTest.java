package com.crystalgui.widget.dnd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;

/**
 * <b>One marker serves several lists, so "has the gap moved" is a question about the NEIGHBOUR.</b>
 *
 * <p>A stripe rail holds two groups — a top run and a bottom one, with a stretch between them — and each
 * is a list of its own numbered from zero. {@link InsertionMarker} re-parented only when the insertion
 * INDEX changed, which cannot tell those apart: dragging from the top group's first slot to the bottom
 * group's first slot is index 0 to index 0, so the guard read it as "nothing changed" and left the gap in
 * the group you had left. The drop preview said <i>Move to Bottom Right</i> while the placeholder sat at
 * the top of the rail.</p>
 *
 * <p>The element it is placed against is unambiguous, so that is what is remembered — with the direction,
 * because the last slot of one group and the first of the next resolve to the same anchor.</p>
 */
public class InsertionMarkerAcrossGroupsTest extends UiDocumentTestBase {

    private static final float BUTTON = 16f;

    private UIElement rail;
    private UIElement stretch;
    private InsertionMarker marker;
    /** The top run, then the bottom one — the two lists the single marker has to tell apart. */
    private final List<UIElement> top = new ArrayList<>();
    private final List<UIElement> bottom = new ArrayList<>();

    private UIElement button() {
        UIElement button = new UIElement().layout(l -> l.width(BUTTON).height(BUTTON));
        rail.append(button);
        return button;
    }

    @Before
    public void buildRail() {
        rail = new UIElement().layout(l -> l.width(20f).height(400f));
        marker = new InsertionMarker(InsertionMarker.Axis.VERTICAL)
                .mode(InsertionMarker.Mode.IN_FLOW);
        // PARKED FIRST, as a rail does -- which is what puts it at the top of the container and makes a
        // gap that never moves look exactly like a gap placed at the top group's first slot.
        marker.parkIn(rail);
        top.add(button());
        top.add(button());
        stretch = new UIElement().layout(l -> l.width(20f).height(120f));
        rail.append(stretch);
        bottom.add(button());
        document.append(rail);
        frame();
    }

    /** Where the gap sits among the rail's children. */
    private int gapAt() {
        return rail.indexOf(marker);
    }

    @Test
    public void theGapMovesBetweenTwoGroupsAtTheSameIndex() {
        marker.showAt(rail, top, 0);
        frame();
        assertTrue("the gap did not land in the top group", gapAt() < rail.indexOf(stretch));

        // THE SAME INDEX IN THE OTHER LIST -- the case an index comparison cannot see.
        marker.showAt(rail, bottom, 0);
        frame();

        assertTrue("the gap stayed in the top group while the drop was aimed at the bottom one",
                gapAt() > rail.indexOf(stretch));
        assertEquals("the gap forgot which slot of its new group it is in", 0, marker.index());
    }

    /** ...and back, which a one-way fix would leave broken. */
    @Test
    public void theGapMovesBackToTheGroupItCameFrom() {
        marker.showAt(rail, bottom, 0);
        frame();
        marker.showAt(rail, top, 0);
        frame();

        assertTrue("the gap stayed in the bottom group", gapAt() < rail.indexOf(stretch));
    }

    /**
     * A group that has EMPTIED is the same question with no neighbour left in it.
     *
     * <p>Dragging the bottom group's only button aims at a list that arrives empty, and the gap has to
     * leave the top group for a place the caller has to name — see {@code emptySlotAfter}.</p>
     */
    @Test
    public void theGapMovesFromAPopulatedGroupToAnEmptyOne() {
        marker.showAt(rail, top, 0);
        frame();

        marker.emptySlotAfter(stretch).emptySlotSize(BUTTON, BUTTON);
        marker.showAt(rail, List.of(), 0);
        frame();

        assertTrue("the gap stayed in the top group while the drop was aimed at an emptied bottom one",
                gapAt() > rail.indexOf(stretch));
    }

    /**
     * A hidden gap keeps its DOM slot, so a re-show has to place itself again from scratch.
     *
     * <p>{@code hide()} is {@code display: none} rather than a detach — deliberately, since taking it out
     * is a structural change to a subtree a drag is live over. So the gap stays parented where it was, and
     * the container is free to move it in the meantime: a rail re-derives its whole arrangement whenever a
     * button changes group, re-appending every button around a marker that is not in that list. What the
     * marker remembers is then true about an anchor that has moved, and a guard trusting it leaves the gap
     * wherever the reorder left it — at the foot of the rail, in this fixture.</p>
     */
    @Test
    public void aGapShownAgainAfterItsContainerWasReorderedPlacesItselfFresh() {
        marker.showAt(rail, top, 0);
        frame();
        marker.hide();
        frame();

        // WHAT A RAIL'S OWN reorder() DOES to a child it does not know about.
        rail.remove(marker);
        rail.append(marker);
        frame();

        marker.showAt(rail, top, 0);
        frame();

        assertTrue("the gap stayed where the reorder left it rather than returning to the slot it was "
                + "shown at: " + gapAt(), gapAt() < rail.indexOf(top.get(0)));
    }

    /**
     * The counter-control: moving WITHIN one group still moves the gap.
     *
     * <p>A fix that simply re-parented on every call would pass everything above; so would one that never
     * re-parented at all, if the only assertions were about groups. This is the ordinary reorder the
     * widget exists for, and it has to keep working.</p>
     */
    @Test
    public void theGapStillMovesWithinOneGroup() {
        marker.showAt(rail, top, 0);
        frame();
        int first = gapAt();

        marker.showAt(rail, top, 2);
        frame();

        assertTrue("the gap did not move past the group's last item: " + first + " -> " + gapAt(),
                gapAt() > first);
        assertEquals("the gap is not reporting the slot it was shown at", 2, marker.index());
    }
}
