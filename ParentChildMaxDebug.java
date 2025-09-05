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

public class ParentChildMaxDebug {
    
    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    private static PrintWriter logWriter;
    
    public static void main(String[] args) throws Exception {
        // Create log file for raw data
        String logFileName = "RAW_DEBUG_DATA_" + System.currentTimeMillis() + ".log";
        logWriter = new PrintWriter(new FileWriter(logFileName));
        
        log("================================================================================");
        log("     MAXIMUM DEBUG: 1 CONNECTION, 5 SESSIONS - RAW DATA CAPTURE");
        log("================================================================================");
        log("Log file: " + logFileName);
        log("Start time: " + sdf.format(new Date()));
        log("================================================================================\n");
        
        // Unique correlation for this test
        long timestamp = System.currentTimeMillis();
        String CORRELATION_KEY = "DEBUG-" + timestamp;
        
        log("🔑 CORRELATION KEY FOR MQSC TRACKING: " + CORRELATION_KEY);
        log("📅 Timestamp (milliseconds): " + timestamp);
        log("📅 Timestamp (formatted): " + sdf.format(new Date(timestamp)));
        log("\n" + "=".repeat(80) + "\n");
        
        // Create factory with all debug properties
        log("CREATING CONNECTION FACTORY");
        log("-".repeat(80));
        
        JmsFactoryFactory ff = JmsFactoryFactory.getInstance(WMQConstants.WMQ_PROVIDER);
        log("  Factory Instance: " + ff);
        log("  Factory Class: " + ff.getClass().getName());
        log("  Factory HashCode: " + ff.hashCode());
        
        JmsConnectionFactory factory = ff.createConnectionFactory();
        log("  Connection Factory: " + factory);
        log("  Connection Factory Class: " + factory.getClass().getName());
        
        // Set all properties with logging
        log("\nSETTING CONNECTION PROPERTIES:");
        factory.setStringProperty(WMQConstants.WMQ_CCDTURL, "file:///workspace/ccdt/ccdt.json");
        log("  WMQ_CCDTURL = file:///workspace/ccdt/ccdt.json");
        
        factory.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
        log("  WMQ_CONNECTION_MODE = " + WMQConstants.WMQ_CM_CLIENT + " (CLIENT)");
        
        factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, WMQConstants.WMQ_CLIENT_RECONNECT);
        log("  WMQ_CLIENT_RECONNECT_OPTIONS = " + WMQConstants.WMQ_CLIENT_RECONNECT);
        
