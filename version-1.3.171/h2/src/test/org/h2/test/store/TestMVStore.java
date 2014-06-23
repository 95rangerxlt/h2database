/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License, Version
 * 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html). Initial Developer: H2 Group
 */
package org.h2.test.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.h2.mvstore.Cursor;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.type.DataType;
import org.h2.mvstore.type.ObjectDataType;
import org.h2.mvstore.type.StringDataType;
import org.h2.store.fs.FilePath;
import org.h2.store.fs.FileUtils;
import org.h2.test.TestBase;
import org.h2.util.New;

/**
 * Tests the MVStore.
 */
public class TestMVStore extends TestBase {

    /**
     * Run just this test.
     *
     * @param a ignored
     */
    public static void main(String... a) throws Exception {
        TestBase.createCaller().init().test();
    }

    public void test() throws Exception {
        FileUtils.deleteRecursive(getBaseDir(), true);
        FileUtils.createDirectories(getBaseDir());

        testOldVersion();
        testAtomicOperations();
        testWriteBuffer();
        testWriteDelay();
        testEncryptedFile();
        testFileFormatChange();
        testRecreateMap();
        testRenameMapRollback();
        testCustomMapType();
        testCacheSize();
        testConcurrentOpen();
        testFileHeader();
        testFileHeaderCorruption();
        testIndexSkip();
        testMinMaxNextKey();
        testStoreVersion();
        testIterateOldVersion();
        testObjects();
        testExample();
        testIterateOverChanges();
        testOpenStoreCloseLoop();
        testVersion();
        testTruncateFile();
        testFastDelete();
        testRollbackInMemory();
        testRollbackStored();
        testMeta();
        testInMemory();
        testLargeImport();
        testBtreeStore();
        testDefragment();
        testReuseSpace();
        testRandom();
        testKeyValueClasses();
        testIterate();
        testCloseTwice();
        testSimple();
    }

    private void testAtomicOperations() {
        String fileName = getBaseDir() + "/testAtomicOperations.h3";
        FileUtils.delete(fileName);
        MVStore s;
        MVMap<Integer, byte[]> m;
        s = new MVStore.Builder().
                fileName(fileName).
                open();
        m = s.openMap("data");

        // putIfAbsent
        assertNull(m.putIfAbsent(1, new byte[1]));
        assertEquals(1, m.putIfAbsent(1, new byte[2]).length);
        assertEquals(1, m.get(1).length);

        // replace
        assertNull(m.replace(2, new byte[2]));
        assertNull(m.get(2));
        assertEquals(1, m.replace(1, new byte[2]).length);
        assertEquals(2, m.replace(1, new byte[3]).length);
        assertEquals(3, m.replace(1, new byte[1]).length);

        // replace with oldValue
        assertFalse(m.replace(1, new byte[2], new byte[10]));
        assertTrue(m.replace(1, new byte[1], new byte[2]));
        assertTrue(m.replace(1, new byte[2], new byte[1]));

        // remove
        assertFalse(m.remove(1, new byte[2]));
        assertTrue(m.remove(1, new byte[1]));

        s.close();
        FileUtils.delete(fileName);
    }

    private void testWriteBuffer() throws IOException {
        String fileName = getBaseDir() + "/testAutoStoreBuffer.h3";
        FileUtils.delete(fileName);
        MVStore s;
        MVMap<Integer, byte[]> m;
        byte[] data = new byte[1000];
        long lastSize = 0;
        int len = 1000;
        for (int bs = 0; bs <= 1; bs++) {
            s = new MVStore.Builder().
                    fileName(fileName).
                    writeBufferSize(bs).
                    open();
            m = s.openMap("data");
            for (int i = 0; i < len; i++) {
                m.put(i, data);
            }
            long size = s.getFile().size();
            assertTrue("last:" + lastSize + " now: " + size, size > lastSize);
            lastSize = size;
            s.close();
        }

        s = new MVStore.Builder().
                fileName(fileName).
                open();
        m = s.openMap("data");
        assertFalse(m.containsKey(1));

        m.put(1, data);
        s.commit();
        m.put(2, data);
        s.close();

        s = new MVStore.Builder().
                fileName(fileName).
                open();
        m = s.openMap("data");
        assertTrue(m.containsKey(1));
        assertFalse(m.containsKey(2));

        s.close();
        FileUtils.delete(fileName);
    }

    private void testWriteDelay() throws InterruptedException {
        String fileName = getBaseDir() + "/testUndoTempStore.h3";
        FileUtils.delete(fileName);
        MVStore s;
        MVMap<Integer, String> m;
        s = new MVStore.Builder().
                writeDelay(1).
                fileName(fileName).
                open();
        m = s.openMap("data");
        m.put(1, "Hello");
        s.store();
        long v = s.getCurrentVersion();
        m.put(2, "World");
        Thread.sleep(5);
        // must not store, as nothing has been committed yet
        assertEquals(v, s.getCurrentVersion());
        s.commit();
        m.put(3, "!");

        for (int i = 100; i > 0; i--) {
            if (s.getCurrentVersion() > v) {
                break;
            }
            if (i < 10) {
                fail();
            }
            Thread.sleep(1);
        }
        s.close();
        s = new MVStore.Builder().
                fileName(fileName).
                open();
        m = s.openMap("data");
        assertEquals("Hello", m.get(1));
        assertEquals("World", m.get(2));
        assertFalse(m.containsKey(3));

        String data = new String(new char[1000]).replace((char) 0, 'x');
        for (int i = 0; i < 1000; i++) {
            m.put(i, data);
        }
        s.close();

        s = new MVStore.Builder().
                fileName(fileName).
                open();
        m = s.openMap("data");
        assertEquals("Hello", m.get(1));
        assertEquals("World", m.get(2));
        assertFalse(m.containsKey(3));
        s.close();

        FileUtils.delete(fileName);
    }

