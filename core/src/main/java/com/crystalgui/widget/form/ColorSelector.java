package com.crystalgui.widget.form;

import com.crystalgui.ui.contract.RatePolicy;
import com.crystalgui.ui.contract.Event;
import com.crystalgui.ui.contract.WidgetContracts;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.StateTypes;
import com.crystalgui.ui.contract.State;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.core.property.Property;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.render.texture.ArgbMath;
import com.crystalgui.render.texture.CgUiColorField;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.ShadowRoot;
import com.crystalgui.ui.dom.UINode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.crystalgui.widget.overlay.Dropdown;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.control.Slider;
import com.crystalgui.ui.box.TextNode;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.widget.overlay.Tooltip;

/**
 * A colour picker: a hue ring around a saturation/value square, four channel sliders, and a hex field.
 *
 * <h3>One model, many views — which is the whole design</h3>
 * <p>Every control here is a <b>projection of one colour</b>, never an independent value. Drag the R
 * slider and the ring and square handles move with it; switch to HSV and the same colour is shown
 * through different channels. That is only coherent if there is exactly one source of truth and every
 * widget both reads from and writes to it, so {@link #color} is a {@link Property} and each control is
 * rebuilt from it whenever it changes.</p>
 *
 * <p><b>The loop closes safely because both ends suppress no-ops.</b> {@code Property.set} ignores an
 * equal value and drops re-entrant sets from inside its own emit, and {@code Slider.setValue}
 * early-returns when the value is unchanged. So pushing the model back into the control that just
 * changed emits nothing, and the cascade terminates in one pass rather than ringing.</p>
 *
 * <h3>Hue and saturation are RETAINED, not derived</h3>
 * <p>Hue is undefined for a grey: {@code toHsv(black)} reports 0. If the ring read its angle from the
 * colour each frame, dragging value down to black would snap it to red and throw away the hue the user
 * chose — and dragging back up would return the wrong colour. So hue and saturation live here, and the
 * ARGB is derived <em>from</em> them; the colour is only decomposed when it is set from outside.</p>
 *
 * <h3>Channel tracks are live</h3>
 * <p>Each slider's gradient runs from "this colour with that channel at its minimum" to "at its
 * maximum", so the G track restains as R is dragged. It is what makes the sliders readable as a picker
 * rather than as four unrelated bars, and it falls out of rebuilding them from the model.</p>
 */
public class ColorSelector extends UINode {

    public static final Name NAME = Name.of("colorselector");

    public static final State<ColorSelector, Mode> MODE =
            State.of("mode", StateTypes.enumOf(Mode.class),
                    ColorSelector::getMode, ColorSelector::setMode, Mode.values()[0]);

    /** What Reset goes back to. Applied BEFORE the live colour -- see the contract below. */
    public static final State<ColorSelector, Integer> ORIGINAL =
            State.of("original", StateTypes.INT,
                    ColorSelector::getOriginalColor, ColorSelector::setInitialColor, 0xFFFFFFFF);

    public static final State<ColorSelector, Integer> COLOR =
            State.of("color", StateTypes.INT, ColorSelector::getColor, ColorSelector::setColor, 0xFFFFFFFF);

    /**
     * The colour moved. {@code plan_ui_rewrite.md} M1: a ColorSelector could not report at all, so a
     * server-side colour picker was a control that showed a colour and could never be told one.
     *
     * <p>{@link EventKind#CHANGE} rather than {@link EventKind#VALUE}: there is a drag here, but what
     * the user is choosing is discrete -- a colour, not a position along a range -- and a server acting
     * on every intermediate hue is doing work nobody asked for. Throttled for the drag, and the
     * released colour always travels.</p>
     */
    public static final Event<ColorSelector, Integer> CHANGED = Event.of("change",
            (selector, sink) -> selector.onColorChanged.connect(sink::accept),
            new Event.Payload<Integer>() {
                @Override public <T> void write(StateMap<T> out, Integer value) {
                    out.putInt("color", value);
                }
                @Override public <T> Integer read(StateMap<T> in) {
                    return in.getInt("color", 0xFFFFFFFF);
                }
            }, RatePolicy.DRAGGING);

