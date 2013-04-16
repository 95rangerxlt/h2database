/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore.db;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.h2.mvstore.Cursor;
import org.h2.mvstore.DataUtils;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVMap.Builder;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.type.DataType;
import org.h2.mvstore.type.ObjectDataType;
import org.h2.util.New;

/**
 * A store that supports concurrent transactions.
 */
public class TransactionStore {

    private static final String LAST_TRANSACTION_ID = "lastTransactionId";

    private static final int MAX_UNSAVED_PAGES = 4 * 1024;

    /**
     * The store.
     */
    final MVStore store;

    /**
     * The persisted map of open transaction.
     * Key: transactionId, value: [ status, name ].
     */
    final MVMap<Long, Object[]> openTransactions;

    /**
     * The map of open transaction objects.
     * Key: transactionId, value: transaction object.
     */
    final HashMap<Long, Transaction> openTransactionMap = New.hashMap();

    /**
     * The undo log.
     * Key: [ transactionId, logId ], value: [ opType, mapId, key, oldValue ].
     */
    final MVMap<long[], Object[]> undoLog;

    /**
     * The lock timeout in milliseconds. 0 means timeout immediately.
     */
    long lockTimeout;

    /**
     * The transaction settings. "lastTransaction" the last transaction id.
     */
    private final MVMap<String, String> settings;

    private long lastTransactionIdStored;

    private long lastTransactionId;

    /**
     * Create a new transaction store.
     *
     * @param store the store
     */
    public TransactionStore(MVStore store) {
        this(store, new ObjectDataType());
    }

    /**
     * Create a new transaction store.
     *
     * @param store the store
     * @param keyType the data type for map keys
     */
    public TransactionStore(MVStore store, DataType keyType) {
        this.store = store;
        settings = store.openMap("settings");
        openTransactions = store.openMap("openTransactions",
                new MVMap.Builder<Long, Object[]>());
        // commit could be faster if we have one undo log per transaction,
        // or a range delete operation for maps
        ArrayType oldValueType = new ArrayType(new DataType[]{
                new ObjectDataType(), new ObjectDataType(),
                keyType
        });
        ArrayType valueType = new ArrayType(new DataType[]{
                new ObjectDataType(), new ObjectDataType(), keyType,
                oldValueType
        });
        MVMap.Builder<long[], Object[]> builder =
                new MVMap.Builder<long[], Object[]>().
                valueType(valueType);
        // TODO escape other map names, to avoid conflicts
        undoLog = store.openMap("undoLog", builder);
        init();
    }

    private void init() {
        String s = settings.get(LAST_TRANSACTION_ID);
        if (s != null) {
            lastTransactionId = Long.parseLong(s);
            lastTransactionIdStored = lastTransactionId;
        }
        Long lastKey = openTransactions.lastKey();
        if (lastKey != null && lastKey.longValue() > lastTransactionId) {
            throw DataUtils.newIllegalStateException("Last transaction not stored");
        }
        Cursor<Long> cursor = openTransactions.keyIterator(null);
        while (cursor.hasNext()) {
            long id = cursor.next();
            Object[] data = openTransactions.get(id);
            int status = (Integer) data[0];
            String name = (String) data[1];
            long[] next = { id + 1, -1 };
            long[] last = undoLog.floorKey(next);
            if (last == null) {
                // no entry
            } else if (last[0] == id) {
                Transaction t = new Transaction(this, id, status, name, last[1]);
                t.setStored(true);
                openTransactionMap.put(id, t);
            }
        }
    }

    /**
     * Get the list of currently open transactions that have pending writes.
     *
     * @return the list of transactions
     */
    public synchronized List<Transaction> getOpenTransactions() {
        ArrayList<Transaction> list = New.arrayList();
        list.addAll(openTransactionMap.values());
        return list;
    }

    /**
     * Close the transaction store.
     */
    public synchronized void close() {
        // to avoid losing transaction ids
        settings.put(LAST_TRANSACTION_ID, "" + lastTransactionId);
        store.commit();
    }

    /**
     * Begin a new transaction.
     *
     * @return the transaction
     */
    public synchronized Transaction begin() {
        long transactionId = lastTransactionId++;
        int status = Transaction.STATUS_OPEN;
        return new Transaction(this, transactionId, status, null, 0);
    }

