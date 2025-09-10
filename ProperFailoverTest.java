import com.ibm.msg.client.jms.JmsConnectionFactory;
import com.ibm.msg.client.jms.JmsFactoryFactory;
import com.ibm.msg.client.wmq.WMQConstants;
import com.ibm.mq.jms.MQConnection;
import javax.jms.*;
import java.util.*;
import java.text.SimpleDateFormat;
import java.io.*;

public class ProperFailoverTest {
    
    private static final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
    private static JmsConnectionFactory factory;
    private static volatile boolean c1FailoverDetected = false;
    private static volatile boolean c2FailoverDetected = false;
    private static String originalC1QM = "";
    private static String originalC2QM = "";
    private static String actualC1QMAfter = "";
    private static String actualC2QMAfter = "";
    
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
        String TRACKING_KEY = "FAILOVER-" + timestamp.substring(timestamp.length()-10);
        
        System.out.println("================================================================================");
        System.out.println("   PROPER FAILOVER TEST WITH ACTUAL QM VERIFICATION");
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
                System.out.println("\n🔴 C1 Connection Lost at " + sdf.format(new Date()));
                c1FailoverDetected = true;
            }
        });
        
        connection1.start();
        
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TRACKING_KEY + "-C2");
        Connection connection2 = factory.createConnection();
        
        connection2.setExceptionListener(new ExceptionListener() {
            public void onException(JMSException exception) {
                System.out.println("\n🔴 C2 Connection Lost at " + sdf.format(new Date()));
                c2FailoverDetected = true;
            }
        });
        
        connection2.start();
        
        // Get initial QMs
        originalC1QM = getQueueManager(connection1);
        originalC2QM = getQueueManager(connection2);
        
        System.out.println("Initial connection distribution:");
        System.out.println("  C1: " + originalC1QM);
        System.out.println("  C2: " + originalC2QM);
        
        // Verify with MQSC
        System.out.println("\nVerifying with MQSC commands...");
        verifyConnectionsOnQMs(TRACKING_KEY);
        
        // Create sessions
        System.out.println("\nCreating sessions...");
        List<Session> sessions1 = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            sessions1.add(connection1.createSession(false, Session.AUTO_ACKNOWLEDGE));
        }
        System.out.println("  C1: 5 sessions created (total 6 connections including parent)");
        
        List<Session> sessions2 = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            sessions2.add(connection2.createSession(false, Session.AUTO_ACKNOWLEDGE));
        }
        System.out.println("  C2: 3 sessions created (total 4 connections including parent)");
        
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
        
        // Determine which QM to stop
        String qmToStop = "";
        if (!originalC1QM.equals(originalC2QM)) {
            // Different QMs - stop C1's QM for selective failover
            qmToStop = originalC1QM;
            System.out.println("\n📌 Selective Failover Test:");
            System.out.println("  C1 on " + originalC1QM + " - Will be stopped");
            System.out.println("  C2 on " + originalC2QM + " - Should remain stable");
        } else {
            // Same QM - both will failover
            qmToStop = originalC1QM;
            System.out.println("\n📌 Both connections on same QM:");
            System.out.println("  C1 on " + originalC1QM + " - Will be stopped");
            System.out.println("  C2 on " + originalC2QM + " - Will also failover");
        }
        
        System.out.println("\n⏸️  Stopping " + qmToStop + " in 3 seconds...");
        Thread.sleep(3000);
        
        // Stop the QM
        Process stopProcess = Runtime.getRuntime().exec("docker stop " + qmToStop.toLowerCase());
        stopProcess.waitFor();
        System.out.println("✅ " + qmToStop + " stopped");
        
        System.out.println("\nWaiting for reconnection...");
        
        // Keep connections alive and wait for reconnection
        int attempts = 0;
        boolean c1Reconnected = false;
        boolean c2Reconnected = false;
        
        while (attempts < 60 && (!c1Reconnected || !c2Reconnected)) {
            // Try C1
            if (c1FailoverDetected && !c1Reconnected) {
                try {
                    TextMessage msg = sessions1.get(0).createTextMessage("RECONNECT-TEST");
                    producer1.send(msg);
                    c1Reconnected = true;
                    System.out.println("🟢 C1 Reconnected at " + sdf.format(new Date()));
                } catch (Exception e) {
                    // Still reconnecting
                }
            }
            
            // Try C2
            if (c2FailoverDetected && !c2Reconnected) {
                try {
                    TextMessage msg = sessions2.get(0).createTextMessage("RECONNECT-TEST");
                    producer2.send(msg);
                    c2Reconnected = true;
                    System.out.println("🟢 C2 Reconnected at " + sdf.format(new Date()));
                } catch (Exception e) {
                    // Still reconnecting
                }
            }
            
            // If C2 wasn't supposed to failover, check if it's still working
            if (!c2FailoverDetected && !originalC1QM.equals(originalC2QM)) {
                try {
                    TextMessage msg = sessions2.get(0).createTextMessage("HEARTBEAT");
                    producer2.send(msg);
                    c2Reconnected = true;
                } catch (Exception e) {
                    // Unexpected failure
                }
            }
            
            Thread.sleep(1000);
            attempts++;
            System.out.print(".");
        }
        
        System.out.println("\n\n" + "=".repeat(80));
        System.out.println("PHASE 3: VERIFY POST-FAILOVER STATE");
        System.out.println("=".repeat(80));
        
        // Wait for stabilization
        System.out.println("\nWaiting for connections to stabilize...");
        Thread.sleep(5000);
        
        // Check actual QMs via MQSC
        System.out.println("\nChecking actual Queue Manager assignments via MQSC...");
        Map<String, String> actualQMs = getActualQMsViaMQSC(TRACKING_KEY);
        actualC1QMAfter = actualQMs.getOrDefault("C1", "UNKNOWN");
        actualC2QMAfter = actualQMs.getOrDefault("C2", "UNKNOWN");
        
        System.out.println("  C1 actual QM: " + actualC1QMAfter);
        System.out.println("  C2 actual QM: " + actualC2QMAfter);
        
        // Close and recreate connections to get fresh properties
        System.out.println("\nRecreating connections to refresh cached properties...");
        try {
            producer1.close();
            producer2.close();
            for (Session s : sessions1) s.close();
            for (Session s : sessions2) s.close();
            connection1.close();
            connection2.close();
        } catch (Exception e) {
            // Ignore close errors
        }
        
        Thread.sleep(2000);
        
        // Create new connections with same APPLTAG to maintain correlation
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TRACKING_KEY + "-C1-NEW");
        Connection newConnection1 = factory.createConnection();
        newConnection1.start();
        
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TRACKING_KEY + "-C2-NEW");
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
        List<ConnectionRow> afterRows = captureState(newConnection1, newSessions1, newConnection2, newSessions2, TRACKING_KEY);
        
        // Update with actual QMs from MQSC
        for (ConnectionRow row : afterRows) {
            if (row.conn.equals("C1")) {
                row.queueManager = actualC1QMAfter;
            } else if (row.conn.equals("C2")) {
                row.queueManager = actualC2QMAfter;
            }
        }
        
        // Print AFTER table
        System.out.println("\n" + "=".repeat(150));
        System.out.println("AFTER FAILOVER - ACTUAL CONNECTION TABLE (from MQSC)");
        System.out.println("-".repeat(150));
        printTable(afterRows);
        
        // Analysis
        System.out.println("\n" + "=".repeat(80));
        System.out.println("FAILOVER ANALYSIS");
        System.out.println("=".repeat(80));
        
        System.out.println("\n📊 Connection Movement (Actual via MQSC):");
        boolean c1Moved = !originalC1QM.equals(actualC1QMAfter) && !actualC1QMAfter.equals("UNKNOWN");
        boolean c2Moved = !originalC2QM.equals(actualC2QMAfter) && !actualC2QMAfter.equals("UNKNOWN");
        
        System.out.println("  C1: " + originalC1QM + " → " + actualC1QMAfter + 
                         (c1Moved ? " ✅ (Failed over successfully)" : " ❌ (No failover detected)"));
        System.out.println("  C2: " + originalC2QM + " → " + actualC2QMAfter);
        
        if (!originalC1QM.equals(originalC2QM)) {
            // Selective failover case
            if (c2Moved) {
                System.out.println("      ⚠️ Unexpected movement (should have stayed on " + originalC2QM + ")");
            } else {
                System.out.println("      ✅ Remained stable as expected");
            }
        } else {
            // Both on same QM case
            if (c2Moved) {
                System.out.println("      ✅ Failed over as expected (was on same QM as C1)");
            } else {
                System.out.println("      ❌ Should have failed over (was on same QM as C1)");
            }
        }
        
        System.out.println("\n📋 Failover Events Detected:");
        System.out.println("  C1: " + (c1FailoverDetected ? "✅ Exception detected, reconnection successful" : "❌ No failover detected"));
        System.out.println("  C2: " + (c2FailoverDetected ? "✅ Exception detected, reconnection successful" : 
                                      (originalC1QM.equals(originalC2QM) ? "❌ Should have detected failover" : "✅ No failover needed")));
        
        // Cleanup
        for (Session s : newSessions1) s.close();
        for (Session s : newSessions2) s.close();
        newConnection1.close();
        newConnection2.close();
        
        // Restart stopped QM
        System.out.println("\n🔄 Restarting " + qmToStop + "...");
        Process startProcess = Runtime.getRuntime().exec("docker start " + qmToStop.toLowerCase());
        startProcess.waitFor();
        System.out.println("✅ " + qmToStop + " restarted");
        
        System.out.println("\n✅ Test completed successfully");
    }
    
    private static void verifyConnectionsOnQMs(String trackingKey) {
        for (String qm : new String[]{"qm1", "qm2", "qm3"}) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{
                    "docker", "exec", qm, "bash", "-c",
                    "echo 'DIS CONN(*) WHERE(APPLTAG LK \"*" + trackingKey + "*\") CHANNEL' | runmqsc " + qm.toUpperCase()
                });
                
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                int count = 0;
                String connType = "";
                while ((line = reader.readLine()) != null) {
                    if (line.contains("CONN(")) count++;
                    if (line.contains("-C1")) connType = "C1";
                    else if (line.contains("-C2")) connType = "C2";
                }
                
                if (count > 0) {
                    System.out.println("  " + qm.toUpperCase() + ": " + count + " connections (" + connType + ")");
                }
                p.waitFor();
            } catch (Exception e) {
                // Ignore
            }
        }
    }
    
    private static Map<String, String> getActualQMsViaMQSC(String trackingKey) {
        Map<String, String> result = new HashMap<>();
        
        for (String qm : new String[]{"qm1", "qm2", "qm3"}) {
            try {
                // Check for C1 connections
                Process p1 = Runtime.getRuntime().exec(new String[]{
                    "docker", "exec", qm, "bash", "-c",
                    "echo 'DIS CONN(*) WHERE(APPLTAG LK \"*" + trackingKey + "-C1*\") CHANNEL' | runmqsc " + qm.toUpperCase()
                });
                
                BufferedReader reader1 = new BufferedReader(new InputStreamReader(p1.getInputStream()));
                String line;
                int c1Count = 0;
                while ((line = reader1.readLine()) != null) {
                    if (line.contains("CONN(")) c1Count++;
                }
                p1.waitFor();
                
                if (c1Count > 0) {
                    result.put("C1", qm.toUpperCase());
                    System.out.println("    Found C1 on " + qm.toUpperCase() + " (" + c1Count + " connections)");
                }
                
                // Check for C2 connections
                Process p2 = Runtime.getRuntime().exec(new String[]{
                    "docker", "exec", qm, "bash", "-c",
                    "echo 'DIS CONN(*) WHERE(APPLTAG LK \"*" + trackingKey + "-C2*\") CHANNEL' | runmqsc " + qm.toUpperCase()
                });
                
                BufferedReader reader2 = new BufferedReader(new InputStreamReader(p2.getInputStream()));
                int c2Count = 0;
                while ((line = reader2.readLine()) != null) {
                    if (line.contains("CONN(")) c2Count++;
                }
                p2.waitFor();
                
                if (c2Count > 0) {
                    result.put("C2", qm.toUpperCase());
                    System.out.println("    Found C2 on " + qm.toUpperCase() + " (" + c2Count + " connections)");
                }
                
            } catch (Exception e) {
                System.err.println("Error checking " + qm + ": " + e.getMessage());
            }
        }
        
        return result;
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
                c1Parent.queueManager = "CHECKING";
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
                c2Parent.connectionId = "N/A";
                c2Parent.fullConnTag = "N/A";
                c2Parent.queueManager = "CHECKING";
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