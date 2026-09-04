package com.crystalgui.workbench.chrome;

import com.crystalgui.core.notify.StatusBar;
import com.crystalgui.workbench.chrome.menu.MenuBarView;
import com.crystalgui.workbench.chrome.notification.NotificationBalloons;
import com.crystalgui.workbench.chrome.notification.NotificationsView;
import com.crystalgui.workbench.chrome.palette.QuickPick;
import com.crystalgui.workbench.chrome.preferences.NavigatorView;
import com.crystalgui.workbench.chrome.problems.ProblemsPanel;
import com.crystalgui.workbench.chrome.status.Breadcrumbs;
import com.crystalgui.workbench.chrome.status.ProcessesPopover;
import com.crystalgui.workbench.chrome.status.ProgressStatusItem;
import com.crystalgui.workbench.chrome.status.StatusBarView;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.ui.dom.NodeContract;
import com.crystalgui.ui.dom.NodeKinds;
import com.crystalgui.ui.dom.UIElementRegistry;

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
        UIElementRegistry.register(QuickPick.NAME, QuickPick::new, NodeContract.INERT);
        UIElementRegistry.register(MenuBarView.NAME, () -> new MenuBarView(CommandRegistry.global()), NodeContract.INERT);
        // A BAR OF ITS OWN when one is DECODED rather than built by a workbench. A described tree
        // names the kind and nothing else, so there is no workbench to ask -- and an unbound bar with
        // no entries is the honest answer, exactly as an unbound WindowFrame is.
        UIElementRegistry.register(StatusBarView.NAME, () -> new StatusBarView(new StatusBar()),
                NodeContract.INERT);
        UIElementRegistry.register(ProgressStatusItem.NAME, ProgressStatusItem::new, NodeContract.INERT);
        UIElementRegistry.register(ProcessesPopover.NAME, ProcessesPopover::new, NodeContract.INERT);
        UIElementRegistry.register(Breadcrumbs.NAME, Breadcrumbs::new, NodeContract.INERT);
        UIElementRegistry.register(NotificationsView.NAME, NotificationsView::new, NodeContract.INERT);
        UIElementRegistry.register(NotificationBalloons.NAME, NotificationBalloons::new, NodeContract.INERT);
        UIElementRegistry.register(ProblemsPanel.NAME, ProblemsPanel::new, NodeContract.INERT);
        UIElementRegistry.register(NavigatorView.NAME, NavigatorView::new, NodeContract.INERT);
    }
}
