package com.crystalgui.core.event;

import com.crystalgui.core.signal.SignalBase;
import com.crystalgui.ui.UIElement;

/**
 * Debug logger for CrystalGUI event/signal/property/focus subsystems.
 *
 * <p>All output goes through this central class so it can be globally
 * enabled/disabled. In the harness, enable via {@code CgUiDebug.setEnabled(true)}.</p>
 */
public final class CgUiDebug {

    /** Set to true to enable verbose debug logging. Also installs signal debug hook. */
    public static boolean enabled = false;

    private static final SignalBase.DebugHook SIGNAL_DEBUG_HOOK = new SignalBase.DebugHook() {
        @Override
        public void onConnect(String signalClass) {
            logSignalConnect(signalClass);
        }

        @Override
        public void onDisconnect(String signalClass) {
            logSignalDisconnect(signalClass);
        }
    };

    /**
     * Enables or disables debug mode, including signal connect/disconnect hooks.
     */
    public static void setEnabled(boolean on) {
        enabled = on;
        SignalBase.debugHook = on ? SIGNAL_DEBUG_HOOK : null;
    }

    private CgUiDebug() {}

    public static void log(String subsystem, String message) {
        if (enabled) {
            System.out.println("[CGUI:" + subsystem + "] " + message);
        }
    }

    // ── Hit-testing ──
    public static void logHitTest(UIElement target, float x, float y) {
        if (enabled) {
            String id = target.getId() != null ? target.getId() : target.getClass().getSimpleName();
            log("hit-test", "target=" + id + " at (" + x + ", " + y + ")");
        }
    }

    // ── Event routing ──
    public static void logEventDispatch(UiEvent event, UIElement element, UiEventPhase phase) {
        if (enabled) {
            String id = element.getId() != null ? element.getId() : element.getClass().getSimpleName();
            log("event", event.getType() + " " + phase + " -> " + id);
        }
    }

    public static void logEventStop(UiEvent event, boolean immediate) {
        if (enabled) {
            log("event", event.getType() + " propagation stopped" + (immediate ? " (immediate)" : ""));
        }
    }

    // ── Signals ──
    public static void logSignalEmit(String signalName, Object value) {
        if (enabled) {
            log("signal", "emit " + signalName + " = " + value);
        }
    }

    public static void logSignalConnect(String signalName) {
        if (enabled) {
            log("signal", "connect " + signalName);
        }
    }

    public static void logSignalDisconnect(String signalName) {
        if (enabled) {
            log("signal", "disconnect " + signalName);
        }
    }

    // ── Properties ──
    public static void logPropertySet(String propName, Object oldVal, Object newVal) {
        if (enabled) {
            log("property", propName + ": " + oldVal + " -> " + newVal);
        }
    }

    public static void logPropertyBind(String targetProp, String sourceProp) {
        if (enabled) {
            log("property", "bind " + targetProp + " <- " + sourceProp);
        }
    }

    // ── Focus ──
    public static void logFocusTransition(UIElement oldFocus, UIElement newFocus) {
        if (enabled) {
            String oldId = oldFocus != null ? (oldFocus.getId() != null ? oldFocus.getId() : oldFocus.getClass().getSimpleName()) : "null";
            String newId = newFocus != null ? (newFocus.getId() != null ? newFocus.getId() : newFocus.getClass().getSimpleName()) : "null";
            log("focus", oldId + " -> " + newId);
        }
    }

    // ── Harness input injection ──
    public static void logSyntheticInput(String inputType, String details) {
        if (enabled) {
            log("harness-input", inputType + ": " + details);
        }
    }
}
