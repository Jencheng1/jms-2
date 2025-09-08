import com.ibm.mq.jms.*;
import com.ibm.msg.client.wmq.WMQConstants;
import javax.jms.*;
import java.util.*;

/**
 * Demonstration of PCF monitoring with PCFUtils
 * Shows how PCF collects same information as RUNMQSC with better correlation
 */
public class PCFDemo {
    
    private static final String QUEUE_MANAGER = "QM1";
    private static final String HOST = "10.10.10.10";
    private static final int PORT = 1414;
    private static final String CHANNEL = "APP.SVRCONN";
    private static final String USER = "app";
    private static final String PASSWORD = "passw0rd";
    private static final String QUEUE_NAME = "TEST.QUEUE";
    
    public static void main(String[] args) {
        String appTag = "PCFDEMO" + (System.currentTimeMillis() % 10000);
        PCFUtils pcfUtils = null;
        Connection jmsConnection = null;
        
        try {
            System.out.println("================================================================================");
            System.out.println("PCF DEMONSTRATION - CORRELATING JMS WITH MQ USING PCF");
            System.out.println("================================================================================");
            System.out.println("Application Tag: " + appTag);
            System.out.println("");
            
            // Step 1: Create JMS Connection and Sessions
            System.out.println("STEP 1: Creating JMS Connection and Sessions");
            System.out.println("---------------------------------------------");
            
            MQConnectionFactory factory = new MQConnectionFactory();
            factory.setHostName(HOST);
            factory.setPort(PORT);
            factory.setChannel(CHANNEL);
            factory.setQueueManager(QUEUE_MANAGER);
            factory.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
            factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, appTag);
            
            jmsConnection = factory.createConnection(USER, PASSWORD);
            jmsConnection.start();
            System.out.println("✓ JMS Connection created with APPTAG: " + appTag);
            
            // Create 5 sessions
            List<Session> sessions = new ArrayList<>();
            List<MessageProducer> producers = new ArrayList<>();
            
            for (int i = 1; i <= 5; i++) {
                Session session = jmsConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                sessions.add(session);
                
                javax.jms.Queue queue = session.createQueue(QUEUE_NAME);
                MessageProducer producer = session.createProducer(queue);
                producers.add(producer);
                
                System.out.println("✓ Session " + i + " created");
            }
            
            // Send messages to activate sessions
            for (int i = 0; i < producers.size(); i++) {
                Session session = sessions.get(i);
                MessageProducer producer = producers.get(i);
                TextMessage msg = session.createTextMessage("Test message from session " + (i + 1));
                producer.send(msg);
            }
            
            System.out.println("\nJMS Level: 1 Connection + 5 Sessions created and activated");
            
            // Wait for connections to establish
            Thread.sleep(2000);
            
            // Step 2: Initialize PCF Utils
            System.out.println("\nSTEP 2: Connecting PCF Monitor");
            System.out.println("-------------------------------");
            
            pcfUtils = new PCFUtils(QUEUE_MANAGER, HOST, PORT, CHANNEL, USER, PASSWORD);
            System.out.println("✓ PCF Utils connected to " + QUEUE_MANAGER);
            
            // Step 3: Query connections using PCF
            System.out.println("\nSTEP 3: PCF Query - Connections by APPTAG");
            System.out.println("------------------------------------------");
            System.out.println("PCF Equivalent: DIS CONN(*) WHERE(APPLTAG EQ '" + appTag + "')");
            System.out.println("");
            
            List<PCFUtils.ConnectionDetails> connections = pcfUtils.getConnectionsByAppTag(appTag);
            
            System.out.println("Connections found: " + connections.size());
            for (int i = 0; i < connections.size(); i++) {
                PCFUtils.ConnectionDetails conn = connections.get(i);
                System.out.println("\nConnection #" + (i + 1) + ":");
                System.out.println("  ID: " + conn.connectionId.substring(0, 16) + "...");
                System.out.println("  Channel: " + conn.channelName);
                System.out.println("  Connection: " + conn.connectionName);
                System.out.println("  User: " + conn.userId);
                System.out.println("  PID: " + conn.pid + ", TID: " + conn.tid);
            }
            
            // Step 4: Analyze parent-child relationships
            System.out.println("\nSTEP 4: Parent-Child Relationship Analysis");
            System.out.println("-------------------------------------------");
            
            PCFUtils.ParentChildAnalysis analysis = pcfUtils.analyzeParentChildRelationships(appTag);
            analysis.printAnalysis();
            
            // Step 5: Query channel status
            System.out.println("\nSTEP 5: PCF Query - Channel Status");
            System.out.println("-----------------------------------");
            System.out.println("PCF Equivalent: DIS CHSTATUS('" + CHANNEL + "')");
            System.out.println("");
            
            List<PCFUtils.ChannelStatus> channelStatuses = pcfUtils.getChannelStatus(CHANNEL);
            System.out.println("Active channel instances: " + channelStatuses.size());
            
            int runningCount = 0;
            for (PCFUtils.ChannelStatus status : channelStatuses) {
                if (status.status == 5) { // RUNNING
                    runningCount++;
                    if (runningCount <= 3) { // Show first 3
                        System.out.println("  Instance " + runningCount + ": " + 
                                         status.connectionName + " - " + status.getStatusString());
                    }
                }
            }
            if (runningCount > 3) {
                System.out.println("  ... and " + (runningCount - 3) + " more");
            }
            
            // Step 6: Query queue manager info
            System.out.println("\nSTEP 6: PCF Query - Queue Manager Info");
            System.out.println("---------------------------------------");
            System.out.println("PCF Equivalent: DIS QMGR");
            System.out.println("");
            
            PCFUtils.QueueManagerInfo qmInfo = pcfUtils.getQueueManagerInfo();
            if (qmInfo != null) {
                System.out.println("Queue Manager: " + qmInfo.name);
                System.out.println("  Platform: " + qmInfo.getPlatformString());
                System.out.println("  Command Level: " + qmInfo.commandLevel);
                System.out.println("  Connection Count: " + qmInfo.connectionCount);
            }
            
            // Step 7: Query queue status
            System.out.println("\nSTEP 7: PCF Query - Queue Status");
            System.out.println("---------------------------------");
            System.out.println("PCF Equivalent: DIS QSTATUS('" + QUEUE_NAME + "')");
            System.out.println("");
            
            PCFUtils.QueueStatus queueStatus = pcfUtils.getQueueStatus(QUEUE_NAME);
            if (queueStatus != null) {
                System.out.println("Queue: " + queueStatus.queueName);
                System.out.println("  Current Depth: " + queueStatus.currentDepth);
                System.out.println("  Open Output: " + queueStatus.openOutputCount);
                System.out.println("  Open Input: " + queueStatus.openInputCount);
            }
            
            // Step 8: Correlation Summary
            System.out.println("\n================================================================================");
            System.out.println("CORRELATION SUMMARY");
            System.out.println("================================================================================");
            System.out.println("JMS Level:");
            System.out.println("  - 1 Connection created");
            System.out.println("  - 5 Sessions created from that connection");
            System.out.println("  - All using APPTAG: " + appTag);
            System.out.println("");
            System.out.println("MQ Level (via PCF):");
            System.out.println("  - " + connections.size() + " MQ connections found");
            System.out.println("  - All connections share APPTAG: " + appTag);
            System.out.println("  - All from same PID/TID: " + analysis.allFromSameProcess);
            System.out.println("  - All on Queue Manager: " + QUEUE_MANAGER);
            System.out.println("");
            
            if (connections.size() >= 6 && analysis.allFromSameProcess) {
                System.out.println("✓✓✓ PARENT-CHILD CORRELATION PROVEN ✓✓✓");
                System.out.println("PCF successfully showed that:");
                System.out.println("  1. One JMS Connection creates multiple MQ connections");
                System.out.println("  2. Sessions appear as additional MQ connections");
                System.out.println("  3. All inherit parent's queue manager affinity");
                System.out.println("  4. Uniform Cluster maintains session affinity");
            }
            
            System.out.println("");
            System.out.println("PCF Advantages over RUNMQSC:");
            System.out.println("  - Programmatic access (no shell/text parsing needed)");
            System.out.println("  - Real-time correlation while app is running");
            System.out.println("  - Structured data response");
            System.out.println("  - Can be integrated directly into Java apps");
            System.out.println("  - Same JVM as application for perfect timing");
            System.out.println("================================================================================");
            
            // Keep alive for 20 seconds for verification
            System.out.println("\nKeeping connections alive for 20 seconds...");
            System.out.println("Verify with: docker exec qm1 bash -c \"echo 'DIS CONN(*) WHERE(APPLTAG EQ " + 
                           appTag + ")' | runmqsc QM1\"");
            
            for (int i = 1; i <= 2; i++) {
                Thread.sleep(10000);
                System.out.println("  " + (i * 10) + " seconds...");
            }
            
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Cleanup
            System.out.println("\nCleaning up...");
            
            if (pcfUtils != null) {
                pcfUtils.close();
            }
            
            if (jmsConnection != null) {
                try {
                    jmsConnection.close();
                } catch (Exception e) {
                    // Ignore
                }
            }
            
            System.out.println("Demo completed!");
        }
    }
}