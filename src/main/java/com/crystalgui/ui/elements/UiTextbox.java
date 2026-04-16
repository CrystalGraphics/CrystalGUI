package com.crystalgui.ui.elements;

import com.crystalgui.core.event.CgUiDebug;
import com.crystalgui.core.event.CgUiKeyCodes;
import com.crystalgui.core.event.Modifiers;
import com.crystalgui.core.event.UiEventType;
import com.crystalgui.core.event.UiKeyEvent;
import com.crystalgui.core.geometry.UiRect;
import com.crystalgui.core.input.FocusPolicy;
import com.crystalgui.core.property.Property;
import com.crystalgui.core.render.CgUiPaintContext;
import com.crystalgui.core.signal.Signal;
import io.github.somehussar.crystalgraphics.api.font.CgFontFamily;
import lombok.Getter;

import javax.annotation.Nullable;

/**
 * Single-line text input widget.
 *
 * <p>Accepts keyboard input when focused, supports caret movement (left/right/home/end),
 * backspace/delete, and select-all (Ctrl+A). Visually highlights when focused by drawing
 * a contrasting border color.</p>
 *
 * <p>Text content is exposed as a {@link Property} and changes emit through
 * {@link #textChanged} signal. An {@link #submitted} signal fires on Enter.</p>
 */
public class UiTextbox extends UiPanel {

    private static final int PADDING = 4;

    private final Property<String> textProperty;
    public final Signal.Action submitted = new Signal.Action();
    public final Signal.Value<String> textChanged = new Signal.Value<>();

    @Nullable
    private CgFontFamily fontFamily;
    private int textColor;
    private int focusBorderColor;

    @Getter
    private int caretPosition;
    private boolean focused;

    public UiTextbox(int bgColor, int textColor, int focusBorderColor) {
        super(bgColor);
        this.textColor = textColor;
        this.focusBorderColor = focusBorderColor;
        this.textProperty = new Property<>("");
        setFocusPolicy(FocusPolicy.CLICK);
        wireEventListeners();
    }

    public UiTextbox(int bgColor, int textColor, int focusBorderColor, @Nullable CgFontFamily fontFamily) {
        this(bgColor, textColor, focusBorderColor);
        this.fontFamily = fontFamily;
    }

    // ── Property accessors ──

    public Property<String> textProperty() { return textProperty; }
    public String getText() { return textProperty.get(); }

    public void setText(String text) {
        textProperty.set(text != null ? text : "");
        caretPosition = Math.min(caretPosition, textProperty.get().length());
        markRenderDirty();
    }

    public void setFontFamily(CgFontFamily fontFamily) {
        this.fontFamily = fontFamily;
        markRenderDirty();
    }

    public void setTextColor(int color) {
        this.textColor = color;
        markRenderDirty();
    }

    // ── Event wiring ──

    private void wireEventListeners() {
        addEventListener(UiEventType.FOCUS_IN, event -> {
            focused = true;
            markRenderDirty();
        });

        addEventListener(UiEventType.FOCUS_OUT, event -> {
            focused = false;
            markRenderDirty();
        });

        addEventListener(UiEventType.KEY_DOWN, event -> {
            if (!(event instanceof UiKeyEvent)) return;
            UiKeyEvent ke = (UiKeyEvent) event;
            handleKeyDown(ke);
        });

        addEventListener(UiEventType.KEY_TYPED, event -> {
            if (!(event instanceof UiKeyEvent)) return;
            UiKeyEvent ke = (UiKeyEvent) event;
            handleCharTyped(ke.getCharacter());
        });
    }

