package com.crystalgui.render.texture.svg;

import org.jetbrains.annotations.Nullable;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns scanned tags into a {@link SvgScene} — the resolution half of the pipeline, ported from
 * <b>usvg</b> (Apache-2.0/MIT).
 *
 * <p>This is where every SVG-shaped question is answered and none is passed on: inheritance, {@code use}
 * expansion, shape-to-path conversion, transform flattening, paint-server lookup and viewBox origin. What
 * comes out is geometry and colour. See {@link SvgScene} for the contract that buys.</p>
 *
 * <p>A class rather than a pile of static methods with a dozen parameters: the walk carries an
 * accumulating output, an id index and a gradient table through every level of recursion, and threading
 * those by hand is how a {@code <use>} ends up appending to the wrong list.</p>
 *
 * <h3>Deliberately NOT a general SVG resolver</h3>
 *
 * <p>usvg also resolves clip paths, masks, filters, patterns, text and nested {@code svg} viewports. None
 * of those is implemented here and none is needed to draw an icon — the shipped set of 51 IntelliJ icons
 * and the Feather chrome set use exactly the subset below. The gaps are recorded in {@code ICONS.md}
 * rather than left to be discovered.</p>
 */
final class SvgResolver {

    /** Elements whose content is a definition, not a picture — skipped unless reached through {@code use}. */
    private static final Set<String> DEFINITIONS =
            Set.of("defs", "symbol", "clipPath", "mask", "pattern", "marker", "linearGradient",
                    "radialGradient", "filter", "style", "title", "desc", "metadata");

    private static final Set<String> SHAPES =
            Set.of("path", "rect", "circle", "ellipse", "line", "polyline", "polygon");

    private static final Set<String> CONTAINERS = Set.of("svg", "g", "a", "switch");

    /**
     * How deep {@code <use>} may nest before the walk gives up.
     *
     * <p>A file may reference itself, directly or through a chain, and that must not be how we find out.
     * Eight is far past anything real artwork nests and cheap to carry.</p>
     */
    private static final int MAX_USE_DEPTH = 8;

    private final List<SvgScanner.Tag> tags;
    private final Map<String, Integer> idIndex = new HashMap<>();
    private final Map<String, SvgGradient> servers = new HashMap<>();
    /** The same servers reduced to one colour each — what {@link SvgStyle} needs for strokes. */
    private final Map<String, Integer> gradientColours = new HashMap<>();
    private final List<SvgScene.Node> nodes = new ArrayList<>();

    /** Curve-flattening resolution, carried down to every shape — see {@link SvgPath#parse(String, int)}. */
    private final int steps;

    private SvgResolver(List<SvgScanner.Tag> tags, int steps) {
        this.tags = tags;
        this.steps = steps;
    }

    static SvgScene resolve(List<SvgScanner.Tag> tags, int steps) {
        return new SvgResolver(tags, steps).run();
    }

    private SvgScene run() {
        float boxWidth = 24f, boxHeight = 24f, originX = 0f, originY = 0f;
        for (SvgScanner.Tag tag : tags) {
            if (!tag.name().equals("svg")) continue;
            String box = tag.get("viewBox");
            if (!box.isBlank()) {
                // Scanned, not split. `String.split` compiles its character class on every call, and this
                // runs once per document to read four numbers -- measured at 2.4 ms across the shipped set,
                // more than every rect in it. Same fix, and the same reason, as SvgGeometry#addPoints.
                float[] parts = new float[5];
                int found = 0;
                SvgPath.Cursor cursor = new SvgPath.Cursor(box);
                while (found < parts.length && cursor.hasNumber()) {
                    int before = cursor.position();
                    parts[found] = cursor.number();
                    if (cursor.position() == before) {
                        cursor.skip();
                        continue;
                    }
                    found++;
                }
                // Exactly four, as the split-based version required: a viewBox with more or fewer numbers
                // is malformed and is ignored rather than half-applied.
                if (found == 4) {
                    // min-x and min-y, NOT ignored. A viewBox states an origin as well as a size, and
                    // artwork authored as "-2 -2 28 28" -- which is how a set gives itself padding --
                    // draws offset by exactly that origin otherwise.
                    originX = parts[0];
                    originY = parts[1];
                    boxWidth = parts[2];
                    boxHeight = parts[3];
                }
            } else {
                boxWidth = SvgDocument.number(tag.get("width"), boxWidth);
                boxHeight = SvgDocument.number(tag.get("height"), boxHeight);
            }
            break;
        }

        indexIds();
        collectGradients();

        // The origin lands in the root transform rather than in a fix-up pass over the points, so nothing
        // downstream ever sees coordinates that are not already in the icon's own 0,0 space.
        SvgTransform root = new SvgTransform(1, 0, 0, 1, -originX, -originY);
        resolveRange(0, tags.size(), SvgStyle.ROOT, root, 0);
        return new SvgScene(nodes, boxWidth, boxHeight);
    }