    private void storeTransaction(Transaction t) {
        if (store.getUnsavedPageCount() > MAX_UNSAVED_PAGES) {
            store.commit();
        }
        if (t.isStored()) {
            return;
        }
        t.setStored(true);
        long transactionId = t.getId();
        Object[] v = { t.getStatus(), null };
        openTransactions.put(transactionId, v);
        openTransactionMap.put(transactionId, t);
        if (lastTransactionId > lastTransactionIdStored) {
            lastTransactionIdStored += 32;
            settings.put(LAST_TRANSACTION_ID, "" + lastTransactionIdStored);
        }
    }

    /**
     * Prepare a transaction.
     *
     * @param transactionId the transaction id
     */
    void prepare(Transaction t) {
        storeTransaction(t);
        Object[] old = openTransactions.get(t.getId());
        Object[] v = { Transaction.STATUS_PREPARED, old[1] };
        openTransactions.put(t.getId(), v);
        store.commit();
    }

    /**
     * Log an entry.
     *
     * @param t the transaction
     * @param logId the log id
     * @param opType the operation type
     * @param mapId the map id
     * @param key the key
     * @param oldValue the old value
     */
    void log(Transaction t, long logId, int opType, int mapId,
            Object key, Object oldValue) {
        storeTransaction(t);
        long[] undoKey = { t.getId(), logId };
        Object[] log = new Object[] { opType, mapId, key, oldValue };
        undoLog.put(undoKey, log);
    }

    /**
     * Set the name of a transaction.
     *
     * @param t the transaction
     * @param name the new name
     */
    void setTransactionName(Transaction t, String name) {
        storeTransaction(t);
        Object[] old = openTransactions.get(t.getId());
        Object[] v = { old[0], name };
        openTransactions.put(t.getId(), v);
    }

    /**
     * Commit a transaction.
     *
     * @param t the transaction
     * @param maxLogId the last log id
     */
    void commit(Transaction t, long maxLogId) {
        for (long logId = 0; logId < maxLogId; logId++) {
            long[] undoKey = new long[] {
                    t.getId(), logId };
            Object[] op = undoLog.get(undoKey);
            int opType = (Integer) op[0];
            if (opType == Transaction.OP_REMOVE) {
                int mapId = (Integer) op[1];
                Map<String, String> meta = store.getMetaMap();
                String m = meta.get("map." + mapId);
                String mapName = DataUtils.parseMap(m).get("name");
                MVMap<Object, Object[]> map = store.openMap(mapName);
                Object key = op[2];
                Object[] value = map.get(key);
                // possibly the entry was added later on
                // so we have to check
                if (value[2] == null) {
                    // remove the value
                    map.remove(key);
                }
            }
            undoLog.remove(undoKey);
        }
        endTransaction(t);
    }

    /**
     * Roll a transaction back.
     *
     * @param t the transaction
     * @param maxLogId the last log id
     */
    void rollback(Transaction t, long maxLogId) {
        rollbackTo(t, maxLogId, 0);
        endTransaction(t);
    }
    
    private void endTransaction(Transaction t) {
        openTransactions.remove(t.getId());
        openTransactionMap.remove(t.getId());
    }

    /**
     * Rollback to an old savepoint.
     *
     * @param t the transaction
     * @param maxLogId the last log id
     * @param toLogId the log id to roll back to
     */
    void rollbackTo(Transaction t, long maxLogId, long toLogId) {
        for (long logId = maxLogId - 1; logId >= toLogId; logId--) {
            Object[] op = undoLog.get(new long[] {
                    t.getId(), logId });
            int mapId = ((Integer) op[1]).intValue();
            Map<String, String> meta = store.getMetaMap();
            String m = meta.get("map." + mapId);
            String mapName = DataUtils.parseMap(m).get("name");
            MVMap<Object, Object[]> map = store.openMap(mapName);
            Object key = op[2];
            Object[] oldValue = (Object[]) op[3];
            if (oldValue == null) {
                // this transaction added the value
                map.remove(key);
            } else {
                // this transaction updated the value
                map.put(key, oldValue);
            }
            undoLog.remove(op);
        }
    }

    /**
     * A transaction.
     */
    public static class Transaction {

        /**
         * The status of an open transaction.
         */
        public static final int STATUS_OPEN = 0;

