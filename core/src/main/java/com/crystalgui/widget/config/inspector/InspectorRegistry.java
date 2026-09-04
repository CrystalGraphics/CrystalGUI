package com.crystalgui.widget.config.inspector;

import com.crystalgui.core.data.DataContext;
import com.crystalgui.core.signal.Signal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Every {@link InspectorSection} anything has contributed.
 *
 * <p>Global and explicit, for the reasons commands are: what can describe a subject is a fact about the
 * application rather than about a window, and nothing self-registers — a registry that quietly acquired
 * sections nobody asked for surprises the thing that enumerates it, which here is every inspector.</p>
 *
 * <p>Modelled on {@code FileDecorations}, which this codebase already had and did not generalise:
 * independent providers, asked rather than told, merged at read time.</p>
 */
public final class InspectorRegistry {

    private InspectorRegistry() {
    }

    private static final List<InspectorSection> SECTIONS = new ArrayList<>();

    /**
     * Something that is inspected has changed — re-ask.
     *
     * <p>Blender's notifier system, minus the categories: an operator changes data and posts a notifier,
     * and the Properties editor redraws because it was listening rather than because the operator knew it
     * existed. Emitting is how a widget says "what I show has moved" without holding an inspector.</p>
     *
     * <p>Every {@link Inspector} listens and re-inspects — deferred to the next frame and deduplicated by
     * subject, so emitting on every mouse-move costs one rebuild at most, and none when nothing moved.</p>
     */
    public static final Signal.Action onDidChangeSubject = new Signal.Action();

    /** Announces that whatever is being inspected may have changed. Cheap; call it freely. */
    public static void subjectChanged() {
        onDidChangeSubject.emit();
    }

    /** Idempotent per instance, so a contribution that runs twice does not double every form. */
    public static void register(InspectorSection section) {
        if (section == null || SECTIONS.contains(section)) return;
        SECTIONS.add(section);
    }

    /**
     * Withdraws a section, for a contribution that is being taken down.
     *
     * <p>Registration is per class in the sense that matters — a section is a view over whatever the
     * {@code DataContext} answers and holds nothing of its own — but it is <em>performed</em> by
     * whoever installs the contribution, and an application that is closing has to be able to undo
     * what it did. Without this the sections of a package nobody has open any more stay in the list,
     * and every one of them is asked about every subject for the life of the process.</p>
     *
     * @return whether it was registered
     */
    public static boolean remove(InspectorSection section) {
        return SECTIONS.remove(section);
    }

    /**
     * The sections that apply to {@code context}, in tab then declared order.
     *
     * <p>Asking is the whole mechanism: the inspector never decides what a subject is, it collects
     * whatever answered yes.</p>
     */
    public static List<InspectorSection> sectionsFor(DataContext context) {
        List<InspectorSection> found = new ArrayList<>();
        for (InspectorSection section : SECTIONS) {
            if (section.accepts(context)) found.add(section);
        }
        found.sort(Comparator.comparing(InspectorSection::tab)
                .thenComparingInt(InspectorSection::order));
        return found;
    }

    /** Every registered section, in registration order. For diagnostics. */
    public static List<InspectorSection> all() {
        return List.copyOf(SECTIONS);
    }

    /** Empties the registry. For tests that need isolation, never for production. */
    public static void resetForTesting() {
        SECTIONS.clear();
    }
}
