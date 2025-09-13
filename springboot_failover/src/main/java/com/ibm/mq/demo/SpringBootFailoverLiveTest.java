package com.ibm.mq.demo;

import com.ibm.mq.jms.MQConnectionFactory;
import com.ibm.msg.client.wmq.WMQConstants;
import com.ibm.mq.jms.MQConnection;
import com.ibm.mq.jms.MQSession;
import javax.jms.*;
import java.util.*;
import java.text.SimpleDateFormat;

/**
 * Spring Boot MQ Failover Live Test - Keeps connections alive for monitoring
 */
public class SpringBootFailoverLiveTest {
    
    private static final String TEST_ID = "SB" + (System.currentTimeMillis() % 1000);
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
        System.out.println("         SPRING BOOT MQ FAILOVER LIVE TEST - 2 MINUTE RUNTIME");
        System.out.println("================================================================================");
        System.out.println("Test ID: " + TEST_ID);
        System.out.println("Start Time: " + timestamp());
        
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
        System.out.println("                    CONNECTION STATE - FULL CONNTAG TABLE");
        System.out.println("================================================================================");
        displayConnectionTable(Arrays.asList(conn1, conn2));
        
        System.out.println("\n[" + timestamp() + "] Connections will stay alive for 2 minutes...");
        System.out.println("[" + timestamp() + "] Use this time to:");
        System.out.println("    1. Check MQSC: DIS CONN(*) WHERE(APPLTAG LK '" + TEST_ID + "*')");
        System.out.println("    2. Stop a Queue Manager to trigger failover");
        System.out.println("    3. Monitor the automatic reconnection");
        
        // Keep alive for 2 minutes, checking every 10 seconds
        for (int i = 0; i < 12; i++) {
            Thread.sleep(10000);
            System.out.println("[" + timestamp() + "] Still alive... (" + ((i+1)*10) + " seconds elapsed)");
            
            // Check if CONNTAG changed (failover occurred)
            try {
                String newConnTag1 = SpringBootFailoverTest.extractFullConnTag(conn1.connection);
                String newConnTag2 = SpringBootFailoverTest.extractFullConnTag(conn2.connection);
                
                if (!newConnTag1.equals(conn1.fullConnTag) || !newConnTag2.equals(conn2.fullConnTag)) {
                    System.out.println("\n[" + timestamp() + "] *** FAILOVER DETECTED! ***");
                    
                    // Update CONNTAGs
                    String oldQM1 = conn1.queueManager;
                    String oldQM2 = conn2.queueManager;
                    
                    conn1.fullConnTag = newConnTag1;
                    conn2.fullConnTag = newConnTag2;
                    conn1.queueManager = extractQueueManager(conn1.connection);
                    conn2.queueManager = extractQueueManager(conn2.connection);
                    
                    System.out.println("\n[" + timestamp() + "] Connection Movement:");
                    System.out.println("    C1: " + oldQM1 + " → " + conn1.queueManager);
                    System.out.println("    C2: " + oldQM2 + " → " + conn2.queueManager);
                    
                    System.out.println("\n================================================================================");
                    System.out.println("                     AFTER FAILOVER - UPDATED CONNTAG TABLE");
                    System.out.println("================================================================================");
                    displayConnectionTable(Arrays.asList(conn1, conn2));
                }
            } catch (Exception e) {
                System.out.println("[" + timestamp() + "] Error checking connection: " + e.getMessage());
            }
        }
        
        // Final state
        System.out.println("\n================================================================================");
        System.out.println("                     FINAL STATE - CONNECTION TABLE");
        System.out.println("================================================================================");
        displayConnectionTable(Arrays.asList(conn1, conn2));
        
        // Cleanup
        System.out.println("\n[" + timestamp() + "] Closing connections...");
        for (ConnectionData data : Arrays.asList(conn1, conn2)) {
            for (Session session : data.sessions) {
                session.close();
            }
            data.connection.close();
        }
        
        System.out.println("\n[" + timestamp() + "] Test completed");
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
                System.out.println("\n[" + timestamp() + "] ExceptionListener triggered for " + connId);
                System.out.println("[" + timestamp() + "] Error: " + e.getMessage());
            }
        });
        
        connection.start();
        
        // Extract properties
        connData.fullConnTag = SpringBootFailoverTest.extractFullConnTag(connection);
        connData.queueManager = extractQueueManager(connection);
        
        System.out.println("[" + timestamp() + "] " + connId + " connected to " + connData.queueManager);
        System.out.println("[" + timestamp() + "] CONNTAG: " + connData.fullConnTag.substring(0, Math.min(50, connData.fullConnTag.length())) + "...");
        
        // Create sessions
        for (int i = 0; i < sessionCount; i++) {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            connData.sessions.add(session);
        }
        System.out.println("[" + timestamp() + "] Created " + sessionCount + " sessions");
        
        return connData;
    }
    
    private static void displayConnectionTable(List<ConnectionData> connections) {
        System.out.println();
        System.out.println("┌────┬────────┬──────┬─────────┬────────┬─────────────────────────────────┐");
        System.out.println("│ #  │ Type   │ Conn │ Session │ QM     │ APPTAG                          │");
        System.out.println("├────┼────────┼──────┼─────────┼────────┼─────────────────────────────────┤");
        
        int row = 1;
        for (ConnectionData data : connections) {
            // Parent
            System.out.printf("│ %-2d │ Parent │ %-4s │    -    │ %-6s │ %-31s │%n",
                row++, data.id, data.queueManager, data.appTag);
            
            // Sessions
            for (int i = 0; i < data.sessions.size(); i++) {
                System.out.printf("│ %-2d │ Session│ %-4s │    %d    │ %-6s │ %-31s │%n",
                    row++, data.id, (i+1), data.queueManager, data.appTag);
            }
        }
        
        System.out.println("└────┴────────┴──────┴─────────┴────────┴─────────────────────────────────┘");
        
        System.out.println("\nSummary:");
        for (ConnectionData data : connections) {
            System.out.println("• " + data.id + ": 1 parent + " + data.sessions.size() + 
                             " sessions = " + (1 + data.sessions.size()) + " total on " + data.queueManager);
        }
    }
    
    private static String extractQueueManager(Connection connection) {
        try {
            String conntag = SpringBootFailoverTest.extractFullConnTag(connection);
            if (conntag != null && conntag.contains("QM")) {
                if (conntag.contains("QM1")) return "QM1";
                if (conntag.contains("QM2")) return "QM2";
                if (conntag.contains("QM3")) return "QM3";
            }
            
            if (connection instanceof MQConnection) {
                MQConnection mqConn = (MQConnection) connection;
                String connId = mqConn.getStringProperty("JMS_IBM_CONNECTION_ID");
                if (connId != null && connId.length() >= 24) {
                    String qmHex = connId.substring(8, 24);
                    if (qmHex.startsWith("514D31")) return "QM1";
                    if (qmHex.startsWith("514D32")) return "QM2";
                    if (qmHex.startsWith("514D33")) return "QM3";
                }
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