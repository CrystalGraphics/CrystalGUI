package com.crystalgui.geometry;


import lombok.Data;

import java.util.Objects;

@Data(staticConstructor = "of")
public class Size {
    public static final Size ZERO = Size.of(0, 0);

    public final int width;
    public final int height;

    public static Size add(Position position) {
        return Size.of(position.x, position.y);
    }

    public Size add(Size other) {
        return Size.of(width + other.width, height + other.height);
    }

    public Size add(int width, int height) {
        return Size.of(this.width + width, this.height + height);
    }

    public Size subtract(Size other) {
        return Size.of(width - other.width, height - other.height);
    }

    public Size addWidth(int width) {
        return Size.of(this.width + width, height);
    }

    public Size addHeight(int height) {
        return Size.of(width, this.height + height);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Size)) return false;
        Size size = (Size) o;
        return width == size.width &&
                height == size.height;
    }

    @Override
    public int hashCode() {
        return Objects.hash(width, height);
    }

    @Override
    public String toString() {
        return width + "x" + height;
    }
}