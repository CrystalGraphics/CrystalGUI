package com.crystalgui.language.java;

import com.crystalgui.language.engine.bridge.CodeActionContext;
import com.crystalgui.text.Change;
import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.lang.CodeAction;
import com.crystalgui.text.lang.CodeActionKind;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Everything a {@link Correction} is given, and the small number of things every one of them does.
 *
 * <p>Bundled because the alternative is the five-parameter helper signature this layer already grew once
 * — {@code (unit, source, documentLength, version, problem, …)} — where every new correction repeats the
 * list and every new need lengthens it. The parts that vary per correction are its arguments; the parts
 * that are the same for every correction in a request are here.</p>
 *
 * <p>It is created once per request and shared by every correction in it, which is safe for the reason
 * {@link Rewrites} records: a rewrite describes intent against a tree it never modifies, so the shared
 * {@link #unit()} cannot be disturbed by one candidate action while another is being computed.</p>
 */
final class FixContext {

    private final CompilationUnit unit;
    private final String source;
    private final long version;
    private final CodeActionContext host;
    private final int from;
    private final int to;

    FixContext(CompilationUnit unit, String source, long version, CodeActionContext host,
               int from, int to) {
        this.unit = unit;
        this.source = source;
        this.version = version;
        this.host = host == null ? CodeActionContext.NONE : host;
        this.from = from;
        this.to = to;
    }

    CompilationUnit unit() {
        return unit;
    }

    String source() {
        return source;
    }

    /** Where the actions were asked for — the caret, or a selection. What an intention decides from. */
    int from() {
        return from;
    }

    int to() {
        return to;
    }

    /** What the host knows and a correction cannot work out for itself. */
    CodeActionContext host() {
        return host;
    }

    // ── Building an action ──────────────────────────────────────────────────────────────────────

    /** A fix that edits the document, stamped with the version its offsets address. */
    CodeAction fix(String id, String title, ChangeSet edit) {
        return CodeAction.fix(id, title, edit, version);
    }

    /** As {@link #fix}, and marked as the one the popup shows without being asked. */
    CodeAction preferredFix(String id, String title, ChangeSet edit) {
        return CodeAction.preferredFix(id, title, edit, version);
    }

    /** An action of some other kind — a whole-file tidy rather than a fix for the problem at hand. */
    CodeAction action(String id, String title, CodeActionKind kind, ChangeSet edit) {
        return new CodeAction(id, title, kind, edit, null, false, version);
    }

    // ── Building an edit ────────────────────────────────────────────────────────────────────────

    /** A rewriter over this unit's tree. @see Rewrites */
    ASTRewrite rewrite() {
        return Rewrites.on(unit);
    }

    /** What {@code rewrite} amounts to, or null if it cannot be expressed. @see Rewrites#toChangeSet */
    ChangeSet changesFrom(ASTRewrite rewrite) {
        return Rewrites.toChangeSet(rewrite, unit, source);
    }

    /**
     * A fresh plan for the type names <em>one action</em> is about to write.
     *
     * <p>Per action, never shared: two candidate fixes for the same problem import different things, and a
     * plan that had seen both would put the second candidate's import into the first one's edit.</p>
     */
    ImportPlan importPlan() {
        return new ImportPlan(unit, source);
    }

    /**
     * {@code rewrite}'s changes and {@code imports}' insertions as one edit, or null if the rewrite
     * cannot be expressed.
     *
     * <p>The two halves come from different mechanisms — the body from JDT's rewriter, the import
     * region from our own arithmetic, for the reasons on {@link ImportRegion} — and meet only here.
     * They cannot overlap: an import insertion sits above the first type declaration and a rewrite of the
     * body sits inside one, so the merge is a sort and {@code ChangeSet.of} keeps checking that.</p>
     */
    ChangeSet changesFrom(ASTRewrite rewrite, ImportPlan imports) {
        ChangeSet body = Rewrites.toChangeSet(rewrite, unit, source);
        if (body == null) return null;
        List<Change> extra = imports.changes();
        if (extra.isEmpty()) return body;
        List<Change> all = new ArrayList<>(body.changes());
        all.addAll(extra);
        all.sort(Comparator.comparingInt(Change::from));
        return ChangeSet.of(source.length(), all);
    }

    /** One direct text change — for the import region, which the rewriter is not used on. */
    ChangeSet changeSet(Change change) {
        return ChangeSet.of(source.length(), change);
    }

    /** Several, which must already be sorted and non-overlapping. @see ChangeSet#of */
    ChangeSet changeSet(List<Change> changes) {
        return ChangeSet.of(source.length(), changes);
    }

    // ── Finding the node ────────────────────────────────────────────────────────────────────────

    /**
     * The nearest enclosing node of {@code type} covering {@code problem}, or null.
     *
     * <p>Stops at a {@link BodyDeclaration}: without that an unused local would walk up to the method
     * containing it and offer to delete <em>that</em> instead, which is a fix that compiles and destroys
     * work. The field carve-out is because a {@code FieldDeclaration} is itself a body declaration and is
     * a legitimate target.</p>
     */
    <T extends ASTNode> T enclosing(IProblem problem, Class<T> type) {
        int start = problem.getSourceStart();
        int length = Math.max(0, problem.getSourceEnd() + 1 - start);
        if (start < 0) return null;
        ASTNode node = NodeFinder.perform(unit, start, length);
        while (node != null && !type.isInstance(node)) {
            if (node instanceof BodyDeclaration && !type.isInstance(node)) {
                if (type == FieldDeclaration.class && node instanceof FieldDeclaration) break;
                if (!(node instanceof FieldDeclaration)) return null;
            }
            node = node.getParent();
        }
        return type.isInstance(node) ? type.cast(node) : null;
    }

    /**
     * The name {@code problem} is about, from its own arguments.
     *
     * <p><b>Not a substitute for reading a declared name off the tree.</b> These are the pieces ECJ
     * assembled its message from and their order is per-problem — {@code UnusedPrivateField} leads with
     * the declaring <em>type</em>, which once titled a fix "Remove field 'Script'" for a field called
     * {@code count}. Use it where the problem is <em>about</em> a name that is not declared anywhere in
     * this file, which is the unresolved-type case, and read the tree everywhere else.</p>
     */
    String reportedName(IProblem problem) {
        String[] arguments = problem.getArguments();
        return arguments == null || arguments.length == 0 ? null : arguments[0];
    }
}
