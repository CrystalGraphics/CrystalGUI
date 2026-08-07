package com.crystalgui.core.notify;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * <b>The two channels that replaced {@code onStatus}.</b>
 *
 * <p>Headless on purpose: neither of these has a widget, a window or a GL context in it, and a dedicated
 * server that creates a folder should be able to say so. That they load here is the assertion.</p>
 */
public class NotifyTest {

    @Before
    public void reset() {
        Notifications.resetForTesting();
        StatusBar.resetForTesting();
    }

    /**
     * <b>Two writers do not clobber each other.</b>
     *
     * <p>The whole reason status items are keyed. {@code onStatus} was one slot, so the shader graph's
     * line-owner readout — which fires on every caret move — erased the explorer's "created folder" a few
     * milliseconds after it appeared, and neither writer could tell it had happened.</p>
     */
    @Test
    public void twoWritersKeepSeparateSlots() {
        StatusBar.set("explorer", "created notes.txt");
        StatusBar.set("shadergraph.lineOwner", "line 12 emitted by multiply");

        assertTrue(StatusBar.text().contains("created notes.txt"));
        assertTrue(StatusBar.text().contains("line 12 emitted by multiply"));

        StatusBar.set("shadergraph.lineOwner", "line 13 emitted by combine");
        assertTrue("its own slot updates in place", StatusBar.text().contains("line 13"));
        assertTrue("and the other writer is untouched", StatusBar.text().contains("created notes.txt"));

        StatusBar.clear("shadergraph.lineOwner");
        assertEquals("created notes.txt", StatusBar.text());
    }

    /**
     * <b>Re-stating the same text is silent.</b>
     *
     * <p>Not an optimisation: the compile summary is written from a recompile, and a graph with an
     * animated node recompiles every frame. An announcement per frame is a poll wearing a callback, and
     * anything bound to it would rebuild forever.</p>
     */
    @Test
    public void restatingAnUnchangedItemAnnouncesNothing() {
        List<String> announced = new ArrayList<>();
        StatusBar.onDidChange.connect(announced::add);

        StatusBar.set("compile", "compiled 9n/8e");
        assertEquals(1, announced.size());

        for (int i = 0; i < 10; i++) StatusBar.set("compile", "compiled 9n/8e");
        assertEquals("an unchanged item announced " + announced.size() + " times", 1, announced.size());
    }

    /**
     * <b>Severity survives, which a String could not carry.</b>
     *
     * <p>"saved" and "save failed" arrived identically through {@code onStatus} and were rendered
     * identically. A consumer cannot colour, hold or filter what it cannot distinguish.</p>
     */
    @Test
    public void severityReachesTheListener() {
        List<Notification> seen = new ArrayList<>();
        Notifications.onDidNotify.connect(seen::add);

        Notifications.info("saved notes.txt");
        Notifications.error("save failed: notes.txt");

        assertEquals(2, seen.size());
        assertEquals(Notification.Severity.INFO, seen.get(0).getSeverity());
        assertEquals(Notification.Severity.ERROR, seen.get(1).getSeverity());
    }

    /** An action travels with the message — the half that makes a failure actionable rather than logged. */
    @Test
    public void anActionTravelsWithTheNotification() {
        boolean[] retried = { false };
        Notifications.show(Notification.error("copy failed")
                .withAction("Retry", () -> retried[0] = true));

        Notification.Action action = Notifications.history().get(0).actions().get(0);
        assertEquals("Retry", action.label());
        action.run().run();
        assertTrue(retried[0]);
    }

    /** The history is a convenience, not a log — it must not grow without bound. */
    @Test
    public void theHistoryIsBounded() {
        for (int i = 0; i < 500; i++) Notifications.info("message " + i);

        List<Notification> history = Notifications.history();
        assertTrue("unbounded at " + history.size(), history.size() <= 100);
        assertEquals("and it keeps the NEWEST", "message 499",
                history.get(history.size() - 1).getMessage());
    }
}
