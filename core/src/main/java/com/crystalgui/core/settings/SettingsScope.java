package com.crystalgui.core.settings;

import javax.annotation.Nullable;

/**
 * Something that owns {@link Settings} and may sit inside something else that does.
 *
 * <h3>VS Code resolves scope by URI; we resolve it by the tree</h3>
 * <p>VS Code's {@code getValue(key, { resource })} selects folder-level overrides by file path, because
 * the configuration service has no structure to walk — the same reason its keybindings need hand-
 * maintained {@code when} clauses. {@code Keymap} already made the argument for the alternative:</p>
 *
 * <blockquote>Scope is the tree, not a condition language. […] a binding is simply attached to an element
 * and is live whenever focus is inside that element's subtree.</blockquote>
 *
 * <p>So reading walks <b>outward</b> to the nearest owner that defines the key. A panel's settings beat
 * the window's; the root holds the application's. That is folder-over-workspace precedence obtained
 * structurally, and it costs this one interface.</p>
 *
 * <h3>The two axes are independent, and conflating them is the trap</h3>
 * <p>{@link SettingsLayer} is <em>who said so</em> — default, user, workspace, document. This is
 * <em>where it was said</em>. A document's own {@code Settings} can hold a {@code USER}-layer value, and
 * an element three levels up can hold a {@code DOCUMENT}-layer one; neither is a contradiction. Walking
 * outward asks each owner for its winner, and the first owner with any answer wins outright — an inner
 * scope's low layer beats an outer scope's high one, because otherwise "override this here" would be
 * unexpressible.</p>
 */
public interface SettingsScope {

    /** This scope's own values. Never null — an owner with nothing set still owns an empty store. */
    Settings settings();

    /** The scope enclosing this one, or null at the root. */
    @Nullable
    default SettingsScope settingsParent() {
        return null;
    }

    /**
     * The nearest defined value for {@code setting}, walking outward, falling back to its default.
     *
     * <p>Cycle-guarded by a depth cap rather than a visited set: a settings scope chain that loops is a
     * bug in whoever built the tree, and the cheap defence keeps a malformed one from hanging the frame
     * instead of merely being wrong. {@code UITreeTraversal} takes the same view.</p>
     */
    default <T> T resolve(Setting<T> setting) {
        String raw = resolveRaw(setting.getId());
        return setting.read(raw);
    }

    /** The nearest raw value, or null when no scope in the chain defines it. */
    @Nullable
    default String resolveRaw(String key) {
        SettingsScope scope = this;
        for (int depth = 0; scope != null && depth < 64; depth++) {
            String held = scope.settings().raw(key);
            if (held != null) return held;
            scope = scope.settingsParent();
        }
        return null;
    }

    /** The nearest scope that defines {@code key}, or null. What a "reveal where this came from" needs. */
    @Nullable
    default SettingsScope scopeDefining(String key) {
        SettingsScope scope = this;
        for (int depth = 0; scope != null && depth < 64; depth++) {
            if (scope.settings().raw(key) != null) return scope;
            scope = scope.settingsParent();
        }
        return null;
    }
}
