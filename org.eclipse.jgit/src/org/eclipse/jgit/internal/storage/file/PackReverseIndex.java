/*
 * Copyright (C) 2008, Marek Zawirski <marek.zawirski@gmail.com>
 * and other copyright owners as documented in the project's IP log.
 *
 * This program and the accompanying materials are made available
 * under the terms of the Eclipse Distribution License v1.0 which
 * accompanies this distribution, is reproduced below, and is
 * available at http://www.eclipse.org/org/documents/edl-v10.php
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or
 * without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above
 *   copyright notice, this list of conditions and the following
 *   disclaimer in the documentation and/or other materials provided
 *   with the distribution.
 *
 * - Neither the name of the Eclipse Foundation, Inc. nor the
 *   names of its contributors may be used to endorse or promote
 *   products derived from this software without specific prior
 *   written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 * CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.eclipse.jgit.internal.storage.file;

import java.text.MessageFormat;

import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.storage.file.PackIndex.MutableEntry;
import org.eclipse.jgit.lib.ObjectId;

/**
 * <p>
 * Reverse index for forward pack index. Provides operations based on offset
 * instead of object id. Such offset-based reverse lookups are performed in
 * O(log n) time.
 * </p>
 *
 * @see PackIndex
 * @see PackFile
 */
public class PackReverseIndex {
	/** Index we were created from, and that has our ObjectId data. */
	private final PackIndex index;

	/** The number of bytes per entry in the offsetIndex. */
	private final long bucketSize;

	/**
	 * An index into the nth mapping, where the value is the position after the
	 * the last index that contains the values of the bucket. For example given
	 * offset o (and bucket = o / bucketSize), the offset will be contained in
	 * the range nth[offsetIndex[bucket - 1]] inclusive to
	 * nth[offsetIndex[bucket]] exclusive.
	 *
	 * See {@link #binarySearch}
	 */
	private final int[] offsetIndex;

	/** Mapping from indices in offset order to indices in SHA-1 order. */
	private final int[] nth;

	/**
	 * Create reverse index from straight/forward pack index, by indexing all
	 * its entries.
	 *
	 * @param packIndex
	 *            forward index - entries to (reverse) index.
	 */
	public PackReverseIndex(final PackIndex packIndex) {
		index = packIndex;

		final long cnt = index.getObjectCount();
		if (cnt + 1 > Integer.MAX_VALUE)
			throw new IllegalArgumentException(
					JGitText.get().hugeIndexesAreNotSupportedByJgitYet);

		if (cnt == 0) {
			bucketSize = Long.MAX_VALUE;
			offsetIndex = new int[1];
			nth = new int[0];
			return;
		}

		final long[] offsetsBySha1 = new long[(int) cnt];

		long maxOffset = 0;
		int ith = 0;
		for (final MutableEntry me : index) {
			final long o = me.getOffset();
			offsetsBySha1[ith++] = o;
			if (o > maxOffset)
				maxOffset = o;
		}

		bucketSize = maxOffset / cnt + 1;
		int[] bucketIndex = new int[(int) cnt];
		int[] bucketValues = new int[(int) cnt + 1];
		for (int oi = 0; oi < offsetsBySha1.length; oi++) {
			final long o = offsetsBySha1[oi];
			final int bucket = (int) (o / bucketSize);
			final int bucketValuesPos = oi + 1;
			final int current = bucketIndex[bucket];
			bucketIndex[bucket] = bucketValuesPos;
			bucketValues[bucketValuesPos] = current;
		}

		int nthByOffset = 0;
		nth = new int[offsetsBySha1.length];
		offsetIndex = bucketIndex; // Reuse the allocation
		for (int bi = 0; bi < bucketIndex.length; bi++) {
			final int start = nthByOffset;
			// Insertion sort of the values in the bucket.
			for (int vi = bucketIndex[bi]; vi > 0; vi = bucketValues[vi]) {
				final int nthBySha1 = vi - 1;
				final long o = offsetsBySha1[nthBySha1];
				int insertion = nthByOffset++;
				for (; start < insertion; insertion--) {
					if (o > offsetsBySha1[nth[insertion - 1]])
						break;
					nth[insertion] = nth[insertion - 1];
				}
				nth[insertion] = nthBySha1;
			}
			offsetIndex[bi] = nthByOffset;
		}
	}

	/**
	 * Search for object id with the specified start offset in this pack
	 * (reverse) index.
	 *
	 * @param offset
	 *            start offset of object to find.
	 * @return object id for this offset, or null if no object was found.
	 */
	public ObjectId findObject(final long offset) {
		final int ith = binarySearch(offset);
		if (ith < 0)
			return null;
		return index.getObjectId(nth[ith]);
	}

	/**
	 * Search for the next offset to the specified offset in this pack (reverse)
	 * index.
	 *
	 * @param offset
	 *            start offset of previous object (must be valid-existing
	 *            offset).
	 * @param maxOffset
	 *            maximum offset in a pack (returned when there is no next
	 *            offset).
	 * @return offset of the next object in a pack or maxOffset if provided
	 *         offset was the last one.
	 * @throws org.eclipse.jgit.errors.CorruptObjectException
	 *             when there is no object with the provided offset.
	 */
	public long findNextOffset(final long offset, final long maxOffset)
			throws CorruptObjectException {
		final int ith = binarySearch(offset);
		if (ith < 0)
			throw new CorruptObjectException(
					MessageFormat.format(
							JGitText.get().cantFindObjectInReversePackIndexForTheSpecifiedOffset,
							Long.valueOf(offset)));

		if (ith + 1 == nth.length)
			return maxOffset;
		return index.getOffset(nth[ith + 1]);
	}

	int findPostion(long offset) {
		return binarySearch(offset);
	}

	private int binarySearch(final long offset) {
		int bucket = (int) (offset / bucketSize);
		int low = bucket == 0 ? 0 : offsetIndex[bucket - 1];
		int high = offsetIndex[bucket];
		while (low < high) {
			final int mid = (low + high) >>> 1;
			final long o = index.getOffset(nth[mid]);
			if (offset < o)
				high = mid;
			else if (offset == o)
				return mid;
			else
				low = mid + 1;
		}
		return -1;
	}

	ObjectId findObjectByPosition(int nthPosition) {
		return index.getObjectId(nth[nthPosition]);
	}
}
