package com.crystalgui.core.settings;

import com.crystalgui.core.signal.Signal;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The layered value store one scope owns — read the winner, write a layer, ask where a value came from.
 *
 * <p>Ported from VS Code's {@code Configuration} class and {@code IConfigurationService}
 * ({@code src/vs/platform/configuration/common/configurationModels.ts} and {@code configuration.ts}),
 * MIT.</p>
 *
 * <h3>Precedence is {@link SettingsLayer}'s, read top-down</h3>
 * <p>{@link #raw} walks the layers from highest to lowest and returns the first answer. That is the whole
 * resolution rule, and it lives in one loop rather than in a chain of conditionals precisely so it cannot
 * drift from the enum that declares the order.</p>
 *
 * <h3>{@link #inspect} is not garnish</h3>
 * <p>Returning only the winner makes three things impossible: offering "reset to default" (you cannot,
 * without knowing whether the value came from a layer that can be cleared), showing the modified dot
 * beside a changed setting, and answering "why is this value what it is" at all. It is the settings
 * equivalent of the question {@code ElementStyle}'s two winner maps exist to answer.</p>
 *
 * <p>Most of all it separates <b>"explicitly set to the same value as the default"</b> from <b>"never
 * set"</b>. Those look identical through {@link #get} and behave differently under reset, and a settings
 * UI that conflates them silently discards a user's deliberate choice.</p>
 */
public final class Settings {

    /** {@code (key, layer)} for every write that changed something. @see SettingsChange */
    public final Signal.Value<SettingsChange> onChanged = new Signal.Value<>();

    private final Map<SettingsLayer, SettingsModel> layers = new EnumMap<>(SettingsLayer.class);

    public Settings() {
        for (SettingsLayer layer : SettingsLayer.values()) {
            if (layer.isWritable()) layers.put(layer, new SettingsModel());
        }
    }

    /** One layer's raw contents — for a codec that loads or saves it. */
    public SettingsModel layer(SettingsLayer layer) {
        SettingsModel model = layers.get(layer);
        if (model == null) {
            throw new IllegalArgumentException(layer + " is not a storage layer; it is the declaration's"
                    + " own default. See SettingsLayer.DEFAULT.");
        }
        return model;
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    /**
     * The winning raw value for {@code key}, or null when no layer has one.
     *
     * <p>Null means "fall back to the declaration", which is why this is not the method most callers
     * want — {@link #get(Setting)} does that step for you.</p>
     */
    @Nullable
    public String raw(String key) {
        SettingsLayer[] all = SettingsLayer.values();
        for (int i = all.length - 1; i >= 0; i--) {
            SettingsModel model = layers.get(all[i]);
            if (model == null) continue;
            String held = model.get(key);
            if (held != null) return held;
        }
        return null;
    }

    /** The winning value, typed, falling back to the declaration's default. */
    public <T> T get(Setting<T> setting) {
        return setting.read(raw(setting.getId()));
    }

    /** Which layer supplies {@code setting}'s current value — {@link SettingsLayer#DEFAULT} if none. */
    public SettingsLayer sourceOf(Setting<?> setting) {
        SettingsLayer[] all = SettingsLayer.values();
        for (int i = all.length - 1; i >= 0; i--) {
            SettingsModel model = layers.get(all[i]);
            if (model != null && model.has(setting.getId())) return all[i];
        }
        return SettingsLayer.DEFAULT;
    }

    /** Whether any writable layer holds this setting at all. @see #inspect */
    public boolean isSet(Setting<?> setting) {
        return sourceOf(setting) != SettingsLayer.DEFAULT;
    }

    /**
     * Every layer's answer, not just the winner. @see Settings
     *
     * @param values one entry per layer that actually holds the key — absent layers are absent, not null
     */
    public record Inspection(String key, SettingsLayer effectiveLayer,
                             Map<SettingsLayer, String> values) {

        /** The raw value at one layer, or null if that layer says nothing. */
        @Nullable
        public String at(SettingsLayer layer) {
            return values.get(layer);
        }

        /** Whether anything above {@link SettingsLayer#DEFAULT} holds it — the modified indicator. */
        public boolean isOverridden() {
            return effectiveLayer != SettingsLayer.DEFAULT;
        }
    }

    public Inspection inspect(Setting<?> setting) {
        Map<SettingsLayer, String> found = new EnumMap<>(SettingsLayer.class);
        for (Map.Entry<SettingsLayer, SettingsModel> entry : layers.entrySet()) {
            String held = entry.getValue().get(setting.getId());
            if (held != null) found.put(entry.getKey(), held);
        }
        return new Inspection(setting.getId(), sourceOf(setting), found);
    }

    /** Every key any layer holds, so a codec or a diff can enumerate. */
    public Set<String> keys() {
        Set<String> all = new LinkedHashSet<>();
        for (SettingsModel model : layers.values()) all.addAll(model.keys());
        return all;
    }

    public boolean isEmpty() {
        for (SettingsModel model : layers.values()) {
            if (!model.isEmpty()) return false;
        }
        return true;
    }

    // ── Writing ─────────────────────────────────────────────────────────────

    /**
     * Writes {@code value} at {@code layer}, or removes it when null.
     *
     * <p><b>Removal is how you reset to the default</b>, and it is not the same as writing the default's
     * text — see {@link SettingsModel}. Refuses a layer the declaration does not permit rather than
     * writing somewhere the setting will never be read from.</p>
     *
     * @return whether anything changed
     */
    public <T> boolean set(SettingsLayer layer, Setting<T> setting, @Nullable T value) {
        return setRaw(layer, setting.getId(), value == null ? null : setting.write(value));
    }

    /** As {@link #set}, by key and text — what a codec and {@link SetSettingEdit} use. */
    public boolean setRaw(SettingsLayer layer, String key, @Nullable String value) {
        SettingsModel model = layer(layer);
        if (!model.set(key, value)) return false;
        onChanged.emit(new SettingsChange(key, layer));
        return true;
    }

    /** Removes {@code setting} from {@code layer} — reset. @see #set */
    public boolean reset(SettingsLayer layer, Setting<?> setting) {
        return setRaw(layer, setting.getId(), null);
    }

    /**
     * Replaces one whole layer — for a codec loading a document or a preferences file.
     *
     * <p>Emits once per key that differs rather than once for the load, so a listener filtering by
     * {@link SettingsChange#affects} still hears about exactly what it cares about. A single "everything
     * changed" signal would force every listener back to re-reading the world, which is the cost this
     * event type exists to avoid.</p>
     */
    public void replaceLayer(SettingsLayer layer, @Nullable Map<String, String> replacement) {
        SettingsModel model = layer(layer);
        Set<String> touched = new LinkedHashSet<>(model.keys());
        if (replacement != null) touched.addAll(replacement.keySet());

        Map<String, String> before = new java.util.LinkedHashMap<>(model.asMap());
        model.replaceAll(replacement);

        for (String key : touched) {
            if (!java.util.Objects.equals(before.get(key), model.get(key))) {
                onChanged.emit(new SettingsChange(key, layer));
            }
        }
    }
}
