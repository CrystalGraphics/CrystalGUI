package com.crystalgui.template;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A document's declared parameters, and the {@code $name} substitution that fills them in.
 *
 * <pre>{@code
 * "params": { "title": { "type": "string", "default": "Untitled" } },
 * "root": { "kind": "text", "state": { "text": "$title" } }
 * }</pre>
 *
 * <p>A {@code $name} is replaced wherever it appears as a whole string; anything else beginning with
 * {@code $} is left alone, so {@code "$5.00"} is text.</p>
 *
 * <p><b>Declaring parameters is what turns the syntax on.</b> In a document that declares none,
 * {@code "$gold"} is a label — which it has to be, or every mod with a currency loses. In one that does,
 * a {@code $name} it has not declared is refused rather than left in the tree, which is the difference
 * between a typo and a label reading {@code $titel}.</p>
 */
final class TemplateParams {

    private TemplateParams() {
    }

    /** One declared parameter: what it is called, what shape it is, and what it is when nobody says. */
    record Param(String name, String type, @Nullable JsonElement fallback) {
    }

    /** {@code $} then a Java-identifier-ish name. A digit after the {@code $} is money, not a parameter. */
    private static final Pattern REFERENCE = Pattern.compile("\\$[A-Za-z_][A-Za-z0-9_]*");

    static Map<String, Param> declare(JsonObject document, String origin) {
        JsonElement declared = document.get("params");
        if (declared == null || !declared.isJsonObject()) return Map.of();
        Map<String, Param> params = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : declared.getAsJsonObject().entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                throw new UiTemplateException(origin, null, "the parameter \"" + entry.getKey()
                        + "\" is declared as {type, default}");
            }
            JsonObject spec = entry.getValue().getAsJsonObject();
            String type = spec.has("type") ? spec.get("type").getAsString() : "string";
            params.put(entry.getKey(), new Param(entry.getKey(), type, spec.get("default")));
        }
        return params;
    }

    /**
     * A copy of {@code node} with every {@code $name} replaced.
     *
     * <p>Returns the node itself when the document declares no parameters, so an ordinary document pays
     * nothing for this.</p>
     */
    static JsonObject substitute(UiTemplate template, JsonObject node, Map<String, Object> supplied) {
        if (template.params().isEmpty()) return node;
        return (JsonObject) walk(template, node, supplied);
    }

    private static JsonElement walk(UiTemplate template, JsonElement value, Map<String, Object> supplied) {
        if (value.isJsonObject()) {
            JsonObject out = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                out.add(entry.getKey(), walk(template, entry.getValue(), supplied));
            }
            return out;
        }
        if (value.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement each : value.getAsJsonArray()) out.add(walk(template, each, supplied));
            return out;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return value;
        String text = value.getAsString();
        if (!REFERENCE.matcher(text).matches()) return value;
        return resolve(template, text.substring(1), supplied);
    }

    private static JsonElement resolve(UiTemplate template, String name, Map<String, Object> supplied) {
        Param declared = template.params().get(name);
        if (declared == null) {
            throw new UiTemplateException(template.origin(), null, "$" + name
                    + " is not a declared parameter -- this document declares " + template.params().keySet());
        }
        Object given = supplied.get(name);
        if (given != null) return TemplateValues.toJson(given);
        if (declared.fallback() != null) return declared.fallback();
        throw new UiTemplateException(template.origin(), null, "the parameter \"" + name
                + "\" has no default, so a value for it is required here");
    }
}
