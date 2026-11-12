package com.crystalgui.ui.service;

import com.crystalgraphics.job.CgHostThread;
import com.crystalgraphics.job.reply.CgReply;
import com.crystalgraphics.platform.input.CgClipboard;
import javax.annotation.Nullable;

/**
 * What was last copied, as the form-rich {@link TransferData}, with its text written through to the system clipboard.
 * One per process, because the system clipboard is: a fragment copied in one application pastes into another.
 *
 * <pre>{@code
 * // Copy: every form the source has; its text reaches other programs
 * UiClipboard.process().put(TransferData.of(fragment, json));
 *
 * // Paste: the rich form, unless another program has copied since
 * UiClipboard.process().read(GraphDocument.class).then(clip -> { if (clip != null) paste(clip); });
 *
 * // Enablement, asked every frame by a menu: what this process holds
 * boolean can = UiClipboard.process().has(GraphDocument.class);
 * }</pre>
 *
 * <p>A paste reads the rich form only while the system text is still what we wrote; another program's copy since then
 * wins, and the rich form is then not offered ({@code read} answers null). A rich form with no text is never
 * contradicted, since the system has nothing of ours to compare. The system text is read on the host's client thread,
 * so {@code read} answers a reply; {@link #has} is optimistic, as it cannot ask.</p>
 *
 * <p>Easy to get wrong: {@code put(null)} keeps what was copied, so copying nothing does not discard a copy.</p>
 */
public final class UiClipboard {

    private static final UiClipboard PROCESS = new UiClipboard();

    /** The process's clipboard. */
    public static UiClipboard process() {
        return PROCESS;
    }

    @Nullable
    private volatile TransferData held;
    @Nullable
    private volatile String wrote;

    /** Replaces what is held and writes its text form through. Null is ignored. */
    public void put(@Nullable TransferData data) {
        if (data == null) return;
        String text = data.text();
        wrote = text;
        held = data;
        if (text != null) CgHostThread.CLIENT.run(() -> CgClipboard.set(text));
    }

    /** What the process holds as a {@code type}, not yet checked against the system's text; null when none. */
    @Nullable
    public <T> T peek(Class<T> type) {
        TransferData data = held;
        return data == null ? null : data.as(type);
    }

    /** Whether the process holds a form of {@code type}. @see #peek */
    public boolean has(Class<?> type) {
        return peek(type) != null;
    }

    /** What is held, as a {@code type}, once checked against the system text; null when none, or superseded. */
    public <T> CgReply<T> read(Class<T> type) {
        TransferData data = held;
        String ours = wrote;
        T form = data == null ? null : data.as(type);
        if (form == null || ours == null || !CgHostThread.CLIENT.isAvailable()) return CgReply.of(form);
        return CgHostThread.CLIENT.call(CgClipboard::get)
                .map(system -> system.isEmpty() || same(system, ours) ? form : null);
    }

    /** Forgets what is held. For a test, which shares the process. */
    public void clear() {
        held = null;
        wrote = null;
    }

    /** Systems rewrite line ends on the way through. */
    private static boolean same(String system, String ours) {
        return system.replace("\r\n", "\n").equals(ours.replace("\r\n", "\n"));
    }
}
