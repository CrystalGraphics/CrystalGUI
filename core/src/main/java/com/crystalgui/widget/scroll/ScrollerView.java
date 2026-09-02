package com.crystalgui.widget.scroll;

import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.style.property.visual.Overflow;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.ShadowRoot;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.dom.UISlot;
import com.crystalgui.ui.event.MouseEvent;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import dev.vfyjxf.taffy.style.TaffyPosition;
import com.crystalgraphics.platform.CgPlatform;
import dev.vfyjxf.taffy.style.FlexDirection;
import com.crystalgui.ui.box.Box;
import javax.annotation.Nullable;

/**
 * A scroll container with visible scrollbars.
 *
 * <p><b>Your children are ordinary children.</b> There is no viewport or content wrapper to reach
 * through — {@code append} puts content in exactly as it does on a {@code <div>}:</p>
 *
 * <pre>{@code
 * ScrollerView list = new ScrollerView();
 * list.append(rowA);   // a direct child, like any element
 * list.append(rowB);
 * }</pre>
 *
 * <p>That is the whole reason this class is thin. Scrolling itself is an <b>element capability</b> —
 * any element with a scrolling {@code overflow} scrolls, offset by {@code scrollTop}/
 * {@code scrollLeft} through the transform chain. This adds only the two scrollbars, so if you don't
 * want visible bars you don't need this class at all: {@code overflow: hidden} on a plain element is
 * already scrollable, which is exactly what CSS says.</p>
 *
 * <p>What a caller adds lands in a {@link UISlot} inside the shadow tree, which is how a widget with
 * its own parts still takes content: the bars and the corner cannot be reached by a rule aimed at your
 * rows, and your rows cannot be reached by a rule aimed at the bars. The slot is content-sized, so the
 * scroll extent Taffy computes is the content's — the bars are absolutely positioned and contribute
 * nothing to it.</p>
 *
 * <p>Contrast LDLib2's {@code ScrollerView}: a verticalContainer wrapping a viewPort wrapping another
 * wrapper wrapping a viewContainer, plus two five-node scrollers — 13 nodes and five levels before your
 * content. This has a slot and three parts.</p>
 */
public class ScrollerView extends UINode {

    // The scroll extents live on the BOX now -- they are geometry, and geometry is not the node's.
    // Wrapped here rather than spelled at each of the dozen call sites, and answering 0 with no box,
    // which is the honest answer for a view nothing has laid out.
    /**
     * Where a smooth scroll is HEADING, not where it has got to.
     *
     * <p>Every relative nudge — a bar button, a track page, a wheel notch — applies to this rather
     * than to the rendered offset, or a second click during an in-flight ease starts over from
     * wherever the animation happened to be and repeated clicks do not accumulate.</p>
     *
     * <p>The old engine kept the target inside its scroll animation and exposed it. The animation is
     * the {@code Animation} service's here and it does not answer that question, so the view keeps
     * its own: written on every scroll it requests, and re-read from the box whenever something ELSE
     * moved the scroll (a {@code scrollIntoView}, a drag, a direct write), which is what stops it
     * drifting away from the truth. Compared with a tolerance because both are floats settled by an
     * ease.</p>
     */
    /** Package-private, not private: where a smooth scroll is HEADED is the only thing that
     *  separates "it settled correctly" from "it never moved", and the covering test sits here. */
    float getTargetScrollTop() {
        return easing() ? targetTop : scrollTop();
    }

    /** @see #getTargetScrollTop() */
    float getTargetScrollLeft() {
        return easing() ? targetLeft : scrollLeft();
    }

    /**
     * Whether a smooth scroll is still running.
     *
     * <p>The one question that separates "the target is a promise" from "the target is history".
     * While nothing is easing the rendered offset IS the target, so reading the box is both simpler
     * and immune to anything else having moved the scroll — a {@code scrollIntoView}, a drag, a
     * direct write. Deliberately not a flag of our own: a flag would have to be cleared by whoever
     * finished the animation, and a missed clear leaves every later nudge accumulating onto a target
     * from a gesture that ended minutes ago.</p>
     */
    private boolean easing() {
        return document() != null && document().animation().isAnimating();
    }

