import com.ibm.msg.client.jms.JmsConnectionFactory;
import com.ibm.msg.client.jms.JmsFactoryFactory;
import com.ibm.msg.client.wmq.WMQConstants;
import com.ibm.mq.jms.MQConnection;
import javax.jms.*;
import java.util.*;
import java.text.SimpleDateFormat;
import java.io.*;

public class AutoFailoverProof {
    
    private static final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
    private static volatile boolean reconnecting = false;
    
    public static void main(String[] args) throws Exception {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String TRACKING_KEY = "AUTO-" + timestamp;
        
        System.out.println("================================================================================");
        System.out.println("   AUTOMATED FAILOVER PROOF - ALL 6 CONNECTIONS MOVE TOGETHER");
        System.out.println("================================================================================");
        System.out.println("Tracking Key: " + TRACKING_KEY);
        System.out.println("");
        
        // Create connection factory
        JmsFactoryFactory ff = JmsFactoryFactory.getInstance(WMQConstants.WMQ_PROVIDER);
        JmsConnectionFactory factory = ff.createConnectionFactory();
        
        // Configure for uniform cluster with reconnect
        factory.setStringProperty(WMQConstants.WMQ_CCDTURL, "file:///workspace/ccdt/ccdt.json");
        factory.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
        factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, WMQConstants.WMQ_CLIENT_RECONNECT);
        factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_TIMEOUT, 300);
        factory.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
        factory.setStringProperty(WMQConstants.USERID, "app");
        factory.setStringProperty(WMQConstants.PASSWORD, "passw0rd");
        factory.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "*");
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TRACKING_KEY);
        
        System.out.println("=== PHASE 1: CREATE INITIAL CONNECTIONS ===\n");
        Connection connection = factory.createConnection();
        
        // Set exception listener
        connection.setExceptionListener(new ExceptionListener() {
            public void onException(JMSException e) {
                System.out.println("\n[" + sdf.format(new Date()) + "] Exception: " + e.getMessage());
                if (e.getMessage() != null && e.getMessage().contains("reconnect")) {
                    reconnecting = true;
                    System.out.println("   => Reconnection detected!");
                }
            }
        });
        
        connection.start();
        
        // Get initial QM
        String initialQM = "UNKNOWN";
        String initialConnId = "UNKNOWN";
        if (connection instanceof MQConnection) {
            MQConnection mqConn = (MQConnection) connection;
            try {
                initialQM = mqConn.getStringProperty("XMSC_WMQ_RESOLVED_QUEUE_MANAGER");
                initialConnId = mqConn.getStringProperty("XMSC_WMQ_CONNECTION_ID");
                if (initialConnId != null && initialConnId.length() > 48) {
                    initialConnId = initialConnId.substring(0, 48);
                }
            } catch (JMSException e) {}
        }
        
        System.out.println("Parent Connection established:");
        System.out.println("  Queue Manager: " + initialQM);
        System.out.println("  CONNECTION_ID: " + initialConnId);
        
        // Create 5 sessions
        System.out.println("\nCreating 5 child sessions...");
        List<Session> sessions = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            sessions.add(session);
            System.out.println("  Session " + i + " created");
        }
        
        // Create producer for heartbeat
        javax.jms.Queue queue = sessions.get(0).createQueue("queue:///UNIFORM.QUEUE");
        MessageProducer producer = sessions.get(0).createProducer(queue);
        
        // Check initial MQSC state
        System.out.println("\n=== INITIAL MQSC STATE ===");
        Map<String, Integer> initialCounts = checkMQSCConnections(TRACKING_KEY);
        printMQSCCounts(initialCounts);
        
        // Identify which QM to stop
        String qmToStop = null;
        for (Map.Entry<String, Integer> entry : initialCounts.entrySet()) {
            if (entry.getValue() == 6) {
                qmToStop = entry.getKey();
                break;
            }
        }
        
        if (qmToStop == null) {
            System.out.println("\n❌ Could not identify QM with 6 connections");
            return;
        }
        
        System.out.println("\n" + "=".repeat(80));
        System.out.println("PHASE 2: TRIGGERING FAILOVER");
        System.out.println("=".repeat(80));
        System.out.println("\nStopping " + qmToStop.toUpperCase() + " (has all 6 connections)...");
        
        // Stop the QM
        try {
            Process p = Runtime.getRuntime().exec("docker stop " + qmToStop);
            p.waitFor();
            System.out.println("✓ " + qmToStop.toUpperCase() + " stopped");
        } catch (Exception e) {
            System.out.println("Error stopping QM: " + e.getMessage());
        }
        
        // Send heartbeat messages during failover
        System.out.println("\nSending heartbeats and waiting for reconnection...");
        int heartbeatCount = 0;
        long startTime = System.currentTimeMillis();
        
        while (System.currentTimeMillis() - startTime < 60000) { // 60 second timeout
            try {
                TextMessage msg = sessions.get(0).createTextMessage("Heartbeat-" + heartbeatCount++);
                producer.send(msg);
                System.out.print(".");
                Thread.sleep(2000);
                
                // Check if reconnected
                if (reconnecting) {
                    Thread.sleep(10000); // Give time to fully reconnect
                    break;
                }
            } catch (Exception e) {
                if (!reconnecting) {
                    System.out.print("!");
                }
            }
        }
        
        System.out.println("\n\n=== PHASE 3: POST-FAILOVER VERIFICATION ===");
        
        // Create new connection to get actual QM
        System.out.println("\nCreating new connection to determine actual Queue Manager...");
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TRACKING_KEY + "-TEST");
        Connection testConnection = factory.createConnection();
        testConnection.start();
        
        String actualQM = "UNKNOWN";
        String newConnId = "UNKNOWN";
        if (testConnection instanceof MQConnection) {
            MQConnection mqConn = (MQConnection) testConnection;
            try {
                actualQM = mqConn.getStringProperty("XMSC_WMQ_RESOLVED_QUEUE_MANAGER");
                newConnId = mqConn.getStringProperty("XMSC_WMQ_CONNECTION_ID");
                if (newConnId != null && newConnId.length() > 48) {
                    newConnId = newConnId.substring(0, 48);
                }
            } catch (JMSException e) {}
        }
        
        System.out.println("New test connection shows:");
        System.out.println("  Queue Manager: " + actualQM);
        System.out.println("  CONNECTION_ID: " + newConnId);
        
        testConnection.close();
        
        // Check MQSC state after failover
        System.out.println("\n=== POST-FAILOVER MQSC STATE ===");
        Map<String, Integer> afterCounts = checkMQSCConnections(TRACKING_KEY);
        printMQSCCounts(afterCounts);
        
        // Analyze results
        System.out.println("\n" + "=".repeat(80));
        System.out.println("FAILOVER ANALYSIS");
        System.out.println("=".repeat(80));
        
        System.out.println("\n📊 Connection Movement:");
        System.out.println("  BEFORE:");
        for (Map.Entry<String, Integer> entry : initialCounts.entrySet()) {
            if (entry.getValue() > 0) {
                System.out.println("    " + entry.getKey().toUpperCase() + ": " + entry.getValue() + " connections");
            }
        }
        
        System.out.println("\n  AFTER:");
        for (Map.Entry<String, Integer> entry : afterCounts.entrySet()) {
            if (entry.getValue() > 0) {
                System.out.println("    " + entry.getKey().toUpperCase() + ": " + entry.getValue() + " connections ✓");
            }
        }
        
        // Find where connections moved to
        String newQM = null;
        for (Map.Entry<String, Integer> entry : afterCounts.entrySet()) {
            if (entry.getValue() >= 6 && !entry.getKey().equals(qmToStop)) {
                newQM = entry.getKey();
                break;
            }
        }
        
        if (newQM != null) {
            System.out.println("\n✅ SUCCESS! Failover proven:");
            System.out.println("  1. All 6 connections moved from " + qmToStop.toUpperCase() + " to " + newQM.toUpperCase());
            System.out.println("  2. Parent-child affinity maintained (all on same QM)");
            System.out.println("  3. Automatic reconnection successful");
            System.out.println("  4. APPLTAG preserved: " + TRACKING_KEY);
        } else {
            System.out.println("\n⚠️  Unable to determine new QM from MQSC counts");
        }
        
        // Cleanup
        producer.close();
        for (Session s : sessions) {
            try { s.close(); } catch (Exception e) {}
        }
        connection.close();
        
        // Restart stopped QM
        System.out.println("\nRestarting " + qmToStop + "...");
        try {
            Runtime.getRuntime().exec("docker start " + qmToStop);
            System.out.println("✓ " + qmToStop.toUpperCase() + " restarted");
        } catch (Exception e) {
            System.out.println("Error restarting QM: " + e.getMessage());
        }
        
        System.out.println("\n✅ Test completed");
    }
    
    private static Map<String, Integer> checkMQSCConnections(String appTag) {
        Map<String, Integer> counts = new HashMap<>();
        counts.put("qm1", 0);
        counts.put("qm2", 0);
        counts.put("qm3", 0);
        
        for (String qm : counts.keySet()) {
            try {
                String cmd = String.format(
                    "docker exec %s bash -c \"echo 'DIS CONN(*) WHERE(APPLTAG LK %s*)' | runmqsc %s 2>/dev/null\" | grep -c AMQ8276I",
                    qm, appTag, qm.toUpperCase()
                );
                
                Process p = Runtime.getRuntime().exec(new String[]{"bash", "-c", cmd});
                Scanner s = new Scanner(p.getInputStream());
                if (s.hasNext()) {
                    String result = s.nextLine().trim();
                    try {
                        counts.put(qm, Integer.parseInt(result));
                    } catch (NumberFormatException e) {
                        // QM might be stopped
                    }
                }
                s.close();
            } catch (Exception e) {
                // QM might be stopped
            }
        }
        
        return counts;
    }
    
    private static void printMQSCCounts(Map<String, Integer> counts) {
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            System.out.println("  " + entry.getKey().toUpperCase() + ": " + entry.getValue() + " connections");
        }
    }
}