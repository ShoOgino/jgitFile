/*
 * Copyright (C) 2015, Google Inc.
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

package org.eclipse.jgit.junit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Date;
import java.util.regex.Pattern;

import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevBlob;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TestRepositoryTest {
	private TestRepository<InMemoryRepository> tr;
	private InMemoryRepository repo;
	private RevWalk rw;

	@Before
	public void setUp() throws Exception {
		tr = new TestRepository<>(new InMemoryRepository(
				new DfsRepositoryDescription("test")));
		repo = tr.getRepository();
		rw = tr.getRevWalk();
	}

	@After
	public void tearDown() {
		rw.close();
		repo.close();
	}

	@Test
	public void insertChangeId() throws Exception {
		RevCommit c1 = tr.commit().message("message").insertChangeId().create();
		rw.parseBody(c1);
		assertTrue(Pattern.matches(
				"^message\n\nChange-Id: I[0-9a-f]{40}\n$", c1.getFullMessage()));

		RevCommit c2 = tr.commit().message("").insertChangeId().create();
		rw.parseBody(c2);
		assertEquals("\n\nChange-Id: I0000000000000000000000000000000000000000\n",
				c2.getFullMessage());
	}

	@Test
	public void resetFromSymref() throws Exception {
		repo.updateRef("HEAD").link("refs/heads/master");
		Ref head = repo.getRef("HEAD");
		RevCommit master = tr.branch("master").commit().create();
		RevCommit branch = tr.branch("branch").commit().create();
		RevCommit detached = tr.commit().create();

		assertTrue(head.isSymbolic());
		assertEquals("refs/heads/master", head.getTarget().getName());
		assertEquals(master, repo.getRef("refs/heads/master").getObjectId());
		assertEquals(branch, repo.getRef("refs/heads/branch").getObjectId());

		// Reset to branches preserves symref.
		tr.reset("master");
		head = repo.getRef("HEAD");
		assertEquals(master, head.getObjectId());
		assertTrue(head.isSymbolic());
		assertEquals("refs/heads/master", head.getTarget().getName());

		tr.reset("branch");
		head = repo.getRef("HEAD");
		assertEquals(branch, head.getObjectId());
		assertTrue(head.isSymbolic());
		assertEquals("refs/heads/master", head.getTarget().getName());
		ObjectId lastHeadBeforeDetach = head.getObjectId().copy();

		// Reset to a SHA-1 detaches.
		tr.reset(detached);
		head = repo.getRef("HEAD");
		assertEquals(detached, head.getObjectId());
		assertFalse(head.isSymbolic());

		tr.reset(detached.name());
		head = repo.getRef("HEAD");
		assertEquals(detached, head.getObjectId());
		assertFalse(head.isSymbolic());

		// Reset back to a branch remains detached.
		tr.reset("master");
		head = repo.getRef("HEAD");
		assertEquals(lastHeadBeforeDetach, head.getObjectId());
		assertFalse(head.isSymbolic());
	}

	@Test
	public void resetFromDetachedHead() throws Exception {
		Ref head = repo.getRef("HEAD");
		RevCommit master = tr.branch("master").commit().create();
		RevCommit branch = tr.branch("branch").commit().create();
		RevCommit detached = tr.commit().create();

		assertNull(head);
		assertEquals(master, repo.getRef("refs/heads/master").getObjectId());
		assertEquals(branch, repo.getRef("refs/heads/branch").getObjectId());

		tr.reset("master");
		head = repo.getRef("HEAD");
		assertEquals(master, head.getObjectId());
		assertFalse(head.isSymbolic());

		tr.reset("branch");
		head = repo.getRef("HEAD");
		assertEquals(branch, head.getObjectId());
		assertFalse(head.isSymbolic());

		tr.reset(detached);
		head = repo.getRef("HEAD");
		assertEquals(detached, head.getObjectId());
		assertFalse(head.isSymbolic());

		tr.reset(detached.name());
		head = repo.getRef("HEAD");
		assertEquals(detached, head.getObjectId());
		assertFalse(head.isSymbolic());
	}

	@Test
	public void amendRef() throws Exception {
		RevCommit root = tr.commit()
				.add("todelete", "to be deleted")
				.create();
		RevCommit orig = tr.commit().parent(root)
				.rm("todelete")
				.add("foo", "foo contents")
				.add("bar", "bar contents")
				.add("dir/baz", "baz contents")
				.create();
		rw.parseBody(orig);
		tr.branch("master").update(orig);
		assertEquals("foo contents", blobAsString(orig, "foo"));
		assertEquals("bar contents", blobAsString(orig, "bar"));
		assertEquals("baz contents", blobAsString(orig, "dir/baz"));

		RevCommit amended = tr.amendRef("master")
				.tick(3)
				.add("bar", "fixed bar contents")
				.create();
		assertEquals(amended, repo.getRef("refs/heads/master").getObjectId());
		rw.parseBody(amended);

		assertEquals(1, amended.getParentCount());
		assertEquals(root, amended.getParent(0));
		assertEquals(orig.getFullMessage(), amended.getFullMessage());
		assertEquals(orig.getAuthorIdent(), amended.getAuthorIdent());

		// Committer name/email is the same, but time was incremented.
		assertEquals(new PersonIdent(orig.getCommitterIdent(), new Date(0)),
				new PersonIdent(amended.getCommitterIdent(), new Date(0)));
		assertTrue(orig.getCommitTime() < amended.getCommitTime());

		assertEquals("foo contents", blobAsString(amended, "foo"));
		assertEquals("fixed bar contents", blobAsString(amended, "bar"));
		assertEquals("baz contents", blobAsString(amended, "dir/baz"));
		assertNull(TreeWalk.forPath(repo, "todelete", amended.getTree()));
	}

	@Test
	public void amendHead() throws Exception {
		repo.updateRef("HEAD").link("refs/heads/master");
		RevCommit root = tr.commit()
				.add("foo", "foo contents")
				.create();
		RevCommit orig = tr.commit().parent(root)
				.message("original message")
				.add("bar", "bar contents")
				.create();
		tr.branch("master").update(orig);

		RevCommit amended = tr.amendRef("HEAD")
				.add("foo", "fixed foo contents")
				.create();

		Ref head = repo.getRef(Constants.HEAD);
		assertEquals(amended, head.getObjectId());
		assertTrue(head.isSymbolic());
		assertEquals("refs/heads/master", head.getTarget().getName());

		rw.parseBody(amended);
		assertEquals("original message", amended.getFullMessage());
		assertEquals("fixed foo contents", blobAsString(amended, "foo"));
		assertEquals("bar contents", blobAsString(amended, "bar"));
	}

	@Test
	public void amendCommit() throws Exception {
		RevCommit root = tr.commit()
				.add("foo", "foo contents")
				.create();
		RevCommit orig = tr.commit().parent(root)
				.message("original message")
				.add("bar", "bar contents")
				.create();
		RevCommit amended = tr.amend(orig.copy())
				.add("foo", "fixed foo contents")
				.create();

		rw.parseBody(amended);
		assertEquals("original message", amended.getFullMessage());
		assertEquals("fixed foo contents", blobAsString(amended, "foo"));
		assertEquals("bar contents", blobAsString(amended, "bar"));
	}

	private String blobAsString(AnyObjectId treeish, String path)
			throws Exception {
		RevObject obj = tr.get(rw.parseTree(treeish), path);
		assertSame(RevBlob.class, obj.getClass());
		ObjectLoader loader = rw.getObjectReader().open(obj);
		return new String(loader.getCachedBytes(), UTF_8);
	}
}
