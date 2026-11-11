package com.crystalgui.core.dispose;

import com.crystalgraphics.gpu.CgDeferral;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.nulldrv.CgNullRHI;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The ownership tree's contract.
 *
 * <p>In {@code headlessTest} deliberately: with no device installed a GPU disposal runs at once, so the tree works
 * where there is no context.</p>
 */
public class DisposerTest {

    /** Records the order things were released in, which is most of what there is to assert. */
    private final List<String> released = new ArrayList<>();

    private Disposable named(String name) {
        return new Disposable() {
            @Override
            public void dispose() {
                released.add(name);
            }

            @Override
            public String toString() {
                return name;
            }
        };
    }

    private Disposable glNamed(String name) {
        return new Disposable.Gl() {
            @Override
            public void dispose() {
                released.add(name);
            }

            @Override
            public String toString() {
                return name;
            }
        };
    }

    @Before
    @After
    public void reset() {
        Disposer.resetForTesting();
        released.clear();
    }

    /**
     * <b>Children before the parent, and later siblings before earlier ones.</b>
     *
     * <p>The reverse order is the load-bearing half: a child registered later may have been built from
     * an earlier one, so releasing forwards frees a dependency out from under its dependent. Asserting
     * the exact sequence rather than mere membership is the only way to pin it.</p>
     */
    @Test
    public void childrenAreReleasedBeforeTheParentAndInReverseOrder() {
        Disposable parent = named("parent");
        Disposable first = named("first");
        Disposable second = named("second");
        Disposer.register(parent, first);
        Disposer.register(parent, second);

        Disposer.dispose(parent);
        assertEquals(List.of("second", "first", "parent"), released);
    }

    /** Depth as well as order: a grandchild goes before its parent, which goes before the root. */
    @Test
    public void aWholeSubtreeIsReleasedDeepestFirst() {
        Disposable root = named("root");
        Disposable mid = named("mid");
        Disposable leaf = named("leaf");
        Disposer.register(root, mid);
        Disposer.register(mid, leaf);

        Disposer.dispose(root);
        assertEquals(List.of("leaf", "mid", "root"), released);
    }

    /** A second dispose must free nothing again — the difference between idempotent and a double free. */
    @Test
    public void disposingTwiceIsANoOp() {
        Disposable target = named("target");
        Disposer.dispose(target);
        Disposer.dispose(target);
        assertEquals(List.of("target"), released);
        assertTrue(Disposer.isDisposed(target));
    }

    /**
     * <b>A throwing child must not take its siblings or its parent with it.</b>
     *
     * <p>Teardown is exactly when stopping halfway is worst: the caller is usually a close handler with
     * nowhere to put an exception, and what is left behind is a half-freed graph.</p>
     */
    @Test
    public void aThrowingChildDoesNotStopTheRest() {
        Disposable parent = named("parent");
        Disposable good = named("good");
        Disposable bad = new Disposable() {
            @Override
            public void dispose() {
                released.add("bad");
                throw new IllegalStateException("deliberate");
            }
        };
        Disposer.register(parent, good);
        Disposer.register(parent, bad);

        Disposer.dispose(parent);
        assertEquals(List.of("bad", "good", "parent"), released);
    }

    /** Registering against something already gone releases the child rather than retaining it. */
    @Test
    public void registeringAgainstADisposedParentDisposesTheChild() {
        Disposable parent = named("parent");
        Disposer.dispose(parent);

        Disposable child = named("child");
        assertFalse("a disposed parent still accepted a child", Disposer.tryRegister(parent, child));
        assertFalse("tryRegister must not release it either", released.contains("child"));

        Disposer.register(parent, child);
        assertTrue("register against a dead parent must not retain the child",
                released.contains("child"));
    }

    /** Disposing a child unlinks it, so the parent's later disposal does not reach it twice. */
    @Test
    public void disposingAChildUnlinksItFromItsParent() {
        Disposable parent = named("parent");
        Disposable child = named("child");
        Disposer.register(parent, child);

        Disposer.dispose(child);
        Disposer.dispose(parent);
        assertEquals(List.of("child", "parent"), released);
    }

    /** Re-parenting moves the child rather than leaving it owned twice. */
    @Test
    public void registeringUnderASecondParentMovesTheChild() {
        Disposable first = named("first");
        Disposable second = named("second");
        Disposable child = named("child");
        Disposer.register(first, child);
        Disposer.register(second, child);

        Disposer.dispose(first);
        assertEquals("the child went with its old parent", List.of("first"), released);

        Disposer.dispose(second);
        assertEquals(List.of("first", "child", "second"), released);
    }

