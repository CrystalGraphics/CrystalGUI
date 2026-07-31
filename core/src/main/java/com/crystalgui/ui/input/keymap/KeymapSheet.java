package com.crystalgui.ui.input.keymap;

import com.crystalgraphics.util.io.CgIO;
import com.crystalgui.core.CrystalGuiCore;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Key bindings as <b>data</b> — VS Code's {@code keybindings.json}, ported.
 *
 * <pre>{@code
 * [
 *   { "key": "Mod+S",       "command": "edit.save" },
 *   { "key": "Mod+K Mod+S", "command": "edit.saveAll" },
 *   { "key": "Space",       "command": "pan.begin" },
 *   { "key": "Space",       "command": "pan.end", "on": "release" },
 *   { "key": "B",           "command": "tool.brush", "whileTyping": true },
 *   { "key": "Mod+P",       "command": "-palette.open" }
 * ]
 * }</pre>
 *
 * <h3>Why this exists at all</h3>
 * <p>A binding names a {@code String} command id rather than holding a lambda, and that indirection is
 * the whole design — but it buys nothing until a sheet can be loaded. This is the half that makes it
 * real: <b>presets</b> (Resolve ships Premiere, FCP and Avid maps; Photoshop ships shortcut sets) and
 * <b>user remapping</b>, neither of which can exist while bindings are only reachable from Java.</p>
 *
 * <h3>Removal, and why it is a leading minus rather than a delete list</h3>
 * <p>{@code "-command"} unbinds instead of binding — VS Code's own syntax. It matters because a user
 * sheet is <em>appended</em> to the defaults rather than replacing them: without a way to say "not that
 * one", the only way to drop a default binding would be to redefine the entire default sheet, which then
 * silently stops tracking upstream changes to it. One character avoids that.</p>
 *
 * <p>A malformed entry is skipped with a warning rather than failing the sheet. One bad line in a user's
 * remapping must not cost them every other binding they wrote — the same call the stylesheet parser makes
 * for a malformed declaration.</p>
 */
public final class KeymapSheet {

    /** One parsed entry. {@code remove} entries carry the id with the minus already stripped. */
    public record Entry(KeyChord chord, String commandId, KeyEventType eventType,
                        boolean allowWhileTyping, boolean remove) {
    }

    private final List<Entry> entries;

    private KeymapSheet(List<Entry> entries) {
        this.entries = Collections.unmodifiableList(entries);
    }

    public List<Entry> entries() {
        return entries;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * Applies this sheet to {@code keymap}, in order.
     *
     * <p>Order is significant and is the sheet's, not sorted: a later entry removing an earlier one is
     * exactly how a user sheet layered over defaults is meant to work.</p>
     */
    public void applyTo(Keymap keymap) {
        for (Entry entry : entries) {
            if (entry.remove()) {
                keymap.unbind(entry.chord(), entry.commandId());
                continue;
            }
            KeyBinding binding = keymap.bind(entry.chord(), entry.commandId()).on(entry.eventType());
            if (entry.allowWhileTyping()) binding.allowWhileTyping();
        }
    }

    // ── Parsing ─────────────────────────────────────────────────────────────

    /** Parses sheet text. Never throws for content reasons — see the class doc. */
    public static KeymapSheet parse(String json) {
        List<Entry> parsed = new ArrayList<>();
        JsonElement root;
        try {
            root = JsonParser.parseString(json == null ? "" : json);
        } catch (RuntimeException e) {
            CrystalGuiCore.LOGGER.warn("Keymap sheet is not valid JSON, ignoring it entirely: {}",
                    e.getMessage());
            return new KeymapSheet(parsed);
        }
        if (root == null || !root.isJsonArray()) {
            CrystalGuiCore.LOGGER.warn("A keymap sheet must be a JSON array of {{key, command}} objects");
            return new KeymapSheet(parsed);
        }

        JsonArray array = root.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
            Entry entry = parseEntry(array.get(i), i);
            if (entry != null) parsed.add(entry);
        }
        return new KeymapSheet(parsed);
    }

    /** Loads and parses a sheet from a resource path, e.g. {@code "crystalgui:ui/keymaps/default.json"}.
     * Routed through {@code CgIO}, so a filesystem override and a resource pack both work, exactly as
     * they do for stylesheets and sprites. */
    public static KeymapSheet load(String path) {
        InputStream stream = CgIO.openStream(path);
        if (stream == null) {
            CrystalGuiCore.LOGGER.warn("Keymap sheet not found: {}", path);
            return new KeymapSheet(new ArrayList<>());
        }
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            StringBuilder text = new StringBuilder();
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) text.append(buffer, 0, read);
            return parse(text.toString());
        } catch (Exception e) {
            CrystalGuiCore.LOGGER.warn("Failed to read keymap sheet '{}': {}", path, e.getMessage());
            return new KeymapSheet(new ArrayList<>());
        }
    }

    @Nullable
    private static Entry parseEntry(JsonElement element, int index) {
        if (element == null || !element.isJsonObject()) {
            CrystalGuiCore.LOGGER.warn("Keymap entry {} is not an object, skipping", index);
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        String key = string(object, "key");
        String command = string(object, "command");
        if (key == null || key.isEmpty()) {
            CrystalGuiCore.LOGGER.warn("Keymap entry {} has no \"key\", skipping", index);
            return null;
        }
        if (command == null || command.isEmpty()) {
            // VS Code treats an empty command as "disable this key". Ours would need a chord-level
            // suppression concept to honour it, which nothing has asked for — so it is refused loudly
            // rather than accepted and ignored.
            CrystalGuiCore.LOGGER.warn(
                    "Keymap entry {} has no \"command\". To remove a binding write \"-the.command.id\"; "
                            + "an empty command is not supported.", index);
            return null;
        }

        boolean remove = command.charAt(0) == '-';
        String commandId = remove ? command.substring(1) : command;
        if (commandId.isEmpty()) {
            CrystalGuiCore.LOGGER.warn("Keymap entry {} is a bare '-' with no command id, skipping", index);
            return null;
        }

        KeyChord chord;
        try {
            chord = KeyChord.parse(key);
        } catch (IllegalArgumentException e) {
            // One unparseable key must not cost the user every other binding in their file.
            CrystalGuiCore.LOGGER.warn("Keymap entry {} has an unparseable key '{}': {}", index, key,
                    e.getMessage());
            return null;
        }

        KeyEventType type = KeyEventType.PRESS;
        String on = string(object, "on");
        if (on != null && on.equalsIgnoreCase("release")) type = KeyEventType.RELEASE;
        else if (on != null && !on.equalsIgnoreCase("press")) {
            CrystalGuiCore.LOGGER.warn("Keymap entry {} has an unknown \"on\" value '{}'; "
                    + "expected \"press\" or \"release\". Treating it as press.", index, on);
        }

        boolean whileTyping = object.has("whileTyping")
                && object.get("whileTyping").isJsonPrimitive()
                && object.get("whileTyping").getAsJsonPrimitive().isBoolean()
                && object.get("whileTyping").getAsBoolean();

        return new Entry(chord, commandId, type, whileTyping, remove);
    }

    @Nullable
    private static String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }
}
