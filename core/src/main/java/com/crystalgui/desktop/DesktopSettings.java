package com.crystalgui.desktop;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.settings.Setting;
import com.crystalgui.core.settings.Settings;
import com.crystalgui.core.settings.SettingsCodec;
import com.crystalgui.core.settings.SettingsLayer;
import com.crystalgui.core.signal.Signal;
import com.crystalgraphics.storage.config.CgConfigStorage;
import com.crystalgui.desktop.taskbar.TaskbarCombine;

import javax.annotation.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * The desktop's own settings, kept in its config store's {@code desktop-settings.json}: how the shell behaves,
 * as distinct from the arrangement {@link DesktopSession} records. A typed, saved view over the desktop's own
 * {@code Desktop.settings()} store, so anything inside the desktop resolves them through the settings tree.
 *
 * <pre>{@code
 * DesktopSettings settings = desktop.desktopSettings();
 * settings.setTaskbarCombine(TaskbarCombine.NEVER);    // saved at once
 * settings.onDidChange.connect(this::redraw);
 * Settings store = settings.store();                   // for a settings page to bind its rows to
 * }</pre>
 *
 * <p>Declared {@link Setting}s, so a settings page can list them without knowing them. Read before the
 * desktop has storage, each answers its default; nothing is saved until {@link Desktop#useStorage} or
 * {@link Desktop#persistTo} gives it somewhere to write.</p>
 */
public final class DesktopSettings {

    public static final String FILE = "desktop-settings.json";

    /** Whether an application's windows share one taskbar button. @see TaskbarCombine */
    public static final Setting<String> TASKBAR_COMBINE = Setting.select("taskbar.combine",
            "Combine taskbar buttons",
            List.of(TaskbarCombine.ALWAYS.id(), TaskbarCombine.WHEN_FULL.id(), TaskbarCombine.NEVER.id()),
            TaskbarCombine.ALWAYS.id());

    /** A setting changed, by the user or by a load. */
    public final Signal.Action onDidChange = new Signal.Action();

    private final Settings store;
    @Nullable
    private CgConfigStorage storage;
    /** True while a load replaces the user layer, which is not a change to write back. */
    private boolean loading;

    DesktopSettings(Settings store) {
        this.store = store;
        store.onChanged.connect(change -> {
            if (!loading && change.layer() == SettingsLayer.USER) save();
            onDidChange.emit();
        });
    }

    /** The live store, for a settings page. */
    public Settings store() {
        return store;
    }

    /** Whether an application's windows share one button: combined unless set otherwise. */
    public TaskbarCombine taskbarCombine() {
        TaskbarCombine mode = TaskbarCombine.byId(store.get(TASKBAR_COMBINE));
        return mode == null ? TaskbarCombine.ALWAYS : mode;
    }

    public void setTaskbarCombine(TaskbarCombine mode) {
        Objects.requireNonNull(mode, "mode");
        store.set(SettingsLayer.USER, TASKBAR_COMBINE, mode.id());
    }

    /** Loads the settings from {@code config} and saves every later change there. */
    void useStorage(CgConfigStorage config) {
        if (config == storage) return;
        storage = config;
        loading = true;
        try {
            store.replaceLayer(SettingsLayer.USER, SettingsCodec.fromJson(config.read(FILE)).asMap());
        } finally {
            loading = false;
        }
    }

    private void save() {
        CgConfigStorage target = storage;
        if (target == null || !target.isWritable()) return;
        try {
            target.write(FILE, SettingsCodec.toJson(store.layer(SettingsLayer.USER)));
        } catch (RuntimeException failed) {
            CrystalGuiCore.LOGGER.warn("[cgui] the desktop's settings could not be saved: {}", failed.getMessage());
        }
    }
}
