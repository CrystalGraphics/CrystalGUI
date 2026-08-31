package com.crystalgui.widget.control;

import com.crystalgui.ui.contract.WidgetContracts;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.StateTypes;
import com.crystalgui.ui.contract.State;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.ShadowRoot;
import com.crystalgui.ui.dom.UINode;

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
 * it needs a clock. {@link Animation.Hook} is that clock, and the ticker returns {@code false} — dropping
 * itself — the moment the bar becomes determinate or leaves the tree. A permanently-registered ticker on
 * a widget that is usually determinate is a frame cost paid by every screen that shows one.</p>
 */
public class ProgressBar extends UINode {

    public static final Name NAME = Name.of("progressbar");

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

    /** The moving part. {@code progressbar::part(fill)} in a sheet — themed there, never sized here. */
    public static final String FILL_PART = "fill";

    /** On the bar itself while it has no total to measure against, so a theme can style the sweep. */
    public static final String INDETERMINATE_CLASS = "__indeterminate__";

    /** How much of the track one sweep of the indeterminate stripe occupies. */
    private static final float SWEEP_WIDTH = 0.3f;

    /** Sweeps per second. A rate, not a duration, so it reads the same as the CSS it replaces. */
    private static final float SWEEP_RATE = 0.9f;

    private final ShadowRoot shadow;
    private final UINode fill;

    private float fraction = -1f;

    /** Whether {@link #setFraction} has ever run, so the constructor's own call is never elided. */
    private boolean applied;

    private float sweep;
    private boolean ticking;

    public ProgressBar() {
        super(NAME);
        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(),
                l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.STRETCH));

        this.shadow = attachShadow();
        this.fill = new UINode();
        this.fill.set(Attribute.PART, FILL_PART);
        this.fill.setHitTest(false);
        shadow.append(this.fill);

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
         * Slider.setValue by hand, TextNode/TextField/Switch through Property.set -- and the engine
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
            // INLINE, not IMPORTANT. The fill's width is DATA -- what the bar is reporting -- so it has
            // to outrank the sheet's resting rule, and INLINE does. IMPORTANT would too, and the new
            // engine may not use it: it sits above every author origin including `!important`, so a
            // theme could never override the bar even deliberately, and it is what made the old
            // cascade the engine's only mutable box model.
            StyleGroup.inlinePipeline(fill.getStyle().getLayoutGroup(),
                    l -> l.widthPercent(percent).marginLeft(0f));
        }
        invalidateStyleMatch();
        // A SERVER-DRIVEN BAR IS THE WHOLE POINT OF writeState BELOW, AND THIS IS WHAT DELIVERS IT.
        // Without it the fraction travels in the opening description and never again: the bar arrives
        // at whatever it was when the window opened and freezes there, with the server's own value
        // advancing correctly and nothing anywhere reporting a problem. It reads as the bar being
        // broken rather than as an update that was never announced, because the FIRST value is right.
        // Slider, Switch, Checkbox, TextField and TextNode all notify from their setters; this and
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
    public UINode fill() {
        return fill;
    }

    /**
     * The attach hook. A bar constructed indeterminate has no document yet, so the sweep starts here.
     *
     * <p>The old engine used {@code onWindowChanged} and recorded why {@code ElementAdded} is not it:
     * that fires when the bar gains a PARENT, which is inside its owner's constructor and before any
     * window, so a bar registered there found none and never retried — the sweep never started and
     * nothing said why.</p>
     */
    @Override
    protected void connected() {
        startTicking();
    }

    /**
     * Registers the sweep, if the bar needs one.
     *
     * <p>The {@code ticking} flag is this class's own, because {@link Animation#every} is a plain list
     * add — registering twice would run the sweep twice per frame and advance it at double rate.</p>
     */
    private void startTicking() {
        if (ticking || !isIndeterminate()) return;
        if (document() == null) return;
        ticking = true;
        document().animation().every(this, this::tickFrame);
    }

    /**
     * Drops itself the moment the bar is determinate.
     *
     * <p><b>It no longer has to check whether it is still in the tree</b>, which the old contract made
     * every ticker's own business and named as a standing hazard — the hidden window that keeps
     * ticking. A hook is OWNED by a node here, so leaving the tree and being frozen both drop it, and
     * the one condition left is the one only this widget knows.</p>
     */
    private boolean tickFrame(float deltaSeconds) {
        if (!isIndeterminate()) {
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
        StyleGroup.inlinePipeline(fill.getStyle().getLayoutGroup(),
                l -> l.marginLeftPercent(marginPercent).widthPercent(widthPercent));
    }

}