    /**
     * MODE, then ORIGINAL, then COLOR -- the order the hand-written readState used, in statement order
     * where nothing could see it.
     *
     * <p>Mode first because it decides how a colour is interpreted; original before colour because
     * {@code setInitialColor} moves the live colour with it, so taking it afterwards would overwrite
     * the value that was actually sent with the one Reset goes back to.</p>
     */
    public static final WidgetContract<ColorSelector> CONTRACT = WidgetContracts.register(
            WidgetContract.of(ColorSelector.class, "colorselector")
                    .state(MODE)
                    .state(ORIGINAL)
                    .state(COLOR)
                    .event(CHANGED)
                    .primary(COLOR)
                    .build());

    public static final String WHEEL_PART = "wheel";
    public static final String RING_PART = "ring";
    public static final String SQUARE_PART = "square";
    public static final String RING_HANDLE_PART = "ring-handle";
    public static final String SQUARE_HANDLE_PART = "square-handle";
    /** The column left of the channels: the swatch pair, then the wheel. */
    public static final String LEFT_PART = "left";
    /** The column right of the wheel: mode chooser, channel rows, hex. */
    public static final String SIDE_PART = "side";
    public static final String CHANNELS_PART = "channels";
    public static final String CHANNEL_ROW_PART = "channel-row";
    public static final String HEX_ROW_PART = "hex-row";
    /** The before/after pair above the channels. */
    public static final String SWATCHES_PART = "swatches";
    public static final String SWATCH_ORIGINAL_PART = "swatch-original";
    public static final String SWATCH_NEW_PART = "swatch-new";

    /**
     * How the four channel rows present the colour.
     *
     * <p>A display choice only — switching modes never changes the colour, exactly as Unity's does.
     * The three are its three, and the pair of RGB modes exist because 0–255 and 0–1 are both in daily
     * use and converting in your head is precisely what a picker should save you.</p>
     */
    public enum Mode {
        RGB_255("RGB 0-255"),
        RGB_01("RGB 0-1.0"),
        HSV("HSV");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** The colour, ARGB. The single source of truth every control here reads and writes. */
    public final Property<Integer> color = new Property<>(0xFFFFFFFF);

    /** Fires after any control changes {@link #color} — the signal a host binds to. */
    public final Signal.Value<Integer> onColorChanged = new Signal.Value<>();

    // Retained rather than derived — see the class docs. Alpha lives in the colour itself, which has
    // no such ambiguity.
    private float hue;
    private float saturation = 1f;
    private float value = 1f;

    private Mode mode = Mode.RGB_255;

    private final ShadowRoot shadow;

    private final UINode left = new UINode();
    private final UINode wheel = new UINode();
    private final UINode ring = new UINode();
    private final UINode square = new UINode();
    private final UINode ringHandle = new UINode();
    private final UINode squareHandle = new UINode();
    private final UINode side = new UINode();
    private final UINode channels = new UINode();
    private final Dropdown modeChooser = new Dropdown("Mode");
    private final TextField hexField = new TextField();
    private final UINode swatches = new UINode();
    private final UINode originalSwatch = new UINode();
    private final UINode newSwatch = new UINode();

    /**
     * What the picker was opened on — the value the left swatch shows and restores.
     *
     * <p>Deliberately NOT tracked by {@link #setColor}. A picker is a bounded editing session, and the
     * "original" is the colour that session started from; if every programmatic set moved it, a host
     * that pushes the live colour back for any reason would quietly erase the thing the user is meant to
     * be comparing against — and the reset swatch would restore the edit rather than undo it.
     * {@link #setInitialColor} is the explicit way to say "this is a new session".</p>
     */
    private int originalColor = 0xFFFFFFFF;

    private final List<ChannelRow> rows = new ArrayList<>();

    /** True while the model is pushing values into the controls, so their echoes are ignored. */
    private boolean updating;

    /**
     * The ring's inner edge, as a fraction of its outer — <b>must match the shader's {@code _InnerRadius}
     * default</b>, which is what actually draws the band.
     */
    private static final float RING_INNER = 0.72f;

