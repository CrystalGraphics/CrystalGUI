package com.crystalgui.net.command;

import javax.annotation.Nullable;

/**
 * What a client will accept from a server's {@code command/*}. <b>The trust boundary.</b>
 *
 * <h3>Why this needed one at all</h3>
 *
 * <p>Every other thing a server sends over this protocol <em>describes</em> — a tree, a file, a state
 * delta. This is the first message that changes what the client's own machinery <b>does</b>: a
 * contributed command lands in the same {@code CommandRegistry} the palette enumerates, the menus render
 * and the keymap resolves against. {@code CommandRegistry.register} replaces by id on purpose (that is
 * how a theme or a mod overrides a built-in), so without a rule a server could claim {@code edit.save}
 * and the client's own Save would quietly become a packet to the server.</p>
 *
 * <h3>A namespace floor, not a configurable allowlist</h3>
 *
 * <p>{@code ScriptPolicy} is the precedent and it settled two things worth reusing. A control nobody will
 * configure is worse than a leaky one that gets used — so the default is safe and needs no host to think
 * about it. And a filter its subject can switch off is not a filter, which is why
 * {@code ScriptPolicy.ALWAYS_REFUSED} is a <b>floor</b> checked ahead of everything else rather than a
 * default a host may edit away.</p>
 *
 * <p>Here the floor is a namespace: a server may only claim ids under {@link #RESERVED_PREFIX}. That is
 * strictly stronger than a denylist of built-ins and needs no maintenance — a built-in added next year is
 * protected by construction, whereas a list of protected ids is a list somebody forgets to add to. It
 * costs a server nothing real: {@code server.restart} is a perfectly good id, and the <b>label</b> is
 * what a user reads.</p>
 *
 * <h3>The other two limits are about a hostile or broken peer, not a malicious one</h3>
 *
 * <ul>
 *   <li><b>A count cap.</b> A palette listing ten thousand rows is unusable, and nothing else on this
 *       wire bounds how many notifications a server sends.</li>
 *   <li><b>Label sanitisation.</b> A label is drawn; a control character or a newline in one is a
 *       rendering fault at best. Truncated rather than refused, because a long label is clumsy and a
 *       missing command is broken.</li>
 * </ul>
 *
 * <p><b>Refusal is per command, never per message.</b> One bad entry in a batch of twenty must not cost
 * the other nineteen — the same reason a state delta applies per entry.</p>
 */
public final class RemoteCommandPolicy {

    /**
     * The only namespace a server may register into.
     *
     * <p>Dotted like every other command id, so it reads as an ordinary id and sorts together in a
     * palette. Not configurable, and deliberately: see the class javadoc.</p>
     */
    public static final String RESERVED_PREFIX = "server.";

    /** How many commands one connection may contribute. */
    public static final int MAX_COMMANDS = 256;

    /** How long a label may be before it is cut. */
    public static final int MAX_LABEL_LENGTH = 120;

    /** Accepts everything the floor allows. What a host gets without doing anything. */
    public static final RemoteCommandPolicy DEFAULT = new RemoteCommandPolicy(true);

    /**
     * Accepts nothing.
     *
     * <p>For a host that wants no server-driven commands at all. Spelled out rather than left to
     * "just don't call it", because the binding happens automatically for every connection.</p>
     */
    public static final RemoteCommandPolicy REFUSE_ALL = new RemoteCommandPolicy(false);

    private final boolean enabled;

    private RemoteCommandPolicy(boolean enabled) {
        this.enabled = enabled;
    }

    /** Whether this client accepts server-contributed commands at all. */
    public boolean acceptsAny() {
        return enabled;
    }

    /**
     * Why {@code id} is unacceptable, or {@code null} if it is fine.
     *
     * <p>Returns the reason rather than a boolean so the refusal can be logged usefully. "A server
     * command was refused" with no id and no reason is a report nobody can act on.</p>
     */
    @Nullable
    public String refuse(String id) {
        if (!enabled) return "this client accepts no server-contributed commands";
        if (id == null || id.isEmpty()) return "a command needs an id";
        if (!id.startsWith(RESERVED_PREFIX)) {
            return "'" + id + "' is outside the reserved namespace '" + RESERVED_PREFIX
                    + "'; a server may not claim an id the client defines";
        }
        if (id.length() == RESERVED_PREFIX.length()) return "'" + id + "' has no name after the prefix";
        return null;
    }

    /**
     * The label as it will be shown.
     *
     * <p>Control characters become spaces rather than being stripped, so two words cannot be run
     * together into a third that was never written.</p>
     */
    public String sanitiseLabel(@Nullable String label, String fallbackId) {
        String source = label == null || label.isEmpty() ? fallbackId : label;
        StringBuilder out = new StringBuilder(Math.min(source.length(), MAX_LABEL_LENGTH));
        for (int i = 0; i < source.length() && out.length() < MAX_LABEL_LENGTH; i++) {
            char c = source.charAt(i);
            out.append(c < ' ' || c == 0x7F ? ' ' : c);
        }
        return out.toString();
    }
}
