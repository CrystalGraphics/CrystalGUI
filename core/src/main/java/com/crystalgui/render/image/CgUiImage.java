package com.crystalgui.render.image;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.api.paint.CgPaint;
import com.crystalgraphics.api.paint.gradient.CgGradient;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgraphics.api.vector.svg.CgSvg;
import com.crystalgraphics.api.paint.CgColor;
import com.crystalgui.render.texture.asset.FileIconTheme;

/**
 * What a {@code background}, {@code overlay}, {@code mask} or {@code outline} holds: CSS's {@code <image>}. An
 * immutable value, compared by value, that writes itself back as the CSS it came from.
 *
 * <pre>{@code
 * element.generalStyle(g -> g.background(CgUiImage.color(0xFF203040)));
 * element.generalStyle(g -> g.overlay(CgUiImage.icon("crystalgui:folder")));        // follows the theme's variant
 * element.generalStyle(g -> g.background(CgUiImage.painted(new HueRing(hue))));    // drawn by hand
 * CgUiImage parsed = ImageValue.parse("linear-gradient(to right, #000, #FFF)");
 * String css = parsed.css();                                                       // the same text back
 * }</pre>
 *
 * <ul>
 *   <li>{@link #NONE} is CSS's {@code none}: nothing drawn.</li>
 *   <li>Every kind but {@link Paint} and an unnamed {@link Painted} has CSS; those two are built in Java and
 *       {@link #css} throws for them, naming the kind.</li>
 *   <li>A transition between two values interpolates where they allow it and cross-fades otherwise
 *       ({@link #interpolate}).</li>
 * </ul>
 */
