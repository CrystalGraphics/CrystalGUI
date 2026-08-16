package com.crystalgui.language.java;

import com.crystalgui.text.diagnostic.DiagnosticTag;

import org.eclipse.jdt.core.compiler.IProblem;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What this engine chooses to report, and how it should be drawn — the two halves of the same decision.
 *
 * <h3>Why the table exists at all</h3>
 *
 * <p>Most of ECJ's optional problems default to {@code ignore}, and a correction keyed on one of them is
 * invisible from its own code: the fix compiles, its unit test passes if the test builds the problem
 * itself, and the popup simply never offers it. That failure is silent in every direction, which is why
 * enabling a problem is written down in one place with the reason beside it rather than as a scattering
 * of {@code options.put} lines.</p>
 *
 * <h3>Severity and tag are one decision, so they live together</h3>
 *
 * <p>{@link DiagnosticTag}'s own javadoc makes the argument: severity answers "how much should this worry
 * you" and a tag answers "what does the text look like now". Unused code is not a <em>lesser</em> warning,
 * it is a different kind of statement — so it keeps a warning's severity and is drawn faded rather than
 * underlined, which is what IntelliJ and VS Code both do and what lets them afford to report all of it.
 * Deciding one without the other is how a file ends up with nine squiggles that all mean "delete me".</p>
 *
 * <p>The two tables are keyed differently and that is not an inconsistency: an <b>option</b> is ECJ's unit
 * of configuration and covers a category ({@code unusedPrivateMember} is one switch for fields, methods,
 * constructors and nested types), while a <b>tag</b> is about one problem's meaning. Merging them would
 * mean either four switches where ECJ has one, or one tag where four are needed.</p>
 *
 * <h3>What is deliberately not enabled</h3>
 *
 * <p><b>{@code MissingOverrideAnnotation}.</b> An override is a <em>relationship</em>, not a defect, and
 * no reference implementation reports one as a diagnostic — IntelliJ draws a gutter marker on the
 * declaration and lets you navigate to what it overrides. Reporting it here would put a squiggle, a
 * Problems row and an error-stripe mark on every correctly written override in the file. The marker is a
 * real feature and is recorded in {@code plan_quickfix_catalog.md} §18.6; it belongs with the editor's
 * gutter parts and needs nothing from this file.</p>
 */
final class EcjProblemPolicy {

    private EcjProblemPolicy() {
    }

