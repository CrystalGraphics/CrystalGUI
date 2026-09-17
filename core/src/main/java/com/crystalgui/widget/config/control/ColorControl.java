package com.crystalgui.widget.config.control;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.ConfigControlContracts;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.StateTypes;
import com.crystalgui.ui.contract.Event;
import com.crystalgui.ui.contract.RatePolicy;
import com.crystalgui.core.data.Transform2D;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.composite.ColorSelector;
import com.crystalgui.widget.overlay.Dialog;
import com.crystalgui.render.texture.CgUiColorField;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.widget.config.ValueControl;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import com.crystalgui.ui.dom.Name;

/**
 * ARGB, edited through a swatch that opens the full {@link ColorSelector}.
 *
 * <p>Unity reference: {@code docs/research/unity-inspector/01-inspector-property.png}'s colour row and
 * {@code docs/research/unity-nodes/01-color-field.png}.</p>
 *
 * <h3>Ported from {@code ShaderColorFieldWidget}, minus the GLSL</h3>
 * <p>The graph's colour field already solved "a swatch that opens a picker in a promoted, draggable,
 * click-to-toggle {@link Dialog}" — including the two reasons neither a bare {@link
 * com.crystalgui.ui.elements.Popover} (not draggable) nor {@code showModal()} (makes the whole document
 * inert) is right for a picker twice the width of what opens it. This is that same shape working in
 * ARGB directly: an inspector already has the int, and asking it to round-trip through
 * {@code vec4(...)} text would discard a type only to immediately reparse it. A shader-graph consumer
 * that wants the GLSL spelling keeps using {@code ShaderColorFieldWidget} at the graph boundary — this
 * control never needs to know that spelling exists.</p>
 */
public class ColorControl extends ValueControl<Integer> {

    public static final Name NAME = Name.of("colorcontrol");

    /** A colour wheel has a drag behind it, so it is throttled and the released colour always travels. */
    public static final Event<ColorControl, Integer> CHANGED =
            ConfigControlContracts.changed(StateTypes.INT, 0xFFFFFFFF, RatePolicy.DRAGGING);

    public static final WidgetContract<ColorControl> CONTRACT = ConfigControlContracts.register(
            ColorControl.class, "colorcontrol", StateTypes.INT, 0xFFFFFFFF, CHANGED);


    public static final String SWATCH_CLASS = "__swatch__";
    public static final String COLOR_BAR_CLASS = "__color-bar__";
    public static final String ALPHA_BAR_CLASS = "__alpha-bar__";
    public static final String ALPHA_FILL_CLASS = "__alpha-fill__";
    /** The colour as hex beside the swatch — hidden by the kit, shown where a panel wants compact rows. */
    public static final String HEX_CLASS = "__color-hex__";
    /** The no-argument constructor the registry's factory needs, over a NEUTRAL
     * descriptor -- an unlabelled control of this kind, which is a real thing rather than a
     * placeholder. Nothing decodes one: the kit is {@code localOnly}, and the registration
     * exists so a theme can address {@code colorcontrol } by tag. */
    public ColorControl() {
        this(ConfigDescriptor.color("", ""), null);
    }

    /** On the popup {@link Dialog} itself, so a theme can style the colour picker's dialog distinctly
     * from every other {@code Dialog} in the engine (see {@code dialog.__picker__} in default.css) —
     * {@code Dialog} is shared, generic chrome and carries no such hook of its own.
     *
     * <p>Needed for translucency specifically: a translucent {@code colorselector} laid directly over
     * an OPAQUE dialog composites right back to opaque (tinted, but alpha 1.0) — there is nothing
     * behind it to actually show through. Both layers have to be translucent together, the same way
     * {@code graphnode}'s own root is fully transparent (`#00000000`) underneath its translucent
     * `.__inputs__`/`.__outputs__` bands, for either one to read as see-through at all.</p> */
    public static final String PICKER_DIALOG_CLASS = "__picker__";

    private static final int DEFAULT_COLOR = 0xFF000000;

    private final UIElement swatch = new UIElement();
    private final UIElement colorBar = new UIElement();
    private final UIElement alphaFill = new UIElement();
    private final UIText hex = new UIText("");

    /** What the hex says for a value of exactly zero, or null to spell it. @see #zeroLabel */
    @Nullable
    private String zeroLabel;
    private final Dialog dialog = new Dialog("Color");
    private final ColorSelector picker = new ColorSelector();