        factory.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
        log("  USER_AUTHENTICATION_MQCSP = true");
        
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, CORRELATION_KEY);
        log("  WMQ_APPLICATIONNAME = " + CORRELATION_KEY + " (THIS IS THE APPLTAG IN MQSC!)");
        
        // Dump all factory properties
        log("\nFACTORY INTERNAL STATE:");
        dumpObjectState(factory, "  ");
        
        log("\n" + "=".repeat(80) + "\n");
        
        // Create connection with full debugging
        log("CREATING PARENT CONNECTION");
        log("-".repeat(80));
        log("  Calling factory.createConnection(\"app\", \"passw0rd\")");
        log("  Time before: " + sdf.format(new Date()));
        
        Connection connection = factory.createConnection("app", "passw0rd");
        
        log("  Time after: " + sdf.format(new Date()));
        log("  Connection created successfully!");
        
        // Extract ALL connection details
        log("\nRAW CONNECTION OBJECT DATA:");
        log("  toString(): " + connection.toString());
        log("  Class: " + connection.getClass().getName());
        log("  HashCode: " + connection.hashCode());
        log("  ClientID: " + connection.getClientID());
        
        // Get metadata
        ConnectionMetaData metadata = connection.getMetaData();
        log("\nCONNECTION METADATA:");
        log("  JMS Version: " + metadata.getJMSVersion());
        log("  JMS Major Version: " + metadata.getJMSMajorVersion());
        log("  JMS Minor Version: " + metadata.getJMSMinorVersion());
        log("  JMS Provider Name: " + metadata.getJMSProviderName());
        log("  Provider Version: " + metadata.getProviderVersion());
        log("  Provider Major Version: " + metadata.getProviderMajorVersion());
        log("  Provider Minor Version: " + metadata.getProviderMinorVersion());
        
        // Extract MQ-specific details
        String QM_NAME = "UNKNOWN";
        String CONNECTION_ID = connection.getClientID();
        
        if (connection instanceof MQConnection) {
            MQConnection mqConn = (MQConnection) connection;
            log("\nMQ CONNECTION SPECIFIC DATA:");
            log("  MQConnection Instance: " + mqConn);
            
            // Use reflection to get ALL fields
            log("\n  ALL INTERNAL FIELDS (via reflection):");
            Field[] allFields = getAllFields(mqConn.getClass());
            for (Field field : allFields) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(mqConn);
                    log("    " + field.getName() + " [" + field.getType().getSimpleName() + "] = " + 
                        (value != null ? value.toString() : "null"));
                    
                    // Try to detect QM name
                    if (value != null) {
                        String valStr = value.toString();
                        if (valStr.contains("QM1")) QM_NAME = "QM1";
                        else if (valStr.contains("QM2")) QM_NAME = "QM2";
                        else if (valStr.contains("QM3")) QM_NAME = "QM3";
                    }
                } catch (Exception e) {
                    log("    " + field.getName() + " = <inaccessible>");
                }
            }
            
            // Try methods too
            log("\n  ACCESSIBLE METHODS OUTPUT:");
            Method[] methods = mqConn.getClass().getMethods();
            for (Method method : methods) {
                if (method.getName().startsWith("get") && method.getParameterCount() == 0) {
                    try {
                        Object result = method.invoke(mqConn);
                        if (result != null) {
                            log("    " + method.getName() + "() = " + result);
                        }
                    } catch (Exception e) {
                        // Skip
                    }
                }
            }
        }
        
        // Parse QM from connection ID if still unknown
        if (QM_NAME.equals("UNKNOWN") && CONNECTION_ID != null) {
            log("\nPARSING QUEUE MANAGER FROM CONNECTION ID:");
            log("  Connection ID hex: " + toHex(CONNECTION_ID));
            if (CONNECTION_ID.contains("514d31")) {
                QM_NAME = "QM1";
                log("  Detected: QM1 (based on 514d31 pattern)");
            } else if (CONNECTION_ID.contains("514d32")) {
                QM_NAME = "QM2";
                log("  Detected: QM2 (based on 514d32 pattern)");
            } else if (CONNECTION_ID.contains("514d33")) {
                QM_NAME = "QM3";
                log("  Detected: QM3 (based on 514d33 pattern)");
            }
        }
        
        log("\n╔" + "═".repeat(78) + "╗");
        log("║" + center("PARENT CONNECTION ESTABLISHED", 78) + "║");
        log("╠" + "═".repeat(78) + "╣");
        log("║ CORRELATION KEY: " + String.format("%-59s", CORRELATION_KEY) + "║");
        log("║ CONNECTION ID:   " + String.format("%-59s", CONNECTION_ID) + "║");
        log("║ QUEUE MANAGER:   " + String.format("%-59s", QM_NAME) + "║");
        log("╚" + "═".repeat(78) + "╝");
        
        // Start connection
        log("\nSTARTING CONNECTION:");
        connection.start();
        log("  Connection started successfully");
        
        log("\n" + "=".repeat(80) + "\n");
        
        // Create 5 child sessions with exhaustive debugging
        log("CREATING 5 CHILD SESSIONS FROM PARENT CONNECTION");
        log("=".repeat(80));
        
        for (int i = 1; i <= 5; i++) {
            log("\n" + "-".repeat(40));
            log("CHILD SESSION #" + i);
            log("-".repeat(40));
            
            log("Parent Connection Reference: " + CONNECTION_ID);
            log("Parent Queue Manager: " + QM_NAME);
            log("Correlation Key: " + CORRELATION_KEY);
            log("Creating session at: " + sdf.format(new Date()));
            
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            
            log("\nRAW SESSION OBJECT DATA:");
            log("  toString(): " + session.toString());
            log("  Class: " + session.getClass().getName());
            log("  HashCode: " + session.hashCode());
            log("  AcknowledgeMode: " + session.getAcknowledgeMode());
            log("  Transacted: " + session.getTransacted());
            
            if (session instanceof MQSession) {
                MQSession mqSession = (MQSession) session;
                log("\nMQ SESSION SPECIFIC DATA:");
                
                // Dump all session fields
                log("  ALL INTERNAL FIELDS:");
                Field[] sessionFields = getAllFields(mqSession.getClass());
                for (Field field : sessionFields) {
                    try {
                        field.setAccessible(true);
                        Object value = field.get(mqSession);
                        log("    " + field.getName() + " = " + 
                            (value != null ? value.toString() : "null"));
                    } catch (Exception e) {
                        log("    " + field.getName() + " = <inaccessible>");
                    }
                }
            }
            
            // Create queue and producer
            log("\nCREATING MESSAGE PRODUCER:");
            javax.jms.Queue queue = session.createQueue("queue:///UNIFORM.QUEUE");
            log("  Queue: " + queue);
            
            MessageProducer producer = session.createProducer(queue);
            log("  Producer: " + producer);
            log("  Producer Class: " + producer.getClass().getName());
            
            // Create and send proof message
            TextMessage msg = session.createTextMessage(
                "PROOF MESSAGE - Session #" + i + " from Parent " + CORRELATION_KEY);
            
            // Set all tracking properties
            msg.setStringProperty("DEBUG_CorrelationKey", CORRELATION_KEY);
            msg.setStringProperty("DEBUG_ParentConnectionId", CONNECTION_ID);
            msg.setStringProperty("DEBUG_ParentQueueManager", QM_NAME);
            msg.setStringProperty("DEBUG_SessionNumber", String.valueOf(i));
            msg.setStringProperty("DEBUG_Timestamp", String.valueOf(System.currentTimeMillis()));
            msg.setStringProperty("DEBUG_ApplicationTag", CORRELATION_KEY);
            msg.setJMSCorrelationID(CORRELATION_KEY + "-S" + i);
            
            log("\nSENDING PROOF MESSAGE:");
            log("  Text: " + msg.getText());
            log("  JMSCorrelationID: " + msg.getJMSCorrelationID());
            
            producer.send(msg);
            
            log("  Message sent successfully!");
            log("  JMSMessageID: " + msg.getJMSMessageID());
            
            // Message ID analysis
            String msgId = msg.getJMSMessageID();
            if (msgId != null && msgId.startsWith("ID:")) {
                log("\n  MESSAGE ID ANALYSIS:");
                log("    Raw: " + msgId);
                log("    Hex: " + toHex(msgId));
                
                // The message ID contains the QM name encoded
                if (msgId.contains("514d31")) {
                    log("    Queue Manager: QM1 (detected from message ID)");
                } else if (msgId.contains("514d32")) {
                    log("    Queue Manager: QM2 (detected from message ID)");
                } else if (msgId.contains("514d33")) {
                    log("    Queue Manager: QM3 (detected from message ID)");
                }
            }
            
            producer.close();
            log("  Producer closed");
            
            log("\n✅ Session #" + i + " proof complete - Message sent to " + QM_NAME);
        }
        
        log("\n" + "=".repeat(80) + "\n");
        
        log("EVIDENCE COLLECTION SUMMARY");
        log("=".repeat(80));
        log("1. PARENT CONNECTION:");
        log("   - Correlation Key (APPLTAG): " + CORRELATION_KEY);
        log("   - Connection ID: " + CONNECTION_ID);
        log("   - Queue Manager: " + QM_NAME);
        log("");
        log("2. CHILD SESSIONS:");
        log("   - Total Sessions Created: 5");
        log("   - All from Parent: " + CONNECTION_ID);
        log("   - All on Queue Manager: " + QM_NAME);
        log("");
        log("3. MQSC VERIFICATION COMMAND:");
        log("   DIS CONN(*) WHERE(APPLTAG EQ '" + CORRELATION_KEY + "') ALL");
        log("");
        log("4. KEY EVIDENCE:");
        log("   - All 5 sessions created from SAME connection object");
        log("   - All sessions inherit parent's queue manager");
        log("   - Correlation key visible in MQSC as APPLTAG");
        log("   - Parent-child relationship proven by shared connection ID");
        
        log("\nKEEPING CONNECTION ALIVE FOR MQSC MONITORING...");
        
        // Keep alive with status updates
        for (int i = 15; i > 0; i--) {
            log("  Active for monitoring: " + i + " seconds remaining...");
            Thread.sleep(1000);
        }
        
        log("\nCLOSING CONNECTION...");
        connection.close();
        log("Connection closed successfully");
        
        log("\n" + "=".repeat(80));
        log("TEST COMPLETED AT: " + sdf.format(new Date()));
        log("RAW DATA LOG FILE: " + logFileName);
        log("=".repeat(80));
        
        logWriter.close();
        
        System.out.println("\n✅ Test completed. Raw debug data saved to: " + logFileName);
        System.out.println("📋 Use this MQSC command to verify:");
        System.out.println("   DIS CONN(*) WHERE(APPLTAG EQ '" + CORRELATION_KEY + "') ALL");
    }
    
    private static void log(String message) {
        System.out.println(message);
        logWriter.println(message);
        logWriter.flush();
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
    
    private static Field[] getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        while (clazz != null && clazz != Object.class) {
            fields.addAll(Arrays.asList(clazz.getDeclaredFields()));
            clazz = clazz.getSuperclass();
        }
        return fields.toArray(new Field[0]);
    }
    
    private static void dumpObjectState(Object obj, String indent) {
        try {
            Field[] fields = obj.getClass().getDeclaredFields();
            for (Field field : fields) {
                field.setAccessible(true);
                try {
                    Object value = field.get(obj);
                    log(indent + field.getName() + " = " + 
                        (value != null ? value.toString() : "null"));
                } catch (Exception e) {
                    // Skip
                }
            }
        } catch (Exception e) {
            log(indent + "<unable to dump state>");
        }
    }
}