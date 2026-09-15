package com.crystalgui.app.uibuilder.document;

import java.util.List;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.crystalgui.core.undo.Edit;
import com.crystalgui.net.mirror.DocumentExtras;
import com.crystalgui.serialization.JsonOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.serialization.style.InlineStyleCodec;
import com.crystalgui.ui.contract.State;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.WidgetContracts;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.ClassNames;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UINode;

/**
 * Every change a builder makes to a document, as data with an inverse.
 *
 * <p>Nothing edits the tree directly: {@link UiBuilderDocument} applies one of these, which is what puts
 * every change in one undo history and what makes "undo returns the file byte for byte" checkable.</p>
 *
 * <pre>{@code
 * document.apply(new BuilderEdit.SetId(node, "title"));
 * document.apply(new BuilderEdit.Insert(parent, child, 0));
 * }</pre>
 *
 * <p>Each carries <b>both</b> values rather than a closure, so undo is the same code as apply with the
 * arguments swapped, and a record's own {@code equals} says whether two edits are the same change.</p>
 *
 * <p>The value edits merge with a later edit of the same field, keeping the first {@code from}: a scrub in
 * the inspector is one step. They merge only while a gesture holds the run — the document's history has no
 * merge window. Structural edits and {@code SetClasses} never merge.</p>
 */
public sealed interface BuilderEdit extends Edit {

    /**
     * The node this edit is about, or null for one that is not about a node.
     *
     * <p>Every variant but {@code SetHeader} has a {@code node} component, so the record's own accessor
     * satisfies this and only the exception says anything. What reads it is the canvas, which selects
     * what an undo just changed — a reversal you cannot see is indistinguishable from a key that did
     * nothing.</p>
     */
    @Nullable
    default UIElement node() {
        return null;
    }

    /** Adds a node at an index. */
    record Insert(UIElement parent, UIElement node, int index) implements BuilderEdit {

        @Override
        public void apply() {
            parent.insertAt(index, node);
        }

        @Override
        public void undo() {
            parent.remove(node);
        }

        @Override
        public String label() {
            return "insert <" + node.tagName() + ">";
        }
    }

    /** Takes a node out, remembering where it was so undo puts it back there. */
    record Remove(UIElement parent, UIElement node, int index) implements BuilderEdit {

        @Override
        public void apply() {
            parent.remove(node);
        }

        @Override
        public void undo() {
            parent.insertAt(index, node);
        }

        @Override
        public String label() {
            return "delete <" + node.tagName() + ">";
        }
    }

    /** Reparents or reorders. One edit, never a remove and an insert — those lose the node. */
    record Move(UIElement node, UIElement fromParent, int fromIndex, UIElement toParent, int toIndex)
            implements BuilderEdit {

        @Override
        public void apply() {
            place(toParent, toIndex);
        }

        @Override
        public void undo() {
            place(fromParent, fromIndex);
        }

        private void place(UIElement parent, int index) {
            UINode current = node.parent();
            if (current instanceof UIElement holder) holder.remove(node);
            parent.insertAt(index, node);
        }

        @Override
        public String label() {
            return "move <" + node.tagName() + ">";
        }
    }

    record SetId(UIElement node, String from, String to) implements BuilderEdit {

        @Override
        public void apply() {
            node.setId(to);
        }

        @Override
        public void undo() {
            node.setId(from);
        }

        @Override
        public String label() {
            return "set id";
        }

        @Override
        public Edit mergeWith(Edit next) {
            return next instanceof SetId later && later.node == node ? new SetId(node, from, later.to) : null;
        }
    }

    /**
     * The whole AUTHORED class list, because a set is invertible and a diff of one is not worth the arithmetic.
     *
     * <p>Engine classes are left as they are in both directions: undo restoring one the widget has since
     * dropped would be the edit fighting the widget.</p>
     */
    record SetClasses(UIElement node, List<String> from, List<String> to) implements BuilderEdit {

        public SetClasses {
            from = ClassNames.authored(from);
            to = ClassNames.authored(to);
        }

        @Override
        public void apply() {
            write(to);
        }

        @Override
        public void undo() {
            write(from);
        }

