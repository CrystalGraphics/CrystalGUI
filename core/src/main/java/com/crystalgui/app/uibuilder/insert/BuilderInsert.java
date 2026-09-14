package com.crystalgui.app.uibuilder.insert;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.joml.Vector2f;

import dev.vfyjxf.taffy.style.TaffyDisplay;

import com.crystalgui.app.uibuilder.canvas.BuilderSurface;
import com.crystalgui.app.uibuilder.canvas.CanvasRects;
import com.crystalgui.app.uibuilder.canvas.DropResolver;
import com.crystalgui.app.uibuilder.canvas.Placement;
import com.crystalgui.app.uibuilder.glyph.GlyphView;
import com.crystalgui.app.uibuilder.glyph.KindGlyphs;
import com.crystalgui.app.uibuilder.library.LibraryCatalog;
import com.crystalgui.app.uibuilder.library.UserLibrary;
import com.crystalgui.core.storage.ConfigRecord;
import com.crystalgui.core.storage.ConfigStorage;
import com.crystalgui.serialization.Codecs;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.GlyphRole;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.surface.insert.InsertMenu;
import com.crystalgui.widget.surface.insert.InsertSource;
import com.crystalgui.widget.surface.insert.Insertable;
import com.crystalgui.widget.text.UIText;

/**
 * The builder's Insert menu: every kind, starter and recent pick, placed where the header says — inside a
 * container, or before or after a node — the place a drag to the same spot would take.
 *
 * <pre>{@code
 * BuilderInsert insert = new BuilderInsert(surface, workbench.extensionStore(UiBuilderContribution.ID));
 * surface.registerInsertSource(insert);
 * insert.openForSelection();              // Shift+Space: inside the selection, or after a leaf
 * insert.openAtPointer(rawX, rawY);       // right-click on the page: where the pointer is
 * }</pre>
 *
 * <ul>
 *   <li>Tab and Shift+Tab walk the places while the caret stays in the search; the canvas draws the one chosen with
 *       the drop indicator a drag uses.</li>
 *   <li>A placement is one undo step, selects what it placed and opens a text for editing ({@link Placement}), so
 *       the next Shift+Space goes inside a container just placed.</li>
 *   <li>The last {@link #RECENTS} picks are listed first while browsing, kept in the extension's store; the
 *       Library's groups, the user's included, follow the starters.</li>
 * </ul>
 */
public final class BuilderInsert implements InsertSource {

    public static final String RELATION_CLASS = "__insert-relation__";
    public static final String TARGET_CLASS = "__insert-target__";
    public static final String CYCLE_CLASS = "__insert-cycle__";
    public static final String KEYCAP_CLASS = "__keycap__";

    /** Recent picks kept, newest first. */
    static final int RECENTS = 8;

    /** The recent picks' file in the extension's store. */
    static final String RECENTS_FILE = "insert-recents.json";

    private final BuilderSurface surface;
    @Nullable
    private final ConfigStorage store;
    private final ConfigRecord<List<String>> recents;

    private final UIText relation = new UIText("");
    private final GlyphView glyph = new GlyphView(null);
    private final UIText target = new UIText("");
    private final UIText keycap = new UIText("Tab");
    private final UIText cycle = new UIText("");

    private List<InsertTarget> targets = List.of();
    private int current;

    /** The menu this has dressed, so a second opening does not dress it again. */
    @Nullable
    private InsertMenu dressed;

    public BuilderInsert(BuilderSurface surface, @Nullable ConfigStorage store) {
        this.surface = surface;
        this.store = store;
        this.recents = ConfigRecord.in(store, RECENTS_FILE, Codecs.listOf(Codecs.STRING), List.of());
        relation.addClass(RELATION_CLASS);
        target.addClass(TARGET_CLASS);
        keycap.addClass(KEYCAP_CLASS);
        cycle.addClass(CYCLE_CLASS);
        for (UIText part : List.of(relation, target, keycap, cycle)) part.setHitTest(false);
    }

