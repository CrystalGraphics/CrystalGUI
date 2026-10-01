package com.crystalgui.ui.service;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgInputService;
import com.crystalgraphics.platform.service.CgSoundService;
import com.crystalgui.core.cursor.Cursor;
import com.crystalgui.core.cursor.CursorService;
import com.crystalgui.ui.dom.UIDocument;

import javax.annotation.Nullable;

/**
 * What a document asks of the platform it runs on: modifier, key and button state, the clipboard, a sound,
 * the cursor.
 *
 * <pre>{@code
 * PlatformPort platform = PlatformPort.current();     // the running document's, or the inline one
 * boolean fine = CgModifiers.hasCtrl(platform.modifiers());
 * platform.setClipboard(selection);
 * platform.playSound("button_click");
 * }</pre>
 *
 * <p>Every call reaches the platform directly today. Once a document runs on its own thread (plan
 * engine-threaded-ui, T1), its port answers state from the event and frame it is handling and sends writes
 * to the render thread, which owns the window. Code that calls {@code CgPlatform.input()}, {@code sound()}
 * or {@link CursorService} itself bypasses that and breaks there.</p>
 *
 * <ul>
 *   <li>{@link #current()} is the port of the document running on this thread: inside a frame, an input
 *       event, or a task of the document. Anywhere else it is {@link #INLINE}.</li>
 *   <li>With no platform registered (a headless test) state reads as nothing held and writes are dropped.</li>
 * </ul>
 */
public interface PlatformPort {

    /** The modifier bits held now: {@code CgModifiers}. */
    int modifiers();

    boolean isKeyDown(int localKeyCode);

    boolean isMouseDown(int localMouseCode);

    /** The clipboard's text, or null when it holds none. */
    @Nullable
    String clipboard();

    void setClipboard(String text);

    void playSound(String soundId);

    void setCursor(Cursor cursor);

    /** The port of the document running on this thread, or {@link #INLINE}. */
    static PlatformPort current() {
        UIDocument running = UIDocument.current();
        return running == null ? INLINE : running.platform();
    }

    /** The platform itself, on the calling thread: every document's port until documents run elsewhere. */
    PlatformPort INLINE = new PlatformPort() {
        @Override
        public int modifiers() {
            CgInputService input = input();
            return input == null ? 0 : input.getCurrentModifiers();
        }

        @Override
        public boolean isKeyDown(int localKeyCode) {
            CgInputService input = input();
            return input != null && input.isKeyDown(localKeyCode);
        }

        @Override
        public boolean isMouseDown(int localMouseCode) {
            CgInputService input = input();
            return input != null && input.isMouseDown(localMouseCode);
        }

        @Override
        @Nullable
        public String clipboard() {
            CgInputService input = input();
            return input == null ? null : input.getClipboard();
        }

        @Override
        public void setClipboard(String text) {
            CgInputService input = input();
            if (input != null) input.setClipboard(text);
        }

        @Override
        public void playSound(String soundId) {
            CgSoundService sound;
            try {
                sound = CgPlatform.sound();
            } catch (IllegalStateException unregistered) {
                return;
            }
            if (sound != null) sound.play(soundId);
        }

        @Override
        public void setCursor(Cursor cursor) {
            CursorService.setCursor(cursor);
        }

        @Nullable
        private CgInputService input() {
            try {
                return CgPlatform.input();
            } catch (IllegalStateException unregistered) {
                return null;
            }
        }
    };
}
