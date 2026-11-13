package com.crystalgui.desktop.app;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

import javax.annotation.Nullable;

/**
 * What the shell knows about an application that stopped answering or failed: a value, logged once whole and shown
 * by the fault dialog's Details.
 *
 * <pre>{@code
 * FaultReport report = FaultReport.hung("VFX Lab", thread);     // a document that does not answer
 * report.title();    // "VFX Lab is not responding"
 * report.origin();   // "com.example.vfx.Painter.paint (Painter.java)", the top frame outside the JDK
 * }</pre>
 *
 * @param kind        what happened
 * @param application the application's display name
 * @param origin      the class and method at the top of the stack that is not the JDK's, whose package names the mod; empty when there is none
 * @param stack       the stack, one frame a line
 * @param deadlock    the threads of a lock cycle, or empty
 * @param timeMillis  when
 */
public record FaultReport(Kind kind, String application, String origin, String stack, String deadlock, long timeMillis) {

    /** What a report is about. */
    public enum Kind {
        /** A document with input waiting and no commit. */
        NOT_RESPONDING,
        /** An exception escaped a frame, a task or an event handler of a running application. */
        STOPPED,
        /** The launch, the first window's build or the restoring of state threw, or a class failed to link. */
        COULDNT_START,
        /** A third fault within a minute of restarts: no Restart is offered, so it cannot loop. */
        KEEPS_STOPPING
    }

    /** The headline: what the dialog says. */
    public String title() {
        return switch (kind) {
            case NOT_RESPONDING -> application + " is not responding";
            case STOPPED -> application + " has stopped working";
            case COULDNT_START -> application + " couldn't start";
            case KEEPS_STOPPING -> application + " keeps stopping";
        };
    }

    /** A report on {@code thread}'s stack, taken now; the thread may be null when the document is idle. */
    public static FaultReport hung(String application, @Nullable Thread thread) {
        StackTraceElement[] frames = thread == null ? new StackTraceElement[0] : thread.getStackTrace();
        StringBuilder stack = new StringBuilder();
        String origin = "";
        for (StackTraceElement frame : frames) {
            stack.append("at ").append(frame).append('\n');
            if (origin.isEmpty() && !isJdk(frame.getClassName())) origin = describe(frame);
        }
        return new FaultReport(Kind.NOT_RESPONDING, application, origin, stack.toString(), lockCycle(),
                System.currentTimeMillis());
    }

    /**
     * A report on an exception: the exception's class and message are the headline's reason, its stack the report's.
     *
     * <pre>{@code
     * try {
     *     launch();
     * } catch (RuntimeException | LinkageError failed) {
     *     FaultReport report = FaultReport.of(Kind.COULDNT_START, "VFX Lab", failed);
     *     report.reason();        // the first line: "IllegalStateException: no shader"
     * }
     * }</pre>
     */
    public static FaultReport of(Kind kind, String application, Throwable cause) {
        StringBuilder stack = new StringBuilder();
        String origin = "";
        for (Throwable at = cause; at != null; at = at.getCause() == at ? null : at.getCause()) {
            if (at != cause) stack.append("caused by: ");
            stack.append(at).append('\n');
            for (StackTraceElement frame : at.getStackTrace()) {
                stack.append("\tat ").append(frame).append('\n');
                if (origin.isEmpty() && !isJdk(frame.getClassName())) origin = describe(frame);
            }
        }
        return new FaultReport(kind, application, origin, stack.toString(), "", System.currentTimeMillis());
    }

    /** This report as another kind: a fault that repeats becomes {@link Kind#KEEPS_STOPPING}. */
    public FaultReport as(Kind other) {
        return new FaultReport(other, application, origin, stack, deadlock, timeMillis);
    }

    /** The first line of the stack: the exception's class and message; empty for a hang. */
    public String reason() {
        if (kind == Kind.NOT_RESPONDING) return "";
        int end = stack.indexOf("\n");
        return end < 0 ? stack : stack.substring(0, end);
    }

    /** The whole report as text, for Copy and the log. */
    public String text() {
        StringBuilder out = new StringBuilder(title()).append('\n');
        if (!origin.isEmpty()) out.append("in ").append(origin).append('\n');
        if (!stack.isEmpty()) out.append(stack);
        if (!deadlock.isEmpty()) out.append("deadlock:\n").append(deadlock);
        return out.toString();
    }

    private static boolean isJdk(String className) {
        return className.startsWith("java.") || className.startsWith("jdk.") || className.startsWith("sun.");
    }

    private static String describe(StackTraceElement frame) {
        // The class names the mod by its package: a Java 8 runtime has no module to ask.
        return frame.getClassName() + "." + frame.getMethodName() + (frame.getFileName() == null ? "" : " (" + frame.getFileName() + ")");
    }

    /** The threads in a lock cycle, or empty. */
    private static String lockCycle() {
        try {
            ThreadMXBean threads = ManagementFactory.getThreadMXBean();
            long[] stuck = threads.findDeadlockedThreads();
            if (stuck == null) return "";
            StringBuilder out = new StringBuilder();
            for (ThreadInfo info : threads.getThreadInfo(stuck)) {
                if (info != null) out.append(info.getThreadName()).append(" waits for ").append(info.getLockName())
                        .append(" held by ").append(info.getLockOwnerName()).append('\n');
            }
            return out.toString();
        } catch (RuntimeException | LinkageError unavailable) {
            return "";
        }
    }
}
