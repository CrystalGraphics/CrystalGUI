package com.crystalgui.core.settings;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.format.CgFormats;
import com.crystalgui.core.CrystalGuiCore;

import javax.annotation.Nullable;

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

    /** In the model's own order, which is what keeps the bytes stable. */
    public static final CgCodec<SettingsModel> MODEL = CgCodecs.xmap(CgCodecs.mapOf(CgCodecs.STRING, CgCodecs.STRING),
            values -> {
                SettingsModel model = new SettingsModel();
                model.replaceAll(values);
                return model;
            }, SettingsModel::asMap);

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
        return CgFormats.JSON.toText(MODEL, model);
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
            return CgFormats.JSON.fromText(MODEL, json);
        } catch (RuntimeException malformed) {
            CrystalGuiCore.LOGGER.warn(
                    "Preferences file could not be read; continuing with the defaults", malformed);
            return new SettingsModel();
        }
    }
}
