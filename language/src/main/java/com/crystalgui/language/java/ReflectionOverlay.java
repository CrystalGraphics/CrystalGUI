package com.crystalgui.language.java;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Types that exist only in memory, made visible to the compiler.
 *
 * <h3>The gap this fills</h3>
 *
 * <p>A classpath is a list of files, and some classes are not in one. A class generated at runtime has
 * no file at all; <b>a class a previous script defined</b> has none either, and that is the case that
 * matters most here — §16.3's "what can I say about this <em>after</em> the script has run". Neither
 * can be resolved by pointing the compiler at a directory, because there is nothing to point at.</p>
 *
 * <p>So they are synthesized: a {@link Class} is read reflectively and a class <em>file</em> is emitted
 * with the same supertypes, members and signatures and no method bodies. The compiler resolves against
 * that; the JVM links against the real one. A stub is only ever a compile-time view, which is why it
 * can be this cheap — nothing here needs to be executable.</p>
 *
 * <h3>Generic signatures are reconstructed, not erased</h3>
 *
 * <p>Reflection retains them ({@link ParameterizedType}, {@link TypeVariable}) and emitting only
 * descriptors would throw them away — so a script's own type would come back raw, and completion on it
 * would offer {@code Object get(int)} where the author wrote {@code List<String>}. The reconstruction is
 * mechanical and it is the difference between the overlay being useful and being a fallback.</p>
 *
 * <h3>The merge rule: reflection wins where both answer</h3>
 *
 * <p>A type can be on the classpath <em>and</em> live, and they can disagree — a transformer or a mixin
 * added members the file never had. What is loaded is what will run, so it is the truth (§15.2). This
 * class does not enforce that; the caller orders the classpath, and the overlay's directory goes first.</p>
 */
public final class ReflectionOverlay {

    /** Class-file version to emit. 52 is Java 8 — the floor every band can read. */
    private static final int CLASS_FILE_VERSION = Opcodes.V1_8;

    private final Path directory;
    private final Set<String> written = new LinkedHashSet<>();

    private ReflectionOverlay(Path directory) {
        this.directory = directory;
    }

    /** An overlay writing into a fresh temporary directory the caller owns. */
    public static ReflectionOverlay temporary() throws IOException {
        return new ReflectionOverlay(Files.createTempDirectory("cgui-overlay"));
    }

    public static ReflectionOverlay in(Path directory) throws IOException {
        Files.createDirectories(directory);
        return new ReflectionOverlay(directory);
    }

    /** The classpath entry to hand the compiler. */
    public Path directory() {
        return directory;
    }

    /** Every class stubbed so far, in the order they were added. */
    public List<String> stubbed() {
        return new ArrayList<>(written);
    }

    /**
     * Stubs {@code type} and everything it needs to be resolvable.
     *
     * <p>Supertypes are pulled in transitively, because a stub whose superclass cannot be found is
     * worse than no stub: the compiler reports an error about a class the author never mentioned.
     * Types that are already resolvable from the ordinary classpath are skipped — {@code java.*} is the
     * bulk of them, and re-stating the JDK would be both enormous and a source of subtle disagreement.</p>
     */
    public ReflectionOverlay add(Class<?> type) throws IOException {
        addTransitively(type);
        return this;
    }

    public ReflectionOverlay addAll(Collection<Class<?>> types) throws IOException {
        for (Class<?> type : types) addTransitively(type);
        return this;
    }

    private void addTransitively(Class<?> type) throws IOException {
        if (type == null || type.isPrimitive() || type.isArray()) return;
        if (!written.add(type.getName())) return;
        if (isAlreadyResolvable(type)) {
            written.remove(type.getName());
            return;
        }
        write(type);
        addTransitively(type.getSuperclass());
        for (Class<?> face : type.getInterfaces()) addTransitively(face);
    }

