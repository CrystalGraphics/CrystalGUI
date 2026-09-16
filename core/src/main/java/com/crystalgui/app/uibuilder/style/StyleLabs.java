package com.crystalgui.app.uibuilder.style;

import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.border.BorderRadiusProperties;

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
 * invisible against a flat colour) and <b>type</b> (a treatment judged by reading it). Everything else keeps
 * the type-driven control {@link DeclarationEditors} resolves, which is the floor this sits on rather than a
 * fallback it replaces.</p>
 *
 * <p>Registration is process-wide, like a widget kind: what a lab belongs to is a property, not a panel.</p>
 */
public final class StyleLabs {

    private StyleLabs() {
    }

    private static boolean registered;

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
                anchor -> ShadowLab.open(anchor, context.property(), context.css())));

        DeclarationEditors.register(StylePropertyRegistry.TRANSFORM, context -> chip(context,
                anchor -> TransformLab.open(anchor, context.property(), context.css())));

        DeclarationEditors.register(StylePropertyRegistry.BACKDROP_FILTER, context -> chip(context,
                anchor -> GlassLab.open(anchor, context.property(), context.css())));

        // THE WHOLE TYPE TREATMENT from any of its rows: size, weight, face, stroke and paint order are one
        // decision made by eye, and six rows of numbers is how it stops being one.
        for (StyleProperty<?> type : List.of(
                StylePropertyRegistry.FONT_SIZE, StylePropertyRegistry.FONT_FAMILY,
                StylePropertyRegistry.FONT_WEIGHT, StylePropertyRegistry.FONT_STYLE,
                StylePropertyRegistry.PAINT_ORDER, StylePropertyRegistry.TEXT_STROKE_WIDTH,
                StylePropertyRegistry.TEXT_STROKE_COLOR)) {
            DeclarationEditors.register(type, context -> chip(context, anchor -> {
                if (context.fields() != null) TypographyLab.open(anchor, context.fields(), context.node());
            }));
        }
    }

    /** A row that draws its own value and opens {@code lab} — anchored on the chip itself — when pressed. */
    private static DeclarationEditors.Field chip(DeclarationEditors.Context context, Consumer<StyleChip> lab) {
        ConfigDescriptor descriptor = ConfigDescriptor.text(context.id(), context.label())
                .tooltip(context.property().name + " — press to open the lab");
        StyleChip chip = new StyleChip(descriptor, drawable(context.property()) ? context.property() : null);
        if (sampled(context.property())) chip.sample("Ag");
        // A shadow is bigger than the swatch it is drawn in, so the swatch gets it to scale.
        if (context.property() == StylePropertyRegistry.TEXT_SHADOW) chip.preview(ShadowLab::fitted);
        chip.unit(unitOf(context.property()));
        chip.onOpen(() -> lab.accept(chip));
        chip.bind(context.css());
        return new DeclarationEditors.Field(descriptor, context.css(), chip);
    }

    /** Whether a swatch of this property says anything: a size or a width applied to a small box does not. */
    private static boolean drawable(StyleProperty<?> property) {
        return property != StylePropertyRegistry.FONT_SIZE
                && property != StylePropertyRegistry.TEXT_STROKE_WIDTH;
    }

    /**
     * What a bare number in this declaration means, for the row to say so.
     *
     * <p>Only where the property is a length the engine reads in pixels: {@code font-size: 34} is 34px, and
     * a row that prints the number alone leaves a reader to guess.</p>
     */
    @Nullable
    private static String unitOf(StyleProperty<?> property) {
        return property == StylePropertyRegistry.FONT_SIZE
                || property == StylePropertyRegistry.TEXT_STROKE_WIDTH ? "px" : null;
    }

    /** Whether the swatch needs something to apply the property TO — a face, a weight, a stroke colour. */
    private static boolean sampled(StyleProperty<?> property) {
        return property == StylePropertyRegistry.FONT_FAMILY || property == StylePropertyRegistry.FONT_WEIGHT
                || property == StylePropertyRegistry.FONT_STYLE || property == StylePropertyRegistry.PAINT_ORDER
                // A SHADOW IS CAST BY GLYPHS: on an empty box it paints nothing at all.
                || property == StylePropertyRegistry.TEXT_SHADOW;
    }
}