        private void write(List<String> wanted) {
            for (String present : ClassNames.authored(node.classes())) node.removeClass(present);
            for (String each : wanted) node.addClass(each);
        }

        @Override
        public String label() {
            return "set classes";
        }
    }

    /** One state slot, encoded — the form the contract reads, so no widget setter is named here. */
    record SetState(UIElement node, String key, @Nullable JsonElement from, @Nullable JsonElement to)
            implements BuilderEdit {

        @Override
        public void apply() {
            write(to);
        }

        @Override
        public void undo() {
            write(from);
        }

        /**
         * THE ONE SLOT, never the contract's whole read: that applies every slot and gives an absent one its
         * fallback, so setting a slider's value reset its range to the fallback's and clamped the value to it.
         */
        private void write(@Nullable JsonElement value) {
            WidgetContract<UIElement> contract = WidgetContracts.of(node);
            if (contract == null || value == null) return;
            JsonObject one = new JsonObject();
            one.add(key, value);
            StateMap<JsonElement> map = new StateMap<>(JsonOps.INSTANCE, one);
            for (State<UIElement, ?> state : contract.states()) {
                if (state.key().equals(key)) state.apply(node, map);
            }
        }

        @Override
        public String label() {
            return "set " + key;
        }

        @Override
        public Edit mergeWith(Edit next) {
            return next instanceof SetState later && later.node == node && later.key.equals(key)
                    ? new SetState(node, key, from, later.to) : null;
        }
    }

    record SetAttribute<T>(UIElement node, Attribute<T> key, T from, T to) implements BuilderEdit {

        @Override
        public void apply() {
            node.set(key, to);
        }

        @Override
        public void undo() {
            node.set(key, from);
        }

        @Override
        public String label() {
            return "set " + key.name();
        }

        @Override
        @SuppressWarnings("unchecked")
        public Edit mergeWith(Edit next) {
            return next instanceof SetAttribute<?> later && later.node == node && later.key == key
                    ? new SetAttribute<>(node, key, from, (T) later.to) : null;
        }
    }

    /** The node's whole inline style, as the codec writes it. */
    record SetInlineStyle(UIElement node, @Nullable JsonElement from, @Nullable JsonElement to)
            implements BuilderEdit {

        @Override
        public void apply() {
            write(to);
        }

        @Override
        public void undo() {
            write(from);
        }

        /**
         * REPLACE, never merge.
         *
         * <p>Both directions of this edit are "the inline style was exactly this", so a merge leaves
         * behind whatever the other direction added — which is undo appearing to do nothing whenever the
         * edit's visible half was a property the node did not have before.</p>
         */
        private void write(@Nullable JsonElement value) {
            InlineStyleCodec.replaceInto(JsonOps.INSTANCE,
                    value == null ? new JsonObject() : value, node);
        }

        @Override
        public String label() {
            return "set style";
        }

        @Override
        public Edit mergeWith(Edit next) {
            return next instanceof SetInlineStyle later && later.node == node
                    ? new SetInlineStyle(node, from, later.to) : null;
        }
    }

    /** A design value, a binding or a hook — document data, which is why it goes in the side table. */
    record SetExtra(DocumentExtras<JsonElement> extras, UIElement node, String key,
            @Nullable JsonElement from, @Nullable JsonElement to) implements BuilderEdit {

        @Override
        public void apply() {
            extras.put(node, key, to);
        }

        @Override
        public void undo() {
            extras.put(node, key, from);
        }

        @Override
        public String label() {
            return "set " + key;
        }
    }

    /** A header key — the sheets a document names, its model class, its preview settings. */
    record SetHeader(JsonObject header, String key, @Nullable JsonElement from, @Nullable JsonElement to)
            implements BuilderEdit {

        @Override
        public void apply() {
            write(to);
        }

        @Override
        public void undo() {
            write(from);
        }

        private void write(@Nullable JsonElement value) {
            if (value == null) header.remove(key);
            else header.add(key, value);
        }

        @Override
        public String label() {
            return "set " + key;
        }

        @Override
        public Edit mergeWith(Edit next) {
            return next instanceof SetHeader later && later.header == header && later.key.equals(key)
                    ? new SetHeader(header, key, from, later.to) : null;
        }
    }
}
