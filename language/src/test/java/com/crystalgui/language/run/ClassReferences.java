package com.crystalgui.language.run;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Every type a compiled class names — the primitive under this module's ownership-boundary tests.
 *
 * <p>Shared by {@link ExecutionNeedsNoGrammarTest} and {@link RunShellIsEngineNeutralTest}, because two
 * copies of a bytecode scan is how one of them stops detecting anything: the negative control each test
 * carries proves the scan sees <em>something</em>, but not that both scans see the same things.</p>
 */
final class ClassReferences {

    private ClassReferences() {
    }

    /** This module's compiled main output, found beside the test output the caller was loaded from. */
    static Path mainClassesRoot(Class<?> testClass) {
        Path testClasses = Path.of(testClass.getProtectionDomain()
                .getCodeSource().getLocation().getPath().replace("%20", " ").replaceFirst("^/", ""));
        return testClasses.getParent().resolve("main");
    }

    /**
     * Every {@code file references type} pair under {@code packageDirectory} whose type starts with one
     * of {@code forbidden}, as {@code relative/path.class references internal/Name} strings.
     */
    static List<String> offences(Path root, String packageDirectory, List<String> forbidden)
            throws IOException {
        List<String> offences = new ArrayList<>();
        Path directory = root.resolve(packageDirectory);
        if (!Files.isDirectory(directory)) return offences;
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toArray(Path[]::new)) {
                for (String referenced : referencesOf(file)) {
                    for (String prefix : forbidden) {
                        if (referenced.startsWith(prefix)) {
                            offences.add(root.relativize(file) + " references " + referenced);
                        }
                    }
                }
            }
        }
        return offences;
    }

    /**
     * Every type a class file names — supertypes, field and method descriptors, and the constant pool.
     *
     * <p>The constant pool is the one that matters and the one an import-based check would miss: a
     * reference put there by a method body is a reference regardless of whether the source has an
     * {@code import}, and a fully-qualified name in a signature has no import at all.</p>
     */
    static Set<String> referencesOf(Path classFile) throws IOException {
        Set<String> names = new LinkedHashSet<>();
        try (InputStream stream = Files.newInputStream(classFile)) {
            ClassReader reader = new ClassReader(stream);
            if (reader.getSuperName() != null) names.add(reader.getSuperName());
            names.addAll(List.of(reader.getInterfaces()));

            // getClassName aside, the pool is walked directly: every CONSTANT_Class entry is a type
            // this class file can reach, whoever put it there.
            for (int index = 1; index < reader.getItemCount(); index++) {
                try {
                    Object constant = reader.readConst(index, new char[reader.getMaxStringLength()]);
                    if (constant instanceof Type) {
                        Type type = (Type) constant;
                        if (type.getSort() == Type.OBJECT) names.add(type.getInternalName());
                    }
                } catch (RuntimeException notAConstant) {
                    // The pool is not densely indexed by constants; the misses are expected.
                }
            }
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visitOuterClass(String owner, String name, String descriptor) {
                    names.add(owner);
                }

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
                    if (exceptions != null) names.addAll(List.of(exceptions));
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitTypeInsn(int opcode, String type) {
                            names.add(type);
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String owner, String fieldName,
                                                   String fieldDescriptor) {
                            names.add(owner);
                            addType(names, Type.getType(fieldDescriptor));
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName,
                                                    String methodDescriptor, boolean isInterface) {
                            names.add(owner);
                            addType(names, Type.getReturnType(methodDescriptor));
                            for (Type argument : Type.getArgumentTypes(methodDescriptor)) {
                                addType(names, argument);
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES);
        }
        return names;
    }

    private static void addType(Set<String> names, Type type) {
        Type element = type;
        while (element.getSort() == Type.ARRAY) element = element.getElementType();
        if (element.getSort() == Type.OBJECT) names.add(element.getInternalName());
    }
}
