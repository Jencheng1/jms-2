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

public class QM1ParentChildDebug {
    
    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    
    public static void main(String[] args) throws Exception {
        System.out.println("================================================================================");
        System.out.println("     QM1-ONLY PARENT-CHILD DEBUG - SINGLE CONNECTION, MULTIPLE SESSIONS");
        System.out.println("================================================================================");
        System.out.println("Start time: " + sdf.format(new Date()));
        System.out.println("================================================================================\n");
        
        // Unique key for tracking
        long timestamp = System.currentTimeMillis();
        String TRACKING_KEY = "QM1TEST-" + timestamp;
        
        System.out.println("🔑 TRACKING KEY: " + TRACKING_KEY);
        System.out.println("   This will appear as APPLTAG in MQSC commands");
        System.out.println("\n" + "=".repeat(80) + "\n");
        
        // Create factory
        System.out.println("STEP 1: CREATING CONNECTION FACTORY");
        System.out.println("-".repeat(80));
        
        JmsFactoryFactory ff = JmsFactoryFactory.getInstance(WMQConstants.WMQ_PROVIDER);
        JmsConnectionFactory factory = ff.createConnectionFactory();
        
        // Configure for QM1 only
        System.out.println("Configuring for QM1 ONLY:");
        factory.setStringProperty(WMQConstants.WMQ_CCDTURL, "file:///workspace/ccdt/ccdt-qm1.json");
        System.out.println("  ✓ CCDTURL = file:///workspace/ccdt/ccdt-qm1.json (QM1 ONLY)");
        
        factory.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
        System.out.println("  ✓ CONNECTION_MODE = CLIENT");
        
        factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, WMQConstants.WMQ_CLIENT_RECONNECT);
        System.out.println("  ✓ RECONNECT = ENABLED");
        
