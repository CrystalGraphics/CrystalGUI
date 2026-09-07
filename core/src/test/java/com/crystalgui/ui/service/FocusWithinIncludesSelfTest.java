package com.crystalgui.ui.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.input.FocusPolicy;

/**
 * <b>{@code :focus-within} means focused OR containing focus.</b>
 *
 * <p>It answered only the second half. {@code Focus} sets the flag walking up from the focus owner's
 * composed parent, so the owner never carries it, and reading the flag alone silently excluded every
 * container that takes focus itself.</p>
 *
 * <p>Reported as a tab going grey: clicking the inspector's body focuses its {@code ViewContainer},
 * which is {@code FocusPolicy.CLICK}, and the strip is tinted by
 * {@code viewcontainer:focus-within tab:checked} — so the panel held focus and its tab said otherwise.
 * Clicking an actual field inside it worked, which made it look like a styling bug rather than a
 * predicate asking the wrong question.</p>
 */
public class FocusWithinIncludesSelfTest extends UiDocumentTestBase {

    @Test
    public void anElementThatHoldsFocusIsFocusWithin() {
        UIElement container = new UIElement();
        container.setFocusPolicy(FocusPolicy.CLICK);
        UIElement child = new UIElement();
        child.setFocusPolicy(FocusPolicy.CLICK);
        container.append(child);
        document.append(container);
        document.update(W, H);

        assertFalse("nothing is focused yet", container.isFocusWithin());

        document.focus().requestFocus(container);
        document.update(W, H);
        assertTrue("a container that holds focus itself is within its own focus",
                container.isFocusWithin());
        assertFalse("...and its child is not", child.isFocusWithin());

        // The half that always worked, asserted beside it so a fix cannot trade one for the other.
        document.focus().requestFocus(child);
        document.update(W, H);
        assertTrue("a container whose descendant holds focus still counts",
                container.isFocusWithin());
    }
}
