import com.ibm.msg.client.jms.JmsConnectionFactory;
import com.ibm.msg.client.jms.JmsFactoryFactory;
import com.ibm.msg.client.wmq.WMQConstants;
import javax.jms.*;

public class SimpleConnectionTest {
    public static void main(String[] args) {
        try {
            System.out.println("Simple Connection Test");
            System.out.println("======================");
            
            // Create factory
            JmsFactoryFactory ff = JmsFactoryFactory.getInstance(WMQConstants.WMQ_PROVIDER);
            JmsConnectionFactory factory = ff.createConnectionFactory();
            
            // Set connection properties
            factory.setStringProperty(WMQConstants.WMQ_HOST_NAME, "10.10.10.10");
            factory.setIntProperty(WMQConstants.WMQ_PORT, 1414);
            factory.setStringProperty(WMQConstants.WMQ_CHANNEL, "APP.SVRCONN");
            factory.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "QM1");
            factory.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
            factory.setStringProperty(WMQConstants.USERID, "app");
            factory.setStringProperty(WMQConstants.PASSWORD, "passw0rd");
            factory.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
            
            String trackingKey = "SIMPLE-TEST-" + System.currentTimeMillis();
            factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, trackingKey);
            
            System.out.println("Tracking Key: " + trackingKey);
            System.out.println("\nConnecting to QM1...");
            
            // Create connection
            Connection connection = factory.createConnection();
            System.out.println("✓ Connection created");
            
            connection.start();
            System.out.println("✓ Connection started");
            
            // Create sessions
            System.out.println("\nCreating 5 sessions...");
            for (int i = 1; i <= 5; i++) {
                Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                System.out.println("  ✓ Session #" + i + " created");
                
                // Send test message
                javax.jms.Queue queue = session.createQueue("UNIFORM.QUEUE");
                MessageProducer producer = session.createProducer(queue);
                TextMessage msg = session.createTextMessage("Test message " + i);
                producer.send(msg);
                producer.close();
                System.out.println("    ✓ Message sent");
            }
            
            System.out.println("\n✅ Test successful! Check connections with:");
            System.out.println("docker exec qm1 bash -c \"echo 'DIS CONN(*) WHERE(APPLTAG EQ " + trackingKey + ")' | runmqsc QM1\"");
            
            // Keep alive for verification
            System.out.println("\nKeeping connection alive for 30 seconds...");
            Thread.sleep(30000);
            
            connection.close();
            System.out.println("✓ Connection closed");
            
        } catch (Exception e) {
            System.err.println("❌ Test failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
