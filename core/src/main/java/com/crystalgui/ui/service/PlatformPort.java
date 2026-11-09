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
 * <p>A document driven asynchronously (the default, {@code DocumentDriver.Mode.ASYNC}) runs on a thread of its
 * own, and its port answers key and button state as of the event it is handling, not as the window holds them
 * now; a clipboard, cursor or sound call is posted to the render thread, which owns the window and the frame's
 * sounds. An inline or lockstep document's port is {@link #INLINE}, the platform itself.</p>
 *
 * <ul>
 *   <li>Calling {@code CgInput}, {@code CgClipboard}, {@code CgSounds} or {@link CursorService} directly from UI
 *       code reads the live window from the wrong thread and writes from it, in an async document.</li>
 *   <li>{@link #current()} is the port of the document running on this thread: inside a frame, an input
 *       event, or a task of the document. Anywhere else it is {@link #INLINE}.</li>
 *   <li>An async document's clipboard read waits for the render thread's next frame, a quarter second at most.</li>
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

    /** The platform itself, on the calling thread: an inline or lockstep document's port, and code outside any. */
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
