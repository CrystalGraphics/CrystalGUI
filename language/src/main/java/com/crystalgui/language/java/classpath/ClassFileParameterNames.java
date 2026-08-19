package com.crystalgui.language.java.classpath;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import javax.annotation.Nullable;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * <b>Real parameter names for a classpath method</b> — read off the class file, with nothing shipped.
 *
 * <h3>The finding this rests on, measured rather than assumed</h3>
 *
 * <p>Parameter names <em>survive compilation</em>. {@code java.util.ArrayList.add} carries {@code e},
 * {@code java.lang.String.format} carries {@code format} and {@code args}, and our own {@code core.jar}
 * carries its names today with no build change, because Gradle passes {@code -g} by default. So the
 * headline benefit of source attachment is reachable on a plain JRE with no artefact at all. This was
 * assumed impossible for a session on the grounds that {@code src.zip} is a JDK-only artefact — true,
 * and the wrong place to have been looking.</p>
 *
 * <p>{@code JavaSignatures.parameterNames} answers only for the unit being analysed, so every classpath
 * member fell through to types-only: {@code println(String)} where IntelliJ shows {@code println(String x)}.
 * This is the fallback for that, and it changes nothing when it finds nothing.</p>
 *
 * <h3>Where it cannot help, and why the gap is shaped like that</h3>
 *
 * <p><b>An abstract or interface method has no {@code Code} attribute, so it has no
 * {@code LocalVariableTable}.</b> {@code java.util.List.add} has neither attribute — verified, not
 * inferred. That matters more than it sounds: idiomatic Java declares variables as the interface, so
 * {@code List.add}, {@code Map.put} and {@code Comparator.compare} are exactly the hovers a reader
 * performs most. {@code MethodParameters} is the one attribute that does not need a body, which is why
 * {@code -parameters} on our own modules is the other half of this — it is the only mechanism that can
 * name an <em>interface</em> method's parameters.</p>
 *
 * <h3>Three traps, each silent, each measured on the real JDK</h3>
 *
 * <ul>
 *   <li><b>Slot 0 is {@code this} for an instance method and the FIRST PARAMETER for a static one.</b>
 *       {@code String.format} is the static case and its slot 0 is {@code format}. Getting this wrong
 *       shifts every name by one and produces a signature that is plausible and wrong.</li>
 *   <li><b>{@code long} and {@code double} occupy two slots each</b>, so a method taking
 *       {@code (long, String)} has its second parameter at slot 3.</li>
 *   <li><b>The table holds every local, not just the parameters</b> — {@code ArrayList.add} reports
 *       {@code [this, e, elementData, s]}. Only the leading slots are parameters, and only as many of
 *       them as the descriptor declares.</li>
 * </ul>
 *
 * <p>Ambiguity resolves to <b>null</b> rather than to a guess. Two overloads of one arity that this
 * cannot tell apart give types-only, which is exactly what the caller did before — a wrong name is worse
 * than an absent one, because a signature naming somebody else's parameters reads as authoritative.</p>
 */
public final class ClassFileParameterNames {

    /** Bounded, and small: a hover asks about a handful of classes and asks about them repeatedly. */
    private static final int MAX_CACHED_CLASSES = 24;

    private final List<String> classpath;

