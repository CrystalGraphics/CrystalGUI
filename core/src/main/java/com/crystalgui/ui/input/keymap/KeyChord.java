package com.crystalgui.ui.input.keymap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A sequence of one or more {@link KeyStroke}s — {@code "Mod+K Mod+S"}.
 *
 * <p>Space-separated, as VS Code writes them. VS Code caps sequences at two strokes; this does not,
 * because the data structure is a list either way and Emacs- or Blender-trained users reach for longer
 * ones. The pending-state UI is only expected to be useful at depth two.</p>
 */
public record KeyChord(List<KeyStroke> strokes) {

    public KeyChord {
        if (strokes == null || strokes.isEmpty()) {
            throw new IllegalArgumentException("A chord needs at least one stroke");
        }
        strokes = Collections.unmodifiableList(new ArrayList<>(strokes));
    }

    public static KeyChord parse(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("Empty key chord");

        List<KeyStroke> strokes = new ArrayList<>();
        for (String part : trimmed.split("\\s+")) {
            if (!part.isEmpty()) strokes.add(KeyStroke.parse(part));
        }
        return new KeyChord(strokes);
    }

    public static KeyChord of(KeyStroke... strokes) {
        return new KeyChord(List.of(strokes));
    }

    public int length() {
        return strokes.size();
    }

    public KeyStroke at(int index) {
        return strokes.get(index);
    }

    /** Whether {@code prefix} is a proper or complete leading run of this chord — the test that decides
     * both "this chord fired" and "this chord is still waiting for more keys". */
    public boolean startsWith(List<KeyStroke> prefix) {
        if (prefix.size() > strokes.size()) return false;
        for (int i = 0; i < prefix.size(); i++) {
            if (!strokes.get(i).equals(prefix.get(i))) return false;
        }
        return true;
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder();
        for (KeyStroke stroke : strokes) {
            if (out.length() > 0) out.append(' ');
            out.append(stroke);
        }
        return out.toString();
    }
}
