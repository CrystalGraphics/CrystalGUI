package com.crystalgui.app.uibuilder.style;

import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.border.BorderRadiusProperties;
import com.crystalgui.ui.dom.UIElement;

/**
 * The properties that have a lab of their own, and the chip each gets in the panel.
 *
 * <pre>{@code
 * StyleLabs.register();     // once, when the UI builder activates
 * }</pre>
 *
 * <p>Six so far, and they are the ones a text field is worst at: a <b>gradient</b> (a ramp with stops),
 * <b>corners</b> (eight longhands nobody tracks as numbers), a <b>shadow</b> (a direction and a softness),
 * a <b>transform</b> (an ordered chain where the order is the meaning), <b>glass</b> (six functions that are
 * invisible against a flat color) and <b>type</b> (a treatment judged by reading it). Everything else keeps
 * the type-driven control {@link DeclarationEditors} resolves, which is the floor this sits on rather than a
 * fallback it replaces.</p>
 *
 * <p>Registration is process-wide, like a widget kind: what a lab belongs to is a property, not a panel.</p>
 */
public final class StyleLabs {

    private StyleLabs() {
    }

    private static boolean registered;

    /** The properties a text sample carries from the element, so a chip's "Ag" is set in the element's own type. */
    private static final List<StyleProperty<?>> FACE = List.of(
            StylePropertyRegistry.FONT_FAMILY, StylePropertyRegistry.FONT_WEIGHT, StylePropertyRegistry.FONT_STYLE);

    /** The outline a paint-order chip draws its sample with: wide enough to see which side of the fill it is on. */
    private static final String SAMPLE_STROKE = "18%";

    /** Gives the six their labs. Idempotent — a second builder does not register them twice. */
    public static void register() {
        if (registered) return;
        registered = true;

        for (StyleProperty<?> drawable : List.of(StylePropertyRegistry.BACKGROUND, StylePropertyRegistry.OVERLAY)) {
            DeclarationEditors.register(drawable, context -> chip(context,
                    anchor -> GradientLab.open(anchor, context.property(), context.css())));
        }

        for (StyleProperty<?> radius : List.of(
                BorderRadiusProperties.TOP_LEFT_X, BorderRadiusProperties.TOP_LEFT_Y,
                BorderRadiusProperties.TOP_RIGHT_X, BorderRadiusProperties.TOP_RIGHT_Y,
                BorderRadiusProperties.BOTTOM_RIGHT_X, BorderRadiusProperties.BOTTOM_RIGHT_Y,
                BorderRadiusProperties.BOTTOM_LEFT_X, BorderRadiusProperties.BOTTOM_LEFT_Y)) {
            DeclarationEditors.register(radius, context -> chip(context, anchor -> {
                // EVERY CORNER AT ONCE, whichever longhand's row was pressed: the lab is about the shape,
                // and a lab that edited one of eight numbers would be the row it was opened from.
                if (context.fields() != null) CornersLab.open(anchor, context.fields());
            }));
        }

        DeclarationEditors.register(StylePropertyRegistry.TEXT_SHADOW, context -> chip(context,
                anchor -> ShadowLab.open(anchor, context.property(), context.css(), context.node())));

        DeclarationEditors.register(StylePropertyRegistry.TRANSFORM, context -> chip(context,
                anchor -> TransformLab.open(anchor, context.property(), context.css())));

        DeclarationEditors.register(StylePropertyRegistry.BACKDROP_FILTER, context -> chip(context,
                anchor -> GlassLab.open(anchor, context.property(), context.css())));

        // THE WHOLE TYPE TREATMENT from any of its rows: face, weight, style, stroke and paint order are one
        // decision made by eye, and six rows of numbers is how it stops being one. The size is a number, and a
        // number is typed; its row is a field.
        for (StyleProperty<?> type : List.of(
                StylePropertyRegistry.FONT_FAMILY, StylePropertyRegistry.FONT_WEIGHT,
                StylePropertyRegistry.FONT_STYLE, StylePropertyRegistry.PAINT_ORDER,
                StylePropertyRegistry.TEXT_STROKE_WIDTH, StylePropertyRegistry.TEXT_STROKE_COLOR)) {
            DeclarationEditors.register(type, context -> chip(context, typography(context)));
        }
        DeclarationEditors.register(StylePropertyRegistry.TEXT_DECORATION_LINE,
                context -> chip(context, typography(context)));
        DeclarationEditors.register(StylePropertyRegistry.FONT_SIZE,
                context -> DeclarationEditors.number(context, "px"));
        DeclarationEditors.register(StylePropertyRegistry.CARET_WIDTH,
                context -> DeclarationEditors.number(context, "px"));
        DeclarationEditors.register(StyleFields.TEXT_STROKE, context -> chip(context, typography(context)));
    }

    private static Consumer<StyleChip> typography(DeclarationEditors.Context context) {
        return anchor -> {
            if (context.fields() != null) TypographyLab.open(anchor, context.fields(), context.node());
        };
    }

