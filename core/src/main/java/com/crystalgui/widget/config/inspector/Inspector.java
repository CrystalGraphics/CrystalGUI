package com.crystalgui.widget.config.inspector;

import com.crystalgui.ui.dom.Name;
import com.crystalgui.core.data.DataContext;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.core.signal.ConnectionGroup;
import com.crystalgui.widget.config.ConfigControl;

import java.util.ArrayList;
import com.crystalgui.widget.config.ConfiguratorPanel;
import com.crystalgui.widget.layout.Tab;
import com.crystalgui.widget.scroll.ScrollerView;
import com.crystalgui.widget.layout.TabView;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * <h3>Rebuild, do not retarget</h3>
 *
 * <p>There is deliberately no {@code setEditor}, no {@code shown} field and no subscription group. A
 * section holds nothing, so pointing the inspector somewhere else is rebuilding from the new context —
 * one code path instead of a retarget protocol. The apparatus that {@code DockPane} needs exists because
 * a pane <em>does</em> hold per-input state; an inspector built this way does not.</p>
 *
 * <p><b>The selected tab survives</b> a subject change where the tab still exists. Switching between two
 * nodes must not throw you back to the first tab, which is the one thing the old per-graph swap also got
 * wrong.</p>
 */
public class Inspector extends UIElement {

    public static final Name NAME = Name.of("inspector");

    /** The inspector itself, so a theme can frame it and the layout can make it fill its panel. */
    public static final String INSPECTOR_CLASS = "__inspector__";

    /** Shown when nothing selected can be described — an ordinary state, not a failure. */
    public static final String EMPTY_CLASS = "__inspector-empty__";

    private final TabView tabs = new TabView();

    /** Tab label → its holder, so a rebuild can keep the selection when the tab is still there. */
    private final Map<String, Tab> tabsByName = new LinkedHashMap<>();

    /**
     * The scrolling host inside each tab, created with the tab.
     *
     * <p><b>A ScrollerView and not just {@code overflow: auto}.</b> Scrolling is an ordinary element
     * capability here, driven by the property — so a plain content box can already be scrolled by wheel.
     * What it cannot do is show a BAR: that is the whole of what ScrollerView adds. An inspector is the
     * one panel whose content is unbounded by construction (however many sections a subject declares) in
     * a region whose height is whatever the user dragged it to, so it is the one that most needs to say
     * how much more there is.</p>
     */
    private final Map<String, ScrollerView> hostsByName = new LinkedHashMap<>();

    /** On each tab's scrolling host. */
    public static final String SCROLL_CLASS = "__inspector-scroll__";

