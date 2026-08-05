package com.crystalgui.render.texture.svg;

import java.util.Map;

/**
 * A linear or radial paint server, evaluated per point.
 *
 * <h3>Why one colour was not enough</h3>
 *
 * <p>The first pass collapsed every gradient to its midpoint stop. That is defensible for a two-stop
 * gradient and badly wrong for real artwork, because of one detail: {@code gradientUnits="userSpaceOnUse"}
 * states the axis in the <b>document's</b> coordinates, not the shape's, and an exported logo routinely
 * gives a shape an axis several times its own size. The shape then occupies a narrow slice of the ramp —
 * so its actual colours have nothing to do with the ramp's middle.</p>
 *
 * <p>The JetBrains mark is the case in point. Its four gradients all run orange-or-pink to blue over axes
 * up to 87 units long inside a 70-unit box, and every one of them has a deliberately desaturated stop near
 * {@code 0.5} to make that blend read smoothly. Sampling the middle picks exactly those stops: the whole
 * logo comes out brown and purple, which is a plausible-looking picture of the wrong thing.</p>
 *
 * <h3>Evaluated on the CPU, per triangle</h3>
 *
 * <p>{@code CgVectorRenderer.Triangle} takes one flat colour, so the gradient is realised by subdivision —
 * the fill is cut fine enough along the gradient's own direction that a flat colour per triangle is
 * indistinguishable from a ramp. That costs triangles and no shader work at all, which is the right trade
 * at icon sizes: a paint-server material would mean a second shader, a second bind, and a per-shape uniform
 * upload, to remove banding nobody can see.</p>
 *
 * <p>Cutting along the gradient's <em>direction</em> rather than uniformly is what keeps the count sane. A
 * horizontal gradient needs no horizontal cuts at all, so its shape stays at a handful of bands however
 * tall it is.</p>
 */
