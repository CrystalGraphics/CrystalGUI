package com.crystalgui.render.text;

import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.text.cache.CgFontRegistry;
import com.crystalgraphics.text.render.CgTextDraw;
import com.crystalgraphics.text.style.CgTextLayer;
import com.crystalgraphics.text.style.CgTextStyle;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.render.InkOverflow;
import com.crystalgui.style.ComputedStyle;
import com.crystalgui.style.GeneralGroup;
import com.crystalgui.style.HighlightStyle;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.style.property.visual.shadow.Shadow;
import com.crystalgui.style.property.visual.shadow.ShadowList;
import com.crystalgui.style.property.visual.text.PaintOrder;
import com.crystalgui.style.property.visual.text.StrokeAlign;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The cascade's {@code color}, {@code text-stroke}, {@code stroke-align}, {@code paint-order} and {@code text-shadow}
 * as one CrystalGraphics {@link CgTextStyle}, put on a text draw. A widget holds one and calls it between building a
 * draw and submitting it:
 *
 * <pre>{@code
 * private final CssTextStyle textStyle = new CssTextStyle();
 *
 * CgTextDraw draw = ctx.text().layout(layout).family(family).at(x, y);
 * textStyle.applyTo(draw, family, general, computedStyle(), color);
 * draw.submit();
 * }</pre>
 *
 * <p>With {@code ::highlight}s that cast shadows from their own glyphs, pass them and each glyph's index into them:</p>
 *
 * <pre>{@code
 * textStyle.applyTo(draw, family, general, computedStyle(), color, scopedHighlights, scopeByGlyph);
 * }</pre>
 *
 * <p>And from the widget's {@code inkOverflow}, so the outline and shadows inside a layer are not clipped at the box:</p>
 *
 * <pre>{@code
 * return CssTextStyle.inkOverflow(general.textShadow(), CssTextStyle.outwardPx(general));
 * }</pre>
 *
 * <ul>
 *   <li><b>Every widget that paints its own glyphs calls it.</b> The properties inherit, so a draw that skips it leaves
 *       them computing correctly and doing nothing.</li>
 *   <li>Pass the colour actually drawn: an unset stroke colour and a {@code currentcolor} shadow take it, so a dimmed
 *       placeholder outlines in its dimmed colour.</li>
 *   <li>The text's colour is the fill's paint and the draw's tint stays white, so a span's colour replaces it alone.</li>
 *   <li>A stroke wider than the face's field can carry is clamped and reported once, never thrown.</li>
 *   <li>One instance per widget: it keeps the style it last built, and rebuilds only when an input changes.</li>
 * </ul>
 */
public final class CssTextStyle {

    private CgTextStyle style = CgTextStyle.PLAIN;
    /** Per layer of {@link #style}, the highlight it is scoped to, or -1. */
    private int[] layerScopes = new int[0];

    private int builtPx = -1;
    private int builtColor;
    private float builtStrokePx;
    private int builtStrokeColor;
    private StrokeAlign builtAlign;
    private PaintOrder builtOrder;
    private ShadowList builtShadows;
    private final List<ShadowList> builtHighlightShadows = new ArrayList<>();
    private int[] builtHighlightColors = new int[0];

    /** As {@link #applyTo(CgTextDraw, CgFontFamily, GeneralGroup, ComputedStyle, int, List, int[])} with no highlight. */
    public void applyTo(CgTextDraw draw, CgFontFamily family, GeneralGroup general, ComputedStyle computed, int color) {
        applyTo(draw, family, general, computed, color, List.of(), null);
    }

