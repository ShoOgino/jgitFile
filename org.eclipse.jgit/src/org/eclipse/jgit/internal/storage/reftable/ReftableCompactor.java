/*
 * Copyright (C) 2017, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.reftable;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jgit.internal.storage.reftable.ReftableWriter.Stats;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.ReflogEntry;

/**
 * Merges reftables and compacts them into a single output.
 * <p>
 * For a partial compaction callers should {@link #setIncludeDeletes(boolean)}
 * to {@code true} to ensure the new reftable continues to use a delete marker
 * to shadow any lower reftable that may have the reference present.
 * <p>
 * By default all log entries within the range defined by
 * {@link #setMinUpdateIndex(long)} and {@link #setMaxUpdateIndex(long)} are
 * copied, even if no references in the output file match the log records.
 * Callers may truncate the log to a more recent time horizon with
 * {@link #setOldestReflogTimeMillis(long)}, or disable the log altogether with
 * {@code setOldestReflogTimeMillis(Long.MAX_VALUE)}.
 */
public class ReftableCompactor {
	private final ReftableWriter writer;
	private final ArrayDeque<ReftableReader> tables = new ArrayDeque<>();

	private long compactBytesLimit;
	private long bytesToCompact;
	private boolean includeDeletes;
	private long minUpdateIndex = -1;
	private long maxUpdateIndex;
	private long oldestReflogTimeMillis;
	private Stats stats;

	/**
	 * Creates a new compactor.
	 *
	 * @param out
	 *            stream to write the compacted tables to. Caller is responsible
	 *            for closing {@code out}.
	 */
	public ReftableCompactor(OutputStream out) {
		writer = new ReftableWriter(out);
	}

	/**
	 * Set configuration for the reftable.
	 *
	 * @param cfg
	 *            configuration for the reftable.
	 * @return {@code this}
	 */
	public ReftableCompactor setConfig(ReftableConfig cfg) {
		writer.setConfig(cfg);
		return this;
	}

	/**
	 * Set limit on number of bytes from source tables to compact.
	 *
	 * @param bytes
	 *            limit on number of bytes from source tables to compact.
	 * @return {@code this}
	 */
	public ReftableCompactor setCompactBytesLimit(long bytes) {
		compactBytesLimit = bytes;
		return this;
	}

	/**
	 * Whether to include deletions in the output, which may be necessary for
	 * partial compaction.
	 *
	 * @param deletes
	 *            {@code true} to include deletions in the output, which may be
	 *            necessary for partial compaction.
	 * @return {@code this}
	 */
	public ReftableCompactor setIncludeDeletes(boolean deletes) {
		includeDeletes = deletes;
		return this;
	}

	/**
	 * Set the minimum update index for log entries that appear in the compacted
	 * reftable.
	 *
	 * @param min
	 *            the minimum update index for log entries that appear in the
	 *            compacted reftable. This should be 1 higher than the prior
	 *            reftable's {@code maxUpdateIndex} if this table will be used
	 *            in a stack.
	 * @return {@code this}
	 */
	public ReftableCompactor setMinUpdateIndex(long min) {
		minUpdateIndex = min;
		return this;
	}

	/**
	 * Set the maximum update index for log entries that appear in the compacted
	 * reftable.
	 *
	 * @param max
	 *            the maximum update index for log entries that appear in the
	 *            compacted reftable. This should be at least 1 higher than the
	 *            prior reftable's {@code maxUpdateIndex} if this table will be
	 *            used in a stack.
	 * @return {@code this}
	 */
	public ReftableCompactor setMaxUpdateIndex(long max) {
		maxUpdateIndex = max;
		return this;
	}

	/**
	 * Set oldest reflog time to preserve.
	 *
	 * @param timeMillis
	 *            oldest log time to preserve. Entries whose timestamps are
	 *            {@code >= timeMillis} will be copied into the output file. Log
	 *            entries that predate {@code timeMillis} will be discarded.
	 *            Specified in Java standard milliseconds since the epoch.
	 * @return {@code this}
	 */
	public ReftableCompactor setOldestReflogTimeMillis(long timeMillis) {
		oldestReflogTimeMillis = timeMillis;
		return this;
	}

