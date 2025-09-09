import javax.jms.*;
import com.ibm.msg.client.jms.*;
import com.ibm.msg.client.wmq.WMQConstants;
import com.ibm.mq.*;
import com.ibm.mq.headers.pcf.*;
import java.util.*;
import java.io.*;
import java.text.SimpleDateFormat;

/**
 * Comprehensive test with full tracing and debugging
 */
public class ComprehensiveTraceTest {
    
    private static PrintWriter log;
    private static SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
    
    public static void main(String[] args) throws Exception {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String baseTag = "COMPREHENSIVE-" + timestamp;
        
        // Create log file
        log = new PrintWriter(new FileWriter("comprehensive_trace_" + timestamp + ".log"));
        
        logHeader("COMPREHENSIVE TRACE TEST");
        log("Test ID: " + timestamp);
        log("Base Tag: " + baseTag);
        log("");
        
        // Test 1: PCF functionality check
        logHeader("TEST 1: PCF FUNCTIONALITY CHECK");
        testPCFFunctionality();
        
        // Test 2: Single QM parent-child test
        logHeader("TEST 2: SINGLE QM PARENT-CHILD TEST");
        testSingleQMParentChild("QM1", "10.10.10.10", baseTag + "-QM1");
        
        // Test 3: Multi-QM distribution test
        logHeader("TEST 3: MULTI-QM DISTRIBUTION TEST");
        testMultiQMDistribution(baseTag);
        
        // Test 4: MQSC connection analysis
        logHeader("TEST 4: MQSC CONNECTION ANALYSIS");
        analyzeMQSCConnections();
        
        // Test 5: Connection sharing investigation
        logHeader("TEST 5: CONNECTION SHARING INVESTIGATION");
        investigateConnectionSharing(baseTag + "-SHARE");
        
        logHeader("TEST COMPLETE");
        log("Results saved to: comprehensive_trace_" + timestamp + ".log");
        
        log.close();
        System.out.println("\nTest complete. See comprehensive_trace_" + timestamp + ".log for details.");
    }
    
    private static void testPCFFunctionality() {
        log("Testing PCF API functionality on all Queue Managers...\n");
        
        for (int i = 1; i <= 3; i++) {
            String qm = "QM" + i;
            String host = "10.10.10." + (9 + i);
            
            log("Testing " + qm + " at " + host + ":");
            
            MQQueueManager qmgr = null;
            PCFMessageAgent agent = null;
            
            try {
                // Connect
                Hashtable<String, Object> props = new Hashtable<>();
                props.put(com.ibm.mq.constants.CMQC.HOST_NAME_PROPERTY, host);
                props.put(com.ibm.mq.constants.CMQC.PORT_PROPERTY, 1414);
                props.put(com.ibm.mq.constants.CMQC.CHANNEL_PROPERTY, "APP.SVRCONN");
                props.put(com.ibm.mq.constants.CMQC.USE_MQCSP_AUTHENTICATION_PROPERTY, false);
                
                qmgr = new MQQueueManager(qm, props);
                agent = new PCFMessageAgent(qmgr);
                
                // Test INQUIRE_Q_MGR
                PCFMessage request = new PCFMessage(com.ibm.mq.constants.CMQCFC.MQCMD_INQUIRE_Q_MGR);
                PCFMessage[] responses = agent.send(request);
                log("  ✓ INQUIRE_Q_MGR: Success (" + responses.length + " response)");
                
                // Extract QM name from response
                if (responses.length > 0) {
                    try {
                        String qmName = responses[0].getStringParameterValue(2015); // MQCA_Q_MGR_NAME
                        log("    Queue Manager Name: " + qmName.trim());
                    } catch (Exception e) {
                        // Ignore
                    }
                }
                
                // Test INQUIRE_CONNECTION
                try {
                    request = new PCFMessage(1201); // MQCMD_INQUIRE_CONNECTION
                    responses = agent.send(request);
                    log("  ✓ INQUIRE_CONNECTION: Success (" + responses.length + " connections)");
                } catch (PCFException e) {
                    log("  ✗ INQUIRE_CONNECTION: Failed (Reason " + e.getReason() + ")");
                    if (e.getReason() == 3007) {
                        log("    Error 3007: MQRCCF_CFH_TYPE_ERROR - Command not supported");
                    }
                }
                
            } catch (Exception e) {
                log("  ✗ Error: " + e.getMessage());
            } finally {
                if (agent != null) try { agent.disconnect(); } catch (Exception e) {}
                if (qmgr != null) try { qmgr.disconnect(); } catch (Exception e) {}
            }
            log("");
        }
    }
    
