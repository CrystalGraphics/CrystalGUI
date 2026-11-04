package com.crystalgui.desktop.taskbar;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.core.window.WindowState;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;

import java.util.ArrayList;
import java.util.List;

/**
 * Every window of a combined taskbar button, side by side: what hovering or clicking that button shows, as
 * Windows 11's thumbnail row does. One {@link WindowPreview} card per window, each with its own close.
 *
 * <pre>{@code
 * row.setWindows(group);        // rebuilt only when the windows change
 * row.sync();                   // every frame it is up: pictures follow their windows
 * row.onPicked.connect(frame -> desktop.activate(frame, false));
 * }</pre>
 */
public final class WindowPreviewRow extends UIElement {

    public static final Name NAME = Name.of("windowpreviewrow");
    public static final String ROW_CLASS = "__preview-row__";

    /** A card was clicked: its window should come forward. */
    public final Signal.Value<WindowFrame> onPicked = new Signal.Value<>();

    private final List<WindowFrame> windows = new ArrayList<>();

    public WindowPreviewRow() {
        super(NAME);
        addClass(ROW_CLASS);
    }

    /** Shows one card per window, in their order. Unchanged windows keep their cards. */
    public void setWindows(List<WindowFrame> group) {
        if (windows.equals(group)) return;
        windows.clear();
        windows.addAll(group);
        for (UIElement card : new ArrayList<>(children())) remove(card);
        for (WindowFrame frame : group) {
            WindowPreview card = new WindowPreview();
            card.setFrame(frame);
            card.onActivated.connect(() -> onPicked.emit(frame));
            append(card);
        }
    }

    /** The windows shown, in order. */
    public List<WindowFrame> windows() {
        return List.copyOf(windows);
    }

    /**
     * Keeps every picture the shape of its window; drops the cards of windows that have closed.
     *
     * @return whether anything changed size, so a placement made against this frame's box would be stale
     */
    public boolean sync() {
        boolean changed = false;
        for (UIElement child : new ArrayList<>(children())) {
            if (!(child instanceof WindowPreview card)) continue;
            WindowFrame frame = card.frame();
            if (frame == null || frame.state() == WindowState.DESTROYED) {
                windows.remove(frame);
                remove(card);
                changed = true;
                continue;
            }
            changed |= card.syncThumbnail();
        }
        return changed;
    }

    /** Takes every card down, so nothing keeps drawing a window off screen. */
    public void clear() {
        windows.clear();
        for (UIElement card : new ArrayList<>(children())) {
            if (card instanceof WindowPreview preview) preview.setFrame(null);
            remove(card);
        }
    }
}
