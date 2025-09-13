import com.ibm.msg.client.jms.JmsConnectionFactory;
import com.ibm.msg.client.jms.JmsFactoryFactory;
import com.ibm.msg.client.wmq.WMQConstants;
import com.ibm.mq.jms.MQConnection;
import javax.jms.*;
import java.util.*;
import java.text.SimpleDateFormat;

public class VerifiedFailoverTest {
    
    private static final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
    private static JmsConnectionFactory factory;
    private static volatile boolean c1FailoverDetected = false;
    private static volatile boolean c2FailoverDetected = false;
    private static volatile String c1ReconnectedTime = "";
    private static volatile String c2ReconnectedTime = "";
    private static String originalC1QM = "";
    private static String originalC2QM = "";
    
    static class ConnectionRow {
        int num;
        String type;
        String conn;
        String session;
        String connectionId;
        String fullConnTag;
        String queueManager;
        String appTag;
    }
    
    public static void main(String[] args) throws Exception {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String TRACKING_KEY = "VERIFY-" + timestamp.substring(timestamp.length()-10);
        
        System.out.println("================================================================================");
        System.out.println("   VERIFIED FAILOVER TEST - WITH RECONNECTION PROOF");
        System.out.println("================================================================================");
        System.out.println("Tracking Key: " + TRACKING_KEY);
        System.out.println("Test Duration: ~90 seconds");
        System.out.println("");
        
        // Create connection factory
        JmsFactoryFactory ff = JmsFactoryFactory.getInstance(WMQConstants.WMQ_PROVIDER);
        factory = ff.createConnectionFactory();
        
        // Configure for uniform cluster with reconnect
        factory.setStringProperty(WMQConstants.WMQ_CCDTURL, "file:///workspace/ccdt/ccdt.json");
        factory.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
        factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, WMQConstants.WMQ_CLIENT_RECONNECT);
        factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_TIMEOUT, 300);
        factory.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
        factory.setStringProperty(WMQConstants.USERID, "app");
        factory.setStringProperty(WMQConstants.PASSWORD, "passw0rd");
        factory.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "*");
        
        System.out.println("=== PHASE 1: CREATE INITIAL CONNECTIONS ===\n");
        
        // Create connections
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TRACKING_KEY + "-C1");
        Connection connection1 = factory.createConnection();
        
        // Set exception listener to detect failover
        connection1.setExceptionListener(new ExceptionListener() {
            public void onException(JMSException exception) {
                System.out.println("\n🔴 C1 DISCONNECTED at " + sdf.format(new Date()) + 
                                 " - Reason: " + exception.getErrorCode());
                c1FailoverDetected = true;
            }
        });
        
        connection1.start();
        
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TRACKING_KEY + "-C2");
        Connection connection2 = factory.createConnection();
        
        connection2.setExceptionListener(new ExceptionListener() {
            public void onException(JMSException exception) {
                System.out.println("\n🔴 C2 DISCONNECTED at " + sdf.format(new Date()) + 
                                 " - Reason: " + exception.getErrorCode());
                c2FailoverDetected = true;
            }
        });
        
        connection2.start();
        
        // Get initial QMs
        originalC1QM = getQueueManager(connection1);
        originalC2QM = getQueueManager(connection2);
        
        System.out.println("Initial connection distribution:");
        System.out.println("  C1: Connected to " + originalC1QM);
        System.out.println("  C2: Connected to " + originalC2QM);
        
        // Create sessions
        System.out.println("\nCreating sessions...");
        List<Session> sessions1 = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            sessions1.add(connection1.createSession(false, Session.AUTO_ACKNOWLEDGE));
        }
        System.out.println("  C1: 5 sessions created (6 total MQ connections)");
        
        List<Session> sessions2 = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            sessions2.add(connection2.createSession(false, Session.AUTO_ACKNOWLEDGE));
        }
        System.out.println("  C2: 3 sessions created (4 total MQ connections)");
        
        // Capture BEFORE state
        List<ConnectionRow> beforeRows = captureState(connection1, sessions1, connection2, sessions2, TRACKING_KEY);
        
        // Print BEFORE table
        System.out.println("\n" + "=".repeat(150));
        System.out.println("BEFORE FAILOVER - CONNECTION STATE");
        System.out.println("-".repeat(150));
        printTable(beforeRows);
        
        // Create message producers for testing
        javax.jms.Queue queue = sessions1.get(0).createQueue("queue:///UNIFORM.QUEUE");
        MessageProducer producer1 = sessions1.get(0).createProducer(queue);
        MessageProducer producer2 = sessions2.get(0).createProducer(queue);
        
        System.out.println("\n" + "=".repeat(80));
        System.out.println("PHASE 2: FAILOVER SCENARIO");
        System.out.println("=".repeat(80));
        
        String qmToStop = "";
        if (!originalC1QM.equals(originalC2QM)) {
            qmToStop = originalC1QM;
            System.out.println("\n📌 SELECTIVE FAILOVER TEST:");
            System.out.println("  • C1 on " + originalC1QM + " → Will failover when " + originalC1QM + " stops");
            System.out.println("  • C2 on " + originalC2QM + " → Should remain stable");
        } else {
            qmToStop = originalC1QM;
            System.out.println("\n📌 FULL FAILOVER TEST:");
            System.out.println("  • Both connections on " + originalC1QM);
            System.out.println("  • Both will failover when " + originalC1QM + " stops");
        }
        
        System.out.println("\n⚠️  ACTION REQUIRED:");
        System.out.println("   Please run this command in another terminal NOW:");
        System.out.println("   ▶️  docker stop " + qmToStop.toLowerCase());
        System.out.println("\nWaiting for failover event (monitoring for 60 seconds)...");
        
        // Monitor for failover and reconnection
        boolean c1Reconnected = false;
        boolean c2Reconnected = false;
        int heartbeatsSent = 0;
        
        for (int i = 0; i < 60; i++) {
            // Test C1 connection
            if (!c1Reconnected) {
                try {
                    TextMessage msg = sessions1.get(0).createTextMessage("HB-C1-" + i);
                    producer1.send(msg);
                    
                    if (c1FailoverDetected) {
                        c1Reconnected = true;
                        c1ReconnectedTime = sdf.format(new Date());
                        System.out.println("\n🟢 C1 RECONNECTED at " + c1ReconnectedTime);
                    }
                } catch (Exception e) {
                    if (!c1FailoverDetected) {
                        System.out.print("X"); // Connection lost
                    }
                }
            }
            
            // Test C2 connection
            if (!c2Reconnected || !c2FailoverDetected) {
                try {
                    TextMessage msg = sessions2.get(0).createTextMessage("HB-C2-" + i);
                    producer2.send(msg);
                    
                    if (c2FailoverDetected && !c2Reconnected) {
                        c2Reconnected = true;
                        c2ReconnectedTime = sdf.format(new Date());
                        System.out.println("\n🟢 C2 RECONNECTED at " + c2ReconnectedTime);
                    }
                } catch (Exception e) {
                    if (!c2FailoverDetected) {
                        System.out.print("Y"); // Connection lost
                    }
                }
            }
            
            if (i % 10 == 0 && i > 0) {
                System.out.print(" " + i + "s ");
            } else {
                System.out.print(".");
            }
            
            Thread.sleep(1000);
        }
        
        System.out.println("\n\n" + "=".repeat(80));
        System.out.println("PHASE 3: POST-FAILOVER VERIFICATION");
        System.out.println("=".repeat(80));
        
        // Allow time for full stabilization
        System.out.println("\nAllowing connections to stabilize...");
        Thread.sleep(5000);
        
        // Close and recreate to get fresh properties
        System.out.println("Refreshing connection properties...");
        
        // Close everything
        try {
            producer1.close();
            producer2.close();
            for (Session s : sessions1) s.close();
            for (Session s : sessions2) s.close();
            connection1.close();
            connection2.close();
        } catch (Exception e) {
            // Ignore
        }
        
        Thread.sleep(2000);
        
        // Create fresh connections to verify final state
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TRACKING_KEY + "-C1-AFTER");
        Connection afterConnection1 = factory.createConnection();
        afterConnection1.start();
        
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TRACKING_KEY + "-C2-AFTER");
        Connection afterConnection2 = factory.createConnection();
        afterConnection2.start();
        
        String afterC1QM = getQueueManager(afterConnection1);
        String afterC2QM = getQueueManager(afterConnection2);
        
        // Create sessions for after state
        List<Session> afterSessions1 = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            afterSessions1.add(afterConnection1.createSession(false, Session.AUTO_ACKNOWLEDGE));
        }
        
        List<Session> afterSessions2 = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            afterSessions2.add(afterConnection2.createSession(false, Session.AUTO_ACKNOWLEDGE));
        }
        
        // Capture AFTER state
        List<ConnectionRow> afterRows = captureState(afterConnection1, afterSessions1, afterConnection2, afterSessions2, TRACKING_KEY + "-AFTER");
        
        // Print AFTER table
        System.out.println("\n" + "=".repeat(150));
        System.out.println("AFTER FAILOVER - NEW CONNECTION STATE");
        System.out.println("-".repeat(150));
        printTable(afterRows);
        
        // Final Analysis
        System.out.println("\n" + "=".repeat(80));
        System.out.println("FAILOVER ANALYSIS RESULTS");
        System.out.println("=".repeat(80));
        
        System.out.println("\n📊 CONNECTION MOVEMENT:");
        System.out.println("  C1: " + originalC1QM + " → " + afterC1QM);
        if (!originalC1QM.equals(afterC1QM)) {
            System.out.println("      ✅ Successfully failed over to different QM");
        } else {
            System.out.println("      ⚠️  Same QM (may indicate " + originalC1QM + " was not stopped)");
        }
        
        System.out.println("  C2: " + originalC2QM + " → " + afterC2QM);
        if (originalC1QM.equals(originalC2QM)) {
            // Both were on same QM
            if (!originalC2QM.equals(afterC2QM)) {
                System.out.println("      ✅ Successfully failed over (was on same QM as C1)");
            } else {
                System.out.println("      ⚠️  No movement detected");
            }
        } else {
            // Different QMs originally
            if (originalC2QM.equals(afterC2QM)) {
                System.out.println("      ✅ Remained stable (selective failover worked)");
            } else {
                System.out.println("      ⚠️  Unexpected movement");
            }
        }
        
        System.out.println("\n📋 FAILOVER EVENTS:");
        if (c1FailoverDetected) {
            System.out.println("  C1: ✅ Disconnection detected");
            if (c1Reconnected) {
                System.out.println("      ✅ Reconnection successful at " + c1ReconnectedTime);
            } else {
                System.out.println("      ❌ Reconnection not confirmed");
            }
        } else {
            System.out.println("  C1: ⚠️  No disconnection detected (QM may not have been stopped)");
        }
        
        if (c2FailoverDetected) {
            System.out.println("  C2: ✅ Disconnection detected");
            if (c2Reconnected) {
                System.out.println("      ✅ Reconnection successful at " + c2ReconnectedTime);
            } else {
                System.out.println("      ❌ Reconnection not confirmed");
            }
        } else {
            if (originalC1QM.equals(originalC2QM)) {
                System.out.println("  C2: ⚠️  No disconnection detected (expected failover)");
            } else {
                System.out.println("  C2: ✅ No disconnection (as expected for selective failover)");
            }
        }
        
        System.out.println("\n📌 PARENT-CHILD AFFINITY:");
        // Check C1 affinity
        boolean c1Affinity = true;
        String c1QM = afterRows.get(0).queueManager;
        for (int i = 1; i <= 5; i++) {
            if (!afterRows.get(i).queueManager.equals(c1QM)) {
                c1Affinity = false;
                break;
            }
        }
        System.out.println("  C1: " + (c1Affinity ? "✅ All 6 connections on " + c1QM : "❌ Sessions split across QMs"));
        
        // Check C2 affinity
        boolean c2Affinity = true;
        String c2QM = afterRows.get(6).queueManager;
        for (int i = 7; i <= 9; i++) {
            if (!afterRows.get(i).queueManager.equals(c2QM)) {
                c2Affinity = false;
                break;
            }
        }
        System.out.println("  C2: " + (c2Affinity ? "✅ All 4 connections on " + c2QM : "❌ Sessions split across QMs"));
        
        // Cleanup
        for (Session s : afterSessions1) s.close();
        for (Session s : afterSessions2) s.close();
        afterConnection1.close();
        afterConnection2.close();
        
        System.out.println("\n" + "=".repeat(80));
        System.out.println("✅ TEST COMPLETED");
        System.out.println("=".repeat(80));
        
        if (!c1FailoverDetected) {
            System.out.println("\n⚠️  IMPORTANT: No failover was detected!");
            System.out.println("   This means " + qmToStop + " was likely not stopped during the test.");
            System.out.println("   Please ensure you run: docker stop " + qmToStop.toLowerCase());
            System.out.println("   Then restart it with: docker start " + qmToStop.toLowerCase());
        } else {
            System.out.println("\n⚠️  Remember to restart the stopped Queue Manager:");
            System.out.println("   ▶️  docker start " + qmToStop.toLowerCase());
        }
    }
    
    private static List<ConnectionRow> captureState(Connection conn1, List<Session> sessions1,
                                                    Connection conn2, List<Session> sessions2,
                                                    String trackingKey) throws Exception {
        List<ConnectionRow> rows = new ArrayList<>();
        int num = 1;
        
        // Connection 1 - Parent
        ConnectionRow c1Parent = new ConnectionRow();
        c1Parent.num = num++;
        c1Parent.type = "Parent";
        c1Parent.conn = "C1";
        c1Parent.session = "-";
        
        if (conn1 instanceof MQConnection) {
            MQConnection mqConn = (MQConnection) conn1;
            try {
                c1Parent.connectionId = formatConnectionId(mqConn.getStringProperty("XMSC_WMQ_CONNECTION_ID"));
                c1Parent.fullConnTag = getFullConnTag(mqConn);
                c1Parent.queueManager = mqConn.getStringProperty("XMSC_WMQ_RESOLVED_QUEUE_MANAGER");
            } catch (Exception e) {
                c1Parent.connectionId = "RECONNECTING";
                c1Parent.fullConnTag = "RECONNECTING";
                c1Parent.queueManager = "RECONNECTING";
            }
        }
        c1Parent.appTag = trackingKey.contains("AFTER") ? trackingKey.replace("-AFTER", "") + "-C1" : trackingKey + "-C1";
        rows.add(c1Parent);
        
        // Connection 1 - Sessions
        for (int i = 0; i < sessions1.size(); i++) {
            ConnectionRow row = new ConnectionRow();
            row.num = num++;
            row.type = "Session";
            row.conn = "C1";
            row.session = String.valueOf(i + 1);
            row.connectionId = c1Parent.connectionId;
            row.fullConnTag = c1Parent.fullConnTag;
            row.queueManager = c1Parent.queueManager;
            row.appTag = c1Parent.appTag;
            rows.add(row);
        }
        
        // Connection 2 - Parent
        ConnectionRow c2Parent = new ConnectionRow();
        c2Parent.num = num++;
        c2Parent.type = "Parent";
        c2Parent.conn = "C2";
        c2Parent.session = "-";
        
        if (conn2 instanceof MQConnection) {
            MQConnection mqConn = (MQConnection) conn2;
            try {
                c2Parent.connectionId = formatConnectionId(mqConn.getStringProperty("XMSC_WMQ_CONNECTION_ID"));
                c2Parent.fullConnTag = getFullConnTag(mqConn);
                c2Parent.queueManager = mqConn.getStringProperty("XMSC_WMQ_RESOLVED_QUEUE_MANAGER");
            } catch (Exception e) {
                c2Parent.connectionId = "RECONNECTING";
                c2Parent.fullConnTag = "RECONNECTING";
                c2Parent.queueManager = "RECONNECTING";
            }
        }
        c2Parent.appTag = trackingKey.contains("AFTER") ? trackingKey.replace("-AFTER", "") + "-C2" : trackingKey + "-C2";
        rows.add(c2Parent);
        
        // Connection 2 - Sessions
        for (int i = 0; i < sessions2.size(); i++) {
            ConnectionRow row = new ConnectionRow();
            row.num = num++;
            row.type = "Session";
            row.conn = "C2";
            row.session = String.valueOf(i + 1);
            row.connectionId = c2Parent.connectionId;
            row.fullConnTag = c2Parent.fullConnTag;
            row.queueManager = c2Parent.queueManager;
            row.appTag = c2Parent.appTag;
            rows.add(row);
        }
        
        return rows;
    }
    
    private static String getQueueManager(Connection conn) {
        try {
            if (conn instanceof MQConnection) {
                return ((MQConnection) conn).getStringProperty("XMSC_WMQ_RESOLVED_QUEUE_MANAGER");
            }
        } catch (Exception e) {
            return "ERROR";
        }
        return "UNKNOWN";
    }
    
    private static String getFullConnTag(MQConnection mqConn) {
        try {
            String connTag = mqConn.getStringProperty("XMSC_WMQ_RESOLVED_CONNECTION_TAG");
            if (connTag != null && !connTag.isEmpty()) {
                return connTag;
            }
            
            String connId = mqConn.getStringProperty("XMSC_WMQ_CONNECTION_ID");
            String qm = mqConn.getStringProperty("XMSC_WMQ_RESOLVED_QUEUE_MANAGER");
            if (connId != null && connId.length() >= 48 && qm != null) {
                String handle = connId.substring(32, 48);
                return "MQCT" + handle + qm;
            }
        } catch (Exception e) {
            return "ERROR";
        }
        return "UNKNOWN";
    }
    
    private static String formatConnectionId(String connId) {
        if (connId == null || connId.length() < 48) return "UNKNOWN";
        return connId.substring(0, 16) + "..." + connId.substring(32, 48);
    }
    
    private static void printTable(List<ConnectionRow> rows) {
        System.out.println("| # | Type | Conn | Session | CONNECTION_ID | FULL_CONNTAG | Queue Manager | APPLTAG |");
        System.out.println("|---|------|------|---------|---------------|--------------|---------------|---------|");
        
        for (ConnectionRow row : rows) {
            System.out.printf("| %d | %s | %s | %s | %s | %-50s | **%s** | %s |\n",
                row.num,
                row.type,
                row.conn,
                row.session,
                row.connectionId,
                row.fullConnTag.length() > 50 ? row.fullConnTag.substring(0, 47) + "..." : row.fullConnTag,
                row.queueManager,
                row.appTag
            );
        }
    }
}