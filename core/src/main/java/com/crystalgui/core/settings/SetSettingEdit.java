package com.crystalgui.core.settings;

import com.crystalgui.core.undo.Edit;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Changes one setting at one layer, undoably.
 *
 * <p><b>Ours, not VS Code's</b> — its configuration service has no undo, because a preference change is
 * not an edit to anything a user is working on. That is true of four of our five layers and false of the
 * fifth: {@link SettingsLayer#DOCUMENT} is document state by the project's own test — a reload must give
 * it back — so a write there goes on the stack exactly the way a node field's does.</p>
 *
 * <h3>The same shape as {@code SetNodeFieldEdit}</h3>
 * <p>Data rather than closures, a "before" read out of the store rather than assumed, and a
 * {@link #mergeWith} that coalesces consecutive writes to the same key so dragging a slider in a
 * preferences panel is one undo step and not one per frame.</p>
 *
 * <h3>Undo must restore ABSENT, not empty</h3>
 * <p>An absent key means "whatever the declaration's default is"; {@code ""} means "explicitly this".
 * Undoing a first-ever write back to {@code ""} would leave the document <em>pinning</em> a default it
 * had previously left open, so a later build that changes that default could never reach the graph — and
 * nothing about the stored value would reveal that it was never a decision. {@link Settings#setRaw}
 * treats null as removal, and this passes null through unchanged.</p>
 */
public record SetSettingEdit(Settings settings, SettingsLayer layer, String key,
                             @Nullable String before, @Nullable String after) implements Edit {

    @Override
    public void apply() {
        settings.setRaw(layer, key, after);
    }

    @Override
    public void undo() {
        settings.setRaw(layer, key, before);
    }

    /**
     * The value this edit records, read from the store rather than assumed.
     *
     * <p>Use this to construct one: the "before" has to be what is genuinely held, <b>including
     * absent</b>, or undo writes a value the store never had.</p>
     */
    public static SetSettingEdit of(Settings settings, SettingsLayer layer, String key,
                                    @Nullable String newValue) {
        return new SetSettingEdit(settings, layer, key, settings.layer(layer).get(key), newValue);
    }

    /** As {@link #of}, typed. A null {@code newValue} is a reset. */
    public static <T> SetSettingEdit of(Settings settings, SettingsLayer layer, Setting<T> setting,
                                        @Nullable T newValue) {
        return of(settings, layer, setting.getId(), newValue == null ? null : setting.write(newValue));
    }

    /** Whether this would actually change anything — a caller must not push a no-op onto the stack. */
    public boolean changesAnything() {
        return !Objects.equals(before, after);
    }

    @Override
    @Nullable
    public Edit mergeWith(Edit next) {
        if (!(next instanceof SetSettingEdit later)) return null;
        if (layer != later.layer || !key.equals(later.key)) return null;
        // Keeps THIS edit's before and the later one's after, so undoing the merged run lands where it
        // started rather than in the middle of it.
        return new SetSettingEdit(settings, layer, key, before, later.after);
    }

    @Override
    public String label() {
        return "set " + key;
    }
}
