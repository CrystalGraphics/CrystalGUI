package com.crystalgui.ui.elements.dock;

import java.util.Objects;

/**
 * What a panel type <em>is</em>, independent of where any instance of it sits.
 *
 * <h3>{@code singleton} is where the editor-vs-toolwindow distinction really lives</h3>
 *
 * <p>VS Code and IntelliJ encode that distinction in the <em>layout</em>: the editor area is a grid and
 * tool windows are a different system arranged around it. This design rejects that asymmetry — the tree is
 * uniform — but the distinction it was protecting is real and survives here, on the type rather than on
 * the position.</p>
 *
 * <ul>
 *   <li><b>Singleton</b> — one instance, reopened from a menu when closed. The node library, an inspector,
 *       a console. Closing it means "hide it", and opening it again must find the existing one.</li>
 *   <li><b>Document</b> — many instances, opened from something. A shader graph, a {@code .glsl} buffer.
 *       Two of them are two different things and both belong on screen at once.</li>
 * </ul>
 *
 * <p>Getting this wrong is not a layout bug: a singleton treated as a document opens a second console
 * every time you press the button, and a document treated as a singleton silently refuses to open the
 * second file you asked for.</p>
 */
public final class DockPanelDescriptor {

    private final String typeId;
    private final String title;
    private final boolean singleton;
    private final boolean closable;

    public DockPanelDescriptor(String typeId, String title) {
        this(typeId, title, false, true);
    }

    public DockPanelDescriptor(String typeId, String title, boolean singleton, boolean closable) {
        this.typeId = Objects.requireNonNull(typeId, "typeId");
        this.title = Objects.requireNonNull(title, "title");
        this.singleton = singleton;
        this.closable = closable;
    }

    public static DockPanelDescriptor singleton(String typeId, String title) {
        return new DockPanelDescriptor(typeId, title, true, true);
    }

    public static DockPanelDescriptor document(String typeId, String title) {
        return new DockPanelDescriptor(typeId, title, false, true);
    }

    public String typeId() {
        return typeId;
    }

    /** The default tab label. A panel may override it per instance through its {@link DockPanelRef}. */
    public String title() {
        return title;
    }

    public boolean isSingleton() {
        return singleton;
    }

    public boolean isClosable() {
        return closable;
    }

    @Override
    public String toString() {
        return typeId + (singleton ? " (singleton)" : "");
    }
}
