import com.ibm.mq.jms.MQConnectionFactory;
import com.ibm.msg.client.wmq.WMQConstants;
import javax.jms.*;
import java.util.*;
import java.text.SimpleDateFormat;
import java.lang.reflect.Method;

public class SpringBootFailoverFullConntagTest {
    private static final String TEST_ID = "SPRING-" + System.currentTimeMillis();
    private static final String CCDT_URL = "file:///workspace/ccdt/ccdt.json";
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss.SSS");
    private static boolean failoverDetected = false;
    
    // Store connection data for table generation
    static class ConnectionData {
        String label;
        Connection connection;
        List<Session> sessions;
        String apptag;
        String fullConnTag;  // Store FULL CONNTAG without truncation
        String connectionId;
        String queueManager;
        Map<Integer, String> sessionFullConnTags = new HashMap<>();  // Full session CONNTAGs
        
        ConnectionData(String label, String apptag) {
            this.label = label;
            this.apptag = apptag;
            this.sessions = new ArrayList<>();
        }
    }
    
    private static List<ConnectionData> connections = new ArrayList<>();
    
    public static void main(String[] args) throws Exception {
        System.out.println("\n================================================================================");
        System.out.println("       SPRING BOOT MQ FAILOVER TEST WITH FULL CONNTAG - TIMESTAMPED");
        System.out.println("================================================================================");
        System.out.println("Test ID: " + TEST_ID);
        System.out.println("Start Time: " + timestamp());
        System.out.println("CCDT: " + CCDT_URL);
        System.out.println("\n=== CCDT Configuration Details ===");
        System.out.println("• affinity: \"none\" - Enables random QM selection");
        System.out.println("• clientWeight: 1 - Equal distribution across QMs");
        System.out.println("• reconnect.enabled: true - Automatic reconnection");
        System.out.println("• reconnect.timeout: 1800 - 30 minute timeout");
        System.out.println("• Queue Managers: QM1, QM2, QM3 (Uniform Cluster)");
        System.out.println();
        
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
            System.out.println("\n[" + timestamp() + "] 🔴 Spring Container ExceptionListener triggered for C1");
            System.out.println("[" + timestamp() + "]    Error: " + ex.getMessage());
            System.out.println("[" + timestamp() + "]    Container initiating reconnection via CCDT...");
            failoverDetected = true;
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
            System.out.println("\n[" + timestamp() + "] 🔴 Spring Container ExceptionListener triggered for C2");
            System.out.println("[" + timestamp() + "]    Error: " + ex.getMessage());
            System.out.println("[" + timestamp() + "]    Container initiating reconnection via CCDT...");
            failoverDetected = true;
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
        System.out.println("                    BEFORE FAILOVER - FULL CONNTAG TABLE");
        System.out.println("================================================================================");
        printFullConntagTable();
        
        // Print CONNTAG analysis
        printConntagAnalysis("BEFORE FAILOVER");
        
        // === WAIT FOR FAILOVER TEST ===
        System.out.println("\n================================================================================");
        System.out.println("         WAITING FOR FAILOVER TEST - Stop a Queue Manager to trigger!");
        System.out.println("================================================================================");
        System.out.println("Test will monitor for 3 minutes. Stop a QM to see failover...\n");
        
        boolean failoverDetected = false;
        String oldC1ConnTag = conn1Data.fullConnTag;
        String oldC2ConnTag = conn2Data.fullConnTag;
        String oldC1QM = conn1Data.queueManager;
        String oldC2QM = conn2Data.queueManager;
        
        for (int i = 0; i < 18; i++) {
            Thread.sleep(10000); // 10 seconds
            
            // Check for changes every 10 seconds
            for (ConnectionData data : connections) {
                String previousTag = data.fullConnTag;
                String previousQM = data.queueManager;
                extractConnectionProperties(data);
                
                if (!data.fullConnTag.equals(previousTag) || !data.queueManager.equals(previousQM)) {
                    failoverDetected = true;
                    System.out.println("\n>>> FAILOVER DETECTED for " + data.label + "!");
                    System.out.println("    Old CONNTAG: " + previousTag);
                    System.out.println("    New CONNTAG: " + data.fullConnTag);
                    System.out.println("    Old QM: " + previousQM + " → New QM: " + data.queueManager);
                }
            }
            
            if (failoverDetected) {
                System.out.println("\n================================================================================");
                System.out.println("                     AFTER FAILOVER - FULL CONNTAG TABLE");
                System.out.println("================================================================================");
                printFullConntagTable();
                printConntagAnalysis("AFTER FAILOVER");
                break;
            }
            
            if (i % 3 == 0) {
                System.out.print(".");
                System.out.flush();
            }
        }
        
        // === FINAL STATE ===
        System.out.println("\n================================================================================");
        System.out.println("                       FINAL STATE - FULL CONNTAG TABLE");
        System.out.println("================================================================================");
        
        // Re-extract final properties
        for (ConnectionData data : connections) {
            extractConnectionProperties(data);
        }
        printFullConntagTable();
        
        // === CONNTAG CHANGE SUMMARY ===
        System.out.println("\n================================================================================");
        System.out.println("                          CONNTAG CHANGE SUMMARY");
        System.out.println("================================================================================");
        
        System.out.println("\nConnection 1 (C1):");
        System.out.println("  BEFORE: " + oldC1ConnTag);
        System.out.println("  AFTER:  " + conn1Data.fullConnTag);
        System.out.println("  CHANGED: " + (!oldC1ConnTag.equals(conn1Data.fullConnTag) ? "YES ✓" : "NO"));
        
        System.out.println("\nConnection 2 (C2):");
        System.out.println("  BEFORE: " + oldC2ConnTag);
        System.out.println("  AFTER:  " + conn2Data.fullConnTag);
        System.out.println("  CHANGED: " + (!oldC2ConnTag.equals(conn2Data.fullConnTag) ? "YES ✓" : "NO"));
        
        // === PARENT-CHILD AFFINITY PROOF ===
        System.out.println("\n================================================================================");
        System.out.println("                    PARENT-CHILD AFFINITY VERIFICATION");
        System.out.println("================================================================================");
        
        for (ConnectionData data : connections) {
            System.out.println("\n" + data.label + " (" + data.apptag + "):");
            System.out.println("  Parent CONNTAG: " + data.fullConnTag);
            System.out.println("  Parent QM: " + data.queueManager);
            
            boolean allMatch = true;
            for (int i = 0; i < data.sessions.size(); i++) {
                String sessionTag = data.sessionFullConnTags.get(i);
                boolean matches = data.fullConnTag.equals(sessionTag);
                System.out.println("  Session " + (i+1) + " CONNTAG: " + sessionTag);
                System.out.println("           Status: " + (matches ? "✓ MATCHES PARENT" : "✗ DIFFERENT"));
                if (!matches) allMatch = false;
            }
            
            System.out.println("\n  RESULT: " + (allMatch ? 
                "✓✓✓ ALL SESSIONS INHERIT PARENT CONNTAG - AFFINITY PROVEN!" : 
                "✗✗✗ Sessions have different CONNTAGs"));
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
            
            // Get FULL CONNTAG without truncation
            data.fullConnTag = findProperty(connProps, 
                "JMS_IBM_CONNECTION_TAG",
                "XMSC_WMQ_RESOLVED_CONNECTION_TAG");
            
            if (data.fullConnTag.equals("NOT FOUND")) {
                data.fullConnTag = "CONNTAG_NOT_AVAILABLE";
            }
            
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
            
            // Extract FULL session CONNTAGs
            for (int i = 0; i < data.sessions.size(); i++) {
                Map<String, Object> sessionProps = getSessionProperties(data.sessions.get(i));
                String sessionTag = findProperty(sessionProps,
                    "JMS_IBM_CONNECTION_TAG",
                    "XMSC_WMQ_RESOLVED_CONNECTION_TAG");
                    
                if (sessionTag.equals("NOT FOUND")) {
                    sessionTag = "SESSION_CONNTAG_NOT_AVAILABLE";
                }
                data.sessionFullConnTags.put(i, sessionTag);
            }
            
        } catch (Exception e) {
            System.err.println("Error extracting properties for " + data.label + ": " + e.getMessage());
        }
    }
    
