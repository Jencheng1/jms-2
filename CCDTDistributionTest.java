import javax.jms.*;
import com.ibm.msg.client.jms.*;
import com.ibm.msg.client.wmq.WMQConstants;
import java.util.*;
import java.io.*;

/**
 * Test CCDT-based distribution across uniform cluster
 */
public class CCDTDistributionTest {
    
    public static void main(String[] args) throws Exception {
        System.out.println("================================================");
        System.out.println(" CCDT DISTRIBUTION TEST");
        System.out.println("================================================\n");
        
        // First, verify CCDT file exists
        File ccdtFile = new File("mq/ccdt/ccdt.json");
        System.out.println("CCDT file check:");
        System.out.println("  Path: " + ccdtFile.getAbsolutePath());
        System.out.println("  Exists: " + ccdtFile.exists());
        System.out.println("  Readable: " + ccdtFile.canRead());
        System.out.println();
        
        // Test with absolute path
        String ccdtUrl = "file://" + ccdtFile.getAbsolutePath();
        System.out.println("Using CCDT URL: " + ccdtUrl);
        System.out.println();
        
        // Create multiple connections
        System.out.println("Creating 9 connections using CCDT...\n");
        
        List<Connection> connections = new ArrayList<>();
        Map<String, Integer> distribution = new HashMap<>();
        distribution.put("QM1", 0);
        distribution.put("QM2", 0);
        distribution.put("QM3", 0);
        
        for (int i = 1; i <= 9; i++) {
            String appTag = "CCDT" + i;
            
            try {
                JmsFactoryFactory ff = JmsFactoryFactory.getInstance(WMQConstants.WMQ_PROVIDER);
                JmsConnectionFactory cf = ff.createConnectionFactory();
                
                // Use CCDT for distribution
                cf.setStringProperty(WMQConstants.WMQ_CCDTURL, ccdtUrl);
                cf.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "*");
                cf.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
                cf.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, appTag);
                
                Connection conn = cf.createConnection("app", "passw0rd");
                conn.start();
                connections.add(conn);
                
                // Get resolved QM
                String resolvedQm = "Unknown";
                if (conn instanceof JmsPropertyContext) {
                    JmsPropertyContext cpc = (JmsPropertyContext) conn;
                    resolvedQm = cpc.getStringProperty(WMQConstants.WMQ_RESOLVED_QUEUE_MANAGER);
                }
                
                System.out.println("Connection " + i + " -> " + resolvedQm + " (tag=" + appTag + ")");
                
                // Update distribution count
                distribution.put(resolvedQm, distribution.getOrDefault(resolvedQm, 0) + 1);
                
                // Create a session to ensure real connection
                Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
                
            } catch (Exception e) {
                System.out.println("Connection " + i + " failed: " + e.getMessage());
                
                // Try to understand the error
                if (e.getCause() != null) {
                    System.out.println("  Cause: " + e.getCause().getMessage());
                }
            }
        }
        
        // Show distribution
        System.out.println("\n--- Distribution Results ---");
        for (Map.Entry<String, Integer> entry : distribution.entrySet()) {
            if (entry.getValue() > 0) {
                System.out.println(entry.getKey() + ": " + entry.getValue() + " connections");
            }
        }
        
        // Check if distribution is even
        int min = Collections.min(distribution.values());
        int max = Collections.max(distribution.values());
        
        if (max - min <= 1) {
            System.out.println("\n✓ Even distribution achieved!");
        } else {
            System.out.println("\n⚠ Uneven distribution (difference: " + (max - min) + ")");
        }
        
        // Verify connections via MQSC
        System.out.println("\n--- MQSC Verification ---");
        for (int i = 1; i <= 9; i++) {
            String appTag = "CCDT" + i;
            for (String qm : new String[]{"QM1", "QM2", "QM3"}) {
                int count = countConnections(qm, appTag);
                if (count > 0) {
                    System.out.println(appTag + " found on " + qm);
                }
            }
        }
        
        // Cleanup
        System.out.println("\nCleaning up connections...");
        for (Connection conn : connections) {
            try { conn.close(); } catch (Exception e) {}
        }
        
        System.out.println("\n================================================");
        System.out.println(" TEST COMPLETE");
        System.out.println("================================================");
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
            return 0;
        }
    }
}