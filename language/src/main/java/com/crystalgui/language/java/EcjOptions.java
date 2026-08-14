package com.crystalgui.language.java;

import org.eclipse.jdt.core.dom.AST;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * How this band's ECJ is configured — the language level it can reach, and the options that spell it.
 *
 * <h3>Why this is its own class rather than a method on whoever needs it</h3>
 *
 * <p>Two things here are asked by more than one caller and are wrong in ways nothing reports. The level
 * is read <em>reflectively</em>, and a copy that drifted would cap a band at a language it can actually
 * parse — which fails by silently refusing modern syntax rather than by throwing. The level's <em>name</em>
 * is spelled two ways (Java 8 is {@code "1.8"}, everything after is its own number), and a copy that got
 * that wrong would configure a parser that reports errors on valid source and says nothing about why.</p>
 *
 * <p>{@link com.crystalgui.language.engine.JlsLevel} is the host-side statement of the same rule and is
 * deliberately <b>not</b> called from here: it lives on the other side of the bridge and this package is
 * loaded by the engine's child loader, so reaching it would either give the child its own copy anyway or
 * widen the shared surface for a static {@code int}. That duplication is a considered one and is
 * documented at both ends. What is <em>not</em> considered is a third copy inside this package, which is
 * what this class exists to prevent — {@code EcjSourceAnalyzer} and {@link AttachedSources} both parse,
 * and they had begun to answer the question separately.</p>
 */
final class EcjOptions {

    private EcjOptions() {
    }

    /**
     * The newest level this band's JDT offers.
     *
     * <p>Read reflectively rather than named, for the reason {@code JlsLevel} sets out at length: an
     * adapter compiled against the oldest band cannot name {@code JLS21}, because the field is not there
     * — and naming {@code JLS8} instead compiles against every band and silently caps the newest at Java
     * 8 syntax, which is worse, because it works.</p>
     */
    static int jlsLevel() {
        int highest = 0;
        for (Field field : AST.class.getFields()) {
            String name = field.getName();
            if (!name.startsWith("JLS") || field.getType() != int.class) continue;
            if (field.isAnnotationPresent(Deprecated.class)) continue;
            if (!name.substring(3).chars().allMatch(Character::isDigit)) continue;
            try {
                highest = Math.max(highest, field.getInt(null));
            } catch (IllegalAccessException unreachable) {
                // A public static final int that cannot be read does not happen; skipping is right.
            }
        }
        if (highest == 0) throw new IllegalStateException("no AST.JLS* constant in this band");
        return highest;
    }

    /**
     * How a feature version is <b>spelled</b> — {@code "1.8"} for 8, {@code "21"} for 21.
     *
     * <p>Java's own two-era naming, and it has to be right in three places at once: the DOM parser's
     * options, the attached-source parser's options, and the batch compiler's {@code -source}/{@code
     * -target} flags. A copy that got it wrong configures a compiler that rejects valid source and says
     * nothing about why, so the rule is stated once.</p>
     */
    static String levelName(int featureVersion) {
        return featureVersion <= 8 ? "1." + featureVersion : Integer.toString(featureVersion);
    }

    /**
     * Source, compliance and target, all set to one feature version.
     *
     * <p>All three, because setting fewer leaves JDT to reconcile them against its own defaults and it
     * reports the disagreement as errors in the user's file rather than as a configuration fault.</p>
     */
    static Map<String, String> forLevel(int featureVersion) {
        String level = levelName(featureVersion);
        Map<String, String> options = new HashMap<>();
        options.put("org.eclipse.jdt.core.compiler.source", level);
        options.put("org.eclipse.jdt.core.compiler.compliance", level);
        options.put("org.eclipse.jdt.core.compiler.codegen.targetPlatform", level);
        return options;
    }
}
