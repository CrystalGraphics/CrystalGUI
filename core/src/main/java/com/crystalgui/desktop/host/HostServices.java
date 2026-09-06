package com.crystalgui.desktop.host;

import java.nio.file.Path;

import javax.annotation.Nullable;

import com.crystalgui.net.protocol.ProtocolConnection;

/**
 * <b>The three things a platform knows and the engine cannot</b> - implement it to run CrystalGUI on a
 * new host.
 *
 * <p>Where private files go, how big a pixel is, and whether there is a server to talk to. Answer those
 * and {@link DesktopHost} gives you a compositor, a workspace that follows the connection, and somewhere
 * for a server's windows to land - none of which you write.</p>
 *
 * <pre>{@code
 * DesktopHost host = DesktopHost.create(new HostServices() {
 *     public Path storageRoot()     { return gameDir.resolve("crystalgui"); }
 *     public float uiScale()        { return currentGuiScale(); }
 *     public String desktopId()     { return "client"; }
 *     public ProtocolConnection<Object> connection() { return liveConnectionOrNull(); }
 * });
 * }</pre>
 *
 * <p>The measure of this interface is what it does <b>not</b> ask: not what the window is called, not
 * which application to open, not where a session goes, not when to ask for the project list. Every one
 * of those is the same answer on every host, so the engine decides them.</p>
 *
 * <h3>The connection is re-asked, never pushed</h3>
 *
 * <p>{@link #connection()} is read once per frame rather than announced, so it may return a different
 * object - or null - at any time and the engine copes. That is deliberate: a reconnect is a new
 * connection carrying the same workspace, and re-asking is what makes the rebind reachable. Answering
 * null simply means "no server right now", which is a supported state rather than an error.</p>
 */
public interface HostServices {

    /**
     * What a host answers {@link #uiScale()} with when it has nothing better to say.
     *
     * <p><b>Deliberately not the game's GUI Scale.</b> That setting sizes 16px widgets and an 8px
     * bitmap font; a desktop carrying an editor, a taskbar and tool windows has far more on it than an
     * inventory, so scaling the two by one number makes whichever the player did not choose for
     * unusable. The shipped sheets are authored and measured at this.</p>
     */
    float DEFAULT_UI_SCALE = 2f;

    /**
     * Where this host's {@code crystalgui/} directory is — the one root for everything it stores.
     *
     * <p>The engine owns the tree inside it: {@code workspace-config/} for what must survive,
     * {@code cache/} for what can be rebuilt, {@code projects/} for a workspace's own files. A host
     * answers <em>where</em>, never <em>what goes where</em>, so two hosts cannot drift into two
     * layouts.</p>
     *
     * <pre>{@code
     * public Path storageRoot() { return gameDir.resolve("crystalgui"); }
     * }</pre>
     *
     * <p>Private, and never inside a workspace: a session record must not become part of a project
     * somebody ships.</p>
     */
    Path storageRoot();

    /** How many device pixels one logical pixel is. Applied once, to the box tree's root transform. */
    float uiScale();

    /**
     * Which desktop this is, for the arrangement record.
     *
     * <p>Two hosts in one installation — a game client and a dedicated tool — keep separate window
     * layouts, and neither should have to know the other exists.</p>
     */
    String desktopId();

    /** The connection to a server, or null when there is none. Re-asked per frame; see the class note. */
    @Nullable
    ProtocolConnection<Object> connection();
}