    public Inspector() {
        super(NAME);
        addClass(INSPECTOR_CLASS);
        append(tabs);
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

    @Override
    protected void disconnected() {
        subscriptions.disconnectAll();
    }

    @Override
    protected void connected() {
        UIDocument current = document();
        // RELEASED FIRST, and unconditionally. Both signals below outlive this element -- one is static
        // and one belongs to the window -- so an inspector that subscribed and was then discarded would
        // stay connected for the life of the process, holding a detached subtree. That is the same
        // failure ConfigControl.connections() exists for, and it would only become unbounded once there
        // is more than one Inspector, which is exactly the second-area feature this design allows.
        subscriptions.disconnectAll();
        if (current == null) return;
        document().animation().every(this, this::tickFrame);

        // Blender's notifier: anything that changes what is inspected says so, and every inspector
        // re-asks. Deferred and deduplicated below, so emitting freely is the intended usage.
        subscriptions.add(InspectorRegistry.onDidChangeSubject.connect(this::refresh));
        // AND A SUBJECT THAT HAS BEEN CLOSED, which the retention rules would otherwise hold forever:
        // a detached source is kept on purpose, and a source nothing can describe is kept on purpose, so
        // a document whose editor was released stayed on screen with its tabs intact. @see #forget
        subscriptions.add(InspectorRegistry.onDidCloseSubject.connect(this::forget));
        // AND THE FOCUS OWNER, which is where the subject actually comes from — see subjectFrom.
        subscriptions.add(current.focus().onDidChangeFocus.connect(this::onFocusChanged));

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
        // Closing a region does BOTH at once. The press moves focus to the header's close button, so
        // inspect(thatButton) is queued; hiding the region re-parents this element; and the rebuild then
        // ran against a button with the retention rule switched off. The panel blanked, and clicking the
        // graph brought it back -- which is precisely what a lost subject looks like rather than a lost
        // layout.
        //
        // Setting pending alone is enough: a genuinely different answer has a different key and gets
        // through on its own merit, and one that resolves to nothing is held back as it should be.
        pending = true;
    }

    /** Everything this inspector subscribed to that outlives it. @see #connected */
    private final ConnectionGroup subscriptions = new ConnectionGroup();

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
        return tabsByName.keySet();
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
        if (isInteracting()) return;
        shownKey = key;

        String wasSelected = selectedTabName();
        // Which tabs EXISTED, so the build below can tell a tab that has just appeared from one that was
        // already there. See the selection rule at the end of this method.
        Set<String> previousTabs = new LinkedHashSet<>(tabsByName.keySet());

        tabs.clearTabs();
        tabsByName.clear();
        hostsByName.clear();
        removeClass(EMPTY_CLASS);

        if (context == null) {
            addClass(EMPTY_CLASS);
            return;
        }

        // ONE PANEL PER TAB, filled by every section that wanted that tab. Sections write into a shared
        // form rather than each returning a widget, so two features sharing a tab read as one panel.
        livePanels.clear();
        // Filled DETACHED, then attached only where something was actually written. A section may accept
        // and still contribute nothing -- accepts() answers about a KIND of subject -- and a tab holding
        // an empty panel reads as broken, which is why Blender hides a panel outright when its poll fails.
        Map<String, InspectorForm> forms = new LinkedHashMap<>();
        for (InspectorSection section : sections) {
            InspectorForm form = forms.computeIfAbsent(section.tab(), this::formFor);
            section.build(form, context);
        }
        for (Map.Entry<String, InspectorForm> entry : forms.entrySet()) {
            InspectorForm form = entry.getValue();
            if (!form.wroteAnything()) continue;
            hostFor(entry.getKey()).append(form.panel());
            livePanels.add(form.panel());
        }

        if (tabsByName.isEmpty()) {
            // Nothing could describe the subject. An empty framed panel reads as broken, so this is a
            // state a theme can draw -- Blender hides a panel entirely when its poll fails.
            addClass(EMPTY_CLASS);
            return;
        }

        tabs.selectTab(tabToSelect(wasSelected, previousTabs));
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

    /** A separator no subject key will contain, so two keys cannot run together into a third. */
    private static final String SEPARATOR = " | ";

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

    /** Whether any control this inspector built is mid-gesture. */
    private boolean isInteracting() {
        for (ConfiguratorPanel panel : livePanels) {
            for (ConfigControl control : panel.controls().values()) {
                if (control.isInteracting()) return true;
            }
        }
        return false;
    }

    /** The panels of the current build, for the interaction check above. */
    private final List<ConfiguratorPanel> livePanels = new ArrayList<>();

    /**
     * One panel per tab, <b>kept across rebuilds</b> — never rebuilt, only refilled.
     *
     * <h3>Why a panel outlives the build that filled it</h3>
     *
     * <p>Because everything a panel remembers is <b>view state</b>: which foldouts are open, and where it
     * is scrolled to. That is the same side of the line as selection and scroll position elsewhere in this
     * engine — how you are looking at the thing, not what the thing is — so it has to survive a subject
     * change, and a panel discarded on every rebuild remembers nothing by construction.</p>
     *
     * <p>{@code ConfiguratorPanel} already implements both halves and says so: {@code groupCollapsed} is
     * documented as outliving {@code clearRows()}, and {@code clearRows()} exists <em>"for a panel that is
     * rebuilt rather than merely updated, which any inspector bound to a selection is"</em>. Building a
     * fresh panel each time orphaned both — you opened a node's {@code About}, clicked the next node, and
     * it had shut itself again, which is the exact failure that javadoc describes.</p>
     *
     * <p><b>Never pruned</b>, deliberately. A tab that disappears when its section stops polling true —
     * {@code Node}, whenever the selection is cleared — must find its foldouts as it left them when it
     * comes back, and dropping the panel with the tab is the same bug one level up. The map is bounded by
     * the number of distinct tab names, which is a handful.</p>
     *
     * <p>Safe only because sections subscribe <b>per row</b> ({@code control().changed}) and rows are
     * destroyed by {@code clearRows()}. A section that connected to something panel-scoped or longer-lived
     * on each build would accumulate one listener per rebuild, and the reuse is what would make that
     * visible — see {@code SettingsConfigurator.bind}.</p>
     */
    private final Map<String, ConfiguratorPanel> panelsByTab = new LinkedHashMap<>();

    /** The form for a tab: its panel, emptied of rows but not of what it remembers. */
    private InspectorForm formFor(String tab) {
        ConfiguratorPanel panel = panelsByTab.computeIfAbsent(tab, t -> new ConfiguratorPanel());
        panel.clearRows();
        // Detached FIRST. The panel is still a child of the previous build's Tab content -- clearTabs()
        // drops the tabs, not the panel's parent pointer -- and re-adding it without this reparents from
        // under a stale owner.
        panel.removeSelf();
        return new InspectorForm(panel);
    }

    /**
     * <b>A tab that has just appeared wins the selection.</b>
     *
     * <p>Otherwise the tab you were on, and only then the first one.</p>
     *
     * <p>Keeping the old tab unconditionally is what "select a node and the Inspector still shows the
     * graph" was: the Node tab was built correctly and left behind the one already in front, so the answer
     * to what you just clicked took a second click to reach. A tab exists only because a section polled
     * true, so a <em>new</em> one is the engine's own evidence that the subject gained something it could
     * not describe a moment ago — which is the thing worth looking at. Unity's Shader Graph focuses Node
     * Settings on selection for the same reason.</p>
     *
     * <p>Self-limiting, which is what makes it safe to apply to every future section: it can fire at most
     * once per appearance, so a tab that stays put never steals focus again, and switching between two
     * nodes leaves you where you were.</p>
     *
     * <p>On the first build every tab is new and the first one is also the fallback, so this changes
     * nothing there.</p>
     */
    private Tab tabToSelect(@Nullable String wasSelected, Set<String> previousTabs) {
        for (Map.Entry<String, Tab> entry : tabsByName.entrySet()) {
            if (!previousTabs.contains(entry.getKey())) return entry.getValue();
        }
        Tab remembered = wasSelected == null ? null : tabsByName.get(wasSelected);
        return remembered != null ? remembered : tabsByName.values().iterator().next();
    }

    private Tab tabFor(String name) {
        return tabsByName.computeIfAbsent(name, n -> {
            Tab tab = tabs.addTab(n);
            ScrollerView host = new ScrollerView();
            host.addClass(SCROLL_CLASS);
            tab.content().append(host);
            hostsByName.put(n, host);
            return tab;
        });
    }

    /** The tab's scrolling host, creating the tab if this is the first section to claim it. */
    private ScrollerView hostFor(String name) {
        tabFor(name);
        return hostsByName.get(name);
    }

    @Nullable
    private String selectedTabName() {
        Tab selected = tabs.getSelectedTab();
        if (selected == null) return null;
        for (Map.Entry<String, Tab> entry : tabsByName.entrySet()) {
            if (entry.getValue() == selected) return entry.getKey();
        }
        return null;
    }
}
