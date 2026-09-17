package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.crystalgui.style.CssComments;
import com.crystalgui.style.CssParsingUtil;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;

/**
 * Reading and writing the composite values a lab edits: the layers of a declaration, and one number in a
 * function's arguments.
 *
 * <pre>{@code
 * List<String> layers = CssValues.layers("0 1px 2px #000, 0 0 8px #4cf");   // two shadows
 * String written = CssValues.join(layers);                                  // back, in order
 * float blur = CssValues.number(shadow, 2, 0f);                             // the third term
 * }</pre>
 *
 * <p><b>Top-level only.</b> A comma inside {@code rgba(0, 0, 0, .5)} does not divide two shadows, and the
 * whole reason the labs can be written against text is that splitting is done once, here, the same way the
 * engine's own parser does it.</p>
 */
public final class CssValues {

    /** The units {@link #readable} recognises after a number; the longest first, so `turn` beats nothing. */
    private static final String[] UNITS = {"turn", "grad", "rad", "deg", "px", "em", "%"};

    private CssValues() {
    }

    /** A single function call longer than this opens onto an argument a line. */
    private static final int BREAK_CALL_AT = 40;

    /** One line of a value as it is read, and whether it continues a function opened on the line above. */
    public record Line(String text, boolean continued) {
    }

    /**
     * A value broken into the lines it reads in, as DevTools and Prettier break CSS: never wrapped further, so the widest
     * line is how narrow a column showing it may be.
     *
     * <pre>{@code
     * CssValues.lines("#000 0 1px 2px, #F00 0 0 4px");      // "#000 0 1px 2px,"  "#F00 0 0 4px"
     * CssValues.lines("translate(1px, 2px) scale(2)");      // "translate(1px, 2px)"  "scale(2)"
     * CssValues.lines("linear-gradient(90deg, #F00, #0F0 40%, #00F)");
     *     // "linear-gradient("  then "90deg,"  "#F00,"  "#0F0 40%,"  "#00F)", each continued
     * }</pre>
     *
     * <ul>
     *   <li><b>A comma list</b> is a layer a line, each but the last ending in its comma.</li>
     *   <li><b>A run of functions</b> — {@code transform}, {@code backdrop-filter} — is a function a line.</li>
     *   <li><b>One long call</b> opens on its name alone, then an argument a line, closing on the last: the name and its
     *       first argument together were the widest line, and so the narrowest the column could go.</li>
     * </ul>
     *
     * <p>A switched-off layer or function reads as the comment it is kept in. Every text is {@link #readable}.</p>
     */
    public static List<Line> lines(@Nullable String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> layers = layerStack(value);
        if (layers.size() >= 2) {
            List<Line> out = new ArrayList<>(layers.size());
            for (int i = 0; i < layers.size(); i++) {
                out.add(new Line(shownEntry(layers.get(i)) + (i < layers.size() - 1 ? "," : ""), false));
            }
            return out;
        }
        List<String> functions = functionStack(value);
        if (functions.size() >= 2 && allCalls(functions)) {
            List<Line> out = new ArrayList<>(functions.size());
            for (String function : functions) out.add(new Line(shownEntry(function), false));
            return out;
        }
        String readable = readable(value);
        List<String> arguments = readable.length() > BREAK_CALL_AT ? callArguments(readable) : List.of();
        if (arguments.size() < 2) return List.of(new Line(readable, false));
        String name = readable.substring(0, readable.indexOf('(') + 1);
        List<Line> out = new ArrayList<>(arguments.size() + 1);
        out.add(new Line(name, false));
        for (int i = 0; i < arguments.size(); i++) {
            out.add(new Line(arguments.get(i) + (i < arguments.size() - 1 ? "," : ")"), true));
        }
        return out;
    }

    private static String shownEntry(String entry) {
        String body = readable(bodyOf(entry));
        return isOff(entry) ? "/* " + body + " */" : body;
    }

    /** Whether every entry is a call: {@code rgba(0, 0, 0, .5) 0 1px} is a shadow, not two functions. */
    private static boolean allCalls(List<String> entries) {
        for (String entry : entries) {
            String body = bodyOf(entry);
            if (body.indexOf('(') <= 0 || !body.endsWith(")")) return false;
        }
        return true;
    }

