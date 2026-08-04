package com.crystalgui.core.settings;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One layer's raw contents — the values a single source has to say, and nothing about precedence.
 *
 * <p>Ported from VS Code's {@code ConfigurationModel}
 * ({@code src/vs/platform/configuration/common/configurationModels.ts}), MIT.</p>
 *
 * <h3>Flat and text-valued</h3>
 * <p>VS Code's model is a nested object tree with a {@code getValue(section)} that walks dotted paths.
 * Ours is a flat {@code Map<String, String>} keyed by the whole dotted id, because the one thing the tree
 * buys — reading {@code "editor"} and getting every editor setting as an object — is not something
 * anything here needs, and the flat form is what makes a layer <b>directly serialisable and
 * content-hashable</b> with no traversal.</p>
 *
 * <p>Insertion-ordered for that last reason: the encoded form has to be byte-stable twice over or a
 * content hash means nothing. Same rule {@code GraphDocument} follows for its nodes.</p>
 *
 * <h3>Absent is not empty</h3>
 * <p>Removing a key and storing {@code ""} are different states and must stay so. An absent key means
 * "whatever the declaration's default is" and has to round-trip as absent, or a file written today
 * <em>pins</em> a default that a later build changes — and nothing about the stored value would reveal
 * that it was never a decision.</p>
 */
public final class SettingsModel {

    private final Map<String, String> values = new LinkedHashMap<>();

    @Nullable
    public String get(String key) {
        return values.get(key);
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    /**
     * Writes a key, or removes it when {@code value} is null.
     *
     * @return whether anything changed — a caller must not announce a write that did nothing
     */
    public boolean set(String key, @Nullable String value) {
        String previous = value == null ? values.remove(key) : values.put(key, value);
        return !Objects.equals(previous, value);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public Set<String> keys() {
        return Collections.unmodifiableSet(values.keySet());
    }

    /** The contents, for a codec. Unmodifiable — writes go through {@link #set}. */
    public Map<String, String> asMap() {
        return Collections.unmodifiableMap(values);
    }

    /** Replaces everything — for a codec loading a layer, not for an editor changing one value. */
    public void replaceAll(@Nullable Map<String, String> replacement) {
        values.clear();
        if (replacement != null) values.putAll(replacement);
    }

    public void clear() {
        values.clear();
    }
}
