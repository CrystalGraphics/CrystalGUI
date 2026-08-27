package com.crystalgui.mc.example;

import org.lwjgl.input.Keyboard;

import com.crystalgui.example.machine.session.MachineClient;
import com.crystalgui.example.machine.ui.MachineStyles;
import com.crystalgui.example.machine.MachineTrace;
import com.crystalgui.mc.client.CgUiScreen;
import com.crystalgui.mc.net.CgUiConnections;
import com.crystalgui.net.ClientUiSessions;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.ProgressBar;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.desktop.WindowFrame;
import com.crystalgui.ui.elements.desktop.WindowState;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

/**
 * The worked example's <b>client half</b>. <b>Press F8.</b>
 *
 * <h3>It is a window, not a screen</h3>
 *
 * <p>The tree the server described becomes the content of a {@link WindowFrame} on the desktop
 * {@link CgUiScreen} already owns — beside the editor, in the same taskbar, under the same compositor.
 * There is <b>one</b> {@code GuiScreen} in this mod and there is meant to be: a second one is a second
 * claim on the input pump, the GL state handoff, the desktop's persistence and the modal stack, and
 * only one of them can be in front. A panel that needed its own screen could not coexist with the
 * editor, which is the whole thing a compositor is for.</p>
 *
 * <p>So this class is short, and what it does <em>not</em> do is the interesting part: no input pump,
 * no {@code paintFrame}, no {@code doesGuiPauseGame}, no GL. Those are the host's, once, and the host
 * already exists.</p>
 *
 * <h3>It opens in the background, deliberately</h3>
 *
 * <p>{@link UIWindow#openWindowInBackground} rather than {@code openWindow}: this window is opened by
 * a <em>server</em> pushing a UI, not by the user asking for one, and taking the keyboard out from
 * under whatever is being typed is the one thing every windowing system agreed to stop doing. F8 is
 * the user asking — that one activates.</p>
 *
 * <h3>The two threads</h3>
 *
 * <p>Everything here runs on the client thread; everything in {@link MachineExample} runs on the
 * server's. In single player both live in one process, which is exactly the configuration where
 * getting that wrong still works — so the console prints the thread on every line and the two columns
 * should never cross:</p>
 *
 * <pre>
 *   [machine] SERVER [Server thread] opened window 7001, 27 elements, hash=1f3c…
 *   [machine] CLIENT [Client thread] window rebuilt from a description: 27 elements
 *   [machine] CLIENT [Client thread] placed on the desktop as a window
 *   [machine] SERVER [Server thread] event: power -&gt; true
 *   [machine] SERVER [Server thread] cycle 1 complete
 *   [machine] CLIENT [Client thread] progress 0.00 -&gt; 0.42
 * </pre>
 */
public final class MachineExampleClient {

    /** So a persisted desktop puts it back where the user left it. */
    private static final String WINDOW_KEY = "crystalgui:machine-example";

    private static KeyBinding openPanel;

    /** The client's view of whatever the server opened, or null before joining. */
    private static volatile MachineClient client;

    /** The connection {@link #client} was built for, so a reconnect rebuilds rather than going stale. */
    private static ProtocolConnection<Object> boundTo;

    /** The desktop window holding the server's tree. */
    private static WindowFrame frame;

    /** Whether this session's sheet has been given to the one style engine. */
    private static boolean sheetInstalled;

    /** Set by F8, cleared once the window has actually been brought forward. */
    private static boolean bringForward;

    /** So the per-frame progress line only prints when it moves. */
    private static float lastProgress = -1f;

    private MachineExampleClient() {
    }

    public static void registerClient() {
        openPanel = new KeyBinding("key.crystalgui.machine", Keyboard.KEY_F8,
                "key.categories.crystalgui");
        ClientRegistry.registerKeyBinding(openPanel);
        // THE FML BUS, not MinecraftForge.EVENT_BUS. fireKeyInput posts to FMLCommonHandler.bus(),
        // and registering on the wrong one compiles, runs, and never fires -- CgUiInput carries the
        // same note for the same reason.
        FMLCommonHandler.instance().bus().register(new ClientHandler());
    }

    /** Instance methods, because {@code @SubscribeEvent} is not honoured on statics. */
    public static final class ClientHandler {

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;

            bindToConnection();
            placeOnDesktop();
            traceProgress();
        }

