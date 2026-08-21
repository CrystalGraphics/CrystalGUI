package com.crystalgui.net.protocol;

/**
 * The UI protocol's vocabulary — what {@code UIPacket}'s nine record types became.
 *
 * <p>Namespaced with a slash, following LSP's {@code textDocument/hover}. The prefix is all the
 * "channel" an IDE-shaped protocol needs: {@code ui/*} here, {@code workspace/*} for the file protocol,
 * {@code script/*} for a runtime in {@code language/} that {@code core} never learns about. Two
 * subsystems cannot collide without choosing to.</p>
 *
 * <p><b>These constants are a convenience, not a registry.</b> Nothing enumerates them and nothing
 * checks a method against them — a peer may send any string, and an unrecognised one is answered with
 * {@link ProtocolErrors#METHOD_NOT_FOUND} or logged once. That is deliberate: the moment this file
 * becomes the list of legal methods, it is {@code UIPacket} again with different syntax.</p>
 *
 * <h3>Every payload carries {@code w}</h3>
 *
 * <p>The window id lives in the payload rather than the envelope, because it is a fact about the UI
 * protocol and the envelope is not allowed to know one. A session drops anything addressed to a
 * different window — one transport serves one session, so this is not routing between concurrent
 * windows but a guard against a packet still in flight when a window closed being applied to whatever
 * session took its place.</p>
 */
public final class UiMethods {

    /** Server → client: a window exists, here is its hash. Notification. */
    public static final String OPEN_WINDOW = "ui/openWindow";

    /**
     * Client → server: send me the tree behind this hash. <b>A request</b>, and always was.
     *
     * <p>{@code RequestDescription} and {@code Description} were two packet types spelling one
     * ask-and-answer, with the correlation left implicit — nothing tied a description to the request
     * that wanted it. As a REQUEST it correlates by id for free, and a client that asks twice cannot
     * confuse the two answers.</p>
     */
    public static final String DESCRIPTION = "ui/description";

    /** Server → client: these elements' states changed. Notification. */
    public static final String STATE_DELTA = "ui/stateDelta";

    /**
     * Server → client: the SHAPE of the tree changed. Notification.
     *
     * <p>Carries, per entry, an anchor's network id and that anchor's described children in full. Both
     * sides then re-derive every id from the new tree — which is what makes this possible at all without
     * transmitting an id table. Ids are a depth-first position, so an insertion renumbers everything
     * after it; the design that looked impossible assumed ids had to be <em>stable</em>, and they only
     * have to be <em>agreed</em>. Two peers applying the same delta to the same tree in the same order
     * agree by construction.</p>
     *
     * <p>Ordering is therefore load-bearing, and is exactly what the transport guarantees within a
     * stream: a state delta computed after a renumber must not overtake the tree delta that caused
     * it.</p>
     */
    public static final String TREE_DELTA = "ui/treeDelta";

    /** Server → client: this window is finished. Notification. */
    public static final String CLOSE_WINDOW = "ui/closeWindow";

    /** Client → server: the user did something to an element. Notification. */
    public static final String EVENT = "ui/event";

    /** The window id, on every {@code ui/*} payload. @see UiMethods */
    public static final String WINDOW = "w";

    private UiMethods() {
    }
}
