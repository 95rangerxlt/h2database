/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore;

import java.nio.ByteBuffer;
import java.util.HashMap;

/**
 * A chunk of data, containing one or multiple pages.
 * <p>
 * Chunks are page aligned (each page is usually 4096 bytes).
 * There are at most 67 million (2^26) chunks,
 * each chunk is at most 2 GB large.
 */
public class Chunk {

    /**
     * The maximum length of a chunk header, in bytes.
     */
    static final int MAX_HEADER_LENGTH = 1024;

    /**
     * The chunk id.
     */
    public final int id;

    /**
     * The start block number within the file.
     */
    public long block;

    /**
     * The length in number of blocks.
     */
    public int len;

    /**
     * The total number of pages in this chunk.
     */
    public int pageCount;

    /**
     * The number of pages still alive.
     */
    public int pageCountLive;

    /**
     * The sum of the max length of all pages.
     */
    public long maxLength;

    /**
     * The sum of the max length of all pages that are in use.
     */
    public long maxLenLive;

    /**
     * The garbage collection priority.
     */
    public int collectPriority;

    /**
     * The position of the meta root.
     */
    public long metaRootPos;

    /**
     * The version stored in this chunk.
     */
    public long version;

    /**
     * When this chunk was created, in milliseconds after the store was created.
     */
    public long time;

    /**
     * The last used map id.
     */
    public int mapId;
    
    /**
     * The predicted position of the next chunk.
     */
    public long next;
    public long nextSize;

    Chunk(int id) {
        this.id = id;
    }

    /**
     * Read the header from the byte buffer.
     *
     * @param buff the source buffer
     * @param start the start of the chunk in the file
     * @return the chunk
     */
    static Chunk readChunkHeader(ByteBuffer buff, long start) {
        int pos = buff.position();
        byte[] data = new byte[Math.min(buff.remaining(), MAX_HEADER_LENGTH)];
        buff.get(data);
        try {
            for (int i = 0; i < data.length; i++) {
                if (data[i] == '\n') {
                    // set the position to the start of the first page
                    buff.position(pos + i + 1);
                    String s = new String(data, 0, i, DataUtils.LATIN).trim();
                    return fromString(s);
                }
            }
        } catch (Exception e) {
            // there could be various reasons
        }
        throw DataUtils.newIllegalStateException(
                DataUtils.ERROR_FILE_CORRUPT,
                "File corrupt reading chunk at position {0}", start);
    }

    /**
     * Write the chunk header.
     *
     * @param buff the target buffer
     */
    void writeChunkHeader(WriteBuffer buff, int minLength) {
        long pos = buff.position();
        buff.put(asString().getBytes(DataUtils.LATIN));
        while (buff.position() - pos < minLength - 1) {
            buff.put((byte) ' ');
        }
        buff.put((byte) '\n');
    }
    
    static String getMetaKey(int chunkId) {
        return "chunk." + Integer.toHexString(chunkId);
    }

    /**
     * Build a block from the given string.
     *
     * @param s the string
     * @return the block
     */
    public static Chunk fromString(String s) {
        HashMap<String, String> map = DataUtils.parseMap(s);
        int id = DataUtils.readHexInt(map, "chunk", 0);
        Chunk c = new Chunk(id);
        c.block = DataUtils.readHexLong(map, "block", 0);
        c.len = DataUtils.readHexInt(map, "len", 0);
        c.pageCount = DataUtils.readHexInt(map, "pages", 0);
        c.pageCountLive = DataUtils.readHexInt(map, "livePages", c.pageCount);
        c.mapId = Integer.parseInt(map.get("map"), 16);
        c.maxLength = DataUtils.readHexLong(map, "max", 0);
        c.maxLenLive = DataUtils.readHexLong(map, "liveMax", c.maxLength);
        c.metaRootPos = DataUtils.readHexLong(map, "root", 0);
        c.time = DataUtils.readHexLong(map, "time", 0);
        c.version = DataUtils.readHexLong(map, "version", id);
        c.next = DataUtils.readHexLong(map, "next", 0);
        return c;
    }

    public int getFillRate() {
        return (int) (maxLength == 0 ? 0 : 100 * maxLenLive / maxLength);
    }

    @Override
    public int hashCode() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Chunk && ((Chunk) o).id == id;
    }

    /**
     * Get the chunk data as a string.
     *
     * @return the string
     */
    public String asString() {
        StringBuilder buff = new StringBuilder();
        DataUtils.appendMap(buff, "chunk", id);
        DataUtils.appendMap(buff, "block", block);
        DataUtils.appendMap(buff, "len", len);
        if (maxLength != maxLenLive) {
            DataUtils.appendMap(buff, "liveMax", maxLenLive);
        }
        if (pageCount != pageCountLive) {
            DataUtils.appendMap(buff, "livePages", pageCountLive);
        }
        DataUtils.appendMap(buff, "map", mapId);
        DataUtils.appendMap(buff, "max", maxLength);
        if (next != 0) {
            DataUtils.appendMap(buff, "next", next);
        }
        DataUtils.appendMap(buff, "pages", pageCount);
        DataUtils.appendMap(buff, "root", metaRootPos);
        DataUtils.appendMap(buff, "time", time);
        if (version != id) {
            DataUtils.appendMap(buff, "version", version);
        }
        return buff.toString();
    }
    
    byte[] getFooterBytes() {
        StringBuilder buff = new StringBuilder();
        DataUtils.appendMap(buff, "chunk", id);
        DataUtils.appendMap(buff, "block", block);
        if (version != id) {
            DataUtils.appendMap(buff, "version", version);
        }
        byte[] bytes = buff.toString().getBytes(DataUtils.LATIN);
        int checksum = DataUtils.getFletcher32(bytes, bytes.length / 2 * 2);
        DataUtils.appendMap(buff, "fletcher", checksum);
        while (buff.length() < MVStore.CHUNK_FOOTER_LENGTH - 1) {
            buff.append(' ');
        }
        buff.append("\n");
        return buff.toString().getBytes(DataUtils.LATIN);
    }

    @Override
    public String toString() {
        return asString();
    }

}

