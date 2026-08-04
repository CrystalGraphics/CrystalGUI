package com.crystalgui.core.settings;

/**
 * What changed, and where.
 *
 * <p>Ported from VS Code's {@code IConfigurationChangeEvent}
 * ({@code src/vs/platform/configuration/common/configuration.ts}), MIT — specifically its
 * {@code affectsConfiguration(section)} predicate, which is the part that earns its keep.</p>
 *
 * <h3>Why a listener gets an event rather than a bare "something changed"</h3>
 * <p>The engine's usual idiom is one argument-free signal that every consumer answers by re-reading the
 * world ({@code GraphSelection.onChanged} says so explicitly). That is right when consumers are cheap and
 * few. Settings are neither: a change signal is heard by every panel in the application, and a panel that
 * rebuilds its rows because an unrelated setting moved is a panel that <b>destroys the control the user
 * is currently dragging</b> — the same failure the table header hit, and the reason
 * {@code NodeFieldBinder} has to suppress its own echo.</p>
 *
 * <p>{@link #affects} is what lets a listener answer "is this mine?" in one comparison instead.</p>
 *
 * @param key   the setting id that changed
 * @param layer the layer written to, so a listener can tell a document edit from a preference change
 */
public record SettingsChange(String key, SettingsLayer layer) {

    public boolean affects(Setting<?> setting) {
        return setting != null && setting.getId().equals(key);
    }

    /**
     * Whether the changed key is {@code section} or lives under it — {@code affects("editor")} is true
     * for {@code "editor.fontSize"}.
     *
     * <p>The dot is required rather than a bare {@code startsWith}, or {@code "editor"} would also claim
     * {@code "editorial.mode"}.</p>
     */
    public boolean affects(String section) {
        return key.equals(section) || key.startsWith(section + ".");
    }
}
