package com.crystalgui.app.frameprofiler;

import com.crystalgraphics.compute.source.CgElementField;
import com.crystalgraphics.render.graph.CgBufferInspector;
import com.crystalgui.core.property.ObservableList;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.collection.table.TableColumn;
import com.crystalgui.widget.collection.table.TableView;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.text.UIText;
import dev.vfyjxf.taffy.style.FlexDirection;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What a GPU buffer holds, field by field: every buffer a compute pass binds, read back on demand and decoded through
 * the kernel's own declaration of it ({@link CgBufferInspector}).
 *
 * <pre>{@code
 * BuffersTab tab = new BuffersTab();
 * tabs.addTab("Buffers").content().append(tab);   // watches from when it first shows until it leaves the document
 * }</pre>
 *
 * <ul>
 *   <li>Live, not the selected frame's: a read is served the next time its pass runs, and lands a frame or two later.
 *       <b>Follow</b> reads again at the profiler's refresh rate.</li>
 *   <li>Watching starts the first time the tab shows, and stops when the tab leaves the document if this tab started
 *       it: while it watches, every compute pass notes what it binds.</li>
 *   <li>A page is {@value #PAGE} elements; a value column sorts by its first number.</li>
 * </ul>
 */
public class BuffersTab extends UIElement {

    public static final Name NAME = Name.of("bufferstab");

    public static final String STATUS_CLASS = "__buffers-status__";
    public static final String SITES_CLASS = "__buffers-sites__";
    public static final String ELEMENTS_CLASS = "__buffers-elements__";

    public static final int PAGE = 256;

    /** A site as a row: what stays the same while its pass keeps running. */
    private record Key(String buffer, String pass) {
    }

    /** An element as a row: its index and each field decoded. */
    private record Element(int index, String[] values) {
    }

    private final Button watch = new Button("Watch");
    private final Button previous = new Button("Previous");
    private final Button next = new Button("Next");
    private final Button follow = new Button("Follow");
    private final UIText status = new UIText("");

    private final ObservableList<Key> siteRows = new ObservableList<>();
    private final Map<Key, CgBufferInspector.Site> latest = new HashMap<>();
    private final TableView<Key> sites = new TableView<>(siteRows);
    private final ObservableList<Element> elementRows = new ObservableList<>();
    /** Made again when the fields shown change: a table's columns are fixed once added. */
    private TableView<Element> elements;
    @Nullable
    private List<CgElementField> shownFields;

    /** A read's answer, landed on the render thread: a {@link CgBufferInspector.Read}, or why it failed. */
    private final AtomicReference<Object> landed = new AtomicReference<>();
    @Nullable
    private Key selected;
    private int first;
    private boolean shown, startedWatching, following, reading;
    private float sinceRefresh = Float.MAX_VALUE;

    public BuffersTab() {
        super(NAME);
        layout(l -> l.flexDirection(FlexDirection.COLUMN));

        UIElement bar = new UIElement();
        bar.addClass(FrameProfilerPanel.TOOLBAR_CLASS);
        watch.attachListener(this::toggleWatching);
        previous.attachListener(() -> page(-PAGE));
        next.attachListener(() -> page(PAGE));
        follow.attachListener(() -> {
            following = !following;
            if (following) read();
            refreshControls();
        });
        bar.append(watch, previous, next, follow);

        sites.addClass(SITES_CLASS);
        sites.addColumn(TableColumn.<Key>of("Buffer", Key::buffer).flexible(2f).minWidth(100f).sortable());
        sites.addColumn(TableColumn.<Key>of("After pass", Key::pass).flexible(2f).minWidth(100f).sortable());
        sites.addColumn(TableColumn.<Key>of("Elements", key -> String.valueOf(site(key).elements()))
                .width(64f).sortable(Comparator.comparingInt(key -> site(key).elements())));
        sites.addColumn(TableColumn.<Key>of("Element", key -> site(key).decl().element() + ", " + site(key).decl().stride() + " B")
                .width(110f).sortable());
        sites.addColumn(TableColumn.<Key>of("Kernel", key -> shortFile(site(key).file()) + " " + site(key).kernel()
                + " (" + site(key).decl().name() + ")").flexible(2f).minWidth(120f).sortable());
        sites.onSelectionChanged.connect(indices -> {
            Key key = sites.getSelectedItems().isEmpty() ? null : sites.getSelectedItems().iterator().next();
            if (key == null || key.equals(selected)) return;
            selected = key;
            first = 0;
            read();
        });

        status.addClass(STATUS_CLASS);
        elements = elementsTable(List.of());
        append(bar, sites, status, elements);

        whileConnected(() -> () -> {
            if (startedWatching) CgBufferInspector.watch(false);
            startedWatching = false;
            shown = false;
        });
        onConnected(() -> {
            UIDocument document = document();
            if (document != null) document.animation().every(this, this::tick);
        });
    }

    public TableView<?> sites() {
        return sites;
    }

    public String statusText() {
        return status.getText();
    }

    private boolean tick(float deltaSeconds) {
        // Hidden behind another tab, it has no box: nothing to show, and nothing started on its behalf.
        if (box() == null) return true;
        if (!shown) {
            shown = true;
            if (!CgBufferInspector.watching()) setWatching(true);
        }
        Object answer = landed.getAndSet(null);
        if (answer != null) land(answer);
        sinceRefresh += deltaSeconds;
        if (sinceRefresh >= ProfilerSettings.refreshSeconds()) {
            sinceRefresh = 0f;
            refreshSites();
            if (following) read();
        }
        return true;
    }

    private void toggleWatching() {
        setWatching(!CgBufferInspector.watching());
    }

    private void setWatching(boolean on) {
        CgBufferInspector.watch(on);
        startedWatching = on;
        if (!on) {
            reading = false;
            following = false;
        }
        refreshSites();
    }

    /** The sites as the engine has them now; rows replaced only when the set changes, so a selection holds. */
    private void refreshSites() {
        List<CgBufferInspector.Site> now = CgBufferInspector.sites();
        List<Key> keys = new ArrayList<>(now.size());
        latest.clear();
        for (CgBufferInspector.Site site : now) {
            Key key = new Key(site.buffer(), site.pass());
            keys.add(key);
            latest.put(key, site);
        }
        if (!sameRows(keys)) siteRows.setAll(keys);
        else sites.refreshRows();
        if (selected != null && !latest.containsKey(selected)) selected = null;
        refreshControls();
    }

    private boolean sameRows(List<Key> keys) {
        if (keys.size() != siteRows.size()) return false;
        for (int i = 0; i < keys.size(); i++) if (!keys.get(i).equals(siteRows.get(i))) return false;
        return true;
    }

    private void page(int by) {
        CgBufferInspector.Site site = selected == null ? null : latest.get(selected);
        if (site == null) return;
        int moved = Math.max(0, Math.min(first + by, (site.elements() - 1) / PAGE * PAGE));
        if (moved == first) return;
        first = moved;
        read();
    }

    private void read() {
        CgBufferInspector.Site site = selected == null ? null : latest.get(selected);
        if (site == null || reading || !CgBufferInspector.watching()) return;
        reading = true;
        try {
            CgBufferInspector.read(site, first, PAGE, new CgBufferInspector.Sink() {
                @Override
                public void accept(CgBufferInspector.Read read) {
                    landed.set(read);
                }

                @Override
                public void failed(String reason) {
                    landed.set(reason);
                }
            });
        } catch (IllegalStateException gone) {
            reading = false;
            setStatus(gone.getMessage());
            return;
        }
        refreshControls();
    }

    private void land(Object answer) {
        reading = false;
        if (answer instanceof CgBufferInspector.Read read) {
            show(read);
        } else {
            setStatus("The read failed: " + answer);
        }
        refreshControls();
    }

    private void show(CgBufferInspector.Read read) {
        List<CgElementField> fields = read.site().decl().fields();
        if (!fields.equals(shownFields)) {
            TableView<Element> made = elementsTable(fields);
            remove(elements);
            elements.dispose();
            elements = made;
            append(elements);
        }
        List<Element> rows = new ArrayList<>(read.count());
        for (int i = 0; i < read.count(); i++) {
            String[] values = new String[fields.size()];
            for (int f = 0; f < values.length; f++) values[f] = read.value(i, fields.get(f));
            rows.add(new Element(read.first() + i, values));
        }
        elementRows.setAll(rows);
        CgBufferInspector.Site site = read.site();
        setStatus(site.buffer() + " after " + site.pass() + ": elements " + read.first() + "-"
                + (read.first() + read.count() - 1) + " of " + site.elements() + ", read at frame " + site.frame());
    }

    private TableView<Element> elementsTable(List<CgElementField> fields) {
        shownFields = fields;
        TableView<Element> table = new TableView<>(elementRows);
        table.addClass(ELEMENTS_CLASS);
        table.addColumn(TableColumn.<Element>of("#", e -> String.valueOf(e.index()))
                .width(56f).sortable(Comparator.comparingInt(Element::index)));
        for (int f = 0; f < fields.size(); f++) {
            int at = f;
            CgElementField field = fields.get(f);
            String header = field.name().isEmpty() ? field.type() : field.name() + " (" + field.type() + ")";
            table.addColumn(TableColumn.<Element>of(header, e -> e.values()[at]).flexible(1f).minWidth(60f)
                    .sortable(Comparator.comparingDouble(e -> firstNumber(e.values()[at]))));
        }
        return table;
    }

    private void refreshControls() {
        boolean watching = CgBufferInspector.watching();
        CgBufferInspector.Site site = selected == null ? null : latest.get(selected);
        setText(watch, watching ? "Stop watching" : "Watch");
        setText(follow, following ? "Stop following" : "Follow");
        previous.setEnabled(site != null && first > 0);
        next.setEnabled(site != null && first + PAGE < site.elements());
        follow.setEnabled(site != null);
        if (!watching) setStatus("Not watching: compute passes note what they bind only while watched.");
        else if (latest.isEmpty()) setStatus("No compute pass has run since watching began.");
        else if (site == null) setStatus("Pick a buffer to read it after its pass.");
        else if (reading && elementRows.isEmpty()) setStatus("Reading " + site.buffer() + " after " + site.pass() + "...");
    }

    private CgBufferInspector.Site site(Key key) {
        return latest.get(key);
    }

    private void setStatus(String text) {
        if (!text.equals(status.getText())) status.setText(text);
    }

    private static void setText(Button button, String text) {
        if (!text.equals(button.getText())) button.setText(text);
    }

    private static String shortFile(String path) {
        return path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf(':')) + 1);
    }

    /** A value's first component as a number: what a column sorts by. NaN, last, for none. */
    static double firstNumber(String value) {
        int comma = value.indexOf(',');
        String head = (comma < 0 ? value : value.substring(0, comma)).trim();
        try {
            return head.startsWith("0x") ? Long.parseLong(head.substring(2), 16) : Double.parseDouble(head);
        } catch (NumberFormatException none) {
            return Double.NaN;
        }
    }
}
