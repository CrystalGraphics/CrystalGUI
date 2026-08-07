package com.crystalgui.ui.input.keymap;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.UIElement;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Matches a keystroke against the keymaps along the focus path, and holds pending-chord state.
 *
 * <h3>Innermost first</h3>
 * <p>The walk goes from the focused element outward to the root, and the <b>first</b> scope with a match
 * wins. That is the same order events bubble, and it is the right answer for the conflict that actually
 * occurs: a text field's {@code Mod+A} must beat the window's {@code Mod+A} without either knowing the
 * other exists.</p>
 *
 * <h3>Where this runs</h3>
 * <p>After {@code KeyboardEvent.Down} has finished bubbling and was not {@code preventDefault()}ed —
 * exactly how a browser applies its own shortcuts, with page handlers getting first refusal. Resolving
 * ahead of dispatch would let an application-wide binding steal a keystroke from a control that wanted
 * it, with no way for the control to object.</p>
 */
public final class KeymapResolver {

    /**
     * How long a half-entered chord waits for its next stroke.
     *
     * <p>A value, not a constant of nature: VS Code waits indefinitely, Emacs waits indefinitely, Blender
     * has no sequences. Indefinite is hostile here — a stray {@code Mod+K} would silently swallow the
     * next keystroke minutes later with nothing on screen to explain it. Five seconds is long enough to
     * think and short enough that a forgotten prefix expires before it can surprise anybody.</p>
     */
    private static final long CHORD_TIMEOUT_MILLIS = 5_000L;

    private final CommandRegistry commands;

    /** Strokes accepted so far toward a longer chord. Empty whenever nothing is pending. */
    private final List<KeyStroke> pending = new ArrayList<>();
    private long pendingSinceMillis;

    /**
     * Fires whenever the pending prefix changes — with the prefix, or null when it clears.
     *
     * <p>Exists because a chord with no feedback is indistinguishable from a dead keyboard. VS Code puts
     * "(Ctrl+K) was pressed, waiting for second key" in its status bar, and anything less leaves the user
     * unable to tell that the application is waiting for them.</p>
     */
    public final Signal.Value<KeyChord> onPendingChanged = new Signal.Value<>();

    public KeymapResolver(CommandRegistry commands) {
        this.commands = commands;
    }

    /** The prefix entered so far, or null when nothing is pending. */
    @Nullable
    public KeyChord pending() {
        return pending.isEmpty() ? null : new KeyChord(pending);
    }

    /**
     * Abandons any half-entered chord.
     *
     * <p>Must be called on focus changes as well as on a non-matching key: a prefix entered in one panel
     * has no business completing in another, and the surviving state would fire the wrong command in the
     * wrong scope.</p>
     */
    public void cancelPending() {
        if (pending.isEmpty()) return;
        pending.clear();
        onPendingChanged.emit(null);
    }

    /**
     * @return true if a binding matched — either firing a command or accepting a chord prefix — in which
     *         case the caller must treat the key as consumed and not fall through to Tab traversal or
     *         Space/Enter activation.
     */
    public boolean resolve(@Nullable UIElement focused, KeyStroke stroke, KeyEventType type,
                           long nowMillis) {
        return resolve(focused, stroke, type, nowMillis, false);
    }

