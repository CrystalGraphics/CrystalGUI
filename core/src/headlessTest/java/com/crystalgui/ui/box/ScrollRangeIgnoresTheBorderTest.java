package com.crystalgui.ui.box;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.visual.Overflow;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;

/**
 * <b>A box whose content exactly fits has no scroll range, border or no border.</b>
 *
 * <p>Taffy measures {@code contentSize} from the BORDER box origin, so a child sitting at the content
 * origin reports an extent that already includes the leading border. {@code clientWidth} takes both
 * borders off. Comparing the two subtracted the leading border once and never added it back, so every
 * bordered scroll container reported exactly {@code border.left} pixels of phantom overflow.</p>
 *
 * <p>Found from a one-pixel sideways jump of the whole UI-builder view on right-click: focus moving to a
 * context menu ran {@code scrollIntoView}, which scrolled that phantom pixel, and moving focus back
 * scrolled it home — a two-pixel round trip in a region that had nothing to scroll.</p>
 */
public class ScrollRangeIgnoresTheBorderTest {

    private static final float W = 400f;
    private static final float H = 300f;

    private Box regionWith(float border) {
        UIDocument document = new UIDocument();
        UIElement region = new UIElement();
        StyleGroup.inlinePipeline(region.getStyle().getLayoutGroup(),
                l -> l.width(200f).height(100f).borderAll(border));
        StyleGroup.inlinePipeline(region.getStyle().getGeneralGroup(),
                g -> g.overflow(Overflow.HIDDEN));
        UIElement child = new UIElement();
        StyleGroup.inlinePipeline(child.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).heightPercent(100f));
        region.append(child);
        document.append(region);
        document.update(W, H);
        return region.box();
    }

    /** The case that was already right, and the control for the one below. */
    @Test
    public void anUnborderedRegionThatFitsHasNoRange() {
        Box box = regionWith(0f);
        assertEquals("nothing overflows", 0f, box.maxScrollLeft(), 0.001f);
        assertEquals(0f, box.maxScrollTop(), 0.001f);
    }

    /** The reported one: a 1px border bought 1px of scroll out of nowhere. */
    @Test
    public void aBorderedRegionThatFitsHasNoRangeEither() {
        Box box = regionWith(1f);
        assertEquals("the border is not content", 0f, box.maxScrollLeft(), 0.001f);
        assertEquals(0f, box.maxScrollTop(), 0.001f);
    }

    /** And real overflow is still reported, so the correction cannot be a blanket zero. */
    @Test
    public void realOverflowIsStillMeasured() {
        UIDocument document = new UIDocument();
        UIElement region = new UIElement();
        StyleGroup.inlinePipeline(region.getStyle().getLayoutGroup(),
                l -> l.width(200f).height(100f).borderAll(1f));
        StyleGroup.inlinePipeline(region.getStyle().getGeneralGroup(),
                g -> g.overflow(Overflow.HIDDEN));
        UIElement child = new UIElement();
        StyleGroup.inlinePipeline(child.getStyle().getLayoutGroup(),
                l -> l.width(500f).height(400f));
        region.append(child);
        document.append(region);
        document.update(W, H);

        assertEquals("500 wide in a 198 content box", 302f, region.box().maxScrollLeft(), 0.5f);
        assertEquals(302f, region.box().maxScrollTop(), 0.5f);
    }
}
