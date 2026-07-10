package com.crystalgui.core.event;

/**
 * Platform-agnostic key code constants for CrystalGUI core.
 *
 * <p>Only keys needed for phases 0–3 are defined here. Platform adapters
 * translate native key codes (GLFW, LWJGL, etc.) into these constants
 * before dispatching into the core event system.</p>
 *
 * <p>No LWJGL/GLFW imports allowed in this class.</p>
 */
public final class CgUiKeyCodes {

    private CgUiKeyCodes() {}

    public static final int KEY_TAB       = 0x09;
    public static final int KEY_BACKSPACE = 0x0E;
    public static final int KEY_ENTER     = 0x1C;
    public static final int KEY_DELETE    = 0xD3;
    public static final int KEY_LEFT      = 0xCB;
    public static final int KEY_RIGHT     = 0xCD;
    public static final int KEY_HOME      = 0xC7;
    public static final int KEY_END       = 0xCF;
    public static final int KEY_A         = 0x1E;
}
