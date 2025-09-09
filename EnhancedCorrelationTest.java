import javax.jms.*;
import com.ibm.msg.client.jms.*;
import com.ibm.msg.client.wmq.WMQConstants;
import com.ibm.mq.*;
import com.ibm.mq.headers.pcf.*;
import java.util.*;
import java.io.*;

public class EnhancedCorrelationTest {
    public static void main(String[] args) throws Exception {
        String baseTag = "ENHANCED-" + System.currentTimeMillis();
        PrintWriter log = new PrintWriter(new FileWriter("enhanced_test_log.txt"));
        
        log.println("===========================================");
        log.println(" ENHANCED CORRELATION TEST");
        log.println(" Tag: " + baseTag);
        log.println("===========================================\n");
        
        // Test each QM
        for (int qmNum = 1; qmNum <= 3; qmNum++) {
            String qmName = "QM" + qmNum;
            String host = "10.10.10." + (9 + qmNum);
            String appTag = baseTag + "-" + qmName;
            
            log.println("\n--- Testing " + qmName + " ---");
            log.println("Host: " + host);
            log.println("APPLTAG: " + appTag);
            
            try {
                // Create JMS connection
                JmsFactoryFactory ff = JmsFactoryFactory.getInstance(WMQConstants.WMQ_PROVIDER);
                JmsConnectionFactory cf = ff.createConnectionFactory();
                
                cf.setStringProperty(WMQConstants.WMQ_HOST_NAME, host);
                cf.setIntProperty(WMQConstants.WMQ_PORT, 1414);
                cf.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, qmName);
                cf.setStringProperty(WMQConstants.WMQ_CHANNEL, "APP.SVRCONN");
                cf.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
                cf.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, appTag);
                
                Connection conn = cf.createConnection("app", "passw0rd");
                conn.start();
                
                // Get connection properties
                JmsPropertyContext cpc = (JmsPropertyContext) conn;
                String connId = cpc.getStringProperty(WMQConstants.XMSC_WMQ_CONNECTION_ID);
                String resolvedQm = cpc.getStringProperty(WMQConstants.WMQ_RESOLVED_QUEUE_MANAGER);
                
                log.println("Connection created:");
                log.println("  Connection ID: " + connId);
                log.println("  Resolved QM: " + resolvedQm);
                
                // Query initial connections
                log.println("\nBefore sessions:");
                int before = queryMQSC(qmName, appTag);
                log.println("  Connections with tag: " + before);
                
                // Create sessions
                log.println("\nCreating 3 sessions...");
                List<Session> sessions = new ArrayList<>();
                for (int i = 1; i <= 3; i++) {
                    Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
                    sessions.add(session);
                    log.println("  Session " + i + " created");
                    
                    // Get session properties
                    if (session instanceof JmsPropertyContext) {
                        JmsPropertyContext spc = (JmsPropertyContext) session;
                        try {
                            String sessConnId = spc.getStringProperty(WMQConstants.XMSC_WMQ_CONNECTION_ID);
                            log.println("    Session connection ID: " + sessConnId);
                            if (connId.equals(sessConnId)) {
                                log.println("    ✓ Session inherits parent connection ID");
                            }
                        } catch (Exception e) {
                            log.println("    Session properties: " + e.getMessage());
                        }
                    }
                }
                
                Thread.sleep(2000);
                
                // Query after sessions
                log.println("\nAfter sessions:");
                int after = queryMQSC(qmName, appTag);
                log.println("  Connections with tag: " + after);
                log.println("  Child connections created: " + (after - before));
                
                // Get detailed connection info
                log.println("\nDetailed MQSC output:");
                getDetailedConnections(qmName, appTag, log);
                
                // Test PCF on this QM
                log.println("\nTesting PCF on " + qmName + ":");
                testPCF(host, qmName, log);
                
                // Cleanup
                for (Session s : sessions) s.close();
                conn.close();
                
                Thread.sleep(1000);
                log.println("\nAfter cleanup:");
                int cleanup = queryMQSC(qmName, appTag);
                log.println("  Remaining connections: " + cleanup);
                
            } catch (Exception e) {
                log.println("Error testing " + qmName + ": " + e.getMessage());
                e.printStackTrace(log);
            }
        }
        
        log.println("\n===========================================");
        log.println(" TEST COMPLETE");
        log.println("===========================================");
        log.close();
        
        // Print summary to console
        System.out.println("Test complete. Results in enhanced_test_log.txt");
    }
    
    private static int queryMQSC(String qm, String appTag) {
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
    
    private static void getDetailedConnections(String qm, String appTag, PrintWriter log) {
        try {
            String cmd = String.format(
                "docker exec %s bash -c \"echo 'DIS CONN(*) WHERE(APPLTAG EQ %s) ALL' | runmqsc %s\"",
                qm.toLowerCase(), appTag, qm
            );
            Process p = Runtime.getRuntime().exec(new String[]{"bash", "-c", cmd});
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("CONN(") || line.contains("CHANNEL(") || 
                    line.contains("CONNAME(") || line.contains("APPLTAG(")) {
                    log.println("  " + line.trim());
                }
            }
            p.waitFor();
        } catch (Exception e) {
            log.println("  Error getting details: " + e.getMessage());
        }
    }
    
    private static void testPCF(String host, String qm, PrintWriter log) {
        MQQueueManager qmgr = null;
        PCFMessageAgent agent = null;
        try {
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
            log.println("  INQUIRE_Q_MGR: ✓ Success (" + responses.length + " response)");
            
            // Test INQUIRE_CONNECTION
            request = new PCFMessage(1201); // MQCMD_INQUIRE_CONNECTION
            try {
                responses = agent.send(request);
                log.println("  INQUIRE_CONNECTION: ✓ Success (" + responses.length + " connections)");
            } catch (PCFException e) {
                log.println("  INQUIRE_CONNECTION: ✗ Failed (Reason " + e.getReason() + ")");
            }
            
        } catch (Exception e) {
            log.println("  PCF Error: " + e.getMessage());
        } finally {
            if (agent != null) try { agent.disconnect(); } catch (Exception e) {}
            if (qmgr != null) try { qmgr.disconnect(); } catch (Exception e) {}
        }
    }
}
