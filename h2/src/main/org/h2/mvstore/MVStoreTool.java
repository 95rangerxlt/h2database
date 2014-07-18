/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.sql.Timestamp;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

import org.h2.mvstore.type.DataType;
import org.h2.mvstore.type.StringDataType;
import org.h2.store.fs.FilePath;
import org.h2.store.fs.FileUtils;

/**
 * Utility methods used in combination with the MVStore.
 */
public class MVStoreTool {

    /**
     * Runs this tool.
     * Options are case sensitive. Supported options are:
     * <table>
     * <tr><td>[-dump &lt;fileName&gt;]</td>
     * <td>Dump the contends of the file</td></tr>
     * <tr><td>[-info &lt;fileName&gt;]</td>
     * <td>Get summary information about a file</td></tr>
     * </table>
     *
     * @param args the command line arguments
     */
    public static void main(String... args) {
        for (int i = 0; i < args.length; i++) {
            if ("-dump".equals(args[i])) {
                String fileName = args[++i];
                dump(fileName, new PrintWriter(System.out));
            } else if ("-info".equals(args[i])) {
                String fileName = args[++i];
                info(fileName, new PrintWriter(System.out));
            } else if ("-compact".equals(args[i])) {
                String fileName = args[++i];
                compact(fileName, false);
            } else if ("-compress".equals(args[i])) {
                String fileName = args[++i];
                compact(fileName, true);
            }
        }
    }

    /**
     * Read the contents of the file and write them to system out.
     *
     * @param fileName the name of the file
     */
    public static void dump(String fileName) {
        dump(fileName, new PrintWriter(System.out));
    }

    /**
     * Read the summary information of the file and write them to system out.
     *
     * @param fileName the name of the file
     */
    public static void info(String fileName) {
        info(fileName, new PrintWriter(System.out));
    }

