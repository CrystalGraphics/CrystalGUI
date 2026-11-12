package com.crystalgui.desktop.notification;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.crystalgui.core.notify.Notification;
import com.crystalgui.core.notify.NotificationService;
import com.crystalgui.core.notify.Notifications;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import org.junit.Test;

/**
 * <b>The toasts are the shell's.</b> A notification posted by anything, with no desktop in hand, shows once, in the
 * desktop's own layer above every window, and is in the desktop service's list for any application's view.
 */
public class ShellToastsTest extends UiDocumentTestBase {

    private static int cardsIn(UIElement layer) {
        int cards = 0;
        for (UIElement child : layer.children()) {
            if (child instanceof NotificationCard) cards++;
        }
        return cards;
    }

    private NotificationBalloons balloonsOf(Desktop desktop) {
        for (UIElement child : desktop.overlayLayer().children()) {
            if (child instanceof NotificationBalloons found) return found;
        }
        return null;
    }

    @Test
    public void aNotificationPostedFromAnywhereIsOneToastInTheShell() {
        Desktop desktop = Desktop.of(document);
        frame();
        NotificationBalloons toasts = balloonsOf(desktop);
        assertNotNull("the desktop carries the toast layer", toasts);

        Notifications.show(Notification.info("Effect compiled").withDetail("from a lab"));
        frame();
        frame();

        assertEquals("one toast, above every window", 1, cardsIn(toasts));
        assertEquals("and it is in the desktop's list, which any application's view reads",
                1, desktop.notifications().history().size());
        assertTrue(NotificationService.current() == desktop.notifications());
    }
}
