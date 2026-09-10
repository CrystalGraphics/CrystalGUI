package com.crystalgui.mc.client;

import com.crystalgui.core.CrystalGuiCore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;

import java.io.File;
import java.lang.reflect.Method;

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
 * <p>Mirrors {@code CgUiAutoTest} on 1.7.10, which has done this since before the single jar.</p>
 *
 * <p>It LOADS A WORLD first, given {@code -Dcrystalgui.autotest.world}. It did not, on the reasoning
 * that the desktop opens over the menu just as it does over a world — and the capture that produced
 * was a photograph of the title screen, because the editor needs a SERVER, so the desktop came up
 * empty and painted nothing over a colour buffer Minecraft does not clear without a level. A
 * singleplayer world brings an integrated server, and with it something on the desktop to photograph.
 * Loading one needs a version-specific call and this module runs on 1.20.1 and 1.20.4 both, which is
 * what {@link #loadWorld} is for.</p>
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

    /** How long to keep trying to move a capture out of {@code screenshots/}: 150 x 20ms = 3s. */
    private static final int MOVE_ATTEMPTS = 150;

    private static final long MOVE_RETRY_MS = 20;

    /**
     * The save to load before opening, or null to stay on the title screen. {@code *} takes the first.
     *
     * <p>Worth loading one: the editor application needs a SERVER, so on the title screen the desktop
     * comes up empty and a capture of it proves only that the screen opened. A singleplayer world
     * brings an integrated server with it, which is what lets {@code crystalgui:editor} launch.</p>
     *
     * <p>NO SPACE IN THE VALUE. Prism splits a {@code JvmArgs} value on spaces whatever the quoting,
     * so {@code -Dcrystalgui.autotest.world=New World} arrives as two arguments and the JVM dies
     * naming a main class it cannot find — which is why {@code *} exists.</p>
     */
    private static final String WORLD = emptyToNull(System.getProperty("crystalgui.autotest.world"));

    private static int ticks;
    private static boolean worldRequested;
    private static boolean opened;
    private static boolean captured;
    private static boolean captureLate;

    private CgUiAutoTest1201() {
    }

    /** Called once per client tick. Cheap when off: one static boolean read. */
    public static void tick() {
        if (!ENABLED) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        // A client tick already fires underneath the loading overlay, so a countdown started before
        // the game is up expires while Mojang's splash is still the only thing drawn -- and the splash
        // is then what gets photographed. Fabric captured exactly that where Forge did not, which is
        // why a tick count is not by itself a statement that there is a game to open a desktop over.
        if (mc.getOverlay() != null) return;
        ticks++;

        if (WORLD != null && !worldRequested) {
            if (ticks < OPEN_AFTER_TICKS) return;
            worldRequested = true;
            ticks = 0;
            loadWorld(mc);
            return;
        }
        // Chunk loading, the integrated server starting and the resource reload that comes with it all
        // land after the request returns, so the clock only starts once there is a level.
        if (WORLD != null && mc.level == null) {
            ticks = 0;
            return;
        }

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

    /**
     * Asks Minecraft to load {@link #WORLD}, through whichever entry point this version has.
     *
     * <p>1.20.1 is the compiled path and 1.20.4 the reflective one, because there is no common method
     * and this module runs on both: 1.20.1 has {@code loadLevel(Screen, String)} and 1.20.4
     * deleted it in favour of {@code checkForBackupAndLoad(String, Runnable)}.</p>
     */
    private static void loadWorld(Minecraft mc) {
        String name = resolveWorld(mc);
        if (name == null) {
            CrystalGuiCore.LOGGER.warn("CGUI AUTOTEST no save under {}; staying on the title screen",
                    new File(mc.gameDirectory, "saves"));
            return;
        }
        CrystalGuiCore.LOGGER.info("CGUI AUTOTEST loading world '{}'", name);
        WorldOpenFlows flows = mc.createWorldOpenFlows();
        // A COMPILED call, not a reflective one: each loader's thin jar is remapped as it is built, so
        // this becomes the SRG member on Forge and the intermediary one on Fabric. A reflective lookup
        // by Mojang name is a plain string and no remapper rewrites a string, so it resolved on
        // NeoForge -- which runs official names -- and on neither of the others.
        try {
            flows.loadLevel(mc.screen, name);
            return;
        } catch (NoSuchMethodError deletedIn1204) {
            // 1.20.4 dropped loadLevel. That build DOES run official names, so the lookup below works
            // there for the same reason it failed above.
        }
        // The Runnable is the GIVE-UP path -- taken when the save cannot be read -- not a completion
        // callback, so there is nothing to do in it.
        if (call(flows, "checkForBackupAndLoad", new Class<?>[] {String.class, Runnable.class},
                name, (Runnable) () -> { })) return;
        CrystalGuiCore.LOGGER.warn("CGUI AUTOTEST {} offers no world-open method this build knows",
                flows.getClass().getName());
    }

    /** @return true when the method EXISTS, whether or not the call itself then succeeded. */
    private static boolean call(Object target, String name, Class<?>[] types, Object... args) {
        Method method;
        try {
            method = target.getClass().getMethod(name, types);
        } catch (NoSuchMethodException otherVersion) {
            return false;
        }
        try {
            method.invoke(target, args);
        } catch (Throwable failed) {
            CrystalGuiCore.LOGGER.warn("CGUI AUTOTEST {} threw", name, failed);
        }
        return true;
    }

    /** The named save, or the first one on disk when {@code *}; null when there is none. */
    private static String resolveWorld(Minecraft mc) {
        if (!"*".equals(WORLD)) return WORLD;
        File[] saves = new File(mc.gameDirectory, "saves").listFiles();
        if (saves == null) return null;
        for (File save : saves) {
            if (new File(save, "level.dat").isFile()) return save.getName();
        }
        return null;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static void shoot(String path) {
        Minecraft mc = Minecraft.getInstance();
        File file = new File(path);
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        // Screenshot.grab's first argument is the GAME DIRECTORY, not the output directory: it writes
        // to <dir>/screenshots/<name> and creates that subdirectory itself. So the frame is grabbed
        // into it and then moved to the path that was actually asked for -- otherwise a caller that
        // waits for its own path sees nothing and calls a successful run a failure.
        File gameDir = parent == null ? new File(".") : parent;
        Screenshot.grab(gameDir, file.getName(), mc.getMainRenderTarget(), message -> { });

        // The PNG is encoded on Util.ioPool(), so it does NOT exist when grab returns -- and the first
        // capture additionally pays for that pool starting its thread, which is why an immediate move
        // left the early one behind in screenshots/ and moved the late one. Retrying is also what
        // makes a partial file safe: renameTo fails while the writer still holds it, so a rename that
        // succeeds is itself the proof that the write finished.
        File written = new File(new File(gameDir, "screenshots"), file.getName());
        boolean moved = written.equals(file);
        for (int attempt = 0; !moved && attempt < MOVE_ATTEMPTS; attempt++) {
            if (written.isFile()) {
                file.delete();
                moved = written.renameTo(file);
            }
            if (!moved) sleep(MOVE_RETRY_MS);
        }
        if (!moved) {
            // Never claim the path that was asked for unless the file is on it: this line is the only
            // evidence a caller has, and an unconditional one reported a capture that was not there.
            CrystalGuiCore.LOGGER.warn("CGUI AUTOTEST capture stayed at {}; nothing was written to {}",
                    written.getAbsolutePath(), file.getAbsolutePath());
            return;
        }
        // WHETHER THE DESKTOP ACTUALLY PAINTED, beside the file. A capture proves only that a frame was
        // read back: with no live GL context the screen's render() returns at once, and with no level
        // Minecraft does not clear the colour buffer, so the frame still holds the PREVIOUS screen and
        // a photograph of the main menu is indistinguishable from a working desktop.
        CrystalGuiCore.LOGGER.info("CGUI AUTOTEST wrote {}x{} capture to {} (desktop painted: {})",
                mc.getMainRenderTarget().width, mc.getMainRenderTarget().height, file.getAbsolutePath(),
                CgUiScreen1201.hasPainted());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void quit() {
        CrystalGuiCore.LOGGER.info("CGUI AUTOTEST done; quitting");
        // stop() rather than System.exit: it runs Minecraft's own shutdown, which is what
        // CgGraphicsLifecycle.destroyContext hangs off. Exiting under it would skip the teardown this
        // is partly here to exercise.
        Minecraft.getInstance().stop();
    }
}
