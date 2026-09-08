package com.crystalgui.app.uibuilder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgui.app.uibuilder.attributes.AttributeGroup;
import com.crystalgui.app.uibuilder.canvas.BuilderEditor;
import com.crystalgui.app.uibuilder.attributes.StyleAttributes;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.core.attribute.AttributeClipboard;
import com.crystalgui.core.attribute.AttributeSet;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.backdrop.BackdropFilterValue;
import com.crystalgui.style.property.visual.texture.TextureValue;
import com.crystalgui.style.property.visual.transform.Transform;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;

/**
 * <b>Copy Attributes and Paste Attributes, and the engine seam under them.</b>
 *
 * <p>The transfer is {@code core.attribute}'s and knows nothing about style: a carrier says what its
 * attributes are, the clipboard holds a set, and the window picks a subset. What is asserted here is the
 * part that would be wrong silently — that a paste MERGES rather than replaces, that a domain mismatch is
 * refused rather than half-applied, and that one paste is one undo step.</p>
 */
public class AttributeTransferTest extends UiDocumentTestBase {

    private UiBuilderDocument model;
    private UIElement source;
    private UIElement target;

    @Before
    public void twoElements() {
        UIElementRegistry.bootstrap();
        AttributeClipboard.clear();
        model = new UiBuilderDocument(
                UiBuilderDocument.EMPTY.getBytes(StandardCharsets.UTF_8), "probe:a.cgui");
        source = new UIElement().layout(l -> l.width(120f).height(40f));
        source.setId("source");
        StyleGroup.inlinePipeline(source.getStyle().getGeneralGroup(),
                g -> g.opacity(0.5f).transform(Transform.scale(2f)));
        target = new UIElement().layout(l -> l.width(60f).height(20f));
        model.root().append(source, target);
        document.append(model.root());
        document.update(W, H);
    }

    @After
    public void emptyTheClipboard() {
        AttributeClipboard.clear();
    }

    /**
     * <b>Sections come in the declared taxonomy's order, with the value-kind ones after it.</b>
     *
     * <p>A set's group order is the order its entries arrive in, and they arrive in property order — so
     * a section's place was decided by the NAME of whichever property happened to come first in it.
     * {@code background} holding a glass put "Glass" at the top, {@code height} put "Layout" second and
     * {@code mask} put "Gradient" third: Layout sandwiched between two value-kind sections by
     * alphabetical accident, on a window whose whole job is to be scanned.</p>
     */
    @Test
    public void theSectionsAreOrderedByTheTaxonomyRatherThanByPropertyName() {
        UIElement element = new UIElement().layout(l -> l.width(40f).height(40f));
        StyleGroup.inlinePipeline(element.getStyle().getGeneralGroup(), g -> g
                .backdropFilter(BackdropFilterValue.parse("blur(12px) tint(#2B2D3088)"))
                .mask(new TextureValue("linear-gradient(45deg, #FF0000FF, #0000FFFF)").compute())
                .overlay(new TextureValue("grid(16, #6EDCD024)").compute()));
        model.root().append(element);
        document.update(W, H);

        List<String> groups = new ArrayList<>(new StyleAttributes(element).copyAttributes().groups());
        assertEquals("the declared taxonomy first, then the kinds the values happen to be: " + groups,
                List.of("Layout", "Appearance", "Gradient", "Grid"), groups);
    }

    /** What was typed onto the element, grouped the way a person looks for it. */
    @Test
    public void aCopyCarriesTheInlineStyleGrouped() {
        AttributeSet copied = new StyleAttributes(source).copyAttributes();

        assertEquals("#source", copied.source());
        assertEquals(StyleAttributes.DOMAIN, copied.domain());
        List<String> ids = new ArrayList<>();
        for (AttributeSet.Entry entry : copied.entries()) ids.add(entry.slot().id());
        assertTrue("the width was typed on, so it travels: " + ids, ids.contains("width"));
        assertTrue(ids.contains("opacity"));
        // A FUNCTION AT A TIME, not `transform` whole -- the fixture scales, so that is the one part
        // its value carries. @see TransformPartsTest
        assertTrue("transform comes apart: " + ids, ids.contains("transform/scale"));

        assertEquals(AttributeGroup.LAYOUT.label(), AttributeGroup.of("width").label());
        assertEquals(AttributeGroup.APPEARANCE.label(), AttributeGroup.of("opacity").label());
        assertTrue("and the functions are filed under the property they belong to: " + copied.groups(),
                copied.groups().contains("Transform"));
    }

