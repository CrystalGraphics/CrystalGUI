package com.crystalgui.render.texture;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgui.render.CgUiPaintContext;

import java.util.ArrayList;
import java.util.List;

/**
 * A linear gradient — CSS's {@code linear-gradient(direction, stop, stop, ...)} as a drawable.
 *
 * <h3>One draw, evaluated per fragment</h3>
 *
 * <p>The whole gradient is one quad through {@code gui_gradient.shader}: the stops travel as material
 * properties and every fragment evaluates the ramp at its own position along CSS's gradient line.
 * That is Skia's shape (an unrolled shader for up to eight stops) and it replaced a quad per pair of
 * stops, which was more material switches for the same pixels and — because {@link CgUiPaintContext}
 * uploads a material's properties on the bind <em>after</em> the draw body — drew every segment with the
 * previous segment's colours. Eight stops per draw; a longer gradient is several draws, each owning a
 * half-open window of {@code t}, so no fragment is written twice and nothing is silently dropped.</p>
 *
 * <h3>What the shader is faithful to</h3>
 *
 * <ul>
 *   <li><b>Any angle.</b> {@code 0deg} is to top, clockwise, and {@code to <side>} / {@code to <corner>}
 *       resolve as CSS resolves them — a corner's angle depends on the box, so it is computed per draw
 *       ({@link #angleFor}). The line runs through the centre with the length that puts 0% and 100% at
 *       the corners (CSS Images 3 §3.4.1), which is what {@link #axisFor} hands the shader.</li>
 *   <li><b>Premultiplied interpolation</b> (CSS Images 3 §3.4.3), because {@code transparent} is
 *       transparent black: a straight lerp from it to a blue passes through a dark half-blue, which is
 *       the muddy shoulder every naive fade has. {@link #colorAt} is the same maths on the CPU.</li>
 *   <li><b>Dithered</b>, half a level of hash noise before the target quantises, because per-fragment
 *       mixing removes the strip edges the old draw had and not the level edges an 8-bit target imposes
 *       on any shallow ramp. Strips banded at under two levels per twenty pixels.</li>
 *   <li><b>Clipped by the element's own corners</b> — {@link CornerRadiusAware}, so a gradient on a
 *       rounded element masks itself with the same SDF the rounded wrap would have used. The documented
 *       gap for a self-clipping background applies: {@code border-width} is not stroked over it.</li>
 * </ul>
 *
 * <p>Missing stop positions spread evenly between their positioned neighbours and the first and last
 * default to 0% and 100%, as CSS spreads them; a position never goes backwards. Colour interpolation
 * hints ({@code 50%} on its own) and {@code repeating-linear-gradient} are not parsed.</p>
 *
 * <p>Research and the sources for each rule: {@code docs/CGUI_MODERN_UI_RENDERING_RESEARCH.md} §8.</p>
 */
public final class CgUiGradient implements CgUiDrawable, CornerRadiusAware {

    private static final CgMaterial MATERIAL = CgMaterial.load("crystalgui:shaders/gui_gradient.shader");

    /** Stops one draw evaluates — Skia's unrolled limit, and the shader's property count. */
    public static final int STOPS_PER_DRAW = 8;

    private static final String[] STOP_PROPERTIES = {
            "_Color0", "_Color1", "_Color2", "_Color3", "_Color4", "_Color5", "_Color6", "_Color7"};

    /** One colour at a fraction of the gradient's length; {@code NaN} means "spread me evenly". */
    public record Stop(float position, int argb) {}