    /** The top-level arguments of {@code text} when it is exactly one call, else empty. */
    private static List<String> callArguments(String text) {
        int open = text.indexOf('(');
        if (open <= 0 || !text.endsWith(")")) return List.of();
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') depth++;
            if (c == ')' && --depth == 0 && i != text.length() - 1) return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : CssParsingUtil.splitTopLevelCommas(text.substring(open + 1, text.length() - 1))) {
            if (!part.isBlank()) out.add(part.trim());
        }
        return out;
    }

    /** A declaration's live layers: what {@code background}, {@code text-shadow} and {@code overlay} hold. */
    public static List<String> layers(@Nullable String css) {
        if (css != null) css = CssComments.strip(css);
        if (css == null || css.isBlank() || css.trim().equalsIgnoreCase("none")) return List.of();
        List<String> parts = new ArrayList<>();
        for (String part : CssParsingUtil.splitTopLevelCommas(css.trim())) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) parts.add(trimmed);
        }
        return parts;
    }

    /**
     * Every layer of a comma list, a switched-off one as the comment it is kept in — what a layer stack edits.
     *
     * <pre>{@code
     * CssValues.layerStack("#000 0 1px 2px /* , #F00 0 0 4px *}{@code /");   // [#000 0 1px 2px, /* #F00 0 0 4px *}{@code /]
     * CssValues.joinLayerStack(layers);                                       // back, with the comma in the comment
     * }</pre>
     *
     * <p>A switched-off layer is a comment in the declaration, as a switched-off declaration is one in the rule: the
     * sheet, or an element's inline style, keeps it and the cascade never sees it.</p>
     */
    public static List<String> layerStack(@Nullable String css) {
        return stack(css, true);
    }

    /** {@link #layerStack} back into a declaration: {@code none} ahead of a comment when every layer is off. */
    public static String joinLayerStack(List<String> layers) {
        List<String> leading = new ArrayList<>();
        StringBuilder out = new StringBuilder();
        boolean wroteOn = false;
        for (String layer : layers) {
            // COMMAS INSIDE THE COMMENT, so what is left when the sheet strips it is still a list.
            if (isOff(layer)) {
                if (wroteOn) out.append(" /* , ").append(bodyOf(layer)).append(" */");
                else leading.add(bodyOf(layer));
                continue;
            }
            if (wroteOn) out.append(", ");
            else if (!leading.isEmpty()) out.append("/* ").append(String.join(", ", leading)).append(", */ ");
            out.append(layer);
            wroteOn = true;
        }
        if (wroteOn) return out.toString();
        return leading.isEmpty() ? "none" : "none /* " + String.join(", ", leading) + " */";
    }

    /** {@link #layerStack} for a {@code transform}: its functions, a switched-off one in a comment. */
    public static List<String> functionStack(@Nullable String css) {
        return stack(css, false);
    }

    /** {@link #functionStack} back into a {@code transform}. */
    public static String joinFunctionStack(List<String> functions) {
        List<String> on = new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (String function : functions) {
            out.add(function);
            if (!isOff(function)) on.add(function);
        }
        if (functions.isEmpty()) return "none";
        if (on.isEmpty()) {
            List<String> bodies = new ArrayList<>();
            for (String function : functions) bodies.add(bodyOf(function));
            return "none /* " + String.join(" ", bodies) + " */";
        }
        return String.join(" ", out);
    }

    /**
     * Whether a stack entry is switched off — <b>one comment and nothing else</b>.
     *
     * <p>A value that merely BEGINS and ENDS with a comment is a stack whose first and last entries are both off,
     * and reading that as one switched-off thing struck the outer markers and stranded the two inside it:
     * {@code /*}{@code  a *}{@code / b /}{@code * c *}{@code /} came back as {@code a *}{@code / b /}{@code * c}.</p>
     */
    public static boolean isOff(String layer) {
        String trimmed = layer.trim();
        return trimmed.startsWith("/*") && trimmed.endsWith("*/") && trimmed.indexOf("*/") == trimmed.length() - 2;
    }

    /** A stack entry without its switch: the layer itself. */
    public static String bodyOf(String layer) {
        if (!isOff(layer)) return layer.trim();
        String trimmed = layer.trim();
        return trimmed.substring(2, trimmed.length() - 2).trim();
    }

    /** {@code layer} switched on or off. */
    public static String switched(String layer, boolean on) {
        return on ? bodyOf(layer) : "/* " + bodyOf(layer) + " */";
    }

    /** {@code body} in place of what {@code layer} held, keeping whether it is on. */
    public static String withBody(String layer, String body) {
        return switched(body, !isOff(layer));
    }

    private static List<String> stack(@Nullable String css, boolean commas) {
        if (css == null || css.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        String text = css.trim();
        int at = 0;
        while (at < text.length()) {
            int open = text.indexOf("/*", at);
            int close = open < 0 ? -1 : text.indexOf("*/", open + 2);
            String live = text.substring(at, open < 0 || close < 0 ? text.length() : open);
            for (String layer : commas ? layers(live) : functions(live)) out.add(layer);
            if (open < 0 || close < 0) break;
            String kept = text.substring(open + 2, close);
            for (String layer : commas ? layers(kept) : functions(kept)) out.add("/* " + layer + " */");
            at = close + 2;
        }
        return out;
    }

    /** Layers back into a declaration, first on top — {@code none} for an empty stack. */
    public static String join(List<String> layers) {
        return layers.isEmpty() ? "none" : String.join(", ", layers);
    }

    /**
     * The functions of a {@code transform}, in order: {@code translate(…) rotate(…)} is two.
     *
     * <p>Space-separated rather than comma-separated, and a function's own arguments hold both — so this
     * scans for the bracket depth rather than splitting on anything.</p>
     */
    public static List<String> functions(@Nullable String css) {
        if (css != null) css = CssComments.strip(css);
        if (css == null || css.isBlank() || css.trim().equalsIgnoreCase("none")) return List.of();
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (char c : css.trim().toCharArray()) {
            if (c == '(') depth++;
            if (c == ')') depth--;
            if (depth == 0 && (c == ' ' || c == ',') && current.length() > 0 && current.charAt(current.length() - 1) == ')') {
                out.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (current.toString().trim().length() > 0) out.add(current.toString().trim());
        return out;
    }

    /** Functions back into a {@code transform}, in order. */
    public static String joinFunctions(List<String> functions) {
        return functions.isEmpty() ? "none" : String.join(" ", functions);
    }

    /** {@code rotate} out of {@code rotate(14deg)}, or "" when it is not a function at all. */
    public static String functionName(String function) {
        int open = function.indexOf('(');
        return open <= 0 ? "" : function.substring(0, open).trim().toLowerCase(Locale.ROOT);
    }

    /** What is between the brackets, or "" — the arguments a lab edits. */
    public static String arguments(String function) {
        int open = function.indexOf('(');
        int close = function.lastIndexOf(')');
        return open < 0 || close < open ? "" : function.substring(open + 1, close).trim();
    }

    /** {@code name(a, b)} from its parts. */
    public static String function(String name, String... arguments) {
        return name + "(" + String.join(", ", arguments) + ")";
    }

    /** The whitespace-separated terms of a value: {@code 0 1px 2px #000} is four. */
    public static List<String> terms(String value) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (char c : value.trim().toCharArray()) {
            if (c == '(') depth++;
            if (c == ')') depth--;
            if (depth == 0 && Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    out.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (current.length() > 0) out.add(current.toString());
        return out;
    }

    /** Term {@code index} as a number, ignoring its unit; {@code fallback} when it is not one. */
    public static float number(List<String> terms, int index, float fallback) {
        if (index < 0 || index >= terms.size()) return fallback;
        return number(terms.get(index), fallback);
    }

    /** {@code 12px} as 12, {@code 0.25turn} as 0.25 — the unit is the caller's business. */
    public static float number(@Nullable String term, float fallback) {
        if (term == null) return fallback;
        int end = 0;
        while (end < term.length() && (Character.isDigit(term.charAt(end)) || term.charAt(end) == '.'
                || term.charAt(end) == '-' || term.charAt(end) == '+')) {
            end++;
        }
        try {
            return end == 0 ? fallback : Float.parseFloat(term.substring(0, end));
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    /** A number as CSS: {@code 12} rather than {@code 12.0}, so what is written is what a person types. */
    public static String write(double value) {
        double rounded = Math.round(value * 1000d) / 1000d;
        return rounded == Math.rint(rounded) ? String.valueOf((long) rounded) : String.valueOf(rounded);
    }

    /** A pixel length, the unit every lab's pads and dials work in. */
    public static String px(double value) {
        return write(value) + "px";
    }

    /**
     * A number as {@code property} itself will accept it: {@code 12px} where the property takes a length,
     * and a bare {@code 12} where it does not.
     *
     * <pre>{@code
     * CssValues.length(StylePropertyRegistry.FONT_SIZE, 34);          // 34   -- a plain float property
     * CssValues.length(StylePropertyRegistry.TEXT_STROKE_WIDTH, 3);   // 3px  -- a LengthPercent
     * }</pre>
     *
     * <p><b>Ask, never assume.</b> {@code font-size} is a plain float in this engine and its parser refuses
     * {@code 34px} outright — so a lab that wrote pixels at it wrote nothing at all, and its slider snapped
     * back to the value that was still there.</p>
     */
    public static String length(@Nullable StyleProperty<?> property, double value) {
        String px = px(value);
        return property == null || DeclarationEditors.parses(property, px) ? px : write(value);
    }

    /**
     * A value as a person reads it, for a chip or a caption — <b>never</b> for what is written.
     *
     * <pre>{@code
     * CssValues.readable("rotate(-0.0022845864rad)");   // rotate(-0.13deg)
     * CssValues.readable("26.0px");                     // 26px
     * }</pre>
     *
     * <p>An angle in radians is what the canvas's own gestures write and what the engine stores; nobody
     * reads one. This only changes how it is shown — the declaration keeps whatever spelling the file has,
     * because rewriting a value to display it is how an editor quietly reformats somebody's sheet.</p>
     */
    public static String readable(@Nullable String value) {
        if (value == null || value.isBlank()) return "";
        StringBuilder out = new StringBuilder();
        int at = 0;
        while (at < value.length()) {
            char c = value.charAt(at);
            if (c == '#') {
                // A HEX COLOR IS NOT A NUMBER, and scanning one as a run of digits mangles it: #00000000
                // came out as #0 and #00FF00 as #0FF0, so every color with a zero component was wrong.
                int hex = at + 1;
                while (hex < value.length() && isHex(value.charAt(hex))) hex++;
                // AN OPAQUE ALPHA SAYS NOTHING: #CF0600FF reads as #CF0600.
                boolean opaque = hex - at == 9 && value.regionMatches(true, hex - 2, "FF", 0, 2);
                out.append(value, at, opaque ? hex - 2 : hex);
                at = hex;
                continue;
            }
            // NOT A NUMBER INSIDE A WORD: `img2.png` read the 2 and its dot as a length and came out `img2png`.
            char before = at == 0 ? ' ' : value.charAt(at - 1);
            boolean inWord = Character.isLetterOrDigit(before) || before == '_' || before == '.';
            boolean starts = !inWord && (Character.isDigit(c)
                    || ((c == '-' || c == '+' || c == '.') && at + 1 < value.length()
                        && Character.isDigit(value.charAt(at + 1))));
            if (!starts) {
                out.append(c);
                at++;
                continue;
            }
            int end = at + 1;
            while (end < value.length() && (Character.isDigit(value.charAt(end)) || value.charAt(end) == '.')) end++;
            float amount = number(value.substring(at, end), 0f);
            String unit = unitAt(value, end);
            at = end + unit.length();
            if (unit.equals("rad")) {
                // NOBODY READS RADIANS, and the canvas's own rotate gesture writes them.
                out.append(write(Math.round(Math.toDegrees(amount) * 100) / 100d)).append("deg");
            } else if (unit.equals("px") || unit.equals("em")) {
                // A TENTH OF A PIXEL is finer than anyone reads; a drag writes hundredths.
                out.append(write(Math.round(amount * 10d) / 10d)).append(unit);
            } else {
                out.append(write(amount)).append(unit);
            }
        }
        return out.toString();
    }

    private static boolean isHex(char c) {
        return Character.isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /**
     * A dragged number, at as much precision as a stylesheet should carry.
     *
     * <pre>{@code
     * CssValues.dragged(71.771236);   // 71.77
     * }</pre>
     *
     * <p>A slider hands back whatever the pointer landed on, and written straight out that is
     * {@code font-size: 71.771236px} in somebody's file. NOT rounded to whole numbers: a half-pixel
     * stroke and a 1.5px blur are real values a lab exists to find, and an integer step cannot reach
     * them. Applied where a gesture AUTHORS a value, never in {@link #px} — a preview scaled for a
     * 28x16 chip is arithmetic, not authorship, and rounding it would skew the shape it is showing.</p>
     */
    public static double dragged(double value) {
        return Math.round(value * 100d) / 100d;
    }

    /** The unit starting at {@code at}, or "" for a bare number. */
    private static String unitAt(String value, int at) {
        for (String unit : UNITS) {
            if (value.startsWith(unit, at)) return unit;
        }
        return "";
    }

    /** {@code #RRGGBB}, or {@code #RRGGBBAA} when there is transparency to state — the color writer's own spelling. */
    public static String color(int argb) {
        return StylePropertyRegistry.COLOR.write(argb);
    }
}
