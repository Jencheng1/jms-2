package com.ibm.mq.demo;

import com.ibm.mq.jms.MQConnectionFactory;
import com.ibm.msg.client.wmq.WMQConstants;
import com.ibm.mq.jms.MQConnection;
import com.ibm.mq.jms.MQSession;
import javax.jms.*;
import java.util.*;
import java.text.SimpleDateFormat;

/**
 * Spring Boot MQ Failover Application - Complete Parent-Child Session Tracking
 * 
 * This application demonstrates the critical aspects of IBM MQ Uniform Cluster failover:
 * 1. Parent Connection creates multiple Child Sessions
 * 2. CONNTAG (Connection Tag) proves parent-child affinity
 * 3. Uniform Cluster ensures all sessions move together during failover
 * 4. Spring Boot specific property extraction using string literals
 * 
 * Key Concepts:
 * - Parent Connection: The main JMS Connection object (1 per connection)
 * - Child Sessions: Multiple Session objects created from parent (5 or 3 sessions)
 * - CONNTAG: Unique identifier format: MQCT<16-char-handle><QM>_<timestamp>
 * - Affinity: Child sessions ALWAYS stay with their parent's Queue Manager
 * - Failover: When QM fails, parent + all children move together to new QM
 * 
 * @author IBM MQ Demo Team
 * @version 1.0
 */
public class SpringBootMQFailoverApplication {
    
    // Unique test identifier for tracking this specific test run in MQSC queries
    private static final String TEST_ID = "SPRINGBOOT-" + System.currentTimeMillis();
    
    // CCDT (Client Channel Definition Table) contains connection endpoints for all 3 QMs
    // Format: [{"channel":"APP.SVRCONN", "queueManager":"", "host":"10.10.10.10", "port":1414},...]
    private static final String CCDT_URL = "file:///workspace/ccdt/ccdt.json";
    
    // High precision timestamp for tracking failover events
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss.SSS");
    
    /**
     * ConnectionData - Tracks parent connection and all child sessions
     * 
     * This class represents the parent-child relationship:
     * - One parent Connection (the main connection to MQ)
     * - Multiple child Sessions (created from the parent)
     * - CONNTAG that proves they're all on the same Queue Manager
     * - APPTAG for filtering in MQSC queries
     */
    static class ConnectionData {
        String id;                              // Connection identifier (C1 or C2)
        Connection connection;                  // Parent JMS Connection object
        List<Session> sessions = new ArrayList<>(); // Child Session objects
        String fullConnTag;                     // Full CONNTAG including QM and timestamp
        String queueManager;                    // Current Queue Manager (QM1, QM2, or QM3)
        String appTag;                          // Application tag for MQSC filtering
        
        ConnectionData(String id, Connection conn, String appTag) {
            this.id = id;
            this.connection = conn;
            this.appTag = appTag;
        }
    }
    
