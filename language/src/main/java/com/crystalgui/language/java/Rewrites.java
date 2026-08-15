package com.crystalgui.language.java;

import com.crystalgui.text.Change;
import com.crystalgui.text.ChangeSet;

import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.DeleteEdit;
import org.eclipse.text.edits.InsertEdit;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.text.edits.TextEdit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The one seam between JDT's rewriter and this codebase's {@link ChangeSet} — the substrate every
 * correction is written on.
 *
 * <h3>Why a rewriter rather than hand-computed ranges</h3>
 *
 * <p>A quick fix is a text edit, and the obvious implementation is to take a node's offsets and delete or
 * splice them. That works for the first fix and degrades from there: a list element needs its comma
 * absorbed, a removed line needs its terminator taken with it or the file fills with blanks, generated
 * code needs the surrounding indentation, and each of those becomes a helper that the <em>next</em> fix
 * copies slightly differently. JDT already owns all of it, and JDT-LS — the one reference built on this
 * same compiler — writes every correction this way, down to "remove unused import".</p>
 *
 * <h3>What was measured before committing to it</h3>
 *
 * <p>{@code ASTRewrite} is normally driven from the Java <em>model</em> ({@code ICompilationUnit}), which
 * this engine deliberately does not have — units here are parsed from a {@code char[]} and
 * {@code getJavaElement()} is null. Four things were probed against the band-8 jars before this class
 * existed, because the answers decide the design rather than merely confirming it:</p>
 *
 * <ol>
 *   <li><b>The rewrite works with no Java model.</b> {@code rewriteAST(IDocument, Map)} takes the document
 *       explicitly and never asks for the type root — it is the no-arg {@code rewriteAST()} that requires
 *       one. This is the whole reason the substrate is available at all.</li>
 *   <li><b>Removal is clean.</b> Dropping an import yields two adjacent {@code DeleteEdit}s — the
 *       declaration and its line terminator — so no empty line is stranded, which is the behaviour the
 *       hand-rolled version had to special-case.</li>
 *   <li><b>Indentation of generated code comes from the options map</b>, honoured for multi-line output.
 *       Irrelevant while every correction is a removal; load-bearing the moment one generates a
 *       statement. @see #formattingOptions</li>
 *   <li><b>{@code ImportRewrite} is unusable</b> — it throws
 *       {@code IllegalArgumentException: AST must have been constructed from a Java element}. That is why
 *       {@code JavaQuickFixes} still handles the import region itself.</li>
 * </ol>
 *
 * <h3>The import region is not routed through here, in either direction</h3>
 *
 * <p>Not inertia, and not a fix that was never got round to. JDT does not intend the general rewriter to
 * be used on imports at all — {@code ImportRewrite} exists for them and is the API above that we cannot
 * reach. Driving the general one there is wrong in two independently measured ways:</p>
 *
 * <ul>
 *   <li><b>Inserting.</b> A {@code ListRewrite} on {@code IMPORTS_PROPERTY} places an import correctly in
 *       a file with a package declaration and produces
 *       {@code import java.util.List;public class Script { }} — no separator, plus two spurious leading
 *       blank lines — in a file without one.</li>
 *   <li><b>Removing.</b> A list's elements are removed with the separators <em>between</em> them, so
 *       emptying a list that nothing precedes leaves the trailing terminator. Removing the only import of
 *       a package-less file yields {@code \npublic class Script { }} — a blank first line. Identical
 *       through {@code remove} and through {@code ListRewrite}, so it is not the API choice.</li>
 * </ul>
 *
 * <p>Both land on the same shape — <b>a file with no package declaration</b>, which is what a script
 * normally is. So the import region keeps its own arithmetic and everything else is described here, which
 * is one boundary rather than a decision per correction.</p>
 *
 * <h3>One unit backs many independent rewrites</h3>
 *
 * <p>{@code ASTRewrite} is <em>descriptive</em>: it records intent against a tree it never modifies. That
 * is what makes it usable here at all, because one request computes every action for a range from a single
 * shared {@code CompilationUnit} — a rewriter that mutated the tree would have each candidate fix
 * corrupting the next. Verified rather than assumed: two rewrites taken off one unit produce two
 * independent results.</p>
 */
final class Rewrites {

    private Rewrites() {
    }

