package com.crystalgui.app.machine.ui;

import java.util.List;

import com.crystalgui.app.machine.MachineModel;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.protocol.FsMessages;
import com.crystalgui.net.window.ClientScope;
import com.crystalgui.net.window.Networked;
import com.crystalgui.net.window.RowSource;
import com.crystalgui.net.window.ServerScope;
import com.crystalgui.net.window.UiType;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.text.UIText;

/**
 * <b>Step 3 — three collections, three shapes, side by side.</b>
 *
 * <p>The question this panel exists to answer is which shape a list wants, because they are not
 * interchangeable and the wrong one is expensive in a way that only shows up at scale:</p>
 *
 * <ul>
 *   <li><b>The inventory</b> is a <em>streamed</em> collection the server holds: two hundred slots, of
 *       which a viewer sees a window. Each row carries a real {@code Button} that reports like any
 *       other, which is the difference between this and a display list — a row is an ordinary
 *       described subtree, not a picture of one.</li>
 *   <li><b>The log</b> is a streamed collection a viewer <em>follows</em>: a window that reaches the
 *       last row receives appends without asking. Without that, every line is a round trip to discover
 *       that the line after it exists too.</li>
 *   <li><b>The files</b> are read straight from the filesystem through {@link ClientScope#workspace()}
 *       and shown in a list this client built for itself. Nothing about them crosses the UI mirror —
 *       the workspace already has watches, etags, chunked reads and a permission model, and shipping a
 *       directory as described elements would be a second, worse copy of all of it.</li>
 * </ul>
 *
 * <h3>The rule the three of them are</h3>
 *
 * <p>Stream what the server <b>owns and the client cannot get any other way</b>. Read through the
 * workspace what is <b>already served over a protocol of its own</b>. Describe outright only what is
 * small and fixed — which is every other panel in this example.</p>
 */
public final class StreamsPanel extends UIElement implements Networked<MachineModel> {

    public static final Name NAME = Name.of("streamspanel");

    public static final UiType<StreamsPanel, MachineModel> TYPE =
            UiType.of("crystalgui:machine-streams", StreamsPanel::new);

    /** Where the inventory's rows land. A plain container: the rows ARE the collection. */
    public UIElement inventory = new UIElement();

    /** Where the log's rows land. Followed, so appends arrive unasked. */
    public UIElement log = new UIElement();

    /** Where the file list lands. Built by the CLIENT from the fs protocol; nothing here travels. */
    public UIElement files = new UIElement();

    public UIText inventoryTitle = new UIText("Inventory");
    public UIText logTitle = new UIText("Log");
    public UIText filesTitle = new UIText("Workspace");

    public StreamsPanel() {
        super(NAME);
    }

    @Override
    public void build(MachineModel model) {
        addClass(MachineStyles.STREAMS_CLASS);
        append(column(inventoryTitle, inventory));
        append(column(logTitle, log));
        append(column(filesTitle, files));
    }

    private static UIElement column(UIText title, UIElement body) {
        UIElement column = new UIElement();
        column.addClass(MachineStyles.STREAM_COLUMN_CLASS);
        title.addClass(MachineStyles.TITLE_CLASS);
        column.append(title);
        column.append(body);
        return column;
    }

    @Override
    public void serve(MachineModel model, ServerScope io) {
        /*
         * ONE STREAM PER COLLECTION, and the row template is the whole of what has to be written.
         *
         * The scope keeps a window per viewer, realises the union of them as described children, and
         * re-reads the source each run -- so a slot emptied by one viewer reaches the other because the
         * source answers differently, not because anything pushed.
         */
        io.stream(inventory, new RowSource<MachineModel.Slot>() {
            @Override
            public int count() {
                return model.slotCount();
            }

            @Override
            public List<MachineModel.Slot> rows(int from, int to) {
                return model.slots(from, to);
            }

            @Override
            public Object keyOf(MachineModel.Slot slot) {
                // THE SLOT'S INDEX, never its position in the window. A slot that empties keeps its
                // place and keeps its element, which is what stops a take rebuilding the whole page.
                return slot.index();
            }
        }, slot -> slotRow(io, model), StreamsPanel::writeSlot);

        io.stream(log, new RowSource<String>() {
            @Override
            public int count() {
                return model.logSize();
            }

            @Override
            public List<String> rows(int from, int to) {
                return model.log(from, to);
            }

            @Override
            public Object keyOf(String line) {
                // A LOG LINE'S IDENTITY IS ITS POSITION, which is the one collection where that is
                // true: nothing is ever inserted above a line, so an index cannot go stale.
                return line;
            }
        }, line -> new UIText(line), (row, line) -> ((UIText) row).setText(line));
    }

