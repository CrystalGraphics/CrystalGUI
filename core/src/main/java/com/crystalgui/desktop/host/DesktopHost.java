package com.crystalgui.desktop.host;

import java.nio.file.Path;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.storage.ConfigStorage;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.window.WindowMount;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.dom.UIDocument;

/**
 * <b>The one object a loader talks to</b> - build it from {@link HostServices} and you have a working
 * desktop.
 *
 * <p>It owns the four things every host would otherwise assemble identically: the {@link UIDocument} and
 * its scale, the user-agent stylesheet, the {@link Desktop} compositor with its arrangement record, and
 * a {@link Workspace} that follows the connection. A host writes none of that.</p>
 *
 * <pre>{@code
 * host = DesktopHost.create(myServices);      // once, when a GL context exists
 * host.shown();                               // the screen opened
 * host.frame(delta, width, height);           // every frame: pumps the wire, ticks, paints
 * host.hidden();                              // the screen closed -- the desktop stays alive
 * host.dispose();                             // at shutdown
 * }</pre>
 *
 * <p>What a host still writes is its own: turning its events into {@link #shown()}, {@link #hidden()}
 * and {@link #frame}, feeding input, and having a GL context when it calls {@code create}. Reach the
 * pieces with {@link #desktop()}, {@link #document()}, {@link #workspace()} and {@link #config()}.</p>
 *
 * <h3>Hidden is not destroyed</h3>
 *
 * <p>{@link #hidden()} means the surface went away; the desktop, its windows and every application on it
 * are still alive and come back exactly as they were. Only {@link #dispose()} takes them down. That is
 * what lets a game screen be closed and reopened without losing a single unsaved document.</p>
 *
 * <h3>The workspace follows the wire, and is rebound rather than rebuilt</h3>
 *
 * <p>A reconnect is a different connection carrying the same workspace, so {@link #frame} re-asks
 * {@link HostServices#connection()} and rebinds the workspace when it has moved - keeping the object
 * every window already holds. Rebuilding it instead would fix the routing and leave every retained
 * window pointing at the old client, with all its callbacks on the wrong object.</p>
 */
public final class DesktopHost implements Disposable {

    private final HostServices services;
    private final UIDocument document;
    private final ConfigStorage config;

    /** Where derived output goes. Handed to an application at launch; never written to by this class. */
    private final Path cacheRoot;
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
        // THE HOST SAYS WHERE crystalgui/ IS AND NOTHING ELSE. The compositor derives its config
        // store, its cache root and its arrangement record from that one directory -- durable and
        // derived as separate trees, so "delete cache/ and nothing is lost" needs no caveat.
        Desktop desktop = Desktop.of(document).useStorage(services.installationDirectory());
        this.config = desktop.config();
        this.cacheRoot = desktop.cacheRoot();
        // AND THE ARRANGEMENT, which the compositor owns reading, applying and writing again.
        desktop.useLocalWorld(services::localWorldDirectory);
        desktop.persistAs(services.desktopId());
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

    /** Where private records go — the arrangement, an application's preferences, its session. */
    public ConfigStorage config() {
        return config;
    }

    /**
     * Where derived output goes — {@code crystalgui/cache/}, and everything under it is disposable.
     *
     * <p>Handed to an application at launch. Deleting this whole tree at any moment loses nothing; that
     * is what makes it a sibling of {@link #config()} rather than a directory inside it.</p>
     */
    public Path cacheRoot() {
        return cacheRoot;
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
                // NO STORE SET HERE. A backup belongs to a WORKSPACE, and which workspace this is
                // cannot be known until the server greets -- so the store is given in
                // WorkbenchApplication.restoreWhenReady, where the identity exists. Setting the
                // desktop's own store here wrote every server's unsaved work into one directory, and
                // was then overwritten by the application's anyway.
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