    private void testEncryptedFile() {
        String fileName = getBaseDir() + "/testEncryptedFile.h3";
        FileUtils.delete(fileName);
        MVStore s;
        MVMap<Integer, String> m;

        char[] passwordChars = "007".toCharArray();
        s = new MVStore.Builder().
                fileName(fileName).
                encryptionKey(passwordChars).
                open();
        assertEquals(0, passwordChars[0]);
        assertEquals(0, passwordChars[1]);
        assertEquals(0, passwordChars[2]);
        assertTrue(FileUtils.exists(fileName));
        m = s.openMap("test");
        m.put(1, "Hello");
        assertEquals("Hello", m.get(1));
        s.store();
        s.close();

        passwordChars = "008".toCharArray();
        try {
            s = new MVStore.Builder().
                    fileName(fileName).
                    encryptionKey(passwordChars).open();
            fail();
        } catch (IllegalStateException e) {
            assertTrue(e.getCause() != null);
        }
        assertEquals(0, passwordChars[0]);
        assertEquals(0, passwordChars[1]);
        assertEquals(0, passwordChars[2]);

        passwordChars = "007".toCharArray();
        s = new MVStore.Builder().
                fileName(fileName).
                encryptionKey(passwordChars).open();
        assertEquals(0, passwordChars[0]);
        assertEquals(0, passwordChars[1]);
        assertEquals(0, passwordChars[2]);
        m = s.openMap("test");
        assertEquals("Hello", m.get(1));
        s.close();
        FileUtils.delete(fileName);
        assertFalse(FileUtils.exists(fileName));
    }

    private void testFileFormatChange() {
        String fileName = getBaseDir() + "/testFileFormatChange.h3";
        FileUtils.delete(fileName);
        MVStore s;
        MVMap<Integer, Integer> m;
        s = openStore(fileName);
        m = s.openMap("test");
        m.put(1, 1);
        Map<String, String> header = s.getFileHeader();
        int format = Integer.parseInt(header.get("format"));
        assertEquals(1, format);
        header.put("format", Integer.toString(format + 1));
        s.store();
        s.close();
        try {
            openStore(fileName).close();
            fail();
        } catch (IllegalStateException e) {
            assertTrue(e.getCause() != null);
        }
        FileUtils.delete(fileName);
    }

    private void testRecreateMap() {
        String fileName = getBaseDir() + "/testRecreateMap.h3";
        FileUtils.delete(fileName);
        MVStore s = openStore(fileName);
        MVMap<Integer, Integer> m = s.openMap("test");
        m.put(1, 1);
        s.store();
        m.removeMap();
        s.store();
        s.close();
        s = openStore(fileName);
        m = s.openMap("test");
        assertNull(m.get(1));
        s.close();
    }

    private void testRenameMapRollback() {
        MVStore s = openStore(null);
        MVMap<Integer, Integer> map;
        map = s.openMap("hello");
        map.put(1, 10);
        long old = s.incrementVersion();
        map.renameMap("world");
        map.put(2, 20);
        assertEquals("world", map.getName());
        s.rollbackTo(old);
        assertEquals("hello", map.getName());
        s.rollbackTo(0);
        assertTrue(map.isClosed());
        s.close();
    }

    private void testCustomMapType() {
        String fileName = getBaseDir() + "/testMapType.h3";
        FileUtils.delete(fileName);
        MVStore s = openStore(fileName);
        SequenceMap seq = s.openMap("data", new SequenceMap.Builder());
        StringBuilder buff = new StringBuilder();
        for (long x : seq.keySet()) {
            buff.append(x).append(';');
        }
        assertEquals("1;2;3;4;5;6;7;8;9;10;", buff.toString());
        s.close();
    }

    private void testCacheSize() {
        String fileName = getBaseDir() + "/testCacheSize.h3";
        MVStore s;
        MVMap<Integer, String> map;
        s = new MVStore.Builder().
                fileName(fileName).
                compressData().open();
        map = s.openMap("test");
        // add 10 MB of data
        for (int i = 0; i < 1024; i++) {
            map.put(i, new String(new char[10240]));
        }
        s.store();
        s.close();
        int[] expectedReadsForCacheSize = {
                3407, 2590, 1924, 1440, 1106, 956, 918
        };
        for (int cacheSize = 0; cacheSize <= 6; cacheSize += 4) {
            s = new MVStore.Builder().
                    fileName(fileName).
                    cacheSize(1 + 3 * cacheSize).open();
            map = s.openMap("test");
            for (int i = 0; i < 1024; i += 128) {
                for (int j = 0; j < i; j++) {
                    String x = map.get(j);
                    assertEquals(10240, x.length());
                }
            }
            assertEquals(expectedReadsForCacheSize[cacheSize],
                    s.getFileReadCount());
            s.close();
        }

    }

    private void testConcurrentOpen() {
        String fileName = getBaseDir() + "/testConcurrentOpen.h3";
        MVStore s = new MVStore.Builder().fileName(fileName).open();
        try {
            MVStore s1 = new MVStore.Builder().fileName(fileName).open();
            s1.close();
            fail();
        } catch (IllegalStateException e) {
            // expected
        }
        try {
            MVStore s1 = new MVStore.Builder().fileName(fileName).readOnly().open();
            s1.close();
            fail();
        } catch (IllegalStateException e) {
            // expected
        }
        assertFalse(s.isReadOnly());
        s.close();
        s = new MVStore.Builder().fileName(fileName).readOnly().open();
        assertTrue(s.isReadOnly());
        s.close();
    }

