package com.crystalgui.desktop.taskbar;

import com.crystalgui.core.storage.InMemoryConfigStorage;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.DesktopCommands;
import com.crystalgui.desktop.app.Application;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.fs.Resource;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class EntryArrivalTest extends UiDocumentTestBase {
    private static ApplicationKind kindOf(String id, String name) {
        ApplicationKind kind = ApplicationKind.of(id, name).standalone();
        return kind.launch(context -> {
            WindowFrame frame = new WindowFrame(name);
            frame.setApplication(kind);
            context.desktop().addWindow(frame);
            return new Stub(kind, frame);
        });
    }

    /** Visible entries never overlap: a new one is measured out of flow at the strip's start, and was drawn there. */
    @After
    public void animationsBackOff() {
        Desktop.setAnimationsEnabled(false);
    }

    @Test
    public void aNewEntryIsNeverSeenOverItsNeighboursWhileItIsMeasured() {
        Desktop.setAnimationsEnabled(false);
        viewport(800f, 600f);
        document.append(new UIElement().layout(l -> l.width(800).height(600)));
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        Desktop desktop = Desktop.of(document);
        ApplicationKind ce = kindOf("t:ce", "Editor"), fp = kindOf("t:fp", "Profiler"), lab = kindOf("t:lab", "VFX Lab"), cues = kindOf("t:cues", "Cues");
        for (var k : new ApplicationKind[]{ce, fp, lab, cues}) desktop.applications().install(k);
        desktop.persistTo(new InMemoryConfigStorage(), "pins");
        desktop.pins().pin(ce);
        for (int i = 0; i < 10; i++) frame();
        DesktopCommands.open(desktop, cues);
        DesktopCommands.open(desktop, fp);
        for (int i = 0; i < 30; i++) frame();
        Desktop.setAnimationsEnabled(true);
        DesktopCommands.open(desktop, lab);
        for (int f = 0; f < 20; f++) {
            float edge = Float.NEGATIVE_INFINITY;
            for (UIElement e : desktop.taskbar().entryRow()) {
                var box = e.box();
                if (box == null || box.opacity() < 0.01f) continue;
                assertTrue("frame " + f + ": an entry is drawn over its neighbour (" + box.worldX() + " < " + edge + ")",
                        box.worldX() >= edge - 1f);
                edge = box.worldX() + box.width();
            }
            frame(0.016f);
        }
    }

    private record Stub(ApplicationKind kind, WindowFrame window) implements Application {
        @Override public WindowFrame mainWindow() { return window; }
        @Override public boolean open(Resource r) { return false; }
        @Override public void activate() { }
        @Override public void dispose() { }
    }
}
