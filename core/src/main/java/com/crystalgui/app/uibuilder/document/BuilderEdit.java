package com.crystalgui.app.uibuilder.document;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.crystalgui.core.undo.Edit;
import com.crystalgui.net.mirror.DocumentExtras;
import com.crystalgui.serialization.JsonOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.serialization.style.InlineStyleCodec;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.WidgetContracts;
import com.crystalgui.ui.dom.Attribute;
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
 */
public sealed interface BuilderEdit extends Edit {

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
    }

    /** The whole class list, because a set is invertible and a diff of one is not worth the arithmetic. */
    record SetClasses(UIElement node, List<String> from, List<String> to) implements BuilderEdit {

        public SetClasses {
            from = List.copyOf(from);
            to = List.copyOf(to);
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
            for (String present : new ArrayList<>(node.classes())) node.removeClass(present);
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

        private void write(@Nullable JsonElement value) {
            WidgetContract<UIElement> contract = WidgetContracts.of(node);
            if (contract == null || value == null) return;
            JsonObject one = new JsonObject();
            one.add(key, value);
            contract.read(node, new StateMap<>(JsonOps.INSTANCE, one));
        }

        @Override
        public String label() {
            return "set " + key;
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

        private void write(@Nullable JsonElement value) {
            InlineStyleCodec.decodeInto(JsonOps.INSTANCE,
                    value == null ? new JsonObject() : value, node);
        }

        @Override
        public String label() {
            return "set style";
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
    }
}