    /**
     * Read the contents of the file and display them in a human-readable
     * format.
     *
     * @param fileName the name of the file
     * @param writer the print writer
     */
    public static void dump(String fileName, Writer writer) {
        PrintWriter pw = new PrintWriter(writer, true);
        if (!FilePath.get(fileName).exists()) {
            pw.println("File not found: " + fileName);
            return;
        }
        long size = FileUtils.size(fileName);
        pw.printf("File %s, %d bytes, %d MB\n", fileName, size, size / 1024 / 1024);
        FileChannel file = null;
        int blockSize = MVStore.BLOCK_SIZE;
        try {
            file = FilePath.get(fileName).open("r");
            long fileSize = file.size();
            int len = Long.toHexString(fileSize).length();
            ByteBuffer block = ByteBuffer.allocate(4096);
            for (long pos = 0; pos < fileSize;) {
                block.rewind();
                DataUtils.readFully(file, pos, block);
                block.rewind();
                int headerType = block.get();
                if (headerType == 'H') {
                    pw.printf("%0" + len + "x fileHeader %s%n",
                            pos,
                            new String(block.array(), DataUtils.LATIN).trim());
                    pos += blockSize;
                    continue;
                }
                if (headerType != 'c') {
                    pos += blockSize;
                    continue;
                }
                block.position(0);
                Chunk c = null;
                try {
                    c = Chunk.readChunkHeader(block, pos);
                } catch (IllegalStateException e) {
                    pos += blockSize;
                    continue;
                }
                int length = c.len * MVStore.BLOCK_SIZE;
                pw.printf("%n%0" + len + "x chunkHeader %s%n",
                        pos, c.toString());
                ByteBuffer chunk = ByteBuffer.allocate(length);
                DataUtils.readFully(file, pos, chunk);
                int p = block.position();
                pos += length;
                int remaining = c.pageCount;
                while (remaining > 0) {
                    chunk.position(p);
                    int pageSize = chunk.getInt();
                    // check value (ignored)
                    chunk.getShort();
                    int mapId = DataUtils.readVarInt(chunk);
                    int entries = DataUtils.readVarInt(chunk);
                    int type = chunk.get();
                    boolean compressed = (type & 2) != 0;
                    boolean node = (type & 1) != 0;
                    pw.printf(
                            "+%0" + len +
                            "x %s, map %x, %d entries, %d bytes%n",
                            p,
                            (node ? "node" : "leaf") +
                            (compressed ? " compressed" : ""),
                            mapId,
                            node ? entries + 1 : entries,
                            pageSize);
                    p += pageSize;
                    remaining--;
                    long[] children = null;
                    long[] counts = null;
                    if (node) {
                        children = new long[entries + 1];
                        for (int i = 0; i <= entries; i++) {
                            children[i] = chunk.getLong();
                        }
                        counts = new long[entries + 1];
                        for (int i = 0; i <= entries; i++) {
                            long s = DataUtils.readVarLong(chunk);
                            counts[i] = s;
                        }
                    }
                    String[] keys = new String[entries];
                    if (mapId == 0) {
                        if (!compressed) {
                            for (int i = 0; i < entries; i++) {
                                String k = StringDataType.INSTANCE.read(chunk);
                                keys[i] = k;
                            }
                        }
                        if (node) {
                            // meta map node
                            for (int i = 0; i < entries; i++) {
                                long cp = children[i];
                                pw.printf("    %d children < %s @ " +
                                        "chunk %x +%0" +
                                        len + "x%n",
                                        counts[i],
                                        keys[i],
                                        DataUtils.getPageChunkId(cp),
                                        DataUtils.getPageOffset(cp));
                            }
                            long cp = children[entries];
                            pw.printf("    %d children >= %s @ chunk %x +%0" +
                                    len + "x%n",
                                    counts[entries],
                                    keys.length >= entries ? null : keys[entries],
                                    DataUtils.getPageChunkId(cp),
                                    DataUtils.getPageOffset(cp));
                        } else if (!compressed) {
                            // meta map leaf
                            String[] values = new String[entries];
                            for (int i = 0; i < entries; i++) {
                                String v = StringDataType.INSTANCE.read(chunk);
                                values[i] = v;
                            }
                            for (int i = 0; i < entries; i++) {
                                pw.println("    " + keys[i] +
                                        " = " + values[i]);
                            }
                        }
                    } else {
                        if (node) {
                            for (int i = 0; i <= entries; i++) {
                                long cp = children[i];
                                pw.printf("    %d children @ chunk %x +%0" +
                                        len + "x%n",
                                        counts[i],
                                        DataUtils.getPageChunkId(cp),
                                        DataUtils.getPageOffset(cp));
                            }
                        }
                    }
                }
                int footerPos = chunk.limit() - Chunk.FOOTER_LENGTH;
                chunk.position(footerPos);
                pw.printf(
                        "+%0" + len + "x chunkFooter %s%n",
                        footerPos,
                        new String(chunk.array(), chunk.position(),
                                Chunk.FOOTER_LENGTH, DataUtils.LATIN).trim());
            }
            pw.printf("%n%0" + len + "x eof%n", fileSize);
            pw.printf("\n");
        } catch (IOException e) {
            pw.println("ERROR: " + e);
            e.printStackTrace(pw);
        } finally {
            if (file != null) {
                try {
                    file.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
        pw.flush();
    }

    /**
     * Read the summary information of the file and write them to system out.
     *
     * @param fileName the name of the file
     * @param writer the print writer
     */
    public static void info(String fileName, Writer writer) {
        PrintWriter pw = new PrintWriter(writer, true);
        if (!FilePath.get(fileName).exists()) {
            pw.println("File not found: " + fileName);
            return;
        }
        long fileLength = FileUtils.size(fileName);
        MVStore store = new MVStore.Builder().
                fileName(fileName).
                readOnly().open();
        try {
            MVMap<String, String> meta = store.getMetaMap();
            Map<String, Object> header = store.getStoreHeader();
            long fileCreated = DataUtils.readHexLong(header, "created", 0L);
            TreeMap<Integer, Chunk> chunks = new TreeMap<Integer, Chunk>();
            long chunkLength = 0;
            long maxLength = 0;
            long maxLengthLive = 0;
            for (Entry<String, String> e : meta.entrySet()) {
                String k = e.getKey();
                if (k.startsWith("chunk.")) {
                    Chunk c = Chunk.fromString(e.getValue());
                    chunks.put(c.id, c);
                    chunkLength += c.len * MVStore.BLOCK_SIZE;
                    maxLength += c.maxLen;
                    maxLengthLive += c.maxLenLive;
                }
            }
            pw.printf("Created: %s\n", formatTimestamp(fileCreated));
            pw.printf("File length: %d\n", fileLength);
            pw.printf("Chunk length: %d\n", chunkLength);
            pw.printf("Chunk count: %d\n", chunks.size());
            pw.printf("Used space: %d%%\n", 100 * chunkLength / fileLength);
            pw.printf("Chunk fill rate: %d%%\n", 100 * maxLengthLive / maxLength);
            for (Entry<Integer, Chunk> e : chunks.entrySet()) {
                Chunk c = e.getValue();
                long created = fileCreated + c.time;
                pw.printf("  Chunk %d: %s, %d%% used, %d blocks\n",
                        c.id, formatTimestamp(created),
                        100 * c.maxLenLive / c.maxLen,
                        c.len
                        );
            }
            pw.printf("\n");
        } catch (Exception e) {
            pw.println("ERROR: " + e);
            e.printStackTrace(pw);
        } finally {
            store.close();
        }
        pw.flush();
    }

    private static String formatTimestamp(long t) {
        String x = new Timestamp(t).toString();
        return x.substring(0, 19);
    }

    /**
     * Compress the store by creating a new file and copying the live pages
     * there.
     *
     * @param fileName the file name
     * @param compress whether to compress the data
     */
    public static void compact(String fileName, boolean compress) {
        compact(fileName, fileName + ".new", compress);
        FileUtils.moveTo(fileName, fileName + ".old");
        FileUtils.moveTo(fileName, fileName);
        FileUtils.delete(fileName + ".old");
    }

    /**
     * Copy all live pages from the source store to the target store.
     *
     * @param sourceFileName the name of the source store
     * @param targetFileName the name of the target store
     */
    public static void compact(String sourceFileName, String targetFileName, boolean compress) {
        MVStore source = new MVStore.Builder().
                fileName(sourceFileName).
                readOnly().
                open();
        FileUtils.delete(targetFileName);
        MVStore.Builder b = new MVStore.Builder().
                fileName(targetFileName);
        if (compress) {
            b.compress();
        }
        MVStore target = b.open();
        MVMap<String, String> sourceMeta = source.getMetaMap();
        MVMap<String, String> targetMeta = target.getMetaMap();
        for (Entry<String, String> m : sourceMeta.entrySet()) {
            String key = m.getKey();
            if (key.startsWith("chunk.")) {
                // ignore
            } else if (key.startsWith("map.")) {
                // ignore
            } else if (key.startsWith("name.")) {
                // ignore
            } else if (key.startsWith("root.")) {
                // ignore
            } else {
                targetMeta.put(key, m.getValue());
            }
        }
        for (String mapName : source.getMapNames()) {
            MVMap.Builder<Object, Object> mp =
                    new MVMap.Builder<Object, Object>().
                    keyType(new GenericDataType()).
                    valueType(new GenericDataType());
            MVMap<Object, Object> sourceMap = source.openMap(mapName, mp);
            MVMap<Object, Object> targetMap = target.openMap(mapName, mp);
            targetMap.copyFrom(sourceMap);
        }
        target.close();
        source.close();
    }

    /**
     * A data type that can read any data that is persisted, and converts it to
     * a byte array.
     */
    static class GenericDataType implements DataType {

        @Override
        public int compare(Object a, Object b) {
            throw DataUtils.newUnsupportedOperationException("Can not compare");
        }

        @Override
        public int getMemory(Object obj) {
            return obj == null ? 0 : ((byte[]) obj).length * 8;
        }

        @Override
        public void write(WriteBuffer buff, Object obj) {
            if (obj != null) {
                buff.put((byte[]) obj);
            }
        }

        @Override
        public void write(WriteBuffer buff, Object[] obj, int len, boolean key) {
            for (Object o : obj) {
                write(buff, o);
            }
        }

        @Override
        public Object read(ByteBuffer buff) {
            int len = buff.remaining();
            if (len == 0) {
                return null;
            }
            byte[] data = new byte[len];
            buff.get(data);
            return data;
        }

        @Override
        public void read(ByteBuffer buff, Object[] obj, int len, boolean key) {
            for (int i = 0; i < obj.length; i++) {
                obj[i] = read(buff);
            }
        }

    }


}
