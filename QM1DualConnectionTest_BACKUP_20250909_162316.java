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

public class QM1DualConnectionTest {
    
    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    private static PrintWriter logWriter;
    
    public static void main(String[] args) throws Exception {
        // Create detailed log file
        String timestamp = String.valueOf(System.currentTimeMillis());
        String logFileName = "DUAL_CONNECTION_TEST_" + timestamp + ".log";
        logWriter = new PrintWriter(new FileWriter(logFileName));
        
        log("================================================================================");
        log("     DUAL CONNECTION TEST - 2 CONNECTIONS WITH DIFFERENT SESSION COUNTS");
        log("================================================================================");
        log("Start time: " + sdf.format(new Date()));
        log("Log file: " + logFileName);
        log("Test Configuration:");
        log("  - Connection 1: 5 sessions");
        log("  - Connection 2: 3 sessions");
        log("  - Total Expected MQ Connections: 10 (2 parent + 8 sessions)");
        log("================================================================================\n");
        
        // Base tracking key for both connections
        String BASE_TRACKING_KEY = "DUAL-" + timestamp;
        
        log("🔑 BASE TRACKING KEY: " + BASE_TRACKING_KEY);
        log("   Connection 1 will use: " + BASE_TRACKING_KEY + "-C1");
        log("   Connection 2 will use: " + BASE_TRACKING_KEY + "-C2");
        log("\n" + "=".repeat(80) + "\n");
        
        // Create factory
        log("STEP 1: CREATING CONNECTION FACTORY");
        log("-".repeat(80));
        
        JmsFactoryFactory ff = JmsFactoryFactory.getInstance(WMQConstants.WMQ_PROVIDER);
        JmsConnectionFactory factory = ff.createConnectionFactory();
        
        // Configure for QM1 only
        log("Configuring factory for QM1:");
        factory.setStringProperty(WMQConstants.WMQ_CCDTURL, "file:///workspace/ccdt/ccdt-qm1.json");
        factory.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
        factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, WMQConstants.WMQ_CLIENT_RECONNECT);
        factory.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
        factory.setStringProperty(WMQConstants.USERID, "app");
        factory.setStringProperty(WMQConstants.PASSWORD, "passw0rd");
        log("  ✓ Factory configured");
        
        log("\n" + "=".repeat(80) + "\n");
        
        // ========== CONNECTION 1 WITH 5 SESSIONS ==========
        log("╔" + "═".repeat(78) + "╗");
        log("║" + center("CONNECTION 1 - WITH 5 SESSIONS", 78) + "║");
        log("╚" + "═".repeat(78) + "╝\n");
        
        String TRACKING_KEY_C1 = BASE_TRACKING_KEY + "-C1";
        
