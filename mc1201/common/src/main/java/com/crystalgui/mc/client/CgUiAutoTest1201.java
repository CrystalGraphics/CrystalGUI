package com.crystalgui.mc.client;

import com.crystalgui.core.CrystalGuiCore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.io.File;

/**
 * Opens the editor, photographs it and quits — with no hand on the mouse.
 *
 * <p>Off unless {@code -Dcrystalgui.autotest=true}, which is set in an installed client through
 * PrismLauncher's {@code JvmArgs}. That is the whole point: a dev run resolves classes from source
 * sets, so nothing there can see what happens to a class <i>file</i> — relocation, remapping,
 * downgrading, a merged manifest. Only an installed client can, and only an unattended one can be
 * driven for every target in turn.</p>
 *
 * <pre>
 * JvmArgs=-Dcrystalgui.autotest=true -Dcrystalgui.autotest.out=X:/out/shot.png
 * </pre>
 *
 * <p>Mirrors {@code CgUiAutoTest} on 1.7.10, which has done this since before the single jar. The
 * difference is that this one <b>stays on the title screen</b>: loading a save needs a
 * version-specific call, and `mc1201/common` is compiled against 1.20.1 and run on 1.20.4 as well.
 * The desktop opens over the menu just as it does over a world, so the capture still exercises the
 * whole render path — context, paint context, compositor, taskbar. What it cannot show is the editor
 * application itself, which needs a server.</p>
 *
 * <p>Easy to get wrong: {@code -Dcrystalgui.autotest.out} must contain no space. Prism strips quotes
 * from a {@code JvmArgs} value and splits on spaces anyway, gluing the tail onto the next argument,
 * and the failure is launcher-side so no log is written at all.</p>
 */
public final class CgUiAutoTest1201 {

    /** @see CgUiAutoTest1201 */
    public static final boolean ENABLED = Boolean.getBoolean("crystalgui.autotest");

    /** Where the capture is written. No spaces. */
    private static final String OUTPUT =
            System.getProperty("crystalgui.autotest.out", "crystalgui-autotest.png");

    /**
     * Ticks at the menu before opening.
     *
     * <p>Not zero: the first client tick fires while resources are still reloading, and opening then
     * measures a half-initialised game rather than the desktop.</p>
     */
    private static final int OPEN_AFTER_TICKS = Integer.getInteger("crystalgui.autotest.openTick", 60);

    /**
     * Ticks after opening before the capture.
     *
     * <p>Layout settles over several passes — {@code UIText} re-measures and pushes its height back
     * until it stops changing — so a capture on the first frame shows a tree mid-settle and reads as
     * a layout bug that is really an early screenshot.</p>
     */
    private static final int CAPTURE_AFTER_TICKS = Integer.getInteger("crystalgui.autotest.frame", 20);

    /** A SECOND capture, later, beside the first with a {@code -late} suffix. 0 disables it. */
    private static final int LATE_CAPTURE_TICKS = Integer.getInteger("crystalgui.autotest.lateFrame", 0);

    private static int ticks;
    private static boolean opened;
    private static boolean captured;
    private static boolean captureLate;

    private CgUiAutoTest1201() {
    }

    /** Called once per client tick. Cheap when off: one static boolean read. */
    public static void tick() {
        if (!ENABLED) return;
        ticks++;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        if (!opened) {
            if (ticks < OPEN_AFTER_TICKS) return;
            CrystalGuiCore.LOGGER.info("CGUI AUTOTEST opening the desktop");
            CgUiScreen1201.openEditor();
            opened = true;
            ticks = 0;
            return;
        }
        if (!captured) {
            if (ticks < CAPTURE_AFTER_TICKS) return;
            shoot(OUTPUT.replace(".png", "-early.png"));
            captured = true;
            ticks = 0;
            if (LATE_CAPTURE_TICKS <= 0) quit();
            return;
        }
        if (!captureLate) {
            if (ticks < LATE_CAPTURE_TICKS) return;
            // The first capture proves the desktop renders; it cannot prove it KEEPS rendering. A
            // resource freed or a pooled buffer recycled after a few hundred frames looks identical
            // to "it never worked" to somebody watching and identical to "it works" to one capture.
            shoot(OUTPUT.replace(".png", "-late.png"));
            captureLate = true;
            quit();
        }
    }

    private static void shoot(String path) {
        Minecraft mc = Minecraft.getInstance();
        File file = new File(path);
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        // Screenshot.grab takes a DIRECTORY and a name, so the target is split rather than passed
        // whole; it writes exactly the frame the player would see, target and all.
        Screenshot.grab(parent == null ? new File(".") : parent, file.getName(),
                mc.getMainRenderTarget(), message -> { });
        CrystalGuiCore.LOGGER.info("CGUI AUTOTEST wrote {}x{} capture to {}",
                mc.getMainRenderTarget().width, mc.getMainRenderTarget().height, file.getAbsolutePath());
    }

    private static void quit() {
        CrystalGuiCore.LOGGER.info("CGUI AUTOTEST done; quitting");
        // stop() rather than System.exit: it runs Minecraft's own shutdown, which is what
        // CgGraphicsLifecycle.destroyContext hangs off. Exiting under it would skip the teardown this
        // is partly here to exercise.
        Minecraft.getInstance().stop();
    }
}
