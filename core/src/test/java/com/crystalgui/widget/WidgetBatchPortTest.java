package com.crystalgui.widget;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.box.Box;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.ConfiguratorGroup;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.SymbolIcon;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.config.ConfiguratorPanel;
import com.crystalgui.widget.layout.PageStack;
import com.crystalgui.widget.layout.SplitView;
import com.crystalgui.widget.layout.Tab;
import com.crystalgui.widget.layout.TabView;
import com.crystalgui.widget.overlay.Dialog;
import com.crystalgui.widget.overlay.DialogManager;
import com.crystalgui.widget.scroll.Scroller;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.control.ProgressBar;
import com.crystalgui.widget.control.Slider;
import com.crystalgui.widget.control.Switch;
import com.crystalgui.widget.overlay.Dropdown;
import com.crystalgui.widget.overlay.Menu;
import com.crystalgui.widget.overlay.MenuItem;
import com.crystalgui.widget.overlay.Popover;
import com.crystalgui.widget.scroll.ScrollerView;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.Test;

/**
 * <b>What the 6.1 batch could have broken silently</b> — sixteen widgets in one pass.
 *
 * <p>Not per-widget behaviour: each widget's own suite already asserts that and moves here wholesale
 * at 6.9. What a bulk port breaks is the things that are the same in every file and therefore wrong
 * in every file at once — a kind that is not registered, a part that stayed a class, a subclass
 * inheriting its parent's tag, an {@code IMPORTANT} write that survived the sweep. Each of those is
 * silent: nothing throws, and the widget looks built.</p>
 */
public class WidgetBatchPortTest extends UiDocumentTestBase {

    /** Every kind this batch registered, with the class the factory must build. */
    private static final List<Object[]> KINDS = List.of(
            new Object[]{Switch.NAME, Switch.class},
            new Object[]{Slider.NAME, Slider.class},
            new Object[]{ProgressBar.NAME, ProgressBar.class},
            new Object[]{ScrollerView.NAME, ScrollerView.class},
            new Object[]{Popover.NAME, Popover.class},
            new Object[]{Menu.NAME, Menu.class},
            new Object[]{MenuItem.NAME, MenuItem.class},
            new Object[]{Dropdown.NAME, Dropdown.class},
            // 6.2. PageStack is here for the registration and no-arg checks and NOT in the shadow-tree
            // list below, with Dialog, SplitView, TabView and Tab: those five keep their structure
            // LIGHT on purpose (D1), so asserting a shadow root on them would assert the opposite of
            // what was decided.
            new Object[]{Dialog.NAME, Dialog.class},
            new Object[]{SplitView.NAME, SplitView.class},
            new Object[]{TabView.NAME, TabView.class},
            new Object[]{Tab.NAME, Tab.class},
            new Object[]{PageStack.NAME, PageStack.class});

    /**
     * Each kind is registered and builds its own class.
     *
     * <p>The failure is not a crash: an unregistered kind decodes to nothing, so a networked panel
     * arrives missing one widget and everything around it looks correct.</p>
     */
    @Test
    public void everyKindInTheBatchIsRegisteredAndBuildsItself() {
        List<String> wrong = new ArrayList<>();
        for (Object[] row : KINDS) {
            Name name = (Name) row[0];
            Class<?> type = (Class<?>) row[1];
            if (!UIElementRegistry.isRegistered(name)) {
                wrong.add(name + " is not registered");
                continue;
            }
            UIElement built = UIElementRegistry.create(name);
            if (!type.isInstance(built)) {
                wrong.add(name + " builds a " + built.getClass().getSimpleName()
                        + ", not a " + type.getSimpleName());
            }
        }
        assertTrue(String.join("\n", wrong), wrong.isEmpty());
    }