    /**
     * Where the handle sits, as a fraction of the ring's FULL width.
     *
     * <p>Derived rather than tuned by eye: the band runs from {@link #RING_INNER} to 1 in half-width
     * units, so its middle is their mean, halved again to convert half-widths to widths. Guessing 0.44
     * instead of this put the handle a pixel outside the band's centre line, which is visible the moment
     * you look at the ring rather than at the colour.</p>
     */
    private static final float BAND_CENTRE = (RING_INNER + 1f) / 2f / 2f;

    public ColorSelector() {
        super(NAME);
        this.shadow = attachShadow();
        // Wheel on the left, everything else in a column on the right — the reference layout, and the
        // one that gives the channel tracks room to be read. Stacked vertically they were a narrow
        // strip under a large wheel, which is the widest thing in the widget wasting the space the
        // things you actually drag need.
        side.set(Attribute.PART, SIDE_PART);
        left.set(Attribute.PART, LEFT_PART);
        // Swatches ABOVE the wheel rather than over the channels. They are a comparison, and the thing
        // being compared against is chosen on the ring — putting them at the top of the same column
        // keeps the before/after next to the control that changes it, and leaves the channel column to
        // be four bars and a hex field rather than five stacked strips.
        buildSwatches();
        buildWheel();
        shadow.append(left);
        buildModeChooser();
        buildChannels();
        buildHexRow();
        shadow.append(side);

        color.changed.connect((from, to) -> {
            onColorChanged.emit(to);
            refresh();
        });
        decompose(color.get());
        refresh();
    }

    // ── Structure ───────────────────────────────────────────────────────────

    private void buildWheel() {
        wheel.set(Attribute.PART, WHEEL_PART);
        ring.set(Attribute.PART, RING_PART);
        square.set(Attribute.PART, SQUARE_PART);
        ringHandle.set(Attribute.PART, RING_HANDLE_PART);
        squareHandle.set(Attribute.PART, SQUARE_HANDLE_PART);
        // Handles never take the pointer: a press must reach the surface underneath so a click ON the
        // handle keeps dragging rather than doing nothing, which is what makes grabbing one feel solid.
        ringHandle.setHitTest(false);
        squareHandle.setHitTest(false);

        ring.append(square);
        ring.append(ringHandle);
        square.append(squareHandle);
        wheel.append(ring);
        left.append(wheel);

        // ABSOLUTE tracking, not delta-from-grab. A slider grabs its thumb, so a delta is right there;
        // here the press point IS the value being chosen — clicking the far side of the ring must jump
        // to that hue rather than nudge from the old one.
        dragSurface(ring, (x, y) -> {
            // A press anywhere inside the ring's box counts, including the middle. The square sits on
            // top and takes its own presses first, so the only thing this catches is the band and the
            // corners — and a corner press picking the nearest hue is better than doing nothing.
            hue = hueFromOffset(x - ring.box().width() * 0.5f,
                    y - ring.box().height() * 0.5f);
            applyHsv();
        });
        dragSurface(square, (x, y) -> {
            saturation = clamp01(x / Math.max(1f, square.box().width()));
            // 1 - y because value runs UP the square while coordinates run down it.
            value = 1f - clamp01(y / Math.max(1f, square.box().height()));
            applyHsv();
        });
    }

    /**
     * The hue at an offset from the ring's centre — <b>the exact inverse of what the shader draws</b>.
     *
     * <p>{@code gui_color_field.shader} computes {@code fract(atan2(x, y) / 2π + 0.5)} with y pointing
     * DOWN, and this has to be the same function or the ring lies about itself. Three conventions were
     * in play before: the shader's, a handle placement missing the half-turn, and a click path that
     * negated y — so hue 300 drew upper-left while the colour under it was upper-right, and clicking
     * the magenta side of the ring returned green.</p>
     *
     * @param dx offset right of centre
     * @param dy offset BELOW centre — screen direction, not maths direction
     */
    public static float hueFromOffset(float dx, float dy) {
        double angle = Math.atan2(dx, dy) / (Math.PI * 2) + 0.5;
        return (float) (angle - Math.floor(angle));
    }

