package com.crystalgui.ui.dom;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.async.UiSequence;
import com.crystalgui.core.cursor.Cursor;
import com.crystalgui.ui.service.PlatformPort;

import javax.annotation.Nullable;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The platform as a document recording on its own sequence sees it (plan engine-threaded-ui §2.8): key and button
 * state from the events the driver dispatched, and every write sent to the render thread, which owns the window.
 *
 * <ul>
 *   <li>State is the sequence's: as of the event being handled, not as of now on the window.</li>
 *   <li>Each host frame the render thread posts what the platform holds for the modifiers and buttons, behind every
 *       event already queued, so a release the window never delivered (focus lost mid-chord) does not stay held.</li>
 *   <li>A clipboard read waits for the render thread's next frame, at most {@link #CLIPBOARD_WAIT_MS}.</li>
 * </ul>
 */
final class PostedPlatformPort implements PlatformPort {

    private static final int[] MODIFIER_KEYS = {
            CgKeyCodes.KEY_LSHIFT, CgKeyCodes.KEY_RSHIFT, CgKeyCodes.KEY_LCONTROL, CgKeyCodes.KEY_RCONTROL,
            CgKeyCodes.KEY_LMENU, CgKeyCodes.KEY_RMENU, CgKeyCodes.KEY_LMETA, CgKeyCodes.KEY_RMETA};
    private static final int KEYS = 512;
    private static final int BUTTONS = 8;
    private static final long CLIPBOARD_WAIT_MS = 250L;

    private final UiSequence sequence;
    /** The sequence's. */
    private final boolean[] keys = new boolean[KEYS];
    private final boolean[] buttons = new boolean[BUTTONS];

    private final Queue<Consumer<PlatformPort>> outbox = new ConcurrentLinkedQueue<>();
    private final AtomicReference<Cursor> cursor = new AtomicReference<>();
    private boolean clipboardTimeoutReported;

    PostedPlatformPort(UiSequence sequence) {
        this.sequence = sequence;
    }

    // ── The sequence ───────────────────────────────────────────────────────────────────────────

    void note(CgSystemInput.Keyboard.Event key) {
        if (key.key() >= 0 && key.key() < KEYS) keys[key.key()] = key.pressed();
    }

    void note(CgSystemInput.Mouse.Event mouse) {
        if (mouse.button() >= 0 && mouse.button() < BUTTONS) buttons[mouse.button()] = mouse.state();
    }

    @Override
    public int modifiers() {
        int held = CgModifiers.NONE;
        if (keys[CgKeyCodes.KEY_LSHIFT] || keys[CgKeyCodes.KEY_RSHIFT]) held |= CgModifiers.SHIFT;
        if (keys[CgKeyCodes.KEY_LCONTROL] || keys[CgKeyCodes.KEY_RCONTROL]) held |= CgModifiers.CTRL;
        if (keys[CgKeyCodes.KEY_LMENU] || keys[CgKeyCodes.KEY_RMENU]) held |= CgModifiers.ALT;
        if (keys[CgKeyCodes.KEY_LMETA] || keys[CgKeyCodes.KEY_RMETA]) held |= CgModifiers.SUPER;
        return held;
    }

    @Override
    public boolean isKeyDown(int localKeyCode) {
        return localKeyCode >= 0 && localKeyCode < KEYS && keys[localKeyCode];
    }

    @Override
    public boolean isMouseDown(int localMouseCode) {
        return localMouseCode >= 0 && localMouseCode < BUTTONS && buttons[localMouseCode];
    }

    @Override
    @Nullable
    public String clipboard() {
        CompletableFuture<String> reply = new CompletableFuture<>();
        outbox.add(platform -> reply.complete(platform.clipboard()));
        try {
            return reply.get(CLIPBOARD_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException late) {
            if (!clipboardTimeoutReported) {
                clipboardTimeoutReported = true;
                CrystalGuiCore.LOGGER.warn("[cgui] the render thread did not answer a clipboard read within {} ms",
                        CLIPBOARD_WAIT_MS);
            }
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    @Override
    public void setClipboard(String text) {
        outbox.add(platform -> platform.setClipboard(text));
    }

    @Override
    public void playSound(String soundId) {
        outbox.add(platform -> platform.playSound(soundId));
    }

    @Override
    public void setCursor(Cursor cursor) {
        this.cursor.set(cursor);
    }

    // ── The render thread ───────────────────────────────────────────────────────────────────────

    /** Carries out what the document asked of the platform since the last frame, and reconciles held state. */
    void service() {
        for (Consumer<PlatformPort> message; (message = outbox.poll()) != null; ) message.accept(PlatformPort.INLINE);
        Cursor wanted = cursor.getAndSet(null);
        if (wanted != null) PlatformPort.INLINE.setCursor(wanted);

        boolean[] heldKeys = new boolean[MODIFIER_KEYS.length];
        for (int i = 0; i < MODIFIER_KEYS.length; i++) heldKeys[i] = PlatformPort.INLINE.isKeyDown(MODIFIER_KEYS[i]);
        boolean[] heldButtons = new boolean[BUTTONS];
        for (int b = 0; b < BUTTONS; b++) heldButtons[b] = PlatformPort.INLINE.isMouseDown(b);
        sequence.execute(() -> {
            for (int i = 0; i < MODIFIER_KEYS.length; i++) keys[MODIFIER_KEYS[i]] = heldKeys[i];
            System.arraycopy(heldButtons, 0, buttons, 0, BUTTONS);
        });
    }
}
