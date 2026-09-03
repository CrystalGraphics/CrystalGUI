/**
 * <b>What the two halves say to each other</b> — a record and a {@code Codec} per message.
 *
 * <p>Empty until F2 of {@code plan_fs_rewrite.md}. It replaces {@code WorkspaceProtocol}'s sixty string
 * constants and the 230 lines of hand-packed {@code StateMap} puts mirrored by hand at the other end,
 * which is where a field could be written on one side and never read on the other with nothing to say
 * so.</p>
 *
 * <p>Shared by both halves, so it may name {@code fs} and {@code fs.project} and neither
 * {@code fs.server} nor {@code fs.client}.</p>
 */
package com.crystalgui.fs.protocol;
