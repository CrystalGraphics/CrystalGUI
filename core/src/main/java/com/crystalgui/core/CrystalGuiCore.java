package com.crystalgui.core;


import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * CrystalGUI's logger, and nothing else.
 *
 * <p>This class used to also be the registry for the engine's platform seams — an input adapter, a
 * clipboard, a sound system, a cursor service — each with its own static field and setter. Those moved to
 * CrystalGraphics' {@code platform} module, where they are reached through
 * {@link com.crystalgraphics.platform.CgPlatform}:</p>
 *
 * <table border="1">
 *   <caption>Where the old registry went</caption>
 *   <tr><th>Was</th><th>Is</th></tr>
 *   <tr><td>{@code CrystalGuiCore.getAdapter()}</td><td>{@code CgPlatform.input()}</td></tr>
 *   <tr><td>{@code CrystalGuiCore.getClipboard().get()}</td><td>{@code CgPlatform.input().getClipboard()}</td></tr>
 *   <tr><td>{@code CrystalGuiCore.getSoundSystem()}</td><td>{@code CgPlatform.sound()}</td></tr>
 *   <tr><td>{@code CrystalGuiCore.getCursorService()}</td><td>{@code CgPlatform.cursor()}</td></tr>
 * </table>
 *
 * <p><b>Why they left.</b> CrystalGraphics is CrystalGUI's parent project and is always present, so two
 * separate registries meant a loader had to know about both and register with each — and could get half
 * way, leaving a UI with a working GL backend and no keyboard. One
 * {@link com.crystalgraphics.platform.CgPlatformService} bundle now carries all of it, so a platform is
 * either wired up or it is not.</p>
 *
 * <p><b>Why the logger did not go with them.</b> It names CrystalGUI, and CrystalGraphics has no central
 * logger to fold it into — every class there calls {@code LogManager.getLogger} for itself. Moving it
 * would mean inventing a shared logger in the parent project purely to hold a child project's name.</p>
 */
public class CrystalGuiCore {

    public static final Logger LOGGER = LogManager.getLogger("CrystalGui");

}
