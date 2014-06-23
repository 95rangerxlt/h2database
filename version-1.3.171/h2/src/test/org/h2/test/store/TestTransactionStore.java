/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.h2.mvstore.MVStore;
import org.h2.mvstore.TransactionStore;
import org.h2.mvstore.TransactionStore.Transaction;
import org.h2.mvstore.TransactionStore.TransactionMap;
import org.h2.store.fs.FileUtils;
import org.h2.test.TestBase;
import org.h2.util.New;

/**
 * Test concurrent transactions.
 */
public class TestTransactionStore extends TestBase {

    /**
     * Run just this test.
     *
     * @param a ignored
     */
    public static void main(String... a) throws Exception {
        TestBase.createCaller().init().test();
    }

    public void test() throws Exception {
        testMultiStatement();
        testTwoPhaseCommit();
        testSavepoint();
        testConcurrentTransactionsReadCommitted();
        testSingleConnection();
        testCompareWithPostgreSQL();
    }

    /**
     * Tests behavior when used for a sequence of SQL statements. Each statement
     * uses a savepoint. Within a statement, changes by the statement itself are
     * not seen; the change is only seen when the statement finished.
     * <p>
     * Update statements that change the key of multiple rows may use delete/add
     * pairs to do so (they don't need to first delete all entries and then
     * re-add them). Trying to add multiple values for the same key is not
     * allowed (an update statement that would result in a duplicate key).
     */
    private void testMultiStatement() {
        MVStore s = MVStore.open(null);
        TransactionStore ts = new TransactionStore(s);
        Transaction tx;
        TransactionMap<String, String> m;
        long startUpdate;
        long version;

        tx = ts.begin();

        // start of statement
        // create table test
        startUpdate = tx.setSavepoint();
        tx.openMap("test");

        // start of statement
        // insert into test(id, name) values(1, 'Hello'), (2, 'World')
        startUpdate = tx.setSavepoint();
        version = s.getCurrentVersion();
        m = tx.openMap("test", version);
        assertTrue(m.trySet("1", "Hello", true));
        assertTrue(m.trySet("2", "World", true));
        // not seen yet (within the same statement)
        assertNull(m.get("1"));
        assertNull(m.get("2"));

        // start of statement
        startUpdate = tx.setSavepoint();
        version = s.getCurrentVersion();
        // now we see the newest version
        m = tx.openMap("test", version);
        assertEquals("Hello", m.get("1"));
        assertEquals("World", m.get("2"));
        // update test set primaryKey = primaryKey + 1
        // (this is usually a tricky cases)
        assertEquals("Hello", m.get("1"));
        assertTrue(m.trySet("1", null, true));
        assertTrue(m.trySet("2", "Hello", true));
        assertEquals("World", m.get("2"));
        // already updated by this statement, so it has no effect
        // but still returns true because it was changed by this transaction
        assertTrue(m.trySet("2", null, true));

        assertTrue(m.trySet("3", "World", true));
        // not seen within this statement
        assertEquals("Hello", m.get("1"));
        assertEquals("World", m.get("2"));
        assertNull(m.get("3"));

        // start of statement
        startUpdate = tx.setSavepoint();
        version = s.getCurrentVersion();
        m = tx.openMap("test", version);
        // select * from test
        assertNull(m.get("1"));
        assertEquals("Hello", m.get("2"));
        assertEquals("World", m.get("3"));

        // start of statement
        startUpdate = tx.setSavepoint();
        version = s.getCurrentVersion();
        m = tx.openMap("test", version);
        // update test set id = 1
        // should fail: duplicate key
        assertTrue(m.trySet("2", null, true));
        assertTrue(m.trySet("1", "Hello", true));
        assertTrue(m.trySet("3", null, true));
        assertFalse(m.trySet("1", "World", true));
        tx.rollbackToSavepoint(startUpdate);

        version = s.getCurrentVersion();
        m = tx.openMap("test", version);
        assertNull(m.get("1"));
        assertEquals("Hello", m.get("2"));
        assertEquals("World", m.get("3"));

        tx.commit();

        ts.close();
        s.close();
    }