    /**
     * @param repeat the platform's auto-repeat flag. Repeats are ignored outright — see below.
     */
    public boolean resolve(@Nullable UIElement focused, KeyStroke stroke, KeyEventType type,
                           long nowMillis, boolean repeat) {
        if (focused == null) return false;

        // A bare modifier press is not a stroke. Ctrl going down is a real key event whose key IS Ctrl,
        // and it can never match a binding — but before this guard it fell all the way through and
        // cancelled any pending chord, so `Mod+K` then `Mod+S` only completed while Ctrl was held
        // without interruption. Release and re-press Ctrl between the two and the prefix was gone.
        //
        // Returning the pending state rather than plain false keeps the key marked as consumed while a
        // chord is in flight, so Tab traversal does not get a look at it mid-chord.
        if (stroke.isBareModifier()) return !pending.isEmpty();

        // Auto-repeat is ignored, and this is not merely a preference.
        //
        // Holding Mod+K made every repeat try to extend the chord with a SECOND Mod+K. Nothing starts
        // with `Mod+K Mod+K`, so each repeat cancelled the prefix and the next one re-armed it — the
        // pending line visibly flickered between "waiting" and idle many times a second, and the chord
        // could only be completed by holding the first stroke down.
        //
        // Ignoring repeats is also the right default independently: a shortcut is an event, not a state.
        // Nobody wants Mod+S to fire thirty times because they leaned on the key. A binding that genuinely
        // wants repeat (arrow-key navigation) is a per-binding opt-in worth adding when something asks.
        if (repeat) return !pending.isEmpty();

        // A RELEASE never touches chord state. It cannot start a chord, cannot continue one, and — the
        // bug this guard exists for — must not CANCEL one: letting go of Mod+K used to wipe the prefix
        // via the fall-through at the bottom of this method, so the only way to complete `Mod+K Mod+S`
        // was to keep the first stroke held down. Chords are sequences of presses, everywhere that has
        // them.
        if (type == KeyEventType.RELEASE) {
            return resolveRelease(focused, stroke);
        }

        if (!pending.isEmpty() && nowMillis - pendingSinceMillis > CHORD_TIMEOUT_MILLIS) {
            cancelPending();
        }

        List<KeyStroke> attempt = new ArrayList<>(pending);
        attempt.add(stroke);

        // Typing guard, evaluated once for the whole walk. Deliberately keyed on the FOCUSED element
        // rather than on each scope: what matters is whether this keystroke is currently being typed
        // into something, not which ancestor happens to own the binding.
        boolean typing = focused.consumesTextInput();

        if (TRACE) trace(focused, stroke, typing);

        boolean prefixMatched = false;
        // Which commands some scope has bound EXPLICITLY. A command that anything in the chain has
        // deliberately bound does not also answer to the default it declared for itself -- otherwise
        // remapping is impossible: rebinding undo to Mod+U would leave Mod+Z working as well, and the
        // old chord could never be taken away. VS Code spells the same idea with a "-command" entry.
        java.util.Set<String> userBound = new java.util.HashSet<>();
        for (UIElement scope = focused; scope != null; scope = scope.getParent()) {
            Keymap keymap = scope.keymapOrNull();
            if (keymap == null) continue;

            for (KeyBinding binding : keymap.bindings()) {
                userBound.add(binding.getCommandId());
            }
            for (KeyBinding binding : keymap.bindings()) {
                if (binding.getEventType() != type) continue;
                if (!binding.getChord().startsWith(attempt)) continue;
                if (typing && !binding.isAllowedWhileTyping() && !stroke.hasNonShiftModifier()) continue;

                if (binding.getChord().length() > attempt.size()) {
                    // A longer chord is still in the running: remember the prefix, but keep scanning in
                    // case some scope has a binding that completes on this very stroke. A complete match
                    // must always beat a partial one, or `Mod+K` alone could never be bound while
                    // `Mod+K Mod+S` exists.
                    prefixMatched = true;
                    continue;
                }
                if (fire(binding, focused)) {
                    cancelPending();
                    return true;
                }
                // Bound but disabled. Keep walking: an outer scope may have its own binding for this
                // chord that IS applicable, which is what lets a disabled editor command fall through to
                // an application-wide one.
            }
        }

        if (prefixMatched) {
            pending.clear();
            pending.addAll(attempt);
            pendingSinceMillis = nowMillis;
            onPendingChanged.emit(new KeyChord(pending));
            return true;
        }

        // No SCOPE claimed it. Fall back to the defaults commands declared for themselves, which are
        // application-wide by construction -- see CommandRegistry.declaredBindings(). Last, so an
        // element-scoped binding always wins and one chord can still mean different things in different
        // widgets.
        for (KeyBinding binding : commands.declaredBindings().bindings()) {
            if (userBound.contains(binding.getCommandId())) continue;
            if (binding.getEventType() != type) continue;
            if (!binding.getChord().startsWith(attempt)) continue;
            if (typing && !binding.isAllowedWhileTyping() && !stroke.hasNonShiftModifier()) continue;
            if (binding.getChord().length() > attempt.size()) {
                prefixMatched = true;
                continue;
            }
            if (fire(binding, focused)) {
                cancelPending();
                return true;
            }
        }

        if (prefixMatched) {
            pending.clear();
            pending.addAll(attempt);
            pendingSinceMillis = nowMillis;
            onPendingChanged.emit(new KeyChord(pending));
            return true;
        }

        // Nothing matched. Any pending prefix is now dead — the user typed something that no chord
        // continues, and silently keeping it would swallow their next keystroke too.
        cancelPending();
        return false;
    }

