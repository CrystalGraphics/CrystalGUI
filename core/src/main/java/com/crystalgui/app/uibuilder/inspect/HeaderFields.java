package com.crystalgui.app.uibuilder.inspect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import com.crystalgui.app.uibuilder.document.BuilderEdit;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.core.property.Property;

/**
 * What a Document-tab row binds to: a key of an open {@code .cgui}'s header, read and written as one
 * {@code SetHeader} per change.
 *
 * <pre>{@code
 * HeaderFields header = HeaderFields.on(document);
 * form.prop(ConfigDescriptor.text("export.package", "Package"), header.text("package"));
 * form.prop(ConfigDescriptor.text("canvas.theme", "Theme"),
 *         header.preview(() -> themeOf(header), theme -> new JsonPrimitive(theme), "theme"));
 * }</pre>
 *
 * <ul>
 *   <li>A blank value removes the key rather than writing it empty, so an untouched header stays as small as it
 *       was written.</li>
 *   <li>{@code preview} is one header key: changing one of its entries writes a copy of the whole object.</li>
 * </ul>
 */
public final class HeaderFields {

    private final UiBuilderDocument document;

    private final NodeFields fields;

    private HeaderFields(UiBuilderDocument document) {
        this.document = document;
        this.fields = NodeFields.on(document);
    }

    public static HeaderFields on(UiBuilderDocument document) {
        return new HeaderFields(Objects.requireNonNull(document, "document"));
    }

    /** A string key; a blank value removes it. */
    public Property<String> text(String key) {
        return fields.bind(() -> stringOf(document.header().get(key)),
                text -> set(key, text == null || text.isBlank() ? null : new JsonPrimitive(text.trim())));
    }

    /** A list of strings, as an array control holds it; empty entries are dropped and an empty list removes the key. */
    public Property<List<Object>> strings(String key) {
        return fields.bind(() -> {
            List<Object> out = new ArrayList<>();
            JsonElement value = document.header().get(key);
            if (value != null && value.isJsonArray()) {
                for (JsonElement each : value.getAsJsonArray()) {
                    if (each.isJsonPrimitive()) out.add(each.getAsString());
                }
            }
            return out;
        }, wanted -> {
            JsonArray array = new JsonArray();
            for (Object each : wanted) {
                String entry = String.valueOf(each).trim();
                if (!entry.isEmpty()) array.add(new JsonPrimitive(entry));
            }
            return set(key, array.size() == 0 ? null : array);
        });
    }

    /** An entry of {@code preview}, read and written through {@code read} and {@code write}. */
    public <V> Property<V> preview(Supplier<V> read, Function<V, JsonElement> write, String key) {
        return fields.bind(read, value -> setPreview(key, write.apply(value)));
    }

    /** An entry of {@code preview}, or null. */
    @Nullable
    public JsonElement previewKey(String key) {
        JsonElement preview = document.header().get("preview");
        return preview != null && preview.isJsonObject() ? preview.getAsJsonObject().get(key) : null;
    }

    /** The edit setting {@code key} to {@code to}, or null when it already holds it. Null removes the key. */
    @Nullable
    public BuilderEdit set(String key, @Nullable JsonElement to) {
        JsonElement from = document.header().get(key);
        return Objects.equals(from, to) ? null : new BuilderEdit.SetHeader(document.header(), key, from, to);
    }

    /** The edit setting one entry of {@code preview}, as a new {@code preview} object. */
    @Nullable
    public BuilderEdit setPreview(String key, @Nullable JsonElement value) {
        JsonElement current = document.header().get("preview");
        // A NEW object, never the one in the header: the edit's `from` still holds that one.
        JsonObject next = new JsonObject();
        if (current != null && current.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : current.getAsJsonObject().entrySet()) {
                next.add(entry.getKey(), entry.getValue());
            }
        }
        if (value == null) next.remove(key);
        else next.add(key, value);
        return set("preview", next.entrySet().isEmpty() ? null : next);
    }

    /** {@code "800x480"} (or {@code ×}, or a space between) as {@code {800, 480}}, or null. */
    @Nullable
    public static float[] parseSize(String text) {
        String[] parts = text.trim().toLowerCase(Locale.ROOT).split("\\s*[x×\\s]\\s*");
        if (parts.length != 2) return null;
        try {
            float width = Float.parseFloat(parts[0]);
            float height = Float.parseFloat(parts[1]);
            return width > 0 && height > 0 ? new float[] {width, height} : null;
        } catch (NumberFormatException notASize) {
            return null;
        }
    }

    private static String stringOf(@Nullable JsonElement value) {
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }
}
