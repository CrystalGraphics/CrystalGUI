package com.crystalgui.desktop.host;

import java.nio.file.Path;

import javax.annotation.Nullable;

import com.crystalgui.net.protocol.ProtocolConnection;

/**
 * <b>Everything a platform genuinely knows and the engine cannot</b> — and nothing else.
 *
 * <p>Three questions. Where this host keeps private files, how big a pixel is, and whether there is a
 * server to talk to. A loader that answers those gets a desktop, a workspace that follows the
 * connection, and somewhere for a server's windows to land; it writes none of it.</p>
 *
 * <p>The measure of this interface is what it does <em>not</em> ask. Not what the window is called, not
 * which application to open, not where the editor's session goes, not when to ask for the project list —
 * every one of which the 1.7.10 screen decided for itself, and every one of which is the same answer on
 * every host.</p>
 *
 * <h3>The connection is re-asked, never pushed</h3>
 *
 * <p>{@link #connection()} is read once per frame rather than announced. A signal was drafted here and
 * dropped: nothing on any host emits one, so it would be a slot that is declared, wired and never
 * fired — and the engine already has to cope with the wire moving under it, because a reconnect is a
 * different {@code ProtocolConnection} carrying the same workspace. Re-asking is free when it has not
 * moved, and it is what makes a rebind reachable at all.</p>
 */
public interface HostServices {

    /**
     * Where this host keeps private files — the desktop's arrangement, an application's session, backups.
     *
     * <p>Private, and never inside a workspace: a session record must not become part of a project
     * somebody ships.</p>
     */
    Path configDirectory();

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
