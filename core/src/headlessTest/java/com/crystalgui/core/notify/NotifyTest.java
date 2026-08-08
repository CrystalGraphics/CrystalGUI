package com.crystalgui.core.notify;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * <b>The two channels that replaced {@code onStatus}.</b>
 *
 * <p>Headless, because neither of them needs a window: a dedicated server that never renders anything can
 * still say that something happened, and that is the point of both living in {@code core}.</p>
 */
public class NotifyTest {

    @Before
    public void setUp() {
        StatusBar.resetForTesting();
        Notifications.resetForTesting();
        NotificationGroups.resetForTesting();
    }

    @After
    public void tearDown() {
        StatusBar.resetForTesting();
        Notifications.resetForTesting();
        NotificationGroups.resetForTesting();
    }

    private static StatusBarEntryAccessor add(String name, String text) {
        return StatusBar.addEntry(StatusBarEntry.of(name, text), name, StatusBarAlignment.LEFT);
    }

    // ── Status bar ──────────────────────────────────────────────────────────────────────────────

    /**
     * <b>Two writers do not clobber each other.</b>
     *
     * <p>{@code onStatus} was one slot, so the shader graph's line-owner readout — which fires on every
     * caret move — erased the explorer's "created folder" a few milliseconds after it appeared, and
     * neither writer could tell it had happened.</p>
     *
     * <p><b>Not even when they choose the same id.</b> Keying by string only narrowed the collision from
     * "one slot for everyone" to "one slot per string"; the handle is what removes it, because two
     * registrations are two entries whatever they are called.</p>
     */
    @Test
    public void twoWritersKeepSeparateEntries() {
        add("explorer", "created notes.txt");
        StatusBarEntryAccessor owner = add("shadergraph.lineOwner", "line 12 emitted by multiply");

        assertTrue(StatusBar.text().contains("created notes.txt"));
        assertTrue(StatusBar.text().contains("line 12 emitted by multiply"));

        owner.update(owner.entry().withText("line 13 emitted by combine"));
        assertTrue("its own entry updates in place", StatusBar.text().contains("line 13"));
        assertTrue("and the other writer is untouched", StatusBar.text().contains("created notes.txt"));

        owner.dispose();
        assertEquals("created notes.txt", StatusBar.text());

        StatusBar.addEntry(StatusBarEntry.of("Build", "compiling"), "same", StatusBarAlignment.LEFT);
        StatusBar.addEntry(StatusBarEntry.of("Index", "indexing"), "same", StatusBarAlignment.LEFT);
        assertEquals("an id is not an identity", 3, StatusBar.size());
    }

    /**
     * <b>Re-stating the same entry is silent.</b>
     *
     * <p>Not an optimisation: the compile summary is written from a recompile, and a graph with an
     * animated node recompiles every frame. An announcement per frame is a poll wearing a callback, and
     * anything bound to it would rebuild forever.</p>
     *
     * <p>The guard is the record's own {@code equals}, which is what makes it total. It used to be a
     * hand-written comparison of every field, so a field added to the entry was silently left out of
     * "changed" — and an entry whose <em>only</em> difference was that field announced nothing at all.</p>
     */
    @Test
    public void restatingAnUnchangedEntryAnnouncesNothing() {
        StatusBarEntryAccessor compile = add("compile", "compiled 9n/8e");

        int[] announced = { 0 };
        StatusBar.onDidChange.connect(() -> announced[0]++);

        for (int i = 0; i < 10; i++) compile.update(StatusBarEntry.of("compile", "compiled 9n/8e"));
        assertEquals("an unchanged entry announced " + announced[0] + " times", 0, announced[0]);

        compile.update(compile.entry().withText("compiled 10n/9e"));
        assertEquals("a real change must still announce", 1, announced[0]);

        compile.update(compile.entry().withTooltip("996 chars"));
        assertEquals("a tooltip-only change is a change", 2, announced[0]);
    }

