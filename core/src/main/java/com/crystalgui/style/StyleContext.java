package com.crystalgui.style;

import com.crystalgui.core.async.Published;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * What every document's cascade reads that is not its own: the theme's variables, the theme's and the scheme's
 * rules, and each sheet of the user-agent and the applications bound to those variables. One immutable generation
 * of it is published; a theme switch builds the next, and a document adopts it at its next frame.
 *
 * <pre>{@code
 * // A document installs the stack once, and adopts each new generation on its own sequence
 * document.styles().addStylesheet(StyleSheet.DEFAULT);
 * document.styles().addStylesheet(StyleContext.THEME);
 * document.styles().addStylesheet(StyleContext.SCHEME);
 *
 * // Anyone, any thread: what the theme says now
 * String accent = StyleContext.current().variables().get("--accent");
 * }</pre>
 *
 * <p>A sheet a document installs is a <em>base</em>: its text, parsed once. {@link #resolve} answers the sheet the
 * cascade reads under this generation, built once and shared by every document that asks (Blink's
 * {@code StyleEngine} shares one {@code RuleSet} the same way); a sheet that names no {@code var()} is its own
 * resolution and builds nothing. {@link #THEME} and {@link #SCHEME} are slots, not sheets: they resolve to this
 * generation's rules for the theme and for the editor scheme.</p>
 *
 * <p>Easy to get wrong: a sheet's rules are never swapped in place for a theme change. Holding a resolved sheet past
 * a frame holds a superseded generation; hold the base and resolve again.</p>
 */
public final class StyleContext {

    /** The stack's slot for the active theme's own rules, installed between the user-agent sheet and the schemes. */
    public static final StyleSheet THEME = StyleSheet.parse("", Map.of());

    /** The stack's slot for the active editor scheme's rules. */
    public static final StyleSheet SCHEME = StyleSheet.parse("", Map.of());

    /** The published generations. Updated by the style service; read from any thread. */
    public static final Published<StyleContext> PUBLISHED = Published.of("style", new StyleContext(Map.of(),
            StyleSheet.parse("", Map.of()), StyleSheet.parse("", Map.of()), null, null, false));

    private final Map<String, String> variables;
    private final StyleSheet theme;
    private final StyleSheet scheme;
    @Nullable
    private final String themeId;
    @Nullable
    private final String schemeId;
    private final boolean light;
    private final Map<StyleSheet, StyleSheet> resolved = Collections.synchronizedMap(new IdentityHashMap<>());

    /**
     * @param variables the resolved tokens every sheet binds to, {@code --name} to value
     * @param theme     the rules the active theme and its ancestors add, already parsed against {@code variables}
     * @param scheme    the same for the editor scheme
     * @param light     whether the theme draws on a light ground, which decides which file-type icons are drawn
     */
    public StyleContext(Map<String, String> variables, StyleSheet theme, StyleSheet scheme,
                        @Nullable String themeId, @Nullable String schemeId, boolean light) {
        this.variables = Map.copyOf(variables);
        this.theme = theme;
        this.scheme = scheme;
        this.themeId = themeId;
        this.schemeId = schemeId;
        this.light = light;
    }

    /** The generation this thread reads: the one its frame pinned, else the latest. */
    public static StyleContext current() {
        return PUBLISHED.current();
    }

    /** The generation's number: a document that adopted this one has nothing to do until it changes. */
    public static long number() {
        return PUBLISHED.generation().number();
    }

    /** The resolved tokens. */
    public Map<String, String> variables() {
        return variables;
    }

    @Nullable
    public String themeId() {
        return themeId;
    }

    @Nullable
    public String schemeId() {
        return schemeId;
    }

    /** Whether the theme draws on a light ground. */
    public boolean light() {
        return light;
    }

    /** The sheet the cascade reads for {@code base} in this generation, built at most once for all documents. */
    public StyleSheet resolve(StyleSheet base) {
        if (base == THEME) return theme;
        if (base == SCHEME) return scheme;
        if (!base.usesVariables()) return base;
        synchronized (resolved) {
            StyleSheet have = resolved.get(base);
            // A hot reload refills a base where it stands, so a copy bound from its old text is stale.
            if (have == null || !have.sameSourceAs(base)) {
                have = base.bound(variables);
                if (have != base) have.warmValues();
                resolved.put(base, have);
            }
            return have;
        }
    }

    /**
     * Resolves the sheets every document is likely to install — the user-agent sheet and the loaded registry sheets —
     * so the builder pays for their rule indices and the documents find them ready when they adopt this generation.
     */
    public StyleContext prepared() {
        resolve(StyleSheet.DEFAULT);
        for (StyleSheet sheet : StyleSheetRegistry.loaded()) resolve(sheet);
        return this;
    }

    /** A new generation with the same content, for a change that is in the sheets' text: a hot reload. */
    public StyleContext renewed() {
        return new StyleContext(variables, theme, scheme, themeId, schemeId, light);
    }
}
