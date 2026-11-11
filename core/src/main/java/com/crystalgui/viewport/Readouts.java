package com.crystalgui.viewport;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * The readouts of one recorded frame: written on the document while the frame is recorded, then read only.
 *
 * <pre>{@code
 * public void readouts(Readouts out) {          // CgUiViewport.Content
 *     out.put(ATTACHED, desktop.parent() != null);
 * }
 * }</pre>
 *
 * <ul>
 *   <li>A key never put reads null.</li>
 *   <li>Put only from {@link CgUiViewport.Content#readouts}: once the frame is committed the host may be reading.</li>
 * </ul>
 */
public final class Readouts {

    private Object[] values = new Object[4];

    Readouts() {
    }

    /** Sets {@code key}'s value for this frame. */
    public <T> void put(Readout<T> key, @Nullable T value) {
        if (key.index >= values.length) values = Arrays.copyOf(values, Math.max(key.index + 1, values.length * 2));
        values[key.index] = value;
    }

    /** {@code key}'s value for this frame, or null. */
    @Nullable
    public <T> T get(Readout<T> key) {
        if (key.index >= values.length) return null;
        @SuppressWarnings("unchecked") T value = (T) values[key.index];
        return value;
    }
}
