package com.crystalgui.widget.config.inspector;

import com.crystalgui.ui.dom.Name;
import com.crystalgui.core.data.DataContext;
import com.crystalgui.core.data.DataKey;
import com.crystalgui.core.data.DataProvider;
import com.crystalgui.ui.data.UiDataKeys;
import com.crystalgui.ui.dom.UIElement;

import com.crystalgui.widget.layout.TabView;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Set;
import com.crystalgui.core.CrystalGuiCore;

/**
 * One inspector, for everything — Blender's Properties editor, DaVinci Resolve's Inspector.
 *
 * <h3>It knows no types, and that is the whole design</h3>
 *
 * <p>There was a {@code ShaderGraphInspector}, which is a contradiction: an inspector is a general tool
 * and that one had a graph in its name, its constructor and its fields. Anything else wanting to be
 * inspectable would have needed a second one, and the workbench would have needed to know which.</p>
 *
 * <p>This resolves its subject from a {@link DataContext} and asks {@link InspectorRegistry} which
 * sections apply. <b>Tabs come from the sections that answered</b>, never from a fixed list — so a
 * package makes something inspectable by registering a section, and nothing here changes.</p>
 *
 * <h3>Refill, do not retarget</h3>
 *
 * <p>There is deliberately no {@code setEditor}, no {@code shown} field and no subscription group. A
 * section holds nothing, so pointing the inspector somewhere else is filling it again from the new context —
 * one code path instead of a retarget protocol. What makes that cheap is the kit's: a refill keeps every row
 * the sections place the same way, so a new subject of the same kind costs its values. @see InspectorTabs</p>
 *
 * <p><b>The selected tab survives</b> a subject change where the tab still exists. Switching between two
 * nodes must not throw you back to the first tab, which is the one thing the old per-graph swap also got
 * wrong.</p>
 */
public class Inspector extends UIElement implements DataProvider {

    public static final Name NAME = Name.of("inspector");

    /** The inspector itself, so a theme can frame it and the layout can make it fill its panel. */
    public static final String INSPECTOR_CLASS = "__inspector__";

    /** Shown when nothing selected can be described — an ordinary state, not a failure. */
    public static final String EMPTY_CLASS = "__inspector-empty__";

    private final TabView tabs = new TabView();

    /** What is shown for the subject: a panel per tab, refilled. */
    private final InspectorTabs view = new InspectorTabs(tabs);

    /**
     * On the PANEL, which is the tab's scroller.
     *
     * <p>There used to be a second {@code ScrollerView} between the tab and the panel, and a
     * {@code ConfiguratorPanel} is one itself — so the inspector was two nested scroll containers where
     * every other panel in the workbench has one. Both symptoms of that were visible at once: the inner
     * panel was sized to its content rather than to the tab, so its horizontal bar sat just under the
     * last row instead of at the bottom of the region, and a wheel notch moved the inner one sideways
     * and the outer one down. The project tree never showed either, having only ever had one.</p>
     */
    public static final String SCROLL_CLASS = "__inspector-scroll__";

    public Inspector() {
        super(NAME);
        addClass(INSPECTOR_CLASS);
        append(tabs);

        // ALL THREE OUTLIVE THIS ELEMENT -- two are static and one belongs to the window -- so an
        // inspector that subscribed and was then discarded would stay connected for the life of the
        // process, holding a detached subtree behind it. @see UINode#whileConnected
        //
        // Blender's notifier: anything that changes what is inspected says so, and every inspector
        // re-asks. Deferred and deduplicated, so emitting freely is the intended usage.
        whileConnected(() -> InspectorRegistry.onDidChangeSubject.connect(this::refresh));
        // AND A SUBJECT THAT HAS BEEN CLOSED, which the retention rules would otherwise hold forever:
        // a detached source is kept on purpose, and a source nothing can describe is kept on purpose, so
        // a document whose editor was released stayed on screen with its tabs intact. @see #forget
        whileConnected(() -> InspectorRegistry.onDidCloseSubject.connect(this::forget));
        // AND THE FOCUS OWNER, which is where the subject actually comes from -- see subjectFrom.
        whileConnected(() -> document().focus().onDidChangeFocus.connect(this::onFocusChanged));
        onConnected(() -> document().animation().every(this, this::tickFrame));
    }

