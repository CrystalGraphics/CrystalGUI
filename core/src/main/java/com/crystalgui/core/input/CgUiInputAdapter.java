package com.crystalgui.core.input;

import com.crystalgui.core.input.keyboard.CgUiKeyCodes;
import com.crystalgui.core.input.keyboard.Modifiers;
import com.crystalgui.core.input.mouse.CgUiMouseCodes;

/**
 * Interface for translating keys between platforms. <br>
 * <br>
 * {@link CgUiKeyCodes} and {@link CgUiKeyCodes} are LWJGL2-coded, meaning on those platforms the calls can be 1:1 <br>
 * <br>
 * Suggested implementation for other systems:
 * <pre>
 *     {@code
 * public class InputAdapter implements CgUiInputAdapter {
 *
 *     private static final Int2IntMap PLATFORM_TO_LOCAL = new Int2IntOpenHashMap();
 *     private static final Int2IntMap LOCAL_TO_PLATFORM = new Int2IntOpenHashMap();
 *
 *     static {
 *         register(Keyboard.KEY_A, CgUiKeyCodes.KEY_A);
 *         register(Keyboard.KEY_LSHIFT, CgUiKeyCodes.KEY_LEFT_SHIFT);
 *         register(Keyboard.KEY_RSHIFT, CgUiKeyCodes.KEY_RIGHT_SHIFT);
 *         // ... full table
 *     }
 *
 *     private static void register(int platformCode, int localCode) {
 *         PLATFORM_TO_LOCAL.put(platformCode, localCode);
 *         LOCAL_TO_PLATFORM.put(localCode, platformCode);
 *     }
 *
 *     @Override
 *     public int translateKeyboardCode(int platformCode) {
 *         return PLATFORM_TO_LOCAL.getOrDefault(platformCode, CgUiKeyCodes.UNKNOWN);
 *     }
 *
 *     private int translateLocalKeyboardCode(int localCode) {
 *         return LOCAL_TO_PLATFORM.getOrDefault(localCode, -1); // -1 or a sentinel for "no platform equivalent"
 *     }
 *
 *     public boolean isKeyDown(int localCode) {
 *         final int platformCode = LOCAL_TO_PLATFORM.getOrDefault(localCode, -1);
 *         if (platformCode == -1) return false;
 *         return Keyboard.isKeyDown(platformCode);
 *     }
 *
 *     @Override
 *     public int getCurrentModifiers() {
 *         int mods = 0;
 *         if (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT))
 *             mods |= Modifiers.SHIFT;
 *         if (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL))
 *             mods |= Modifiers.CTRL;
 *         if (Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU))
 *             mods |= Modifiers.ALT;
 *         return mods;
 *     }
 * }
 * }
 * </pre>
 */
@SuppressWarnings("unused")
public interface CgUiInputAdapter {

    /**
     * Platform-agnostic way of getting current modifiers (special pressed keys)
     * @return {@link Modifiers}-coded modifier mask
     */
    int getCurrentModifiers();

    /**
     * Platform-agnostic way of reading key codes
     * @param platformCode keycode on the platform you're running.
     * @return {@link CgUiKeyCodes} keycode equivalent.
     */
    int translateKeyboardCodes(int platformCode);

    boolean isKeyDown(int localKeyCode);


    /**
     * Platform-agnostic way of checking button codes. <br>
     * Usually 1:1 buttons IDs.
     * @param platformCode mouse button on the platform you're running
     * @return {@link CgUiMouseCodes} keycode equivalent
     */
    default int translateMouseCodes(int platformCode) {
        return platformCode;
    }

    boolean isMouseDown(int localMouseCode);

    /**
     * @return How many mouse keys are supported by your platform
     */
    int howManyMouseButtons();
}
