/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License (the "License").
 * You may not use this file except in compliance with the License.
 *
 * See LICENSE.txt included in this distribution for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at LICENSE.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information: Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 */

/*
 * Copyright (c) 2018 Oracle and/or its affiliates. All rights reserved.
 */
package org.opengrok.suggest;

import org.apache.commons.io.FileUtils;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.junit.Test;
import org.opengrok.suggest.query.SuggesterPrefixQuery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsIterableContainingInAnyOrder.containsInAnyOrder;
import static org.junit.Assert.assertFalse;

public class SuggesterTest {

    private static class SuggesterTestData {

        private Suggester s;
        private Path indexDir;
        private Path suggesterDir;


        private void close() throws IOException {
            s.close();
            FileUtils.deleteDirectory(indexDir.toFile());
            FileUtils.deleteDirectory(suggesterDir.toFile());
        }

        private Suggester.NamedIndexDir getNamedIndexDir() {
            return new Suggester.NamedIndexDir("test", indexDir);
        }

        private Directory getIndexDirectory() throws IOException {
            return FSDirectory.open(indexDir);
        }

        private Suggester.NamedIndexReader getNamedIndexReader() throws IOException {
            return new Suggester.NamedIndexReader("test", DirectoryReader.open(getIndexDirectory()));
        }

    }

    @Test(expected = IllegalArgumentException.class)
    public void testNullSuggesterDir() {
        new Suggester(null, 10, Duration.ofMinutes(5), false, true, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNullDuration() throws IOException {
        Path tempFile = Files.createTempFile("opengrok", "test");
        try {
            new Suggester(tempFile.toFile(), 10, null, false, true, null);
        } finally {
            tempFile.toFile().delete();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNegativeDuration() throws IOException {
        Path tempFile = Files.createTempFile("opengrok", "test");
        try {
            new Suggester(tempFile.toFile(), 10, Duration.ofMinutes(-4), false, true, null);
        } finally {
            tempFile.toFile().delete();
        }
    }

    private SuggesterTestData initSuggester() throws IOException {
        Path tempIndexDir = Files.createTempDirectory("opengrok");
        Directory dir = FSDirectory.open(tempIndexDir);

        addText(dir, "term1 term2 term3");

        dir.close();

        Path tempSuggesterDir = Files.createTempDirectory("opengrok");

        Suggester s = new Suggester(tempSuggesterDir.toFile(), 10, Duration.ofMinutes(1), false,
                true, Collections.singleton("test"));

        s.init(Collections.singleton(new Suggester.NamedIndexDir("test", tempIndexDir)));

        await().atMost(2, TimeUnit.SECONDS).until(() -> getSuggesterProjectDataSize(s) == 1);

        SuggesterTestData testData = new SuggesterTestData();
        testData.s = s;
        testData.indexDir = tempIndexDir;
        testData.suggesterDir = tempSuggesterDir;
        return testData;
    }

    private void addText(final Directory dir, final String text) throws IOException {
        try (IndexWriter iw = new IndexWriter(dir, new IndexWriterConfig())) {
            Document doc = new Document();
            doc.add(new TextField("test", text, Field.Store.NO));

            iw.addDocument(doc);
        }
    }

    private static int getSuggesterProjectDataSize(final Suggester suggester) throws Exception {
        java.lang.reflect.Field f2 = Suggester.class.getDeclaredField("projectData");
        f2.setAccessible(true);

        return ((Map) f2.get(suggester)).size();
    }

    @Test
    public void testSimpleSuggestions() throws IOException {
        SuggesterTestData t = initSuggester();

        Suggester.NamedIndexReader ir = t.getNamedIndexReader();

        List<LookupResultItem> res = t.s.search(Collections.singletonList(ir),
                new SuggesterPrefixQuery(new Term("test", "t")), null);

        assertThat(res.stream().map(LookupResultItem::getPhrase).collect(Collectors.toList()),
                containsInAnyOrder("term1", "term2", "term3"));

        t.close();
    }

    @Test
    public void testRefresh() throws IOException {
        SuggesterTestData t = initSuggester();

        addText(t.getIndexDirectory(), "a1 a2");

        t.s.rebuild(Collections.singleton(t.getNamedIndexDir()));

        Suggester.NamedIndexReader ir = t.getNamedIndexReader();

        List<LookupResultItem> res = t.s.search(Collections.singletonList(ir),
                new SuggesterPrefixQuery(new Term("test", "a")), null);

        assertThat(res.stream().map(LookupResultItem::getPhrase).collect(Collectors.toList()),
                containsInAnyOrder("a1", "a2"));

        t.close();
    }

    @Test
    public void testIndexChangedWhileOffline() throws IOException {
        SuggesterTestData t = initSuggester();

        t.s.close();

        addText(t.getIndexDirectory(), "a1 a2");

        t.s = new Suggester(t.suggesterDir.toFile(), 10, Duration.ofMinutes(1), false,
                true, Collections.singleton("test"));

        t.s.init(Collections.singleton(t.getNamedIndexDir()));

        await().atMost(2, TimeUnit.SECONDS).until(() -> getSuggesterProjectDataSize(t.s) == 1);

        Suggester.NamedIndexReader ir = t.getNamedIndexReader();

        List<LookupResultItem> res = t.s.search(Collections.singletonList(ir),
                new SuggesterPrefixQuery(new Term("test", "a")), null);

        assertThat(res.stream().map(LookupResultItem::getPhrase).collect(Collectors.toList()),
                containsInAnyOrder("a1", "a2"));

        t.close();
    }

    @Test
    public void testRemove() throws IOException {
        SuggesterTestData t = initSuggester();

        t.s.remove(Collections.singleton("test"));

        assertFalse(t.suggesterDir.resolve("test").toFile().exists());

        FileUtils.deleteDirectory(t.suggesterDir.toFile());
        FileUtils.deleteDirectory(t.indexDir.toFile());
    }

}