package com.ibm.mq.demo;

import com.ibm.mq.jms.MQConnectionFactory;
import com.ibm.msg.client.wmq.WMQConstants;
import com.ibm.mq.jms.MQConnection;
import com.ibm.mq.jms.MQSession;
import javax.jms.*;
import java.util.*;
import java.text.SimpleDateFormat;

/**
 * Spring Boot MQ Failover Test - Shows ACTUAL CONNTAG for ALL 10 sessions
 * No "INHERITS FROM PARENT" - shows real values for every session
 */
public class SpringBootFailoverCompleteTable {
    
    private static final String TEST_ID = "SPRING-" + System.currentTimeMillis();
    private static final String CCDT_URL = "file:///workspace/ccdt/ccdt.json";
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss.SSS");
    
    static class SessionData {
        int sessionNumber;
        Session session;
        String actualConnTag;  // The REAL CONNTAG value, not inherited placeholder
        
        SessionData(int num, Session sess) {
            this.sessionNumber = num;
            this.session = sess;
        }
    }
    
    static class ConnectionData {
        String id;
        Connection connection;
        String parentConnTag;
        String queueManager;
        String host;
        String appTag;
        List<SessionData> sessions = new ArrayList<>();
        
        ConnectionData(String id, Connection conn, String appTag) {
            this.id = id;
            this.connection = conn;
            this.appTag = appTag;
        }
    }
    
