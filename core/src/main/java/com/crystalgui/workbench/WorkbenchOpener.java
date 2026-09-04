package com.crystalgui.workbench;

import com.crystalgui.core.async.FrameProfile;
import com.crystalgui.core.notify.Notifications;
import com.crystalgui.core.pattern.FilePatternMap;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.document.DocumentEditor;
import com.crystalgui.document.DocumentKind;
import com.crystalgui.document.DocumentState;
import com.crystalgui.document.EditorInput;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.client.ContentProvider;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.syntax.LanguageRegistry;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.texteditor.TextEditor;
import com.crystalgui.workbench.dock.DockArea;
import com.crystalgui.workbench.dock.DockGroup;
import com.crystalgui.workbench.dock.DockWindow;
import com.crystalgui.workbench.dock.drag.DockDropZone;
import com.crystalgui.workbench.dock.drag.DockPlacement;
import com.crystalgui.workbench.dock.layout.DockLeaf;
import com.crystalgui.workbench.dock.layout.DockPanelRef;
import com.crystalgui.workbench.dock.panel.DockInput;
import com.crystalgui.workbench.dock.panel.DockOpenOptions;
import com.crystalgui.workbench.dock.panel.DockPanelDescriptor;
import com.crystalgui.workbench.editor.EditorService;
import javax.annotation.Nullable;

/**
 * Extracted from {@link Workbench}. See the plan's §4.5 for why this cluster is one thing.
 */
final class WorkbenchOpener {

    private final Workbench workbench;

    WorkbenchOpener(Workbench workbench) {
        this.workbench = workbench;
    }

    /**
     * Opens {@code input} <b>where</b> {@code placement} says and <b>how</b> {@code options} say.
     *
     * <h3>What this replaced</h3>
     *
     * <p>Three overloads — {@code openPanel(ref)}, {@code openPanelWith(sibling, ref)} and
     * {@code openPanelBeside(ref, zone, share)} — which read as three operations and were really one
     * operation with two independent variables. Their genuine differences were buried in their bodies:
     * one activated what it opened, one deliberately restored the previous selection, one set a size
     * share. A caller wanting "beside, without stealing focus" had no overload and no way to ask.</p>
     *
     * <p>That is VS Code's {@code openEditor(input, options, group)}, and the reason it has that shape.</p>
     *
     * @return the leaf it landed in, so a caller can act on it without searching for it again
     */
    public DockLeaf open(DockInput input, DockPlacement placement, DockOpenOptions options) {
        // THE DOCK THE USER IS WORKING IN, which is not always this workbench's own -- see activeDock().
        // Shadowed deliberately: every line below means "the dock this open is going into", and one
        // resolution at the top is what stops half a method reading the field and half the answer.
        DockArea dock = activeDock();
        DockPanelRef ref = input.ref();

        // ALREADY OPEN wins over placement, always. Re-opening a file that is on screen means "show me
        // that one", never "make a second copy of it somewhere else" -- and a placement that ignored this
        // would silently duplicate a document, which is the one outcome no caller wants.
        DockLeaf existing = dock.layout().leafContaining(ref);
        if (existing != null) {
            existing.activate(ref);
            dock.syncGroups();
            if (options.activates()) dock.setActiveGroup(dock.groupFor(existing));
            return existing;
        }

        DockLeaf target = DockPlacement.resolve(placement, dock);
        boolean splitting = placement instanceof DockPlacement.Side;
        if (target == null) target = centralLeaf(dock);

        if (!splitting) {
            // The selection is captured and PUT BACK when the caller asked not to activate. DockLeaf.add
            // activates what it inserts, which is right for a file and wrong for a companion panel.
            DockPanelRef wasActive = target.activePanel();
            long timed = FrameProfile.begin();
            target.add(ref);
            FrameProfile.step(timed, "dock.leaf.add");
            if (!options.activates() && wasActive != null) target.activate(wasActive);
            timed = FrameProfile.begin();
            dock.syncGroups();
            FrameProfile.step(timed, "dock.syncGroups");
            if (options.activates()) {
                timed = FrameProfile.begin();
                dock.setActiveGroup(dock.groupFor(target));
                FrameProfile.step(timed, "dock.setActiveGroup");
            }
            return target;
        }

        DockDropZone zone = ((DockPlacement.Side) placement).zone();
        float whole = target.size();
        DockLeaf placed = dock.layout().drop(target, zone, new DockLeaf(ref));
        if (options.hasShare()) {
            // Ratios within a branch are all that matter, so this is correct whether drop inserted a
            // sibling (the two weights still sum to the target's old share, leaving every other child
            // untouched) or wrapped the target in a new branch (where the pair are its only children).
            target.size(whole * (1f - options.share()));
            placed.size(whole * options.share());
        }
        // requestRebuild, not syncGroups: the TREE changed, not just a selection.
        //
        // The new pane is deliberately NOT made active even when asked. It has no group yet -- the
        // rebuild is deferred to the next frame -- so asking for one now yields null, and setting THAT
        // sends rebuild() down its "nothing is active" path, which picks leaves.get(0): the file tree.
        dock.requestRebuild();
        return placed;
    }

