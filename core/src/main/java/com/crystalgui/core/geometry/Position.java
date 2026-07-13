package com.crystalgui.core.geometry;

import lombok.Data;
import org.joml.Vector2f;

import java.util.Objects;

@Data(staticConstructor = "of")
public class Position {
    public static final Position ORIGIN = Position.of(0, 0);

    public final int x;
    public final int y;

    public Position add(Position other) {
        return Position.of(x + other.x, y + other.y);
    }

    public Position add(int x, int y) {
        return Position.of(this.x + x, this.y + y);
    }

    public Position subtract(Position other) {
        return Position.of(x - other.x, y - other.y);
    }

    public Position add(Size size) {
        return Position.of(x + size.width, y + size.height);
    }

    public Position addX(int x) {
        return Position.of(this.x + x,y);
    }

    public Position addY(int y){
        return Position.of(x,this.y + y);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Position)) return false;
        Position position = (Position) o;
        return x == position.x &&
                y == position.y;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y);
    }

    @Override
    public String toString() {
        return String.format("[x=%d, y=%d]", x, y);
    }

    public Vector2f vec2() {
        return new Vector2f(x, y);
    }
}