package com.crystalgui.workbench.extension;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.notify.Notifications;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.workbench.WorkbenchContext;
import com.crystalgui.workbench.chrome.notification.NotificationsView;
import com.crystalgui.workbench.region.DockRegion;
import com.crystalgui.workbench.region.RegionSide;
import com.crystalgui.workbench.toolwindow.ToolWindowKind;
import com.crystalgui.workbench.view.ViewContainerRegistry;

/**
 * The notification history, as a feature a manifest can enable.
 *
 * <p>One of the engine's own three, and none of them had any business being the engine's:
 * {@code new Workbench(workspace)} shipped Project, Problems and Notifications whether an application
 * asked or not, so a product with no use for a notification history had one anyway. That is the defect
 * the {@code with(...)} id list exists to remove, and W6.5 is it applied to the built-ins.</p>
 *
 * <h3>The auxiliary rail, which is not an arbitrary choice</h3>
 *
 * <p>Where IntelliJ keeps it: a notification history is something you consult rather than something you
 * work in, so it belongs on the side that holds the things you glance at rather than beside the project
 * tree.</p>
 *
 * <h3>A dot, not a count</h3>
 *
 * <p>IntelliJ marks the bell and does not say how many, which is the right call: the number is not
 * actionable — you open the panel either way — and a two-digit count over a 20px rail icon is
 * unreadable. Written whether or not the panel has ever been opened, because the count is what tells
 * you to open it; a window dragged from one stripe to the other keeps it with no further wiring.</p>
 */
public final class NotificationsExtension implements WorkbenchExtension {

    public static final String ID = "crystalgui:notifications";

    /** The panel type id — a session record and a stripe button both name it. */
    public static final String TYPE = "notifications";

    /** Reveals the panel. What an unread badge points at. */
    public static final String SHOW = "workbench.showNotifications";

    /** {@code ServiceLoader} needs a public no-argument constructor. */
    public NotificationsExtension() {
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Disposable activate(WorkbenchContext workbench) {
        // BUILT EAGERLY: the dock caches a panel factory's result permanently, so returning a
        // placeholder while waiting for something hands back the placeholder for the rest of the session.
        NotificationsView view = new NotificationsView();
        return workbench.registerToolWindow(
                ToolWindowKind.of(TYPE, "Notifications")
                        .icon("crystalgui:toolwindows/notifications")
                        .region(DockRegion.AUXILIARY)
                        .side(RegionSide.PRIMARY)
                        .view(ctx -> view)
                        .toggle(SHOW)
                        .badge((ctx, set) -> {
                            Connection watch = Notifications.onDidChangeUnread.connect(count ->
                                    set.accept(count == null || count <= 0
                                            ? null : ViewContainerRegistry.DOT));
                            return watch::disconnect;
                        }));
    }
}
