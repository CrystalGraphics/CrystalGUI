package com.crystalgui.mc.fabric;

import com.crystalgraphics.mc.modern.platform.ResourceIds;
import com.crystalgraphics.mc.modern.platform.Windows;
import com.crystalgraphics.mc.shared.CrashVariant;
import com.crystalgraphics.mc.shared.VariantEntry;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.mc.modern.client.CgUiKeybinds;
import com.crystalgui.mc.modern.platform.LifecycleCrystalGUI;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
//? if >=26.1 {
/*import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
*///?} else {
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
//?}
//? if >=26.1 {
/*import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
*///?} elif >=1.15 {
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
//?}
//? if >=1.16 {
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
//?}
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

// 26.3 ships SDL3 and no GLFW.
//? if >=26.3 {
/*import org.lwjgl.PointerBuffer;
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDLMouse;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.sdl.SDL_EventFilter;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
*///?} else {
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWCharModsCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
import org.lwjgl.glfw.GLFWScrollCallback;
//?}

import static com.crystalgui.mc.modern.platform.CrystalGUI.MODID;
import static com.crystalgui.mc.modern.platform.CrystalGUI.NAME;

/**
 * Everything Fabric that runs on <b>both sides</b> — the common entry point and the {@link Events}
 * subscriptions. The connections are CrystalGraphics'.
 *
 * <p>Separate from {@link CrystalGUIFabric} because a dedicated server runs this one and must
 * touch no client class: the workspace is server-side. The client half of
 * {@code Events} is registered from there instead.</p>
 *
 * <p>The engine is deliberately absent: CrystalGraphics loads as its own mod and owns the render,
 * reload and shutdown hooks.</p>
 */
public final class CrystalGUIFabricCommon implements VariantEntry {

    /** @param context null — Fabric hands an entry point nothing; {@code FabricBootstrap} passes it on. */
    @Override
    public void start(Object context) {
        // WHICH VARIANT, in the log rather than the crash report: Fabric Loader exposes no crash
        // callable, so unlike Forge and 1.7.10 there is nothing to register with. @see CrashVariant
        CrystalGuiCore.LOGGER.info("[cgui] {}: {}", CrashVariant.label(NAME),
                CrashVariant.report(CrystalGUIFabricCommon.class));
        LifecycleCrystalGUI.bootstrap();
        Events.registerCommon();
    }

    // -- Events -----------------------------------------------------------------

    /** Fabric event subscription. Every body is one forward into {@link LifecycleCrystalGUI}. */
    static final class Events {

        private Events() {}

        /** Both sides. A dedicated server runs this and no client class may be touched from it. */
        static void registerCommon() {
            ServerLifecycleEvents.SERVER_STARTING.register(LifecycleCrystalGUI::serverStarting);
            ServerLifecycleEvents.SERVER_STARTED.register(LifecycleCrystalGUI::serverStarted);
            ServerLifecycleEvents.SERVER_STOPPING.register(server -> LifecycleCrystalGUI.serverStopping());
            ServerTickEvents.END_SERVER_TICK.register(server -> LifecycleCrystalGUI.serverTick());
        }

        static void registerClient() {
            LifecycleCrystalGUI.bootstrapClient();
            //? if >=26.1 {
            /*CgUiKeybinds.all().forEach(KeyMappingHelper::registerKeyMapping);
            *///?} else {
            CgUiKeybinds.all().forEach(KeyBindingHelper::registerKeyBinding);
            //?}
            ClientTickEvents.END_CLIENT_TICK.register(client -> LifecycleCrystalGUI.clientTick());

            // Pinned windows. ScreenOverlay decides; the loader only forwards and honours the boolean.
            // Fabric API for 26.1 replaced the callback with HUD elements: one, added last, so on top.
            // Fabric API for 1.15 hands the HUD callback the tick delta alone; 1.14's has none, and the
            // HUD is a node mixin there. @see com.crystalgui.mc.fabric.mixin.HudHook
            //? if >=26.1 {
            /*HudElementRegistry.addLast(ResourceIds.of(MODID, "hud"), (graphics, delta) -> LifecycleCrystalGUI.paintHud());
            *///?} elif >=1.16 {
            HudRenderCallback.EVENT.register((graphics, tickDelta) -> LifecycleCrystalGUI.paintHud());
            //?} elif >=1.15 {
            /*HudRenderCallback.EVENT.register(tickDelta -> LifecycleCrystalGUI.paintHud());
            *///?}
            // Fabric API for 1.15 has no screen events, so pinned windows do not draw over another
            // mod's screen there; the desktop, the HUD and input are unaffected.
            //? if >=26.1 {
            /*ScreenEvents.AFTER_INIT.register((client, screen, width, height) ->
                    ScreenEvents.afterExtract(screen).register(
                            (s, g, mx, my, td) -> LifecycleCrystalGUI.paintOverlay()));
            *///?} elif >=1.16 {
            ScreenEvents.AFTER_INIT.register((client, screen, width, height) ->
                    ScreenEvents.afterRender(screen).register(
                            (s, g, mx, my, td) -> LifecycleCrystalGUI.paintOverlay()));
            //?}

            ClientLifecycleEvents.CLIENT_STARTED.register(
                    client -> Input.install(Windows.handle(client)));
        }

