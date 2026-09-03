/**
 * <b>What the two halves say to each other</b> — a record and a {@code Codec} per message.
 *
 * <p>{@link com.crystalgui.fs.protocol.FsMethods} names them,
 * {@link com.crystalgui.fs.protocol.FsMessages} carries what each one holds, and
 * {@link com.crystalgui.fs.protocol.FsHello} is the greeting the client asks for first.</p>
 *
 * <p>Shared by both halves, so it may name {@code fs} and {@code fs.project} and neither
 * {@code fs.server} nor {@code fs.client}.</p>
 */
package com.crystalgui.fs.protocol;
