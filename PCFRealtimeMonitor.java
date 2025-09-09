import com.ibm.mq.*;
import com.ibm.mq.constants.*;
import com.ibm.mq.headers.pcf.*;
import java.io.*;
import java.util.*;
import java.text.SimpleDateFormat;

/**
 * PCF Realtime Monitor - Continuously monitors MQ connections using PCF
 * Filters by APPTAG and shows parent-child relationships in real-time
 */
public class PCFRealtimeMonitor {
    
    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss.SSS");
    private static PrintWriter logFile;
    
    static class ConnectionGroup {
        String appTag;
        String queueManager;
        List<ConnectionDetail> connections = new ArrayList<>();
        Date firstSeen;
        Date lastSeen;
        
        boolean isConsistent() {
            // Check if all connections are on same QM
            return connections.stream()
                .map(c -> c.queueManager)
                .distinct()
                .count() == 1;
        }
        
        int getParentCount() {
            // Typically the first connection is the parent
            return 1;
        }
        
        int getSessionCount() {
            // Rest are sessions
            return Math.max(0, connections.size() - 1);
        }
    }
    
    static class ConnectionDetail {
        String connectionId;
        String queueManager;
        String channelName;
        String connectionName;
        String applicationTag;
        String userId;
        int pid;
        int tid;
        Date connectTime;
        boolean isParent;
        
        @Override
        public String toString() {
            return String.format("[%s] %s on %s (%s) PID:%d TID:%d",
                isParent ? "PARENT" : "SESSION",
                connectionId != null ? connectionId.substring(0, 16) : "UNKNOWN",
                queueManager,
                connectionName,
                pid, tid);
        }
    }
    