    private void handleKeyDown(UiKeyEvent event) {
        String text = textProperty.get();
        int keyCode = event.getKeyCode();

        switch (keyCode) {
            case CgUiKeyCodes.KEY_BACKSPACE:
                if (caretPosition > 0) {
                    String newText = text.substring(0, caretPosition - 1) + text.substring(caretPosition);
                    caretPosition--;
                    applyTextChange(newText);
                }
                break;
            case CgUiKeyCodes.KEY_DELETE:
                if (caretPosition < text.length()) {
                    String newText = text.substring(0, caretPosition) + text.substring(caretPosition + 1);
                    applyTextChange(newText);
                }
                break;
            case CgUiKeyCodes.KEY_LEFT:
                if (caretPosition > 0) {
                    caretPosition--;
                    markRenderDirty();
                }
                break;
            case CgUiKeyCodes.KEY_RIGHT:
                if (caretPosition < text.length()) {
                    caretPosition++;
                    markRenderDirty();
                }
                break;
            case CgUiKeyCodes.KEY_HOME:
                if (caretPosition != 0) {
                    caretPosition = 0;
                    markRenderDirty();
                }
                break;
            case CgUiKeyCodes.KEY_END:
                if (caretPosition != text.length()) {
                    caretPosition = text.length();
                    markRenderDirty();
                }
                break;
            case CgUiKeyCodes.KEY_ENTER:
                CgUiDebug.logSignalEmit("UiTextbox.submitted", text);
                submitted.emit();
                break;
            case CgUiKeyCodes.KEY_A:
                if (event.hasCtrl()) {
                    caretPosition = text.length();
                    markRenderDirty();
                }
                break;
            default:
                break;
        }
    }

    private void handleCharTyped(char c) {
        if (c < 0x20 || c == 0x7F) return; // ignore control chars
        String text = textProperty.get();
        String newText = text.substring(0, caretPosition) + c + text.substring(caretPosition);
        caretPosition++;
        applyTextChange(newText);
    }

    private void applyTextChange(String newText) {
        String oldText = textProperty.get();
        textProperty.set(newText);
        if (!oldText.equals(newText)) {
            textChanged.emit(newText);
            CgUiDebug.logPropertySet("UiTextbox.text", oldText, newText);
        }
        markRenderDirty();
    }

    // ── Drawing ──

    @Override
    public void draw(CgUiPaintContext ctx) {
        UiRect box = getLayoutState().getLayoutBox();
        if (box.getWidth() <= 0 || box.getHeight() <= 0) return;

        float x = box.getX();
        float y = box.getY();
        float w = box.getWidth();
        float h = box.getHeight();

        // Background
        ctx.fillRect(x, y, w, h, getColor());

        // Focus border (2px inset)
        if (focused) {
            float b = 2;
            ctx.fillRect(x, y, w, b, focusBorderColor);           // top
            ctx.fillRect(x, y + h - b, w, b, focusBorderColor);   // bottom
            ctx.fillRect(x, y + b, b, h - 2 * b, focusBorderColor); // left
            ctx.fillRect(x + w - b, y + b, b, h - 2 * b, focusBorderColor); // right
        }

        // Text
        String text = textProperty.get();
        if (text != null && !text.isEmpty() && ctx.hasTextServices()) {
            CgFontFamily font = fontFamily != null ? fontFamily : ctx.getDefaultFontFamily();
            if (font != null) {
                float textY = y + h * 0.5f + 4; // approximate vertical center
                ctx.drawText(font, text, x + PADDING, textY, textColor);
            }
        }

        // Caret (1px wide line when focused)
        if (focused) {
            float caretX = x + PADDING + measureTextWidth(ctx, text, caretPosition);
            float caretY1 = y + 4;
            float caretY2 = y + h - 4;
            ctx.fillRect(caretX, caretY1, 1, caretY2 - caretY1, textColor);
        }
    }

    private float measureTextWidth(CgUiPaintContext ctx, String text, int endIndex) {
        if (text == null || endIndex <= 0 || !ctx.hasTextServices()) return 0;
        CgFontFamily font = fontFamily != null ? fontFamily : ctx.getDefaultFontFamily();
        if (font == null) return 0;
        String prefix = text.substring(0, Math.min(endIndex, text.length()));
        return ctx.measureTextWidth(font, prefix);
    }
}
