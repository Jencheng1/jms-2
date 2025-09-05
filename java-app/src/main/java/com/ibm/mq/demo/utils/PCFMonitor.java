package com.ibm.mq.demo.utils;

import com.ibm.mq.*;
import com.ibm.mq.constants.CMQC;
import com.ibm.mq.constants.CMQCFC;
import com.ibm.mq.headers.pcf.*;
import java.io.IOException;
import java.util.*;

/**
 * PCF (Programmable Command Format) Monitor for IBM MQ
 * Provides real-time connection and session monitoring using PCF commands
 * to get undisputable evidence of parent-child relationships
 */
public class PCFMonitor {
    
    private final String queueManagerName;
    private final String hostname;
    private final int port;
    private final String channel;
    private final String userId;
    private final String password;
    private PCFMessageAgent agent;
    
    /**
     * Connection information retrieved via PCF
     */
    public static class ConnectionDetails {
        public String connectionId;
        public String channelName;
        public String connectionName;
        public String applicationTag;
        public String applicationName;
        public String userId;
        public String queueManager;
        public int pid;
        public int tid;
        public String applType;
        public Date connectTime;
        public Map<String, Object> additionalProperties = new HashMap<>();
        
        @Override
        public String toString() {
            return String.format("Connection[id=%s, QM=%s, channel=%s, appTag=%s, appName=%s, conName=%s]",
                connectionId, queueManager, channelName, applicationTag, applicationName, connectionName);
        }
    }
    
    /**
     * Create PCF Monitor for a specific Queue Manager
     */
    public PCFMonitor(String queueManagerName, String hostname, int port, 
                      String channel, String userId, String password) {
        this.queueManagerName = queueManagerName;
        this.hostname = hostname;
        this.port = port;
        this.channel = channel;
        this.userId = userId;
        this.password = password;
    }
    
    /**
     * Connect to the Queue Manager for PCF operations
     */
    public void connect() throws MQException, IOException {
        // Set up connection properties
        Hashtable<String, Object> props = new Hashtable<>();
        props.put(CMQC.HOST_NAME_PROPERTY, hostname);
        props.put(CMQC.PORT_PROPERTY, port);
        props.put(CMQC.CHANNEL_PROPERTY, channel);
        props.put(CMQC.USER_ID_PROPERTY, userId);
        props.put(CMQC.PASSWORD_PROPERTY, password);
        props.put(CMQC.USE_MQCSP_AUTHENTICATION_PROPERTY, true);
        
        // Create PCF Message Agent
        MQQueueManager qmgr = new MQQueueManager(queueManagerName, props);
        agent = new PCFMessageAgent(qmgr);
        
        System.out.println("[PCFMonitor] Connected to " + queueManagerName + " for monitoring");
    }
    
    /**
     * Disconnect from the Queue Manager
     */
    public void disconnect() {
        if (agent != null) {
            try {
                agent.disconnect();
                System.out.println("[PCFMonitor] Disconnected from " + queueManagerName);
            } catch (Exception e) {
                System.err.println("[PCFMonitor] Error disconnecting: " + e.getMessage());
            }
        }
    }
    