    /** Opens into the central work area and brings it forward — what opening a file means. */
    public DockLeaf open(DockInput input) {
        return open(input, DockPlacement.central(), DockOpenOptions.ACTIVATE);
    }

    /**
     * Opens files matching these extensions with the panel type {@code typeId} instead of the text editor.
     *
     * <p>The host registers the panel itself with {@link #registerPanel} and then says which files it is
     * for. The two are separate calls because they are separate facts — a panel type can exist without
     * claiming any file (the graph, the Problems list), and a binding is meaningless without a panel to
     * build.</p>
     *
     * <p>A bound panel is handed the same {@code PATH_STATE} and title as a text editor would be, so its
     * factory reads the path exactly the same way and nothing else in the dock needs to know a binding
     * happened.</p>
     */
    public Workbench bindEditorExtensions(String typeId, String... extensions) {
        workbench.editorBindings.putExtensions(typeId, extensions);
        return workbench;
    }

    /**
     * The panel reference identifying one open file — {@code path} is what makes two of them distinct.
     *
     * <p><b>The type comes from the binding, not from a constant.</b> This returned {@link #FILE_TYPE}
     * unconditionally, which is why every file opened in a text editor however little sense that made: a
     * PNG arrived as mojibake and a {@code .shadergraph} as JSON. Resolution is
     * {@link FilePatternMap}'s — exact name, then extension, then glob — and the text editor is the
     * fallback rather than the rule.</p>
     *
     * <p>An instance method now, because bindings belong to a workbench. It is also the identity used to
     * <em>find</em> an open tab again, for closing and for renaming, so it must be a pure function of the
     * path and the bindings — which it is, since bindings are registered at startup. A rename that changes
     * the extension therefore legitimately produces a different ref, and the rename path already replaces
     * one ref with the other: renaming {@code a.txt} to {@code a.png} swaps the editor with it, which is
     * the correct answer rather than an accident.</p>
     */
    public DockPanelRef refFor(CgPath path) {
        return workbench.refForResource(Resource.of(path));
    }

    /**
     * Opens a file in its own tab, or focuses the tab it is already in.
     *
     * <p><b>Reads first, adds the tab second.</b> A tab created before the content arrives stays empty when
     * the read fails, and the failure has nowhere to go but a status line nobody was watching — leaving a
     * blank editor with no explanation.</p>
     */
    public void openFile(CgPath path) {
        openFile(path, null);
    }

    /**
     * Opens a file and runs {@code onOpened} <b>once the document actually exists</b>.
     *
     * <p><b>The callback is the whole point, because this method has two paths and only one of them is
     * synchronous.</b> A file already on screen is activated and returns immediately; a file that is not
     * open goes through a read, which is a round trip, and returns long before anything has
     * been adopted. Every caller that wanted to do something <em>to</em> the file it just opened wrote
     * the second statement as though the first had finished:</p>
     * 
     * @param onOpened run after the document is present and its tab is active, on both paths; never run
     *                 if the read fails, since there is nothing to act on
     */
    public void openFile(CgPath path, @Nullable Runnable onOpened) {
        // BEFORE the already-open early return below, so re-activating a tab still promotes the file.
        // "Recent" means recently used, not recently created -- and the branch that returns early is the
        // common one once a session has been running for a while.
        workbench.recentFiles.record(path);
        DockPanelRef ref = refFor(path);
        for (DockLeaf leaf : workbench.dock.layout().leaves()) {
            if (leaf.indexOf(ref) < 0) continue;
            leaf.activate(ref);
            // syncGroups, not requestRebuild: only the selection changed, and this usually runs inside the
            // click that asked for it -- a widget must never rebuild the elements it is being clicked on.
            workbench.dock.syncGroups();
            workbench.dock.setActiveGroup(workbench.dock.groupFor(leaf));
            if (onOpened != null) onOpened.run();
            return;
        }
        openResource(Resource.of(path), onOpened);
    }