    /**
     * Where a hue sits on the ring, as offsets from its centre in fractions of the full width.
     *
     * @return {@code [dx, dy]}, dy positive downward — inverse of {@link #hueFromOffset}
     */
    public static float[] offsetForHue(float hue, float radius) {
        double theta = (hue - 0.5) * Math.PI * 2;
        return new float[] { (float) (radius * Math.sin(theta)), (float) (radius * Math.cos(theta)) };
    }

    /** Press-and-drag on a surface, reported in that surface's own local coordinates. */
    private void dragSurface(UINode surface, java.util.function.BiConsumer<Float, Float> onPoint) {
        // Target phase only (both flags false): a press on the SQUARE must not also reach the ring
        // underneath it, or one click would set the hue and the saturation at once.
        surface.onMouseDown.attachListener((el, event) -> {
            if (!isEnabled()) return;
            float rawX = event.getPosition().x(), rawY = event.getPosition().y();
            // A synthesized activation press (Space/Enter) carries the cursor's position, which is not
            // over this surface — honouring it would fling the colour somewhere arbitrary. Slider
            // discards those the same way.
            if (!surface.containsSurfacePoint(rawX, rawY)) return;

            var local = surface.toLocal(rawX, rawY);
            onPoint.accept(withinX(surface, local.x()), withinY(surface, local.y()));

            var window = document();
            if (window == null) return;
            // mx/my arrive in the same space screenToLocal produces — UIDragController converts through
            // the source's own transform before calling this — so they need the same origin shift and
            // must NOT be converted a second time.
            Drag.start(surface, rawX, rawY,
                    (mx, my, sx, sy, dx, dy) ->
                            onPoint.accept(withinX(surface, mx), withinY(surface, my)));
        }, false, false);
    }

    /**
     * A {@code screenToLocal} result shifted so 0 is the surface's own left edge.
     *
     * <p><b>{@code screenToLocal} does not do this for you.</b> It maps into the space the element's box
     * is expressed in, and the element's own layout position is part of that space — so the raw result
     * is offset by wherever the surface sits. {@code Slider} subtracts its {@code contentLeft()} for the
     * same reason. Skipping the shift made every coordinate a few hundred pixels too large, which
     * clamped to 1 and put every single click in the square's bottom-right corner.</p>
     */
    private static float withinX(UINode surface, float localX) {
        return localX - surface.box().x();
    }

