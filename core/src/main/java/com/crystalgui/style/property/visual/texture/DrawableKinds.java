package com.crystalgui.style.property.visual.texture;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.style.property.visual.color.ColorValue;
import com.crystalgui.render.texture.CgUiRect;

/**
 * Every CSS function that produces a drawable — <b>declared once, read by everything</b>.
 *
 * <pre>{@code
 * DrawableKinds.register(DrawableKinds.Kind.function("blur", "Blur", "blur", MyMod::parseBlur));
 * // ...and `background: blur(4px)` now parses, and gets its own section in Paste Attributes
 * }</pre>
 *
 * <h3>Why this is a registry and not an if-chain</h3>
 *
 * <p>The parser knew the list and so did the thing that groups a copied drawable by which function made
 * it, and the second copy is the kind that goes quietly wrong: a function added to the parser and not
 * to the other list still renders, and simply stops being recognised when copied — filed under nothing
 * in particular, with no error anywhere. One list, and both readers walk it.</p>
 *
 * <p>It is also the whole of what a third party has to touch. Registering a kind gives it parsing, a
 * name, and a section of its own wherever drawables are grouped, with no edit to this engine.</p>
 *
 * <h3>{@code none} is not a kind</h3>
 *
 * <p>{@code none} and {@code empty} resolve to the shared EMPTY drawable and are handled before any
 * kind is consulted. They are an absence rather than a way of drawing something, so filing them beside
 * gradients and sprites would put an empty section in front of somebody for every property they left
 * unset.</p>
 */
public final class DrawableKinds {

    /**
     * One way of writing a drawable.
     *
     * @param id      stable, and what a copied slot is keyed by — {@code "gradient"}
     * @param label   the section it is filed under — {@code "Gradient"}
     * @param matches whether some CSS is this kind, given the text already trimmed and lower-cased
     * @param parse   the text — the WHOLE declaration, not its arguments — to a drawable, or null if
     *                it will not read
     */
    public record Kind(String id, String label, Predicate<String> matches,
                       Function<String, CgUiDrawable> parse) {

        /**
         * The ordinary shape: {@code name(arguments)}.
         *
         * <p>Slicing the arguments out is done here rather than in each parser, because every one of
         * them did it identically and a function whose name and whose {@code substring} length disagree
         * is a bug that renders as a missing first character.</p>
         */
        public static Kind function(String id, String label, String name,
                                    Function<String, CgUiDrawable> arguments) {
            String open = name + "(";
            return new Kind(id, label,
                    css -> css.startsWith(open) && css.endsWith(")"),
                    css -> arguments.apply(css.substring(open.length(), css.length() - 1)));
        }
    }

    private static final Map<String, Kind> BY_ID = new LinkedHashMap<>();

    static {
        // THE BUILT-INS, in the order they were tried before — order is only visible where two kinds
        // could match one text, which none of these can, but keeping it makes this list and the one it
        // replaced diffable.
        register(new Kind("colour", "Colour",
                css -> css.startsWith("#") || css.startsWith("rgb(") || css.startsWith("rgba("),
                DrawableKinds::parseColour));
        register(Kind.function("image", "Image", "image", TextureValue::parseImage));
        register(Kind.function("sprite", "Sprite", "sprite", TextureValue::parseSprite));
        register(Kind.function("asset", "Asset", "asset", TextureValue::parseAsset));
        register(Kind.function("shape", "Shape", "shape", TextureValue::parseShape));
        register(Kind.function("icon", "Icon", "icon", TextureValue::parseIcon));
        register(Kind.function("gradient", "Gradient", "linear-gradient",
                TextureValue::parseLinearGradient));
        register(Kind.function("grid", "Grid", "grid", TextureValue::parseGrid));
    }

    private DrawableKinds() {
    }

    /** Adds a kind, or replaces one with the same id. */
    public static void register(Kind kind) {
        BY_ID.put(kind.id(), kind);
    }

    /** Removes a kind by id. For a consumer withdrawing one, and for a test not leaving its own behind. */
    public static void unregister(String id) {
        BY_ID.remove(id);
    }

    /** Every kind, in registration order. */
    public static Collection<Kind> all() {
        return Collections.unmodifiableCollection(BY_ID.values());
    }

    /** Which kind {@code css} is, or null when nothing claims it. */
    @Nullable
    public static Kind matching(@Nullable String css) {
        if (css == null) return null;
        String lower = css.trim().toLowerCase(Locale.ROOT);
        for (Kind kind : BY_ID.values()) {
            if (kind.matches().test(lower)) return kind;
        }
        return null;
    }

    /**
     * {@code css} as a drawable, or null when no kind claims it or the one that does cannot read it.
     *
     * <p>Case is the reason the kind is chosen on a lower-cased copy and parsed from the original:
     * {@code ASSET("Foo:Bar")} names the same function and a different file.</p>
     */
    @Nullable
    public static CgUiDrawable parse(String css) {
        String value = css.trim();
        Kind kind = matching(value);
        return kind == null ? null : kind.parse().apply(value);
    }

    /** {@code #RRGGBB}, {@code #RRGGBBAA} or {@code rgb()/rgba()} as a flat fill. */
    @Nullable
    private static CgUiDrawable parseColour(String css) {
        Integer color = ColorValue.parseColor(css);
        return color == null ? null : CgUiRect.ofColor(color);
    }
}