        /**
         * The status of a prepared transaction.
         */
        public static final int STATUS_PREPARED = 1;

        /**
         * The status of a closed transaction (committed or rolled back).
         */
        public static final int STATUS_CLOSED = 2;

        /**
         * The operation type for changes in a map.
         */
        static final int OP_REMOVE = 0, OP_ADD = 1, OP_SET = 2;

        /**
         * The transaction store.
         */
        final TransactionStore store;

        /**
         * The version of the store at the time the transaction was started.
         */
        final long startVersion;

        /**
         * The transaction id.
         */
        final Long transactionId;

        long logId;
        
        private int status;

        private String name;
        
        private boolean stored;

        Transaction(TransactionStore store, long transactionId, int status, String name, long logId) {
            this.store = store;
            this.startVersion = store.store.getCurrentVersion();
            this.transactionId = transactionId;
            this.status = status;
            this.name = name;
            this.logId = logId;
        }

        boolean isStored() {
            return stored;
        }

        void setStored(boolean stored) {
            this.stored = stored;
        }

        /**
         * Get the transaction id.
         *
         * @return the transaction id
         */
        public Long getId() {
            return transactionId;
        }

        /**
         * Get the transaction status.
         *
         * @return the status
         */
        public int getStatus() {
            return status;
        }

        /**
         * Set the name of the transaction.
         *
         * @param name the new name
         */
        public void setName(String name) {
            checkOpen();
            store.setTransactionName(this, name);
            this.name = name;
        }

        /**
         * Get the name of the transaction.
         *
         * @return name the name
         */
        public String getName() {
            return name;
        }

        /**
         * Create a new savepoint.
         *
         * @return the savepoint id
         */
        public long setSavepoint() {
            checkOpen();
            return logId;
        }

        /**
         * Add a log entry.
         *
         * @param opType the operation type
         * @param mapId the map id
         * @param key the key
         * @param oldValue the old value
         */
        void log(int opType, int mapId, Object key, Object oldValue) {
            store.log(this, logId++, opType, mapId, key, oldValue);
        }

        /**
         * Open a data map.
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param name the name of the map
         * @return the transaction map
         */
        public <K, V> TransactionMap<K, V> openMap(String name) {
            checkOpen();
            return new TransactionMap<K, V>(this, name, new ObjectDataType(),
                    new ObjectDataType());
        }

        /**
         * Open the map to store the data.
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param name the name of the map
         * @param builder the builder
         * @return the transaction map
         */
        public <K, V> TransactionMap<K, V> openMap(String name, Builder<K, V> builder) {
            checkOpen();
            DataType keyType = builder.getKeyType();
            if (keyType == null) {
                keyType = new ObjectDataType();
            }
            DataType valueType = builder.getValueType();
            if (valueType == null) {
                valueType = new ObjectDataType();
            }
            return new TransactionMap<K, V>(this, name, keyType, valueType);
        }

        /**
         * Roll back to the given savepoint. This is only allowed if the
         * transaction is open.
         *
         * @param savepointId the savepoint id
         */
        public void rollbackToSavepoint(long savepointId) {
            checkOpen();
            store.rollbackTo(this, this.logId, savepointId);
            this.logId = savepointId;
        }

        /**
         * Prepare the transaction. Afterwards, the transaction can only be
         * committed or rolled back.
         */
        public void prepare() {
            checkOpen();
            store.prepare(this);
            status = STATUS_PREPARED;
        }

        /**
         * Commit the transaction. Afterwards, this transaction is closed.
         */
        public void commit() {
            if (status != STATUS_CLOSED) {
                store.commit(this, logId);
                status = STATUS_CLOSED;
            }
        }

        /**
         * Roll the transaction back. Afterwards, this transaction is closed.
         */
        public void rollback() {
            if (status != STATUS_CLOSED) {
                store.rollback(this, logId);
                status = STATUS_CLOSED;
            }
        }

        /**
         * Check whether this transaction is still open.
         */
        void checkOpen() {
            if (status != STATUS_OPEN) {
                throw DataUtils.newIllegalStateException("Transaction is closed");
            }
        }

        public long getCurrentVersion() {
            return store.store.getCurrentVersion();
        }

    }

    /**
     * A map that supports transactions.
     *
     * @param <K> the key type
     * @param <V> the value type
     */
    public static class TransactionMap<K, V> {