    private void testFileHeader() {
        String fileName = getBaseDir() + "/testFileHeader.h3";
        MVStore s = openStore(fileName);
        long time = System.currentTimeMillis();
        assertEquals("3", s.getFileHeader().get("H"));
        long creationTime = Long.parseLong(s.getFileHeader()
                .get("creationTime"));
        assertTrue(Math.abs(time - creationTime) < 100);
        s.getFileHeader().put("test", "123");
        MVMap<Integer, Integer> map = s.openMap("test");
        map.put(10, 100);
        s.store();
        s.close();
        s = openStore(fileName);
        assertEquals("123", s.getFileHeader().get("test"));
        s.close();
    }

    private void testFileHeaderCorruption() throws IOException {
        String fileName = getBaseDir() + "/testFileHeader.h3";
        MVStore s = openStore(fileName);
        MVMap<Integer, Integer> map = s.openMap("test");
        map.put(10, 100);
        FilePath f = FilePath.get(s.getFileName());
        s.store();
        s.close();
        int blockSize = 4 * 1024;
        // test corrupt file headers
        for (int i = 0; i <= blockSize; i += blockSize) {
            FileChannel fc = f.open("rw");
            if (i == 0) {
                // corrupt the last block (the end header)
                fc.truncate(fc.size() - 4096);
            }
            ByteBuffer buff = ByteBuffer.allocate(4 * 1024);
            fc.read(buff, i);
            String h = new String(buff.array(), "UTF-8").trim();
            int idx = h.indexOf("fletcher:");
            int old = Character.digit(h.charAt(idx + "fletcher:".length()), 16);
            int bad = (old + 1) & 15;
            buff.put(idx + "fletcher:".length(),
                    (byte) Character.forDigit(bad, 16));
            buff.rewind();
            fc.write(buff, i);
            fc.close();
            if (i == 0) {
                // if the first header is corrupt, the second
                // header should be used
                s = openStore(fileName);
                map = s.openMap("test");
                assertEquals(100, map.get(10).intValue());
                s.close();
            } else {
                // both headers are corrupt
                try {
                    s = openStore(fileName);
                    fail();
                } catch (Exception e) {
                    // expected
                }
            }
        }
    }

    private void testIndexSkip() {
        MVStore s = openStore(null);
        s.setPageSize(4);
        MVMap<Integer, Integer> map = s.openMap("test");
        for (int i = 0; i < 100; i += 2) {
            map.put(i, 10 * i);
        }
        for (int i = -1; i < 100; i++) {
            long index = map.getKeyIndex(i);
            if (i < 0 || (i % 2) != 0) {
                assertEquals(i < 0 ? -1 : -(i / 2) - 2, index);
            } else {
                assertEquals(i / 2, index);
            }
        }
        for (int i = -1; i < 60; i++) {
            Integer k = map.getKey(i);
            if (i < 0 || i >= 50) {
                assertNull(k);
            } else {
                assertEquals(i * 2, k.intValue());
            }
        }
        // skip
        Cursor<Integer> c = map.keyIterator(0);
        assertTrue(c.hasNext());
        assertEquals(0, c.next().intValue());
        c.skip(0);
        assertEquals(2, c.next().intValue());
        c.skip(1);
        assertEquals(6, c.next().intValue());
        c.skip(20);
        assertEquals(48, c.next().intValue());

        c = map.keyIterator(0);
        c.skip(20);
        assertEquals(40, c.next().intValue());

        c = map.keyIterator(0);
        assertEquals(0, c.next().intValue());

        assertEquals(12, map.keyList().indexOf(24));
        assertEquals(24, map.keyList().get(12).intValue());
        assertEquals(-14, map.keyList().indexOf(25));
        assertEquals(map.size(), map.keyList().size());
    }

    private void testMinMaxNextKey() {
        MVStore s = openStore(null);
        MVMap<Integer, Integer> map = s.openMap("test");
        map.put(10, 100);
        map.put(20, 200);
        assertEquals(10, map.firstKey().intValue());
        assertEquals(20, map.lastKey().intValue());
        assertEquals(20, map.ceilingKey(15).intValue());
        assertEquals(20, map.ceilingKey(20).intValue());
        assertEquals(10, map.floorKey(15).intValue());
        assertEquals(10, map.floorKey(10).intValue());
        assertEquals(20, map.higherKey(10).intValue());
        assertEquals(10, map.lowerKey(20).intValue());

        for (int i = 3; i < 20; i++) {
            s = openStore(null);
            s.setPageSize(4);
            map = s.openMap("test");
            for (int j = 3; j < i; j++) {
                map.put(j * 2, j * 20);
            }
            if (i == 3) {
                assertNull(map.firstKey());
                assertNull(map.lastKey());
            } else {
                assertEquals(6, map.firstKey().intValue());
                int max = (i - 1) * 2;
                assertEquals(max, map.lastKey().intValue());

                for (int j = 0; j < i * 2 + 2; j++) {
                    if (j > max) {
                        assertNull(map.ceilingKey(j));
                    } else {
                        int ceiling = Math.max((j + 1) / 2 * 2, 6);
                        assertEquals(ceiling, map.ceilingKey(j).intValue());
                    }

                    int floor = Math.min(max, Math.max(j / 2 * 2, 4));
                    if (floor < 6) {
                        assertNull(map.floorKey(j));
                    } else {
                        map.floorKey(j);
                    }

                    int lower = Math.min(max, Math.max((j - 1) / 2 * 2, 4));
                    if (lower < 6) {
                        assertNull(map.lowerKey(j));
                    } else {
                        assertEquals(lower, map.lowerKey(j).intValue());
                    }

                    int higher = Math.max((j + 2) / 2 * 2, 6);
                    if (higher > max) {
                        assertNull(map.higherKey(j));
                    } else {
                        assertEquals(higher, map.higherKey(j).intValue());
                    }
                }
            }
        }
    }