    private void indexIds() {
        for (int i = 0; i < tags.size(); i++) {
            SvgScanner.Tag tag = tags.get(i);
            if (tag.kind() == SvgScanner.Kind.CLOSE) continue;
            String id = tag.get("id");
            if (!id.isEmpty()) idIndex.putIfAbsent(id, i);
        }
    }

    // ── Paint servers ───────────────────────────────────────────────────────────────────────────────

    /**
     * Reads every paint server in the file, keeping the whole ramp and not only a colour.
     *
     * <p>Two passes over the same tags: the first builds each gradient from its own attributes and stops,
     * the second resolves {@code href} inheritance. Bounded iteration rather than recursion — a file can
     * reference itself in a cycle, and that must not be how we find out.</p>
     */
    private void collectGradients() {
        Map<String, String> inherits = new HashMap<>();
        for (int i = 0; i < tags.size(); i++) {
            SvgScanner.Tag tag = tags.get(i);
            if (tag.kind() == SvgScanner.Kind.CLOSE) continue;
            boolean linear = tag.name().equals("linearGradient");
            if (!linear && !tag.name().equals("radialGradient")) continue;
            String id = tag.get("id");
            if (id.isEmpty()) continue;

            String href = tag.has("href") ? tag.get("href") : tag.get("xlink:href");
            if (href.startsWith("#")) inherits.put(id, href.substring(1));

            Map<String, String> attributes = new HashMap<>(tag.attributes());
            // SvgGradient reads the element kind out of the same map as everything else, so that its
            // factory takes one argument instead of a boolean nobody can read at the call site.
            attributes.put("__tag__", tag.name());
            int end = tag.kind() == SvgScanner.Kind.OPEN ? matchingClose(i) : i;
            List<Stop> stops = readStops(i, end);
            float[] offsets = new float[stops.size()];
            int[] colours = new int[stops.size()];
            for (int s = 0; s < stops.size(); s++) {
                offsets[s] = stops.get(s).offset();
                colours[s] = stops.get(s).argb();
            }
            servers.put(id, SvgGradient.of(attributes, offsets, colours, null));
        }
        for (int pass = 0; pass < 4; pass++) {
            for (Map.Entry<String, String> entry : inherits.entrySet()) {
                SvgGradient self = servers.get(entry.getKey());
                SvgGradient source = servers.get(entry.getValue());
                if (self == null || source == null || self.colours().length > 0) continue;
                servers.put(entry.getKey(), new SvgGradient(self.radial(), self.userSpace(),
                        self.transform(), self.x1(), self.y1(), self.x2(), self.y2(),
                        source.offsets(), source.colours(), self.spread()));
            }
        }
        for (Map.Entry<String, SvgGradient> entry : servers.entrySet()) {
            if (entry.getValue().colours().length == 0) continue;
            gradientColours.put(entry.getKey(), entry.getValue().representativeColour());
        }
    }

