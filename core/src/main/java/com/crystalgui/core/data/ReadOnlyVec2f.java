package com.crystalgui.core.data;

import org.joml.Vector2f;

public class ReadOnlyVec2f {
    private final Vector2f vec;
    public ReadOnlyVec2f(Vector2f vec) {
        this.vec = vec;
    }

    public float x() {
        return vec.x();
    }
    public float y() {
        return vec.y();
    }

    public Vector2f copy() {
        return new Vector2f(vec);
    }

    public Vector2f copy(Vector2f dest) {
        return dest.set(vec);
    }
}
