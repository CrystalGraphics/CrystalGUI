package com.crystalgui.serialization;

import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgDynamicOps;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The one concrete {@link CgDynamicOps} implementation shipped in {@code core/} — backs {@link Codec}
 * definitions with Gson's {@link JsonElement} tree, reusing the already-proven Gson dependency (see
 * {@code CgUiSpriteRegistry}, which already parses JSON asset packs the same way). Format-specific;
 * it's the {@link Codec}/{@link CgDynamicOps} split itself (not this class) that keeps codec
 * definitions reusable against other formats later — see {@link CgDynamicOps}'s class doc. */
public final class JsonOps implements CgDynamicOps<JsonElement> {

    public static final JsonOps INSTANCE = new JsonOps();

    private JsonOps() {
    }

    @Override
    public JsonElement empty() {
        return JsonNull.INSTANCE;
    }

    @Override
    public JsonElement createString(String value) {
        return new JsonPrimitive(value);
    }

    @Override
    public JsonElement createNumber(Number value) {
        return new JsonPrimitive(value);
    }

    @Override
    public JsonElement createBoolean(boolean value) {
        return new JsonPrimitive(value);
    }

    @Override
    public JsonElement createList(List<JsonElement> values) {
        JsonArray array = new JsonArray();
        for (JsonElement value : values) array.add(value);
        return array;
    }

    @Override
    public JsonElement createMap(Map<JsonElement, JsonElement> entries) {
        JsonObject object = new JsonObject();
        for (Map.Entry<JsonElement, JsonElement> entry : entries.entrySet()) {
            object.add(getStringValue(entry.getKey()), entry.getValue());
        }
        return object;
    }

    @Override
    public String getStringValue(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new CgCodecException("Not a JSON string: " + value);
        }
        return value.getAsString();
    }

    @Override
    public Number getNumberValue(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new CgCodecException("Not a JSON number: " + value);
        }
        return value.getAsNumber();
    }

    @Override
    public boolean getBooleanValue(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new CgCodecException("Not a JSON boolean: " + value);
        }
        return value.getAsBoolean();
    }

    @Override
    public List<JsonElement> getListValue(JsonElement value) {
        if (!value.isJsonArray()) {
            throw new CgCodecException("Not a JSON array: " + value);
        }
        List<JsonElement> list = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) list.add(element);
        return list;
    }

    @Override
    public Map<JsonElement, JsonElement> getMapValue(JsonElement value) {
        if (!value.isJsonObject()) {
            throw new CgCodecException("Not a JSON object: " + value);
        }
        Map<JsonElement, JsonElement> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            map.put(new JsonPrimitive(entry.getKey()), entry.getValue());
        }
        return map;
    }
}
