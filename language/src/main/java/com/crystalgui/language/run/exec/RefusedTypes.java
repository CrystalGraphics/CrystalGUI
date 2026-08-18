package com.crystalgui.language.run.exec;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Every type a compiled script names, checked against the policy <b>before any of it runs</b>.
 *
 * <h3>Why the bytes and not the source</h3>
 *
 * <p>A compiler's name environment can refuse what a script <em>writes down</em>, and that is worth doing
 * because it puts a red squiggle under the offending line. It is not what a script <em>links</em>. The
 * constant pool is: a reference put there by a method body is a reference whether or not the source
 * carried an import, and a fully-qualified name in a signature has no import at all. This is the same
 * primitive the module's own ownership tests use on our own class files, pointed at a script instead.</p>
 *
 * <h3>Why before, and not at the loader</h3>
 *
 * <p>{@code ScriptClassLoader} gating {@code loadClass} is the other half and is genuinely needed —
 * it is the only thing that sees a name resolved late. But on its own it refuses <b>mid-run</b>: the
 * script has already opened a file, sent a packet or mutated a world before it touches the class that
 * gets it stopped, and a partly-applied script is its own hazard. Refusing the whole thing at load costs
 * one pass over bytes that are about to be walked anyway by {@link Safepoints}.</p>
 *
 * <h3>What this does not do</h3>
 *
 * <p>It reads names. {@code Class.forName(someString)} names {@code java.lang.Class} and nothing else, so
 * a script that builds a class name at run time passes this and is caught — if at all — by the loader
 * gate. That is why {@code ScriptPolicy.UNSAFE} refuses the reflection surface outright: with reflection
 * unreachable there is no late name to resolve, and with it reachable neither half of this means much.
 * §19.1 is the honest statement of what the pair is worth.</p>
 */
public final class RefusedTypes {

    private RefusedTypes() {
    }

    /**
     * What an {@code invokedynamic} bootstrap puts in the pool without the author writing it.
     *
     * <p>Since Java 9 a string concatenation compiles to an {@code invokedynamic} against
     * {@code StringConcatFactory}, and every lambda and method reference to one against
     * {@code LambdaMetafactory}; both bootstrap descriptors name {@code MethodHandles.Lookup},
     * {@code MethodType} and {@code MethodHandle}. All of it lands in the constant pool of a class whose
     * source says {@code "count: " + n}.</p>
     *
     * <p>So this is not a hole in the filter — these names are still reported when an author actually
     * <em>calls</em> one, because that emits an instruction and the visitor adds it by owner. It is only
     * the pool walk that cannot tell the two apart.</p>
     */
    private static final Set<String> BOOTSTRAP_SURFACE = new LinkedHashSet<>(java.util.Arrays.asList(
            "java.lang.invoke.StringConcatFactory",
            "java.lang.invoke.LambdaMetafactory",
            "java.lang.invoke.MethodHandles",
            "java.lang.invoke.MethodHandles$Lookup",
            "java.lang.invoke.MethodHandle",
            "java.lang.invoke.MethodType",
            "java.lang.invoke.CallSite"));

