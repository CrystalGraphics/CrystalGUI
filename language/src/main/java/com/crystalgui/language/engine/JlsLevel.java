package com.crystalgui.language.engine;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * The newest Java language level a band's JDT can be asked for — discovered, never named.
 *
 * <h3>This is what makes "one adapter, three bands" possible</h3>
 *
 * <p>{@code ASTParser.newParser(int level)} takes a constant from {@code org.eclipse.jdt.core.dom.AST},
 * and the set of those constants grows with every JDT release. An adapter compiled against the oldest
 * band cannot name {@code JLS21}, because the field does not exist there — and an adapter that named it
 * anyway would fail to compile against band 8 and would fail at <em>runtime</em> on band 8 with
 * {@code NoSuchFieldError} if it somehow got through. Naming the oldest constant instead compiles
 * everywhere and silently caps the newest band at Java 8 syntax, which is worse: it works.</p>
 *
 * <p>So the level is read out of the jar that is actually loaded. The adapter names no level at all.</p>
 *
 * <h3>Why the field name is parsed rather than the value trusted</h3>
 *
 * <p>JDT's constants are not a clean sequence. {@code JLS2}…{@code JLS4} are the old integers 2–4, then
 * {@code JLS8} is 8 and the modern ones are the Java feature version — but several are deprecated, and
 * {@code AST.getJLSLatest()} exists only from JDT 3.14 onward and returns the newest <em>supported</em>
 * level, which is what we want but which is not present in every band we might one day add. Reading
 * {@code JLS<n>} field names and taking the highest is stable across every version of the class, needs
 * no method to exist, and cannot be fooled by a renumbering.</p>
 *
 * <p><b>Deprecated constants are skipped.</b> JDT deprecates a level when it stops being meaningfully
 * supported, and passing one produces a parser that works and quietly refuses modern syntax.</p>
 */
public final class JlsLevel {

    /** JDT's own name for the class that carries the constants. */
    private static final String AST_CLASS = "org.eclipse.jdt.core.dom.AST";

    /**
     * The oldest level anything here would accept.
     *
     * <p>JLS8 rather than JLS2: a parser at JLS4 cannot read a lambda, and every band's JDT is far
     * newer than that. If discovery ever returns something below this, the jar is not what we think it
     * is and failing is better than parsing a modern script with a 2011 grammar.</p>
     */
    public static final int MINIMUM = 8;

    private JlsLevel() {
    }

    /**
     * The highest usable {@code AST.JLS*} constant in this loader's JDT.
     *
     * @param engineLoader the band's {@link EngineClassLoader}
     * @throws IllegalStateException if the class is missing or carries no usable level — both mean the
     *                               band's jars are not what the pin says, which is worth failing on
     *                               rather than degrading to a level that happens to parse
     */
    public static int highestAvailable(ClassLoader engineLoader) {
        Class<?> ast;
        try {
            ast = Class.forName(AST_CLASS, false, engineLoader);
        } catch (ClassNotFoundException absent) {
            throw new IllegalStateException("no " + AST_CLASS + " in this engine band — the jars are "
                    + "not the JDT the band pins", absent);
        }

        int highest = 0;
        for (Field field : ast.getFields()) {
            if (!isUsableLevelField(field)) continue;
            int level = levelOf(field);
            if (level > highest) highest = level;
        }

        if (highest < MINIMUM) {
            throw new IllegalStateException("no usable AST.JLS* constant found (highest was " + highest
                    + ", minimum " + MINIMUM + ")");
        }
        return highest;
    }

    private static boolean isUsableLevelField(Field field) {
        int modifiers = field.getModifiers();
        if (!Modifier.isPublic(modifiers) || !Modifier.isStatic(modifiers) || !Modifier.isFinal(modifiers)) {
            return false;
        }
        if (field.getType() != int.class) return false;
        if (field.isAnnotationPresent(Deprecated.class)) return false;
        return field.getName().startsWith("JLS") && suffixIsDigits(field.getName());
    }

    private static boolean suffixIsDigits(String name) {
        String suffix = name.substring(3);
        if (suffix.isEmpty()) return false;
        for (int i = 0; i < suffix.length(); i++) {
            if (!Character.isDigit(suffix.charAt(i))) return false;
        }
        return true;
    }

    /**
     * The constant's VALUE, not the number in its name.
     *
     * <p>They agree from JLS8 onward and it would be tempting to parse the name — but the value is what
     * {@code newParser} is given, and a band where the two disagreed would be a band where we passed a
     * number JDT does not recognise. Reading the field costs one reflective get at startup.</p>
     */
    private static int levelOf(Field field) {
        try {
            return field.getInt(null);
        } catch (IllegalAccessException unreachable) {
            return 0;
        }
    }
}
