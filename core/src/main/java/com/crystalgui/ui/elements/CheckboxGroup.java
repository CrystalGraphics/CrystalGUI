package com.crystalgui.ui.elements;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain (non-{@code UIElement}) exclusivity coordinator for a set of {@link Checkbox}es — radio-like
 * behavior layered on top of ordinary checkboxes rather than a distinct widget type. Listens to each
 * member's {@link Checkbox#onCheckedChanged} to enforce single-selection; {@link Checkbox} itself
 * stays entirely group-agnostic.
 *
 * <p>Deliberately not a container {@code UIElement} — nothing else in this codebase establishes a
 * "container widget with restricted/auto-managed children" pattern yet, so a real
 * {@code CheckboxGroupElement} is left as a future addition rather than built speculatively here.</p>
 */
public final class CheckboxGroup {

    private final List<Checkbox> members = new ArrayList<>();
    private Checkbox current;
    private boolean allowEmpty = true;

    public CheckboxGroup allowEmpty(boolean value) {
        this.allowEmpty = value;
        return this;
    }

    public void register(Checkbox checkbox) {
        if (members.contains(checkbox)) return;
        members.add(checkbox);
        if (checkbox.isChecked()) {
            if (current != null && current != checkbox) current.setChecked(false);
            current = checkbox;
        }
        checkbox.attachListener(isChecked -> onMemberChanged(checkbox, isChecked));
    }

    public void unregister(Checkbox checkbox) {
        members.remove(checkbox);
        if (current == checkbox) current = null;
    }

    private void onMemberChanged(Checkbox source, boolean isChecked) {
        if (isChecked) {
            if (current != null && current != source) current.setChecked(false);
            current = source;
        } else if (source == current) {
            if (!allowEmpty) {
                source.setChecked(true); // refuse to leave the group with nothing checked
                return;
            }
            current = null;
        }
    }

    public Checkbox getCurrent() {
        return current;
    }
}
