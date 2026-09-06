package com.crystalgui.widget.control;

import com.crystalgui.core.signal.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Radio-like exclusivity over a set of ordinary {@link Checkbox}es — <b>not</b> a node.
 *
 * <p>It listens to each member's {@link Checkbox#onCheckedChanged} and unchecks whichever one was
 * checked before, so a checkbox never learns that it is in a group. Layered rather than built as a
 * distinct widget because the alternative — a container node that owns and restricts its children —
 * is a pattern this codebase does not otherwise have, and inventing one for a radio group would mean
 * every consumer accepting a container where it wanted a layout of its own.</p>
 *
 * <p>Deliberately not ported to a {@code UIElement}: it has no box, paints nothing, and describing it
 * would put a node on the wire that exists only to hold a rule about two others.</p>
 */
public final class CheckboxGroup {

    /**
     * The members, each mapped to the connection this group made to it.
     *
     * <p>A map rather than a list because {@link #unregister} has to be able to <b>disconnect</b>,
     * and it could not: the old version dropped the checkbox from a list and left its listener
     * attached, so a member that left and rejoined got a second listener and was unchecked twice per
     * change. Unreachable then — nothing called {@code unregister}, {@code setGroup(null)} only
     * forgot the field — and reachable the moment {@link Checkbox#setGroup} started leaving properly.
     * Insertion-ordered so {@link #getMembers} answers in the order they joined, which is the order a
     * caller built them in.</p>
     */
    private final Map<Checkbox, Connection> members = new LinkedHashMap<>();
    @Nullable
    private Checkbox current;
    private boolean allowEmpty = true;

    /**
     * Whether the group may end up with nothing checked (the default).
     *
     * <p>{@code false} makes it a true radio group: unchecking the current member is refused rather
     * than allowed, which is what every radio group on every platform does.</p>
     */
    public CheckboxGroup allowEmpty(boolean value) {
        this.allowEmpty = value;
        return this;
    }

    public void register(Checkbox checkbox) {
        if (members.containsKey(checkbox)) return;
        if (checkbox.isChecked()) {
            if (current != null && current != checkbox) current.setChecked(false);
            current = checkbox;
        }
        members.put(checkbox, checkbox.onCheckedChanged.connect(
                isChecked -> onMemberChanged(checkbox, isChecked)));
    }

    /** Removes {@code checkbox} and disconnects this group's listener from it. */
    public void unregister(Checkbox checkbox) {
        Connection connection = members.remove(checkbox);
        if (connection != null) connection.disconnect();
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

    /** The checked member, or null. */
    @Nullable
    public Checkbox getCurrent() {
        return current;
    }

    /** The members, in the order they joined. */
    public List<Checkbox> getMembers() {
        return new ArrayList<>(members.keySet());
    }
}
