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
 * The <b>Notifications</b> panel - the history of everything that has been announced.
 *
 * <p>Enable it by naming {@link #ID} in an application's manifest. It registers the tool window and a
 * rail badge that marks unread. Anything raised through {@code Notifications} appears here; nothing has
 * to know this panel exists.</p>
 *
 * <h3>The auxiliary rail, and a dot rather than a count</h3>
 *
 * <p>A notification history is something you consult rather than something you work in, so it sits on
 * the side that holds the things you glance at - where IntelliJ keeps it. The badge is a dot because the
 * number is not actionable (you open the panel either way) and a two-digit count over a 20px rail icon
 * is unreadable. It is written whether or not the panel has ever been opened, since the unread mark is
 * exactly what tells you to open it.</p>
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
