package com.crystalgui.render.text;

import com.crystalgraphics.text.render.CgTextRenderer;
import com.crystalgui.style.GeneralGroup;
import com.crystalgui.style.property.visual.shadow.Shadow;
import com.crystalgui.style.property.visual.shadow.ShadowList;
import com.crystalgui.ui.box.InkOverflow;

/**
 * Puts the cascade's {@code text-shadow} on a text draw, and says how far it paints outside the box.
 *
 * <p>Call it beside {@link TextStrokeStyle}, between building a draw and submitting it:</p>
 *
 * <pre>{@code
 * CgTextRenderer.Draw draw = ctx.text().draw().layout(layout).family(family).at(x, y).color(fill);
 * TextStrokeStyle.applyTo(draw, family, general, computedStyle(), color);
 * TextShadowStyle.applyTo(draw, general, color);
 * draw.submit();
 * }</pre>
 *
 * <p>And from the widget's {@code inkOverflow}, so a shadow inside a layer is not clipped at the box:</p>
 *
 * <pre>{@code
 * @Override public InkOverflow inkOverflow() {
 *     GeneralGroup general = getStyle().getGeneralGroup();
 *     return TextShadowStyle.inkOverflow(general.textShadow(), TextStrokeStyle.outwardPx(general));
 * }
 * }</pre>
 *
 * <p><b>Every widget that paints its own glyphs has to call this</b>, for the reason
 * {@link TextStrokeStyle} gives: {@code text-shadow} is INHERITABLE, and a draw that skips it leaves the
 * property computing correctly and doing nothing.</p>
 *
 * <p>Easy to get wrong: pass the element's {@code color}, which is what {@code currentcolor} means in a
 * shadow, not {@code text-fill-color}. A placeholder that dims its text passes the dimmed colour, as its
 * stroke does.</p>
 */
public final class TextShadowStyle {

    private TextShadowStyle() {
    }

    public static void applyTo(CgTextRenderer.Draw draw, GeneralGroup general, int currentColor) {
        ShadowList shadows = general.textShadow();
        if (shadows == null || shadows.isEmpty()) return;
        draw.shadowCount(shadows.size());
        for (int i = 0; i < shadows.size(); i++) {
            Shadow s = shadows.get(i);
            draw.shadow(i, s.x(), s.y(), s.sigma(), s.spread(), s.color().resolve(currentColor), s.inset());
        }
    }

    /**
     * Appends {@code shadows} to the draw, applying only to glyphs of {@code scope}: a
     * {@code ::highlight}'s shadows, after the element's own, so they paint beneath them.
     *
     * <pre>{@code
     * TextShadowStyle.applyTo(draw, general, color);                         // every glyph
     * draw.shadowScopes(scopeByGlyph);
     * TextShadowStyle.appendScoped(draw, highlight.textShadow(), 0, highlight.color(color));
     * }</pre>
     */
    public static void appendScoped(CgTextRenderer.Draw draw, ShadowList shadows, int scope, int currentColor) {
        if (shadows == null || shadows.isEmpty()) return;
        int base = draw.shadowCount();
        draw.shadowCount(base + shadows.size());
        for (int i = 0; i < shadows.size(); i++) {
            Shadow s = shadows.get(i);
            draw.shadow(base + i, s.x(), s.y(), s.sigma(), s.spread(), s.color().resolve(currentColor), s.inset())
                    .shadowScope(base + i, scope);
        }
    }

    /**
     * How far the text paints past its box, per side: the stroke's outward reach, and for each outer shadow
     * its offset plus {@code ceil(3 * sigma)} plus its spread plus that same reach, Blink's
     * {@code ShadowData::RectOutsets}. Inset shadows stay inside the glyphs and add nothing.
     *
     * @param strokeOutwardPx {@link TextStrokeStyle#outwardPx}, since a shadow's shape includes the stroke
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
}