    /** {@link UINode#scrollTo} plus the target this view nudges from. */
    private void scrollAimingAt(float left, float top) {
        // CLAMP THE TARGET, not only the position it produces. A target past maxScroll is somewhere
        // the view can never arrive, so `getTargetScrollTop()` answers with a number the scroll then
        // settles away from -- and a held step button accumulates one step per frame, so it runs off
        // indefinitely while the view sits at the end. Releasing it then looks like the repeat kept
        // running, because the reported target keeps changing after the button came up.
        targetLeft = Math.max(0f, Math.min(maxScrollLeft(), left));
        targetTop = Math.max(0f, Math.min(maxScrollTop(), top));
        scrollTo(targetLeft, targetTop);
    }

    private float targetLeft;
    private float targetTop;

    private float maxScrollTop() {
        return box() == null ? 0f : box().maxScrollTop();
    }

    private float maxScrollLeft() {
        return box() == null ? 0f : box().maxScrollLeft();
    }

    private float clientWidth() {
        return box() == null ? 0f : box().clientWidth();
    }

    private float clientHeight() {
        return box() == null ? 0f : box().clientHeight();
    }

    private float scrollWidth() {
        return box() == null ? 0f : box().scrollWidth();
    }

    private float scrollHeight() {
        return box() == null ? 0f : box().scrollHeight();
    }


    public static final Name NAME = Name.of("scrollerview");

    /** {@code scrollerview::part(v-scroller)} in a sheet. */
    public static final String V_SCROLLER_PART = "v-scroller";
    /** {@code scrollerview::part(h-scroller)}. */
    public static final String H_SCROLLER_PART = "h-scroller";
    /** The square where the two bars meet — a browser's {@code ::-webkit-scrollbar-corner}. */
    public static final String CORNER_PART = "corner";

    private final ShadowRoot shadow;
    private final UISlot viewport;
    private final Scroller verticalScroller;
    private final Scroller horizontalScroller;
    private final UINode corner;

    /** Guards the two-way sync between scroll offset and bar value from feeding back on itself. */
    private boolean syncing = false;
    private boolean scrollbarsVisible = true;

    public ScrollerView() {
        this(NAME);
    }

    /**
     * The constructor a subclass hands its own kind to.
     *
     * <p>Without one a subclass reports {@code scrollerview} — {@code ConfiguratorPanel} extends this,
     * and a theme's {@code configuratorpanel { }} would have matched nothing while every
     * {@code scrollerview} rule reached it. The standing rule: a subclass inherits its parent's kind
     * unless it is GIVEN one, and only the parent can offer the seam.</p>
     */
    protected ScrollerView(Name name) {
        super(name);
        StyleGroup.defaultPipeline(getStyle().getGeneralGroup(), g -> g.overflow(Overflow.AUTO));

        // A SLOT, which is what replaced `acceptsPublicChildren() == true`. A caller's children are
        // still added with append() and still scroll; they simply land in the slot rather than beside
        // the bars, so nothing a caller adds can collide with a part and no ordering rule has to be
        // remembered. The slot is content-sized, so the scroll extent Taffy computes for this box is
        // the content's -- the bars are absolutely positioned and contribute nothing to it.
        this.shadow = attachShadow();
        this.viewport = new UISlot();
        // THE SLOT MUST FILL THE VIEW'S WIDTH, and it does not by default: a slot is an ordinary box
        // and this engine's Taffy default is `flex-shrink: 0` with an `auto` basis, so it sizes to its
        // CONTENT. A caller's `width: 100%` row then resolves against the slot rather than against the
        // view -- measured at 42px inside a 776px scroller, which reads as the rows being unstyled
        // rather than as the box around them having collapsed.
        //
        // HEIGHT IS A MINIMUM, NEVER A SIZE, and the distinction is the whole of a scroll container:
        // `min-height: 100%` fills the viewport when the content is shorter and gets out of the way the
        // moment it is taller, which is exactly what a viewport is for. Leaving it unconstrained is what
        // the comment below argued for and it is only half right -- content must be free to EXCEED the
        // viewport, and it must also be able to FILL one.
        //
        // Nothing inside could fill before this. A window's content slot is a ScrollerView, so the whole
        // Crystal Editor -- rails, project panel, tab strip, editor, console, status bar -- was laid out
        // at its own natural height inside a 490px window: measured at `slot 582x78` under a
        // `scrollerview 582x468`, which on screen is a menu bar, a 78px sliver and nothing else. It reads
        // as the workbench having failed to build rather than as the box around it having no height.
        //
        // Height is deliberately not a fixed size: the content must be free to exceed the viewport,
        // which is the whole point of a scroll container.
        StyleGroup.defaultPipeline(viewport.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).minHeightPercent(100f));
        mirrorFlexContainer();
        shadow.append(viewport);