    public static void main(String[] args) throws Exception {
        String tagFilter = null;
        int refreshInterval = 5; // seconds
        
        // Parse arguments
        if (args.length > 0) {
            tagFilter = args[0];
        }
        if (args.length > 1) {
            refreshInterval = Integer.parseInt(args[1]);
        }
        
        // Initialize logging
        String timestamp = String.valueOf(System.currentTimeMillis());
        logFile = new PrintWriter(new FileWriter("PCF_MONITOR_" + timestamp + ".log"));
        
        System.out.println("========================================");
        System.out.println(" PCF REALTIME CONNECTION MONITOR");
        System.out.println("========================================");
        System.out.println("Tag Filter: " + (tagFilter != null ? tagFilter : "ALL"));
        System.out.println("Refresh Interval: " + refreshInterval + " seconds");
        System.out.println("Log File: PCF_MONITOR_" + timestamp + ".log");
        System.out.println("Press Ctrl+C to stop monitoring");
        System.out.println("========================================\n");
        
        // Monitor loop
        Map<String, ConnectionGroup> previousGroups = new HashMap<>();
        
        while (true) {
            Map<String, ConnectionGroup> currentGroups = new HashMap<>();
            
            // Query each Queue Manager
            for (int qmNum = 1; qmNum <= 3; qmNum++) {
                String qmName = "QM" + qmNum;
                
                try {
                    List<ConnectionDetail> connections = queryQueueManager(qmName, qmNum, tagFilter);
                    
                    // Group by application tag
                    for (ConnectionDetail conn : connections) {
                        String tag = conn.applicationTag != null ? conn.applicationTag : "UNTAGGED";
                        
                        ConnectionGroup group = currentGroups.computeIfAbsent(tag, k -> {
                            ConnectionGroup g = new ConnectionGroup();
                            g.appTag = k;
                            g.queueManager = conn.queueManager;
                            g.firstSeen = new Date();
                            g.lastSeen = new Date();
                            return g;
                        });
                        
                        group.connections.add(conn);
                        group.lastSeen = new Date();
                    }
                } catch (Exception e) {
                    System.err.println("Error querying " + qmName + ": " + e.getMessage());
                }
            }
            
            // Clear screen (Unix/Linux)
            System.out.print("\033[H\033[2J");
            System.out.flush();
            
            // Display header
            System.out.println("========================================");
            System.out.println(" PCF MONITOR - " + dateFormat.format(new Date()));
            System.out.println("========================================\n");
            
            // Display connection groups
            if (currentGroups.isEmpty()) {
                System.out.println("No connections found" + (tagFilter != null ? " with tag: " + tagFilter : ""));
            } else {
                for (ConnectionGroup group : currentGroups.values()) {
                    // Skip if doesn't match filter
                    if (tagFilter != null && !group.appTag.startsWith(tagFilter)) {
                        continue;
                    }
                    
                    System.out.println("Application Tag: " + group.appTag);
                    System.out.println("Queue Manager: " + group.queueManager);
                    System.out.println("Total Connections: " + group.connections.size() + 
                                     " (Parent: " + group.getParentCount() + 
                                     ", Sessions: " + group.getSessionCount() + ")");
                    
                    // Check consistency
                    if (group.isConsistent()) {
                        System.out.println("Status: ✓ All on same QM");
                    } else {
                        System.out.println("Status: ✗ SPLIT across QMs!");
                    }
                    
                    // Show connections
                    for (ConnectionDetail conn : group.connections) {
                        System.out.println("  " + conn);
                    }
                    
                    // Check for changes
                    ConnectionGroup previous = previousGroups.get(group.appTag);
                    if (previous != null) {
                        int diff = group.connections.size() - previous.connections.size();
                        if (diff > 0) {
                            System.out.println("  [+] " + diff + " new connection(s)");
                        } else if (diff < 0) {
                            System.out.println("  [-] " + Math.abs(diff) + " connection(s) closed");
                        }
                    } else {
                        System.out.println("  [NEW] Group first seen");
                    }
                    
                    System.out.println();
                    
                    // Log to file
                    logGroup(group);
                }
            }
            
            // Show summary
            System.out.println("----------------------------------------");
            System.out.println("Summary:");
            System.out.println("  Total Groups: " + currentGroups.size());
            System.out.println("  Total Connections: " + 
                currentGroups.values().stream().mapToInt(g -> g.connections.size()).sum());
            
            // Calculate distribution
            Map<String, Integer> distribution = new HashMap<>();
            for (ConnectionGroup group : currentGroups.values()) {
                for (ConnectionDetail conn : group.connections) {
                    distribution.merge(conn.queueManager, 1, Integer::sum);
                }
            }
            
            System.out.println("\nDistribution:");
            for (int i = 1; i <= 3; i++) {
                String qm = "QM" + i;
                int count = distribution.getOrDefault(qm, 0);
                int total = distribution.values().stream().mapToInt(Integer::intValue).sum();
                double percent = total > 0 ? (count * 100.0 / total) : 0;
                System.out.printf("  %s: %d connections (%.1f%%)\n", qm, count, percent);
            }
            
            System.out.println("\nRefreshing in " + refreshInterval + " seconds...");
            
            // Store for next iteration
            previousGroups = currentGroups;
            
            // Wait for next refresh
            Thread.sleep(refreshInterval * 1000);
        }
    }
    
