package com.crystalgui.workbench.editor;

import com.crystalgui.document.BytesDocumentModel;
import com.crystalgui.document.DocumentEditor;
import com.crystalgui.fs.Resource;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.text.UIText;

import java.util.Locale;

/**
 * What a file with no text in it looks like — its name, its type and its size.
 *
 * <p>The view for a {@link BytesDocumentModel}: an image, an archive, a class file, anything a NUL
 * sniff refused to treat as text. It offers no way to edit, which is the point — a text editor over
 * binary shows a screenful of replacement characters and is writable, so the first {@code Ctrl+S}
 * writes those characters back over the file.</p>
 *
 * <p>VS Code's binary editor and IntelliJ's "file is not displayable" occupy the same slot and say
 * about as much. Saying the size is worth the line: it is the one fact a reader can act on, and it
 * distinguishes an empty file from one this build cannot show.</p>
 */
public final class BinaryFileView implements DocumentEditor {

    /** The panel. `ua/editor.css` sizes it. */
    public static final String CLASS = "__binary-file__";

    /** The line naming the file, so a theme can give it the weight a heading has. */
    public static final String NAME_CLASS = "__binary-file-name__";

    /** The line underneath it — type and size. */
    public static final String DETAIL_CLASS = "__binary-file-detail__";

    private final UIElement root = new UIElement();

    public BinaryFileView(Resource resource, BytesDocumentModel model) {
        root.addClass(CLASS);

        UIText name = new UIText(resource.name());
        name.addClass(NAME_CLASS);
        root.append(name);

        UIText detail = new UIText(describe(resource, model.size()));
        detail.addClass(DETAIL_CLASS);
        root.append(detail);
    }

    @Override
    public UIElement view() {
        return root;
    }

    private static String describe(Resource resource, int bytes) {
        String extension = resource.extension();
        String kind = extension.isEmpty()
                ? "Binary file" : extension.toUpperCase(Locale.ROOT) + " file";
        return kind + " — " + size(bytes) + ", not shown";
    }

    /**
     * {@code 1.4 MB}. Powers of 1024 with decimal names, which is what every file manager on every
     * platform shows and therefore what a reader will compare this against.
     */
    static String size(int bytes) {
        if (bytes < 1024) return bytes + (bytes == 1 ? " byte" : " bytes");
        String[] units = {"KB", "MB", "GB"};
        double scaled = bytes;
        int unit = -1;
        while (scaled >= 1024d && unit < units.length - 1) {
            scaled /= 1024d;
            unit++;
        }
        // ONE DECIMAL BELOW TEN, none above: "1.4 MB" carries information and "847.3 KB" is three digits
        // of precision nobody reads off a file listing.
        return scaled < 10d
                ? String.format(Locale.ROOT, "%.1f %s", scaled, units[unit])
                : String.format(Locale.ROOT, "%.0f %s", scaled, units[unit]);
    }
}