    /**
     * Get all active connections matching the specified channel
     */
    public List<ConnectionDetails> getActiveConnections(String channelFilter) throws PCFException, IOException {
        List<ConnectionDetails> connections = new ArrayList<>();
        
        // Create PCF request to inquire connections
        PCFMessage request = new PCFMessage(CMQCFC.MQCMD_INQUIRE_CONNECTION);
        
        // Add filter for channel if specified
        if (channelFilter != null && !channelFilter.isEmpty()) {
            request.addParameter(CMQCFC.MQCACH_CHANNEL_NAME, channelFilter);
        }
        
        // Request all connection attributes
        request.addParameter(CMQCFC.MQIACF_CONNECTION_ATTRS, new int[] {
            CMQCFC.MQIACF_ALL
        });
        
        // Send request and get responses
        PCFMessage[] responses = agent.send(request);
        
        // Process each response
        for (PCFMessage response : responses) {
            ConnectionDetails conn = new ConnectionDetails();
            
            // Extract connection ID (MQBACF_CONN_ID)
            try {
                byte[] connIdBytes = response.getByteParameterValue(CMQCFC.MQBACF_CONNECTION_ID);
                conn.connectionId = bytesToHex(connIdBytes);
            } catch (PCFException e) {
                // Connection ID not available
            }
            
            // Extract channel name
            try {
                conn.channelName = response.getStringParameterValue(CMQCFC.MQCACH_CHANNEL_NAME);
            } catch (PCFException e) {
                // Channel name not available
            }
            
            // Extract connection name (IP address and port)
            try {
                conn.connectionName = response.getStringParameterValue(CMQCFC.MQCACH_CONNECTION_NAME);
            } catch (PCFException e) {
                // Connection name not available
            }
            
            // Extract application tag (MQCACF_APPL_TAG)
            try {
                conn.applicationTag = response.getStringParameterValue(CMQCFC.MQCACF_APPL_TAG);
            } catch (PCFException e) {
                // Application tag not available
            }
            
            // Extract application name
            try {
                conn.applicationName = response.getStringParameterValue(CMQCFC.MQCACF_APPL_NAME);
            } catch (PCFException e) {
                // Application name not available
            }
            
            // Extract user ID
            try {
                conn.userId = response.getStringParameterValue(CMQCFC.MQCACH_USER_ID);
            } catch (PCFException e) {
                // User ID not available
            }
            
            // Extract application type
            try {
                int applTypeInt = response.getIntParameterValue(CMQCFC.MQIACF_APPL_TYPE);
                conn.applType = getApplicationTypeString(applTypeInt);
            } catch (PCFException e) {
                // Application type not available
            }
            
            // Extract process ID
            try {
                conn.pid = response.getIntParameterValue(CMQCFC.MQIACF_PROCESS_ID);
            } catch (PCFException e) {
                // PID not available
            }
            
            // Extract thread ID
            try {
                conn.tid = response.getIntParameterValue(CMQCFC.MQIACF_THREAD_ID);
            } catch (PCFException e) {
                // TID not available
            }
            
            // Set queue manager name
            conn.queueManager = queueManagerName;
            
            connections.add(conn);
        }
        
        return connections;
    }
    
    /**
     * Get connections grouped by application tag to show parent-child relationships
     */
    public Map<String, List<ConnectionDetails>> getConnectionsByAppTag(String channelFilter) 
            throws PCFException, IOException {
        
        Map<String, List<ConnectionDetails>> groupedConnections = new HashMap<>();
        List<ConnectionDetails> allConnections = getActiveConnections(channelFilter);
        
        for (ConnectionDetails conn : allConnections) {
            String appTag = conn.applicationTag;
            if (appTag == null || appTag.trim().isEmpty()) {
                appTag = "UNTAGGED";
            }
            
            groupedConnections.computeIfAbsent(appTag, k -> new ArrayList<>()).add(conn);
        }
        
        return groupedConnections;
    }
    
