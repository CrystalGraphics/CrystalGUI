package com.crystalgui.workbench;

import com.crystalgui.core.notify.StatusBar;
import com.crystalgui.core.notify.StatusBarAlignment;
import com.crystalgui.core.notify.StatusBarEntry;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Extracted from {@link Workbench}. See the plan's §4.5 for why this cluster is one thing.
 */
final class PresenceBinding {

    private final Workbench workbench;

    PresenceBinding(Workbench workbench) {
        this.workbench = workbench;
    }

    /**
     * Phase 5.6 — says who else has the active file open.
     *
     * <p>The data has existed since Phase 4 and nothing showed it: {@code fs.watch} is sent for every
     * file a client reads, so the server has always known. What was missing was a view <em>across</em>
     * peers — a watcher belongs to one connection — and anywhere to put the answer.</p>
     *
     * <p><b>Removed rather than emptied when nobody is there.</b> A permanent "1 person" slot that
     * usually reads zero is a thing the eye learns to skip, which is the one failure a presence
     * indicator cannot afford. Same shape as the problem count above, and for the same reason.</p>
     */
    void refreshPresence() {
        CgPath active = workbench.activeFilePath();
        String editing = workbench.saveActions.othersEditing(active);
        String viewing = othersWithOpen(active);
        if (editing == null && viewing == null) {
            if (workbench.presenceEntry != null) workbench.presenceEntry.dispose();
            workbench.presenceEntry = null;
            return;
        }
        // EDITING LEADS, because it is the half that costs somebody their work. Who merely has it open
        // is the fuller picture and belongs in the tooltip -- which is what this entry's tooltip has
        // always CLAIMED to say ("also has this file open") while its text said who was editing.
        String name = editing != null ? "Editing" : "Viewing";
        String text = editing != null ? editing : viewing;
        StatusBarEntry entry = new StatusBarEntry(name, text, tooltipFor(editing, viewing),
                null, StatusBarEntry.Kind.STANDARD);
        if (workbench.presenceEntry == null) {
            workbench.presenceEntry = workbench.statusBar().addEntry(entry, "workbench.presence",
                    StatusBarAlignment.RIGHT, Workbench.PRESENCE_PRIORITY);
        } else {
            workbench.presenceEntry.update(entry);
        }
    }

    /** Both halves, each said only when there is somebody in it. @see #refreshPresence */
    private static String tooltipFor(@Nullable String editing, @Nullable String viewing) {
        if (editing == null) return viewing + " has this file open";
        if (viewing == null) return editing + " is editing this file";
        return editing + " is editing this file; " + viewing + " has it open";
    }

    /**
     * Who else has this file open, phrased for a human — the softer half of presence.
     *
     * <p>Worth saying on its own: somebody reading a file you are about to change is not a conflict and
     * is still worth knowing, and it is the only thing there is to say before anybody has typed. The
     * accessor behind it was written with the rest of presence and read by nothing, because nothing
     * ever sent a {@code fs/presence} notification for it to read.</p>
     */
    @Nullable
    private String othersWithOpen(@Nullable CgPath target) {
        if (target == null) return null;
        return phrase(workbench.workspace.presence().whoElseHasOpen(Resource.of(target)));
    }

    /** {@code alice}, {@code alice and bob}, {@code alice and 3 others}. */
    @Nullable
    static String phrase(List<String> people) {
        if (people.isEmpty()) return null;
        if (people.size() == 1) return people.get(0);
        if (people.size() == 2) return people.get(0) + " and " + people.get(1);
        return people.get(0) + " and " + (people.size() - 1) + " others";
    }

}
