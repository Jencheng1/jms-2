import com.ibm.mq.jms.MQConnectionFactory;
import com.ibm.msg.client.wmq.WMQConstants;
import javax.jms.*;
import java.util.*;
import java.text.SimpleDateFormat;
import java.lang.reflect.Method;

public class SpringBootFailoverTestSimple {
    private static final String TEST_ID = "SPRING-" + (System.currentTimeMillis() % 100000);
    private static final String CCDT_URL = "file:///workspace/ccdt/ccdt.json";
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== Spring Boot Style Failover Test ===");
        System.out.println("Test ID: " + TEST_ID);
        System.out.println("Time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date()));
        
        // Enable JMS debug trace
        System.setProperty("com.ibm.msg.client.commonservices.trace.status", "ON");
        System.setProperty("com.ibm.msg.client.commonservices.trace.outputName", "mqjms_trace.log");
        
        // Create connection factory (Spring Boot style)
        MQConnectionFactory factory = new MQConnectionFactory();
        factory.setStringProperty(WMQConstants.WMQ_CCDTURL, CCDT_URL);
        factory.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "*");
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TEST_ID + "-C1");
        factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, WMQConstants.WMQ_CLIENT_RECONNECT);
        factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_TIMEOUT, 1800);
        factory.setTransportType(WMQConstants.WMQ_CM_CLIENT);
        
        // Create connection 1 with 5 sessions
        System.out.println("\n=== Creating Connection 1 with 5 sessions ===");
        Connection conn1 = factory.createConnection();
        conn1.setExceptionListener(ex -> {
            System.out.println("[C1] JMS Exception detected: " + ex.getMessage());
        });
        conn1.start();
        
        List<Session> sessions1 = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            Session session = conn1.createSession(false, Session.AUTO_ACKNOWLEDGE);
            sessions1.add(session);
            System.out.println("Created C1 Session " + i);
        }
        
        // Create connection 2 with 3 sessions
        System.out.println("\n=== Creating Connection 2 with 3 sessions ===");
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TEST_ID + "-C2");
        Connection conn2 = factory.createConnection();
        conn2.setExceptionListener(ex -> {
            System.out.println("[C2] JMS Exception detected: " + ex.getMessage());
        });
        conn2.start();
        
        List<Session> sessions2 = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            Session session = conn2.createSession(false, Session.AUTO_ACKNOWLEDGE);
            sessions2.add(session);
            System.out.println("Created C2 Session " + i);
        }
        
        // Extract and display connection properties using reflection
        System.out.println("\n=== Initial Connection State ===");
        printConnectionProperties("C1", conn1, sessions1);
        printConnectionProperties("C2", conn2, sessions2);
        
        // Keep alive for failover test
        System.out.println("\n=== Running failover test (3 minutes) ===");
        System.out.println("Stop a Queue Manager to trigger failover...");
        
        for (int i = 0; i < 18; i++) {
            Thread.sleep(10000); // 10 seconds
            System.out.println("Time elapsed: " + (i+1)*10 + " seconds");
            
            // Check connection state every 30 seconds
            if (i % 3 == 2) {
                System.out.println("\n=== Connection State Check at " + ((i+1)*10) + " seconds ===");
                printConnectionProperties("C1", conn1, sessions1);
                printConnectionProperties("C2", conn2, sessions2);
            }
        }
        
        // Final state
        System.out.println("\n=== Final Connection State ===");
        printConnectionProperties("C1", conn1, sessions1);
        printConnectionProperties("C2", conn2, sessions2);
        
        // Cleanup
        for (Session s : sessions1) { try { s.close(); } catch (Exception e) {} }
        for (Session s : sessions2) { try { s.close(); } catch (Exception e) {} }
        conn1.close();
        conn2.close();
        
        System.out.println("\n=== Test Complete ===");
    }
    
    private static void printConnectionProperties(String label, Connection conn, List<Session> sessions) {
        try {
            System.out.println("\n" + label + " Connection Properties:");
            
            // Use reflection to get properties
            Map<String, Object> connProps = getConnectionProperties(conn);
            
            // Look for CONNTAG with various possible property names
            String connTag = findProperty(connProps, 
                "JMS_IBM_CONNECTION_TAG",
                "XMSC_WMQ_RESOLVED_CONNECTION_TAG", 
                "JmsIbmConnectionTag",
                "connectionTag");
            
            // Look for CONNECTION_ID
            String connId = findProperty(connProps,
                "JMS_IBM_CONNECTION_ID",
                "XMSC_WMQ_CONNECTION_ID",
                "JmsIbmConnectionId",
                "connectionId");
            
            // Look for Queue Manager
            String qm = findProperty(connProps,
                "JMS_IBM_RESOLVED_QUEUE_MANAGER",
                "XMSC_WMQ_RESOLVED_QUEUE_MANAGER",
                "JmsIbmResolvedQueueManager",
                "resolvedQueueManager");
            
            System.out.println("  CONNTAG: " + (connTag != null ? connTag : "NOT FOUND"));
            System.out.println("  CONNECTION_ID: " + (connId != null ? connId : "NOT FOUND"));
            System.out.println("  Queue Manager: " + (qm != null ? qm : "NOT FOUND"));
            
            // Parse QM from CONNECTION_ID if needed
            if (qm == null && connId != null && connId.length() >= 32) {
                String qmPrefix = connId.substring(0, 32);
                if (qmPrefix.startsWith("414D5143514D31")) qm = "QM1";
                else if (qmPrefix.startsWith("414D5143514D32")) qm = "QM2";
                else if (qmPrefix.startsWith("414D5143514D33")) qm = "QM3";
                if (qm != null) {
                    System.out.println("  Queue Manager (from ID): " + qm);
                }
            }
            
            // Check first session
            if (!sessions.isEmpty()) {
                Map<String, Object> sessionProps = getSessionProperties(sessions.get(0));
                String sessionConnTag = findProperty(sessionProps,
                    "JMS_IBM_CONNECTION_TAG",
                    "XMSC_WMQ_RESOLVED_CONNECTION_TAG",
                    "JmsIbmConnectionTag",
                    "connectionTag");
                
                System.out.println("  First Session CONNTAG: " + (sessionConnTag != null ? sessionConnTag : "NOT FOUND"));
                if (connTag != null && sessionConnTag != null) {
                    System.out.println("  Session inherits parent CONNTAG: " + connTag.equals(sessionConnTag));
                }
            }
            
            System.out.println("  Total sessions: " + sessions.size());
            
        } catch (Exception e) {
            System.err.println("Error getting connection properties: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static Map<String, Object> getConnectionProperties(Connection connection) {
        Map<String, Object> props = new HashMap<>();
        
        try {
            // Try to get property context
            Method getPropertyContextMethod = connection.getClass().getMethod("getPropertyContext");
            Object context = getPropertyContextMethod.invoke(connection);
            
            if (context != null) {
                // Try to get properties map
                try {
                    Method getPropertiesMethod = context.getClass().getMethod("getProperties");
                    Object propertiesObj = getPropertiesMethod.invoke(context);
                    if (propertiesObj instanceof Map) {
                        props.putAll((Map<String, Object>) propertiesObj);
                    }
                } catch (Exception e) {
                    // Try alternative method names
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
        } catch (Exception e) {
            System.err.println("Could not extract properties via reflection: " + e.getMessage());
        }
        
        return props;
    }
    
    private static Map<String, Object> getSessionProperties(Session session) {
        Map<String, Object> props = new HashMap<>();
        
        try {
            // Try to get property context
            Method getPropertyContextMethod = session.getClass().getMethod("getPropertyContext");
            Object context = getPropertyContextMethod.invoke(session);
            
            if (context != null) {
                // Try to get properties map
                try {
                    Method getPropertiesMethod = context.getClass().getMethod("getProperties");
                    Object propertiesObj = getPropertiesMethod.invoke(context);
                    if (propertiesObj instanceof Map) {
                        props.putAll((Map<String, Object>) propertiesObj);
                    }
                } catch (Exception e) {
                    // Try alternative methods
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
        } catch (Exception e) {
            System.err.println("Could not extract session properties: " + e.getMessage());
        }
        
        return props;
    }
    
    private static String findProperty(Map<String, Object> props, String... possibleNames) {
        for (String name : possibleNames) {
            // Try exact match
            Object value = props.get(name);
            if (value != null) {
                return value.toString();
            }
            
            // Try case-insensitive match
            for (Map.Entry<String, Object> entry : props.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue().toString();
                }
            }
        }
        return null;
    }
}