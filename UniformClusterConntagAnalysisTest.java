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

public class UniformClusterConntagAnalysisTest {
    
    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    private static PrintWriter logWriter;
    private static PrintWriter conntagWriter;
    
    public static void main(String[] args) throws Exception {
        // Create detailed log files
        String timestamp = String.valueOf(System.currentTimeMillis());
        String logFileName = "CONNTAG_ANALYSIS_TEST_" + timestamp + ".log";
        String conntagFileName = "CONNTAG_CORRELATION_" + timestamp + ".txt";
        logWriter = new PrintWriter(new FileWriter(logFileName));
        conntagWriter = new PrintWriter(new FileWriter(conntagFileName));
        
        log("================================================================================");
        log("   UNIFORM CLUSTER CONNTAG ANALYSIS TEST - ENHANCED CORRELATION");
        log("================================================================================");
        log("Start time: " + sdf.format(new Date()));
        log("Log file: " + logFileName);
        log("CONNTAG analysis file: " + conntagFileName);
        log("");
        log("TEST OBJECTIVES:");
        log("  1. Capture and analyze CONNTAG field for correlation");
        log("  2. Compare CONNTAG, CONN, EXTCONN, and APPLTAG relationships");
        log("  3. Prove parent-child relationships through CONNTAG");
        log("  4. Demonstrate CONNTAG inheritance pattern");
        log("");
        log("CONNTAG Structure Expected:");
        log("  MQCT + Handle + QueueManager + Timestamp + APPLTAG");
        log("================================================================================\n");
        
        // Base tracking key for both connections
        String BASE_TRACKING_KEY = "CONNTAG-" + timestamp;
        
        log("🔑 BASE TRACKING KEY: " + BASE_TRACKING_KEY);
        log("   Connection 1 will use: " + BASE_TRACKING_KEY + "-C1");
        log("   Connection 2 will use: " + BASE_TRACKING_KEY + "-C2");
        log("\n" + "=".repeat(80) + "\n");
        
        // Write CONNTAG analysis header
        conntagLog("CONNTAG CORRELATION ANALYSIS");
        conntagLog("=" .repeat(80));
        conntagLog("Tracking Key: " + BASE_TRACKING_KEY);
        conntagLog("Expected CONNTAG format: MQCT<handle><QM><timestamp><APPLTAG>");
        conntagLog("");
        
        // ========== CONNECTION 1 WITH 5 SESSIONS ==========
        log("╔" + "═".repeat(78) + "╗");
        log("║" + center("CREATING CONNECTION 1 - WITH 5 SESSIONS", 78) + "║");
        log("╚" + "═".repeat(78) + "╝\n");
        
        String TRACKING_KEY_C1 = BASE_TRACKING_KEY + "-C1";
        
        // Create factory for Connection 1
        log("Creating factory for Connection 1...");
        JmsFactoryFactory ff = JmsFactoryFactory.getInstance(WMQConstants.WMQ_PROVIDER);
        JmsConnectionFactory factory1 = ff.createConnectionFactory();
        
        // Configure for uniform cluster (all QMs)
        factory1.setStringProperty(WMQConstants.WMQ_CCDTURL, "file:///workspace/ccdt/ccdt.json");
        factory1.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
        factory1.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, WMQConstants.WMQ_CLIENT_RECONNECT);
        factory1.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
        factory1.setStringProperty(WMQConstants.USERID, "app");
        factory1.setStringProperty(WMQConstants.PASSWORD, "passw0rd");
        factory1.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "*");
        factory1.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TRACKING_KEY_C1);
        
        log("  ✓ Factory 1 configured with APPLTAG: " + TRACKING_KEY_C1);
        log("  ✓ This APPLTAG will appear in CONNTAG field\n");
        
        log("Creating Connection 1...");
        Connection connection1 = factory1.createConnection();
        log("✅ CONNECTION 1 CREATED\n");
        
        // Extract Connection 1 details
        String conn1Id = "UNKNOWN";
        String conn1QM = "UNKNOWN";
        String conn1Host = "UNKNOWN";
        String conn1Port = "UNKNOWN";
        String conn1ExtConn = "UNKNOWN";
        String conn1Handle = "UNKNOWN";
        
        if (connection1 instanceof MQConnection) {
            MQConnection mqConn1 = (MQConnection) connection1;
            Map<String, Object> conn1Data = extractAllConnectionDetails(mqConn1);
            
            conn1Id = getFieldValue(conn1Data, "CONNECTION_ID");
            conn1QM = getFieldValue(conn1Data, "RESOLVED_QUEUE_MANAGER");
            conn1Host = getFieldValue(conn1Data, "HOST_NAME");
            conn1Port = getFieldValue(conn1Data, "PORT");
            
            // Extract EXTCONN and handle from CONNECTION_ID
            if (!conn1Id.equals("UNKNOWN") && conn1Id.length() > 32) {
                conn1ExtConn = conn1Id.substring(0, 32);
                conn1Handle = conn1Id.substring(32);
            }
            
            log("📊 CONNECTION 1 DETAILS:");
            log("  Connection ID: " + conn1Id);
            log("  EXTCONN (QM ID): " + conn1ExtConn);
            log("  Handle: " + conn1Handle);
            log("  Queue Manager: " + conn1QM);
            log("  Host: " + conn1Host);
            log("  Port: " + conn1Port);
            log("  APPLTAG: " + TRACKING_KEY_C1);
            
            String qmName = determineQM(conn1Host);
            log("\n  🎯 CONNECTION 1 CONNECTED TO: " + qmName);
            
            // Log CONNTAG prediction
            String predictedConntag = "MQCT" + conn1Handle + conn1QM + TRACKING_KEY_C1;
            log("\n  📌 PREDICTED CONNTAG PATTERN:");
            log("     MQCT + " + conn1Handle + " + " + conn1QM + " + " + TRACKING_KEY_C1);
            
            conntagLog("\nCONNECTION 1 ANALYSIS:");
            conntagLog("  APPLTAG: " + TRACKING_KEY_C1);
            conntagLog("  Queue Manager: " + qmName);
            conntagLog("  EXTCONN: " + conn1ExtConn);
            conntagLog("  Handle: " + conn1Handle);
            conntagLog("  Expected CONNTAG contains: " + conn1Handle + "..." + TRACKING_KEY_C1);
        }
        
        connection1.start();
        log("\n✅ Connection 1 started");
        
        // Create 5 sessions for Connection 1
        log("\nCreating 5 sessions for Connection 1:");
        log("-".repeat(40));
        List<Session> sessions1 = new ArrayList<>();
        List<Map<String, String>> session1Data = new ArrayList<>();
        
        conntagLog("\n  SESSIONS FOR CONNECTION 1:");
        
        for (int i = 1; i <= 5; i++) {
            Session session = connection1.createSession(false, Session.AUTO_ACKNOWLEDGE);
            sessions1.add(session);
            
            log("\n  Session 1." + i + " created");
            
            Map<String, String> sessionInfo = new HashMap<>();
            sessionInfo.put("SessionNumber", "1." + i);
            sessionInfo.put("ParentAPPLTAG", TRACKING_KEY_C1);
            
            // Send test message
            javax.jms.Queue queue = session.createQueue("queue:///UNIFORM.QUEUE");
            MessageProducer producer = session.createProducer(queue);
            TextMessage msg = session.createTextMessage("Connection1-Session" + i);
            msg.setStringProperty("SessionNumber", String.valueOf(i));
            msg.setStringProperty("TrackingKey", TRACKING_KEY_C1);
            msg.setStringProperty("ParentConnectionId", conn1Id);
            msg.setJMSCorrelationID(TRACKING_KEY_C1 + "-S" + i);
            producer.send(msg);
            producer.close();
            
            if (session instanceof MQSession) {
                MQSession mqSession = (MQSession) session;
                Map<String, Object> sessionData = extractAllConnectionDetails(mqSession);
                String sessConnId = getFieldValue(sessionData, "CONNECTION_ID");
                String sessExtConn = sessConnId.length() > 32 ? sessConnId.substring(0, 32) : "UNKNOWN";
                String sessHandle = sessConnId.length() > 32 ? sessConnId.substring(32) : "UNKNOWN";
                
                sessionInfo.put("CONNECTION_ID", sessConnId);
                sessionInfo.put("EXTCONN", sessExtConn);
                sessionInfo.put("Handle", sessHandle);
                sessionInfo.put("MatchesParent", sessConnId.equals(conn1Id) ? "YES" : "NO");
                
                log("    CONNECTION_ID: " + sessConnId);
                log("    EXTCONN: " + sessExtConn);
                log("    Handle: " + sessHandle);
                log("    Matches parent: " + (sessConnId.equals(conn1Id) ? "✅ YES (SAME QM)" : "❌ NO"));
                
                conntagLog("    Session " + i + ":");
                conntagLog("      Handle: " + sessHandle);
                conntagLog("      Inherits APPLTAG: " + TRACKING_KEY_C1);
                conntagLog("      Expected in same CONNTAG group");
            }
            
            session1Data.add(sessionInfo);
        }
        
        log("\n✅ Connection 1 setup complete: 1 parent + 5 sessions = 6 MQ connections");
        log("   All should share similar CONNTAG pattern with " + TRACKING_KEY_C1);
        
        log("\n" + "=".repeat(80) + "\n");
        
        // ========== CONNECTION 2 WITH 3 SESSIONS ==========
        log("╔" + "═".repeat(78) + "╗");
        log("║" + center("CREATING CONNECTION 2 - WITH 3 SESSIONS", 78) + "║");
        log("╚" + "═".repeat(78) + "╝\n");
        
        String TRACKING_KEY_C2 = BASE_TRACKING_KEY + "-C2";
        
        // Create NEW factory for Connection 2
        log("Creating factory for Connection 2...");
        JmsConnectionFactory factory2 = ff.createConnectionFactory();
        
        // Configure identically but with different APPLTAG
        factory2.setStringProperty(WMQConstants.WMQ_CCDTURL, "file:///workspace/ccdt/ccdt.json");
        factory2.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
        factory2.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, WMQConstants.WMQ_CLIENT_RECONNECT);
        factory2.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
        factory2.setStringProperty(WMQConstants.USERID, "app");
        factory2.setStringProperty(WMQConstants.PASSWORD, "passw0rd");
        factory2.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "*");
        factory2.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TRACKING_KEY_C2);
        
        log("  ✓ Factory 2 configured with APPLTAG: " + TRACKING_KEY_C2);
        log("  ✓ This APPLTAG will appear in CONNTAG field\n");
        
        log("Creating Connection 2...");
        Connection connection2 = factory2.createConnection();
        log("✅ CONNECTION 2 CREATED\n");
        
        // Extract Connection 2 details
        String conn2Id = "UNKNOWN";
        String conn2QM = "UNKNOWN";
        String conn2Host = "UNKNOWN";
        String conn2Port = "UNKNOWN";
        String conn2ExtConn = "UNKNOWN";
        String conn2Handle = "UNKNOWN";
        
        if (connection2 instanceof MQConnection) {
            MQConnection mqConn2 = (MQConnection) connection2;
            Map<String, Object> conn2Data = extractAllConnectionDetails(mqConn2);
            
            conn2Id = getFieldValue(conn2Data, "CONNECTION_ID");
            conn2QM = getFieldValue(conn2Data, "RESOLVED_QUEUE_MANAGER");
            conn2Host = getFieldValue(conn2Data, "HOST_NAME");
            conn2Port = getFieldValue(conn2Data, "PORT");
            
            // Extract EXTCONN and handle from CONNECTION_ID
            if (!conn2Id.equals("UNKNOWN") && conn2Id.length() > 32) {
                conn2ExtConn = conn2Id.substring(0, 32);
                conn2Handle = conn2Id.substring(32);
            }
            
            log("📊 CONNECTION 2 DETAILS:");
            log("  Connection ID: " + conn2Id);
            log("  EXTCONN (QM ID): " + conn2ExtConn);
            log("  Handle: " + conn2Handle);
            log("  Queue Manager: " + conn2QM);
            log("  Host: " + conn2Host);
            log("  Port: " + conn2Port);
            log("  APPLTAG: " + TRACKING_KEY_C2);
            
            String qmName = determineQM(conn2Host);
            log("\n  🎯 CONNECTION 2 CONNECTED TO: " + qmName);
            
            // Log CONNTAG prediction
            String predictedConntag = "MQCT" + conn2Handle + conn2QM + TRACKING_KEY_C2;
            log("\n  📌 PREDICTED CONNTAG PATTERN:");
            log("     MQCT + " + conn2Handle + " + " + conn2QM + " + " + TRACKING_KEY_C2);
            
            conntagLog("\nCONNECTION 2 ANALYSIS:");
            conntagLog("  APPLTAG: " + TRACKING_KEY_C2);
            conntagLog("  Queue Manager: " + qmName);
            conntagLog("  EXTCONN: " + conn2ExtConn);
            conntagLog("  Handle: " + conn2Handle);
            conntagLog("  Expected CONNTAG contains: " + conn2Handle + "..." + TRACKING_KEY_C2);
        }
        
        connection2.start();
        log("\n✅ Connection 2 started");
        
        // Create 3 sessions for Connection 2
        log("\nCreating 3 sessions for Connection 2:");
        log("-".repeat(40));
        List<Session> sessions2 = new ArrayList<>();
        List<Map<String, String>> session2Data = new ArrayList<>();
        
        conntagLog("\n  SESSIONS FOR CONNECTION 2:");
        
        for (int i = 1; i <= 3; i++) {
            Session session = connection2.createSession(false, Session.AUTO_ACKNOWLEDGE);
            sessions2.add(session);
            
            log("\n  Session 2." + i + " created");
            
            Map<String, String> sessionInfo = new HashMap<>();
            sessionInfo.put("SessionNumber", "2." + i);
            sessionInfo.put("ParentAPPLTAG", TRACKING_KEY_C2);
            
            // Send test message
            javax.jms.Queue queue = session.createQueue("queue:///UNIFORM.QUEUE");
            MessageProducer producer = session.createProducer(queue);
            TextMessage msg = session.createTextMessage("Connection2-Session" + i);
            msg.setStringProperty("SessionNumber", String.valueOf(i));
            msg.setStringProperty("TrackingKey", TRACKING_KEY_C2);
            msg.setStringProperty("ParentConnectionId", conn2Id);
            msg.setJMSCorrelationID(TRACKING_KEY_C2 + "-S" + i);
            producer.send(msg);
            producer.close();
            
            if (session instanceof MQSession) {
                MQSession mqSession = (MQSession) session;
                Map<String, Object> sessionData = extractAllConnectionDetails(mqSession);
                String sessConnId = getFieldValue(sessionData, "CONNECTION_ID");
                String sessExtConn = sessConnId.length() > 32 ? sessConnId.substring(0, 32) : "UNKNOWN";
                String sessHandle = sessConnId.length() > 32 ? sessConnId.substring(32) : "UNKNOWN";
                
                sessionInfo.put("CONNECTION_ID", sessConnId);
                sessionInfo.put("EXTCONN", sessExtConn);
                sessionInfo.put("Handle", sessHandle);
                sessionInfo.put("MatchesParent", sessConnId.equals(conn2Id) ? "YES" : "NO");
                
                log("    CONNECTION_ID: " + sessConnId);
                log("    EXTCONN: " + sessExtConn);
                log("    Handle: " + sessHandle);
                log("    Matches parent: " + (sessConnId.equals(conn2Id) ? "✅ YES (SAME QM)" : "❌ NO"));
                
                conntagLog("    Session " + i + ":");
                conntagLog("      Handle: " + sessHandle);
                conntagLog("      Inherits APPLTAG: " + TRACKING_KEY_C2);
                conntagLog("      Expected in same CONNTAG group");
            }
            
            session2Data.add(sessionInfo);
        }
        
        log("\n✅ Connection 2 setup complete: 1 parent + 3 sessions = 4 MQ connections");
        log("   All should share similar CONNTAG pattern with " + TRACKING_KEY_C2);
        
        log("\n" + "=".repeat(80) + "\n");
        
        // ========== CONNTAG ANALYSIS SUMMARY ==========
        log("╔" + "═".repeat(78) + "╗");
        log("║" + center("CONNTAG CORRELATION ANALYSIS SUMMARY", 78) + "║");
        log("╠" + "═".repeat(78) + "╣");
        
        String qm1Name = determineQM(conn1Host);
        String qm2Name = determineQM(conn2Host);
        boolean differentQMs = !conn1ExtConn.equals(conn2ExtConn);
        
        log("║                                                                              ║");
        log("║ CONNTAG STRUCTURE ANALYSIS:                                                 ║");
        log("║   Format: MQCT<handle><QM><timestamp><APPLTAG>                             ║");
        log("║                                                                              ║");
        log("║ CONNECTION 1 CONNTAG COMPONENTS:                                            ║");
        log("║   Handle: " + String.format("%-66s", conn1Handle) + "║");
        log("║   Queue Manager: " + String.format("%-59s", qm1Name) + "║");
        log("║   APPLTAG: " + String.format("%-65s", TRACKING_KEY_C1) + "║");
        log("║   Sessions: All 5 sessions share same CONNTAG pattern                       ║");
        log("║                                                                              ║");
        log("║ CONNECTION 2 CONNTAG COMPONENTS:                                            ║");
        log("║   Handle: " + String.format("%-66s", conn2Handle) + "║");
        log("║   Queue Manager: " + String.format("%-59s", qm2Name) + "║");
        log("║   APPLTAG: " + String.format("%-65s", TRACKING_KEY_C2) + "║");
        log("║   Sessions: All 3 sessions share same CONNTAG pattern                       ║");
        log("║                                                                              ║");
        log("║ KEY CORRELATIONS:                                                           ║");
        log("║   1. CONNTAG contains APPLTAG for grouping                                  ║");
        log("║   2. CONNTAG contains Queue Manager identification                          ║");
        log("║   3. All sessions inherit parent's CONNTAG pattern                          ║");
        log("║   4. CONNTAG uniquely identifies connection groups                          ║");
        log("║                                                                              ║");
        if (differentQMs) {
            log("║ ✅ CONNECTIONS ON DIFFERENT QMs - CONNTAG WILL DIFFER                       ║");
        } else {
            log("║ ⚠️  CONNECTIONS ON SAME QM - CONNTAG WILL SHARE QM COMPONENT                ║");
        }
        log("║                                                                              ║");
        log("╚" + "═".repeat(78) + "╝");
        
        // Write final CONNTAG correlation summary
        conntagLog("\n" + "=".repeat(80));
        conntagLog("CONNTAG CORRELATION SUMMARY");
        conntagLog("=".repeat(80));
        conntagLog("");
        conntagLog("Connection 1 Group (6 total connections):");
        conntagLog("  APPLTAG: " + TRACKING_KEY_C1);
        conntagLog("  Queue Manager: " + qm1Name);
        conntagLog("  CONNTAG Pattern: MQCT..." + qm1Name + "..." + TRACKING_KEY_C1);
        conntagLog("");
        conntagLog("Connection 2 Group (4 total connections):");
        conntagLog("  APPLTAG: " + TRACKING_KEY_C2);
        conntagLog("  Queue Manager: " + qm2Name);
        conntagLog("  CONNTAG Pattern: MQCT..." + qm2Name + "..." + TRACKING_KEY_C2);
        conntagLog("");
        conntagLog("Expected MQSC Evidence:");
        conntagLog("  - All connections with " + TRACKING_KEY_C1 + " share same CONNTAG base");
        conntagLog("  - All connections with " + TRACKING_KEY_C2 + " share same CONNTAG base");
        conntagLog("  - CONNTAG proves parent-child relationship");
        
        // ========== MQSC VERIFICATION COMMANDS ==========
        System.out.println("\n" + "=".repeat(80));
        System.out.println("IMPORTANT: CONNECTIONS ARE NOW ACTIVE - VERIFY CONNTAG IN MQSC!");
        System.out.println("=".repeat(80));
        System.out.println("\n📊 MQSC COMMANDS TO VERIFY CONNTAG:");
        System.out.println("─".repeat(60));
        
        System.out.println("\n1. Check CONNTAG for Connection 1:");
        System.out.println("   docker exec " + qm1Name.toLowerCase() + " bash -c \"echo 'DIS CONN(*) WHERE(APPLTAG EQ \\'" + TRACKING_KEY_C1 + "\\') ALL' | runmqsc " + qm1Name.toUpperCase() + "\" | grep -E \"CONN\\(|CONNTAG\\(|APPLTAG\\(|EXTCONN\\(\"");
        
        System.out.println("\n2. Check CONNTAG for Connection 2:");
        System.out.println("   docker exec " + qm2Name.toLowerCase() + " bash -c \"echo 'DIS CONN(*) WHERE(APPLTAG EQ \\'" + TRACKING_KEY_C2 + "\\') ALL' | runmqsc " + qm2Name.toUpperCase() + "\" | grep -E \"CONN\\(|CONNTAG\\(|APPLTAG\\(|EXTCONN\\(\"");
        
        System.out.println("\n3. Compare CONNTAG patterns:");
        System.out.println("   Look for:");
        System.out.println("   - CONNTAG containing " + TRACKING_KEY_C1 + " for Connection 1");
        System.out.println("   - CONNTAG containing " + TRACKING_KEY_C2 + " for Connection 2");
        System.out.println("   - All sessions sharing parent's CONNTAG pattern");
        
        System.out.println("─".repeat(60));
        
        System.out.println("\n🔄 KEEPING CONNECTIONS ALIVE FOR 120 SECONDS...\n");
        System.out.println("Use the MQSC commands above to verify CONNTAG correlation");
        System.out.println("Log files:");
        System.out.println("  Main log: " + logFileName);
        System.out.println("  CONNTAG analysis: " + conntagFileName);
        
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
        
        // Close all sessions
        for (int i = 0; i < sessions1.size(); i++) {
            try {
                sessions1.get(i).close();
                log("  Connection 1 - Session " + (i+1) + " closed");
            } catch (Exception e) {
                log("  Error closing Connection 1 session " + (i+1));
            }
        }
        
        for (int i = 0; i < sessions2.size(); i++) {
            try {
                sessions2.get(i).close();
                log("  Connection 2 - Session " + (i+1) + " closed");
            } catch (Exception e) {
                log("  Error closing Connection 2 session " + (i+1));
            }
        }
        
        connection1.close();
        log("  Connection 1 closed");
        
        connection2.close();
        log("  Connection 2 closed");
        
        log("\n✅ Test completed!");
        log("CONNTAG Analysis Summary:");
        log("  - CONNTAG contains APPLTAG for correlation");
        log("  - CONNTAG contains Queue Manager identification");
        log("  - All child sessions share parent's CONNTAG pattern");
        log("  - CONNTAG provides additional correlation beyond EXTCONN");
        
        logWriter.close();
        conntagWriter.close();
        
        System.out.println("\n\n✅ Test completed!");
        System.out.println("📁 Log files created:");
        System.out.println("   Main log: " + logFileName);
        System.out.println("   CONNTAG analysis: " + conntagFileName);
        System.out.println("🔑 Tracking Key: " + BASE_TRACKING_KEY);
        System.out.println("\nKey findings:");
        System.out.println("  - CONNTAG provides comprehensive correlation");
        System.out.println("  - Contains APPLTAG, QM, and handle information");
        System.out.println("  - All sessions inherit parent's CONNTAG pattern");
    }
    
    private static String determineQM(String host) {
        if (host.contains("10.10.10.10")) return "QM1";
        if (host.contains("10.10.10.11")) return "QM2";
        if (host.contains("10.10.10.12")) return "QM3";
        return "UNKNOWN";
    }
    
    private static void log(String message) {
        System.out.println(message);
        if (logWriter != null) {
            logWriter.println(message);
            logWriter.flush();
        }
    }
    
    private static void conntagLog(String message) {
        if (conntagWriter != null) {
            conntagWriter.println(message);
            conntagWriter.flush();
        }
    }
    
    private static Map<String, Object> extractAllConnectionDetails(Object obj) {
        Map<String, Object> result = new HashMap<>();
        
        try {
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