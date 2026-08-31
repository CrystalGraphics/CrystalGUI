package com.crystalgui.desktop;

import com.crystalgui.desktop.window.WindowIcon;
import com.crystalgui.ui.dom.NodeContract;
import com.crystalgui.ui.dom.NodeKinds;
import com.crystalgui.ui.dom.UINodeRegistry;

/**
 * <b>The desktop layer's kinds</b> — the compositor's own nodes.
 *
 * <p>Its own service rather than an entry in {@code Widgets}, because the point of
 * {@link NodeKinds} is that a LAYER speaks for itself: {@code widget} does not know what a window is,
 * and a registry importing both would be the upward reference {@code LayeringTest} refuses.</p>
 */
public final class DesktopKinds implements NodeKinds {

    /** {@code ServiceLoader} needs a public no-argument constructor. */
    public DesktopKinds() {
    }

    @Override
    public void register() {
        UINodeRegistry.register(WindowIcon.NAME, WindowIcon::new, NodeContract.INERT);
    }
}
