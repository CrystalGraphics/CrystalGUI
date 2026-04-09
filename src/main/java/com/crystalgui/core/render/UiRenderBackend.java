package com.crystalgui.core.render;

public interface UiRenderBackend {

    UiPrimitiveRenderer getPrimitiveRenderer();

    UiTextRenderer getTextRenderer();
}
