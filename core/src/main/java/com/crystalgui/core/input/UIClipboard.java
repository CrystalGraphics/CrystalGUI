package com.crystalgui.core.input;

/**
 * System clipboard access, as a platform SPI — {@code core} is loader-blind and has no way to reach
 * one itself.
 *
 * <p>Register an implementation on {@link com.crystalgui.core.CrystalGuiCore}, the same shape as
 * {@link com.crystalgui.core.sound.UISoundSystem}. Unset, {@link #NOOP} makes copy/paste silently do
 * nothing rather than throwing, so a host that never wires one up still runs.</p>
 */
public interface UIClipboard {

    /** Current clipboard contents, or an empty string when empty/unavailable. Never {@code null}. */
    String get();

    void set(String text);

    /** Reads as permanently empty and discards writes. */
    UIClipboard NOOP = new UIClipboard() {
        @Override public String get() { return ""; }
        @Override public void set(String text) { }
    };
}
