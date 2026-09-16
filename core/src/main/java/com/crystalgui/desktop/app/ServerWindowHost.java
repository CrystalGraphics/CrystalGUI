package com.crystalgui.desktop.app;

import com.crystalgui.net.window.WindowMount;

import javax.annotation.Nullable;

/**
 * An {@link Application} that wants first refusal on the windows a server opens.
 *
 * <p>A server's window has to land somewhere. By default that is the desktop, as a floating frame.
 * An application with places of its own — an editor tab, a tool window — can do better, and this is
 * how it says so.</p>
 *
 * <pre>{@code
 * public final class MyApp extends UIElement implements Application, ServerWindowHost {
 *     @Override
 *     public WindowMount windowMount(@Nullable WindowMount desktop) {
 *         // Take what this app has a place for; hand everything else back.
 *         return myPanels.mountOver(desktop);
 *     }
 * }
 * }</pre>
 *
 * <p>Return {@code desktop} unchanged to decline. Returning a mount that handles <em>everything</em>
 * is legal and means no server window ever reaches the desktop, which is rarely what you want: a
 * client with this application closed would then have nowhere to put one.</p>
 *
 * <p>Asked through a supplier and re-asked per frame, so an application built after the host is
 * still picked up. @see com.crystalgui.desktop.host.HostSession</p>
 */
public interface ServerWindowHost {

    /**
     * Where a server's windows should go while this application is running.
     *
     * @param desktop the fallback — the desktop's own mount, or null if there is none yet
     * @return the mount to use, or null to leave the fallback in place
     */
    @Nullable
    WindowMount windowMount(@Nullable WindowMount desktop);
}
