import javax.jms.*;
import com.ibm.msg.client.jms.*;
import com.ibm.msg.client.wmq.WMQConstants;

public class ManualTest {
    public static void main(String[] args) throws Exception {
        String tag = "MANUAL-TEST-1757417665";
        JmsFactoryFactory ff = JmsFactoryFactory.getInstance(WMQConstants.WMQ_PROVIDER);
        JmsConnectionFactory cf = ff.createConnectionFactory();
        
        cf.setStringProperty(WMQConstants.WMQ_HOST_NAME, "10.10.10.10");
        cf.setIntProperty(WMQConstants.WMQ_PORT, 1414);
        cf.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "QM1");
        cf.setStringProperty(WMQConstants.WMQ_CHANNEL, "APP.SVRCONN");
        cf.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
        cf.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, tag);
        
        // Key: disable sharing to see all connections
        cf.setIntProperty(WMQConstants.WMQ_SHARE_CONV_ALLOWED, 0);
        
        Connection conn = cf.createConnection("app", "passw0rd");
        conn.start();
        
        System.out.println("Parent connection created with tag: " + tag);
        
        for (int i = 1; i <= 5; i++) {
            Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
            System.out.println("Created session " + i);
        }
        
        Thread.sleep(15000);
        conn.close();
    }
}