    /**
     * What to inspect next, and whether anything has asked.
     *
     * <p>The <b>element</b>, never a {@link DataContext}: a context is a snapshot of one question-asking
     * pass and says so — "build one, use it, drop it". Held across a frame it would answer with whatever
     * was true when it was built.</p>
     */
    @Nullable
    private UIElement pendingSource;
    private boolean pending;

    /** What the panel is describing now, or null — whose history an edit made in it went into. */
    @Nullable
    private UIElement shownSource;

    /**
     * The undo history of what is being described, so Ctrl+Z pressed in a row undoes the edit that row
     * made.
     *
     * <p>Commands resolve outward from focus, and the inspector sits beside the editor rather than inside
     * it — so without this the walk from a focused checkbox reached the workbench and never the document
     * the checkbox had just changed. A focused control with a history of its own, a text field's typing,
     * answers first and keeps it.</p>
     */
    @Override
    @Nullable
    public Object getData(DataKey<?> key) {
        if (key != UiDataKeys.UNDO_STACK) return null;
        UIElement source = shownSource;
        return source == null || source.document() == null ? null : DataContext.from(source).get(UiDataKeys.UNDO_STACK);
    }

    @Override
    protected void connected() {
        super.connected();
        // AND RE-ASK, because entering a tree is itself a reason the answer may have changed.
        //
        // A RegionHost re-parents its occupant on every sync -- SplitView.paneContent clears and re-adds
        // -- so being detached and reattached is a ROUTINE state here rather than an edge case. Anything
        // resolved while detached was resolved against a tree this element was not in, and shownKey then
        // latches that answer: the panel sits empty for a subject it would happily describe, and nothing
        // later disagrees with it because the key never changes again.
        //
        // NOT by clearing shownKey, which is what this did first and was a real bug: the key is also what
        // the "nothing can describe it, keep the last subject" rule below tests. Clearing it disables that
        // rule for one rebuild -- and a rebuild is exactly what a re-parent triggers.
        //
        // Setting pending alone is enough: a genuinely different answer has a different key and gets
        // through on its own merit, and one that resolves to nothing is held back as it should be.
        pending = true;
    }

    /**
     * <b>The focus owner is the subject</b> — Blender's {@code context.object}, IntelliJ's data context
     * pulled from the focus owner.
     *
     * <p>This is what makes the inspector work for a contributor the engine has never heard of. It used
     * to be handed a subject by the application, which resolved it as "the active document's view" — so
     * only a document could ever be inspected, and a section describing a file-tree row or a timeline
     * key could register successfully and never once be asked.</p>
     *
     * <h3>Two things focus does that a subject must not</h3>
     *
     * <p><b>Focus moving into this inspector is not a new subject.</b> Asking to see something must not
     * change what is being shown, and every control this builds is focusable — so scrubbing a row would
     * otherwise re-point the inspector at itself. IntelliJ solves the same problem by skipping tool
     * windows that provide no context.</p>
     *
     * <p><b>Losing focus is not losing the subject.</b> Focus goes null routinely — clicking chrome, a
     * popup closing — and blanking on that would make the panel flicker empty for reasons the user never
     * connected to what they did. So it latches, and only a real new subject replaces it.</p>
     */
    private void onFocusChanged(@Nullable UIElement focused) {
        if (focused == null || contains(focused)) return;
        inspect(focused);
    }

