/*
 * Copyright 2004-2007 H2 Group. Licensed under the H2 License, Version 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.result;

import java.sql.SQLException;

import org.h2.constant.SysProperties;
import org.h2.engine.Constants;
import org.h2.engine.Database;
import org.h2.engine.Session;
import org.h2.store.DataPage;
import org.h2.store.FileStore;
import org.h2.util.ObjectArray;
import org.h2.value.Value;

class ResultDiskBuffer {

    private static final int READ_AHEAD = 128;

    private DataPage rowBuff;
    private FileStore file;
    private ObjectArray tapes;
    private ResultDiskTape mainTape;
    private SortOrder sort;
    private int columnCount;

    public ResultDiskBuffer(Session session, SortOrder sort, int columnCount) throws SQLException {
        this.sort = sort;
        this.columnCount = columnCount;
        Database db = session.getDatabase();
        rowBuff = DataPage.create(db, Constants.DEFAULT_DATA_PAGE_SIZE);
        String fileName = session.getDatabase().createTempFile();
        file = session.getDatabase().openFile(fileName, "rw", false);
        file.setCheckedWriting(false);
        file.autoDelete();
        file.seek(FileStore.HEADER_LENGTH);
        if (sort != null) {
            tapes = new ObjectArray();
        } else {
            mainTape = new ResultDiskTape();
            mainTape.pos = FileStore.HEADER_LENGTH;
        }
    }

    public void addRows(ObjectArray rows) throws SQLException {
        if (sort != null) {
            sort.sort(rows);
        }
        DataPage buff = rowBuff;
        long start = file.getFilePointer();
        for (int i = 0; i < rows.size(); i++) {
            buff.reset();
            buff.writeInt(0);
            Value[] row = (Value[]) rows.get(i);
            for (int j = 0; j < columnCount; j++) {
                buff.writeValue(row[j]);
            }
            buff.fillAligned();
            int len = buff.length();
            buff.setInt(0, len);
            buff.updateChecksum();
            file.write(buff.getBytes(), 0, len);
        }
        if (sort != null) {
            ResultDiskTape tape = new ResultDiskTape();
            tape.start = start;
            tape.end = file.getFilePointer();
            tapes.add(tape);
        } else {
            mainTape.end = file.getFilePointer();
        }
    }

    public void done() throws SQLException {
        file.seek(FileStore.HEADER_LENGTH);
    }

    public void reset() {
        if (sort != null) {
            for (int i = 0; i < tapes.size(); i++) {
                ResultDiskTape tape = getTape(i);
                tape.pos = tape.start;
                tape.buffer = new ObjectArray();
            }
        } else {
            mainTape.pos = FileStore.HEADER_LENGTH;
        }
    }

    private void readRow(ResultDiskTape tape) throws SQLException {
        int min = Constants.FILE_BLOCK_SIZE;
        DataPage buff = rowBuff;
        buff.reset();
        file.readFully(buff.getBytes(), 0, min);
        int len = buff.readInt();
        buff.checkCapacity(len);
        if(len-min > 0) {
            file.readFully(buff.getBytes(), min, len - min);
        }
        buff.check(len);
        tape.pos += len;
        Value[] row = new Value[columnCount];
        for (int k = 0; k < columnCount; k++) {
            row[k] = buff.readValue();
        }
        tape.buffer.add(row);
    }

    public Value[] next() throws SQLException {
        return sort != null ? nextSorted() : nextUnsorted();
    }

    private Value[] nextUnsorted() throws SQLException {
        file.seek(mainTape.pos);
        if (mainTape.buffer.size() == 0) {
            for (int j = 0; mainTape.pos < mainTape.end && j < READ_AHEAD; j++) {
                readRow(mainTape);
            }
        }
        Value[] row = (Value[]) mainTape.buffer.get(0);
        mainTape.buffer.remove(0);
        return row;
    }

    private Value[] nextSorted() throws SQLException {
        int next = -1;
        for (int i = 0; i < tapes.size(); i++) {
            ResultDiskTape tape = getTape(i);
            if (tape.buffer.size() == 0 && tape.pos < tape.end) {
                file.seek(tape.pos);
                for (int j = 0; tape.pos < tape.end && j < READ_AHEAD; j++) {
                    readRow(tape);
                }
            }
            if (tape.buffer.size() > 0) {
                if (next == -1) {
                    next = i;
                } else if (compareTapes(tape, getTape(next)) < 0) {
                    next = i;
                }
            }
        }
        ResultDiskTape t = getTape(next);
        Value[] row = (Value[]) t.buffer.get(0);
        t.buffer.remove(0);
        return row;
    }
    
    private ResultDiskTape getTape(int i) {
        return (ResultDiskTape) tapes.get(i);
    }

    private int compareTapes(ResultDiskTape a, ResultDiskTape b) throws SQLException {
        Value[] va = (Value[]) a.buffer.get(0);
        Value[] vb = (Value[]) b.buffer.get(0);
        return sort.compare(va, vb);
    }

    protected void finalize() {
        if(!SysProperties.runFinalize) {
            return;
        }        
        close();
    }

    public void close() {
        if (file != null) {
            file.closeAndDeleteSilently();
            file = null;
        }
    }

}
