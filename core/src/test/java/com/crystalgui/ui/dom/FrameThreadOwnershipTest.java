package com.crystalgui.ui.dom;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.crystalgui.core.async.UiThread;
import com.crystalgui.testsupport.UiDocumentTestBase;
import org.junit.Test;

/**
 * <b>The frame thread owns the tree, and the engine now says so.</b> {@code plan/engine-rewrite.md} M0,
 * engine audit §9.
 *
 * <p>{@code UiThread} was a marker: it recorded which thread ran frames and <em>asserted nothing</em>,
 * so the rule lived only in a javadoc. It has already been paid for once -- a script thread emitted a
 * signal, a listener called {@code setEnabled}, that reached {@code invalidateStyleMatch()}, and the
 * cascade dirty-match {@code HashSet} was mutated while the frame thread was copying it. The result was
 * {@code ArrayIndexOutOfBoundsException: Index 358 out of bounds for length 358} thrown out of
 * {@code HashMap.keysToArray} inside {@code advanceFrame}, with nothing about the Run panel anywhere in
 * the stack. A trace at the WRITE names the culprit; a crash a frame later names the victim.</p>
 *
 * <h3>Ownership is per-TREE</h3>
 *
 * <p>Which is what makes the check usable rather than merely correct. A tree nothing has painted has no
 * owner, so everything a headless test, a dedicated server or a background load builds is free; a tree
 * that <em>is</em> being painted refuses everyone but its painter. A process-wide owner would refuse any
 * thread that was not the most recent painter anywhere -- which fails a test suite wholesale, since
 * JUnit runs timed methods on their own threads.</p>
 */
public class FrameThreadOwnershipTest extends UiDocumentTestBase {

    /** Builds a document and paints it once, so this thread owns its tree. */
    private UIDocument paintedWindow(UIElement root) {
        document.append(root);
        frame();
        return document;
    }

    @Test
    public void mutatingAPaintedTreeOffItsFrameThreadIsRefused() throws Exception {
        UIElement root = new UIElement();
        paintedWindow(root);

        final Throwable[] raised = new Throwable[1];
        Thread other = new Thread(() -> {
            try {
                root.append(new UIElement());
            } catch (Throwable t) {
                raised[0] = t;
            }
        }, "not-the-frame-thread");
        other.start();
        other.join();

        if (raised[0] == null) {
            fail("an off-thread mutation of a painted tree must throw WHERE IT HAPPENS -- the tree has "
                    + "no safe concurrent reader, so it corrupts rather than fails, and the crash "
                    + "otherwise surfaces a frame later with nothing about the culprit in the trace");
        }
        assertTrue(raised[0] instanceof IllegalStateException);
        assertTrue("the message must name the operation, was: " + raised[0].getMessage(),
                // The new tree names the mutation "inserting <tag>"; the old one said "Adding a child".
                // What the assertion is for is unchanged -- the refusal has to say WHAT was being done,
                // or the trace names the victim rather than the culprit.
                raised[0].getMessage().contains("inserting"));
    }

    @Test
    public void removingOffTheFrameThreadIsRefusedToo() throws Exception {
        UIElement root = new UIElement();
        UIElement child = new UIElement();
        root.append(child);
        paintedWindow(root);

        final Throwable[] raised = new Throwable[1];
        Thread other = new Thread(() -> {
            try {
                root.remove(child);
            } catch (Throwable t) {
                raised[0] = t;
            }
        }, "not-the-frame-thread");
        other.start();
        other.join();

        assertTrue("removal is the other mutation primitive and needs the same guard",
                raised[0] instanceof IllegalStateException);
    }

    /**
     * The counter-assertion, and it is what keeps the guard usable: an unpainted tree has no owner.
     * A guard written as "refuse any thread but the last painter anywhere" passes the two tests above
     * and fails the entire headless suite, a dedicated server, and every background build.
     */
    @Test
    public void anUnpaintedTreeHasNoOwnerAndRefusesNobody() throws Exception {
        UIElement root = new UIElement();

        final Throwable[] raised = new Throwable[1];
        Thread other = new Thread(() -> {
            try {
                root.append(new UIElement());
            } catch (Throwable t) {
                raised[0] = t;
            }
        }, "some-other-thread");
        other.start();
        other.join();

        if (raised[0] != null) {
            fail("a tree nobody is painting must be freely buildable from any thread, but: " + raised[0]);
        }
    }

    @Test
    public void theOwningThreadItselfIsNeverRefused() {
        UIElement root = new UIElement();
        paintedWindow(root);
        root.append(new UIElement());
        root.remove(root.children().get(0));
    }

    @Test
    public void aHostMayTurnTheGuardOff() throws Exception {
        // It exists because refusing to be turned off is how a safety check gets deleted.
        UIElement root = new UIElement();
        paintedWindow(root);
        UiThread.setEnforcing(false);
        try {
            final Throwable[] raised = new Throwable[1];
            Thread other = new Thread(() -> {
                try {
                    root.append(new UIElement());
                } catch (Throwable t) {
                    raised[0] = t;
                }
            }, "not-the-frame-thread");
            other.start();
            other.join();
            if (raised[0] != null) fail("disarmed, and still threw: " + raised[0]);
        } finally {
            UiThread.setEnforcing(true);
        }
    }
}
