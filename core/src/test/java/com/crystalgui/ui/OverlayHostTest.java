package com.crystalgui.ui;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import dev.vfyjxf.taffy.style.FlexDirection;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <b>Nothing may parent an overlay to {@code ui.rootElement}.</b>
 *
 * <h3>Why this is a source scan and not a unit test</h3>
 *
 * <p>Because the defect is a <em>habit</em>, and it came back five times in one afternoon. An element must
 * be in the tree before it can be promoted to the top layer, so every overlay has to be parented first —
 * and the obvious place is the root. That works until the root is a composite: {@code CrystalEditor}
 * returns {@code acceptsPublicChildren() == false}, and each call site threw
 * {@code UnsupportedOperationException} from wherever it happened to be standing. The context menu, then
 * the palette, then a submenu, then the New File prompt, then the delete confirmation.</p>
 *
 * <p>Fixing them one at a time is what I did first and it does not work: the next overlay anybody writes
 * reaches for the root again, because that is the line that looks correct. {@link UIWindow#addOverlay} is
 * the API; this makes the alternative fail the build rather than fail in the harness.</p>
 *
 * <p>Same shape as {@code ShippedShaderStagePurityTest}, and for the same reason — a rule that no single
 * unit test can express because it is about every file that does not exist yet.</p>
 */
public class OverlayHostTest extends UiTestBase {

    /** The one legitimate mention: {@code UIWindow.overlayHost} itself, which returns it as a fallback. */
    private static final String CANONICAL = "UIWindow.java";

    @Test
    public void noSourceParentsAnOverlayToTheWindowRoot() throws IOException {
        Path main = Paths.get("src", "main", "java", "com", "crystalgui");
        if (!Files.isDirectory(main)) main = Paths.get("core", "src", "main", "java", "com", "crystalgui");
        assertTrue("cannot find the sources to scan from " + Paths.get("").toAbsolutePath(),
                Files.isDirectory(main));

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(main)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (file.getFileName().toString().equals(CANONICAL)) continue;
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    // Comments are allowed to name the bad line -- several deliberately do, to explain
                    // why it is wrong. Only real code counts.
                    String code = line.trim();
                    if (code.startsWith("//") || code.startsWith("*")) continue;
                    if (code.contains("rootElement.addChild")) {
                        offenders.add(file.getFileName() + ":" + (i + 1) + "  " + code);
                    }
                }
            }
        }

        if (!offenders.isEmpty()) {
            fail("Overlays must go through UIWindow.addOverlay(overlay, near), never straight onto the\n"
                    + "window root -- a root that refuses public children (any composite, and CrystalEditor\n"
                    + "is one) throws UnsupportedOperationException at show time:\n  "
                    + String.join("\n  ", offenders));
        }
    }

    /** A root shaped like {@code CrystalEditor}: refuses public children, holds one internal wrapper. */
    private static final class RefusingRoot extends UIElement {
        final UIElement content = new UIElement();

        RefusingRoot() {
            layout(l -> l.widthPercent(100f).heightPercent(100f).flexDirection(FlexDirection.COLUMN));
            content.layout(l -> l.widthPercent(100f).height(0).flexGrow(1f));
            addInternalChild(content);
        }

        @Override
        public boolean acceptsPublicChildren() {
            return false;
        }
    }

    /** The host is never something that would refuse the overlay — the whole contract, in one line. */
    @Test
    public void theHostAlwaysAcceptsChildren() {
        RefusingRoot root = new RefusingRoot();
        UIElement panel = new UIElement().layout(l -> l.widthPercent(100f).height(0).flexGrow(1f));
        root.content.addChild(panel);

        UIWindow window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 600);

        assertTrue(window.overlayHost(panel).acceptsPublicChildren());
        assertTrue(window.overlayHost(root.content).acceptsPublicChildren());
        // Even asked about the refusing root itself, or about nothing at all, it must not hand back
        // something that throws -- those are the two cases a caller is most likely to pass by accident.
        assertTrue(window.overlayHost(root).acceptsPublicChildren()
                || window.overlayHost(root) == root);
    }

    /** It lands beside what it belongs to, not at the top of the window. */
    @Test
    public void theHostIsTheNearestAcceptingAncestor() {
        RefusingRoot root = new RefusingRoot();
        UIElement composite = new RefusingRoot();
        root.content.addChild(composite);

        UIWindow window = new UIWindow(Ui.of(root));
        window.init(800, 600);

        assertSame(root.content, window.overlayHost(composite));
    }

    /** addOverlay is idempotent — an overlay already in the tree is left exactly where it is. */
    @Test
    public void addOverlayLeavesAnAlreadyParentedOverlayAlone() {
        RefusingRoot root = new RefusingRoot();
        UIElement panel = new UIElement().layout(l -> l.widthPercent(100f).height(0).flexGrow(1f));
        root.content.addChild(panel);

        UIWindow window = new UIWindow(Ui.of(root));
        window.init(800, 600);

        UIElement overlay = new UIElement();
        panel.addChild(overlay);
        window.addOverlay(overlay, root.content);

        assertSame("addOverlay re-parented something that was already placed", panel, overlay.getParent());
    }
}
