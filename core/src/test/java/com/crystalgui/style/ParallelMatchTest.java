package com.crystalgui.style;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.junit.After;
import org.junit.Test;

import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.Checkbox;
import com.crystalgui.widget.control.Slider;
import com.crystalgui.widget.control.Switch;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.text.UIText;

/** A round matched on workers styles every element exactly as one matched on the frame thread. */
public class ParallelMatchTest extends UiDocumentTestBase {

    private static final String SHEET = """
            .panel { padding: 4px; background-color: #202020; }
            .panel.wide { width: 50%; }
            .row button { color: #ff8800; font-size: 1.5em; }
            .row:disabled text { color: #808080; }
            .deep .deep .deep text { font-size: 14; }
            textfield:focus { outline-color: #00ff00; }
            """;

    @After
    public void restoreThreshold() {
        StyleEngine.setParallelMin(64);
    }

    /** Panels of rows of widgets, varied enough that most of a round's matches are distinct. */
    private static UIElement build() {
        UIElement root = new UIElement();
        for (int p = 0; p < 12; p++) {
            UIElement panel = new UIElement().addClass("panel");
            if (p % 3 == 0) panel.addClass("wide");
            UIElement nest = panel;
            for (int d = 0; d < p % 4; d++) {
                UIElement deeper = new UIElement().addClass("deep");
                nest.append(deeper);
                nest = deeper;
            }
            for (int r = 0; r < 6; r++) {
                UIElement row = new UIElement().addClass("row").addClass("r" + r);
                if (r == 2) row.setEnabled(false);
                row.append(new Button("b" + p + r));
                row.append(new Checkbox("c" + p + r));
                row.append(new UIText("t" + p + r));
                if (r % 2 == 0) row.append(new TextField());
                if (r % 3 == 0) row.append(new Switch());
                if (r == 5) row.append(new Slider());
                nest.append(row);
            }
            root.append(panel);
        }
        return root;
    }

    private static List<UIElement> styled(int parallelMin) {
        StyleEngine.setParallelMin(parallelMin);
        UIDocument document = new UIDocument();
        document.styles().addStylesheet(StyleSheet.DEFAULT);
        document.styles().addStylesheet(StyleSheet.parse(SHEET));
        document.append(build());
        document.update(W, H);
        List<UIElement> all = new ArrayList<>();
        for (UIElement element : document.composedSubtree()) all.add(element);
        return all;
    }

    @Test
    public void everyPropertyOfEveryElementAgrees() {
        List<UIElement> serial = styled(0);
        List<UIElement> parallel = styled(1);
        assertEquals("the two trees differ in shape", serial.size(), parallel.size());
        assertTrue("too small a tree to exercise the workers: " + serial.size(), serial.size() > 300);

        List<String> differences = new ArrayList<>();
        for (int i = 0; i < serial.size(); i++) {
            for (StyleProperty<?> property : StylePropertyRegistry.all()) {
                Object expected = serial.get(i).computedStyle().get(property);
                Object actual = parallel.get(i).computedStyle().get(property);
                if (!Objects.equals(expected, actual) && differences.size() < 20) {
                    differences.add(serial.get(i).tagName() + " #" + i + " " + property.name + ": " + expected + " vs " + actual);
                }
            }
        }
        assertTrue("matched on workers, styles differ:\n" + String.join("\n", differences), differences.isEmpty());
    }
}
