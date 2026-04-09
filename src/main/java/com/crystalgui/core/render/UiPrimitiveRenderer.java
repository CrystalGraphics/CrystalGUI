package com.crystalgui.core.render;

import com.crystalgui.core.geometry.UiRect;

public interface UiPrimitiveRenderer {

    void fillRect(UiRenderContext context, UiRect rect, int argb);
}
