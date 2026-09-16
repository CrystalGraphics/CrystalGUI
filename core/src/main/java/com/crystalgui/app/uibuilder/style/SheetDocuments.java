package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.async.Reply;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.document.DocumentReference;
import com.crystalgui.document.TextDocumentModel;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.ui.dom.UIDocument;

/**
 * The stylesheets a {@code .cgui} names, as things that can be <b>edited</b>: a sheet in the project is one
 * {@code TextBuffer}, shared with its editor tab, and the canvas follows it as it is typed into.
 *
 * <pre>{@code
 * SheetDocuments sheets = new SheetDocuments(workbench.documents()::open, document.resource());
 * sheets.install(window, document.stylesheets());   // again whenever the list changes
 *
 * SheetDocuments.Sheet sheet = sheets.byId("menu.css");
 * sheet.buffer().edit(CssEdits.replaceValue(model, declaration, "18%"));   // the canvas restyles
 * }</pre>
 *
 * <p><b>Two kinds of sheet, and only one of them is editable.</b> An id with a namespace
 * ({@code crystalgui:ui/styles/ore}) is a shipped asset: loaded through the registry, shared with every
 * other window, and read-only because it lives inside a jar. Anything else is a path to a file in the
 * project — {@code menu.css} beside the document, {@code /ui/menu.css} from the project root — opened as
 * an ordinary document, so an editor tab on that file and this canvas are the same buffer and the same
 * undo history.</p>
 *
 * <ul>
 *   <li>Opening a project sheet is asynchronous. When one arrives the whole list is re-installed in the
 *       document's own order, so cascade order is what the file says rather than what arrived first.</li>
 *   <li>A sheet that cannot be read costs the look and never the tree — the canvas is built without it and
 *       the log names it.</li>
 *   <li>{@link #dispose} takes every installed sheet off the window and closes the documents it opened.</li>
 * </ul>
 */
public final class SheetDocuments {

    /**
     * How a sheet in the project is opened — {@code workbench.documents()::open}.
     *
     * <p>A seam rather than the service itself, so the builder's style package names no workbench and a
     * test can answer with a document it made.</p>
     */
    @FunctionalInterface
    public interface SheetStore {
        Reply<DocumentReference> open(Resource resource);
    }

    /**
     * How a sheet is put in front of a person — {@code workbench.editors()::open}, a tab on the file.
     *
     * <p>Static because <i>Go to source</i> is one gesture wherever it is pressed, and the button that
     * offers it is several layers from whoever knows about tabs. A host that never sets one simply has no
     * such button.</p>
     */
    @Nullable
    private static Consumer<Resource> reveal;

    /** @see #reveal */
    public static void revealWith(@Nullable Consumer<Resource> opener) {
        reveal = opener;
    }

    /** Opens {@code sheet}'s file in an editor, if a host said how. For the rule itself, @see RuleTextEditor */
    public static boolean goToSource(Sheet sheet) {
        if (reveal == null || sheet.resource() == null) return false;
        reveal.accept(sheet.resource());
        return true;
    }

    /** One installed sheet: what it is, where it came from, and whether it can be written to. */
    public static final class Sheet {

        private final String id;
        private final StyleSheet sheet;

        @Nullable
        private final Resource resource;

        @Nullable
        private final TextBuffer buffer;

        @Nullable
        private final DocumentReference reference;

        private Connection watch = Connection.DISCONNECTED;

        private Sheet(String id, StyleSheet sheet, @Nullable Resource resource, @Nullable TextBuffer buffer,
                      @Nullable DocumentReference reference) {
            this.id = id;
            this.sheet = sheet;
            this.resource = resource;
            this.buffer = buffer;
            this.reference = reference;
        }

        /** The id the document named it by. */
        public String id() {
            return id;
        }

        /** The live sheet the window is styled by. */
        public StyleSheet sheet() {
            return sheet;
        }

        /** The file it is, or null for a shipped asset. */
        @Nullable
        public Resource resource() {
            return resource;
        }

        /** The text to edit, or null for a shipped asset. @see #isEditable */
        @Nullable
        public TextBuffer buffer() {
            return buffer;
        }

        /** Whether a write lands anywhere. False for a shipped asset, which is inside a jar. */
        public boolean isEditable() {
            return buffer != null;
        }

        /** What a pane calls it: the file's name, or the asset id. */
        public String label() {
            return resource == null ? id : resource.name();
        }
    }

    @Nullable
    private final SheetStore store;

    /** The document whose sheets these are — what a relative path is resolved against. */
    @Nullable
    private final Resource origin;

    /** Installed, by id, in the order the document named them. */
    private final Map<String, Sheet> installed = new LinkedHashMap<>();

    /** Ids being opened right now, so a re-install while one is in flight does not ask twice. */
    private final List<String> opening = new ArrayList<>();

    @Nullable
    private UIDocument window;

    private List<String> wanted = List.of();

    private boolean disposed;

    public SheetDocuments(@Nullable SheetStore store, @Nullable Resource origin) {
        this.store = store;
        this.origin = origin;
    }

