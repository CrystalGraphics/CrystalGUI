package com.crystalgui.style.selector;

import com.crystalgui.style.Styleable;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A full selector chain, e.g. {@code .panel > .row .button:hover}. {@link CompoundSelector}s are
 * matched right-to-left: the rightmost compound must match the target element, then ancestors are
 * walked to satisfy each remaining combinator in turn — the standard CSS selector-matching order.
 */
public record Selector(List<CompoundSelector> compounds, List<Combinator> combinators) {

    public enum Combinator { CHILD, DESCENDANT }

    public Selector {
        if (compounds.isEmpty()) {
            throw new IllegalArgumentException("Selector must have at least one compound selector");
        }
        if (combinators.size() != compounds.size() - 1) {
            throw new IllegalArgumentException("Expected " + (compounds.size() - 1) + " combinators for "
                    + compounds.size() + " compound selectors, got " + combinators.size());
        }
    }

    /**
     * The pseudo-element this selector targets, or null for an ordinary selector.
     *
     * <p>Read from the rightmost compound only, because that is the only place CSS allows one: a
     * pseudo-element is not a real element, so nothing may be selected relative to it.</p>
     */
    @Nullable
    public CompoundSelector.Part pseudoElement() {
        return compounds.get(compounds.size() - 1).pseudoElement();
    }

    /**
     * Whether this selector's pseudo-element selects a real element in a shadow tree
     * ({@code ::part}) rather than a paint-time overlay ({@code ::highlight}).
     *
     * @see CompoundSelector#selectsShadowPart()
     */
    public boolean selectsShadowPart() {
        return compounds.get(compounds.size() - 1).selectsShadowPart();
    }

    /**
     * Whether this selector applies to {@code element}'s own style.
     *
     * <p>Always false when a pseudo-element is present — those rules style an overlay, not the element,
     * and are routed separately by {@code StyleEngine}. See {@link #matchesOriginating}.</p>
     */
    public boolean matches(Styleable element) {
        if (pseudoElement() != null) return false;
        return matchesOriginating(element);
    }

    /**
     * Whether {@code element} is the <b>originating element</b> — the one a pseudo-element hangs off.
     *
     * <p>Identical to {@link #matches} except that a trailing pseudo-element is ignored rather than
     * disqualifying. This is what {@code .code text::highlight(keyword)} is matched with.</p>
     */
    /**
     * Whether the parts after the pseudo-element describe {@code part} — @see
     * CompoundSelector#matchesAfterPseudoElement.
     */
    public boolean matchesAfterPseudoElement(Styleable part) {
        return compounds.get(compounds.size() - 1).matchesAfterPseudoElement(part);
    }

    public boolean matchesOriginating(Styleable element) {
        int lastIndex = compounds.size() - 1;
        if (!compounds.get(lastIndex).matchesOriginating(element)) return false;

        Styleable current = element;
        for (int i = lastIndex - 1; i >= 0; i--) {
            var compound = compounds.get(i);
            var combinator = combinators.get(i); // combinators[i] connects compounds[i] -> compounds[i+1]

            if (combinator == Combinator.CHILD) {
                current = current.getParent();
                if (current == null || !compound.matches(current)) return false;
            } else { // DESCENDANT
                current = current.getParent();
                while (current != null && !compound.matches(current)) {
                    current = current.getParent();
                }
                if (current == null) return false;
            }
        }
        return true;
    }

    public int specificity() {
        int total = 0;
        for (var c : compounds) total += c.specificity();
        return total;
    }

    /** Parses one combinator selector chain, e.g. {@code .panel > .row .button:hover}. No commas —
     * comma-separated selector lists are split into separate {@code Selector.parse} calls by the
     * caller (one rule block can share its declarations across several selectors). */
    public static Selector parse(String raw) {
        String spaced = raw.trim().replace(">", " > ");
        String[] tokens = spaced.trim().split("\\s+");

        List<CompoundSelector> compounds = new ArrayList<>();
        List<Combinator> combinators = new ArrayList<>();
        Combinator pending = null;

        for (String token : tokens) {
            if (token.isEmpty()) continue;
            if (token.equals(">")) {
                pending = Combinator.CHILD;
                continue;
            }
            if (!compounds.isEmpty()) {
                combinators.add(pending != null ? pending : Combinator.DESCENDANT);
            }
            pending = null;
            compounds.add(CompoundSelector.parse(token));
        }
        return new Selector(compounds, combinators);
    }
}
