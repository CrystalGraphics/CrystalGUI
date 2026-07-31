package com.crystalgui.ui.elements;

import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.visual.Overflow;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.event.MouseEvent;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import dev.vfyjxf.taffy.style.TaffyPosition;
import com.crystalgraphics.platform.CgPlatform;

/**
 * A scroll container with visible scrollbars.
 *
 * <p><b>Your children are direct children.</b> {@code acceptsPublicChildren()} stays {@code true} and
 * there is no viewport or content wrapper — this element <em>is</em> the viewport, so
 * {@code addChild} puts content on the top layer exactly as it does on a {@code <div>}:</p>
 *
 * <pre>{@code
 * ScrollerView list = new ScrollerView();
 * list.addChild(rowA);   // a direct child, like any element
 * list.addChild(rowB);
 * }</pre>
 *
 * <p>That is the whole reason this class is thin. Scrolling itself is an <b>element capability</b> —
 * any element with a scrolling {@code overflow} scrolls, offset by {@code scrollTop}/
 * {@code scrollLeft} through the transform chain. This adds only the two scrollbars, so if you don't
 * want visible bars you don't need this class at all: {@code overflow: hidden} on a plain element is
 * already scrollable, which is exactly what CSS says.</p>
 *
 * <p>Contrast LDLib2's {@code ScrollerView}: a verticalContainer wrapping a viewPort wrapping another
 * wrapper wrapping a viewContainer, plus two five-node scrollers — 13 internal nodes and five levels
 * before your content. This has <b>two</b>, both {@code markAsInternal()}, so an inspector filtering
 * {@code isInternalUI()} shows just {@code scrollerview > your content}.</p>
 */
public class ScrollerView extends UIElement implements com.crystalgui.ui.UIFrameTicker {

    public static final String V_SCROLLER_CLASS = "__v-scroller__";
    public static final String H_SCROLLER_CLASS = "__h-scroller__";
    /** The square where the two bars meet — a browser's {@code ::-webkit-scrollbar-corner}. */
    public static final String CORNER_CLASS = "__corner__";

    private final Scroller verticalScroller;
    private final Scroller horizontalScroller;
    private final UIElement corner;

    /** Guards the two-way sync between scroll offset and bar value from feeding back on itself. */
    private boolean syncing = false;
    private boolean scrollbarsVisible = true;

    public ScrollerView() {
        StyleGroup.defaultPipeline(getStyle().getGeneralGroup(), g -> g.overflow(Overflow.AUTO));

        this.verticalScroller = newBar(Scroller.Orientation.VERTICAL, V_SCROLLER_CLASS);
        this.horizontalScroller = newBar(Scroller.Orientation.HORIZONTAL, H_SCROLLER_CLASS);

        // Fills the gap the two bars leave for each other, so it isn't a hole showing the content
        // through. Same pinning/exemption as a bar; sized from their thicknesses in reserveCorner.
        this.corner = new UIElement();
        this.corner.addClass(CORNER_CLASS);
        StyleGroup.defaultPipeline(corner.getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE).right(0).bottom(0));
        StyleGroup.defaultPipeline(corner.getStyle().getGeneralGroup(), g -> g.zIndex(1));
        addInternalChild(corner);
        corner.setScrollExempt(true);

        // A DRAG must land instantly or the thumb lags the cursor by the smooth-scroll duration;
        // a button or track-page click should ease, the way browser scrollbar arrows do. The bar
        // reports which is happening, so the same listener serves both.
        verticalScroller.attachListener(v -> {
            if (syncing) return;
            float top = v * getMaxScrollTop();
            if (verticalScroller.isDragging()) setScrollImmediate(getScrollLeft(), top);
            else setScrollTop(top);
        });
        horizontalScroller.attachListener(v -> {
            if (syncing) return;
            float left = v * getMaxScrollLeft();
            if (horizontalScroller.isDragging()) setScrollImmediate(left, getScrollTop());
            else setScrollLeft(left);
        });

        // Relative nudges (buttons, track paging) apply to the TARGET, not the rendered offset —
        // otherwise a second click during an in-flight animation would start over from wherever the
        // ease had got to, and repeated clicks wouldn't accumulate.
        verticalScroller.onScrollIntent.connect(
                f -> setScrollTop(getTargetScrollTop() + f * getScrollHeight()));
        horizontalScroller.onScrollIntent.connect(
                f -> setScrollLeft(getTargetScrollLeft() + f * getScrollWidth()));

