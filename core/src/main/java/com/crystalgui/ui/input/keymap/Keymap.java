package com.crystalgui.ui.input.keymap;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.ui.UIElement;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The key bindings owned by one element — its scope.
 *
 * <h3>Scope is the tree, not a condition language</h3>
 * <p>VS Code needs {@code when} clauses ({@code editorTextFocus}, {@code listHasSelection}) because it has
 * no tree to walk: those context keys are hand-maintained booleans. This engine has a real DOM with
 * focus, so a binding is simply attached to an element and is live whenever focus is inside that element's
 * subtree — and {@link KeymapResolver} walks focus outward, <b>innermost first</b>.</p>
 *
 * <p>That ordering settles the obvious conflict for free: a text field's {@code Mod+A} (select all text)
 * beats the window's {@code Mod+A} (select all items) without either knowing the other exists. It is
 * {@code when}-clause specificity, obtained structurally.</p>
 *
 * <p>Application-wide bindings are therefore not a special case — they are bound to the root element,
 * which is every element's ancestor.</p>
 */
public final class Keymap {

    private final List<KeyBinding> bindings = new ArrayList<>();

    /**
     * Binds {@code chord} to a command id.
     *
     * @param chord     VS Code syntax: modifiers joined by {@code +}, sequential strokes separated by
     *                  spaces — {@code "Mod+S"}, {@code "Mod+K Mod+S"}
     * @param commandId the id of a {@link com.crystalgui.core.command.Command}. Deliberately not the
     *                  command itself: a binding that names an id stays data, so keymaps can be parsed
     *                  from a resource, shipped as presets and remapped by users.
     */
    public KeyBinding bind(String chord, String commandId) {
        return bind(KeyChord.parse(chord), commandId);
    }

    /**
     * Binds several alternative chords to one command — {@code "Mod+Equals, Mod+Add"}.
     *
     * <p><b>Comma-separated, because space is already taken.</b> A space separates the strokes of a
     * <em>sequence</em> ({@code "Mod+K Mod+S"}), so alternatives need a different separator or
     * {@code "Mod+K Mod+S, F1"} could not be told from a three-stroke chord. Split on the comma first,
     * then each part on whitespace, and both meanings survive in one string.</p>
     *
     * <p>VS Code has no equivalent — its keybindings file takes one entry per chord and you repeat the
     * command. That is fine for a JSON file a user edits and verbose in Java, where the same six
     * bindings were six lines that had to be kept in step by eye. IntelliJ, which lists multiple
     * shortcuts per action, is the better model here.</p>
     *
     * <p>Returns the {@link Keymap} rather than a binding, since there is no single one to return. Where
     * a per-binding modifier is needed — {@link KeyBinding#allowWhileTyping()} — bind that chord on its
     * own with {@link #bind}.</p>
     */
    public Keymap bindAll(String chords, String commandId) {
        if (chords == null || chords.isBlank()) {
            throw new IllegalArgumentException("bindAll needs at least one chord");
        }
        for (String chord : chords.split(",")) {
            if (!chord.isBlank()) bind(KeyChord.parse(chord.trim()), commandId);
        }
        return this;
    }

    public KeyBinding bind(KeyChord chord, String commandId) {
        if (commandId == null || commandId.isEmpty()) {
            throw new IllegalArgumentException("A binding needs a command id — see Keymap.bind");
        }
        KeyBinding binding = new KeyBinding(chord, commandId);
        // Warn rather than reject: a conflict is a bug in the keymap, but refusing the second binding
        // would make load order significant and turn a diagnosable mistake into a mysterious one.
        // Innermost-first resolution would otherwise hide this entirely.
        for (KeyBinding existing : bindings) {
            if (existing.getChord().equals(chord) && existing.getEventType() == binding.getEventType()) {
                CrystalGuiCore.LOGGER.warn("Keymap conflict: {} is already bound to '{}' in this scope,"
                        + " now also '{}'. The earlier binding wins.",
                        chord, existing.getCommandId(), commandId);
            }
        }
        bindings.add(binding);
        return binding;
    }

    public Keymap unbind(String chord) {
        KeyChord parsed = KeyChord.parse(chord);
        bindings.removeIf(binding -> binding.getChord().equals(parsed));
        return this;
    }

    /**
     * Removes only the binding of {@code chord} to {@code commandId} — what a sheet's {@code "-command"}
     * entry does.
     *
     * <p>Targeted rather than clearing the chord, because a user removing one default must not silently
     * take some other extension's binding on the same key with it.</p>
     */
    public Keymap unbind(KeyChord chord, String commandId) {
        bindings.removeIf(binding -> binding.getChord().equals(chord)
                && binding.getCommandId().equals(commandId));
        return this;
    }

