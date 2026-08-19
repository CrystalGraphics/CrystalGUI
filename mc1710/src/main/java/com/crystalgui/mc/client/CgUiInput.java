package com.crystalgui.mc.client;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.ui.UIWindow;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * The key that opens the editor, and the translation of one LWJGL2 event into one engine event.
 *
 * <h3>Where the pump lives, and the false trail that moved it</h3>
 *
 * <p>Minecraft 1.7.10 delivers input to a screen through <b>{@code GuiScreen.handleInput()}</b>, called
 * from {@code Minecraft.runTick} at line ~1731:</p>
 *
 * <pre>
 * public void handleInput() {
 *     if (Mouse.isCreated())    { while (Mouse.next())    this.handleMouseInput(); }
 *     if (Keyboard.isCreated()) { while (Keyboard.next()) this.handleKeyboardInput(); }
 * }
 * </pre>
 *
 * <p>Unconditional on both halves — so a screen <b>does</b> see key releases, and
 * {@link CgUiScreen} overriding those two methods is the correct and only place to pump from.</p>
 *
 * <p>This was originally built the other way round, on FML's {@code InputEvent}, to work around a
 * key-release gap that does not exist on this path. That gap is real but belongs to a <em>different,
 * secondary</em> block further down {@code runTick}:</p>
 *
 * <pre>
 * if (this.currentScreen == null || this.currentScreen.allowUserInput) {   // line 1771
 *     while (Mouse.next())    { ... FMLCommonHandler.instance().fireMouseInput(); }
 *     while (Keyboard.next()) { ... fireKeyInput(); }                      // press-only dispatch
 * }
 * </pre>
 *
 * <p><b>{@code allowUserInput} defaults to false</b>, so with a screen open that whole block — the FML
 * events included — never runs. Pumping from {@code InputEvent} therefore delivered <b>nothing at all</b>
 * while looking entirely reasonable, and the empty {@code handleMouseInput}/{@code handleKeyboardInput}
 * overrides that accompanied it swallowed the events that <em>were</em> being delivered. Two mistakes
 * that hid each other: the editor rendered perfectly and could not be clicked or typed in.</p>
 *
 * <p>The keybind below stays on the FML bus, and that is not inconsistent: it must fire when
 * <b>no</b> screen is open, which is exactly the case where the gated block does run.</p>
 */
public final class CgUiInput {

    private static final long NANOS_IN_MILLIS = 1_000_000L;

    /** LWJGL2's origin is bottom-left and CrystalGUI's is top-left, so every Y and dY flips. */
    private static final int NORMALIZE_TOP_LEFT_ORIGIN = -1;

    /**
     * Notches to units, <b>and the sign</b>.
     *
     * <p>A <em>positive</em> {@code MouseEvent.Scroll} means the wheel rolled <b>down</b> — the one
     * statement of that in the engine is {@code ScrollerView}'s {@code setScrollTop(before + delta)}.
     * {@code CanvasView} shipped zooming the wrong way by taking the sign at face value, and no test
     * caught it because the test was written from the implementation.</p>
     */
    private static final float MOUSE_SCROLL_NORMALIZE = 1 / 120f * NORMALIZE_TOP_LEFT_ORIGIN;

    private static KeyBinding openEditor;

    private CgUiInput() {
    }

    public static void register() {
        openEditor = new KeyBinding("key.crystalgui.open", Keyboard.KEY_F6, "key.categories.crystalgui");
        ClientRegistry.registerKeyBinding(openEditor);
        // THE FML BUS, not MinecraftForge.EVENT_BUS -- fireKeyInput posts to FMLCommonHandler.bus(), and
        // registering on the wrong one compiles, runs, and never fires.
        FMLCommonHandler.instance().bus().register(new CgUiInput.Handler());
    }

    /**
     * One LWJGL2 mouse event into the window. Called once per event from
     * {@link CgUiScreen#handleMouseInput()}, with that event current.
     *
     * @param displayHeight raw device height — <b>not</b> {@code GuiScreen.height}, which is the scaled
     *                      GUI size and would put the pointer off by the scale factor
     */
    static void pumpMouse(UIWindow window, int displayHeight) {
        int button = Mouse.getEventButton();
        // A MOVE EVENT HAS NO BUTTON and must not carry a click timestamp, or the multi-click detail
        // counter drifts and a slow double-click registers as a triple.
        long millis = button == -1 ? -1 : Mouse.getEventNanoseconds() / NANOS_IN_MILLIS;

        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Mouse.getEventX(),
                displayHeight - Mouse.getEventY(),
                Mouse.getEventDX(),
                Mouse.getEventDY() * NORMALIZE_TOP_LEFT_ORIGIN,
                button,
                Mouse.getEventButtonState(),
                Mouse.getEventDWheel() * MOUSE_SCROLL_NORMALIZE,
                millis));
    }

    /**
     * One LWJGL2 keyboard event into the window.
     *
     * @return whether the event was left <b>unconsumed</b>, i.e. it should keep propagating — which is
     *         what lets the screen close on an Escape nothing else wanted
     */
    static boolean pumpKeyboard(UIWindow window) {
        return window.getInputHandler().consumeKeyboardEvent(new CgSystemInput.Keyboard.Event(
                Keyboard.getEventCharacter(),
                Keyboard.getEventKey(),
                Keyboard.getEventKeyState(),
                Keyboard.isRepeatEvent(),
                Keyboard.getEventNanoseconds() / NANOS_IN_MILLIS));
    }

    /** Instance methods, because {@code @SubscribeEvent} is not honoured on statics. */
    public static final class Handler {

        /**
         * Opens the editor. Only reached with no screen up — see the class javadoc: with one open,
         * {@code allowUserInput} gates this event out entirely, which is exactly the behaviour wanted
         * here (F6 must not re-trigger while the editor already has the keyboard).
         */
        @SubscribeEvent
        public void onKeyInput(InputEvent.KeyInputEvent event) {
            if (Minecraft.getMinecraft().currentScreen != null) return;
            if (openEditor != null && openEditor.isPressed()) CgUiScreen.open();
        }
    }
}