    /**
     * Set {@code -Dcrystalgui.keymap.trace=true} to log every keystroke and every binding it was offered.
     *
     * <p>"My shortcut does nothing" has four indistinguishable causes from outside — the key arrived as a
     * different code, no scope in the chain holds the binding, the binding is there but disabled, or the
     * typing guard suppressed it — and no amount of reading the source separates them. This prints all
     * four in one line each.</p>
     */
    private static final boolean TRACE = Boolean.getBoolean("crystalgui.keymap.trace");

    private static void trace(UIElement focused, KeyStroke stroke, boolean typing) {
        StringBuilder offered = new StringBuilder();
        for (UIElement scope = focused; scope != null; scope = scope.getParent()) {
            Keymap keymap = scope.keymapOrNull();
            if (keymap == null) continue;
            for (KeyBinding binding : keymap.bindings()) {
                if (offered.length() > 0) offered.append(", ");
                offered.append(scope.tagName()).append(':').append(binding.getChord())
                        .append("->").append(binding.getCommandId());
            }
        }
        CrystalGuiCore.LOGGER.info("[keymap] stroke={} key={} mods={} focused={} typing={} | visible: {}",
                stroke, stroke.key(), stroke.modifiers(), focused.tagName(), typing, offered);
    }

    /**
     * Release bindings only — a flat walk with no chord handling at all.
     *
     * <p>Kept separate rather than folded into the main path so that "a release never touches pending
     * state" is a property of the structure instead of a condition somebody has to keep re-checking.</p>
     */
    private boolean resolveRelease(UIElement focused, KeyStroke stroke) {
        boolean typing = focused.consumesTextInput();
        for (UIElement scope = focused; scope != null; scope = scope.getParent()) {
            Keymap keymap = scope.keymapOrNull();
            if (keymap == null) continue;
            for (KeyBinding binding : keymap.bindings()) {
                if (binding.getEventType() != KeyEventType.RELEASE) continue;
                // Single strokes only. A multi-stroke release binding would need its own pending state,
                // and nothing has ever wanted one — space-to-pan is the whole use case.
                if (binding.getChord().length() != 1) continue;
                if (!binding.getChord().at(0).equals(stroke)) continue;
                if (typing && !binding.isAllowedWhileTyping() && !stroke.hasNonShiftModifier()) continue;
                if (fire(binding, focused)) return true;
            }
        }
        return false;
    }

    private boolean fire(KeyBinding binding, UIElement source) {
        Command command = commands.get(binding.getCommandId());
        if (command == null) {
            // Real, because keymaps and registries are edited separately and a sheet can name a command
            // that no longer exists. Warn rather than throw: one stale entry must not take down every
            // other binding on the same keystroke.
            CrystalGuiCore.LOGGER.warn("Binding {} names command '{}', which is not registered",
                    binding.getChord(), binding.getCommandId());
            return false;
        }
        return command.execute(new CommandContext(source, binding.getArgs()));
    }
}