    private static void testSingleQMParentChild(String qm, String host, String appTag) {
        log("Testing parent-child relationship on " + qm);
        log("APPLTAG: " + appTag);
        log("");
        
        Connection conn = null;
        
        try {
            // Create connection
            log("Creating JMS Connection...");
            JmsFactoryFactory ff = JmsFactoryFactory.getInstance(WMQConstants.WMQ_PROVIDER);
            JmsConnectionFactory cf = ff.createConnectionFactory();
            
            cf.setStringProperty(WMQConstants.WMQ_HOST_NAME, host);
            cf.setIntProperty(WMQConstants.WMQ_PORT, 1414);
            cf.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, qm);
            cf.setStringProperty(WMQConstants.WMQ_CHANNEL, "APP.SVRCONN");
            cf.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
            cf.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, appTag);
            
            conn = cf.createConnection("app", "passw0rd");
            conn.start();
            
            // Get connection metadata
            ConnectionMetaData meta = conn.getMetaData();
            log("  JMS Provider: " + meta.getJMSProviderName() + " " + meta.getProviderVersion());
            
            // Get WMQ properties
            if (conn instanceof JmsPropertyContext) {
                JmsPropertyContext cpc = (JmsPropertyContext) conn;
                
                // Get various properties
                String resolvedQm = cpc.getStringProperty(WMQConstants.WMQ_RESOLVED_QUEUE_MANAGER);
                String hostName = cpc.getStringProperty(WMQConstants.WMQ_HOST_NAME);
                int port = cpc.getIntProperty(WMQConstants.WMQ_PORT);
                
                log("  Resolved QM: " + resolvedQm);
                log("  Connected to: " + hostName + ":" + port);
                
                // Try to get connection ID (might not be available)
                try {
                    String connTag = cpc.getStringProperty(WMQConstants.WMQ_CONNECTION_TAG);
                    log("  Connection Tag: " + connTag);
                } catch (Exception e) {
                    // Not available
                }
            }
            
            // Query initial connections
            log("\nBefore creating sessions:");
            int before = queryConnectionsViaDocker(qm, appTag);
            log("  Connections with APPLTAG: " + before);
            
            // Create sessions
            log("\nCreating 5 sessions...");
            List<Session> sessions = new ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
                sessions.add(session);
                log("  Session " + i + " created");
                
