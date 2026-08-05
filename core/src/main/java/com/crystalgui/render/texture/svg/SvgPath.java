package com.crystalgui.render.texture.svg;

import java.util.ArrayList;
import java.util.List;

/**
 * An SVG {@code <path d="…">} flattened into polylines.
 *
 * <h3>Scope: path data, and nothing else</h3>
 *
 * <p>The {@code d} grammar — {@code M L H V C S Q T A Z}, absolute and relative — and no strokes,
 * gradients, transforms, groups or text. That is the whole of what an icon needs: every vector editor
 * exports artwork as filled paths, and the rest of SVG is a document format we are not implementing.</p>
 *
 * <h3>Flattened, not kept as curves</h3>
 *
 * <p>This produces points. Keeping the curves would matter for a distance-field bake, where segment type
 * is the input msdfgen wants — see {@code ICONS.md}. For getting a shape on screen through
 * {@code ctx.curve()}, which strokes straight segments, points are the useful form and flattening here
 * means the consumer needs no curve maths at all.</p>
 *
 * <p>Fixed subdivision rather than adaptive: an icon is a few dozen segments and the difference is
 * invisible. Adaptive flattening is worth it when the curve count is unbounded, which is exactly the case
 * this is not.</p>
 */
public final class SvgPath {

    /** Points per curve segment. 16 is smooth at any icon size and trivial to draw. */
    private static final int STEPS = 16;

    private SvgPath() {
    }

    /** One closed or open run of points, in the path's own coordinate space. */
    public record Polyline(List<float[]> points, boolean closed) {
    }

    public static List<Polyline> parse(String d) {
        List<Polyline> out = new ArrayList<>();
        if (d == null || d.isBlank()) return out;

        Cursor cursor = new Cursor(d);
        List<float[]> current = new ArrayList<>();
        float x = 0, y = 0, startX = 0, startY = 0;
        // The reflected control point for S/T, per the spec: the previous one mirrored about the
        // current point, or the current point itself when the previous command was not a curve.
        float lastCx = 0, lastCy = 0;
        char previous = ' ';
        char command = ' ';

        while (cursor.hasMore()) {
            char next = cursor.peekCommand();
            if (next != 0) {
                command = cursor.readCommand();
            } else if (command == 'M') {
                command = 'L';          // implicit lineto after a moveto, per the spec
            } else if (command == 'm') {
                command = 'l';
            }
            boolean relative = Character.isLowerCase(command);
            char op = Character.toUpperCase(command);

            switch (op) {
                case 'M': {
                    float nx = cursor.number(), ny = cursor.number();
                    x = relative ? x + nx : nx;
                    y = relative ? y + ny : ny;
                    if (current.size() > 1) out.add(new Polyline(current, false));
                    current = new ArrayList<>();
                    current.add(new float[]{x, y});
                    startX = x;
                    startY = y;
                    break;
                }
                case 'L': {
                    float nx = cursor.number(), ny = cursor.number();
                    x = relative ? x + nx : nx;
                    y = relative ? y + ny : ny;
                    current.add(new float[]{x, y});
                    break;
                }
                case 'H': {
                    float nx = cursor.number();
                    x = relative ? x + nx : nx;
                    current.add(new float[]{x, y});
                    break;
                }
                case 'V': {
                    float ny = cursor.number();
                    y = relative ? y + ny : ny;
                    current.add(new float[]{x, y});
                    break;
                }
                case 'C': case 'S': {
                    float c1x, c1y;
                    if (op == 'C') {
                        c1x = value(cursor.number(), x, relative);
                        c1y = value(cursor.number(), y, relative);
                    } else {
                        boolean smooth = previous == 'C' || previous == 'S';
                        c1x = smooth ? 2 * x - lastCx : x;
                        c1y = smooth ? 2 * y - lastCy : y;
                    }
                    float c2x = value(cursor.number(), x, relative);
                    float c2y = value(cursor.number(), y, relative);
                    float ex = value(cursor.number(), x, relative);
                    float ey = value(cursor.number(), y, relative);
                    cubic(current, x, y, c1x, c1y, c2x, c2y, ex, ey);
                    lastCx = c2x;
                    lastCy = c2y;
                    x = ex;
                    y = ey;
                    break;
                }
                case 'Q': case 'T': {
                    float cx, cy;
                    if (op == 'Q') {
                        cx = value(cursor.number(), x, relative);
                        cy = value(cursor.number(), y, relative);
                    } else {
                        boolean smooth = previous == 'Q' || previous == 'T';
                        cx = smooth ? 2 * x - lastCx : x;
                        cy = smooth ? 2 * y - lastCy : y;
                    }
                    float ex = value(cursor.number(), x, relative);
                    float ey = value(cursor.number(), y, relative);
                    // A quadratic IS a cubic with both controls at 2/3 of the way to the single one.
                    cubic(current, x, y, x + 2f / 3f * (cx - x), y + 2f / 3f * (cy - y),
                            ex + 2f / 3f * (cx - ex), ey + 2f / 3f * (cy - ey), ex, ey);
                    lastCx = cx;
                    lastCy = cy;
                    x = ex;
                    y = ey;
                    break;
                }
                case 'A': {
                    float rx = cursor.number(), ry = cursor.number();
                    float rotation = cursor.number();
                    boolean largeArc = cursor.number() != 0f;
                    boolean sweep = cursor.number() != 0f;
                    float ex = value(cursor.number(), x, relative);
                    float ey = value(cursor.number(), y, relative);
                    arc(current, x, y, rx, ry, rotation, largeArc, sweep, ex, ey);
                    x = ex;
                    y = ey;
                    break;
                }
                case 'Z': {
                    if (!current.isEmpty()) {
                        out.add(new Polyline(current, true));
                        current = new ArrayList<>();
                        current.add(new float[]{startX, startY});
                    }
                    x = startX;
                    y = startY;
                    break;
                }
                default:
                    // An unknown command would otherwise spin forever on the same character.
                    cursor.skip();
            }
            previous = op;
        }
        if (current.size() > 1) out.add(new Polyline(current, false));
        return out;
    }

