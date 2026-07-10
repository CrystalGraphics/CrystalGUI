package com.crystalgui.mc;

import com.crystalgui.core.event.UiEventType;
import com.crystalgui.core.event.UiKeyEvent;
import com.crystalgui.core.input.FocusManager;
import com.crystalgui.core.input.UiInputManager;
import com.crystalgui.ui.UIContainer;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * Drains LWJGL 2 Mouse and Keyboard event queues and forwards translated
 * events into a CrystalGUI {@link UIContainer}'s input system.
 *
 * <p>This is a stateless utility — it does not own or manage the container,
 * does not extend any Minecraft class, and makes no assumptions about the
 * host context. The caller decides <em>when</em> to drain input (e.g. once
 * per frame from a GuiScreen, an overlay renderer, or any other host) and
 * <em>how</em> to transform coordinates via the {@link CoordinateTransform}.</p>
 *
 * <h3>Coordinate transform</h3>
 * <p>LWJGL reports mouse positions in raw display pixels with Y=0 at bottom.
 * CrystalGUI expects top-left origin coordinates in whatever unit the layout
 * uses (typically GUI-scaled pixels). The {@link CoordinateTransform} callback
 * lets the host supply any scaling/flipping logic without baking Minecraft's
 * {@code ScaledResolution} into this adapter.</p>
 *
 * <h3>Keyboard handling</h3>
 * <p>The adapter translates LWJGL key codes to {@link com.crystalgui.core.event.CgUiKeyCodes}
 * via {@link LwjglKeyTranslator} and synthesizes KEY_DOWN + KEY_TYPED (for
 * printable characters) on press, KEY_UP on release.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // From any host (GuiScreen, overlay, HUD, etc.):
 * CgUiInputAdapater.drainMouseEvents(container, (rawX, rawY) -> {
 *     float scale = scaledResolution.getScaleFactor();
 *     return new float[]{ rawX / scale, (displayHeight - rawY) / scale };
 * });
 * CgUiInputAdapater.drainKeyboardEvents(container, null);
 * }</pre>
 */
public final class CgUiInputAdapter {

    private CgUiInputAdapter() {}

    /**
     * Transforms raw LWJGL display-pixel mouse coordinates into
     * CrystalGUI container-space coordinates.
     */
    @FunctionalInterface
    public interface CoordinateTransform {
        /**
         * @param rawX raw pixel X from {@code Mouse.getEventX()}
         * @param rawY raw pixel Y from {@code Mouse.getEventY()} (Y=0 at bottom)
         * @return float[2] with {transformedX, transformedY} in container-space (Y=0 at top)
         */
        float[] transform(int rawX, int rawY);
    }

    /**
     * Callback interface for keyboard events that the host wants to intercept
     * before they reach CrystalGUI (e.g. ESC to close a screen).
     */
    @FunctionalInterface
    public interface KeyFilter {
        /**
         * @param lwjglKey the raw LWJGL key code
         * @param character the typed character
         * @param pressed true for key-down, false for key-up
         * @return true if the event was consumed by the host and should NOT
         *         be forwarded to CrystalGUI
         */
        boolean consume(int lwjglKey, char character, boolean pressed);
    }

    /**
     * Drains the entire LWJGL Mouse event queue and forwards each event to
     * the container's {@link UiInputManager}.
     *
     * @param container  the target container (must have an interaction context)
     * @param transform  coordinate transformer (raw display pixels → container-space)
     */
    public static void drainMouseEvents(UIContainer container, CoordinateTransform transform) {
        UiInputManager input = container.getInputManager();
        if (input == null) return;

        while (Mouse.next()) {
            int rawX = Mouse.getEventX();
            int rawY = Mouse.getEventY();
            float[] pos = transform.transform(rawX, rawY);
            dispatchCurrentMouseEvent(input, pos[0], pos[1]);
        }
    }

    /**
     * Drains the entire LWJGL Keyboard event queue and forwards each event to
     * the container's {@link FocusManager}.
     *
     * <p>If a {@link KeyFilter} is provided, each event is offered to the filter
     * first. If the filter returns {@code true}, the event is consumed by the host
     * and not forwarded to CrystalGUI.</p>
     *
     * @param container  the target container (must have an interaction context)
     * @param keyFilter  optional host-side key filter (e.g. ESC handling), may be null
     */
    public static void drainKeyboardEvents(UIContainer container, KeyFilter keyFilter) {
        FocusManager focus = container.getFocusManager();
        if (focus == null) return;

        while (Keyboard.next()) {
            int lwjglKey = Keyboard.getEventKey();
            char ch = Keyboard.getEventCharacter();
            boolean pressed = Keyboard.getEventKeyState();

            // Let host intercept first (e.g. ESC to close)
            if (keyFilter != null && keyFilter.consume(lwjglKey, ch, pressed)) {
                continue;
            }

            int keyCode = LwjglKeyTranslator.translateKeyCode(lwjglKey);
            int mods = LwjglKeyTranslator.currentModifiers();

            if (pressed) {
                UiKeyEvent keyDown = UiKeyEvent.key(UiEventType.KEY_DOWN, keyCode, mods);
                focus.dispatchKeyEvent(keyDown);

                if (ch >= 0x20 && ch != 0x7F) {
                    UiKeyEvent typed = UiKeyEvent.typed(ch, mods);
                    focus.dispatchKeyEvent(typed);
                }
            } else {
                UiKeyEvent keyUp = UiKeyEvent.key(UiEventType.KEY_UP, keyCode, mods);
                focus.dispatchKeyEvent(keyUp);
            }
        }
    }

