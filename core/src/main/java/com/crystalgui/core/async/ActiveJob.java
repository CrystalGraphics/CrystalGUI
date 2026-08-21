package com.crystalgui.core.async;

/**
 * One row of {@link JobScheduler#active()} — a job the chrome should be drawing.
 *
 * <p>A value, taken on the UI thread from state the worker swapped in whole. Nothing here is live: it
 * describes the job as of the frame it was taken in, which is the only thing a widget can safely draw.</p>
 *
 * @param key             what to pass to {@link JobScheduler#cancel} when the row's × is pressed
 * @param state           the reading — text, units, and whether it is determinate
 * @param cancelRequested whether cancellation has been asked for and not yet acknowledged. The row should
 *                        look inert but <b>keep its bar</b>: the work is still running, and a row that
 *                        vanished on the click would claim otherwise
 */
public record ActiveJob(JobKey key, ProgressState state, boolean cancelRequested) {
}
