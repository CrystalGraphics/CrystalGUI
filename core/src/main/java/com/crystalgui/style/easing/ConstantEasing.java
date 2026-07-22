package com.crystalgui.style.easing;

public final class ConstantEasing implements Easing {
    private final double value;

    ConstantEasing(double value) {
        this.value = value;
    }

    @Override
    public double ease(double time) {
        return value;
    }
}