    private static float value(float raw, float origin, boolean relative) {
        return relative ? origin + raw : raw;
    }

    private static void cubic(List<float[]> out, float x0, float y0, float x1, float y1,
                              float x2, float y2, float x3, float y3) {
        for (int i = 1; i <= STEPS; i++) {
            float t = (float) i / STEPS;
            float u = 1 - t;
            float a = u * u * u, b = 3 * u * u * t, c = 3 * u * t * t, e = t * t * t;
            out.add(new float[]{a * x0 + b * x1 + c * x2 + e * x3,
                    a * y0 + b * y1 + c * y2 + e * y3});
        }
    }

    /**
     * An elliptical arc, sampled.
     *
     * <p>SVG states arcs by their <b>endpoint</b> — where to finish, which radii, and two flags choosing
     * between the four arcs that fit — so drawing one means converting to the centre parametrisation
     * first. That conversion is the appendix-F.6 formula from the SVG spec, and it is why arcs are the one
     * command worth calling out: every other command's data is already in the form it is drawn from.</p>
     *
     * <p>They are not optional in practice. Feather, Lucide and Material all round their corners with
     * {@code a}, so a parser without this handles almost no real icon.</p>
     */
    private static void arc(List<float[]> out, float x0, float y0, float rx, float ry,
                            float rotationDeg, boolean largeArc, boolean sweep, float x1, float y1) {
        if (rx == 0 || ry == 0) {
            out.add(new float[]{x1, y1});
            return;
        }
        rx = Math.abs(rx);
        ry = Math.abs(ry);
        double phi = Math.toRadians(rotationDeg);
        double cos = Math.cos(phi), sin = Math.sin(phi);

        double dx2 = (x0 - x1) / 2.0, dy2 = (y0 - y1) / 2.0;
        double x1p = cos * dx2 + sin * dy2;
        double y1p = -sin * dx2 + cos * dy2;

        // Radii too small to reach the endpoint are scaled up, per the spec, rather than refused.
        double lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
        if (lambda > 1) {
            double scale = Math.sqrt(lambda);
            rx *= (float) scale;
            ry *= (float) scale;
        }

        double sign = largeArc == sweep ? -1 : 1;
        double numerator = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p;
        double denominator = rx * rx * y1p * y1p + ry * ry * x1p * x1p;
        double coefficient = sign * Math.sqrt(Math.max(0, numerator / denominator));
        double cxp = coefficient * rx * y1p / ry;
        double cyp = -coefficient * ry * x1p / rx;

        double cx = cos * cxp - sin * cyp + (x0 + x1) / 2.0;
        double cy = sin * cxp + cos * cyp + (y0 + y1) / 2.0;

        double startAngle = angle(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry);
        double sweepAngle = angle((x1p - cxp) / rx, (y1p - cyp) / ry,
                (-x1p - cxp) / rx, (-y1p - cyp) / ry);
        if (!sweep && sweepAngle > 0) sweepAngle -= 2 * Math.PI;
        if (sweep && sweepAngle < 0) sweepAngle += 2 * Math.PI;

        for (int i = 1; i <= STEPS; i++) {
            double t = startAngle + sweepAngle * i / STEPS;
            double px = cos * rx * Math.cos(t) - sin * ry * Math.sin(t) + cx;
            double py = sin * rx * Math.cos(t) + cos * ry * Math.sin(t) + cy;
            out.add(new float[]{(float) px, (float) py});
        }
    }

    private static double angle(double ux, double uy, double vx, double vy) {
        double dot = ux * vx + uy * vy;
        double len = Math.sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy));
        double a = Math.acos(Math.max(-1, Math.min(1, dot / len)));
        return ux * vy - uy * vx < 0 ? -a : a;
    }

    /** Walks the {@code d} string. Commas and whitespace are both separators, and both are optional. */
    private static final class Cursor {
        private final String text;
        private int at;

        Cursor(String text) {
            this.text = text;
        }

        boolean hasMore() {
            skipSeparators();
            return at < text.length();
        }

        void skip() {
            at++;
        }

        private void skipSeparators() {
            while (at < text.length()) {
                char c = text.charAt(at);
                if (c == ',' || Character.isWhitespace(c)) at++;
                else break;
            }
        }

        /** The command letter here, or 0 when the next token is a number — an implicit repeat. */
        char peekCommand() {
            skipSeparators();
            if (at >= text.length()) return 0;
            char c = text.charAt(at);
            return Character.isLetter(c) ? c : 0;
        }

        char readCommand() {
            skipSeparators();
            return text.charAt(at++);
        }

        float number() {
            skipSeparators();
            int start = at;
            if (at < text.length() && (text.charAt(at) == '-' || text.charAt(at) == '+')) at++;
            while (at < text.length()) {
                char c = text.charAt(at);
                if (Character.isDigit(c) || c == '.') {
                    at++;
                } else if ((c == 'e' || c == 'E')) {
                    at++;
                    if (at < text.length() && (text.charAt(at) == '-' || text.charAt(at) == '+')) at++;
                } else {
                    break;
                }
            }
            if (start == at) return 0f;
            try {
                return Float.parseFloat(text.substring(start, at));
            } catch (NumberFormatException malformed) {
                return 0f;
            }
        }
    }
}