    /**
     * CSS's {@code to <corner>}: the gradient runs from the opposite corner to this one, at whatever
     * angle puts the 50% line through the other two corners — so it depends on the box.
     */
    public enum Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT }

    /** The angle, when the direction is not a corner. */
    private final float angleDeg;
    private final Corner corner;
    private final List<Stop> stops;

    private float rxTL, ryTL, rxTR, ryTR, rxBR, ryBR, rxBL, ryBL;

    /**
     * @param angleDeg CSS's angle: 0 = to top, 90 = to right, 180 = to bottom, 270 = to left
     * @param stops    at least two, in order
     */
    public CgUiGradient(float angleDeg, List<Stop> stops) {
        this.angleDeg = ((angleDeg % 360f) + 360f) % 360f;
        this.corner = null;
        this.stops = resolvePositions(stops);
    }

    /** {@code to top right} and friends. */
    public CgUiGradient(Corner corner, List<Stop> stops) {
        this.angleDeg = Float.NaN;
        this.corner = corner;
        this.stops = resolvePositions(stops);
    }

    /** CSS's rule for missing positions: first 0, last 1, the rest evenly between their neighbours. */
    private static List<Stop> resolvePositions(List<Stop> in) {
        if (in.size() < 2) throw new IllegalArgumentException("a gradient needs at least two stops");
        float[] pos = new float[in.size()];
        for (int i = 0; i < in.size(); i++) pos[i] = in.get(i).position();
        if (Float.isNaN(pos[0])) pos[0] = 0f;
        if (Float.isNaN(pos[pos.length - 1])) pos[pos.length - 1] = 1f;
        int i = 1;
        while (i < pos.length - 1) {
            if (!Float.isNaN(pos[i])) { i++; continue; }
            int j = i;
            while (Float.isNaN(pos[j])) j++;
            float from = pos[i - 1], to = pos[j];
            int gaps = j - i + 1;
            for (int k = i; k < j; k++) pos[k] = from + (to - from) * (k - i + 1) / gaps;
            i = j;
        }
        // Positions never go backwards -- CSS clamps a stop to the largest position before it.
        for (int k = 1; k < pos.length; k++) pos[k] = Math.max(pos[k], pos[k - 1]);
        List<Stop> out = new ArrayList<>(in.size());
        for (int k = 0; k < in.size(); k++) {
            out.add(new Stop(Math.max(0f, Math.min(1f, pos[k])), in.get(k).argb()));
        }
        return out;
    }

    public List<Stop> stops() {
        return stops;
    }

    /** The declared angle, or {@code NaN} when the direction is a {@link Corner}. */
    public float angleDeg() {
        return angleDeg;
    }

    public Corner corner() {
        return corner;
    }

    /**
     * The angle the gradient runs at over a {@code width}×{@code height} box, in degrees.
     *
     * <p>A declared angle is itself. A corner is the angle whose perpendicular is the box's other
     * diagonal: for {@code to top right} the direction is perpendicular to the top-left → bottom-right
     * diagonal, so its angle is {@code atan2(height, width)} — 45° on a square, shallower on a wide box.</p>
     */
    public float angleFor(float width, float height) {
        if (corner == null) return angleDeg;
        float diagonal = (float) Math.toDegrees(Math.atan2(height, width));
        return switch (corner) {
            case TOP_RIGHT -> diagonal;
            case BOTTOM_RIGHT -> 180f - diagonal;
            case BOTTOM_LEFT -> 180f + diagonal;
            case TOP_LEFT -> 360f - diagonal;
        };
    }

    /**
     * The shader's {@code _Axis} for a box: the vector such that {@code t = 0.5 + dot(uv - 0.5, axis)}
     * is CSS's position along the gradient line, with 0 and 1 at the corners the line's length reaches.
     *
     * <p>The direction is {@code (sin a, -cos a)} — 0° points up in a Y-down projection — and the line's
     * length is {@code |W sin a| + |H cos a|}. In box-normalised space that is the direction scaled by
     * the box and divided by the length, so the axis-aligned cases collapse: 90° gives {@code (1, 0)},
     * 180° gives {@code (0, 1)}.</p>
     */
    public float[] axisFor(float width, float height) {
        double radians = Math.toRadians(angleFor(width, height));
        float s = (float) Math.sin(radians), c = (float) Math.cos(radians);
        float length = Math.abs(width * s) + Math.abs(height * c);
        if (length <= 0f) return new float[]{1f, 0f};
        return new float[]{width * s / length, -height * c / length};
    }

    /** The gradient's colour at {@code t} in {@code [0, 1]} along its line, before any tint. */
    public int colorAt(float t) {
        Stop first = stops.get(0);
        if (t <= first.position()) return first.argb();
        for (int i = 1; i < stops.size(); i++) {
            Stop prev = stops.get(i - 1), next = stops.get(i);
            if (t <= next.position()) {
                float span = next.position() - prev.position();
                if (span <= 0f) return next.argb();
                return ArgbMath.lerpPremultiplied(prev.argb(), next.argb(), (t - prev.position()) / span);
            }
        }
        return stops.get(stops.size() - 1).argb();
    }

    @Override
    public void setCornerRadii(float rxTL, float ryTL, float rxTR, float ryTR,
                               float rxBR, float ryBR, float rxBL, float ryBL) {
        this.rxTL = rxTL; this.ryTL = ryTL;
        this.rxTR = rxTR; this.ryTR = ryTR;
        this.rxBR = rxBR; this.ryBR = ryBR;
        this.rxBL = rxBL; this.ryBL = ryBL;
    }

    @Override
    public void draw(CgUiPaintContext ctx, float mouseX, float mouseY, float x, float y, float width, float height) {
        if (width <= 0f || height <= 0f) return;
        int tint = ctx.getColor();
        if ((tint >>> 24) == 0) return;
        float[] axis = axisFor(width, height);
        boolean rounded = rxTL > 0f || ryTL > 0f || rxTR > 0f || ryTR > 0f
                || rxBR > 0f || ryBR > 0f || rxBL > 0f || ryBL > 0f;
        MATERIAL.toggleKeyword("WITH_MASK", rounded);

        // Windows of eight, each sharing its first stop with the previous window's last so the ramp is
        // continuous across the seam, and each owning [first stop, last stop) of t -- the first from
        // minus infinity, the last to plus infinity, which is where CSS pads with the end colours.
        int last = stops.size() - 1;
        int first = 0;
        while (true) {
            int end = Math.min(first + STOPS_PER_DRAW - 1, last);
            drawWindow(ctx, x, y, width, height, tint, axis, first, end,
                    first == 0 ? -1f : stops.get(first).position(),
                    end == last ? 2f : stops.get(end).position());
            if (end >= last) break;
            first = end;
        }
    }

    /**
     * One draw over the whole box, evaluating stops {@code first..end} and writing only fragments
     * whose {@code t} is in {@code [windowFrom, windowTo)}.
     *
     * <p>Properties are set INSIDE the body and nothing is flushed there: {@code withMaterial} uploads
     * them on its second bind and flushes after it, which is the ordering {@link CgUiRoundedRect}
     * follows and the one that drew every segment with stale colours when it was broken here.</p>
     */
    private void drawWindow(CgUiPaintContext ctx, float x, float y, float width, float height, int tint,
                            float[] axis, int first, int end, float windowFrom, float windowTo) {
        ctx.withMaterial(MATERIAL, () -> {
            MATERIAL.applyProperties(b -> {
                float[] positions = new float[STOPS_PER_DRAW];
                for (int k = 0; k < STOPS_PER_DRAW; k++) {
                    // Slots past the window's last stop repeat it; the shader stops at _Count anyway.
                    Stop stop = stops.get(Math.min(first + k, end));
                    int argb = stop.argb();
                    float a = ((argb >>> 24) & 0xFF) / 255f;
                    float r = ((argb >>> 16) & 0xFF) / 255f;
                    float g = ((argb >>> 8) & 0xFF) / 255f;
                    float bl = (argb & 0xFF) / 255f;
                    b.vec4(STOP_PROPERTIES[k], r * a, g * a, bl * a, a);
                    positions[k] = stop.position();
                }
                b.vec4("_Positions0", positions[0], positions[1], positions[2], positions[3]);
                b.vec4("_Positions1", positions[4], positions[5], positions[6], positions[7]);
                b.set1f("_Count", end - first + 1);
                b.vec2("_Axis", axis[0], axis[1]);
                b.vec2("_Window", windowFrom, windowTo);
                b.vec4("_CornerRadiusX", rxTL, rxTR, rxBR, rxBL);
                b.vec4("_CornerRadiusY", ryTL, ryTR, ryBR, ryBL);
                b.vec2("_BoxSize", width, height);
            });
            ctx.quad().at(x, y).size(width, height).color(tint).submit();
        });
    }
}
