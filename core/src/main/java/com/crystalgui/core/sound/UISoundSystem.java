package com.crystalgui.core.sound;

/**
 * Loader-blind seam for playing UI sounds (button clicks, etc.), matching the same
 * platform-registration shape as {@link com.crystalgui.core.input.CgUiInputAdapter}
 * ({@link com.crystalgui.core.CrystalGuiCore#getSoundSystem()}/{@code setSoundSystem}). Sound is
 * purely cosmetic — widgets call this unconditionally; {@link #NOOP} is the default so no loader
 * has to register anything for core widgets to function.
 */
public interface UISoundSystem {

    /** Plays a named UI sound (e.g. {@code "button_click"}). No-op for unrecognized ids. */
    void play(String soundId);

    UISoundSystem NOOP = soundId -> {};
}
