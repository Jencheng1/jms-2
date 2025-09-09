import javax.jms.*;
import com.ibm.msg.client.jms.JmsConnectionFactory;
import com.ibm.msg.client.jms.JmsFactoryFactory;
import com.ibm.msg.client.jms.JmsPropertyContext;
import com.ibm.msg.client.wmq.WMQConstants;

public class SimpleUniformTest {
    public static void main(String[] args) throws Exception {
        String tag = "UNIFORM-" + System.currentTimeMillis();
        
        // Create connection factory
        JmsFactoryFactory ff = JmsFactoryFactory.getInstance(WMQConstants.WMQ_PROVIDER);
        JmsConnectionFactory cf = ff.createConnectionFactory();
        
        // Direct connection to QM1
        cf.setStringProperty(WMQConstants.WMQ_HOST_NAME, "10.10.10.10");
        cf.setIntProperty(WMQConstants.WMQ_PORT, 1414);
        cf.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "QM1");
        cf.setStringProperty(WMQConstants.WMQ_CHANNEL, "APP.SVRCONN");
        cf.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
        cf.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, tag);
        
        // Create connection
        Connection conn = cf.createConnection("app", "passw0rd");
        conn.start();
        
        // Get resolved QM
        JmsPropertyContext cpc = (JmsPropertyContext) conn;
        String resolvedQm = cpc.getStringProperty(WMQConstants.WMQ_RESOLVED_QUEUE_MANAGER);
        
        System.out.println("=== UNIFORM CLUSTER TEST ===");
        System.out.println("APPLTAG: " + tag);
        System.out.println("Resolved QM: " + resolvedQm);
        System.out.println("\nCreating 5 sessions...");
        
        // Create 5 sessions
        for (int i = 1; i <= 5; i++) {
            Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
            System.out.println("  Created session " + i);
        }
        
        System.out.println("\nWaiting for connections to stabilize...");
        Thread.sleep(2000);
        
        System.out.println("\n=== CHECKING MQ CONNECTIONS ===");
        System.out.println("Expected: 6 connections (1 parent + 5 sessions)");
        System.out.println("All should be on " + resolvedQm);
        
        System.out.println("\nHolding connection open for verification...");
        Thread.sleep(10000);
        
        conn.close();
        System.out.println("\nTest complete");
    }
}