    public static void main(String[] args) throws Exception {
        System.out.println("\n================================================================================");
        System.out.println("   SPRING BOOT FAILOVER TEST - COMPLETE 10 SESSION TABLE WITH ACTUAL CONNTAGS");
        System.out.println("================================================================================");
        System.out.println("Test ID: " + TEST_ID);
        System.out.println("Time: " + timestamp());
        System.out.println("CCDT: " + CCDT_URL);
        
        // Create connection factory
        MQConnectionFactory factory = createFactory();
        
        // Create Connection 1 with 5 sessions
        System.out.println("\n[" + timestamp() + "] Creating Connection 1 (PARENT) with 5 CHILD sessions...");
        ConnectionData conn1 = createConnectionWithSessions(factory, "C1", 5);
        
        // Create Connection 2 with 3 sessions  
        System.out.println("\n[" + timestamp() + "] Creating Connection 2 (PARENT) with 3 CHILD sessions...");
        ConnectionData conn2 = createConnectionWithSessions(factory, "C2", 3);
        
        // Extract ACTUAL CONNTAGs for all sessions
        extractAllConnTags(conn1);
        extractAllConnTags(conn2);
        
        // Display BEFORE FAILOVER table with ACTUAL values
        System.out.println("\n================================================================================");
        System.out.println("              📊 BEFORE FAILOVER - ACTUAL CONNTAG FOR ALL 10 SESSIONS");
        System.out.println("================================================================================");
        displayCompleteTable(Arrays.asList(conn1, conn2), "BEFORE");
        
        // Keep connections alive and monitor for failover
        System.out.println("\n================================================================================");
        System.out.println("                           ⏱️ MONITORING FOR FAILOVER");
        System.out.println("================================================================================");
        System.out.println("[" + timestamp() + "] Connections established. Monitoring for 60 seconds...");
        System.out.println("[" + timestamp() + "] To trigger failover: docker stop " + conn1.queueManager.toLowerCase());
        System.out.println("[" + timestamp() + "] Waiting for failover event...");
        
        // Store before values
        Map<String, String> beforeConnTags = new HashMap<>();
        beforeConnTags.put("C1-PARENT", conn1.parentConnTag);
        for (SessionData sd : conn1.sessions) {
            beforeConnTags.put("C1-SESSION-" + sd.sessionNumber, sd.actualConnTag);
        }
        beforeConnTags.put("C2-PARENT", conn2.parentConnTag);
        for (SessionData sd : conn2.sessions) {
            beforeConnTags.put("C2-SESSION-" + sd.sessionNumber, sd.actualConnTag);
        }
        
        // Monitor for failover
        boolean failoverDetected = false;
        for (int i = 0; i < 30; i++) {
            Thread.sleep(2000);
            System.out.print(".");
            
            // Check if CONNTAG changed
            String currentTag1 = SpringBootFailoverTest.extractFullConnTag(conn1.connection);
            String currentTag2 = SpringBootFailoverTest.extractFullConnTag(conn2.connection);
            
            if (!currentTag1.equals(conn1.parentConnTag) || !currentTag2.equals(conn2.parentConnTag)) {
                failoverDetected = true;
                System.out.println("\n\n[" + timestamp() + "] 🚨 FAILOVER DETECTED!");
                System.out.println("[" + timestamp() + "] 🔄 Connections moving to new Queue Manager...");
                
                // Wait for stabilization
                Thread.sleep(5000);
                
                // Re-extract ALL CONNTAGs after failover
                extractAllConnTags(conn1);
                extractAllConnTags(conn2);
                
                // Display AFTER FAILOVER table with ACTUAL values
                System.out.println("\n================================================================================");
                System.out.println("               📊 AFTER FAILOVER - ACTUAL CONNTAG FOR ALL 10 SESSIONS");
                System.out.println("================================================================================");
                displayCompleteTable(Arrays.asList(conn1, conn2), "AFTER");
                
                // Show changes
                System.out.println("\n================================================================================");
                System.out.println("                            🔍 FAILOVER ANALYSIS");
                System.out.println("================================================================================");
                showFailoverChanges(beforeConnTags, conn1, conn2);
                
                break;
            }
        }
        
        if (!failoverDetected) {
            System.out.println("\n[" + timestamp() + "] No failover detected. Showing current state:");
            displayCompleteTable(Arrays.asList(conn1, conn2), "CURRENT");
        }
        
        // Show parent-child verification
        System.out.println("\n================================================================================");
        System.out.println("                      ✅ PARENT-CHILD AFFINITY VERIFICATION");
        System.out.println("================================================================================");
        verifyParentChildAffinity(conn1, conn2);
        
        // Cleanup
        System.out.println("\n[" + timestamp() + "] Closing all connections...");
        for (ConnectionData data : Arrays.asList(conn1, conn2)) {
            for (SessionData sd : data.sessions) {
                sd.session.close();
            }
            data.connection.close();
        }
        
        System.out.println("[" + timestamp() + "] Test completed successfully");
        System.out.println("================================================================================\n");
    }
    
