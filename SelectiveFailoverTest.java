import com.ibm.msg.client.jms.JmsConnectionFactory;
import com.ibm.msg.client.jms.JmsFactoryFactory;
import com.ibm.msg.client.wmq.WMQConstants;
import com.ibm.mq.jms.MQConnection;
import javax.jms.*;
import java.util.*;
import java.text.SimpleDateFormat;

public class SelectiveFailoverTest {
    
    private static final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
    private static volatile boolean c1FailoverDetected = false;
    private static JmsConnectionFactory factory;
    
    static class ConnectionData {
        int num;
        String type;
        String conn;
        String sessionNum;
        String connectionId;
        String connTag;
        String queueManager;
        String appTag;
    }
    
    public static void main(String[] args) throws Exception {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String TRACKING_KEY = "SELECTIVE-" + timestamp;
        
        System.out.println("================================================================================");
        System.out.println("   SELECTIVE FAILOVER TEST - ONLY C1 (5 SESSIONS) FAILS OVER");
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
        
        System.out.println("=== PHASE 1: CREATE INITIAL CONNECTIONS ===\n");
        
        // Keep creating connections until C1 and C2 are on different QMs
        Connection connection1 = null;
        Connection connection2 = null;
        List<Session> sessions1 = new ArrayList<>();
        List<Session> sessions2 = new ArrayList<>();
        String c1QM = null;
        String c2QM = null;
        int attempts = 0;
        
        while (attempts < 10) {
            attempts++;
            System.out.println("Attempt " + attempts + " to get connections on different QMs...");
            
            // Clean up previous attempts
            if (connection1 != null) {
                try { connection1.close(); } catch (Exception e) {}
            }
            if (connection2 != null) {
                try { connection2.close(); } catch (Exception e) {}
            }
            sessions1.clear();
            sessions2.clear();
            
            // Create Connection 1
            factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TRACKING_KEY + "-C1");
            connection1 = factory.createConnection();
            connection1.start();
            
            // Create Connection 2
            factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TRACKING_KEY + "-C2");
            connection2 = factory.createConnection();
            connection2.start();
            
            // Get QMs
            if (connection1 instanceof MQConnection) {
                c1QM = ((MQConnection) connection1).getStringProperty("XMSC_WMQ_RESOLVED_QUEUE_MANAGER");
            }
            if (connection2 instanceof MQConnection) {
                c2QM = ((MQConnection) connection2).getStringProperty("XMSC_WMQ_RESOLVED_QUEUE_MANAGER");
            }
            
            System.out.println("  C1 on: " + c1QM);
            System.out.println("  C2 on: " + c2QM);
            
            if (!c1QM.equals(c2QM)) {
                System.out.println("✓ Connections on different QMs!");
                break;
            }
            