    /**
     * Puts the style on {@code draw}, its tint white and its outline and shadows overflowing the box, as CSS's do.
     *
     * @param highlights   the {@code ::highlight}s whose shadows apply to their own glyphs alone, painted beneath the
     *                     element's; empty for none
     * @param scopeByGlyph per glyph of the draw's layout, its index into {@code highlights}, -1 for none; null when
     *                     {@code highlights} is empty
     */
    public void applyTo(CgTextDraw draw, CgFontFamily family, GeneralGroup general, ComputedStyle computed, int color,
                        List<HighlightStyle> highlights, int[] scopeByGlyph) {
        float fontSize = general.fontSize();
        int px = Math.max(1, Math.round(fontSize));
        float strokePx = strokePx(family, general, fontSize);
        // Unset means `currentcolor`, so a width on its own outlines in the text's own colour.
        int strokeColor = computed.isSet(StylePropertyRegistry.TEXT_STROKE_COLOR) ? general.textStrokeColor() : color;
        if ((strokeColor >>> 24) == 0) strokePx = 0f;
        ShadowList shadows = general.textShadow() == null ? ShadowList.NONE : general.textShadow();

        if (px != builtPx || color != builtColor || strokePx != builtStrokePx
                || (strokePx > 0f && (strokeColor != builtStrokeColor || general.strokeAlign() != builtAlign
                || general.paintOrder() != builtOrder))
                || !shadows.equals(builtShadows) || !sameHighlights(highlights, color)) {
            build(px, color, strokePx, strokeColor, general.strokeAlign(), general.paintOrder(), shadows, highlights);
        }

        draw.style(style).color(0xFFFFFFFF).contained(false);
        if (scopeByGlyph == null || highlights.isEmpty()) return;
        draw.scopes(scopeByGlyph);
        for (int i = 0; i < layerScopes.length; i++) if (layerScopes[i] >= 0) draw.layerScope(i, layerScopes[i]);
    }

    /**
     * Bottom to top: the highlights' outer shadows (the last highlight lowest), the element's outer shadows, the fill
     * and outline in paint order, then every inset shadow. CSS paints a list's first shadow on top, so each list goes
     * in reversed.
     */
    private void build(int px, int color, float strokePx, int strokeColor, StrokeAlign align, PaintOrder order,
                       ShadowList shadows, List<HighlightStyle> highlights) {
        builtPx = px;
        builtColor = color;
        builtStrokePx = strokePx;
        builtStrokeColor = strokeColor;
        builtAlign = align;
        builtOrder = order;
        builtShadows = shadows;
        builtHighlightShadows.clear();
        builtHighlightColors = new int[highlights.size()];
        for (int h = 0; h < highlights.size(); h++) {
            builtHighlightShadows.add(highlights.get(h).textShadow());
            builtHighlightColors[h] = highlights.get(h).color(color);
        }

        boolean stroked = strokePx > 0f;
        CgTextStyle.Builder b = CgTextStyle.builder(null).size(px * 72f / 96f);
        List<Integer> scopes = new ArrayList<>();
        for (int h = highlights.size() - 1; h >= 0; h--) {
            addShadows(b, scopes, "highlight" + h + ".", builtHighlightShadows.get(h), builtHighlightColors[h], false, stroked, h);
        }
        addShadows(b, scopes, "shadow", shadows, color, false, stroked, -1);

        CgTextLayer.Builder fill = CgTextLayer.fill("fill").paint(color);
        if (!stroked) {
            b.layer(fill);
            scopes.add(-1);
        } else {
            CgTextLayer.Builder outline = CgTextLayer.outline("stroke").width(strokePx).paint(strokeColor)
                    // CSS's stroke: a path stroke's mitre
                    .join(CgTextLayer.Join.MITRE)
                    .align(align == StrokeAlign.CENTER ? CgTextLayer.Align.CENTRE
                            : align == StrokeAlign.INSET ? CgTextLayer.Align.INSIDE : CgTextLayer.Align.OUTSIDE);
            // `paint-order: stroke` alone puts the stroke under the fill; `normal` and `fill` paint it over.
            boolean under = order == PaintOrder.STROKE;
            b.layer(under ? outline : fill).layer(under ? fill : outline);
            scopes.add(-1);
            scopes.add(-1);
        }

        addShadows(b, scopes, "inset", shadows, color, true, stroked, -1);
        for (int h = highlights.size() - 1; h >= 0; h--) {
            addShadows(b, scopes, "highlight" + h + ".inset", builtHighlightShadows.get(h), builtHighlightColors[h], true,
                    stroked, h);
        }

        style = b.build();
        layerScopes = new int[scopes.size()];
        for (int i = 0; i < layerScopes.length; i++) layerScopes[i] = scopes.get(i);
    }

