package com.crystalgui.style.property.visual.texture;

import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.render.texture.CgUiCrossFade;
import com.crystalgui.render.texture.ArgbMath;
import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.render.texture.CgUiRect;
import com.crystalgui.render.texture.CgUiLayers;
import com.crystalgui.render.texture.CgUiShape;
import lombok.experimental.Accessors;

@Accessors(chain = true)
public class TextureProperty extends StyleProperty<CgUiDrawable> {
    public TextureProperty(String name, CgUiDrawable initialValue) {
        super(name, CgUiDrawable.class, initialValue, TextureValue::new);
        setAllowTransition(true);
        setInterpolator(this::interpolate);
    }

    /**
     * {@code background}/{@code overlay} hold a {@code CgUiRect} or one of the self-drawing kinds
     * (gradient, svg, glass) — rounding and border are the painter's, not a background value type —
     * and there is no shared parameter space to lerp between two arbitrary drawables, so this always
     * draws both and blends opacity via {@link CgUiCrossFade}.
     */
    private CgUiDrawable interpolate(CgUiDrawable from, CgUiDrawable to, float lerp) {
        return new CgUiCrossFade(from, to, lerp);
    }

    /**
     * The CSS that produced this drawable.
     *
     * <p>A drawable cannot describe itself: {@code CgUiSvg} holds a parsed document and {@code CgUiSprite}
     * resolved UVs, so the arguments that made them are gone by the time anyone asks. What is kept is the
     * text they were parsed from ({@link TextureValue#sourceOf}), which is a better answer anyway — it is
     * exactly what a person wrote, and re-parsing it is deterministic.</p>
     *
     * <p>Two cases the map cannot answer, and both have one:</p>
     * <ul>
     *   <li>the shared EMPTY, which is what every {@code none} in every sheet resolves to, so it is
     *       spelled rather than remembered;</li>
     *   <li>a flat colour built in Java — a widget writing {@code CgUiRect.ofColor(argb)} rather than a
     *       stylesheet — which writes as the colour it holds;</li>
     *   <li>a vector mark, which is a record over one enum and so is exactly {@code shape(kind)}.</li>
     * </ul>
     *
     * <p><b>Both of those are also the two whose equality is value-based, and that is not a
     * coincidence any more — it is the rule.</b> {@link TextureValue#sourceOf} is a weak map keyed by
     * equality, so two equal drawables share one entry keyed on whichever was seen first; when THAT one
     * is collected the entry goes with it and the live one is left with no source. It takes a temporary
     * to trigger — the codec's own write-then-read check makes one — so it appears as a shape that has
     * been on screen all along suddenly having no CSS. A drawable that can describe itself does not
     * depend on the map at all, which is why anything with value equality must.</p>
     *
     * <p>Anything else built in Java throws, and says so plainly: a drawable that reached an element
     * without going through CSS has no CSS to write, and inventing one would put something on the
     * clipboard that renders differently from what was copied.</p>
     */
    @Override
    public String write(CgUiDrawable value) {
        if (value == CgUiDrawable.EMPTY) return "none";
        String source = TextureValue.sourceOf(value);
        if (source != null) return source;
        if (value instanceof CgUiRect rect && rect.isPlain()
                && rect.getFill() instanceof CgUiRect.Fill.Color(int argb)) {
            return ArgbMath.toCss(argb);
        }
        if (value instanceof CgUiShape shape) return "shape(\"" + CgUiShape.cssName(shape.kind()) + "\")";
        // A STACK IS ITS LAYERS, written the same way and comma-joined. It needs this rather than a
        // remembered source because a stack is BUILT in Java whenever one layer is pasted onto another
        // element -- `DrawableParts.mergePart` assembles a new one, and the merged value is re-encoded
        // on the spot, which threw.
        if (value instanceof CgUiLayers layers) {
            StringBuilder out = new StringBuilder();
            for (CgUiDrawable layer : layers.layers()) {
                if (out.length() > 0) out.append(", ");
                out.append(write(layer));
            }
            return out.toString();
        }
        throw new IllegalStateException("A " + value.getClass().getSimpleName() + " for '" + name
                + "' was built in Java rather than parsed from CSS, so there is no CSS to write for it."
                + " Set it from a stylesheet or an inline style, or give it a source.");
    }
}
