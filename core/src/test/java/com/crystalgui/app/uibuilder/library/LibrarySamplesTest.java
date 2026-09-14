package com.crystalgui.app.uibuilder.library;

import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.Preview;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;

import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;

/** Every shipped kind the Library lists draws something on its card — a kind whose sample lays out to nothing fails here, by name. */
public class LibrarySamplesTest extends UiDocumentTestBase {

    @Test
    public void everyListedShippedKindDrawsItsSample() {
        UIElementRegistry.bootstrap();
        withDefaultStyles();
        UIElement wall = new UIElement().layout(l -> l.width(W).flexDirection(FlexDirection.ROW).flexWrap(FlexWrap.WRAP));
        document.append(wall);
        Map<Name, PreviewCard> cards = new LinkedHashMap<>();
        for (LibraryCatalog.Entry entry : LibraryCatalog.current().entries()) {
            if (!Name.DEFAULT_NAMESPACE.equals(entry.kind().namespace())) continue;
            PreviewCard card = new PreviewCard(PreviewStyles.of(document).group());
            card.show(entry);
            wall.append(card);
            cards.put(entry.kind(), card);
        }
        PreviewStyles.of(document).sync();
        for (int i = 0; i < 4; i++) frame();

        List<String> empty = new ArrayList<>();
        for (Map.Entry<Name, PreviewCard> each : cards.entrySet()) {
            PreviewCard card = each.getValue();
            boolean picture = card.entry().preview() instanceof Preview.Picture;
            if (!picture && (card.isPlaceholder() || card.sample() == null)) empty.add(each.getKey().local());
        }
        assertTrue("these kinds show a placeholder instead of their sample: " + empty, empty.isEmpty());
    }
}