    /**
     * Drops the subject when {@code closed} is it, or contains it.
     *
     * <p>The one case the retention rules must not cover. They exist so the panel changes only when
     * there is a better answer — but a closed document has no better answer coming, and holding its
     * tabs over an unrelated file is worse than blanking.</p>
     *
     * <p>Contains, not equals: an editor is released as a whole and the subject is usually something
     * INSIDE it — the graph, a node, a field that had focus.</p>
     */
    private void forget(@Nullable UIElement closed) {
        if (closed == null) return;
        // THE SUBJECT IS OFTEN NOT THE THING THAT CLOSED, and that is the whole difficulty. Pressing a
        // tab's X moves focus to the X -- so `inspect(thatButton)` is already queued by the time the
        // close arrives, and the subject points at the tab strip rather than at anything inside the
        // editor. Ctrl+W leaves focus in the editor and looks like it works; the two are the same close.
        //
        // So the containment test only decides whether to DROP the source. What a close always does is
        // suspend the retention rules for one pass: the panel is allowed to end up empty, which is the
        // one thing they exist to prevent and the one thing that is right here.
        if (pendingSource != null && (pendingSource == closed || closed.contains(pendingSource))) {
            pendingSource = null;
        }
        shownKey = null;
        shownSource = null;
        forcing = true;
        pending = true;
        if (document() == null) {
            pending = false;
            rebuild(pendingSource);
            forcing = false;
        }
    }

    /** Set by {@link #forget}: this rebuild may blank the panel. Cleared once it has run. */
    private boolean forcing;

    /** Re-ask about the current subject, next frame. What {@code onDidChangeSubject} calls. */
    public void refresh() {
        pending = true;
    }

    private boolean tickFrame(float deltaSeconds) {
        if (pending) {
            pending = false;
            rebuild(pendingSource);
            forcing = false;
        }
        return true;
    }

    /** The tabs it built, so a caller can select one. */
    public TabView tabs() {
        return tabs;
    }

    /** The tab labels currently shown, in order — what the sections asked for. */
    public Set<String> tabNames() {
        return view.names();
    }

    /**
     * Brings the tab called {@code name} to the front.
     *
     * <pre>{@code
     * inspector.showTab("Style");   // what "reveal in the Styles tab" does
     * }</pre>
     *
     * <p>A tab that is not in front has no boxes, so what its rows follow is not polled until it is shown.</p>
     *
     * @return whether there was such a tab
     */
    public boolean showTab(String name) {
        return view.select(name);
    }


    /**
     * Inspect whatever {@code source} is about, <b>on the next frame</b>.
     *
     * <h3>Why this defers</h3>
     *
     * <p>Rebuilding here would tear down this subtree while an event is being dispatched through it. It
     * is reached from {@code onDidChangeActivePanel}, which fires from a <b>mouse-down capture
     * listener</b> in {@code DockGroup} — so clicking a tab rebuilt the inspector mid-dispatch and the
     * input handler walked a path with a detached element in it: {@code "Cannot read field events because
     * path[i] is null"}. That is the rule this codebase already states — a widget must never rebuild the
     * elements it is being clicked on — and the shape it prescribes: event, then a flag, then one rebuild
     * next frame. {@code DockArea} defers its own rebuilds for exactly this reason.</p>
     *
     * <p><b>Applied immediately when there is no window</b>, because the deferral exists only to avoid a
     * dispatch in flight and a detached inspector cannot have one. That is what keeps it usable from a
     * headless test rather than a convenience fork.</p>
     */
    public void inspect(@Nullable UIElement source) {
        pendingSource = source;
        pending = true;
        if (document() == null) {
            pending = false;
            rebuild(source);
        }
    }

    /** {@code -Dcrystalgui.builder.diagnose=true} — what the panel was asked and what answered. */
    private static final boolean DIAGNOSE = Boolean.getBoolean("crystalgui.builder.diagnose");

