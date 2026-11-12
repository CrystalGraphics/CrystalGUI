package com.crystalgui.style.theme;

import com.crystalgraphics.util.io.CgIO;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.async.UiSequence;
import com.crystalgui.style.StyleContext;
import com.crystalgui.style.StyleEngine;
import com.crystalgui.style.sheet.DeclarationParser;
import com.crystalgui.style.sheet.StyleSheet;
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
 * The active theme, the editor scheme and the user's token overrides, and the one place that turns them into a
 * {@link StyleContext}.
 *
 * <pre>{@code
 * UiThemeManager themes = UiThemeManager.getInstance();
 * themes.installInto(document.styles());   // once per document: user-agent, theme, scheme
 * themes.setTheme("crystal-light");        // publishes the next generation; each document adopts it at its next frame
 * }</pre>
 *
 * <p>The stack is {@link StyleSheet#DEFAULT} → {@link StyleContext#THEME} → {@link StyleContext#SCHEME} → the
 * document's own sheets. A switch merges the variable tables of the inheritance chain (root-first) and the scheme,
 * resolves them to a fixed point once, parses the chain's override rules against that table, and publishes the
 * result; nothing is rewritten in place and no document is told to restyle.</p>
 *
 * <p>Easy to get wrong: the sheets' own text is unthemed. A sheet read outside a document's engine shows the
 * fallbacks of its {@code var()}s; resolve it through {@link StyleContext#resolve}.</p>
 */
public final class UiThemeManager {

    private static final UiThemeManager INSTANCE = new UiThemeManager();

    public static UiThemeManager getInstance() {
        return INSTANCE;
    }

    @Nullable
    private UiTheme activeTheme;
    @Nullable
    private UiTheme activeScheme;

    /**
     * Per-token overrides applied <b>over</b> whatever theme is active — VS Code's
     * {@code workbench.colorCustomizations}, and the reason its users never have to fork a theme to
     * change one colour.
     *
     * <p>Last in the merge (plan/style-overhaul.md §3.3), so it survives a theme swap and re-applies on top
     * of the new one: "I always want my accent pink" is a statement about the user, not about the
     * theme they happen to be running. Keys are token names with or without the {@code --} prefix —
     * a settings file written by hand should not fail on a detail that carries no information.</p>
     */
    private final Map<String, String> overrides = new LinkedHashMap<>();

    private UiThemeManager() {
    }

    /** The theme of the generation published last: what a document reads, which a switch still in flight has not reached. */
    @Nullable
    public String activeThemeId() {
        return StyleContext.current().themeId();
    }

    /** As {@link #activeThemeId()}, for the editor scheme. */
    @Nullable
    public String activeSchemeId() {
        return StyleContext.current().schemeId();
    }

    /**
     * Adds the canonical stack to {@code engine} — idempotent, so a host that already added
     * {@link StyleSheet#DEFAULT} itself is not double-registered. App sheets go on after this,
     * where the caller adds them as before.
     */
    public void installInto(StyleEngine engine) {
        List<StyleSheet> present = engine.getSheets();
        boolean added = !present.contains(StyleContext.THEME);
        if (!present.contains(StyleSheet.DEFAULT)) engine.addStylesheet(StyleSheet.DEFAULT);
        if (added) engine.addStylesheet(StyleContext.THEME);
        if (!present.contains(StyleContext.SCHEME)) engine.addStylesheet(StyleContext.SCHEME);
        if (added) {
            CrystalGuiCore.LOGGER.info("theme stack installed into a style engine (sheets now {})",
                    engine.getSheets().size());
        }
    }

    /**
     * Activates a registered theme; {@code null} deactivates (back to the unthemed base). False, with the reason
     * logged and the previous theme left standing, for an unknown id or an id that names a scheme.
     *
     * <p>Called from a document, the next generation is built on the services sequence and the answer is only
     * that the switch was accepted; every document, the caller's included, shows it at its next frame. Called from
     * any other thread it is built before this returns.</p>
     */
    public boolean setTheme(@Nullable String id) {
        UiTheme theme = null;
        if (id != null) {
            theme = ThemeRegistry.get(id);
            if (theme == null || theme.role() != UiTheme.Role.THEME) {
                CrystalGuiCore.LOGGER.warn("UiThemeManager: no registered theme '{}' — keeping '{}'",
                        id, activeThemeId());
                return false;
            }
        }
        UiTheme wanted = theme;
        build(() -> {
            // Same id, same registered object: a no-op, deliberately. Settings re-apply wholesale on every
            // change, so without this every unrelated toggle would rebuild a generation.
            if (wanted == activeTheme) return;
            activeTheme = wanted;
            apply();
        });
        return true;
    }

    /** As {@link #setTheme}, for the editor colour scheme — the independent second axis. */
    public boolean setScheme(@Nullable String id) {
        UiTheme scheme = null;
        if (id != null) {
            scheme = ThemeRegistry.get(id);
            if (scheme == null || scheme.role() != UiTheme.Role.SCHEME) {
                CrystalGuiCore.LOGGER.warn("UiThemeManager: no registered scheme '{}' — keeping '{}'",
                        id, activeSchemeId());
                return false;
            }
        }
        UiTheme wanted = scheme;
        build(() -> {
            if (wanted == activeScheme) return;
            activeScheme = wanted;
            apply();
        });
        return true;
    }

    /**
     * Runs a change to the state this class owns where it cannot race: on the services sequence when asked from a
     * document's, so a document never builds a generation for the others, and on the caller's own thread otherwise.
     */
    private void build(Runnable change) {
        UiSequence from = UiSequence.current();
        if (from == null || from == UiSequence.services()) {
            synchronized (this) {
                change.run();
            }
            return;
        }
        UiSequence.services().execute(() -> {
            synchronized (this) {
                change.run();
            }
        });
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
        build(() -> {
            if (normalised.equals(overrides)) return;      // same guard as setTheme, same reason
            overrides.clear();
            overrides.putAll(normalised);
            apply();
        });
    }

    /** The overrides in force, token names normalised to their {@code --} form. */
    public synchronized Map<String, String> overrides() {
        return Map.copyOf(overrides);
    }

    /**
     * Deactivates both axes, drops the overrides and publishes the pristine generation. Nothing calls this by
     * itself: a theme is process-wide, so a test that applies one changes what every later test in the JVM
     * resolves {@code var(--x, fallback)} to, and a fixture that asserts on style resets it.
     */
    public void resetForTesting() {
        build(() -> {
            activeTheme = null;
            activeScheme = null;
            overrides.clear();
            apply();
        });
    }

    /**
     * Re-reads every theme file from disk and re-applies the active pair — <b>theme hot reload</b>,
     * and the call a host pairs with {@code StyleEngine.reloadStylesheets()}.
     *
     * <p>Both halves are needed and neither covers the other. A stylesheet reload re-reads the sheets
     * and re-substitutes them against the variables of the generation published <em>when the theme was last
     * applied</em> — so an edited token is invisible. This re-reads the
     * theme, rebinds the table and restyles; the sheets keep the rules they already had.</p>
     *
     * <p>Three things go stale here and only one is obvious. The registry entries are replaced by
     * {@link ThemeRegistry#reloadAll()}, so this class's own {@code activeTheme} reference points at
     * the object that was just superseded and has to be re-resolved by id. {@code baseTable} is
     * {@code themes/base.css} read once and cached forever, so a component-token edit would never
     * appear. And a file that stopped parsing keeps its previous entry, which means a re-resolve can
     * legitimately answer with the old object — that is the intended degrade, not a failure, and is
     * why a null answer here restores rather than deactivates.</p>
     *
     * @return how many theme files were re-read and accepted
     */
    public int reloadFromDisk() {
        int reloaded = ThemeRegistry.reloadAll();
        build(() -> {
            // NEVER deactivate on a null. A theme whose file was deleted or is momentarily unreadable keeps
            // its registry entry, so null here means the id itself is gone -- and dropping to the unthemed
            // base mid-edit blanks the look and reads as the reload being broken.
            if (activeTheme != null) {
                UiTheme reread = ThemeRegistry.get(activeTheme.id());
                if (reread != null) activeTheme = reread;
            }
            if (activeScheme != null) {
                UiTheme reread = ThemeRegistry.get(activeScheme.id());
                if (reread != null) activeScheme = reread;
            }
            baseTable = null;
            apply();
        });
        return reloaded;
    }

    private void apply() {
        List<UiTheme> chain = inheritanceChain(activeTheme);

        // Merge order (later wins): base ← root ancestor ← … ← theme ← scheme ← the user's own
        // overrides (plan/style-overhaul.md §3.3). With NOTHING active the table stays empty — pristine
        // sheets on their fallbacks, not a half-bound base.
        LinkedHashMap<String, String> merged = new LinkedHashMap<>();
        boolean anything = !chain.isEmpty() || activeScheme != null || !overrides.isEmpty();
        if (anything) merged.putAll(baseTable());
        for (UiTheme link : chain) merged.putAll(link.variables());
        if (activeScheme != null) merged.putAll(activeScheme.variables());
        merged.putAll(overrides);

        Map<String, String> table = DeclarationParser.resolveTable(merged);

        // One parse over the concatenated chain, root-first — cross-link source order falls out of
        // the single parse instead of needing rules renumbered by hand.
        StringBuilder themeCss = new StringBuilder();
        for (UiTheme link : chain) themeCss.append(link.source()).append('\n');
        StyleSheet theme = StyleSheet.parse(themeCss.toString(), table);
        StyleSheet scheme = StyleSheet.parse(activeScheme == null ? "" : activeScheme.source(), table);
        // The file-type icons are DRAWINGS, not tinted glyphs: a filled multi-colour shape cannot be recoloured
        // for the opposite ground, so the set follows the theme's declared kind. Unthemed keeps the dark set.
        boolean light = activeTheme != null && activeTheme.kind() == UiTheme.Kind.LIGHT;

        String themeId = activeTheme == null ? null : activeTheme.id();
        String schemeId = activeScheme == null ? null : activeScheme.id();
        StyleContext next = new StyleContext(table, theme, scheme, themeId, schemeId, light).prepared();
        StyleContext.PUBLISHED.update(old -> next);

        // ONE LINE PER SWAP: a swap has several parts that can each be fine while the screen does not change
        // (the theme resolved to nothing, the table came out empty, a sheet parsed to no rules), and from
        // outside they are indistinguishable. Seen across the loader seam, where sheets resolve through the
        // resource manager and a swap that works in the harness can do nothing.
        CrystalGuiCore.LOGGER.info("theme swap: theme={} scheme={} vars={} themeRules={} schemeRules={}",
                themeId, schemeId, merged.size(),
                theme.getRules().size(), scheme.getRules().size());

    }

    /** The component-token derivation table, {@code themes/base.css} — loaded once, lazily. Sits
     * UNDER every theme in the merge, which is what makes a theme a delta rather than a
     * restatement. Missing (a stripped-down consumer) degrades to empty: every engine sheet
     * carries fallbacks, so nothing breaks — themes just lose the derived defaults. */
    private static volatile Map<String, String> baseTable;

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
