import com.ibm.mq.jms.*;
import com.ibm.msg.client.wmq.WMQConstants;
import javax.jms.*;
import java.util.*;

public class TempProof {
    public static void main(String[] args) throws Exception {
        String appTag = "PCF1568";
        
        System.out.println("Creating connections with APPTAG: " + appTag);
        
        MQConnectionFactory factory = new MQConnectionFactory();
        factory.setHostName("10.10.10.10");
        factory.setPort(1414);
        factory.setChannel("APP.SVRCONN");
        factory.setQueueManager("QM1");
        factory.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, appTag);
        
        Connection connection = factory.createConnection("app", "passw0rd");
        connection.start();
        System.out.println("✓ Parent connection created");
        
        List<Session> sessions = new ArrayList<>();
        List<MessageProducer> producers = new ArrayList<>();
        
        for (int i = 1; i <= 5; i++) {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            sessions.add(session);
            javax.jms.Queue queue = session.createQueue("TEST.QUEUE");
            MessageProducer producer = session.createProducer(queue);
            producers.add(producer);
            TextMessage msg = session.createTextMessage("Test " + i);
            producer.send(msg);
            System.out.println("✓ Session " + i + " created and activated");
        }
        
        System.out.println("\nConnections established. Keeping alive for 60 seconds...");
        System.out.println("APPTAG for monitoring: " + appTag);
        
        for (int i = 1; i <= 6; i++) {
            Thread.sleep(10000);
            System.out.println("  " + (i * 10) + " seconds...");
        }
        
        for (MessageProducer p : producers) p.close();
        for (Session s : sessions) s.close();
        connection.close();
        
        System.out.println("Test completed!");
    }
}