    private final Map<String, Map<String, List<String>>> cache =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Map<String, List<String>>> eldest) {
                    return size() > MAX_CACHED_CLASSES;
                }
            };

    private ClassFileParameterNames(List<String> classpath) {
        this.classpath = classpath == null ? List.of() : List.copyOf(classpath);
    }

    public static ClassFileParameterNames forClasspath(@Nullable List<String> classpath) {
        return new ClassFileParameterNames(classpath);
    }

    /**
     * The declared names of {@code methodName}'s parameters, or null.
     *
     * @param ownerBinaryName  the <em>declaring</em> class, binary form — {@code java.util.ArrayList}
     * @param methodName       the method, or {@code <init>} for a constructor
     * @param erasedParameters each parameter's erased type in binary form, in order; the arity and the
     *                         widths both come from this, so it must describe the method as the class
     *                         file does rather than as the source generic signature does
     */
    @Nullable
    public synchronized List<String> namesOf(@Nullable String ownerBinaryName,
                                             @Nullable String methodName,
                                             @Nullable List<String> erasedParameters) {
        if (ownerBinaryName == null || methodName == null || erasedParameters == null) return null;
        if (erasedParameters.isEmpty()) return null;

        Map<String, List<String>> declared = cache.get(ownerBinaryName);
        if (declared == null) {
            declared = readNames(ownerBinaryName);
            cache.put(ownerBinaryName, declared);
        }
        List<String> exact = declared.get(key(methodName, erasedParameters));
        if (exact != null) return exact;

        // AMBIGUITY IS ANSWERED WITH NOTHING. A single same-name candidate of this arity is safe to
        // take -- ECJ resolved the overload already and there is only one thing it could have meant --
        // but two are not, and `Math.max(int, int)` against `max(double, double)` is exactly the shape
        // that would otherwise show one member's names on the other's signature.
        String prefix = methodName + "/" + erasedParameters.size() + "/";
        List<String> only = null;
        for (Map.Entry<String, List<String>> candidate : declared.entrySet()) {
            if (!candidate.getKey().startsWith(prefix)) continue;
            if (only != null) return null;
            only = candidate.getValue();
        }
        return only;
    }

    /** {@code name/arity/erased,types} — arity is in the key so the fallback above can prefix-match it. */
    private static String key(String methodName, List<String> erasedParameters) {
        return methodName + "/" + erasedParameters.size() + "/" + String.join(",", erasedParameters);
    }

    /** Every method in one class, keyed as above. Empty when the bytes are unreachable. */
    private Map<String, List<String>> readNames(String ownerBinaryName) {
        byte[] bytes = bytesOf(ownerBinaryName);
        if (bytes == null) return Map.of();

        Map<String, List<String>> found = new LinkedHashMap<>();
        try {
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    Type[] parameters = Type.getArgumentTypes(descriptor);
                    if (parameters.length == 0) return null;
                    boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
                    return new NameCollector(found, name, parameters, isStatic);
                }
            }, ClassReader.SKIP_FRAMES);
        } catch (RuntimeException unreadable) {
            // A class file this ASM cannot read is a reason to show types, not to fail a hover.
            return Map.of();
        }
        return found;
    }

    /**
     * Collects one method's parameter names from whichever attribute carries them.
     *
     * <p>{@code MethodParameters} wins when present because it is unambiguous — it lists parameters and
     * only parameters, in order. The local-variable table is the fallback and needs all three traps
     * handled: the {@code this} slot, the two-slot types, and the trailing locals that are not
     * parameters at all.</p>
     */
    private static final class NameCollector extends MethodVisitor {

        private final Map<String, List<String>> into;
        private final String methodName;
        private final Type[] parameters;
        private final boolean isStatic;
        private final List<String> fromAttribute = new ArrayList<>();
        private final Map<Integer, String> bySlot = new LinkedHashMap<>();

        NameCollector(Map<String, List<String>> into, String methodName, Type[] parameters,
                      boolean isStatic) {
            super(Opcodes.ASM9);
            this.into = into;
            this.methodName = methodName;
            this.parameters = parameters;
            this.isStatic = isStatic;
        }

        @Override
        public void visitParameter(String name, int access) {
            if (name != null && !name.isEmpty()) fromAttribute.add(name);
        }

        @Override
        public void visitLocalVariable(String name, String descriptor, String signature,
                                       Label start, Label end, int index) {
            // FIRST ENTRY PER SLOT WINS. A slot is reused by later, narrower scopes, and javac emits a
            // method's parameters first -- so the first entry for a slot is the parameter where there is
            // one. Preferring a later entry names a loop variable as a parameter.
            bySlot.putIfAbsent(index, name);
        }

        @Override
        public void visitEnd() {
            List<String> names = fromAttribute.size() == parameters.length
                    ? List.copyOf(fromAttribute) : fromLocals();
            if (names == null) return;
            into.put(key(methodName, erasedNamesOf(parameters)), names);
        }

        @Nullable
        private List<String> fromLocals() {
            if (bySlot.isEmpty()) return null;
            List<String> names = new ArrayList<>(parameters.length);
            int slot = isStatic ? 0 : 1;
            for (Type parameter : parameters) {
                String name = bySlot.get(slot);
                if (name == null || name.isEmpty()) return null;
                names.add(name);
                slot += parameter.getSize();
            }
            return names;
        }
    }

    /** Each parameter's erased type in the same binary spelling the caller supplies. */
    private static List<String> erasedNamesOf(Type[] parameters) {
        List<String> names = new ArrayList<>(parameters.length);
        for (Type parameter : parameters) names.add(parameter.getClassName());
        return names;
    }

    /**
     * The class file's bytes, from the analysis classpath first and the running loader second.
     *
     * <p>Both are needed and neither is sufficient. The classpath is where a mod's or a workspace's
     * classes are, and a JDK type is not on it — {@code java.util.ArrayList} lives in the runtime image.
     * The loader answers for the image (measured: {@code getSystemResourceAsStream} returns 19KB for
     * {@code java/util/ArrayList.class}) and cannot see a jar the host never loaded.</p>
     */
    @Nullable
    private byte[] bytesOf(String binaryName) {
        String path = binaryName.replace('.', '/') + ".class";
        for (String entry : classpath) {
            byte[] bytes = bytesFrom(entry, path);
            if (bytes != null) return bytes;
        }
        try (InputStream in = ClassLoader.getSystemResourceAsStream(path)) {
            return in == null ? null : in.readAllBytes();
        } catch (Exception unreadable) {
            return null;
        }
    }

    @Nullable
    private static byte[] bytesFrom(String entry, String classFilePath) {
        if (entry == null || entry.isBlank()) return null;
        try {
            File file = new File(entry);
            if (file.isDirectory()) {
                Path found = file.toPath().resolve(classFilePath);
                return Files.isRegularFile(found) ? Files.readAllBytes(found) : null;
            }
            if (!file.isFile()) return null;
            try (ZipFile zip = new ZipFile(file)) {
                ZipEntry found = zip.getEntry(classFilePath);
                if (found == null) return null;
                try (InputStream in = zip.getInputStream(found)) {
                    return in.readAllBytes();
                }
            }
        } catch (Exception unreadable) {
            return null;
        }
    }
}