    public ColorControl(ConfigDescriptor descriptor, @Nullable Integer defaultValue) {
        super(NAME, descriptor, defaultValue == null ? DEFAULT_COLOR : defaultValue);
        addClass("__color__");
        swatch.addClass(SWATCH_CLASS);
        colorBar.addClass(COLOR_BAR_CLASS);
        UIElement alphaBar = new UIElement();
        alphaBar.addClass(ALPHA_BAR_CLASS);
        alphaFill.addClass(ALPHA_FILL_CLASS);
        alphaBar.append(alphaFill);
        swatch.append(colorBar);
        swatch.append(alphaBar);
        // The parts are scenery; the swatch takes every press, same as ShaderColorFieldWidget's — a
        // thin alpha strip is effectively unclickable on its own.
        colorBar.setHitTest(false);
        alphaBar.setHitTest(false);

        dialog.addClass(PICKER_DIALOG_CLASS);
        paint(getValue());
        picker.setInitialColor(getValue());
        picker.onColorChanged.connect(argb -> {
            paint(argb);
            commit(argb);
        });
        // ONE EDIT PER DRAG: the picker changes the colour every frame, and each would be its own undo step.
        picker.onDragging.connect(active -> {
            if (Boolean.TRUE.equals(active)) {
                beginInteraction();
            } else {
                endInteraction();
            }
        });
        dialog.getContent().append(picker);
        swatch.append(dialog);

        swatch.onMouseDown.attachListener((el, event) -> {
            event.stopPropagation();
            togglePicker(event.getPosition().x(), event.getPosition().y());
        }, false, false);
        hex.addClass(HEX_CLASS);
        hex.setHitTest(true);
        hex.onMouseDown.attachListener((el, event) -> {
            event.stopPropagation();
            togglePicker(event.getPosition().x(), event.getPosition().y());
        }, false, false);

        append(swatch);
        append(hex);
        // THE CHECKERBOARD ONCE ON SCREEN: it is a drawable, and a control a server describes never joins a document.
        onConnected(() -> paint(getValue()));
    }

    private void togglePicker(float worldX, float worldY) {
        if (dialog.isOpen()) {
            dialog.close();
            return;
        }
        picker.setInitialColor(getValue() == null ? DEFAULT_COLOR : getValue());
        dialog.show();
        UIDocument window = swatch.document();
        if (window == null) return;
        // Promoted BY HAND: Dialog.show() is modeless, and the spec promotes only showModal() —
        // see ShaderColorFieldWidget's own note on why showModal() (inert whole document) and a
        // bare Popover (not draggable) are both wrong for this.
        window.promote(dialog);
        placeAtPointer(window, dialog, worldX, worldY);
    }

    /**
     * The colour over a checkerboard, so a translucent one looks translucent, with the alpha again as a proportional
     * strip beneath — the strip is the faster read at a glance, the checkerboard the truer one.
     */
    private void paint(@Nullable Integer value) {
        int argb = value == null ? DEFAULT_COLOR : value;
        if (document() != null) {
            // THE OPAQUE FILL REMOVED, not zeroed: a background-colour that is SET at all is drawn instead of the
            // background, so a zero drew nothing where the checkerboard should be.
            colorBar.getStyle().removeCandidates(StylePropertyRegistry.BACKGROUND_COLOR, slot -> true);
            StyleGroup.inlinePipeline(colorBar.getStyle().getGeneralGroup(), g -> g.background(new CgUiColorField()
                    .setMode(CgUiColorField.Mode.GRADIENT)
                    .setGradient(argb, argb)));
        } else {
            // background-COLOR, not a drawable, off screen: building one reaches a CrystalGraphics texture type,
            // which a dedicated server has no class for.
            colorBar.generalStyle(g -> g.backgroundColor(argb | 0xFF000000));
        }
        float alpha = ((argb >>> 24) & 0xFF) / 255f;
        alphaFill.layout(l -> l.widthPercent(alpha * 100f));
        // EIGHT DIGITS ONLY WITH TRANSPARENCY TO STATE, as a sheet's colour writer spells it.
        int a = (argb >>> 24) & 0xFF;
        if (argb == 0 && zeroLabel != null) {
            hex.setText(zeroLabel);
        } else {
            hex.setText(a == 0xFF ? String.format("#%06X", argb & 0xFFFFFF) : String.format("#%06X%02X", argb & 0xFFFFFF, a));
        }
    }

    @Override
    protected void writeToWidgets(@Nullable Integer value) {
        paint(value);
        picker.setColor(value == null ? DEFAULT_COLOR : value);
    }

    /** As {@code ShaderColorFieldWidget.placeAtPointer} — the pointer, not the swatch, and the world
     * coordinate has to come back through the root transform, the one definition of {@code uiScale}.
     *
     * <p>{@code placeAt}, not {@code moveTo}: a picker raised from a row near the bottom of the screen
     * has to come back inside it rather than hang off, which is what opening a popup means and what a
     * drag's caption clamp deliberately does not do. @see Dialog#placeAt */
    private static void placeAtPointer(UIDocument window, Dialog dialog, float worldX, float worldY) {
        var local = Transform2D.apply(new Matrix4f(window.boxes().rootTransform()).invert(), worldX, worldY);
        dialog.placeAt(local.x() - 6f, local.y() - 6f);
    }

    /**
     * What the hex reads for a value of zero, for a property where zero is not a colour but "inherit one".
     *
     * <pre>{@code
     * control.zeroLabel("currentColor");   // caret-color: 0 is the text's own colour
     * }</pre>
     */
    public ColorControl zeroLabel(@Nullable String label) {
        zeroLabel = label;
        paint(getValue());
        return this;
    }

    /** The swatch, for a host that needs to reach the widget directly. */
    public UIElement swatch() {
        return swatch;
    }

    /** The picker itself, for a host that wants to react to the session (e.g. HDR mode) rather than
     * only the committed value. */
    public ColorSelector picker() {
        return picker;
    }
}
