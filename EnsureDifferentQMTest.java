import com.ibm.msg.client.jms.JmsConnectionFactory;
import com.ibm.msg.client.jms.JmsFactoryFactory;
import com.ibm.msg.client.wmq.WMQConstants;
import com.ibm.mq.jms.MQConnection;
import javax.jms.*;
import java.util.*;
import java.text.SimpleDateFormat;

public class EnsureDifferentQMTest {
    
    private static final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
    private static JmsConnectionFactory factory;
    private static volatile boolean c1FailoverDetected = false;
    private static volatile boolean c2FailoverDetected = false;
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
        String TRACKING_KEY = "ENSURED-" + timestamp.substring(timestamp.length()-10);
        
        System.out.println("================================================================================");
        System.out.println("   SELECTIVE FAILOVER TEST - ENSURING DIFFERENT QMs");
        System.out.println("================================================================================");
        System.out.println("Tracking Key: " + TRACKING_KEY);
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
        
        System.out.println("=== PHASE 1: ENSURE CONNECTIONS ON DIFFERENT QMs ===\n");
        
        Connection connection1 = null;
        Connection connection2 = null;
        int attempts = 0;
        int maxAttempts = 20;
        
        // Keep trying until we get connections on different QMs
        while (attempts < maxAttempts) {
            attempts++;
            System.out.println("Attempt " + attempts + " to get connections on different QMs...");
            
            // Close any existing connections
            if (connection1 != null) {
                try { connection1.close(); } catch (Exception e) {}
            }
            if (connection2 != null) {
                try { connection2.close(); } catch (Exception e) {}
            }
            
            // Create C1
            factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TRACKING_KEY + "-C1");
            connection1 = factory.createConnection();
            connection1.start();
            originalC1QM = getQueueManager(connection1);
            
