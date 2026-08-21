package com.crystalgui.net.command;

/**
 * The {@code command/*} vocabulary — a server contributing actions to a client's command registry.
 *
 * <p>{@code CommandRegistry}'s javadoc has anticipated this since it was written ("a server-driven UI can
 * have two windows open whose {@code edit.save} legitimately mean different things"), and {@code Protocols}
 * makes the namespace ordinary: {@code command/*} beside {@code ui/*} and {@code fs.*}, bound to every
 * connection the same way the workspace is.</p>
 *
 * <h3>Four messages, and the direction of each is the design</h3>
 *
 * <p>Everything <b>about</b> a command flows server → client as a notification, because the server is the
 * only one that knows; the single thing flowing the other way is <b>the user did it</b>, and that is a
 * request, because a command can fail and the person who pressed the key deserves to be told.</p>
 *
 * <p>Enablement is pushed rather than asked. A client that had to ask "may I?" as the menu opened would
 * put a round trip inside a UI gesture — which is the same reasoning that shapes 5.4, and the reason
 * {@link #SET_ENABLED} exists at all rather than {@code command/isEnabled}.</p>
 *
 * @see RemoteCommandPolicy for what a client will and will not accept
 */
public final class CommandProtocol {

    private CommandProtocol() {
    }

    /**
     * Server → client. Commands to add, or to redefine.
     *
     * <p>Carries {@link #COMMANDS}, a list of {@code {id, label, enabled}}. Re-contributing an id
     * replaces it, matching {@code CommandRegistry.register}, which allows replacement on purpose — that
     * is how a label is updated without a withdraw/contribute pair racing the palette.</p>
     */
    public static final String CONTRIBUTE = "command/contribute";

    /** Server → client. Ids to remove, as {@link #COMMANDS} of {@code {id}}. */
    public static final String WITHDRAW = "command/withdraw";

    /**
     * Server → client. Enablement for ids already contributed, as {@link #COMMANDS} of
     * {@code {id, enabled}}.
     *
     * <p>Separate from {@link #CONTRIBUTE} because it is the message that will actually be sent often —
     * a selection changing, an operator being promoted — and re-sending a label and a menu placement to
     * say "no longer available" is the shape that makes people avoid pushing state at all.</p>
     */
    public static final String SET_ENABLED = "command/setEnabled";

    /**
     * Client → server. The user ran it.
     *
     * <p>A <b>request</b>, not a notification, so a refusal reaches the person who pressed the key. A
     * command that fails silently is indistinguishable from a keybinding that is not wired up, which is
     * the single most confusing failure a command system can have.</p>
     */
    public static final String INVOKE = "command/invoke";

    // ── Payload keys ────────────────────────────────────────────────────────────────────────────

    /** The list every server → client message carries. */
    public static final String COMMANDS = "commands";

    public static final String ID = "id";
    public static final String LABEL = "label";
    public static final String ENABLED = "enabled";
}
