package com.crystalgui.style.selector;

import com.crystalgui.style.PseudoClasses;
import com.crystalgui.ui.UIElement;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
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

    /**
     * @param argument the parenthesised argument of a functional part — the highlight name in
     *                 {@code ::highlight(keyword)}. Null for every other kind.
     */
    public record Part(SelectorType type, String identity, String argument) {
        public Part(SelectorType type, String identity) {
            this(type, identity, null);
        }

        public Part {
            if (type == SelectorType.PSEUDO_CLASS) {
                // Validate eagerly so a typo'd pseudo-class fails at stylesheet-parse time, not
                // silently (never matching) at paint time. Via lookup(), not valueOf(): hyphenated
                // names like `focus-visible` are not legal Java identifiers.
                PseudoClasses.lookup(identity);
            }
            if (type == SelectorType.PSEUDO_ELEMENT) {
                if (!identity.equals("highlight")) {
                    throw new IllegalArgumentException("Unsupported pseudo-element '::" + identity
                            + "'. The only one this engine implements is ::highlight(name) — structural"
                            + " pseudo-elements (::before/::after) have no equivalent here; widgets use"
                            + " internal children with __double-underscore__ classes instead.");
                }
                if (argument == null || argument.isEmpty()) {
                    throw new IllegalArgumentException("::highlight() requires a name, e.g. ::highlight(keyword)");
                }
            }
        }
    }

    /**
     * The pseudo-element this compound selects, or null.
     *
     * <p>Only ever legal on the rightmost compound of a selector — CSS forbids anything after a
     * pseudo-element, since it is not a real element and so has no descendants to select.</p>
     */
    @Nullable
    public Part pseudoElement() {
        for (var part : parts) {
            if (part.type() == SelectorType.PSEUDO_ELEMENT) return part;
        }
        return null;
    }

    /**
     * Whether this compound matches {@code element} <b>as an element</b>.
     *
     * <p>A compound carrying a pseudo-element never does. {@code text::highlight(kw)} selects the
     * highlight overlay of a {@code text}, not the {@code text} itself, so letting it match here would
     * paint the whole paragraph in the highlight colour — the single most likely way to get this
     * wrong.</p>
     */
    public boolean matches(UIElement element) {
        if (pseudoElement() != null) return false;
        return matchesOriginating(element);
    }

    /** Matches ignoring any pseudo-element part — "is this the <em>originating</em> element?" */
    public boolean matchesOriginating(UIElement element) {
        for (var part : parts) {
            if (part.type() == SelectorType.PSEUDO_ELEMENT) continue;
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
            case PSEUDO_CLASS -> PseudoClasses.lookup(part.identity()).applies(element);
            // Unreachable — both callers skip pseudo-element parts before getting here. Present so
            // the switch stays exhaustive, which is what makes adding a SelectorType a compile error.
            case PSEUDO_ELEMENT -> false;
        };
    }

    public int specificity() {
        int total = 0;
        for (var part : parts) total += part.type().weight;
        return total;
    }

    /** {@code ::name(arg)} is listed first on purpose: the alternation is ordered, so with
     * {@code :name} ahead of it a {@code ::highlight(x)} would lex as a pseudo-class whose name
     * began with a colon. */
    private static final Pattern PART_PATTERN =
            Pattern.compile("(::[\\w-]+(?:\\([\\w-]+\\))?)|(#[\\w-]+)|(\\.[\\w-]+)|(:[\\w-]+)|(\\*)|([A-Za-z][\\w-]*)");

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
            } else if (token.startsWith("::")) {
                // The argument is optional HERE and required by Part's constructor, deliberately. An
                // argument-less `::before` lexes cleanly and is then rejected with a message that names
                // what this engine does support; making the lexer reject it instead would report only
                // "unparseable selector fragment", which says nothing about why.
                int paren = token.indexOf('(');
                parts.add(paren < 0
                        ? new Part(SelectorType.PSEUDO_ELEMENT, token.substring(2), null)
                        : new Part(SelectorType.PSEUDO_ELEMENT, token.substring(2, paren),
                                token.substring(paren + 1, token.length() - 1)));
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
