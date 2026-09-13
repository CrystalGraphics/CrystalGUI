package com.crystalgui.app.uibuilder.panel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgui.app.uibuilder.canvas.BuilderContext;
import com.crystalgui.app.uibuilder.document.BuilderEdit;
import com.crystalgui.app.uibuilder.document.NodeIds;
import com.crystalgui.app.uibuilder.document.TreeDropRules;
import com.crystalgui.app.uibuilder.document.TreeMoves;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.collection.tree.TreeEditModel;

/**
 * What dragging, pasting, duplicating, deleting and renaming do to a <b>document's nodes</b> — the Hierarchy
 * panel's half of {@code TreeEditing}.
 *
 * <p>Ordered: a node lands before, into or after another, by GrapesJS's index arithmetic in
 * {@link TreeMoves}. Every verb is one undo step on the document, and what it lands becomes the selection.
 * The name edited is the node's id.</p>
 *
 * <ul>
 *   <li>A node from another document — cut there, pasted here — is copied, never moved: a move would take
 *       it out of that document behind its own history. Its design values stay with that document.</li>
 *   <li>Alt makes a drop a copy, as in every design tool; Ctrl is the row toggle.</li>
 * </ul>
 */
final class HierarchyEditModel implements TreeEditModel<UIElement> {

    private final BuilderContext builder;

    HierarchyEditModel(BuilderContext builder) {
        this.builder = builder;
    }

    private UiBuilderDocument document() {
        return builder.getDocument();
    }

    private UIElement root() {
        return document().root();
    }

    @Override
    public boolean isOrdered() {
        return true;
    }

    /** Null for the root, whatever the canvas has put it inside. */
    @Nullable
    @Override
    public UIElement parentOf(UIElement node) {
        return node == root() ? null : node.parentElement();
    }

    @Override
    public int indexOf(UIElement node) {
        UIElement parent = parentOf(node);
        return parent == null ? -1 : parent.indexOf(node);
    }

    @Override
    public boolean isContainer(UIElement node) {
        return TreeDropRules.isContainer(root(), node);
    }

    @Override
    public boolean canEdit(UIElement node) {
        return TreeDropRules.isSource(root(), node);
    }

    @Override
    public boolean canDrop(List<UIElement> nodes, UIElement parent) {
        return TreeDropRules.canMove(root(), nodes, parent);
    }

    @Override
    public String nameOf(UIElement node) {
        return node.id();
    }

    @Override
    public String labelOf(UIElement node) {
        return HierarchyPanel.describe(node);
    }

    @Override
    public String noun() {
        return "element";
    }

    @Override
    public int copyModifier() {
        return CgModifiers.ALT;
    }

    /** A copy instead when any node is not in this document — cut from another, or deleted since the cut. */
    @Override
    public void move(List<UIElement> nodes, Target<UIElement> to) {
        for (UIElement node : nodes) {
            if (!TreeDropRules.isInside(node, root())) {
                copy(nodes, to);
                return;
            }
        }
        document().applyAll("move", TreeMoves.move(to.parent(), indexIn(to), nodes));
        builder.builderSelection().replaceWith(TreeMoves.outermost(nodes));
    }

    @Override
    public void copy(List<UIElement> nodes, Target<UIElement> to) {
        List<BuilderEdit> edits = TreeMoves.duplicate(document(), to.parent(), indexIn(to), nodes);
        document().applyAll(nodes.size() == 1 ? "duplicate" : "duplicate " + nodes.size(), edits);
        List<UIElement> copies = new ArrayList<>(edits.size());
        for (BuilderEdit edit : edits) {
            if (edit instanceof BuilderEdit.Insert insert) copies.add(insert.node());
        }
        builder.builderSelection().replaceWith(copies);
    }

    /** Last child first within a parent, so every recorded index is still true when it is removed. */
    @Override
    public void delete(List<UIElement> nodes) {
        List<UIElement> doomed = TreeMoves.outermost(nodes);
        Collections.reverse(doomed);
        List<BuilderEdit> edits = new ArrayList<>(doomed.size());
        for (UIElement node : doomed) {
            UIElement parent = node.parentElement();
            if (parent != null) edits.add(new BuilderEdit.Remove(parent, node, parent.indexOf(node)));
        }
        builder.builderSelection().clear();
        document().applyAll("delete", edits);
    }

    @Override
    public void rename(UIElement node, String id) {
        document().apply(new BuilderEdit.SetId(node, node.id(), id));
    }

    /** Any id a selector can spell, and empty, which clears it. */
    @Override
    public boolean acceptsName(UIElement node, String id) {
        return NodeIds.isSpellable(id);
    }

    /** A shared id would style both nodes through one rule and make {@code #id} find either. */
    @Nullable
    @Override
    public String freeNameFor(UIElement node, String id) {
        if (id.isEmpty()) return null;
        Set<String> taken = NodeIds.taken(root());
        taken.remove(node.id());
        String free = NodeIds.free(id, taken);
        return free.equals(id) ? null : free;
    }

    /** After the selection in its parent, as the default says — and into the root with nothing selected. */
    @Nullable
    @Override
    public Target<UIElement> pasteTarget(List<UIElement> selection) {
        return selection.isEmpty() ? new Target<>(root(), -1) : TreeEditModel.super.pasteTarget(selection);
    }

    private static int indexIn(Target<UIElement> to) {
        return to.index() < 0 ? to.parent().children().size() : to.index();
    }
}
