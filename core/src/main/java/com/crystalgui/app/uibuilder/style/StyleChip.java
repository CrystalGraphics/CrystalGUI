package com.crystalgui.app.uibuilder.style;

import java.util.function.UnaryOperator;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.render.texture.CgUiColorField;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.visual.color.ColorProperty;
import com.crystalgui.style.property.visual.color.ColorValue;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.ui.contract.Event;
import com.crystalgui.ui.contract.RatePolicy;
import com.crystalgui.ui.contract.StateTypes;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.ConfigControlContracts;
import com.crystalgui.widget.config.ValueControl;
import com.crystalgui.widget.text.UIText;

/**
 * A declaration's row when it has a lab: the value drawn as itself, and a press opens the lab.
 *
 * <pre>{@code
 * StyleChip chip = new StyleChip(descriptor, StylePropertyRegistry.BACKGROUND);
 * chip.onOpen(() -> GradientLab.open(chip, property, css));
 * chip.bind(fields.value("background"));
 * }</pre>
 *
 * <p>The chip is an element with the declaration applied to it — a gradient strip is the gradient, a corner
 * box is the corners — so a narrow panel still says what the value <em>is</em> rather than what it is
 * spelled. Whatever needs room to edit is the lab's job, one press away.</p>
 */
public class StyleChip extends ValueControl<String> {

    public static final Name NAME = Name.of("stylechip");

    public static final String CHIP_CLASS = "__style-chip__";
    public static final String SWATCH_CLASS = "__style-chip-swatch__";
    public static final String TEXT_CLASS = "__style-chip-text__";

    /** Changed when the lab writes: one act, like a colour picked. */
    public static final Event<StyleChip, String> CHANGED =
            ConfigControlContracts.changed(StateTypes.STRING, "", RatePolicy.IMMEDIATE);

    public static final WidgetContract<StyleChip> CONTRACT = ConfigControlContracts.register(
            StyleChip.class, "stylechip", StateTypes.STRING, "", CHANGED);

    private final UIElement swatch = new UIElement();
    private final UIText text = new UIText("");

    /** What the swatch holds for a property whose effect needs something to be applied TO — a face, a stroke. */
    @Nullable
    private UIText sample;

    @Nullable
    private final StyleProperty<?> property;

    @Nullable
    private Runnable open;

    /** How the value is drawn in a swatch this size, or null to apply it as written. @see #preview */
    @Nullable
    private UnaryOperator<String> preview;

    private String value = "";

    @Nullable
    private String unit;

    public StyleChip() {
        this(ConfigDescriptor.text("chip", ""), null);
    }

    /** @param property what the swatch draws with, or null for a declaration with nothing to show */
    public StyleChip(ConfigDescriptor descriptor, @Nullable StyleProperty<?> property) {
        super(NAME, descriptor, "");
        this.property = property;
        addClass(CHIP_CLASS);
        swatch.addClass(SWATCH_CLASS);
        text.addClass(TEXT_CLASS);
        append(swatch);
        append(text);
        setHitTest(true);
        onMouseDown.attachListener((element, event) -> {
            if (open != null) open.run();
            event.preventDefault();
        }, false, true);
    }

    /**
     * Draws the swatch as {@code text} set in this declaration — for a property that does nothing to an
     * empty box.
     *
     * <pre>{@code
     * chip.sample("Ag");   // font-weight, font-family, text-stroke-color
     * }</pre>
     */
    public StyleChip sample(String text) {
        if (sample == null) {
            sample = new UIText(text);
            swatch.append(sample);
        }
        sample.setText(text);
        return this;
    }

    /**
     * The unit a bare number in this declaration means, shown after the value.
     *
     * <pre>{@code
     * chip.unit("px");   // a file saying `font-size: 34` reads as 34px
     * }</pre>
     *
     * <p>Display only — the file keeps the spelling it has. The engine reads an unsuffixed length as pixels,
     * so a row showing the number alone answers "34 what?" with nothing.</p>
     */
    public StyleChip unit(@Nullable String unit) {
        this.unit = unit;
        writeToWidgets(value);
        return this;
    }