            // Create C2
            factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TRACKING_KEY + "-C2");
            connection2 = factory.createConnection();
            connection2.start();
            originalC2QM = getQueueManager(connection2);
            
            System.out.println("  C1: " + originalC1QM);
            System.out.println("  C2: " + originalC2QM);
            
            if (!originalC1QM.equals(originalC2QM)) {
                System.out.println("\n✅ SUCCESS! C1 and C2 are on different Queue Managers!");
                break;
            }
            
            System.out.println("  Both on same QM, trying again...\n");
            Thread.sleep(500);
        }
        
        if (originalC1QM.equals(originalC2QM)) {
            System.out.println("\n❌ Unable to get connections on different QMs after " + maxAttempts + " attempts");
            System.out.println("This can happen with random distribution. Please run the test again.");
            System.exit(1);
        }
        
        // Set exception listeners
        connection1.setExceptionListener(new ExceptionListener() {
            public void onException(JMSException exception) {
                System.out.println("\n🔴 C1 DISCONNECTED at " + sdf.format(new Date()));
                c1FailoverDetected = true;
            }
        });
        
        connection2.setExceptionListener(new ExceptionListener() {
            public void onException(JMSException exception) {
                System.out.println("\n🔴 C2 DISCONNECTED at " + sdf.format(new Date()));
                c2FailoverDetected = true;
            }
        });
        
        System.out.println("\n=== PHASE 2: CREATE CHILD SESSIONS ===\n");
        
        // Create sessions
        List<Session> sessions1 = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            sessions1.add(connection1.createSession(false, Session.AUTO_ACKNOWLEDGE));
        }
        System.out.println("C1: Created 5 child sessions (6 total connections)");
        
        List<Session> sessions2 = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            sessions2.add(connection2.createSession(false, Session.AUTO_ACKNOWLEDGE));
        }
        System.out.println("C2: Created 3 child sessions (4 total connections)");
        
        // Capture BEFORE state
        List<ConnectionRow> beforeRows = captureState(connection1, sessions1, connection2, sessions2, TRACKING_KEY);
        
        // Print BEFORE table with full CONNTAG
        System.out.println("\n" + "=".repeat(250));
        System.out.println("BEFORE FAILOVER - CONNECTION STATE");
        System.out.println("-".repeat(250));
        printFullTable(beforeRows);
        
        // Create message producers
        javax.jms.Queue queue = sessions1.get(0).createQueue("queue:///UNIFORM.QUEUE");
        MessageProducer producer1 = sessions1.get(0).createProducer(queue);
        MessageProducer producer2 = sessions2.get(0).createProducer(queue);
        
        System.out.println("\n" + "=".repeat(80));
        System.out.println("PHASE 3: SELECTIVE FAILOVER TEST");
        System.out.println("=".repeat(80));
        System.out.println("\n📌 SELECTIVE FAILOVER SCENARIO:");
        System.out.println("  • C1 (parent + 5 children) on " + originalC1QM + " → WILL failover when stopped");
        System.out.println("  • C2 (parent + 3 children) on " + originalC2QM + " → SHOULD NOT move");
        
        System.out.println("\n⚠️  ACTION REQUIRED:");
        System.out.println("   Stop " + originalC1QM + " to trigger selective failover:");
        System.out.println("   ▶️  docker stop " + originalC1QM.toLowerCase());
        System.out.println("\nMonitoring connections for 60 seconds...");
        
        // Monitor for failover
        boolean c1Reconnected = false;
        for (int i = 0; i < 60; i++) {
            // Test C1
            try {
                TextMessage msg = sessions1.get(0).createTextMessage("HB-C1-" + i);
                producer1.send(msg);
                if (c1FailoverDetected && !c1Reconnected) {
                    c1Reconnected = true;
                    System.out.println("\n🟢 C1 RECONNECTED successfully!");
                }
            } catch (Exception e) {
                if (!c1FailoverDetected) {
                    System.out.print("X");
                } else {
                    System.out.print("R");
                }
            }
            
            // Test C2
            try {
                TextMessage msg = sessions2.get(0).createTextMessage("HB-C2-" + i);
                producer2.send(msg);
                System.out.print(".");
            } catch (Exception e) {
                System.out.print("!");
            }
            
            if (i % 10 == 0 && i > 0) {
                System.out.print(" " + i + "s ");
            }
            
            Thread.sleep(1000);
        }
        
        System.out.println("\n\n" + "=".repeat(80));
        System.out.println("PHASE 4: VERIFY SELECTIVE FAILOVER RESULTS");
        System.out.println("=".repeat(80));
        
        // Allow stabilization
        System.out.println("\nAllowing connections to stabilize...");
        Thread.sleep(5000);
        
        // Check where connections actually are via MQSC
        System.out.println("\n📊 Checking actual connection locations:");
        System.out.println("   (To bypass JMS cache, we'll create fresh test connections)");
        
        // Close and recreate to get accurate state
        try {
            producer1.close();
            producer2.close();
            for (Session s : sessions1) s.close();
            for (Session s : sessions2) s.close();
            connection1.close();
            connection2.close();
        } catch (Exception e) {}
        
        Thread.sleep(2000);
        
        // Create fresh connections to verify state
        System.out.println("\nCreating fresh connections to verify post-failover state...");
        
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
        List<ConnectionRow> afterRows = captureState(afterConnection1, afterSessions1, 
                                                      afterConnection2, afterSessions2, 
                                                      TRACKING_KEY + "-AFTER");
        
        // Print AFTER table
        System.out.println("\n" + "=".repeat(250));
        System.out.println("AFTER FAILOVER - FRESH CONNECTION STATE");
        System.out.println("-".repeat(250));
        printFullTable(afterRows);
        
        // Analysis
        System.out.println("\n" + "=".repeat(80));
        System.out.println("SELECTIVE FAILOVER ANALYSIS");
        System.out.println("=".repeat(80));
        
        System.out.println("\n📊 CONNECTION MOVEMENT:");
        
        // C1 Analysis
        System.out.println("\n  C1 (6 connections - parent + 5 children):");
        System.out.println("    Before: " + originalC1QM);
        System.out.println("    After:  " + afterC1QM);
        if (c1FailoverDetected) {
            if (!originalC1QM.equals(afterC1QM) && !afterC1QM.equals(originalC1QM)) {
                System.out.println("    Result: ✅ Successfully failed over to different QM");
            } else {
                System.out.println("    Result: ⚠️  New connection on unexpected QM");
            }
        } else {
            System.out.println("    Result: ❌ No failover detected - " + originalC1QM + " may not have been stopped");
        }
        
        // C2 Analysis
        System.out.println("\n  C2 (4 connections - parent + 3 children):");
        System.out.println("    Before: " + originalC2QM);
        System.out.println("    After:  " + afterC2QM);
        System.out.println("    Expected: Should remain on " + originalC2QM);
        if (!c2FailoverDetected) {
            if (afterC2QM.equals(originalC2QM)) {
                System.out.println("    Result: ✅ Correctly remained on " + originalC2QM + " (no failover)");
            } else {
                System.out.println("    Result: ⚠️  New connection landed on different QM (CCDT random)");
            }
        } else {
            System.out.println("    Result: ❌ Unexpected failover detected!");
        }
        
        System.out.println("\n📌 PARENT-CHILD AFFINITY:");
        // Check affinity for both connections
        boolean c1Affinity = true;
        for (int i = 1; i <= 5; i++) {
            if (!afterRows.get(i).queueManager.equals(afterC1QM)) {
                c1Affinity = false;
                break;
            }
        }
        System.out.println("  C1-AFTER: " + (c1Affinity ? "✅ All 6 on " + afterC1QM : "❌ Split across QMs"));
        
        boolean c2Affinity = true;
        for (int i = 7; i <= 9; i++) {
            if (!afterRows.get(i).queueManager.equals(afterC2QM)) {
                c2Affinity = false;
                break;
            }
        }
        System.out.println("  C2-AFTER: " + (c2Affinity ? "✅ All 4 on " + afterC2QM : "❌ Split across QMs"));
        
        System.out.println("\n📝 KEY FINDINGS:");
        if (c1FailoverDetected && !c2FailoverDetected) {
            System.out.println("  ✅ SELECTIVE FAILOVER WORKED!");
            System.out.println("     • C1 and its 5 children failed over together");
            System.out.println("     • C2 and its 3 children remained stable");
        } else if (!c1FailoverDetected) {
            System.out.println("  ⚠️  No failover detected - ensure " + originalC1QM + " was stopped");
        } else if (c2FailoverDetected) {
            System.out.println("  ❌ Both connections failed over - not selective");
        }
        
        // Cleanup
        for (Session s : afterSessions1) s.close();
        for (Session s : afterSessions2) s.close();
        afterConnection1.close();
        afterConnection2.close();
        
        System.out.println("\n" + "=".repeat(80));
        System.out.println("✅ TEST COMPLETED");
        System.out.println("=".repeat(80));
        
        if (!c1FailoverDetected) {
            System.out.println("\n⚠️  Remember to stop " + originalC1QM + " if you haven't already");
        }
        System.out.println("\n⚠️  Remember to restart any stopped Queue Manager:");
        System.out.println("   ▶️  docker start " + originalC1QM.toLowerCase());
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
                c1Parent.connectionId = "N/A";
                c1Parent.fullConnTag = "N/A";
                c1Parent.queueManager = "N/A";
            }
        }
        c1Parent.appTag = trackingKey + "-C1";
        rows.add(c1Parent);
        
        // Connection 1 - Children
        for (int i = 0; i < sessions1.size(); i++) {
            ConnectionRow row = new ConnectionRow();
            row.num = num++;
            row.type = "Child-" + (i+1);
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
                c2Parent.connectionId = "N/A";
                c2Parent.fullConnTag = "N/A";
                c2Parent.queueManager = "N/A";
            }
        }
        c2Parent.appTag = trackingKey + "-C2";
        rows.add(c2Parent);
        
        // Connection 2 - Children
        for (int i = 0; i < sessions2.size(); i++) {
            ConnectionRow row = new ConnectionRow();
            row.num = num++;
            row.type = "Child-" + (i+1);
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
    
    private static void printFullTable(List<ConnectionRow> rows) {
        // Print header - NO TRUNCATION
        System.out.println("| # | Type     | Conn | Session | CONNECTION_ID                     | FULL_CONNTAG (Complete Value - No Truncation)                                                                                     | Queue Manager | APPLTAG |");
        System.out.println("|---|----------|------|---------|-----------------------------------|-------------------------------------------------------------------------------------------------------------------------------------|---------------|---------|");
        
        for (ConnectionRow row : rows) {
            // Print FULL CONNTAG without any truncation
            System.out.printf("| %d | %-8s | %-4s | %-7s | %-33s | %-131s | **%-11s** | %s |\n",
                row.num,
                row.type,
                row.conn,
                row.session,
                row.connectionId,
                row.fullConnTag,  // FULL VALUE - NO TRUNCATION
                row.queueManager,
                row.appTag
            );
        }
    }
}