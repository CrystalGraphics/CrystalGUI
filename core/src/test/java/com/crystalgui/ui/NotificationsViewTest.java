package com.crystalgui.ui;

import com.crystalgui.core.notify.Notification;
import com.crystalgui.core.notify.Notifications;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.chrome.NotificationBalloons;
import com.crystalgui.ui.elements.chrome.NotificationsView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** The notification history's view half — IntelliJ's Notifications tool window. */
public class NotificationsViewTest extends UiTestBase {

    private UIWindow window;
    private NotificationsView view;

    @Before
    public void setUp() {
        Notifications.resetForTesting();
        view = new NotificationsView();
        UIElement root = new UIElement().layout(l -> l.width(300).height(400));
        root.addChild(view);
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(300, 400);
        // uiScale 1, so the root's 300x400 IS the window's logical area. At the default scale of 2 the
        // logical area is 150x200 and most of this panel — including every card — lays out off-screen,
        // where nothing is hit-testable and a click test fails for a reason that has nothing to do with
        // the widget.
        window.setUiScale(1f);
        settle();
    }

    @After
    public void tearDown() {
        Notifications.resetForTesting();
    }

    private void settle() {
        for (int i = 0; i < 4; i++) window.updateWithoutPainting();
    }

    private List<UIElement> entries() {
        return view.getElementsByClassName(NotificationsView.ENTRY_CLASS);
    }

    private static String textOf(UIElement entry, String cls) {
        UIElement found = entry.querySelector("." + cls);
        assertNotNull("no ." + cls + " in the entry", found);
        return ((UIText) found).getText();
    }

    /** Newest first: what you want on opening the panel is what just happened. */
    @Test
    public void theNewestNotificationIsAtTheTop() {
        Notifications.info("first");
        Notifications.warning("second");
        settle();

        assertEquals(2, entries().size());
        assertEquals("second", textOf(entries().get(0), NotificationsView.MESSAGE_CLASS));
        assertEquals("first", textOf(entries().get(1), NotificationsView.MESSAGE_CLASS));
    }

    /**
     * <b>An arrival appends; it does not rebuild.</b>
     *
     * <p>Entries carry action links, so this list is something the user clicks on — and a notification can
     * arrive from a handler running inside one of those clicks. Rebuilding would detach the element being
     * clicked, which this engine has paid for three times (the table header, the palette's key chips, the
     * editor's gutter arrows) and which no screenshot shows.</p>
     */
    @Test
    public void anArrivalLeavesTheExistingCardsAlone() {
        Notifications.info("first");
        settle();
        UIElement firstCard = entries().get(0);

        Notifications.info("second");
        settle();

        List<UIElement> now = entries();
        assertEquals(2, now.size());
        assertSame("the existing card was rebuilt", firstCard, now.get(1));
    }

    /** Clear all empties the column, and the empty state takes over. */
    @Test
    public void clearAllEmptiesTheColumn() {
        Notifications.info("something");
        settle();
        assertEquals(1, entries().size());

        Notifications.clear();
        settle();
        assertTrue("the cards outlived the history", entries().isEmpty());
        assertNotNull("the empty state left the tree",
                view.querySelector("." + NotificationsView.EMPTY_CLASS));
    }

    /** Severity reaches the sheet as a class — never as a colour written from Java. */
    @Test
    public void severityIsCarriedAsAClass() {
        Notifications.error("broke");
        settle();
        UIElement icon = entries().get(0).querySelector("." + NotificationsView.ICON_CLASS);
        assertNotNull(icon);
        assertTrue("no severity class on the glyph: " + icon.getClasses(),
                icon.hasClass(NotificationsView.SEVERITY_PREFIX + "error"));
    }

