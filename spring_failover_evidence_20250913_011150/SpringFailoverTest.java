import com.ibm.mq.jms.MQConnectionFactory;
import com.ibm.mq.jms.MQConnection;
import com.ibm.mq.jms.MQSession;
import com.ibm.msg.client.jms.JmsPropertyContext;
import com.ibm.msg.client.wmq.WMQConstants;
import javax.jms.*;
import java.util.*;
import java.text.SimpleDateFormat;

public class SpringFailoverTest {
    private static final String TEST_ID = System.getenv("TEST_ID");
    private static final String CCDT_URL = "file:///workspace/ccdt/ccdt.json";
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== Spring Boot Failover Test ===");
        System.out.println("Test ID: " + TEST_ID);
        System.out.println("Time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date()));
        
        // Enable JMS debug trace
        System.setProperty("com.ibm.msg.client.commonservices.trace.status", "ON");
        System.setProperty("com.ibm.msg.client.commonservices.trace.outputName", "/evidence/jms_debug_trace.log");
        
        // Create connection factory
        MQConnectionFactory factory = new MQConnectionFactory();
        factory.setStringProperty(WMQConstants.WMQ_CCDTURL, CCDT_URL);
        factory.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "*");
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TEST_ID + "-C1");
        factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, WMQConstants.WMQ_CLIENT_RECONNECT);
        factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_TIMEOUT, 1800);
        factory.setTransportType(WMQConstants.WMQ_CM_CLIENT);
        
        // Create connection 1 with 5 sessions
        Connection conn1 = factory.createConnection();
        conn1.setExceptionListener(ex -> {
            System.out.println("[C1] Exception: " + ex.getMessage());
        });
        conn1.start();
        
        List<Session> sessions1 = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            Session session = conn1.createSession(false, Session.AUTO_ACKNOWLEDGE);
            sessions1.add(session);
            System.out.println("Created C1 Session " + i);
        }
        
        // Create connection 2 with 3 sessions
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TEST_ID + "-C2");
        Connection conn2 = factory.createConnection();
        conn2.setExceptionListener(ex -> {
            System.out.println("[C2] Exception: " + ex.getMessage());
        });
        conn2.start();
        
        List<Session> sessions2 = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            Session session = conn2.createSession(false, Session.AUTO_ACKNOWLEDGE);
            sessions2.add(session);
            System.out.println("Created C2 Session " + i);
        }
        
        // Extract and display CONNTAG for both connections
        System.out.println("\n=== Initial Connection State ===");
        printConnectionDetails("C1", conn1, sessions1);
        printConnectionDetails("C2", conn2, sessions2);
        
        // Keep alive for failover test
        System.out.println("\n=== Waiting for failover test (3 minutes) ===");
        for (int i = 0; i < 18; i++) {
            Thread.sleep(10000); // 10 seconds
            System.out.println("Time elapsed: " + (i+1)*10 + " seconds");
            
            // Check connection state every 30 seconds
            if (i % 3 == 2) {
                System.out.println("\n=== Connection State Check ===");
                printConnectionDetails("C1", conn1, sessions1);
                printConnectionDetails("C2", conn2, sessions2);
            }
        }
        
        // Final state
        System.out.println("\n=== Final Connection State ===");
        printConnectionDetails("C1", conn1, sessions1);
        printConnectionDetails("C2", conn2, sessions2);
        
        // Cleanup
        conn1.close();
        conn2.close();
        
        System.out.println("\n=== Test Complete ===");
    }
    
    private static void printConnectionDetails(String label, Connection conn, List<Session> sessions) {
        try {
            if (conn instanceof MQConnection) {
                MQConnection mqConn = (MQConnection) conn;
                JmsPropertyContext context = mqConn.getPropertyContext();
                
                // Extract CONNTAG using correct constant
                String connTag = context.getStringProperty(WMQConstants.JMS_IBM_CONNECTION_TAG);
                String connId = context.getStringProperty(WMQConstants.JMS_IBM_CONNECTION_ID);
                String qm = context.getStringProperty(WMQConstants.JMS_IBM_RESOLVED_QUEUE_MANAGER);
                
                System.out.println(label + " Parent Connection:");
                System.out.println("  CONNTAG: " + connTag);
                System.out.println("  CONNECTION_ID: " + connId);
                System.out.println("  Queue Manager: " + qm);
                
                // Check first session
                if (!sessions.isEmpty() && sessions.get(0) instanceof MQSession) {
                    MQSession mqSession = (MQSession) sessions.get(0);
                    JmsPropertyContext sessionContext = mqSession.getPropertyContext();
                    String sessionConnTag = sessionContext.getStringProperty(WMQConstants.JMS_IBM_CONNECTION_TAG);
                    System.out.println("  First Session CONNTAG: " + sessionConnTag);
                    System.out.println("  Session inherits parent: " + connTag.equals(sessionConnTag));
                }
            }
        } catch (Exception e) {
            System.err.println("Error getting connection details: " + e.getMessage());
        }
    }
}