    /**
     * <b>A paste merges; it does not make the target a copy of the source.</b>
     *
     * <p>The properties nobody ticked are the ones being kept — so the target's own height survives a
     * paste that carried a width.</p>
     */
    @Test
    public void pastingASubsetLeavesTheRestAlone() {
        AttributeSet copied = new StyleAttributes(source).copyAttributes();
        AttributeSet onlyOpacity = copied.keeping(slot -> slot.id().equals("opacity"));
        assertEquals(1, onlyOpacity.entries().size());

        long before = model.version();
        assertTrue(new StyleAttributes(target).applyAsEdit(model, onlyOpacity));
        document.update(W, H);

        assertEquals("the ticked property landed",
                0.5f, target.getStyle().computed().get(StylePropertyRegistry.OPACITY), 0.001f);
        assertEquals("and the target kept its own size", 60f, target.box().width(), 0.5f);
        assertTrue("one paste is one step", model.version() > before);

        model.history().undo();
        document.update(W, H);
        assertEquals("which undoes in one",
                1f, target.getStyle().computed().get(StylePropertyRegistry.OPACITY), 0.001f);
    }

    /** Nothing ticked is nothing written, and no step in the history for it. */
    @Test
    public void anEmptyChoiceWritesNothing() {
        AttributeSet copied = new StyleAttributes(source).copyAttributes();
        long before = model.version();

        assertFalse(new StyleAttributes(target).applyAsEdit(model, copied.keeping(slot -> false)));
        assertEquals(before, model.version());
    }

    /**
     * <b>The clipboard refuses a domain it did not come from.</b>
     *
     * <p>A string rather than a Java type, so two unrelated consumers can still agree to share a
     * vocabulary — but a graph node's attributes must never half-apply to a widget.</p>
     */
    @Test
    public void theClipboardRefusesAnotherKindOfThing() {
        AttributeClipboard.put(new StyleAttributes(source).copyAttributes());

        assertNotNull(AttributeClipboard.pending(StyleAttributes.DOMAIN));
        assertNull("a different domain gets nothing", AttributeClipboard.pending("mymod:clip"));
    }

    /**
     * <b>The remembered choice belongs to the COPY, not the session.</b>
     *
     * <p>Resolve's "Don't show until next copy". Copying something else re-opens the question, because
     * the ticks were about what was copied and there is no reason to think the answer carries over.</p>
     */
    @Test
    public void aRememberedChoiceIsForgottenOnTheNextCopy() {
        AttributeSet copied = new StyleAttributes(source).copyAttributes();
        AttributeClipboard.put(copied);
        assertFalse(AttributeClipboard.remembersAChoice());

        AttributeClipboard.remember(copied.keeping(slot -> slot.id().equals("opacity")));
        assertTrue(AttributeClipboard.remembersAChoice());
        assertEquals(1, AttributeClipboard.remembered(copied).entries().size());

        AttributeClipboard.put(new StyleAttributes(target).copyAttributes());
        assertFalse("a new copy asks again", AttributeClipboard.remembersAChoice());
    }

    /**
     * <b>A right-click is about what is under the pointer, not about the selection.</b>
     *
     * <p>The commands resolve from the selection, so a menu offered over empty plane describes an element
     * somewhere else entirely — you would be acting on something you cannot see from where you clicked.
     * Empty space therefore gets no menu, and an element gets selected before its menu opens.</p>
     */
    @Test
    public void aRightClickActsOnWhatIsUnderThePointer() {
        BuilderEditor editor = new BuilderEditor(model);
        UIElement host = new UIElement().layout(l -> l.width(400f).height(300f));
        host.append(editor.view());
        document.append(host);
        document.update(W, H);
        editor.selection().selectOnly(source);

        assertNull("empty plane describes nothing, so it offers nothing", editor.menuFor(null));

        assertNotNull("an element gets its menu", editor.menuFor(target));
        assertEquals("and is selected first, so the commands are about it",
                target, editor.selection().node());
    }

    /** Right-clicking inside a multi-selection must not collapse it to the one under the pointer. */
    @Test
    public void aRightClickInsideASelectionLeavesItAlone() {
        BuilderEditor editor = new BuilderEditor(model);
        UIElement host = new UIElement().layout(l -> l.width(400f).height(300f));
        host.append(editor.view());
        document.append(host);
        document.update(W, H);
        editor.selection().selectOnly(source);
        editor.selection().toggle(target);
        assertEquals(2, editor.selection().nodes().size());

        assertNotNull(editor.menuFor(target));
        assertEquals("the selection was already holding it, so it stands",
                2, editor.selection().nodes().size());
    }

    /** An element with nothing typed on it puts nothing down, so Paste stays unavailable. */
    @Test
    public void copyingAnElementWithNoInlineStyleLeavesTheClipboardEmpty() {
        UIElement bare = new UIElement();
        model.root().append(bare);
        document.update(W, H);

        AttributeClipboard.put(new StyleAttributes(bare).copyAttributes());
        assertFalse(AttributeClipboard.hasSomething());
        assertNull(AttributeClipboard.pending(StyleAttributes.DOMAIN));
    }
}
