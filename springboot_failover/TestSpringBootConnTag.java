import com.ibm.mq.jms.MQConnectionFactory;
import com.ibm.msg.client.wmq.WMQConstants;
import com.ibm.mq.jms.MQConnection;
import com.ibm.mq.jms.MQSession;
import javax.jms.*;
import java.util.*;
import java.lang.reflect.*;

/**
 * Test to find the correct CONNTAG property for Spring Boot
 * Spring Boot uses different property names than plain JMS
 */
public class TestSpringBootConnTag {
    
    public static void main(String[] args) throws Exception {
        System.out.println("========================================");
        System.out.println("   SPRING BOOT CONNTAG Property Test");
        System.out.println("========================================");
        System.out.println("Testing different property names for Spring Boot...\n");
        
        // Create connection factory
        MQConnectionFactory factory = new MQConnectionFactory();
        factory.setTransportType(WMQConstants.WMQ_CM_CLIENT);
        factory.setStringProperty(WMQConstants.WMQ_CCDTURL, "file:///workspace/ccdt/ccdt.json");
        factory.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "*");
        factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, WMQConstants.WMQ_CLIENT_RECONNECT);
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, "SPRINGBOOT-TEST");
        
        // Create connection
        Connection connection = factory.createConnection("app", "passw0rd");
        connection.start();
        
        System.out.println("✓ Connection created successfully\n");
        
        // Test different Spring Boot property names
        String[] springBootPropertyNames = {
            // Spring Boot specific
            "JMS_IBM_CONNECTION_TAG",
            "jms.ibm.connection.tag",
            "spring.jms.ibm.connection.tag",
            
            // IBM MQ Spring Boot properties
            "ibm.mq.connTag",
            "ibm.mq.connectionTag",
            "mq.connection.tag",
            
            // Standard JMS
            "JMSConnectionTag",
            "ConnectionTag",
            "CONN_TAG",
            "CONNTAG",
            
            // IBM specific
            "JMS_IBM_CONN_TAG",
            "XMSC_WMQ_CONNECTION_TAG",
            "XMSC_WMQ_RESOLVED_CONNECTION_TAG",
            "WMQ_CONNECTION_TAG",
            "WMQ_RESOLVED_CONNECTION_TAG"
        };
        
        System.out.println("Testing Connection properties:");
        System.out.println("-".repeat(80));
        
        if (connection instanceof MQConnection) {
            MQConnection mqConn = (MQConnection) connection;
            
            // Try all property names
            for (String propName : springBootPropertyNames) {
                try {
                    String value = mqConn.getStringProperty(propName);
                    if (value != null && !value.isEmpty()) {
                        System.out.println("✓ FOUND: " + propName + " = " + value);
                    }
                } catch (Exception e) {
                    // Property doesn't exist
                }
            }
            
            // Also try reflection to see internal fields
            System.out.println("\nChecking internal fields via reflection:");
            System.out.println("-".repeat(80));
            
            try {
                Field[] fields = mqConn.getClass().getDeclaredFields();
                for (Field field : fields) {
                    if (field.getName().toLowerCase().contains("tag") || 
                        field.getName().toLowerCase().contains("conn")) {
                        field.setAccessible(true);
                        Object value = field.get(mqConn);
                        if (value != null && !value.toString().isEmpty()) {
                            System.out.println("Field: " + field.getName() + " = " + value);
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("Could not access internal fields: " + e.getMessage());
            }
            
            // Try to get via getPropertyNames
            System.out.println("\nAvailable property names:");
            System.out.println("-".repeat(80));
            try {
                Enumeration<?> propNames = mqConn.getPropertyNames();
                while (propNames.hasMoreElements()) {
                    String name = propNames.nextElement().toString();
                    if (name.toLowerCase().contains("conn") || name.toLowerCase().contains("tag")) {
                        System.out.println("Property: " + name);
                    }
                }
            } catch (Exception e) {
                System.out.println("Could not enumerate properties: " + e.getMessage());
            }
        }
        
        // Test with Session
        System.out.println("\n\nTesting Session properties:");
        System.out.println("-".repeat(80));
        
        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        
        if (session instanceof MQSession) {
            MQSession mqSession = (MQSession) session;
            
            for (String propName : springBootPropertyNames) {
                try {
                    String value = mqSession.getStringProperty(propName);
                    if (value != null && !value.isEmpty()) {
                        System.out.println("✓ FOUND: " + propName + " = " + value);
                    }
                } catch (Exception e) {
                    // Property doesn't exist
                }
            }
        }
        
        // Cleanup
        session.close();
        connection.close();
        
        System.out.println("\n========================================");
        System.out.println("Test completed - check which property names worked");
        System.out.println("========================================");
    }
}