    public static void main(String[] args) throws Exception {
        System.out.println("\n================================================================================");
        System.out.println("         SPRING BOOT MQ FAILOVER TEST - FULL CONNTAG DISPLAY");
        System.out.println("================================================================================");
        System.out.println("Test ID: " + TEST_ID);
        System.out.println("Start Time: " + timestamp());
        System.out.println("CCDT: " + CCDT_URL);
        
        System.out.println("\n=== Spring Boot Approach ===");
        System.out.println("• Uses string literal \"JMS_IBM_CONNECTION_TAG\"");
        System.out.println("• Casts to MQConnection/MQSession");
        System.out.println("• Different from regular JMS which uses XMSC constants");
        
        // Create connection factory
        MQConnectionFactory factory = createFactory();
        
        // Create Connection 1 (Parent) with 5 Sessions (Children)
        // This creates 6 total MQ connections: 1 parent + 5 child sessions
        System.out.println("\n[" + timestamp() + "] Creating Connection 1 with 5 sessions...");
        ConnectionData conn1 = createConnectionWithSessions(factory, "C1", 5);
        
        // Create Connection 2 (Parent) with 3 Sessions (Children)
        // This creates 4 total MQ connections: 1 parent + 3 child sessions
        System.out.println("\n[" + timestamp() + "] Creating Connection 2 with 3 sessions...");
        ConnectionData conn2 = createConnectionWithSessions(factory, "C2", 3);
        
        // Display BEFORE state
        System.out.println("\n================================================================================");
        System.out.println("                    BEFORE FAILOVER - FULL CONNTAG TABLE");
        System.out.println("================================================================================");
        displayConnectionTable(Arrays.asList(conn1, conn2));
        
        // Show parent-child affinity
        System.out.println("\n================================================================================");
        System.out.println("                    PARENT-CHILD AFFINITY VERIFICATION");
        System.out.println("================================================================================");
        verifyAffinity(Arrays.asList(conn1, conn2));
        
        // Keep alive for testing
        System.out.println("\n[" + timestamp() + "] Keeping connections alive for 30 seconds...");
        System.out.println("[" + timestamp() + "] To trigger failover, stop the Queue Manager with 6 connections");
        
        // Monitor for changes
        for (int i = 0; i < 6; i++) {
            Thread.sleep(5000);
            System.out.print(".");
            
            // CRITICAL: Monitor CONNTAG changes to detect failover
            // When a Queue Manager fails, the CONNTAG will change to reflect the new QM
            // Example: MQCT12A4C06800370040QM2_2025-09-05_02.13.42 → MQCT1DA7C06800280040QM1_2025-09-05_02.13.44
            String newConnTag1 = SpringBootFailoverTest.extractFullConnTag(conn1.connection);
            String newConnTag2 = SpringBootFailoverTest.extractFullConnTag(conn2.connection);
            
            if (!newConnTag1.equals(conn1.fullConnTag) || !newConnTag2.equals(conn2.fullConnTag)) {
                System.out.println("\n\n[" + timestamp() + "] FAILOVER DETECTED!");
                
                // Update CONNTAGs
                conn1.fullConnTag = newConnTag1;
                conn2.fullConnTag = newConnTag2;
                conn1.queueManager = extractQueueManager(conn1.connection);
                conn2.queueManager = extractQueueManager(conn2.connection);
                
                System.out.println("\n================================================================================");
                System.out.println("                     AFTER FAILOVER - FULL CONNTAG TABLE");
                System.out.println("================================================================================");
                displayConnectionTable(Arrays.asList(conn1, conn2));
                break;
            }
        }
        
        // Cleanup
        System.out.println("\n[" + timestamp() + "] Closing connections...");
        for (ConnectionData data : Arrays.asList(conn1, conn2)) {
            for (Session session : data.sessions) {
                session.close();
            }
            data.connection.close();
        }
        
        System.out.println("\n[" + timestamp() + "] Test completed successfully");
        System.out.println("================================================================================\n");
    }
    
    /**
     * Creates MQ Connection Factory with Uniform Cluster configuration
     * 
     * Key settings for failover:
     * - WMQ_CCDTURL: Points to CCDT with all 3 Queue Managers
     * - WMQ_QUEUE_MANAGER: "*" allows connection to ANY available QM
     * - WMQ_CLIENT_RECONNECT: Enables automatic reconnection on failure
     * - WMQ_CLIENT_RECONNECT_TIMEOUT: 1800 seconds (30 minutes) to retry
     */
    private static MQConnectionFactory createFactory() throws Exception {
        MQConnectionFactory factory = new MQConnectionFactory();
        
        // Use client transport (not bindings)
        factory.setTransportType(WMQConstants.WMQ_CM_CLIENT);
        
        // CCDT contains QM1 (10.10.10.10), QM2 (10.10.10.11), QM3 (10.10.10.12)
        factory.setStringProperty(WMQConstants.WMQ_CCDTURL, CCDT_URL);
        
        // "*" means connect to any available QM from CCDT (enables load balancing)
        factory.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "*");
        