    /**
     * One gradient stop.
     *
     * <p>A record rather than a {@code float[2]} because the second value is an ARGB int, and a float
     * cannot hold one: 24 bits of mantissa against 32 bits of colour. Opaque colours happen to survive the
     * round trip — {@code 0xFF000000} is exactly {@code -2^24} — and anything with a partial alpha does
     * not, so packing them would work on every stop in the shipped set and quietly shift the colour of the
     * first translucent one anybody adds.</p>
     */
    private record Stop(float offset, int argb) {
    }

    private List<Stop> readStops(int from, int to) {
        List<Stop> out = new ArrayList<>();
        for (int i = from + 1; i <= to && i < tags.size(); i++) {
            SvgScanner.Tag stop = tags.get(i);
            if (!stop.name().equals("stop") || stop.kind() == SvgScanner.Kind.CLOSE) continue;

            // stop-color lives in an attribute OR inside style="", and Illustrator exports use the second
            // exclusively -- reading only the attribute finds no stops at all in a file that is full of
            // them, and every gradient then falls back to grey.
            Map<String, String> declarations = new HashMap<>(stop.attributes());
            declarations.putAll(SvgDocument.styleDeclarations(stop.get("style")));

            Integer colour = SvgColor.parseColor(declarations.get("stop-color"));
            if (colour == null) colour = 0xFF000000;
            String opacity = declarations.get("stop-opacity");
            if (opacity != null) colour = SvgColor.withOpacity(colour, SvgDocument.number(opacity, 1f));

            String rawOffset = declarations.getOrDefault("offset", "0").trim();
            float offset = rawOffset.endsWith("%")
                    ? SvgDocument.number(rawOffset.substring(0, rawOffset.length() - 1), 0f) / 100f
                    : SvgDocument.number(rawOffset, 0f);
            out.add(new Stop(Math.max(0f, Math.min(1f, offset)), colour));
        }
        return out;
    }

    // ── The walk ────────────────────────────────────────────────────────────────────────────────────

    private void resolveRange(int from, int to, SvgStyle style, SvgTransform transform, int depth) {
        int i = from;
        while (i < to) {
            i = resolveNode(i, style, transform, depth, false) + 1;
        }
    }

    /**
     * Resolves one node and everything under it.
     *
     * @param forced the node was reached through {@code <use>}, so a {@code <symbol>} or a member of
     *               {@code <defs>} is now genuinely part of the picture
     * @return the index of the last token this node consumed
     */
    private int resolveNode(int index, SvgStyle style, SvgTransform transform, int depth, boolean forced) {
        SvgScanner.Tag tag = tags.get(index);
        if (tag.kind() == SvgScanner.Kind.CLOSE) return index;

        int end = tag.kind() == SvgScanner.Kind.OPEN ? matchingClose(index) : index;
        String name = tag.name();
        if (DEFINITIONS.contains(name) && !forced) return end;

        SvgStyle childStyle = style.inherit(tag.attributes(), gradientColours);
        SvgTransform childTransform = SvgTransform.parse(tag.get("transform")).then(transform);

        if (name.equals("use")) {
            resolveUse(tag, childStyle, childTransform, depth);
            return end;
        }
        if (SHAPES.contains(name)) {
            resolveShape(tag, childStyle, childTransform);
            return end;
        }
        if (tag.kind() == SvgScanner.Kind.OPEN
                && (CONTAINERS.contains(name) || forced || !DEFINITIONS.contains(name))) {
            // <symbol> and <defs> reached through <use> descend as ordinary groups, and so does any
            // element we do not recognise -- an unknown container that swallowed its children would lose
            // whole regions of a file for a tag we simply had not heard of.
            resolveRange(index + 1, end, childStyle, childTransform, depth);
        }
        return end;
    }

