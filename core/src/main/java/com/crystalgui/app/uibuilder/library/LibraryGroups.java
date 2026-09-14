package com.crystalgui.app.uibuilder.library;

import java.util.List;

import com.crystalgui.app.uibuilder.library.LibraryCatalog.Group;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.Checkbox;
import com.crystalgui.widget.control.Slider;
import com.crystalgui.widget.control.Switch;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.text.UIText;

/**
 * The groups the Library lists ahead of its categories.
 *
 * <pre>{@code
 * LibraryCatalog.of(kinds, UIElementRegistry::infoOf, LibraryGroups.SHIPPED);
 * }</pre>
 */
public final class LibraryGroups {

    /** What most documents start from. */
    public static final Group COMMON = new Group("Common", List.<Name>of(
            UIElement.NAME, UIText.NAME, Button.NAME, TextField.NAME, Checkbox.NAME, Switch.NAME, Slider.NAME));

    public static final List<Group> SHIPPED = List.of(COMMON);

    private LibraryGroups() {
    }
}