    /**
     * Opens at the raw pointer, placing where a drag released there would; off every node — the page below its
     * content, or the plane — at the end of the page.
     *
     * @return whether it opened
     */
    public boolean openAtPointer(float rawX, float rawY) {
        UIElement root = surface.getDocument().root();
        DropResolver.Drop drop = new DropResolver(root, surface.dropIndicator()).resolve(List.of(), rawX, rawY);
        // THE ROOT IS CONTENT-SIZED, so most of a fresh page is outside it, and that is where a first node goes.
        targets = drop == null ? InsertTarget.around(root, root, null) : InsertTarget.around(root, drop.target(), drop);
        Vector2f world = surface.surface().toWorld(rawX, rawY);
        return open(world.x, world.y);
    }

    /**
     * Opens under the selection: inside it when it takes children, else after it; with nothing selected, at the end
     * of the page.
     *
     * @return whether it opened
     */
    public boolean openForSelection() {
        UIElement root = surface.getDocument().root();
        List<UIElement> selected = surface.builderSelection().nodes();
        UIElement anchor = selected.isEmpty() ? root : selected.get(selected.size() - 1);
        targets = InsertTarget.around(root, anchor, null);
        // UNDER THE ANCHOR'S LEADING EDGE, where a menu about it is looked for.
        UIElement viewport = surface.surface().element();
        float[] rect = CanvasRects.ofLayout(anchor, viewport);
        Vector2f world = rect == null ? new Vector2f() : surface.surface().viewportToWorld(rect[0], rect[1] + rect[3]);
        return open(world.x, world.y);
    }

    /** The places Tab walks, the default first. */
    public List<InsertTarget> targets() {
        return targets;
    }

    /** Where a pick lands now, or null before the menu has opened with somewhere to put it. */
    @Nullable
    public InsertTarget target() {
        return targets.isEmpty() ? null : targets.get(current);
    }

    /** Moves to the next place ({@code +1}) or the previous ({@code -1}), wrapping. */
    public void cycle(int step) {
        if (targets.isEmpty()) return;
        current = Math.floorMod(current + step, targets.size());
        showTarget();
    }

    private boolean open(float worldX, float worldY) {
        if (targets.isEmpty()) return false;
        current = 0;
        InsertMenu menu = surface.openInsertMenu(worldX, worldY);
        dress(menu);
        showTarget();
        return true;
    }

    private void dress(InsertMenu menu) {
        if (dressed == menu) return;
        dressed = menu;
        UIElement spacer = new UIElement().addClass("__insert-spacer__");
        spacer.setHitTest(false);
        glyph.element().setHitTest(false);
        menu.header().append(relation, glyph.element(), target, spacer, keycap, cycle);
        menu.showHeader(true);
        // EVERY CATEGORY OPEN while browsing: the menu is scanned, and a fold is one press away.
        menu.setAutoExpandThreshold(Integer.MAX_VALUE);
        menu.onCycle.connect(this::cycle);
        // THE PREVIEW GOES WITH THE MENU, however it closes: a pick, Escape, or a click elsewhere.
        menu.onClosed.connect(() -> surface.dropIndicator().clear());
    }