	/**
	 * Add all of the tables, in the specified order.
	 * <p>
	 * Unconditionally adds all tables, ignoring the
	 * {@link #setCompactBytesLimit(long)}.
	 *
	 * @param readers
	 *            tables to compact. Tables should be ordered oldest first/most
	 *            recent last so that the more recent tables can shadow the
	 *            older results. Caller is responsible for closing the readers.
	 * @throws java.io.IOException
	 *             update indexes of a reader cannot be accessed.
	 */
	public void addAll(List<ReftableReader> readers) throws IOException {
		for (ReftableReader r : readers) {
			tables.add(r);
			adjustUpdateIndexes(r);
		}
	}

	/**
	 * Try to add this reader at the bottom of the stack.
	 * <p>
	 * A reader may be rejected by returning {@code false} if the compactor is
	 * already rewriting its {@link #setCompactBytesLimit(long)}. When this
	 * happens the caller should stop trying to add tables, and execute the
	 * compaction.
	 *
	 * @param reader
	 *            the reader to insert at the bottom of the stack. Caller is
	 *            responsible for closing the reader.
	 * @return {@code true} if the compactor accepted this table; {@code false}
	 *         if the compactor has reached its limit.
	 * @throws java.io.IOException
	 *             if size of {@code reader}, or its update indexes cannot be read.
	 */
	public boolean tryAddFirst(ReftableReader reader) throws IOException {
		long sz = reader.size();
		if (compactBytesLimit > 0 && bytesToCompact + sz > compactBytesLimit) {
			return false;
		}
		bytesToCompact += sz;
		adjustUpdateIndexes(reader);
		tables.addFirst(reader);
		return true;
	}

	private void adjustUpdateIndexes(ReftableReader reader) throws IOException {
		if (minUpdateIndex == -1) {
			minUpdateIndex = reader.minUpdateIndex();
		} else {
			minUpdateIndex = Math.min(minUpdateIndex, reader.minUpdateIndex());
		}
		maxUpdateIndex = Math.max(maxUpdateIndex, reader.maxUpdateIndex());
	}

	/**
	 * Write a compaction to {@code out}.
	 *
	 * @throws java.io.IOException
	 *             if tables cannot be read, or cannot be written.
	 */
	public void compact() throws IOException {
		MergedReftable mr = new MergedReftable(new ArrayList<>(tables));
		mr.setIncludeDeletes(includeDeletes);

		writer.setMinUpdateIndex(Math.max(minUpdateIndex, 0));
		writer.setMaxUpdateIndex(maxUpdateIndex);
		writer.begin();
		mergeRefs(mr);
		mergeLogs(mr);
		writer.finish();
		stats = writer.getStats();
	}

	/**
	 * Get statistics of the last written reftable.
	 *
	 * @return statistics of the last written reftable.
	 */
	public Stats getStats() {
		return stats;
	}

	private void mergeRefs(MergedReftable mr) throws IOException {
		try (RefCursor rc = mr.allRefs()) {
			while (rc.next()) {
				writer.writeRef(rc.getRef(), rc.getRef().getUpdateIndex());
			}
		}
	}

	private void mergeLogs(MergedReftable mr) throws IOException {
		if (oldestReflogTimeMillis == Long.MAX_VALUE) {
			return;
		}

		try (LogCursor lc = mr.allLogs()) {
			while (lc.next()) {
				long updateIndex = lc.getUpdateIndex();
				if (updateIndex < minUpdateIndex
						|| updateIndex > maxUpdateIndex) {
					// Cannot merge log records outside the header's range.
					continue;
				}

				String refName = lc.getRefName();
				ReflogEntry log = lc.getReflogEntry();
				if (log == null) {
					if (includeDeletes) {
						writer.deleteLog(refName, updateIndex);
					}
					continue;
				}

				PersonIdent who = log.getWho();
				if (who.getWhen().getTime() >= oldestReflogTimeMillis) {
					writer.writeLog(
							refName,
							updateIndex,
							who,
							log.getOldId(),
							log.getNewId(),
							log.getComment());
				}
			}
		}
	}
}
