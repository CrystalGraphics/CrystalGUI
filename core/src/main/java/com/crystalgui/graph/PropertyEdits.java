package com.crystalgui.graph;

import com.crystalgui.core.undo.Edit;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Undoable changes to a graph's declared properties.
 *
 * <h3>Four edits in one file, unlike {@link SetNodeFieldEdit}</h3>
 * <p>They are one feature and are meaningless apart: nothing adds a property without being able to
 * remove it, and every one of them is four lines. Splitting them into four files would spread one
 * mechanism across four places to look, which is the cost {@code com.crystalgui.text.cursor} pays
 * deliberately for algorithms worth reading alone and this does not.</p>
 *
 * <h3>Position is recorded, not recomputed</h3>
 * <p>{@link Remove} keeps the index it was at, because undoing a delete has to put the property
 * <b>back where it was</b>. Appending instead would silently reorder the generated {@code Properties}
 * block — a diff in the shader for an operation the user believes was a no-op.</p>
 */
public final class PropertyEdits {

    private PropertyEdits() {
    }

    /** Declares a new property. */
    public record Add(GraphDocument document, GraphProperty property, int index) implements Edit {

        public static Add of(GraphDocument document, GraphProperty property) {
            return new Add(document, property, document.propertyCount());
        }

        @Override
        public void apply() {
            if (document.property(property.id()) == null) document.addProperty(property, index);
        }

        @Override
        public void undo() {
            document.removeProperty(property.id());
        }

        @Override
        public String label() {
            return "add property " + property.name();
        }
    }

    /**
     * Removes a property.
     *
     * <p><b>Does not touch the nodes referencing it.</b> A property node whose property is gone becomes
     * an error node rather than disappearing, which is the same call {@code GraphDocument} makes for a
     * node of an unknown type — deleting the declaration is not a statement about the graph's shape, and
     * silently removing wired nodes would destroy connections the user never asked to lose. Undoing the
     * removal makes every one of them whole again with no further bookkeeping.</p>
     */
    public record Remove(GraphDocument document, GraphProperty property, int index) implements Edit {

        @Nullable
        public static Remove of(GraphDocument document, String propertyId) {
            GraphProperty held = document.property(propertyId);
            if (held == null) return null;
            return new Remove(document, held, document.indexOfProperty(propertyId));
        }

        @Override
        public void apply() {
            document.removeProperty(property.id());
        }

        @Override
        public void undo() {
            if (document.property(property.id()) == null) document.addProperty(property, index);
        }

        @Override
        public String label() {
            return "remove property " + property.name();
        }
    }

    /**
     * Replaces a property with an edited copy — a rename, a retype, a new default.
     *
     * <p>One edit type for every field rather than one per field, because they all reduce to the same
     * operation: swap the record, keep the position. {@link #mergeWith} joins consecutive changes to
     * the <b>same property</b>, so typing a name is one undo step and not one per keystroke — the same
     * rule {@link SetNodeFieldEdit} follows, and the reason the merge compares ids rather than values.</p>
     */
    public record Change(GraphDocument document, String propertyId,
                         GraphProperty before, GraphProperty after) implements Edit {

        @Nullable
        public static Change of(GraphDocument document, GraphProperty edited) {
            GraphProperty current = document.property(edited.id());
            if (current == null) return null;
            return new Change(document, edited.id(), current, edited);
        }

        @Override
        public void apply() {
            document.replaceProperty(after);
        }

        @Override
        public void undo() {
            document.replaceProperty(before);
        }

        public boolean changesAnything() {
            return !Objects.equals(before, after);
        }

        @Override
        @Nullable
        public Edit mergeWith(Edit next) {
            if (!(next instanceof Change later) || !propertyId.equals(later.propertyId)) return null;
            // This edit's before and the later one's after, so undoing the run lands where it started.
            return new Change(document, propertyId, before, later.after);
        }

        @Override
        public String label() {
            return "edit property " + after.name();
        }
    }

    /** Reorders the Blackboard. */
    public record Move(GraphDocument document, String propertyId, int from, int to) implements Edit {

        @Nullable
        public static Move of(GraphDocument document, String propertyId, int to) {
            int from = document.indexOfProperty(propertyId);
            if (from < 0 || from == to) return null;
            return new Move(document, propertyId, from, to);
        }

        @Override
        public void apply() {
            document.moveProperty(propertyId, to);
        }

        @Override
        public void undo() {
            document.moveProperty(propertyId, from);
        }

        @Override
        public String label() {
            return "reorder properties";
        }
    }
}
