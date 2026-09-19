package com.crystalgui.app.uibuilder.inspect;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.core.property.Property;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.ui.dom.UIElement;

/**
 * Where a gizmo's edits land: one declaration read, written and recorded — <b>wherever the target keeps it</b>.
 *
 * <pre>{@code
 * BoxModelEditor box = new BoxModelEditor(node, Declarations.inline(node, document));
 * StyleScrub.on(label, LayoutProperties.LEFT, Declarations.inline(node, document));
 * }</pre>
 *
 * <p>The box model and the scrub each carried the whole of it — the inline style, the codec that restores one, the
 * edit that records one — so two gizmos knew what a {@code BuilderEdit} is. They name a declaration now, and one
 * class answers with the element's own. <b>A second answer is a class, not a rewrite</b>: a diagram over a rule in
 * a sheet was built and dropped as a duplicate of the Layout tab's, and what it needed from here was nothing more
 * than an implementation.</p>
 *
 * <h3>A gesture is one step</h3>
 * <p>A drag writes every frame and is undone once. {@link #beginGesture} opens the run, every {@link #set} inside it
 * is live and unrecorded, and {@link #endGesture} either keeps what was written as one step or puts back what was
 * there. A write outside a gesture records itself.</p>
 */
public interface Declarations {

    /** The declaration as the target holds it, {@code ""} when it holds none. */
    String valueOf(StyleProperty<?> property);

    /**
     * The declaration as a bound property — read, written and recorded wherever the target keeps it, which is what
     * an inspector ROW binds to.
     */
    Property<String> value(StyleProperty<?> property);

    /**
     * Writes {@code css}, or clears the declaration for an empty string.
     *
     * @return whether it landed — false for a value the property's own parser will not read
     */
    boolean set(StyleProperty<?> property, String css);

    /** Whether the target itself declares it, as against inheriting or computing it. What a diagram draws bold. */
    boolean declares(StyleProperty<?> property);

    /** Whether anything written here lands at all: a live pick has nothing to record into, and a sheet may be read-only. */
    boolean canWrite();

    /** Where an edit here is undone: the document for an element, the sheet's own buffer for a rule. */
    @Nullable
    UndoStack history();

    /** Opens a gesture: everything written until it closes is one undo step. */
    void beginGesture();

    /** Closes it, keeping what was written as one step, or putting back what was there. */
    void endGesture(boolean keep);

    /** The node's own inline style, recorded in {@code document} — what the Element and Layout tabs edit. */
    static Declarations inline(UIElement node, @Nullable UiBuilderDocument document) {
        return new InlineDeclarations(node, document);
    }
}
