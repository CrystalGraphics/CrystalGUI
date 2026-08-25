package com.crystalgui.style.theme;

import com.crystalgraphics.util.io.CgIO;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.render.texture.asset.FileIconTheme;
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

    /**
     * Per-token overrides applied <b>over</b> whatever theme is active — VS Code's
     * {@code workbench.colorCustomizations}, and the reason its users never have to fork a theme to
     * change one colour.
     *
     * <p>Last in the merge (plan_styling.md §3.3), so it survives a theme swap and re-applies on top
     * of the new one: "I always want my accent pink" is a statement about the user, not about the
     * theme they happen to be running. Keys are token names with or without the {@code --} prefix —
     * a settings file written by hand should not fail on a detail that carries no information.</p>
     */
    private final Map<String, String> overrides = new LinkedHashMap<>();

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
        boolean addedTheme = !present.contains(themeSheet);
        if (!present.contains(StyleSheet.DEFAULT)) engine.addStylesheet(StyleSheet.DEFAULT);
        if (addedTheme) engine.addStylesheet(themeSheet);
        if (!present.contains(schemeSheet)) engine.addStylesheet(schemeSheet);
        // SAID ONCE PER ENGINE, because an engine that never had this called on it is the one state a
        // swap cannot recover from and cannot report: `apply()` would re-substitute every sheet, refill
        // the user-agent sheet and restyle every window, all correctly, and this window would still show
        // none of it -- its engine simply does not hold the theme sheet. `WorkbenchSettings.apply` only
        // calls this when the workbench HAS a window, and nothing re-runs it when one arrives.
        if (addedTheme) {
            CrystalGuiCore.LOGGER.info("theme stack installed into a style engine (sheets now {})",
                    engine.getSheets().size());
        }
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

    /**
     * Replaces the user's token overrides and re-applies. Values are raw CSS text, so
     * {@code var(--accent)} is as legal as {@code #FF00AA} — an override may point one token at
     * another, which is how "make the focus ring my accent" is said in one line.
     *
     * <p>An override naming a token nothing else defines is kept rather than refused: a theme
     * arriving later may introduce it, and dropping it here would silently lose the user's setting
     * in between. It resolves to nothing until then, which is the same degrade any undefined
     * reference gets.</p>
     */
    public void setOverrides(Map<String, String> tokenOverrides) {
        Map<String, String> normalised = new LinkedHashMap<>();
        tokenOverrides.forEach((key, value) -> {
            String name = key.startsWith("--") ? key : "--" + key;
            normalised.put(name, value);
        });
        if (normalised.equals(overrides)) return;      // same guard as setTheme, same reason
        overrides.clear();
        overrides.putAll(normalised);
        apply();
    }

    /** The overrides in force, token names normalised to their {@code --} form. */
    public Map<String, String> overrides() {
        return Map.copyOf(overrides);
    }

    /** Deactivates both axes, drops the overrides, and unbinds the table — the pristine sheets. */
    public void resetForTesting() {
        activeTheme = null;
        activeScheme = null;
        overrides.clear();
        apply();
    }

    private void apply() {
        List<UiTheme> chain = inheritanceChain(activeTheme);

        // Merge order (later wins): base ← root ancestor ← … ← theme ← scheme ← the user's own
        // overrides (plan_styling.md §3.3). With NOTHING active the table stays empty — pristine
        // sheets on their fallbacks, not a half-bound base.
        LinkedHashMap<String, String> merged = new LinkedHashMap<>();
        boolean anything = !chain.isEmpty() || activeScheme != null || !overrides.isEmpty();
        if (anything) merged.putAll(baseTable());
        for (UiTheme link : chain) merged.putAll(link.variables());
        if (activeScheme != null) merged.putAll(activeScheme.variables());
        merged.putAll(overrides);

        StyleSheetRegistry.bindVariables(DeclarationParser.resolveTable(merged));

        // The file-type icons are DRAWINGS, not tinted glyphs — JetBrains ships each one twice because a
        // filled multi-colour shape cannot be recoloured for the opposite background the way a monochrome
        // stroke can. So the icon set follows the theme's declared kind rather than any token, and this is
        // the one place that knows the kind changed. Unthemed keeps the DARK drawings, matching the ua/
        // fallbacks' own dark-first look.
        FileIconTheme.setVariant(activeTheme != null && activeTheme.kind() == UiTheme.Kind.LIGHT
                ? FileIconTheme.Variant.LIGHT
                : FileIconTheme.Variant.DARK);

        // One parse over the concatenated chain, root-first — cross-link source order falls out of
        // the single parse instead of needing rules renumbered by hand.
        StringBuilder themeCss = new StringBuilder();
        for (UiTheme link : chain) themeCss.append(link.source()).append('\n');
        themeSheet.refillFrom(StyleSheet.parse(themeCss.toString()));
        schemeSheet.refillFrom(StyleSheet.parse(activeScheme == null ? "" : activeScheme.source()));

        int restyled = StyleEngine.restyleAllWindows();

        // ONE LINE PER SWAP, AND IT IS NOT DEBUG NOISE.
        //
        // A theme swap has five things that can each be individually fine while the screen does not
        // change, and from outside they are indistinguishable: the theme resolved to nothing, the
        // variable table came out empty, the sheet re-parsed to no rules, the user-agent sheet was not
        // refilled (it is a rules-COPY of the cached one, and the refill is skipped when the cache has
        // no entry -- `StyleSheetRegistry.of` does not cache a MISS), or there was no live engine to
        // restyle. "Nothing happened" is the report for all five.
        //
        // Reported because this is the shape that only appears across the loader seam: theme switching
        // works in the harness, which resolves sheets from source directories, and does nothing in a
        // client, which resolves them through the resource manager. A swap happens when a person picks
        // one, so this costs a line an hour at worst.
        CrystalGuiCore.LOGGER.info(
                "theme swap: theme={} scheme={} vars={} themeRules={} schemeRules={} uaRules={} engines={}",
                activeThemeId(), activeSchemeId(), merged.size(),
                themeSheet.getRules().size(), schemeSheet.getRules().size(),
                StyleSheet.DEFAULT.getRules().size(), restyled);

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