    private static void addShadows(CgTextStyle.Builder b, List<Integer> scopes, String prefix, ShadowList shadows,
                                   int currentColor, boolean inset, boolean stroked, int scope) {
        for (int i = shadows.size() - 1; i >= 0; i--) {
            Shadow s = shadows.get(i);
            if (s.inset() != inset) continue;
            b.layer(CgTextLayer.shadow(prefix + i).offset(s.x(), s.y()).blur(s.sigma()).spread(s.spread())
                    .inset(inset).outlined(stroked).paint(s.color().resolve(currentColor)));
            scopes.add(scope);
        }
    }

    private boolean sameHighlights(List<HighlightStyle> highlights, int color) {
        if (highlights.size() != builtHighlightShadows.size()) return false;
        for (int h = 0; h < highlights.size(); h++) {
            HighlightStyle style = highlights.get(h);
            if (!Objects.equals(style.textShadow(), builtHighlightShadows.get(h))) return false;
            if (style.color(color) != builtHighlightColors[h]) return false;
        }
        return true;
    }

    /** The declared stroke in local pixels, clamped to what the family's field carries; 0 for none. */
    private static float strokePx(CgFontFamily family, GeneralGroup general, float fontSize) {
        LengthPercent width = general.textStrokeWidth();
        if (width == null || fontSize <= 0f) return 0f;
        float widthEm = width.resolve(fontSize) / fontSize;
        if (widthEm <= 0f) return 0f;
        // Clamped here as well as in the shader, so what a caller reads back is what draws. Asked of the family: a
        // face with no dense script is banded wider than CgTextLayer.MAX_FIELD_WIDTH_EM.
        float ceilingEm = CgFontRegistry.get().maxStrokeWidthEm(family);
        if (widthEm > ceilingEm) {
            warnStrokeClamped(widthEm, fontSize, ceilingEm);
            widthEm = ceilingEm;
        }
        return widthEm * fontSize;
    }

    /**
     * How far the stroke reaches outside the glyph outline, in local pixels: all of an outset stroke, half of a
     * centred one, none of an inset one. For a widget's {@code inkOverflow}, and the reach of a shadow, which includes
     * the stroke.
     */
    public static float outwardPx(GeneralGroup general) {
        LengthPercent width = general.textStrokeWidth();
        float fontSize = general.fontSize();
        if (width == null || fontSize <= 0f) return 0f;
        float px = Math.max(0f, width.resolve(fontSize));
        StrokeAlign align = general.strokeAlign();
        return align == StrokeAlign.INSET ? 0f : align == StrokeAlign.CENTER ? px * 0.5f : px;
    }

    /**
     * How far the text paints past its box, per side: the stroke's outward reach, and for each outer shadow its offset
     * plus {@code ceil(3 * sigma)} plus its spread plus that same reach, Blink's {@code ShadowData::RectOutsets}. Inset
     * shadows stay inside the glyphs and add nothing.
     *
     * @param strokeOutwardPx {@link #outwardPx}, since a shadow's shape includes the stroke
     */
    public static InkOverflow inkOverflow(ShadowList shadows, float strokeOutwardPx) {
        float stroke = Math.max(0f, strokeOutwardPx);
        float left = stroke, top = stroke, right = stroke, bottom = stroke;
        int count = shadows == null ? 0 : shadows.size();
        for (int i = 0; i < count; i++) {
            Shadow s = shadows.get(i);
            if (s.inset()) continue;
            float blurAndSpread = (float) Math.ceil(3.0 * s.sigma()) + Math.max(0f, s.spread()) + stroke;
            left = Math.max(left, blurAndSpread - s.x());
            right = Math.max(right, blurAndSpread + s.x());
            top = Math.max(top, blurAndSpread - s.y());
            bottom = Math.max(bottom, blurAndSpread + s.y());
        }
        return left == 0f && top == 0f && right == 0f && bottom == 0f
                ? InkOverflow.NONE : new InkOverflow(left, top, right, bottom);
    }

    /** Once per distinct (width, size, ceiling): a paint method runs every frame. */
    private static void warnStrokeClamped(float widthEm, float fontSize, float ceilingEm) {
        // The size check first, so a full table builds no key string per label per frame.
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
