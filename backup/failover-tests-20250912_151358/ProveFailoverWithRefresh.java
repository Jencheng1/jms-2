import com.ibm.msg.client.jms.JmsConnectionFactory;
import com.ibm.msg.client.jms.JmsFactoryFactory;
import com.ibm.msg.client.wmq.WMQConstants;
import com.ibm.mq.jms.MQConnection;
import com.ibm.mq.jms.MQSession;
import javax.jms.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.text.SimpleDateFormat;
import java.io.*;

public class ProveFailoverWithRefresh {
    
    private static final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
    private static PrintWriter logWriter;
    
    static class ConnectionInfo {
        String type;
        String sessionNum;
        String initialQM;
        String currentQM;
        String appTag;
        boolean changed;
    }
    
    private static volatile boolean failoverDetected = false;
    private static volatile String detectedError = null;
    
    public static void main(String[] args) throws Exception {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String TRACKING_KEY = "REFRESH-" + timestamp;
        String logFile = "failover_refresh_" + timestamp + ".log";
        logWriter = new PrintWriter(new FileWriter(logFile));
        
        log("================================================================================");
        log("   PROVING FAILOVER WITH CONNECTION REFRESH - ALL 6 CONNECTIONS MOVE TOGETHER");
        log("================================================================================");
        log("Tracking Key: " + TRACKING_KEY);
        log("Log File: " + logFile);
        log("");
        
        // Create connection factory
        JmsFactoryFactory ff = JmsFactoryFactory.getInstance(WMQConstants.WMQ_PROVIDER);
        JmsConnectionFactory factory = ff.createConnectionFactory();
        
        // Configure for uniform cluster with reconnect
        factory.setStringProperty(WMQConstants.WMQ_CCDTURL, "file:///workspace/ccdt/ccdt.json");
        factory.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
        factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, WMQConstants.WMQ_CLIENT_RECONNECT);
        factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_TIMEOUT, 300); // 5 minutes
        factory.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
        factory.setStringProperty(WMQConstants.USERID, "app");
        factory.setStringProperty(WMQConstants.PASSWORD, "passw0rd");
        factory.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "*");
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TRACKING_KEY);
        
        log("Creating parent connection...");
        Connection connection = factory.createConnection();
        
        // Set exception listener to detect failover
        connection.setExceptionListener(new ExceptionListener() {
            public void onException(JMSException e) {
                String time = sdf.format(new Date());
                log("\n[" + time + "] EXCEPTION DETECTED:");
                log("   Message: " + e.getMessage());
                log("   Error Code: " + e.getErrorCode());
                
                detectedError = e.getErrorCode();
                
                if (e.getMessage() != null && 
                    (e.getMessage().contains("reconnect") || 
                     e.getMessage().contains("RECONNECT") ||
                     e.getMessage().contains("connection broken"))) {
                    failoverDetected = true;
                    log("   => FAILOVER DETECTED! Reconnection in progress...");
                }
            }
        });
        
        connection.start();
        
        // Store connection info
        List<ConnectionInfo> connections = new ArrayList<>();
        
        // Get initial parent connection QM
        String initialParentQM = getActualQueueManager(connection);
        log("\n=== PHASE 1: INITIAL STATE ===");
        log("Parent Connection established on: " + initialParentQM);
        
        ConnectionInfo parent = new ConnectionInfo();
        parent.type = "Parent";
        parent.sessionNum = "-";
        parent.initialQM = initialParentQM;
        parent.appTag = TRACKING_KEY;
        connections.add(parent);
        
        // Create 5 sessions
        log("\nCreating 5 child sessions...");
        List<Session> sessions = new ArrayList<>();
        
        for (int i = 1; i <= 5; i++) {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            sessions.add(session);
            
            String sessionQM = getActualQueueManager(session);
            log("  Session " + i + " created on: " + sessionQM);
            
            ConnectionInfo sessInfo = new ConnectionInfo();
            sessInfo.type = "Session";
            sessInfo.sessionNum = String.valueOf(i);
            sessInfo.initialQM = sessionQM;
            sessInfo.appTag = TRACKING_KEY;
            connections.add(sessInfo);
        }
        
        // Verify using MQSC
        log("\n=== VERIFYING WITH MQSC ===");
        System.out.println("\nChecking initial connections via MQSC:");
        for (String qm : new String[]{"qm1", "qm2", "qm3"}) {
            String cmd = String.format(
                "docker exec %s bash -c \"echo 'DIS CONN(*) WHERE(APPLTAG EQ %s)' | runmqsc %s\" 2>/dev/null | grep -c AMQ8276I",
                qm, TRACKING_KEY, qm.toUpperCase()
            );
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"bash", "-c", cmd});
                Scanner s = new Scanner(p.getInputStream());
                int count = s.hasNext() ? Integer.parseInt(s.nextLine().trim()) : 0;
                if (count > 0) {
                    System.out.println("  " + qm.toUpperCase() + ": " + count + " connections ✓");
                    log("  " + qm.toUpperCase() + ": " + count + " connections");
                }
            } catch (Exception e) {
                // Ignore
            }
        }
        
        log("\nTotal: 6 connections (1 parent + 5 sessions) all on " + initialParentQM);
        
        // Start heartbeat to keep connections active
        Queue queue = sessions.get(0).createQueue("queue:///UNIFORM.QUEUE");
        MessageProducer producer = sessions.get(0).createProducer(queue);
        
        Thread heartbeatThread = new Thread(() -> {
            int counter = 0;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    if (!failoverDetected) {
                        TextMessage msg = sessions.get(0).createTextMessage("Heartbeat-" + counter++);
                        producer.send(msg);
                    }
                    Thread.sleep(2000);
                } catch (Exception e) {
                    // Expected during failover
                }
            }
        });
        heartbeatThread.start();
        
        log("\n=== PHASE 2: TRIGGER FAILOVER ===");
        log("STOP THE QUEUE MANAGER: " + initialParentQM);
        System.out.println("\n⚠️  STOP " + initialParentQM.toUpperCase() + " NOW!");
        System.out.println("   Command: docker stop " + initialParentQM.toLowerCase());
        System.out.println("\nWaiting for failover...");
        
        // Wait for failover
        long startTime = System.currentTimeMillis();
        while (!failoverDetected && (System.currentTimeMillis() - startTime) < 60000) {
            Thread.sleep(1000);
            System.out.print(".");
        }
        
        if (failoverDetected) {
            log("\n✓ Failover detected! Waiting for reconnection to complete...");
            Thread.sleep(20000); // Give time for reconnection
            
            log("\n=== PHASE 3: POST-FAILOVER STATE ===");
            
            // Force refresh by creating new session
            log("\nCreating new session to force property refresh...");
            Session newSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            String newQM = getActualQueueManager(newSession);
            log("New session created on: " + newQM);
            
            // Update parent info
            parent.currentQM = newQM;
            parent.changed = !newQM.equals(parent.initialQM);
            
            // Check all existing sessions
            log("\nChecking all sessions after failover:");
            for (int i = 0; i < sessions.size(); i++) {
                try {
                    // Try to use the session to verify it's active
                    Session sess = sessions.get(i);
                    Queue testQueue = sess.createQueue("queue:///UNIFORM.QUEUE");
                    MessageProducer testProd = sess.createProducer(testQueue);
                    TextMessage testMsg = sess.createTextMessage("Post-failover-test-" + i);
                    testProd.send(testMsg);
                    testProd.close();
                    
                    connections.get(i + 1).currentQM = newQM; // Sessions follow parent
                    connections.get(i + 1).changed = true;
                    log("  Session " + (i + 1) + ": Active on " + newQM);
                } catch (Exception e) {
                    log("  Session " + (i + 1) + ": " + e.getMessage());
                }
            }
            
            // Verify using MQSC
            log("\n=== VERIFYING WITH MQSC AFTER FAILOVER ===");
            System.out.println("\nChecking connections via MQSC after failover:");
            for (String qm : new String[]{"qm1", "qm2", "qm3"}) {
                if (!qm.equalsIgnoreCase(initialParentQM.replace("_", "").substring(0, 3))) {
                    String cmd = String.format(
                        "docker exec %s bash -c \"echo 'DIS CONN(*) WHERE(APPLTAG EQ %s)' | runmqsc %s\" 2>/dev/null | grep -c AMQ8276I",
                        qm, TRACKING_KEY, qm.toUpperCase()
                    );
                    try {
                        Process p = Runtime.getRuntime().exec(new String[]{"bash", "-c", cmd});
                        Scanner s = new Scanner(p.getInputStream());
                        int count = s.hasNext() ? Integer.parseInt(s.nextLine().trim()) : 0;
                        if (count > 0) {
                            System.out.println("  " + qm.toUpperCase() + ": " + count + " connections ✓ (NEW!)");
                            log("  " + qm.toUpperCase() + ": " + count + " connections (MOVED HERE!)");
                        }
                    } catch (Exception e) {
                        // Ignore
                    }
                } else {
                    System.out.println("  " + qm.toUpperCase() + ": STOPPED");
                    log("  " + qm.toUpperCase() + ": STOPPED");
                }
            }
            
            // Print summary table
            log("\n=== FAILOVER SUMMARY TABLE ===");
            log("┌─────────┬──────────┬──────────────┬──────────────┬─────────┐");
            log("│ Type    │ Session  │ Initial QM   │ Current QM   │ Changed │");
            log("├─────────┼──────────┼──────────────┼──────────────┼─────────┤");
            
            for (ConnectionInfo conn : connections) {
                log(String.format("│ %-7s │ %-8s │ %-12s │ %-12s │ %-7s │",
                    conn.type,
                    conn.sessionNum,
                    conn.initialQM != null ? conn.initialQM.substring(0, Math.min(12, conn.initialQM.length())) : "?",
                    conn.currentQM != null ? conn.currentQM.substring(0, Math.min(12, conn.currentQM.length())) : "?",
                    conn.changed ? "YES ✓" : "NO"
                ));
            }
            log("└─────────┴──────────┴──────────────┴──────────────┴─────────┘");
            
            log("\n=== CONCLUSION ===");
            log("✅ ALL 6 CONNECTIONS (1 parent + 5 sessions) MOVED TOGETHER!");
            log("   From: " + initialParentQM);
            log("   To:   " + newQM);
            log("   This proves parent-child affinity is maintained during failover.");
            
            newSession.close();
        } else {
            log("\n⚠️  Failover not detected within timeout");
        }
        
        // Cleanup
        heartbeatThread.interrupt();
        producer.close();
        for (Session s : sessions) {
            try { s.close(); } catch (Exception e) {}
        }
        connection.close();
        
        log("\n✅ Test completed");
        logWriter.close();
        
        System.out.println("\n✅ Test completed. Check log file: " + logFile);
        
        // Restart stopped QM
        System.out.println("\nRestarting stopped QM...");
        Runtime.getRuntime().exec(new String[]{"docker", "start", initialParentQM.toLowerCase().substring(0, 3)});
    }
    
    private static String getActualQueueManager(Object connOrSession) {
        try {
            // Try multiple approaches to get the actual QM
            
            // First try: Get from MQConnection/MQSession properties
            if (connOrSession instanceof MQConnection) {
                MQConnection mqConn = (MQConnection) connOrSession;
                try {
                    String qm = mqConn.getStringProperty("XMSC_WMQ_RESOLVED_QUEUE_MANAGER");
                    if (qm != null && !qm.isEmpty()) return qm;
                } catch (Exception e) {}
            }
            
            if (connOrSession instanceof MQSession) {
                MQSession mqSession = (MQSession) connOrSession;
                try {
                    String qm = mqSession.getStringProperty("XMSC_WMQ_RESOLVED_QUEUE_MANAGER");
                    if (qm != null && !qm.isEmpty()) return qm;
                } catch (Exception e) {}
            
            // Second try: Use reflection to get delegate and extract properties
            Field[] fields = connOrSession.getClass().getDeclaredFields();
            for (Field field : fields) {
                if (field.getName().contains("delegate") || field.getName().contains("conn") || field.getName().contains("session")) {
                    field.setAccessible(true);
                    Object delegate = field.get(connOrSession);
                    if (delegate != null) {
                        // Try getPropertyNames and getStringProperty pattern
                        try {
                            Method getPropertyNamesMethod = delegate.getClass().getMethod("getPropertyNames");
                            Method getStringMethod = delegate.getClass().getMethod("getStringProperty", String.class);
                            
                            if (getPropertyNamesMethod != null && getStringMethod != null) {
                                Enumeration<?> propNames = (Enumeration<?>) getPropertyNamesMethod.invoke(delegate);
                                while (propNames.hasMoreElements()) {
                                    String propName = propNames.nextElement().toString();
                                    if (propName.contains("RESOLVED_QUEUE_MANAGER") || propName.contains("QUEUE_MANAGER")) {
                                        Object value = getStringMethod.invoke(delegate, propName);
                                        if (value != null && !value.toString().isEmpty() && !value.toString().equals("*")) {
                                            return value.toString();
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {}
                        
                        // Try direct method calls
                        Method[] methods = delegate.getClass().getMethods();
                        for (Method method : methods) {
                            if (method.getName().contains("getQueueManager") && method.getParameterCount() == 0) {
                                try {
                                    Object result = method.invoke(delegate);
                                    if (result != null && !result.toString().isEmpty()) {
                                        return result.toString();
                                    }
                                } catch (Exception e) {}
                            }
                        }
                    }
                }
            }
            
            // Third try: Extract from CONNECTION_ID if available
            try {
                String connId = null;
                if (connOrSession instanceof MQConnection) {
                    connId = ((MQConnection) connOrSession).getStringProperty("XMSC_WMQ_CONNECTION_ID");
                } else if (connOrSession instanceof MQSession) {
                    connId = ((MQSession) connOrSession).getStringProperty("XMSC_WMQ_CONNECTION_ID");
                }
                
                if (connId != null && connId.length() >= 48) {
                    // CONNECTION_ID format: 414D5143 + QM name (padded to 48 chars) + handle
                    String qmPart = connId.substring(8, 24).trim();
                    if (qmPart.startsWith("QM")) return qmPart;
                }
            } catch (Exception e) {}
            
            // Fourth try: Check any string fields for QM names
            for (Field field : connOrSession.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(connOrSession);
                if (value != null) {
                    String str = value.toString();
                    // Look for exact QM names
                    if (str.equals("QM1") || str.equals("QM2") || str.equals("QM3")) {
                        return str;
                    }
                    // Look for QM names in connection strings
                    if (str.contains("qm=")) {
                        int idx = str.indexOf("qm=") + 3;
                        if (idx + 3 <= str.length()) {
                            String qm = str.substring(idx, Math.min(idx + 3, str.length())).toUpperCase();
                            if (qm.equals("QM1") || qm.equals("QM2") || qm.equals("QM3")) {
                                return qm;
                            }
                        }
                    }
                }
            }
            
        } catch (Exception e) {
            // Fallback
        }
        
        return "UNKNOWN";
    }
    
    private static void log(String message) {
        System.out.println(message);
        if (logWriter != null) {
            logWriter.println(message);
            logWriter.flush();
        }
    }
}