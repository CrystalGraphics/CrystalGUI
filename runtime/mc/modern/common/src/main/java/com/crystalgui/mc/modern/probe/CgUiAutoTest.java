package com.crystalgui.mc.modern.probe;

import java.io.File;

import javax.annotation.Nullable;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.desktop.host.HostSession;
import com.crystalgui.mc.modern.client.CgUiScreen;
import com.crystalgui.probe.AutoTest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;

/**
 * The MC 1.20.x half of {@link AutoTest}: load a world, open the desktop, photograph it, quit.
 *
 * <p>The sequence is {@code core}'s. What is here is how this era loads a save, takes a screenshot and
 * stops — and the screenshot is the interesting one, because it is <b>asynchronous</b>.</p>
 *
 * <p>Easy to get wrong: {@code -Dcrystalgui.autotest.out} must contain no space. Prism strips quotes
 * from a {@code JvmArgs} value and splits on spaces anyway, gluing the tail onto the next argument, and
 * the failure is launcher-side so no log is written at all.</p>
 */
public final class CgUiAutoTest {

    /** @see AutoTest#ENABLED */
    public static final boolean ENABLED = AutoTest.ENABLED;

    /** How long to keep trying to move a capture out of {@code screenshots/}: 150 x 20ms = 3s. */
    private static final int MOVE_ATTEMPTS = 150;

    private static final long MOVE_RETRY_MS = 20;

    /** TICKS on this era. @see AutoTest.Host */
    private static int sinceOpen;

    private CgUiAutoTest() {
    }

    /** Called once per client tick. Cheap when off: one static boolean read. */
    public static void tick() {
        if (!ENABLED) return;
        AutoTest.tick(HOST);
        AutoTest.settled(HOST, sinceOpen++);
    }

    /**
     * Gets this client into a world, so an unattended run needs nobody at the keyboard.
     *
     * <p><b>It loads; it does not create.</b> 1.7.10 makes a superflat world when there is none, and
     * this era deliberately does not: {@code createFreshLevel} needs a {@code LevelSettings}, a
     * {@code WorldOptions} and a {@code Function<RegistryAccess, WorldDimensions>}, and 1.20.4 changed
     * its signature — a lot of version-sensitive construction for a run that can simply be given a save.
     * With none it says so, once, and the caller stops asking.</p>
     *
     * @return whether the request has been made; false while resources are still reloading
     */
    static boolean enterWorld() {
        Minecraft mc = Minecraft.getInstance();
        // A client tick fires underneath the loading overlay, so a launch requested before the game is
        // up races Mojang's splash.
        if (mc == null || mc.getOverlay() != null) return false;
        // -PcgJoin pointed this client at a server: it is already on its way, and there is no save.
        if (mc.getCurrentServer() != null) return true;
        loadWorld(mc);
        return true;
    }

    private static final AutoTest.Host HOST = new AutoTest.Host() {

        /**
         * A client tick already fires underneath the loading overlay, so a countdown started before the
         * game is up expires while Mojang's splash is still the only thing drawn — and the splash is
         * then what gets photographed. Fabric captured exactly that where Forge did not.
         */
        @Override
        public boolean readyToDrive() {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && mc.getOverlay() == null;
        }

        @Override
        public boolean inWorld() {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && mc.level != null;
        }

        @Override
        public boolean enterWorld() {
            return CgUiAutoTest.enterWorld();
        }

        @Override
        public void openDesktop() {
            sinceOpen = 0;
            CgUiScreen.openEditor();
        }

        @Override
        public void capture(String path) {
            shoot(path);
        }

        @Override
        public void quit() {
            CrystalGuiCore.LOGGER.info("CGUI AUTOTEST done; quitting");
            // stop() rather than System.exit: it runs Minecraft's own shutdown, which is what
            // CgGraphicsLifecycle.destroyContext hangs off. Exiting under it would skip the teardown
            // this is partly here to exercise.
            Minecraft.getInstance().stop();
        }

        @Override
        public int openAfter() {
            return Integer.getInteger(AutoTest.OPEN_AFTER, 60);
        }

        @Override
        public int captureAt() {
            return Integer.getInteger(AutoTest.CAPTURE_AT, 20);
        }

        @Override
        public int lateCaptureAt() {
            return Integer.getInteger(AutoTest.LATE_CAPTURE_AT, 0);
        }
    };

    /** Asks Minecraft to load the save, through whichever entry point this version has. */
    private static void loadWorld(Minecraft mc) {
        String name = resolveWorld(mc);
        if (name == null) {
            CrystalGuiCore.LOGGER.warn("CGUI AUTOTEST no save under {}; staying on the title screen",
                    new File(mc.gameDirectory, "saves"));
            return;
        }
        CrystalGuiCore.LOGGER.info("CGUI AUTOTEST loading world '{}'", name);
        WorldOpenFlows flows = mc.createWorldOpenFlows();
        // COMPILED on both sides of the break, never reflective: each thin jar is remapped as it is
        // built, so this becomes the SRG member on Forge and the intermediary one on Fabric, where a
        // lookup by Mojang name is a string no remapper rewrites. The Runnable is the GIVE-UP path,
        // taken when the save cannot be read, not a completion callback. `openWorld` is measured on
        // 1.21.1 and `checkForBackupAndLoad` on 1.20.4; the boundary between them moves to the first
        // version a node between the two is built on.
        //? if >=1.21 {
        /*flows.openWorld(name, () -> { });
        *///?} elif >=1.20.2 {
        /*flows.checkForBackupAndLoad(name, () -> { });
        *///?} else {
        flows.loadLevel(mc.screen, name);
        //?}
    }

    /**
     * The named save, or the first on disk when {@code *}; null when there is none.
     *
     * <p>Null means "stay on the title screen" here — this host cannot create one, unlike 1.7.10.
     * Worth loading one though: the editor application needs a SERVER, so on the title screen the
     * desktop comes up empty and a capture of it proves only that the screen opened. A singleplayer
     * world brings an integrated server with it, which is what lets {@code crystalgui:editor} launch.
     * @see AutoTest#resolveWorld</p>
     */
    @Nullable
    private static String resolveWorld(Minecraft mc) {
        return AutoTest.resolveWorld(new File(mc.gameDirectory, "saves"));
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
                HostSession.isInstalled() && HostSession.session().hasPainted());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
