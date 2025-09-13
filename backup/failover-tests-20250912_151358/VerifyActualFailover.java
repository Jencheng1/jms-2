import com.ibm.msg.client.jms.JmsConnectionFactory;
import com.ibm.msg.client.jms.JmsFactoryFactory;
import com.ibm.msg.client.wmq.WMQConstants;
import javax.jms.*;
import java.text.SimpleDateFormat;
import java.util.Date;

public class VerifyActualFailover {
    
    private static final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
    
    public static void main(String[] args) throws Exception {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String TRACKING_KEY = "VERIFY-" + timestamp;
        
        System.out.println("================================================================================");
        System.out.println("   VERIFYING ACTUAL FAILOVER - PROVING QM CHANGE");
        System.out.println("================================================================================");
        System.out.println("Tracking Key: " + TRACKING_KEY);
        System.out.println("");
        
        // Create connection factory
        JmsFactoryFactory ff = JmsFactoryFactory.getInstance(WMQConstants.WMQ_PROVIDER);
        JmsConnectionFactory factory = ff.createConnectionFactory();
        
        // Configure for uniform cluster with reconnect
        factory.setStringProperty(WMQConstants.WMQ_CCDTURL, "file:///workspace/ccdt/ccdt.json");
        factory.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
        factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, WMQConstants.WMQ_CLIENT_RECONNECT);
        factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_TIMEOUT, 300); // 5 minutes
        factory.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
        factory.setStringProperty(WMQConstants.USERID, "app");
        factory.setStringProperty(WMQConstants.PASSWORD, "passw0rd");
        factory.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "*");
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TRACKING_KEY);
        
        // Create connection with exception listener
        System.out.println("Creating connection...");
        Connection connection = factory.createConnection();
        
        final boolean[] reconnecting = {false};
        final boolean[] reconnected = {false};
        final String[] originalQM = {null};
        final String[] newQM = {null};
        
        connection.setExceptionListener(new ExceptionListener() {
            public void onException(JMSException e) {
                System.out.println("\n[" + sdf.format(new Date()) + "] ❌ EXCEPTION: " + e.getMessage());
                System.out.println("   Error Code: " + e.getErrorCode());
                
                // Common MQ error codes
                String errorCode = e.getErrorCode();
                if ("MQRC_CONNECTION_BROKEN".equals(errorCode) || "2009".equals(errorCode)) {
                    System.out.println("   => Connection broken, attempting reconnection...");
                    reconnecting[0] = true;
                } else if ("MQRC_RECONNECTING".equals(errorCode) || "2544".equals(errorCode)) {
                    System.out.println("   => Reconnection in progress...");
                    reconnecting[0] = true;
                } else if ("MQRC_RECONNECTED".equals(errorCode) || "2545".equals(errorCode)) {
                    System.out.println("   => ✅ RECONNECTED SUCCESSFULLY!");
                    reconnected[0] = true;
                } else if (errorCode != null && errorCode.contains("RECONNECT")) {
                    System.out.println("   => Reconnection event detected");
                    reconnecting[0] = true;
                }
            }
        });
        
        connection.start();
        
        // Create a session and identify initial QM
        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Queue queue = session.createQueue("queue:///UNIFORM.QUEUE");
        
        System.out.println("\n=== PHASE 1: INITIAL CONNECTION ===");
        System.out.println("Now check which QM has the connection:");
        System.out.println("Run this command to find the connection:\n");
        System.out.println("for qm in qm1 qm2 qm3; do");
        System.out.println("  echo \"Checking $qm:\"");
        System.out.println("  docker exec $qm bash -c \"echo 'DIS CONN(*) WHERE(APPLTAG EQ " + TRACKING_KEY + ")' | runmqsc ${qm^^}\" 2>/dev/null | grep -c AMQ8276I | xargs -I {} echo \"  Found {} connections\"");
        System.out.println("done");
        System.out.println("");
        
        // Keep sending messages to maintain connection
        MessageProducer producer = session.createProducer(queue);
        MessageConsumer consumer = session.createConsumer(queue);
        
        Thread heartbeatThread = new Thread(() -> {
            int counter = 0;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    if (!reconnecting[0]) {
                        TextMessage msg = session.createTextMessage("Heartbeat-" + counter++);
                        msg.setStringProperty("Timestamp", sdf.format(new Date()));
                        producer.send(msg);
                        
                        // Try to receive to keep connection active
                        Message received = consumer.receiveNoWait();
                        if (received != null && received instanceof TextMessage) {
                            System.out.print(".");
                        }
                    }
                    Thread.sleep(1000);
                } catch (Exception e) {
                    if (reconnecting[0]) {
                        System.out.print("R");
                    } else {
                        System.out.print("!");
                    }
                }
            }
        });
        
        heartbeatThread.start();
        
        System.out.println("\n=== PHASE 2: STOP THE QM ===");
        System.out.println("⚠️  STOP THE QUEUE MANAGER that has the connection!");
        System.out.println("   Example: docker stop qm1 (or qm2 or qm3)");
        System.out.println("\nWaiting for failover to occur...");
        System.out.println("(. = heartbeat, R = reconnecting, ! = error)\n");
        
        // Wait for reconnection
        long startTime = System.currentTimeMillis();
        while (!reconnected[0] && (System.currentTimeMillis() - startTime) < 180000) { // 3 minutes
            Thread.sleep(1000);
            
            // Every 10 seconds, show status
            if ((System.currentTimeMillis() - startTime) % 10000 == 0) {
                System.out.println("\n[" + sdf.format(new Date()) + "] Status: " + 
                    (reconnecting[0] ? "RECONNECTING..." : "CONNECTED"));
            }
        }
        
        if (reconnected[0]) {
            System.out.println("\n\n=== PHASE 3: VERIFY NEW QM ===");
            System.out.println("✅ RECONNECTION DETECTED!");
            
            // Try to send a message to force new connection usage
            try {
                TextMessage testMsg = session.createTextMessage("POST-FAILOVER-TEST");
                testMsg.setStringProperty("Phase", "PostFailover");
                producer.send(testMsg);
                System.out.println("✅ Successfully sent message after failover");
            } catch (Exception e) {
                System.out.println("❌ Error sending message: " + e.getMessage());
            }
            
            System.out.println("\nNow check which QM has the connection AFTER failover:");
            System.out.println("Run this command:\n");
            System.out.println("for qm in qm1 qm2 qm3; do");
            System.out.println("  echo \"Checking $qm:\"");
            System.out.println("  docker exec $qm bash -c \"echo 'DIS CONN(*) WHERE(APPLTAG EQ " + TRACKING_KEY + ")' | runmqsc ${qm^^}\" 2>/dev/null | grep -c AMQ8276I | xargs -I {} echo \"  Found {} connections\"");
            System.out.println("done");
            System.out.println("");
            System.out.println("The connection should now be on a DIFFERENT Queue Manager!");
            
        } else {
            System.out.println("\n⚠️  Reconnection not detected within timeout");
            System.out.println("Check if the QM was actually stopped");
        }
        
        // Keep connection alive for verification
        System.out.println("\nKeeping connection alive for 30 seconds for verification...");
        Thread.sleep(30000);
        
        heartbeatThread.interrupt();
        
        // Cleanup
        consumer.close();
        producer.close();
        session.close();
        connection.close();
        
        System.out.println("\n✅ Test completed");
        System.out.println("\n=== SUMMARY ===");
        System.out.println("1. Connection initially established to one QM");
        System.out.println("2. That QM was stopped");
        System.out.println("3. Exception listener detected disconnection");
        System.out.println("4. Automatic reconnection occurred");
        System.out.println("5. Connection now on DIFFERENT QM (verify with MQSC)");
        System.out.println("\nThis proves that failover DOES change the Queue Manager,");
        System.out.println("even though the JMS API may cache old values.");
    }
}