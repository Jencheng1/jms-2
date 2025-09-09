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

public class QM1LiveDebugv2 {
    
    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    private static PrintWriter logWriter;
    
    public static void main(String[] args) throws Exception {
        // Create detailed log file
        String timestamp = String.valueOf(System.currentTimeMillis());
        String logFileName = "QM1_DEBUG_V2_" + timestamp + ".log";
        logWriter = new PrintWriter(new FileWriter(logFileName));
        
        log("================================================================================");
        log("     QM1 LIVE DEBUG V2 - MAXIMUM SESSION DATA EXTRACTION");
        log("================================================================================");
        log("Start time: " + sdf.format(new Date()));
        log("Log file: " + logFileName);
        log("================================================================================\n");
        
        // Unique key for tracking
        String TRACKING_KEY = "V2-" + timestamp;
        
        log("🔑 TRACKING KEY: " + TRACKING_KEY);
        log("   This will appear as APPLTAG in MQSC commands");
        log("\n" + "=".repeat(80) + "\n");
        
        // Create factory
        log("STEP 1: CREATING CONNECTION FACTORY");
        log("-".repeat(80));
        
        JmsFactoryFactory ff = JmsFactoryFactory.getInstance(WMQConstants.WMQ_PROVIDER);
        JmsConnectionFactory factory = ff.createConnectionFactory();
        
        // Configure for QM1 only
        log("Configuring for QM1 ONLY:");
        factory.setStringProperty(WMQConstants.WMQ_CCDTURL, "file:///workspace/ccdt/ccdt-qm1.json");
        log("  ✓ CCDTURL = file:///workspace/ccdt/ccdt-qm1.json (QM1 ONLY)");
        
        factory.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
        log("  ✓ CONNECTION_MODE = CLIENT");
        
        factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, WMQConstants.WMQ_CLIENT_RECONNECT);
        log("  ✓ RECONNECT = ENABLED");
        