    /**
     * <b>Alignment and priority are the writer's, and they decide the order.</b>
     *
     * <p>Only the writer knows which end an entry belongs to — "Ln 51, Col 39" is glanced at in a fixed
     * place, "created notes.txt" is read as prose. Order used to be "whoever registered first", so the
     * right-hand group's layout was an implementation detail of {@code TextFileDocument.setActive}.
     * Higher priority is further left, which is VS Code's rule in both groups.</p>
     */
    @Test
    public void entriesReportTheirWritersAlignmentAndOrder() {
        StatusBar.addEntry(StatusBarEntry.of("explorer", "created notes.txt"), "explorer",
                StatusBarAlignment.LEFT);
        StatusBar.addEntry(StatusBarEntry.of("Encoding", "UTF-8"), "encoding",
                StatusBarAlignment.RIGHT, 98);
        StatusBar.addEntry(StatusBarEntry.of("Cursor position", "51:39"), "caret",
                StatusBarAlignment.RIGHT, 100);

        assertEquals(1, StatusBar.entries(StatusBarAlignment.LEFT).size());
        List<StatusBarEntryAccessor> right = StatusBar.entries(StatusBarAlignment.RIGHT);
        assertEquals("51:39", right.get(0).entry().text());
        assertEquals("the lower priority follows it", "UTF-8", right.get(1).entry().text());

        assertTrue("text() composes every entry, whatever end it sits at",
                StatusBar.text().contains("created notes.txt") && StatusBar.text().contains("51:39"));
    }

    /** An entry says what it is as well as what it shows — the split a hide menu needs. */
    @Test
    public void anEntryCarriesWhatItIsAsWellAsWhatItShows() {
        StatusBarEntry entry = new StatusBarEntry("Cursor position", "51:39", null, "editor.gotoLine",
                StatusBarEntry.Kind.STANDARD);

        assertEquals("Cursor position", entry.name());
        assertEquals("with no tooltip, hovering says what it is", "Cursor position", entry.hoverText());
        assertEquals("Line and column", entry.withTooltip("Line and column").hoverText());
        assertEquals("editor.gotoLine", entry.command());
    }

    // ── Notifications ───────────────────────────────────────────────────────────────────────────