    /**
     * An action arrives as a link, labelled and wired, beside its detail.
     *
     * <p>The <b>press</b> is deliberately not simulated. Doing so needs a screen point, and this widget's
     * cards live inside a {@code ScrollerView} whose contents did not answer {@code containsScreenPoint}
     * for any point in the window under test — a question about the fixture's coordinates rather than
     * anything this class decides. Asserting a click the harness cannot actually deliver would be a test
     * that passes for the wrong reason; what is pinned is that the link exists, says what the producer
     * named it, and carries the producer's own {@code Runnable}.</p>
     */
    @Test
    public void anActionArrivesAsALabelledLink() {
        boolean[] ran = {false};
        Notification notification = Notification.warning("copy failed")
                .withDetail("notes.txt")
                .withAction("Retry", () -> ran[0] = true);
        Notifications.show(notification);
        settle();

        UIElement entry = entries().get(0);
        assertEquals("notes.txt", textOf(entry, NotificationsView.DETAIL_CLASS));
        UIElement link = entry.querySelector("." + NotificationsView.ACTION_CLASS);
        assertNotNull("no action link", link);
        assertEquals("Retry", ((UIText) link).getText());

        notification.actions().get(0).run().run();
        assertTrue(ran[0]);
    }

    @Test
    public void openingThePanelMarksEverythingRead() {
        Notifications.info("arrived while you were elsewhere");
        assertEquals(1, Notifications.unread());

        settle();
        assertEquals("attaching the view is what counts as looking", 0, Notifications.unread());
        assertEquals("and it kept the message", 1, entries().size());
    }

    /**
     * <b>A fifth balloon must not hang the window.</b>
     *
     * <p>The cap was {@code while (live.size() > MAX_VISIBLE) beginLeaving(live.get(0))}, which never
     * terminates: marking an entry as leaving does not remove it — the element stays mounted for the
     * length of its fade — so the list never shrinks, the same already-leaving entry is found again, and
     * the loop spins. It took the harness out entirely on the fifth file opened in a row, and any test
     * building a workbench with it.</p>
     *
     * <p>Asserted through the layer rather than the view because it is the balloon layer's rule; the two
     * share nothing but the model.</p>
     */
    @Test(timeout = 5000)
    public void aFifthBalloonDoesNotSpin() {
        NotificationBalloons balloons = new NotificationBalloons();
        UIElement root = new UIElement().layout(l -> l.width(300).height(400));
        root.addChild(balloons);
        UIWindow host = new UIWindow(Ui.of(root));
        host.init(300, 400);
        for (int i = 0; i < 4; i++) host.updateWithoutPainting();

        for (int i = 0; i < NotificationBalloons.MAX_VISIBLE + 3; i++) {
            Notifications.info("message " + i);
        }
        for (int i = 0; i < 4; i++) host.updateWithoutPainting();

        assertTrue("nothing was put up at all", balloons.liveCount() > 0);
        assertTrue("the cap let " + balloons.liveCount() + " stay",
                balloons.liveCount() <= NotificationBalloons.MAX_VISIBLE + 3);
    }

    /** Builds a balloon layer in its own window, since it is a separate part from the history panel. */
    private NotificationBalloons balloonLayer() {
        NotificationBalloons balloons = new NotificationBalloons();
        UIElement root = new UIElement().layout(l -> l.width(300).height(400));
        root.addChild(balloons);
        UIWindow host = new UIWindow(Ui.of(root));
        host.init(300, 400);
        for (int i = 0; i < 4; i++) host.updateWithoutPainting();
        return balloons;
    }

    /**
     * <b>Every balloon leaves on its own — errors included.</b>
     *
     * <p>They used to be sticky, on the grounds that a failure removing itself unseen was never really
     * reported. It is: the history keeps it and the bell carries an unread dot, so the balloon is not the
     * record. A failure that demands a click before the screen is usable is its own kind of noise, and
     * IntelliJ fades errors for the same reason.</p>
     *
     * <p>Driven by calling the ticker with a large delta rather than by waiting — the linger is ten seconds
     * and a test must not be.</p>
     */
    @Test
    public void everyBalloonLeavesOnItsOwn() {
        NotificationBalloons balloons = balloonLayer();
        Notifications.info("routine");
        Notifications.warning("odd");
        Notifications.error("broke");

        balloons.tickFrame(0.016f);                       // the reveal frame
        assertEquals("all three went up", 3, balloons.liveCount());

        balloons.tickFrame(NotificationBalloons.LINGER_MS / 1000f + 1f);
        balloons.tickFrame(NotificationBalloons.FADE_MS / 1000f + 0.1f);

        assertEquals("something outstayed its welcome", 0, balloons.liveCount());
    }
}
