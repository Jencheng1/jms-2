import com.ibm.mq.jms.*;
import com.ibm.msg.client.wmq.WMQConstants;
import javax.jms.*;
import java.util.*;

public class SimpleProof {
    
    public static void main(String[] args) throws Exception {
        // Use shorter APPTAG
        String appTag = "TEST" + (System.currentTimeMillis() % 100000);
        
        System.out.println("========================================================================");
        System.out.println("PARENT-CHILD PROOF: 1 CONNECTION → 5 SESSIONS = 6 MQ CONNECTIONS");
        System.out.println("========================================================================");
        System.out.println("APPTAG: " + appTag);
        System.out.println("");
        
        // Enable trace
        System.setProperty("com.ibm.msg.client.commonservices.trace.enable", "true");
        
        // Create connection factory
        MQConnectionFactory factory = new MQConnectionFactory();
        factory.setHostName("10.10.10.10");
        factory.setPort(1414);
        factory.setChannel("APP.SVRCONN");
        factory.setQueueManager("QM1");
        factory.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, appTag);
        
        System.out.println("STEP 1: Creating 1 JMS Connection...");
        Connection connection = factory.createConnection("app", "passw0rd");
        connection.start();
        System.out.println("✓ Parent connection created");
        System.out.println("");
        
        System.out.println("STEP 2: Creating 5 Sessions from parent connection...");
        List<Session> sessions = new ArrayList<>();
        List<MessageProducer> producers = new ArrayList<>();
        
        for (int i = 1; i <= 5; i++) {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            sessions.add(session);
            
            // Create producer to ensure session is active
            javax.jms.Queue queue = session.createQueue("TEST.QUEUE");
            MessageProducer producer = session.createProducer(queue);
            producers.add(producer);
            
            System.out.println("  ✓ Child session " + i + " created");
        }
        
        System.out.println("");
        System.out.println("STEP 3: Sending messages to activate all sessions...");
        for (int i = 0; i < producers.size(); i++) {
            Session session = sessions.get(i);
            MessageProducer producer = producers.get(i);
            
            TextMessage msg = session.createTextMessage("Test from session " + (i+1));
            msg.setStringProperty("APPTAG", appTag);
            producer.send(msg);
            System.out.println("  ✓ Message sent from session " + (i+1));
        }
        
        System.out.println("");
        System.out.println("========================================================================");
        System.out.println("PROOF POINT: Check MQ connections now!");
        System.out.println("========================================================================");
        System.out.println("");
        System.out.println("RUN THIS COMMAND:");
        System.out.println("docker exec qm1 bash -c \"echo 'DIS CONN(*) WHERE(APPLTAG EQ " + appTag + ")' | runmqsc QM1\"");
        System.out.println("");
        System.out.println("EXPECTED: 6 connections (1 parent + 5 children)");
        System.out.println("");
        System.out.println("Keeping connection alive for 60 seconds...");
        System.out.println("APPTAG for monitoring: " + appTag);
        System.out.println("");
        
        for (int i = 1; i <= 6; i++) {
            Thread.sleep(10000);
            System.out.println("  " + (i * 10) + " seconds elapsed... (APPTAG: " + appTag + ")");
        }
        
        // Cleanup
        System.out.println("");
        System.out.println("Cleaning up...");
        for (MessageProducer producer : producers) {
            producer.close();
        }
        for (Session session : sessions) {
            session.close();
        }
        connection.close();
        
        System.out.println("");
        System.out.println("Test completed!");
        System.out.println("========================================================================");
    }
}