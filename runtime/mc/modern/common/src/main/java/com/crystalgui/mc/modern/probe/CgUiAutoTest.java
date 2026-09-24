package com.crystalgui.mc.modern.probe;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.desktop.host.HostSession;
import com.crystalgui.mc.modern.client.CgUiScreen;
import com.crystalgui.probe.AutoTest;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
//? if >=1.19 {
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
//?}

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

    /** Ticks a quit waits for captures still being written: 200 = 10s. */
    private static final int QUIT_WAIT_TICKS = 200;

    /** Captures grabbed and not yet on disk. Written from Minecraft's IO pool. */
    private static final AtomicInteger PENDING_CAPTURES = new AtomicInteger();

    /** Ticks since the sequence asked to quit, or -1 until it does. */
    private static int quitWaited = -1;

    /** TICKS on this era. @see AutoTest.Host */
    private static int sinceOpen;

    private CgUiAutoTest() {
    }

    /** Called once per client tick. Cheap when off: one static boolean read. */
    public static void tick() {
        if (!ENABLED) return;
        if (quitWaited >= 0) {
            quitWhenWritten();
            return;
        }
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
            quitWaited = 0;
            quitWhenWritten();
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

    /**
     * Quits once every capture is on disk, or after {@link #QUIT_WAIT_TICKS}. From 1.21.5 a screenshot is
     * a GPU readback that completes on a LATER frame, so the render thread has to keep running for it.
     */
    private static void quitWhenWritten() {
        if (PENDING_CAPTURES.get() > 0 && quitWaited++ < QUIT_WAIT_TICKS) return;
        quitWaited = Integer.MIN_VALUE;
        CrystalGuiCore.LOGGER.info("CGUI AUTOTEST done; quitting");
        // stop() rather than System.exit: it runs Minecraft's own shutdown, which is what
        // CgGraphicsLifecycle.destroyContext hangs off. Exiting under it would skip the teardown
        // this is partly here to exercise.
        Minecraft.getInstance().stop();
    }

    /** Asks Minecraft to load the save, through whichever entry point this version has. */
    private static void loadWorld(Minecraft mc) {
        String name = resolveWorld(mc);
        if (name == null) {
            CrystalGuiCore.LOGGER.warn("CGUI AUTOTEST no save under {}; staying on the title screen",
                    new File(mc.gameDirectory, "saves"));
            return;
        }
        CrystalGuiCore.LOGGER.info("CGUI AUTOTEST loading world '{}'", name);
        // COMPILED on both sides of the break, never reflective: each thin jar is remapped as it is
        // built, so this becomes the SRG member on Forge and the intermediary one on Fabric, where a
        // lookup by Mojang name is a string no remapper rewrites. The Runnable is the GIVE-UP path,
        // taken when the save cannot be read, not a completion callback. `openWorld` arrived with
        // 1.20.5's world-recovery flow (read on 1.20.6); `checkForBackupAndLoad` is 1.20.3-1.20.4;
        // WorldOpenFlows itself is 1.19's, and before it Minecraft loads a save directly.
        //? if >=1.20.5 {
        /*mc.createWorldOpenFlows().openWorld(name, () -> { });
        *///?} elif >=1.20.3 {
        /*mc.createWorldOpenFlows().checkForBackupAndLoad(name, () -> { });
        *///?} elif >=1.19 {
        WorldOpenFlows flows = mc.createWorldOpenFlows();
        flows.loadLevel(mc.screen, name);
        //?} elif >=1.16 {
        /*mc.loadLevel(name);
        *///?} else {
        /*mc.selectLevel(name, name, null);
        *///?}
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
        // to <dir>/screenshots/<name>. So the frame is grabbed into it and moved to the path asked for.
        File gameDir = parent == null ? new File(".") : parent;
        File written = new File(new File(gameDir, "screenshots"), file.getName());
        // Read HERE, on the render thread: the callback runs on Minecraft's IO pool. Whether the desktop
        // painted matters because a capture proves only that a frame was read back -- with no live GL
        // context the screen's render() returns at once, and with no level Minecraft does not clear the
        // colour buffer, so a photograph of the main menu is indistinguishable from a working desktop.
        int width = mc.getMainRenderTarget().width;
        int height = mc.getMainRenderTarget().height;
        boolean painted = HostSession.isInstalled() && HostSession.session().hasPainted();
        PENDING_CAPTURES.incrementAndGet();
        // The callback fires once the PNG is written, on every version: encoded on the IO pool through
        // 1.21.4, and from 1.21.5 read back from the GPU on a later frame first.
        // 1.21.6 added a downscale factor; 1 is the frame as drawn.
        Consumer<Component> onWritten = message -> {
            try {
                boolean moved = written.equals(file);
                if (!moved && written.isFile()) {
                    file.delete();
                    moved = written.renameTo(file);
                }
                if (!moved) {
                    // Never claim the path that was asked for unless the file is on it: this line is
                    // the only evidence a caller has.
                    CrystalGuiCore.LOGGER.warn("CGUI AUTOTEST capture stayed at {}; nothing was written to {}",
                            written.getAbsolutePath(), file.getAbsolutePath());
                    return;
                }
                CrystalGuiCore.LOGGER.info("CGUI AUTOTEST wrote {}x{} capture to {} (desktop painted: {})",
                        width, height, file.getAbsolutePath(), painted);
            } finally {
                PENDING_CAPTURES.decrementAndGet();
            }
        };
        //? if >=1.21.6 {
        /*Screenshot.grab(gameDir, file.getName(), mc.getMainRenderTarget(), 1, onWritten);
        *///?} elif >=1.17 {
        try {
            Screenshot.grab(gameDir, file.getName(), mc.getMainRenderTarget(), onWritten);
        } catch (NoSuchMethodError before1171) {
            grabWithSize(gameDir, file.getName(), mc.getMainRenderTarget(), onWritten);
        }
        //?} else {
        /*RenderTarget target = mc.getMainRenderTarget();
        Screenshot.grab(gameDir, file.getName(), target.width, target.height, target, onWritten);
        *///?}
    }

    /**
     * 1.17's grab, which also takes the frame's size: the node that claims 1.17 is compiled against
     * 1.17.1, which dropped it. Found by parameter shape, since its runtime name is an intermediary one.
     */
    private static void grabWithSize(File gameDir, String name, RenderTarget target, Consumer<Component> done) {
        for (Method method : Screenshot.class.getDeclaredMethods()) {
            Class<?>[] p = method.getParameterTypes();
            // Public: a private helper of the same shape sits beside it.
            if (Modifier.isStatic(method.getModifiers()) && Modifier.isPublic(method.getModifiers())
                    && p.length == 6 && p[0] == File.class
                    && p[1] == String.class && p[2] == int.class && p[3] == int.class
                    && p[4].isInstance(target) && p[5] == Consumer.class) {
                try {
                    method.invoke(null, gameDir, name, target.width, target.height, target, done);
                    return;
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("CGUI AUTOTEST could not take a screenshot", e);
                }
            }
        }
        throw new IllegalStateException("CGUI AUTOTEST found no Screenshot.grab this version answers");
    }
}