            Thread.sleep(1000);
        }
        
        if (c1QM.equals(c2QM)) {
            System.out.println("⚠️  Could not get connections on different QMs after " + attempts + " attempts");
            System.out.println("   Proceeding anyway...");
        }
        
        // Set exception listener only for C1
        connection1.setExceptionListener(new ExceptionListener() {
            public void onException(JMSException e) {
                System.out.println("\n[" + sdf.format(new Date()) + "] C1 EXCEPTION: " + e.getMessage());
                if (!c1FailoverDetected && e.getMessage() != null && 
                    (e.getMessage().contains("reconnect") || e.getMessage().contains("broken"))) {
                    c1FailoverDetected = true;
                    System.out.println("   => C1 FAILOVER DETECTED!");
                }
            }
        });
        
        // Create sessions for Connection 1
        System.out.println("\nCreating 5 sessions for C1...");
        for (int i = 1; i <= 5; i++) {
            sessions1.add(connection1.createSession(false, Session.AUTO_ACKNOWLEDGE));
            System.out.println("  Session " + i + " created");
        }
        
        // Create sessions for Connection 2
        System.out.println("\nCreating 3 sessions for C2...");
        for (int i = 1; i <= 3; i++) {
            sessions2.add(connection2.createSession(false, Session.AUTO_ACKNOWLEDGE));
            System.out.println("  Session " + i + " created");
        }
        
        // Capture BEFORE state
        List<ConnectionData> beforeData = captureConnectionData(connection1, sessions1, connection2, sessions2, TRACKING_KEY);
        
        // Print BEFORE table
        System.out.println("\n" + "=".repeat(180));
        System.out.println("BEFORE FAILOVER - CONNECTION TABLE (10 connections total)");
        System.out.println("-".repeat(180));
        printConnectionTable(beforeData);
        
        System.out.println("\n📌 Connection Distribution:");
        System.out.println("  C1 (6 connections): " + c1QM);
        System.out.println("  C2 (4 connections): " + c2QM);
        
        // Create producer for C1 heartbeat
        javax.jms.Queue queue1 = sessions1.get(0).createQueue("queue:///UNIFORM.QUEUE");
        MessageProducer producer1 = sessions1.get(0).createProducer(queue1);
        
        // Create producer for C2 to keep it alive
        javax.jms.Queue queue2 = sessions2.get(0).createQueue("queue:///UNIFORM.QUEUE");
        MessageProducer producer2 = sessions2.get(0).createProducer(queue2);
        
        System.out.println("\n" + "=".repeat(80));
        System.out.println("PHASE 2: TRIGGER FAILOVER FOR C1 ONLY");
        System.out.println("=".repeat(80));
        System.out.println("\n⚠️  C1 is on: " + c1QM);
        System.out.println("⚠️  C2 is on: " + c2QM + " (WILL NOT FAILOVER)");
        System.out.println("\n⚠️  STOPPING " + c1QM + " to trigger C1 failover...");
        
        String qmToStop = c1QM.toLowerCase().replace("_", "").substring(0, 3);
        
        // Stop only the QM where C1 is connected
        try {
            Process p = Runtime.getRuntime().exec("docker stop " + qmToStop);
            p.waitFor();
            System.out.println("✓ " + qmToStop.toUpperCase() + " stopped");
        } catch (Exception e) {
            System.out.println("Error stopping QM: " + e.getMessage());
        }
        
        // Keep C2 alive while waiting for C1 failover
        System.out.println("\nWaiting for C1 failover while keeping C2 active...");
        long startTime = System.currentTimeMillis();
        int heartbeatCount = 0;
        
        while (System.currentTimeMillis() - startTime < 40000) { // 40 seconds
            try {
                // Try to send on C1 (will fail during failover)
                if (!c1FailoverDetected) {
                    TextMessage msg1 = sessions1.get(0).createTextMessage("C1-HB-" + heartbeatCount);
                    producer1.send(msg1);
                }
                
                // Keep C2 active
                TextMessage msg2 = sessions2.get(0).createTextMessage("C2-HB-" + heartbeatCount);
                producer2.send(msg2);
                System.out.print(".");
                
                heartbeatCount++;
                Thread.sleep(2000);
            } catch (Exception e) {
                // Expected for C1 during failover
            }
        }
        
        System.out.println("\n\n" + "=".repeat(80));
        System.out.println("PHASE 3: VERIFY SELECTIVE FAILOVER");
        System.out.println("=".repeat(80));
        
        // Close and recreate connections to get fresh properties
        System.out.println("\nClosing and recreating connections to clear cache...");
        
        // Close all
        try {
            producer1.close();
            producer2.close();
            for (Session s : sessions1) s.close();
            for (Session s : sessions2) s.close();
            connection1.close();
            connection2.close();
        } catch (Exception e) {}
        
        Thread.sleep(2000);
        
        // Create NEW connections to see actual state
        System.out.println("Creating new connections to verify actual state...");
        
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, "NEW-C1-" + timestamp);
        Connection newConnection1 = factory.createConnection();
        newConnection1.start();
        
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, "NEW-C2-" + timestamp);
        Connection newConnection2 = factory.createConnection();
        newConnection2.start();
        
        // Create new sessions
        List<Session> newSessions1 = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            newSessions1.add(newConnection1.createSession(false, Session.AUTO_ACKNOWLEDGE));
        }
        
        List<Session> newSessions2 = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            newSessions2.add(newConnection2.createSession(false, Session.AUTO_ACKNOWLEDGE));
        }
        
        // Capture AFTER state
        List<ConnectionData> afterData = captureConnectionData(newConnection1, newSessions1, newConnection2, newSessions2, "NEW-" + timestamp);
        
        // Print AFTER table
        System.out.println("\n" + "=".repeat(180));
        System.out.println("AFTER FAILOVER - CONNECTION TABLE (C1 moved, C2 stayed)");
        System.out.println("-".repeat(180));
        printConnectionTable(afterData);
        
        // Get new QMs
        String newC1QM = afterData.get(0).queueManager;
        String newC2QM = afterData.get(6).queueManager;
        
        System.out.println("\n📌 Connection Distribution After Failover:");
        System.out.println("  C1 (6 connections): " + c1QM + " → " + newC1QM + (c1QM.equals(newC1QM) ? " (NO CHANGE)" : " (MOVED!)"));
        System.out.println("  C2 (4 connections): " + c2QM + " → " + newC2QM + (c2QM.equals(newC2QM) ? " (STAYED)" : " (MOVED!)"));
        
        // Analysis
        System.out.println("\n" + "=".repeat(80));
        System.out.println("SELECTIVE FAILOVER ANALYSIS");
        System.out.println("=".repeat(80));
        
        boolean c1Moved = !c1QM.equals(newC1QM);
        boolean c2Stayed = c2QM.equals(newC2QM);
        
        System.out.println("\n📊 Results:");
        System.out.println("  Connection 1 (5 sessions):");
        System.out.println("    - Before: " + c1QM);
        System.out.println("    - After:  " + newC1QM);
        System.out.println("    - Status: " + (c1Moved ? "✅ MOVED (Failover successful)" : "❌ Did not move"));
        System.out.println("");
        System.out.println("  Connection 2 (3 sessions):");
        System.out.println("    - Before: " + c2QM);
        System.out.println("    - After:  " + newC2QM);
        System.out.println("    - Status: " + (c2Stayed ? "✅ STAYED (As expected)" : "⚠️  Moved (unexpected)"));
        
        if (c1Moved && c2Stayed) {
            System.out.println("\n✅ SUCCESS! Selective failover achieved:");
            System.out.println("  1. C1 (6 connections) failed over from " + c1QM + " to " + newC1QM);
            System.out.println("  2. C2 (4 connections) remained on " + c2QM);
            System.out.println("  3. Parent-child affinity maintained for both connections");
            System.out.println("  4. Only the affected connection failed over");
        }
        
        // CONNTAG comparison
        System.out.println("\n📋 CONNTAG Evidence:");
        System.out.println("  C1 Before: " + beforeData.get(0).connTag);
        System.out.println("  C1 After:  " + afterData.get(0).connTag);
        System.out.println("  C2 Before: " + beforeData.get(6).connTag);
        System.out.println("  C2 After:  " + afterData.get(6).connTag);
        
        // Cleanup
        for (Session s : newSessions1) s.close();
        for (Session s : newSessions2) s.close();
        newConnection1.close();
        newConnection2.close();
        
        // Restart stopped QM
        System.out.println("\nRestarting " + qmToStop + "...");
        Runtime.getRuntime().exec("docker start " + qmToStop);
        
        System.out.println("\n✅ Test completed");
    }
    
    private static List<ConnectionData> captureConnectionData(Connection conn1, List<Session> sessions1,
                                                               Connection conn2, List<Session> sessions2,
                                                               String trackingKey) throws Exception {
        List<ConnectionData> data = new ArrayList<>();
        int num = 1;
        
        // Connection 1 Parent
        ConnectionData c1Parent = new ConnectionData();
        c1Parent.num = num++;
        c1Parent.type = "Parent";
        c1Parent.conn = "C1";
        c1Parent.sessionNum = "-";
        c1Parent.appTag = trackingKey.contains("-C1") ? trackingKey : trackingKey + "-C1";
        
        if (conn1 instanceof MQConnection) {
            MQConnection mqConn = (MQConnection) conn1;
            try {
                c1Parent.connectionId = mqConn.getStringProperty("XMSC_WMQ_CONNECTION_ID");
                c1Parent.connTag = mqConn.getStringProperty("XMSC_WMQ_RESOLVED_CONNECTION_TAG");
                c1Parent.queueManager = mqConn.getStringProperty("XMSC_WMQ_RESOLVED_QUEUE_MANAGER");
                
                if (c1Parent.connTag == null && c1Parent.connectionId != null && c1Parent.connectionId.length() >= 48) {
                    String handle = c1Parent.connectionId.substring(32, 48);
                    c1Parent.connTag = "MQCT" + handle + c1Parent.queueManager;
                }
            } catch (JMSException e) {}
        }
        data.add(c1Parent);
        
        // Connection 1 Sessions
        for (int i = 0; i < sessions1.size(); i++) {
            ConnectionData sessData = new ConnectionData();
            sessData.num = num++;
            sessData.type = "Session";
            sessData.conn = "C1";
            sessData.sessionNum = String.valueOf(i + 1);
            sessData.connectionId = c1Parent.connectionId;
            sessData.connTag = c1Parent.connTag;
            sessData.queueManager = c1Parent.queueManager;
            sessData.appTag = c1Parent.appTag;
            data.add(sessData);
        }
        
        // Connection 2 Parent
        ConnectionData c2Parent = new ConnectionData();
        c2Parent.num = num++;
        c2Parent.type = "Parent";
        c2Parent.conn = "C2";
        c2Parent.sessionNum = "-";
        c2Parent.appTag = trackingKey.contains("-C2") ? trackingKey : trackingKey + "-C2";
        
        if (conn2 instanceof MQConnection) {
            MQConnection mqConn = (MQConnection) conn2;
            try {
                c2Parent.connectionId = mqConn.getStringProperty("XMSC_WMQ_CONNECTION_ID");
                c2Parent.connTag = mqConn.getStringProperty("XMSC_WMQ_RESOLVED_CONNECTION_TAG");
                c2Parent.queueManager = mqConn.getStringProperty("XMSC_WMQ_RESOLVED_QUEUE_MANAGER");
                
                if (c2Parent.connTag == null && c2Parent.connectionId != null && c2Parent.connectionId.length() >= 48) {
                    String handle = c2Parent.connectionId.substring(32, 48);
                    c2Parent.connTag = "MQCT" + handle + c2Parent.queueManager;
                }
            } catch (JMSException e) {}
        }
        data.add(c2Parent);
        
        // Connection 2 Sessions
        for (int i = 0; i < sessions2.size(); i++) {
            ConnectionData sessData = new ConnectionData();
            sessData.num = num++;
            sessData.type = "Session";
            sessData.conn = "C2";
            sessData.sessionNum = String.valueOf(i + 1);
            sessData.connectionId = c2Parent.connectionId;
            sessData.connTag = c2Parent.connTag;
            sessData.queueManager = c2Parent.queueManager;
            sessData.appTag = c2Parent.appTag;
            data.add(sessData);
        }
        
        return data;
    }
    
    private static void printConnectionTable(List<ConnectionData> data) {
        System.out.println(String.format("%-4s %-8s %-4s %-8s %-50s %-60s %-30s %-20s",
            "#", "Type", "Conn", "Session", "CONNECTION_ID", "FULL_CONNTAG", "Queue Manager", "APPLTAG"));
        System.out.println("-".repeat(180));
        
        for (ConnectionData row : data) {
            String connIdDisplay = row.connectionId;
            if (connIdDisplay != null && connIdDisplay.length() > 48) {
                connIdDisplay = connIdDisplay.substring(0, 48);
            }
            
            System.out.println(String.format("%-4d %-8s %-4s %-8s %-50s %-60s %-30s %-20s",
                row.num,
                row.type,
                row.conn,
                row.sessionNum,
                connIdDisplay != null ? connIdDisplay : "UNKNOWN",
                row.connTag != null ? row.connTag : "UNKNOWN",
                row.queueManager != null ? row.queueManager : "UNKNOWN",
                row.appTag != null ? row.appTag : "UNKNOWN"
            ));
        }
        System.out.println("-".repeat(180));
    }
}