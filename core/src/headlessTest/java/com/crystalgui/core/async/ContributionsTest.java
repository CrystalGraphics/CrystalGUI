package com.crystalgui.core.async;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.crystalgui.core.dispose.Disposable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** The generation model every registry now sits on: whole generations, owners, conflicts, and a frame's pin. */
public class ContributionsTest {

    private record Kind(String id, int version) {
    }

    @Test
    public void ownersLeaveInOneGeneration() {
        Contributions<Kind> kinds = Contributions.of("k", Kind::id);
        Owner app = Owner.of("app");
        kinds.add(Owner.PROCESS, new Kind("a", 1));
        kinds.add(app, new Kind("b", 1));
        kinds.add(app, new Kind("c", 1));
        long before = kinds.generation();

        kinds.removeOwner(app);

        assertEquals(List.of(new Kind("a", 1)), kinds.all());
        assertEquals("both of the owner's contributions left in one generation", before + 1, kinds.generation());
    }

    @Test
    public void theLaterOfTwoWithOneIdWins() {
        Contributions<Kind> kinds = Contributions.of("k", Kind::id);
        kinds.add(Owner.PROCESS, new Kind("a", 1));
        kinds.add(Owner.of("mod"), new Kind("a", 2));

        assertEquals(List.of(new Kind("a", 2)), kinds.all());
    }

    @Test
    public void rejectRefusesADifferentOneAndForgivesTheSame() {
        Contributions<Kind> kinds = Contributions.of("k", Kind::id, Contributions.Conflict.REJECT);
        kinds.add(Owner.PROCESS, new Kind("a", 1));
        kinds.add(Owner.PROCESS, new Kind("a", 1));
        assertThrows(IllegalArgumentException.class, () -> kinds.add(Owner.PROCESS, new Kind("a", 2)));
        assertEquals(1, kinds.all().size());
    }

    @Test
    public void firstWinsKeepsTheEarlier() {
        Contributions<Kind> kinds = Contributions.of("k", Kind::id, Contributions.Conflict.FIRST_WINS);
        kinds.add(Owner.PROCESS, new Kind("a", 1));
        kinds.add(Owner.PROCESS, new Kind("a", 2));
        assertEquals(List.of(new Kind("a", 1)), kinds.all());
    }

    @Test
    public void sharedKeepsAnIdUntilEveryHolderHasGone() {
        Contributions<Kind> kinds = Contributions.of("k", Kind::id, Contributions.Conflict.SHARED);
        Owner one = Owner.of("one");
        Owner two = Owner.of("two");
        kinds.add(one, new Kind("a", 1));
        kinds.add(two, new Kind("a", 1));
        assertThrows("one owner cannot hold an id twice", IllegalArgumentException.class,
                () -> kinds.add(one, new Kind("a", 1)));

        kinds.removeOwner(one);
        assertEquals("still held by the other", 1, kinds.entries().size());
        kinds.removeOwner(two);
        assertTrue(kinds.entries().isEmpty());
    }

    @Test
    public void disposingAHandleRemovesOnlyThatContribution() {
        Contributions<Kind> kinds = Contributions.of("k");
        Disposable first = kinds.add(Owner.PROCESS, new Kind("a", 1));
        kinds.add(Owner.PROCESS, new Kind("b", 1));
        first.dispose();
        assertEquals(List.of(new Kind("b", 1)), kinds.all());
    }

    @Test
    public void anIndexIsBuiltOncePerGeneration() {
        Contributions<Kind> kinds = Contributions.of("k", Kind::id);
        int[] builds = {0};
        Contributions.Index<Map<String, Kind>> byId = kinds.index(entries -> {
            builds[0]++;
            Map<String, Kind> map = new java.util.HashMap<>();
            for (Contributions.Entry<Kind> entry : entries) map.put(entry.item().id(), entry.item());
            return map;
        });
        kinds.add(Owner.PROCESS, new Kind("a", 1));

        Map<String, Kind> first = byId.get();
        assertSame(first, byId.get());
        assertEquals(1, builds[0]);

        kinds.add(Owner.PROCESS, new Kind("b", 1));
        assertEquals(2, byId.get().size());
        assertEquals(2, builds[0]);
    }

    /**
     * A registration made from another sequence while a frame runs is not seen by that frame, and is seen whole by the
     * next: the frame reads the generation it pinned, never half of a change.
     */
    @Test
    public void aRegistrationFromAnotherThreadMidFrameIsSeenWholeAtTheNextFrame() throws Exception {
        Contributions<Kind> kinds = Contributions.of("k", Kind::id);
        kinds.add(Owner.PROCESS, new Kind("a", 1));
        Contributions.Index<List<Kind>> index = kinds.index(entries -> kinds.all());

        List<Kind> during;
        try (Published.Pin frame = Published.pin()) {
            List<Kind> atStart = kinds.all();
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> failed = new AtomicReference<>();
            Thread other = new Thread(() -> {
                try {
                    Owner app = Owner.of("other-sequence");
                    kinds.add(app, new Kind("b", 1));
                    kinds.add(app, new Kind("c", 1));
                } catch (Throwable t) {
                    failed.set(t);
                } finally {
                    done.countDown();
                }
            });
            other.start();
            done.await();
            assertEquals(null, failed.get());

            during = kinds.all();
            assertSame("the frame reads the generation it pinned", atStart, during);
            assertEquals(1, during.size());
        }

        assertEquals("the next frame sees both, whole", 3, kinds.all().size());
        assertFalse(index.get().isEmpty());
    }

    @Test
    public void aPinIsPerPublishedAndNests() throws Exception {
        Published<Integer> a = Published.of("a", 1);
        Published<Integer> b = Published.of("b", 10);
        try (Published.Pin outer = Published.pin()) {
            assertEquals(1, (int) a.current());
            Thread other = new Thread(() -> {
                a.update(v -> v + 1);
                b.update(v -> v + 1);
            });
            other.start();
            other.join();
            try (Published.Pin inner = Published.pin()) {
                assertEquals("still the outer frame's generation", 1, (int) a.current());
                assertEquals("b is pinned at its first read, which comes after its update", 11, (int) b.current());
            }
            assertEquals(1, (int) a.current());
        }
        assertEquals(2, (int) a.current());
    }

    @Test
    public void aThreadReadsItsOwnWritesInsideAPin() {
        Published<Integer> a = Published.of("a", 1);
        try (Published.Pin frame = Published.pin()) {
            assertEquals(1, (int) a.current());
            a.update(v -> v + 1);
            assertEquals("a widget registered while the frame builds it is found in that frame", 2, (int) a.current());
        }
    }
}