    /**
     * Opens anything — a project file, a decompiled class, a generated shader — in one lane.
     *
     * <p>{@code openFile} and the viewer lane were two of these, and the second cost four hundred lines:
     * its own state key, its own editor map, its own loaded-set, its own read, its own re-derivation of
     * adopt and presentation. The cause was a document store keyed by {@code CgPath}, so anything that
     * was not a project file could not be in it. Keyed by {@link Resource} there is one store and one
     * lane, and {@link EditorService} is what holds it.</p>
     *
     * <p><b>Nothing happens when nothing knows how to read the scheme.</b> That is the ordinary state of
     * a deployment shipping no engine: the answer to "go to declaration" is then the same as it was
     * before any of this existed. Silence rather than an error, for the reason the three-tier absence
     * rule gives everywhere else.</p>
     *
     * @param onOpened run once the document exists and its tab is active; never run if the read fails,
     *                 since there is nothing to act on
     */
    public void openResource(Resource resource, @Nullable Runnable onOpened) {
        if (resource == null) return;
        if (!resource.isProject() && workbench.workspace.providerFor(resource) == null) return;
        CgPath path = resource.asPath();
        if (path != null) workbench.recentFiles.record(path);
        DockPanelRef ref = workbench.refForResource(resource);
        // THE TAB FIRST, so a split, a drag and a layout restore all build the panel from the ref alone
        // and the read is the document store's business rather than this method's.
        for (DockLeaf leaf : workbench.dock.layout().leaves()) {
            if (leaf.indexOf(ref) < 0) continue;
            // syncGroups, not requestRebuild: only the selection changed, and this usually runs inside
            // the click that asked for it -- a widget must never rebuild the elements it is being
            // clicked on.
            leaf.activate(ref);
            workbench.dock.syncGroups();
            workbench.dock.setActiveGroup(workbench.dock.groupFor(leaf));
            workbench.editors.open(EditorInput.of(resource))
                    .then(tab -> workbench.runWhenReady(tab, onOpened));
            return;
        }
        workbench.editors.open(EditorInput.of(resource))
                .onError(failure -> Notifications.show(workbench.saveActions.openFailed(resource, failure)
                        // AN ACTION, because a read failure is the case actions exist for: it is usually
                        // transient (a server round trip), the recovery is exactly what was just
                        // attempted, and without one the message names a problem and leaves the user to
                        // find the verb again.
                        .withAction("Retry", () -> openResource(resource, onOpened))))
                .then(tab -> {
                    open(DockInput.of(ref));
                    // AFTER open(), not before: the tab has to be the active one for activeEditor() to
                    // answer with the document this callback is about.
                    workbench.runWhenReady(tab, onOpened);
                });
    }

    /** @see #openResource(Resource, Runnable) */
    public void openResource(Resource resource) {
        openResource(resource, null);
    }

    /**
     * Sends this editor's cross-document jumps somewhere — a workspace file, or a viewer.
     *
     * <p><b>Every editor the workbench builds needs this, and one of them did not have it.</b> A viewer
     * was created without it, so Ctrl+B <em>inside</em> a library class emitted into a signal nobody was
     * listening to — and so did the documentation popup's Jump to Source, which is the same call one
     * layer up. Both looked like resolution failing, while the hover in the very same file was drawing
     * the symbol's full documentation: the engine had the answer throughout and nothing was carrying
     * it.</p>
     *
     * <p>Written once and called from both, rather than copied into the viewer, because the two are
     * expected to stay identical: jumping out of a library class into another library class is the same
     * gesture as jumping out of your own file, and a reader drilling through the JDK is doing it
     * repeatedly. Two copies would be two places for the routing rules to drift.</p>
     */
    void routeDefinitionsOf(TextEditor editor) {
        editor.onDefinitionChosen.connect(site -> {
            if (site.resource() == null) return;
            // A RESOURCE THE WORKSPACE DOES NOT HOLD goes to a viewer. This used to return here, so
            // Ctrl+B into anything on the classpath did nothing -- and it read as the engine having
            // no answer, when the engine had simply never been asked for one.
            if (!site.resource().isProject()) {
                openResourceAt(site.resource(), site.start(), site.member());
                return;
            }
            openFileAt(site.resource().asPath(), site.start());
        });
    }

