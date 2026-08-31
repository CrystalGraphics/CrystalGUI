package com.crystalgui.ui.elements.config.control;

import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.config.ConfigControl;
import com.crystalgui.ui.elements.config.ConfigControls;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.ui.elements.config.ValueControl;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * A repeated value: a header, a body of rows, and {@code +} / {@code −} at the bottom right.
 *
 * <p>Unity reference: the {@code Entries} list in
 * {@code docs/research/unity-inspector/03-inspector-keyword-enum.png}, the {@code Active Targets} list
 * in {@code 07-full-window.png}, and the empty-state placeholder in {@code 04-inspector-custom-function.png}.</p>
 *
 * <h3>Built directly, NOT on {@code ListView} — a deliberate change from the plan</h3>
 * <p>{@code ListView} is virtualised: it realises only the visible rows and <b>recycles</b> them
 * through {@code bind(item, index, template)}. That is exactly right for ten thousand log lines and
 * exactly wrong here, for two reasons that compound:</p>
 * <ul>
 *   <li>A config array is short — a keyword's entries, a node's ports. Virtualisation buys nothing and
 *       costs the ability to let the list size itself to its contents.</li>
 *   <li><b>A recycled row carries live controls with listeners.</b> A listener may only be attached
 *       once, so it must read its row index at call time rather than capturing it — and a control that
 *       captured its index would edit whatever entry its slot was last used for. That is the pooled
 *       gutter-arrow trap from {@code AGENTS.md}, and it keeps working right up until someone
 *       scrolls.</li>
 * </ul>
 * <p>A plain column has neither problem. If a genuinely long config array ever turns up, that is the
 * moment to reach for {@code ListView} — not before.</p>
 *
 * <h3>An empty list shows a placeholder rather than collapsing</h3>
 * <p>Unity's {@code List is Empty} row. A list that collapsed to nothing when emptied would leave the
 * {@code +} button floating with no indication of what it adds to, and would make the difference
 * between "no entries" and "no list" invisible.</p>
 */
public class ArrayControl extends ValueControl<List<Object>> {

    public static final String HEAD_CLASS = "__head__";
    public static final String BODY_CLASS = "__body__";
    public static final String FOOT_CLASS = "__foot__";
    public static final String ENTRY_CLASS = "__entry__";
    public static final String EMPTY_CLASS = "__empty__";

    private final UIElement body = new UIElement();
    private final UIElement foot = new UIElement();
    private final ConfigDescriptor element;
    private final List<Object> values = new ArrayList<>();

    public ArrayControl(ConfigDescriptor descriptor, @Nullable List<Object> defaultValue) {
        super(descriptor, defaultValue == null ? List.of() : List.copyOf(defaultValue));
        this.element = descriptor.element() == null
                ? ConfigDescriptor.text(descriptor.id() + ".entry", "")
                : descriptor.element();
        addClass("__array__");
        markAsInternal();

        UIElement head = new UIElement();
        head.addClass(HEAD_CLASS);
        UIText title = new UIText(descriptor.label());
        title.setHitTest(false);
        head.addChild(title);

        body.addClass(BODY_CLASS);

        Button add = new Button("+");
        add.addClass("__add__");
        add.attachListener(() -> {
            values.add(null);
            rebuild();
            commit(List.copyOf(values));
        });
        Button remove = new Button("-");
        remove.addClass("__remove__");
        remove.attachListener(() -> {
            if (values.isEmpty()) return;
            values.remove(values.size() - 1);
            rebuild();
            commit(List.copyOf(values));
        });
        foot.addClass(FOOT_CLASS);
        foot.addChild(add);
        foot.addChild(remove);

        addInternalChild(head);
        addInternalChild(body);
        addInternalChild(foot);

        if (defaultValue != null) values.addAll(defaultValue);
        rebuild();
    }

    /** Self-labelling: the header carries the name, so a row must not add a second one. */
    @Override
    public boolean selfLabelling() {
        return true;
    }

    @Override
    protected void writeToWidgets(@Nullable List<Object> value) {
        values.clear();
        if (value != null) values.addAll(value);
        rebuild();
    }

    /**
     * Rebuilds every entry row.
     *
     * <p>Wholesale rather than incrementally, and the reason is the same one that kept this off
     * {@code ListView}: an entry's control owns a listener bound to its index, so a row that survived a
     * removal would go on editing the slot it used to be. Rebuilding is O(n) on a list that is short by
     * construction, and it cannot be wrong.</p>
     *
     * <p><b>Never called from inside an entry's own change handler</b> — only from add, remove and a
     * programmatic write. Rebuilding under a live edit would detach the control being typed into, which
     * is the widget-rebuild trap {@code AGENTS.md} records against the table header.</p>
     */
    private void rebuild() {
        body.clearAllChildren();
        if (values.isEmpty()) {
            UIText empty = new UIText("List is Empty");
            empty.addClass(EMPTY_CLASS);
            empty.setHitTest(false);
            body.addChild(empty);
            return;
        }
        for (int i = 0; i < values.size(); i++) {
            final int index = i;
            ConfigControl control = ConfigControls.create(element, values.get(i));
            if (control == null) continue;
            control.changed.connect(v -> {
                // Read the index from the capture, which is safe ONLY because this row is discarded
                // rather than recycled — see rebuild()'s note.
                if (index < values.size()) {
                    values.set(index, v);
                    commit(List.copyOf(values));
                }
            });
            UIElement entry = new UIElement();
            entry.addClass(ENTRY_CLASS);
            entry.addChild(control);
            body.addChild(entry);
        }
    }

    public int size() {
        return values.size();
    }
}