    /** A row that draws its own value and opens {@code lab} — anchored on the chip itself — when pressed. */
    private static DeclarationEditors.Field chip(DeclarationEditors.Context context, Consumer<StyleChip> lab) {
        StyleProperty<?> property = context.property();
        String name = property == null ? context.label() : property.name;
        ConfigDescriptor descriptor = ConfigDescriptor.text(context.id(), context.label())
                .tooltip(name + " — press to open the lab");
        StyleChip chip = new StyleChip(descriptor, drawable(property) ? property : null);
        if (sampled(name)) {
            chip.sample("Ag");
            followFace(chip, property, context.node());
        }
        // A shadow is bigger than the swatch it is drawn in, so the swatch gets it to scale.
        if (property == StylePropertyRegistry.TEXT_SHADOW) chip.preview(ShadowLab::fitted);
        if (property == StylePropertyRegistry.FONT_FAMILY) chip.display(TypographyLab::shortName);
        if (property == StylePropertyRegistry.PAINT_ORDER) chip.painter(StyleLabs::paintOrderSample);
        if (StyleFields.TEXT_STROKE.equals(name)) chip.painter((c, css) -> strokeSample(c, css, context.node()));
        if (property == StylePropertyRegistry.TEXT_DECORATION_LINE) {
            chip.painter((c, css) -> onSample(c, StylePropertyRegistry.TEXT_DECORATION_LINE, css));
        }
        chip.unit(property == StylePropertyRegistry.TEXT_STROKE_WIDTH ? "px" : null);
        chip.onOpen(() -> lab.accept(chip));
        chip.bind(context.css());
        return new DeclarationEditors.Field(descriptor, context.css(), chip);
    }

    /** Whether a swatch of this property says anything: a width applied to a small box does not. */
    private static boolean drawable(@Nullable StyleProperty<?> property) {
        return property != null && property != StylePropertyRegistry.TEXT_STROKE_WIDTH;
    }

    /** Whether the swatch needs something to apply the property TO — a face, a weight, a stroke. */
    private static boolean sampled(String name) {
        return name.equals("font-family") || name.equals("font-weight") || name.equals("font-style")
                || name.equals("paint-order") || name.equals(StyleFields.TEXT_STROKE)
                || name.equals("text-decoration-line")
                // A SHADOW IS CAST BY GLYPHS: on an empty box it paints nothing at all.
                || name.equals("text-shadow");
    }

    /**
     * Sets the sample in the element's own face, weight and style — all but the one this chip is about, which
     * the chip applies itself — so a bold italic chip reads as what the element will actually get.
     */
    private static void followFace(StyleChip chip, @Nullable StyleProperty<?> own, @Nullable UIElement node) {
        UIElement sample = chip.sampleText();
        if (sample == null || node == null) return;
        for (StyleProperty<?> face : FACE) {
            if (face == own) continue;
            LiveEdits.follow(sample, face, TypographyLab.computed(node, face));
        }
    }

    /** {@code css} applied to the sample itself, or taken off it when blank. */
    private static void onSample(StyleChip chip, StyleProperty<?> property, String css) {
        UIElement sample = chip.sampleText();
        if (sample == null) return;
        if (css.isBlank()) LiveEdits.clearInline(sample, property);
        else LiveEdits.setInline(sample, property, css);
    }

    /** The sample outlined in the element's accent, with the order this declaration says. */
    private static void paintOrderSample(StyleChip chip, String css) {
        UIElement sample = chip.sampleText();
        if (sample == null) return;
        LiveEdits.setInline(sample, StylePropertyRegistry.TEXT_STROKE_WIDTH, SAMPLE_STROKE);
        LiveEdits.setInline(sample, StylePropertyRegistry.TEXT_STROKE_COLOR, "#8A8D93");
        if (css.isBlank()) LiveEdits.clearInline(sample, StylePropertyRegistry.PAINT_ORDER);
        else LiveEdits.setInline(sample, StylePropertyRegistry.PAINT_ORDER, css);
    }

    /**
     * The sample outlined as the declaration says, at the width it says RELATIVE TO THE TEXT: a percentage is
     * already a share of the em, and pixels become the share they are of the element's own font size, so the
     * small sample carries the proportion the element does.
     */
    private static void strokeSample(StyleChip chip, String css, @Nullable UIElement node) {
        UIElement sample = chip.sampleText();
        if (sample == null) return;
        String color = TypographyLab.color(css);
        String width = TypographyLab.width(css);
        if (css.isBlank() || color.isEmpty() || width.isEmpty()) {
            LiveEdits.clearInline(sample, StylePropertyRegistry.TEXT_STROKE_WIDTH);
            LiveEdits.clearInline(sample, StylePropertyRegistry.TEXT_STROKE_COLOR);
            return;
        }
        String share = width;
        if (!width.endsWith("%")) {
            Float size = node == null ? null : node.getStyle().computed().get(StylePropertyRegistry.FONT_SIZE);
            float px = CssValues.number(width, 0f);
            share = size == null || size <= 0f ? width : CssValues.write(px / size * 100f) + "%";
        }
        LiveEdits.setInline(sample, StylePropertyRegistry.TEXT_STROKE_WIDTH, share);
        LiveEdits.setInline(sample, StylePropertyRegistry.TEXT_STROKE_COLOR, color);
    }
}