        /**
         * F8.
         *
         * <p>Only reached with no screen up: {@code GuiScreen.allowUserInput} is false while one is
         * open, so FML gates this event out entirely. With the desktop already showing, the window is
         * reached the way every other window is — from the taskbar.</p>
         */
        @SubscribeEvent
        public void onKeyInput(InputEvent.KeyInputEvent event) {
            if (Minecraft.getMinecraft().currentScreen != null) return;
            if (openPanel == null || !openPanel.isPressed()) return;

            MachineClient current = client;
            if (current == null || current.root() == null) {
                // Four different things look like "F8 did nothing" -- never joined, no connection, the
                // window has not arrived yet, the server refused it -- and a report of it can only ever
                // be that nothing happened. So say which.
                MachineTrace.log(MachineTrace.CLIENT, "F8 -- no window yet ("
                        + (current == null ? "no session on this connection"
                                : "session open, description not rebuilt") + ")");
                return;
            }

            bringForward = true;
            MachineTrace.log(MachineTrace.CLIENT, "F8 -- opening the desktop");
            // Ask for the DESKTOP, not the editor: F6 brings the editor forward whatever state it was
            // left in, which is not what was asked for here. The window is brought forward below, on
            // the tick after the screen exists.
            CgUiScreen.openDesktop();
        }
    }

    // ── The session ─────────────────────────────────────────────────────────

    private static void bindToConnection() {
        ProtocolConnection<Object> connection = CgUiConnections.client();
        if (connection == null) {
            if (boundTo != null) {
                // Disconnected. Drop everything, or a rejoin hands the new server's window to a client
                // still holding the old one's tree -- "a client-side memo of what the server was told
                // is a lie the moment the connection changes".
                MachineTrace.log(MachineTrace.CLIENT, "connection gone; dropping the window");
                if (frame != null && frame.state() != WindowState.DESTROYED) frame.destroy();
            }
            client = null;
            boundTo = null;
            frame = null;
            lastProgress = -1f;
            return;
        }
        if (connection == boundTo) return;

        boundTo = connection;
        client = null;
        frame = null;

        /*
         * ClientUiSessions rather than a bare ClientUiSession, because ui/openWindow is the one message
         * that cannot be routed by window id -- it is what ANNOUNCES the id -- so a single owner per
         * connection has to hand out the per-window sessions. The two are mutually exclusive: both bind
         * that notification, and the router refuses the second.
         *
         * Installed the moment the connection appears. A host that installs it late has already missed
         * a window, and the miss is silent.
         */
        ClientUiSessions.forConnection(connection).onSession(session -> {
            // BOTH, because the two carry different halves of the protocol: the session answers and
            // makes CALLS, the connection sends and receives NOTIFICATIONS. See MachineClient.
            client = new MachineClient(session, connection);
            MachineTrace.log(MachineTrace.CLIENT, "session " + session.windowId() + " arrived");
        });
        MachineTrace.log(MachineTrace.CLIENT, "listening for windows on a new connection");
    }

    // ── The window ──────────────────────────────────────────────────────────

    /**
     * Puts the server's tree on the desktop, once there is a desktop to put it on.
     *
     * <p>Polled rather than pushed because the two are independent: a window can arrive before the
     * screen has ever been opened, and the screen can be opened before any window arrives. Whichever
     * happens second is the one that completes this, and a poll is how you write that without a flag
     * per ordering.</p>
     */
    private static void placeOnDesktop() {
        MachineClient current = client;
        if (current == null || current.root() == null) return;

        UIWindow host = CgUiScreen.window();
        if (host == null) return;   // the screen has never been opened; nothing to place onto yet

        if (frame == null || frame.state() == WindowState.DESTROYED) {
            /*
             * The sheet the server NAMED, resolved locally and added to the ONE style engine.
             *
             * A real host looks each SheetRef up: by id through the resource manager when it has that
             * theme, and by fetching the server's bytes when the hashes disagree or there is no id.
             * This example's sheet ships in this very jar, so the lookup is a constant -- honest for a
             * demo, and the one place this class is not the shape a mod would use.
             *
             * Once. Re-adding a sheet APPENDS it, that is, at the highest priority -- so a re-add on
             * every window would quietly climb above everything else in the engine.
             */
            if (!sheetInstalled) {
                host.getStyleEngine().addStylesheet(StyleSheet.parse(MachineStyles.CSS));
                sheetInstalled = true;
            }

            frame = new WindowFrame("Machine control");
            frame.setKey(WINDOW_KEY);
            frame.setContent(current.root());
            host.openWindowInBackground(frame);
            lastProgress = -1f;
            MachineTrace.log(MachineTrace.CLIENT, "placed on the desktop as a window");
        }

        if (!bringForward) return;
        bringForward = false;
        if (frame.state() == WindowState.HIDDEN) frame.show(false);
        host.desktop().activate(frame);
        MachineTrace.log(MachineTrace.CLIENT, "brought forward");
    }

    /**
     * Prints the value the server pushed, when it changes.
     *
     * <p>Read off the widget rather than from a callback, because there is no per-delta hook and this
     * is the honest thing a view does anyway. What it demonstrates is the direction: nothing on this
     * side computed the number, and this line prints on the <b>client</b> thread while the
     * {@code cycle N complete} line beside it prints on the server's.</p>
     */
    private static void traceProgress() {
        MachineClient current = client;
        if (current == null || current.root() == null) return;

        UIElement found = current.root().querySelector("#progress");
        if (!(found instanceof ProgressBar)) return;

        float now = ((ProgressBar) found).fraction();
        if (Math.abs(now - lastProgress) < 0.05f) return;
        MachineTrace.log(MachineTrace.CLIENT,
                String.format("progress %.2f -> %.2f", Math.max(0f, lastProgress), now));
        lastProgress = now;
    }
}