    /**
     * What file name a resource's content should be treated as, for choosing a language.
     *
     * <p>{@code LanguageRegistry} answers by file name and a library resource has none. Only the
     * provider knows what it produced, so only the provider can say that its output is Java.</p>
     */
    String languageFileNameOf(Resource resource) {
        if (resource.isProject()) return resource.name();
        ContentProvider provider = workbench.workspace.providerFor(resource);
        return provider == null ? resource.name() : provider.languageFileName(resource);
    }

    /**
     * Opens a non-workspace resource and puts the caret at {@code at}, focusing it.
     *
     * <h3>One definition, two callers, and a third coming</h3>
     *
     * <p>Ctrl+B into a library class and Go to Class are the same act with different ways of naming the
     * target — one has a {@code DeclarationSite}, the other has a name and a line somebody typed. The
     * routing between them was written inline for the first caller; extracting it when the second arrived
     * is the rule {@code routeDefinitionsOf} already states about its own two halves ("two copies would be
     * two places for the routing rules to drift").</p>
     *
     * <p><b>The viewer's own editor, never {@code activeEditor()}.</b> That resolves through
     * {@code PATH_STATE}, which a viewer panel deliberately does not carry, so it answers null here — and
     * a null there is silent: the tab opens at the top of the file and the reveal simply does not happen,
     * which reads as the declaration having been at line 1.</p>
     *
     * @param at where to put the caret, or null to open at the top — which is what a name with no
     *           location means, and is not an error
     */
    public void openFileAt(CgPath path, @Nullable TextPoint at) {
        if (path == null) return;
        openFile(path, () -> {
            TextEditor opened = workbench.activeEditor();
            if (opened == null) return;
            if (at != null) opened.revealAt(at);
            UIDocument window = workbench.document();
            if (window != null) window.focus().requestFocus(opened);
        });
    }

    /** @see #openFileAt — the same act for a resource the workspace does not hold. */
    public void openResourceAt(Resource resource, @Nullable TextPoint at) {
        openResourceAt(resource, at, null);
    }

    /**
     * @param member the member to land on once the text exists, or null when {@code at} is already right
     *               — see {@code DeclarationSite.member}
     */
    public void openResourceAt(Resource resource, @Nullable TextPoint at, @Nullable String member) {
        if (resource == null) return;
        openResource(resource, () -> {
            TextEditor opened = workbench.editorFor(resource);
            if (opened == null) return;
            if (at != null) opened.revealAt(at);
            UIDocument window = workbench.document();
            if (window != null) window.focus().requestFocus(opened);
            if (member != null) revealMember(resource, opened, member);
        });
    }

    /**
     * Moves the caret onto {@code member} once the provider has worked out where it is.
     *
     * <h3>Off the UI thread, and the reveal to the top happens anyway</h3>
     *
     * <p>{@link ContentProvider#locate} parses the reconstructed text, which is the same order of cost
     * as producing it, so it cannot run in the callback that opened the tab. The tab therefore opens at
     * the top and the caret arrives a moment later — which is the behaviour every IDE has for a
     * decompiled class and is strictly better than holding the tab closed until a parse finishes.</p>
     */
    private void revealMember(Resource resource, TextEditor viewer, String member) {
        ContentProvider provider = workbench.workspace.providerFor(resource);
        if (provider == null) return;
        provider.locate(resource, member).then(point -> {
            if (point == null) return;
            // NOTHING IS REVEALED IF THE TAB MOVED ON. A reader who navigates twice quickly must not
            // have the first answer land in the second class, so the editor is re-read rather than
            // captured, and a mismatch leaves the caret where the open put it.
            if (workbench.editorFor(resource) != viewer) return;
            viewer.revealAt(point);
        });
    }

    private DockLeaf centralLeaf() {
        return centralLeaf(workbench.dock);
    }

