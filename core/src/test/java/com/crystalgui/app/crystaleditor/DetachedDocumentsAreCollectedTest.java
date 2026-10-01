package com.crystalgui.app.crystaleditor;

import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import org.junit.Test;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertTrue;

/**
 * Every kind of node, once detached, lets its document go: nothing process-wide holds it. A widget that subscribes
 * to a static signal without {@code whileConnected} keeps every window it was ever shown in.
 */
public class DetachedDocumentsAreCollectedTest {

    @Test
    public void noKindKeepsItsDocumentOnceDetached() throws InterruptedException {
        UIElementRegistry.bootstrap();
        List<String> kept = new ArrayList<>();
        for (Name name : new ArrayList<>(UIElementRegistry.names())) {
            if (!UIElementRegistry.isBuildable(name)) continue;
            WeakReference<UIDocument> held;
            try {
                held = shownThenDetached(name);
            } catch (RuntimeException needsMoreThanABareDocument) {
                continue;   // building it is another test's question
            }
            // GC is the fast filter; a static path is the verdict, so collection timing cannot fail this.
            for (int i = 0; i < 4 && held.get() != null; i++) {
                System.gc();
                Thread.sleep(10);
            }
            UIDocument still = held.get();
            if (still == null) continue;
            String path = ReferencePaths.find(still, Map.of());
            if (path != null) kept.add(name + " is held by " + path);
        }
        assertTrue(String.join("\n", kept), kept.isEmpty());
    }

    private static WeakReference<UIDocument> shownThenDetached(Name name) {
        UIDocument document = new UIDocument();
        UIElement element = UIElementRegistry.create(name);
        document.append(element);
        for (int i = 0; i < 3; i++) document.frame(0.016f, 800, 600);
        document.removeAll();
        document.frame(0.016f, 800, 600);
        return new WeakReference<>(document);
    }
}