    /** A rewriter over {@code unit}'s tree. Records intent; never modifies the tree. */
    static ASTRewrite on(CompilationUnit unit) {
        return ASTRewrite.create(unit.getAST());
    }

    /**
     * Runs {@code rewrite} against {@code source} and converts the result into a {@link ChangeSet}.
     *
     * <p>The conversion is a flatten and a sort, and nothing more, because the two models already agree
     * on the thing that matters: a {@code TextEdit}'s offsets address the <b>original</b> document, which
     * is exactly {@link Change}'s contract. So no rebasing is needed and none is done — the sort exists
     * only because the edit tree is not required to be in document order.</p>
     *
     * <p><b>Null on failure rather than a thrown exception.</b> One correction that cannot express itself
     * must not take the whole list with it: a request computes every action for a range at once, so a
     * throw here would turn one bad fix into no fixes at all. The caller skips a null the same way it
     * skips a node it could not find.</p>
     */
    static ChangeSet toChangeSet(ASTRewrite rewrite, CompilationUnit unit, String source) {
        try {
            TextEdit edit = rewrite.rewriteAST(new Document(source), formattingOptions(unit));
            List<Change> changes = new ArrayList<>();
            collect(edit, changes);
            if (changes.isEmpty()) return null;
            changes.sort(Comparator.comparingInt(Change::from));
            // ChangeSet.of re-checks sorted-and-non-overlapping and throws if the flatten produced
            // something incoherent. That check is kept rather than bypassed: it is the same guarantee the
            // editor's apply path relies on, and a rewriter emitting overlapping edits is a bug worth
            // hearing about here rather than as mangled text later.
            return ChangeSet.of(source.length(), changes);
        } catch (RuntimeException cannotExpressIt) {
            return null;
        }
    }

    /**
     * Every leaf edit, in tree order.
     *
     * <p>Handled by type rather than by "has no children", because an empty {@link MultiTextEdit} — what a
     * rewrite with nothing recorded produces — is childless and is not a leaf. <b>An unrecognised leaf
     * throws</b> rather than being skipped: the copy and move edits exist, they carry text, and quietly
     * dropping one would produce a fix that applies cleanly and loses code.</p>
     */
    private static void collect(TextEdit edit, List<Change> out) {
        if (edit == null) return;
        if (edit instanceof InsertEdit) {
            InsertEdit insert = (InsertEdit) edit;
            out.add(new Change(insert.getOffset(), insert.getOffset(), insert.getText()));
        } else if (edit instanceof ReplaceEdit) {
            ReplaceEdit replace = (ReplaceEdit) edit;
            out.add(new Change(replace.getOffset(), replace.getExclusiveEnd(), replace.getText()));
        } else if (edit instanceof DeleteEdit) {
            DeleteEdit delete = (DeleteEdit) edit;
            out.add(new Change(delete.getOffset(), delete.getExclusiveEnd(), ""));
        } else if (edit instanceof MultiTextEdit) {
            for (TextEdit child : edit.getChildren()) collect(child, out);
        } else {
            throw new IllegalStateException(
                    "unhandled TextEdit " + edit.getClass().getName() + " — it may carry text, and "
                            + "skipping it would silently drop part of a fix");
        }
    }

    /**
     * What the rewriter formats generated code with.
     *
     * <p>Passed explicitly rather than left null, which would send JDT to {@code JavaCore.getOptions()} —
     * a workspace preference lookup, in a process that has no workspace.</p>
     *
     * <p>The compliance comes from the tree's own API level so that generated nodes are rendered for the
     * language the file was parsed as. The indent is <b>spaces, four</b>, which is this codebase's own and
     * is a placeholder in exactly one sense: it is the editor's setting that should decide, and that
     * arrives with the host-side context described in {@code plan_quickfix_catalog.md} §14-A. Until a
     * correction generates a line, no output is reachable from these two keys at all.</p>
     */
    private static Map<String, String> formattingOptions(CompilationUnit unit) {
        // JLS2/3/4 are api levels that are not feature versions; anything this engine parses is >= 8.
        int level = unit == null ? 8 : Math.max(8, unit.getAST().apiLevel());
        Map<String, String> options = EcjOptions.forLevel(level);
        options.put("org.eclipse.jdt.core.formatter.tabulation.char", "space");
        options.put("org.eclipse.jdt.core.formatter.tabulation.size", "4");
        return options;
    }
}