    /**
     * Whether the compiler will find this type without help.
     *
     * <p>The test is "does it come from a code source with a location" — a class loaded from a jar or a
     * directory has one and is on some classpath; a class defined from bytes has none. That is a
     * stronger question than the package name and it is the one that actually matters: a script's class
     * and a runtime proxy both answer no regardless of what they are called.</p>
     */
    private static boolean isAlreadyResolvable(Class<?> type) {
        if (type.getName().startsWith("java.") || type.getName().startsWith("javax.")) return true;
        java.security.ProtectionDomain domain = type.getProtectionDomain();
        return domain != null && domain.getCodeSource() != null
                && domain.getCodeSource().getLocation() != null;
    }

    private void write(Class<?> type) throws IOException {
        ClassWriter writer = new ClassWriter(0);
        String internalName = Type.getInternalName(type);
        String[] interfaces = new String[type.getInterfaces().length];
        for (int i = 0; i < interfaces.length; i++) {
            interfaces[i] = Type.getInternalName(type.getInterfaces()[i]);
        }
        // ACC_SUPER is not reported by getModifiers and is required on every modern class file; ACC_SYNTHETIC
        // is stripped so the compiler does not hide the type from resolution.
        int access = (type.getModifiers() & ~Opcodes.ACC_SYNTHETIC) | Opcodes.ACC_SUPER;
        writer.visit(CLASS_FILE_VERSION, access, internalName, Signatures.ofClass(type),
                type.getSuperclass() == null ? "java/lang/Object"
                        : Type.getInternalName(type.getSuperclass()),
                interfaces);

        for (Field field : type.getDeclaredFields()) {
            if (field.isSynthetic()) continue;
            writer.visitField(field.getModifiers(), field.getName(),
                    Type.getDescriptor(field.getType()), Signatures.of(field.getGenericType()),
                    null).visitEnd();
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (constructor.isSynthetic()) continue;
            writer.visitMethod(constructor.getModifiers(), "<init>",
                    Type.getConstructorDescriptor(constructor),
                    Signatures.ofExecutable(constructor.getTypeParameters(),
                            constructor.getGenericParameterTypes(), void.class,
                            constructor.getGenericExceptionTypes()),
                    exceptionsOf(constructor.getExceptionTypes())).visitEnd();
        }
        for (Method method : type.getDeclaredMethods()) {
            // BRIDGES AND SYNTHETICS DROPPED, for the same reason membersOf drops them: they carry
            // erased signatures and exist only so an override links. A stub containing both would make
            // every generic override ambiguous to the compiler rather than merely noisy.
            if (method.isSynthetic() || method.isBridge()) continue;
            writer.visitMethod(method.getModifiers(), method.getName(), Type.getMethodDescriptor(method),
                    Signatures.ofExecutable(method.getTypeParameters(),
                            method.getGenericParameterTypes(), method.getGenericReturnType(),
                            method.getGenericExceptionTypes()),
                    exceptionsOf(method.getExceptionTypes())).visitEnd();
        }
        writer.visitEnd();

        Path target = directory.resolve(internalName + ".class");
        Files.createDirectories(target.getParent());
        Files.write(target, writer.toByteArray());
    }

    private static String[] exceptionsOf(Class<?>[] thrown) {
        if (thrown.length == 0) return null;
        String[] internal = new String[thrown.length];
        for (int i = 0; i < thrown.length; i++) internal[i] = Type.getInternalName(thrown[i]);
        return internal;
    }

    /**
     * JVMS 4.7.9.1 signature strings, rebuilt from {@code java.lang.reflect}.
     *
     * <p>Kept separate because it is pure translation and because getting it slightly wrong produces a
     * class file the compiler rejects with a message about a signature rather than about anything the
     * author did. Every method returns null when the type is not generic, which is exactly what ASM
     * wants for "no {@code Signature} attribute".</p>
     */
    static final class Signatures {

        private Signatures() {
        }

        static String ofClass(Class<?> type) {
            TypeVariable<?>[] parameters = type.getTypeParameters();
            java.lang.reflect.Type superclass = type.getGenericSuperclass();
            java.lang.reflect.Type[] interfaces = type.getGenericInterfaces();
            if (parameters.length == 0 && !isGeneric(superclass) && !anyGeneric(interfaces)) return null;

            StringBuilder signature = new StringBuilder();
            appendFormals(signature, parameters);
            signature.append(of(superclass == null ? Object.class : superclass));
            for (java.lang.reflect.Type face : interfaces) signature.append(of(face));
            return signature.toString();
        }

