package com.crystalgui.language.run.exec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A script named types this deployment refuses — thrown before any of it runs.
 *
 * <h3>An exception, where a stop is an Error</h3>
 *
 * <p>{@link ScriptStoppedException} is an {@code Error} because it has to escape a script's own
 * {@code catch (Exception e)}. This is the opposite situation: nothing of the script is on the stack.
 * It is refused during preparation, so it is an ordinary failure of a host operation and belongs in the
 * same channel as a compile error — which is how the Run panel already reports one.</p>
 *
 * <h3>Every refusal, not the first</h3>
 *
 * <p>A script reaching for one refused class usually reaches for several, and a message naming one at a
 * time turns tightening a policy into a guessing game: fix, re-run, discover the next. The scan has the
 * whole set for free because it walks every class either way.</p>
 */
public final class ScriptRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final List<String> refused;

    public ScriptRefusedException(List<String> refused) {
        super(messageFor(refused));
        this.refused = Collections.unmodifiableList(new ArrayList<>(refused));
    }

    /** The binary names, in the order the scan met them. */
    public List<String> refused() {
        return refused;
    }

    private static String messageFor(List<String> refused) {
        if (refused == null || refused.isEmpty()) return "this script reaches a refused class";
        StringBuilder message = new StringBuilder("this script reaches ")
                .append(refused.size() == 1 ? "a class" : refused.size() + " classes")
                .append(" the deployment's ScriptPolicy refuses: ");
        // CAPPED, because a script that reaches for a whole refused package names a great many of its
        // classes and a console line is not a report. The count above is the honest total either way.
        int shown = Math.min(refused.size(), 8);
        for (int i = 0; i < shown; i++) {
            if (i > 0) message.append(", ");
            message.append(refused.get(i));
        }
        if (shown < refused.size()) message.append(", and ").append(refused.size() - shown).append(" more");
        return message.toString();
    }
}
