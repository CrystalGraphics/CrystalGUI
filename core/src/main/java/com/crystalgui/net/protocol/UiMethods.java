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
    /**
     * An ordered edit script for the described tree. Replaced {@code ui/treeDelta}, which re-described
     * a whole child list per changed place and so destroyed every sibling's instance. @see TreeOps
     */
    public static final String TREE_OPS = "ui/treeOps";

    /** Server → client: this window is finished. Notification. */
    public static final String CLOSE_WINDOW = "ui/closeWindow";

    /**
     * Client → server: <b>the user closed this window.</b> Notification.
     *
     * <p>The direction that did not exist, and its absence was not a gap in a feature — it was half the
     * lifecycle missing. Minecraft has had it since alpha ({@code C0DPacketCloseWindow} →
     * {@code processCloseWindow} → {@code closeContainer}, and {@code ServerboundContainerClosePacket}
     * → {@code doCloseContainer} on 1.20), because a server holding a window's model needs to know when
     * nobody is looking at it any more. Without it a closed window left its session open, observing,
     * and flushing state deltas into a frame that had been destroyed — and the only close anything ever
     * noticed was the player disconnecting.</p>
     *
     * <p>A notification rather than a request: nobody is waiting. The window is already gone on the
     * side that sent this, so there is nothing an answer could change.</p>
     */
    public static final String CLOSE = "ui/close";

    /**
     * Server → client: <b>bring this window forward.</b> Notification.
     *
     * <p>What re-opening an already-open window means. Minecraft's answer is to close the previous
     * container and open a fresh one; ours keeps the window (its tree, its scroll position, whatever is
     * half-typed in it) and asks the compositor to raise it, because re-opening the same subject should
     * not cost the user their place in it.</p>
     *
     * <p>Deliberately not spelled as a re-sent {@code ui/openWindow}. That works — the client treats an
     * open as authoritative and would rebuild — but rebuilding a whole tree to answer "look at this
     * one" throws away exactly the state the window was kept for.</p>
     */
    public static final String FOCUS_WINDOW = "ui/focusWindow";

    /**
     * Client → server: <b>this window is / is not on screen.</b> Notification.
     *
     * <p>Hiding a window is not closing it — a hidden window is retained, detached, and expected to
     * come back exactly as it was. But the server does not know, so it goes on computing and sending
     * state deltas to a tree nobody is drawing. This says so, and the session suppresses its whole
     * flush until the window comes back.</p>
     *
     * <p>Payload: {@code {w, visible}}.</p>
     */
    public static final String VISIBILITY = "ui/visibility";

    /**
     * Client → server: <b>send me the stylesheet behind this hash.</b> A request.
     *
     * <p>The counterpart of {@link #DESCRIPTION}, and for the same reason: a {@link
     * com.crystalgui.net.SheetRef} crossed the wire from the day sheets did, and there was no way to
     * <em>fetch</em> one — so a client confronted with a hash it did not recognise had nothing to call,
     * and every host resolved refs from a constant in its own jar instead. That works for a UI whose
     * mod is installed on both sides and is a wall for anything a server authors.</p>
     *
     * <p>Content-addressed like a description, so a sheet is fetched once per hash however many windows
     * name it, and a changed sheet is simply a different key.</p>
     */
    public static final String SHEET = "ui/sheet";

    /** Client → server: the user did something to an element. Notification. */
    public static final String EVENT = "ui/event";

    /** {@code ui/openWindow}: the window's <b>type</b> — what a client dispatches local behaviour on. */
    public static final String TYPE = "type";

    /** {@code ui/openWindow}: the window's title, decided by the side that knows what it is. */
    public static final String TITLE = "title";

    /** {@code ui/openWindow}: the window's uniqueness and persistence key, or absent. */
    public static final String KEY = "key";

    /** The window id, on every {@code ui/*} payload. @see UiMethods */
    /**
     * The panel's class name, on {@code ui/openWindow} — what lets the client initialise the class
     * (and so register its tag) before the description arrives, with no registration call anywhere.
     * Additive with a fallback, like {@link #TYPE}: an older peer omits or ignores it.
     */
    public static final String UI_CLASS = "uiClass";

    public static final String WINDOW = "w";

    private UiMethods() {
    }
}
