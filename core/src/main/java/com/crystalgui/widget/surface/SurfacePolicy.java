package com.crystalgui.widget.surface;

import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.core.undo.Edit;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.ui.dom.UIElement;

/**
 * <b>What a consumer supplies at construction</b>, and the one seam that lets a single engine drive a
 * node graph and a laid-out UI tree. Three questions, and nothing else.
 *
 * <pre>{@code
 * new SurfaceEditor(new SurfacePolicy() {
 *     public UIElement itemFor(UIElement hit) { return GraphNode.enclosing(hit); }
 *     public PressOwner ownerOf(UIElement hit) {
 *         return hit instanceof PortDefaultEditor ? PressOwner.TREE : PressOwner.SURFACE;
 *     }
 *     public void markSelected(UIElement item, boolean on) { ((GraphNode) item).setSelected(on); }
 *     public Edit moveEdit(List<Move> moves) { return new MoveNodesEdit(document, moves); }
 * }, enabledExtensionIds);
 * }</pre>
 *
 * <p>Every gesture the engine has asks these instead of knowing the answer, so a tool written for one
 * consumer works on the other. {@link #ownerOf} is the one that decides whether an editor <em>inside</em>
 * an item still takes input: a graph answers {@code TREE} for a port's value field, a builder answers
 * {@code SURFACE} for everything inside a live artboard.</p>
 */
public interface SurfacePolicy {

    /** One item's move within a gesture, in world units. */
    record Move(UIElement item, float fromX, float fromY, float toX, float toY) {
    }

    /** Who a press belongs to. @see #ownerOf */
    enum PressOwner {
        /** The engine's — selection, marquee, move, the current tool. */
        SURFACE,
        /** The widget under the pointer, dispatched normally. */
        TREE
    }

    /**
     * Where this surface's undo history lives, or <b>null</b> for one of the surface's own.
     *
     * <p>A document owns its history — two panes onto one file share it — so a consumer with a document
     * answers that. One without says null and the surface keeps a stack nobody else can reach.</p>
     *
     * <p>Asked once, while the surface is being built, so it must not read anything the consumer has not
     * initialised yet. That is why declining is spelled as null rather than as a field.</p>
     */
    @Nullable
    default UndoStack history() {
        return null;
    }

    /**
     * The item {@code hit} belongs to — usually an ancestor walk — or null when it belongs to none.
     *
     * <p>What "an item" is: the thing selection, move and delete operate on. A graph answers the
     * enclosing node; a builder answers the described element inside an artboard.</p>
     */
    @Nullable
    UIElement itemFor(@Nullable UIElement hit);

    /**
     * A chance to correct what the box tree found under the pointer.
     *
     * <p>The default returns it unchanged. A consumer whose chrome is drawn on something other than the
     * painted box has to correct it here, or the mouse selects one rectangle while the outline marks
     * another.</p>
     *
     * <p><b>A REFINEMENT, not a replacement.</b> The hit tells you what the pointer is over — a handle, a
     * toolbar, an overlay — and answering the question from scratch loses all of it: the surface's own
     * chrome stops taking presses, because a walk of the edited content never reaches anything that is
     * not edited content.</p>
     *
     * <pre>{@code
     * public UIElement pickAt(UIElement painted, float rawX, float rawY) {
     *     if (!isMine(painted)) return painted;                 // chrome answers for itself
     *     return deepestWhoseLayoutBoxContains(rawX, rawY);
     * }
     * }</pre>
     */
    @Nullable
    default UIElement pickAt(@Nullable UIElement painted, float rawX, float rawY) {
        return painted;
    }

    /** Whether the engine handles a press on {@code hit}, or the tree under it does. */
    PressOwner ownerOf(UIElement hit);

    /**
     * Shows or clears an item's selected state.
     *
     * <p>The selection model is the engine's; how it <em>reads</em> is not — a graph node draws a ring
     * from a pseudo-class, a described element gets handles. Called once per item that changed.</p>
     */
    void markSelected(UIElement item, boolean selected);

    /**
     * The undoable record of removing {@code items}, or null when this surface deletes nothing.
     *
     * <p>What deleting <em>means</em> is the consumer's: a graph takes the nodes and the wires that
     * touched them; a tree takes the subtree. The engine only knows that Delete acts on the selection.</p>
     */
    @Nullable
    default Edit deleteEdit(List<UIElement> items) {
        return null;
    }

    /**
     * The undoable record of a completed move, or null when moves are not undoable here.
     *
     * <p>Called once at the end of a drag, however many items moved, so the whole gesture is one undo
     * step. The engine has already applied the move — this only records it.</p>
     */
    @Nullable
    Edit moveEdit(List<Move> moves);

    /**
     * Whether dragging an item MOVES it on the plane.
     *
     * <p>True for a graph, where a node's position is its own and the plane is where it lives. False for
     * anything holding a laid-out TREE: a node inside a UI document is placed by its parent, so dragging
     * it means reorder or reparent — a different gesture entirely — and there is no coordinate to write.
     * </p>
     *
     * <p>Answering false is not a nicety. The move gesture marks what it is moving with a class while the
     * drag runs, and a class on a node of a document is <b>encoded into the file</b>: dragging a text
     * node left {@code __moving__} on it, dirtied the tab, and would have saved it.</p>
     */
    default boolean movesItems() {
        return true;
    }
}
