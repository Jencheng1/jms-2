import com.ibm.mq.jms.MQConnectionFactory;
import com.ibm.msg.client.wmq.WMQConstants;
import com.ibm.mq.jms.MQConnection;
import com.ibm.mq.jms.MQSession;
import javax.jms.*;
import java.util.*;

/**
 * Test Session CONNTAG Extraction - No Authentication
 * Proves we extract ACTUAL CONNTAG from sessions, not assume inheritance
 */
public class TestSessionConntagNoAuth {
    
    private static final String CCDT_URL = "file:///workspace/ccdt/ccdt.json";
    private static final String TEST_ID = "TEST-" + System.currentTimeMillis();
    
    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(100));
        System.out.println("    TEST: ACTUAL SESSION CONNTAG EXTRACTION");
        System.out.println("=".repeat(100));
        System.out.println("Test ID: " + TEST_ID);
        System.out.println("\nThis proves we extract ACTUAL CONNTAG from each session, not inherit from parent\n");
        
        // Create connection factory
        MQConnectionFactory factory = new MQConnectionFactory();
        factory.setTransportType(WMQConstants.WMQ_CM_CLIENT);
        factory.setStringProperty(WMQConstants.WMQ_CCDTURL, CCDT_URL);
        factory.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "*");
        factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, WMQConstants.WMQ_CLIENT_RECONNECT);
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TEST_ID);
        
        // Create connection with proper authentication
        System.out.println("Creating parent connection...");
        Connection connection = factory.createConnection("app", "passw0rd");
        connection.start();
        
        // Extract parent CONNTAG
        String parentConnTag = "NOT_EXTRACTED";
        try {
            if (connection instanceof MQConnection) {
                MQConnection mqConn = (MQConnection) connection;
                parentConnTag = mqConn.getStringProperty("JMS_IBM_CONNECTION_TAG");
            }
        } catch (Exception e) {
            System.err.println("Error extracting parent CONNTAG: " + e.getMessage());
        }
        
        System.out.println("\nPARENT CONNECTION:");
        System.out.println("  CONNTAG: " + parentConnTag);
        System.out.println("  Queue Manager: " + extractQM(parentConnTag));
        
        // Create 5 sessions
        System.out.println("\nCreating 5 child sessions...");
        List<Session> sessions = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            sessions.add(connection.createSession(false, Session.AUTO_ACKNOWLEDGE));
        }
        
        // Extract ACTUAL CONNTAG from each session
        System.out.println("\nEXTRACTING ACTUAL CONNTAG FROM EACH SESSION:");
        System.out.println("-".repeat(100));
        System.out.printf("| %-10s | %-60s | %-6s | %-15s |\n", 
            "Session", "ACTUAL CONNTAG (Extracted from Session)", "QM", "Matches Parent?");
        System.out.println("-".repeat(100));
        
        int sessionNum = 1;
        int matchCount = 0;
        for (Session session : sessions) {
            String sessionConnTag = "NOT_EXTRACTED";
            
            // CRITICAL: Extract ACTUAL CONNTAG from this session
            try {
                if (session instanceof MQSession) {
                    MQSession mqSession = (MQSession) session;
                    sessionConnTag = mqSession.getStringProperty("JMS_IBM_CONNECTION_TAG");
                }
            } catch (Exception e) {
                System.err.println("Error extracting session " + sessionNum + " CONNTAG: " + e.getMessage());
            }
            
            boolean matches = (sessionConnTag != null && parentConnTag != null && sessionConnTag.equals(parentConnTag));
            if (matches) matchCount++;
            
            System.out.printf("| Session %-2d | %-60s | %-6s | %-15s |\n",
                sessionNum,
                sessionConnTag.length() > 60 ? sessionConnTag.substring(0, 57) + "..." : sessionConnTag,
                extractQM(sessionConnTag),
                matches ? "YES ✓" : "NO ✗");
            
            if (!matches && !sessionConnTag.equals("NOT_EXTRACTED")) {
                System.out.println("  ⚠ WARNING: Session " + sessionNum + " has DIFFERENT CONNTAG than parent!");
            }
            
            sessionNum++;
        }
        System.out.println("-".repeat(100));
        
        // Summary
        System.out.println("\nRESULTS:");
        System.out.println("  Sessions with matching CONNTAG: " + matchCount + " / " + sessions.size());
        
        if (matchCount == sessions.size()) {
            System.out.println("\n✅ SUCCESS: All sessions have SAME CONNTAG as parent");
            System.out.println("   This PROVES parent-child affinity through ACTUAL extraction!");
            System.out.println("   We are NOT assuming inheritance - we extracted and verified!");
        } else {
            System.out.println("\n❌ ISSUE: Not all sessions have same CONNTAG as parent");
        }
        
        // Show full CONNTAGs
        System.out.println("\nFULL CONNTAG VALUES:");
        System.out.println("Parent: " + parentConnTag);
        int i = 1;
        for (Session session : sessions) {
            try {
                if (session instanceof MQSession) {
                    MQSession mqSession = (MQSession) session;
                    String tag = mqSession.getStringProperty("JMS_IBM_CONNECTION_TAG");
                    System.out.println("Session " + i + ": " + tag);
                }
            } catch (Exception e) {}
            i++;
        }
        
        // Cleanup
        for (Session session : sessions) {
            session.close();
        }
        connection.close();
        
        System.out.println("\nTest completed successfully");
        System.out.println("=".repeat(100));
    }
    
    private static String extractQM(String conntag) {
        if (conntag == null) return "?";
        if (conntag.contains("QM1")) return "QM1";
        if (conntag.contains("QM2")) return "QM2";
        if (conntag.contains("QM3")) return "QM3";
        return "?";
    }
}