        /**
         * The map used for writing (the latest version).
         * <p>
         * Key: key the key of the data.
         * Value: { transactionId, oldVersion, value }
         */
        final MVMap<K, Object[]> map;
        
        private Transaction transaction;

        private final int mapId;

        /**
         * If a record was read that was updated by this transaction, and the
         * update occurred before this log id, the older version is read. This
         * is so that changes are not immediately visible, to support statement
         * processing (for example "update test set id = id + 1").
         */
        private long readLogId = Long.MAX_VALUE;

        TransactionMap(Transaction transaction, String name, DataType keyType,
                DataType valueType) {
            this.transaction = transaction;
            ArrayType arrayType = new ArrayType(new DataType[] {
                    new ObjectDataType(), new ObjectDataType(), valueType
            });
            MVMap.Builder<K, Object[]> builder = new MVMap.Builder<K, Object[]>()
                    .keyType(keyType).valueType(arrayType);
            map = transaction.store.store.openMap(name, builder);
            mapId = map.getId();
        }
        
        private TransactionMap(Transaction transaction, MVMap<K, Object[]> map, int mapId) {
            this.transaction = transaction;
            this.map = map;
            this.mapId = mapId;
        }
        
        /**
         * Set the savepoint. Afterwards, reads are based on the specified
         * savepoint.
         * 
         * @param savepoint the savepoint
         */
        public void setSavepoint(long savepoint) {
            this.readLogId = savepoint;
        }
        
        /**
         * Get a clone of this map for the given transaction.
         * 
         * @param transaction the transaction
         * @param savepoint the savepoint
         * @return the map
         */
        public TransactionMap<K, V> getInstance(Transaction transaction, long savepoint) {
            TransactionMap<K, V> m = new TransactionMap<K, V>(transaction, map, mapId);
            m.setSavepoint(savepoint);
            return m;
        }

        /**
         * Get the size of the map as seen by this transaction.
         *
         * @return the size
         */
        public long getSize() {
            // TODO this method is very slow
            long size = 0;
            Cursor<K> cursor = map.keyIterator(null);
            while (cursor.hasNext()) {
                K key = cursor.next();
                if (get(key) != null) {
                    size++;
                }
            }
            return size;
        }

        private void checkOpen() {
            transaction.checkOpen();
        }

        /**
         * Remove an entry.
         * <p>
         * If the row is locked, this method will retry until the row could be
         * updated or until a lock timeout.
         *
         * @param key the key
         * @throws IllegalStateException if a lock timeout occurs
         */
        public V remove(K key) {
            return set(key, null);
        }

        /**
         * Update the value for the given key.
         * <p>
         * If the row is locked, this method will retry until the row could be
         * updated or until a lock timeout.
         *
         * @param key the key
         * @param value the new value (not null)
         * @throws IllegalStateException if a lock timeout occurs
         */
        public V put(K key, V value) {
            DataUtils.checkArgument(value != null, "The value may not be null");
            return set(key, value);
        }

        private V set(K key, V value) {
            checkOpen();
            long start = 0;
            while (true) {
                V old = get(key);
                boolean ok = trySet(key, value, false);
                if (ok) {
                    return old;
                }
                // an uncommitted transaction:
                // wait until it is committed, or until the lock timeout
                long timeout = transaction.store.lockTimeout;
                if (timeout == 0) {
                    throw DataUtils.newIllegalStateException("Lock timeout");
                }
                if (start == 0) {
                    start = System.currentTimeMillis();
                } else {
                    long t = System.currentTimeMillis() - start;
                    if (t > timeout) {
                        throw DataUtils.newIllegalStateException("Lock timeout");
                    }
                    // TODO use wait/notify instead, or remove the feature
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        // ignore
                    }
                }
            }
        }

        /**
         * Try to remove the value for the given key.
         * <p>
         * This will fail if the row is locked by another transaction (that
         * means, if another open transaction changed the row).
         *
         * @param key the key
         * @return whether the entry could be removed
         */
        public boolean tryRemove(K key) {
            return trySet(key, null, false);
        }

        /**
         * Try to update the value for the given key.
         * <p>
         * This will fail if the row is locked by another transaction (that
         * means, if another open transaction changed the row).
         *
         * @param key the key
         * @param value the new value
         * @return whether the entry could be updated
         */
        public boolean tryPut(K key, V value) {
            DataUtils.checkArgument(value != null, "The value may not be null");
            return trySet(key, value, false);
        }

