package com.crystalgui.widget.dnd;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIElement;

import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;

/**
 * <b>A list that wraps is several lines, and a drop lands in the line the pointer is on.</b>
 *
 * <p>A tab strip in multiple-rows mode. Along the axis alone, every line has an item under the pointer, so the index
 * answered for whichever line sorted first; and the in-flow gap, moving tabs between lines, moved the answer with it.</p>
 */
public class InsertionMarkerWrappedTest extends UiDocumentTestBase {

    private static final float ITEM_W = 50f;
    private static final float ITEM_H = 20f;

    private UIElement row;
    private InsertionMarker marker;
    private final List<UIElement> items = new ArrayList<>();

    @Before
    public void buildRow() {
        // THREE ITEMS A LINE: 160 holds three 50s.
        row = new UIElement().layout(l -> l.width(160f).height(100f)
                .flexDirection(FlexDirection.ROW).flexWrap(FlexWrap.WRAP));
        marker = new InsertionMarker(InsertionMarker.Axis.HORIZONTAL).mode(InsertionMarker.Mode.IN_FLOW);
        marker.parkIn(row);
        for (int i = 0; i < 6; i++) {
            UIElement item = new UIElement().layout(l -> l.width(ITEM_W).height(ITEM_H).flexShrink(0f));
            row.append(item);
            items.add(item);
        }
        document.append(row);
        frame();
    }

    /** A point {@code fx} of the way across item {@code i}, halfway down it. */
    private float[] over(int i, float fx) {
        Box box = items.get(i).box();
        return new float[]{box.worldX() + box.width() * fx, box.worldY() + box.height() / 2f};
    }

    @Test
    public void theLinePointedAtDecides() {
        float[] leftOfFifth = over(4, 0.25f);
        assertEquals("the second line's second item, left half", 4,
                marker.indexFor(row, items, leftOfFifth[0], leftOfFifth[1]));
        float[] rightOfFirst = over(0, 0.75f);
        assertEquals(1, marker.indexFor(row, items, rightOfFirst[0], rightOfFirst[1]));
    }

    @Test
    public void pastALinesLastItemIsRightAfterIt() {
        Box third = items.get(2).box();
        float x = third.worldX() + third.width() + 4f;
        assertEquals("past the first line's end is before the second line, not the list's end", 3,
                marker.indexFor(row, items, x, third.worldY() + ITEM_H / 2f));
    }

    /** The gap reflows the lines; a pointer resting on it must not chase it. */
    @Test
    public void aPointerOnTheGapKeepsTheGap() {
        float[] at = over(2, 0.25f);
        int first = marker.showFor(row, items, at[0], at[1]);
        frame();
        Box gap = marker.box();
        float gx = gap.worldX() + gap.width() / 2f;
        float gy = gap.worldY() + gap.height() / 2f;
        for (int i = 0; i < 4; i++) {
            assertEquals("the gap moved under a resting pointer", first, marker.showFor(row, items, gx, gy));
            frame();
        }
    }
}