    /**
     * Convenience: drains both Mouse and Keyboard queues in one call.
     *
     * @param container  the target container
     * @param transform  coordinate transformer
     * @param keyFilter  optional host-side key filter, may be null
     */
    public static void drainAllEvents(UIContainer container, CoordinateTransform transform, KeyFilter keyFilter) {
        drainMouseEvents(container, transform);
        drainKeyboardEvents(container, keyFilter);
    }

    // ── Single-event forwarding (for hosts that own the event loop) ────

    /**
     * Forwards a single already-extracted keyboard event to the container's
     * {@link FocusManager}.
     *
     * <p>Use this when the caller owns the {@code Keyboard.next()} loop
     * (e.g. the harness pause handler, or Forge {@code InputEvent.KeyInputEvent}
     * where the LWJGL event has already been consumed from the queue).</p>
     *
     * @param container  the target container
     * @param lwjglKey   raw LWJGL key code (from {@code Keyboard.getEventKey()})
     * @param character  typed character (from {@code Keyboard.getEventCharacter()})
     * @param pressed    true for key-down, false for key-up
     */
    public static void forwardKeyEvent(UIContainer container, int lwjglKey, char character, boolean pressed) {
        FocusManager focus = container.getFocusManager();
        if (focus == null) return;

        int keyCode = LwjglKeyTranslator.translateKeyCode(lwjglKey);
        int mods = LwjglKeyTranslator.currentModifiers();

        if (pressed) {
            UiKeyEvent keyDown = UiKeyEvent.key(UiEventType.KEY_DOWN, keyCode, mods);
            focus.dispatchKeyEvent(keyDown);

            if (character >= 0x20 && character != 0x7F) {
                UiKeyEvent typed = UiKeyEvent.typed(character, mods);
                focus.dispatchKeyEvent(typed);
            }
        } else {
            UiKeyEvent keyUp = UiKeyEvent.key(UiEventType.KEY_UP, keyCode, mods);
            focus.dispatchKeyEvent(keyUp);
        }
    }

    /**
     * Forwards the <em>current</em> LWJGL Mouse event (the one most recently
     * returned by {@code Mouse.next()}) to the container's {@link UiInputManager}.
     *
     * <p>Use this from Forge {@code InputEvent.MouseInputEvent} where Minecraft
     * has already advanced the Mouse queue and the current event data is available
     * via {@code Mouse.getEventX()}, {@code Mouse.getEventButton()}, etc.</p>
     *
     * @param container  the target container
     * @param transform  coordinate transformer
     */
    public static void forwardCurrentMouseEvent(UIContainer container, CoordinateTransform transform) {
        UiInputManager input = container.getInputManager();
        if (input == null) return;

        int rawX = Mouse.getEventX();
        int rawY = Mouse.getEventY();
        float[] pos = transform.transform(rawX, rawY);
        dispatchCurrentMouseEvent(input, pos[0], pos[1]);
    }

    /**
     * Forwards the <em>current</em> LWJGL Keyboard event to the container.
     *
     * <p>Use this from Forge {@code InputEvent.KeyInputEvent} where Minecraft
     * has already advanced the Keyboard queue.</p>
     *
     * @param container  the target container
     * @param keyFilter  optional host-side key filter, may be null
     */
    public static void forwardCurrentKeyEvent(UIContainer container, KeyFilter keyFilter) {
        FocusManager focus = container.getFocusManager();
        if (focus == null) return;

        int lwjglKey = Keyboard.getEventKey();
        char ch = Keyboard.getEventCharacter();
        boolean pressed = Keyboard.getEventKeyState();

        if (keyFilter != null && keyFilter.consume(lwjglKey, ch, pressed)) {
            return;
        }

        forwardKeyEvent(container, lwjglKey, ch, pressed);
    }

    // ── Shared mouse dispatch helper ──────────────────────────────────────

    /**
     * Dispatches the current LWJGL Mouse event to the given input manager.
     * Reads button, scroll, and modifier state from the current LWJGL event.
     */
    private static void dispatchCurrentMouseEvent(UiInputManager input, float x, float y) {
        int button = Mouse.getEventButton();
        int dWheel = Mouse.getEventDWheel();
        int mods = LwjglKeyTranslator.currentModifiers();

        if (button == -1 && dWheel == 0) {
            input.processMouseMove(x, y, mods);
        }
        if (button >= 0) {
            if (Mouse.getEventButtonState()) {
                input.processMouseDown(x, y, button, mods);
            } else {
                input.processMouseUp(x, y, button, mods);
            }
        }
        if (dWheel != 0) {
            input.processMouseWheel(x, y, dWheel > 0 ? 1.0f : -1.0f, mods);
        }
    }
}