        /**
         * Try to set or remove the value. When updating only unchanged entries,
         * then the value is only changed if it was not changed after opening
         * the map.
         *
         * @param key the key
         * @param value the new value (null to remove the value)
         * @param onlyIfUnchanged only set the value if it was not changed (by
         *            this or another transaction) since the map was opened
         * @return true if the value was set
         */
        public boolean trySet(K key, V value, boolean onlyIfUnchanged) {
            Object[] current = map.get(key);
            if (onlyIfUnchanged) {
                Object[] old = getArray(key, readLogId);
                if (!map.areValuesEqual(old, current)) {
                    long tx = (Long) current[0];
                    if (tx == transaction.transactionId) {
                        if (value == null) {
                            // ignore removing an entry
                            // if it was added or changed
                            // in the same statement
                            return true;
                        } else if (current[2] == null) {
                            // add an entry that was removed
                            // in the same statement
                        } else {
                            return false;
                        }
                    } else {
                        return false;
                    }
                }
            }
            int opType;
            if (current == null || current[2] == null) {
                if (value == null) {
                    // remove a removed value
                    opType = Transaction.OP_SET;
                } else {
                    opType = Transaction.OP_ADD;
                }
            } else {
                if (value == null) {
                    opType = Transaction.OP_REMOVE;
                } else {
                    opType = Transaction.OP_SET;
                }
            }
            Object[] newValue = { 
                    transaction.transactionId, 
                    transaction.logId, value };
            if (current == null) {
                // a new value
                Object[] old = map.putIfAbsent(key, newValue);
                if (old == null) {
                    transaction.log(opType, mapId, key, current);
                    return true;
                }
                return false;
            }
            long tx = (Long) current[0];
            if (tx == transaction.transactionId) {
                // added or updated by this transaction
                if (map.replace(key, current, newValue)) {
                    transaction.log(opType, mapId, key, current);
                    return true;
                }
                // strange, somebody overwrite the value
                // even thought the change was not committed
                return false;
            }
            // added or updated by another transaction
            boolean open = transaction.store.openTransactions.containsKey(tx);
            if (!open) {
                // the transaction is committed:
                // overwrite the value
                if (map.replace(key, current, newValue)) {
                    transaction.log(opType, mapId, key, current);
                    return true;
                }
                // somebody else was faster
                return false;
            }
            // the transaction is not yet committed
            return false;
        }

        /**
         * Get the value for the given key at the time when this map was opened.
         *
         * @param key the key
         * @return the value or null
         */
        public V get(K key) {
            return get(key, readLogId);
        }

        /**
         * Get the most recent value for the given key.
         *
         * @param key the key
         * @return the value or null
         */
        public V getLatest(K key) {
            return get(key, Long.MAX_VALUE);
        }

        /**
         * Whether the map contains the key.
         *
         * @param key the key
         * @return true if the map contains an entry for this key
         */
        public boolean containsKey(K key) {
            return get(key) != null;
        }

        /**
         * Get the value for the given key.
         *
         * @param key the key
         * @param maxLogId the maximum log id
         * @return the value or null
         */
        @SuppressWarnings("unchecked")
        public V get(K key, long maxLogId) {
            checkOpen();
            Object[] data = getArray(key, maxLogId);
            return data == null ? null : (V) data[2];
        }
        
        private Object[] getArray(K key, long maxLog) {
            Object[] data = map.get(key);
            while (true) {
                long tx;
                if (data == null) {
                    // doesn't exist or deleted by a committed transaction
                    return null;
                }
                tx = (Long) data[0];
                long logId = (Long) data[1];
                if (tx == transaction.transactionId) {
                    // added by this transaction
                    if (logId < maxLog) {
                        return data;
                    }
                }
                // added or updated by another transaction
                boolean open = transaction.store.openTransactions.containsKey(tx);
                if (!open) {
                    // it is committed
                    return data;
                }
                // get the value before the uncommitted transaction
                long[] x = new long[] { tx, logId };
                data = transaction.store.undoLog.get(x);
                data = (Object[]) data[3];
            }
        }


        /**
         * Rename the map.
         *
         * @param newMapName the new map name
         */
        public void renameMap(String newMapName) {
            // TODO rename maps transactionally
            map.renameMap(newMapName);
        }

