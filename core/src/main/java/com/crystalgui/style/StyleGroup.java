package com.crystalgui.style;

import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StyleSlot;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.function.Consumer;

public abstract class StyleGroup<TYPE extends StyleGroup<TYPE>> {

    @Getter
    public final ElementStyle style;

    @Getter
    @Accessors(chain = true)
    private StyleOrigin priority = StyleOrigin.INLINE;

    public StyleGroup(ElementStyle style) {
        this.style = style;
    }

    public final <T> void set(StyleProperty<T> property, T value) {
        set(priority, property, value);
    }

    public final <T> void set(StyleOrigin origin, StyleProperty<T> property, T value) {
        if (value == null) {
            style.removeCandidates(property, slot -> slot.origin() == origin &&
                    slot.specificity() == 0 &&
                    slot.sourceOrder() == 0);
            return;
        }
        style.replaceOrPutCandidate(property, StyleSlot.of(
                property,
                origin,
                0, 0,
                value
        ));
    }

    @Nullable
    public <T> T getValue(StyleProperty<T> property, StyleOrigin origin) {
        if (!style.candidates.containsKey(property)) return null;
        return cast(style.candidates.get(property).stream()
                .filter(slot -> slot.origin() == origin)
                .sorted(((a, b) -> StyleSlot.compare(b, a)))
                .map(StyleSlot::value)
                .findFirst()
                .orElse(null));
    }

    public <T> Optional<T> getValue(StyleProperty<T> property) {
        return Optional.ofNullable(style.getComputed(property));
    }

    public <T> T getValueSave(StyleProperty<T> property) {
        var value = style.getComputed(property);
        if (value != null) return value;
        return property.initialValue;
    }

    public <T> void setDefault(StyleProperty<T> property, T value) {
        set(StyleOrigin.DEFAULT, property, value);
    }

    @Nullable
    public <T> T getDefault(StyleProperty<T> property) {
        return getValue(property, StyleOrigin.DEFAULT);
    }

    public <T> void setInline(StyleProperty<T> property, T value) {
        set(StyleOrigin.INLINE, property, value);
    }

    @Nullable
    public <T> T getInline(StyleProperty<T> property) {
        return getValue(property, StyleOrigin.INLINE);
    }

    public <T> void setImportant(StyleProperty<T> property, T value) {
        set(StyleOrigin.IMPORTANT, property, value);
    }

    @Nullable
    public <T> T getImportant(StyleProperty<T> property) {
        return getValue(property, StyleOrigin.IMPORTANT);
    }


    @SuppressWarnings("unchecked")
    public TYPE setPriority(StyleOrigin origin) {
        this.priority = origin;
        return (TYPE) this;
    }

    public static <T extends StyleGroup<?>> T pipeline(StyleOrigin pipelineState, T style, Consumer<T> styleConsumer) {
        var previousPipeline = style.getPriority();
        style.setPriority(pipelineState);
        styleConsumer.accept(style);
        style.setPriority(previousPipeline);
        return style;
    }

    public static <T extends StyleGroup<?>> T importantPipeline(T style, Consumer<T> styleConsumer) {
        return pipeline(StyleOrigin.IMPORTANT, style, styleConsumer);
    }

    public static <T extends StyleGroup<?>> T inlinePipeline(T style, Consumer<T> styleConsumer) {
        return pipeline(StyleOrigin.INLINE, style, styleConsumer);
    }

    public static <T extends StyleGroup<?>> T defaultPipeline(T style, Consumer<T> styleConsumer) {
        return pipeline(StyleOrigin.DEFAULT, style, styleConsumer);
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object o) { return (T) o; }
}
