package com.crystalgui.desktop.taskbar;

import javax.annotation.Nullable;

/**
 * Whether an application's windows share one taskbar button: Windows' "Combine taskbar buttons".
 *
 * <pre>{@code
 * desktop.desktopSettings().setTaskbarCombine(TaskbarCombine.WHEN_FULL);
 * }</pre>
 *
 * <p>A combined button shows every window of its application as a row of previews on hover. A window with no
 * application is never combined.</p>
 */
public enum TaskbarCombine {

    /** One button per application, always: Windows 11's default. */
    ALWAYS("always"),
    /** A button per window until they no longer fit the bar, then one per application. */
    WHEN_FULL("whenFull"),
    /** A button per window. */
    NEVER("never");

    private final String id;

    TaskbarCombine(String id) {
        this.id = id;
    }

    /** What the desktop's record stores. */
    public String id() {
        return id;
    }

    /** The mode a record names, or null for one this version does not know. */
    @Nullable
    public static TaskbarCombine byId(String id) {
        for (TaskbarCombine mode : values()) {
            if (mode.id.equals(id)) return mode;
        }
        return null;
    }
}
