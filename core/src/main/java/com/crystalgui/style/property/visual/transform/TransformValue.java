package com.crystalgui.style.property.visual.transform;

import com.crystalgui.style.CssAngle;
import com.crystalgui.style.CssParsingUtil;
import com.crystalgui.style.property.StyleValue;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.ui.UITransform;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses CSS's {@code transform} function list into a {@link UITransform}.
 *
 * <pre>
 *   transform: none                                   -> IDENTITY
 *   transform: translate(10px, 5px)                   -> one TRANSLATE
 *   transform: translateX(50%)                        -> TRANSLATE(50%, 0) — percentages are of the
 *                                                        element's own box, as in CSS
 *   transform: scale(2)                               -> SCALE(2, 2) — one argument means both axes
 *   transform: rotate(45deg) scale(0.5)               -> rotate FIRST, then scale in the rotated space
 * </pre>
 *
 * <p>Order is preserved and is meaningful: {@code translate(10px) scale(2)} is not
 * {@code scale(2) translate(10px)}. See {@link UITransform} for why the value type is a list.</p>
 *
 * <p>Anything unrecognised — an unknown function, a missing unit on an angle, a wrong argument count —
 * yields {@code null}, which {@link StyleValue#compute()} logs and treats as an unset declaration. That
 * is deliberately all-or-nothing: silently dropping one bad function out of a chain would produce a
 * transform the author never wrote.</p>
 */
public class TransformValue extends StyleValue<UITransform> {

    public TransformValue(String rawValue) {
        super(rawValue);
    }

    @Override
    protected @Nullable UITransform doCompute(String rawValue) {
        return parse(rawValue);
    }

    static @Nullable UITransform parse(String rawValue) {
        if (rawValue == null) return null;
        String trimmed = rawValue.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.equalsIgnoreCase("none")) return UITransform.IDENTITY;

        List<UITransform.Op> ops = new ArrayList<>();
        for (String token : CssParsingUtil.splitFunctionList(trimmed)) {
            UITransform.Op op = parseFunction(token);
            if (op == null) return null;
            ops.add(op);
        }
        return ops.isEmpty() ? null : UITransform.of(ops);
    }

    /** @return the op, or {@code null} if {@code token} is not a supported {@code name(args)} call. */
    private static @Nullable UITransform.Op parseFunction(String token) {
        int open = token.indexOf('(');
        if (open <= 0 || !token.endsWith(")")) return null;

        String name = token.substring(0, open).trim().toLowerCase(Locale.ROOT);
        List<String> args = CssParsingUtil.splitTopLevelCommas(token.substring(open + 1, token.length() - 1));
        args.replaceAll(String::trim);
        if (args.size() == 1 && args.get(0).isEmpty()) return null;

        return switch (name) {
            // Percentages resolve against the element's own box, which is exactly LengthPercent's model.
            case "translate" -> args.size() == 1 ? translate(args.get(0), "0")
                    : args.size() == 2 ? translate(args.get(0), args.get(1))
                    : null;
            case "translatex" -> args.size() == 1 ? translate(args.get(0), "0") : null;
            case "translatey" -> args.size() == 1 ? translate("0", args.get(0)) : null;

            // CSS: one argument scales BOTH axes, unlike translate where the second defaults to zero.
            case "scale" -> args.size() == 1 ? scale(args.get(0), args.get(0))
                    : args.size() == 2 ? scale(args.get(0), args.get(1))
                    : null;
            case "scalex" -> args.size() == 1 ? scale(args.get(0), "1") : null;
            case "scaley" -> args.size() == 1 ? scale("1", args.get(0)) : null;

            case "rotate" -> args.size() == 1 ? rotate(args.get(0)) : null;

            case "skew" -> args.size() == 1 ? skew(args.get(0), "0")
                    : args.size() == 2 ? skew(args.get(0), args.get(1))
                    : null;
            case "skewx" -> args.size() == 1 ? skew(args.get(0), "0") : null;
            case "skewy" -> args.size() == 1 ? skew("0", args.get(0)) : null;

            // matrix() is deliberately unsupported — see UITransform's "known divergences".
            default -> null;
        };
    }

    private static @Nullable UITransform.Op translate(String x, String y) {
        LengthPercent lx = LengthPercent.parse(x);
        LengthPercent ly = LengthPercent.parse(y);
        return (lx == null || ly == null) ? null : UITransform.Op.translate(lx, ly);
    }

    private static @Nullable UITransform.Op scale(String x, String y) {
        Float fx = number(x);
        Float fy = number(y);
        return (fx == null || fy == null) ? null : UITransform.Op.scale(fx, fy);
    }

    private static @Nullable UITransform.Op rotate(String angle) {
        Float radians = CssAngle.parse(angle);
        return radians == null ? null : UITransform.Op.rotate(radians);
    }

    private static @Nullable UITransform.Op skew(String x, String y) {
        Float rx = CssAngle.parse(x);
        Float ry = CssAngle.parse(y);
        return (rx == null || ry == null) ? null : UITransform.Op.skew(rx, ry);
    }

    /** A bare unitless multiplier. {@code scale(200%)} is valid CSS but has no use here, so it is not
     * accepted — a percentage would silently read as the number 200 without this rejecting it. */
    private static @Nullable Float number(String raw) {
        try {
            return Float.parseFloat(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
