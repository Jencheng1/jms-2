import com.ibm.mq.jms.*;
import com.ibm.msg.client.wmq.WMQConstants;
import javax.jms.*;
import java.util.*;

public class DirectConnectionProof {
    
    public static void main(String[] args) throws Exception {
        String appTag = "DIRECT-" + System.currentTimeMillis();
        
        System.out.println("========================================================================");
        System.out.println("DIRECT CONNECTION PROOF - 1 CONNECTION, 5 SESSIONS");
        System.out.println("========================================================================");
        System.out.println("APPTAG: " + appTag);
        System.out.println("");
        
        // Create connection factory
        MQConnectionFactory factory = new MQConnectionFactory();
        factory.setHostName("10.10.10.10");
        factory.setPort(1414);
        factory.setChannel("APP.SVRCONN");
        factory.setQueueManager("QM1");
        factory.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, appTag);
        
        System.out.println("Creating 1 JMS Connection...");
        Connection connection = factory.createConnection("app", "passw0rd");
        connection.start();
        System.out.println("✓ Connection created and started");
        System.out.println("");
        
        System.out.println("Creating 5 Sessions from the connection...");
        List<Session> sessions = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            sessions.add(session);
            System.out.println("  ✓ Session " + i + " created");
        }
        
        System.out.println("");
        System.out.println("All sessions created successfully!");
        System.out.println("");
        System.out.println("RUN THIS COMMAND TO SEE 6 CONNECTIONS:");
        System.out.println("docker exec qm1 bash -c \"echo 'DIS CONN(*) WHERE(APPLTAG EQ " + appTag + ")' | runmqsc QM1\"");
        System.out.println("");
        System.out.println("Keeping connection alive for 60 seconds...");
        
        for (int i = 1; i <= 6; i++) {
            Thread.sleep(10000);
            System.out.println("  " + (i * 10) + " seconds... (APPTAG: " + appTag + ")");
        }
        
        // Cleanup
        for (Session session : sessions) {
            session.close();
        }
        connection.close();
        
        System.out.println("");
        System.out.println("Test completed!");
        System.out.println("========================================================================");
    }
}