                // Create a temporary queue to force real connection
                try {
                    javax.jms.Queue tempQueue = session.createTemporaryQueue();
                    log("    Temporary queue created: " + tempQueue.getQueueName());
                } catch (Exception e) {
                    log("    Could not create temp queue: " + e.getMessage());
                }
            }
            
            Thread.sleep(2000);
            
            // Query after sessions
            log("\nAfter creating sessions:");
            int after = queryConnectionsViaDocker(qm, appTag);
            log("  Connections with APPLTAG: " + after);
            log("  Difference: " + (after - before) + " new connections");
            
            // Get detailed info
            log("\nConnection details from MQSC:");
            showDetailedConnections(qm, appTag);
            
            // Cleanup
            for (Session s : sessions) {
                try { s.close(); } catch (Exception e) {}
            }
            
        } catch (Exception e) {
            log("Error: " + e.getMessage());
            e.printStackTrace(log);
        } finally {
            if (conn != null) {
                try { conn.close(); } catch (Exception e) {}
            }
        }
        
        log("");
    }
    
    private static void testMultiQMDistribution(String baseTag) {
        log("Testing connection distribution across QMs");
        log("Using CCDT for distribution");
        log("");
        
        // Create multiple connections to see distribution
        for (int i = 1; i <= 3; i++) {
            String appTag = baseTag + "-DIST-" + i;
            log("Connection " + i + " with APPLTAG: " + appTag);
            
            try {
                JmsFactoryFactory ff = JmsFactoryFactory.getInstance(WMQConstants.WMQ_PROVIDER);
                JmsConnectionFactory cf = ff.createConnectionFactory();
                
                // Use CCDT for distribution
                cf.setStringProperty(WMQConstants.WMQ_CCDTURL, "file:///workspace/ccdt/ccdt.json");
                cf.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "*");
                cf.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
                cf.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, appTag);
                
                Connection conn = cf.createConnection("app", "passw0rd");
                conn.start();
                
                if (conn instanceof JmsPropertyContext) {
                    JmsPropertyContext cpc = (JmsPropertyContext) conn;
                    String resolvedQm = cpc.getStringProperty(WMQConstants.WMQ_RESOLVED_QUEUE_MANAGER);
                    log("  Resolved to: " + resolvedQm);
                }
                
                // Create a session to ensure connection
                Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
                
                Thread.sleep(500);
                
                // Find which QM has this connection
                for (int qmNum = 1; qmNum <= 3; qmNum++) {
                    String qm = "QM" + qmNum;
                    int count = queryConnectionsViaDocker(qm, appTag);
                    if (count > 0) {
                        log("  Found on " + qm + " (" + count + " connections)");
                    }
                }
                
                session.close();
                conn.close();
                
            } catch (Exception e) {
                log("  Error: " + e.getMessage());
            }
            log("");
        }
    }
    
    private static void analyzeMQSCConnections() {
        log("Analyzing current connections on all QMs");
        log("");
        
        for (int i = 1; i <= 3; i++) {
            String qm = "QM" + i;
            log(qm + " connections:");
            
            try {
                // Get total connection count
                String cmd = String.format(
                    "docker exec %s bash -c \"echo 'DIS CONN(*) TYPE(CONN)' | runmqsc %s | grep -c 'CONN('\"",
                    qm.toLowerCase(), qm
                );
                
                Process p = Runtime.getRuntime().exec(new String[]{"bash", "-c", cmd});
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line = reader.readLine();
                p.waitFor();
                
                int total = line != null ? Integer.parseInt(line.trim()) : 0;
                log("  Total connections: " + total);
                
                // Get connections by channel
                cmd = String.format(
                    "docker exec %s bash -c \"echo 'DIS CONN(*) WHERE(CHANNEL EQ APP.SVRCONN)' | runmqsc %s | grep -c 'CONN('\"",
                    qm.toLowerCase(), qm
                );
                
                p = Runtime.getRuntime().exec(new String[]{"bash", "-c", cmd});
                reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                line = reader.readLine();
                p.waitFor();
                
                int appConns = line != null ? Integer.parseInt(line.trim()) : 0;
                log("  APP.SVRCONN connections: " + appConns);
                
            } catch (Exception e) {
                log("  Error: " + e.getMessage());
            }
            log("");
        }
    }
    
    private static void investigateConnectionSharing(String appTag) {
        log("Investigating connection sharing behavior");
        log("APPLTAG: " + appTag);
        log("");
        
        try {
            // Create connection with sharing disabled
            log("Creating connection with sharing considerations...");
            
            JmsFactoryFactory ff = JmsFactoryFactory.getInstance(WMQConstants.WMQ_PROVIDER);
            JmsConnectionFactory cf = ff.createConnectionFactory();
            
            cf.setStringProperty(WMQConstants.WMQ_HOST_NAME, "10.10.10.10");
            cf.setIntProperty(WMQConstants.WMQ_PORT, 1414);
            cf.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "QM1");
            cf.setStringProperty(WMQConstants.WMQ_CHANNEL, "APP.SVRCONN");
            cf.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
            cf.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, appTag);
            
            Connection conn = cf.createConnection("app", "passw0rd");
            conn.start();
            
            log("  Connection created");
            
            // Check initial
            int initial = queryConnectionsViaDocker("QM1", appTag);
            log("  Initial connections: " + initial);
            
            // Create multiple sessions with actual operations
            log("\nCreating sessions with queue operations...");
            List<Session> sessions = new ArrayList<>();
            List<MessageProducer> producers = new ArrayList<>();
            
            for (int i = 1; i <= 3; i++) {
                Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
                sessions.add(session);
                
                // Create a producer to force real connection
                javax.jms.Queue queue = session.createQueue("DEMO.QUEUE");
                MessageProducer producer = session.createProducer(queue);
                producers.add(producer);
                
                // Send a message
                TextMessage msg = session.createTextMessage("Test " + i);
                msg.setStringProperty("SessionNum", String.valueOf(i));
                producer.send(msg);
                
                log("  Session " + i + ": Created producer and sent message");
                
                Thread.sleep(500);
                
                // Check connections after each session
                int current = queryConnectionsViaDocker("QM1", appTag);
                log("    Current connections: " + current);
            }
            
            log("\nFinal connection count:");
            int finalCount = queryConnectionsViaDocker("QM1", appTag);
            log("  Total connections with APPLTAG: " + finalCount);
            
            if (finalCount == initial) {
                log("  ⚠ Connection sharing is active (sessions multiplexed)");
            } else {
                log("  ✓ Each session has separate connection");
            }
            
            // Cleanup
            for (MessageProducer p : producers) p.close();
            for (Session s : sessions) s.close();
            conn.close();
            
        } catch (Exception e) {
            log("Error: " + e.getMessage());
        }
        log("");
    }
    
    private static int queryConnectionsViaDocker(String qm, String appTag) {
        try {
            String cmd = String.format(
                "docker exec %s bash -c \"echo 'DIS CONN(*) WHERE(APPLTAG EQ %s)' | runmqsc %s | grep -c 'CONN('\"",
                qm.toLowerCase(), appTag, qm
            );
            
            Process p = Runtime.getRuntime().exec(new String[]{"bash", "-c", cmd});
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = reader.readLine();
            p.waitFor();
            
            return line != null ? Integer.parseInt(line.trim()) : 0;
        } catch (Exception e) {
            return -1;
        }
    }
    
    private static void showDetailedConnections(String qm, String appTag) {
        try {
            String cmd = String.format(
                "docker exec %s bash -c \"echo 'DIS CONN(*) WHERE(APPLTAG EQ %s) ALL' | runmqsc %s\"",
                qm.toLowerCase(), appTag, qm
            );
            
            Process p = Runtime.getRuntime().exec(new String[]{"bash", "-c", cmd});
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            
            String line;
            boolean inConnection = false;
            while ((line = reader.readLine()) != null) {
                if (line.contains("CONN(")) {
                    inConnection = true;
                    String connId = line.substring(line.indexOf("CONN(") + 5, line.indexOf(")"));
                    log("  Connection: " + connId);
                } else if (inConnection && line.contains("CHANNEL(")) {
                    String channel = extractValue(line, "CHANNEL");
                    log("    Channel: " + channel);
                } else if (inConnection && line.contains("CONNAME(")) {
                    String conname = extractValue(line, "CONNAME");
                    log("    Conname: " + conname);
                } else if (inConnection && line.contains("USERID(")) {
                    String userid = extractValue(line, "USERID");
                    log("    UserId: " + userid);
                }
            }
            p.waitFor();
            
        } catch (Exception e) {
            log("  Error getting details: " + e.getMessage());
        }
    }
    
    private static String extractValue(String line, String key) {
        int start = line.indexOf(key + "(");
        if (start == -1) return "";
        start += key.length() + 1;
        int end = line.indexOf(")", start);
        if (end == -1) return "";
        return line.substring(start, end).trim();
    }
    
    private static void logHeader(String header) {
        log("");
        log("================================================");
        log(" " + header);
        log("================================================");
        log("");
    }
    
    private static void log(String message) {
        String timestamp = sdf.format(new Date());
        String logLine = "[" + timestamp + "] " + message;
        log.println(logLine);
        log.flush();
        System.out.println(logLine);
    }
}