    private void testStoreVersion() {
        String fileName = getBaseDir() + "/testStoreVersion.h3";
        FileUtils.delete(fileName);
        MVStore s = MVStore.open(fileName);
        assertEquals(0, s.getCurrentVersion());
        assertEquals(0, s.getStoreVersion());
        s.setStoreVersion(1);
        s.close();
        s = MVStore.open(fileName);
        assertEquals(0, s.getCurrentVersion());
        assertEquals(0, s.getStoreVersion());
        s.setStoreVersion(1);
        s.store();
        s.close();
        s = MVStore.open(fileName);
        assertEquals(1, s.getCurrentVersion());
        assertEquals(1, s.getStoreVersion());
        s.close();
    }

    private void testIterateOldVersion() {
        MVStore s;
        Map<Integer, Integer> map;
        s = new MVStore.Builder().open();
        map = s.openMap("test");
        int len = 100;
        for (int i = 0; i < len; i++) {
            map.put(i, 10 * i);
        }
        Iterator<Integer> it = map.keySet().iterator();
        s.incrementVersion();
        for (int i = 0; i < len; i += 2) {
            map.remove(i);
        }
        int count = 0;
        while (it.hasNext()) {
            it.next();
            count++;
        }
        assertEquals(len, count);
        s.close();
    }

    private void testObjects() {
        String fileName = getBaseDir() + "/testObjects.h3";
        FileUtils.delete(fileName);
        MVStore s;
        Map<Object, Object> map;
        s = new MVStore.Builder().fileName(fileName).open();
        map = s.openMap("test");
        map.put(1,  "Hello");
        map.put("2", 200);
        map.put(new Object[1], new Object[]{1, "2"});
        s.store();
        s.close();
        s = new MVStore.Builder().fileName(fileName).open();
        map = s.openMap("test");
        assertEquals("Hello", map.get(1).toString());
        assertEquals(200, ((Integer) map.get("2")).intValue());
        Object[] x = (Object[]) map.get(new Object[1]);
        assertEquals(2, x.length);
        assertEquals(1, ((Integer) x[0]).intValue());
        assertEquals("2", (String) x[1]);
        s.close();
    }

    private void testExample() {
        String fileName = getBaseDir() + "/testExample.h3";
        FileUtils.delete(fileName);

        // open the store (in-memory if fileName is null)
        MVStore s = MVStore.open(fileName);

        // create/get the map named "data"
        MVMap<Integer, String> map = s.openMap("data");

        // add some data
        map.put(1, "Hello");
        map.put(2, "World");

        // get the current version, for later use
        long oldVersion = s.getCurrentVersion();

        // from now on, the old version is read-only
        s.incrementVersion();

        // more changes, in the new version
        // changes can be rolled back if required
        // changes always go into "head" (the newest version)
        map.put(1, "Hi");
        map.remove(2);

        // access the old data (before incrementVersion)
        MVMap<Integer, String> oldMap =
                map.openVersion(oldVersion);

        // mark the changes as committed
        s.commit();

        // print the old version (can be done
        // concurrently with further modifications)
        // this will print "Hello" and "World":
        // System.out.println(oldMap.get(1));
        assertEquals("Hello", oldMap.get(1));
        // System.out.println(oldMap.get(2));
        assertEquals("World", oldMap.get(2));
        oldMap.close();

        // print the newest version ("Hi")
        // System.out.println(map.get(1));
        assertEquals("Hi", map.get(1));

        // close the store - this doesn't write to disk
        s.close();
    }

    private void testOpenStoreCloseLoop() {
        String fileName = getBaseDir() + "/testOpenClose.h3";
        FileUtils.delete(fileName);
        for (int k = 0; k < 1; k++) {
            // long t = System.currentTimeMillis();
            for (int j = 0; j < 3; j++) {
                MVStore s = openStore(fileName);
                Map<String, Integer> m = s.openMap("data");
                for (int i = 0; i < 3; i++) {
                    Integer x = m.get("value");
                    m.put("value", x == null ? 0 : x + 1);
                    s.store();
                }
                s.close();
            }
            // System.out.println("open/close: " + (System.currentTimeMillis() - t));
            // System.out.println("size: " + FileUtils.size(fileName));
        }
    }

    private void testIterateOverChanges() {
        String fileName = getBaseDir() + "/testIterate.h3";
        FileUtils.delete(fileName);
        MVStore s = openStore(fileName);
        s.setPageSize(6);
        MVMap<Integer, String> m = s.openMap("data");
        for (int i = 0; i < 100; i++) {
            m.put(i, "Hi");
        }
        s.incrementVersion();
        s.store();
        for (int i = 20; i < 40; i++) {
            assertEquals("Hi", m.put(i, "Hello"));
        }
        long old = s.getCurrentVersion();
        s.incrementVersion();
        for (int i = 10; i < 15; i++) {
            m.put(i, "Hallo");
        }
        m.put(50, "Hallo");
        for (int i = 90; i < 93; i++) {
            assertEquals("Hi", m.remove(i));
        }
        assertEquals(null, m.put(100, "Hallo"));
        Iterator<Integer> it = m.changeIterator(old);
        ArrayList<Integer> list = New.arrayList();
        while (it.hasNext()) {
            list.add(it.next());
        }
        assertEquals("[10, 11, 12, 13, 14, 50, 100, 90, 91, 92]", list.toString());
        s.close();
    }

