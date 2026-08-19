package com.crystalgui.language.run;

import com.crystalgui.language.run.exec.Safepoints;

import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Method;

import static org.junit.Assert.assertTrue;

/**
 * <b>§23 row 14 — what a safepoint costs on a hot loop.</b> Measured, because the row says so.
 *
 * <h3>Why the row could not be closed by reasoning</h3>
 *
 * <p>It reads <em>"measure; one volatile read per backward branch should vanish in JIT — verify, don't
 * assume"</em>, and it is worth noting the row's own premise is stale: the injection is <b>not</b> a
 * volatile read. It is a single {@code invokestatic} of a void no-arg method, because a read-and-branch
 * needs a new branch target, which needs a new {@code StackMapTable} entry, which needs
 * {@code COMPUTE_FRAMES}, which makes ASM call {@code getCommonSuperClass} and <b>load classes at
 * instrumentation time</b> — fatal on a Minecraft host. So the question the row asks is really whether
 * HotSpot inlines the callee back to the volatile read the obvious version would have emitted.</p>
 *
 * <h3>Opt-in, and that is deliberate</h3>
 *
 * <p>A timing assertion inside {@code check} is a flaky test waiting to happen, and the thing worth
 * protecting against regression is not a number — it is the <em>structural</em> property that makes the
 * number small, which {@code SafepointsTest} asserts deterministically (one instruction, no branch, and
 * neither {@code maxStack} nor {@code maxLocals} moving). This exists to <b>answer</b> §23, once, with
 * the answer recorded in the plan. Run it with {@code gradlew :language:test --tests '*SafepointOverheadBenchmark' -Pbench}.</p>
 *
 * <p>The ceiling it asserts when it does run is deliberately loose. A precise bound would be measuring
 * the machine; what this must catch is the <em>shape</em> going wrong — an injection that allocated, or
 * took a lock, or stopped being inlinable, would not be 20% slower, it would be an order of magnitude.</p>
 */
public class SafepointOverheadBenchmark {

    /** Enough iterations that the loop is unambiguously hot, and few enough to stay a second or so. */
    private static final long ITERATIONS = 400_000_000L;

    private static final int ROUNDS = 5;

    @Test
    public void aSafepointOnEveryBackwardBranchIsNotWorthAvoiding() throws Exception {
        Assume.assumeTrue("opt-in: run with -Pbench",
                Boolean.parseBoolean(System.getProperty("cgui.test.bench")));

        byte[] plain = countedLoop("Bench");
        byte[] guarded = Safepoints.inject(countedLoop("BenchGuarded"));

        assertTrue("the fixture has no backward branch, so this measures nothing",
                Safepoints.countInjectionPoints(countedLoop("Bench")) >= 2);

        Method uninstrumented = entryPointOf("Bench", plain);
        Method instrumented = entryPointOf("BenchGuarded", guarded);

        // WARM BOTH BEFORE TIMING EITHER, and warm them the same amount: whichever runs first
        // otherwise pays for the interpreter and the other does not, which is a 50x difference and
        // has nothing to do with the safepoint.
        for (int round = 0; round < ROUNDS; round++) {
            uninstrumented.invoke(null, 10_000_000L);
            instrumented.invoke(null, 10_000_000L);
        }

        long plainNanos = Long.MAX_VALUE;
        long guardedNanos = Long.MAX_VALUE;
        for (int round = 0; round < ROUNDS; round++) {
            // THE BEST OF N, not the mean. A slow round is the machine doing something else; there is
            // no such thing as a spuriously fast one, so the minimum is the least noisy estimator of
            // what the code costs.
            plainNanos = Math.min(plainNanos, timeOf(uninstrumented));
            guardedNanos = Math.min(guardedNanos, timeOf(instrumented));
        }

        double plainPerIteration = plainNanos / (double) ITERATIONS;
        double guardedPerIteration = guardedNanos / (double) ITERATIONS;
        double ratio = guardedNanos / (double) plainNanos;

        System.out.printf("%n  safepoint overhead, %,d iterations, best of %d%n", ITERATIONS, ROUNDS);
        System.out.printf("    uninstrumented  %6.2f ms   %.3f ns/iteration%n",
                plainNanos / 1e6, plainPerIteration);
        System.out.printf("    instrumented    %6.2f ms   %.3f ns/iteration%n",
                guardedNanos / 1e6, guardedPerIteration);
        System.out.printf("    ratio           %6.2fx    (+%.3f ns/iteration)%n%n",
                ratio, guardedPerIteration - plainPerIteration);

        assertTrue("a safepoint on every backward branch costs " + String.format("%.1f", ratio)
                        + "x, which is not a call HotSpot inlined -- check that ScriptControl.checkpoint "
                        + "is still a static void no-arg reading a field and nothing else",
                ratio < 4.0);
    }

    private static long timeOf(Method work) throws Exception {
        long from = System.nanoTime();
        work.invoke(null, ITERATIONS);
        return System.nanoTime() - from;
    }

    /**
     * {@code public static long work(long n) { long s = 0; for (long i = 0; i < n; i++) s += i; return s; }}
     *
     * <p>Hand-built rather than compiled, so this needs no engine band and no ECJ: the question is about
     * one injected instruction in a loop, and a loop is a loop however its bytecode was produced.</p>
     */
    private static byte[] countedLoop(String name) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null,
                "java/lang/Object", null);

        MethodVisitor work = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "work", "(J)J", null, null);
        work.visitCode();
        work.visitInsn(Opcodes.LCONST_0);
        work.visitVarInsn(Opcodes.LSTORE, 2);            // sum
        work.visitInsn(Opcodes.LCONST_0);
        work.visitVarInsn(Opcodes.LSTORE, 4);            // i

        Label top = new Label();
        Label done = new Label();
        work.visitLabel(top);
        work.visitVarInsn(Opcodes.LLOAD, 4);
        work.visitVarInsn(Opcodes.LLOAD, 0);
        work.visitInsn(Opcodes.LCMP);
        work.visitJumpInsn(Opcodes.IFGE, done);
        work.visitVarInsn(Opcodes.LLOAD, 2);
        work.visitVarInsn(Opcodes.LLOAD, 4);
        work.visitInsn(Opcodes.LADD);
        work.visitVarInsn(Opcodes.LSTORE, 2);
        work.visitVarInsn(Opcodes.LLOAD, 4);
        work.visitInsn(Opcodes.LCONST_1);
        work.visitInsn(Opcodes.LADD);
        work.visitVarInsn(Opcodes.LSTORE, 4);
        work.visitJumpInsn(Opcodes.GOTO, top);           // the backward branch being measured
        work.visitLabel(done);
        work.visitVarInsn(Opcodes.LLOAD, 2);
        work.visitInsn(Opcodes.LRETURN);
        work.visitMaxs(0, 0);
        work.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Method entryPointOf(String name, byte[] bytes) throws Exception {
        ClassLoader loader = new ClassLoader(SafepointOverheadBenchmark.class.getClassLoader()) {
            @Override protected Class<?> findClass(String wanted) throws ClassNotFoundException {
                if (!name.equals(wanted)) throw new ClassNotFoundException(wanted);
                return defineClass(wanted, bytes, 0, bytes.length);
            }
        };
        return loader.loadClass(name).getMethod("work", long.class);
    }
}