    /**
     * One inventory row: a label and a Take button, wired here because the row is built here.
     *
     * <p>The button is an ordinary reporting widget in a streamed row, which is the property worth
     * demonstrating: the row is a described subtree rather than a rendering of one, so everything that
     * works on a described widget works here with nothing said about streams.</p>
     */
    private UIElement slotRow(ServerScope io, MachineModel model) {
        UIElement row = new UIElement();
        row.addClass(MachineStyles.SLOT_CLASS);
        UIText text = new UIText("");
        text.addClass(MachineStyles.LABEL_CLASS);
        Button take = new Button("Take");
        take.addClass(MachineStyles.TAKE_CLASS);
        row.append(text);
        row.append(take);
        io.on(take, Button.ACTIVATE, context -> takeFrom(model, row));
        return row;
    }

    /** Set on a row so its button knows which slot it is looking at, without capturing an index. */
    private static final String SLOT_ID = "slot";

    private static void writeSlot(UIElement row, MachineModel.Slot slot) {
        row.setId(SLOT_ID + slot.index());
        UIText text = (UIText) row.children().get(0);
        text.setText(slot.index() + "  " + slot.item() + (slot.count() == 0 ? "" : " x" + slot.count()));
        Button take = (Button) row.children().get(1);
        take.setEnabled(slot.count() > 0);
    }

    /**
     * Empties the slot this ROW is currently showing.
     *
     * <p>Read off the element, never captured. A row's element is reused for whatever slot the window
     * puts there, so a handler that closed over an index would take from whichever slot the element was
     * first used for — the pooled-gutter-arrow trap, one layer up.</p>
     */
    private static void takeFrom(MachineModel model, UIElement row) {
        String id = row.getId();
        if (!id.startsWith(SLOT_ID)) return;
        try {
            model.take(Integer.parseInt(id.substring(SLOT_ID.length())));
        } catch (NumberFormatException notASlot) {
            // A row whose id is not a slot is a row nothing has written yet; ignoring it is right.
        }
    }

    @Override
    public void client(ClientScope io) {
        /*
         * THE FILE LIST, READ THROUGH THE WORKSPACE.
         *
         * Not through the mirror, and the difference is the whole point of this column: the workspace
         * is a protocol of its own with watches, etags and a permission model, and re-shipping a
         * directory listing as described elements would be a second copy of all of it that could
         * disagree with the first.
         *
         * The rows are LOCAL, so they are never described, never numbered and never counted -- the
         * server has no idea this column exists.
         */
        io.workspace().projects()
                .onError(failure -> addFileRow(io, "no workspace here: " + failure.code()))
                .then(projects -> {
                    if (projects.isEmpty()) {
                        addFileRow(io, "no projects on this server");
                        return;
                    }
                    Resource root = Resource.of(CgPath.ofProject(projects.get(0).id()));
                    io.workspace().files().list(root)
                            .onError(failure -> addFileRow(io, "could not read: " + failure.code()))
                            .then(listing -> {
                                for (FsMessages.Entry entry : listing.entries()) {
                                    addFileRow(io, entry.name());
                                }
                                if (listing.entries().isEmpty()) addFileRow(io, "(empty)");
                            });
                });
    }

    private void addFileRow(ClientScope io, String text) {
        UIText row = new UIText(text);
        row.addClass(MachineStyles.LABEL_CLASS);
        io.addLocal(files, row);
    }

}
