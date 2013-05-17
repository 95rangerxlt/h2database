/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore.type;

import java.nio.ByteBuffer;

/**
 * A data type.
 */
public interface DataType {

    /**
     * Compare two keys.
     *
     * @param a the first key
     * @param b the second key
     * @return -1 if the first key is smaller, 1 if larger, and 0 if equal
     * @throws UnsupportedOperationException if the type is not orderable
     */
    int compare(Object a, Object b);

    /**
     * Estimate the used memory in bytes.
     *
     * @param obj the object
     * @return the used memory
     */
    int getMemory(Object obj);

    /**
     * Write the object.
     *
     * @param buff the target buffer
     * @param obj the value
     * @return the byte buffer
     */
    ByteBuffer write(ByteBuffer buff, Object obj);

    /**
     * Read an object.
     *
     * @param buff the source buffer
     * @return the object
     */
    Object read(ByteBuffer buff);

}

