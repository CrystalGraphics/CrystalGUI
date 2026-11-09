package com.crystalgui.ui.service;

import com.crystalgraphics.audio.CgSounds;
import com.crystalgraphics.platform.input.CgClipboard;
import com.crystalgraphics.platform.input.CgInput;
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
 * to the render thread, which owns the window. Code that calls {@code CgInput}, {@code CgClipboard}
 * or {@link CursorService} itself bypasses that and breaks there.</p>
 *
 * <ul>
 *   <li>{@link #current()} is the port of the document running on this thread: inside a frame, an input
 *       event, or a task of the document. Anywhere else it is {@link #INLINE}.</li>
 *   <li>With no toolkit provided (a headless test) state reads as nothing held, the clipboard as empty, and
 *       writes are dropped.</li>
 *   <li>A sound is CrystalGraphics' {@link CgSounds#play2D}: a host sound event or a file, unplaced in the UI class.</li>
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
            return CgInput.modifiers();
        }

        @Override
        public boolean isKeyDown(int localKeyCode) {
            return CgInput.isKeyDown(localKeyCode);
        }

        @Override
        public boolean isMouseDown(int localMouseCode) {
            return CgInput.isMouseDown(localMouseCode);
        }

        @Override
        public String clipboard() {
            return CgClipboard.get();
        }

        @Override
        public void setClipboard(String text) {
            CgClipboard.set(text);
        }

        @Override
        public void playSound(String soundId) {
            CgSounds.play2D(soundId);
        }

        @Override
        public void setCursor(Cursor cursor) {
            CursorService.setCursor(cursor);
        }
    };
}