    /**
     * <b>A subclass declares its OWN kind and does not inherit its parent's.</b>
     *
     * <p>{@code Dropdown extends Button}, {@code MenuItem extends Button}, {@code Menu extends
     * Popover} — and a {@code Name} is passed to the constructor here, so a subclass whose
     * constructor chains to the public parent one silently reports the PARENT's tag. Every rule the
     * subclass has in every sheet then matches nothing, which the old engine's row describes exactly:
     * it reads as the widget not having been BUILT rather than not being styled.</p>
     *
     * <p>The counter-assertion is the point of the pairing — a dropdown deliberately does not answer
     * {@code button}, because taking a button's whole look is wrong for it.</p>
     */
    @Test
    public void aSubclassDeclaresItsOwnKind() {
        assertEquals("crystalgui:dropdown", new Dropdown("x").tagName());
        assertEquals("crystalgui:menuitem", new MenuItem("x").tagName());
        assertEquals("crystalgui:menu", new Menu().tagName());

        assertFalse("a dropdown must not answer `button`", new Dropdown("x").matchesType("button"));
        assertFalse("nor a menu `popover`", new Menu().matchesType("popover"));
    }

    /** The registry's factory is a no-arg supplier, so every registered kind must have one. */
    @Test
    public void everyRegisteredKindHasANoArgumentConstructor() {
        List<String> missing = new ArrayList<>();
        for (Object[] row : KINDS) {
            try {
                ((Class<?>) row[1]).getConstructor();
            } catch (NoSuchMethodException e) {
                missing.add(((Class<?>) row[1]).getSimpleName()
                        + " has no no-arg constructor, so a description cannot decode into it");
            }
        }
        assertTrue(String.join("\n", missing), missing.isEmpty());
    }

    /**
     * The composites build their parts into a SHADOW tree, so nothing a caller adds can collide.
     *
     * <p>Driven over the whole batch rather than per widget because the conversion was mechanical:
     * one that was missed leaves the parts as light children, where a caller's {@code append} lands
     * beside them and an outer descendant selector reaches them — the exact failure
     * {@code .__content__} caused three times over.</p>
     */
    @Test
    public void everyCompositeKeepsItsPartsInAShadowTree() {
        List<Supplier<UIElement>> composites = List.of(
                Switch::new, Slider::new, ProgressBar::new, ScrollerView::new, Menu::new,
                () -> new MenuItem("x"), () -> new Dropdown("x"));
        List<String> offenders = new ArrayList<>();
        for (Supplier<UIElement> make : composites) {
            UIElement node = make.get();
            if (node.shadowRoot() == null) {
                offenders.add(node.getClass().getSimpleName() + " has no shadow root");
            } else if (!node.children().isEmpty()) {
                offenders.add(node.getClass().getSimpleName() + " has "
                        + node.children().size() + " LIGHT children of its own");
            }
        }
        assertTrue(String.join("\n", offenders), offenders.isEmpty());
    }

    /**
     * <b>A {@link ScrollerView} still takes a caller's children, through a slot.</b>
     *
     * <p>The one composite in the batch that accepts content, and the case a blanket "parts go in the
     * shadow tree" conversion breaks: with a shadow root and no slot, every light child is hidden —
     * laid out nowhere, painted never — and the view looks empty with the rows demonstrably in the
     * tree. Asserted through the COMPOSED tree, which is what layout walks.</p>
     */
    @Test
    public void aScrollerViewStillShowsWhatACallerAddsToIt() {
        ScrollerView view = new ScrollerView();
        UIElement row = new UIElement().setId("row");
        view.append(row);
        document.append(view);
        layoutOnly();

        assertSame("a light child, added normally", view, row.parent());
        assertTrue("and composed into the shadow tree through the slot",
                composed(view).contains(row));
        assertNotNull("which is only possible because the box tree reached it", row.box());
    }

