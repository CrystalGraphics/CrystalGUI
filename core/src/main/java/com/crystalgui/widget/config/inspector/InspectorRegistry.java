package com.crystalgui.widget.config.inspector;

import com.crystalgui.core.data.DataContext;
import com.crystalgui.ui.dom.UIElement;
import javax.annotation.Nullable;
import com.crystalgui.core.signal.Signal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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

    // COPY-ON-WRITE, because the two ends run at different times: an extension registers while a
    // workbench is being built and withdraws while one is being torn down, and `sectionsFor` walks the
    // list every time the inspector's subject changes. Both are the frame thread today, so a plain
    // ArrayList was correct rather than lucky -- but nothing says so, and the cost of the guarantee is a
    // copy per registration, which happens a handful of times per application.
    private static final List<InspectorSection> SECTIONS = new CopyOnWriteArrayList<>();

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

    /**
     * Fires with a subject that has been <b>closed</b> — its editor released, its document dropped.
     *
     * <p>Distinct from {@link #onDidChangeSubject}, and the distinction is the whole point. An inspector
     * RETAINS what it is showing when the source goes detached or when nothing else can be described,
     * deliberately: regions re-parent constantly and the panel must not blank because the question was
     * asked at a bad moment. A closed document is not a bad moment — the subject is gone and will not
     * come back — so it needs a way to say so that the retention rule does not swallow.</p>
     */
    public static final Signal.Value<UIElement> onDidCloseSubject = new Signal.Value<>();

    /** Announces that {@code subject}, and anything inside it, is gone for good. */
    public static void subjectClosed(@Nullable UIElement subject) {
        if (subject != null) onDidCloseSubject.emit(subject);
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