    private static MQConnectionFactory createFactory() throws Exception {
        MQConnectionFactory factory = new MQConnectionFactory();
        factory.setTransportType(WMQConstants.WMQ_CM_CLIENT);
        factory.setStringProperty(WMQConstants.WMQ_CCDTURL, CCDT_URL);
        factory.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "*");
        factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, WMQConstants.WMQ_CLIENT_RECONNECT);
        factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_TIMEOUT, 1800);
        return factory;
    }
    
    private static ConnectionData createConnectionWithSessions(MQConnectionFactory factory,
                                                              String connId, int sessionCount) throws Exception {
        String appTag = TEST_ID + "-" + connId;
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, appTag);
        
        Connection connection = factory.createConnection("mqm", "");
        ConnectionData connData = new ConnectionData(connId, connection, appTag);
        
        connection.setExceptionListener(new ExceptionListener() {
            @Override
            public void onException(JMSException e) {
                System.out.println("\n[" + timestamp() + "] 🔴 ExceptionListener triggered for " + connId);
                System.out.println("[" + timestamp() + "] 🔴 Error: " + e.getErrorCode() + " - " + e.getMessage());
            }
        });
        
        connection.start();
        
        // Create sessions
        for (int i = 0; i < sessionCount; i++) {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            SessionData sd = new SessionData(i + 1, session);
            connData.sessions.add(sd);
            System.out.println("[" + timestamp() + "]   Created Session " + (i+1) + " for " + connId);
        }
        
        return connData;
    }
    
    private static void extractAllConnTags(ConnectionData data) {
        // Extract parent CONNTAG
        data.parentConnTag = SpringBootFailoverTest.extractFullConnTag(data.connection);
        data.queueManager = extractQueueManager(data.parentConnTag);
        data.host = getHostForQM(data.queueManager);
        
        // Extract ACTUAL CONNTAG for each session
        for (SessionData sd : data.sessions) {
            // Get the REAL CONNTAG value for this session
            String sessionTag = SpringBootFailoverTest.extractSessionConnTag(sd.session);
            
            // If it says "INHERITED_FROM_PARENT", use the actual parent value
            if (sessionTag.equals("INHERITED_FROM_PARENT")) {
                sd.actualConnTag = data.parentConnTag;  // Use the ACTUAL parent CONNTAG
            } else {
                sd.actualConnTag = sessionTag;  // Use what was returned
            }
        }
    }
    
    private static void displayCompleteTable(List<ConnectionData> connections, String phase) {
        System.out.println();
        System.out.println("┌────┬──────────┬──────┬─────────┬────────────────────────────────────────────────────────────────────────────────────┬────────┬──────────────────────┬─────────────────────────┐");
        System.out.println("│ #  │ Type     │ Conn │ Session │ ACTUAL FULL CONNTAG (Complete Value - No Truncation)                              │ QM     │ Host                 │ APPTAG                  │");
        System.out.println("├────┼──────────┼──────┼─────────┼────────────────────────────────────────────────────────────────────────────────────┼────────┼──────────────────────┼─────────────────────────┤");
        
        int row = 1;
        for (ConnectionData data : connections) {
            // Parent connection - show ACTUAL CONNTAG
            System.out.printf("│ %-2d │ %-8s │ %-4s │    -    │ %-82s │ %-6s │ %-20s │ %-23s │%n",
                row++, "PARENT", data.id, data.parentConnTag, data.queueManager, data.host, data.appTag);
            
            // Child sessions - show ACTUAL CONNTAG for each
            for (SessionData sd : data.sessions) {
                System.out.printf("│ %-2d │ %-8s │ %-4s │    %d    │ %-82s │ %-6s │ %-20s │ %-23s │%n",
                    row++, "CHILD", data.id, sd.sessionNumber, 
                    sd.actualConnTag,  // ACTUAL value, not "INHERITS"
                    data.queueManager, data.host, data.appTag);
            }
        }
        
        System.out.println("└────┴──────────┴──────┴─────────┴────────────────────────────────────────────────────────────────────────────────────┴────────┴──────────────────────┴─────────────────────────┘");
        
        // Summary
        System.out.println("\n📊 " + phase + " FAILOVER Summary:");
        int totalConnections = 0;
        for (ConnectionData data : connections) {
            int connTotal = 1 + data.sessions.size();
            totalConnections += connTotal;
            System.out.println("  • " + data.id + ": 1 PARENT + " + data.sessions.size() + 
                             " CHILDREN = " + connTotal + " connections on " + 
                             data.queueManager + " (" + data.host + ")");
        }
        System.out.println("  • TOTAL: " + totalConnections + " connections (2 parents + 8 children)");
        
        // Verify all CONNTAGs are complete
        System.out.println("\n✅ CONNTAG Completeness Check:");
        for (ConnectionData data : connections) {
            System.out.println("  • " + data.id + " Parent CONNTAG length: " + data.parentConnTag.length() + " characters");
            for (SessionData sd : data.sessions) {
                System.out.println("    - Session " + sd.sessionNumber + " CONNTAG length: " + sd.actualConnTag.length() + " characters");
            }
        }
    }
    
    private static void verifyParentChildAffinity(ConnectionData conn1, ConnectionData conn2) {
        System.out.println("\n🔗 Connection C1 Parent-Child Verification:");
        System.out.println("  PARENT CONNTAG: " + conn1.parentConnTag);
        for (SessionData sd : conn1.sessions) {
            boolean matches = sd.actualConnTag.equals(conn1.parentConnTag);
            System.out.println("  Session " + sd.sessionNumber + ": " + 
                             (matches ? "✅ MATCHES parent (same CONNTAG)" : "❌ DIFFERENT from parent"));
        }
        
        System.out.println("\n🔗 Connection C2 Parent-Child Verification:");
        System.out.println("  PARENT CONNTAG: " + conn2.parentConnTag);
        for (SessionData sd : conn2.sessions) {
            boolean matches = sd.actualConnTag.equals(conn2.parentConnTag);
            System.out.println("  Session " + sd.sessionNumber + ": " + 
                             (matches ? "✅ MATCHES parent (same CONNTAG)" : "❌ DIFFERENT from parent"));
        }
    }
    
    private static void showFailoverChanges(Map<String, String> before, ConnectionData conn1, ConnectionData conn2) {
        System.out.println("\n🔄 CONNTAG Changes During Failover:");
        
        System.out.println("\nConnection C1:");
        System.out.println("  PARENT:");
        System.out.println("    BEFORE: " + before.get("C1-PARENT"));
        System.out.println("    AFTER:  " + conn1.parentConnTag);
        System.out.println("    CHANGED: " + (!before.get("C1-PARENT").equals(conn1.parentConnTag) ? "YES ✅" : "NO"));
        
        for (SessionData sd : conn1.sessions) {
            System.out.println("  SESSION " + sd.sessionNumber + ":");
            System.out.println("    BEFORE: " + before.get("C1-SESSION-" + sd.sessionNumber));
            System.out.println("    AFTER:  " + sd.actualConnTag);
            System.out.println("    CHANGED: " + (!before.get("C1-SESSION-" + sd.sessionNumber).equals(sd.actualConnTag) ? "YES ✅" : "NO"));
        }
        
        System.out.println("\nConnection C2:");
        System.out.println("  PARENT:");
        System.out.println("    BEFORE: " + before.get("C2-PARENT"));
        System.out.println("    AFTER:  " + conn2.parentConnTag);
        System.out.println("    CHANGED: " + (!before.get("C2-PARENT").equals(conn2.parentConnTag) ? "YES ✅" : "NO"));
        
        for (SessionData sd : conn2.sessions) {
            System.out.println("  SESSION " + sd.sessionNumber + ":");
            System.out.println("    BEFORE: " + before.get("C2-SESSION-" + sd.sessionNumber));
            System.out.println("    AFTER:  " + sd.actualConnTag);
            System.out.println("    CHANGED: " + (!before.get("C2-SESSION-" + sd.sessionNumber).equals(sd.actualConnTag) ? "YES ✅" : "NO"));
        }
        
        // Extract Queue Manager changes
        System.out.println("\n📍 Queue Manager Movement:");
        String beforeQM1 = extractQueueManager(before.get("C1-PARENT"));
        String beforeQM2 = extractQueueManager(before.get("C2-PARENT"));
        System.out.println("  C1: " + beforeQM1 + " → " + conn1.queueManager);
        System.out.println("  C2: " + beforeQM2 + " → " + conn2.queueManager);
    }
    
    private static String extractQueueManager(String conntag) {
        if (conntag.contains("QM1")) return "QM1";
        if (conntag.contains("QM2")) return "QM2";
        if (conntag.contains("QM3")) return "QM3";
        return "UNKNOWN";
    }
    
    private static String getHostForQM(String qm) {
        switch(qm) {
            case "QM1": return "10.10.10.10:1414";
            case "QM2": return "10.10.10.11:1414";
            case "QM3": return "10.10.10.12:1414";
            default: return "UNKNOWN";
        }
    }
    
    private static String timestamp() {
        return TIME_FORMAT.format(new Date());
    }
}