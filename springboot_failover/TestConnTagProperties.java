import com.ibm.mq.jms.MQConnectionFactory;
import com.ibm.msg.client.wmq.WMQConstants;
import com.ibm.mq.jms.MQConnection;
import com.ibm.mq.jms.MQSession;
import javax.jms.*;
import java.util.*;
import com.ibm.msg.client.jms.JmsConstants;
import com.ibm.msg.client.wmq.common.CommonConstants;

/**
 * Test to find correct CONNTAG property name
 */
public class TestConnTagProperties {
    
    public static void main(String[] args) throws Exception {
        System.out.println("Testing CONNTAG property extraction...\n");
        
        // Create connection factory
        MQConnectionFactory factory = new MQConnectionFactory();
        factory.setTransportType(WMQConstants.WMQ_CM_CLIENT);
        factory.setStringProperty(WMQConstants.WMQ_CCDTURL, "file:///workspace/ccdt/ccdt.json");
        factory.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "*");
        factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, WMQConstants.WMQ_CLIENT_RECONNECT);
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, "PROP-TEST");
        
        // Create connection
        Connection connection = factory.createConnection("app", "passw0rd");
        connection.start();
        
        System.out.println("Connection created successfully\n");
        
        // Try different property names for CONNTAG
        String[] propertyNames = {
            "JMS_IBM_CONNECTION_TAG",
            "JMS_IBM_CONNTAG",
            "XMSC_WMQ_RESOLVED_CONNECTION_TAG",
            "XMSC.WMQ_RESOLVED_CONNECTION_TAG",
            "XMSC_WMQ_CONNECTION_TAG",
            "XMSC_WMQ_CONNTAG",
            "CONNECTION_TAG",
            "CONNTAG",
            // JmsConstants.JMS_IBM_CONNECTION_TAG,  // Not available
            // CommonConstants.WMQ_CONNECTION_TAG     // Not available
        };
        
        System.out.println("Testing Connection properties:");
        System.out.println("-" . repeat(80));
        
        if (connection instanceof MQConnection) {
            MQConnection mqConn = (MQConnection) connection;
            
            for (String propName : propertyNames) {
                try {
                    String value = mqConn.getStringProperty(propName);
                    if (value != null && !value.isEmpty()) {
                        System.out.println("✓ " + propName + " = " + value);
                    }
                } catch (Exception e) {
                    // Property doesn't exist or error
                }
            }
            
            // Also try CONNECTION_ID
            try {
                String connId = mqConn.getStringProperty("JMS_IBM_CONNECTION_ID");
                if (connId != null) {
                    System.out.println("✓ JMS_IBM_CONNECTION_ID = " + connId);
                }
            } catch (Exception e) {}
        }
        
        // Create a session and test
        System.out.println("\nTesting Session properties:");
        System.out.println("-" . repeat(80));
        
        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        
        if (session instanceof MQSession) {
            MQSession mqSession = (MQSession) session;
            
            for (String propName : propertyNames) {
                try {
                    String value = mqSession.getStringProperty(propName);
                    if (value != null && !value.isEmpty()) {
                        System.out.println("✓ " + propName + " = " + value);
                    }
                } catch (Exception e) {
                    // Property doesn't exist or error
                }
            }
            
            // Also try CONNECTION_ID
            try {
                String connId = mqSession.getStringProperty("JMS_IBM_CONNECTION_ID");
                if (connId != null) {
                    System.out.println("✓ JMS_IBM_CONNECTION_ID = " + connId);
                }
            } catch (Exception e) {}
        }
        
        // Cleanup
        session.close();
        connection.close();
        
        System.out.println("\nTest completed");
    }
}