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

public class UniformClusterConntagFixed {
    
    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    private static PrintWriter logWriter;
    private static PrintWriter conntagWriter;
    
    public static void main(String[] args) throws Exception {
        // Create detailed log files
        String timestamp = String.valueOf(System.currentTimeMillis());
        String logFileName = "CONNTAG_FIXED_TEST_" + timestamp + ".log";
        String conntagFileName = "CONNTAG_FIXED_CORRELATION_" + timestamp + ".txt";
        logWriter = new PrintWriter(new FileWriter(logFileName));
        conntagWriter = new PrintWriter(new FileWriter(conntagFileName));
        
        log("================================================================================");
        log("   UNIFORM CLUSTER CONNTAG TEST - FIXED DISTRIBUTION");
        log("================================================================================");
        log("Start time: " + sdf.format(new Date()));
        log("Log file: " + logFileName);
        log("CONNTAG analysis file: " + conntagFileName);
        log("");
        log("FIX APPLIED: Removed WMQ_QUEUE_MANAGER='*' to allow CCDT load balancing");
        log("");
        log("TEST OBJECTIVES:");
        log("  1. Verify connections distribute across different QMs");
        log("  2. Capture and analyze CONNTAG field for correlation");
        log("  3. Compare CONNTAG when connections are on DIFFERENT QMs");
        log("================================================================================\n");
        
        // Base tracking key for both connections
        String BASE_TRACKING_KEY = "FIXED-" + timestamp;
        
        log("🔑 BASE TRACKING KEY: " + BASE_TRACKING_KEY);
        log("   Connection 1 will use: " + BASE_TRACKING_KEY + "-C1");
        log("   Connection 2 will use: " + BASE_TRACKING_KEY + "-C2");
        log("\n" + "=".repeat(80) + "\n");
        
        // Write CONNTAG analysis header
        conntagLog("CONNTAG FIXED DISTRIBUTION ANALYSIS");
        conntagLog("=" .repeat(80));
        conntagLog("Tracking Key: " + BASE_TRACKING_KEY);
        conntagLog("Expected: Different QMs = Different CONNTAG patterns");
        conntagLog("");
        
        // Create multiple connections to test distribution
        log("Creating 10 test connections to verify distribution...\n");
        
        Map<String, Integer> distribution = new HashMap<>();
        distribution.put("QM1", 0);
        distribution.put("QM2", 0);
        distribution.put("QM3", 0);
        
        JmsFactoryFactory ff = JmsFactoryFactory.getInstance(WMQConstants.WMQ_PROVIDER);
        
        for (int i = 1; i <= 10; i++) {
            JmsConnectionFactory testFactory = ff.createConnectionFactory();
            
            // Configure WITHOUT setting WMQ_QUEUE_MANAGER
            testFactory.setStringProperty(WMQConstants.WMQ_CCDTURL, "file:///workspace/ccdt/ccdt.json");
            testFactory.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
            testFactory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, WMQConstants.WMQ_CLIENT_RECONNECT);
            testFactory.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
            testFactory.setStringProperty(WMQConstants.USERID, "app");
            testFactory.setStringProperty(WMQConstants.PASSWORD, "passw0rd");
            // NOT SETTING: testFactory.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "*");
            testFactory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, "DIST-TEST-" + i);
            
