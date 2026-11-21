package com.crystalgui.style.property;

import com.crystalgui.style.sheet.StyleRule;

import java.util.List;

/**
 * A CSS shorthand: one name a sheet writes, expanded at parse time into the longhands that cascade.
 *
 * <p>The longhands are the only real properties; the shorthand never reaches the cascade. So
 * {@code padding: 4px 8px} becomes four declarations at the rule's own origin, specificity and order,
 * and a later {@code padding-left} beats it the way it would in a browser. Every shorthand is listed in
 * {@link Shorthands}, which the declaration parser and {@code transition} both read.</p>
 *
 * <pre>{@code
 * record TextOffset() implements Shorthand {
 *     public String name() { return "text-offset"; }
 *     public List<StyleProperty<?>> longhands() { return List.of(TEXT_OFFSET_X, TEXT_OFFSET_Y); }
 *     public boolean expand(List<StyleRule.Declaration> out, String value, boolean important) {
 *         List<String> tokens = CssParsingUtil.splitTopLevelSpaces(value);
 *         if (tokens.size() != 2) return false;
 *         out.add(Shorthand.declaration(TEXT_OFFSET_X, tokens.get(0), important));
 *         out.add(Shorthand.declaration(TEXT_OFFSET_Y, tokens.get(1), important));
 *         return true;
 *     }
 * }
 * }</pre>
 *
 * <ul>
 *   <li>A shorthand sets <b>every</b> longhand it names, an omitted part to its initial value, as CSS does.</li>
 *   <li>{@code expand} answers false for a value it does not take, and appends nothing; the parser warns.</li>
 *   <li>A longhand CSS does not have ({@code text-offset-x}) is marked {@code setAuthoredThrough(name)},
 *       so a sheet can only write the shorthand.</li>
 * </ul>
 */
public interface Shorthand {

    /** The name a sheet writes. */
    String name();

    /** Every property the shorthand sets; {@code transition: <name>} animates exactly these. */
    List<StyleProperty<?>> longhands();

    /** Appends the declarations {@code value} expands to; false, appending nothing, when it does not parse. */
    boolean expand(List<StyleRule.Declaration> out, String value, boolean important);

    /** {@code property} parsed from {@code raw}. */
    static <T> StyleRule.Declaration declaration(StyleProperty<T> property, String raw, boolean important) {
        return new StyleRule.Declaration(property, property.valueParser.parse(raw), important);
    }

    /** {@code property} at its initial value. */
    static <T> StyleRule.Declaration initial(StyleProperty<T> property, boolean important) {
        return new StyleRule.Declaration(property,
                StyleValue.of(property.write(property.initialValue), property.initialValue), important);
    }
}
