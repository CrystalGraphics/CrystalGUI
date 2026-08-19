package com.crystalgui.language.run;

import com.crystalgui.language.run.exec.Safepoints;
import com.crystalgui.language.run.exec.ScriptControl;
import com.crystalgui.language.run.exec.ScriptStoppedException;
import org.junit.Test;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import com.crystalgui.language.engine.ScriptClassLoader;

/**
 * The safepoint pass, on bytecode built by hand so the shapes are exactly the ones under test.
 *
 * <p>Hand-built rather than compiled from source, because the property is about <em>bytecode</em>
 * shapes — a backward jump, an abstract method, an empty method — and a compiler is free to emit any
 * of them differently. Here the input is unambiguous.</p>
 */
public class SafepointsTest {

    /** A class with one method: an entry, a loop, or neither, as asked. */
    /**
     * <b>Injection moves neither {@code maxStack} nor {@code maxLocals}</b> — the property the whole
     * design rests on, and the one worth a deterministic test.
     *
     * <p>{@code SafepointOverheadBenchmark} measures what a safepoint costs (§23 row 14: 1.06x on a
     * 400M-iteration loop, +0.014 ns per iteration — it vanishes). A number is not something to assert
     * in an ordinary build, and it is not what a regression would break first. <b>This is.</b> An
     * {@code invokestatic} of a void no-arg method pushes nothing and declares nothing, so every frame
     * and every max already in the class file stays valid — which is why {@code inject} can run with
     * neither {@code COMPUTE_FRAMES} nor {@code COMPUTE_MAXS}, and therefore without ASM calling
     * {@code getCommonSuperClass} and loading classes at instrumentation time.</p>
     *
     * <p>Change the injected instruction to anything that touches the stack and the bytes still verify
     * on this fixture while the recomputation cost returns everywhere. This fails instead.</p>
     */
    @Test
    public void injectingChangesNeitherTheStackNorTheLocals() {
        byte[] original = classWith("MaxProbe", true, false);
        int[] before = maxsOf(original);
        int[] after = maxsOf(Safepoints.inject(original));

        assertEquals("a safepoint grew the operand stack, so every frame in every instrumented class "
                + "now has to be recomputed -- which is what loads classes at instrumentation time",
                before[0], after[0]);
        assertEquals("a safepoint declared a local", before[1], after[1]);
    }

    /** {@code work}'s {@code (maxStack, maxLocals)}, read off the class file rather than recomputed. */
    private static int[] maxsOf(byte[] bytes) {
        int[] found = {-1, -1};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!"work".equals(name)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMaxs(int maxStack, int maxLocals) {
                        found[0] = maxStack;
                        found[1] = maxLocals;
                    }
                };
            }
        }, 0);
        assertTrue("no `work` method in the fixture", found[0] >= 0);
        return found;
    }

    private static byte[] classWith(String name, boolean loop, boolean abstractMethod) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8,
                Opcodes.ACC_PUBLIC | (abstractMethod ? Opcodes.ACC_ABSTRACT : 0) | Opcodes.ACC_SUPER,
                name, null, "java/lang/Object", null);

        if (!abstractMethod) {
            // A DEFAULT CONSTRUCTOR. Hand-built classes get none for free, and the omission surfaces as
            // NoSuchMethodException at newInstance -- which reads as the instrumentation having eaten
            // something rather than as the fixture never having had one.
            MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            init.visitCode();
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitInsn(Opcodes.RETURN);
            init.visitMaxs(1, 1);
            init.visitEnd();
        }

        if (abstractMethod) {
            writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "work", "()V", null, null)
                    .visitEnd();
        } else {
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "work", "()V", null, null);
            method.visitCode();
            if (loop) {
                Label top = new Label();
                method.visitLabel(top);
                method.visitInsn(Opcodes.NOP);
                method.visitJumpInsn(Opcodes.GOTO, top);   // backward
            }
            method.visitInsn(Opcodes.RETURN);
            method.visitMaxs(1, 1);
            method.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    public void aMethodEntryGetsOneCheck() {
        // Two entries: the constructor and `work`. Straight-line code inside them gets nothing, which
        // is the point -- a method that runs to completion pays one check, not one per statement.
        assertEquals(2, Safepoints.countInjectionPoints(classWith("Plain", false, false)));
    }

    @Test
    public void aBackwardBranchGetsOneMore() {
        // Entry plus loop. The pair is what makes a spinning script reachable at all: recursion goes
        // through the entry, iteration through the branch, and nothing spins without one of them.
        assertEquals(3, Safepoints.countInjectionPoints(classWith("Looping", true, false)));
    }

    @Test
    public void anAbstractMethodGetsNothing() {
        // Emitting into a method with no body produces a class file that fails verification with a
        // message about a method that plainly has no code.
        assertEquals(0, Safepoints.countInjectionPoints(classWith("Abstract", false, true)));
    }

    @Test
    public void injectingChangesTheBytecode() {
        byte[] original = classWith("Looping", true, false);
        byte[] instrumented = Safepoints.inject(original);
        assertNotEquals("nothing was injected", original.length, instrumented.length);
        assertTrue(instrumented.length > original.length);
    }

    @Test
    public void anInstrumentedClassStillVerifiesAndRuns() throws Exception {
        // The real assertion. A frame-invalidating injection produces a class that loads and then
        // throws VerifyError on first call -- which no length comparison would catch.
        byte[] instrumented = Safepoints.inject(classWith("Runs", false, false));
        Class<?> loaded = new com.crystalgui.language.engine.ScriptClassLoader(
                Map.of("Runs", instrumented), getClass().getClassLoader())
                .loadClass("Runs");
        Object instance = loaded.getDeclaredConstructor().newInstance();
        loaded.getMethod("work").invoke(instance);
    }

    @Test
    public void anInstrumentedMethodThrowsOnAnInterruptedThread() throws Exception {
        byte[] instrumented = Safepoints.inject(classWith("Checks", false, false));
        Class<?> loaded = new com.crystalgui.language.engine.ScriptClassLoader(
                Map.of("Checks", instrumented), getClass().getClassLoader())
                .loadClass("Checks");
        Object instance = loaded.getDeclaredConstructor().newInstance();

        Thread.currentThread().interrupt();
        try {
            loaded.getMethod("work").invoke(instance);
            fail("the injected checkpoint did not fire on an interrupted thread");
        } catch (java.lang.reflect.InvocationTargetException thrown) {
            assertTrue(String.valueOf(thrown.getCause()),
                    thrown.getCause() instanceof ScriptStoppedException);
        } finally {
            // CLEARED, or every later test in this JVM starts interrupted. The checkpoint deliberately
            // does not clear it, so the test that set it has to.
            Thread.interrupted();
        }
    }

    @Test
    public void aStopIsAnErrorSoAScriptCannotSwallowItByAccident() {
        // Scripts are full of `catch (Exception e)` around exactly the loop a stop has to break out of.
        assertTrue(new ScriptStoppedException() instanceof Error);
    }

    @Test
    public void aStopCarriesNoStackTrace() {
        // Filling one in costs more than every check that led to it, and it would describe an arbitrary
        // loop iteration. A stop is not a fault.
        assertEquals(0, new ScriptStoppedException().getStackTrace().length);
    }

    @Test
    public void aCheckpointOnAnUninterruptedThreadDoesNothing() {
        // The hot path, and it must be free of anything observable -- no allocation, no side effect.
        ScriptControl.checkpoint();
    }
}