    private void resolveUse(SvgScanner.Tag tag, SvgStyle style, SvgTransform transform, int depth) {
        if (depth >= MAX_USE_DEPTH) return;
        String href = tag.has("href") ? tag.get("href") : tag.get("xlink:href");
        if (!href.startsWith("#")) return;
        Integer target = idIndex.get(href.substring(1));
        if (target == null) return;

        // x/y on a <use> are a translation, applied INSIDE its own transform -- a sprite sheet that places
        // twenty copies of one symbol depends on it, and dropping it stacks all twenty.
        float x = SvgDocument.number(tag.get("x"), 0f);
        float y = SvgDocument.number(tag.get("y"), 0f);
        SvgTransform placed = (x != 0f || y != 0f)
                ? new SvgTransform(1, 0, 0, 1, x, y).then(transform)
                : transform;
        resolveNode(target, style, placed, depth + 1, true);
    }

    /**
     * One shape becomes one {@link SvgScene.Node}.
     *
     * <p><b>Paint is resolved before the points move, geometry after.</b> An {@code objectBoundingBox}
     * gradient resolves against the shape's box in the space the gradient was <em>stated</em> in — which
     * is the space in effect where it is referenced, before this element's own transform — so the box has
     * to be measured while the contours are still local. The transform then survives on the paint (see
     * {@link SvgScene.Gradient}) and is flattened into the contours, which is exactly usvg's split.</p>
     */
    private void resolveShape(SvgScanner.Tag tag, SvgStyle style, SvgTransform transform) {
        if (!style.fills() && !style.strokes()) return;
        List<SvgPath.Polyline> contours = SvgGeometry.of(tag, steps);
        if (contours.isEmpty()) return;

        SvgScene.Fill fill = style.fills() ? resolveFill(contours, style, transform) : null;

        for (SvgPath.Polyline contour : contours) {
            for (float[] point : contour.points()) {
                float px = transform.applyX(point[0], point[1]);
                float py = transform.applyY(point[0], point[1]);
                point[0] = px;
                point[1] = py;
            }
        }

        SvgScene.Stroke stroke = null;
        if (style.strokes()) {
            stroke = new SvgScene.Stroke(
                    new SvgScene.Solid(style.strokeArgb(), style.stroke().currentColor()),
                    style.strokeHalfWidth() * transform.lengthScale(), style.cap());
        }
        if (fill == null && stroke == null) return;
        nodes.add(new SvgScene.Node(contours, fill, stroke));
    }

    @Nullable
    private SvgScene.Fill resolveFill(List<SvgPath.Polyline> contours, SvgStyle style,
                                      SvgTransform transform) {
        SvgGradient gradient = style.fill().reference() == null
                ? null : servers.get(style.fill().reference());
        // A one-stop ramp is not a gradient, and treating it as one costs a subdivided mesh to paint a
        // shape one colour. Falls through to the solid path, which is what the style already resolved.
        if (gradient != null && gradient.colours().length < 2) gradient = null;

        float alpha = style.fillOpacity() * style.opacity();
        if (gradient == null) {
            return new SvgScene.Fill(
                    new SvgScene.Solid(style.fillArgb(), style.fill().currentColor()), style.evenOdd());
        }
        return new SvgScene.Fill(
                new SvgScene.Gradient(gradient.resolvedAgainst(SvgGeometry.boundsOf(contours)),
                        transform, alpha, style.fillArgb(), style.fill().currentColor()),
                style.evenOdd());
    }

    // ── Tokens ──────────────────────────────────────────────────────────────────────────────────────

    /**
     * The index of the {@code </name>} closing the tag at {@code open}.
     *
     * <p>Matched <b>by name</b>, not by a bare depth counter: real files carry stray closing tags and
     * unclosed ones, and a counter walks straight past the true close of a group into its siblings — which
     * silently reparents them and applies the wrong transform to the rest of the file.</p>
     */
    private int matchingClose(int open) {
        String name = tags.get(open).name();
        int depth = 0;
        for (int i = open + 1; i < tags.size(); i++) {
            SvgScanner.Tag tag = tags.get(i);
            if (!tag.name().equals(name)) continue;
            if (tag.kind() == SvgScanner.Kind.OPEN) depth++;
            else if (tag.kind() == SvgScanner.Kind.CLOSE) {
                if (depth == 0) return i;
                depth--;
            }
        }
        return tags.size() - 1;
    }
}
