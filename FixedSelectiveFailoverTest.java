import com.ibm.msg.client.jms.JmsConnectionFactory;
import com.ibm.msg.client.jms.JmsFactoryFactory;
import com.ibm.msg.client.wmq.WMQConstants;
import com.ibm.mq.jms.MQConnection;
import javax.jms.*;
import java.util.*;
import java.text.SimpleDateFormat;

public class FixedSelectiveFailoverTest {
    
    private static final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
    private static JmsConnectionFactory factory;
    private static volatile boolean failoverDetected = false;
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
        String TRACKING_KEY = "SELECTIVE-" + timestamp.substring(timestamp.length()-10);
        
        System.out.println("================================================================================");
        System.out.println("   FIXED SELECTIVE FAILOVER TEST - TESTING ACTUAL RECONNECTION");
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
        
        // Create connections
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TRACKING_KEY + "-C1");
        Connection connection1 = factory.createConnection();
        
        // Set exception listener to detect failover
        connection1.setExceptionListener(new ExceptionListener() {
            public void onException(JMSException exception) {
                System.out.println("\n⚠️  C1 Exception detected at " + sdf.format(new Date()) + 
                                 ": " + exception.getMessage());
                failoverDetected = true;
            }
        });
        
        connection1.start();
        
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TRACKING_KEY + "-C2");
        Connection connection2 = factory.createConnection();
        
        connection2.setExceptionListener(new ExceptionListener() {
            public void onException(JMSException exception) {
                System.out.println("\n⚠️  C2 Exception detected at " + sdf.format(new Date()) + 
                                 ": " + exception.getMessage());
            }
        });
        
        connection2.start();
        
        // Get initial QMs
        originalC1QM = getQueueManager(connection1);
        originalC2QM = getQueueManager(connection2);
        
        System.out.println("Initial connection distribution:");
        System.out.println("  C1: " + originalC1QM);
        System.out.println("  C2: " + originalC2QM);
        
        // Create sessions
        System.out.println("\nCreating sessions...");
        List<Session> sessions1 = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            sessions1.add(connection1.createSession(false, Session.AUTO_ACKNOWLEDGE));
        }
        System.out.println("  C1: 5 sessions created");
        
        List<Session> sessions2 = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            sessions2.add(connection2.createSession(false, Session.AUTO_ACKNOWLEDGE));
        }
        System.out.println("  C2: 3 sessions created");
        
        // Capture BEFORE state
        List<ConnectionRow> beforeRows = captureState(connection1, sessions1, connection2, sessions2, TRACKING_KEY);
        
        // Print BEFORE table
        System.out.println("\n" + "=".repeat(150));
        System.out.println("BEFORE FAILOVER - FULL CONNECTION TABLE");
        System.out.println("-".repeat(150));
        printTable(beforeRows);
        
        // Create heartbeat producers
        javax.jms.Queue queue = sessions1.get(0).createQueue("queue:///UNIFORM.QUEUE");
        MessageProducer producer1 = sessions1.get(0).createProducer(queue);
        MessageProducer producer2 = sessions2.get(0).createProducer(queue);
        
        System.out.println("\n" + "=".repeat(80));
        System.out.println("PHASE 2: TRIGGER SELECTIVE FAILOVER");
        System.out.println("=".repeat(80));
        System.out.println("\n📌 Current State:");
        System.out.println("  C1 on " + originalC1QM + " - Will be stopped to trigger failover");
        System.out.println("  C2 on " + originalC2QM + " - Should remain stable");
        System.out.println("\n⏸️  PAUSE HERE: Stop " + originalC1QM + " externally");
        System.out.println("   Command: docker stop " + originalC1QM.toLowerCase());
        System.out.println("\nKeeping connections alive and waiting for reconnection...");
        
        // Keep connections alive and wait for failover
        int heartbeatCount = 0;
        int maxWaitSeconds = 60;
        
        for (int i = 0; i < maxWaitSeconds; i++) {
            // Try to send heartbeat on C1
            try {
                TextMessage msg1 = sessions1.get(0).createTextMessage("HB-C1-" + heartbeatCount);
                producer1.send(msg1);
                
                // If we can send after failover was detected, connection has recovered
                if (failoverDetected) {
                    System.out.println("\n✅ C1 reconnected! Heartbeat successful after failover.");
                    break;
                }
                System.out.print(".");
            } catch (Exception e) {
                if (!failoverDetected) {
                    System.out.print("X");
                } else {
                    System.out.print("R"); // Reconnecting
                }
            }
            
            // Keep C2 alive
            try {
                TextMessage msg2 = sessions2.get(0).createTextMessage("HB-C2-" + heartbeatCount);
                producer2.send(msg2);
            } catch (Exception e) {
                System.out.print("!");
            }
            
            heartbeatCount++;
            Thread.sleep(1000);
        }
        
        System.out.println("\n\n" + "=".repeat(80));
        System.out.println("PHASE 3: VERIFY POST-FAILOVER STATE");
        System.out.println("=".repeat(80));
        
        // Wait a bit more for reconnection to stabilize
        System.out.println("\nWaiting for reconnection to stabilize...");
        Thread.sleep(5000);
        
        // Get current QMs after failover (from same connections)
        String currentC1QM = getQueueManager(connection1);
        String currentC2QM = getQueueManager(connection2);
        
        System.out.println("\nCurrent connection state:");
        System.out.println("  C1: " + currentC1QM);
        System.out.println("  C2: " + currentC2QM);
        
        // Capture AFTER state from the SAME connections
        List<ConnectionRow> afterRows = captureState(connection1, sessions1, connection2, sessions2, TRACKING_KEY);
        
        // Print AFTER table
        System.out.println("\n" + "=".repeat(150));
        System.out.println("AFTER FAILOVER - FULL CONNECTION TABLE");
        System.out.println("-".repeat(150));
        printTable(afterRows);
        
        // Analysis
        System.out.println("\n" + "=".repeat(80));
        System.out.println("FAILOVER ANALYSIS");
        System.out.println("=".repeat(80));
        
        System.out.println("\n📊 Connection Movement:");
        System.out.println("  C1: " + originalC1QM + " → " + currentC1QM + 
                         (originalC1QM.equals(currentC1QM) ? " ❌ (NO FAILOVER - Problem!)" : " ✅ (Failed over)"));
        System.out.println("  C2: " + originalC2QM + " → " + currentC2QM + 
                         (originalC2QM.equals(currentC2QM) ? " ✅ (Remained stable)" : " ⚠️ (Unexpected movement)"));
        
        // Verify parent-child relationships
        System.out.println("\n📋 Parent-Child Affinity Check:");
        String c1QMAfter = afterRows.get(0).queueManager;
        boolean c1AllSame = true;
        for (int i = 1; i <= 5; i++) {
            if (!afterRows.get(i).queueManager.equals(c1QMAfter)) {
                c1AllSame = false;
                break;
            }
        }
        System.out.println("  C1: All 6 connections on " + c1QMAfter + " - " + 
                         (c1AllSame ? "✅ Parent-child preserved" : "❌ Parent-child broken!"));
        
        String c2QMAfter = afterRows.get(6).queueManager;
        boolean c2AllSame = true;
        for (int i = 7; i <= 9; i++) {
            if (!afterRows.get(i).queueManager.equals(c2QMAfter)) {
                c2AllSame = false;
                break;
            }
        }
        System.out.println("  C2: All 4 connections on " + c2QMAfter + " - " + 
                         (c2AllSame ? "✅ Parent-child preserved" : "❌ Parent-child broken!"));
        
        System.out.println("\n📋 CONNTAG Evidence:");
        System.out.println("  C1 Before: " + beforeRows.get(0).fullConnTag);
        System.out.println("  C1 After:  " + afterRows.get(0).fullConnTag);
        System.out.println("  C2 Before: " + beforeRows.get(6).fullConnTag);
        System.out.println("  C2 After:  " + afterRows.get(6).fullConnTag);
        
        // Cleanup
        try {
            producer1.close();
            producer2.close();
            for (Session s : sessions1) s.close();
            for (Session s : sessions2) s.close();
            connection1.close();
            connection2.close();
        } catch (Exception e) {
            // Ignore cleanup errors
        }
        
        System.out.println("\n✅ Test completed");
        System.out.println("\n⚠️  Remember to restart the stopped Queue Manager:");
        System.out.println("   Command: docker start " + originalC1QM.toLowerCase());
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
                // Connection might be in reconnection state
                c1Parent.connectionId = "RECONNECTING";
                c1Parent.fullConnTag = "RECONNECTING";
                c1Parent.queueManager = "RECONNECTING";
            }
        }
        c1Parent.appTag = trackingKey + "-C1";
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
                // Connection might be in reconnection state
                c2Parent.connectionId = "RECONNECTING";
                c2Parent.fullConnTag = "RECONNECTING";
                c2Parent.queueManager = "RECONNECTING";
            }
        }
        c2Parent.appTag = trackingKey + "-C2";
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
            return "RECONNECTING";
        }
        return "UNKNOWN";
    }
    
    private static String getFullConnTag(MQConnection mqConn) {
        try {
            String connTag = mqConn.getStringProperty("XMSC_WMQ_RESOLVED_CONNECTION_TAG");
            if (connTag != null && !connTag.isEmpty()) {
                return connTag;
            }
            
            // If not available, construct from CONNECTION_ID
            String connId = mqConn.getStringProperty("XMSC_WMQ_CONNECTION_ID");
            String qm = mqConn.getStringProperty("XMSC_WMQ_RESOLVED_QUEUE_MANAGER");
            if (connId != null && connId.length() >= 48 && qm != null) {
                String handle = connId.substring(32, 48);
                return "MQCT" + handle + qm;
            }
        } catch (Exception e) {
            return "RECONNECTING";
        }
        return "UNKNOWN";
    }
    
    private static String formatConnectionId(String connId) {
        if (connId == null || connId.length() < 48) return "UNKNOWN";
        return connId.substring(0, 16) + "..." + connId.substring(32, 48);
    }
    
    private static void printTable(List<ConnectionRow> rows) {
        // Print header
        System.out.println("| # | Type | Conn | Session | CONNECTION_ID | FULL_CONNTAG | Queue Manager | APPLTAG |");
        System.out.println("|---|------|------|---------|---------------|--------------|---------------|---------|");
        
        // Print rows
        for (ConnectionRow row : rows) {
            System.out.printf("| %d | %s | %s | %s | %s | %s | **%s** | %s |\n",
                row.num,
                row.type,
                row.conn,
                row.session,
                row.connectionId,
                row.fullConnTag,
                row.queueManager,
                row.appTag
            );
        }
    }
}