        /**
         * Input, on GLFW's own callbacks rather than Fabric's screen events.
         *
         * <p><b>Fabric API has neither half of what this needs.</b> {@code ScreenKeyboardEvents} has no
         * character event, so typing could not reach a pinned window at all, and there is no non-screen
         * input event, so HUD mode heard nothing -- the two gaps Forge fills with {@code CharacterTyped}
         * and {@code InputEvent}. Chaining GLFW is the pattern this project already uses for scroll,
         * which Fabric also has no event for, and it is preferred to a mixin.</p>
         *
         * <p>One path for the HUD and for a screen, which is what mc1710's own drain is. Not forwarding
         * to the previous callback IS the cancellation: Minecraft never sees what we consumed.</p>
         *
         * <p>From 26.3 the same chain is SDL's event filter: returning false drops an event before
         * Minecraft polls it.</p>
         */
        private static final class Input {

            private Input() {}

            //? if >=26.3 {
            /*// Held: SDL keeps only the native pointer, and a collected callback is a crash.
            private static SDL_EventFilter filter;
            private static Thread renderThread;

            static void install(long window) {
                renderThread = Thread.currentThread();
                SDL_EventFilter previous;
                long previousData;
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    PointerBuffer function = stack.mallocPointer(1);
                    PointerBuffer data = stack.mallocPointer(1);
                    boolean had = SDLEvents.SDL_GetEventFilter(function, data) && function.get(0) != MemoryUtil.NULL;
                    previous = had ? SDL_EventFilter.createSafe(function.get(0)) : null;
                    previousData = had ? data.get(0) : MemoryUtil.NULL;
                }
                filter = SDL_EventFilter.create((userdata, event) -> {
                    // SDL may filter an event pushed from another thread; the engine is the render
                    // thread's, so anything else passes through untouched.
                    if (Thread.currentThread() == renderThread && consumed(SDL_Event.create(event))) return false;
                    return previous == null || previous.invoke(previousData, event);
                });
                SDLEvents.SDL_SetEventFilter(filter, MemoryUtil.NULL);
            }

            private static boolean consumed(SDL_Event event) {
                switch (event.type()) {
                    case SDLEvents.SDL_EVENT_MOUSE_BUTTON_DOWN:
                    case SDLEvents.SDL_EVENT_MOUSE_BUTTON_UP:
                        return LifecycleCrystalGUI.offerMouse(event.button().button() & 0xFF,
                                event.button().down(), 0f);
                    case SDLEvents.SDL_EVENT_KEY_DOWN:
                    case SDLEvents.SDL_EVENT_KEY_UP:
                        return !event.key().repeat()
                                && LifecycleCrystalGUI.offerKey(event.key().scancode(), (char) 0, event.key().down());
                    case SDLEvents.SDL_EVENT_TEXT_INPUT: {
                        String text = event.text().textString();
                        boolean taken = false;
                        for (int i = 0; text != null && i < text.length(); i++) {
                            taken |= LifecycleCrystalGUI.offerKey(0, text.charAt(i), true);
                        }
                        return taken;
                    }
                    case SDLEvents.SDL_EVENT_MOUSE_WHEEL: {
                        float y = event.wheel().y();
                        if (event.wheel().direction() == SDLMouse.SDL_MOUSEWHEEL_FLIPPED) y = -y;
                        return LifecycleCrystalGUI.offerMouse(-1, false, y);
                    }
                    default:
                        return false;
                }
            }
            *///?} else {
            static void install(long window) {
                // Read back by setting null, because GLFW hands the previous callback to the SETTER --
                // there is no getter, and Minecraft's own must keep running for everything we decline.
                GLFWMouseButtonCallback prevButton = GLFW.glfwSetMouseButtonCallback(window, null);
                GLFW.glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
                    if (action != GLFW.GLFW_REPEAT
                            && LifecycleCrystalGUI.offerMouse(button, action == GLFW.GLFW_PRESS, 0f)) {
                        return;
                    }
                    if (prevButton != null) prevButton.invoke(win, button, action, mods);
                });

                GLFWKeyCallback prevKey = GLFW.glfwSetKeyCallback(window, null);
                GLFW.glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> {
                    if (action != GLFW.GLFW_REPEAT
                            && LifecycleCrystalGUI.offerKey(key, (char) 0, action == GLFW.GLFW_PRESS)) {
                        return;
                    }
                    if (prevKey != null) prevKey.invoke(win, key, scancode, action, mods);
                });

                // CHAR_MODS, not CHAR. Minecraft installs its character handler on the mods variant
                // (InputConstants.setupKeyboardCallbacks) and GLFW fires BOTH for one keystroke, so
                // hooking CHAR puts us beside its handler rather than in front of it: declining to
                // forward suppresses nothing and the character lands in chat and in the editor at once.
                GLFWCharModsCallback prevChar = GLFW.glfwSetCharModsCallback(window, null);
                GLFW.glfwSetCharModsCallback(window, (win, codepoint, mods) -> {
                    if (LifecycleCrystalGUI.offerKey(0, (char) codepoint, true)) return;
                    if (prevChar != null) prevChar.invoke(win, codepoint, mods);
                });

                GLFWScrollCallback prevScroll = GLFW.glfwSetScrollCallback(window, null);
                GLFW.glfwSetScrollCallback(window, (win, dx, dy) -> {
                    if (LifecycleCrystalGUI.offerMouse(-1, false, (float) dy)) return;
                    if (prevScroll != null) prevScroll.invoke(win, dx, dy);
                });
            }
            //?}
        }
    }
}
