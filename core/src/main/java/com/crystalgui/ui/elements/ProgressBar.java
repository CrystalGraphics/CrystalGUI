package com.crystalgui.ui.elements;

import com.crystalgui.ui.contract.WidgetContracts;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.StateTypes;
import com.crystalgui.ui.contract.State;
import com.crystalgui.serialization.StateMap;
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

    /**
     * The filled fraction, where a negative value means indeterminate.
     *
     * <p>Written unconditionally rather than omitted at a default, because {@code -1} IS a meaningful
     * value here (indeterminate) and omitting it would be indistinguishable from a bar nobody has set.
     */
    public static final State<ProgressBar, Float> FRACTION =
            State.of("fraction", StateTypes.FLOAT, ProgressBar::fraction, ProgressBar::setFraction, -1f);

    public static final WidgetContract<ProgressBar> CONTRACT = WidgetContracts.register(
            WidgetContract.of(ProgressBar.class, "progressbar")
                    .state(FRACTION)
                    .primary(FRACTION)
                    .build());

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

    /** Whether {@link #setFraction} has ever run, so the constructor's own call is never elided. */
    private boolean applied;

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
        float resolved = nowIndeterminate ? -1f : Math.min(1f, value);

        /*
         * IDEMPOTENT, AND THE GUARD IS NOT A MICRO-OPTIMISATION.
         *
         * Every other state setter in the engine already early-returns on an unchanged value --
         * Slider.setValue by hand, UIText/TextField/Switch through Property.set -- and the engine
         * relies on it: MachinePanel.mirror's javadoc states the consequence as a rule, that "calling
         * this more often than necessary costs a few comparisons, not traffic". This setter was the
         * one exception, so that rule was simply FALSE for any panel mirroring a bar every tick.
         * notifyStateChanged() marked the bar dirty on every call, so an IDLE window sent a
         * ui/stateDelta per tick, forever, carrying a value that had not moved -- and invalidateStyleMatch()
         * beside it put a full selector re-match on the element every frame.
         *
         * Invisible for as long as every server-side mirror was gated behind a dirty flag of its own,
         * which is what MachinePanel does and why the worked example never showed it. The first panel
         * to mirror unconditionally -- on the honest grounds that the setters are idempotent -- turned
         * a quiet window into constant traffic, and nothing failed.
         *
         * `applied` rather than a bare equality test, because the CONSTRUCTOR's own setFraction(-1f)
         * has to run: the field starts at -1, so a plain guard would skip the class and the ticker
         * that make an indeterminate bar indeterminate.
         */
        if (applied && resolved == this.fraction) return;
        applied = true;
        this.fraction = resolved;

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
        // A SERVER-DRIVEN BAR IS THE WHOLE POINT OF writeState BELOW, AND THIS IS WHAT DELIVERS IT.
        // Without it the fraction travels in the opening description and never again: the bar arrives
        // at whatever it was when the window opened and freezes there, with the server's own value
        // advancing correctly and nothing anywhere reporting a problem. It reads as the bar being
        // broken rather than as an update that was never announced, because the FIRST value is right.
        // Slider, Switch, Checkbox, TextField and UIText all notify from their setters; this and
        // Dropdown were given writeState without it.
        notifyStateChanged();
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
