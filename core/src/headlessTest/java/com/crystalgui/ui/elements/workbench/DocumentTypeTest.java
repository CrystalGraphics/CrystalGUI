package com.crystalgui.ui.elements.workbench;

import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.ui.UIElement;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * {@link DocumentType} — a file type declared by whoever owns it.
 *
 * <p>It was {@code registerDocumentType(id, title, factory)} plus {@code bindEditorExtensions(id, …)},
 * two calls that are meaningless apart: a factory with no binding never opens anything, and a binding
 * with no factory throws when a user opens a file. Two calls that must both happen are one fact.</p>
 */
public class DocumentTypeTest {

    private static final class Stub implements FileDocument {
        private final Resource resource;
        private final UIElement view = new UIElement();

        Stub(CgPath path) {
            this.resource = Resource.of(path);
        }

        @Override public Resource resource() { return resource; }
        @Override public UIElement view() { return view; }
        @Override public byte[] encode() { return new byte[0]; }
        @Override public void adopt(byte[] bytes) { }
        @Override public Connection onDidChange(Runnable listener) { return () -> { }; }
    }

    @Test
    public void aTypeCarriesItsFactoryAndItsPatterns() {
        DocumentType type = DocumentType.of("thing", "Thing")
                .forExtensions("thing", "thg")
                .forNames("THINGFILE")
                .forGlobs("**/*.thing")
                .document(Stub::new);

        assertEquals("thing", type.typeId());
        assertEquals("Thing", type.title());
        assertEquals(java.util.List.of("thing", "thg"), type.extensions());
        assertEquals(java.util.List.of("THINGFILE"), type.fileNames());
        assertEquals(java.util.List.of("**/*.thing"), type.globs());
        assertTrue(type.factory() != null);
    }

    /**
     * <b>An incomplete type is detectable before anything opens.</b>
     *
     * <p>The failure this shape exists to move. Half a registration used to surface as
     * {@code "No document factory for panel type"} when a <em>user</em> opened a file — a report about
     * somebody's document rather than about the code that forgot the second call.
     * {@code Workbench.contribute} refuses it on the spot; what is asserted here is the condition it
     * reads, since building a workbench needs a workspace client this test has no use for.</p>
     */
    @Test
    public void aTypeWithNoFactoryIsIncompleteAndSaysSo() {
        assertNull("a type declaring no factory could never open anything",
                DocumentType.of("half", "Half").forExtensions("half").factory());
        assertNotNull(DocumentType.of("whole", "Whole").document(Stub::new).factory());
    }
}