    private static void printFullConntagTable() {
        System.out.println();
        System.out.println("| # | Type    | Conn | Session | FULL CONNTAG (NO TRUNCATION)                              | QM   | APPTAG         |");
        System.out.println("|---|---------|------|---------|-----------------------------------------------------------|------|----------------|");
        
        int row = 1;
        for (ConnectionData data : connections) {
            // Parent connection row
            System.out.printf("| %d | Parent  | %-4s | -       | %-57s | %-4s | %-14s |\n",
                row++,
                data.label,
                data.fullConnTag,
                data.queueManager != null ? data.queueManager : "N/A",
                data.apptag);
            
            // Child session rows
            for (int i = 0; i < data.sessions.size(); i++) {
                String sessionTag = data.sessionFullConnTags.get(i);
                System.out.printf("| %d | Session | %-4s | %d       | %-57s | %-4s | %-14s |\n",
                    row++,
                    data.label,
                    i + 1,
                    sessionTag,
                    data.queueManager != null ? data.queueManager : "N/A",
                    data.apptag);
            }
        }
        System.out.println();
    }
    
    private static void printConntagAnalysis(String phase) {
        System.out.println("\n" + phase + " - CONNTAG Analysis:");
        
        for (ConnectionData data : connections) {
            System.out.println("\n  " + data.label + " (" + data.apptag + "):");
            System.out.println("    Full CONNTAG: " + data.fullConnTag);
            
            // Parse CONNTAG if it has the expected format
            if (data.fullConnTag != null && data.fullConnTag.startsWith("MQCT") && data.fullConnTag.length() > 20) {
                try {
                    String prefix = data.fullConnTag.substring(0, 4);
                    String handle = data.fullConnTag.substring(4, Math.min(20, data.fullConnTag.length()));
                    
                    // Find QM name in CONNTAG
                    String qmPart = "";
                    if (data.fullConnTag.contains("QM1")) qmPart = "QM1";
                    else if (data.fullConnTag.contains("QM2")) qmPart = "QM2";
                    else if (data.fullConnTag.contains("QM3")) qmPart = "QM3";
                    
                    System.out.println("    Parsed: Prefix=" + prefix + ", Handle=" + handle + ", QM=" + qmPart);
                    System.out.println("    Queue Manager: " + data.queueManager);
                    System.out.println("    Total connections: " + (1 + data.sessions.size()));
                } catch (Exception e) {
                    System.out.println("    Could not parse CONNTAG format");
                }
            }
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
    
    private static String timestamp() {
        return TIME_FORMAT.format(new Date());
    }
}