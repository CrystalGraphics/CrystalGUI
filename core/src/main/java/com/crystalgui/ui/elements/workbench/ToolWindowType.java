package com.crystalgui.ui.elements.workbench;

/**
 * How a tool window is presented — IntelliJ's {@code ToolWindowType}, and the field
 * {@link ToolWindowState} was written without on the grounds that <i>"floating tool windows do not
 * exist here"</i>.
 *
 * <h3>Three, not five</h3>
 *
 * <p>IntelliJ has {@code DOCKED}, {@code UNDOCKED}, {@code SLIDING}, {@code FLOATING} and
 * {@code WINDOWED}. The first three are all "in the region", differing only in whether the region
 * steals space from the editor and whether it auto-hides — which is {@code autoHide} and a layout
 * question, not a presentation one. Collapsing them leaves the distinction that actually changes what
 * the thing IS: a panel in a region, or a panel in a window.</p>
 *
 * <h3>The two window modes are one class apart, and the difference is who owns the frame</h3>
 *
 * <p>Both {@link #FLOATING} and {@link #WINDOWED} put the tool window in a
 * {@link com.crystalgui.ui.elements.desktop.WindowFrame}. What separates them is the same thing that
 * separates a dialog from a document window on any desktop:</p>
 *
 * <ul>
 *   <li>{@link #FLOATING} is <b>owned</b> by the workbench's own frame — Win32's owner/owned
 *       relationship, via {@code WindowFrame.attachOwned}. It stays above its owner, travels with it,
 *       and hides when the owner hides, with no bookkeeping: the owner's {@code z-index} carries it.
 *       It is not in the {@code WindowRegistry}, so it has no taskbar entry — which is right, because
 *       it is not independently reachable.</li>
 *   <li>{@link #WINDOWED} is a <b>top-level</b> window opened through {@code UIWindow.openWindow}: its
 *       own stacking slot, its own taskbar entry, raisable and minimisable on its own. IntelliJ draws
 *       the same line, and its Window mode is the one that survives the IDE frame being minimised.</li>
 * </ul>
 *
 * <p>That both are the same widget is the payoff of Design B. A float is not a special kind of panel
 * with a hand-rolled drag and a hand-rolled resize; it is the window widget the desktop already has,
 * parented somewhere else.</p>
 */
public enum ToolWindowType {

    /** In its region, sharing the workbench's layout. What everything is until something says otherwise. */
    DOCKED,

    /** In a frame owned by the workbench's window — above it, travelling with it, no taskbar entry. */
    FLOATING,

    /** In a top-level window of its own, with a stacking slot and a taskbar entry. */
    WINDOWED;

    /** Whether this mode puts the tool window in a {@code WindowFrame} rather than in a region. */
    public boolean isWindowed() {
        return this != DOCKED;
    }

    /**
     * The constant named {@code name}, or {@link #DOCKED}.
     *
     * <p>Matched by hand for the reason {@link com.crystalgui.ui.elements.dock.RegionSide#ofName}
     * gives: a session record is untrusted input written by a possibly-newer build, and
     * {@code StateMap.getEnum} throws for a constant this one does not have. Losing the mode costs one
     * gesture; losing the record costs the region, the order and whether it was open.</p>
     */
    public static ToolWindowType ofName(String name) {
        for (ToolWindowType type : values()) {
            if (type.name().equals(name)) return type;
        }
        return DOCKED;
    }
}
