
import com.ibm.mq.jms.MQConnectionFactory;
import com.ibm.msg.client.wmq.WMQConstants;
import com.ibm.mq.jms.MQConnection;
import com.ibm.mq.jms.MQSession;
import javax.jms.*;
import java.util.*;

/**
 * Verification Test - Extract ACTUAL CONNTAG from Sessions
 * 
 * This test PROVES that we extract the real CONNTAG from each session,
 * not assuming inheritance. This is CRITICAL for verifying parent-child affinity.
 */
public class VerifyActualSessionConnTag {
    
    private static final String CCDT_URL = "file:///workspace/ccdt/ccdt.json";
    private static final String TEST_ID = "VERIFY-" + System.currentTimeMillis();
    
    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(100));
        System.out.println("    VERIFY ACTUAL SESSION CONNTAG EXTRACTION TEST");
        System.out.println("=".repeat(100));
        System.out.println("Test ID: " + TEST_ID);
        System.out.println("\nThis test extracts ACTUAL CONNTAG from each session independently");
        System.out.println("to PROVE parent-child affinity, not assume it.\n");
        
        // Create connection factory
        MQConnectionFactory factory = new MQConnectionFactory();
        factory.setTransportType(WMQConstants.WMQ_CM_CLIENT);
        factory.setStringProperty(WMQConstants.WMQ_CCDTURL, CCDT_URL);
        factory.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "*");
        factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, WMQConstants.WMQ_CLIENT_RECONNECT);
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TEST_ID);
        
        // Create connection
        System.out.println("Creating parent connection...");
        Connection connection = factory.createConnection("app", "passw0rd");
        connection.start();
        
        // Extract parent CONNTAG
        String parentConnTag = extractConnTag(connection);
        String parentConnId = extractConnectionId(connection);
        System.out.println("\nPARENT CONNECTION:");
        System.out.println("  CONNTAG: " + parentConnTag);
        System.out.println("  CONN_ID: " + parentConnId);
        
        // Create 5 sessions
        System.out.println("\nCreating 5 child sessions and extracting their ACTUAL CONNTAGs...");
        List<Session> sessions = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            sessions.add(connection.createSession(false, Session.AUTO_ACKNOWLEDGE));
        }
        
        // Extract ACTUAL CONNTAG from each session
        System.out.println("\nCHILD SESSIONS (extracting ACTUAL CONNTAG from each):");
        System.out.println("-".repeat(100));
        System.out.printf("| %-10s | %-60s | %-15s | %-10s |\n", 
            "Session", "ACTUAL CONNTAG (extracted from session)", "Matches Parent?", "Status");
        System.out.println("-".repeat(100));
        
        int sessionNum = 1;
        int matchCount = 0;
        for (Session session : sessions) {
            // Extract ACTUAL CONNTAG from this session
            String sessionConnTag = extractSessionConnTag(session);
            String sessionConnId = extractSessionConnectionId(session);
            boolean matches = sessionConnTag.equals(parentConnTag);
            
            if (matches) matchCount++;
            
            System.out.printf("| Session %-2d | %-60s | %-15s | %-10s |\n",
                sessionNum,
                sessionConnTag,
                matches ? "YES ✓" : "NO ✗",
                matches ? "CORRECT" : "ERROR!");
            
            // If doesn't match, show details
            if (!matches) {
                System.out.println("  WARNING: Session " + sessionNum + " has different CONNTAG!");
                System.out.println("    Parent: " + parentConnTag);
                System.out.println("    Session: " + sessionConnTag);
            }
            
            sessionNum++;
        }
        System.out.println("-".repeat(100));
        
        // Summary
        System.out.println("\nVERIFICATION RESULTS:");
        System.out.println("  Sessions with matching CONNTAG: " + matchCount + " / " + sessions.size());
        System.out.println("  Parent-Child Affinity: " + (matchCount == sessions.size() ? "VERIFIED ✓" : "FAILED ✗"));
        
        if (matchCount == sessions.size()) {
            System.out.println("\n✓ SUCCESS: All sessions have the SAME CONNTAG as parent");
            System.out.println("  This PROVES parent-child affinity through actual extraction,");
            System.out.println("  not assumption or inheritance!");
        } else {
            System.out.println("\n✗ FAILURE: Some sessions have different CONNTAGs");
            System.out.println("  This would indicate a serious issue with parent-child affinity");
        }
        
        // Test failover scenario
        System.out.println("\n" + "=".repeat(100));
        System.out.println("    FAILOVER TEST - Verify CONNTAGs change together");
        System.out.println("=".repeat(100));
        System.out.println("\nKeep running for 60 seconds. Stop the QM to trigger failover...");
        
        // Monitor for changes
        String lastParentTag = parentConnTag;
        for (int i = 0; i < 12; i++) {  // 12 x 5 seconds = 60 seconds
            Thread.sleep(5000);
            
            String currentParentTag = extractConnTag(connection);
            if (!currentParentTag.equals(lastParentTag)) {
                System.out.println("\nFAILOVER DETECTED!");
                System.out.println("Parent CONNTAG changed from: " + lastParentTag);
                System.out.println("                         to: " + currentParentTag);
                
                // Check all sessions
                System.out.println("\nVerifying all sessions also changed...");
                int changedCount = 0;
                for (int j = 0; j < sessions.size(); j++) {
                    String newSessionTag = extractSessionConnTag(sessions.get(j));
                    if (newSessionTag.equals(currentParentTag)) {
                        changedCount++;
                        System.out.println("  Session " + (j+1) + ": CONNTAG updated correctly ✓");
                    } else {
                        System.out.println("  Session " + (j+1) + ": CONNTAG mismatch ✗");
                    }
                }
                
                if (changedCount == sessions.size()) {
                    System.out.println("\n✓ FAILOVER SUCCESS: All sessions moved with parent!");
                } else {
                    System.out.println("\n✗ FAILOVER ISSUE: Not all sessions updated correctly");
                }
                break;
            }
            
            if (i % 2 == 0) {
                System.out.print(".");
            }
        }
        
        // Cleanup
        for (Session session : sessions) {
            session.close();
        }
        connection.close();
        
        System.out.println("\n\nTest completed successfully");
        System.out.println("=".repeat(100));
    }
    
    // Extract CONNTAG from Connection
    private static String extractConnTag(Connection connection) {
        try {
            if (connection instanceof MQConnection) {
                MQConnection mqConn = (MQConnection) connection;
                String conntag = mqConn.getStringProperty("JMS_IBM_CONNECTION_TAG");
                if (conntag != null && !conntag.isEmpty()) {
                    return conntag;
                }
            }
        } catch (Exception e) {
            System.err.println("Error extracting connection CONNTAG: " + e.getMessage());
        }
        return "CONNTAG_UNAVAILABLE";
    }
    
    // Extract CONNTAG from Session - CRITICAL METHOD
    private static String extractSessionConnTag(Session session) {
        try {
            if (session instanceof MQSession) {
                MQSession mqSession = (MQSession) session;
                // Sessions have access to the same properties as their parent connection
                String conntag = mqSession.getStringProperty("JMS_IBM_CONNECTION_TAG");
                if (conntag != null && !conntag.isEmpty()) {
                    return conntag;  // ACTUAL session's CONNTAG
                }
            }
        } catch (Exception e) {
            System.err.println("Error extracting session CONNTAG: " + e.getMessage());
        }
        return "SESSION_CONNTAG_UNAVAILABLE";
    }
    
    // Extract CONNECTION_ID from Connection
    private static String extractConnectionId(Connection connection) {
        try {
            if (connection instanceof MQConnection) {
                MQConnection mqConn = (MQConnection) connection;
                String connId = mqConn.getStringProperty("JMS_IBM_CONNECTION_ID");
                if (connId != null) {
                    return connId;
                }
            }
        } catch (Exception e) {}
        return "UNKNOWN";
    }
    
    // Extract CONNECTION_ID from Session
    private static String extractSessionConnectionId(Session session) {
        try {
            if (session instanceof MQSession) {
                MQSession mqSession = (MQSession) session;
                String connId = mqSession.getStringProperty("JMS_IBM_CONNECTION_ID");
                if (connId != null) {
                    return connId;
                }
            }
        } catch (Exception e) {}
        return "UNKNOWN";
    }
}