    /**
     * How this declaration is drawn in the swatch, when the value at its own size says nothing there.
     *
     * <pre>{@code
     * chip.preview(ShadowLab::fitted);   // a 16px blur, scaled to a 28x16 box
     * }</pre>
     *
     * <p>Display only, and the row prints the real value beside it. A swatch is 28x16 and a shadow is
     * routinely larger than that in every direction, so drawn at its own scale it is a corner of a blur
     * with the shape, the softness and most of the colour outside the box.</p>
     */
    public StyleChip preview(UnaryOperator<String> preview) {
        this.preview = preview;
        writeToWidgets(value);
        return this;
    }

    /** What a press does — the lab this row belongs to. */
    public StyleChip onOpen(Runnable opener) {
        this.open = opener;
        return this;
    }

    @Override
    public Object getValueObject() {
        return value;
    }

    @Override
    protected void writeToWidgets(@Nullable String next) {
        value = next == null ? "" : next;
        // SHOWN readable, WRITTEN as it is: the file keeps whatever spelling it has.
        text.setText(value.isEmpty() ? "—" : shown(CssValues.readable(value)));
        // NO SWATCH AND NO COLUMN where there is nothing to see: a size or a width applied to a 28x16 box
        // says nothing, and a reserved-but-empty column indents a value past rows that have no swatch at all.
        // `hidden` takes the space with it, which is the point.
        swatch.set(Attribute.HIDDEN, property == null);
        if (property == null) return;
        if (value.isEmpty()) {
            LiveEdits.clearInline(swatch, property);
            LiveEdits.clearInline(swatch, StylePropertyRegistry.BACKGROUND);
            return;
        }
        // THE SWATCH IS THE VALUE, applied: a gradient chip draws the gradient, a radius chip is rounded.
        //
        // A COLOUR IS THE EXCEPTION, and it has to be: applying `text-stroke-color` to an empty box paints
        // nothing at all, which is what made that chip an unexplainable grey rectangle. A colour is shown as
        // a patch OF that colour, which is what every picker in the application does.
        if (property instanceof ColorProperty) {
            // OVER A CHECKERBOARD, which is the only way a transparent colour reads as transparent rather
            // than as a swatch that failed to draw. `#972D8E00` is a colour somebody picked, and flat
            // against the panel it is indistinguishable from no swatch at all -- so a picker that was
            // working looked broken. `CgUiColorField` is what the colour picker's own swatches use.
            //
            // A DRAWABLE rather than the CSS text, and the BACKGROUND rather than the background COLOUR:
            // the swatch's own band is a `background`, and a colour set underneath it composites with it
            // -- #E8913A under the band came out #27180A.
            Integer argb = ColorValue.parseColor(value);
            if (argb == null) {
                LiveEdits.setInline(swatch, StylePropertyRegistry.BACKGROUND, value);
                return;
            }
            paintColour(swatch, argb);
        } else {
            LiveEdits.setInline(swatch, property, preview == null ? value : preview.apply(value));
        }
    }

    /**
     * Paints {@code element} as a patch of {@code argb}, over the checkerboard that makes a transparent
     * colour read as transparent rather than as a patch that failed to draw.
     *
     * <pre>{@code
     * StyleChip.paintColour(swatch, 0x80FF0000);   // half-transparent red, and it looks it
     * }</pre>
     */
    public static void paintColour(UIElement element, int argb) {
        StyleGroup.inlinePipeline(element.getStyle().getGeneralGroup(),
                group -> group.background(new CgUiColorField()
                        .setMode(CgUiColorField.Mode.GRADIENT)
                        .setGradient(argb, argb)
                        .setCornerRadius(2f, 2f)));
    }

    /** The value as the row says it: a bare number carries the unit the engine reads it as. */
    private String shown(String readable) {
        if (unit == null || readable.isEmpty()) return readable;
        for (int i = 0; i < readable.length(); i++) {
            char c = readable.charAt(i);
            if (!Character.isDigit(c) && c != '.' && c != '-' && c != '+') return readable;
        }
        return readable + unit;
    }
}
