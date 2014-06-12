/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.unit;

import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Set;
import javax.management.Attribute;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import org.h2.test.TestBase;
import org.h2.util.New;

/**
 * Tests the JMX feature.
 */
public class TestJmx extends TestBase {

    /**
     * Run just this test.
     *
     * @param a ignored
     */
    public static void main(String... a) throws Exception {
        TestBase.createCaller().init().test();
    }

    @Override
    public void test() throws Exception {
        HashMap<String, MBeanAttributeInfo> attrMap;
        HashMap<String, MBeanOperationInfo> opMap;
        String result;
        MBeanInfo info;
        ObjectName name;
        Connection conn;
        Statement stat;

        MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();

        conn = getConnection("mem:jmx;jmx=true");
        stat = conn.createStatement();

        name = new ObjectName("org.h2:name=JMX,path=mem_jmx");
        info = mbeanServer.getMBeanInfo(name);
        assertEquals("0", mbeanServer.
                getAttribute(name, "CacheSizeMax").toString());
        // cache size is ignored for in-memory databases
        mbeanServer.setAttribute(name, new Attribute("CacheSizeMax", 1));
        assertEquals("0", mbeanServer.
                getAttribute(name, "CacheSizeMax").toString());
        assertEquals("0", mbeanServer.
                getAttribute(name, "CacheSize").toString());
        assertEquals("false", mbeanServer.
                getAttribute(name, "Exclusive").toString());
        assertEquals("0", mbeanServer.
                getAttribute(name, "FileSize").toString());
        assertEquals("0", mbeanServer.
                getAttribute(name, "FileReadCount").toString());
        assertEquals("0", mbeanServer.
                getAttribute(name, "FileWriteCount").toString());
        assertEquals("0", mbeanServer.
                getAttribute(name, "FileWriteCountTotal").toString());
        if (config.mvStore) {
            assertEquals("1", mbeanServer.
                    getAttribute(name, "LogMode").toString());
            mbeanServer.setAttribute(name, new Attribute("LogMode", 2));
            assertEquals("2", mbeanServer.
                    getAttribute(name, "LogMode").toString());
        }
        assertEquals("REGULAR", mbeanServer.
                getAttribute(name, "Mode").toString());
        assertEquals("false", mbeanServer.
                getAttribute(name, "MultiThreaded").toString());
        if (config.mvStore) {
            assertEquals("true", mbeanServer.
                    getAttribute(name, "Mvcc").toString());
        } else {
            assertEquals("false", mbeanServer.
                    getAttribute(name, "Mvcc").toString());
        }
        assertEquals("false", mbeanServer.
                getAttribute(name, "ReadOnly").toString());
        assertEquals("1", mbeanServer.
                getAttribute(name, "TraceLevel").toString());
        mbeanServer.setAttribute(name, new Attribute("TraceLevel", 0));
        assertEquals("0", mbeanServer.
                getAttribute(name, "TraceLevel").toString());
        assertTrue(mbeanServer.
                getAttribute(name, "Version").toString().startsWith("1."));
        assertEquals(14, info.getAttributes().length);
        result = mbeanServer.invoke(name, "listSettings", null, null).toString();
        assertTrue(result.contains("ANALYZE_AUTO"));

        conn.setAutoCommit(false);
        stat.execute("create table test(id int)");
        stat.execute("insert into test values(1)");

        result = mbeanServer.invoke(name, "listSessions", null, null).toString();
        assertTrue(result.contains("session id"));
        if (config.mvcc) {
            assertTrue(result.contains("read lock"));
        } else {
            assertTrue(result.contains("write lock"));
        }

        assertEquals(2, info.getOperations().length);
        assertTrue(info.getDescription().contains("database"));
        attrMap = New.hashMap();
        for (MBeanAttributeInfo a : info.getAttributes()) {
            attrMap.put(a.getName(), a);
        }
        assertTrue(attrMap.get("CacheSize").getDescription().contains("KB"));
        opMap = New.hashMap();
        for (MBeanOperationInfo o : info.getOperations()) {
            opMap.put(o.getName(), o);
        }
        assertTrue(opMap.get("listSessions").getDescription().contains("lock"));
        assertEquals(MBeanOperationInfo.INFO, opMap.get("listSessions").getImpact());

        conn.close();

        conn = getConnection("jmx;jmx=true");
        conn.close();
        conn = getConnection("jmx;jmx=true");

        name = new ObjectName("org.h2:name=JMX,*");
        @SuppressWarnings("rawtypes")
        Set set = mbeanServer.queryNames(name, null);
        name = (ObjectName) set.iterator().next();

        assertEquals("16384", mbeanServer.
                getAttribute(name, "CacheSizeMax").toString());
        mbeanServer.setAttribute(name, new Attribute("CacheSizeMax", 1));
        if (config.mvStore) {
            assertEquals("1024", mbeanServer.
                    getAttribute(name, "CacheSizeMax").toString());
            assertEquals("0", mbeanServer.
                    getAttribute(name, "CacheSize").toString());
            assertTrue(0 < (Long) mbeanServer.
                    getAttribute(name, "FileReadCount"));
            assertTrue(0 < (Long) mbeanServer.
                    getAttribute(name, "FileWriteCount"));
            assertEquals("0", mbeanServer.
                    getAttribute(name, "FileWriteCountTotal").toString());
        } else {
            assertEquals("1", mbeanServer.
                    getAttribute(name, "CacheSizeMax").toString());
            assertTrue(0 < (Integer) mbeanServer.
                    getAttribute(name, "CacheSize"));
            assertTrue(0 < (Long) mbeanServer.
                    getAttribute(name, "FileSize"));
            assertTrue(0 < (Long) mbeanServer.
                    getAttribute(name, "FileReadCount"));
            assertTrue(0 < (Long) mbeanServer.
                    getAttribute(name, "FileWriteCount"));
            assertTrue(0 < (Long) mbeanServer.
                    getAttribute(name, "FileWriteCountTotal"));
        }
        mbeanServer.setAttribute(name, new Attribute("LogMode", 0));
        assertEquals("0", mbeanServer.
                getAttribute(name, "LogMode").toString());

        conn.close();

    }

}
