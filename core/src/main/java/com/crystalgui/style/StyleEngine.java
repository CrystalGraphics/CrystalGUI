package com.crystalgui.style;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StyleSlot;
import com.crystalgui.style.sheet.StyleRule;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.transition.TransitionEngine;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One instance per {@link UIWindow}. Owns the registered stylesheets, the dirty-rematch queue
 * (selector re-matching on id/class/pseudo-class change), and — via {@link TransitionEngine} — the
 * active state-transition driver. Driven once per frame from {@link UIWindow#paintFrame()}, before
 * layout.
 */
public final class StyleEngine {
    private final UIWindow window;

    @Getter
    private final TransitionEngine transitionEngine = new TransitionEngine();

    private final List<StyleSheet> sheets = new ArrayList<>();
    private final Set<UIElement> dirtyMatch = new HashSet<>();

    /** Exactly the STYLESHEET/IMPORTANT-origin slots this engine last applied to each element — kept
     * so a re-match can remove precisely what it added, without guessing by origin (an IMPORTANT-
     * origin slot might equally have come from user Java code via StyleGroup.setImportant()). */
    private final Map<UIElement, List<StyleSlot<?>>> appliedByElement = new HashMap<>();

    public StyleEngine(UIWindow window) {
        this.window = window;
    }

    public void addStylesheet(StyleSheet sheet) {
        sheets.add(sheet);
        dirtyMatch.addAll(window.getElements());
    }

    public void removeStylesheet(StyleSheet sheet) {
        if (sheets.remove(sheet)) {
            dirtyMatch.addAll(window.getElements());
        }
    }

    /** Called from {@link UIElement#invalidateStyleMatch()} — marks an element for re-matching. */
    public void markDirty(UIElement element) {
        dirtyMatch.add(element);
    }

    /** Called when an element leaves the tree — stops tracking it for matching and animation. */
    public void onElementDetached(UIElement element) {
        dirtyMatch.remove(element);
        appliedByElement.remove(element);
        transitionEngine.onElementDetached(element);
    }

    /** Called once per frame from {@link UIWindow#paintFrame()}, before layout is recomputed. */
    public void calculateStyle(float deltaSeconds) {
        drainDirtyMatch();
        transitionEngine.tick(deltaSeconds);
    }

    private void drainDirtyMatch() {
        if (dirtyMatch.isEmpty()) return;
        // Snapshot-and-clear before iterating: a reentrant invalidateStyleMatch() call during
        // matching (plausible — style-change listeners already mutate state synchronously) must
        // land in the freshly-cleared set and get picked up next frame, not throw a
        // ConcurrentModificationException or recurse within this same pass.
        var batch = new ArrayList<>(dirtyMatch);
        dirtyMatch.clear();
        for (var element : batch) {
            rematch(element);
        }
    }

    /** Declarations within one rule share the rule's own {@code sourceOrder} — this multiplier
     * folds each declaration's position within the rule into that same int, so a later declaration
     * for the same target property (e.g. an explicit {@code margin-left:} after a {@code margin:}
     * shorthand expansion in the same rule) still correctly outranks the earlier one via ordinary
     * {@link StyleSlot#compare}, instead of tying and falling back to insertion order. No realistic
     * rule has anywhere near this many declarations. */
    private static final int DECLARATION_ORDER_MULTIPLIER = 100_000;

    /** Stride between registered stylesheets in the packed {@code sourceOrder}. Sized to clear any
     * realistic sheet (a sheet would need ~10 million rules to reach it) and comfortably within a
     * {@code long} — which is exactly why {@link StyleSlot#sourceOrder()} is a {@code long}: an
     * {@code int} would cap this at roughly twenty sheets before wrapping. */
    private static final long SHEET_ORDER_STRIDE = 1_000_000_000_000L;

    private void rematch(UIElement element) {
        var previouslyApplied = appliedByElement.get(element);

        List<StyleSlot<?>> newSlots = new ArrayList<>();
        for (int sheetIndex = 0; sheetIndex < sheets.size(); sheetIndex++) {
            var sheet = sheets.get(sheetIndex);
            for (var rule : sheet.candidatesFor(element)) {
                if (!rule.selector().matches(element)) continue;
                int specificity = rule.selector().specificity();
                var decls = rule.declarations();
                for (int i = 0; i < decls.size(); i++) {
                    var decl = decls.get(i);
                    // The sheet's own origin, so a USER_AGENT sheet (StyleSheet.DEFAULT) can never
                    // out-rank an author one. `!important` still escalates to IMPORTANT regardless —
                    // which is why default.css must not use it: doing so would jump it above every
                    // author sheet and defeat the whole point.
                    var origin = decl.important() ? StyleOrigin.IMPORTANT : sheet.getOrigin();
                    // Registration index packed ABOVE the rule index, so a later-registered sheet
                    // outranks an earlier one at equal specificity — CSS's "later sheet wins".
                    // StyleSheet.parse restarts sourceOrder at 0 for every sheet, so without this a
                    // big sheet's rule #40 beat a later sheet's rule #2 purely by rule count.
                    long sourceOrder = (long) sheetIndex * SHEET_ORDER_STRIDE
                            + (long) rule.sourceOrder() * DECLARATION_ORDER_MULTIPLIER + i;
                    var slot = toSlot(decl, origin, specificity, sourceOrder);
                    if (slot != null) newSlots.add(slot);
                }
            }
        }

        if (newSlots.isEmpty()) {
            appliedByElement.remove(element);
            if (previouslyApplied != null && !previouslyApplied.isEmpty()) {
                element.getStyle().removeCandidates(previouslyApplied::contains);
            }
        } else {
            appliedByElement.put(element, newSlots);
            if (previouslyApplied != null && !previouslyApplied.isEmpty()) {
                // Atomic remove+add — see ElementStyle.replaceCandidates()'s doc for why this must
                // not be two separate calls (would defeat transitions via a spurious null passthrough).
                element.getStyle().replaceCandidates(previouslyApplied::contains, newSlots);
            } else {
                element.getStyle().putCandidates(newSlots);
            }
        }
    }

    /**
     * Builds a {@link StyleSlot} from a matched declaration, or {@code null} if the declaration's
     * value failed to parse — a malformed value (e.g. {@code color: notacolor;}) must never become a
     * cascade winner with a bogus null value, silently overriding a real lower-priority one.
     */
    @Nullable
    @SuppressWarnings("unchecked")
    static <T> StyleSlot<T> toSlot(StyleRule.Declaration decl, StyleOrigin origin, int specificity, long sourceOrder) {
        var property = (StyleProperty<T>) decl.property();
        T value = (T) decl.value().compute();
        if (value == null) {
            CrystalGuiCore.LOGGER.warn("Stylesheet declaration '{}: {}' failed to parse — skipping",
                    property.name, decl.value().rawValue);
            return null;
        }
        return StyleSlot.of(property, origin, specificity, sourceOrder, value);
    }

    public List<StyleSheet> getSheets() {
        return List.copyOf(sheets);
    }
}