    /**
     * Print parent-child correlation report
     */
    public void printCorrelationReport(String channelFilter) throws PCFException, IOException {
        System.out.println("\n========================================");
        System.out.println("PCF PARENT-CHILD CORRELATION REPORT");
        System.out.println("Queue Manager: " + queueManagerName);
        System.out.println("========================================\n");
        
        Map<String, List<ConnectionDetails>> grouped = getConnectionsByAppTag(channelFilter);
        
        // Group by connection name prefix to identify parent-child relationships
        Map<String, List<ConnectionDetails>> parentGroups = new HashMap<>();
        
        for (Map.Entry<String, List<ConnectionDetails>> entry : grouped.entrySet()) {
            String appTag = entry.getKey();
            List<ConnectionDetails> conns = entry.getValue();
            
            System.out.println("Application Tag: " + appTag);
            System.out.println("  Connections: " + conns.size());
            
            for (ConnectionDetails conn : conns) {
                System.out.println("    " + conn);
                
                // Group by connection name prefix
                if (conn.connectionName != null) {
                    String prefix = conn.connectionName.split("\\(")[0];
                    parentGroups.computeIfAbsent(prefix, k -> new ArrayList<>()).add(conn);
                }
            }
        }
        
        // Analyze parent groups
        System.out.println("\n--- Parent-Child Groups by Connection ---");
        for (Map.Entry<String, List<ConnectionDetails>> entry : parentGroups.entrySet()) {
            String parentConn = entry.getKey();
            List<ConnectionDetails> children = entry.getValue();
            
            if (children.size() > 1) {
                System.out.println("\nParent Connection: " + parentConn);
                
                // Check if all children are on same QM
                boolean allSameQM = children.stream()
                    .map(c -> c.queueManager)
                    .distinct()
                    .count() == 1;
                
                for (ConnectionDetails child : children) {
                    System.out.println("  Child: " + child.connectionId + 
                                     " (QM: " + child.queueManager + 
                                     ", Tag: " + child.applicationTag + ")");
                }
                
                if (allSameQM) {
                    System.out.println("  ✓ All children on same QM!");
                } else {
                    System.out.println("  ✗ Children on different QMs!");
                }
            }
        }
        
        System.out.println("\n========================================");
    }
    
    /**
     * Get correlation evidence for a specific application tag
     */
    public String getCorrelationEvidence(String applicationTag) throws PCFException, IOException {
        StringBuilder evidence = new StringBuilder();
        
        evidence.append("PCF CORRELATION EVIDENCE FOR: ").append(applicationTag).append("\n");
        evidence.append("Queue Manager: ").append(queueManagerName).append("\n");
        evidence.append("Timestamp: ").append(new Date()).append("\n\n");
        
        List<ConnectionDetails> connections = getActiveConnections("APP.SVRCONN");
        
        // Filter by application tag
        List<ConnectionDetails> matchingConns = new ArrayList<>();
        for (ConnectionDetails conn : connections) {
            if (applicationTag.equals(conn.applicationTag) || 
                (conn.applicationTag != null && conn.applicationTag.startsWith(applicationTag))) {
                matchingConns.add(conn);
            }
        }
        
        evidence.append("Found ").append(matchingConns.size()).append(" connections with tag: ")
                .append(applicationTag).append("\n\n");
        
        // Group by connection name to identify parent-child
        Map<String, List<ConnectionDetails>> groups = new HashMap<>();
        for (ConnectionDetails conn : matchingConns) {
            String key = conn.connectionName != null ? conn.connectionName.split("\\(")[0] : "UNKNOWN";
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(conn);
        }
        
        for (Map.Entry<String, List<ConnectionDetails>> entry : groups.entrySet()) {
            evidence.append("Connection Group: ").append(entry.getKey()).append("\n");
            for (ConnectionDetails conn : entry.getValue()) {
                evidence.append("  - ID: ").append(conn.connectionId)
                        .append(", Channel: ").append(conn.channelName)
                        .append(", QM: ").append(conn.queueManager)
                        .append("\n");
            }
            
            // Check if all in same QM
            boolean samQM = entry.getValue().stream()
                .map(c -> c.queueManager)
                .distinct()
                .count() == 1;
            
            if (samQM) {
                evidence.append("  ✓ All connections in this group on SAME Queue Manager\n");
            } else {
                evidence.append("  ✗ Connections in different Queue Managers\n");
            }
            evidence.append("\n");
        }
        
        return evidence.toString();
    }
    
    /**
     * Convert byte array to hex string
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02X", b));
        }
        return result.toString();
    }
    
    /**
     * Convert application type integer to string
     */
    private String getApplicationTypeString(int applType) {
        switch (applType) {
            case CMQC.MQAT_JAVA:
                return "JAVA";
            case CMQC.MQAT_JMS:
                return "JMS";
            case CMQC.MQAT_USER:
                return "USER";
            case CMQC.MQAT_SYSTEM:
                return "SYSTEM";
            default:
                return "TYPE_" + applType;
        }
    }
}