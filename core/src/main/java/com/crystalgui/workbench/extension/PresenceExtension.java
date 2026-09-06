package com.crystalgui.workbench.extension;

import javax.annotation.Nullable;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.notify.StatusBarAlignment;
import com.crystalgui.core.notify.StatusBarEntry;
import com.crystalgui.core.notify.StatusBarEntryAccessor;
import com.crystalgui.core.signal.ConnectionGroup;
import com.crystalgui.fs.CgPath;
import com.crystalgui.workbench.WorkbenchContext;

/**
 * <b>Who else is in this file</b> - one status-bar entry, on a shared workspace.
 *
 * <p>Enable it by naming {@link #ID} in an application's manifest. The server already knows who has each
 * file open and who is editing it; this is the view of it. On a single-player workspace it is silent by
 * construction, because there is nobody else to report.</p>
 *
 * <h3>Editing leads, viewing is the tooltip</h3>
 *
 * <p>Editing is the half that can cost somebody their work, so it is what the entry says. Who merely has
 * the file open is the fuller picture and belongs in the tooltip.</p>
 *
 * <h3>Removed rather than emptied</h3>
 *
 * <p>With nobody else in the file the entry is withdrawn, not left reading zero. A permanent slot that
 * usually says "nobody" is a slot the eye learns to skip, which is the one failure a presence indicator
 * cannot afford.</p>
 */
public final class PresenceExtension implements WorkbenchExtension {

    public static final String ID = "crystalgui:presence";

    /** Right-hand end, outside the caret readout: a fact about other people, not about this file. */
    public static final int PRIORITY = 50;

    /** {@code ServiceLoader} needs a public no-argument constructor. */
    public PresenceExtension() {
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Disposable activate(WorkbenchContext workbench) {
        Live live = new Live(workbench);
        live.bind();
        return live::close;
    }

    private static final class Live {

        private final WorkbenchContext workbench;
        private final ConnectionGroup lifetime = new ConnectionGroup();

        @Nullable
        private StatusBarEntryAccessor entry;

        Live(WorkbenchContext workbench) {
            this.workbench = workbench;
        }

        void bind() {
            lifetime.add(workbench.workspace().presence().onDidChange.connect(this::refresh));
            // AND WHEN THE FILE IN FRONT CHANGES, because the entry is about THIS file: presence itself
            // may not have moved at all.
            lifetime.add(workbench.onDidOpenDocument().connect(path -> refresh()));
            refresh();
        }

        void refresh() {
            CgPath active = workbench.activeFilePath();
            String editing = workbench.othersEditing(active);
            String viewing = workbench.othersViewing(active);
            if (editing == null && viewing == null) {
                if (entry != null) entry.dispose();
                entry = null;
                return;
            }
            String name = editing != null ? "Editing" : "Viewing";
            String text = editing != null ? editing : viewing;
            StatusBarEntry built = new StatusBarEntry(name, text, tooltipFor(editing, viewing),
                    null, StatusBarEntry.Kind.STANDARD);
            if (entry == null) {
                entry = workbench.statusBar().addEntry(built, "workbench.presence",
                        StatusBarAlignment.RIGHT, PRIORITY);
            } else {
                entry.update(built);
            }
        }

        /** Both halves, each said only when there is somebody in it. */
        private static String tooltipFor(@Nullable String editing, @Nullable String viewing) {
            if (editing == null) return viewing + " has this file open";
            if (viewing == null) return editing + " is editing this file";
            return editing + " is editing this file; " + viewing + " has it open";
        }

        void close() {
            lifetime.disconnectAll();
            if (entry != null) entry.dispose();
            entry = null;
        }
    }
}
