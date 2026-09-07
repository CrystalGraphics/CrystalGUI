package com.crystalgui.widget.overlay;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.crystalgui.widget.control.Checkbox;

/**
 * <b>A group tick and its members, which fed each other.</b>
 *
 * <p>{@code Checkbox.setChecked} announces, so the master-sets-members and members-correct-master
 * listeners re-entered one another: a master ticking its first member fired that member's listener, which
 * recomputed the master from a list only partly written, which fired the master again. It showed as a
 * group that set some of its boxes and not others, and as one member clearing the whole group.</p>
 *
 * <p>Asserted on the boxes rather than through the window, because the fault is entirely in the wiring
 * and a dialog would only make it need a document to reproduce.</p>
 */
public class PasteAttributesGroupsTest {

    private final Checkbox master = new Checkbox("Transform");
    private final List<Checkbox> members =
            List.of(new Checkbox("transform"), new Checkbox("origin-x"), new Checkbox("origin-y"));

    private void wire() {
        master.setChecked(true);
        for (Checkbox member : members) member.setChecked(true);
        PasteAttributesDialog.wireGroups(Map.of(master, members));
    }

    /** The reported one: clearing the group cleared some of its members and left others. */
    @Test
    public void aMasterSetsEveryMember() {
        wire();
        master.setChecked(false);

        for (Checkbox member : members) {
            assertFalse(member.getLabel() + " was left behind", member.isChecked());
        }

        master.setChecked(true);
        for (Checkbox member : members) {
            assertTrue(member.getLabel() + " was not brought back", member.isChecked());
        }
    }

    /** The other one: unchecking a single property cleared the whole group. */
    @Test
    public void oneMemberClearsOnlyTheMaster() {
        wire();
        members.get(0).setChecked(false);

        assertFalse("the group is no longer all-on, so its tick has to go", master.isChecked());
        assertTrue("but its siblings are nobody else's business", members.get(1).isChecked());
        assertTrue(members.get(2).isChecked());
    }

    /** And the master comes back on its own once every member is ticked again. */
    @Test
    public void theMasterReturnsWhenTheLastMemberDoes() {
        wire();
        members.get(0).setChecked(false);
        assertFalse(master.isChecked());

        members.get(0).setChecked(true);
        assertTrue("all of them are on, so the group is", master.isChecked());
    }
}
