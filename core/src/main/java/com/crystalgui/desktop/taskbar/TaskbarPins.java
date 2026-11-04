package com.crystalgui.desktop.taskbar;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.desktop.app.ApplicationKind;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The applications pinned to a desktop's taskbar, in the order the bar shows them.
 *
 * <p>A pin names an {@link ApplicationKind} by id, never a window: pinned and not running is a launch button,
 * and running, the application's windows take the pin's place. The order is saved with the desktop's
 * arrangement, so pins come back after a restart.</p>
 *
 * <pre>{@code
 * TaskbarPins pins = desktop.pins();
 * pins.pin(VfxLab.KIND);                        // appended at the end of the pins
 * pins.move(VfxLab.KIND.id(), Cues.KIND.id());   // now just before Cues
 * pins.unpin(VfxLab.KIND);
 * pins.onDidChange.connect(this::redraw);
 * }</pre>
 *
 * <p>An id whose application is not installed is kept and simply not drawn, so a mod that is missing for one
 * launch gets its pin back when it returns.</p>
 */
public final class TaskbarPins {

    /** A pin was added, removed or moved. */
    public final Signal.Action onDidChange = new Signal.Action();

    private final List<String> ids = new ArrayList<>();

    /** Every pinned id, in the bar's order. */
    public List<String> ids() {
        return Collections.unmodifiableList(ids);
    }

    public boolean isPinned(ApplicationKind kind) {
        return ids.contains(kind.id());
    }

    /** Pins {@code kind} at the end of the pins; already pinned, nothing changes. */
    public void pin(ApplicationKind kind) {
        if (ids.contains(kind.id())) return;
        ids.add(kind.id());
        onDidChange.emit();
    }

    public void unpin(ApplicationKind kind) {
        if (ids.remove(kind.id())) onDidChange.emit();
    }

    /** Moves the pin {@code id} to just before {@code beforeId}, or to the end of the pins when that is null. */
    public void move(String id, @Nullable String beforeId) {
        if (!ids.contains(id) || id.equals(beforeId)) return;
        int from = ids.indexOf(id);
        ids.remove(from);
        int at = beforeId == null ? -1 : ids.indexOf(beforeId);
        ids.add(at < 0 ? ids.size() : at, id);
        if (ids.indexOf(id) != from) onDidChange.emit();
    }

    /** Replaces every pin with a saved order: what the desktop does when it reads its record. */
    public void restore(List<String> saved) {
        ids.clear();
        for (String id : saved) {
            if (!id.isEmpty() && !ids.contains(id)) ids.add(id);
        }
        onDidChange.emit();
    }
}