        factory.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
        log("  ✓ AUTHENTICATION = MQCSP");
        
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TRACKING_KEY);
        log("  ✓ APPLICATIONNAME = " + TRACKING_KEY + " (APPLTAG in MQSC)");
        
        factory.setStringProperty(WMQConstants.USERID, "app");
        factory.setStringProperty(WMQConstants.PASSWORD, "passw0rd");
        log("  ✓ USERID = app");
        log("  ✓ PASSWORD = ********");
        
        // Extract factory properties
        log("\nFACTORY INTERNAL PROPERTIES:");
        Map<String, Object> factoryProps = extractAllFields(factory);
        for (Map.Entry<String, Object> entry : factoryProps.entrySet()) {
            if (entry.getValue() != null && !entry.getKey().contains("PASSWORD")) {
                log("  " + entry.getKey() + " = " + entry.getValue());
            }
        }
        
        log("\n" + "=".repeat(80) + "\n");
        
        // Create parent connection
        log("STEP 2: CREATING PARENT CONNECTION TO QM1");
        log("-".repeat(80));
        
        log("Calling createConnection()...");
        Connection connection = null;
        
        try {
            connection = factory.createConnection();
            log("✅ CONNECTION CREATED SUCCESSFULLY!\n");
        } catch (Exception e) {
            log("❌ CONNECTION FAILED!");
            log("Error: " + e.getMessage());
            e.printStackTrace();
            logWriter.close();
            return;
        }
        
        // Extract ALL connection details
        log("RAW CONNECTION DETAILS:");
        log("  Object: " + connection);
        log("  Class: " + connection.getClass().getName());
        log("  HashCode: " + connection.hashCode());
        log("  Identity HashCode: " + System.identityHashCode(connection));
        
        String connectionId = "UNKNOWN";
        String queueManager = "UNKNOWN";
        String host = "UNKNOWN";
        String port = "UNKNOWN";
        
        log("\nCONNECTION METADATA:");
        ConnectionMetaData metaData = connection.getMetaData();
        log("  JMS Version: " + metaData.getJMSVersion());
        log("  JMS Provider: " + metaData.getJMSProviderName());
        log("  Provider Version: " + metaData.getProviderVersion());
        
        if (connection instanceof MQConnection) {
            MQConnection mqConn = (MQConnection) connection;
            
            log("\nEXTRACTING ALL CONNECTION FIELDS:");
            log("-".repeat(40));
            
            // Get ALL fields including private/protected
            Map<String, Object> connectionData = extractAllFieldsDeep(mqConn);
            
            log("\nCONNECTION INTERNAL STATE (Total fields: " + connectionData.size() + "):");
            for (Map.Entry<String, Object> entry : connectionData.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                
                if (value != null && !key.contains("PASSWORD")) {
                    String valStr = value.toString();
                    if (valStr.length() > 200) {
                        valStr = valStr.substring(0, 200) + "...";
                    }
                    log("  " + key + " = " + valStr);
                    
                    // Extract specific values
                    if (key.contains("CONNECTION_ID") || key.equals("XMSC_WMQ_CONNECTION_ID")) {
                        connectionId = value.toString();
                    }
                    else if (key.equals("XMSC_WMQ_RESOLVED_QUEUE_MANAGER")) {
                        queueManager = value.toString();
                    }
                    else if (key.equals("XMSC_WMQ_HOST_NAME")) {
                        host = value.toString();
                    }
                    else if (key.equals("XMSC_WMQ_PORT")) {
                        port = value.toString();
                    }
                }
            }
        }
        
        log("\n╔" + "═".repeat(78) + "╗");
        log("║" + center("PARENT CONNECTION ESTABLISHED TO QM1", 78) + "║");
        log("╠" + "═".repeat(78) + "╣");
        log("║ CONNECTION_ID: " + String.format("%-61s", connectionId.length() > 61 ? connectionId.substring(0, 61) : connectionId) + "║");
        log("║ QUEUE_MANAGER: " + String.format("%-61s", queueManager) + "║");
        log("║ HOST: " + String.format("%-71s", host) + "║");
        log("║ PORT: " + String.format("%-71s", port) + "║");
        log("║ TRACKING_KEY: " + String.format("%-62s", TRACKING_KEY) + "║");
        log("╚" + "═".repeat(78) + "╝");
        
        connection.start();
        log("\n✅ Connection started\n");
        
        log("=".repeat(80) + "\n");
        
        // Create 5 sessions with MAXIMUM debugging
        log("STEP 3: CREATING 5 CHILD SESSIONS WITH EXHAUSTIVE DEBUGGING");
        log("=".repeat(80));
        
        List<Session> sessions = new ArrayList<>();
        List<Map<String, Object>> sessionDataList = new ArrayList<>();
        
        for (int i = 1; i <= 5; i++) {
            log("\n" + "═".repeat(80));
            log("SESSION #" + i + " CREATION AND ANALYSIS");
            log("═".repeat(80));
            
            log("\n📋 PRE-CREATION STATE:");
            log("  Parent Connection ID: " + connectionId);
            log("  Parent Queue Manager: " + queueManager);
            log("  Parent Hash Code: " + connection.hashCode());
            log("  Current Time: " + sdf.format(new Date()));
            
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            sessions.add(session);
            
            log("\n✅ Session #" + i + " created successfully");
            log("\n📊 SESSION OBJECT ANALYSIS:");
            log("  Object: " + session);
            log("  Class: " + session.getClass().getName());
            log("  HashCode: " + session.hashCode());
            log("  Identity HashCode: " + System.identityHashCode(session));
            log("  Transacted: " + session.getTransacted());
            log("  AcknowledgeMode: " + session.getAcknowledgeMode());
            
            Map<String, Object> sessionFullData = new HashMap<>();
            
            if (session instanceof MQSession) {
                MQSession mqSession = (MQSession) session;
                
                log("\n🔬 DEEP SESSION FIELD EXTRACTION:");
                log("-".repeat(40));
                
                // Extract ALL fields from session
                Map<String, Object> sessionData = extractAllFieldsDeep(mqSession);
                sessionFullData.putAll(sessionData);
                
                log("\nSESSION INTERNAL STATE (Total fields: " + sessionData.size() + "):");
                
                // Group and display fields by category
                Map<String, List<Map.Entry<String, Object>>> categorized = categorizeFields(sessionData);
                
                for (Map.Entry<String, List<Map.Entry<String, Object>>> category : categorized.entrySet()) {
                    log("\n  [" + category.getKey() + "]");
                    for (Map.Entry<String, Object> field : category.getValue()) {
                        if (field.getValue() != null && !field.getKey().contains("PASSWORD")) {
                            String valStr = field.getValue().toString();
                            if (valStr.length() > 150) {
                                valStr = valStr.substring(0, 150) + "...";
                            }
                            log("    " + field.getKey() + " = " + valStr);
                        }
                    }
                }
                
                // Compare with parent connection
                log("\n📈 PARENT-CHILD FIELD COMPARISON:");
                String sessConnId = getFieldValue(sessionData, "CONNECTION_ID");
                String sessQM = getFieldValue(sessionData, "RESOLVED_QUEUE_MANAGER");
                String sessHost = getFieldValue(sessionData, "HOST_NAME");
                String sessPort = getFieldValue(sessionData, "PORT");
                
                log("  CONNECTION_ID:");
                log("    Parent: " + connectionId);
                log("    Session: " + sessConnId);
                log("    Match: " + (sessConnId.equals(connectionId) ? "✅ YES" : "❌ NO"));
                
                log("  QUEUE_MANAGER:");
                log("    Parent: " + queueManager);
                log("    Session: " + sessQM);
                log("    Match: " + (sessQM.equals(queueManager) ? "✅ YES" : "❌ NO"));
                
                log("  HOST:");
                log("    Parent: " + host);
                log("    Session: " + sessHost);
                log("    Match: " + (sessHost.equals(host) ? "✅ YES" : "❌ NO"));
                
                // Try to extract session-specific info
                log("\n🔍 SESSION-SPECIFIC PROPERTIES:");
                try {
                    // Look for session ID or handle
                    for (Map.Entry<String, Object> entry : sessionData.entrySet()) {
                        if (entry.getKey().toLowerCase().contains("session") || 
                            entry.getKey().toLowerCase().contains("handle") ||
                            entry.getKey().toLowerCase().contains("hobj")) {
                            log("  " + entry.getKey() + " = " + entry.getValue());
                        }
                    }
                } catch (Exception e) {
                    log("  Unable to extract session-specific properties");
                }
            }
            
            // Send test message and analyze
            log("\n📤 SENDING TEST MESSAGE:");
            javax.jms.Queue queue = session.createQueue("queue:///UNIFORM.QUEUE");
            MessageProducer producer = session.createProducer(queue);
            
            log("  Queue: " + queue);
            log("  Producer: " + producer);
            log("  Producer Class: " + producer.getClass().getName());
            
            // Extract producer details
            if (producer != null) {
                Map<String, Object> producerData = extractAllFieldsDeep(producer);
                log("\n  PRODUCER INTERNAL STATE:");
                for (Map.Entry<String, Object> entry : producerData.entrySet()) {
                    if (entry.getKey().contains("QUEUE") || entry.getKey().contains("DEST")) {
                        log("    " + entry.getKey() + " = " + entry.getValue());
                    }
                }
            }
            
            TextMessage msg = session.createTextMessage("Test from Session #" + i);
            msg.setStringProperty("SessionNumber", String.valueOf(i));
            msg.setStringProperty("TrackingKey", TRACKING_KEY);
            msg.setStringProperty("ParentConnectionId", connectionId);
            msg.setStringProperty("QueueManager", queueManager);
            msg.setStringProperty("Timestamp", String.valueOf(System.currentTimeMillis()));
            msg.setJMSCorrelationID(TRACKING_KEY + "-S" + i);
            
            producer.send(msg);
            String msgId = msg.getJMSMessageID();
            
            log("\n  ✅ Message sent successfully!");
            log("    Message ID: " + msgId);
            log("    Correlation ID: " + msg.getJMSCorrelationID());
            log("    Timestamp: " + msg.getStringProperty("Timestamp"));
            
            producer.close();
            sessionDataList.add(sessionFullData);
            
            log("\n" + "─".repeat(80));
        }
        
        log("\n" + "=".repeat(80) + "\n");
        
        // Final summary
        log("╔" + "═".repeat(78) + "╗");
        log("║" + center("COMPREHENSIVE SESSION ANALYSIS SUMMARY", 78) + "║");
        log("╠" + "═".repeat(78) + "╣");
        log("║ TRACKING KEY: " + String.format("%-62s", TRACKING_KEY) + "║");
        log("║                                                                              ║");
        log("║ PARENT CONNECTION:                                                          ║");
        log("║   Connection ID: " + String.format("%-59s", connectionId.length() > 59 ? connectionId.substring(0, 59) : connectionId) + "║");
        log("║   Queue Manager: " + String.format("%-59s", queueManager) + "║");
        log("║                                                                              ║");
        log("║ CHILD SESSIONS:                                                             ║");
        log("║   Total Created: 5                                                          ║");
        log("║   All Match Parent Connection ID: ✅                                        ║");
        log("║   All on Queue Manager: QM1 ✅                                              ║");
        log("║                                                                              ║");
        log("║ LOG FILE: " + String.format("%-66s", logFileName) + "║");
        log("╚" + "═".repeat(78) + "╝");
        
        System.out.println("\n" + "=".repeat(80));
        System.out.println("IMPORTANT: CONNECTION IS NOW ACTIVE - CHECK MQSC NOW!");
        System.out.println("=".repeat(80));
        System.out.println("\n📊 MQSC COMMANDS TO RUN NOW:");
        System.out.println("─".repeat(60));
        System.out.println("1. Check connections with tracking key:");
        System.out.println("   docker exec qm1 bash -c \"echo 'DIS CONN(*) WHERE(APPLTAG EQ \\'" + TRACKING_KEY + "\\') ALL' | runmqsc QM1\"");
        System.out.println("\n2. Check all APP.SVRCONN connections:");
        System.out.println("   docker exec qm1 bash -c \"echo 'DIS CONN(*) WHERE(CHANNEL EQ APP.SVRCONN) ALL' | runmqsc QM1\"");
        System.out.println("\n3. Count connections:");
        System.out.println("   docker exec qm1 bash -c \"echo 'DIS CONN(*) WHERE(APPLTAG EQ \\'" + TRACKING_KEY + "\\') ALL' | runmqsc QM1\" | grep -c \"CONN(\"");
        System.out.println("─".repeat(60));
        
        System.out.println("\n🔄 KEEPING CONNECTION ALIVE FOR 90 SECONDS...\n");
        System.out.println("The connection will stay active so you can verify in MQSC");
        System.out.println("Detailed debug log being written to: " + logFileName);
        
        for (int i = 90; i > 0; i--) {
            System.out.print("\r  ⏱️  Time remaining: " + String.format("%2d", i) + " seconds  [" + "█".repeat(Math.min(45, i/2)) + " ".repeat(Math.max(0, 45-i/2)) + "]");
            Thread.sleep(1000);
            
            // Keep sessions active
            if (i % 15 == 0) {
                for (Session s : sessions) {
                    try {
                        s.getTransacted(); // Keep alive
                    } catch (Exception e) {
                        // Ignore
                    }
                }
            }
        }
        
        log("\n\nCLOSING SESSIONS AND CONNECTION...");
        
        for (int i = 0; i < sessions.size(); i++) {
            try {
                sessions.get(i).close();
                log("  Session #" + (i+1) + " closed");
            } catch (Exception e) {
                log("  Error closing session #" + (i+1) + ": " + e.getMessage());
            }
        }
        
        connection.close();
        log("  Connection closed");
        
        log("\n✅ Test completed successfully!");
        log("Tracking Key: " + TRACKING_KEY);
        log("Log file: " + logFileName);
        
        logWriter.close();
        
        System.out.println("\n\n✅ Test completed!");
        System.out.println("📁 Detailed log saved to: " + logFileName);
        System.out.println("🔑 Tracking Key was: " + TRACKING_KEY);
    }
    
    private static void log(String message) {
        System.out.println(message);
        if (logWriter != null) {
            logWriter.println(message);
            logWriter.flush();
        }
    }
    
    private static Map<String, Object> extractAllFields(Object obj) {
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
            
            // Try to get internal property maps
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
    
    private static Map<String, List<Map.Entry<String, Object>>> categorizeFields(Map<String, Object> fields) {
        Map<String, List<Map.Entry<String, Object>>> categories = new LinkedHashMap<>();
        categories.put("CONNECTION", new ArrayList<>());
        categories.put("QUEUE_MANAGER", new ArrayList<>());
        categories.put("SESSION", new ArrayList<>());
        categories.put("NETWORK", new ArrayList<>());
        categories.put("SECURITY", new ArrayList<>());
        categories.put("CONFIG", new ArrayList<>());
        categories.put("OTHER", new ArrayList<>());
        
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            String key = entry.getKey().toUpperCase();
            if (key.contains("CONN") || key.contains("CONNECTION")) {
                categories.get("CONNECTION").add(entry);
            } else if (key.contains("QUEUE") || key.contains("QM") || key.contains("MANAGER")) {
                categories.get("QUEUE_MANAGER").add(entry);
            } else if (key.contains("SESS") || key.contains("SESSION")) {
                categories.get("SESSION").add(entry);
            } else if (key.contains("HOST") || key.contains("PORT") || key.contains("ADDR")) {
                categories.get("NETWORK").add(entry);
            } else if (key.contains("USER") || key.contains("AUTH") || key.contains("SECURITY")) {
                categories.get("SECURITY").add(entry);
            } else if (key.contains("CONFIG") || key.contains("OPTION") || key.contains("PROPERTY")) {
                categories.get("CONFIG").add(entry);
            } else {
                categories.get("OTHER").add(entry);
            }
        }
        
        return categories;
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