        this.verticalScroller = newBar(Scroller.Orientation.VERTICAL, V_SCROLLER_PART);
        this.horizontalScroller = newBar(Scroller.Orientation.HORIZONTAL, H_SCROLLER_PART);

        // Fills the gap the two bars leave for each other, so it isn't a hole showing the content
        // through. Same pinning/exemption as a bar; sized from their thicknesses in reserveCorner.
        this.corner = new UINode();
        this.corner.set(Attribute.PART, CORNER_PART);
        StyleGroup.defaultPipeline(corner.getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE).right(0).bottom(0));
        StyleGroup.defaultPipeline(corner.getStyle().getGeneralGroup(), g -> g.zIndex(1));
        shadow.append(corner);
        corner.setScrollExempt(true);

        // A DRAG must land instantly or the thumb lags the cursor by the smooth-scroll duration;
        // a button or track-page click should ease, the way browser scrollbar arrows do. The bar
        // reports which is happening, so the same listener serves both.
        verticalScroller.attachListener(v -> {
            if (syncing) return;
            float top = v * maxScrollTop();
            if (verticalScroller.isDragging()) setScrollOffsets(scrollLeft(), top);
            else scrollAimingAt(scrollLeft(), top);
        });
        horizontalScroller.attachListener(v -> {
            if (syncing) return;
            float left = v * maxScrollLeft();
            if (horizontalScroller.isDragging()) setScrollOffsets(left, scrollTop());
            else scrollAimingAt(left, scrollTop());
        });

        // Relative nudges (buttons, track paging) apply to the TARGET, not the rendered offset —
        // otherwise a second click during an in-flight animation would start over from wherever the
        // ease had got to, and repeated clicks wouldn't accumulate.
        verticalScroller.onScrollIntent.connect(
                f -> scrollAimingAt(scrollLeft(), getTargetScrollTop() + f * scrollHeight()));
        horizontalScroller.onScrollIntent.connect(
                f -> scrollAimingAt(getTargetScrollLeft() + f * scrollWidth(), scrollTop()));

        // The wheel is handled HERE, not by the engine. A bare UINode is programmatic-only however
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
            int held = CgPlatform.input().getCurrentModifiers();
            if (CgModifiers.hasCtrl(held)
                    || CgModifiers.hasSuper(held)) {
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
            if (!horizontal && maxScrollTop() <= 0f && maxScrollLeft() > 0f) horizontal = true;

            float before = horizontal ? getTargetScrollLeft() : getTargetScrollTop();
            if (horizontal) scrollAimingAt(before + delta, scrollTop());
            else scrollAimingAt(scrollLeft(), before + delta);
            float after = horizontal ? getTargetScrollLeft() : getTargetScrollTop();

            // Only claim the wheel if it actually moved us; at either end it should pass to an outer
            // scroller, which is the scroll-chaining browsers do.
            if (after != before) {
                event.preventDefault();
                event.stopPropagation();
            }
        }, false, true);
    }

    private Scroller newBar(Scroller.Orientation orientation, String partName) {
        Scroller bar = new Scroller();
        bar.setOrientation(orientation);
        bar.set(Attribute.PART, partName);
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
        shadow.append(bar);
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
    /**
     * Gives the slot the view's own {@code flex-direction}, {@code align-items} and
     * {@code justify-content}.
     *
     * <p><b>A slot is a real box between a host and its content, so it is the flex container the
     * content actually lays out in</b> — and none of the three inherits. The slot used to state
     * {@code COLUMN} outright, which made a horizontal scroller impossible: a TabView's tab rail is a
     * {@code ScrollerView}, its sheet gives the rail a row, and its tabs stacked vertically anyway,
     * each one full width. That took two more symptoms with it — the rail became a VERTICAL scroll
     * container, so it ate wheel notches meant for the page, and the strip bar it sized was for an axis
     * nothing scrolls on.</p>
     *
     * <p><b>Alignment is the same trap and cost a second round.</b> Three shipped rules set
     * {@code align-items} on a rail, and the dock's is {@code center} — which centred the SLOT inside
     * the rail (where it already filled it) and left the slot stretching the tabs to the rail's full
     * height. Measured: a dock tab is 17.0 tall on the old engine and was 22.0 here, so every editor
     * tab's focus ring was five pixels taller than its label needed. It reads as a restyle rather than
     * as a box having appeared between two elements that used to be adjacent.</p>
     *
     * <p>{@code justify-content} is mirrored with them although nothing sets it on a scroller today: it
     * is the identical shape, and leaving it would arm the same trap for whoever writes that rule.</p>
     *
     * <p>Mirroring rather than inheriting is the narrowest fix that is also correct: a scroll
     * container's direction IS the axis its content runs along, and alignment set on a scroller is
     * alignment meant for what it scrolls. Written at DEFAULT origin so a sheet can still address the
     * view itself, and re-run from {@link #computedChanged} because a theme may set any of them long
     * after construction — a rail that is a row until the first restyle is worse than one that is never
     * a row, because only one of the two is reproducible.</p>
     */
    private void mirrorFlexContainer() {
        FlexDirection direction = computedStyle().get(LayoutProperties.FLEX_DIRECTION);
        AlignItems align = computedStyle().get(LayoutProperties.ALIGN_ITEMS);
        AlignContent justify = computedStyle().get(LayoutProperties.JUSTIFY_CONTENT);
        StyleGroup.defaultPipeline(viewport.getStyle().getLayoutGroup(),
                l -> {
                    l.flexDirection(direction == null ? FlexDirection.COLUMN : direction);
                    if (align != null) l.alignItems(align);
                    if (justify != null) l.justifyContent(justify);
                });
    }

    @Override
    public void computedChanged(StyleProperty<?> property, @Nullable Object oldValue,
                                @Nullable Object newValue) {
        super.computedChanged(property, oldValue, newValue);
        if (property == LayoutProperties.FLEX_DIRECTION
                || property == LayoutProperties.ALIGN_ITEMS
                || property == LayoutProperties.JUSTIFY_CONTENT) mirrorFlexContainer();
    }

    public void refreshScrollers() {
        syncing = true;
        try {
            Overflow overflow = getStyle().getGeneralGroup().overflow();

            float maxTop = maxScrollTop();
            float maxLeft = maxScrollLeft();

            updateBar(verticalScroller, overflow, maxTop > 0f,
                    clientHeight(), scrollHeight(), maxTop > 0f ? scrollTop() / maxTop : 0f);
            updateBar(horizontalScroller, overflow, maxLeft > 0f,
                    clientWidth(), scrollWidth(), maxLeft > 0f ? scrollLeft() / maxLeft : 0f);
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
     * edge at INLINE origin, since whether a corner is needed is runtime state, not something a
     * stylesheet can know.</p>
     */
    private void reserveCorner(Overflow overflow, boolean vOverflowing, boolean hOverflowing) {
        boolean both = scrollbarsVisible
                && overflow.showsScrollbar(vOverflowing) && overflow.showsScrollbar(hOverflowing);
        // NULL-CHECKED, and zero is the right answer here specifically: a bar that is not shown is
        // `display: none`, so it HAS no box, and a bar that is not there reserves no corner. Written
        // out rather than hidden behind an accessor that answers 0 for everything, because "not laid
        // out" and "zero wide" are different facts and only this method knows they coincide.
        Box vBar = verticalScroller.box();
        Box hBar = horizontalScroller.box();
        float vThickness = vBar == null ? 0f : vBar.width();
        float hThickness = hBar == null ? 0f : hBar.height();

        StyleGroup.inlinePipeline(verticalScroller.getStyle().getLayoutGroup(),
                l -> l.bottom(both ? hThickness : 0f));
        StyleGroup.inlinePipeline(horizontalScroller.getStyle().getLayoutGroup(),
                l -> l.right(both ? vThickness : 0f));

        // Fill the gap they just left each other, rather than leaving a hole onto the content.
        StyleGroup.inlinePipeline(corner.getStyle().getLayoutGroup(),
                l -> l.display(both ? TaffyDisplay.FLEX : TaffyDisplay.NONE)
                        .width(both ? vThickness : 0f)
                        .height(both ? hThickness : 0f));
    }

    /** The square between the two bars, exposed for styling. */
    public UINode corner() {
        return corner;
    }

    public boolean isScrollbarsVisible() {
        return scrollbarsVisible;
    }

    /**
     * Hides the bars while leaving the view fully scrollable by wheel and by API.
     *
     * <p>Not expressible in CSS: the bars' {@code display} is runtime state written at INLINE origin,
     * which an ordinary stylesheet rule cannot outrank (a deliberate {@code !important} still can,
     * which is the right answer -- a theme that really means it should win). Distinct from {@code overflow: hidden}, which also
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
        StyleGroup.inlinePipeline(bar.getStyle().getLayoutGroup(),
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

    /**
     * Scrolls, and keeps the bars with it.
     *
     * <p>Was an override of the element's own {@code setScroll}, which this engine has no hook for —
     * scroll is the BOX's and a node cannot intercept a write to it. That is not a loss: everything
     * that scrolls this view already goes through one of the entry points below, and the post-layout
     * refresh catches whatever does not. What it does mean is that the resync is driven by geometry
     * rather than by a call, which is the honest direction: the bar's state is derived from measured
     * sizes and nothing else.</p>
     */
    public ScrollerView setScroll(float left, float top) {
        scrollAimingAt(left, top);
        if (!syncing) refreshScrollers();
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
     *
     * <p>A standing POST-LAYOUT hook rather than an {@code onLayoutChanged} override, which this
     * engine has no counterpart for: layout is one pass with no feedback into it, and anything that
     * must READ a measured box goes here. It also covers what the old {@code setScroll} override
     * did — a smooth scroll moves the offset over several frames and the thumb has to travel with
     * it, which now falls out of the hook running every frame rather than needing a second ticker
     * started from inside a setter.</p>
     */
    @Override
    protected void connected() {
        // STANDING, for this view's whole life in the tree: bar state is derived from measured sizes,
        // so it has to be recomputed after every layout rather than after every call that might have
        // changed one. Dropped for free when the view leaves the tree or is frozen -- the hook is
        // owned by this node.
        document().animation().afterLayout(this, this::refreshAfterLayout);
    }

    private boolean refreshAfterLayout(float deltaSeconds) {
        if (!syncing) refreshScrollers();
        return true;
    }


    /**
     * Whether a caller's content moves with the view, or the subclass moves it itself.
     *
     * <h3>Exemption only cancels the offset of the box that HOSTS you</h3>
     *
     * <p>The bars, the corner and anything else beside the slot are direct children of this view, so
     * {@code setScrollExempt(true)} on one of them cancels this view's own scroll and they hold still.
     * A node a subclass appends is NOT beside them — it lands in the slot, one level further down, and
     * by then the offset is already baked into the slot's matrix. {@code BoxTree.compose} says so where
     * it applies the rule: <em>"an element that wanted to opt out any further down would be undoing an
     * offset already baked into its parent's matrix."</em> So the exemption is a no-op there, silently.
     * </p>
     *
     * <p><b>What that cost.</b> {@code TextEditor} positions its rows in DOCUMENT coordinates and moves
     * them with one matrix per layer — Monaco's {@code linesContent} — so its text viewport, gutter and
     * fold column are all {@code setScrollExempt(true)} and have been since they were written. All three
     * were in the slot, so all three scrolled anyway: the content moved by {@code scrollTop} from the
     * slot AND by {@code scrollTop} from the layer's own transform, arriving at <b>twice</b> the offset.
     * Measured at {@code scrollTop = 300}: the layer's transform read {@code translate(0, -300)} and its
     * {@code worldY} read {@code -600}.</p>
     *
     * <p>Doubling does not look like doubling. Which rows are REALISED is computed from {@code scrollTop}
     * and which rows are VISIBLE from twice it, so the two windows drift apart as you scroll: at
     * {@code scrollTop = 160} the realised rows were 8..40 while only those from 22 up were on screen —
     * a band of text at the top of the editor and blank below it, growing until past a viewport's worth
     * the two sets stop overlapping and the editor goes empty. At {@code scrollTop = 0} they coincide
     * exactly, which is why it was reported as appearing "the second I scroll down" and read as text
     * being destroyed rather than as text being drawn twice as far away as it was placed.</p>
     *
     * <p>The old engine could not reach this: its {@code ScrollerView} had no shadow root — <em>"your
     * children are ordinary children; there is no viewport or content wrapper to reach"</em> — so a
     * child was hosted by the scrolling box itself and its exemption applied.</p>
     */
    protected final void setContentScrollExempt(boolean exempt) {
        viewport.setScrollExempt(exempt);
    }
}