    /** A GPU disposal off the device's thread waits for the executor's landing, before the next frame. */
    @Test
    public void aGlDisposableIsDeferredWhenOffThread() {
        withDevice(() -> {
            Thread off = new Thread(() -> Disposer.dispose(glNamed("gl")));
            off.start();
            try {
                off.join();
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            assertTrue("a GL disposable was freed off the device's thread", released.isEmpty());
            CgDeferral.applyAll();
            assertEquals(List.of("gl"), released);
        });
    }

    /** On the device's thread there is nothing to defer. */
    @Test
    public void aGlDisposableRunsImmediatelyOnTheDeviceThread() {
        withDevice(() -> {
            Disposer.dispose(glNamed("gl"));
            assertEquals(List.of("gl"), released);
        });
    }

    /** A device owned by this thread for {@code body}, then whatever was installed before. */
    private static void withDevice(Runnable body) {
        CgRHI before = CgRHI.installed() ? CgRHI.get() : null;
        CgRHI.install(new CgNullRHI(1, 1).withoutLog());
        try {
            body.run();
        } finally {
            CgRHI.install(before);
        }
    }

    /** With no device there is no thread to be off, which lets every headless test use this. */
    @Test
    public void withNoDeviceAGlDisposableRunsImmediately() {
        Disposer.dispose(glNamed("gl"));
        assertEquals(List.of("gl"), released);
    }

    /**
     * <b>The leak assertion.</b> A tree that has been disposed leaves nothing behind, which is what
     * makes {@code liveCount()} usable as a regression net elsewhere.
     */
    @Test
    public void aDisposedTreeLeavesNothingRegistered() {
        int before = Disposer.liveCount();
        Disposable root = named("root");
        for (int i = 0; i < 20; i++) Disposer.register(root, named("child" + i));
        assertTrue("registration did not grow the tree", Disposer.liveCount() > before);

        Disposer.dispose(root);
        assertEquals("a disposed tree still holds nodes", before, Disposer.liveCount());
    }

    /** Repeated open/close of an owned subtree must not accumulate — the session-growth assertion. */
    @Test
    public void repeatedRegisterAndDisposeDoesNotAccumulate() {
        int before = Disposer.liveCount();
        for (int cycle = 0; cycle < 50; cycle++) {
            Disposable owner = named("owner" + cycle);
            Disposer.register(owner, named("a" + cycle));
            Disposer.register(owner, named("b" + cycle));
            Disposer.dispose(owner);
        }
        assertEquals("fifty open/close cycles grew the tree", before, Disposer.liveCount());
        assertEquals(150, released.size());
    }

    /**
     * Deep trees must not overflow the stack. Teardown is unrecoverable if it does — half the graph is
     * freed and the rest is unreachable.
     */
    @Test
    public void aDeepTreeDisposesWithoutRecursion() {
        Disposable root = named("root");
        Disposable current = root;
        for (int i = 0; i < 5000; i++) {
            Disposable next = named("n" + i);
            Disposer.register(current, next);
            current = next;
        }
        Disposer.dispose(root);
        assertEquals(5001, released.size());
        assertEquals("the deepest node must go first", "n4999", released.get(0));
        assertEquals("root", released.get(released.size() - 1));
    }

    /**
     * <b>Releasing something must not be the reason it is kept.</b>
     *
     * <p>The record of what has been disposed used to be an {@code IdentityHashMap} with strong keys
     * that nothing but {@code resetForTesting()} removed from — so every object ever disposed was
     * retained for the life of the process, with everything it referenced behind it. Four disposed
     * editors kept four whole editor trees, which came out as an {@code OutOfMemoryError} in a test
     * worker and read as the editors leaking rather than as the disposer holding them.</p>
     *
     * <p>Weak is exactly right rather than a compromise: a question about an object nobody holds cannot
     * be asked, so the record only has to outlive the object's last holder — which is what
     * {@link #aDisposedObjectIsStillReportedDisposedWhileAnybodyHoldsIt} beside this asserts.</p>
     */
    @Test
    public void disposingSomethingDoesNotRetainIt() {
        Disposable one = named("one");
        WeakReference<Disposable> watched = new WeakReference<>(one);
        Disposer.dispose(one);

        one = null;
        for (int attempt = 0; attempt < 40 && watched.get() != null; attempt++) {
            System.gc();
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        assertNull("the disposer kept a strong reference to something it had already released",
                watched.get());
    }

    /** ...and the record still answers for as long as anybody can ask about it. */
    @Test
    public void aDisposedObjectIsStillReportedDisposedWhileAnybodyHoldsIt() {
        Disposable one = named("one");
        Disposer.dispose(one);
        for (int attempt = 0; attempt < 5; attempt++) System.gc();
        assertTrue("a held object's disposal must not be forgotten by a collection",
                Disposer.isDisposed(one));
    }

    /** A dispose() that re-enters must find the work already claimed rather than doing it twice. */
    @Test
    public void reentrantDisposeDoesNotDoubleRelease() {
        Disposable parent = named("parent");
        Disposable child = new Disposable() {
            @Override
            public void dispose() {
                released.add("child");
                Disposer.dispose(this);      // the re-entry
            }
        };
        Disposer.register(parent, child);

        Disposer.dispose(parent);
        assertEquals(List.of("child", "parent"), released);
    }
}
