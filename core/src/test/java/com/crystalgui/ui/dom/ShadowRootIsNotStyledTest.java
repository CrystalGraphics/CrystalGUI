package com.crystalgui.ui.dom;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.widget.control.Button;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * A shadow root is a {@code DocumentFragment}, so the cascade must never see it — and for the whole
 * of M5 and M6 it did.
 *
 * <p>{@code UIDocument.allNodes()} is what a sheet change re-matches, and it used to end
 * {@code if (shadow != null) collect(shadow, into)} — collecting the shadow ROOT as well as its
 * children. Every shadow root in the tree was therefore matched against every selector in every
 * sheet and cascaded into a full {@code ElementStyle} that nothing ever read: the box tree walks
 * {@code composedChildren()}, where a shadow root is transparent by construction, so it was never
 * laid out and never painted. One wasted entry per shadow-hosting widget <em>instance</em>.</p>
 *
 * <p><b>Nothing could observe it</b>, which is why it survived two milestones — a cascade result
 * nobody reads is indistinguishable from no cascade at all. What found it was the Node/Element split
 * (6.10): once {@code ShadowRoot} stopped being a {@code UIElement}, {@code collect} stopped
 * compiling. This test is the standing version of that compiler error, so the behaviour survives any
 * future widening of the seam.</p>
 *
 * <p>In {@code core/src/test} rather than the headless set: {@link StyleSheet} cannot class-load
 * without CrystalGraphics, its {@code DEFAULT} field reading {@code default.css} through
 * {@code CgIO} at class-init.</p>
 */
public class ShadowRootIsNotStyledTest extends UiDocumentTestBase {

    /** A {@link Button} is one of the 23 widgets that host a shadow tree, and it has parts to find. */
    private Button shadowHostingWidget() {
        Button button = new Button("press");
        document.append(button);
        frame();
        return button;
    }

    @Test
    public void theCascadeIsNeverHandedAShadowRoot() {
        shadowHostingWidget();

        // NOT `instanceof`: the compiler now REFUSES that comparison, because allNodes() answers
        // UIElement and a ShadowRoot is not one -- which is the strongest form this assertion can
        // take and also an unwritable one. Asked by kind instead, so the test still fails loudly if
        // the list is ever widened back to UINode.
        for (UIElement node : document.allNodes()) {
            assertFalse("a shadow root reached the cascade: " + node,
                    ShadowRoot.NAME.equals(node.name()));
        }
    }

    /**
     * <b>The counter-control, and it is the assertion that matters.</b>
     *
     * <p>A "fix" that stopped descending into shadow trees at all would satisfy the test above
     * perfectly and take every composite's styling with it — no button would have a label colour, a
     * padding or a background, because a part is styled through {@code ::part()} and a part lives in
     * a shadow tree. The root is walked <em>through</em>, never <em>into</em> the list.</p>
     */
    @Test
    public void butItsChildrenAreStillStyled() {
        Button button = shadowHostingWidget();
        UIElement label = part(button, Button.LABEL_PART);
        assertNotNull("the fixture needs a part to look for", label);

        List<UIElement> styled = document.allNodes();

        assertTrue("a part inside a shadow tree must still be matched", styled.contains(label));
        assertTrue("and so must its host", styled.contains(button));
    }

    /**
     * The type-level half, asserted rather than left to the compiler.
     *
     * <p>{@code ShadowRoot extends UINode} is what makes the row above unwritable, and it is the one
     * bare node in the engine — {@code UIDocument} is deliberately a {@code UIElement}, because ours
     * is the root element as well as the document. Written as a runtime check so the intent survives
     * somebody making {@code ShadowRoot} an element again to reach one convenient method.</p>
     */
    @Test
    public void aShadowRootIsANodeAndNotAnElement() {
        Button button = shadowHostingWidget();
        ShadowRoot shadow = button.shadowRoot();
        assertNotNull("a Button hosts a shadow tree", shadow);

        assertFalse("a shadow root is a DocumentFragment, not an Element",
                UIElement.class.isAssignableFrom(shadow.getClass()));
        assertTrue("a document IS an element here, and that divergence is deliberate",
                UIElement.class.isAssignableFrom(document.getClass()));
    }

    /**
     * A sheet naming {@code shadow-root} matched one, silently, because the root answered a tag like
     * any other element. Now there is nothing for such a rule to match.
     */
    @Test
    public void aSheetCannotReachAShadowRootByItsTag() {
        shadowHostingWidget();
        document.styleEngine().addStylesheet(StyleSheet.parse("shadow-root { opacity: 0.5; }"));
        frame();

        for (UIElement node : document.allNodes()) {
            assertFalse("nothing should have matched a shadow-root rule",
                    node.name().equals(ShadowRoot.NAME));
        }
    }

    /**
     * The walk out is the half a shadow root MUST keep: encapsulation governs which rules match a
     * node, never which questions it may ask outward.
     */
    @Test
    public void butTheWalkOutStillCrossesIt() {
        Button button = shadowHostingWidget();
        ShadowRoot shadow = button.shadowRoot();
        assertNotNull(shadow);

        assertSame("commandParent() is how a command invoked inside a composite resolves outward",
                button, shadow.commandParent());
    }
}
