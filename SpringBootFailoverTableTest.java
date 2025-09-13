import com.ibm.mq.jms.MQConnectionFactory;
import com.ibm.msg.client.wmq.WMQConstants;
import javax.jms.*;
import java.util.*;
import java.text.SimpleDateFormat;
import java.lang.reflect.Method;

public class SpringBootFailoverTableTest {
    private static final String TEST_ID = "SPRING-" + (System.currentTimeMillis() % 100000);
    private static final String CCDT_URL = "file:///workspace/ccdt/ccdt.json";
    
    // Store connection data for table generation
    static class ConnectionData {
        String label;
        Connection connection;
        List<Session> sessions;
        String apptag;
        String connTag;
        String connectionId;
        String queueManager;
        Map<Integer, String> sessionTags = new HashMap<>();
        
        ConnectionData(String label, String apptag) {
            this.label = label;
            this.apptag = apptag;
            this.sessions = new ArrayList<>();
        }
    }
    
    private static List<ConnectionData> connections = new ArrayList<>();
    
    public static void main(String[] args) throws Exception {
        System.out.println("================================================================================");
        System.out.println("          SPRING BOOT MQ FAILOVER TEST WITH PARENT-CHILD TABLES");
        System.out.println("================================================================================");
        System.out.println("Test ID: " + TEST_ID);
        System.out.println("Time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date()));
        System.out.println();
        
        // Enable JMS trace
        System.setProperty("com.ibm.msg.client.commonservices.trace.status", "ON");
        
        // Create connection factory
        MQConnectionFactory factory = new MQConnectionFactory();
        factory.setStringProperty(WMQConstants.WMQ_CCDTURL, CCDT_URL);
        factory.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "*");
        factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, WMQConstants.WMQ_CLIENT_RECONNECT);
        factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_TIMEOUT, 1800);
        factory.setTransportType(WMQConstants.WMQ_CM_CLIENT);
        
        // === CONNECTION 1: 5 SESSIONS ===
        System.out.println("Creating Connection 1 with 5 sessions...");
        ConnectionData conn1Data = new ConnectionData("C1", TEST_ID + "-C1");
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, conn1Data.apptag);
        
        conn1Data.connection = factory.createConnection();
        conn1Data.connection.setExceptionListener(ex -> {
            System.out.println("[C1] FAILOVER DETECTED: " + ex.getMessage());
        });
        conn1Data.connection.start();
        
        for (int i = 1; i <= 5; i++) {
            Session session = conn1Data.connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            conn1Data.sessions.add(session);
            System.out.println("  Created C1 Session " + i);
        }
        connections.add(conn1Data);
        
        // === CONNECTION 2: 3 SESSIONS ===
        System.out.println("\nCreating Connection 2 with 3 sessions...");
        ConnectionData conn2Data = new ConnectionData("C2", TEST_ID + "-C2");
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, conn2Data.apptag);
        
        conn2Data.connection = factory.createConnection();
        conn2Data.connection.setExceptionListener(ex -> {
            System.out.println("[C2] FAILOVER DETECTED: " + ex.getMessage());
        });
        conn2Data.connection.start();
        
        for (int i = 1; i <= 3; i++) {
            Session session = conn2Data.connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            conn2Data.sessions.add(session);
            System.out.println("  Created C2 Session " + i);
        }
        connections.add(conn2Data);
        
        // Extract initial properties
        System.out.println("\nExtracting connection properties...");
        for (ConnectionData data : connections) {
            extractConnectionProperties(data);
        }
        
        // === BEFORE FAILOVER TABLE ===
        System.out.println("\n================================================================================");
        System.out.println("                        BEFORE FAILOVER - CONNECTION TABLE");
        System.out.println("================================================================================");
        printConnectionTable();
        
        // Print distribution summary
        printDistributionSummary("BEFORE FAILOVER");
        
        // === WAIT FOR FAILOVER TEST ===
        System.out.println("\n================================================================================");
        System.out.println("           WAITING FOR FAILOVER TEST (Stop a Queue Manager now!)");
        System.out.println("================================================================================");
        System.out.println("Test will run for 3 minutes. Stop a QM to trigger failover...\n");
        
        boolean failoverDetected = false;
        for (int i = 0; i < 18; i++) {
            Thread.sleep(10000); // 10 seconds
            
            if (i == 6) { // At 60 seconds
                System.out.println("\n=== 60 SECONDS - Checking for failover... ===");
                
                // Re-extract properties to detect changes
                for (ConnectionData data : connections) {
                    String oldConnTag = data.connTag;
                    String oldQM = data.queueManager;
                    extractConnectionProperties(data);
                    
                    if (!data.connTag.equals(oldConnTag) || !data.queueManager.equals(oldQM)) {
                        failoverDetected = true;
                        System.out.println("FAILOVER DETECTED for " + data.label + "!");
                        System.out.println("  Old CONNTAG: " + oldConnTag);
                        System.out.println("  New CONNTAG: " + data.connTag);
                        System.out.println("  Old QM: " + oldQM);
                        System.out.println("  New QM: " + data.queueManager);
                    }
                }
                
                if (failoverDetected) {
                    System.out.println("\n================================================================================");
                    System.out.println("                         AFTER FAILOVER - CONNECTION TABLE");
                    System.out.println("================================================================================");
                    printConnectionTable();
                    printDistributionSummary("AFTER FAILOVER");
                }
            }
            
            if (i % 3 == 0) {
                System.out.println("Time elapsed: " + (i * 10) + " seconds...");
            }
        }
        
        // === FINAL STATE ===
        System.out.println("\n================================================================================");
        System.out.println("                           FINAL STATE - CONNECTION TABLE");
        System.out.println("================================================================================");
        
        // Re-extract final properties
        for (ConnectionData data : connections) {
            extractConnectionProperties(data);
        }
        printConnectionTable();
        printDistributionSummary("FINAL STATE");
        
        // === PARENT-CHILD PROOF ===
        System.out.println("\n================================================================================");
        System.out.println("                          PARENT-CHILD AFFINITY PROOF");
        System.out.println("================================================================================");
        
        for (ConnectionData data : connections) {
            System.out.println("\n" + data.label + " (" + data.apptag + "):");
            System.out.println("  Parent CONNTAG: " + data.connTag);
            System.out.println("  Parent QM: " + data.queueManager);
            
            boolean allMatch = true;
            for (int i = 0; i < data.sessions.size(); i++) {
                String sessionTag = data.sessionTags.get(i);
                boolean matches = data.connTag.equals(sessionTag);
                System.out.println("  Session " + (i+1) + " CONNTAG: " + sessionTag + 
                                 " [" + (matches ? "✓ MATCHES PARENT" : "✗ DIFFERENT") + "]");
                if (!matches) allMatch = false;
            }
            
            System.out.println("  RESULT: " + (allMatch ? 
                "✓ ALL SESSIONS INHERIT PARENT CONNTAG - AFFINITY PROVEN!" : 
                "✗ Sessions have different CONNTAGs"));
        }
        
        // Cleanup
        System.out.println("\nCleaning up connections...");
        for (ConnectionData data : connections) {
            for (Session s : data.sessions) {
                try { s.close(); } catch (Exception e) {}
            }
            try { data.connection.close(); } catch (Exception e) {}
        }
        
        System.out.println("\n================================================================================");
        System.out.println("                              TEST COMPLETE");
        System.out.println("================================================================================");
    }
    
    private static void extractConnectionProperties(ConnectionData data) {
        try {
            // Extract parent connection properties
            Map<String, Object> connProps = getConnectionProperties(data.connection);
            
            data.connTag = findProperty(connProps, 
                "JMS_IBM_CONNECTION_TAG",
                "XMSC_WMQ_RESOLVED_CONNECTION_TAG");
            
            data.connectionId = findProperty(connProps,
                "JMS_IBM_CONNECTION_ID",
                "XMSC_WMQ_CONNECTION_ID");
            
            data.queueManager = findProperty(connProps,
                "JMS_IBM_RESOLVED_QUEUE_MANAGER",
                "XMSC_WMQ_RESOLVED_QUEUE_MANAGER");
            
            // Parse QM from CONNECTION_ID if not found
            if ((data.queueManager == null || data.queueManager.equals("NOT FOUND")) 
                && data.connectionId != null && data.connectionId.length() >= 32) {
                String qmPrefix = data.connectionId.substring(0, 32);
                if (qmPrefix.startsWith("414D5143514D31")) data.queueManager = "QM1";
                else if (qmPrefix.startsWith("414D5143514D32")) data.queueManager = "QM2";
                else if (qmPrefix.startsWith("414D5143514D33")) data.queueManager = "QM3";
            }
            
            // Extract session properties
            for (int i = 0; i < data.sessions.size(); i++) {
                Map<String, Object> sessionProps = getSessionProperties(data.sessions.get(i));
                String sessionTag = findProperty(sessionProps,
                    "JMS_IBM_CONNECTION_TAG",
                    "XMSC_WMQ_RESOLVED_CONNECTION_TAG");
                data.sessionTags.put(i, sessionTag);
            }
            
        } catch (Exception e) {
            System.err.println("Error extracting properties for " + data.label + ": " + e.getMessage());
        }
    }
    
    private static void printConnectionTable() {
        System.out.println();
        System.out.println("| # | Type    | Conn | Session | CONNECTION_ID (first 32 chars)  | CONNTAG (first 40 chars)        | QM   | APPTAG         |");
        System.out.println("|---|---------|------|---------|----------------------------------|----------------------------------|------|----------------|");
        
        int row = 1;
        for (ConnectionData data : connections) {
            // Parent connection row
            System.out.printf("| %d | Parent  | %-4s | -       | %-32s | %-32s | %-4s | %-14s |\n",
                row++,
                data.label,
                data.connectionId != null ? data.connectionId.substring(0, Math.min(32, data.connectionId.length())) : "N/A",
                data.connTag != null ? data.connTag.substring(0, Math.min(32, data.connTag.length())) : "N/A",
                data.queueManager != null ? data.queueManager : "N/A",
                data.apptag);
            
            // Child session rows
            for (int i = 0; i < data.sessions.size(); i++) {
                String sessionTag = data.sessionTags.get(i);
                System.out.printf("| %d | Session | %-4s | %d       | %-32s | %-32s | %-4s | %-14s |\n",
                    row++,
                    data.label,
                    i + 1,
                    data.connectionId != null ? data.connectionId.substring(0, Math.min(32, data.connectionId.length())) : "N/A",
                    sessionTag != null ? sessionTag.substring(0, Math.min(32, sessionTag.length())) : "N/A",
                    data.queueManager != null ? data.queueManager : "N/A",
                    data.apptag);
            }
        }
        System.out.println();
    }
    
    private static void printDistributionSummary(String phase) {
        System.out.println("\n" + phase + " Distribution Summary:");
        Map<String, Integer> qmCounts = new HashMap<>();
        
        for (ConnectionData data : connections) {
            String qm = data.queueManager != null ? data.queueManager : "UNKNOWN";
            int totalConnections = 1 + data.sessions.size(); // Parent + sessions
            qmCounts.put(qm, qmCounts.getOrDefault(qm, 0) + totalConnections);
            System.out.println("  " + data.label + " (" + data.apptag + "): " + 
                             totalConnections + " connections on " + qm);
        }
        
        System.out.println("\nTotal by Queue Manager:");
        for (Map.Entry<String, Integer> entry : qmCounts.entrySet()) {
            System.out.println("  " + entry.getKey() + ": " + entry.getValue() + " connections");
        }
    }
    
    // Reflection helper methods (same as before)
    private static Map<String, Object> getConnectionProperties(Connection connection) {
        Map<String, Object> props = new HashMap<>();
        try {
            Method getPropertyContextMethod = connection.getClass().getMethod("getPropertyContext");
            Object context = getPropertyContextMethod.invoke(connection);
            
            if (context != null) {
                try {
                    Method getPropertiesMethod = context.getClass().getMethod("getProperties");
                    Object propertiesObj = getPropertiesMethod.invoke(context);
                    if (propertiesObj instanceof Map) {
                        props.putAll((Map<String, Object>) propertiesObj);
                    }
                } catch (Exception e) {
                    for (Method m : context.getClass().getMethods()) {
                        if (m.getName().contains("Property") || m.getName().contains("properties")) {
                            try {
                                Object result = m.invoke(context);
                                if (result instanceof Map) {
                                    props.putAll((Map<String, Object>) result);
                                    break;
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
        } catch (Exception e) {}
        return props;
    }
    
    private static Map<String, Object> getSessionProperties(Session session) {
        Map<String, Object> props = new HashMap<>();
        try {
            Method getPropertyContextMethod = session.getClass().getMethod("getPropertyContext");
            Object context = getPropertyContextMethod.invoke(session);
            
            if (context != null) {
                try {
                    Method getPropertiesMethod = context.getClass().getMethod("getProperties");
                    Object propertiesObj = getPropertiesMethod.invoke(context);
                    if (propertiesObj instanceof Map) {
                        props.putAll((Map<String, Object>) propertiesObj);
                    }
                } catch (Exception e) {
                    for (Method m : context.getClass().getMethods()) {
                        if (m.getName().contains("Property") || m.getName().contains("properties")) {
                            try {
                                Object result = m.invoke(context);
                                if (result instanceof Map) {
                                    props.putAll((Map<String, Object>) result);
                                    break;
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
        } catch (Exception e) {}
        return props;
    }
    
    private static String findProperty(Map<String, Object> props, String... possibleNames) {
        for (String name : possibleNames) {
            Object value = props.get(name);
            if (value != null) {
                return value.toString();
            }
            
            for (Map.Entry<String, Object> entry : props.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue().toString();
                }
            }
        }
        return "NOT FOUND";
    }
}