    private void testOldVersion() {
        MVStore s;
        for (int op = 0; op <= 1; op++) {
            for (int i = 0; i < 5; i++) {
                s = openStore(null);
                s.setRetainVersion(0);
                MVMap<String, String> m;
                m = s.openMap("data");
                for (int j = 0; j < 5; j++) {
                    if (op == 1) {
                        m.put("1", "" + s.getCurrentVersion());
                    }
                    s.incrementVersion();
                }
                for (int j = 0; j < s.getCurrentVersion(); j++) {
                    MVMap<String, String> old = m.openVersion(j);
                    if (op == 1) {
                        assertEquals("" + j, old.get("1"));
                    }
                }
                s.close();
            }
        }
    }

    private void testVersion() {
        String fileName = getBaseDir() + "/testVersion.h3";
        FileUtils.delete(fileName);
        MVStore s;
        s = openStore(fileName);
        MVMap<String, String> m;
        m = s.openMap("data");
        long first = s.getCurrentVersion();
        s.incrementVersion();
        m.put("1", "Hello");
        m.put("2", "World");
        for (int i = 10; i < 20; i++) {
            m.put("" + i, "data");
        }
        long old = s.getCurrentVersion();
        s.incrementVersion();
        m.put("1", "Hallo");
        m.put("2", "Welt");
        MVMap<String, String> mFirst;
        mFirst = m.openVersion(first);
        assertEquals(0, mFirst.size());
        MVMap<String, String> mOld;
        assertEquals("Hallo", m.get("1"));
        assertEquals("Welt", m.get("2"));
        mOld = m.openVersion(old);
        assertEquals("Hello", mOld.get("1"));
        assertEquals("World", mOld.get("2"));
        assertTrue(mOld.isReadOnly());
        s.getCurrentVersion();
        long old3 = s.store();

        // the old version is still available
        assertEquals("Hello", mOld.get("1"));
        assertEquals("World", mOld.get("2"));

        mOld = m.openVersion(old3);
        assertEquals("Hallo", mOld.get("1"));
        assertEquals("Welt", mOld.get("2"));

        m.put("1",  "Hi");
        assertEquals("Welt", m.remove("2"));
        s.store();
        s.close();

        s = openStore(fileName);
        m = s.openMap("data");
        assertEquals("Hi", m.get("1"));
        assertEquals(null, m.get("2"));

        mOld = m.openVersion(old3);
        assertEquals("Hallo", mOld.get("1"));
        assertEquals("Welt", mOld.get("2"));

        try {
            m.openVersion(-3);
            fail();
        } catch (IllegalArgumentException e) {
            // expected
        }
        s.close();
    }

    private void testTruncateFile() {
        String fileName = getBaseDir() + "/testTruncate.h3";
        FileUtils.delete(fileName);
        MVStore s;
        MVMap<Integer, String> m;
        s = openStore(fileName);
        m = s.openMap("data");
        for (int i = 0; i < 1000; i++) {
            m.put(i, "Hello World");
        }
        s.store();
        s.close();
        long len = FileUtils.size(fileName);
        s = openStore(fileName);
        s.setRetentionTime(0);
        m = s.openMap("data");
        m.clear();
        s.store();
        s.compact(100);
        s.close();
        long len2 = FileUtils.size(fileName);
        assertTrue("len2: " + len2 + " len: " + len, len2 < len);
    }

    private void testFastDelete() {
        String fileName = getBaseDir() + "/testFastDelete.h3";
        FileUtils.delete(fileName);
        MVStore s;
        MVMap<Integer, String> m;
        s = openStore(fileName);
        s.setPageSize(700);
        m = s.openMap("data");
        for (int i = 0; i < 1000; i++) {
            m.put(i, "Hello World");
            assertEquals(i + 1, m.size());
        }
        assertEquals(1000, m.size());
        assertEquals(284, s.getUnsavedPageCount());
        s.store();
        assertEquals(2, s.getFileWriteCount());
        s.close();

        s = openStore(fileName);
        m = s.openMap("data");
        m.clear();
        assertEquals(0, m.size());
        s.store();
        // ensure only nodes are read, but not leaves
        assertEquals(42, s.getFileReadCount());
        assertEquals(1, s.getFileWriteCount());
        s.close();
    }

