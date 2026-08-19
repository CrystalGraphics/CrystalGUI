package com.crystalgui.ui.elements;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIFrameTicker;
import com.crystalgui.ui.UIWindow;

import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;

/**
 * A determinate bar, or an indeterminate stripe.
 *
 * <h3>One widget for both, because a caller cannot always choose</h3>
 *
 * <p>A transfer knows its total; a manifest resolve does not; and the <em>same</em> job can start
 * indeterminate and become determinate the moment a {@code Content-Length} arrives. Two widgets would mean
 * a caller swapping one for the other mid-flight, which is a rebuild of the element being drawn — the trap
 * this project has paid for at the command palette's key chips and again at the editor's gutter arrows.
 * {@link #setFraction} chooses, and a negative value means indeterminate.</p>
 *
 * <h3>Structure only</h3>
 *
 * <p>No size, no colour, no duration here. The track is this element, the fill is an internal child, and
 * everything about how they look is {@code default.css} plus the theme. The one number this class owns is
 * the fill's <em>proportion</em>, which is not a style but the data.</p>
 *
 * <h3>The indeterminate sweep is a ticker, and it is off unless it is needed</h3>
 *
 * <p>A determinate bar animates by having its width written; an indeterminate one has nothing to write, so
 * it needs a clock. {@link UIFrameTicker} is that clock, and the ticker returns {@code false} — dropping
 * itself — the moment the bar becomes determinate or leaves the tree. A permanently-registered ticker on
 * a widget that is usually determinate is a frame cost paid by every screen that shows one.</p>
 */
public class ProgressBar extends UIElement implements UIFrameTicker {

    /** The moving part. Themed through this class, never sized here. */
    public static final String FILL_CLASS = "__fill__";

    /** On the bar itself while it has no total to measure against, so a theme can style the sweep. */
    public static final String INDETERMINATE_CLASS = "__indeterminate__";

    /** How much of the track one sweep of the indeterminate stripe occupies. */
    private static final float SWEEP_WIDTH = 0.3f;

    /** Sweeps per second. A rate, not a duration, so it reads the same as the CSS it replaces. */
    private static final float SWEEP_RATE = 0.9f;

    private final UIElement fill;

    private float fraction = -1f;
    private float sweep;
    private boolean ticking;

    public ProgressBar() {
        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(),
                l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.STRETCH));

        this.fill = new UIElement();
        this.fill.addClass(FILL_CLASS);
        this.fill.setHitTest(false);
        addInternalChild(this.fill);

        // A bar is a readout. It is never a tab stop, and a press on it means nothing -- so it does not
        // take the pointer either, which lets the row it sits in stay clickable through it.
        setHitTest(false);

        setFraction(-1f);
    }

    /**
     * How full, 0..1 — or <b>negative for indeterminate</b>.
     *
     * <p>Clamped rather than rejected. A caller reporting 1.4 has a bug, and refusing to draw is a worse
     * way to tell them than drawing a full bar.</p>
     */
    public void setFraction(float value) {
        boolean nowIndeterminate = value < 0f;
        this.fraction = nowIndeterminate ? -1f : Math.min(1f, value);

        if (nowIndeterminate) {
            addClass(INDETERMINATE_CLASS);
            startTicking();
            applySweep();
        } else {
            removeClass(INDETERMINATE_CLASS);
            // The ticker drops itself on its next tick; nothing to unregister.
            float percent = this.fraction * 100f;
            StyleGroup.importantPipeline(fill.getStyle().getLayoutGroup(),
                    l -> l.widthPercent(percent).marginLeft(0f));
        }
        invalidateStyleMatch();
    }

    /** What was last set — negative when indeterminate. */
    public float fraction() {
        return fraction;
    }

    public boolean isIndeterminate() {
        return fraction < 0f;
    }

    /** The fill, so a caller can put a class on it. Not for sizing — that is this class's one number. */
    public UIElement fill() {
        return fill;
    }

    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    /**
     * <b>The attach hook, and {@code ElementAdded} is not it.</b>
     *
     * <p>{@code registerTicker} is on {@link UIWindow}, and {@code ElementAdded} fires when this gains a
     * PARENT — inside its owner's constructor, before any window. A bar registered there found none and
     * never retried, so the sweep never started and nothing said why.</p>
     */
    @Override
    protected void onWindowChanged(UIWindow previous, UIWindow current) {
        if (current != null) startTicking();
    }

    /**
     * Registers the sweep, if there is a window and the bar needs one.
     *
     * <p>Idempotent by construction — {@code registerTicker} is {@code HashSet}-backed, so asking twice
     * costs a hash lookup and there is deliberately no unregister to pair with.</p>
     */
    private void startTicking() {
        if (ticking || !isIndeterminate()) return;
        UIWindow window = getAttachedWindow();
        if (window == null) return;
        ticking = true;
        window.registerTicker(this);
    }

    /** Drops itself the moment the bar is determinate or has left the tree. */
    @Override
    public boolean tickFrame(float deltaSeconds) {
        if (!isIndeterminate() || getAttachedWindow() == null) {
            ticking = false;
            return false;
        }
        sweep += deltaSeconds * SWEEP_RATE;
        if (sweep > 1f) sweep -= 1f;
        applySweep();
        return true;
    }

    /**
     * Places a fixed-width stripe along the track.
     *
     * <p>Travels from fully off the left to fully off the right, so the stripe enters and leaves rather
     * than appearing at the edge — which is what makes it read as motion rather than as a flicker.</p>
     */
    private void applySweep() {
        float travel = 1f + SWEEP_WIDTH;
        float left = (sweep * travel) - SWEEP_WIDTH;
        float visibleLeft = Math.max(0f, left);
        float visibleRight = Math.min(1f, left + SWEEP_WIDTH);
        float width = Math.max(0f, visibleRight - visibleLeft);

        float marginPercent = visibleLeft * 100f;
        float widthPercent = width * 100f;
        StyleGroup.importantPipeline(fill.getStyle().getLayoutGroup(),
                l -> l.marginLeftPercent(marginPercent).widthPercent(widthPercent));
    }
}
