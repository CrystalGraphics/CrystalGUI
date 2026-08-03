package com.crystalgui.ui.input.keymap;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * One key plus a modifier mask — {@code "Mod+Shift+P"}.
 *
 * <h3>{@code Mod} rather than parallel platform bindings</h3>
 * <p>{@code Mod} resolves to Ctrl on Windows and Linux and to Super on macOS, so one binding covers both.
 * VS Code instead ships duplicate {@code mac:} entries, which doubles every keymap file and lets the two
 * halves drift; CodeMirror and tinykeys use a single token, and that is the better idea. {@code Ctrl} and
 * {@code Super} remain spellable for the rare binding that genuinely means one specific key.</p>
 *
 * <p>Modifier order is irrelevant — {@code "Shift+Mod+P"} and {@code "Mod+Shift+P"} are the same stroke,
 * because the mask is a set. {@link #toString()} always renders the canonical order so a conflict report
 * and a menu accelerator label agree with each other.</p>
 */
public record KeyStroke(int key, int modifiers) {

    /**
     * The mouse wheel, as strokes — {@code "Mod+WheelUp"}, {@code "Mod+WheelDown"}.
     *
     * <p><b>Negative, so they cannot collide with a real key.</b> {@code CgKeyCodes} is LWJGL2-shaped and
     * every constant in it is a non-negative scan code, so the space below zero is free forever and a
     * future key cannot silently become "wheel up".</p>
     *
     * <p>Modelling the wheel as a stroke rather than as its own binding type is what makes it rebindable
     * for free: the resolver, the conflict report, the accelerator label and {@code bindAll} all work on
     * it without knowing it is not a key. A binding that cannot be remapped is the shape section H spent
     * its whole existence removing, and a hard-coded Ctrl+wheel would have reintroduced it.</p>
     */
    public static final int WHEEL_UP = -100;
    public static final int WHEEL_DOWN = -101;

    /** The stroke a wheel notch makes. A POSITIVE notch means the wheel rolled DOWN — the engine's rule,
     * stated by {@code ScrollerView}'s {@code setScrollTop(before + delta)} and got wrong once already by
     * {@code CanvasView}, which shipped zooming in on scroll-down. */
    public static KeyStroke ofWheel(float notches, int modifiers) {
        return new KeyStroke(notches > 0f ? WHEEL_DOWN : WHEEL_UP, modifiers);
    }

    public boolean isWheel() {
        return key == WHEEL_UP || key == WHEEL_DOWN;
    }

    /** Modifiers that participate in matching. Anything else the platform reports is ignored rather than
     * required, so a stray lock key cannot make a binding unreachable. */
    private static final int MATCHED = CgModifiers.SHIFT | CgModifiers.CTRL | CgModifiers.ALT | CgModifiers.SUPER;

    public KeyStroke {
        modifiers &= MATCHED;
    }

    /**
     * Whether this stroke carries a modifier that makes it safe around text input.
     *
     * <p>Shift deliberately does <b>not</b> count: {@code Shift+B} still types a capital B, so it is as
     * dangerous inside a text field as bare {@code B}. This is the predicate behind the guard in
     * {@link KeymapResolver} — see {@link KeyBinding#allowWhileTyping()}.</p>
     */
    public boolean hasNonShiftModifier() {
        return (modifiers & (CgModifiers.CTRL | CgModifiers.ALT | CgModifiers.SUPER)) != 0;
    }

    /**
     * Whether this stroke's key is <b>itself</b> a modifier — Ctrl, Shift, Alt or the Super/Meta key.
     *
     * <p>Pressing Ctrl generates a real key-down whose key <em>is</em> Ctrl, and such a stroke can never
     * match a binding: {@code Mod+S} means "S, with Ctrl held", never "Ctrl". Left unfiltered it does
     * worse than not matching — it reaches the end of the resolver and <b>cancels a pending chord</b>.
     * That is why {@code Mod+K} then {@code Mod+S} only worked while Ctrl was held down without
     * interruption: releasing and re-pressing Ctrl inserted a bare-modifier press between the two
     * strokes, which threw the prefix away.</p>
     */
    public boolean isBareModifier() {
        return key == CgKeyCodes.KEY_LCONTROL || key == CgKeyCodes.KEY_RCONTROL
                || key == CgKeyCodes.KEY_LSHIFT || key == CgKeyCodes.KEY_RSHIFT
                || key == CgKeyCodes.KEY_LMENU || key == CgKeyCodes.KEY_RMENU
                || key == CgKeyCodes.KEY_LMETA || key == CgKeyCodes.KEY_RMETA;
    }

    /** Parses one stroke: modifiers joined by {@code +}, key last. Case-insensitive. */
    public static KeyStroke parse(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("Empty key stroke");

        String[] parts = trimmed.split("\\+");
        int modifiers = 0;
        for (int i = 0; i < parts.length - 1; i++) {
            modifiers |= parseModifier(parts[i].trim(), trimmed);
        }
        return new KeyStroke(parseKey(parts[parts.length - 1].trim(), trimmed), modifiers);
    }

    private static int parseModifier(String token, String whole) {
        switch (token.toLowerCase(Locale.ROOT)) {
            case "mod":     return MOD;
            case "ctrl": case "control": return CgModifiers.CTRL;
            case "shift":   return CgModifiers.SHIFT;
            case "alt": case "option": return CgModifiers.ALT;
            case "super": case "cmd": case "command": case "meta": case "win": return CgModifiers.SUPER;
            default:
                throw new IllegalArgumentException("Unknown modifier '" + token + "' in '" + whole
                        + "'. Expected Mod, Ctrl, Shift, Alt or Super.");
        }
    }

    private static int parseKey(String token, String whole) {
        Integer code = BY_NAME.get(token.toUpperCase(Locale.ROOT));
        if (code == null) {
            throw new IllegalArgumentException("Unknown key '" + token + "' in '" + whole + "'");
        }
        return code;
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder();
        // Fixed order, so two spellings of the same stroke render identically. A conflict report that
        // printed "Ctrl+Shift+P" and "Shift+Ctrl+P" would look like two different bindings.
        if ((modifiers & CgModifiers.CTRL) != 0) out.append("Ctrl+");
        if ((modifiers & CgModifiers.ALT) != 0) out.append("Alt+");
        if ((modifiers & CgModifiers.SHIFT) != 0) out.append("Shift+");
        if ((modifiers & CgModifiers.SUPER) != 0) out.append("Super+");
        return out.append(BY_CODE.getOrDefault(key, "Key" + key)).toString();
    }

    // ── Key name table ──────────────────────────────────────────────────────

    /**
     * {@code CgKeyCodes} has ~131 constants and grows. Reflecting over it rather than hand-writing a table
     * means the two cannot drift — a hand-written map would silently lack whatever was added last, and the
     * symptom would be a key that simply refuses to bind.
     */
    private static final Map<String, Integer> BY_NAME = new HashMap<>();
    private static final Map<Integer, String> BY_CODE = new TreeMap<>();

    static {
        // The wheel first, so a CgKeyCodes constant could never shadow these names.
        BY_NAME.put("WHEELUP", WHEEL_UP);
        BY_NAME.put("WHEELDOWN", WHEEL_DOWN);
        BY_CODE.put(WHEEL_UP, "WheelUp");
        BY_CODE.put(WHEEL_DOWN, "WheelDown");
        for (Field field : CgKeyCodes.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != int.class) continue;
            if (!field.getName().startsWith("KEY_")) continue;
            try {
                int code = field.getInt(null);
                String name = field.getName().substring(4);
                BY_NAME.put(name, code);
                BY_CODE.putIfAbsent(code, name);
            } catch (IllegalAccessException ignored) {
                // A non-public constant is simply not bindable by name; nothing actionable.
            }
        }
        // Aliases for names people actually type. The canonical spelling stays whatever CgKeyCodes calls
        // it, so toString() output remains stable.
        BY_NAME.put("ENTER", BY_NAME.get("RETURN"));
        BY_NAME.put("ESC", BY_NAME.get("ESCAPE"));
        BY_NAME.put("DEL", BY_NAME.get("DELETE"));
        // The name every user, every keymap file and every other editor writes. The reflected name is
        // BACK, because CgKeyCodes is LWJGL2-shaped and LWJGL2 called it KEY_BACK — which is an
        // implementation detail of a backend leaking into a user-facing string. Missing this alias
        // crashed a scene at startup on the first binding that wanted it.
        BY_NAME.put("BACKSPACE", BY_NAME.get("BACK"));
        // Same leak, second occurrence — and it crashed a scene at startup in exactly the same way, which
        // is why the aliases now come with `ShippedKeymapDefaultsTest` rather than another comment. LWJGL2
        // named the page keys after the PC/AT scancodes (PRIOR/NEXT); nobody has typed those since.
        BY_NAME.put("PAGEUP", BY_NAME.get("PRIOR"));
        BY_NAME.put("PAGEDOWN", BY_NAME.get("NEXT"));
        BY_NAME.put("PGUP", BY_NAME.get("PRIOR"));
        BY_NAME.put("PGDN", BY_NAME.get("NEXT"));
        BY_NAME.put("PLUS", BY_NAME.get("ADD"));
        BY_NAME.values().removeIf(value -> value == null);
    }

    /**
     * What {@code Mod} means on this machine.
     *
     * <p>Resolved from {@code os.name} rather than from anything platform-specific, because {@code core/}
     * may not import a loader — and because the answer is a property of the operating system rather than
     * of the render backend.</p>
     */
    static final int MOD = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")
            ? CgModifiers.SUPER
            : CgModifiers.CTRL;
}