    /**
     * <b>Severity survives, which a String could not carry.</b>
     *
     * <p>"saved" and "save failed" arrived identically through {@code onStatus} and were rendered
     * identically. A consumer cannot colour, hold or filter what it cannot distinguish.</p>
     */
    @Test
    public void severityReachesTheListener() {
        List<Notification> seen = new ArrayList<>();
        Notifications.onDidChange.connect(event -> {
            if (event.kind() == NotificationEvent.Kind.ADDED) seen.add(event.notification());
        });

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

    /**
     * <b>The history is bounded, and an eviction is announced.</b>
     *
     * <p>The bound is what stops a convenience becoming a log. The <em>announcement</em> is what stops the
     * panel showing what the model has thrown away: with only "something arrived" and "everything went" to
     * emit, {@code NotificationsView} could only ever append, so past the limit its column outgrew the
     * history without bound and nothing could have told it. @see NotificationEvent</p>
     */
    @Test
    public void theHistoryIsBoundedAndSaysWhatItDropped() {
        List<Notification> evicted = new ArrayList<>();
        Notifications.onDidChange.connect(event -> {
            if (event.kind() == NotificationEvent.Kind.REMOVED) evicted.add(event.notification());
        });

        for (int i = 0; i < Notifications.HISTORY_LIMIT + 5; i++) Notifications.info("message " + i);

        List<Notification> history = Notifications.history();
        assertEquals("unbounded at " + history.size(), Notifications.HISTORY_LIMIT, history.size());
        assertEquals("and it keeps the NEWEST", "message " + (Notifications.HISTORY_LIMIT + 4),
                history.get(history.size() - 1).getMessage());

        assertEquals("one eviction per overflow", 5, evicted.size());
        assertEquals("the OLDEST went first", "message 0", evicted.get(0).getMessage());
        assertEquals("a view can only splice what it is handed",
                Notifications.HISTORY_LIMIT, Notifications.size());
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
        assertEquals(2, Notifications.size());

        Notifications.markAllRead();
        assertEquals("the badge is gone", 0, Notifications.unread());
        assertEquals("but the messages are not", 2, Notifications.size());

        Notifications.clear();
        assertTrue(Notifications.isEmpty());
    }

    /**
     * A cleared history is its own kind of change, because nothing arrived to carry it.
     *
     * <p>And it is one event rather than a hundred removals: a view can rebuild an empty column once
     * instead of splicing every card out of it individually.</p>
     */
    @Test
    public void clearingIsItsOwnKindOfChange() {
        List<NotificationEvent.Kind> seen = new ArrayList<>();
        Notifications.onDidChange.connect(event -> seen.add(event.kind()));

        Notifications.info("something");
        assertEquals(List.of(NotificationEvent.Kind.ADDED), seen);

        Notifications.clear();
        assertEquals("one event, not one per entry",
                List.of(NotificationEvent.Kind.ADDED, NotificationEvent.Kind.CLEARED), seen);

        Notifications.clear();
        assertEquals("clearing an empty history says nothing", 2, seen.size());
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
     * hundred-deep history. It counts on the entry instead, and arrives as {@code CHANGED} so a view
     * updates the card it is already showing rather than adding another.</p>
     *
     * <p>A repeat also leaves the unread count alone: the same message twice is not new information, and a
     * bell that ticks up while nothing new has been said is worse than one that stays put.</p>
     */
    @Test
    public void anImmediateRepeatCollapses() {
        List<NotificationEvent> changes = new ArrayList<>();
        Notifications.onDidChange.connect(event -> {
            if (event.kind() == NotificationEvent.Kind.CHANGED) changes.add(event);
        });

        Notifications.error("Save failed");
        Notifications.error("Save failed");
        Notifications.error("Save failed");

        assertEquals("three arrivals became one entry", 1, Notifications.size());
        assertEquals(3, Notifications.history().get(0).getRepeats());
        assertEquals("each repeat announced once", 2, changes.size());
        assertSame("and it names the entry already on screen",
                Notifications.history().get(0), changes.get(0).notification());
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

        assertEquals("the older one was folded into the newer", 3, Notifications.size());
        for (Notification each : Notifications.history()) {
            assertEquals("nothing should have collapsed", 1, each.getRepeats());
        }
    }

    // ── Groups and handles ──────────────────────────────────────────────────────────────────────

    /**
     * <b>A group's display type is what {@code groupId} was always for.</b>
     *
     * <p>The field was carried for a long time and read by exactly one thing — {@code saysTheSameAs} — so
     * it scoped deduplication and nothing else. IntelliJ hangs the whole balloon-versus-log decision off
     * it, overridable by the user, which is how a chatty producer stops owning the screen without its
     * author having to agree.</p>
     */
    @Test
    public void aGroupDecidesHowLoudlyItSpeaks() {
        assertEquals("an unregistered group must still be heard",
                NotificationDisplay.BALLOON, NotificationGroups.displayOf("never.registered"));

        NotificationGroups.register("compiler", "Shader compilation", NotificationDisplay.LOG_ONLY);
        assertEquals(NotificationDisplay.LOG_ONLY, NotificationGroups.displayOf("compiler"));

        NotificationGroups.setDisplay("compiler", NotificationDisplay.BALLOON);
        assertEquals("the user outranks the declaration",
                NotificationDisplay.BALLOON, NotificationGroups.displayOf("compiler"));
        assertTrue(NotificationGroups.isOverridden("compiler"));

        NotificationGroups.setDisplay("compiler", null);
        assertEquals("and clearing the override restores the declared default",
                NotificationDisplay.LOG_ONLY, NotificationGroups.displayOf("compiler"));
    }

    /**
     * <b>{@code NONE} is the one value that discards.</b>
     *
     * <p>Not shown, not logged, not counted — which is why it is never a default and only ever something a
     * user chooses when a producer is wrong rather than merely noisy.</p>
     */
    @Test
    public void aSilencedGroupIsNotEvenHeld() {
        NotificationGroups.register("chatty", "Chatty", NotificationDisplay.NONE);

        Notifications.show(Notification.info("tick").inGroup("chatty"));
        Notifications.info("something else");

        assertEquals(1, Notifications.size());
        assertEquals("something else", Notifications.history().get(0).getMessage());
        assertEquals("a silenced message must not ring the bell", 1, Notifications.unread());
    }

    /**
     * <b>A producer can take back what it said.</b>
     *
     * <p>Announcing was one-way, so a message stood until it aged out whether or not it was still true.
     * The alternative producers reach for is a second notification saying the first no longer applies,
     * which is how a history fills with corrections.</p>
     */
    @Test
    public void aHandleCanWithdrawAndReviseWhatItSaid() {
        NotificationHandle handle = Notifications.show(Notification.error("Disconnected"));
        assertNotNull(handle);
        assertTrue(handle.isOpen());

        List<NotificationEvent.Kind> kinds = new ArrayList<>();
        Notifications.onDidChange.connect(event -> kinds.add(event.kind()));
        int[] closed = { 0 };
        handle.onDidClose.connect(() -> closed[0]++);

        handle.updateMessage("Reconnecting");
        assertEquals("a revision is not a new event", List.of(NotificationEvent.Kind.CHANGED), kinds);
        assertEquals("Reconnecting", Notifications.history().get(0).getMessage());
        assertEquals("and it does not re-ring the bell", 1, Notifications.unread());

        handle.close();
        assertFalse(handle.isOpen());
        assertTrue(Notifications.isEmpty());
        assertEquals("a withdrawal reaches views as the kind they already handle",
                List.of(NotificationEvent.Kind.CHANGED, NotificationEvent.Kind.REMOVED), kinds);
        assertEquals(1, closed[0]);

        handle.close();
        assertEquals("closing twice says nothing", 1, closed[0]);
    }

    /** Ageing out closes the handle too — otherwise isOpen() would answer for an entry nobody holds. */
    @Test
    public void ageingOutClosesTheHandle() {
        NotificationHandle first = Notifications.show(Notification.info("message 0"));
        int[] closed = { 0 };
        first.onDidClose.connect(() -> closed[0]++);

        for (int i = 1; i <= Notifications.HISTORY_LIMIT; i++) Notifications.info("message " + i);

        assertFalse("it left the history without saying so", first.isOpen());
        assertEquals(1, closed[0]);
    }

    /**
     * <b>"Don't show again" silences a KIND of message, not an instance of it.</b>
     *
     * <p>An instance is gone the moment it fades, so a flag on it would suppress nothing. The id is also
     * deliberately not the group: silencing "this particular warning" and silencing "everything the
     * compiler says" are different requests, and a user offered only the second will take it and lose the
     * first.</p>
     */
    @Test
    public void silencingAKindOfMessageOutlivesTheMessage() {
        Notifications.show(Notification.warning("Preview unavailable").withNeverShowAgain("preview.off"));
        assertEquals(1, Notifications.size());

        Notifications.suppress("preview.off");
        Notifications.show(Notification.warning("Preview unavailable").withNeverShowAgain("preview.off"));
        Notifications.show(Notification.warning("Preview unavailable for another node")
                .withNeverShowAgain("preview.off"));
        assertEquals("a silenced kind got through", 1, Notifications.size());

        Notifications.info("something unrelated");
        assertEquals("it silenced more than it was asked to", 2, Notifications.size());

        assertEquals(List.of("preview.off"), Notifications.suppressed());

        // CLEARING THE LIST IS NOT UN-SILENCING. "Clear all" empties a list; it is not a request to start
        // being interrupted again by everything the user has already dismissed for good.
        Notifications.clear();
        Notifications.show(Notification.warning("Preview unavailable").withNeverShowAgain("preview.off"));
        assertTrue("clearing the history un-silenced it", Notifications.isEmpty());

        Notifications.unsuppress("preview.off");
        Notifications.show(Notification.warning("Preview unavailable").withNeverShowAgain("preview.off"));
        assertEquals(1, Notifications.size());
    }

    /** Secondary actions travel separately, so a card knows which verb to lead with. */
    @Test
    public void secondaryActionsAreKeptApartFromPrimaryOnes() {
        Notification notification = Notification.error("Save failed")
                .withAction("Retry", () -> { })
                .withSecondaryAction("Open the log", () -> { });

        assertEquals(1, notification.actions().size());
        assertEquals("Retry", notification.actions().get(0).label());
        assertEquals(1, notification.secondaryActions().size());
        assertEquals("Open the log", notification.secondaryActions().get(0).label());
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
        assertFalse("and a different group is too — what a group scopes today",
                base.saysTheSameAs(Notification.error("Save failed").withDetail("notes.txt")
                        .inGroup("compiler")));
        assertFalse(base.saysTheSameAs(null));
        assertNotNull(Notification.info("x").toString());
    }
}