public sealed interface CgUiImage permits CgUiImage.None, CgUiImage.Color, CgUiImage.Gradient, CgUiImage.Grid,
        CgUiImage.Picture, CgUiImage.Sheet, CgUiImage.Icon, CgUiImage.Mark, CgUiImage.Painted, CgUiImage.Paint,
        CgUiImage.CrossFade, CgUiImage.Layers {

    /** CSS's {@code none}. */
    None NONE = None.NONE;

    /** This value as CSS, which parses back to an equal value. */
    String css();

    /** Whether it covers any pixel at most once, so an element's opacity can fold into it rather than group it. */
    default boolean onePrimitive() {
        return true;
    }

    /** Whether it draws in the element's {@code color} ({@code currentColor}). */
    default boolean followsColor() {
        return false;
    }

    // ── Factories ─────────────────────────────────────────────────────────────

    static Color color(int argb) {
        return new Color(argb);
    }

    /** An icon by name, its light or dark drawing chosen by the theme when it is drawn. */
    static Icon icon(String name) {
        return new Icon(name, true, 0xFFFFFFFF, false, 0f, null);
    }

    /**
     * The icon named {@code name}, or null when its file is missing or CrystalGraphics cannot draw it: a typo is not
     * an invisible icon. Reads the file once (it is cached), so call it where an icon is chosen, not per frame.
     */
    @Nullable
    static Icon findIcon(@Nullable String name) {
        if (name == null || name.isEmpty()) return null;
        Icon icon = icon(name);
        try {
            return CgSvg.of(icon.path()) != null ? icon : null;
        } catch (IllegalArgumentException refused) {
            CrystalGuiCore.LOGGER.warn("[svg] {} cannot be drawn: {}", icon.path(), refused.getMessage());
            return null;
        }
    }

    /** An SVG file, drawn as it is. */
    static Icon svg(String path) {
        return new Icon(path, false, 0xFFFFFFFF, false, 0f, null);
    }

    static Mark mark(CgUiMark mark) {
        return new Mark(mark);
    }

    /** A painter set from Java; it has no CSS. */
    static Painted painted(CgUiPainter painter) {
        return new Painted(null, painter);
    }

    /** A CrystalGraphics paint set from Java; only a solid colour has CSS. */
    static Paint paint(CgPaint paint) {
        return new Paint(paint);
    }

    /** {@code layers} drawn first on top, as CSS stacks a comma list; one layer is itself. */
    static CgUiImage layers(List<CgUiImage> layers) {
        return layers.size() == 1 ? layers.get(0) : new Layers(layers);
    }

    /**
     * {@code from} at {@code 1 - t} and {@code to} at {@code t}: colours and like gradients mix, anything else
     * cross-fades.
     */
    static CgUiImage interpolate(CgUiImage from, CgUiImage to, float t) {
        if (t <= 0f) return from;
        if (t >= 1f) return to;
        if (from.equals(to)) return to;
        if (from instanceof Color a && to instanceof Color b) {
            return new Color(CgColor.lerpArgbPremultiplied(a.argb(), b.argb(), t));
        }
        if (from instanceof Gradient a && to instanceof Gradient b && a.sameShape(b)) return a.mix(b, t);
        // A fade still running is the picture it is now, under the next one
        return new CrossFade(from, to, t);
    }

    // ── The kinds ─────────────────────────────────────────────────────────────

    /** Nothing. */
    enum None implements CgUiImage {
        NONE;

        @Override
        public String css() {
            return "none";
        }
    }

    /** A flat colour, straight {@code 0xAARRGGBB}. */
    record Color(int argb) implements CgUiImage {
        @Override
        public String css() {
            return CgColor.toHex(argb);
        }
    }

    /**
     * CSS's {@code linear-gradient} or {@code radial-gradient}. A corner direction and a radial's extents depend on the
     * box, so the paint is resolved for each box size it is drawn at ({@link #paint}).
     */
    final class Gradient implements CgUiImage {

        /** One colour at a fraction of the gradient's length; {@code NaN} spreads it evenly, as CSS does. */
        public record Stop(float position, int argb) {
        }

        /** CSS's {@code to <corner>}: its angle is the one whose perpendicular is the box's other diagonal. */
        public enum Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT }

        /** CSS's {@code <radial-extent>}: the side or corner the ending shape reaches. */
        public enum Extent { CLOSEST_SIDE, CLOSEST_CORNER, FARTHEST_SIDE, FARTHEST_CORNER }

        /** Pixels, or a fraction of the box's width or height. */
        public record Length(float value, boolean percent) {
            public static final Length CENTRE = new Length(0.5f, true);

            public float resolve(float reference) {
                return percent ? value * reference : value;
            }
        }

        /** A radial gradient's ending shape and centre; {@code sizeX} null takes the {@code extent}. */
        public record Radial(boolean circle, Extent extent, @Nullable Length sizeX, @Nullable Length sizeY,
                             Length centreX, Length centreY) {
            public static final Radial DEFAULT =
                    new Radial(false, Extent.FARTHEST_CORNER, null, null, Length.CENTRE, Length.CENTRE);
        }

        private final float angle;
        @Nullable
        private final Corner corner;
        @Nullable
        private final Radial radial;
        /** As written, positions {@code NaN} where CSS spreads them: what {@link #css} writes and equality compares. */
        private final List<Stop> declared;
        private final float[] positions;
        private final int[] colours;
        /** The last box's paint; not part of the value. */
        @Nullable
        private volatile Resolved resolved;

        private record Resolved(float width, float height, CgGradient paint) {
        }

        private Gradient(float angle, @Nullable Corner corner, @Nullable Radial radial, List<Stop> stops) {
            if (stops.size() < 2) throw new IllegalArgumentException("a gradient needs at least two stops");
            this.angle = Float.isNaN(angle) ? angle : ((angle % 360f) + 360f) % 360f;
            this.corner = corner;
            this.radial = radial;
            this.declared = List.copyOf(stops);
            this.positions = spread(stops);
            this.colours = new int[stops.size()];
            for (int i = 0; i < colours.length; i++) colours[i] = stops.get(i).argb();
        }

        /** {@code linear-gradient(<angle>deg, ...)}: 0 to top, clockwise. */
        public static Gradient linear(float degrees, List<Stop> stops) {
            return new Gradient(degrees, null, null, stops);
        }

        /** {@code linear-gradient(to <corner>, ...)}. */
        public static Gradient linear(Corner corner, List<Stop> stops) {
            return new Gradient(Float.NaN, corner, null, stops);
        }

        /** {@code radial-gradient(...)}: the stops run from the centre out to the ending shape. */
        public static Gradient radial(Radial radial, List<Stop> stops) {
            return new Gradient(Float.NaN, null, radial, stops);
        }

        public List<Stop> stops() {
            return declared;
        }

        /** The gradient for a {@code width x height} box, remade only when the box changes what it is. */
        public CgGradient paint(float width, float height) {
            Resolved r = resolved;
            boolean anyBox = radial == null && corner == null;
            if (r != null && (anyBox || r.width == width && r.height == height)) return r.paint;
            CgGradient shape;
            if (radial != null) {
                float cx = radial.centreX().resolve(width), cy = radial.centreY().resolve(height);
                float[] radii = radii(width, height, cx, cy);
                // A zero radius draws the last colour everywhere, as CSS does; the paint takes only positive radii
                shape = CgGradient.radial(cx / width, cy / height, Math.max(radii[0], 1e-3f) / width,
                        Math.max(radii[1], 1e-3f) / height);
            } else {
                shape = CgGradient.linear(angleFor(width, height));
            }
            CgGradient paint = shape.stops(positions, colours);
            resolved = new Resolved(width, height, paint);
            return paint;
        }

        /** The angle over a box: a declared one is itself; a corner's leaves the other diagonal at 50%. */
        public float angleFor(float width, float height) {
            if (corner == null) return angle;
            float diagonal = (float) Math.toDegrees(Math.atan2(height, width));
            return switch (corner) {
                case TOP_RIGHT -> diagonal;
                case BOTTOM_RIGHT -> 180f - diagonal;
                case BOTTOM_LEFT -> 180f + diagonal;
                case TOP_LEFT -> 360f - diagonal;
            };
        }

        /** The ending shape's radii in pixels: CSS Images 3 §3.5.2. */
        private float[] radii(float width, float height, float cx, float cy) {
            Radial r = Objects.requireNonNull(radial);
            if (r.sizeX() != null) {
                float rx = r.sizeX().resolve(width);
                return new float[]{rx, r.circle() || r.sizeY() == null ? rx : r.sizeY().resolve(height)};
            }
            float left = Math.abs(cx), right = Math.abs(width - cx), top = Math.abs(cy), bottom = Math.abs(height - cy);
            boolean closest = r.extent() == Extent.CLOSEST_SIDE || r.extent() == Extent.CLOSEST_CORNER;
            float sideX = closest ? Math.min(left, right) : Math.max(left, right);
            float sideY = closest ? Math.min(top, bottom) : Math.max(top, bottom);
            boolean toCorner = r.extent() == Extent.CLOSEST_CORNER || r.extent() == Extent.FARTHEST_CORNER;
            if (r.circle()) {
                float radius = toCorner ? (float) Math.hypot(sideX, sideY)
                        : closest ? Math.min(sideX, sideY) : Math.max(sideX, sideY);
                return new float[]{radius, radius};
            }
            if (!toCorner || sideX <= 0f || sideY <= 0f) return new float[]{sideX, sideY};
            // Through (sideX, sideY), keeping the side extent's aspect
            float rx = (float) Math.sqrt(2.0) * sideX;
            return new float[]{rx, rx * sideY / sideX};
        }

        boolean sameShape(Gradient other) {
            return Float.compare(angle, other.angle) == 0 && corner == other.corner
                    && Objects.equals(radial, other.radial) && declared.size() == other.declared.size();
        }

        /** Each stop mixed with {@code other}'s at {@code t}: one ramp sliding into another. */
        Gradient mix(Gradient other, float t) {
            List<Stop> stops = new ArrayList<>(declared.size());
            for (int i = 0; i < declared.size(); i++) {
                stops.add(new Stop(positions[i] + (other.positions[i] - positions[i]) * t,
                        CgColor.lerpArgbPremultiplied(colours[i], other.colours[i], t)));
            }
            return new Gradient(angle, corner, radial, stops);
        }

        /** CSS's rule for missing positions: first 0, last 1, the rest evenly between neighbours; never backwards. */
        private static float[] spread(List<Stop> in) {
            float[] pos = new float[in.size()];
            for (int i = 0; i < pos.length; i++) pos[i] = in.get(i).position();
            if (Float.isNaN(pos[0])) pos[0] = 0f;
            if (Float.isNaN(pos[pos.length - 1])) pos[pos.length - 1] = 1f;
            for (int i = 1; i < pos.length - 1; ) {
                if (!Float.isNaN(pos[i])) {
                    i++;
                    continue;
                }
                int j = i;
                while (Float.isNaN(pos[j])) j++;
                float from = pos[i - 1], to = pos[j];
                for (int k = i; k < j; k++) pos[k] = from + (to - from) * (k - i + 1) / (j - i + 1);
                i = j;
            }
            for (int k = 1; k < pos.length; k++) pos[k] = Math.max(pos[k], pos[k - 1]);
            for (int k = 0; k < pos.length; k++) pos[k] = Math.max(0f, Math.min(1f, pos[k]));
            return pos;
        }

        @Override
        public String css() {
            StringBuilder out = new StringBuilder(radial != null ? "radial-gradient(" : "linear-gradient(");
            if (radial != null) {
                String head = radialCss(radial);
                if (!head.isEmpty()) out.append(head).append(", ");
            } else if (corner != null) {
                out.append("to ").append(corner.name().toLowerCase(Locale.ROOT).replace('_', ' ')).append(", ");
            } else if (angle != 180f) {
                out.append(number(angle)).append("deg, ");
            }
            for (int i = 0; i < declared.size(); i++) {
                if (i > 0) out.append(", ");
                Stop s = declared.get(i);
                out.append(CgColor.toHex(s.argb()));
                if (!Float.isNaN(s.position())) out.append(' ').append(number(s.position() * 100f)).append('%');
            }
            return out.append(')').toString();
        }

        private static String radialCss(Radial r) {
            List<String> words = new ArrayList<>();
            boolean impliedCircle = r.sizeX() != null && r.sizeY() == null;
            if (r.circle() != impliedCircle) words.add(r.circle() ? "circle" : "ellipse");
            if (r.sizeX() != null) {
                words.add(length(r.sizeX()));
                if (r.sizeY() != null) words.add(length(r.sizeY()));
            } else if (r.extent() != Extent.FARTHEST_CORNER) {
                words.add(r.extent().name().toLowerCase(Locale.ROOT).replace('_', '-'));
            }
            if (!r.centreX().equals(Length.CENTRE) || !r.centreY().equals(Length.CENTRE)) {
                words.add("at " + length(r.centreX()) + " " + length(r.centreY()));
            }
            return String.join(" ", words);
        }

        private static String length(Length l) {
            return l.percent() ? number(l.value() * 100f) + "%" : number(l.value()) + "px";
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Gradient g && Float.compare(angle, g.angle) == 0 && corner == g.corner
                    && Objects.equals(radial, g.radial) && declared.equals(g.declared);
        }

        @Override
        public int hashCode() {
            return Objects.hash(angle, corner, radial, declared);
        }

        @Override
        public String toString() {
            return css();
        }
    }

    /** {@code grid(cell, colour[, line width])}: a ruled grid, cells and lines in logical pixels. */
    record Grid(float cellX, float cellY, float lineWidth, int argb) implements CgUiImage {
        @Override
        public String css() {
            String cell = cellX == cellY ? number(cellX) : number(cellX) + " " + number(cellY);
            return "grid(" + cell + ", " + CgColor.toHex(argb) + (lineWidth == 1f ? "" : ", " + number(lineWidth)) + ")";
        }
    }

    /** How a nine-slice's edges and middle fill their span: CSS's {@code border-image-repeat}. */
    enum Repeat {
        STRETCH, REPEAT, ROUND, SPACE;

        public String css() {
            return name().toLowerCase(Locale.ROOT);
        }

        @Nullable
        public static Repeat parse(String keyword) {
            for (Repeat r : values()) if (r.css().equals(keyword)) return r;
            return null;
        }
    }

    /**
     * A texture: {@code image("path", ...)}, or a nine-slice {@code sprite("path", region, slices, ...)}.
     *
     * @param path       the texture's resource path
     * @param x          the region's left, in the reference size's pixels; with {@code w} 0, the whole texture
     * @param y          the region's top
     * @param w          the region's width, 0 for the whole texture
     * @param h          the region's height
     * @param slices     the nine-slice's left, top, right and bottom, null for a plain picture
     * @param repeatX    how a nine-slice's horizontal edges and middle fill their span
     * @param repeatY    the same down
     * @param tint       multiplied into the texture's colours
     * @param refW       the size the region and slices are written against, 0 for the texture's own (a resource pack
     *                   at another resolution keeps the same CSS)
     * @param refH       the same down
     * @param hollow     a nine-slice whose middle is not drawn
     * @param sliceScale the slices drawn at this many times their source size
     */
    record Picture(String path, int x, int y, int w, int h, @Nullable Slices slices, Repeat repeatX, Repeat repeatY,
                   int tint, int refW, int refH, boolean hollow, float sliceScale) implements CgUiImage {

        /** A nine-slice's insets from the region's sides. */
        public record Slices(int left, int top, int right, int bottom) {
        }

        /** {@code image("path")}: the whole texture, stretched. */
        public static Picture of(String path) {
            return new Picture(path, 0, 0, 0, 0, null, Repeat.STRETCH, Repeat.STRETCH, 0xFFFFFFFF, 0, 0, false, 1f);
        }

        public Picture region(int x, int y, int w, int h) {
            return new Picture(path, x, y, w, h, slices, repeatX, repeatY, tint, refW, refH, hollow, sliceScale);
        }

        public Picture slices(int left, int top, int right, int bottom) {
            return new Picture(path, x, y, w, h, new Slices(left, top, right, bottom), repeatX, repeatY, tint, refW, refH,
                    hollow, sliceScale);
        }

        public Picture repeat(Repeat x, Repeat y) {
            return new Picture(path, this.x, this.y, w, h, slices, x, y, tint, refW, refH, hollow, sliceScale);
        }

        public Picture tint(int argb) {
            return new Picture(path, x, y, w, h, slices, repeatX, repeatY, argb, refW, refH, hollow, sliceScale);
        }

        public Picture reference(int width, int height) {
            return new Picture(path, x, y, w, h, slices, repeatX, repeatY, tint, width, height, hollow, sliceScale);
        }

        public Picture hollow(boolean hollow) {
            return new Picture(path, x, y, w, h, slices, repeatX, repeatY, tint, refW, refH, hollow, sliceScale);
        }

        public Picture sliceScale(float scale) {
            return new Picture(path, x, y, w, h, slices, repeatX, repeatY, tint, refW, refH, hollow, scale);
        }

        @Override
        public String css() {
            if (hollow || sliceScale != 1f) {
                throw new IllegalStateException("a picture with a hollow middle or scaled slices was built in Java and"
                        + " has no CSS: " + path);
            }
            StringBuilder out = new StringBuilder(slices != null ? "sprite(" : "image(").append(quote(path));
            if (slices != null || w > 0) out.append(", ").append(quote(x + " " + y + " " + w + " " + h));
            if (slices != null) {
                out.append(", ").append(quote(slices.left() + " " + slices.top() + " " + slices.right() + " "
                        + slices.bottom()));
            }
            if (refW > 0) out.append(", ").append(quote(refW + " " + refH));
            if (slices != null && (repeatX != Repeat.STRETCH || repeatY != Repeat.STRETCH)) {
                out.append(", ").append(quote(repeatX == repeatY ? repeatX.css() : repeatX.css() + " " + repeatY.css()));
            }
            if (slices == null && tint != 0xFFFFFFFF) out.append(", ").append(CgColor.toHex(tint));
            return out.append(')').toString();
        }
    }

    /** {@code asset("sheet", "element")}: a named entry of a sprite sheet ({@code ui/sprites/<sheet>.json}). */
    record Sheet(String sheet, String element) implements CgUiImage {
        @Override
        public String css() {
            return "asset(" + quote(sheet) + ", " + quote(element) + ")";
        }
    }

    /**
     * An SVG: an icon by name ({@code icon("ns:name")}, its light or dark drawing chosen by the theme when drawn), or a
     * file by path. Fitted into its box and centred, never stretched.
     *
     * @param source       the icon's name, or the file's path
     * @param named        whether {@code source} is an icon name, resolved through the theme's variant
     * @param tint         what {@code currentColor} resolves to; white follows the element's {@code color}
     * @param monochrome   every colour in the file drawn in the tint: a silhouette
     * @param strokeHalfWidth a monochrome icon's stroke half-width in device pixels, 0 for the file's own
     * @param variant      a variant forced for this icon alone (an icon on an accented chip), or null for the theme's
     */
    record Icon(String source, boolean named, int tint, boolean monochrome, float strokeHalfWidth,
                @Nullable FileIconTheme.Variant variant) implements CgUiImage {

        public Icon tint(int argb) {
            return new Icon(source, named, argb, monochrome, strokeHalfWidth, variant);
        }

        public Icon monochrome(boolean monochrome) {
            return new Icon(source, named, tint, monochrome, strokeHalfWidth, variant);
        }

        public Icon strokeHalfWidth(float halfWidth) {
            return new Icon(source, named, tint, monochrome, halfWidth, variant);
        }

        public Icon variant(@Nullable FileIconTheme.Variant variant) {
            return new Icon(source, named, tint, monochrome, strokeHalfWidth, variant);
        }

        /** The file to draw now: a named icon's path in the current (or forced) variant. */
        public String path() {
            if (!named) return source;
            return FileIconTheme.toResourcePath(FileIconTheme.withVariant(source,
                    variant != null ? variant : FileIconTheme.getVariant()));
        }

        @Override
        public boolean onePrimitive() {
            return false;
        }

        /** An untinted icon takes the element's colour; a tinted one keeps its own. */
        @Override
        public boolean followsColor() {
            return tint == 0xFFFFFFFF;
        }

        @Override
        public String css() {
            if (!named || strokeHalfWidth != 0f || variant != null) {
                throw new IllegalStateException("an icon by path, with a stroke override or a forced variant was built"
                        + " in Java and has no CSS: " + source);
            }
            return "icon(" + quote(source) + (tint != 0xFFFFFFFF ? ", " + CgColor.toHex(tint) : "")
                    + (monochrome ? ", monochrome" : "") + ")";
        }
    }

    /** {@code shape("name")}: a built-in mark in the element's colour. */
    record Mark(CgUiMark mark) implements CgUiImage {
        @Override
        public boolean followsColor() {
            return true;
        }

        @Override
        public boolean onePrimitive() {
            return mark.name().startsWith("TRIANGLE");
        }

        @Override
        public String css() {
            return "shape(" + quote(mark.cssName()) + ")";
        }
    }

    /** A painter: {@code paint(name)} for one registered by name, or one set from Java (no name, no CSS). */
    record Painted(@Nullable String name, CgUiPainter painter) implements CgUiImage {
        @Override
        public boolean onePrimitive() {
            return painter.onePrimitive();
        }

        @Override
        public String css() {
            if (name == null) {
                throw new IllegalStateException("a " + painter.getClass().getSimpleName()
                        + " painter was set from Java and has no CSS; register it to write it as paint(name)");
            }
            return "paint(" + name + ")";
        }
    }

    /** A CrystalGraphics paint set from Java: filled into the box. Only a solid colour has CSS. */
    record Paint(CgPaint paint) implements CgUiImage {
        @Override
        public String css() {
            if (paint instanceof CgPaint.Solid solid && solid.intensity() == 1f) return CgColor.toHex(solid.argb());
            throw new IllegalStateException("a " + paint.getClass().getSimpleName() + " paint was set from Java and has"
                    + " no CSS");
        }
    }

    /** CSS's {@code cross-fade()}: {@code from} at {@code 1 - t} under {@code to} at {@code t}. */
    record CrossFade(CgUiImage from, CgUiImage to, float t) implements CgUiImage {
        @Override
        public boolean onePrimitive() {
            return false;
        }

        @Override
        public String css() {
            return "cross-fade(" + from.css() + " " + number((1f - t) * 100f) + "%, " + to.css() + ")";
        }
    }

    /** A comma list, the first drawn on top. */
    record Layers(List<CgUiImage> layers) implements CgUiImage {
        public Layers {
            layers = List.copyOf(layers);
        }

        @Override
        public boolean onePrimitive() {
            return false;
        }

        @Override
        public String css() {
            StringBuilder out = new StringBuilder();
            for (CgUiImage layer : layers) {
                if (out.length() > 0) out.append(", ");
                out.append(layer.css());
            }
            return out.toString();
        }
    }

    // ── Writing ───────────────────────────────────────────────────────────────

    /** A number as CSS writes it: no trailing {@code .0}. */
    static String number(float value) {
        if (value == (int) value) return Integer.toString((int) value);
        String s = Float.toString(value);
        return s.contains("E") ? String.format(Locale.ROOT, "%.4f", value) : s;
    }

    static String quote(String s) {
        return '"' + s + '"';
    }
}
