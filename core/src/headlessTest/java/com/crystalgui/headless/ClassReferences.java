package com.crystalgui.headless;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Every type a compiled class NAMES — supertypes, field and method descriptors, and every class
 * constant and instruction operand in its bodies.
 *
 * <p>The same helper {@code language/}'s {@code RunShellIsEngineNeutralTest} uses, and for the same
 * reason it scans bytecode rather than imports: a reference in the constant pool is the real question.
 * If a class file names a type at all then some input can reach it, and a runtime check only ever
 * proves the path that test happened to take.</p>
 *
 * <p>A forbidden entry ending in {@code /} is a package prefix; any other entry is an exact class
 * (its nested classes included), so {@code com/crystalgui/ui/dom/Node} can be forbidden without
 * forbidding {@code NodeContract} beside it.</p>
 */
public final class ClassReferences {

    private ClassReferences() {
    }

    /** {@code build/classes/java/main} for the module the test class was compiled in. */
    public static Path mainClassesRoot(Class<?> testClass) {
        Path testClasses = Path.of(testClass.getProtectionDomain()
                .getCodeSource().getLocation().getPath().replace("%20", " ").replaceFirst("^/", ""));
        return testClasses.getParent().resolve("main");
    }

    /** Offences under one package directory. */
    public static List<String> offences(Path root, String packageDirectory, List<String> forbidden)
            throws IOException {
        return offences(root, root.resolve(packageDirectory), file -> true, forbidden);
    }

    /**
     * Offences under {@code directory}, for the class files {@code include} admits.
     *
     * @param include a filter on the class file's path relative to {@code root} — what lets a scan of
     *                the whole tree skip the packages it is protecting
     */
    public static List<String> offences(Path root, Path directory, Predicate<String> include,
                                        List<String> forbidden) throws IOException {
        List<String> offences = new ArrayList<>();
        if (!Files.isDirectory(directory)) return offences;
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toArray(Path[]::new)) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (!include.test(relative)) continue;
                for (String referenced : referencesOf(file)) {
                    for (String rule : forbidden) {
                        if (matches(referenced, rule)) {
                            offences.add(relative + " references " + referenced);
                        }
                    }
                }
            }
        }
        return offences;
    }

    /**
     * Every {@code owner.member} a class's bodies touch: a field read or written, a method called.
     * For rules about WHAT is done with a type rather than whether it is named — an enum constant, a
     * particular static method.
     */
    public static Set<String> memberReferencesOf(Path classFile) throws IOException {
        Set<String> members = new LinkedHashSet<>();
        try (InputStream stream = Files.newInputStream(classFile)) {
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDescriptor) {
                            members.add(owner + "." + fieldName);
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName,
                                                    String methodDescriptor, boolean isInterface) {
                            members.add(owner + "." + methodName);
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES);
        }
        return members;
    }

    private static boolean matches(String referenced, String rule) {
        if (rule.endsWith("/")) return referenced.startsWith(rule);
        return referenced.equals(rule) || referenced.startsWith(rule + "$");
    }

    public static Set<String> referencesOf(Path classFile) throws IOException {
        Set<String> names = new LinkedHashSet<>();
        try (InputStream stream = Files.newInputStream(classFile)) {
            ClassReader reader = new ClassReader(stream);
            if (reader.getSuperName() != null) names.add(reader.getSuperName());
            names.addAll(List.of(reader.getInterfaces()));
            for (int index = 1; index < reader.getItemCount(); index++) {
                try {
                    Object constant = reader.readConst(index, new char[reader.getMaxStringLength()]);
                    if (constant instanceof Type) {
                        Type type = (Type) constant;
                        if (type.getSort() == Type.OBJECT) names.add(type.getInternalName());
                    }
                } catch (RuntimeException notAConstant) {
                    // Not every pool slot is a loadable constant; the ones that are not are not types.
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
