package com.crystalgui.ui.input.keymap;

/**
 * Whether a binding fires when the key goes down or when it comes back up.
 *
 * <p><b>Two constants, not five.</b> Blender's keymap has press / release / click / double-click / drag,
 * and the axis is worth having — space-to-pan is a <em>hold</em>, so a press-only keymap cannot express
 * it and would need a second mechanism bolted on later. But click, double-click and drag are mouse
 * concepts {@code UIInputHandler} already owns, and importing them here would be borrowing a vocabulary
 * rather than a capability. Take the axis, leave the enum.</p>
 */
public enum KeyEventType {
    PRESS,
    RELEASE
}
