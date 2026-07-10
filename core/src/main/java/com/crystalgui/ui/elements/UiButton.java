package com.crystalgui.ui.elements;

import com.crystalgui.core.event.CgUiDebug;
import com.crystalgui.core.event.UiEventType;
import com.crystalgui.core.input.FocusPolicy;
import com.crystalgui.core.signal.Signal;
import lombok.Getter;

/**
 * First interactive widget: a clickable button extending UiPanel.
 *
 * <p>Exposes {@link #clicked} and {@link #hoverChanged} signals wired from
 * DOM event listeners. If the button is disabled, high-level signals are
 * suppressed even though routed events may still reach it.</p>
 */
public class UiButton extends UiPanel {

    /** Emits when the button is clicked (only when enabled). */
    public final Signal.Action clicked = new Signal.Action();

    /** Emits (isHovered) on hover state changes. */
    public final Signal.Value<Boolean> hoverChanged = new Signal.Value<>();

    @Getter
    private boolean hovered;

    public UiButton(int color) {
        super(color);
        setFocusPolicy(FocusPolicy.CLICK);
        wireEventListeners();
    }

    private void wireEventListeners() {
        addEventListener(UiEventType.CLICK, event -> {
            if (!isEnabled()) return;
            CgUiDebug.logSignalEmit("UiButton.clicked", "click");
            clicked.emit();
        });

        addEventListener(UiEventType.MOUSE_ENTER, event -> {
            hovered = true;
            markRenderDirty();
            hoverChanged.emit(true);
        });

        addEventListener(UiEventType.MOUSE_LEAVE, event -> {
            hovered = false;
            markRenderDirty();
            hoverChanged.emit(false);
        });
    }
}
