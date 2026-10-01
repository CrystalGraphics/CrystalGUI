package com.crystalgui.ui.box;

import static com.crystalgui.ui.box.BoxFixtures.box;
import static com.crystalgui.ui.box.BoxFixtures.sized;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import org.junit.Test;

/** A node shown or hidden re-syncs its own subtree, in place among its siblings, without walking the document. */
public class LocalSyncTest {

    private final UIDocument document = new UIDocument();
    private final UIElement list = sized(100f, 300f);
    private final UIElement a = sized(100f, 10f), b = sized(100f, 20f), c = sized(100f, 30f);

    private void layout() {
        document.update(100f, 300f);
    }

    private void build() {
        document.append(list);
        list.append(a);
        list.append(b);
        list.append(c);
        b.append(sized(50f, 5f));
        layout();
    }

    @Test
    public void hidingAndShowingARowRebuildsNothingElse() {
        build();
        int syncs = document.boxes().syncPasses();

        b.setDisplayed(false);
        layout();
        assertNull("a hidden row keeps no box", b.box());
        assertEquals("the row below it closes the gap", 10f, box(c).y(), 0.01f);

        b.setDisplayed(true);
        layout();
        assertEquals("shown again in its own place", 10f, box(b).y(), 0.01f);
        assertEquals(30f, box(c).y(), 0.01f);
        assertEquals("its children came back with it", 1, box(b).children().size());
        assertSame(box(list), box(b).host());
        assertEquals("neither change walked the document", syncs, document.boxes().syncPasses());
    }

    @Test
    public void aChangeThatIsNotLocalStillSyncsTheDocument() {
        build();
        int syncs = document.boxes().syncPasses();
        list.append(sized(100f, 5f));
        layout();
        assertTrue("an insert walks the document", document.boxes().syncPasses() > syncs);
    }
}
