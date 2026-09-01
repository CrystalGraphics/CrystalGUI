package com.crystalgui.workbench.toolwindow;

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
 *   <li>{@link #WINDOWED} is a <b>top-level</b> window opened through {@code UIDocument.openWindow}, with
 *       a stacking slot of its own — so it can be dragged anywhere on the desktop instead of being
 *       clamped inside its owner, which is the one thing a child cannot do.</li>
 * </ul>
 *
 * <h3>Neither is a taskbar entry, and that is the correction</h3>
 *
 * <p>{@code WINDOWED} used to mean "and therefore a citizen of the desktop": a taskbar button, a place
 * in the switcher, and a life that carried on after the window it came out of was minimised. That is
 * wrong, and it is wrong the way a screenshot shows immediately — the strip along the bottom read
 * <i>Welcome · Geometry · Crystal Editor · Inspector · Notifications</i>, as though a panel were a
 * peer of the IDE it belongs to.</p>
 *
 * <p>Win32 has a bit for exactly this ({@code WS_EX_TOOLWINDOW}) and IntelliJ uses it: a floating tool
 * window is top-level, freely draggable, above its IDE frame, and in neither the taskbar nor Alt+Tab.
 * Both modes here now carry {@link com.crystalgui.ui.elements.desktop.WindowFrame#isToolWindow()}, so
 * what separates them is only the clamp. A torn-out <b>editor</b> is the thing that does deserve an
 * entry, and it gets one by not setting the bit.</p>
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

    /** Top-level, so it reaches the whole desktop — but still no taskbar entry, and still hides with its owner. */
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
