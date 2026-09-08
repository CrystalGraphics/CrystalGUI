package com.crystalgui.app.uibuilder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.crystalgui.app.uibuilder.attributes.StyleAttributes;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.core.attribute.AttributeSet;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.style.property.visual.transform.Transform;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;

/**
 * <b>{@code transform} is copied a function at a time.</b>
 *
 * <p>One property holding four ideas, and "the rotation, not the scale" is a thing a designer asks for
 * that CSS has no name for. What matters here is that a part merges into the target rather than
 * replacing it, and that a value the split cannot describe is refused instead of flattened.</p>
 */
public class TransformPartsTest extends UiDocumentTestBase {

    private UiBuilderDocument model;
    private UIElement source;
    private UIElement target;

    @Before
    public void twoElements() {
        UIElementRegistry.bootstrap();
        model = new UiBuilderDocument(
                UiBuilderDocument.EMPTY.getBytes(StandardCharsets.UTF_8), "probe:a.cgui");
        source = new UIElement().layout(l -> l.width(120f).height(40f));
        target = new UIElement().layout(l -> l.width(60f).height(20f));
        model.root().append(source, target);
        document.append(model.root());
        document.update(W, H);
    }

    private static void transform(UIElement node, Transform value) {
        StyleGroup.inlinePipeline(node.getStyle().getGeneralGroup(), g -> g.transform(value));
    }

    private static Transform transformOf(UIElement node) {
        return node.getStyle().computed().get(StylePropertyRegistry.TRANSFORM);
    }

    private static List<String> idsOf(AttributeSet set) {
        List<String> ids = new ArrayList<>();
        for (AttributeSet.Entry entry : set.entries()) ids.add(entry.slot().id());
        return ids;
    }

    /** Each function is its own slot, in its own section, and only the ones actually present. */
    @Test
    public void aTransformIsOfferedFunctionByFunction() {
        transform(source, Transform.IDENTITY
                .then(Transform.Op.translate(LengthPercent.px(10f), LengthPercent.px(0f)))
                .then(Transform.Op.rotate(0.5f)));

        AttributeSet copied = new StyleAttributes(source).copyAttributes();
        List<String> ids = idsOf(copied);

        assertTrue(ids.toString(), ids.contains("transform/translate"));
        assertTrue(ids.toString(), ids.contains("transform/rotate"));
        assertFalse("nothing scaled it, so there is nothing to offer", ids.contains("transform/scale"));
        assertFalse("and the whole property is not offered as well", ids.contains("transform"));

        // ONE SECTION, holding both. The group is the composite and the parts are what is in it: four
        // headings for one property read as four unrelated properties that happen to sit together.
        assertTrue("the functions are filed under Transform: " + copied.groups(),
                copied.groups().contains("Transform"));
        assertFalse("…and not under a section of their own: " + copied.groups(),
                copied.groups().contains("Translate"));
    }

    /**
     * The point of the whole exercise: pasting one function leaves the target's others alone. Starting
     * the merge from identity rather than from the target would silently drop them.
     */
    @Test
    public void pastingOneFunctionKeepsTheTargetsOthers() {
        transform(source, Transform.IDENTITY.then(Transform.Op.rotate(1.0f)));
        transform(target, Transform.scale(2f, 2f));
        document.update(W, H);

        AttributeSet copied = new StyleAttributes(source).copyAttributes();
        AttributeSet rotationOnly = copied.keeping(slot -> slot.id().equals("transform/rotate"));
        assertTrue(new StyleAttributes(target).applyAsEdit(model, rotationOnly));
        document.update(W, H);

        List<Transform.Op> ops = transformOf(target).ops();
        assertEquals("the target keeps its scale and gains the rotation: " + ops, 2, ops.size());
        assertTrue(ops.stream().anyMatch(op -> op.kind() == Transform.Kind.SCALE));
        assertTrue(ops.stream().anyMatch(op -> op.kind() == Transform.Kind.ROTATE));
    }

    /** A function the target already has is replaced where it stood, so nothing else changes meaning. */
    @Test
    public void aFunctionTheTargetAlreadyHasIsReplacedInPlace() {
        transform(source, Transform.rotate(1.0f));
        transform(target, Transform.IDENTITY
                .then(Transform.Op.rotate(0.1f))
                .then(Transform.Op.scale(3f, 3f)));
        document.update(W, H);

        AttributeSet copied = new StyleAttributes(source).copyAttributes();
        new StyleAttributes(target).applyAsEdit(model,
                copied.keeping(slot -> slot.id().equals("transform/rotate")));
        document.update(W, H);

        List<Transform.Op> ops = transformOf(target).ops();
        assertEquals(2, ops.size());
        assertEquals("the rotation stays first, where the old one was",
                Transform.Kind.ROTATE, ops.get(0).kind());
        assertEquals(1.0f, ops.get(0).fx(), 0.001f);
        assertEquals(Transform.Kind.SCALE, ops.get(1).kind());
    }

    /**
     * <b>Interleaved functions are refused, not flattened.</b>
     *
     * <p>{@code rotate scale rotate} has no single "the rotation": pulling both out and putting them
     * back anywhere renders differently, and nothing about the choice would say so. The whole property
     * is offered instead.</p>
     */
    @Test
    public void anInterleavedTransformIsOfferedWholeInstead() {
        transform(source, Transform.IDENTITY
                .then(Transform.Op.rotate(0.2f))
                .then(Transform.Op.scale(2f, 2f))
                .then(Transform.Op.rotate(0.3f)));

        List<String> ids = idsOf(new StyleAttributes(source).copyAttributes());

        assertFalse("there is no honest 'the rotation' here: " + ids, ids.contains("transform/rotate"));
        assertTrue("so the property is offered whole: " + ids, ids.contains("transform"));
        // ALL OR NOTHING, so the scale is not offered separately either -- even though on its own it
        // divides perfectly well. Offering it would mean ticking every box and still not reproducing
        // the source, with nothing on screen to say the rotation had been left behind.
        assertFalse("and no part of it is offered piecemeal: " + ids, ids.contains("transform/scale"));
    }
}