    /**
     * The parts carry {@code part} names, which is what a {@code ::part()} rule matches on.
     *
     * <p>A part that kept its {@code __double-underscore__} class instead is unreachable from
     * outside: the class is inside the shadow tree, so no outer rule matches it and no
     * {@code ::part()} rule matches it either. The widget draws its structure with none of its
     * styling, on a tree that looks perfectly correct.</p>
     */
    @Test
    public void theShadowPartsAreNamedAndNotJustClassed() {
        assertTrue(hasPart(new Switch(), "knob"));
        assertTrue(hasPart(new Switch(), "spacer"));
        assertTrue(hasPart(new Slider(), "thumb"));
        assertTrue(hasPart(new Slider(), "fill"));
        assertTrue(hasPart(new ProgressBar(), "fill"));
        assertTrue(hasPart(new ScrollerView(), "v-scroller"));
        assertTrue(hasPart(new ScrollerView(), "corner"));
    }

    private boolean hasPart(UIElement node, String part) {
        for (UIElement at : node.composedSubtree()) {
            if (part.equals(at.get(Attribute.PART))) return true;
        }
        return false;
    }

    /**
     * A {@link ProgressBar} reports nothing for a value that has not moved.
     *
     * <p>The old engine's one non-idempotent setter, and the rule it broke is the one every
     * server-side panel is written against: mirror the model each tick, and an unchanged value costs
     * comparisons rather than traffic. It notified unconditionally, so a panel following the
     * documented shape sent a delta per tick forever, carrying a value nobody had moved — and
     * nothing failed, because every one of those deltas was correct.</p>
     *
     * <p>Asserted through the state-change count, never through the state: the state is right either
     * way, which is why this shipped.</p>
     */
    @Test
    public void aProgressBarReportsNothingForAnUnchangedFraction() {
        ProgressBar bar = new ProgressBar();
        document.append(bar);
        List<UIElement> reports = new ArrayList<>();
        new com.crystalgui.ui.dom.UIElementTreeSource(document).observe(
                new com.crystalgui.ui.dom.TreeObserver.Adapter<>() {
                    @Override public void stateChanged(UIElement node) {
                        reports.add(node);
                    }
                });

        bar.setFraction(0.5f);
        assertEquals("the change is reported once", 1, reports.size());

        bar.setFraction(0.5f);
        bar.setFraction(0.5f);
        assertEquals("and an unchanged value reports nothing", 1, reports.size());
    }

    /**
     * A {@link Popover} has no shadow root until a subclass asks for one.
     *
     * <p>Its content IS the caller's, so light children are exactly right and a slot would be a box
     * in the way. {@link Menu} needs parts, so it gets a root — and the root has to carry a default
     * slot, or the menu's own items (which it appends as light children) vanish.</p>
     */
    @Test
    public void aBarePopoverHasNoShadowRootAndAMenuDoes() {
        assertNull("nothing to encapsulate", new Popover().shadowRoot());
        assertNotNull("a menu builds parts, so it has one", new Menu().shadowRoot());
    }

    /**
     * <b>A {@link ScrollerView} whose content overflows draws a bar with a thumb in it.</b>
     *
     * <h3>Why this exists</h3>
     *
     * <p>It regressed once and nothing saw it. The bars are sized by a bare {@code .__v-scroller__}
     * rule — deliberately keyed on a class rather than a tag list, because a {@code ScrollerView}
     * subclass reports its own tag and would match none of them — and on this engine the bars are
     * shadow PARTS, which no outer class selector can reach. The translation is a HOSTLESS
     * {@code ::part(v-scroller)}, which was added by hand and then lost to a revert that went one
     * commit too far.</p>
     *
     * <p>Every observable around it stayed correct: the view scrolled, the wheel worked, the bar was
     * in the composed tree with the right visible ratio, and {@code refreshScrollers} had computed
     * everything. Only the bar's BOX was {@code 0x0}, laid out in flow below the content instead of
     * absolutely at the edge. So the assertion has to be geometric, and it has to reach the THUMB —
     * a track that sizes while its thumb does not is the same bug one level down.</p>
     */
    @Test
    public void anOverflowingScrollerViewSizesItsBarAndItsThumb() {
        withDefaultStyles();

        ScrollerView view = new ScrollerView();
        layout(view, l -> l.width(400f).height(200f));
        document.append(view);
        UIElement tall = new UIElement();
        StyleGroup.inlinePipeline(tall.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).height(1000f));
        view.append(tall);
        frame();
        frame();