        /**
         * Check whether this map is closed.
         *
         * @return true if closed
         */
        public boolean isClosed() {
            return map.isClosed();
        }

        /**
         * Remove the map.
         */
        public void removeMap() {
            // TODO remove in a transaction
            map.removeMap();
        }

        /**
         * Clear the map.
         */
        public void clear() {
            // TODO truncate transactionally
            map.clear();
        }

        /**
         * Get the first key.
         *
         * @return the first key, or null if empty
         */
        public K firstKey() {
            // TODO transactional firstKey
            return map.firstKey();
        }

        /**
         * Get the last key.
         *
         * @return the last key, or null if empty
         */
        public K lastKey() {
            // TODO transactional lastKey
            return map.lastKey();
        }

        /**
         * Iterate over all keys.
         *
         * @param from the first key to return
         * @return the iterator
         */
        public Iterator<K> keyIterator(final K from) {
            return new Iterator<K>() {
                private final Cursor<K> cursor = map.keyIterator(from);
                private K current;
                
                {
                    fetchNext();
                }
                
                private void fetchNext() {
                    while (cursor.hasNext()) {
                        current = cursor.next();
                        if (containsKey(current)) {
                            return;
                        }
                    }
                    current = null;
                }

                @Override
                public boolean hasNext() {
                    return current != null;
                }

                @Override
                public K next() {
                    K result = current;
                    fetchNext();
                    return result;
                }

                @Override
                public void remove() {
                    throw DataUtils.newUnsupportedOperationException(
                            "Removing is not supported");
                }
            };
        }

        /**
         * Get the smallest key that is larger or equal to this key.
         *
         * @param key the key (may not be null)
         * @return the result
         */
        public K ceilingKey(K key) {
            // TODO transactional ceilingKey
            return map.ceilingKey(key);
        }

        /**
         * Get the smallest key that is larger than the given key, or null if no
         * such key exists.
         *
         * @param key the key (may not be null)
         * @return the result
         */
        public K higherKey(K key) {
            // TODO transactional higherKey
            return map.higherKey(key);
        }

        /**
         * Get the largest key that is smaller than the given key, or null if no
         * such key exists.
         *
         * @param key the key (may not be null)
         * @return the result
         */
        public K lowerKey(K key) {
            // TODO Auto-generated method stub
            return map.lowerKey(key);
        }

        public Transaction getTransaction() {
            return transaction;
        }

    }

    /**
     * A data type that contains an array of objects with the specified data
     * types.
     */
    public static class ArrayType implements DataType {

        private final int arrayLength;
        private final DataType[] elementTypes;

        ArrayType(DataType[] elementTypes) {
            this.arrayLength = elementTypes.length;
            this.elementTypes = elementTypes;
        }

        @Override
        public int getMemory(Object obj) {
            Object[] array = (Object[]) obj;
            int size = 0;
            for (int i = 0; i < arrayLength; i++) {
                DataType t = elementTypes[i];
                Object o = array[i];
                if (o != null) {
                    size += t.getMemory(o);
                }
            }
            return size;
        }

        @Override
        public int compare(Object aObj, Object bObj) {
            if (aObj == bObj) {
                return 0;
            }
            Object[] a = (Object[]) aObj;
            Object[] b = (Object[]) bObj;
            for (int i = 0; i < arrayLength; i++) {
                DataType t = elementTypes[i];
                int comp = t.compare(a[i], b[i]);
                if (comp != 0) {
                    return comp;
                }
            }
            return 0;
        }

        @Override
        public ByteBuffer write(ByteBuffer buff, Object obj) {
            Object[] array = (Object[]) obj;
            for (int i = 0; i < arrayLength; i++) {
                DataType t = elementTypes[i];
                Object o = array[i];
                if (o == null) {
                    buff.put((byte) 0);
                } else {
                    buff.put((byte) 1);
                    buff = t.write(buff, o);
                }
            }
            return buff;
        }

        @Override
        public Object read(ByteBuffer buff) {
            Object[] array = new Object[arrayLength];
            for (int i = 0; i < arrayLength; i++) {
                DataType t = elementTypes[i];
                if (buff.get() == 1) {
                    array[i] = t.read(buff);
                }
            }
            return array;
        }

    }

}