    /**
     * The options this engine sets, on top of ECJ's defaults.
     *
     * <p>Everything absent from here is left at JDT's own default, which for the {@code unused*} family
     * is already {@code warning} — so the eight problems the corrections in this package key on are
     * mostly reported with no entry at all. The table is short because it should be: each line is a mark
     * in somebody's file that ECJ had decided not to make.</p>
     */
    static Map<String, String> severities() {
        Map<String, String> options = new HashMap<>();

        // ALREADY THIS ENGINE'S ONE OPINION before the table existed. A script calling an API that is
        // going away is worth a mark, and SymbolModifier.DEPRECATED already has a drawing contract.
        options.put("org.eclipse.jdt.core.compiler.problem.deprecation", "warning");

        // A `;` on its own. Pure tidiness and the mildest thing here, which is why it is drawn faded
        // rather than underlined -- a stray semicolon is dead weight, not a defect.
        options.put("org.eclipse.jdt.core.compiler.problem.emptyStatement", "warning");

        // `new FileWriter(f);` with the result discarded. NOT tidiness: an allocation nobody keeps is
        // almost always a forgotten assignment, so this one is a genuine defect and stays underlined.
        // Deliberately has no quick fix -- see the class note in UnusedCorrections for why offering to
        // delete the line would be offering to discard the evidence.
        options.put("org.eclipse.jdt.core.compiler.problem.unusedObjectAllocation", "warning");

        // THREE MORE KINDS OF DEAD WEIGHT, each with a list-element correction behind it. All three are
        // reported by IntelliJ's default inspection profile, which is the bar this table uses for
        // "opinion nobody will resent". ECJ's own sub-options stay at their defaults, and two of them
        // matter: an exception named in the Javadoc is not "unused", and Exception/Throwable are exempt
        // -- a `throws Exception` on a script's main method is a convention, not a mistake.
        options.put("org.eclipse.jdt.core.compiler.problem.unusedDeclaredThrownException", "warning");
        options.put("org.eclipse.jdt.core.compiler.problem.redundantSuperinterface", "warning");
        options.put("org.eclipse.jdt.core.compiler.problem.unusedTypeParameter", "warning");

        // ONE OPTION, TWO PROBLEMS: UnnecessaryCast and UnnecessaryInstanceof both hang off
        // `unnecessaryTypeCheck`, so they are enabled together whether or not that was intended.
        //
        // MEASURED BEFORE ENABLING, because the obvious objection to a cast fix is that a cast can be
        // load-bearing for OVERLOAD RESOLUTION -- `take((Object) s)` picks take(Object) over take(String),
        // and removing it silently calls a different method. ECJ does not report that cast: with both
        // overloads present the warning disappears entirely. So a cast this engine offers to remove can
        // never be one that decides which method runs.
        options.put("org.eclipse.jdt.core.compiler.problem.unnecessaryTypeCheck", "warning");

        // A null check the flow analysis proves cannot fail. ALSO MEASURED, and it is far narrower than
        // it sounds: a defensive `if (s != null)` on a parameter is NOT reported, because ECJ knows
        // nothing about what a caller passes. What fires is a local it has actually tracked -- one just
        // assigned `new Object()`, or one that is definitely null -- which is a real mistake rather than
        // a habit. IntelliJ reports the same set.
        options.put("org.eclipse.jdt.core.compiler.problem.redundantNullCheck", "warning");

        // NOT unusedExceptionParameter. It fires on every `catch (Exception e)` that ignores `e`, which
        // in a script is most of them, and IntelliJ's own unused-declaration inspection excludes catch
        // parameters by default for the same reason. The rename-to-`ignored` correction the catalogue
        // lists therefore has no diagnostic to hang off, and is not written.

        // SWITCHED OFF, and it is the only thing here turned DOWN from ECJ's default.
        //
        // "The serializable class X does not declare a static final serialVersionUID field of type long"
        // is about the binary compatibility of SERIALIZED INSTANCES ACROSS BUILDS: without an explicit
        // stamp the compiler derives one from the class's exact shape, so adding a field later changes it
        // and streams written by the old build fail to load. That is a real concern for code that
        // serializes; it is not one for a script.
        //
        // And it cannot be avoided by not asking for it: Throwable implements Serializable, so EVERY
        // custom exception a script declares is flagged. The remedy is a magic constant nobody reads,
        // which means the warning's only achievable outcome is boilerplate added to silence it -- and a
        // diagnostic whose fix is "write this line you do not understand" teaches nothing and costs a line
        // of every exception class in the codebase.
        //
        // Left reportable rather than deleted: a project that does serialize turns it back on here, which
        // is one entry rather than a rebuild.
        options.put("org.eclipse.jdt.core.compiler.problem.missingSerialVersion", "ignore");

        // AND `@SuppressWarnings` STOPS REPORTING ON THIS COMPILER'S CONFIGURATION.
        //
        // Writing @SuppressWarnings("unused") produced an INFO diagnostic reading "At least one of the
        // problems in category 'unused' is not analysed due to a compiler option being ignored" -- on the
        // annotation, with a squiggle and a Problems row. It is true, and it is about US: the 'unused'
        // category includes `unusedParameter` and `unusedExceptionParameter`, which are left at ECJ's
        // `ignore` deliberately, so the category is not fully analysed and ECJ says so. Nothing the author
        // of a script can do about it, on one of the most common annotations there is.
        //
        // THE TRADE, MEASURED. This option also carries UnusedWarningToken -- "unnecessary
        // @SuppressWarnings" -- so silencing one silences the other. That costs less than it looks: while
        // any sub-option of a category is ignored ECJ reports ProblemNotAnalysed *instead of*
        // UnusedWarningToken, because it cannot know whether the suppression was needed. So for the
        // "unused" token, the warning being given up is one we could never have produced. Recovering it
        // would mean enabling `unusedParameter`, which flags every ignored parameter of every override.
        options.put("org.eclipse.jdt.core.compiler.problem.unusedWarningToken", "ignore");

        return options;
    }

