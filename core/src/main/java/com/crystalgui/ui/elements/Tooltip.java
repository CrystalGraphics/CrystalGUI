package com.crystalgui.ui.elements;

import com.crystalgui.core.data.Transform2D;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.AnchoredPlacement;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIFrameTicker;
import com.crystalgui.ui.UIWindow;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import org.joml.Vector2f;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * A tooltip — an element promoted into the {@linkplain UIElement#isInTopLayer() top layer} and kept
 * anchored to another element.
 *
 * <h3>Why this needs the top layer at all</h3>
 * <p>A tooltip's whole job is to draw <em>outside</em> the thing it describes. Before the top layer
 * existed there was no way to do that: {@code drawSubtree} paints depth-first under every ancestor's
 * scissor, so a tooltip on a row inside an {@code overflow: hidden} scroller was clipped to the
 * scroller. Promotion is what lets it escape ancestor clip, opacity and transform — see
 * {@link TopLayer#add}.</p>
 *
 * <h3>Placement</h3>
 * <p>Recomputed <b>every frame</b> from the anchor's current box rather than cached at show time.
 * That is not laziness — an anchor can scroll, animate, or be reflowed by a sibling's text wrapping,
 * and a cached position would silently drift away from it. Cost is a few floats per visible tooltip,
 * and only while one is visible.</p>
 *
 * <p>Placement is below the anchor by default and <b>flips above</b> when there is not enough room
 * below, then clamps horizontally into the containing block. This is the useful subset of the web's
 * CSS Anchor Positioning ({@code position-try-fallbacks}); the full property surface is deliberately
 * not implemented yet.</p>
 *
 * <p><b>No pixel values here.</b> The gap between anchor and tooltip is the tooltip's own
 * {@code margin} from {@code default.css} — Java positions it flush to the anchor edge and lets the
 * cascade decide the spacing, per this codebase's rule that widgets write structure, stylesheets
 * write geometry.</p>
 */
public class Tooltip extends UIElement {

    public static final String LABEL_CLASS = "__label__";

    private final UIText label;

    @Nullable
    private UIElement anchor;
    private boolean placementTickerRunning;

    public Tooltip() {
        this("");
    }

    public Tooltip(String text) {
        this.label = new UIText(text == null ? "" : text);
        this.label.addClass(LABEL_CLASS);
        this.label.setHitTest(false);
        addInternalChild(this.label);

        // A tooltip is decoration: it must never eat the pointer, or hovering the tooltip that
        // appeared under the cursor would count as leaving the anchor, hiding it, which un-hovers
        // the tooltip, which shows it again — a flicker loop. The web marks tooltips
        // pointer-events: none for exactly this reason.
        setHitTest(false);

        setHidden(true);
    }

    /**
     * A closed tooltip is {@code display: none}, exactly as a closed popover is on the web.
     *
     * <p>This is not cosmetic. A tooltip lives as an internal child of its anchor so the cascade
     * works, and an ordinary child participates in its parent's flex flow — so a <em>hidden</em>
     * tooltip would silently pad every element that had one. {@code display: none} takes it out of
     * Taffy's layout entirely, and is also short-circuited by both {@code drawSubtree} and
     * {@code elementHitTest}, so one property covers layout, paint and input together.</p>
     */
    private void setHidden(boolean hidden) {
        StyleGroup.importantPipeline(getStyle().getLayoutGroup(),
                l -> l.display(hidden ? TaffyDisplay.NONE : TaffyDisplay.FLEX));
    }

    /**
     * Attaches a hover tooltip to {@code anchor} and returns it.
     *
     * <p>This lives here rather than as {@code UIElement.setTooltip} on purpose. {@link UIElement} is
     * the core DOM node that every widget is built on; a tooltip is a widget. Putting the wiring on
     * {@code UIElement} inverted that — core would import {@code ui.elements} — and it grew the class
     * every element in the tree pays for by a field and three methods, for a feature most elements
     * never use. Here, the cost is borne only by trees that actually have a tooltip.</p>
     *
     * <p>The tooltip becomes an <b>internal child of the anchor</b>, which keeps the cascade behaving
     * the way the web's does: it inherits {@code color}, {@code font-family} and the rest from where
     * it sits in the tree, not from wherever it happens to paint. Promotion moves only its Taffy node
     * and its paint/hit-test entry. Being internal, it is skipped by public traversal and by
     * {@code UIDescriptionCodec}, like every other internal child.</p>
     *
     * <p>Shown on {@code mouseenter}, hidden on {@code mouseleave}. Deliberately no delay: a delay is
     * a timing value, and timing values belong in the cascade rather than hard-coded here — doing it
     * properly means a real CSS property, which is separate work.</p>
     */
    public static Tooltip attach(UIElement anchor, String text) {
        Objects.requireNonNull(anchor, "anchor");
        Tooltip tooltip = new Tooltip(text);
        anchor.addInternalChild(tooltip);

        // Listeners are attached exactly once, here, against a tooltip that is created in the same
        // breath. The earlier UIElement.setTooltip could be called repeatedly — and a
        // set(text)/set(null)/set(text) cycle silently attached a second pair every time.
        anchor.onMouseEnter.attachListener((el, event) -> tooltip.showFor(anchor), false, false);
        anchor.onMouseLeave.attachListener((el, event) -> tooltip.hide(), false, false);
        return tooltip;
    }

    /** Detaches a tooltip created by {@link #attach}, hiding it and removing it from its anchor.
     * The anchor's hover listeners become inert rather than being removed — they hold only this
     * instance, which no longer has anywhere to show. */
    public void detach() {
        hide();
        UIElement parent = getParent();
        if (parent != null) parent.removeInternalChild(this);
    }

    /** The internal text element, so callers can style or measure it without opening the tree. */
    public UIText getLabel() {
        return label;
    }

    public Tooltip setText(String text) {
        label.setText(text == null ? "" : text);
        return this;
    }

    public String getText() {
        return label.getText();
    }

    /** A tooltip owns its label; it has no public content slot. */
    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    @Nullable
    public UIElement getAnchor() {
        return anchor;
    }

    // ── Show / hide ─────────────────────────────────────────────────────────

    /**
     * Promotes this tooltip and anchors it to {@code anchor}. Idempotent — calling it again while
     * already shown just re-anchors and raises.
     */
    public Tooltip showFor(UIElement anchor) {
        if (anchor == null || anchor.getAttachedWindow() == null) return this;
        this.anchor = anchor;

        setHidden(false);
        addToTopLayer();
        reposition();

        if (!placementTickerRunning) {
            placementTickerRunning = true;
            UIWindow window = getAttachedWindow();
            if (window != null) window.registerTicker(new PlacementTicker());
        }
        return this;
    }

    /** Demotes and detaches from its anchor. The placement ticker drops itself on the next frame. */
    public Tooltip hide() {
        this.anchor = null;
        removeFromTopLayer();
        setHidden(true);
        return this;
    }

    public boolean isShown() {
        return anchor != null && isInTopLayer();
    }

    /**
     * Re-place once this element's own box is known.
     *
     * <p>{@link #showFor} runs before the promoted node has ever been laid out, so at that moment
     * this tooltip's width and height are both 0 — and flipping and clamping are decided by exactly
     * those. Without this hook the first frame is placed as if the tooltip were a point, and only
     * the next frame's ticker corrects it: a visible one-frame jump.</p>
     *
     * <p>Same shape as {@code UIText}, which also re-derives geometry after layout and pushes it back
     * at IMPORTANT origin. It settles for the same reason: {@code replaceOrPutCandidate} no-ops on an
     * unchanged value, so the extra pass stops re-dirtying the tree as soon as placement holds still.</p>
     */
    @Override
    protected void onLayoutChanged() {
        super.onLayoutChanged();
        if (isShown()) reposition();
    }

    /**
     * Re-runs placement against the anchor's current box.
     *
     * <p>The geometry — reading the anchor through the transform chain rather than its layout box,
     * flipping above when cramped, clamping into the containing block — lives in
     * {@link AnchoredPlacement}, extracted here when {@code Popover} became its second consumer. A
     * tooltip is just {@code Side.BOTTOM} with no offset.</p>
     */
    public void reposition() {
        AnchoredPlacement.place(this, anchor, AnchoredPlacement.Side.BOTTOM, 0f);
    }

    /** Keeps placement current while shown, then drops itself. Registration is idempotent
     * ({@code HashSet}-backed) but the flag avoids re-registering on every {@code showFor}. */
    private final class PlacementTicker implements UIFrameTicker {
        @Override
        public boolean tickFrame(float deltaSeconds) {
            if (!isShown()) {
                placementTickerRunning = false;
                return false;
            }
            reposition();
            return true;
        }
    }
}
