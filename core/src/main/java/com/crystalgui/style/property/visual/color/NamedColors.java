package com.crystalgui.style.property.visual.color;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * CSS Color 4's 148 named colours, as straight ARGB.
 *
 * <pre>{@code
 * NamedColors.argb("rebeccapurple");   // 0xFF663399
 * NamedColors.argb("Gold");            // 0xFFFFD700: case-insensitive, as CSS keywords are
 * NamedColors.argb("transparent");     // null: a keyword of its own, which ColorValue reads
 * }</pre>
 */
public final class NamedColors {

    private static final Map<String, Integer> NAMED = new HashMap<>(256);

    static {
        put("aliceblue", 0xFFF0F8FF);
        put("antiquewhite", 0xFFFAEBD7);
        put("aqua", 0xFF00FFFF);
        put("aquamarine", 0xFF7FFFD4);
        put("azure", 0xFFF0FFFF);
        put("beige", 0xFFF5F5DC);
        put("bisque", 0xFFFFE4C4);
        put("black", 0xFF000000);
        put("blanchedalmond", 0xFFFFEBCD);
        put("blue", 0xFF0000FF);
        put("blueviolet", 0xFF8A2BE2);
        put("brown", 0xFFA52A2A);
        put("burlywood", 0xFFDEB887);
        put("cadetblue", 0xFF5F9EA0);
        put("chartreuse", 0xFF7FFF00);
        put("chocolate", 0xFFD2691E);
        put("coral", 0xFFFF7F50);
        put("cornflowerblue", 0xFF6495ED);
        put("cornsilk", 0xFFFFF8DC);
        put("crimson", 0xFFDC143C);
        put("cyan", 0xFF00FFFF);
        put("darkblue", 0xFF00008B);
        put("darkcyan", 0xFF008B8B);
        put("darkgoldenrod", 0xFFB8860B);
        put("darkgray", 0xFFA9A9A9);
        put("darkgreen", 0xFF006400);
        put("darkgrey", 0xFFA9A9A9);
        put("darkkhaki", 0xFFBDB76B);
        put("darkmagenta", 0xFF8B008B);
        put("darkolivegreen", 0xFF556B2F);
        put("darkorange", 0xFFFF8C00);
        put("darkorchid", 0xFF9932CC);
        put("darkred", 0xFF8B0000);
        put("darksalmon", 0xFFE9967A);
        put("darkseagreen", 0xFF8FBC8F);
        put("darkslateblue", 0xFF483D8B);
        put("darkslategray", 0xFF2F4F4F);
        put("darkslategrey", 0xFF2F4F4F);
        put("darkturquoise", 0xFF00CED1);
        put("darkviolet", 0xFF9400D3);
        put("deeppink", 0xFFFF1493);
        put("deepskyblue", 0xFF00BFFF);
        put("dimgray", 0xFF696969);
        put("dimgrey", 0xFF696969);
        put("dodgerblue", 0xFF1E90FF);
        put("firebrick", 0xFFB22222);
        put("floralwhite", 0xFFFFFAF0);
        put("forestgreen", 0xFF228B22);
        put("fuchsia", 0xFFFF00FF);
        put("gainsboro", 0xFFDCDCDC);
        put("ghostwhite", 0xFFF8F8FF);
        put("gold", 0xFFFFD700);
        put("goldenrod", 0xFFDAA520);
        put("gray", 0xFF808080);
        put("green", 0xFF008000);
        put("greenyellow", 0xFFADFF2F);
        put("grey", 0xFF808080);
        put("honeydew", 0xFFF0FFF0);
        put("hotpink", 0xFFFF69B4);
        put("indianred", 0xFFCD5C5C);
        put("indigo", 0xFF4B0082);
        put("ivory", 0xFFFFFFF0);
        put("khaki", 0xFFF0E68C);
        put("lavender", 0xFFE6E6FA);
        put("lavenderblush", 0xFFFFF0F5);
        put("lawngreen", 0xFF7CFC00);
        put("lemonchiffon", 0xFFFFFACD);
        put("lightblue", 0xFFADD8E6);
        put("lightcoral", 0xFFF08080);
        put("lightcyan", 0xFFE0FFFF);
        put("lightgoldenrodyellow", 0xFFFAFAD2);
        put("lightgray", 0xFFD3D3D3);
        put("lightgreen", 0xFF90EE90);
        put("lightgrey", 0xFFD3D3D3);
        put("lightpink", 0xFFFFB6C1);
        put("lightsalmon", 0xFFFFA07A);
        put("lightseagreen", 0xFF20B2AA);
        put("lightskyblue", 0xFF87CEFA);
        put("lightslategray", 0xFF778899);
        put("lightslategrey", 0xFF778899);
        put("lightsteelblue", 0xFFB0C4DE);
        put("lightyellow", 0xFFFFFFE0);
        put("lime", 0xFF00FF00);
        put("limegreen", 0xFF32CD32);
        put("linen", 0xFFFAF0E6);
        put("magenta", 0xFFFF00FF);
        put("maroon", 0xFF800000);
        put("mediumaquamarine", 0xFF66CDAA);
        put("mediumblue", 0xFF0000CD);
        put("mediumorchid", 0xFFBA55D3);
        put("mediumpurple", 0xFF9370DB);
        put("mediumseagreen", 0xFF3CB371);
        put("mediumslateblue", 0xFF7B68EE);
        put("mediumspringgreen", 0xFF00FA9A);
        put("mediumturquoise", 0xFF48D1CC);
        put("mediumvioletred", 0xFFC71585);
        put("midnightblue", 0xFF191970);
        put("mintcream", 0xFFF5FFFA);
        put("mistyrose", 0xFFFFE4E1);
        put("moccasin", 0xFFFFE4B5);
        put("navajowhite", 0xFFFFDEAD);
        put("navy", 0xFF000080);
        put("oldlace", 0xFFFDF5E6);
        put("olive", 0xFF808000);
        put("olivedrab", 0xFF6B8E23);
        put("orange", 0xFFFFA500);
        put("orangered", 0xFFFF4500);
        put("orchid", 0xFFDA70D6);
        put("palegoldenrod", 0xFFEEE8AA);
        put("palegreen", 0xFF98FB98);
        put("paleturquoise", 0xFFAFEEEE);
        put("palevioletred", 0xFFDB7093);
        put("papayawhip", 0xFFFFEFD5);
        put("peachpuff", 0xFFFFDAB9);
        put("peru", 0xFFCD853F);
        put("pink", 0xFFFFC0CB);
        put("plum", 0xFFDDA0DD);
        put("powderblue", 0xFFB0E0E6);
        put("purple", 0xFF800080);
        put("rebeccapurple", 0xFF663399);
        put("red", 0xFFFF0000);
        put("rosybrown", 0xFFBC8F8F);
        put("royalblue", 0xFF4169E1);
        put("saddlebrown", 0xFF8B4513);
        put("salmon", 0xFFFA8072);
        put("sandybrown", 0xFFF4A460);
        put("seagreen", 0xFF2E8B57);
        put("seashell", 0xFFFFF5EE);
        put("sienna", 0xFFA0522D);
        put("silver", 0xFFC0C0C0);
        put("skyblue", 0xFF87CEEB);
        put("slateblue", 0xFF6A5ACD);
        put("slategray", 0xFF708090);
        put("slategrey", 0xFF708090);
        put("snow", 0xFFFFFAFA);
        put("springgreen", 0xFF00FF7F);
        put("steelblue", 0xFF4682B4);
        put("tan", 0xFFD2B48C);
        put("teal", 0xFF008080);
        put("thistle", 0xFFD8BFD8);
        put("tomato", 0xFFFF6347);
        put("turquoise", 0xFF40E0D0);
        put("violet", 0xFFEE82EE);
        put("wheat", 0xFFF5DEB3);
        put("white", 0xFFFFFFFF);
        put("whitesmoke", 0xFFF5F5F5);
        put("yellow", 0xFFFFFF00);
        put("yellowgreen", 0xFF9ACD32);
    }

    private NamedColors() {
    }

    /** The colour {@code name} names, or null when it names none. */
    public static Integer argb(String name) {
        return name == null ? null : NAMED.get(name.trim().toLowerCase(Locale.ROOT));
    }

    private static void put(String name, int argb) {
        NAMED.put(name, argb);
    }
}
