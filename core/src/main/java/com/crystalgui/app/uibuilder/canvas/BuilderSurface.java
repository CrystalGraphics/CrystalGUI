package com.crystalgui.app.uibuilder.canvas;

import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.BuilderSelection;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.core.data.DataKey;
import com.crystalgui.core.data.DataProvider;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.widget.surface.SurfaceEditor;

/**
 * The UI builder's plane: a {@link SurfaceEditor} that answers {@link BuilderContext}.
 *
 * <p>The same shape the graph takes — {@code GraphView} is a surface that answers {@code GraphContext} —
 * and for the same reason: a feature is written against the CONTEXT, so it needs something that is both
 * a surface and a builder to activate against.</p>
 *
 * <p>Built by {@link BuilderEditor}; nothing else constructs one.</p>
 */
public final class BuilderSurface extends SurfaceEditor implements BuilderContext, DataProvider {

    public static final Name NAME = Name.of("buildersurface");

    private final UiBuilderDocument document;

    private final Artboard artboard;

    private final BuilderSelection selection = new BuilderSelection();

    /** The editor this plane belongs to, for {@link BuilderEditor#UI_BUILDER}. Set once, straight after
     * construction — the editor cannot hand itself over from inside its own field initialiser. */
    @Nullable
    private BuilderEditor owner;

    /** Fires after design mode is switched. @see #setDesignMode */
    public final Signal.Value<Boolean> onDidChangeDesignMode = new Signal.Value<>();

    /** Guards the two selections against answering each other. @see #bridgeSelections */
    private boolean syncingSelection;

    BuilderSurface(UiBuilderDocument document, Artboard artboard, @Nullable List<String> enabled) {
        super(NAME, new TreePolicy(artboard), enabled);
        this.document = document;
        this.artboard = artboard;
        bridgeSelections();
        // LAST: an extension activated any earlier gets a surface whose document and artboard are null.
        ensureExtensions();
    }

    /**
     * Keeps the engine's item set and the builder's selection saying the same thing.
     *
     * <p>Two of them because they answer different questions — the engine moves a set of items and knows
     * nothing about rules or tokens — and one of them because a canvas click and a hierarchy click are
     * the same selection to everybody who reads it. Both directions, since the hierarchy and the
     * inspector write the builder's and the canvas writes the engine's.</p>
     *
     * <p>The guard is not belt-and-braces: each write announces, and an announcement the other side acts
     * on comes straight back.</p>
     */
    private void bridgeSelections() {
        selection().onChanged.connect(() -> {
            if (syncingSelection) return;
            syncingSelection = true;
            try {
                selection.replaceWith(selection().items());
            } finally {
                syncingSelection = false;
            }
        });
        selection.onChanged.connect(() -> {
            if (syncingSelection) return;
            syncingSelection = true;
            try {
                selection().replaceWith(selection.nodes());
            } finally {
                syncingSelection = false;
            }
        });
    }

    /**
     * The gesture a press on a positioned node hands off to.
     *
     * <p>Set after construction for the reason {@link #ownedBy} is: the editor builds both and neither
     * can name the other from inside its own field initialiser.</p>
     */
    void movesWith(MoveOutOfFlow gesture) {
        this.moveGesture = gesture;
    }

    /** @see #movesWith */
    @Nullable
    public MoveOutOfFlow moveGesture() {
        return moveGesture;
    }

    @Nullable
    private MoveOutOfFlow moveGesture;

    @Override
    public boolean isDesignMode() {
        return artboard.isDesignMode();
    }

    /**
     * Switches design and preview.
     *
     * <p>Two things move together and neither is enough alone: the artboard's {@code hit-test}, which
     * decides whether the document's widgets see input at all, and the surface's own mode, which is
     * asked before the tree and would otherwise keep swallowing every press in preview.</p>
     */
    @Override
    public void setDesignMode(boolean design) {
        if (artboard.isDesignMode() == design) return;
        artboard.setDesignMode(design);
        if (design) modes().attach(document());
        else modes().detach();
        onDidChangeDesignMode.emit(design);
    }

    @Override
    public Signal.Value<Boolean> onDidChangeDesignMode() {
        return onDidChangeDesignMode;
    }

    void ownedBy(BuilderEditor editor) {
        this.owner = editor;
    }

    /**
     * The one selection the canvas, the hierarchy and the inspector share.
     *
     * <p>Named apart from {@link #selection()}, which is the ENGINE's: that one is a set of items a
     * gesture moves, this one also carries the rule and the token a panel picked. L4.4 makes the canvas
     * drive both; until then this is what the inspector reads.</p>
     */
    @Override
    public BuilderSelection builderSelection() {
        return selection;
    }

    /**
     * The three keys the builder answers, from an ELEMENT.
     *
     * <p>A {@code DataContext} walks outward from whatever has focus, over elements — so an editor that
     * is not one cannot be found by it however many keys it declares. This plane is what the canvas, the
     * hierarchy rows and the inspector controls all sit inside.</p>
     */
    @Override
    public Object getData(DataKey<?> key) {
        if (key == BuilderEditor.UI_BUILDER) return owner;
        if (key == BuilderEditor.UI_DOCUMENT) return document;
        // ONLY WHEN IT HAS SOMETHING TO SAY. A DataContext stops at the first non-null answer, and the
        // inspector's source is the ACTIVE EDITOR'S VIEW -- which for a .cgui is this element. Answering
        // an empty selection therefore shadowed the document-level LiveSubject outright, so live inspect
        // reported nothing for exactly the file type it exists for. Silence lets the walk reach it.
        if (key == BuilderEditor.BUILDER_SELECTION) return selection.statesNothing() ? null : selection;
        return null;
    }

    @Override
    public UiBuilderDocument getDocument() {
        return document;
    }

    @Override
    public Artboard artboard() {
        return artboard;
    }
}