    private static float withinY(UINode surface, float localY) {
        return localY - surface.box().y();
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /**
     * The before/after pair, ported from Unity's — left is where you started, right is where you are.
     *
     * <p><b>Why a picker needs it.</b> Every other control here answers "what colour is this?"; none of
     * them answers "is it better than what I had?", which is the question an edit is actually being made
     * to settle. Held side by side the comparison is direct, and the left half doubles as the undo:
     * Unity's tooltip is literally <i>"click this swatch to reset the color picker to this value"</i>.</p>
     *
     * <p>Both swatches are a {@code GRADIENT} whose two stops are the SAME colour — a flat fill that
     * still composites over the transparency checkerboard, so a half-alpha colour reads as half-alpha
     * rather than as a darker opaque one. Reusing the channel tracks' mode rather than adding a "flat"
     * one keeps the checker's size and phase identical everywhere alpha is shown in the widget.</p>
     */
    private void buildSwatches() {
        swatches.set(Attribute.PART, SWATCHES_PART);
        originalSwatch.set(Attribute.PART, SWATCH_ORIGINAL_PART);
        newSwatch.set(Attribute.PART, SWATCH_NEW_PART);
        // Decomposed, like a hex edit and unlike a slider drag: restoring the original names a colour
        // outright, so the hue it implies is the one wanted — including when the original is a grey,
        // whose retained hue would otherwise survive a reset that is supposed to undo everything.
        originalSwatch.onMouseDown.attachListener((el, event) -> setColor(originalColor), false, true);
        // Both halves stay HITTABLE, including the right one, which has no click behaviour at all: a
        // tooltip is driven by hover, so setHitTest(false) would silently take the label away with the
        // click. Pressing it does nothing, which is the correct response to "make it the colour it
        // already is".
        //
        // Unity labels both, and the left one has to be labelled: a swatch that resets the picker when
        // clicked is not guessable from a coloured rectangle, and finding it by accident means losing
        // the edit you were making.
        Tooltip.attach(originalSwatch, "The original colour. Click to reset the picker to it.");
        Tooltip.attach(newSwatch, "The new colour.");
        swatches.append(originalSwatch);
        swatches.append(newSwatch);
        left.append(swatches);
    }

    private void buildModeChooser() {
        for (Mode m : Mode.values()) modeChooser.addOption(m.label());
        modeChooser.select(mode.label());
        modeChooser.attachSelectionListener(index -> {
            if (index < 0 || index >= Mode.values().length) return;
            setMode(Mode.values()[index]);
        });
        side.append(modeChooser);
    }

    private void buildChannels() {
        channels.set(Attribute.PART, CHANNELS_PART);
        for (int i = 0; i < 4; i++) {
            ChannelRow row = new ChannelRow(i);
            rows.add(row);
            channels.append(row.root);
        }
        side.append(channels);
    }

    private void buildHexRow() {
        UINode row = new UINode();
        row.set(Attribute.PART, HEX_ROW_PART);
        TextNode label = new TextNode("Hexadecimal");
        label.setHitTest(false);
        // IMMEDIATE, not the ON_COMMIT default. A picker's fields are a live view of one colour: a hex
        // that only published on Enter looked completely inert, because the ring, square and four
        // sliders all read the model and the model had not changed. An unparseable prefix is simply
        // ignored below, so typing through "B0", "B00", "B00D" costs nothing.
        hexField.setUpdateMode(TextField.UpdateMode.IMMEDIATE);
        hexField.attachListener(text -> {
            if (updating) return;
            Integer parsed = parseHex(text);
            if (parsed != null) {
                // Decomposed because a hex edit names a colour outright — the hue it implies is the one
                // the user asked for, unlike a value drag where their chosen hue has to survive.
                decompose(parsed);
                color.set(parsed);
            }
        });
        row.append(label);
        row.append(hexField);
        side.append(row);
    }

    // ── Public API ──────────────────────────────────────────────────────────

    public int getColor() {
        return color.get();
    }

    public ColorSelector setColor(int argb) {
        decompose(argb);
        color.set(argb);
        return this;
    }

    /** What the left swatch shows and restores — the colour this editing session started from. */
    public int getOriginalColor() {
        return originalColor;
    }

    /**
     * Starts a new editing session: sets the colour <b>and</b> the original it will be compared against.
     *
     * <p>This is what a host calls when opening the picker on something. Plain {@link #setColor} moves
     * only the current colour, so a host that pushes an updated value mid-session does not destroy the
     * before/after comparison — and does not turn the reset swatch into a redo of its own write.</p>
     */
    public ColorSelector setInitialColor(int argb) {
        this.originalColor = argb;
        setColor(argb);
        // Explicit, because the repaint normally rides on color.changed and Property.set suppresses an
        // equal value. Re-opening the picker on the colour it already held would otherwise leave the
        // left swatch showing the PREVIOUS session's original — the one case where nothing changing is
        // exactly when the swatch most needs to.
        refresh();
        return this;
    }

    public Mode getMode() {
        return mode;
    }

    /** Switches how the channels are presented. Never changes the colour. */
    public ColorSelector setMode(Mode value) {
        if (value == null || value == this.mode) return this;
        this.mode = value;
        modeChooser.select(value.label());
        refresh();
        return this;
    }

    // ── The model ───────────────────────────────────────────────────────────

    /**
     * Reads hue/saturation/value out of an externally-supplied colour.
     *
     * <p>Only called when the colour arrives from OUTSIDE — never from this widget's own controls,
     * which already know the hue they meant. That is what stops a grey from resetting the ring.</p>
     */
    private void decompose(int argb) {
        float[] hsv = ArgbMath.toHsv(argb);
        // ONLY the hue is retained, and only when the colour has none to give. Saturation and value are
        // never ambiguous — white's saturation genuinely IS 0 — so keeping them was simply wrong: typing
        // FFFFFF left saturation at whatever it had been, so the square's handle stayed hard right and
        // the square went on showing the previous hue while every other control read white.
        if (hsv[1] > 0f) hue = hsv[0];
        saturation = hsv[1];
        value = hsv[2];
    }

    /**
     * Rebuilds the colour from the retained hue/saturation/value, preserving alpha.
     *
     * <p><b>HSV is the model here; the ARGB is a projection of it, and the projection is lossy.</b> At
     * {@code saturation == 0} every hue composes to the same grey, and at {@code value == 0} to the same
     * black — so a real move on the ring can leave the colour byte-identical. {@code Property.set}
     * suppresses an equal value, quite correctly, which means {@code color.changed} does not fire and
     * the {@link #refresh()} that normally rides on it never runs.</p>
     *
     * <p>The result is a widget that looks broken rather than one that looks unchanged: the ring handle
     * does not move and the SV square goes on drawing the <em>previous</em> hue, so the ring reads as
     * dead until something makes the colour differ. It shipped that way because the gallery opens on a
     * saturated purple; a node's colour defaults to {@code vec4(1.0, 1.0, 1.0, 1.0)}, which is white,
     * which is exactly the case that has no hue to show.</p>
     */
    private void applyHsv() {
        int before = color.get();
        int next = ArgbMath.fromHsv(hue, saturation, value, (before >>> 24) & 0xFF);
        color.set(next);
        // The colour did not move, but the MODEL did — so drive the refresh by hand. Not an else-branch
        // on a listener: this is the one caller that knows a suppressed set still changed something.
        if (next == before) refresh();
    }

    /** Pushes the model into every control. Guarded, so the echoes it provokes are ignored. */
    private void refresh() {
        if (updating) return;
        updating = true;
        try {
            int argb = color.get();
            paintWheel();
            // originalSwatch is added first (see buildSwatches), so it sits on the LEFT — its own outer
            // edge is the left one, and the new swatch's is the right.
            paintSwatch(newSwatch, argb, false);
            paintSwatch(originalSwatch, originalColor, true);
            for (ChannelRow row : rows) row.refresh(argb);
            hexField.setText(toHex(argb));
        } finally {
            updating = false;
        }
    }

    /** Matches {@code colorselector .__swatches__ .__swatch-original__}/{@code -new__}'s CSS radius —
     * kept here rather than read back out of the cascade because there is nothing to read: see
     * {@link CgUiColorField#setCornerRadius(float, float, float, float, float, float, float, float)}'s
     * own doc for why this specific shape can't go through {@code border-radius} at all. */
    private static final float SWATCH_CORNER_RADIUS = 3f;

    /**
     * A flat fill that still shows its alpha, by being a gradient with both stops the same.
     *
     * <p>A plain {@code background: #RRGGBBAA} would be blended against whatever is behind the widget,
     * so a 50%-alpha colour would render as a darker opaque one and the two swatches would be
     * indistinguishable at every alpha. The checkerboard is the only thing that makes transparency
     * legible as transparency, and {@code GRADIENT} already composites over it.</p>
     *
     * <p>Rounded only on its OWN outer edge — left for the original swatch, right for the new one — so
     * the pair still reads as one pill with a straight seam down the middle, the same shape the CSS
     * {@code border-*-radius} pair on {@code CgUiRoundedRect}-backed swatches elsewhere in this sheet
     * gets for free. This one can't get it for free: see {@code setCornerRadius}'s own doc.</p>
     */
    private static void paintSwatch(UINode swatch, int argb, boolean roundLeft) {
        CgUiColorField field = new CgUiColorField()
                .setMode(CgUiColorField.Mode.GRADIENT)
                .setGradient(argb, argb);
        float r = SWATCH_CORNER_RADIUS;
        if (roundLeft) {
            field.setCornerRadius(r, r, 0f, 0f, 0f, 0f, r, r);
        } else {
            field.setCornerRadius(0f, 0f, r, r, r, r, 0f, 0f);
        }
        swatch.generalStyle(g -> g.background(field));
    }

    private void paintWheel() {
        ring.generalStyle(g -> g.background(new CgUiColorField().setMode(CgUiColorField.Mode.HUE_RING)));
        square.generalStyle(g -> g.background(
                new CgUiColorField().setMode(CgUiColorField.Mode.SV_SQUARE).setHue(hue)));

        // PERCENTAGE insets, so the handles stay put under a resize without this widget ever knowing
        // its own pixel size — which it cannot know before layout has run anyway.
        squareHandle.layout(l -> l.leftPercent(percent(saturation)).topPercent(percent(1f - value)));
        // Placed by the inverse of the function the shader draws with, so the handle is on the colour it
        // names by construction rather than by a sign convention someone has to keep in their head.
        float[] offset = offsetForHue(hue, BAND_CENTRE);
        ringHandle.layout(l -> l
                .leftPercent(percent(0.5f + offset[0]))
                .topPercent(percent(0.5f + offset[1])));
    }

    private static float percent(float fraction) {
        return Math.max(0f, Math.min(1f, fraction)) * 100f;
    }

    // ── Channels ────────────────────────────────────────────────────────────

    /**
     * One labelled slider plus its numeric field.
     *
     * <p>Which channel it edits depends on the current {@link Mode}, so the row is a <b>slot</b> rather
     * than a fixed R or H — switching modes relabels and re-ranges the same four rows instead of
     * rebuilding them, which is what keeps the widget's structure stable across a mode change.</p>
     */
    private final class ChannelRow {
        private final int index;
        private final UINode root = new UINode();
        private final TextNode label = new TextNode("");
        private final Slider slider = new Slider();
        private final TextField field = new TextField();

        ChannelRow(int index) {
            this.index = index;
            root.set(Attribute.PART, CHANNEL_ROW_PART);
            label.setHitTest(false);
            slider.attachListener(v -> {
                if (updating) return;
                writeChannel(index, v);
            });
            // Live, like the hex field — every control here is a view of one colour, so a field that
            // published only on Enter would sit showing a number the rest of the widget disagreed with.
            field.setUpdateMode(TextField.UpdateMode.IMMEDIATE);
            field.attachListener(text -> {
                if (updating) return;
                try {
                    writeChannel(index, Float.parseFloat(text.trim()));
                } catch (NumberFormatException malformed) {
                    // Left as typed: a half-entered number is not an error, and snapping the field back
                    // mid-keystroke makes it impossible to type "0." at all.
                }
            });
            root.append(label);
            root.append(slider);
            root.append(field);
        }

        void refresh(int argb) {
            label.setText(channelName(mode, index));
            float max = channelMax(mode, index);
            float current = readChannel(argb, index);
            slider.setRange(0f, max);
            slider.setValue(current);
            field.setText(formatChannel(mode, current));
            // Belongs HERE and not in the constructor: a row is a slot, so a mode change re-ranges it
            // rather than rebuilding it, and a cap set once would stay at whatever mode happened to be
            // selected when the picker was created — five characters in 0-255 mode, three in 0-1.0.
            //
            // Sized to the longest value the mode can PRODUCE: "0.000" is five, "255" and "360" are
            // three. The field's width was measured against exactly those, so anything longer overflows
            // a box that was fitted rather than guessed.
            //
            // Text, not number. A fourth decimal never meant anything — writeChannel quantizes to 8
            // bits, so 0.6905 and 0.690 are the same byte — it only ever overran the box.
            field.setMaxLength(mode == Mode.RGB_01 ? 5 : 3);
            // H is the one channel a two-stop gradient cannot describe — hue 0 and hue 1 are both red,
            // so it gets the sweep instead. Every other channel, alpha included, is a gradient.
            //
            // The hue strip is drawn at FULL saturation and value, never the current ones. Passing the
            // live pair in made the strip wash out to near-white whenever the colour was pale, so the
            // one control whose job is "pick a hue" became unreadable exactly when a hue was hardest to
            // judge. It is a palette, not a preview.
            boolean isHue = mode == Mode.HSV && index == 0;
            slider.generalStyle(g -> g.background(isHue
                    ? new CgUiColorField().setMode(CgUiColorField.Mode.HUE_STRIP)
                    : new CgUiColorField()
                            .setMode(CgUiColorField.Mode.GRADIENT)
                            .setGradient(trackStop(argb, index, 0f), trackStop(argb, index, max))));
        }
    }

    private static String channelName(Mode mode, int index) {
        String[] names = mode == Mode.HSV
                ? new String[] { "H", "S", "V", "A" }
                : new String[] { "R", "G", "B", "A" };
        return names[index];
    }

    private static float channelMax(Mode mode, int index) {
        if (mode == Mode.RGB_01) return 1f;
        if (mode == Mode.HSV) return index == 0 ? 360f : 100f;
        return 255f;
    }

    private static String formatChannel(Mode mode, float value) {
        return mode == Mode.RGB_01
                ? String.format(Locale.ROOT, "%.3f", value)
                : String.valueOf(Math.round(value));
    }

    private float readChannel(int argb, int index) {
        float max = channelMax(mode, index);
        if (mode == Mode.HSV) {
            switch (index) {
                case 0: return hue * max;
                case 1: return saturation * max;
                case 2: return value * max;
                default: return ((argb >>> 24) & 0xFF) / 255f * max;
            }
        }
        int shift = new int[] { 16, 8, 0, 24 }[index];
        return ((argb >>> shift) & 0xFF) / 255f * max;
    }

    /**
     * A gradient stop for one track — the colour that channel would produce, forced opaque unless the
     * track <em>is</em> alpha.
     *
     * <p><b>Only the A track shows transparency.</b> Carrying the live alpha into the others meant that
     * at alpha 0 the R, G and B tracks were pure checkerboard: three controls showing nothing at all,
     * at exactly the moment you would want to see what colour you were about to make visible again.
     * Their job is to show what the channel does, and alpha is not their channel.</p>
     */
    private int trackStop(int argb, int index, float channelValue) {
        int stop = withChannel(argb, index, channelValue);
        boolean isAlphaTrack = index == 3;
        return isAlphaTrack ? stop : (stop | 0xFF000000);
    }

    /** The colour this would be with one channel moved — used for the slider's own gradient stops. */
    private int withChannel(int argb, int index, float channelValue) {
        float max = channelMax(mode, index);
        float unit = max <= 0f ? 0f : channelValue / max;
        if (mode == Mode.HSV) {
            switch (index) {
                case 0: return ArgbMath.fromHsv(unit, saturation, value, (argb >>> 24) & 0xFF);
                case 1: return ArgbMath.fromHsv(hue, unit, value, (argb >>> 24) & 0xFF);
                case 2: return ArgbMath.fromHsv(hue, saturation, unit, (argb >>> 24) & 0xFF);
                default: return (argb & 0x00FFFFFF) | (Math.round(unit * 255f) << 24);
            }
        }
        int shift = new int[] { 16, 8, 0, 24 }[index];
        return (argb & ~(0xFF << shift)) | (Math.round(unit * 255f) << shift);
    }

    private void writeChannel(int index, float channelValue) {
        float max = channelMax(mode, index);
        float unit = max <= 0f ? 0f : Math.max(0f, Math.min(1f, channelValue / max));
        if (mode == Mode.HSV) {
            switch (index) {
                case 0: hue = unit; break;
                case 1: saturation = unit; break;
                case 2: value = unit; break;
                default:
                    color.set((color.get() & 0x00FFFFFF) | (Math.round(unit * 255f) << 24));
                    return;
            }
            applyHsv();
            return;
        }
        int next = withChannel(color.get(), index, channelValue);
        // Decomposed because an RGB edit genuinely redefines the hue — unlike a value drag, where the
        // user's chosen hue must survive.
        decompose(next);
        color.set(next);
    }

    // ── Hex ─────────────────────────────────────────────────────────────────

    private static String toHex(int argb) {
        return String.format(Locale.ROOT, "%06X", argb & 0xFFFFFF);
    }

    /** {@code RRGGBB} or {@code AARRGGBB}, with or without a leading {@code #}; null when unparseable. */
    private Integer parseHex(String text) {
        String cleaned = text == null ? "" : text.trim().replace("#", "");
        if (cleaned.length() != 6 && cleaned.length() != 8) return null;
        try {
            long parsed = Long.parseLong(cleaned, 16);
            // Six digits carry no alpha, so the current one is kept rather than forcing opaque — a hex
            // edit is about the colour, and silently resetting a deliberate alpha is not what was asked.
            return cleaned.length() == 6
                    ? (int) ((color.get() & 0xFF000000L) | parsed)
                    : (int) parsed;
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

}