    private void testRollbackStored() {
        String fileName = getBaseDir() + "/testRollback.h3";
        FileUtils.delete(fileName);
        MVMap<String, String> meta;
        MVStore s = openStore(fileName);
        assertEquals(45000, s.getRetentionTime());
        s.setRetentionTime(0);
        assertEquals(0, s.getRetentionTime());
        s.setRetentionTime(45000);
        assertEquals(45000, s.getRetentionTime());
        assertEquals(0, s.getCurrentVersion());
        assertFalse(s.hasUnsavedChanges());
        MVMap<String, String> m = s.openMap("data");
        assertTrue(s.hasUnsavedChanges());
        MVMap<String, String> m0 = s.openMap("data0");
        m.put("1", "Hello");
        assertEquals(1, s.incrementVersion());
        s.rollbackTo(1);
        assertEquals(1, s.getCurrentVersion());
        assertEquals("Hello", m.get("1"));

        long v2 = s.store();
        assertEquals(2, v2);
        assertEquals(2, s.getCurrentVersion());
        assertFalse(s.hasUnsavedChanges());
        assertEquals("Hello", m.get("1"));
        s.close();

        s = openStore(fileName);
        assertEquals(2, s.getCurrentVersion());
        meta = s.getMetaMap();
        m = s.openMap("data");
        assertFalse(s.hasUnsavedChanges());
        assertEquals("Hello", m.get("1"));
        m0 = s.openMap("data0");
        MVMap<String, String> m1 = s.openMap("data1");
        m.put("1", "Hallo");
        m0.put("1", "Hallo");
        m1.put("1", "Hallo");
        assertEquals("Hallo", m.get("1"));
        assertEquals("Hallo", m1.get("1"));
        assertTrue(s.hasUnsavedChanges());
        s.rollbackTo(v2);
        assertFalse(s.hasUnsavedChanges());
        assertNull(meta.get("name.data1"));
        assertNull(m0.get("1"));
        assertEquals("Hello", m.get("1"));
        assertEquals(2, s.store());
        s.close();

        s = openStore(fileName);
        assertEquals(2, s.getCurrentVersion());
        meta = s.getMetaMap();
        assertTrue(meta.get("name.data") != null);
        assertTrue(meta.get("name.data0") != null);
        assertNull(meta.get("name.data1"));
        m = s.openMap("data");
        m0 = s.openMap("data0");
        assertNull(m0.get("1"));
        assertEquals("Hello", m.get("1"));
        assertFalse(m0.isReadOnly());
        m.put("1",  "Hallo");
        s.incrementVersion();
        long v3 = s.getCurrentVersion();
        assertEquals(3, v3);
        long v4 = s.store();
        assertEquals(4, v4);
        assertEquals(4, s.getCurrentVersion());
        s.close();

        s = openStore(fileName);
        assertEquals(4, s.getCurrentVersion());
        m = s.openMap("data");
        m.put("1",  "Hi");
        s.store();
        s.close();

        s = openStore(fileName);
        m = s.openMap("data");
        assertEquals("Hi", m.get("1"));
        s.rollbackTo(v4);
        assertEquals("Hallo", m.get("1"));
        s.close();

        s = openStore(fileName);
        m = s.openMap("data");
        assertEquals("Hallo", m.get("1"));
        s.close();
    }

    private void testRollbackInMemory() {
        String fileName = getBaseDir() + "/testRollback.h3";
        FileUtils.delete(fileName);
        MVStore s = openStore(fileName);
        assertEquals(0, s.getCurrentVersion());
        s.setPageSize(5);
        MVMap<String, String> m = s.openMap("data");
        s.rollbackTo(0);
        assertTrue(m.isClosed());
        assertEquals(0, s.getCurrentVersion());
        m = s.openMap("data");

        MVMap<String, String> m0 = s.openMap("data0");
        MVMap<String, String> m2 = s.openMap("data2");
        m.put("1", "Hello");
        for (int i = 0; i < 10; i++) {
            m2.put("" + i, "Test");
        }
        long v1 = s.incrementVersion();
        assertEquals(1, v1);
        assertEquals(1, s.getCurrentVersion());
        MVMap<String, String> m1 = s.openMap("data1");
        assertEquals("Test", m2.get("1"));
        m.put("1", "Hallo");
        m0.put("1", "Hallo");
        m1.put("1", "Hallo");
        m2.clear();
        assertEquals("Hallo", m.get("1"));
        assertEquals("Hallo", m1.get("1"));
        s.rollbackTo(v1);
        assertEquals(1, s.getCurrentVersion());
        for (int i = 0; i < 10; i++) {
            assertEquals("Test", m2.get("" + i));
        }
        assertEquals("Hello", m.get("1"));
        assertNull(m0.get("1"));
        assertTrue(m1.isClosed());
        assertFalse(m0.isReadOnly());
        assertTrue(m1.isReadOnly());
        s.close();
    }

    private void testMeta() {
        String fileName = getBaseDir() + "/testMeta.h3";
        FileUtils.delete(fileName);
        MVStore s = openStore(fileName);
        MVMap<String, String> m = s.getMetaMap();
        MVMap<String, String> data = s.openMap("data");
        data.put("1", "Hello");
        data.put("2", "World");
        s.store();
        assertEquals(1, s.getCurrentVersion());
        assertTrue(m.containsKey("chunk.1"));
        assertFalse(m.containsKey("chunk.2"));

        String id = s.getMetaMap().get("name.data");
        assertEquals("name:data", m.get("map." + id));
        assertTrue(m.containsKey("chunk.1"));
        assertEquals("Hello", data.put("1", "Hallo"));
        s.store();
        assertEquals("name:data", m.get("map." + id));
        assertTrue(m.get("root.1").length() > 0);
        assertTrue(m.containsKey("chunk.1"));
        assertEquals("id:1,length:246,maxLength:224,maxLengthLive:0," +
                "metaRoot:274877910922,pageCount:2," +
                "start:8192,time:0,version:1", m.get("chunk.1"));

        assertTrue(m.containsKey("chunk.2"));
        assertEquals(2, s.getCurrentVersion());

        s.rollbackTo(1);
        assertEquals("Hello", data.get("1"));
        assertEquals("World", data.get("2"));
        assertTrue(m.containsKey("chunk.1"));
        assertFalse(m.containsKey("chunk.2"));

        s.close();
    }

