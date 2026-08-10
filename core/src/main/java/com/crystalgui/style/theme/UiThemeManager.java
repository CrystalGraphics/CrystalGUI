package com.crystalgui.style.theme;

import com.crystalgraphics.util.io.CgIO;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.style.StyleEngine;
import com.crystalgui.style.sheet.DeclarationParser;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The owner the sheet stack never had — the one object that answers "what sheets does a themed
 * window run, in what order", and the only sanctioned caller of the swap primitives.
 *
 * <h3>The canonical stack</h3>
 * <p>{@link StyleSheet#DEFAULT} → {@code themeSheet} → {@code schemeSheet} → the app's own sheets
 * ({@code plan_styling.md} §3.3). The two sheets this class owns are <b>stable instances whose
 * rules are swapped in place</b> ({@link StyleSheet#refillFrom}), never removed and re-added — the
 * sheet <em>list</em> of an installed engine does not change across a swap, which is what keeps
 * "re-adding appends at highest priority" out of the picture by construction.</p>
 *
 * <h3>What a swap is</h3>
 * <p>Variable tables of the inheritance chain (root-first) and the active scheme merge, resolve to
 * a fixed point once ({@link DeclarationParser#resolveTable}), and bind through
 * {@link StyleSheetRegistry#bindVariables} — which re-substitutes every registered sheet in place,
 * no I/O. The chain's override <em>rules</em> re-parse against that same table into
 * {@code themeSheet} (one parse over the concatenated chain sources, so a child's equal-specificity
 * rule beats its parent's by ordinary source order). Then every live window re-matches, once.</p>
 *
 * <p>Process-global on purpose — sheets are shared instances, so per-window divergence needs
 * per-window copies; the plan defers that until the in-game ore-dialogs-beside-crystal-dark case
 * actually lands.</p>
 */
public final class UiThemeManager {

    private static final UiThemeManager INSTANCE = new UiThemeManager();

    public static UiThemeManager getInstance() {
        return INSTANCE;
    }

    /** Fired after a successful theme or scheme change, once every live window is invalidated —
     * what a Preferences page listens to for its dropdown state. */
    public final Signal.Action onChanged = new Signal.Action();

    /** The active theme's override rules — stack slot 4. Stable identity; contents swap. */
    private final StyleSheet themeSheet = StyleSheet.parse("", Map.of());
    /** The active editor scheme's rules — stack slot 5. Stable identity; contents swap. */
    private final StyleSheet schemeSheet = StyleSheet.parse("", Map.of());

    @Nullable
    private UiTheme activeTheme;
    @Nullable
    private UiTheme activeScheme;

    private UiThemeManager() {
    }

    @Nullable
    public String activeThemeId() {
        return activeTheme == null ? null : activeTheme.id();
    }

    @Nullable
    public String activeSchemeId() {
        return activeScheme == null ? null : activeScheme.id();
    }

    public StyleSheet themeSheet() {
        return themeSheet;
    }

    public StyleSheet schemeSheet() {
        return schemeSheet;
    }

    /**
     * Adds the canonical stack to {@code engine} — idempotent, so a host that already added
     * {@link StyleSheet#DEFAULT} itself is not double-registered. App sheets go on after this,
     * where the caller adds them as before.
     */
    public void installInto(StyleEngine engine) {
        List<StyleSheet> present = engine.getSheets();
        if (!present.contains(StyleSheet.DEFAULT)) engine.addStylesheet(StyleSheet.DEFAULT);
        if (!present.contains(themeSheet)) engine.addStylesheet(themeSheet);
        if (!present.contains(schemeSheet)) engine.addStylesheet(schemeSheet);
    }

    /**
     * Activates a registered theme. {@code null} deactivates (back to the unthemed base). False —
     * with the reason logged, and the previous theme left standing — for an unknown id or an id
     * that names a scheme.
     */
    public boolean setTheme(@Nullable String id) {
        // Same id, same registered object: a no-op, deliberately. Settings re-apply wholesale on
        // every change, so without this every unrelated toggle would re-substitute every sheet.
        if (Objects.equals(id, activeThemeId())
                && (id == null || ThemeRegistry.get(id) == activeTheme)) {
            return true;
        }
        if (id == null) {
            activeTheme = null;
            apply();
            return true;
        }
        UiTheme theme = ThemeRegistry.get(id);
        if (theme == null || theme.role() != UiTheme.Role.THEME) {
            CrystalGuiCore.LOGGER.warn("UiThemeManager: no registered theme '{}' — keeping '{}'",
                    id, activeThemeId());
            return false;
        }
        activeTheme = theme;
        apply();
        return true;
    }

    /** As {@link #setTheme}, for the editor colour scheme — the independent second axis. */
    public boolean setScheme(@Nullable String id) {
        if (Objects.equals(id, activeSchemeId())
                && (id == null || ThemeRegistry.get(id) == activeScheme)) {
            return true;
        }
        if (id == null) {
            activeScheme = null;
            apply();
            return true;
        }
        UiTheme scheme = ThemeRegistry.get(id);
        if (scheme == null || scheme.role() != UiTheme.Role.SCHEME) {
            CrystalGuiCore.LOGGER.warn("UiThemeManager: no registered scheme '{}' — keeping '{}'",
                    id, activeSchemeId());
            return false;
        }
        activeScheme = scheme;
        apply();
        return true;
    }

    /** Deactivates both axes and unbinds the variable table — restores the pristine sheets. */
    public void resetForTesting() {
        activeTheme = null;
        activeScheme = null;
        apply();
    }

    private void apply() {
        List<UiTheme> chain = inheritanceChain(activeTheme);

        // Merge order (later wins): base ← root ancestor ← … ← theme ← scheme. plan_styling.md
        // §3.3; the user-override layer slots in after the scheme when it lands. With neither axis
        // active the table stays EMPTY — pristine sheets, not a half-bound base.
        LinkedHashMap<String, String> merged = new LinkedHashMap<>();
        if (!chain.isEmpty() || activeScheme != null) merged.putAll(baseTable());
        for (UiTheme link : chain) merged.putAll(link.variables());
        if (activeScheme != null) merged.putAll(activeScheme.variables());

        StyleSheetRegistry.bindVariables(DeclarationParser.resolveTable(merged));

        // One parse over the concatenated chain, root-first — cross-link source order falls out of
        // the single parse instead of needing rules renumbered by hand.
        StringBuilder themeCss = new StringBuilder();
        for (UiTheme link : chain) themeCss.append(link.source()).append('\n');
        themeSheet.refillFrom(StyleSheet.parse(themeCss.toString()));
        schemeSheet.refillFrom(StyleSheet.parse(activeScheme == null ? "" : activeScheme.source()));

        StyleEngine.restyleAllWindows();
        onChanged.emit();
    }

    /** The component-token derivation table, {@code themes/base.css} — loaded once, lazily. Sits
     * UNDER every theme in the merge, which is what makes a theme a delta rather than a
     * restatement. Missing (a stripped-down consumer) degrades to empty: every engine sheet
     * carries fallbacks, so nothing breaks — themes just lose the derived defaults. */
    private static Map<String, String> baseTable;

    private static Map<String, String> baseTable() {
        if (baseTable == null) {
            String css = CgIO.loadSource("crystalgui:ui/themes/base.css");
            baseTable = css == null ? Map.of() : Map.copyOf(StyleSheet.variablesOf(css));
            if (css == null) {
                CrystalGuiCore.LOGGER.warn("UiThemeManager: themes/base.css not found — component-token derivations unavailable");
            }
        }
        return baseTable;
    }

    /**
     * Root-first {@code @extends} chain. A missing parent warns and truncates there (the child
     * still applies); a cycle warns and stops at the repeat. Cross-role extension is refused the
     * same way — a theme extending a scheme is a wiring mistake, not a feature.
     */
    private static List<UiTheme> inheritanceChain(@Nullable UiTheme leaf) {
        if (leaf == null) return List.of();
        ArrayDeque<UiTheme> chain = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        UiTheme current = leaf;
        while (current != null) {
            if (!seen.add(current.id())) {
                CrystalGuiCore.LOGGER.warn("UiThemeManager: '@extends' cycle at '{}' — stopping the chain there",
                        current.id());
                break;
            }
            chain.addFirst(current);
            String parentId = current.parentId();
            if (parentId == null) break;
            UiTheme parent = ThemeRegistry.get(parentId);
            if (parent == null) {
                CrystalGuiCore.LOGGER.warn("UiThemeManager: '{}' extends unregistered '{}' — treating it as a root",
                        current.id(), parentId);
                break;
            }
            if (parent.role() != leaf.role()) {
                CrystalGuiCore.LOGGER.warn("UiThemeManager: '{}' extends '{}' across roles ({} vs {}) — refusing the parent",
                        current.id(), parentId, leaf.role(), parent.role());
                break;
            }
            current = parent;
        }
        return new ArrayList<>(chain);
    }
}
