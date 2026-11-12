package com.crystalgui.core.settings;

import com.crystalgraphics.storage.config.CgConfigStorage;
import com.crystalgui.core.CrystalGuiCore;
import javax.annotation.Nullable;

/**
 * Where {@link Preferences} live on disk: {@code preferences.json} in the desktop's config storage, written by the
 * shell that attached it.
 *
 * <pre>{@code
 * // Once, by the host that owns the installation; the file is read and every later change is written back
 * PreferencesStore.attach(config);
 * }</pre>
 *
 * <p>Process-wide, like the preferences: the first storage attached wins. Easy to get wrong: attaching publishes the
 * file's contents over whatever was set before, so attach before anything writes a preference.</p>
 */
public final class PreferencesStore {

    public static final String FILE = "preferences.json";

    @Nullable
    private static CgConfigStorage storage;

    private PreferencesStore() {
    }

    /** Reads the file and persists every change from now on. A second call, from another desktop, does nothing. */
    public static synchronized void attach(@Nullable CgConfigStorage target) {
        if (storage != null || target == null) return;
        storage = target;
        SettingsModel loaded = SettingsCodec.fromJson(target.read(FILE));
        Preferences.update(p -> p.withAll(loaded.asMap()));
    }

    /** Writes {@code preferences} if a storage is attached: called by {@link Preferences#update} after each change. */
    static void save(Preferences preferences) {
        CgConfigStorage target = storage;
        if (target == null || !target.isWritable()) return;
        SettingsModel model = new SettingsModel();
        model.replaceAll(preferences.asMap());
        try {
            target.write(FILE, SettingsCodec.toJson(model));
        } catch (RuntimeException unwritable) {
            CrystalGuiCore.LOGGER.warn("[cgui] preferences were not saved: {}", unwritable.getMessage());
        }
    }

    /** Detaches the storage. For tests, which share one process. */
    public static synchronized void detachForTesting() {
        storage = null;
    }
}