    private void rebuild(@Nullable UIElement source) {
        // A DETACHED SUBJECT ANSWERS NOTHING, and that is not the same as "nothing to describe".
        //
        // DataContext walks up from the source, so an element that is momentarily out of the tree finds
        // no providers and looks exactly like an unremarkable subject. Regions re-parent constantly --
        // closing any one of them re-mounts the others -- so this fires routinely, and rebuilding on it
        // wiped the graph's tabs every time a panel was closed.
        //
        // Keeping what is shown is the same rule the no-sections branch below follows, for the same
        // reason: the panel should only change when there is a better answer, never because the question
        // was asked at a bad moment.
        // Only while THIS inspector is live. A headless caller inspects detached elements deliberately --
        // that is the whole of how the contribution tests work -- and there the subject being out of a
        // tree is the normal case rather than a symptom.
        if (!forcing && document() != null && source != null && source.document() == null) return;

        DataContext context = source == null ? null : DataContext.from(source);
        List<InspectorSection> sections =
                context == null ? List.of() : InspectorRegistry.sectionsFor(context);
        if (DIAGNOSE) {
            CrystalGuiCore.LOGGER.info(
                    "[inspector] rebuild source={} attached={} sections={}",
                    source, source == null ? null : source.document() != null, sections.size());
        }

        // NOTHING CAN DESCRIBE IT, SO IT IS NOT A SUBJECT. Keep showing the last thing that was.
        //
        // Both references behave this way and neither treats it as a special case. Blender's Properties
        // editor reads the scene's ACTIVE OBJECT, which moving into the Text Editor or the Console does
        // not change -- those editors never contributed to it, so they cannot clear it, and the panel
        // simply keeps describing the object. IntelliJ pulls its data context from the focus owner and
        // lets a component that provides nothing fall through rather than answer null on everyone's
        // behalf; focusing a tool window does not blank the Structure view.
        //
        // The alternative is worse than it looks: with focus as the subject, EVERY click on unrelated
        // chrome -- a text tab, a toolbar, the file tree -- would empty the panel, and the user would
        // have no way to connect the blanking to what they did. What they lose is not information; it is
        // the thing they were working on.
        //
        // AND MOST THINGS ARE NEVER DESCRIBABLE, permanently. This is the steady state, not a gap.
        //
        // An inspector is for structured, non-linear data whose editing surface genuinely IS a property
        // list -- a graph node, a canvas item, a mesh, a scene object. A text buffer is edited in place,
        // and the metadata it does have (encoding, line endings, language, indent) belongs in a status
        // bar: VS Code puts all four there, clickable, and IntelliJ has no inspector at all. Giving a
        // .txt a tab here would be inventing a panel neither reference has, to hold facts both already
        // put somewhere better.
        //
        // So this branch is load-bearing forever rather than until someone writes the missing section,
        // which is why it keeps the last subject rather than naming which subjects are worth keeping.
        if (!forcing && sections.isEmpty() && shownKey != null) return;

        // NOT AN OPTIMISATION. A rebuild replaces every control in the panel, and this engine has a
        // standing rule that a widget must never rebuild the elements it is being clicked or dragged on:
        // screenToLocal goes stale and every later frame of the gesture feeds it garbage. A selection
        // that re-asserts itself -- a press on an already-selected node does exactly that -- would
        // otherwise tear the panel down under the press that caused it.
        String key = subjectKey(context, sections);
        if (key.equals(shownKey)) return;
        // And a live gesture INSIDE the inspector is the other half of the same rule: scrubbing a row
        // while the selection changes must not replace the row being scrubbed.
        if (view.isInteracting()) return;
        shownKey = key;
        shownSource = context == null ? null : source;
        boolean described = context != null && view.show(context, sections);
        // Nothing could describe the subject. An empty framed panel reads as broken, so this is a state a theme can
        // draw -- Blender hides a panel entirely when its poll fails.
        if (!described) view.clear();
        toggleClass(EMPTY_CLASS, !described);
    }

    /**
     * What is on screen, so an unchanged subject costs nothing. Sections answer for their own part.
     *
     * <p>Null until the first build, deliberately: {@code ""} is a <b>real</b> key — it is what nothing
     * inspectable produces — so starting there made the first inspect a no-op and the empty state never
     * appeared.</p>
     */
    @Nullable
    private String shownKey;

    private String subjectKey(@Nullable DataContext context, List<InspectorSection> sections) {
        if (context == null) return "";
        StringBuilder key = new StringBuilder();
        for (InspectorSection section : sections) {
            // A separator no key will contain, so two sections' keys cannot run together into a third
            // that happens to match.
            key.append(section.subjectKey(context)).append("");
        }
        return key.toString();
    }
}
