package com.crystalgui.core.notify;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    /**
     * <b>Alignment is the writer's, and it is part of what "changed" means.</b>
     *
     * <p>Only the writer knows which end an item belongs to — "Ln 51, Col 39" is glanced at in a fixed
     * place, "created notes.txt" is read as prose — so a view guessing from the id or the text would be
     * inventing an answer that already exists. And moving an item between ends has to announce: comparing
     * the text alone drops the move exactly when the words stay the same, which is when a bar that never
     * redrew is hardest to spot.</p>
     */
    @Test
    public void anItemCarriesItsAlignmentAndMovingItAnnounces() {
        StatusBar.set("explorer", "created notes.txt");
        StatusBar.set("caret", "Ln 51, Col 39", StatusBar.Align.RIGHT);

        assertEquals(StatusBar.Align.LEFT, StatusBar.items().get(0).align());
        assertEquals("the two-argument form chooses LEFT",
                StatusBar.Align.RIGHT, StatusBar.items().get(1).align());
        assertTrue("text() still composes every item, whatever end it sits at",
                StatusBar.text().contains("created notes.txt") && StatusBar.text().contains("Ln 51"));

        List<String> announced = new ArrayList<>();
        StatusBar.onDidChange.connect(announced::add);

        StatusBar.set("caret", "Ln 51, Col 39", StatusBar.Align.RIGHT);
        assertTrue("an identical rewrite is silent", announced.isEmpty());

        StatusBar.set("caret", "Ln 51, Col 39", StatusBar.Align.LEFT);
        assertEquals("the same words at the other end is a change", 1, announced.size());
    }

    /**
     * <b>Reading is not dismissing.</b>
     *
     * <p>Two verbs, deliberately: opening the panel clears the bell, and the messages stay until "Clear
     * all". Folding them together is how a panel you opened once quietly throws away the message you
     * opened it for — and it is invisible, because the badge going away looks like the feature working.</p>
     */
    @Test
    public void markingReadKeepsTheHistoryAndClearingDoesNot() {
        Notifications.info("one");
        Notifications.warning("two");
        assertEquals(2, Notifications.unread());
        assertEquals(2, Notifications.history().size());

        Notifications.markAllRead();
        assertEquals("the badge is gone", 0, Notifications.unread());
        assertEquals("but the messages are not", 2, Notifications.history().size());

        Notifications.clear();
        assertEquals(0, Notifications.history().size());
    }

    /**
     * A cleared history announces on its own channel, because nothing arrived to announce it with.
     *
     * <p>A view watching only {@code onDidNotify} would go on showing a list the user has just
     * dismissed — there is no notification to hand it, which is exactly why the second signal exists.</p>
     */
    @Test
    public void clearingAnnouncesOnItsOwnChannel() {
        List<String> seen = new ArrayList<>();
        Notifications.onDidClear.connect(() -> seen.add("cleared"));

        Notifications.info("something");
        assertTrue("an arrival is not a clear", seen.isEmpty());

        Notifications.clear();
        assertEquals(1, seen.size());

        Notifications.clear();
        assertEquals("clearing an empty history says nothing", 1, seen.size());
    }

    /** A notification is stamped when it happens, not when something gets round to showing it. */
    @Test
    public void aNotificationCarriesWhenItHappened() {
        long before = System.currentTimeMillis();
        Notification made = Notification.error("boom").withDetail("the detail").inGroup("compiler");
        long after = System.currentTimeMillis();

        assertTrue("stamped at construction: " + made.getTimestamp(),
                made.getTimestamp() >= before && made.getTimestamp() <= after);
        assertEquals("the detail", made.getDetail());
        assertEquals("compiler", made.getGroupId());
        assertEquals("an unnamed group is the default",
                Notification.DEFAULT_GROUP, Notification.info("x").getGroupId());
    }

    /**
     * <b>An immediate repeat collapses into the message it repeats.</b>
     *
     * <p>The case worth catching is a producer reached from a path that fires more than once — a retry loop,
     * a recompile — where the same sentence lands several times in a row and buries everything else in a
     * hundred-deep history. It counts on the entry instead, and announces on its own channel so a view
     * updates the card it is already showing rather than adding another.</p>
     *
     * <p>A repeat also leaves the unread count alone: the same message twice is not new information, and a
     * bell that ticks up while nothing new has been said is worse than one that stays put.</p>
     */
    @Test
    public void animmediateRepeatCollapses() {
        List<Notification> repeated = new ArrayList<>();
        Notifications.onDidRepeat.connect(repeated::add);

        Notifications.error("Save failed");
        Notifications.error("Save failed");
        Notifications.error("Save failed");

        assertEquals("three arrivals became one entry", 1, Notifications.history().size());
        assertEquals(3, Notifications.history().get(0).getRepeats());
        assertEquals("each repeat announced once", 2, repeated.size());
        assertEquals("a repeat is not news", 1, Notifications.unread());
    }

    /**
     * Only against the NEWEST entry — two failures either side of something else are two things that
     * happened, and folding them would make "arrived just now" unanswerable.
     */
    @Test
    public void aRepeatMustBeImmediateToCollapse() {
        Notifications.error("Save failed");
        Notifications.info("Something else");
        Notifications.error("Save failed");

        assertEquals("the older one was folded into the newer", 3, Notifications.history().size());
        for (Notification each : Notifications.history()) {
            assertEquals("nothing should have collapsed", 1, each.getRepeats());
        }
    }

    /** Severity and detail are part of sameness; the timestamp and the actions deliberately are not. */
    @Test
    public void whatCountsAsTheSameMessage() {
        Notification base = Notification.error("Save failed").withDetail("notes.txt");
        assertTrue(base.saysTheSameAs(
                Notification.error("Save failed").withDetail("notes.txt").withAction("Retry", () -> { })));
        assertFalse("a different detail is a different message",
                base.saysTheSameAs(Notification.error("Save failed").withDetail("other.txt")));
        assertFalse("a different severity is a different message",
                base.saysTheSameAs(Notification.warning("Save failed").withDetail("notes.txt")));
        assertFalse(base.saysTheSameAs(null));
    }
}
