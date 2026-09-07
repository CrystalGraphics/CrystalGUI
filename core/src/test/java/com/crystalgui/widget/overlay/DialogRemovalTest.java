package com.crystalgui.widget.overlay;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Before;
import org.junit.Test;

import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElementRegistry;

/**
 * The other half of {@link Dialog#removeWhenClosed()}: it does eventually leave.
 *
 * <p><b>No stylesheet</b>, deliberately — with no {@code transition} loaded {@code display: none} lands
 * on the frame it is written, so "once the fade has finished" is immediate and the removal is testable
 * without a wall clock (see {@code DialogFadesByDefaultTest} for why one cannot be used).</p>
 */
public class DialogRemovalTest extends UiDocumentTestBase {

    private Dialog dialog;

    @Before
    public void anUnstyledDialog() {
        UIElementRegistry.bootstrap();
        dialog = new Dialog("Prompt").removeWhenClosed();
        dialog.layout(l -> l.width(120).height(80));
        document.addOverlay(dialog, document);
        frame();
    }

    @Test
    public void itLeavesTheTreeOnceThereIsNoBoxLeft() {
        dialog.show();
        frame();
        assertNotNull(dialog.parent());

        dialog.close();
        frame();
        frame();

        assertNull("with no fade to wait for, it goes at once", dialog.parent());
    }

    /** Shown again mid-close, it stays: the pending removal is about a dialog that is going away. */
    @Test
    public void aDialogShownAgainWhileClosingIsNotTakenAway() {
        dialog.show();
        frame();
        dialog.close();
        dialog.show();
        frame();
        frame();

        assertNotNull("it was re-opened, so nothing should have removed it", dialog.parent());
    }
}
