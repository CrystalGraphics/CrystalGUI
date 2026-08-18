package com.crystalgui.language.java;

import com.crystalgui.language.java.classpath.HostClasspath;
import com.crystalgui.language.java.classpath.TypeIndex;

import org.junit.Assume;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * <b>§23 row 7 — what the type index actually costs.</b> Measured, because the row says so and M9
 * shipped without it.
 *
 * <p>The row reads <em>"type-index scale on a real large modpack (count, scan time, table size vs §7.3
 * budget) — measure during M9, not before"</em>. It was not measured, and {@code TypeIndex} carries a
 * comment asserting "fifty thousand entries" that nothing had ever counted.</p>
 *
 * <h3>What a modpack is, and what this can honestly stand in for</h3>
 *
 * <p>There is no modpack in this repository, so this scans the largest real classpath available — the
 * running JVM's, which is the JDK image plus this module's whole dependency set, and is exactly the
 * classpath {@code HostClasspath.detect()} would hand production. <b>That is a stand-in and is labelled
 * one</b>, but it is a useful one: the scan is linear in class-file entries and does no work per
 * <em>jar</em> beyond opening it, so a modpack's few hundred extra jars extrapolate from the per-entry
 * cost this reports rather than needing their own measurement.</p>
 *
 * <p>Opt-in behind {@code -Pbench} for the reason {@code SafepointOverheadBenchmark} gives: a timing
 * assertion in an ordinary build is a flaky test, and this exists to answer §23 once, with the answer
 * recorded in the plan.</p>
 */
public class TypeIndexScaleBenchmark {

    /** §7.3: cold scan "background, seconds, once"; memory "< ~20MB". */
    private static final long COLD_SCAN_CEILING_MS = 10_000;

    private static final long MEMORY_CEILING_BYTES = 20L * 1024 * 1024;

    @Test
    public void aColdScanOfARealClasspathStaysInsideTheBudget() {
        Assume.assumeTrue("opt-in: run with -Pbench",
                Boolean.parseBoolean(System.getProperty("cgui.test.bench")));

        List<String> classpath = HostClasspath.detect();
        assertTrue("no classpath detected, so this measures nothing", classpath.size() > 1);

        long from = System.nanoTime();
        TypeIndex index = new TypeIndex(classpath);
        // THE FIRST QUERY PAYS FOR THE SCAN -- there is no background warm-up, deliberately, and the
        // class javadoc says why. So this is the cold scan, not a query.
        TypeIndex.Match probe = index.matching("Str");
        long scanNanos = System.nanoTime() - from;

        int count = index.size();
        long retained = retainedPerIndex(classpath);

        System.out.printf("%n  type index over %d classpath entries%n", classpath.size());
        System.out.printf("    cold scan       %,d ms%n", scanNanos / 1_000_000);
        System.out.printf("    types indexed   %,d%s%n", count,
                probe.truncated() ? "  (the probe query truncated at MAX_RESULTS, as designed)" : "");
        System.out.printf("    retained heap   ~%,d KB   (mean of %d held indexes)%n",
                retained / 1024, COPIES);
        System.out.printf("    per type        ~%,d bytes,  %.1f us scan%n",
                count == 0 ? 0 : retained / count,
                count == 0 ? 0.0 : scanNanos / 1000.0 / count);
        System.out.printf("    at the %,d cap  ~%,d MB%n", TypeIndex.MAX_TYPES,
                count == 0 ? 0 : retained / count * TypeIndex.MAX_TYPES / (1024 * 1024));
        System.out.println("    NOTE: this is the JVM's own classpath, not a modpack -- see the "
                + "class javadoc for what it does and does not stand in for.\n");

        assertTrue("the cold scan took " + scanNanos / 1_000_000 + "ms, and \u00a77.3 budgets it at "
                        + "\"background, seconds, once\"", scanNanos / 1_000_000 < COLD_SCAN_CEILING_MS);
        assertTrue("the scan found almost nothing, so the numbers above describe an empty index",
                count > 1_000);
        // THE BUDGET IS ABOUT A FULL INDEX, not this classpath's. \u00a77.3 says "< ~20MB", and what has to
        // stay inside it is the index at its own cap -- MAX_TYPES exists precisely so there is a ceiling
        // to hold to. Extrapolated per type, because that is the quantity that does not depend on which
        // machine ran this.
        long atCap = count == 0 ? 0 : retained / count * (long) TypeIndex.MAX_TYPES;
        assertTrue("a full index would retain ~" + atCap / (1024 * 1024) + "MB at the "
                + TypeIndex.MAX_TYPES + "-type cap, and \u00a77.3 budgets it at ~20MB",
                atCap < MEMORY_CEILING_BYTES);
    }

    /** How many indexes are built and held at once, so retention scales and churn does not. */
    private static final int COPIES = 5;

    /**
     * What one index retains — measured by <b>scaling</b>, because both obvious methods lie.
     *
     * <p>A heap delta bracketing the build counts the scan's churn: two dozen archives means
     * {@code ZipFile} structures, inflater buffers and a great many short-lived strings, and whatever
     * the collector has not got to yet lands in the answer. The first version of this read <b>~14MB for
     * 11k types</b> — about 1.3KB per {@code Entry}, five times what a record of three strings can
     * weigh, and it would have put the cap at four times §7.3's budget. That conclusion would have been
     * drawn from garbage.</p>
     *
     * <p>Nulling the reference and re-measuring does not work either, and fails the other way: the
     * caller's own local still holds the index, so the second measurement reads <b>~0KB</b> and the
     * index appears free. Both numbers are wrong and neither looks it.</p>
     *
     * <p>So build several and hold them all. Churn is transient and does not multiply; retention does,
     * exactly. One index is built and dropped first, so the JVM's one-time costs — archive directories,
     * the classes the scan itself loads — are already paid before the baseline is taken.</p>
     */
    private static long retainedPerIndex(List<String> classpath) {
        TypeIndex warm = new TypeIndex(classpath);
        warm.size();
        warm = null;
        long baseline = settledHeap();

        List<TypeIndex> held = new ArrayList<>(COPIES);
        for (int copy = 0; copy < COPIES; copy++) {
            TypeIndex index = new TypeIndex(classpath);
            index.size();
            held.add(index);
        }
        long withAll = settledHeap();
        // Read it after measuring, so nothing can decide the list is dead early.
        assertTrue(held.size() == COPIES);
        return Math.max(0, (withAll - baseline) / COPIES);
    }

    /**
     * Used heap after asking for a collection, twice.
     */
    private static long settledHeap() {
        Runtime runtime = Runtime.getRuntime();
        for (int attempt = 0; attempt < 2; attempt++) {
            System.gc();
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return runtime.totalMemory() - runtime.freeMemory();
    }
}