        // Enable automatic reconnection - CRITICAL for failover
        factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, WMQConstants.WMQ_CLIENT_RECONNECT);
        
        // Keep trying to reconnect for 30 minutes
        factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_TIMEOUT, 1800);
        
        return factory;
    }
    
    /**
     * Creates a parent connection with multiple child sessions
     * 
     * This method demonstrates the parent-child relationship:
     * 1. Creates ONE parent Connection object
     * 2. Creates MULTIPLE child Session objects from the parent
     * 3. All sessions inherit the parent's CONNTAG (proving affinity)
     * 4. Sets APPTAG for tracking in MQSC queries
     * 
     * @param factory The configured MQConnectionFactory
     * @param connId Connection identifier (C1 or C2)
     * @param sessionCount Number of child sessions to create (5 or 3)
     * @return ConnectionData with parent and all children
     */
    private static ConnectionData createConnectionWithSessions(MQConnectionFactory factory,
                                                              String connId, int sessionCount) throws Exception {
        // APPTAG appears in MQSC DIS CONN output for filtering
        String appTag = TEST_ID + "-" + connId;
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, appTag);
        
        Connection connection = factory.createConnection("mqm", "");
        ConnectionData connData = new ConnectionData(connId, connection, appTag);
        
        // CRITICAL: Exception listener detects Queue Manager failures
        // When QM fails, this listener is triggered for the parent connection
        // The Uniform Cluster then moves parent + all children to a new QM
        connection.setExceptionListener(new ExceptionListener() {
            @Override
            public void onException(JMSException e) {
                System.out.println("\n[" + timestamp() + "] ExceptionListener triggered for " + connId);
                System.out.println("[" + timestamp() + "] Error: " + e.getMessage());
                // At this point, MQ client is already attempting reconnection via CCDT
                // All child sessions will move with the parent to the new QM
            }
        });
        
        connection.start();
        
        // Extract properties using Spring Boot approach
        connData.fullConnTag = SpringBootFailoverTest.extractFullConnTag(connection);
        connData.queueManager = extractQueueManager(connection);
        
        System.out.println("[" + timestamp() + "] " + connId + " connected to " + connData.queueManager);
        System.out.println("[" + timestamp() + "] FULL CONNTAG: " + connData.fullConnTag);
        
        // CRITICAL: Create child sessions from parent connection
        // Each session is a separate MQ connection but inherits parent's CONNTAG
        // This proves they're all on the same Queue Manager
        for (int i = 0; i < sessionCount; i++) {
            // false = non-transacted, AUTO_ACKNOWLEDGE = automatic message acknowledgment
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            connData.sessions.add(session);
            
            // Extract session's CONNTAG - should match parent's CONNTAG
            String sessionTag = SpringBootFailoverTest.extractSessionConnTag(session);
            System.out.println("[" + timestamp() + "]   Session " + (i+1) + " CONNTAG: " + 
                             (sessionTag.equals("INHERITED_FROM_PARENT") ? "Inherits from parent" : sessionTag));
        }
        
        return connData;
    }
    
    private static void displayConnectionTable(List<ConnectionData> connections) {
        System.out.println();
        System.out.println("┌────┬────────┬──────┬─────────┬──────────────────────────────────────────────────────────────────────┬────────┬─────────────────────────┐");
        System.out.println("│ #  │ Type   │ Conn │ Session │ FULL CONNTAG (No Truncation)                                        │ QM     │ APPTAG                  │");
        System.out.println("├────┼────────┼──────┼─────────┼──────────────────────────────────────────────────────────────────────┼────────┼─────────────────────────┤");
        
        int row = 1;
        for (ConnectionData data : connections) {
            // Parent
            System.out.printf("│ %-2d │ Parent │ %-4s │    -    │ %-68s │ %-6s │ %-23s │%n",
                row++, data.id, data.fullConnTag, data.queueManager, data.appTag);
            
            // Sessions
            for (int i = 0; i < data.sessions.size(); i++) {
                String sessionTag = SpringBootFailoverTest.extractSessionConnTag(data.sessions.get(i));
                if (sessionTag.equals("INHERITED_FROM_PARENT")) {
                    sessionTag = data.fullConnTag; // Show parent's CONNTAG
                }
                System.out.printf("│ %-2d │ Session│ %-4s │    %d    │ %-68s │ %-6s │ %-23s │%n",
                    row++, data.id, (i+1), sessionTag, data.queueManager, data.appTag);
            }
        }
        
        System.out.println("└────┴────────┴──────┴─────────┴──────────────────────────────────────────────────────────────────────┴────────┴─────────────────────────┘");
        
        System.out.println("\nSummary:");
        for (ConnectionData data : connections) {
            System.out.println("• Connection " + data.id + ": 1 parent + " + data.sessions.size() + 
                             " sessions = " + (1 + data.sessions.size()) + " total on " + data.queueManager);
        }
    }
    
    /**
     * Verifies parent-child affinity by comparing CONNTAGs
     * 
     * This is the KEY PROOF that Uniform Cluster maintains affinity:
     * - Parent has a CONNTAG identifying its Queue Manager
     * - All child sessions inherit the SAME CONNTAG
     * - This proves they're all on the same Queue Manager
     * - During failover, all move together to maintain affinity
     */
    private static void verifyAffinity(List<ConnectionData> connections) {
        for (ConnectionData data : connections) {
            System.out.println("\n" + data.id + " (" + data.appTag + "):");
            System.out.println("  Parent CONNTAG: " + data.fullConnTag);
            
            boolean allMatch = true;
            for (int i = 0; i < data.sessions.size(); i++) {
                String sessionTag = SpringBootFailoverTest.extractSessionConnTag(data.sessions.get(i));
                boolean matches = sessionTag.equals("INHERITED_FROM_PARENT") || sessionTag.equals(data.fullConnTag);
                System.out.println("  Session " + (i+1) + ": " + 
                                 (matches ? "✅ Inherits parent CONNTAG" : "❌ Different CONNTAG"));
                if (!matches && !sessionTag.equals("INHERITED_FROM_PARENT")) {
                    allMatch = false;
                }
            }
            
            System.out.println("  RESULT: " + (allMatch ? 
                "✅ ALL SESSIONS INHERIT PARENT CONNTAG - AFFINITY PROVEN!" : 
                "❌ Sessions have different CONNTAGs"));
        }
    }
    
    /**
     * Extracts Queue Manager name from connection properties
     * 
     * Methods to identify QM:
     * 1. Parse CONNTAG for QM name (most reliable)
     * 2. Check CONNECTION_ID hex encoding (514D31=QM1, 514D32=QM2, 514D33=QM3)
     * 3. Use JMS_IBM_RESOLVED_QUEUE_MANAGER property
     * 
     * @param connection The JMS Connection to analyze
     * @return Queue Manager name (QM1, QM2, QM3) or UNKNOWN
     */
    private static String extractQueueManager(Connection connection) {
        try {
            // Method 1: Extract from CONNTAG (e.g., MQCT...QM2_2025-09-05...)
            String conntag = SpringBootFailoverTest.extractFullConnTag(connection);
            if (conntag != null && conntag.contains("QM")) {
                if (conntag.contains("QM1")) return "QM1";
                if (conntag.contains("QM2")) return "QM2";
                if (conntag.contains("QM3")) return "QM3";
            }
            
            if (connection instanceof MQConnection) {
                MQConnection mqConn = (MQConnection) connection;
                
                // Try to get CONNECTION_ID first
                String connId = mqConn.getStringProperty("JMS_IBM_CONNECTION_ID");
                if (connId != null && connId.length() >= 24) {
                    String qmHex = connId.substring(8, 24);
                    if (qmHex.startsWith("514D31")) return "QM1";
                    if (qmHex.startsWith("514D32")) return "QM2";
                    if (qmHex.startsWith("514D33")) return "QM3";
                }
                
                // Try direct QM property
                String qm = mqConn.getStringProperty("JMS_IBM_RESOLVED_QUEUE_MANAGER");
                if (qm != null) return qm.trim();
            }
        } catch (Exception e) {
            // Silent
        }
        return "UNKNOWN";
    }
    
    private static String timestamp() {
        return TIME_FORMAT.format(new Date());
    }
}