    /**
     * Installs exactly {@code ids} on {@code window}, in that order — the call to make whenever the
     * document's {@code stylesheets} list changes. Sheets no longer named come off; ones already on stay
     * as they are, keeping the buffer and its history.
     */
    public void install(@Nullable UIDocument window, List<String> ids) {
        if (disposed || window == null) return;
        this.window = window;
        this.wanted = List.copyOf(ids);

        for (String id : List.copyOf(installed.keySet())) {
            if (!wanted.contains(id)) remove(id);
        }
        for (String id : wanted) {
            if (installed.containsKey(id) || opening.contains(id)) continue;
            if (isAssetId(id)) {
                Sheet sheet = assetSheet(id);
                if (sheet != null) installed.put(id, sheet);
            } else {
                openProjectSheet(id);
            }
        }
        reorder();
    }

    /** Every installed sheet, in the document's own order. */
    public List<Sheet> sheets() {
        List<Sheet> ordered = new ArrayList<>(installed.size());
        for (String id : wanted) {
            Sheet sheet = installed.get(id);
            if (sheet != null) ordered.add(sheet);
        }
        return List.copyOf(ordered);
    }

    @Nullable
    public Sheet byId(String id) {
        return installed.get(id);
    }

    /** The entry for a live sheet — how a matched rule's sheet is traced back to its text. */
    @Nullable
    public Sheet of(@Nullable StyleSheet sheet) {
        if (sheet == null) return null;
        for (Sheet entry : installed.values()) {
            if (entry.sheet == sheet) return entry;
        }
        return null;
    }

    /** Takes every sheet off the window and closes what was opened for it. */
    public void dispose() {
        disposed = true;
        for (String id : List.copyOf(installed.keySet())) remove(id);
        window = null;
    }

    // ── Installing ──────────────────────────────────────────────────────────

    /** {@code namespace:path} is a shipped asset; anything else is a file in the project. */
    private static boolean isAssetId(String id) {
        int colon = id.indexOf(':');
        return colon > 0 && colon < id.length() - 1;
    }

    @Nullable
    private Sheet assetSheet(String id) {
        try {
            StyleSheet sheet = StyleSheetRegistry.of(id);
            if (sheet == null) return null;
            return new Sheet(id, sheet, null, null, null);
        } catch (RuntimeException | LinkageError missing) {
            // A sheet the document names and the host has not got costs the LOOK, never the tree.
            CrystalGuiCore.LOGGER.warn("[cgui] a document names the stylesheet '{}', which could not be "
                    + "loaded here; the canvas is styled without it", id, missing);
            return null;
        }
    }

    private void openProjectSheet(String id) {
        Resource resource = resolve(id);
        if (store == null || resource == null) return;
        opening.add(id);
        store.open(resource)
                .then(reference -> adopt(id, resource, reference))
                .onError(error -> {
                    opening.remove(id);
                    CrystalGuiCore.LOGGER.warn("[cgui] the stylesheet '{}' could not be opened: {}", id, error);
                });
    }

    private void adopt(String id, Resource resource, DocumentReference reference) {
        opening.remove(id);
        if (disposed || !wanted.contains(id) || window == null) {
            reference.dispose();
            return;
        }
        TextDocumentModel model = reference.document().model() instanceof TextDocumentModel text ? text : null;
        if (model == null) {
            reference.dispose();
            CrystalGuiCore.LOGGER.warn("[cgui] the stylesheet '{}' did not open as text", id);
            return;
        }
        TextBuffer buffer = model.buffer();
        Sheet entry = new Sheet(id, parse(buffer.toString()), resource, buffer, reference);
        // THE CANVAS FOLLOWS THE TEXT, whoever typed it -- this pane, an editor tab on the same file, or
        // an undo in either. One buffer is what makes that a subscription rather than a copy.
        entry.watch = buffer.onChanged.connect(change -> refill(entry));
        installed.put(id, entry);
        reorder();
    }

    private void refill(Sheet entry) {
        if (disposed || window == null) return;
        entry.sheet.refillFrom(parse(entry.buffer == null ? "" : entry.buffer.toString()));
        // The rules are different objects now, so every match is stale -- including the elements this
        // sheet did NOT match before and may match now.
        window.styles().invalidateAllMatches();
    }

    /** Parsed against the bound theme variables, so a sheet's {@code var()} reads what the theme set. */
    private static StyleSheet parse(String text) {
        return StyleSheet.parse(text, StyleSheetRegistry.boundVariables());
    }

    /**
     * Puts the installed sheets on the window in the document's order.
     *
     * <p>Re-adding a sheet appends it, so cascade order is registration order — and a project sheet
     * arrives whenever its file is read. Taking them all off and adding them back in the declared order is
     * what makes the cascade the document's statement rather than a race.</p>
     */
    private void reorder() {
        if (window == null) return;
        for (Sheet entry : installed.values()) {
            if (window.styles().hasStylesheet(entry.sheet, null)) window.styles().removeStylesheet(entry.sheet);
        }
        for (Sheet entry : sheets()) window.styles().addStylesheet(entry.sheet);
    }

    private void remove(String id) {
        Sheet entry = installed.remove(id);
        if (entry == null) return;
        entry.watch.disconnect();
        if (window != null && window.styles().hasStylesheet(entry.sheet, null)) {
            window.styles().removeStylesheet(entry.sheet);
        }
        if (entry.reference != null) entry.reference.dispose();
    }

    /**
     * The file an id names: {@code /ui/menu.css} from the project root, anything else beside the document
     * itself. Null when there is no document to be beside.
     */
    @Nullable
    public Resource resolve(String id) {
        if (origin == null || !origin.isProject()) return null;
        CgPath path = origin.asPath();
        if (id.startsWith("/")) return Resource.of(CgPath.of(path.project(), id.substring(1)));
        return Resource.of(path.parent().resolve(id));
    }
}
