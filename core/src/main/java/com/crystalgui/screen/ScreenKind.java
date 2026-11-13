package com.crystalgui.screen;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.desktop.app.ApplicationKind;

import java.util.List;
import java.util.Objects;

/**
 * A screen of the game, designed in CrystalGUI: it fills the window with no desktop, frame or taskbar, and opens from a
 * key, a command or a server's word. One is up at a time, as in Minecraft.
 *
 * <pre>{@code
 * static final ScreenKind SETTINGS = ScreenKind.of("mymod:settings", screen -> {
 *             screen.root().append(new SettingsPanel(screen::close));     // built on the screen's own thread
 *         })
 *         .background(ScreenKind.Background.DIM)
 *         .closedBy(CgKeyCodes.KEY_O);
 *
 * CgKeyBindings.declare("mymod:settings", CgKeyCodes.KEY_O, "mymod:keys", () -> CgUiScreen.show(SETTINGS));
 * }</pre>
 *
 * <ul>
 *   <li>Each {@link CgUiScreen#show} builds a document and runs {@code builder} on it; closing drops both. Keep nothing
 *       of the tree between shows; keep state in your own model.</li>
 *   <li>Escape closes the screen unless something in it takes the key first (a popup, a text field). {@link #closedBy}
 *       adds keys, which close it only while no text field has focus.</li>
 *   <li>The game keeps running under it unless {@link #pausesGame}: a paused integrated server pumps no connection.</li>
 *   <li>Windows pinned on the desktop stay over it and take their presses.</li>
 *   <li>A kind is a value: share one constant, do not build one per show.</li>
 * </ul>
 *
 * @param id         names it in logs, its document and its error panel
 * @param builder    fills the screen's root, on the screen's own thread
 * @param background what is drawn behind the content
 * @param pausesGame whether a single-player game stops while it is up
 * @param closeKeys  {@link CgKeyCodes} keys that close it besides Escape
 * @param threading  where its document runs: {@code SEQUENCED} (default), or {@code INLINE} on the render thread for a
 *                   small screen that reads the game every frame
 */
public record ScreenKind(String id, Builder builder, Background background, boolean pausesGame, List<Integer> closeKeys,
                         ApplicationKind.Threading threading) {

    /** What is drawn behind a screen's content. */
    public enum Background {
        /** The game as it is. */
        NONE,
        /** The game, darkened: Minecraft's own screens. The default. */
        DIM,
        /** The game, blurred and darkened. */
        BLUR
    }

    /** Fills a screen's root. */
    @FunctionalInterface
    public interface Builder {
        void build(ScreenContext screen);
    }

    public ScreenKind {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(background, "background");
        Objects.requireNonNull(threading, "threading");
        closeKeys = List.copyOf(closeKeys);
    }

    /** A screen that dims the game, leaves it running and closes on Escape. */
    public static ScreenKind of(String id, Builder builder) {
        return new ScreenKind(id, builder, Background.DIM, false, List.of(), ApplicationKind.Threading.SEQUENCED);
    }

    public ScreenKind background(Background background) {
        return new ScreenKind(id, builder, background, pausesGame, closeKeys, threading);
    }

    /** Whether the game stops while it is up. A server's game is never paused. */
    public ScreenKind pausesGame(boolean pauses) {
        return new ScreenKind(id, builder, background, pauses, closeKeys, threading);
    }

    /** Keys that close it besides Escape: the key that opened it, usually. */
    public ScreenKind closedBy(int... keys) {
        Integer[] boxed = new Integer[keys.length];
        for (int i = 0; i < keys.length; i++) boxed[i] = keys[i];
        return new ScreenKind(id, builder, background, pausesGame, List.of(boxed), threading);
    }

    public ScreenKind threading(ApplicationKind.Threading threading) {
        return new ScreenKind(id, builder, background, pausesGame, closeKeys, threading);
    }

    /** Whether {@code key} closes it. */
    boolean closesOn(int key) {
        return closeKeys.contains(key);
    }
}
