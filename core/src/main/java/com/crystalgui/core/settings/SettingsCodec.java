package com.crystalgui.core.settings;

import com.crystalgui.serialization.Codec;
import com.crystalgui.serialization.DynamicOps;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One {@link SettingsModel} as a flat string map — what a document or a preferences file stores.
 *
 * <h3>A layer at a time, never the whole stack</h3>
 * <p>Encoding a {@link Settings} wholesale would be wrong in a way that is easy to miss: the layers come
 * from <em>different files</em>. A document carries only its own {@link SettingsLayer#DOCUMENT} values,
 * and writing the user's preferences into it as well would mean opening that document on another machine
 * silently re-applied someone else's preferences as if the document had asked for them.</p>
 *
 * <h3>Omitted when empty, never written as an empty map</h3>
 * <p>A document is content-addressed, so "absent" and "present but empty" must not be two encodings of
 * the same thing. Callers get {@link #isWorthWriting} rather than having to remember; the same rule
 * {@code UIDescriptionCodec} follows for absent optionals.</p>
 */
public final class SettingsCodec {

    private SettingsCodec() {
    }

    public static final Codec<SettingsModel> MODEL = new Codec<>() {
        @Override
        public <T> T encode(DynamicOps<T> ops, SettingsModel input) {
            Map<T, T> encoded = new LinkedHashMap<>();
            // Insertion order is preserved from the model, which is what keeps the bytes stable — and
            // byte-stability is the whole reason the model is a LinkedHashMap.
            for (Map.Entry<String, String> entry : input.asMap().entrySet()) {
                encoded.put(ops.createString(entry.getKey()), ops.createString(entry.getValue()));
            }
            return ops.createMap(encoded);
        }

        @Override
        public <T> SettingsModel decode(DynamicOps<T> ops, T input) {
            SettingsModel model = new SettingsModel();
            Map<String, String> values = new LinkedHashMap<>();
            for (Map.Entry<T, T> entry : ops.getMapValue(input).entrySet()) {
                values.put(ops.getStringValue(entry.getKey()), ops.getStringValue(entry.getValue()));
            }
            model.replaceAll(values);
            return model;
        }
    };

    /** Whether this layer has anything worth encoding. @see SettingsCodec */
    public static boolean isWorthWriting(@Nullable SettingsModel model) {
        return model != null && !model.isEmpty();
    }

    // ── As a file ───────────────────────────────────────────────────────────

    /**
     * One layer as pretty-printed JSON — a preferences file somebody is expected to open.
     *
     * <p>Values are quoted, because a {@link SettingsModel} is text throughout and this codec does not
     * know a boolean from a string. That is the cost of the trade the class note above describes, and it
     * is paid here rather than by making the storage layer learn every value type.</p>
     */
    public static String toJson(SettingsModel model) {
        return new com.google.gson.GsonBuilder().setPrettyPrinting().create()
                .toJson(MODEL.encode(com.crystalgui.serialization.JsonOps.INSTANCE, model)) + "\n";
    }

    /**
     * Reads a preferences file. Unparseable text yields an <b>empty</b> model, never an exception.
     *
     * <p>Empty rather than a throw for the same reason {@link Setting#read} degrades: a settings file is
     * hand-edited, and refusing to start because one is malformed is a support burden with no upside. The
     * caller decides whether to overwrite it — see {@code CrystalEditor}, which does not, so a file
     * somebody is halfway through editing is not destroyed by having been read.</p>
     */
    public static SettingsModel fromJson(@Nullable String json) {
        if (json == null || json.trim().isEmpty()) return new SettingsModel();
        try {
            return MODEL.decode(com.crystalgui.serialization.JsonOps.INSTANCE,
                    com.google.gson.JsonParser.parseString(json));
        } catch (RuntimeException malformed) {
            com.crystalgui.core.CrystalGuiCore.LOGGER.warn(
                    "Preferences file could not be read; continuing with the defaults", malformed);
            return new SettingsModel();
        }
    }
}
