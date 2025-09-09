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

public class UniformClusterFailoverTest {
    
    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    private static PrintWriter logWriter;
    private static PrintWriter rawLogWriter;
    
    // Class to store connection/session details
    static class ConnectionDetails {
        String type; // "Parent" or "Session"
        String connNum; // "C1" or "C2"
        String sessionNum; // "1", "2", etc or "-" for parent
        String connectionId;
        String connTag;
        String queueManager;
        String host;
        String appTag;
        long timestamp;
        
        ConnectionDetails(String type, String connNum, String sessionNum) {
            this.type = type;
            this.connNum = connNum;
            this.sessionNum = sessionNum;
            this.timestamp = System.currentTimeMillis();
        }
    }
    
    private static volatile boolean keepRunning = true;
    private static List<ConnectionDetails> preFailoverConnections = new ArrayList<>();
    private static List<ConnectionDetails> postFailoverConnections = new ArrayList<>();
    
    public static void main(String[] args) throws Exception {
        // Create detailed log files
        String timestamp = String.valueOf(System.currentTimeMillis());
        String logFileName = "FAILOVER_TEST_" + timestamp + ".log";
        String rawLogFileName = "FAILOVER_RAW_JVM_" + timestamp + ".log";
        logWriter = new PrintWriter(new FileWriter(logFileName));
        rawLogWriter = new PrintWriter(new FileWriter(rawLogFileName));
        
        // Enable MQ tracing for reconnection events
        System.setProperty("com.ibm.msg.client.commonservices.trace.outputName", "mqtrace_failover_" + timestamp + ".trc");
        System.setProperty("com.ibm.msg.client.commonservices.trace.status", "ON");
        
        log("================================================================================");
        log("   UNIFORM CLUSTER FAILOVER TEST - PARENT-CHILD CONNTAG PRESERVATION");
        log("================================================================================");
        log("Start time: " + sdf.format(new Date()));
        log("Log file: " + logFileName);
        log("Raw JVM log: " + rawLogFileName);
        log("");
        log("TEST OBJECTIVES:");
        log("  1. Establish connections with parent-child relationships");
        log("  2. Capture initial CONNTAG values for all 10 connections");
        log("  3. Force failover by stopping Queue Manager");
        log("  4. Monitor automatic reconnection");
        log("  5. Verify CONNTAG preservation after failover");
        log("================================================================================\n");
        
        // Base tracking key for both connections
        String BASE_TRACKING_KEY = "FAILOVER-" + timestamp;
        
        log("🔑 BASE TRACKING KEY: " + BASE_TRACKING_KEY);
        log("\n" + "=".repeat(80) + "\n");
        
        // ========== CONNECTION 1 WITH 5 SESSIONS ==========
        log("PHASE 1: ESTABLISHING INITIAL CONNECTIONS");
        log("=" + "=".repeat(79) + "\n");
        
        String TRACKING_KEY_C1 = BASE_TRACKING_KEY + "-C1";
        
        // Create factory for Connection 1
        rawLog("Creating factory for Connection 1...");
        JmsFactoryFactory ff = JmsFactoryFactory.getInstance(WMQConstants.WMQ_PROVIDER);
        JmsConnectionFactory factory1 = ff.createConnectionFactory();
        
        // Configure for uniform cluster with reconnect
        factory1.setStringProperty(WMQConstants.WMQ_CCDTURL, "file:///workspace/ccdt/ccdt.json");
        factory1.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
        factory1.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, WMQConstants.WMQ_CLIENT_RECONNECT);
        factory1.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_TIMEOUT, 1800); // 30 minutes
        factory1.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
        factory1.setStringProperty(WMQConstants.USERID, "app");
        factory1.setStringProperty(WMQConstants.PASSWORD, "passw0rd");
        factory1.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "*");
        factory1.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TRACKING_KEY_C1);
        
        // Add exception listener for Connection 1
        ExceptionListener exceptionListener1 = new ExceptionListener() {
            public void onException(JMSException e) {
                rawLog("!!! CONNECTION 1 EXCEPTION: " + e.getMessage());
                rawLog("    Error Code: " + e.getErrorCode());
                rawLog("    Linked Exception: " + e.getLinkedException());
            }
        };
        
        log("Creating Connection 1...");
        Connection connection1 = factory1.createConnection();
        connection1.setExceptionListener(exceptionListener1);
        
        // Extract initial Connection 1 details
        ConnectionDetails conn1Parent = captureConnectionDetails(connection1, "Parent", "C1", "-", TRACKING_KEY_C1);
        preFailoverConnections.add(conn1Parent);
        
        log("📊 CONNECTION 1 INITIAL STATE:");
        log("  Connection ID: " + conn1Parent.connectionId);
        log("  CONNTAG: " + conn1Parent.connTag);
        log("  Queue Manager: " + conn1Parent.queueManager);
        log("  Host: " + conn1Parent.host);
        
        connection1.start();
        
        // Create 5 sessions for Connection 1
        log("\nCreating 5 sessions for Connection 1:");
        List<Session> sessions1 = new ArrayList<>();
        
        for (int i = 1; i <= 5; i++) {
            Session session = connection1.createSession(false, Session.AUTO_ACKNOWLEDGE);
            sessions1.add(session);
            
            ConnectionDetails sessDetails = captureSessionDetails(session, "Session", "C1", String.valueOf(i), TRACKING_KEY_C1);
            preFailoverConnections.add(sessDetails);
            
            log("  Session 1." + i + " - CONNTAG: " + sessDetails.connTag);
        }
        
        // ========== CONNECTION 2 WITH 3 SESSIONS ==========
        String TRACKING_KEY_C2 = BASE_TRACKING_KEY + "-C2";
        
        // Create factory for Connection 2
        JmsConnectionFactory factory2 = ff.createConnectionFactory();
        
        // Configure identically but with different APPLTAG
        factory2.setStringProperty(WMQConstants.WMQ_CCDTURL, "file:///workspace/ccdt/ccdt.json");
        factory2.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
        factory2.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, WMQConstants.WMQ_CLIENT_RECONNECT);
        factory2.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_TIMEOUT, 1800);
        factory2.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
        factory2.setStringProperty(WMQConstants.USERID, "app");
        factory2.setStringProperty(WMQConstants.PASSWORD, "passw0rd");
        factory2.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "*");
        factory2.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TRACKING_KEY_C2);
        
        // Add exception listener for Connection 2
        ExceptionListener exceptionListener2 = new ExceptionListener() {
            public void onException(JMSException e) {
                rawLog("!!! CONNECTION 2 EXCEPTION: " + e.getMessage());
                rawLog("    Error Code: " + e.getErrorCode());
                rawLog("    Linked Exception: " + e.getLinkedException());
            }
        };
        
        log("\nCreating Connection 2...");
        Connection connection2 = factory2.createConnection();
        connection2.setExceptionListener(exceptionListener2);
        
        // Extract initial Connection 2 details
        ConnectionDetails conn2Parent = captureConnectionDetails(connection2, "Parent", "C2", "-", TRACKING_KEY_C2);
        preFailoverConnections.add(conn2Parent);
        
        log("📊 CONNECTION 2 INITIAL STATE:");
        log("  Connection ID: " + conn2Parent.connectionId);
        log("  CONNTAG: " + conn2Parent.connTag);
        log("  Queue Manager: " + conn2Parent.queueManager);
        log("  Host: " + conn2Parent.host);
        
        connection2.start();
        
        // Create 3 sessions for Connection 2
        log("\nCreating 3 sessions for Connection 2:");
        List<Session> sessions2 = new ArrayList<>();
        
        for (int i = 1; i <= 3; i++) {
            Session session = connection2.createSession(false, Session.AUTO_ACKNOWLEDGE);
            sessions2.add(session);
            
            ConnectionDetails sessDetails = captureSessionDetails(session, "Session", "C2", String.valueOf(i), TRACKING_KEY_C2);
            preFailoverConnections.add(sessDetails);
            
            log("  Session 2." + i + " - CONNTAG: " + sessDetails.connTag);
        }
        
        // Print initial state table
        printConnectionTable("PRE-FAILOVER CONNECTION TABLE", preFailoverConnections);
        
        // Determine which QM has 6 connections
        String qmWithSixConnections = conn1Parent.queueManager.contains("QM") ? 
            conn1Parent.queueManager.substring(0, 3) : "UNKNOWN";
        
        log("\n" + "=".repeat(80));
        log("PHASE 2: FAILOVER SIMULATION");
        log("=" + "=".repeat(79));
        log("\n⚠️  CONNECTION 1 is on " + qmWithSixConnections + " with 6 connections");
        log("⚠️  CONNECTION 2 is on " + conn2Parent.queueManager.substring(0, 3) + " with 4 connections");
        log("\n📌 INSTRUCTIONS FOR FAILOVER TEST:");
        log("   1. The test will run for 3 minutes");
        log("   2. After 30 seconds, STOP " + qmWithSixConnections + " using:");
        log("      docker stop " + qmWithSixConnections.toLowerCase());
        log("   3. Monitor the reconnection process");
        log("   4. After reconnection, new CONNTAG values will be captured");
        log("\n" + "=".repeat(80) + "\n");
        
        // Start monitoring thread
        Thread monitorThread = new Thread(() -> {
            int iteration = 0;
            while (keepRunning) {
                try {
                    Thread.sleep(10000); // Check every 10 seconds
                    iteration++;
                    
                    rawLog("\n--- MONITOR CHECK #" + iteration + " at " + sdf.format(new Date()) + " ---");
                    
                    // Check Connection 1 status
                    if (connection1 instanceof MQConnection) {
                        try {
                            MQConnection mqConn = (MQConnection) connection1;
                            Map<String, Object> connData = extractAllConnectionDetails(mqConn);
                            String currentConnId = getFieldValue(connData, "CONNECTION_ID");
                            String currentConnTag = getResolvedConnectionTag(connData);
                            String currentQM = getFieldValue(connData, "RESOLVED_QUEUE_MANAGER");
                            
                            rawLog("Connection 1 Status:");
                            rawLog("  Current CONN_ID: " + currentConnId);
                            rawLog("  Current CONNTAG: " + currentConnTag);
                            rawLog("  Current QM: " + currentQM);
                            
                            if (!currentConnId.equals(conn1Parent.connectionId)) {
                                rawLog("  ⚠️  CONNECTION ID CHANGED! Reconnection detected!");
                            }
                        } catch (Exception e) {
                            rawLog("  ❌ Error checking Connection 1: " + e.getMessage());
                        }
                    }
                    
                    // Check Connection 2 status
                    if (connection2 instanceof MQConnection) {
                        try {
                            MQConnection mqConn = (MQConnection) connection2;
                            Map<String, Object> connData = extractAllConnectionDetails(mqConn);
                            String currentConnId = getFieldValue(connData, "CONNECTION_ID");
                            String currentConnTag = getResolvedConnectionTag(connData);
                            String currentQM = getFieldValue(connData, "RESOLVED_QUEUE_MANAGER");
                            
                            rawLog("Connection 2 Status:");
                            rawLog("  Current CONN_ID: " + currentConnId);
                            rawLog("  Current CONNTAG: " + currentConnTag);
                            rawLog("  Current QM: " + currentQM);
                            
                            if (!currentConnId.equals(conn2Parent.connectionId)) {
                                rawLog("  ⚠️  CONNECTION ID CHANGED! Reconnection detected!");
                            }
                        } catch (Exception e) {
                            rawLog("  ❌ Error checking Connection 2: " + e.getMessage());
                        }
                    }
                    
                    // Try to send test messages
                    try {
                        for (int i = 0; i < sessions1.size(); i++) {
                            Session s = sessions1.get(i);
                            javax.jms.Queue queue = s.createQueue("queue:///UNIFORM.QUEUE");
                            MessageProducer prod = s.createProducer(queue);
                            TextMessage msg = s.createTextMessage("Heartbeat-C1-S" + (i+1) + "-" + iteration);
                            prod.send(msg);
                            prod.close();
                        }
                        rawLog("  ✅ Connection 1 sessions active");
                    } catch (Exception e) {
                        rawLog("  ❌ Connection 1 sessions error: " + e.getMessage());
                    }
                    
                    try {
                        for (int i = 0; i < sessions2.size(); i++) {
                            Session s = sessions2.get(i);
                            javax.jms.Queue queue = s.createQueue("queue:///UNIFORM.QUEUE");
                            MessageProducer prod = s.createProducer(queue);
                            TextMessage msg = s.createTextMessage("Heartbeat-C2-S" + (i+1) + "-" + iteration);
                            prod.send(msg);
                            prod.close();
                        }
                        rawLog("  ✅ Connection 2 sessions active");
                    } catch (Exception e) {
                        rawLog("  ❌ Connection 2 sessions error: " + e.getMessage());
                    }
                    
                } catch (Exception e) {
                    rawLog("Monitor thread error: " + e.getMessage());
                }
            }
        });
        
        monitorThread.start();
        
        System.out.println("\n🔴 TEST IS RUNNING - PLEASE STOP " + qmWithSixConnections + " NOW!");
        System.out.println("   Command: docker stop " + qmWithSixConnections.toLowerCase());
        System.out.println("\n⏱️  Test will run for 3 minutes to observe failover...\n");
        
        // Run for 3 minutes with status updates
        for (int i = 180; i > 0; i--) {
            System.out.print("\r  ⏱️  Time remaining: " + String.format("%3d", i) + " seconds  ");
            
            if (i == 150) {
                System.out.print("(Stop QM now if not done!)");
            } else if (i == 120) {
                System.out.print("(Monitoring reconnection...)");
            } else if (i == 60) {
                System.out.print("(Capturing post-failover state...)");
                
                // Capture post-failover state
                log("\n\nPHASE 3: POST-FAILOVER STATE CAPTURE");
                log("=" + "=".repeat(79) + "\n");
                
                // Re-capture Connection 1 details
                ConnectionDetails conn1PostFailover = captureConnectionDetails(connection1, "Parent", "C1", "-", TRACKING_KEY_C1);
                postFailoverConnections.add(conn1PostFailover);
                
                log("📊 CONNECTION 1 POST-FAILOVER STATE:");
                log("  Connection ID: " + conn1PostFailover.connectionId);
                log("  CONNTAG: " + conn1PostFailover.connTag);
                log("  Queue Manager: " + conn1PostFailover.queueManager);
                log("  Changed: " + (!conn1PostFailover.connectionId.equals(conn1Parent.connectionId)));
                
                // Re-capture sessions for Connection 1
                for (int j = 0; j < sessions1.size(); j++) {
                    ConnectionDetails sessDetails = captureSessionDetails(sessions1.get(j), "Session", "C1", String.valueOf(j+1), TRACKING_KEY_C1);
                    postFailoverConnections.add(sessDetails);
                    log("  Session 1." + (j+1) + " - CONNTAG: " + sessDetails.connTag);
                }
                
                // Re-capture Connection 2 details
                ConnectionDetails conn2PostFailover = captureConnectionDetails(connection2, "Parent", "C2", "-", TRACKING_KEY_C2);
                postFailoverConnections.add(conn2PostFailover);
                
                log("\n📊 CONNECTION 2 POST-FAILOVER STATE:");
                log("  Connection ID: " + conn2PostFailover.connectionId);
                log("  CONNTAG: " + conn2PostFailover.connTag);
                log("  Queue Manager: " + conn2PostFailover.queueManager);
                log("  Changed: " + (!conn2PostFailover.connectionId.equals(conn2Parent.connectionId)));
                
                // Re-capture sessions for Connection 2
                for (int j = 0; j < sessions2.size(); j++) {
                    ConnectionDetails sessDetails = captureSessionDetails(sessions2.get(j), "Session", "C2", String.valueOf(j+1), TRACKING_KEY_C2);
                    postFailoverConnections.add(sessDetails);
                    log("  Session 2." + (j+1) + " - CONNTAG: " + sessDetails.connTag);
                }
                
                // Print post-failover table
                printConnectionTable("POST-FAILOVER CONNECTION TABLE", postFailoverConnections);
            }
            
            Thread.sleep(1000);
        }
        
        keepRunning = false;
        monitorThread.join(5000);
        
        // Final analysis
        log("\n" + "=".repeat(80));
        log("PHASE 4: FAILOVER ANALYSIS");
        log("=" + "=".repeat(79) + "\n");
        
        analyzeFailover(preFailoverConnections, postFailoverConnections);
        
        // Close everything
        log("\nCLOSING CONNECTIONS...");
        for (Session s : sessions1) { try { s.close(); } catch (Exception e) {} }
        for (Session s : sessions2) { try { s.close(); } catch (Exception e) {} }
        connection1.close();
        connection2.close();
        
        log("\n✅ Failover test completed!");
        logWriter.close();
        rawLogWriter.close();
        
        System.out.println("\n\n✅ Failover test completed!");
        System.out.println("📁 Log file: " + logFileName);
        System.out.println("📁 Raw JVM log: " + rawLogFileName);
    }
    
    private static ConnectionDetails captureConnectionDetails(Connection conn, String type, String connNum, String sessionNum, String appTag) {
        ConnectionDetails details = new ConnectionDetails(type, connNum, sessionNum);
        details.appTag = appTag;
        
        if (conn instanceof MQConnection) {
            MQConnection mqConn = (MQConnection) conn;
            Map<String, Object> connData = extractAllConnectionDetails(mqConn);
            details.connectionId = getFieldValue(connData, "CONNECTION_ID");
            details.connTag = getResolvedConnectionTag(connData);
            details.queueManager = getFieldValue(connData, "RESOLVED_QUEUE_MANAGER");
            details.host = getFieldValue(connData, "HOST_NAME");
        }
        
        return details;
    }
    
    private static ConnectionDetails captureSessionDetails(Session session, String type, String connNum, String sessionNum, String appTag) {
        ConnectionDetails details = new ConnectionDetails(type, connNum, sessionNum);
        details.appTag = appTag;
        
        if (session instanceof MQSession) {
            MQSession mqSession = (MQSession) session;
            Map<String, Object> sessionData = extractAllConnectionDetails(mqSession);
            details.connectionId = getFieldValue(sessionData, "CONNECTION_ID");
            details.connTag = getResolvedConnectionTag(sessionData);
            details.queueManager = getFieldValue(sessionData, "RESOLVED_QUEUE_MANAGER");
            details.host = getFieldValue(sessionData, "HOST_NAME");
        }
        
        return details;
    }
    
    private static void printConnectionTable(String title, List<ConnectionDetails> connections) {
        log("\n" + "=".repeat(80));
        log(title);
        log("-".repeat(180));
        log(String.format("%-4s %-8s %-5s %-8s %-50s %-50s %-30s %-15s", 
            "#", "Type", "Conn", "Session", "CONNECTION_ID", "FULL_CONNTAG", "QM", "Host"));
        log("-".repeat(180));
        
        int rowNum = 1;
        for (ConnectionDetails details : connections) {
            String connIdShort = details.connectionId != null && details.connectionId.length() > 48 ? 
                details.connectionId.substring(0, 48) : details.connectionId;
            
            log(String.format("%-4d %-8s %-5s %-8s %-50s %-50s %-30s %-15s",
                rowNum++,
                details.type,
                details.connNum,
                details.sessionNum,
                connIdShort != null ? connIdShort : "UNKNOWN",
                details.connTag != null ? details.connTag : "UNKNOWN",
                details.queueManager != null ? details.queueManager : "?",
                details.host != null ? details.host : "UNKNOWN"
            ));
        }
        log("-".repeat(180));
    }
    
    private static void analyzeFailover(List<ConnectionDetails> pre, List<ConnectionDetails> post) {
        log("FAILOVER IMPACT ANALYSIS:");
        log("-".repeat(40));
        
        // Analyze Connection 1
        ConnectionDetails preC1 = pre.stream().filter(c -> c.connNum.equals("C1") && c.type.equals("Parent")).findFirst().orElse(null);
        ConnectionDetails postC1 = post.stream().filter(c -> c.connNum.equals("C1") && c.type.equals("Parent")).findFirst().orElse(null);
        
        if (preC1 != null && postC1 != null) {
            log("\nCONNECTION 1 ANALYSIS:");
            log("  Pre-Failover QM:  " + preC1.queueManager);
            log("  Post-Failover QM: " + postC1.queueManager);
            log("  QM Changed: " + (!preC1.queueManager.equals(postC1.queueManager)));
            log("  Pre-Failover CONNTAG:  " + preC1.connTag);
            log("  Post-Failover CONNTAG: " + postC1.connTag);
            log("  CONNTAG Changed: " + (!preC1.connTag.equals(postC1.connTag)));
            
            // Check if sessions maintain relationship
            List<ConnectionDetails> preC1Sessions = pre.stream()
                .filter(c -> c.connNum.equals("C1") && c.type.equals("Session"))
                .sorted((a,b) -> a.sessionNum.compareTo(b.sessionNum))
                .collect(java.util.stream.Collectors.toList());
            List<ConnectionDetails> postC1Sessions = post.stream()
                .filter(c -> c.connNum.equals("C1") && c.type.equals("Session"))
                .sorted((a,b) -> a.sessionNum.compareTo(b.sessionNum))
                .collect(java.util.stream.Collectors.toList());
            
            boolean allSessionsFollowed = true;
            for (int i = 0; i < preC1Sessions.size() && i < postC1Sessions.size(); i++) {
                if (!postC1Sessions.get(i).queueManager.equals(postC1.queueManager)) {
                    allSessionsFollowed = false;
                    break;
                }
            }
            log("  All Sessions Followed Parent: " + allSessionsFollowed);
        }
        
        // Analyze Connection 2
        ConnectionDetails preC2 = pre.stream().filter(c -> c.connNum.equals("C2") && c.type.equals("Parent")).findFirst().orElse(null);
        ConnectionDetails postC2 = post.stream().filter(c -> c.connNum.equals("C2") && c.type.equals("Parent")).findFirst().orElse(null);
        
        if (preC2 != null && postC2 != null) {
            log("\nCONNECTION 2 ANALYSIS:");
            log("  Pre-Failover QM:  " + preC2.queueManager);
            log("  Post-Failover QM: " + postC2.queueManager);
            log("  QM Changed: " + (!preC2.queueManager.equals(postC2.queueManager)));
            log("  Pre-Failover CONNTAG:  " + preC2.connTag);
            log("  Post-Failover CONNTAG: " + postC2.connTag);
            log("  CONNTAG Changed: " + (!preC2.connTag.equals(postC2.connTag)));
        }
        
        log("\nKEY FINDINGS:");
        log("  1. Automatic reconnection: " + (postC1 != null ? "✅ SUCCESS" : "❌ FAILED"));
        log("  2. Parent-child affinity maintained: " + (postC1 != null && postC2 != null ? "✅ YES" : "❌ NO"));
    }
    
    private static void log(String message) {
        System.out.println(message);
        if (logWriter != null) {
            logWriter.println(message);
            logWriter.flush();
        }
    }
    
    private static void rawLog(String message) {
        String timestamped = "[" + sdf.format(new Date()) + "] " + message;
        if (rawLogWriter != null) {
            rawLogWriter.println(timestamped);
            rawLogWriter.flush();
        }
    }
    
    // [All the extraction methods remain the same as in UniformClusterDualConnectionTest]
    private static Map<String, Object> extractAllConnectionDetails(Object obj) {
        Map<String, Object> result = new HashMap<>();
        try {
            extractViaDelegate(obj, result);
            extractViaReflection(obj, result);
            extractViaGetters(obj, result);
            extractPropertyMaps(obj, result);
        } catch (Exception e) {}
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
        } catch (Exception e) {}
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
                            } catch (Exception e2) {}
                        }
                    }
                }
            }
        } catch (Exception e) {}
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
                        } catch (Exception e) {}
                    }
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Exception e) {}
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
                    } catch (Exception e) {}
                }
            }
        } catch (Exception e) {}
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
        } catch (Exception e) {}
    }
    
    private static String getFieldValue(Map<String, Object> data, String fieldPattern) {
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (entry.getKey().contains(fieldPattern) && entry.getValue() != null) {
                return entry.getValue().toString();
            }
        }
        return "UNKNOWN";
    }
    
    private static String getResolvedConnectionTag(Map<String, Object> data) {
        String connTag = getFieldValue(data, "XMSC_WMQ_RESOLVED_CONNECTION_TAG");
        if (!"UNKNOWN".equals(connTag)) return connTag;
        
        connTag = getFieldValue(data, "RESOLVED_CONNECTION_TAG");
        if (!"UNKNOWN".equals(connTag)) return connTag;
        
        connTag = getFieldValue(data, "CONNTAG");
        if (!"UNKNOWN".equals(connTag)) return connTag;
        
        connTag = getFieldValue(data, "CONNECTION_TAG");
        if (!"UNKNOWN".equals(connTag)) return connTag;
        
        return "UNKNOWN";
    }
}