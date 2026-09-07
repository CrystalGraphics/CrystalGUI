package com.crystalgui.ui.box;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.style.property.layout.length.LPSize;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;

import dev.vfyjxf.taffy.style.FlexDirection;

/**
 * <b>All four gap spellings reach the layout.</b>
 *
 * <p>{@code gap} did not. {@code BoxStyle} reset the bridge by setting {@code gap-all} to ZERO before
 * applying what the cascade held, and the bridge resolves an axis as "the longhand if it is not auto,
 * else {@code gap-all} if it is not auto, else {@code gap}" — so a non-auto reset made the last branch
 * unreachable. The property parsed, cascaded and answered {@code isSet}, and laid out at zero.</p>
 *
 * <p>Which is why every shipped sheet spells it {@code gap-all}: the one rule that reached for the CSS
 * name got a toolbar whose controls touched, and no error anywhere.</p>
 */
public class GapShorthandTest extends UiDocumentTestBase {

    @Test
    public void everyGapSpellingSpacesARow() {
        assertEquals("gap", 40f, secondChildX(g -> g.set(LayoutProperties.GAP, new LPSize(
                dev.vfyjxf.taffy.geometry.TaffySize.all(
                        dev.vfyjxf.taffy.style.LengthPercentage.length(10f))))), 0.01f);
        assertEquals("gap-all", 40f, secondChildX(g -> g.gapAll(10f)), 0.01f);
        assertEquals("gap-column", 40f, secondChildX(g -> g.gapColumn(10f)), 0.01f);
    }

    /** A withdrawn gap is withdrawn — only `gap-all` was reset before, so a longhand lingered. */
    @Test
    public void aGapIsWithdrawnWhenItStopsBeingSet() {
        UIElement row = row(g -> g.gapColumn(10f));
        document.update(W, H);
        assertEquals(40f, row.children().get(1).box().x(), 0.01f);

        row.getStyle().getLayoutGroup().gapColumn(0f);
        document.update(W, H);
        assertEquals("the gap outlived the value that set it",
                30f, row.children().get(1).box().x(), 0.01f);
    }

    private float secondChildX(java.util.function.Consumer<com.crystalgui.style.LayoutGroup> gap) {
        UIElement row = row(gap);
        document.update(W, H);
        return row.children().get(1).box().x();
    }

    private UIElement row(java.util.function.Consumer<com.crystalgui.style.LayoutGroup> gap) {
        UIElement row = new UIElement().layout(l -> {
            l.width(300).height(50).flexDirection(FlexDirection.ROW);
            gap.accept(l);
        });
        row.append(new UIElement().layout(l -> l.width(30).height(20)));
        row.append(new UIElement().layout(l -> l.width(30).height(20)));
        document.append(row);
        return row;
    }
}
