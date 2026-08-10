package com.crystalgui.style;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StyleSlot;
import com.crystalgui.style.sheet.StyleRule;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.style.transition.TransitionEngine;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

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

    /**
     * Resolved {@code ::highlight(name)} styles, per element, per highlight name.
     *
     * <p>Kept apart from the element's own cascade because a highlight pseudo-element is not an element:
     * it has no box, no children and no transitions, and folding its declarations into
     * {@link ElementStyle} would recolour the whole paragraph. Rebuilt wholesale in {@link #rematch},
     * so it stays in step with the ordinary cascade by construction rather than by a second
     * invalidation path.</p>
     */
    private final Map<UIElement, Map<String, HighlightStyle>> highlightsByElement = new HashMap<>();

    /**
     * Every live engine, weakly held — what {@link #reloadStylesheets()} restyles.
     *
     * <p>Weak because a window that goes out of scope must not be kept alive by a debug facility, and
     * there is no {@code UIWindow.close()} to unregister from. A discarded window simply falls out of the
     * set when it is collected; until then it is restyled harmlessly.</p>
     */
    private static final Set<StyleEngine> LIVE =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    public StyleEngine(UIWindow window) {
        this.window = window;
        LIVE.add(this);
    }

    /**
     * Re-reads every stylesheet from disk and restyles every live window — <b>CSS hot reload</b>.
     *
     * <p>The one call a host needs. {@code StyleSheetRegistry.reloadAll()} refills the sheets in place so
     * existing registrations stay valid, and every engine then re-matches; see
     * {@link #invalidateAllMatches()} for why re-matching alone is sufficient to drop deleted rules.</p>
     *
     * <p><b>Global rather than per-window on purpose.</b> Stylesheets are a global cache, so a per-window
     * call would re-read every file once per window and still leave the other windows stale unless the
     * caller remembered all of them. The harness binds this to a key; {@code CgAssetReloader}'s F3+T path
     * is the same shape.</p>
     *
     * @return how many stylesheets were re-read successfully
     */
    public static int reloadStylesheets() {
        int reloaded = StyleSheetRegistry.reloadAll();
        restyleAllWindows();
        return reloaded;
    }

    /**
     * Re-matches every element of every live window against the sheets' <b>current</b> contents —
     * the second half of any in-place sheet mutation. {@code StyleSheetRegistry.reloadAll()} and
     * {@code bindVariables()} both change what the sheets say without changing any engine's sheet
     * list, so no per-window hook fires; whoever mutated the sheets calls this next.
     */
    public static void restyleAllWindows() {
        synchronized (LIVE) {
            for (StyleEngine engine : LIVE) engine.invalidateAllMatches();
        }
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

    /**
     * Marks every element in this window for re-matching — what a stylesheet <b>hot-reload</b> needs.
     *
     * <p>The same thing {@link #addStylesheet} does inline, named so a caller that changed a sheet's
     * contents rather than the sheet list can ask for it. {@code StyleSheetRegistry.reloadAll()} refills
     * sheets in place precisely so existing registrations stay valid, which means nothing about the sheet
     * <em>list</em> changes and no existing hook fires.</p>
     *
     * <p>Re-matching is enough on its own: {@code rematch} remembers the slots it last applied per element
     * and replaces that whole set atomically, so a declaration deleted from the file is dropped rather
     * than left behind as a winning candidate nothing overwrites.</p>
     */
    public void invalidateAllMatches() {
        dirtyMatch.addAll(window.getElements());
    }

    /** Called from {@link UIElement#invalidateStyleMatch()} — marks an element for re-matching. */
    public void markDirty(UIElement element) {
        dirtyMatch.add(element);
    }

    /** Called when an element leaves the tree — stops tracking it for matching and animation. */
    public void onElementDetached(UIElement element) {
        dirtyMatch.remove(element);
        appliedByElement.remove(element);
        highlightsByElement.remove(element);
        transitionEngine.onElementDetached(element);
    }

    /** Called from {@link UIWindow#paintFrame()}, before layout is recomputed — and again afterwards for
     * as long as {@link #hasPendingMatches()} reports work that layout itself created. */
    public void calculateStyle(float deltaSeconds) {
        drainDirtyMatch();
        transitionEngine.tick(deltaSeconds);
    }

    /**
     * Whether any element is waiting to be re-matched.
     *
     * <p>Exists so {@code UIWindow} can tell whether <em>layout</em> dirtied the cascade — a ticker or an
     * {@code onLayoutChanged} hook that sets a class runs after this frame's {@link #calculateStyle}, so
     * without a second pass the class is set and its computed style is a frame behind it. Reported rather
     * than acted on here because only the window knows that re-cascading means re-laying-out too.</p>
     */
    public boolean hasPendingMatches() {
        return !dirtyMatch.isEmpty();
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

    /**
     * The resolved style of {@code ::highlight(name)} on {@code element}, or
     * {@link HighlightStyle#EMPTY} when no rule matched.
     *
     * <p>Never null, because "no theme styles this highlight name" is an ordinary, expected state — a
     * highlighter emits names without knowing which of them a given theme cares about.</p>
     */
    public HighlightStyle highlightStyle(UIElement element, String name) {
        var forElement = highlightsByElement.get(element);
        if (forElement == null) return HighlightStyle.EMPTY;
        return forElement.getOrDefault(name, HighlightStyle.EMPTY);
    }

    private void rematch(UIElement element) {
        var previouslyApplied = appliedByElement.get(element);

        List<StyleSlot<?>> newSlots = new ArrayList<>();
        // name -> property -> winning slot. Built alongside the element's own cascade rather than in a
        // second pass, so the two cannot disagree about which rules matched.
        Map<String, Map<StyleProperty<?>, StyleSlot<?>>> highlightSlots = new HashMap<>();

        for (int sheetIndex = 0; sheetIndex < sheets.size(); sheetIndex++) {
            var sheet = sheets.get(sheetIndex);
            for (var rule : sheet.candidatesFor(element)) {
                var pseudo = rule.selector().pseudoElement();
                if (pseudo != null) {
                    if (rule.selector().matchesOriginating(element)) {
                        collectHighlight(highlightSlots, pseudo.argument(), rule, sheet, sheetIndex);
                    }
                    continue;
                }
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

        if (highlightSlots.isEmpty()) {
            highlightsByElement.remove(element);
        } else {
            Map<String, HighlightStyle> resolved = new HashMap<>();
            highlightSlots.forEach((name, slots) -> {
                Map<StyleProperty<?>, Object> values = new HashMap<>();
                slots.forEach((property, slot) -> values.put(property, slot.value()));
                resolved.put(name, new HighlightStyle(values));
            });
            highlightsByElement.put(element, resolved);
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
     * Folds one matched {@code ::highlight(name)} rule into the per-name winner map.
     *
     * <p>Resolves by ordinary {@link StyleSlot#compare} rather than by a bespoke rule, so a highlight
     * obeys origin, specificity and source order exactly as any other declaration does — a theme can
     * override the user-agent sheet's {@code ::highlight()} colours the same way it overrides anything
     * else.</p>
     *
     * <p><b>Declarations outside the allowed set are dropped with a warning.</b> CSS restricts highlight
     * pseudo-elements to properties that cannot affect layout, and the restriction is the feature: a
     * {@code font-size} here would reflow the text as you typed in a search box. Silently ignoring it
     * would leave an author with a rule that looks right and does nothing.</p>
     */
    private void collectHighlight(Map<String, Map<StyleProperty<?>, StyleSlot<?>>> out, String name,
                                  StyleRule rule, StyleSheet sheet, int sheetIndex) {
        int specificity = rule.selector().specificity();
        var decls = rule.declarations();
        for (int i = 0; i < decls.size(); i++) {
            var decl = decls.get(i);
            if (!HighlightStyle.ALLOWED.contains(decl.property())) {
                // Two different failures, deliberately worded differently: one is the author asking for
                // something CSS itself forbids, the other is the author asking for something CSS allows
                // and we cannot draw yet. Collapsing them would tell the first author to go and check a
                // spec that agrees with them.
                if (HighlightStyle.NOT_YET_PAINTABLE.contains(decl.property())) {
                    CrystalGuiCore.LOGGER.warn(
                            "'{}' is valid on ::highlight({}) per CSS but is NOT IMPLEMENTED here yet, so it"
                                    + " was ignored. It needs per-range geometry from the text layout, which"
                                    + " CgStyleSpan cannot express. Recolour or underline the range instead.",
                            decl.property().name, name);
                } else {
                    CrystalGuiCore.LOGGER.warn(
                            "'{}' is not allowed on ::highlight({}) and was ignored. Highlight"
                                    + " pseudo-elements accept only properties that cannot affect layout —"
                                    + " CSS Pseudo-Elements 4 — because a highlight must never reflow the text"
                                    + " it highlights.",
                            decl.property().name, name);
                }
                continue;
            }
            var origin = decl.important() ? StyleOrigin.IMPORTANT : sheet.getOrigin();
            long sourceOrder = (long) sheetIndex * SHEET_ORDER_STRIDE
                    + (long) rule.sourceOrder() * DECLARATION_ORDER_MULTIPLIER + i;
            var slot = toSlot(decl, origin, specificity, sourceOrder);
            if (slot == null) continue;
            var forName = out.computeIfAbsent(name, ignored -> new HashMap<>());
            var existing = forName.get(decl.property());
            if (existing == null || existing.compareTo(slot) < 0) {
                forName.put(decl.property(), slot);
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