    private void testInMemory() {
        for (int j = 0; j < 1; j++) {
            MVStore s = openStore(null);
            // s.setMaxPageSize(10);
            // long t;
            int len = 100;
            // TreeMap<Integer, String> m = new TreeMap<Integer, String>();
            // HashMap<Integer, String> m = New.hashMap();
            MVMap<Integer, String> m = s.openMap("data");
            // t = System.currentTimeMillis();
            for (int i = 0; i < len; i++) {
                assertNull(m.put(i, "Hello World"));
            }
            // System.out.println("put: " + (System.currentTimeMillis() - t));
            // t = System.currentTimeMillis();
            for (int i = 0; i < len; i++) {
                assertEquals("Hello World", m.get(i));
            }
            // System.out.println("get: " + (System.currentTimeMillis() - t));
            // t = System.currentTimeMillis();
            for (int i = 0; i < len; i++) {
                assertEquals("Hello World", m.remove(i));
            }
            // System.out.println("remove: " + (System.currentTimeMillis() - t));
            // System.out.println();
            assertEquals(null, m.get(0));
            assertEquals(0, m.size());
            s.close();
        }
    }

    private void testLargeImport() {
        String fileName = getBaseDir() + "/testImport.h3";
        int len = 1000;
        for (int j = 0; j < 5; j++) {
            FileUtils.delete(fileName);
            MVStore s = openStore(fileName);
            // s.setCompressor(null);
            s.setPageSize(40);
            MVMap<Integer, Object[]> m = s.openMap("data",
                    new MVMap.Builder<Integer, Object[]>()
                            .valueType(new RowDataType(new DataType[] {
                                    new ObjectDataType(),
                                    StringDataType.INSTANCE,
                                    StringDataType.INSTANCE })));

            // Profiler prof = new Profiler();
            // prof.startCollecting();
            // long t = System.currentTimeMillis();
            for (int i = 0; i < len;) {
                Object[] o = new Object[3];
                o[0] = i;
                o[1] = "Hello World";
                o[2] = "World";
                m.put(i, o);
                i++;
                if (i % 10000 == 0) {
                    s.store();
                }
            }
            s.store();
            s.close();
            // System.out.println(prof.getTop(5));
            // System.out.println("store time " + (System.currentTimeMillis() - t));
            // System.out.println("store size " + FileUtils.size(fileName));
        }
    }

    private void testBtreeStore() {
        String fileName = getBaseDir() + "/testBtreeStore.h3";
        FileUtils.delete(fileName);
        MVStore s = openStore(fileName);
        s.close();

        s = openStore(fileName);
        MVMap<Integer, String> m = s.openMap("data");
        int count = 2000;
        // Profiler p = new Profiler();
        // p.startCollecting();
        // long t = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            assertNull(m.put(i, "hello " + i));
            assertEquals("hello " + i, m.get(i));
        }
        // System.out.println("put: " + (System.currentTimeMillis() - t));
        // System.out.println(p.getTop(5));
        // p = new Profiler();
        //p.startCollecting();
        // t = System.currentTimeMillis();
        s.store();
        // System.out.println("store: " + (System.currentTimeMillis() - t));
        // System.out.println(p.getTop(5));
        assertEquals("hello 0", m.remove(0));
        assertNull(m.get(0));
        for (int i = 1; i < count; i++) {
            assertEquals("hello " + i, m.get(i));
        }
        s.store();
        s.close();

