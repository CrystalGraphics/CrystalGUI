package com.crystalgui.core.notify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.crystalgui.core.settings.Preferences;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The notification service's own rules: who said it, what closes with an application, and what the user silenced
 * being theirs across the desktop. (The history, the repeats and the eviction are {@link NotifyTest}'s.)
 */
public class NotificationServiceTest {

    private NotificationService service;
    private String[] focus;

    @Before
    public void setUp() {
        Preferences.update(p -> p.withAll(Map.of()));
        service = new NotificationService();
        focus = null;
        NotificationService.install(service, () -> service, () -> focus);
    }

    @After
    public void tearDown() {
        Preferences.update(p -> p.withAll(Map.of()));
    }

    /** A producer with no desktop in hand posts through {@code Notifications}; the focused application is its source. */
    @Test
    public void aNotificationNamesTheApplicationThatSaidIt() {
        focus = new String[] {"crystalgui:vfx-lab", "VFX Lab"};
        Notifications.info("Effect compiled");

        Notification said = service.history().get(0);
        assertEquals("crystalgui:vfx-lab", said.getSourceId());
        assertEquals("VFX Lab", said.getSourceName());

        focus = new String[] {"crystalgui:editor", "Crystal Editor"};
        Notifications.show(Notification.warning("From a job").from("crystalgui:vfx-lab", "VFX Lab"));
        assertEquals("a producer that names its source keeps it", "crystalgui:vfx-lab",
                service.history().get(1).getSourceId());
    }

    /** One list for the shell's toasts and every application's view; an application may read only its own. */
    @Test
    public void anApplicationCanReadJustWhatItSaid() {
        Notifications.show(Notification.info("one").from("a", "A"));
        Notifications.show(Notification.info("two").from("b", "B"));
        Notifications.show(Notification.info("three").from("a", "A"));

        assertEquals(3, service.history().size());
        List<Notification> mine = service.historyFrom("a");
        assertEquals(List.of("one", "three"), mine.stream().map(Notification::getMessage).toList());
    }

    /** An application's sticky notifications close with it; its ordinary ones stay in the history. */
    @Test
    public void anApplicationsStickyNotificationsCloseWithIt() {
        service.groups().register("must-ack", "Must acknowledge", NotificationDisplay.STICKY_BALLOON);
        NotificationHandle sticky = service.show(Notification.error("Disconnected").inGroup("must-ack")
                .from("a", "A"));
        service.show(Notification.info("Saved").from("a", "A"));
        NotificationHandle others = service.show(Notification.error("Their own").inGroup("must-ack")
                .from("b", "B"));

        service.closeFrom("a");

        assertFalse("its sticky one is gone", sticky.isOpen());
        assertTrue("another application's is not", others.isOpen());
        assertEquals("the ordinary one stays in the list", 2, service.history().size());
    }

    /** "Never show again" and a group's display are the user's, desktop-wide: they live in the preferences. */
    @Test
    public void whatTheUserSilencedHoldsForTheWholeDesktop() {
        NotificationService other = new NotificationService();
        service.suppress("hint.once");

        assertTrue("another service on the same desktop sees it", other.isSuppressed("hint.once"));
        assertTrue("and it is a preference, so it is saved with them",
                Preferences.current().raw("notifications.silenced.hint.once") != null);
        assertEquals(List.of("hint.once"), other.suppressed());

        service.groups().setDisplay("chatty", NotificationDisplay.NONE);
        assertEquals(NotificationDisplay.NONE, other.groups().displayOf("chatty"));
    }
}
