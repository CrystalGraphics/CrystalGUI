package com.crystalgui.widget.surface.extension;

import java.util.List;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.widget.config.inspector.InspectorRegistry;
import com.crystalgui.widget.config.inspector.InspectorSection;

/**
 * A consumer's inspector sections, put in and taken out as one — and counted, so two open editors do not
 * double the forms.
 *
 * <pre>{@code
 * private static final SectionSet SECTIONS = SectionSet.of(
 *         new PropertySection(), new NodeSection(), new WireSection());
 *
 * public Disposable activate(SurfaceContext surface) {
 *     return SECTIONS.register();
 * }
 * }</pre>
 *
 * <p><b>Instances, not factories.</b> A section reads its subject out of the {@code DataContext} it is
 * handed and holds nothing of its own, so one instance answers for every document in every window. Per
 * call was the first shape and it meant four open editors put twenty sections in the registry and drew
 * four copies of every form.</p>
 *
 * <p><b>Counted, not idempotent</b>, because both answers have to be right: a second editor must not
 * double the forms, and the first editor closing must not empty the inspector under the second one. The
 * sections are registered for exactly as long as at least one holder wants them.</p>
 */
public final class SectionSet {

    private final List<InspectorSection> sections;

    private int holders;

    private SectionSet(List<InspectorSection> sections) {
        this.sections = List.copyOf(sections);
    }

    public static SectionSet of(InspectorSection... sections) {
        return new SectionSet(List.of(sections));
    }

    public List<InspectorSection> sections() {
        return sections;
    }

    /** How many holders currently want them. For a test that asserts the counting. */
    public int holders() {
        return holders;
    }

    /** Puts them in, and hands back the way to take them out again. */
    public synchronized Disposable register() {
        if (holders++ == 0) {
            for (InspectorSection section : sections) InspectorRegistry.register(section);
        }
        return new Disposable() {
            private boolean released;

            @Override
            public void dispose() {
                if (released) return;
                released = true;
                synchronized (SectionSet.this) {
                    if (--holders > 0) return;
                    for (InspectorSection section : sections) InspectorRegistry.remove(section);
                }
            }
        };
    }
}