            try {
                Connection testConn = testFactory.createConnection();
                
                if (testConn instanceof MQConnection) {
                    MQConnection mqTestConn = (MQConnection) testConn;
                    Map<String, Object> testData = extractAllConnectionDetails(mqTestConn);
                    String qm = getFieldValue(testData, "RESOLVED_QUEUE_MANAGER");
                    
                    if (qm.contains("QM1")) {
                        distribution.put("QM1", distribution.get("QM1") + 1);
                        log("  Test connection " + i + " -> QM1");
                    } else if (qm.contains("QM2")) {
                        distribution.put("QM2", distribution.get("QM2") + 1);
                        log("  Test connection " + i + " -> QM2");
                    } else if (qm.contains("QM3")) {
                        distribution.put("QM3", distribution.get("QM3") + 1);
                        log("  Test connection " + i + " -> QM3");
                    }
                }
                
                testConn.close();
                Thread.sleep(100); // Small delay between connections
                
            } catch (Exception e) {
                log("  Test connection " + i + " failed: " + e.getMessage());
            }
        }
        
        log("\nDistribution Test Results:");
        log("  QM1: " + distribution.get("QM1") + " connections");
        log("  QM2: " + distribution.get("QM2") + " connections");
        log("  QM3: " + distribution.get("QM3") + " connections");
        
        long activeQMs = distribution.values().stream().filter(v -> v > 0).count();
        if (activeQMs > 1) {
            log("\n✅ DISTRIBUTION WORKING: Connections spread across " + activeQMs + " Queue Managers");
        } else {
            log("\n⚠️ DISTRIBUTION ISSUE: All connections on same QM");
        }
        
        log("\n" + "=".repeat(80) + "\n");
        
        // ========== CONNECTION 1 WITH 5 SESSIONS ==========
        log("╔" + "═".repeat(78) + "╗");
        log("║" + center("CREATING CONNECTION 1 - WITH 5 SESSIONS", 78) + "║");
        log("╚" + "═".repeat(78) + "╝\n");
        
        String TRACKING_KEY_C1 = BASE_TRACKING_KEY + "-C1";
        
        // Create factory for Connection 1
        log("Creating factory for Connection 1...");
        JmsConnectionFactory factory1 = ff.createConnectionFactory();
        
        // Configure WITHOUT WMQ_QUEUE_MANAGER
        factory1.setStringProperty(WMQConstants.WMQ_CCDTURL, "file:///workspace/ccdt/ccdt.json");
        factory1.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
        factory1.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, WMQConstants.WMQ_CLIENT_RECONNECT);
        factory1.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
        factory1.setStringProperty(WMQConstants.USERID, "app");
        factory1.setStringProperty(WMQConstants.PASSWORD, "passw0rd");
        // REMOVED: factory1.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "*");
        factory1.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TRACKING_KEY_C1);
        
        log("  ✓ Factory 1 configured with APPLTAG: " + TRACKING_KEY_C1);
        log("  ✓ NO WMQ_QUEUE_MANAGER set - allowing CCDT to choose\n");
        
        log("Creating Connection 1...");
        Connection connection1 = factory1.createConnection();
        log("✅ CONNECTION 1 CREATED\n");
        
        // Extract Connection 1 details
        String conn1Id = "UNKNOWN";
        String conn1QM = "UNKNOWN";
        String conn1ExtConn = "UNKNOWN";
        String conn1Handle = "UNKNOWN";
        
        if (connection1 instanceof MQConnection) {
            MQConnection mqConn1 = (MQConnection) connection1;
            Map<String, Object> conn1Data = extractAllConnectionDetails(mqConn1);
            
            conn1Id = getFieldValue(conn1Data, "CONNECTION_ID");
            conn1QM = getFieldValue(conn1Data, "RESOLVED_QUEUE_MANAGER");
            
            if (!conn1Id.equals("UNKNOWN") && conn1Id.length() > 32) {
                conn1ExtConn = conn1Id.substring(0, 32);
                conn1Handle = conn1Id.substring(32);
            }
            
            String qmName = determineQM(conn1ExtConn, conn1QM);
            
            log("📊 CONNECTION 1 DETAILS:");
            log("   Queue Manager: " + qmName);
            log("   CONNECTION_ID: " + conn1Id);
            log("   EXTCONN: " + conn1ExtConn);
            log("   Handle: " + conn1Handle);
            log("   APPLTAG: " + TRACKING_KEY_C1);
            
            // Predict CONNTAG pattern
            String predictedConntag1 = "MQCT" + conn1Handle + qmName;
            log("\n📌 PREDICTED CONNTAG PATTERN for C1:");
            log("   " + predictedConntag1 + "..." + TRACKING_KEY_C1);
            
            conntagLog("\nCONNECTION 1 ANALYSIS:");
            conntagLog("  Queue Manager: " + qmName);
            conntagLog("  EXTCONN: " + conn1ExtConn);
            conntagLog("  Handle: " + conn1Handle);
            conntagLog("  APPLTAG: " + TRACKING_KEY_C1);
            conntagLog("  Expected CONNTAG contains: " + conn1Handle + "..." + qmName + "..." + TRACKING_KEY_C1);
        }
        
        log("\n" + "-".repeat(80) + "\n");
        
        log("Creating 5 sessions from Connection 1...\n");
        List<Session> sessions1 = new ArrayList<>();
        
        for (int i = 1; i <= 5; i++) {
            Session session = connection1.createSession(false, Session.AUTO_ACKNOWLEDGE);
            sessions1.add(session);
            log("  ✓ Session " + i + " created");
            
            if (session instanceof MQSession) {
                MQSession mqSession = (MQSession) session;
                Map<String, Object> sessionData = extractAllConnectionDetails(mqSession);
                String sessConnId = getFieldValue(sessionData, "CONNECTION_ID");
                String sessHandle = sessConnId.length() > 32 ? sessConnId.substring(32) : "UNKNOWN";
                
                conntagLog("    Session " + i + ":");
                conntagLog("      Handle: " + sessHandle);
                conntagLog("      Inherits APPLTAG: " + TRACKING_KEY_C1);
                conntagLog("      Expected in same CONNTAG group");
            }
        }
        
        log("\n✅ Connection 1 total: 1 parent + 5 sessions = 6 MQ connections");
        log("   All should share same CONNTAG pattern\n");
        
        log("\n" + "=".repeat(80) + "\n");
        
        // ========== CONNECTION 2 WITH 3 SESSIONS ==========
        log("╔" + "═".repeat(78) + "╗");
        log("║" + center("CREATING CONNECTION 2 - WITH 3 SESSIONS", 78) + "║");
        log("╚" + "═".repeat(78) + "╝\n");
        
        String TRACKING_KEY_C2 = BASE_TRACKING_KEY + "-C2";
        
        // Create factory for Connection 2
        log("Creating factory for Connection 2...");
        JmsConnectionFactory factory2 = ff.createConnectionFactory();
        
        // Configure WITHOUT WMQ_QUEUE_MANAGER
        factory2.setStringProperty(WMQConstants.WMQ_CCDTURL, "file:///workspace/ccdt/ccdt.json");
        factory2.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
        factory2.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, WMQConstants.WMQ_CLIENT_RECONNECT);
        factory2.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
        factory2.setStringProperty(WMQConstants.USERID, "app");
        factory2.setStringProperty(WMQConstants.PASSWORD, "passw0rd");
        // REMOVED: factory2.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "*");
        factory2.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TRACKING_KEY_C2);
        
        log("  ✓ Factory 2 configured with APPLTAG: " + TRACKING_KEY_C2);
        log("  ✓ NO WMQ_QUEUE_MANAGER set - allowing CCDT to choose\n");
        
        log("Creating Connection 2...");
        Connection connection2 = factory2.createConnection();
        log("✅ CONNECTION 2 CREATED\n");
        
        // Extract Connection 2 details
        String conn2Id = "UNKNOWN";
        String conn2QM = "UNKNOWN";
        String conn2ExtConn = "UNKNOWN";
        String conn2Handle = "UNKNOWN";
        
        if (connection2 instanceof MQConnection) {
            MQConnection mqConn2 = (MQConnection) connection2;
            Map<String, Object> conn2Data = extractAllConnectionDetails(mqConn2);
            
            conn2Id = getFieldValue(conn2Data, "CONNECTION_ID");
            conn2QM = getFieldValue(conn2Data, "RESOLVED_QUEUE_MANAGER");
            
            if (!conn2Id.equals("UNKNOWN") && conn2Id.length() > 32) {
                conn2ExtConn = conn2Id.substring(0, 32);
                conn2Handle = conn2Id.substring(32);
            }
            
            String qmName = determineQM(conn2ExtConn, conn2QM);
            
            log("📊 CONNECTION 2 DETAILS:");
            log("   Queue Manager: " + qmName);
            log("   CONNECTION_ID: " + conn2Id);
            log("   EXTCONN: " + conn2ExtConn);
            log("   Handle: " + conn2Handle);
            log("   APPLTAG: " + TRACKING_KEY_C2);
            
            // Predict CONNTAG pattern
            String predictedConntag2 = "MQCT" + conn2Handle + qmName;
            log("\n📌 PREDICTED CONNTAG PATTERN for C2:");
            log("   " + predictedConntag2 + "..." + TRACKING_KEY_C2);
            
            conntagLog("\nCONNECTION 2 ANALYSIS:");
            conntagLog("  Queue Manager: " + qmName);
            conntagLog("  EXTCONN: " + conn2ExtConn);
            conntagLog("  Handle: " + conn2Handle);
            conntagLog("  APPLTAG: " + TRACKING_KEY_C2);
            conntagLog("  Expected CONNTAG contains: " + conn2Handle + "..." + qmName + "..." + TRACKING_KEY_C2);
        }
        
        log("\n" + "-".repeat(80) + "\n");
        
        log("Creating 3 sessions from Connection 2...\n");
        List<Session> sessions2 = new ArrayList<>();
        
        for (int i = 1; i <= 3; i++) {
            Session session = connection2.createSession(false, Session.AUTO_ACKNOWLEDGE);
            sessions2.add(session);
            log("  ✓ Session " + i + " created");
            
            if (session instanceof MQSession) {
                MQSession mqSession = (MQSession) session;
                Map<String, Object> sessionData = extractAllConnectionDetails(mqSession);
                String sessConnId = getFieldValue(sessionData, "CONNECTION_ID");
                String sessHandle = sessConnId.length() > 32 ? sessConnId.substring(32) : "UNKNOWN";
                
                conntagLog("    Session " + i + ":");
                conntagLog("      Handle: " + sessHandle);
                conntagLog("      Inherits APPLTAG: " + TRACKING_KEY_C2);
                conntagLog("      Expected in same CONNTAG group");
            }
        }
        
        log("\n✅ Connection 2 total: 1 parent + 3 sessions = 4 MQ connections");
        log("   All should share same CONNTAG pattern\n");
        
        // ========== CONNTAG COMPARISON ==========
        log("\n" + "=".repeat(80) + "\n");
        log("📊 CONNTAG COMPARISON ANALYSIS");
        log("=" .repeat(80) + "\n");
        
        boolean sameQM = conn1ExtConn.equals(conn2ExtConn);
        
        log("Connection Distribution:");
        log("  Connection 1: " + determineQM(conn1ExtConn, conn1QM));
        log("  Connection 2: " + determineQM(conn2ExtConn, conn2QM));
        log("  Same Queue Manager: " + (sameQM ? "YES (random selection chose same)" : "NO (distributed)"));
        
        log("\nCONNTAG Expectations:");
        if (sameQM) {
            log("  ⚠️ Both connections on same QM - CONNTAG will differ only in handle and APPLTAG");
            log("  - Different handles: " + conn1Handle + " vs " + conn2Handle);
            log("  - Different APPLTAGs: " + TRACKING_KEY_C1 + " vs " + TRACKING_KEY_C2);
        } else {
            log("  ✅ Connections on DIFFERENT QMs - CONNTAG will differ in QM, handle, and APPLTAG");
            log("  - Different QMs in CONNTAG");
            log("  - Different handles: " + conn1Handle + " vs " + conn2Handle);
            log("  - Different APPLTAGs: " + TRACKING_KEY_C1 + " vs " + TRACKING_KEY_C2);
        }
        
        conntagLog("\n" + "=".repeat(80));
        conntagLog("CONNTAG COMPARISON SUMMARY");
        conntagLog("=".repeat(80));
        conntagLog("");
        conntagLog("Connection 1 Group (6 total connections):");
        conntagLog("  All share CONNTAG pattern with: " + conn1Handle + "..." + TRACKING_KEY_C1);
        conntagLog("");
        conntagLog("Connection 2 Group (4 total connections):");
        conntagLog("  All share CONNTAG pattern with: " + conn2Handle + "..." + TRACKING_KEY_C2);
        conntagLog("");
        conntagLog("Key Differences in CONNTAG:");
        conntagLog("  1. Handle component: " + conn1Handle + " vs " + conn2Handle);
        conntagLog("  2. APPLTAG component: " + TRACKING_KEY_C1 + " vs " + TRACKING_KEY_C2);
        if (!sameQM) {
            conntagLog("  3. Queue Manager component: DIFFERENT QMs");
        }
        
        // Keep connections alive for monitoring
        log("\n" + "=".repeat(80) + "\n");
        log("⏰ Keeping connections alive for 120 seconds for MQSC verification...");
        log("   Run this command to verify CONNTAG:");
        log("   ./run_conntag_verification.sh " + BASE_TRACKING_KEY + "\n");
        
        // Create verification script
        createVerificationScript(BASE_TRACKING_KEY);
        
        Thread.sleep(120000);
        
        // Clean up
        log("\nClosing connections...");
        for (Session s : sessions1) s.close();
        for (Session s : sessions2) s.close();
        connection1.close();
        connection2.close();
        
        log("\n✅ Test completed successfully!");
        log("   Check CONNTAG analysis file: " + conntagFileName);
        
        logWriter.close();
        conntagWriter.close();
    }
    
    private static String center(String text, int width) {
        int padding = (width - text.length()) / 2;
        return " ".repeat(Math.max(0, padding)) + text;
    }
    
    private static String determineQM(String extConn, String resolvedQM) {
        if (extConn.contains("514D31") || resolvedQM.contains("QM1")) return "QM1";
        if (extConn.contains("514D32") || resolvedQM.contains("QM2")) return "QM2";
        if (extConn.contains("514D33") || resolvedQM.contains("QM3")) return "QM3";
        return "UNKNOWN";
    }
    
    private static void log(String message) {
        System.out.println(message);
        logWriter.println(message);
        logWriter.flush();
    }
    
    private static void conntagLog(String message) {
        conntagWriter.println(message);
        conntagWriter.flush();
    }
    
    private static void createVerificationScript(String trackingKey) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("#!/bin/bash\n");
        sb.append("TRACKING_KEY=\"").append(trackingKey).append("\"\n");
        sb.append("echo \"CONNTAG Verification for $TRACKING_KEY\"\n");
        sb.append("echo \"========================================\"\n");
        sb.append("echo \"\"\n\n");
        sb.append("for qm in qm1 qm2 qm3; do\n");
        sb.append("    echo \"=== Checking $qm ===\"\n");
        sb.append("    \n");
        sb.append("    # Check C1\n");
        sb.append("    C1_COUNT=$(docker exec $qm bash -c \"echo 'DIS CONN(*) WHERE(APPLTAG EQ '\\\\''${TRACKING_KEY}-C1'\\\\'') ALL' | runmqsc ${qm^^}\" 2>/dev/null | grep -c \"CONN(\" || echo \"0\")\n");
        sb.append("    if [ $C1_COUNT -gt 0 ]; then\n");
        sb.append("        echo \"Connection 1 found on ${qm^^}: $C1_COUNT connections\"\n");
        sb.append("        docker exec $qm bash -c \"echo 'DIS CONN(*) WHERE(APPLTAG EQ '\\\\''${TRACKING_KEY}-C1'\\\\'') ALL' | runmqsc ${qm^^}\" 2>/dev/null | grep \"CONNTAG(\" | head -1\n");
        sb.append("    fi\n");
        sb.append("    \n");
        sb.append("    # Check C2\n");
        sb.append("    C2_COUNT=$(docker exec $qm bash -c \"echo 'DIS CONN(*) WHERE(APPLTAG EQ '\\\\''${TRACKING_KEY}-C2'\\\\'') ALL' | runmqsc ${qm^^}\" 2>/dev/null | grep -c \"CONN(\" || echo \"0\")\n");
        sb.append("    if [ $C2_COUNT -gt 0 ]; then\n");
        sb.append("        echo \"Connection 2 found on ${qm^^}: $C2_COUNT connections\"\n");
        sb.append("        docker exec $qm bash -c \"echo 'DIS CONN(*) WHERE(APPLTAG EQ '\\\\''${TRACKING_KEY}-C2'\\\\'') ALL' | runmqsc ${qm^^}\" 2>/dev/null | grep \"CONNTAG(\" | head -1\n");
        sb.append("    fi\n");
        sb.append("    \n");
        sb.append("    echo \"\"\n");
        sb.append("done\n");
        
        PrintWriter scriptWriter = new PrintWriter(new FileWriter("run_conntag_verification.sh"));
        scriptWriter.print(sb.toString());
        scriptWriter.close();
        
        // Make executable
        Runtime.getRuntime().exec("chmod +x run_conntag_verification.sh");
    }
    
    private static Map<String, Object> extractAllConnectionDetails(Object mqObject) {
        Map<String, Object> details = new HashMap<>();
        
        try {
            // Try via delegate first
            extractViaDelegate(mqObject, details);
            
            // Try direct field access
            Field[] fields = mqObject.getClass().getDeclaredFields();
            for (Field field : fields) {
                field.setAccessible(true);
                Object value = field.get(mqObject);
                if (value != null) {
                    details.put(field.getName(), value);
                }
            }
            
            // Try methods
            Method[] methods = mqObject.getClass().getMethods();
            for (Method method : methods) {
                if (method.getName().startsWith("get") && method.getParameterCount() == 0) {
                    try {
                        Object value = method.invoke(mqObject);
                        if (value != null) {
                            String propName = method.getName().substring(3);
                            details.put(propName, value);
                        }
                    } catch (Exception e) {
                        // Skip problematic methods
                    }
                }
            }
            
        } catch (Exception e) {
            // Continue with what we have
        }
        
        return details;
    }
    
    private static void extractViaDelegate(Object mqObject, Map<String, Object> details) {
        try {
            Field[] fields = mqObject.getClass().getDeclaredFields();
            for (Field field : fields) {
                if (field.getName().equals("delegate") || field.getName().equals("commonConn")) {
                    field.setAccessible(true);
                    Object delegate = field.get(mqObject);
                    if (delegate != null) {
                        // Extract properties via getStringProperty
                        Method getStringProp = delegate.getClass().getMethod("getStringProperty", String.class);
                        Method getIntProp = delegate.getClass().getMethod("getIntProperty", String.class);
                        
                        String[] stringProps = {
                            "XMSC_WMQ_CONNECTION_ID",
                            "XMSC_WMQ_RESOLVED_QUEUE_MANAGER",
                            "XMSC_WMQ_HOST_NAME",
                            "XMSC_WMQ_APPNAME",
                            "XMSC_WMQ_QUEUE_MANAGER"
                        };
                        
                        for (String prop : stringProps) {
                            try {
                                Object value = getStringProp.invoke(delegate, prop);
                                if (value != null) {
                                    String simpleName = prop.replace("XMSC_WMQ_", "");
                                    details.put(simpleName, value);
                                }
                            } catch (Exception e) {
                                // Skip
                            }
                        }
                        
                        // Try port
                        try {
                            Object port = getIntProp.invoke(delegate, "XMSC_WMQ_PORT");
                            if (port != null) {
                                details.put("PORT", port.toString());
                            }
                        } catch (Exception e) {
                            // Skip
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Continue
        }
    }
    
    private static String getFieldValue(Map<String, Object> data, String fieldName) {
        Object value = data.get(fieldName);
        return value != null ? value.toString() : "UNKNOWN";
    }
}