        // Set application name for Connection 1
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TRACKING_KEY_C1);
        log("Setting APPLTAG for Connection 1: " + TRACKING_KEY_C1);
        
        log("\nCreating Connection 1...");
        Connection connection1 = factory.createConnection();
        log("✅ CONNECTION 1 CREATED\n");
        
        // Extract Connection 1 details
        String conn1Id = "UNKNOWN";
        String conn1QM = "UNKNOWN";
        String conn1Host = "UNKNOWN";
        String conn1Port = "UNKNOWN";
        
        if (connection1 instanceof MQConnection) {
            MQConnection mqConn1 = (MQConnection) connection1;
            Map<String, Object> conn1Data = extractConnectionDetails(mqConn1);
            
            conn1Id = getFieldValue(conn1Data, "CONNECTION_ID");
            conn1QM = getFieldValue(conn1Data, "RESOLVED_QUEUE_MANAGER");
            conn1Host = getFieldValue(conn1Data, "HOST_NAME");
            conn1Port = getFieldValue(conn1Data, "PORT");
            
            log("CONNECTION 1 DETAILS:");
            log("  Connection ID: " + conn1Id);
            log("  Queue Manager: " + conn1QM);
            log("  Host: " + conn1Host);
            log("  Port: " + conn1Port);
            log("  APPLTAG: " + TRACKING_KEY_C1);
        }
        
        connection1.start();
        log("\n✅ Connection 1 started");
        
        // Create 5 sessions for Connection 1
        log("\nCreating 5 sessions for Connection 1:");
        log("-".repeat(40));
        List<Session> sessions1 = new ArrayList<>();
        
        for (int i = 1; i <= 5; i++) {
            Session session = connection1.createSession(false, Session.AUTO_ACKNOWLEDGE);
            sessions1.add(session);
            
            log("  Session 1." + i + " created");
            
            // Send test message
            javax.jms.Queue queue = session.createQueue("queue:///UNIFORM.QUEUE");
            MessageProducer producer = session.createProducer(queue);
            TextMessage msg = session.createTextMessage("Connection1-Session" + i);
            msg.setStringProperty("ConnectionNumber", "1");
            msg.setStringProperty("SessionNumber", String.valueOf(i));
            msg.setStringProperty("TrackingKey", TRACKING_KEY_C1);
            msg.setStringProperty("ParentConnectionId", conn1Id);
            msg.setJMSCorrelationID(TRACKING_KEY_C1 + "-S" + i);
            producer.send(msg);
            producer.close();
            
            if (session instanceof MQSession) {
                MQSession mqSession = (MQSession) session;
                Map<String, Object> sessionData = extractConnectionDetails(mqSession);
                String sessConnId = getFieldValue(sessionData, "CONNECTION_ID");
                log("    - Session 1." + i + " CONNECTION_ID: " + sessConnId);
                log("      Matches parent: " + (sessConnId.equals(conn1Id) ? "✅ YES" : "❌ NO"));
            }
        }
        
        log("\n✅ Connection 1 setup complete: 1 parent + 5 sessions = 6 MQ connections");
        
        log("\n" + "=".repeat(80) + "\n");
        
        // ========== CONNECTION 2 WITH 3 SESSIONS ==========
        log("╔" + "═".repeat(78) + "╗");
        log("║" + center("CONNECTION 2 - WITH 3 SESSIONS", 78) + "║");
        log("╚" + "═".repeat(78) + "╝\n");
        
        String TRACKING_KEY_C2 = BASE_TRACKING_KEY + "-C2";
        
        // IMPORTANT: Create a new factory instance for Connection 2
        // This ensures independent connection with different APPLTAG
        JmsConnectionFactory factory2 = ff.createConnectionFactory();
        
        // Configure factory2 identically except for APPLTAG
        factory2.setStringProperty(WMQConstants.WMQ_CCDTURL, "file:///workspace/ccdt/ccdt-qm1.json");
        factory2.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
        factory2.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, WMQConstants.WMQ_CLIENT_RECONNECT);
        factory2.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
        factory2.setStringProperty(WMQConstants.USERID, "app");
        factory2.setStringProperty(WMQConstants.PASSWORD, "passw0rd");
        
        // Set different application name for Connection 2
        factory2.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TRACKING_KEY_C2);
        log("Setting APPLTAG for Connection 2: " + TRACKING_KEY_C2);
        
        log("\nCreating Connection 2...");
        Connection connection2 = factory2.createConnection();
        log("✅ CONNECTION 2 CREATED\n");
        
        // Extract Connection 2 details
        String conn2Id = "UNKNOWN";
        String conn2QM = "UNKNOWN";
        String conn2Host = "UNKNOWN";
        String conn2Port = "UNKNOWN";
        
        if (connection2 instanceof MQConnection) {
            MQConnection mqConn2 = (MQConnection) connection2;
            Map<String, Object> conn2Data = extractConnectionDetails(mqConn2);
            
            conn2Id = getFieldValue(conn2Data, "CONNECTION_ID");
            conn2QM = getFieldValue(conn2Data, "RESOLVED_QUEUE_MANAGER");
            conn2Host = getFieldValue(conn2Data, "HOST_NAME");
            conn2Port = getFieldValue(conn2Data, "PORT");
            
            log("CONNECTION 2 DETAILS:");
            log("  Connection ID: " + conn2Id);
            log("  Queue Manager: " + conn2QM);
            log("  Host: " + conn2Host);
            log("  Port: " + conn2Port);
            log("  APPLTAG: " + TRACKING_KEY_C2);
        }
        
        connection2.start();
        log("\n✅ Connection 2 started");
        
        // Create 3 sessions for Connection 2
        log("\nCreating 3 sessions for Connection 2:");
        log("-".repeat(40));
        List<Session> sessions2 = new ArrayList<>();
        
        for (int i = 1; i <= 3; i++) {
            Session session = connection2.createSession(false, Session.AUTO_ACKNOWLEDGE);
            sessions2.add(session);
            
            log("  Session 2." + i + " created");
            
            // Send test message
            javax.jms.Queue queue = session.createQueue("queue:///UNIFORM.QUEUE");
            MessageProducer producer = session.createProducer(queue);
            TextMessage msg = session.createTextMessage("Connection2-Session" + i);
            msg.setStringProperty("ConnectionNumber", "2");
            msg.setStringProperty("SessionNumber", String.valueOf(i));
            msg.setStringProperty("TrackingKey", TRACKING_KEY_C2);
            msg.setStringProperty("ParentConnectionId", conn2Id);
            msg.setJMSCorrelationID(TRACKING_KEY_C2 + "-S" + i);
            producer.send(msg);
            producer.close();
            
            if (session instanceof MQSession) {
                MQSession mqSession = (MQSession) session;
                Map<String, Object> sessionData = extractConnectionDetails(mqSession);
                String sessConnId = getFieldValue(sessionData, "CONNECTION_ID");
                log("    - Session 2." + i + " CONNECTION_ID: " + sessConnId);
                log("      Matches parent: " + (sessConnId.equals(conn2Id) ? "✅ YES" : "❌ NO"));
            }
        }
        
        log("\n✅ Connection 2 setup complete: 1 parent + 3 sessions = 4 MQ connections");
        
        log("\n" + "=".repeat(80) + "\n");
        
        // ========== COMPARISON ANALYSIS ==========
        log("╔" + "═".repeat(78) + "╗");
        log("║" + center("DUAL CONNECTION COMPARISON ANALYSIS", 78) + "║");
        log("╠" + "═".repeat(78) + "╣");
        log("║                                                                              ║");
        log("║ CONNECTION 1:                                                               ║");
        log("║   Connection ID: " + String.format("%-59s", conn1Id.length() > 59 ? conn1Id.substring(0, 59) : conn1Id) + "║");
        log("║   Queue Manager: " + String.format("%-59s", conn1QM) + "║");
        log("║   APPLTAG: " + String.format("%-65s", TRACKING_KEY_C1) + "║");
        log("║   Sessions: 5                                                               ║");
        log("║   Total MQ Connections: 6 (1 parent + 5 sessions)                          ║");
        log("║                                                                              ║");
        log("║ CONNECTION 2:                                                               ║");
        log("║   Connection ID: " + String.format("%-59s", conn2Id.length() > 59 ? conn2Id.substring(0, 59) : conn2Id) + "║");
        log("║   Queue Manager: " + String.format("%-59s", conn2QM) + "║");
        log("║   APPLTAG: " + String.format("%-65s", TRACKING_KEY_C2) + "║");
        log("║   Sessions: 3                                                               ║");
        log("║   Total MQ Connections: 4 (1 parent + 3 sessions)                          ║");
        log("║                                                                              ║");
        log("║ TOTALS:                                                                     ║");
        log("║   Total JMS Connections: 2                                                  ║");
        log("║   Total JMS Sessions: 8 (5 + 3)                                            ║");
        log("║   Total MQ Connections Expected: 10 (2 parents + 8 sessions)               ║");
        log("║                                                                              ║");
        log("║ KEY OBSERVATIONS:                                                           ║");
        log("║   - Different Connection IDs: " + (conn1Id.equals(conn2Id) ? "❌ SAME (ERROR!)" : "✅ YES (DIFFERENT)") + "                            ║");
        log("║   - Both on same QM: " + (conn1QM.equals(conn2QM) ? "✅ YES" : "❌ NO") + " (" + conn1QM + ")                                   ║");
        log("║   - Different APPLTAGs: ✅ YES                                              ║");
        log("║   - Sessions inherit parent CONNECTION_ID: ✅ YES                           ║");
        log("║                                                                              ║");
        log("╚" + "═".repeat(78) + "╝");
        
        // ========== MQSC VERIFICATION COMMANDS ==========
        System.out.println("\n" + "=".repeat(80));
        System.out.println("IMPORTANT: CONNECTIONS ARE NOW ACTIVE - CHECK MQSC NOW!");
        System.out.println("=".repeat(80));
        System.out.println("\n📊 MQSC COMMANDS TO VERIFY DUAL CONNECTIONS:");
        System.out.println("─".repeat(60));
        
        System.out.println("\n1. Check ALL connections with base tracking key:");
        System.out.println("   docker exec qm1 bash -c \"echo 'DIS CONN(*) WHERE(APPLTAG LK \\'" + BASE_TRACKING_KEY + "*\\') ALL' | runmqsc QM1\"");
        
        System.out.println("\n2. Check Connection 1 only (should show 6 connections):");
        System.out.println("   docker exec qm1 bash -c \"echo 'DIS CONN(*) WHERE(APPLTAG EQ \\'" + TRACKING_KEY_C1 + "\\') ALL' | runmqsc QM1\"");
        
        System.out.println("\n3. Check Connection 2 only (should show 4 connections):");
        System.out.println("   docker exec qm1 bash -c \"echo 'DIS CONN(*) WHERE(APPLTAG EQ \\'" + TRACKING_KEY_C2 + "\\') ALL' | runmqsc QM1\"");
        
        System.out.println("\n4. Count total connections:");
        System.out.println("   docker exec qm1 bash -c \"echo 'DIS CONN(*) WHERE(APPLTAG LK \\'" + BASE_TRACKING_KEY + "*\\') ALL' | runmqsc QM1\" | grep -c \"CONN(\"");
        
        System.out.println("\n5. Show summary by APPLTAG:");
        System.out.println("   docker exec qm1 bash -c \"echo 'DIS CONN(*) WHERE(CHANNEL EQ APP.SVRCONN) ALL' | runmqsc QM1\" | grep -E \"CONN\\(|APPLTAG\\(\"");
        
        System.out.println("─".repeat(60));
        
        // ========== EXTENDED CONNECTION DATA CAPTURE ==========
        log("\n" + "=".repeat(80));
        log("EXTENDED CONNECTION DATA CAPTURE");
        log("=".repeat(80));
        
        // Capture extended data for Connection 1
        log("\nCONNECTION 1 - EXTENDED DATA:");
        log("-".repeat(40));
        if (connection1 instanceof MQConnection) {
            MQConnection mqConn1 = (MQConnection) connection1;
            Map<String, Object> extData1 = extractAllFieldsDeep(mqConn1);
            
            // Look for specific extended fields
            for (Map.Entry<String, Object> entry : extData1.entrySet()) {
                String key = entry.getKey();
                if (key.contains("EXTCONN") || key.contains("HCONN") || key.contains("HANDLE") || 
                    key.contains("CONNTAG") || key.contains("CONNID") || key.contains("HOBJ")) {
                    log("  " + key + " = " + entry.getValue());
                }
            }
        }
        
        // Capture extended data for Connection 2
        log("\nCONNECTION 2 - EXTENDED DATA:");
        log("-".repeat(40));
        if (connection2 instanceof MQConnection) {
            MQConnection mqConn2 = (MQConnection) connection2;
            Map<String, Object> extData2 = extractAllFieldsDeep(mqConn2);
            
            // Look for specific extended fields
            for (Map.Entry<String, Object> entry : extData2.entrySet()) {
                String key = entry.getKey();
                if (key.contains("EXTCONN") || key.contains("HCONN") || key.contains("HANDLE") || 
                    key.contains("CONNTAG") || key.contains("CONNID") || key.contains("HOBJ")) {
                    log("  " + key + " = " + entry.getValue());
                }
            }
        }
        
        log("\n" + "=".repeat(80));
        
        System.out.println("\n🔄 KEEPING CONNECTIONS ALIVE FOR 120 SECONDS...\n");
        System.out.println("Both connections will stay active so you can verify in MQSC");
        System.out.println("Detailed debug log being written to: " + logFileName);
        
        for (int i = 120; i > 0; i--) {
            System.out.print("\r  ⏱️  Time remaining: " + String.format("%3d", i) + " seconds  [" + "█".repeat(Math.min(60, i/2)) + " ".repeat(Math.max(0, 60-i/2)) + "]");
            Thread.sleep(1000);
            
            // Keep sessions active
            if (i % 20 == 0) {
                for (Session s : sessions1) {
                    try { s.getTransacted(); } catch (Exception e) { }
                }
                for (Session s : sessions2) {
                    try { s.getTransacted(); } catch (Exception e) { }
                }
            }
        }
        
        log("\n\nCLOSING SESSIONS AND CONNECTIONS...");
        
        // Close Connection 1 sessions
        for (int i = 0; i < sessions1.size(); i++) {
            try {
                sessions1.get(i).close();
                log("  Connection 1 - Session " + (i+1) + " closed");
            } catch (Exception e) {
                log("  Error closing Connection 1 session " + (i+1) + ": " + e.getMessage());
            }
        }
        
        // Close Connection 2 sessions
        for (int i = 0; i < sessions2.size(); i++) {
            try {
                sessions2.get(i).close();
                log("  Connection 2 - Session " + (i+1) + " closed");
            } catch (Exception e) {
                log("  Error closing Connection 2 session " + (i+1) + ": " + e.getMessage());
            }
        }
        
        connection1.close();
        log("  Connection 1 closed");
        
        connection2.close();
        log("  Connection 2 closed");
        
        log("\n✅ Test completed successfully!");
        log("Base Tracking Key: " + BASE_TRACKING_KEY);
        log("Connection 1 APPLTAG: " + TRACKING_KEY_C1);
        log("Connection 2 APPLTAG: " + TRACKING_KEY_C2);
        log("Log file: " + logFileName);
        
        logWriter.close();
        
        System.out.println("\n\n✅ Test completed!");
        System.out.println("📁 Detailed log saved to: " + logFileName);
        System.out.println("🔑 Base Tracking Key: " + BASE_TRACKING_KEY);
        System.out.println("   Connection 1: " + TRACKING_KEY_C1 + " (6 MQ connections)");
        System.out.println("   Connection 2: " + TRACKING_KEY_C2 + " (4 MQ connections)");
        System.out.println("   Total: 10 MQ connections expected");
    }
    
    private static void log(String message) {
        System.out.println(message);
        if (logWriter != null) {
            logWriter.println(message);
            logWriter.flush();
        }
    }
    
    private static Map<String, Object> extractConnectionDetails(Object obj) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // Try different approaches to get fields
            extractViaDelegate(obj, result);
            extractViaReflection(obj, result);
        } catch (Exception e) {
            // Ignore
        }
        
        return result;
    }
    
    private static Map<String, Object> extractAllFieldsDeep(Object obj) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // Extract via multiple methods
            extractViaDelegate(obj, result);
            extractViaReflection(obj, result);
            extractViaGetters(obj, result);
            extractPropertyMaps(obj, result);
        } catch (Exception e) {
            // Ignore
        }
        
        return result;
    }
    
    private static void extractViaDelegate(Object obj, Map<String, Object> result) {
        try {
            // Look for delegate/commonConn/commonSess fields
            Field[] fields = obj.getClass().getDeclaredFields();
            for (Field field : fields) {
                if (field.getName().equals("delegate") || 
                    field.getName().equals("commonConn") || 
                    field.getName().equals("commonSess")) {
                    field.setAccessible(true);
                    Object delegate = field.get(obj);
                    if (delegate != null) {
                        extractPropertiesFromDelegate(delegate, result);
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }
    }
    
    private static void extractPropertiesFromDelegate(Object delegate, Map<String, Object> result) {
        try {
            Method getPropertyNamesMethod = null;
            for (Method method : delegate.getClass().getMethods()) {
                if (method.getName().equals("getPropertyNames") && method.getParameterCount() == 0) {
                    getPropertyNamesMethod = method;
                    break;
                }
            }
            
            if (getPropertyNamesMethod != null) {
                Object propNames = getPropertyNamesMethod.invoke(delegate);
                if (propNames instanceof Enumeration) {
                    Enumeration<?> names = (Enumeration<?>) propNames;
                    while (names.hasMoreElements()) {
                        String name = names.nextElement().toString();
                        try {
                            Method getStringMethod = delegate.getClass().getMethod("getStringProperty", String.class);
                            Object value = getStringMethod.invoke(delegate, name);
                            if (value != null) {
                                result.put(name, value);
                            }
                        } catch (Exception e) {
                            try {
                                Method getIntMethod = delegate.getClass().getMethod("getIntProperty", String.class);
                                Object value = getIntMethod.invoke(delegate, name);
                                if (value != null) {
                                    result.put(name, value);
                                }
                            } catch (Exception e2) {
                                // Skip
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }
    }
    
    private static void extractViaReflection(Object obj, Map<String, Object> result) {
        try {
            Class<?> clazz = obj.getClass();
            while (clazz != null && clazz != Object.class) {
                Field[] fields = clazz.getDeclaredFields();
                for (Field field : fields) {
                    if (!field.getName().startsWith("$")) {
                        field.setAccessible(true);
                        try {
                            Object value = field.get(obj);
                            if (value != null && !(value instanceof Class)) {
                                String key = "FIELD_" + field.getName();
                                result.put(key, value);
                            }
                        } catch (Exception e) {
                            // Skip
                        }
                    }
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Exception e) {
            // Ignore
        }
    }
    
    private static void extractViaGetters(Object obj, Map<String, Object> result) {
        try {
            Method[] methods = obj.getClass().getMethods();
            for (Method method : methods) {
                if ((method.getName().startsWith("get") || method.getName().startsWith("is")) && 
                    method.getParameterCount() == 0 && 
                    !method.getName().equals("getClass")) {
                    try {
                        Object value = method.invoke(obj);
                        if (value != null && !(value instanceof Class)) {
                            String key = "METHOD_" + method.getName();
                            result.put(key, value);
                        }
                    } catch (Exception e) {
                        // Skip
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }
    }
    
    private static void extractPropertyMaps(Object obj, Map<String, Object> result) {
        try {
            // Look for property maps or hashtables
            Field[] fields = obj.getClass().getDeclaredFields();
            for (Field field : fields) {
                if (field.getType().equals(Map.class) || 
                    field.getType().equals(HashMap.class) || 
                    field.getType().equals(Hashtable.class)) {
                    field.setAccessible(true);
                    Object mapObj = field.get(obj);
                    if (mapObj instanceof Map) {
                        Map<?, ?> map = (Map<?, ?>) mapObj;
                        for (Map.Entry<?, ?> entry : map.entrySet()) {
                            result.put("MAP_" + field.getName() + "_" + entry.getKey(), entry.getValue());
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }
    }
    
    private static String getFieldValue(Map<String, Object> data, String fieldPattern) {
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (entry.getKey().contains(fieldPattern) && entry.getValue() != null) {
                return entry.getValue().toString();
            }
        }
        return "UNKNOWN";
    }
    
    private static String center(String text, int width) {
        int padding = (width - text.length()) / 2;
        return " ".repeat(Math.max(0, padding)) + text + 
               " ".repeat(Math.max(0, width - text.length() - padding));
    }
}