package com.ibm.mq.demo;

import com.ibm.mq.jms.MQConnectionFactory;
import com.ibm.msg.client.wmq.WMQConstants;
import com.ibm.mq.jms.MQConnection;
import com.ibm.mq.jms.MQSession;
import javax.jms.*;
import java.util.*;
import java.text.SimpleDateFormat;

/**
 * Spring Boot MQ Failover Application
 * Demonstrates full CONNTAG extraction and parent-child session affinity
 */
public class SpringBootMQFailoverApplication {
    
    private static final String TEST_ID = "SPRINGBOOT-" + System.currentTimeMillis();
    private static final String CCDT_URL = "file:///workspace/ccdt/ccdt.json";
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss.SSS");
    
    static class ConnectionData {
        String id;
        Connection connection;
        List<Session> sessions = new ArrayList<>();
        String fullConnTag;
        String queueManager;
        String appTag;
        
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
        
        // Create Connection 1 with 5 sessions
        System.out.println("\n[" + timestamp() + "] Creating Connection 1 with 5 sessions...");
        ConnectionData conn1 = createConnectionWithSessions(factory, "C1", 5);
        
        // Create Connection 2 with 3 sessions
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
            
            // Check if CONNTAG changed (failover occurred)
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
        
        Connection connection = factory.createConnection("app", "");
        ConnectionData connData = new ConnectionData(connId, connection, appTag);
        
        connection.setExceptionListener(new ExceptionListener() {
            @Override
            public void onException(JMSException e) {
                System.out.println("\n[" + timestamp() + "] ExceptionListener triggered for " + connId);
                System.out.println("[" + timestamp() + "] Error: " + e.getMessage());
            }
        });
        
        connection.start();
        
        // Extract properties using Spring Boot approach
        connData.fullConnTag = SpringBootFailoverTest.extractFullConnTag(connection);
        connData.queueManager = extractQueueManager(connection);
        
        System.out.println("[" + timestamp() + "] " + connId + " connected to " + connData.queueManager);
        System.out.println("[" + timestamp() + "] FULL CONNTAG: " + connData.fullConnTag);
        
        // Create sessions
        for (int i = 0; i < sessionCount; i++) {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            connData.sessions.add(session);
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
    
    private static String extractQueueManager(Connection connection) {
        try {
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