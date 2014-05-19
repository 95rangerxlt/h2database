/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License, Version
 * 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html). Initial Developer: H2 Group
 */
package org.h2.util;

import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Utility to detect AB-BA deadlocks.
 */
public class AbbaLockingDetector implements Runnable {

    private int tickInterval_ms = 2;
    private volatile boolean stop;

    private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    private Thread thread;

    /**
     * Map of (object A) -> ( map of (object locked before object A) ->
     * (stacktrace where locked) )
     */
    private final Map<String, Map<String, String>> LOCK_ORDERING = new WeakHashMap<String, Map<String, String>>();
    private final Set<String> KNOWN_DEADLOCKS = new HashSet<String>();

    /**
     * Start collecting locking data.
     * 
     * @return this
     */
    public AbbaLockingDetector startCollecting() {
        thread = new Thread(this, "AbbaLockingDetector");
        thread.setDaemon(true);
        thread.start();
        return this;
    }

    public synchronized void reset() {
        LOCK_ORDERING.clear();
        KNOWN_DEADLOCKS.clear();
    }
    
    /**
     * Stop collecting.
     * 
     * @return this
     */
    public AbbaLockingDetector stopCollecting() {
        stop = true;
        if (thread != null) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                // ignore
            }
            thread = null;
        }
        return this;
    }

    @Override
    public void run() {
        while (!stop) {
            try {
                tick();
            } catch (Throwable t) {
                break;
            }
        }
    }

    private void tick() {
        if (tickInterval_ms > 0) {
            try {
                Thread.sleep(tickInterval_ms);
            } catch (InterruptedException ex) {
                // ignore
            }
        }

        ThreadInfo[] list = threadMXBean.dumpAllThreads(true/* lockedMonitors */, false/* lockedSynchronizers */);
        processThreadList(list);
    }

    private void processThreadList(ThreadInfo[] threadInfoList) {
        final List<String> lockOrder = new ArrayList<String>();
        for (ThreadInfo threadInfo : threadInfoList) {
            lockOrder.clear();
            generateOrdering(lockOrder, threadInfo);
            if (lockOrder.size() > 1) {
                markHigher(lockOrder, threadInfo);
            }
        }
    }

    /**
     * We cannot simply call getLockedMonitors because it is not guaranteed to
     * return the locks in the correct order.
     */
    private static void generateOrdering(final List<String> lockOrder, ThreadInfo info) {
        final MonitorInfo[] lockedMonitors = info.getLockedMonitors();
        Arrays.sort(lockedMonitors, new Comparator<MonitorInfo>() {
	        @Override
	        public int compare(MonitorInfo a, MonitorInfo b) {
	        	return b.getLockedStackDepth() - a.getLockedStackDepth();
	        }
        });
        for (MonitorInfo mi : lockedMonitors) {
            String lockName = getObjectName(mi);
            if (lockName.equals("sun.misc.Launcher$AppClassLoader")) {
                // ignore, it shows up everywhere
                continue;
            }
            // Ignore locks which are locked multiple times in
            // succession - Java locks are recursive.
            if (!lockOrder.contains(lockName)) {
                lockOrder.add(lockName);
            }
        }
    }

    private synchronized void markHigher(List<String> lockOrder, ThreadInfo threadInfo) {
        String topLock = lockOrder.get(lockOrder.size() - 1);
        Map<String, String> map = LOCK_ORDERING.get(topLock);
        if (map == null) {
            map = new WeakHashMap<String, String>();
            LOCK_ORDERING.put(topLock, map);
        }
        String oldException = null;
        for (int i = 0; i < lockOrder.size() - 1; i++) {
            String olderLock = lockOrder.get(i);
            Map<String, String> oldMap = LOCK_ORDERING.get(olderLock);
            boolean foundDeadLock = false;
            if (oldMap != null) {
                String e = oldMap.get(topLock);
                if (e != null) {
                    foundDeadLock = true;
                    String deadlockType = topLock + " " + olderLock;
                    if (!KNOWN_DEADLOCKS.contains(deadlockType)) {
                        System.out.println(topLock + " synchronized after \n " + olderLock
                                + ", but in the past before\n" + "AFTER\n" + getStackTraceForThread(threadInfo)
                                + "BEFORE\n" + e);
                        KNOWN_DEADLOCKS.add(deadlockType);
                    }
                }
            }
            if (!foundDeadLock && !map.containsKey(olderLock)) {
                if (oldException == null) {
                    oldException = getStackTraceForThread(threadInfo);
                }
                map.put(olderLock, oldException);
            }
        }
    }

    /**
     * Dump data in the same format as {@link ThreadInfo#toString()}, but with
     * some modifications (no stack frame limit, and removal of uninteresting
     * stack frames)
     */
    private static String getStackTraceForThread(ThreadInfo info) {
        StringBuilder sb = new StringBuilder("\"" + info.getThreadName() + "\"" + " Id=" + info.getThreadId() + " "
                + info.getThreadState());
        if (info.getLockName() != null) {
            sb.append(" on " + info.getLockName());
        }
        if (info.getLockOwnerName() != null) {
            sb.append(" owned by \"" + info.getLockOwnerName() + "\" Id=" + info.getLockOwnerId());
        }
        if (info.isSuspended()) {
            sb.append(" (suspended)");
        }
        if (info.isInNative()) {
            sb.append(" (in native)");
        }
        sb.append('\n');
        final StackTraceElement[] stackTrace = info.getStackTrace();
        final MonitorInfo[] lockedMonitors = info.getLockedMonitors();
        boolean startDumping = false;
        for (int i = 0; i < stackTrace.length; i++) {
            StackTraceElement ste = stackTrace[i];
            if (startDumping) {
                dumpStackTraceElement(info, sb, i, ste);
            }

            for (MonitorInfo mi : lockedMonitors) {
                if (mi.getLockedStackDepth() == i) {
                    // Only start dumping the stack from the first time we lock
                    // something.
                    // Removes a lot of unnecessary noise from the output.
                    if (!startDumping) {
                        dumpStackTraceElement(info, sb, i, ste);
                        startDumping = true;
                    }
                    sb.append("\t-  locked " + mi);
                    sb.append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static void dumpStackTraceElement(ThreadInfo info, StringBuilder sb, int i, StackTraceElement ste) {
        sb.append("\tat " + ste.toString());
        sb.append('\n');
        if (i == 0 && info.getLockInfo() != null) {
            Thread.State ts = info.getThreadState();
            switch (ts) {
            case BLOCKED:
                sb.append("\t-  blocked on " + info.getLockInfo());
                sb.append('\n');
                break;
            case WAITING:
                sb.append("\t-  waiting on " + info.getLockInfo());
                sb.append('\n');
                break;
            case TIMED_WAITING:
                sb.append("\t-  waiting on " + info.getLockInfo());
                sb.append('\n');
                break;
            default:
            }
        }
    }

    private static String getObjectName(MonitorInfo info) {
        return info.getClassName() + "@" + Integer.toHexString(info.getIdentityHashCode());
    }

}