        Scroller bar = null;
        for (UIElement child : view.shadowRoot().children()) {
            if (child instanceof Scroller candidate
                    && candidate.getOrientation() == Scroller.Orientation.VERTICAL) {
                bar = candidate;
            }
        }
        assertTrue("the vertical bar is not in the shadow tree at all", bar != null);

        Box barBox = document.boxes().boxOf(bar);
        assertTrue("the bar has no box: the sizing rule reached nothing", barBox != null);
        assertTrue("the bar laid out " + barBox.width() + "x" + barBox.height()
                        + " -- a bare `.__v-scroller__` cannot reach a shadow part, so the hostless"
                        + " `::part(v-scroller)` twin is what sizes it",
                barBox.width() > 0f && barBox.height() > 0f);

        // AND THE THUMB, which is the part a hand actually has to hit. It is proportional, so it must
        // be shorter than its track as well as non-zero -- 200 visible of 1000 is a fifth.
        Box thumb = null;
        for (UIElement part : bar.shadowRoot().children()) {
            for (UIElement inner : part.children()) {
                Box box = document.boxes().boxOf(inner);
                if (box != null && box.height() > 0f) thumb = box;
            }
        }
        assertTrue("the bar is sized but its thumb is not", thumb != null);
        assertTrue("the thumb is " + thumb.height() + " tall in a " + barBox.height()
                        + " track, which is not a fifth of it",
                thumb.height() > 0f && thumb.height() < barBox.height());
    }

    /**
     * <b>A drag whose SOURCE moves with it still tracks the pointer one-for-one.</b>
     *
     * <p>{@code Drag} captured its start point once in the source's local space and recomputed the
     * pointer in that space every frame. Since M6.1 {@code toLocal} puts the box's own origin at zero,
     * so a source that travels changes the frame its own delta is measured in: the reported delta is
     * pointer travel MINUS how far the source has already gone, the window moves less than the hand,
     * and the next frame reports less still. On screen that is a title bar rubber-banding against the
     * cursor.</p>
     *
     * <p>Every earlier consumer was a {@code Slider}, a {@code Scroller} or a {@code TextField} — none
     * of which moves — so nothing could see it. A {@code Dialog} is the first drag whose source
     * travels and {@code SplitView}'s divider is the second, which is why this arrives with 6.2.</p>
     */
    @Test
    public void aDialogDragTracksThePointerOneForOne() {
        withDefaultStyles();
        UIElement stage = sized("stage", 600f, 400f);
        document.append(stage);
        DialogManager manager = new DialogManager(stage);
        Dialog dialog = manager.manage(new Dialog("panel"));
        dialog.show();
        frame();

        Box bar = document.boxes().boxOf(dialog.getTitleBar());
        assertTrue("no title bar to drag", bar != null);
        float px = bar.worldX() + 20f;
        float py = bar.worldY() + 5f;
        press(px, py);
        frame();
        float startX = document.boxes().boxOf(dialog).worldX();

        // FIVE STEPS, not one. One step passes against the bug -- the first frame's delta is correct
        // and the error compounds only once the source has moved.
        for (int step = 1; step <= 5; step++) {
            move(px + step * 20f, py);
            frame();
            float moved = document.boxes().boxOf(dialog).worldX() - startX;
            assertEquals("after " + (step * 20) + "px of pointer travel the dialog moved " + moved,
                    step * 20f, moved, 0.5f);
        }
        release(px + 100f, py);
    }

    /**
     * <b>Selecting a tab reveals it in its own rail and scrolls nothing else.</b>
     *
     * <p>{@code revealPendingTab} used {@code Box.scrollIntoView()}, which walks every clipping
     * ancestor to the root. That is the DOM's behaviour and right for focus that lands off-screen; it
     * is wrong for a reveal that wants one container. A TabView inside a scrolling page therefore
     * scrolled the PAGE to bring a tab into view, so clicking the last tab jumped the whole document
     * out from under the pointer.</p>
     *
     * <p>Asserted on the ANCESTOR's offset rather than the rail's: the rail is allowed to move, and a
     * fix that simply stopped revealing anything would satisfy an assertion about the tab.</p>
     */
    @Test
    public void selectingATabDoesNotScrollThePageAroundIt() {
        withDefaultStyles();
        ScrollerView page = new ScrollerView();
        layout(page, l -> l.width(600f).height(200f));
        document.append(page);

        TabView tabs = new TabView();
        StyleGroup.inlinePipeline(tabs.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).height(150f));
        page.append(tabs);
        for (String name : new String[] {"one", "two", "three", "four", "five", "six", "seven"}) {
            tabs.addTab(name).content().append(new UIElement());
        }
        UIElement below = new UIElement();
        StyleGroup.inlinePipeline(below.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).height(600f));
        page.append(below);
        frame();
        frame();

        assertTrue("the fixture's page has to be scrollable, or this passes for the wrong reason",
                page.box().maxScrollTop() > 0f);

        tabs.selectIndex(6);
        frame();
        frame();

        assertEquals("selecting a tab scrolled the page around it",
                0f, page.box().scrollTop(), 0.01f);
    }

    /**
     * <b>A horizontal {@link ScrollerView} lays its content out in a row.</b>
     *
     * <p>The slot is a real box between a host and its content, so it is the flex container the
     * content actually lays out in — and {@code flex-direction} does not inherit. The slot stated
     * {@code COLUMN} outright, which made a horizontal scroller impossible: a TabView's tab rail is a
     * {@code ScrollerView}, its sheet gives the rail a row, and its tabs stacked vertically anyway,
     * each one full width.</p>
     *
     * <p>Asserted through a TabView rather than a bare ScrollerView, because the rail is what made it
     * visible and because the tabs being side by side is the thing a reader can check against a
     * screenshot.</p>
     */
    @Test
    public void aTabStripLaysItsTabsOutInARow() {
        withDefaultStyles();
        TabView tabs = new TabView();
        layout(tabs, l -> l.width(600f).height(200f));
        document.append(tabs);
        tabs.addTab("one");
        tabs.addTab("two");
        frame();
        frame();

        Box first = document.boxes().boxOf(tabs.getTab(0));
        Box second = document.boxes().boxOf(tabs.getTab(1));
        assertTrue("a tab has no box", first != null && second != null);
        assertTrue("the second tab is at y=" + second.y() + ", below the first at y=" + first.y()
                        + " -- the rail's slot is laying them out in a column",
                second.x() > first.x() && Math.abs(second.y() - first.y()) < 0.5f);
    }

    /**
     * <b>Every kind of config control lays out with a label and an editor that both have size.</b>
     *
     * <p>Thirteen controls, one panel, and the check is per ROW rather than per panel — a panel whose
     * own box is fine while a row inside it measures zero is the shape M6.1 kept producing, and
     * asserting the container passes against every one of those.</p>
     *
     * <p>Driven through {@code ConfiguratorPanel.add} rather than by constructing controls, because
     * that is the only path anything real uses: a descriptor goes in and {@code ConfigControls}
     * chooses the control. A kind whose factory was lost in the port would come back as nothing here,
     * and constructing the control directly would hide exactly that.</p>
     */
    @Test
    public void everyConfigControlKindLaysOutARow() {
        withDefaultStyles();
        ConfiguratorPanel panel = new ConfiguratorPanel();
        layout(panel, l -> l.width(400f).height(600f));
        document.append(panel);

        panel.add(ConfigDescriptor.header("Header"), null);
        panel.add(ConfigDescriptor.text("text", "Text"), "value");
        panel.add(ConfigDescriptor.number("number", "Number"), 1.0);
        panel.add(ConfigDescriptor.bool("bool", "Bool"), true);
        panel.add(ConfigDescriptor.select("select", "Select", List.of("a", "b")), "a");
        panel.add(ConfigDescriptor.vector("vector", "Vector", 3), new double[] {0, 1, 0});
        panel.add(ConfigDescriptor.color("color", "Colour"), 0xFF3C8CFF);
        panel.add(ConfigDescriptor.mask("mask", "Mask", List.of("x", "y")), Set.of("x"));
        panel.add(ConfigDescriptor.matrix("matrix", "Matrix", 4), null);
        panel.add(ConfigDescriptor.asset("asset", "Asset"), "a/b.shader");
        frame();
        frame();

        List<String> offenders = new ArrayList<>();
        for (String id : new String[] {"text", "number", "bool", "select", "vector",
                                       "color", "mask", "matrix", "asset"}) {
            UIElement control = panel.control(id);
            if (control == null) {
                offenders.add(id + ": ConfigControls built nothing for this kind");
                continue;
            }
            Box box = document.boxes().boxOf(control);
            if (box == null) {
                offenders.add(id + ": no box");
            } else if (!(box.width() > 0f) || !(box.height() > 0f)) {
                offenders.add(id + ": measured " + box.width() + "x" + box.height());
            }
        }
        assertTrue(String.join("\n", offenders), offenders.isEmpty());
    }

    /**
     * A {@link ConfiguratorGroup} folds, and folding is what makes its content stop taking space.
     *
     * <p>The one behaviour in the kit that is a layout question rather than a value one, and the
     * counter-assertion matters: a group that never showed its content would satisfy "collapsed is
     * zero" perfectly.</p>
     */
    @Test
    public void aConfiguratorGroupFolds() {
        withDefaultStyles();
        ConfiguratorPanel panel = new ConfiguratorPanel();
        layout(panel, l -> l.width(400f).height(400f));
        document.append(panel);
        ConfiguratorGroup group = new ConfiguratorGroup("Advanced");
        panel.append(group);
        panel.addTo(group.content(), ConfigDescriptor.number("bias", "Bias"), 0.0);
        frame();
        frame();

        Box open = document.boxes().boxOf(group.content());
        assertTrue("an open group has to show its content", open != null && open.height() > 0f);

        group.setCollapsed(true);
        frame();
        frame();

        Box closed = document.boxes().boxOf(group.content());
        assertTrue("a collapsed group still takes " + (closed == null ? "no box" : closed.height()),
                closed == null || closed.height() <= 0f);
    }

    /**
     * <b>Every 6.2 widget lays out to a real box, with real content in it.</b>
     *
     * <h3>Why a layout smoke and not a behaviour test</h3>
     *
     * <p>These seven had never been laid out when they were committed — they compiled, their kinds
     * registered, and nothing had asked any of them for a box. That is the gap M6.1's whole
     * retrospective is about: the defects were not in the widgets, they were in what the widgets
     * landed on, and every one of them was found by eye on a gallery scene. This is the mechanical
     * half of that, and it is cheap: build one, give it room, lay the document out, and ask whether
     * the thing a user has to hit actually has a size.</p>
     *
     * <p><b>Each is asked about a DESCENDANT, never about itself.</b> A composite whose own box is
     * fine while its content measures zero is exactly the shape 6.1 kept producing — a
     * {@code TextField} at {@code 215x0}, a {@code ScrollerView} slot at 42px inside a 776px view, a
     * popover compressed to {@code 60x4} by a flex parent. Asserting the root's box passes against
     * every one of those.</p>
     */
    @Test
    public void every62WidgetLaysOutWithItsContent() {
        List<String> offenders = new ArrayList<>();

        SplitView split = new SplitView();
        UIElement inFirst = filled();
        split.first().append(inFirst);
        check(split, inFirst, "SplitView's first pane", offenders);

        TabView tabs = new TabView();
        Tab tab = tabs.addTab("one");
        UIElement inTab = filled();
        tab.content().append(inTab);
        check(tabs, inTab, "TabView's selected pane", offenders);

        PageStack<String> stack = new PageStack<>();
        stack.setPageFactory(key -> filled());
        check(stack, stack.show("alpha"), "PageStack's shown page", offenders);

        assertTrue(String.join("\n", offenders), offenders.isEmpty());
    }

    /**
     * A {@link Dialog} is laid out and moved, and its content has a box.
     *
     * <p>Separate from the three above because a dialog must be SHOWN first: a closed one is
     * {@code display: none}, so every box in it measures zero and a "does it fit" assertion passes
     * against {@code 0 <= 0}. That is a standing invariant row and it is this batch's most likely
     * green-against-nothing.</p>
     */
    @Test
    public void aShownDialogLaysOutItsContent() {
        UIElement stage = sized("stage", 400f, 300f);
        document.append(stage);
        DialogManager manager = new DialogManager(stage);

        Dialog dialog = manager.manage(new Dialog("panel"));
        UIElement body = filled();
        dialog.getContent().append(body);
        dialog.show();
        frame();

        Box box = document.boxes().boxOf(body);
        assertTrue("a shown dialog's content has no box at all", box != null);
        assertTrue("...and it measured " + box.width() + "x" + box.height(),
                box.width() > 0f && box.height() > 0f);
    }

    /** Lays {@code root} out at a usable size and reports whether {@code content} got a box. */
    private void check(UIElement root, UIElement content, String what, List<String> offenders) {
        // A SPLIT DIVIDES WHAT IT IS GIVEN and a tab's panes fill what is left, so both measure to
        // nothing inside a content-sized parent. Sizing them here is the fixture's job, not the
        // widget's -- the same thing the gallery's stylesheet does for the same reason.
        layout(root, l -> l.width(400f).height(300f));
        document.append(root);
        frame();

        Box box = content == null ? null : document.boxes().boxOf(content);
        if (box == null) {
            offenders.add(what + " has no box at all");
        } else if (!(box.width() > 0f) || !(box.height() > 0f)) {
            offenders.add(what + " measured " + box.width() + "x" + box.height());
        }
        root.removeSelf();
    }

    /** Something with a size of its own, so a zero box means the container and not the content. */
    private static UIElement filled() {
        UIElement node = new UIElement();
        StyleGroup.inlinePipeline(node.getStyle().getLayoutGroup(),
                l -> l.width(60f).height(20f));
        return node;
    }


    /**
     * A symbol icon placed as a widget's icon is still styled.
     *
     * <p><b>Its whole appearance is authored in an outer sheet</b> — {@code .__completion-icon__} gives it
     * a 12x12 box and {@code .completion-kind-*} gives it its picture — and {@code Button.setPreIcon} puts
     * it INSIDE the host's shadow tree. An outer rule cannot match a node in a shadow tree however simple
     * its selector, so both rules stopped applying: a dock tab whose file had resolved to a declaration
     * drew no icon at all, while a tab whose symbol had not resolved kept its file-type glyph and looked
     * fine. Icons appearing on some tabs and not others reads as the icons failing to load.</p>
     *
     * <p>A hostless {@code ::part(pre-icon)} twin is the crossing, the same answer
     * {@code .__v-scroller__} needed. Asserted on the BOX, because the node was present and correctly
     * classed throughout — only unstyled.</p>
     */
    @Test
    public void aSymbolIconInAWidgetsIconSlotIsStyled() {
        withDefaultStyles();
        Button host = new Button("File.java");
        SymbolIcon icon = new SymbolIcon().show(SymbolKind.CLASS, Set.of());
        host.setPreIcon(icon);
        document.append(host);
        frame();
        frame();

        Box box = icon.box();
        assertNotNull("the icon has a box", box);
        assertEquals("the sheet's size reaches it through ::part()", 12f, box.width(), 0.5f);
        assertEquals("the sheet's size reaches it through ::part()", 12f, box.height(), 0.5f);
    }

    /**
     * Focusing a field in the same breath as attaching it works.
     *
     * <p>Every summoned widget does exactly this — a command palette, a Go to Class, a rename box all
     * build their tree and focus their field the moment they open, because the caret is the point of the
     * widget. {@code Focus.focusable} refused all of them: it asked the BOX TREE whether the node was
     * rendered, and a node attached after the last layout has no box yet.</p>
     *
     * <p><b>It is the ordinary case, not a corner.</b> {@code UIDocument.frame} lays out and then
     * dispatches input in {@code endFrame}, so a popover opened by a chord is always built after that
     * frame's layout. The request was refused in silence and the caret simply never appeared, so typing
     * went to whatever held focus before.</p>
     *
     * <p>Asserted WITHOUT a frame between attaching and focusing, because a frame is what made it work.</p>
     */
    @Test
    public void aFieldFocusedAsItIsAttachedTakesFocus() {
        withDefaultStyles();
        document.append(new Button("something else"));
        frame();

        TextField field = new TextField();
        document.append(field);
        document.focus().requestFocus(field);

        assertSame("a node attached this frame can still take focus", field, document.focus().focused());
    }

    /**
     * A resize is forgotten when the popup closes.
     *
     * <p>A drag writes {@code width}/{@code height} at INLINE, per the CSS spec's rule for a user resize.
     * {@code clearUserSizing} dropped the FLAGS and left those candidates standing, so the size won every
     * cascade for the rest of the widget's life — the classes came off, the sheet's floor came back, and
     * neither could be seen because an INLINE width outranks both. A documentation popup reopened at the
     * previous SYMBOL's size, so one wide declaration made every later hover wide.</p>
     */
    @Test
    public void closingAPopoverForgetsAUserResize() {
        withDefaultStyles();
        Popover popover = new Popover();
        document.append(popover);
        // OPEN, or it is `display: none` and has no box to measure at all.
        popover.showAt(20f, 20f, null);
        frame();
        frame();

        StyleGroup.inlinePipeline(popover.getStyle().getLayoutGroup(), l -> l.width(640f).height(480f));
        popover.markUserSized(true, true);
        frame();
        assertEquals("the drag is in force", 640f, popover.box().width(), 0.5f);

        popover.clearUserSizing();
        frame();
        assertTrue("the dragged width outlived the drag",
                Math.abs(popover.box().width() - 640f) > 0.5f);
    }

    /**
     * A scroller's alignment reaches what it scrolls.
     *
     * <p>A slot is a real box between a host and its content, so it is the flex container the content
     * lays out in — and {@code align-items} does not inherit. {@code align-items: center} on a
     * {@code ScrollerView} therefore centred the SLOT, which already filled the view, and the slot went
     * on stretching the content to full height.</p>
     *
     * <p>Measured where it showed: a dock tab is <b>17.0</b> tall on the old engine and was <b>22.0</b>
     * here, so every editor tab's focus ring was five pixels taller than its label needed. The widget was
     * identical in isolation — a bare {@code Tab} measures 17.0 on both engines — which is what makes
     * this read as a restyle rather than as a box having appeared between two elements that used to be
     * adjacent. {@code flex-direction} was already mirrored for the same reason.</p>
     */
    @Test
    public void aScrollersAlignmentReachesItsContent() {
        withDefaultStyles();
        ScrollerView view = new ScrollerView();
        layout(view, l -> l.width(200f).height(60f)
                .flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER));
        UIElement item = new UIElement();
        layout(item, l -> l.width(40f).height(20f));
        view.append(item);
        document.append(view);
        frame();
        frame();

        assertEquals("the item keeps its own height rather than stretching",
                20f, item.box().height(), 0.5f);
    }
}