    private void testTwoPhaseCommit() throws Exception {
        String fileName = getBaseDir() + "/testTwoPhaseCommit.h3";
        FileUtils.delete(fileName);

        MVStore s;
        TransactionStore ts;
        Transaction tx;
        Transaction txOld;
        TransactionMap<String, String> m;
        List<Transaction> list;

        s = MVStore.open(fileName);
        ts = new TransactionStore(s);
        tx = ts.begin();
        assertEquals(null, tx.getName());
        tx.setName("first transaction");
        assertEquals("first transaction", tx.getName());
        assertEquals(0, tx.getId());
        assertEquals(Transaction.STATUS_OPEN, tx.getStatus());
        m = tx.openMap("test");
        m.put("1", "Hello");
        list = ts.getOpenTransactions();
        assertEquals(1, list.size());
        txOld = list.get(0);
        assertTrue(tx == txOld);
        s.commit();
        ts.close();
        s.close();

        s = MVStore.open(fileName);
        ts = new TransactionStore(s);
        tx = ts.begin();
        assertEquals(1, tx.getId());
        m = tx.openMap("test");
        assertEquals(null, m.get("1"));
        list = ts.getOpenTransactions();
        assertEquals(2, list.size());
        txOld = list.get(0);
        assertEquals(0, txOld.getId());
        assertEquals(Transaction.STATUS_OPEN, txOld.getStatus());
        assertEquals("first transaction", txOld.getName());
        txOld.prepare();
        assertEquals(Transaction.STATUS_PREPARED, txOld.getStatus());
        txOld = list.get(1);
        assertEquals(1, txOld.getId());
        assertNull(txOld.getName());
        assertEquals(Transaction.STATUS_OPEN, txOld.getStatus());
        txOld.rollback();
        s.commit();
        s.close();

        s = MVStore.open(fileName);
        ts = new TransactionStore(s);
        tx = ts.begin();
        m = tx.openMap("test");
        // TransactionStore was not closed, so we lost some ids
        assertEquals(33, tx.getId());
        list = ts.getOpenTransactions();
        assertEquals(2, list.size());
        txOld = list.get(0);
        assertEquals(0, txOld.getId());
        assertEquals(Transaction.STATUS_PREPARED, txOld.getStatus());
        assertEquals("first transaction", txOld.getName());
        txOld.commit();
        assertEquals("Hello", m.get("1"));
        s.close();

        FileUtils.delete(fileName);
    }

    private void testSavepoint() throws Exception {
        MVStore s = MVStore.open(null);
        TransactionStore ts = new TransactionStore(s);
        Transaction tx;
        TransactionMap<String, String> m;

        tx = ts.begin();
        m = tx.openMap("test");
        m.put("1", "Hello");
        m.put("2", "World");
        m.put("1", "Hallo");
        m.remove("2");
        m.put("3", "!");
        long logId = tx.setSavepoint();
        m.put("1", "Hi");
        m.put("2", ".");
        m.remove("3");
        tx.rollbackToSavepoint(logId);
        assertEquals("Hallo", m.get("1"));
        assertNull(m.get("2"));
        assertEquals("!", m.get("3"));
        tx.rollback();

        tx = ts.begin();
        m = tx.openMap("test");
        assertNull(m.get("1"));
        assertNull(m.get("2"));
        assertNull(m.get("3"));

        ts.close();
        s.close();
    }