    private static List<ConnectionDetail> queryQueueManager(String qmName, int qmNum, String tagFilter) throws Exception {
        List<ConnectionDetail> connections = new ArrayList<>();
        PCFMessageAgent agent = null;
        MQQueueManager qmgr = null;
        
        try {
            // Connect to QM
            Hashtable<String, Object> props = new Hashtable<>();
            props.put(CMQC.HOST_NAME_PROPERTY, "10.10.10." + (9 + qmNum));
            props.put(CMQC.PORT_PROPERTY, 1414);
            props.put(CMQC.CHANNEL_PROPERTY, "APP.SVRCONN");
            props.put(CMQC.USER_ID_PROPERTY, "app");
            props.put(CMQC.PASSWORD_PROPERTY, "passw0rd");
            props.put(CMQC.USE_MQCSP_AUTHENTICATION_PROPERTY, true);
            
            qmgr = new MQQueueManager(qmName, props);
            agent = new PCFMessageAgent(qmgr);
            
            // Create PCF request
            PCFMessage request = new PCFMessage(CMQCFC.MQCMD_INQUIRE_CONNECTION);
            request.addParameter(CMQCFC.MQCACH_CHANNEL_NAME, "APP.SVRCONN");
            request.addParameter(CMQCFC.MQIACF_CONNECTION_ATTRS, new int[] { CMQCFC.MQIACF_ALL });
            
            // Send request
            PCFMessage[] responses = agent.send(request);
            
            // Process responses
            for (PCFMessage response : responses) {
                ConnectionDetail conn = new ConnectionDetail();
                conn.queueManager = qmName;
                
                try {
                    // Get connection ID
                    byte[] connIdBytes = response.getByteParameterValue(CMQCFC.MQBACF_CONNECTION_ID);
                    conn.connectionId = bytesToHex(connIdBytes);
                    
                    // Get app tag
                    try {
                        conn.applicationTag = response.getStringParameterValue(CMQCFC.MQCACF_APPL_TAG);
                    } catch (PCFException e) {
                        // No app tag
                    }
                    
                    // Apply filter
                    if (tagFilter != null && (conn.applicationTag == null || 
                        !conn.applicationTag.startsWith(tagFilter))) {
                        continue;
                    }
                    
                    // Get other attributes
                    try { 
                        conn.channelName = response.getStringParameterValue(CMQCFC.MQCACH_CHANNEL_NAME); 
                    } catch (PCFException e) {}
                    
                    try { 
                        conn.connectionName = response.getStringParameterValue(CMQCFC.MQCACH_CONNECTION_NAME); 
                    } catch (PCFException e) {}
                    
                    try { 
                        conn.userId = response.getStringParameterValue(CMQCFC.MQCACH_USER_ID); 
                    } catch (PCFException e) {}
                    
                    try { 
                        conn.pid = response.getIntParameterValue(CMQCFC.MQIACF_PROCESS_ID); 
                    } catch (PCFException e) {}
                    
                    try { 
                        conn.tid = response.getIntParameterValue(CMQCFC.MQIACF_THREAD_ID); 
                    } catch (PCFException e) {}
                    
                    // Try to determine if parent (heuristic: first connection in a group)
                    conn.isParent = false; // Will be determined by grouping logic
                    
                    connections.add(conn);
                    
                } catch (PCFException e) {
                    // Skip connections without required fields
                }
            }
            
        } finally {
            if (agent != null) {
                try { agent.disconnect(); } catch (Exception e) {}
            }
            if (qmgr != null) {
                try { qmgr.disconnect(); } catch (Exception e) {}
            }
        }
        
        // Sort connections by tag and connection ID to identify parents
        connections.sort((a, b) -> {
            int tagCompare = String.valueOf(a.applicationTag).compareTo(String.valueOf(b.applicationTag));
            if (tagCompare != 0) return tagCompare;
            return String.valueOf(a.connectionId).compareTo(String.valueOf(b.connectionId));
        });
        
        // Mark first connection in each tag group as parent
        String lastTag = null;
        for (ConnectionDetail conn : connections) {
            if (!Objects.equals(conn.applicationTag, lastTag)) {
                conn.isParent = true;
                lastTag = conn.applicationTag;
            }
        }
        
        return connections;
    }
    
    private static void logGroup(ConnectionGroup group) {
        logFile.println("[" + dateFormat.format(new Date()) + "] " + group.appTag + 
                       " - " + group.connections.size() + " connections on " + group.queueManager +
                       " (Consistent: " + group.isConsistent() + ")");
        logFile.flush();
    }
    
    private static String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02X", b));
        }
        return result.toString();
    }
}