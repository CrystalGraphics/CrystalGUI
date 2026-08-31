package com.crystalgui.chrome;

import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.ui.dom.NodeContract;
import com.crystalgui.ui.dom.NodeKinds;
import com.crystalgui.ui.dom.UINodeRegistry;

/**
 * The {@code chrome} layer's kinds — {@link NodeKinds} for the shell.
 *
 * <h3>Its own service, because a LAYER speaks for itself</h3>
 *
 * <p>{@code Widgets} registered these at first and {@code LayeringTest} refused it: {@code widget}
 * is below {@code chrome}, so a widget-layer class naming {@code QuickPick} is a layer reaching
 * upward. The check is right — the whole point of the per-layer service is that nothing below has to
 * know what is above it — and the repair is one file rather than an exemption.</p>
 *
 * <h3>All INERT, and the registration is for the CASCADE</h3>
 *
 * <p>Nothing here decodes from a description: a picker is a gesture and a menu bar is built by its
 * host. What a kind buys is that a theme can address it — 32 shipped rules name {@code quickpick} —
 * and that the node does not report {@code crystalgui:element}, which would match none of them and
 * every rule written for a bare element instead.</p>
 */
public final class ChromeKinds implements NodeKinds {

    /** {@code ServiceLoader} needs a public no-argument constructor. */
    public ChromeKinds() {
    }

    @Override
    public void register() {
        UINodeRegistry.register(QuickPick.NAME, QuickPick::new, NodeContract.INERT);
        UINodeRegistry.register(MenuBarView.NAME,
                () -> new MenuBarView(CommandRegistry.global()), NodeContract.INERT);
    }
}
