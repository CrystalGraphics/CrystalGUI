package com.crystalgui.core.attribute;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A copied object's properties, in the order its owner listed them.
 *
 * <pre>{@code
 * AttributeSet copied = AttributeSet.builder("crystalgui:style", "#save")
 *         .add(new AttributeSlot("opacity", "Appearance", "Opacity"), 0.5f)
 *         .build();
 *
 * AttributeSet chosen = copied.keeping(slot -> wanted.contains(slot.id()));
 * }</pre>
 *
 * <p><b>Ordered, and that is load-bearing for the window rather than for the data.</b> The Paste
 * Attributes dialog lists what it is given; if the order moved between openings, so would every checkbox,
 * and a person ticking the third box twice would tick two different things.</p>
 *
 * <p>Values are opaque {@code Object}s. Nothing between the carrier that produced them and the carrier
 * that consumes them looks inside — the window shows labels, the clipboard holds the set, and only the
 * domain's own code knows what a value means.</p>
 */
public final class AttributeSet {

    private final String domain;

    private final String source;

    private final List<Entry> entries;

    /** One property and its value. */
    public record Entry(AttributeSlot slot, Object value) {
    }

    private AttributeSet(String domain, String source, List<Entry> entries) {
        this.domain = domain;
        this.source = source;
        this.entries = List.copyOf(entries);
    }

    public static Builder builder(String domain, String source) {
        return new Builder(domain, source);
    }

    /** What kind of thing this came off — a paste onto another domain is refused. */
    public String domain() {
        return domain;
    }

    /** What it was copied from, for the window's "From" line. */
    public String source() {
        return source;
    }

    public List<Entry> entries() {
        return entries;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** The group headings, first-seen order — which is the order the window draws them in. */
    public Set<String> groups() {
        Set<String> groups = new LinkedHashSet<>();
        for (Entry entry : entries) groups.add(entry.slot().group());
        return groups;
    }

    /** The same set with only the slots the predicate keeps. */
    public AttributeSet keeping(Predicate<AttributeSlot> keep) {
        List<Entry> kept = new ArrayList<>();
        for (Entry entry : entries) {
            if (keep.test(entry.slot())) kept.add(entry);
        }
        return new AttributeSet(domain, source, kept);
    }

    /** @see AttributeSet */
    public static final class Builder {

        private final String domain;
        private final String source;
        private final List<Entry> entries = new ArrayList<>();

        private Builder(String domain, String source) {
            this.domain = domain;
            this.source = source;
        }

        public Builder add(AttributeSlot slot, Object value) {
            entries.add(new Entry(slot, value));
            return this;
        }

        public AttributeSet build() {
            return new AttributeSet(domain, source, entries);
        }
    }
}