    /**
     * The refused types this script names, in encounter order, or empty when it names none.
     *
     * @param classes   compiled class files by binary name — what {@link Safepoints#inject} is handed
     * @param permitted asked with a <b>binary</b> name ({@code java.util.List}), never an internal one
     */
    public static List<String> in(Map<String, byte[]> classes, Predicate<String> permitted) {
        List<String> refused = new ArrayList<>();
        if (classes == null || permitted == null) return refused;
        Set<String> seen = new LinkedHashSet<>();
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            for (String named : referencesOf(entry.getValue())) {
                // THE SCRIPT'S OWN CLASSES ARE NOT REACHED THROUGH THE POLICY. A script names itself, its
                // nested classes and its siblings, and none of them exists for the policy to have an
                // opinion about -- asking would refuse every script under any allowlist that does not
                // happen to name the synthetic package the compiler put it in.
                if (classes.containsKey(named)) continue;
                if (isOurs(named)) continue;
                if (!seen.add(named)) continue;
                if (!permitted.test(named)) refused.add(named);
            }
        }
        return refused;
    }

    /**
     * Whether we put this reference there ourselves.
     *
     * <p><b>{@link Safepoints} injects a call to {@link ScriptControl#checkpoint()} into every method of
     * every script</b>, which means every compiled script names it whether or not its author has heard
     * of it. Policing that reference makes the kill switch the thing that refuses the script: any policy
     * not naming our own internals refuses <em>everything</em>, and the message points at a class the
     * author never wrote. Found by a test whose allowlist was simply a normal one.</p>
     *
     * <p>Not a hole. This is the runtime a script is <em>given</em> — the same category as the class the
     * prelude wrapped it in — and it is a void no-arg call that reads a flag. A policy has no more
     * business refusing it than it has refusing the loader that defined the script.</p>
     */
    private static boolean isOurs(String binaryName) {
        return binaryName.equals(ScriptControl.class.getName());
    }

    /**
     * Every type one class file names — supertypes, descriptors, and the constant pool.
     *
     * <p>Deliberately the same walk as the module's ownership scans rather than a lighter one: a check
     * that reads only the imports is a check a method body walks straight past.</p>
     */
    private static Set<String> referencesOf(byte[] bytes) {
        Set<String> names = new LinkedHashSet<>();
        if (bytes == null || bytes.length == 0) return names;
        ClassReader reader = new ClassReader(bytes);
        add(names, reader.getSuperName());
        for (String each : reader.getInterfaces()) add(names, each);

        for (int index = 1; index < reader.getItemCount(); index++) {
            try {
                Object constant = reader.readConst(index, new char[reader.getMaxStringLength()]);
                if (!(constant instanceof Type)) continue;
                Set<String> fromPool = new LinkedHashSet<>();
                addType(fromPool, (Type) constant);
                for (String named : fromPool) {
                    // THE POOL SEES THE COMPILER'S PLUMBING, and the author never wrote it. `"a" + b`
                    // compiles to an invokedynamic whose bootstrap names StringConcatFactory and
                    // MethodHandles$Lookup, and every lambda names LambdaMetafactory -- so a policy
                    // refusing `java.lang.invoke` refused every script that concatenates a string or
                    // takes a lambda, which is very nearly every script. Reported as
                    // "reaches 7 classes" over a file whose author had reached for three.
                    //
                    // Skipped HERE and not in the visitor below: an author calling
                    // `MethodHandles.lookup()` emits an invokestatic, which visitMethodInsn adds by owner
                    // and this exemption never sees. Bootstrap plumbing appears in the pool ONLY, so the
                    // distinction is exactly where the reference came from.
                    if (!BOOTSTRAP_SURFACE.contains(named)) names.add(named);
                }
            } catch (RuntimeException notAConstant) {
                // The pool is not densely indexed by constants; the misses are expected.
            }
        }

        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                addType(names, Type.getType(descriptor));
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                addType(names, Type.getReturnType(descriptor));
                for (Type argument : Type.getArgumentTypes(descriptor)) addType(names, argument);
                if (exceptions != null) {
                    for (String thrown : exceptions) add(names, thrown);
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        add(names, type);
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fieldName,
                                               String fieldDescriptor) {
                        add(names, owner);
                        addType(names, Type.getType(fieldDescriptor));
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDescriptor, boolean isInterface) {
                        add(names, owner);
                        addType(names, Type.getReturnType(methodDescriptor));
                        for (Type argument : Type.getArgumentTypes(methodDescriptor)) {
                            addType(names, argument);
                        }
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof Type) addType(names, (Type) value);
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        return names;
    }

    private static void addType(Set<String> names, Type type) {
        Type element = type;
        while (element.getSort() == Type.ARRAY) element = element.getElementType();
        if (element.getSort() == Type.OBJECT) add(names, element.getInternalName());
    }

    /** Internal name in, BINARY name out — the policy speaks the language a deployment writes. */
    private static void add(Set<String> names, String internalName) {
        if (internalName == null || internalName.isEmpty()) return;
        if (internalName.charAt(0) == '[') {
            addType(names, Type.getType(internalName));
            return;
        }
        names.add(internalName.replace('/', '.'));
    }
}
