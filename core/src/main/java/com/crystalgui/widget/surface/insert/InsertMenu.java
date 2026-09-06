package com.crystalgui.widget.surface.insert;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgui.ui.dom.Name;
import com.crystalgui.widget.composite.CreateMenu;
import com.crystalgui.widget.surface.SurfaceContext;

/**
 * The engine's Add menu: everything the registered {@link InsertSource}s offer, searchable, opened at a
 * point on the plane.
 *
 * <pre>{@code
 * ctx.surface();                       // the menu is opened by the engine, not by a feature
 * surface.openInsertMenu(worldX, worldY);
 * }</pre>
 *
 * <p>Sources merge into one tree grouped by each row's {@code path()}; choosing a row calls its
 * {@code insert} with the point the menu was opened at. What a surface offers is entirely its sources' —
 * the graph offers node types, a UI builder offers widget kinds and templates.</p>
 */
public final class InsertMenu extends CreateMenu<InsertMenu.Row, Insertable> {

    public static final Name NAME = Name.of("insertmenu");

    /** A row: either a folder with children, or one offer. */
    public record Row(String label, @Nullable Insertable offer, List<Row> children) {
    }

    private final SurfaceContext ctx;

    private float worldX;
    private float worldY;

    public InsertMenu(SurfaceContext ctx) {
        super(NAME, "Insert");
        this.ctx = ctx;
        addClass("insertmenu");
        setRows(new Rows<Row, Insertable>() {
            @Override
            public List<Row> roots(String query) {
                return build(query);
            }

            @Override
            public List<Row> children(Row node) {
                return node.children();
            }

            @Override
            public String label(Row node) {
                return node.label();
            }

            @Override
            public boolean isCategory(Row node) {
                return node.offer() == null;
            }

            @Override
            @Nullable
            public Insertable payload(Row node) {
                return node.offer();
            }

            @Override
            public List<String> categorySegments(Row node) {
                return node.offer() == null ? List.of() : node.offer().path();
            }
        });
        onChosen.connect(offer -> offer.insert(worldX, worldY));
    }

    /** Opens at a world point, remembering it — what a chosen row is inserted at. */
    public InsertMenu openAtWorld(float worldX, float worldY) {
        this.worldX = worldX;
        this.worldY = worldY;
        var viewport = ctx.surface().toViewport(worldX, worldY);
        openAt(viewport.x(), viewport.y(), ctx.surface().element());
        return this;
    }

    /**
     * Every source's offers, as one tree.
     *
     * <p>A query <b>flattens</b>: a result set is ranked by the search box, not filed. Browsing keeps the
     * folders, which is what a path is for.</p>
     */
    private List<Row> build(String query) {
        List<Insertable> offers = new ArrayList<>();
        for (InsertSource source : ctx.insertSources()) offers.addAll(source.offers());
        offers.removeIf(offer -> !matches(offer, query));

        if (!query.trim().isEmpty()) {
            List<Row> flat = new ArrayList<>(offers.size());
            for (Insertable offer : offers) flat.add(new Row(offer.label(), offer, List.of()));
            return flat;
        }

        Map<String, List<Insertable>> byPath = new LinkedHashMap<>();
        for (Insertable offer : offers) {
            byPath.computeIfAbsent(String.join("/", offer.path()), ignored -> new ArrayList<>()).add(offer);
        }
        List<Row> roots = new ArrayList<>();
        for (Map.Entry<String, List<Insertable>> entry : byPath.entrySet()) {
            List<Row> leaves = new ArrayList<>(entry.getValue().size());
            for (Insertable offer : entry.getValue()) leaves.add(new Row(offer.label(), offer, List.of()));
            // A row with no path is offered at the top rather than under a folder called nothing.
            if (entry.getKey().isEmpty()) roots.addAll(leaves);
            else roots.add(new Row(entry.getKey(), null, leaves));
        }
        return roots;
    }

    private static boolean matches(Insertable offer, String query) {
        String wanted = query.trim().toLowerCase();
        if (wanted.isEmpty()) return true;
        if (offer.label().toLowerCase().contains(wanted)) return true;
        for (String synonym : offer.synonyms()) {
            if (synonym.toLowerCase().contains(wanted)) return true;
        }
        for (String segment : offer.path()) {
            if (segment.toLowerCase().contains(wanted)) return true;
        }
        return false;
    }
}