public record SvgGradient(boolean radial, boolean userSpace, SvgTransform transform,
                          float x1, float y1, float x2, float y2,
                          float[] offsets, int[] colours, int spread) {

    /** Clamp at both ends — SVG's default and the only one exported artwork uses in practice. */
    public static final int SPREAD_PAD = 0;
    public static final int SPREAD_REFLECT = 1;
    public static final int SPREAD_REPEAT = 2;

    /**
     * How finely a gradient fill is cut, as a fraction of the whole ramp.
     *
     * <p>32 bands across the full colour range. Chosen against the visible artefact rather than by feel:
     * the widest jump between adjacent stops in the JetBrains gradients is about a fifth of the range, so a
     * band carries at most a few units of colour difference — under the threshold where a flat step reads
     * as a line. Doubling it doubles the triangle count and removes nothing anyone can see.</p>
     */
    public static final int STEPS = 32;

    /**
     * Where {@code (x, y)} falls on the ramp, in {@code [0, 1]} after the spread method.
     *
     * @param box the shape's bounding box as {@code minX, minY, width, height} — only read when the
     *            gradient is in {@code objectBoundingBox} units, which is SVG's default
     */
    public float parameterAt(float x, float y, float[] box) {
        float ax1 = x1, ay1 = y1, ax2 = x2, ay2 = y2;
        if (!userSpace) {
            // objectBoundingBox: the axis is stated as a fraction of the shape's own box, so it has to be
            // resolved per shape rather than once at parse time -- the same gradient element legitimately
            // paints two shapes of different sizes.
            ax1 = box[0] + ax1 * box[2];
            ay1 = box[1] + ay1 * box[3];
            ax2 = box[0] + ax2 * box[2];
            ay2 = box[1] + ay2 * box[3];
        }
        // gradientTransform maps the gradient's space into the element's, so a sample point travels the
        // other way. Applied to the axis instead of inverting the matrix: for a linear gradient the two are
        // equivalent, and it avoids an inversion that a degenerate matrix would make undefined.
        if (transform != SvgTransform.IDENTITY) {
            float tx1 = transform.applyX(ax1, ay1), ty1 = transform.applyY(ax1, ay1);
            float tx2 = transform.applyX(ax2, ay2), ty2 = transform.applyY(ax2, ay2);
            ax1 = tx1;
            ay1 = ty1;
            ax2 = tx2;
            ay2 = ty2;
        }

        float t;
        if (radial) {
            // x1,y1 is the centre and x2 carries the radius; a focal point is ignored, which shifts the
            // highlight and never changes which colours appear.
            float radius = Math.max(1e-6f, ax2 - ax1);
            float dx = x - ax1, dy = y - ay1;
            t = (float) Math.sqrt(dx * dx + dy * dy) / radius;
        } else {
            float dx = ax2 - ax1, dy = ay2 - ay1;
            float lengthSq = dx * dx + dy * dy;
            if (lengthSq < 1e-9f) return 0f;
            t = ((x - ax1) * dx + (y - ay1) * dy) / lengthSq;
        }
        return spread(t);
    }

    private float spread(float t) {
        return switch (spread) {
            case SPREAD_REPEAT -> t - (float) Math.floor(t);
            case SPREAD_REFLECT -> {
                float wrapped = Math.abs(t) % 2f;
                yield wrapped > 1f ? 2f - wrapped : wrapped;
            }
            default -> Math.max(0f, Math.min(1f, t));
        };
    }

    /** The colour at a ramp position, interpolating between the two stops that bracket it. */
    public int colourAt(float t) {
        if (colours.length == 0) return 0xFF000000;
        if (colours.length == 1 || t <= offsets[0]) return colours[0];
        if (t >= offsets[offsets.length - 1]) return colours[colours.length - 1];
        for (int i = 0; i + 1 < offsets.length; i++) {
            if (t < offsets[i] || t > offsets[i + 1]) continue;
            float span = offsets[i + 1] - offsets[i];
            float local = span < 1e-6f ? 0f : (t - offsets[i]) / span;
            return SvgColor.mix(colours[i], colours[i + 1], local);
        }
        return colours[colours.length - 1];
    }

    public int colourAt(float x, float y, float[] box) {
        return colourAt(parameterAt(x, y, box));
    }

    /** The single colour this reduces to when it has to be one — used for strokes and as a fallback. */
    public int representativeColour() {
        return colourAt(0.5f);
    }

    /**
     * How far apart, in user units, two samples may sit before the step between them shows.
     *
     * <p>Returned per axis, and a zero means "this axis does not change the colour at all" — which is the
     * whole reason the caller asks: a horizontal gradient returns {@code 0} for y, so a tall shape is not
     * cut into a hundred bands to render a ramp that does not vary down it.</p>
     */
    public float[] sampleSpacing(float[] box) {
        float ax1 = x1, ay1 = y1, ax2 = x2, ay2 = y2;
        if (!userSpace) {
            ax1 = box[0] + ax1 * box[2];
            ay1 = box[1] + ay1 * box[3];
            ax2 = box[0] + ax2 * box[2];
            ay2 = box[1] + ay2 * box[3];
        }
        float target = 1f / STEPS;
        if (radial) {
            float radius = Math.max(1e-6f, ax2 - ax1);
            float step = radius * target;
            return new float[]{step, step};
        }
        float dx = ax2 - ax1, dy = ay2 - ay1;
        float lengthSq = dx * dx + dy * dy;
        if (lengthSq < 1e-9f) return new float[]{0f, 0f};
        // dt/dx and dt/dy of the projection, inverted into "units per band".
        float perX = Math.abs(dx) / lengthSq;
        float perY = Math.abs(dy) / lengthSq;
        return new float[]{perX < 1e-9f ? 0f : target / perX, perY < 1e-9f ? 0f : target / perY};
    }

    // ── Parsing ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Builds a gradient from a tag's attributes and its already-collected stops.
     *
     * @param inherited the gradient this one points at with {@code href}, for geometry it does not state
     *                  itself; null when there is none
     */
    public static SvgGradient of(Map<String, String> attributes, float[] offsets, int[] colours,
                                 SvgGradient inherited) {
        boolean radial = "radialGradient".equals(attributes.get("__tag__"));
        String units = attributes.get("gradientUnits");
        boolean userSpace = units != null
                ? units.trim().equals("userSpaceOnUse")
                : inherited != null && inherited.userSpace();

        SvgTransform transform = attributes.containsKey("gradientTransform")
                ? SvgTransform.parse(attributes.get("gradientTransform"))
                : inherited != null ? inherited.transform() : SvgTransform.IDENTITY;

        float x1, y1, x2, y2;
        if (radial) {
            // Packed into the same four fields as a linear axis: centre in the first pair, radius in x2.
            // Two record shapes for what is one evaluation with two formulas would be a hierarchy earning
            // its keep only in the type checker.
            x1 = coordinate(attributes, "cx", inherited != null ? inherited.x1() : 0.5f, userSpace);
            y1 = coordinate(attributes, "cy", inherited != null ? inherited.y1() : 0.5f, userSpace);
            x2 = x1 + coordinate(attributes, "r", inherited != null ? inherited.x2() - inherited.x1() : 0.5f,
                    userSpace);
            y2 = y1;
        } else {
            x1 = coordinate(attributes, "x1", inherited != null ? inherited.x1() : 0f, userSpace);
            y1 = coordinate(attributes, "y1", inherited != null ? inherited.y1() : 0f, userSpace);
            x2 = coordinate(attributes, "x2", inherited != null ? inherited.x2() : 1f, userSpace);
            y2 = coordinate(attributes, "y2", inherited != null ? inherited.y2() : 0f, userSpace);
        }

        if (colours.length == 0 && inherited != null) {
            offsets = inherited.offsets();
            colours = inherited.colours();
        }
        String spreadRaw = attributes.get("spreadMethod");
        int spread = spreadRaw == null ? (inherited != null ? inherited.spread() : SPREAD_PAD)
                : switch (spreadRaw.trim()) {
                    case "reflect" -> SPREAD_REFLECT;
                    case "repeat" -> SPREAD_REPEAT;
                    default -> SPREAD_PAD;
                };
        return new SvgGradient(radial, userSpace, transform, x1, y1, x2, y2, offsets, colours, spread);
    }

    private static float coordinate(Map<String, String> attributes, String name,
                                    float fallback, boolean userSpace) {
        String raw = attributes.get(name);
        if (raw == null || raw.isBlank()) return fallback;
        String value = raw.trim();
        try {
            if (value.endsWith("%")) {
                float percent = Float.parseFloat(value.substring(0, value.length() - 1)) / 100f;
                // A percentage in objectBoundingBox units IS the fraction; in user space it is a percentage
                // of the viewport, which we do not track -- treating it as the fraction keeps it in range.
                return percent;
            }
            return Float.parseFloat(value);
        } catch (NumberFormatException malformed) {
            return fallback;
        }
    }
}
