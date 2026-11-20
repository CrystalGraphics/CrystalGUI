package com.crystalgui.style.property.visual.image;

import java.util.Collection;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.crystalgui.core.async.Contributions;
import com.crystalgui.core.async.Owner;
import com.crystalgui.render.image.CgUiImage;
import com.crystalgui.style.property.visual.color.ColorValue;

/**
 * Every CSS function that makes an image, declared once: the parser and whatever groups a copied image by kind both
 * read it. A mod registers its own and gets parsing, a name and a section of its own wherever images are grouped.
 *
 * <pre>{@code
 * ImageKinds.register(ImageKinds.Kind.function("stripes", "Stripes", "stripes", MyMod::parseStripes));
 * // `background: stripes(4px, #FFF)` now parses
 * }</pre>
 *
 * <ul>
 *   <li>{@code none} is not a kind: it is handled before any kind is asked, as an absence.</li>
 *   <li>A parser answers null for text it cannot read; that fails the declaration.</li>
 *   <li>For drawing by hand, register a {@link com.crystalgui.render.image.CgUiPainter} by name instead: CSS writes
 *       it {@code paint(name)}.</li>
 * </ul>
 */
public final class ImageKinds {

    /**
     * One way of writing an image.
     *
     * @param id      stable, what a copied slot is keyed by: {@code "gradient"}
     * @param label   the section it is filed under: {@code "Gradient"}
     * @param matches whether some CSS is this kind, given the text trimmed and lower-cased
     * @param parse   the whole text to an image, or null if it will not read
     */
    public record Kind(String id, String label, Predicate<String> matches, Function<String, CgUiImage> parse) {

        /** The ordinary shape, {@code name(arguments)}: {@code arguments} gets the text between the parentheses. */
        public static Kind function(String id, String label, String name, Function<String, CgUiImage> arguments) {
            String open = name + "(";
            return new Kind(id, label,
                    css -> css.startsWith(open) && css.endsWith(")"),
                    css -> arguments.apply(css.substring(open.length(), css.length() - 1)));
        }
    }

    // Threads: a published list; every document's cascade parses on its own thread while a mod may register.
    private static final Contributions<Kind> KINDS = Contributions.of("image-kinds", Kind::id);

    static {
        register(new Kind("color", "Color",
                css -> css.startsWith("#") || css.startsWith("rgb(") || css.startsWith("rgba("),
                ImageKinds::parseColour));
        register(Kind.function("image", "Image", "image", ImageValue::parseImage));
        register(Kind.function("sprite", "Sprite", "sprite", ImageValue::parseSprite));
        register(Kind.function("asset", "Asset", "asset", ImageValue::parseAsset));
        register(Kind.function("shape", "Shape", "shape", ImageValue::parseShape));
        register(Kind.function("icon", "Icon", "icon", ImageValue::parseIcon));
        // Linear and radial share one section: both are "a gradient" to whoever copies one
        Kind linear = Kind.function("gradient", "Gradient", "linear-gradient", ImageValue::parseLinearGradient);
        Kind radial = Kind.function("gradient", "Gradient", "radial-gradient", ImageValue::parseRadialGradient);
        register(new Kind("gradient", "Gradient",
                css -> linear.matches().test(css) || radial.matches().test(css),
                css -> (linear.matches().test(css.toLowerCase(Locale.ROOT)) ? linear : radial).parse().apply(css)));
        register(Kind.function("grid", "Grid", "grid", ImageValue::parseGrid));
        register(Kind.function("paint", "Painter", "paint", ImageValue::parsePaint));
        register(Kind.function("cross-fade", "Cross-fade", "cross-fade", ImageValue::parseCrossFade));
    }

    private ImageKinds() {
    }

    /** Adds a kind, or replaces one with the same id. */
    public static void register(Kind kind) {
        KINDS.replace(Owner.PROCESS, kind);
    }

    /** Removes a kind by id. */
    public static void unregister(String id) {
        KINDS.removeIf(kind -> kind.id().equals(id));
    }

    /** Every kind, in registration order: a snapshot. */
    public static Collection<Kind> all() {
        return KINDS.all();
    }

    /** Which kind {@code css} is, or null when nothing claims it. */
    @Nullable
    public static Kind matching(@Nullable String css) {
        if (css == null) return null;
        String lower = css.trim().toLowerCase(Locale.ROOT);
        for (Kind kind : KINDS.all()) {
            if (kind.matches().test(lower)) return kind;
        }
        return null;
    }

    /**
     * {@code css} as an image, or null when no kind claims it or the one that does cannot read it. The kind is chosen
     * on a lower-cased copy and parsed from the original: {@code ASSET("Foo:Bar")} names another file.
     */
    @Nullable
    public static CgUiImage parse(String css) {
        String value = css.trim();
        Kind kind = matching(value);
        return kind == null ? null : kind.parse().apply(value);
    }

    @Nullable
    private static CgUiImage parseColour(String css) {
        Integer color = ColorValue.parseColor(css);
        return color == null ? null : CgUiImage.color(color);
    }
}