    /**
     * How a problem is drawn, beyond its severity — {@code null} where it is drawn as usual.
     *
     * <p>Membership here is a judgement about what the mark <em>means</em>, and the line is between dead
     * weight and a defect. "This is never used" tells you to delete something; "you discarded this
     * object" tells you something is wrong. The first fades, the second keeps its underline.</p>
     */
    static Set<DiagnosticTag> tagsFor(int problemId) {
        Set<DiagnosticTag> tags = TAGS.get(problemId);
        return tags == null ? Collections.emptySet() : tags;
    }

    private static final Map<Integer, Set<DiagnosticTag>> TAGS = buildTags();

    private static Map<Integer, Set<DiagnosticTag>> buildTags() {
        Map<Integer, Set<DiagnosticTag>> tags = new HashMap<>();
        Set<DiagnosticTag> unnecessary = Collections.singleton(DiagnosticTag.UNNECESSARY);
        Set<DiagnosticTag> deprecated = Collections.singleton(DiagnosticTag.DEPRECATED);

        // DEAD WEIGHT -- faded. Everything whose message amounts to "nothing reads this".
        for (int problem : new int[] {
                IProblem.UnusedImport,
                IProblem.LocalVariableIsNeverUsed,
                IProblem.UnusedPrivateField,
                IProblem.UnusedPrivateMethod,
                IProblem.UnusedPrivateConstructor,
                IProblem.UnusedPrivateType,
                IProblem.UnusedLabel,
                IProblem.UnusedTypeParameter,
                IProblem.UnusedMethodDeclaredThrownException,
                IProblem.UnusedConstructorDeclaredThrownException,
                IProblem.RedundantSuperinterface,
                // Unreachable rather than unused, and the same statement about the text: nothing runs
                // this. VS Code fades unreachable code for exactly this reason.
                IProblem.DeadCode,
                // A `;` that parses to nothing at all -- the purest case in the table.
                IProblem.SuperfluousSemicolon}) {
            tags.put(problem, unnecessary);
        }

        // STILL WORKS, SHOULD NOT BE USED -- struck through. Reported since before this table existed,
        // and drawn as an ordinary warning the whole time because nothing produced tags.
        for (int problem : new int[] {
                IProblem.UsingDeprecatedType,
                IProblem.UsingDeprecatedField,
                IProblem.UsingDeprecatedMethod,
                IProblem.UsingDeprecatedConstructor,
                IProblem.OverridingDeprecatedMethod}) {
            tags.put(problem, deprecated);
        }

        // NOT TAGGED, and each is a decision rather than an oversight:
        //   UnusedObjectAllocation  -- a discarded `new` is a bug, not dead weight. Fading it would say
        //                              "delete this", and the fix is nearly always to assign it.
        //   AssignmentHasNoEffect   -- `n = n` reads as dead weight and is usually a typo for
        //                              `this.n = n`, which is a defect worth underlining.
        return Collections.unmodifiableMap(withImmutableValues(tags));
    }

    private static Map<Integer, Set<DiagnosticTag>> withImmutableValues(
            Map<Integer, Set<DiagnosticTag>> tags) {
        Map<Integer, Set<DiagnosticTag>> copy = new HashMap<>();
        for (Map.Entry<Integer, Set<DiagnosticTag>> entry : tags.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableSet(new HashSet<>(entry.getValue())));
        }
        return copy;
    }
}
