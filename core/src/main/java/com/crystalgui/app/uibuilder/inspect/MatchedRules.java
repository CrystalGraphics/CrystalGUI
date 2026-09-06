package com.crystalgui.app.uibuilder.inspect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgui.style.ElementStyle;
import com.crystalgui.style.StyleEngine;
import com.crystalgui.style.Styleable;
import com.crystalgui.style.StyleOrigin;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StyleSlot;

/**
 * The cascade for one element, as something a pane can draw.
 *
 * <pre>{@code
 * for (MatchedRules.Rule rule : MatchedRules.of(element)) {
 *     for (MatchedRules.Declaration d : rule.declarations()) {
 *         draw(d.property().getName(), d.value(), d.won() ? PLAIN : STRUCK);
 *     }
 * }
 * }</pre>
 *
 * <p><b>Nothing here is computed that the engine has not.</b> Every candidate already carries its origin,
 * specificity, proximity and source order, and {@link ElementStyle#computeCandidateSlot} already knows
 * which one wins — so this groups what is there and asks who won, rather than re-deciding the cascade.
 * A pane that re-implemented the ordering would eventually disagree with what is on screen, and be
 * believed.</p>
 *
 * <p>A <b>rule</b> here is what the sheet wrote: the declarations of one selector block that reached this
 * element, identified by the sheet and rule number packed into every slot's {@code sourceOrder}. Slots a
 * widget wrote — inline, important, animation — belong to no sheet and are grouped by origin alone.</p>
 */
public final class MatchedRules {

    private MatchedRules() {
    }

    /**
     * One declaration a rule contributed.
     *
     * @param won whether this slot is the one the element actually uses. False is what a pane draws
     *            struck through: the declaration matched, and something above it won.
     */
    public record Declaration(StyleProperty<?> property, @Nullable Object value,
                              StyleOrigin origin, boolean won) {
    }

    /**
     * One block of declarations that reached the element.
     *
     * @param sheetIndex an index into the engine's sheet list, or -1 when no sheet wrote it
     * @param ruleOrder  the rule's number within that sheet — the same number a
     *                   {@code CssSourceModel.Rule} carries, which is how a matched rule is traced to
     *                   its text
     */
    public record Rule(StyleOrigin origin, int sheetIndex, int ruleOrder,
                       List<Declaration> declarations) {

        /** Whether anything in this rule survived. A rule entirely struck through is still shown — that
         * it lost is the useful fact. */
        public boolean anyWon() {
            for (Declaration declaration : declarations) {
                if (declaration.won()) return true;
            }
            return false;
        }
    }

    /**
     * Every rule that reached {@code element}, in <b>cascade order, weakest first</b>.
     *
     * <p>Weakest first because that is how a browser's Styles pane reads: the winner is at the top of
     * what you scroll to, and the history of what it beat is under it. Empty for an element the cascade
     * has never visited.</p>
     */
    public static List<Rule> of(@Nullable Styleable element) {
        if (element == null) return List.of();
        ElementStyle style = element.getStyle();
        if (style == null) return List.of();

        Map<String, List<Declaration>> byRule = new LinkedHashMap<>();
        Map<String, StyleSlot<?>> firstSlotOf = new LinkedHashMap<>();

        List<StyleSlot<?>> slots = new ArrayList<>();
        for (List<StyleSlot<?>> candidates : style.candidates.values()) slots.addAll(candidates);
        // Weakest first: origin decides, then where in the sheets it was written. Spelled out rather
        // than composed, because a Comparator over a wildcard type cannot be inferred.
        slots.sort((a, b) -> {
            int byOrigin = a.origin().compareTo(b.origin());
            return byOrigin != 0 ? byOrigin : Long.compare(a.sourceOrder(), b.sourceOrder());
        });

        for (StyleSlot<?> slot : slots) {
            String key = keyOf(slot);
            byRule.computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add(new Declaration(slot.property(), slot.value(), slot.origin(), wins(style, slot)));
            firstSlotOf.putIfAbsent(key, slot);
        }

        List<Rule> rules = new ArrayList<>(byRule.size());
        for (Map.Entry<String, List<Declaration>> entry : byRule.entrySet()) {
            StyleSlot<?> first = firstSlotOf.get(entry.getKey());
            rules.add(new Rule(first.origin(),
                    StyleEngine.sheetIndexOf(first.sourceOrder()),
                    StyleEngine.ruleOrderOf(first.sourceOrder()),
                    List.copyOf(entry.getValue())));
        }
        return List.copyOf(rules);
    }

    /** Whether {@code slot} is the winner for its own property. */
    private static boolean wins(ElementStyle style, StyleSlot<?> slot) {
        StyleSlot<?> winner = winnerFor(style, slot.property());
        return winner == slot;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static StyleSlot<?> winnerFor(ElementStyle style, StyleProperty<?> property) {
        return style.computeCandidateSlot((StyleProperty<Object>) property);
    }

    /** Slots from one sheet rule group together; slots a widget wrote group by origin, having no rule. */
    private static String keyOf(StyleSlot<?> slot) {
        int sheet = StyleEngine.sheetIndexOf(slot.sourceOrder());
        int rule = StyleEngine.ruleOrderOf(slot.sourceOrder());
        return slot.origin().name() + ":" + sheet + ":" + rule;
    }
}