        factory.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
        System.out.println("  ✓ AUTHENTICATION = MQCSP");
        
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TRACKING_KEY);
        System.out.println("  ✓ APPLICATIONNAME = " + TRACKING_KEY + " (APPLTAG in MQSC)");
        
        System.out.println("\n" + "=".repeat(80) + "\n");
        
        // Create parent connection
        System.out.println("STEP 2: CREATING PARENT CONNECTION TO QM1");
        System.out.println("-".repeat(80));
        
        System.out.println("Calling createConnection(\"app\", \"passw0rd\")...");
        Connection connection = factory.createConnection("app", "passw0rd");
        System.out.println("✅ CONNECTION CREATED SUCCESSFULLY!\n");
        
        // Extract ALL connection details
        System.out.println("RAW CONNECTION DETAILS:");
        System.out.println("  Object: " + connection);
        System.out.println("  Class: " + connection.getClass().getName());
        System.out.println("  HashCode: " + connection.hashCode());
        
        String connectionId = "UNKNOWN";
        String queueManager = "UNKNOWN";
        String host = "UNKNOWN";
        String port = "UNKNOWN";
        
        if (connection instanceof MQConnection) {
            MQConnection mqConn = (MQConnection) connection;
            
            System.out.println("\nEXTRACTING INTERNAL CONNECTION DATA:");
            System.out.println("-".repeat(40));
            
            // Get all fields via reflection
            Map<String, Object> connectionData = extractAllFields(mqConn);
            
            // Find key fields
            for (Map.Entry<String, Object> entry : connectionData.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                
                if (value != null) {
                    String valStr = value.toString();
                    
                    // Extract specific values
                    if (key.contains("CONNECTION_ID") || key.equals("XMSC_WMQ_CONNECTION_ID")) {
                        connectionId = valStr;
                        System.out.println("  🔗 CONNECTION_ID: " + connectionId);
                    }
                    else if (key.contains("RESOLVED_QUEUE_MANAGER") || key.equals("XMSC_WMQ_RESOLVED_QUEUE_MANAGER")) {
                        queueManager = valStr;
                        System.out.println("  📍 QUEUE_MANAGER: " + queueManager);
                    }
                    else if (key.contains("HOST_NAME") || key.equals("XMSC_WMQ_HOST_NAME")) {
                        host = valStr;
                        System.out.println("  🌐 HOST: " + host);
                    }
                    else if (key.contains("PORT") || key.equals("XMSC_WMQ_PORT")) {
                        port = valStr;
                        System.out.println("  🔌 PORT: " + port);
                    }
                    else if (key.contains("APPNAME") || key.equals("XMSC_WMQ_APPNAME")) {
                        System.out.println("  🏷️ APPNAME: " + valStr);
                    }
                    else if (key.contains("CHANNEL") && valStr.contains("SVRCONN")) {
                        System.out.println("  📡 CHANNEL: " + valStr);
                    }
                }
            }
        }
        
        System.out.println("\n╔" + "═".repeat(78) + "╗");
        System.out.println("║" + center("PARENT CONNECTION ESTABLISHED TO QM1", 78) + "║");
        System.out.println("╠" + "═".repeat(78) + "╣");
        System.out.println("║ CONNECTION_ID: " + String.format("%-61s", connectionId) + "║");
        System.out.println("║ QUEUE_MANAGER: " + String.format("%-61s", queueManager) + "║");
        System.out.println("║ HOST: " + String.format("%-71s", host) + "║");
        System.out.println("║ PORT: " + String.format("%-71s", port) + "║");
        System.out.println("║ TRACKING_KEY: " + String.format("%-62s", TRACKING_KEY) + "║");
        System.out.println("╚" + "═".repeat(78) + "╝");
        
        connection.start();
        System.out.println("\n✅ Connection started\n");
        
        System.out.println("=".repeat(80) + "\n");
        
        // Create 5 sessions and verify they're on QM1
        System.out.println("STEP 3: CREATING 5 CHILD SESSIONS FROM PARENT CONNECTION");
        System.out.println("=".repeat(80));
        
        List<Map<String, String>> sessionDetails = new ArrayList<>();
        
        for (int i = 1; i <= 5; i++) {
            System.out.println("\n" + "─".repeat(60));
            System.out.println("CREATING SESSION #" + i);
            System.out.println("─".repeat(60));
            
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            System.out.println("✅ Session #" + i + " created");
            
            Map<String, String> sessionInfo = new HashMap<>();
            sessionInfo.put("number", String.valueOf(i));
            sessionInfo.put("parent_connection_id", connectionId);
            sessionInfo.put("parent_queue_manager", queueManager);
            
            if (session instanceof MQSession) {
                MQSession mqSession = (MQSession) session;
                
                System.out.println("\nSESSION #" + i + " INTERNAL DATA:");
                Map<String, Object> sessionData = extractAllFields(mqSession);
                
                // Check key fields match parent
                for (Map.Entry<String, Object> entry : sessionData.entrySet()) {
                    String key = entry.getKey();
                    Object value = entry.getValue();
                    
                    if (value != null) {
                        String valStr = value.toString();
                        
                        if (key.contains("CONNECTION_ID") || key.equals("XMSC_WMQ_CONNECTION_ID")) {
                            System.out.println("  CONNECTION_ID: " + valStr);
                            boolean matches = valStr.equals(connectionId);
                            System.out.println("    → Matches Parent: " + (matches ? "✅ YES" : "❌ NO"));
                            sessionInfo.put("connection_id", valStr);
                            sessionInfo.put("matches_parent", String.valueOf(matches));
                        }
                        else if (key.contains("RESOLVED_QUEUE_MANAGER")) {
                            System.out.println("  QUEUE_MANAGER: " + valStr);
                            boolean isQM1 = valStr.equals("QM1");
                            System.out.println("    → Is QM1: " + (isQM1 ? "✅ YES" : "❌ NO"));
                            sessionInfo.put("queue_manager", valStr);
                        }
                        else if (key.contains("HOST_NAME")) {
                            System.out.println("  HOST: " + valStr);
                            sessionInfo.put("host", valStr);
                        }
                    }
                }
            }
            
            // Send test message
            System.out.println("\nSending test message from Session #" + i + ":");
            javax.jms.Queue queue = session.createQueue("queue:///UNIFORM.QUEUE");
            MessageProducer producer = session.createProducer(queue);
            
            TextMessage msg = session.createTextMessage("Test from Session #" + i);
            msg.setStringProperty("SessionNumber", String.valueOf(i));
            msg.setStringProperty("TrackingKey", TRACKING_KEY);
            msg.setStringProperty("ParentConnectionId", connectionId);
            msg.setStringProperty("QueueManager", queueManager);
            msg.setJMSCorrelationID(TRACKING_KEY + "-S" + i);
            
            producer.send(msg);
            String msgId = msg.getJMSMessageID();
            System.out.println("  Message sent with ID: " + msgId);
            
            // Analyze message ID for QM
            if (msgId != null && msgId.startsWith("ID:")) {
                String hex = toHex(msgId);
                if (hex.contains("514d31")) {
                    System.out.println("  ✅ Message ID confirms QM1 (contains 514d31)");
                }
            }
            
            producer.close();
            sessionDetails.add(sessionInfo);
        }
        
        System.out.println("\n" + "=".repeat(80) + "\n");
        
        // Summary
        System.out.println("╔" + "═".repeat(78) + "╗");
        System.out.println("║" + center("PARENT-CHILD VERIFICATION SUMMARY", 78) + "║");
        System.out.println("╠" + "═".repeat(78) + "╣");
        System.out.println("║ PARENT CONNECTION:                                                          ║");
        System.out.println("║   Connection ID: " + String.format("%-59s", connectionId.length() > 59 ? connectionId.substring(0, 59) : connectionId) + "║");
        System.out.println("║   Queue Manager: " + String.format("%-59s", queueManager) + "║");
        System.out.println("║                                                                              ║");
        System.out.println("║ CHILD SESSIONS:                                                             ║");
        
        boolean allOnQM1 = true;
        boolean allMatchParent = true;
        
        for (Map<String, String> session : sessionDetails) {
            String num = session.get("number");
            String qm = session.get("queue_manager");
            String matches = session.get("matches_parent");
            
            System.out.println("║   Session #" + num + ": QM=" + qm + ", Matches Parent=" + matches + String.format("%" + (58 - qm.length() - matches.length()) + "s", "") + "║");
            
            if (!qm.equals("QM1")) allOnQM1 = false;
            if (!"true".equals(matches)) allMatchParent = false;
        }
        
        System.out.println("║                                                                              ║");
        
        if (allOnQM1 && allMatchParent) {
            System.out.println("║ ✅ SUCCESS: All 5 sessions on QM1 with same connection ID as parent!        ║");
        } else {
            System.out.println("║ ❌ FAILURE: Sessions not properly linked to parent!                         ║");
        }
        
        System.out.println("╚" + "═".repeat(78) + "╝");
        
        System.out.println("\n📊 MQSC VERIFICATION COMMANDS:");
        System.out.println("─".repeat(60));
        System.out.println("Check connection on QM1:");
        System.out.println("  docker exec qm1 bash -c \"echo 'DIS CONN(*) WHERE(APPLTAG EQ \\'" + TRACKING_KEY + "\\') ALL' | runmqsc QM1\"");
        System.out.println("\nCheck all connections on QM1:");
        System.out.println("  docker exec qm1 bash -c \"echo 'DIS CONN(*) CHANNEL CONNAME APPLTAG' | runmqsc QM1\"");
        
        System.out.println("\n🔄 Keeping connection alive for 20 seconds for MQSC verification...\n");
        
        for (int i = 20; i > 0; i--) {
            System.out.print("\r  Time remaining: " + i + " seconds  ");
            Thread.sleep(1000);
        }
        
        System.out.println("\n\nClosing connection...");
        connection.close();
        System.out.println("✅ Test completed successfully!");
        System.out.println("\nTracking Key for verification: " + TRACKING_KEY);
    }
    
    private static Map<String, Object> extractAllFields(Object obj) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // Check if object has a delegate field (common in MQ classes)
            Field delegateField = null;
            try {
                delegateField = obj.getClass().getDeclaredField("delegate");
                delegateField.setAccessible(true);
                Object delegate = delegateField.get(obj);
                if (delegate != null) {
                    extractFieldsFromDelegate(delegate, result);
                }
            } catch (NoSuchFieldException e) {
                // No delegate field
            }
            
            // Also try commonConn field
            try {
                Field commonConnField = obj.getClass().getDeclaredField("commonConn");
                commonConnField.setAccessible(true);
                Object commonConn = commonConnField.get(obj);
                if (commonConn != null) {
                    extractFieldsFromDelegate(commonConn, result);
                }
            } catch (NoSuchFieldException e) {
                // No commonConn field
            }
            
            // Also try commonSess field for sessions
            try {
                Field commonSessField = obj.getClass().getDeclaredField("commonSess");
                commonSessField.setAccessible(true);
                Object commonSess = commonSessField.get(obj);
                if (commonSess != null) {
                    extractFieldsFromDelegate(commonSess, result);
                }
            } catch (NoSuchFieldException e) {
                // No commonSess field
            }
            
        } catch (Exception e) {
            // Ignore
        }
        
        return result;
    }
    
    private static void extractFieldsFromDelegate(Object delegate, Map<String, Object> result) {
        try {
            // Get the propertyMap from JmsPropertyContextImpl
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
                            // Get property value
                            Method getPropertyMethod = delegate.getClass().getMethod("getStringProperty", String.class);
                            Object value = getPropertyMethod.invoke(delegate, name);
                            if (value != null) {
                                result.put(name, value);
                            }
                        } catch (Exception e) {
                            try {
                                Method getPropertyMethod = delegate.getClass().getMethod("getIntProperty", String.class);
                                Object value = getPropertyMethod.invoke(delegate, name);
                                if (value != null) {
                                    result.put(name, value);
                                }
                            } catch (Exception e2) {
                                // Skip this property
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }
    }
    
    private static String center(String text, int width) {
        int padding = (width - text.length()) / 2;
        return " ".repeat(Math.max(0, padding)) + text + 
               " ".repeat(Math.max(0, width - text.length() - padding));
    }
    
    private static String toHex(String str) {
        if (str == null) return "null";
        StringBuilder hex = new StringBuilder();
        for (char c : str.toCharArray()) {
            hex.append(String.format("%02x", (int) c));
        }
        return hex.toString();
    }
}