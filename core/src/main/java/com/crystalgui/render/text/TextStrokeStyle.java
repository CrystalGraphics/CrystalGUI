package com.crystalgui.render.text;

import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.text.CgStrokeAlign;
import com.crystalgraphics.text.cache.CgFontRegistry;
import com.crystalgraphics.text.render.CgTextRenderer;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.style.ComputedStyle;
import com.crystalgui.style.GeneralGroup;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.text.PaintOrder;
import com.crystalgui.style.property.visual.text.StrokeAlign;
import com.crystalgui.style.property.visual.border.LengthPercent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Puts the cascade's {@code text-stroke} on a text draw, or leaves it unstroked when nothing asked.
 *
 * <p>Call it between building a draw and submitting it:</p>
 *
 * <pre>{@code
 * CgFontFamily family = resolveFamily();
 * CgTextRenderer.Draw draw = ctx.text().draw()
 *         .text(shown).family(family).at(x, y).color(color);
 * TextStrokeStyle.applyTo(draw, family, general, computedStyle(), color);
 * draw.submit();
 * }</pre>
 *
 * <p><b>Every widget that paints its own glyphs has to call this.</b> {@code text-stroke-width} and
 * {@code text-stroke-color} are INHERITABLE, so a declaration on any ancestor reaches every text
 * under it the way CSS does — a widget that draws text and skips this leaves the property computing
 * correctly and doing nothing, which is indistinguishable from the property not existing. It lives
 * here rather than on one widget because {@code UIText} and {@code TextField} both need it and
 * neither owns the other.</p>
 *
 * <p>Easy to get wrong: pass the colour that is ACTUALLY being drawn as {@code inheritedColor}, not
 * the element's {@code color} — an unset {@code text-stroke-color} means {@code currentcolor}, so a
 * dimmed placeholder should outline in the dimmed colour. A width wider than the face's field can
 * carry is clamped and reported once, never thrown.</p>
 */
public final class TextStrokeStyle {

    private TextStrokeStyle() {
    }

    public static void applyTo(CgTextRenderer.Draw draw, CgFontFamily family,
                               GeneralGroup general, ComputedStyle computed, int inheritedColor) {
        LengthPercent width = general.textStrokeWidth();
        if (width == null) return;
        float fontSize = general.fontSize();
        if (fontSize <= 0f) return;
        float widthEm = width.resolve(fontSize) / fontSize;
        if (widthEm <= 0f) return;

        // CAPPED HERE, not only in the shader. The field carries distance for a fraction of the em, so
        // an outline wider than the band's ceiling cannot be drawn at any size -- the shader has always
        // clamped per fragment, which meant the value handed down was one the renderer would never
        // draw, and a caller reading it back got a number that was never true.
        //
        // Asked of the FAMILY, not read off CgTextStroke#MAX_FIELD_WIDTH_EM: that constant is the
        // narrow band every face can hold, and a face with no dense script in it is banded 2.3x wider.
        //
        // Reported once per (width, size, ceiling), because the difference is invisible: a 2px outline
        // on 12px text silently drew 0.67px, and nothing anywhere said which number was real.
        float ceilingEm = CgFontRegistry.get().maxStrokeWidthEm(family);
        if (widthEm > ceilingEm) {
            warnStrokeClamped(widthEm, fontSize, ceilingEm);
            widthEm = ceilingEm;
        }

        // Unset means `currentcolor`, so a width on its own outlines in the text's own colour.
        int strokeColor = computed.isSet(StylePropertyRegistry.TEXT_STROKE_COLOR)
                ? general.textStrokeColor() : inheritedColor;
        if ((strokeColor >>> 24) == 0) return;

        StrokeAlign align = general.strokeAlign();
        draw.stroke(widthEm, strokeColor)
                .strokeAlign(align == StrokeAlign.CENTER ? CgStrokeAlign.CENTER
                        : align == StrokeAlign.INSET ? CgStrokeAlign.INSET : CgStrokeAlign.OUTSET)
                // `paint-order: stroke` is the one that reorders; `fill` and `normal` are the same
                // thing, which is why the enum carries all three rather than a boolean.
                .strokeOverFill(general.paintOrder() != PaintOrder.STROKE);
    }

    /**
     * Says, once per distinct (width, size, ceiling), that a declared outline is wider than the
     * distance field can describe and what it was drawn at instead.
     *
     * <p>Bounded: a paint method runs every frame, and a warning per frame is a log nobody reads.</p>
     */
    private static void warnStrokeClamped(float widthEm, float fontSize, float ceilingEm) {
        // The size check FIRST: this runs inside a paint method, so once the table is full every
        // later frame would otherwise build a key string per label per frame to throw it away.
        if (REPORTED_CLAMPS.size() >= MAX_REPORTED_CLAMPS) return;
        if (!REPORTED_CLAMPS.add(Math.round(widthEm * 1000f) + "@" + Math.round(fontSize)
                + "/" + Math.round(ceilingEm * 1000f))) return;
        CrystalGuiCore.LOGGER.warn(String.format(
                "[cgui] text-stroke %.2fpx on %.0fpx text is %.4fem, wider than the %.4fem the glyph "
                        + "atlas can describe; drawing %.2fpx. A stroke is bounded by the stored "
                        + "distance field, so the widest outline is a fraction of the em at every size.",
                widthEm * fontSize, fontSize, widthEm, ceilingEm, ceilingEm * fontSize));
    }

    private static final int MAX_REPORTED_CLAMPS = 32;
    private static final Set<String> REPORTED_CLAMPS = ConcurrentHashMap.newKeySet();
}
