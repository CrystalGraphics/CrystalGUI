package com.crystalgui.app.uibuilder.library;

import java.util.List;

import javax.annotation.Nullable;

import dev.vfyjxf.taffy.style.TaffyDisplay;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.scroll.ScrollerView;
import com.crystalgui.widget.text.UIText;

/**
 * The Library's foot: the selected kind drawn larger, with its name, its tag and what it is for. Hidden while
 * nothing is selected. As tall as its preview however narrow the panel: the words scroll beside it.
 *
 * <pre>{@code
 * panel.onSelect.connect(detail::show);
 * }</pre>
 */
public final class LibraryDetail extends UIElement {

    public static final Name NAME = Name.of("librarydetail");

    public static final String NAME_CLASS = "__detail-name__";
    public static final String TAG_CLASS = "__detail-tag__";
    public static final String DESCRIPTION_CLASS = "__detail-description__";
    public static final String WORDS_CLASS = "__detail-words__";
    public static final String PREVIEW_CLASS = "__detail-preview__";

    private final UIElement preview = new UIElement();
    private final UIText name = new UIText("");
    private final UIText tag = new UIText("");
    private final UIText description = new UIText("");

    @Nullable
    private PreviewCard card;

    @Nullable
    private LibraryCatalog.Entry shown;

    public LibraryDetail() {
        super(NAME);
        name.addClass(NAME_CLASS);
        tag.addClass(TAG_CLASS);
        description.addClass(DESCRIPTION_CLASS);
        preview.addClass(PREVIEW_CLASS);
        UIElement words = new UIElement().addClass(WORDS_CLASS);
        words.append(name, tag, description);
        ScrollerView scroller = new ScrollerView();
        scroller.append(words);
        append(preview, scroller);
        show(null);
    }

    /** Describes {@code entry}, or hides the strip for null. */
    public void show(@Nullable LibraryCatalog.Entry entry) {
        shown = entry;
        display(this, entry != null);
        if (entry == null) return;
        name.setText(entry.label());
        tag.setText(entry.kind().toString());
        String about = entry.info().description();
        description.setText(about == null ? "" : about);
        display(description, about != null);
        // THE CARD NEEDS THE WINDOW'S GROUP, so it is made on the first show inside one.
        UIDocument window = document();
        if (card == null && window != null) {
            card = new PreviewCard(PreviewStyles.of(window).group());
            preview.append(card);
        }
        if (card != null) card.show(entry);
    }

    @Nullable
    public LibraryCatalog.Entry shown() {
        return shown;
    }

    /** The larger preview, or null before the first show inside a window. */
    @Nullable
    public PreviewCard card() {
        return card;
    }

    public String descriptionText() {
        return description.getText();
    }

    private static void display(UIElement element, boolean visible) {
        StyleGroup.inlinePipeline(element.getStyle().getLayoutGroup(),
                l -> l.display(visible ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
    }

    /** None: the parts are rebuilt by the constructor. */
    @Override
    public List<UIElement> describedChildren() {
        return List.of();
    }
}