    private void showTarget() {
        InsertTarget place = target();
        if (place == null) return;
        relation.setText(place.word());
        glyph.show(place.reference());
        target.setText(place.name());
        boolean several = targets.size() > 1;
        cycle.setText(several ? (current + 1) + "/" + targets.size() : "");
        for (UIText part : List.of(keycap, cycle)) {
            StyleGroup.inlinePipeline(part.getStyle().getLayoutGroup(),
                    l -> l.display(several ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
        }
        surface.dropIndicator().show(new DropResolver(surface.getDocument().root(), surface.dropIndicator())
                .at(place.parent(), place.index()));
    }

    // ── What it offers ──────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Recent picks first, then the starters, then the Library's groups — shipped and the user's — then every listed
     * kind by category. A recent pick and a group's member are shortcuts to a kind listed under its category too.
     */
    @Override
    public List<Insertable> offers() {
        // READ PER OPENING, from the store the Library writes: a group made there since the last opening is listed.
        LibraryCatalog catalog = LibraryCatalog.current(UserLibrary.in(store).groups());
        // Another editor's picks since this one last listed them.
        recents.reload();
        List<Insertable> out = new ArrayList<>();
        for (String id : recents.get()) {
            Offer offer = offerFor(id, catalog);
            if (offer != null) out.add(offer.shortcutUnder("Recent"));
        }
        for (BuilderStarters.Starter starter : BuilderStarters.ALL) out.add(starterOffer(starter));
        for (LibraryCatalog.Group group : catalog.groups()) {
            for (Name kind : group.kinds()) {
                LibraryCatalog.Entry entry = catalog.entry(kind);
                if (entry != null) out.add(kindOffer(entry).shortcutUnder(group.label()));
            }
        }
        List<LibraryCatalog.Entry> entries = new ArrayList<>(catalog.entries());
        entries.sort(Comparator.comparing((LibraryCatalog.Entry entry) -> String.join("/", entry.path()),
                String.CASE_INSENSITIVE_ORDER).thenComparing(LibraryCatalog.Entry::label, String.CASE_INSENSITIVE_ORDER));
        for (LibraryCatalog.Entry entry : entries) out.add(kindOffer(entry));
        return out;
    }

    @Nullable
    private Offer offerFor(String id, LibraryCatalog catalog) {
        if (id.startsWith("kind:")) {
            LibraryCatalog.Entry entry = catalog.entry(Name.parse(id.substring("kind:".length())));
            return entry == null ? null : kindOffer(entry);
        }
        for (BuilderStarters.Starter starter : BuilderStarters.ALL) {
            if (starter.id().equals(id)) return starterOffer(starter);
        }
        return null;
    }

    private Offer kindOffer(LibraryCatalog.Entry entry) {
        KindGlyphs.Glyph glyph = KindGlyphs.ofKind(entry.kind());
        String about = entry.info().description();
        return new Offer(this, "kind:" + entry.kind(), entry.label(), entry.path(), entry.info().synonyms(),
                about, glyph.icon(), glyph.role().cssClass(), false, entry::build);
    }

    private Offer starterOffer(BuilderStarters.Starter starter) {
        return new Offer(this, starter.id(), starter.label(), List.of("Starters"), starter.synonyms(),
                starter.description(), starter.icon(), GlyphRole.LAYOUT.cssClass(), false, starter::build);
    }

    /** Places a fresh node at the current place, and remembers the pick. */
    private void place(String id, Supplier<? extends UIElement> build) {
        InsertTarget place = target();
        if (place == null) return;
        if (!Placement.at(surface, place.parent(), place.index(), build.get())) return;
        recents.update(before -> {
            List<String> after = new ArrayList<>(RECENTS);
            after.add(id);
            for (String kept : before) {
                if (!kept.equals(id) && after.size() < RECENTS) after.add(kept);
            }
            return after;
        });
    }

    /** One row of the menu, bound to this insert. */
    private record Offer(BuilderInsert owner, String id, String label, List<String> path, List<String> synonyms,
                         @Nullable String description, @Nullable String icon, @Nullable String iconClass,
                         boolean browsingOnly, Supplier<? extends UIElement> build) implements Insertable {

        /** The same pick under {@code folder}, listed only while browsing: a search finds it under its own path. */
        Offer shortcutUnder(String folder) {
            return new Offer(owner, id, label, List.of(folder), synonyms, description, icon, iconClass, true, build);
        }

        @Override
        public void insert(float worldX, float worldY) {
            owner.place(id, build);
        }
    }
}
