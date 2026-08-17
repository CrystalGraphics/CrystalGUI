package com.crystalgui.language.java.fix.edit;

import com.crystalgui.language.java.EcjOptions;
import com.crystalgui.text.Change;
import com.crystalgui.text.ChangeSet;

import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.CopySourceEdit;
import org.eclipse.text.edits.CopyTargetEdit;
import org.eclipse.text.edits.DeleteEdit;
import org.eclipse.text.edits.ISourceModifier;
import org.eclipse.text.edits.InsertEdit;
import org.eclipse.text.edits.MoveSourceEdit;
import org.eclipse.text.edits.MoveTargetEdit;
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
 *       statement. @see Indent#detect</li>
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
            TextEdit edit = rewrite.rewriteAST(new Document(source), formattingOptions(unit, source));
            List<Change> changes = new ArrayList<>();
            collect(edit, source, changes);
            if (changes.isEmpty()) return null;
            // BY START, THEN BY END. An insertion at an offset that a deletion also starts at must come
            // first -- ChangeSet.of requires each change to begin at or after the previous one ENDS, and
            // a zero-width insert sorted after a delete at the same offset would begin inside it. Wrapping
            // a statement is exactly that shape: the `try {` goes in where the statement was, and the
            // statement's old text goes out from the same offset.
            changes.sort(Comparator.comparingInt(Change::from).thenComparingInt(Change::to));
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
     * throws</b> rather than being skipped: an edit that carries text and is quietly dropped would produce
     * a fix that applies cleanly and loses code — which is how the move pair below was found.</p>
     *
     * <h3>Moves and copies</h3>
     *
     * <p>{@code createMoveTarget} — wrapping a statement in a {@code try}, lifting an initialiser into an
     * assignment — comes out as a {@link MoveSourceEdit} where the text was and a {@link MoveTargetEdit}
     * where it goes. The source becomes a deletion. The target becomes an insertion of the moved text, and
     * that text is <em>not</em> the original substring: the source edit's own children are edits inside
     * it, and its {@link ISourceModifier} is how JDT re-indents code that moved to a different nesting
     * level. Both are applied to the substring here; neither is recursed into by the walk, or the inner
     * edits would land twice. Copies are the same with no deletion.</p>
     */
    private static void collect(TextEdit edit, String source, List<Change> out) {
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
        } else if (edit instanceof MoveSourceEdit) {
            out.add(new Change(edit.getOffset(), edit.getExclusiveEnd(), ""));
        } else if (edit instanceof MoveTargetEdit) {
            MoveSourceEdit from = ((MoveTargetEdit) edit).getSourceEdit();
            out.add(new Change(edit.getOffset(), edit.getOffset(),
                    movedText(from, from.getSourceModifier(), source)));
        } else if (edit instanceof CopySourceEdit) {
            // The text stays; only the target does anything.
        } else if (edit instanceof CopyTargetEdit) {
            CopySourceEdit from = ((CopyTargetEdit) edit).getSourceEdit();
            out.add(new Change(edit.getOffset(), edit.getOffset(),
                    movedText(from, from.getSourceModifier(), source)));
        } else if (edit instanceof MultiTextEdit) {
            for (TextEdit child : edit.getChildren()) collect(child, source, out);
        } else {
            throw new IllegalStateException(
                    "unhandled TextEdit " + edit.getClass().getName() + " — it may carry text, and "
                            + "skipping it would silently drop part of a fix");
        }
    }

    /**
     * The text a source edit contributes at its target: its substring, with its own child edits and then
     * its modifier's re-indentation applied.
     *
     * <p>Children are absolute offsets and are applied back to front so earlier ones stay valid; the
     * modifier's edits are relative to the text it is handed and are applied the same way. A child that
     * is itself a move or a copy is refused — nested relocation is a shape nothing here produces, and
     * guessing at it would be the silent-loss failure this class exists to prevent.</p>
     */
    private static String movedText(TextEdit from, ISourceModifier modifier, String source) {
        int base = from.getOffset();
        StringBuilder text = new StringBuilder(source.substring(base, from.getExclusiveEnd()));
        List<TextEdit> children = new ArrayList<>(List.of(from.getChildren()));
        children.sort(Comparator.comparingInt(TextEdit::getOffset).reversed());
        for (TextEdit child : children) {
            int start = child.getOffset() - base;
            if (child instanceof InsertEdit) {
                text.insert(start, ((InsertEdit) child).getText());
            } else if (child instanceof ReplaceEdit) {
                text.replace(start, start + child.getLength(), ((ReplaceEdit) child).getText());
            } else if (child instanceof DeleteEdit) {
                text.delete(start, start + child.getLength());
            } else {
                throw new IllegalStateException("nested " + child.getClass().getSimpleName() + " inside a moved region");
            }
        }
        if (modifier != null) {
            String moved = text.toString();
            List<ReplaceEdit> reindent = new ArrayList<>(List.of(modifier.getModifications(moved)));
            reindent.sort(Comparator.comparingInt(TextEdit::getOffset).reversed());
            for (ReplaceEdit each : reindent) {
                text.replace(each.getOffset(), each.getExclusiveEnd(), each.getText());
            }
        }
        return text.toString();
    }

    /**
     * What the rewriter formats generated code with.
     *
     * <p>Passed explicitly rather than left null, which would send JDT to {@code JavaCore.getOptions()} —
     * a workspace preference lookup, in a process that has no workspace.</p>
     *
     * <p>The compliance comes from the tree's own API level so that generated nodes are rendered for the
     * language the file was parsed as. The indent comes from {@link Indent#detect} — <b>the file's own</b>.
     * It used to be four spaces with a note promising that the editor's setting would arrive with a
     * host-side context; it did not, and meanwhile every correction that generates a line put spaces into
     * a tab-indented script. The document is the answer that needs no seam, and is what both references
     * fall back to.</p>
     */
    private static Map<String, String> formattingOptions(CompilationUnit unit, String source) {
        // JLS2/3/4 are api levels that are not feature versions; anything this engine parses is >= 8.
        int level = unit == null ? 8 : Math.max(8, unit.getAST().apiLevel());
        Map<String, String> options = EcjOptions.forLevel(level);
        Indent.Style style = Indent.detect(source);
        options.put("org.eclipse.jdt.core.formatter.tabulation.char", style.tabs() ? "tab" : "space");
        options.put("org.eclipse.jdt.core.formatter.tabulation.size", Integer.toString(style.size()));
        return options;
    }
}
