package com.crystalgui.desktop.host;

import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.storage.ConfigStorage;
import com.crystalgui.core.storage.LocalConfigStorage;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.window.WindowMount;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.dom.UIDocument;

/**
 * <b>The one object a loader talks to.</b> A surface, a compositor, a workspace that follows the wire,
 * and somewhere for a server's windows to land.
 *
 * <p>What a host writes after this is its own: turning its events into {@link #shown()},
 * {@link #hidden()} and {@link #frame}, pumping its input, and handing over a GL context. What it no
 * longer writes is the assembly — the document, the scale, the user-agent sheet, the arrangement
 * record, the workspace's rebind-on-reconnect, and the mount's re-ask. Every one of those was in the
 * 1.7.10 screen and is the same answer on every host.</p>
 *
 * <h3>The workspace follows the connection, and is rebound rather than rebuilt</h3>
 *
 * <p>A reconnect is a different wire carrying the same workspace. Replacing the client would fix the
 * routing and lose something else: a window retained across the disconnect still holds the OLD one in a
 * final field, with every callback registered on it. {@link Workspace#rebind} swaps the wire and keeps
 * the object, so a retained window comes back working without knowing a reconnect happened.</p>
 *
 * <p>And it is re-asked every frame, which is not belt-and-braces: nothing else calls the accessor
 * again. An application takes its workspace once at construction and holds it, so without a per-frame
 * re-ask the rebind is machinery that can never fire and a retained window stays pointed at a dead
 * router for good.</p>
 */
public final class DesktopHost implements Disposable {

    private final HostServices services;
    private final UIDocument document;
    private final ConfigStorage config;
    private final DesktopWindowMount mount;

    @Nullable
    private Workspace workspace;

    /** Which wire the workspace is bound to, so a rebind can be told from a no-op. */
    @Nullable
    private ProtocolConnection<Object> bound;

    /** What should be offered a server's window before the desktop. @see #setWindowMount */
    @Nullable
    private Supplier<WindowMount> preferredMount;

    private DesktopHost(HostServices services) {
        this.services = services;
        this.document = new UIDocument();
        // THE SCALE, ONCE. It lives on the box tree's root transform, which is the matrix layout
        // composes, painting reads and hit-testing inverts -- so there is no second place it can be
        // applied and no window in which the three can disagree.
        this.document.boxes().setUiScale(services.uiScale());
        // NOT INSTALLED FOR YOU. Without this the surface matches no selector at all and everything on
        // it renders as an unstyled column of boxes.
        this.document.styles().addStylesheet(StyleSheet.DEFAULT);
        this.config = new LocalConfigStorage(services.configDirectory());
        // WHERE THE ARRANGEMENT LIVES, and nothing else. The compositor owns reading it, applying it to
        // windows as they open, and writing it again when the surface closes; a host has no business
        // holding a second copy of that policy.
        Desktop.of(document).persistTo(config, services.desktopId());
        this.mount = new DesktopWindowMount(document);
    }

    public static DesktopHost create(HostServices services) {
        return new DesktopHost(services);
    }

    /** The surface everything is drawn on. */
    public UIDocument document() {
        return document;
    }

    /** The compositor. */
    public Desktop desktop() {
        return Desktop.of(document);
    }

    /** Where private records go — the arrangement, an application's session, backups. */
    public ConfigStorage config() {
        return config;
    }

    /** The file client, or null until there is a connection to carry it. */
    @Nullable
    public Workspace workspace() {
        return workspace;
    }

    /** Where a server's windows land when no application claims them. */
    public DesktopWindowMount windowMount() {
        return mount;
    }

    /**
     * What should be offered a server's window first — an application's mount.
     *
     * <p>A supplier rather than a mount, because the thing that offers it is built later than this and
     * may be rebuilt: a host sets this once and the answer follows whatever is running.</p>
     */
    public DesktopHost setWindowMount(@Nullable Supplier<WindowMount> preferred) {
        this.preferredMount = preferred;
        return this;
    }

    /**
     * One frame's worth of host work, before anything reads the workspace.
     *
     * <p>Ordered: the workspace is repaired first, then the mount is re-asked, so the same frame uses
     * the repaired client. Painting is deliberately not here — a host decides when and through what,
     * and on some of them it is not even the same call every frame.</p>
     */
    public void frame(float deltaSeconds) {
        ProtocolConnection<Object> live = services.connection();
        if (live != null) {
            if (workspace == null) {
                // AN ATTACHMENT ON THE CONNECTION, not a constructor: this wire is shared, and a second
                // workspace on it would be a second subscriber to fs.changed. @see Workspace#of
                workspace = Workspace.of(live);
                workspace.setStorage(config);
                bound = live;
            } else if (bound != live) {
                workspace.rebind(live);
                bound = live;
            }
        }
        // WHERE A SERVER'S WINDOWS GO. Free when the wire has not moved. Windows that arrived before
        // this point were queued by ClientWindows and land on the next tick.
        mount.bind(live, preferredMount == null ? null : preferredMount.get());
    }

    /**
     * The surface came back.
     *
     * <p>A host closes and reopens its screen; the desktop is not rebuilt for that, it is resumed —
     * every window, its arrangement and everything open in it survive, which is the whole reason this
     * object outlives a screen.</p>
     */
    public void shown() {
        // exitHudMode FIRST, and it is a no-op unless the surface was closed with something pinned: it
        // is what puts back the windows the HUD hid, and it has to run BEFORE the resume so the desktop
        // it restores them onto is the attached one.
        desktop().exitHudMode();
        desktop().resume();
    }

    /** The surface went away. The compositor detaches; nothing is destroyed. */
    public void hidden() {
        desktop().suspend();
    }

    /**
     * Takes the surface down: every window destroyed, and the compositor detached.
     *
     * <p>Destroying the windows is what a host shutdown means and what nothing did before this — the
     * 1.7.10 screen's {@code disposeAll} said in its own javadoc that it ran "at game shutdown", and
     * nothing ran it at game shutdown either. A window's own {@code destroy} is what releases whatever
     * it holds, an application included.</p>
     */
    @Override
    public void dispose() {
        for (WindowFrame frame : desktop().windows()) frame.destroy();
        desktop().suspend();
    }
}