    private void testCompareWithPostgreSQL() throws Exception {
        ArrayList<Statement> statements = New.arrayList();
        ArrayList<Transaction> transactions = New.arrayList();
        ArrayList<TransactionMap<Integer, String>> maps = New.arrayList();
        int connectionCount = 3, opCount = 1000, rowCount = 10;
        try {
            Class.forName("org.postgresql.Driver");
            for (int i = 0; i < connectionCount; i++) {
                Connection conn = DriverManager.getConnection(
                        "jdbc:postgresql:test", "sa", "sa");
                statements.add(conn.createStatement());
            }
        } catch (Exception e) {
            // database not installed - ok
            return;
        }
        statements.get(0).execute(
                "drop table if exists test");
        statements.get(0).execute(
                "create table test(id int primary key, name varchar(255))");

        MVStore s = MVStore.open(null);
        TransactionStore ts = new TransactionStore(s);
        for (int i = 0; i < connectionCount; i++) {
            Statement stat = statements.get(i);
            // 100 ms to avoid blocking (the test is single threaded)
            stat.execute("set statement_timeout to 100");
            Connection c = stat.getConnection();
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            Transaction transaction = ts.begin();
            transactions.add(transaction);
            TransactionMap<Integer, String> map;
            map = transaction.openMap("test");
            maps.add(map);
        }
        StringBuilder buff = new StringBuilder();

        Random r = new Random(1);
        try {
            for (int i = 0; i < opCount; i++) {
                int connIndex = r.nextInt(connectionCount);
                Statement stat = statements.get(connIndex);
                Transaction transaction = transactions.get(connIndex);
                TransactionMap<Integer, String> map = maps.get(connIndex);
                if (transaction == null) {
                    transaction = ts.begin();
                    map = transaction.openMap("test");
                    transactions.set(connIndex, transaction);
                    maps.set(connIndex, map);

                    // read all data, to get a snapshot
                    ResultSet rs = stat.executeQuery(
                            "select * from test order by id");
                    buff.append(i).append(": [" + connIndex + "]=");
                    int size = 0;
                    while (rs.next()) {
                        buff.append(' ');
                        int k = rs.getInt(1);
                        String v = rs.getString(2);
                        buff.append(k).append(':').append(v);
                        assertEquals(v, map.get(k));
                        size++;
                    }
                    buff.append('\n');
                    if (size != map.getSize()) {
                        assertEquals(size, map.getSize());
                    }
                }
                int x = r.nextInt(rowCount);
                int y = r.nextInt(rowCount);
                buff.append(i).append(": [" + connIndex + "]: ");
                ResultSet rs = null;
                switch (r.nextInt(7)) {
                case 0:
                    buff.append("commit");
                    stat.getConnection().commit();
                    transaction.commit();
                    transactions.set(connIndex, null);
                    break;
                case 1:
                    buff.append("rollback");
                    stat.getConnection().rollback();
                    transaction.rollback();
                    transactions.set(connIndex, null);
                    break;
                case 2:
                    // insert or update
                    String old = map.get(x);
                    if (old == null) {
                        buff.append("insert " + x + "=" + y);
                        if (map.tryPut(x, "" + y)) {
                            stat.execute("insert into test values(" + x + ", '" + y + "')");
                        } else {
                            buff.append(" -> row was locked");
                            // the statement would time out in PostgreSQL
                            // TODO test sometimes if timeout occurs
                        }
                    } else {
                        buff.append("update " + x + "=" + y + " (old:" + old + ")");
                        if (map.tryPut(x, "" + y)) {
                            int c = stat.executeUpdate("update test set name = '" + y
                                    + "' where id = " + x);
                            assertEquals(1, c);
                        } else {
                            buff.append(" -> row was locked");
                            // the statement would time out in PostgreSQL
                            // TODO test sometimes if timeout occurs
                        }
                    }
                    break;
                case 3:
                    buff.append("delete " + x);
                    try {
                        int c = stat.executeUpdate("delete from test where id = " + x);
                        if (c == 1) {
                            map.remove(x);
                        } else {
                            assertNull(map.get(x));
                        }
                    } catch (SQLException e) {
                        assertTrue(map.get(x) != null);
                        assertFalse(map.tryRemove(x));
                        // PostgreSQL needs to rollback
                        buff.append(" -> rollback");
                        stat.getConnection().rollback();
                        transaction.rollback();
                        transactions.set(connIndex, null);
                    }
                    break;
                case 4:
                case 5:
                case 6:
                    rs = stat.executeQuery("select * from test where id = " + x);
                    String expected = rs.next() ? rs.getString(2) : null;
                    buff.append("select " + x + "=" + expected);
                    assertEquals("i:" + i, expected, map.get(x));
                    break;
                }
                buff.append('\n');
            }
        } catch (Exception e) {
            e.printStackTrace();
            fail(buff.toString());
        }
        for (Statement stat : statements) {
            stat.getConnection().close();
        }
        ts.close();
        s.close();
    }