        // The wheel is handled HERE, not by the engine. A bare UIElement is programmatic-only however
        // its overflow is set; opting in is what makes something a scroll *view*. Relative to the
        // target so notches spun in quick succession accumulate instead of each restarting the ease.
        // A BUBBLE listener, not attachDefaultListener: the wheel's target is whichever row is under
        // the cursor, not this view, and default listeners only fire in the TARGET phase.
        this.events.getGroup(MouseEvent.Scroll.class).attachListener((el, event) -> {
            if (!isEnabled()) return;
            // Mod+wheel is NOT scrolling. Declining it here is what lets it fall through to the keymap,
            // where `editor.zoomIn` and anything else can bind it -- and declining is the only way, since
            // the resolver runs after dispatch and only on what nothing consumed. Shift+wheel is still
            // ours: it is this view's horizontal scroll, and the convention everywhere.
            int held = com.crystalgraphics.platform.CgPlatform.input().getCurrentModifiers();
            if (com.crystalgraphics.platform.input.CgModifiers.hasCtrl(held)
                    || com.crystalgraphics.platform.input.CgModifiers.hasSuper(held)) {
                return;
            }
            float delta = event.getScroll() * WHEEL_PIXELS_PER_NOTCH;

            // Shift+wheel scrolls horizontally — the convention everywhere, and the only way to reach
            // a horizontal overflow with a plain vertical wheel.
            var adapter = CgPlatform.input();
            boolean horizontal = adapter != null && CgModifiers.hasShift(adapter.getCurrentModifiers());

            // ...and on a view that can ONLY scroll sideways, the plain wheel drives that axis too.
            // Otherwise a horizontal-only strip (a tab bar, a toolbar) simply ignores the wheel and
            // looks broken — you'd have to know to hold shift. Browsers do the same.
            if (!horizontal && getMaxScrollTop() <= 0f && getMaxScrollLeft() > 0f) horizontal = true;

            float before = horizontal ? getTargetScrollLeft() : getTargetScrollTop();
            if (horizontal) setScrollLeft(before + delta);
            else setScrollTop(before + delta);
            float after = horizontal ? getTargetScrollLeft() : getTargetScrollTop();

            // Only claim the wheel if it actually moved us; at either end it should pass to an outer
            // scroller, which is the scroll-chaining browsers do.
            if (after != before) {
                event.preventDefault();
                event.stopPropagation();
            }
        }, false, true);
    }

    private Scroller newBar(Scroller.Orientation orientation, String cssClass) {
        Scroller bar = new Scroller();
        bar.setOrientation(orientation);
        bar.addClass(cssClass);
        // Absolutely positioned so the bars overlay the content rather than taking a slice of the
        // flex line — the CSS `overflow: auto` look, and it keeps children laying out as if the bars
        // weren't there.
        StyleGroup.defaultPipeline(bar.getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE));
        // Above the content, always — browsers paint scrollbars on top of everything they scroll.
        // Required, not cosmetic: sortedChildren orders equal z-index by *later-inserted first*, and
        // the bars are created in this constructor, so every row added afterwards would sort above
        // them. The bar would then be behind the content and unclickable — which is exactly how it
        // first behaved.
        StyleGroup.defaultPipeline(bar.getStyle().getGeneralGroup(), g -> g.zIndex(1));
        addInternalChild(bar);
        // Painted outside this element's scroll translate, so the bar stays pinned instead of
        // scrolling away with the content. Browsers get this free by not making scrollbars nodes.
        bar.setScrollExempt(true);
        return bar;
    }

    /** The vertical bar, exposed for styling/inspection. */
    public Scroller verticalScroller() {
        return verticalScroller;
    }

    public Scroller horizontalScroller() {
        return horizontalScroller;
    }

    /**
     * Pushes the current scroll state into the bars: thumb length from the visible fraction, thumb
     * position from the offset, and visibility per {@code overflow}
     * ({@code auto} shows a bar only when that axis actually overflows; {@code scroll} always does).
     *
     * <p>Call after the content changes. Cheap and idempotent.</p>
     */
    public void refreshScrollers() {
        syncing = true;
        try {
            Overflow overflow = getStyle().getGeneralGroup().overflow();

            float maxTop = getMaxScrollTop();
            float maxLeft = getMaxScrollLeft();

            updateBar(verticalScroller, overflow, maxTop > 0f,
                    getClientHeight(), getScrollHeight(), maxTop > 0f ? getScrollTop() / maxTop : 0f);
            updateBar(horizontalScroller, overflow, maxLeft > 0f,
                    getClientWidth(), getScrollWidth(), maxLeft > 0f ? getScrollLeft() / maxLeft : 0f);
            reserveCorner(overflow, maxTop > 0f, maxLeft > 0f);
        } finally {
            syncing = false;
        }
    }

    /**
     * Stops each bar short of the other when both are showing, leaving the empty square browsers put
     * in the bottom-right corner.
     *
     * <p>Without it the two bars run their full length and overlap in that corner — and with the step
     * buttons enabled the two tail buttons land on top of each other, which is what made the overlap
     * obvious. The CSS pins both bars with {@code bottom: 0}/{@code right: 0}; this overrides the far
     * edge at IMPORTANT origin, since whether a corner is needed is runtime state, not something a
     * stylesheet can know.</p>
     */
    private void reserveCorner(Overflow overflow, boolean vOverflowing, boolean hOverflowing) {
        boolean both = scrollbarsVisible
                && overflow.showsScrollbar(vOverflowing) && overflow.showsScrollbar(hOverflowing);
        float vThickness = verticalScroller.getRuntimeCache().getWidth();
        float hThickness = horizontalScroller.getRuntimeCache().getHeight();

        StyleGroup.importantPipeline(verticalScroller.getStyle().getLayoutGroup(),
                l -> l.bottom(both ? hThickness : 0f));
        StyleGroup.importantPipeline(horizontalScroller.getStyle().getLayoutGroup(),
                l -> l.right(both ? vThickness : 0f));

        // Fill the gap they just left each other, rather than leaving a hole onto the content.
        StyleGroup.importantPipeline(corner.getStyle().getLayoutGroup(),
                l -> l.display(both ? TaffyDisplay.FLEX : TaffyDisplay.NONE)
                        .width(both ? vThickness : 0f)
                        .height(both ? hThickness : 0f));
    }

    /** The square between the two bars, exposed for styling. */
    public UIElement corner() {
        return corner;
    }

    public boolean isScrollbarsVisible() {
        return scrollbarsVisible;
    }

    /**
     * Hides the bars while leaving the view fully scrollable by wheel and by API.
     *
     * <p>Not expressible in CSS: the bars' {@code display} is runtime state written at IMPORTANT
     * origin, which a stylesheet cannot outrank. Distinct from {@code overflow: hidden}, which also
     * hides the bars but forbids user scrolling entirely.</p>
     *
     * <p>The case this exists for is a bar that would sit <em>on top of</em> the content rather than
     * beside it — the bars are absolutely positioned, so on a short strip (a tab bar, a toolbar) the
     * horizontal bar covers the bottom few pixels of what it is supposed to be scrolling.</p>
     */
    public ScrollerView setScrollbarsVisible(boolean scrollbarsVisible) {
        if (this.scrollbarsVisible == scrollbarsVisible) return this;
        this.scrollbarsVisible = scrollbarsVisible;
        refreshScrollers();
        return this;
    }

    private void updateBar(Scroller bar, Overflow overflow, boolean overflowing,
                           float client, float content, float value) {
        boolean visible = scrollbarsVisible && overflow.showsScrollbar(overflowing);
        StyleGroup.importantPipeline(bar.getStyle().getLayoutGroup(),
                l -> l.display(visible ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
        if (!visible) return;
        bar.setVisibleRatio(content <= 0f ? 1f : client / content);
        // One arrow click = one line, regardless of how long the list is. Expressed as a fraction
        // because the bar works in 0..1 and has no idea of the content's pixel size; without this the
        // default fraction would make a click jump further the more content there is, which is the
        // opposite of how a browser's arrows feel.
        bar.setStepFraction(content <= 0f ? 0f : LINE_HEIGHT_PX / content);
        bar.setValue(value);
    }

    /** One "line" — what a single scrollbar-arrow click moves. Matches the wheel's per-notch step. */
    private static final float LINE_HEIGHT_PX = 40f;

    /** Wheel deltas arrive already normalised to this engine's top-left-origin convention (same sign
     * as Y), so a positive notch is wheel-DOWN and increases scrollTop. */
    private static final float WHEEL_PIXELS_PER_NOTCH = 40f;

    @Override
    public UIElement setScroll(float left, float top) {
        super.setScroll(left, top);
        if (!syncing) {
            refreshScrollers();
            // A smooth scroll moves the offset over several frames, and the thumb has to move with
            // it. Without this the bar would only resync when something else happened to call
            // refreshScrollers, leaving the thumb frozen while the content glided underneath.
            if (isAnimating()) {
                var window = getAttachedWindow();
                if (window != null) window.registerTicker(this);
            }
        }
        return this;
    }

    /**
     * Resyncs the bars whenever this view's geometry changes.
     *
     * <p>Needed because bar state is derived from measured sizes, and those only exist after a
     * layout. In particular {@link #reserveCorner} reads the horizontal bar's thickness — on the very
     * frame that bar becomes visible it has not been laid out yet and still measures zero, so the
     * corner would be skipped and the two bars would overlap until something else happened to
     * refresh. Reacting to the layout instead of trusting callers to re-call
     * {@link #refreshScrollers} closes that gap.</p>
     */
    @Override
    protected void onLayoutChanged() {
        super.onLayoutChanged();
        if (!syncing) refreshScrollers();
    }

    private boolean isAnimating() {
        return getScrollTop() != getTargetScrollTop() || getScrollLeft() != getTargetScrollLeft();
    }

    @Override
    public boolean tickFrame(float deltaSeconds) {
        refreshScrollers();
        return isAnimating();
    }
}
