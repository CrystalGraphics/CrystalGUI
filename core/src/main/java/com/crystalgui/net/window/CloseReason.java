package com.crystalgui.net.window;

import javax.annotation.Nullable;

/**
 * <b>Why a window ended</b>, and the same answer on both sides of the wire.
 *
 * <p>Top-level rather than nested in {@code ServerWindow}, because a CLIENT panel is told it too and a
 * client naming a server class to hear about its own teardown is backwards.</p>
 *
 * <p>It used to differ per side, and the old javadoc said so outright: the server was handed a reason
 * NAME and the client "the detail string the wire carried". So the same panel class, asked the same
 * question, got {@code "NOT_VALID"} on one side and {@code "no longer valid"} on the other — and a
 * teardown that branched on it worked on exactly one of them.</p>
 */
public enum CloseReason {

        /** The server asked — {@code window.close(…)} or {@code host.close(window, …)}. */
        SERVER,
        /** The user closed the frame. {@code ui/close}, the direction that used to be missing. */
        CLIENT,
        /** {@link Networked#stillValid} answered false. */
        NOT_VALID,
        /** The peer went away: a logout, a kick, a server stop. Nothing was sent; nobody was there. */
        CONNECTION_LOST,

    /**
     * A code this build does not know.
     *
     * <p>Only reachable across a version gap — a newer server naming a reason an older client has never
     * heard of. Answering something rather than throwing is deliberate: a window ending is not the
     * moment to fail, and a panel that cannot tell why it closed can still close.</p>
     */
    UNKNOWN;

    /** Parses a wire code, never throwing: an unrecognised one is {@link #UNKNOWN}. */
    public static CloseReason parse(@Nullable String code) {
        if (code == null || code.isEmpty()) return UNKNOWN;
        for (CloseReason reason : values()) {
            if (reason.name().equals(code)) return reason;
        }
        return UNKNOWN;
    }
}
