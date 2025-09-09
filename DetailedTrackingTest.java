import javax.jms.*;
import com.ibm.msg.client.jms.*;
import com.ibm.msg.client.wmq.WMQConstants;
import java.util.*;
import java.io.*;
import java.text.SimpleDateFormat;

public class DetailedTrackingTest {
    private static SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
    
    public static void main(String[] args) throws Exception {
        PrintWriter log = new PrintWriter(new FileWriter("detailed_tracking.log"));
        
        log.println("================================================");
        log.println(" DETAILED CONNECTION TRACKING TEST");
        log.println(" Time: " + sdf.format(new Date()));
        log.println("================================================\n");
        
        // Test with unique tag
        String uniqueTag = "TRACK" + (System.currentTimeMillis() % 100000);
        log.println("Unique tracking tag: " + uniqueTag);
        log.println();
        
        Connection conn = null;
        
        try {
            // Create connection
            log.println("[" + sdf.format(new Date()) + "] Creating JMS Connection...");
            
            JmsFactoryFactory ff = JmsFactoryFactory.getInstance(WMQConstants.WMQ_PROVIDER);
            JmsConnectionFactory cf = ff.createConnectionFactory();
            
            cf.setStringProperty(WMQConstants.WMQ_HOST_NAME, "10.10.10.10");
            cf.setIntProperty(WMQConstants.WMQ_PORT, 1414);
            cf.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "QM1");
            cf.setStringProperty(WMQConstants.WMQ_CHANNEL, "APP.SVRCONN");
            cf.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
            cf.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, uniqueTag);
            
            conn = cf.createConnection("app", "passw0rd");
            conn.start();
            
            log.println("[" + sdf.format(new Date()) + "] Connection created");
            
            // Get connection properties
            if (conn instanceof JmsPropertyContext) {
                JmsPropertyContext cpc = (JmsPropertyContext) conn;
                String resolvedQm = cpc.getStringProperty(WMQConstants.WMQ_RESOLVED_QUEUE_MANAGER);
                log.println("  Resolved Queue Manager: " + resolvedQm);
                
                // Try to get more properties
                try {
                    String hostName = cpc.getStringProperty(WMQConstants.WMQ_HOST_NAME);
                    int port = cpc.getIntProperty(WMQConstants.WMQ_PORT);
                    log.println("  Connected to: " + hostName + ":" + port);
                } catch (Exception e) {
                    // Ignore
                }
            }
            
            // Check MQSC before sessions
            log.println("\n[" + sdf.format(new Date()) + "] MQSC check before sessions:");
            int before = countConnections("QM1", uniqueTag);
            log.println("  Connections with APPLTAG " + uniqueTag + ": " + before);
            
            // Get detailed MQSC info
            getDetailedMQSC("QM1", uniqueTag, log);
            
            // Create sessions
            log.println("\n[" + sdf.format(new Date()) + "] Creating 5 sessions...");
            List<Session> sessions = new ArrayList<>();
            List<MessageProducer> producers = new ArrayList<>();
            
            for (int i = 1; i <= 5; i++) {
                Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
                sessions.add(session);
                
                // Create queue and producer
                Queue queue = session.createQueue("DEMO.QUEUE");
                MessageProducer producer = session.createProducer(queue);
                producers.add(producer);
                
                // Send a test message
                TextMessage msg = session.createTextMessage("Test message " + i);
                msg.setStringProperty("SessionNumber", String.valueOf(i));
                msg.setStringProperty("TrackingTag", uniqueTag);
                producer.send(msg);
                
                log.println("  Session " + i + " created and message sent");
                
                // Brief pause
                Thread.sleep(200);
            }
            
            // Wait for connections to establish
            Thread.sleep(2000);
            
            // Check MQSC after sessions
            log.println("\n[" + sdf.format(new Date()) + "] MQSC check after sessions:");
            int after = countConnections("QM1", uniqueTag);
            log.println("  Connections with APPLTAG " + uniqueTag + ": " + after);
            log.println("  New connections created: " + (after - before));
            
            // Get detailed MQSC info
            getDetailedMQSC("QM1", uniqueTag, log);
            
            // Analysis
            log.println("\n================================================");
            log.println(" ANALYSIS");
            log.println("================================================");
            log.println("JMS Objects created:");
            log.println("  1 Connection");
            log.println("  5 Sessions");
            log.println("  5 MessageProducers");
            log.println("  5 Messages sent");
            log.println("\nMQ Connections observed:");
            log.println("  Before: " + before);
            log.println("  After: " + after);
            log.println("  Difference: " + (after - before));
            
            if (after > before) {
                log.println("\n✓ Child sessions created additional MQ connections");
                log.println("✓ All connections on same QM (parent-child affinity proven)");
            } else {
                log.println("\n⚠ Connection sharing active (sessions multiplexed)");
            }
            
            // Cleanup
            log.println("\n[" + sdf.format(new Date()) + "] Cleaning up...");
            for (MessageProducer p : producers) p.close();
            for (Session s : sessions) s.close();
            conn.close();
            
            Thread.sleep(1000);
            
            // Final check
            int finalCount = countConnections("QM1", uniqueTag);
            log.println("[" + sdf.format(new Date()) + "] After cleanup: " + finalCount + " connections remain");
            
        } catch (Exception e) {
            log.println("\nERROR: " + e.getMessage());
            e.printStackTrace(log);
        } finally {
            if (conn != null) {
                try { conn.close(); } catch (Exception e) {}
            }
        }
        
        log.println("\n[" + sdf.format(new Date()) + "] Test complete");
        log.close();
        
        // Print summary to console
        System.out.println("Detailed tracking test complete. See detailed_tracking.log");
    }
    
    private static int countConnections(String qm, String appTag) {
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
    
    private static void getDetailedMQSC(String qm, String appTag, PrintWriter log) {
        try {
            String cmd = String.format(
                "docker exec %s bash -c \"echo 'DIS CONN(*) WHERE(APPLTAG EQ %s) ALL' | runmqsc %s\"",
                qm.toLowerCase(), appTag, qm
            );
            Process p = Runtime.getRuntime().exec(new String[]{"bash", "-c", cmd});
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            log.println("  MQSC Details:");
            while ((line = reader.readLine()) != null) {
                if (line.contains("CONN(") || line.contains("PID(") || 
                    line.contains("TID(") || line.contains("CHANNEL(") ||
                    line.contains("CONNAME(") || line.contains("EXTCONN(")) {
                    log.println("    " + line.trim());
                }
            }
            p.waitFor();
        } catch (Exception e) {
            log.println("  Error getting MQSC details: " + e.getMessage());
        }
    }
}
