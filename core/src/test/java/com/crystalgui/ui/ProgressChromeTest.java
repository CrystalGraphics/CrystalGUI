package com.crystalgui.ui;

import com.crystalgui.core.async.JobKey;
import com.crystalgui.core.async.JobLane;
import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.chrome.ProgressStatusItem;
import com.crystalgui.ui.elements.chrome.StatusBarView;

import org.junit.After;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>The indicator actually appears.</b>
 *
 * <p>Written because it did not. The item registered its per-frame ticker from
 * {@code DOMEvent.ElementAdded}, which fires when an element gains a <em>parent</em> — and a status bar
 * builds its parts in its own constructor, so that moment is long before any window exists.
 * {@code registerTicker} lives on {@link UIWindow}, so the one attempt found none and never retried.</p>
 *
 * <p>Nothing threw and nothing logged. The job ran, the scheduler tracked it, and the chrome stayed
 * blank — which from the outside is indistinguishable from the job never having started. That is what
 * makes it worth a test rather than a look: the failure has no symptom to recognise.</p>
 */
public class ProgressChromeTest extends UiTestBase {

    private final CountDownLatch release = new CountDownLatch(1);

    @After
    public void releaseWorkers() {
        release.countDown();
        JobScheduler.shared().cancelAll(ProgressChromeTest.class);
    }

    /** A job that reports and stays running until the test lets it go. */
    private void startReportingJob(String what) throws InterruptedException {
        CountDownLatch reported = new CountDownLatch(1);
        JobScheduler.shared().job(JobKey.of(ProgressChromeTest.class, what), JobLane.BACKGROUND,
                context -> {
                    context.progress().begin(what, 100);
                    context.progress().advance(50);
                    reported.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return what;
                }).submit();
        JobScheduler.shared().drain();
        assertTrue("the worker never started", reported.await(10, TimeUnit.SECONDS));
    }

    /**
     * <b>A running job reaches the status bar.</b>
     *
     * <p>Asserted on the item's own state rather than on pixels — this is about the widget being fed at
     * all, which is the thing that broke. Where the bar sits and how wide it is are not this test's
     * business and never should be.</p>
     */
    @Test
    public void aRunningJobShowsInTheStatusBar() throws Exception {
        StatusBarView bar = new StatusBarView();
        UIWindow window = new UIWindow(Ui.of(bar));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 600);

        ProgressStatusItem item = bar.progress();
        assertNotNull("the status bar has no progress slot", item);
        assertFalse("something was shown before any job ran", item.isShowing());

        startReportingJob("Downloading engine band");

        // Past the delay, then a frame. The frame is what does it: the item reads the scheduler from its
        // own ticker, so a test that never paints proves nothing about whether it is wired.
        Thread.sleep(JobScheduler.DEFAULT_SHOW_AFTER_MILLIS + 50);
        frame(window);

        assertTrue("a running job never reached the status bar", item.isShowing());
        assertEquals("Downloading engine band", item.shownLabel());
    }

    /** And it goes away again, so the bar is not left claiming work that finished. */
    @Test
    public void itLeavesWhenTheWorkEnds() throws Exception {
        StatusBarView bar = new StatusBarView();
        UIWindow window = new UIWindow(Ui.of(bar));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 600);

        startReportingJob("Resolving manifest");
        Thread.sleep(JobScheduler.DEFAULT_SHOW_AFTER_MILLIS + 50);
        frame(window);
        assertTrue(bar.progress().isShowing());

        release.countDown();
        // Let the worker finish, then wait out the minimum-visible time.
        Thread.sleep(JobScheduler.DEFAULT_MINIMUM_VISIBLE_MILLIS + 200);
        for (int i = 0; i < 5; i++) frame(window);

        assertFalse("the bar kept claiming work that had finished", bar.progress().isShowing());
    }

    /** One paint, which is what drives the item's ticker. */
    private void frame(UIWindow window) {
        // advanceFrame() -- which is what runs registered tickers, and therefore what makes the item read
        // the scheduler at all. A test that skipped this would pass against a widget nothing ever feeds.
        window.updateWithoutPainting();
    }
}
