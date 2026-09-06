package com.crystalgui.template;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** Plain Java values to the form a {@code StateMap} reads — what an override map is written in. */
final class TemplateValues {

    private TemplateValues() {
    }

    static JsonElement toJson(Object value) {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof JsonElement element) return element;
        if (value instanceof String text) return new JsonPrimitive(text);
        if (value instanceof Number number) return new JsonPrimitive(number);
        if (value instanceof Boolean flag) return new JsonPrimitive(flag);
        if (value instanceof Enum<?> constant) return new JsonPrimitive(constant.name());
        if (value instanceof Map<?, ?> map) {
            JsonObject out = new JsonObject();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.add(String.valueOf(entry.getKey()), toJson(entry.getValue()));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            JsonArray out = new JsonArray();
            for (Object each : list) out.add(toJson(each));
            return out;
        }
        if (value instanceof int[] numbers) {
            JsonArray out = new JsonArray();
            for (int each : numbers) out.add(new JsonPrimitive(each));
            return out;
        }
        if (value instanceof float[] numbers) {
            JsonArray out = new JsonArray();
            for (float each : numbers) out.add(new JsonPrimitive(each));
            return out;
        }
        if (value instanceof double[] numbers) {
            JsonArray out = new JsonArray();
            for (double each : numbers) out.add(new JsonPrimitive(each));
            return out;
        }
        if (value instanceof String[] strings) {
            JsonArray out = new JsonArray();
            for (String each : strings) out.add(new JsonPrimitive(each));
            return out;
        }
        throw new IllegalArgumentException("a state value of type " + value.getClass().getName()
                + " has no encoding -- pass a string, a number, a boolean, an enum, a list or a map");
    }

    static JsonObject mapToJson(Map<String, Object> values) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            out.add(entry.getKey(), toJson(entry.getValue()));
        }
        return out;
    }
}
