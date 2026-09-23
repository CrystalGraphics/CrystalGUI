package com.crystalgui.app.frameprofiler;

import com.crystalgraphics.trace.CgFrameImages;
import com.crystalgraphics.trace.CgFrameRecord;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.text.UIText;
import dev.vfyjxf.taffy.style.FlexDirection;

import javax.annotation.Nullable;

/**
 * What was on screen at the selected frame — its captured image, or the nearest earlier one, and which
 * frame that is.
 *
 * <pre>{@code
 * ScreenTab tab = new ScreenTab();
 * CgFrameRecord frame = model.selectedFrame();
 * tab.show(frame, frame == null ? null : model.imageAtOrBefore(frame.index()));   // never from a paint
 * }</pre>
 */
public class ScreenTab extends UIElement {

    public static final Name NAME = Name.of("screentab");

    public static final String CAPTION_CLASS = "__screen-caption__";

    private final UIText caption = new UIText("");
    private final FrameImageView view = new FrameImageView();

    public ScreenTab() {
        super(NAME);
        layout(l -> l.flexDirection(FlexDirection.COLUMN));
        caption.addClass(CAPTION_CLASS);
        append(caption, view);
    }

    public FrameImageView view() {
        return view;
    }

    public String captionText() {
        return caption.getText();
    }

    public void show(@Nullable CgFrameRecord frame, @Nullable CgFrameImages.Image image) {
        view.show(image);
        setCaption(describe(frame, image));
    }

    private static String describe(@Nullable CgFrameRecord frame, @Nullable CgFrameImages.Image image) {
        if (image == null) {
            if (!CgTrace.isEnabled(CgFrameImages.IMAGES)) {
                return "No frame images: tick ‘images’ in the channel menu to capture one every "
                        + CgFrameImages.interval() + " frames.";
            }
            return frame == null ? "No frame selected." : "No image at or before frame #" + frame.index()
                    + " yet: one is taken every " + CgFrameImages.interval() + " frames.";
        }
        if (frame == null || image.frameIndex() == frame.index()) return "Frame #" + image.frameIndex();
        long before = frame.index() - image.frameIndex();
        // SAID, because the picture is not of the frame selected: a frame between two captures shows the
        // earlier, and a reader must not take it for this one.
        return "Frame #" + image.frameIndex() + ", the nearest image — " + before
                + (before == 1 ? " frame" : " frames") + " before #" + frame.index();
    }

    private void setCaption(String text) {
        if (!text.equals(caption.getText())) caption.setText(text);
    }
}