        static String ofExecutable(TypeVariable<?>[] parameters, java.lang.reflect.Type[] arguments,
                                   java.lang.reflect.Type returned,
                                   java.lang.reflect.Type[] thrown) {
            if (parameters.length == 0 && !anyGeneric(arguments) && !isGeneric(returned)
                    && !anyGeneric(thrown)) {
                return null;
            }
            StringBuilder signature = new StringBuilder();
            appendFormals(signature, parameters);
            signature.append('(');
            for (java.lang.reflect.Type argument : arguments) signature.append(of(argument));
            signature.append(')').append(of(returned));
            for (java.lang.reflect.Type exception : thrown) signature.append('^').append(of(exception));
            return signature.toString();
        }

        private static void appendFormals(StringBuilder into, TypeVariable<?>[] parameters) {
            if (parameters.length == 0) return;
            into.append('<');
            for (TypeVariable<?> parameter : parameters) {
                into.append(parameter.getName());
                java.lang.reflect.Type[] bounds = parameter.getBounds();
                // A CLASS BOUND IS ALWAYS EMITTED, even when it is Object: the grammar is
                // `:ClassBound *InterfaceBound`, and omitting the colon makes the whole signature
                // unparseable rather than merely imprecise.
                into.append(':');
                if (bounds.length > 0 && !isInterface(bounds[0])) {
                    into.append(of(bounds[0]));
                    for (int i = 1; i < bounds.length; i++) into.append(':').append(of(bounds[i]));
                } else {
                    for (java.lang.reflect.Type bound : bounds) into.append(':').append(of(bound));
                }
            }
            into.append('>');
        }

        private static boolean isInterface(java.lang.reflect.Type bound) {
            Class<?> raw = rawOf(bound);
            return raw != null && raw.isInterface();
        }

        private static Class<?> rawOf(java.lang.reflect.Type type) {
            if (type instanceof Class) return (Class<?>) type;
            if (type instanceof ParameterizedType) return rawOf(((ParameterizedType) type).getRawType());
            return null;
        }

        static String of(java.lang.reflect.Type type) {
            if (type instanceof Class) {
                Class<?> raw = (Class<?>) type;
                return raw == void.class ? "V" : Type.getDescriptor(raw);
            }
            if (type instanceof ParameterizedType) {
                ParameterizedType parameterized = (ParameterizedType) type;
                Class<?> raw = (Class<?>) parameterized.getRawType();
                StringBuilder built = new StringBuilder("L").append(Type.getInternalName(raw)).append('<');
                for (java.lang.reflect.Type argument : parameterized.getActualTypeArguments()) {
                    built.append(of(argument));
                }
                return built.append(">;").toString();
            }
            if (type instanceof GenericArrayType) {
                return "[" + of(((GenericArrayType) type).getGenericComponentType());
            }
            if (type instanceof TypeVariable) {
                return "T" + ((TypeVariable<?>) type).getName() + ";";
            }
            if (type instanceof WildcardType) {
                WildcardType wildcard = (WildcardType) type;
                if (wildcard.getLowerBounds().length > 0) return "-" + of(wildcard.getLowerBounds()[0]);
                java.lang.reflect.Type[] upper = wildcard.getUpperBounds();
                if (upper.length == 0 || upper[0] == Object.class) return "*";
                return "+" + of(upper[0]);
            }
            return "Ljava/lang/Object;";
        }

        private static boolean isGeneric(java.lang.reflect.Type type) {
            return type != null && !(type instanceof Class);
        }

        private static boolean anyGeneric(java.lang.reflect.Type[] types) {
            for (java.lang.reflect.Type type : types) {
                if (isGeneric(type)) return true;
            }
            return false;
        }
    }

    /** Deletes the overlay's directory. The caller owns it; nothing here does it automatically. */
    public void delete() {
        try (java.util.stream.Stream<Path> walk = Files.walk(directory)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A temp directory that outlives one analysis is not worth failing it over.
                }
            });
        } catch (IOException ignored) {
            // Same.
        }
    }

}
