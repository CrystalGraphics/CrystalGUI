package com.crystalgui.core.event;

import com.crystalgui.ui.UIElement;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nullable;

/**
 * Base class for all UI events in the routed event system.
 *
 * <p>Carries routing metadata (target, currentTarget, phase) and propagation
 * control. Subclasses add type-specific payloads (pointer coords, key codes).</p>
 *
 * <p>Coordinates in pointer events are in <b>absolute container space</b>,
 * matching the output of {@code LayoutContext.updateLayoutBoxes(...)}.</p>
 */
@Getter
public class UiEvent {

    private final UiEventType type;

    @Setter @Nullable
    private UIElement target;
    @Setter @Nullable
    private UIElement currentTarget;
    @Setter
    private UiEventPhase phase = UiEventPhase.TARGET;
    private boolean propagationStopped;
    private boolean immediatePropagationStopped;
    private boolean defaultPrevented;
    
    

    protected UiEvent(UiEventType type) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        this.type = type;
    }

    /**
     * Creates a bare UiEvent for non-payload event types (FOCUS_IN, FOCUS_OUT, etc.).
     *
     * <p>Use this instead of the protected constructor when you need to create
     * a base event from outside the event package.</p>
     */
    public static UiEvent of(UiEventType type) {
        return new UiEvent(type);
    }

    /** Stops propagation after all listeners on the current element complete. */
    public void stopPropagation() {
        this.propagationStopped = true;
        CgUiDebug.logEventStop(this, false);
    }

    /**
     * Stops propagation immediately — remaining listeners on the same element
     * are skipped, and no further elements receive the event.
     */
    public void stopImmediatePropagation() {
        this.propagationStopped = true;
        this.immediatePropagationStopped = true;
        CgUiDebug.logEventStop(this, true);
    }

    /** Prevents the default action associated with this event. */
    public void preventDefault() {
        this.defaultPrevented = true;
    }
}
