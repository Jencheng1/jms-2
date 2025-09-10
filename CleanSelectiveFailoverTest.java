import com.ibm.msg.client.jms.JmsConnectionFactory;
import com.ibm.msg.client.jms.JmsFactoryFactory;
import com.ibm.msg.client.wmq.WMQConstants;
import com.ibm.mq.jms.MQConnection;
import javax.jms.*;
import java.util.*;
import java.text.SimpleDateFormat;

public class CleanSelectiveFailoverTest {
    
    private static final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
    private static JmsConnectionFactory factory;
    
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
        System.out.println("   SELECTIVE FAILOVER TEST - FULL CONNTAG DISPLAY");
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
        connection1.start();
        
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TRACKING_KEY + "-C2");
        Connection connection2 = factory.createConnection();
        connection2.start();
        
        // Get initial QMs
        String c1QM = getQueueManager(connection1);
        String c2QM = getQueueManager(connection2);
        
        System.out.println("Initial connection distribution:");
        System.out.println("  C1: " + c1QM);
        System.out.println("  C2: " + c2QM);
        
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
        System.out.println("  C1 on " + c1QM + " - Will be stopped to trigger failover");
        System.out.println("  C2 on " + c2QM + " - Should remain stable");
        System.out.println("\n⏸️  PAUSE HERE: Stop " + c1QM + " externally");
        System.out.println("   Command: docker stop " + c1QM.toLowerCase());
        System.out.println("\nKeeping connections alive for 60 seconds...");
        
        // Keep connections alive
        for (int i = 0; i < 30; i++) {
            try {
                TextMessage msg1 = sessions1.get(0).createTextMessage("HB-C1-" + i);
                producer1.send(msg1);
            } catch (Exception e) {
                // Expected for C1 during failover
            }
            
            try {
                TextMessage msg2 = sessions2.get(0).createTextMessage("HB-C2-" + i);
                producer2.send(msg2);
                System.out.print(".");
            } catch (Exception e) {
                System.out.print("!");
            }
            
            Thread.sleep(2000);
        }
        
        System.out.println("\n\n" + "=".repeat(80));
        System.out.println("PHASE 3: CAPTURE POST-FAILOVER STATE");
        System.out.println("=".repeat(80));
        
        // Close and recreate to get fresh properties
        System.out.println("\nClosing original connections...");
        try {
            producer1.close();
            producer2.close();
            for (Session s : sessions1) s.close();
            for (Session s : sessions2) s.close();
            connection1.close();
            connection2.close();
        } catch (Exception e) {}
        
        Thread.sleep(2000);
        
        System.out.println("Creating new connections to verify state...");
        
        // Create new connections
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, "NEW" + timestamp.substring(timestamp.length()-10) + "-C1");
        Connection newConnection1 = factory.createConnection();
        newConnection1.start();
        
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, "NEW" + timestamp.substring(timestamp.length()-10) + "-C2");
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
        List<ConnectionRow> afterRows = captureState(newConnection1, newSessions1, newConnection2, newSessions2, "NEW" + timestamp.substring(timestamp.length()-10));
        
        // Print AFTER table
        System.out.println("\n" + "=".repeat(150));
        System.out.println("AFTER FAILOVER - FULL CONNECTION TABLE");
        System.out.println("-".repeat(150));
        printTable(afterRows);
        
        // Analysis
        System.out.println("\n" + "=".repeat(80));
        System.out.println("FAILOVER ANALYSIS");
        System.out.println("=".repeat(80));
        
        String newC1QM = afterRows.get(0).queueManager;
        String newC2QM = afterRows.get(6).queueManager;
        
        System.out.println("\n📊 Connection Movement:");
        System.out.println("  C1: " + c1QM + " → " + newC1QM + (c1QM.equals(newC1QM) ? " (No change)" : " ✅ (Moved)"));
        System.out.println("  C2: " + c2QM + " → " + newC2QM + (c2QM.equals(newC2QM) ? " ✅ (Stayed)" : " (Moved)"));
        
        System.out.println("\n📋 CONNTAG Evidence:");
        System.out.println("  C1 Before: " + beforeRows.get(0).fullConnTag);
        System.out.println("  C1 After:  " + afterRows.get(0).fullConnTag);
        System.out.println("  C2 Before: " + beforeRows.get(6).fullConnTag);
        System.out.println("  C2 After:  " + afterRows.get(6).fullConnTag);
        
        // Cleanup
        for (Session s : newSessions1) s.close();
        for (Session s : newSessions2) s.close();
        newConnection1.close();
        newConnection2.close();
        
        System.out.println("\n✅ Test completed");
        System.out.println("\n⚠️  Remember to restart the stopped Queue Manager");
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
            c1Parent.connectionId = formatConnectionId(mqConn.getStringProperty("XMSC_WMQ_CONNECTION_ID"));
            c1Parent.fullConnTag = getFullConnTag(mqConn);
            c1Parent.queueManager = mqConn.getStringProperty("XMSC_WMQ_RESOLVED_QUEUE_MANAGER");
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
            c2Parent.connectionId = formatConnectionId(mqConn.getStringProperty("XMSC_WMQ_CONNECTION_ID"));
            c2Parent.fullConnTag = getFullConnTag(mqConn);
            c2Parent.queueManager = mqConn.getStringProperty("XMSC_WMQ_RESOLVED_QUEUE_MANAGER");
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
        } catch (Exception e) {}
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
        } catch (Exception e) {}
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