        s = openStore(fileName);
        m = s.openMap("data");
        assertNull(m.get(0));
        for (int i = 1; i < count; i++) {
            assertEquals("hello " + i, m.get(i));
        }
        for (int i = 1; i < count; i++) {
            m.remove(i);
        }
        s.store();
        assertNull(m.get(0));
        for (int i = 0; i < count; i++) {
            assertNull(m.get(i));
        }
        s.close();
    }

    private void testDefragment() {
        String fileName = getBaseDir() + "/testDefragment.h3";
        FileUtils.delete(fileName);
        long initialLength = 0;
        for (int j = 0; j < 20; j++) {
            MVStore s = openStore(fileName);
            s.setRetentionTime(0);
            MVMap<Integer, String> m = s.openMap("data");
            for (int i = 0; i < 100; i++) {
                m.put(j + i, "Hello " + j);
            }
            s.store();
            s.compact(80);
            s.close();
            long len = FileUtils.size(fileName);
            // System.out.println("   len:" + len);
            if (initialLength == 0) {
                initialLength = len;
            } else {
                assertTrue("initial: " + initialLength + " len: " + len, len <= initialLength * 3);
            }
        }
        // long len = FileUtils.size(fileName);
        // System.out.println("len0: " + len);
        MVStore s = openStore(fileName);
        MVMap<Integer, String> m = s.openMap("data");
        for (int i = 0; i < 100; i++) {
            m.remove(i);
        }
        s.store();
        s.compact(80);
        s.close();
        // len = FileUtils.size(fileName);
        // System.out.println("len1: " + len);
        s = openStore(fileName);
        m = s.openMap("data");
        s.compact(80);
        s.close();
        // len = FileUtils.size(fileName);
        // System.out.println("len2: " + len);
    }

    private void testReuseSpace() throws Exception {
        String fileName = getBaseDir() + "/testReuseSpace.h3";
        FileUtils.delete(fileName);
        long initialLength = 0;
        for (int j = 0; j < 20; j++) {
            MVStore s = openStore(fileName);
            s.setRetentionTime(0);
            MVMap<Integer, String> m = s.openMap("data");
            for (int i = 0; i < 10; i++) {
                m.put(i, "Hello");
            }
            s.store();
            for (int i = 0; i < 10; i++) {
                assertEquals("Hello", m.get(i));
                assertEquals("Hello", m.remove(i));
            }
            s.store();
            s.close();
            long len = FileUtils.size(fileName);
            if (initialLength == 0) {
                initialLength = len;
            } else {
                assertTrue("len: " + len + " initial: " + initialLength + " j: " + j,
                        len <= initialLength * 2);
            }
        }
    }

    private void testRandom() {
        String fileName = getBaseDir() + "/testRandom.h3";
        FileUtils.delete(fileName);
        MVStore s = openStore(fileName);
        MVMap<Integer, Integer> m = s.openMap("data");
        TreeMap<Integer, Integer> map = new TreeMap<Integer, Integer>();
        Random r = new Random(1);
        int operationCount = 1000;
        int maxValue = 30;
        Integer expected, got;
        for (int i = 0; i < operationCount; i++) {
            int k = r.nextInt(maxValue);
            int v = r.nextInt();
            boolean compareAll;
            switch (r.nextInt(3)) {
            case 0:
                log(i + ": put " + k + " = " + v);
                expected = map.put(k, v);
                got = m.put(k, v);
                if (expected == null) {
                    assertNull(got);
                } else {
                    assertEquals(expected, got);
                }
                compareAll = true;
                break;
            case 1:
                log(i + ": remove " + k);
                expected = map.remove(k);
                got = m.remove(k);
                if (expected == null) {
                    assertNull(got);
                } else {
                    assertEquals(expected, got);
                }
                compareAll = true;
                break;
            default:
                Integer a = map.get(k);
                Integer b = m.get(k);
                if (a == null || b == null) {
                    assertTrue(a == b);
                } else {
                    assertEquals(a.intValue(), b.intValue());
                }
                compareAll = false;
                break;
            }
            if (compareAll) {
                Iterator<Integer> it = m.keyIterator(null);
                Iterator<Integer> itExpected = map.keySet().iterator();
                while (itExpected.hasNext()) {
                    assertTrue(it.hasNext());
                    expected = itExpected.next();
                    got = it.next();
                    assertEquals(expected, got);
                }
                assertFalse(it.hasNext());
            }
        }
        s.close();
    }

    private void testKeyValueClasses() {
        String fileName = getBaseDir() + "/testKeyValueClasses.h3";
        FileUtils.delete(fileName);
        MVStore s = openStore(fileName);
        MVMap<Integer, String> is = s.openMap("intString");
        is.put(1, "Hello");
        MVMap<Integer, Integer> ii = s.openMap("intInt");
        ii.put(1, 10);
        MVMap<String, Integer> si = s.openMap("stringInt");
        si.put("Test", 10);
        MVMap<String, String> ss = s.openMap("stringString");
        ss.put("Hello", "World");
        s.store();
        s.close();
        s = openStore(fileName);
        is = s.openMap("intString");
        assertEquals("Hello", is.get(1));
        ii = s.openMap("intInt");
        assertEquals(10, ii.get(1).intValue());
        si = s.openMap("stringInt");
        assertEquals(10, si.get("Test").intValue());
        ss = s.openMap("stringString");
        assertEquals("World", ss.get("Hello"));
        s.close();
    }

    private void testIterate() {
        String fileName = getBaseDir() + "/testIterate.h3";
        FileUtils.delete(fileName);
        MVStore s = openStore(fileName);
        MVMap<Integer, String> m = s.openMap("data");
        Iterator<Integer> it = m.keyIterator(null);
        assertFalse(it.hasNext());
        for (int i = 0; i < 10; i++) {
            m.put(i, "hello " + i);
        }
        s.store();
        it = m.keyIterator(null);
        it.next();
        assertThrows(UnsupportedOperationException.class, it).remove();

        it = m.keyIterator(null);
        for (int i = 0; i < 10; i++) {
            assertTrue(it.hasNext());
            assertEquals(i, it.next().intValue());
        }
        assertFalse(it.hasNext());
        assertNull(it.next());
        for (int j = 0; j < 10; j++) {
            it = m.keyIterator(j);
            for (int i = j; i < 10; i++) {
                assertTrue(it.hasNext());
                assertEquals(i, it.next().intValue());
            }
            assertFalse(it.hasNext());
        }
        s.close();
    }

    private void testCloseTwice() {
        String fileName = getBaseDir() + "/testCloseTwice.h3";
        FileUtils.delete(fileName);
        MVStore s = openStore(fileName);
        MVMap<Integer, String> m = s.openMap("data");
        for (int i = 0; i < 3; i++) {
            m.put(i, "hello " + i);
        }
        s.store();
        // closing twice should be fine
        s.close();
        s.close();
    }

    private void testSimple() {
        String fileName = getBaseDir() + "/testSimple.h3";
        FileUtils.delete(fileName);
        MVStore s = openStore(fileName);
        MVMap<Integer, String> m = s.openMap("data");
        for (int i = 0; i < 3; i++) {
            m.put(i, "hello " + i);
        }
        s.store();
        assertEquals("hello 0", m.remove(0));

        assertNull(m.get(0));
        for (int i = 1; i < 3; i++) {
            assertEquals("hello " + i, m.get(i));
        }
        s.store();
        s.close();

        s = openStore(fileName);
        m = s.openMap("data");
        assertNull(m.get(0));
        for (int i = 1; i < 3; i++) {
            assertEquals("hello " + i, m.get(i));
        }
        s.close();
    }

    /**
     * Open a store for the given file name, using a small page size.
     *
     * @param fileName the file name (null for in-memory)
     * @return the store
     */
    protected static MVStore openStore(String fileName) {
        MVStore store = new MVStore.Builder().
                fileName(fileName).open();
        store.setPageSize(1000);
        return store;
    }


    /**
     * Log the message.
     *
     * @param msg the message
     */
    protected static void log(String msg) {
        // System.out.println(msg);
    }

}
