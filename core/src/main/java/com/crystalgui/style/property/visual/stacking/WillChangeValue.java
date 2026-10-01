package com.crystalgui.style.property.visual.stacking;

import com.crystalgui.style.property.StyleValue;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Parses CSS {@code will-change}: {@code auto}, or a comma-separated list of the properties a box expects to change.
 * A name with no {@link WillChange} is skipped, as CSS skips any property it was not asked to prepare for.
 */
public final class WillChangeValue extends StyleValue<Set<WillChange>> {

    public WillChangeValue(String rawValue) {
        super(rawValue);
    }

    @Override
    protected Set<WillChange> doCompute(String raw) {
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty() || trimmed.equals("auto")) return Collections.emptySet();

        EnumSet<WillChange> changes = EnumSet.noneOf(WillChange.class);
        for (String token : trimmed.split(",")) {
            String name = token.trim();
            for (WillChange change : WillChange.values()) {
                if (change.name().replace('_', '-').toLowerCase(Locale.ROOT).equals(name)) changes.add(change);
            }
        }
        return Collections.unmodifiableSet(changes);
    }
}
