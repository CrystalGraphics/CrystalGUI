package com.crystalgui.language.run.exec;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Makes a script interruptible, by putting a check where a runaway one will hit it.
 *
 * <h3>Method entry and backward branches — that pair is not arbitrary</h3>
 *
 * <p>A thread that never returns to the host is either <b>looping</b> or <b>recursing</b>. Every loop,
 * however it is written — {@code while}, {@code for}, a labelled {@code continue}, a hand-rolled
 * {@code goto} out of a compiler — ends in a jump to a label that has already been visited: a backward
 * branch. Every recursion goes through a method entry. Two instrumentation points, and no way to spin
 * forever without crossing one.</p>
 *
 * <p>Straight-line code gets nothing, which is the point: a method that runs to completion pays one
 * check, not one per statement.</p>
 *
 * <h3>The injected instruction is a CALL, and that is a frame decision</h3>
 *
 * <p>The obvious injection is a volatile read and a conditional throw. It is also a <b>branch</b>, and
 * a new branch target in a Java 7+ class file needs a new {@code StackMapTable} entry — which means
 * {@code COMPUTE_FRAMES}, which means ASM calling {@code getCommonSuperClass}, which means <b>loading
 * classes at instrumentation time</b>. On a Minecraft host that is loading Minecraft classes while
 * compiling, and it fails outright for a type that is not loadable yet.</p>
 *
 * <p>A single {@code invokestatic} of a void no-arg method adds no branch, no local, and no stack
 * depth, so the existing frames stay valid and nothing needs recomputing. The branch still happens —
 * inside {@link ScriptControl#checkpoint()}, where HotSpot inlines it back to exactly the volatile read
 * and conditional the obvious version would have emitted. The cost is the same and the instrumentation
 * is a great deal safer.</p>
 *
 * <h3>Why this is affordable at all</h3>
 *
 * <p>§19.3: the output remap pass is already rewriting every script class, so the ASM machinery is paid
 * for. This is the second consumer that justifies it, not a reason to introduce it.</p>
 */
public final class Safepoints {

    private Safepoints() {
    }

    /** Instruments every class in {@code classes}. The input is not modified. */
    public static Map<String, byte[]> inject(Map<String, byte[]> classes) {
        Map<String, byte[]> instrumented = new HashMap<>(classes.size());
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            instrumented.put(entry.getKey(), inject(entry.getValue()));
        }
        return instrumented;
    }

    /** Instruments one class. */
    public static byte[] inject(byte[] original) {
        ClassReader reader = new ClassReader(original);
        // COMPUTE_MAXS is not requested either: an invokestatic of ()V changes neither the maximum
        // stack nor the locals, so every existing max stays correct. Recomputing would be work with a
        // guaranteed-identical answer.
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (delegate == null) return null;
                // ABSTRACT AND NATIVE METHODS HAVE NO BODY. Emitting into one produces a class file
                // that fails verification with a message about a method that plainly has no code.
                if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return delegate;
                return new SafepointMethodVisitor(delegate);
            }
        }, 0);
        return writer.toByteArray();
    }

    /** Counts what an instrumented class would get — for tests, and for anyone measuring the cost. */
    public static int countInjectionPoints(byte[] original) {
        final int[] count = {0};
        new ClassReader(original).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    private final Set<Label> seen = new HashSet<>();

                    @Override
                    public void visitCode() {
                        count[0]++;
                    }

                    @Override
                    public void visitLabel(Label label) {
                        seen.add(label);
                    }

                    @Override
                    public void visitJumpInsn(int opcode, Label label) {
                        if (seen.contains(label)) count[0]++;
                    }
                };
            }
        }, 0);
        return count[0];
    }

    /**
     * One method's worth of instrumentation.
     *
     * <p>A label is "backward" if it has already been visited when the jump to it is emitted, which is
     * exactly what ASM's visit order gives for free — no control-flow analysis, no basic blocks. It is
     * also the definition that catches every loop shape, including ones no source language produces.</p>
     */
    private static final class SafepointMethodVisitor extends MethodVisitor {

        private final Set<Label> visited = new HashSet<>();

        SafepointMethodVisitor(MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            emitCheckpoint();
        }

        @Override
        public void visitLabel(Label label) {
            super.visitLabel(label);
            visited.add(label);
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            // BEFORE the jump, not after the target. Emitting at the target would also run for the
            // fall-through entry into a loop, which is already covered by the method entry -- and,
            // worse, the target label is a frame boundary, so inserting there is the one position that
            // would invalidate the StackMapTable this whole design avoids touching.
            if (visited.contains(label)) emitCheckpoint();
            super.visitJumpInsn(opcode, label);
        }

        private void emitCheckpoint() {
            super.visitMethodInsn(Opcodes.INVOKESTATIC, ScriptControl.INTERNAL_NAME,
                    ScriptControl.CHECKPOINT, ScriptControl.CHECKPOINT_DESCRIPTOR, false);
        }
    }
}