    private void testConcurrentTransactionsReadCommitted() {
        MVStore s = MVStore.open(null);

        TransactionStore ts = new TransactionStore(s);

        Transaction tx1, tx2;
        TransactionMap<String, String> m1, m2;

        tx1 = ts.begin();
        m1 = tx1.openMap("test");
        m1.put("1", "Hi");
        m1.put("3", ".");
        tx1.commit();

        tx1 = ts.begin();
        m1 = tx1.openMap("test");
        m1.put("1", "Hello");
        m1.put("2", "World");
        m1.remove("3");
        tx1.commit();

        // start new transaction to read old data
        tx2 = ts.begin();
        m2 = tx2.openMap("test");

        // start transaction tx1, update/delete/add
        tx1 = ts.begin();
        m1 = tx1.openMap("test");
        m1.put("1", "Hallo");
        m1.remove("2");
        m1.put("3", "!");

        assertEquals("Hello", m2.get("1"));
        assertEquals("World", m2.get("2"));
        assertNull(m2.get("3"));

        tx1.commit();

        assertEquals("Hallo", m2.get("1"));
        assertNull(m2.get("2"));
        assertEquals("!", m2.get("3"));

        tx1 = ts.begin();
        m1 = tx1.openMap("test");
        m1.put("2", "World");

        assertNull(m2.get("2"));
        assertFalse(m2.tryRemove("2"));
        assertFalse(m2.tryPut("2", "Welt"));

        tx2 = ts.begin();
        m2 = tx2.openMap("test");
        assertNull(m2.get("2"));
        m1.remove("2");
        assertNull(m2.get("2"));
        tx1.commit();

        tx1 = ts.begin();
        m1 = tx1.openMap("test");
        assertNull(m1.get("2"));
        m1.put("2", "World");
        m1.put("2", "Welt");
        tx1.rollback();

        tx1 = ts.begin();
        m1 = tx1.openMap("test");
        assertNull(m1.get("2"));

        ts.close();
        s.close();
    }

    private void testSingleConnection() {
        MVStore s = MVStore.open(null);

        TransactionStore ts = new TransactionStore(s);

        Transaction tx;
        TransactionMap<String, String> m;

        // add, rollback
        tx = ts.begin();
        m = tx.openMap("test");
        m.put("1", "Hello");
        assertEquals("Hello", m.get("1"));
        m.put("2", "World");
        assertEquals("World", m.get("2"));
        tx.rollback();
        tx = ts.begin();
        m = tx.openMap("test");
        assertNull(m.get("1"));
        assertNull(m.get("2"));

        // add, commit
        tx = ts.begin();
        m = tx.openMap("test");
        m.put("1", "Hello");
        m.put("2", "World");
        assertEquals("Hello", m.get("1"));
        assertEquals("World", m.get("2"));
        tx.commit();
        tx = ts.begin();
        m = tx.openMap("test");
        assertEquals("Hello", m.get("1"));
        assertEquals("World", m.get("2"));

        // update+delete+insert, rollback
        tx = ts.begin();
        m = tx.openMap("test");
        m.put("1", "Hallo");
        m.remove("2");
        m.put("3", "!");
        assertEquals("Hallo", m.get("1"));
        assertNull(m.get("2"));
        assertEquals("!", m.get("3"));
        tx.rollback();
        tx = ts.begin();
        m = tx.openMap("test");
        assertEquals("Hello", m.get("1"));
        assertEquals("World", m.get("2"));
        assertNull(m.get("3"));

        // update+delete+insert, commit
        tx = ts.begin();
        m = tx.openMap("test");
        m.put("1", "Hallo");
        m.remove("2");
        m.put("3", "!");
        assertEquals("Hallo", m.get("1"));
        assertNull(m.get("2"));
        assertEquals("!", m.get("3"));
        tx.commit();
        tx = ts.begin();
        m = tx.openMap("test");
        assertEquals("Hallo", m.get("1"));
        assertNull(m.get("2"));
        assertEquals("!", m.get("3"));

        ts.close();
        s.close();
    }

}