    /** The central leaf of {@code in} — which is not always this workbench's own dock. @see #activeDock */
    private static DockLeaf centralLeaf(DockArea in) {
        for (DockLeaf leaf : in.layout().leaves()) {
            if (leaf.isCentral()) return leaf;
        }
        return in.layout().leaves().get(0);
    }

    /**
     * The dock a newly opened document goes into — <b>the one the user is working in</b> (W9).
     *
     * <h3>Asked of the compositor, not tracked here</h3>
     *
     * <p>Once an editor tab can be torn out into a window of its own, "open this file" has more than one
     * possible destination, and both references answer it the same way: the last editor group you were
     * in gets the next file. Opening into this workbench's own dock regardless would mean a torn-out
     * window could never be worked in — every file you opened from it would appear behind it, in the
     * window you had just deliberately left.</p>
     *
     * <p>The answer is the <b>active window</b>, which the desktop already tracks and already updates on
     * exactly the gestures that should move it: a press in a frame, focus arriving, the switcher. A
     * second notion of "active dock" maintained here would be a copy of that, kept in step by hand, and
     * would disagree with the title bar the first time one of them missed an event.</p>
     *
     * <p>Falls back to this workbench's own dock whenever the active window is not a torn-out one, which
     * covers the ordinary case, the no-desktop case, and the editor's own frame.</p>
     */
    public DockArea activeDock() {
        UIDocument window = workbench.document();
        if (window == null) return workbench.dock;
        WindowFrame active = Desktop.of(window).activeWindow();
        if (active instanceof DockWindow torn && torn.area() != null) return torn.area();
        return workbench.dock;
    }

    /**
     * Registers a kind of document, and the dock panel that shows one — <b>the whole registration</b>.
     *
     * <p>What a package that owns a file type calls, and the only thing an application has to know
     * about that package. One call rather than two, because the panel's content simply <em>is</em> a
     * view of the document: separated, a host can register a panel type it has no kind for, which
     * builds a tab that cannot be saved and reports nothing wrong.</p>
     *
     * <pre>{@code
     * workbench.contribute(DocumentKind.of("mymod:graph", "Shader Graph")
     *         .model(GraphModel::decode)
     *         .editor(GraphView::new), "shadergraph");
     * }</pre>
     *
     * <p>The panel's factory reaches the tab through {@link EditorService} rather than reading the file
     * itself, which is what makes a split, a drag and a layout restore all show the SAME document —
     * rather than one that re-read over whatever was unsaved in it.</p>
     */
    public Workbench contribute(DocumentKind kind) {
        workbench.kinds.register(kind);
        workbench.registry.register(DockPanelDescriptor.document(kind.id(), kind.displayName()), ref -> {
            Resource resource = Resource.parse(ref.state(Workbench.PATH_STATE, ""));
            EditorInput input = EditorInput.of(resource);
            EditorService.Tab tab = workbench.editors.tabFor(input);
            if (tab == null) {
                // The dock also builds panels after a layout RESTORE, where nothing has opened this file
                // yet. Through the ONE lane rather than a read of our own, so the second pane of a split
                // joins the document the first one already holds.
                workbench.editors.open(input);
                tab = workbench.editors.tabFor(input);
            }
            DocumentEditor view = tab == null ? null : tab.editor();
            // A TAB EXISTS IMMEDIATELY, IN LOADING, and its view arrives when the read lands -- which is
            // what lets a session restore put twelve tabs on screen at once rather than revealing them
            // one round trip at a time. An empty element is the placeholder until then.
            //
            // RECORDED, because DockGroup memoises what it built and nothing would ask again. What the
            // placeholder was built FOR is the whole guard: with it, a panel is rebuilt exactly when the
            // element on screen is not what this factory would produce now, and never for a state change
            // that does not move the answer. @see #refreshPanelForTab
            if (view != null) {
                workbench.placeholders.remove(ref);
                return view.view();
            }
            workbench.placeholders.put(ref, tab == null ? DocumentState.LOADING : tab.state());
            return new UIElement();
        });
        return workbench;
    }

    /** As {@link #contribute}, plus the file patterns that open into this kind's panel. */
    public Workbench contribute(DocumentKind kind, String... extensions) {
        contribute(kind);
        if (extensions.length > 0) bindEditorExtensions(kind.id(), extensions);
        return workbench;
    }

}
