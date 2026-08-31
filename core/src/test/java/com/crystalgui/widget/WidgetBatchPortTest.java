package com.crystalgui.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.dom.UINodeRegistry;
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
            new Object[]{Dropdown.NAME, Dropdown.class});

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
            if (!UINodeRegistry.isRegistered(name)) {
                wrong.add(name + " is not registered");
                continue;
            }
            UINode built = UINodeRegistry.create(name);
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
        List<Supplier<UINode>> composites = List.of(
                Switch::new, Slider::new, ProgressBar::new, ScrollerView::new, Menu::new,
                () -> new MenuItem("x"), () -> new Dropdown("x"));
        List<String> offenders = new ArrayList<>();
        for (Supplier<UINode> make : composites) {
            UINode node = make.get();
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
        UINode row = new UINode().setId("row");
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

    private boolean hasPart(UINode node, String part) {
        for (UINode at : node.composedSubtree()) {
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
        List<UINode> reports = new ArrayList<>();
        new com.crystalgui.ui.dom.UINodeTreeSource(document).observe(
                new com.crystalgui.ui.dom.TreeObserver.Adapter<>() {
                    @Override public void stateChanged(UINode node) {
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
}
