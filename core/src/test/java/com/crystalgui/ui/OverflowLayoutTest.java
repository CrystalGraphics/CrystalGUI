package com.crystalgui.ui;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.visual.Overflow;
import dev.vfyjxf.taffy.style.FlexDirection;
import com.crystalgui.testsupport.UiTestBase;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * {@code overflow} feeding layout, not just painting.
 *
 * <p>A non-visible overflow zeroes an item's <em>automatic minimum size</em> (CSS 2.1 /
 * css-overflow-3), which is what lets a flex item shrink below its own content. Taffy implements the
 * rule; CrystalGUI simply never set {@code TaffyStyle.overflow}, so {@code overflow: hidden} was
 * purely cosmetic. This pins the wiring.</p>
 *
 * <h3>Why the setup below looks over-specified</h3>
 * <p>{@code flexShrink(1)} and {@code minWidthAuto()} are both stated explicitly because
 * <b>CrystalGUI's defaults differ from CSS on both</b>, and each one independently makes the rule
 * unobservable:</p>
 * <ul>
 *   <li>{@code FLEX_SHRINK} defaults to {@code 0} here; CSS and Taffy default it to {@code 1}. At 0 a
 *       flex item never shrinks at all, so no min-size question is ever asked.</li>
 *   <li>{@code MIN_WIDTH} defaults to {@code ZERO} here; Taffy's own default is {@code AUTO}. An
 *       explicit min-size takes precedence over the overflow branch in {@code FlexboxComputer}, so a
 *       0 min means the automatic-minimum-size path is never reached.</li>
 * </ul>
 * <p>Consequence worth knowing: with stock defaults this rule cannot fire anywhere in the engine, so
 * the {@code min-width: 0} that used to be written by hand around the codebase was always a no-op —
 * the value was already 0. What actually made SplitView's panes overflow was {@code flex-basis: auto}
 * (base size = content) combined with {@code flex-shrink: 0} (so it could not shrink back down);
 * {@code flex-basis: 0} is what fixed it.</p>
 *
 * <p>Differential on purpose: the {@code VISIBLE} case asserts the item <em>refuses</em> to shrink.
 * Without that half, the {@code HIDDEN} assertion would still pass if the engine simply ignored
 * content sizing, and would prove nothing.</p>
 */
public class OverflowLayoutTest extends UiTestBase {

    /** Content far wider than any container it's put in below. */
    private static final float OVERSIZED = 2000f;
    private static final float CONTAINER = 400f;

    @Test
    public void visibleOverflowRefusesToShrinkBelowItsContent() {
        float width = flexItemWidthWith(Overflow.VISIBLE);
        assertEquals("a visible-overflow item should keep its content's min size",
                OVERSIZED, width, 1f);
    }

    @Test
    public void hiddenOverflowLetsTheItemShrinkBelowItsContent() {
        float width = flexItemWidthWith(Overflow.HIDDEN);
        assertTrue("overflow: hidden did not zero the automatic minimum size — item stayed "
                        + width + "px inside a " + CONTAINER + "px container",
                width <= CONTAINER + 1f);
    }

    /** Isolates where the value is lost: style side, or layout side. */
    @Test
    public void overflowReachesTheTaffyStyle() {
        UIElement item = new UIElement().layout(l -> l.height(50));
        StyleGroup.defaultPipeline(item.getStyle().getGeneralGroup(), g -> g.overflow(Overflow.HIDDEN));

        UIElement container = new UIElement().layout(l -> l.width(CONTAINER).height(100));
        container.addChild(item);
        UIWindow window = new UIWindow(Ui.of(container));
        window.init(800, 600);
        window.getStyleEngine().calculateStyle(0.016f);
        window.calculateLayout();

        assertEquals("overflow never reached the Taffy style",
                dev.vfyjxf.taffy.style.Overflow.HIDDEN,
                item.getStyle().taffyBridge.style.overflow.x);
    }

    /** The two cases must actually differ, or neither assertion above means anything. */
    @Test
    public void overflowChangesTheOutcome() {
        assertNotEquals(flexItemWidthWith(Overflow.VISIBLE),
                flexItemWidthWith(Overflow.HIDDEN), 1f);
    }

    /**
     * Lays out {@code container(400px) > item(width 100px, overflow=mode) > content(2000px)} and
     * returns the item's resolved width.
     *
     * <p>The item deliberately has no explicit width and leaves {@code flex-basis} at {@code auto},
     * so its base size IS its content (2000px) and the 400px container puts it under genuine shrink
     * pressure. That pressure is the precondition: automatic minimum size only bites when an item
     * would otherwise shrink below its own content. A {@code flex-basis: 0} + grow setup sidesteps
     * the rule entirely — the item is then sized purely by its grow share, both modes come out
     * identical, and the test proves nothing (verified: it did exactly that on the first attempt).</p>
     */
    private float flexItemWidthWith(Overflow mode) {
        UIElement container = new UIElement().layout(l -> l.width(CONTAINER).height(100)
                .flexDirection(FlexDirection.ROW));

        // No explicit width and flex-basis left at auto, so the item's base size IS its content
        // (2000px) and the 400px container puts it under real shrink pressure. That pressure is the
        // precondition for the rule under test: automatic min size only bites when an item would
        // otherwise shrink below its own content. This is exactly the shape SplitView's panes had
        // before they were given flex-basis: 0.
        UIElement item = new UIElement().layout(l -> l.height(50).minWidthAuto().flexShrink(1));
        StyleGroup.defaultPipeline(item.getStyle().getGeneralGroup(), g -> g.overflow(mode));
        container.addChild(item);

        item.addChild(new UIElement().layout(l -> l.width(OVERSIZED).height(20)));

        UIWindow window = new UIWindow(Ui.of(container));
        window.init(800, 600);
        window.getStyleEngine().calculateStyle(0.016f);
        window.calculateLayout();

        return item.getRuntimeCache().getWidth();
    }
}
