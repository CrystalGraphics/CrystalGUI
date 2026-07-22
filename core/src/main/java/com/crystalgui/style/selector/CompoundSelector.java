package com.crystalgui.style.selector;

import com.crystalgui.style.PseudoClasses;
import com.crystalgui.ui.UIElement;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One simple-selector group with no combinators, e.g. {@code .foo.bar:hover} or {@code button#id}.
 * A {@link Selector} chains these together with combinators for descendant/child matching.
 *
 * <p>Pseudo-classes are matched by delegating directly to {@link PseudoClasses#applies(UIElement)} —
 * no synthetic {@code __hovered__}-style classes are added to the element.
 */
public record CompoundSelector(List<Part> parts) {

    public record Part(SelectorType type, String identity) {
        public Part {
            if (type == SelectorType.PSEUDO_CLASS) {
                // Validate eagerly so a typo'd pseudo-class fails at stylesheet-parse time, not
                // silently (never matching) at paint time.
                PseudoClasses.valueOf(identity.toUpperCase(Locale.ROOT));
            }
        }
    }

    public boolean matches(UIElement element) {
        for (var part : parts) {
            if (!partMatches(part, element)) return false;
        }
        return true;
    }

    private static boolean partMatches(Part part, UIElement element) {
        return switch (part.type()) {
            case UNIVERSAL -> true;
            case TYPE -> element.tagName().equals(part.identity());
            case ID -> element.getId().equals(part.identity());
            case CLASS -> element.hasClass(part.identity());
            case PSEUDO_CLASS -> PseudoClasses.valueOf(part.identity().toUpperCase(Locale.ROOT)).applies(element);
        };
    }

    public int specificity() {
        int total = 0;
        for (var part : parts) total += part.type().weight;
        return total;
    }

    private static final Pattern PART_PATTERN =
            Pattern.compile("(#[\\w-]+)|(\\.[\\w-]+)|(:[\\w-]+)|(\\*)|([A-Za-z][\\w-]*)");

    /** Parses one combinator-free simple-selector group, e.g. {@code button#id.foo.bar:hover}. */
    public static CompoundSelector parse(String text) {
        List<Part> parts = new ArrayList<>();
        Matcher m = PART_PATTERN.matcher(text);
        int consumed = 0;
        while (m.find()) {
            if (m.start() != consumed) {
                throw new IllegalArgumentException(
                        "Unparseable selector fragment near '" + text.substring(consumed) + "' in '" + text + "'");
            }
            String token = m.group();
            if (token.equals("*")) {
                parts.add(new Part(SelectorType.UNIVERSAL, "*"));
            } else if (token.charAt(0) == '#') {
                parts.add(new Part(SelectorType.ID, token.substring(1)));
            } else if (token.charAt(0) == '.') {
                parts.add(new Part(SelectorType.CLASS, token.substring(1)));
            } else if (token.charAt(0) == ':') {
                parts.add(new Part(SelectorType.PSEUDO_CLASS, token.substring(1)));
            } else {
                parts.add(new Part(SelectorType.TYPE, token));
            }
            consumed = m.end();
        }
        if (consumed != text.length()) {
            throw new IllegalArgumentException(
                    "Unparseable selector fragment near '" + text.substring(consumed) + "' in '" + text + "'");
        }
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("Empty compound selector");
        }
        return new CompoundSelector(parts);
    }
}
