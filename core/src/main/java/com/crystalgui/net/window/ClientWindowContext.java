package com.crystalgui.net.window;

import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.SheetRef;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.ui.UIElement;

/**
 * Everything a {@link WindowMount} or a {@link ClientWindowBehaviour} is told about one window.
 *
 * <p>Handed over at mount and again whenever the tree is rebuilt, so a consumer never has to remember
 * which of its fields have gone stale.</p>
 */
public interface ClientWindowContext {

    /** The rebuilt tree. A <b>new object</b> after a re-describe — never cache it across one. */
    UIElement root();

    /** What kind of window this is, or {@code ""} from a server that named none. */
    String type();

    /** What to call it on screen, or {@code ""}. */
    String title();

    /** Its uniqueness and persistence key, or {@code null}. A frame takes it so geometry restores. */
    @Nullable
    String key();

    /**
     * The themes the server named, <b>in the order it named them</b>.
     *
     * <p>Order is load-bearing and must not be sorted: the style engine's sheet list is flat and
     * ordered, and re-adding a sheet appends it — that is, at the highest priority. A host that applies
     * these in a different order gets a different-looking panel with every rule correct.</p>
     */
    List<SheetRef> sheets();

    /** Whether the engine's own sheet goes underneath. Almost always yes. */
    boolean useUserAgentSheet();

    /** This window's session — for {@code call}, {@code onCall}, {@code notify}, {@code onNotify}. */
    ClientUiSession<Object> session();

    /** The wire everything on this client shares. For connection-scoped things, never window ones. */
    ProtocolConnection<Object> connection();

    /**
     * <b>The user closed this window.</b> The one thing a mount owes the host.
     *
     * <p>Sends {@code ui/close}, ends the session locally and tells the behaviour. Idempotent, and
     * silent when the window has already ended some other way — so a mount may call it from a teardown
     * path that also runs on a server-driven close without having to work out which happened.</p>
     */
    void userClosed();

    /**
     * Whether this window is on screen, for a mount that can hide one without closing it.
     *
     * <p>Optional, and a mount that never calls it costs nothing but the deltas it goes on receiving.
     * @see com.crystalgui.net.protocol.UiMethods#VISIBILITY</p>
     */
    void visibilityChanged(boolean visible);
}