    /** Applies a sheet to this scope — see {@link KeymapSheet}. */
    public Keymap load(KeymapSheet sheet) {
        sheet.applyTo(this);
        return this;
    }

    public Keymap clear() {
        bindings.clear();
        return this;
    }

    public List<KeyBinding> bindings() {
        return Collections.unmodifiableList(bindings);
    }

    public boolean isEmpty() {
        return bindings.isEmpty();
    }

    /**
     * The chord bound to {@code commandId} <b>in this scope only</b>, or null.
     *
     * <p>{@link #acceleratorFor} is almost always what a caller wants instead — a menu item does not know
     * which scope its command is bound in, only where it sits in the tree.</p>
     */
    @Nullable
    public KeyChord chordFor(String commandId) {
        for (KeyBinding binding : bindings) {
            // PRESS only. A release binding is real but is not an accelerator anyone can render: "Space
            // (on release)" is not something a menu says, and showing the press half of a press/release
            // pair twice would be worse.
            if (binding.getEventType() != KeyEventType.PRESS) continue;
            if (binding.getCommandId().equals(commandId)) return binding.getChord();
        }
        return null;
    }

    /**
     * The accelerator to display for {@code commandId} as seen from {@code from} — the chord that would
     * <b>actually fire</b> it there, or null if nothing would.
     *
     * <h3>Why this walks, rather than the command carrying its own chord</h3>
     * <p>A command has no single accelerator. The same id can be bound in several scopes, to different
     * chords, and which one applies depends entirely on where you are in the tree. Storing a chord on
     * {@link com.crystalgui.core.command.Command} would therefore be storing an answer to a question that
     * has not been asked yet.</p>
     *
     * <p>So this walks outward taking the innermost match — <b>the same walk, in the same order, as
     * {@link KeymapResolver}</b>. That is not a coincidence to preserve casually: it is what guarantees a
     * menu item's label cannot disagree with what pressing the key does. Any cheaper implementation (a
     * flat registry, a first-found scan) can drift from resolution, and the failure mode is a menu that
     * confidently advertises the wrong shortcut.</p>
     *
     * <p>Null is an ordinary answer, not an error — most commands are never bound, and a menu item simply
     * renders no accelerator.</p>
     */
    @Nullable
    public static KeyChord acceleratorFor(@Nullable UIElement from, String commandId) {
        for (UIElement scope = from; scope != null; scope = scope.getParent()) {
            Keymap keymap = scope.keymapOrNull();
            if (keymap == null) continue;
            KeyChord chord = keymap.chordFor(commandId);
            if (chord != null) return chord;
        }
        return null;
    }

    /**
     * Every command reachable from {@code from}, mapped to the accelerator that would fire it — what a
     * command palette lists.
     *
     * <p>Innermost wins, so an inner scope's rebinding of a command shadows an outer one exactly as it
     * would when the key is pressed. Commands bound nowhere on this path are absent rather than mapped to
     * null; a palette shows them with no accelerator by looking them up in the registry, not here.</p>
     */
    public static Map<String, KeyChord> acceleratorsFrom(@Nullable UIElement from) {
        Map<String, KeyChord> out = new LinkedHashMap<>();
        for (UIElement scope = from; scope != null; scope = scope.getParent()) {
            Keymap keymap = scope.keymapOrNull();
            if (keymap == null) continue;
            for (KeyBinding binding : keymap.bindings) {
                if (binding.getEventType() != KeyEventType.PRESS) continue;
                // putIfAbsent, because the walk is innermost-first and the first answer is the winner —
                // the same precedence resolution uses.
                out.putIfAbsent(binding.getCommandId(), binding.getChord());
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * Chords bound more than once in this scope, with everything bound to each.
     *
     * <p>Exists so a test can assert a keymap is clean, and so a settings screen can show the user what
     * they have broken. Silent resolution of a duplicate is the failure this reports: innermost-first
     * would quietly pick one and the other would simply never fire.</p>
     */
    public Map<KeyChord, List<String>> conflicts() {
        Map<KeyChord, List<String>> byChord = new LinkedHashMap<>();
        for (KeyBinding binding : bindings) {
            byChord.computeIfAbsent(binding.getChord(), ignored -> new ArrayList<>())
                    .add(binding.getCommandId());
        }
        byChord.values().removeIf(commands -> commands.size() < 2);
        return byChord;
    }
}
