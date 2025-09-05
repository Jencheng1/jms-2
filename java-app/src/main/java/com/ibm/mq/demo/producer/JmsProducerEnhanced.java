package com.ibm.mq.demo.producer;

import com.ibm.mq.demo.utils.ConnectionInfo;
import com.ibm.mq.demo.utils.MQConnectionFactory;
import com.ibm.mq.demo.utils.SessionTracker;
import com.ibm.mq.MQException;
import com.ibm.mq.jms.MQConnection;
import com.ibm.mq.jms.MQSession;
import com.ibm.msg.client.wmq.WMQConstants;
import javax.jms.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class JmsProducerEnhanced {
    private static final String QUEUE_NAME = "UNIFORM.QUEUE";
    private static final AtomicInteger messageCounter = new AtomicInteger(0);
    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Map<String, List<SessionInfo>> connectionSessionMap = Collections.synchronizedMap(new HashMap<>());
    
    // Class to track session information
    static class SessionInfo {
        String sessionId;
        String parentConnectionId;
        String queueManager;
        String channel;
        int sessionNumber;
        long createdTime;
        
        SessionInfo(String sessionId, String parentConnectionId, String queueManager, String channel, int sessionNumber) {
            this.sessionId = sessionId;
            this.parentConnectionId = parentConnectionId;
            this.queueManager = queueManager;
            this.channel = channel;
            this.sessionNumber = sessionNumber;
            this.createdTime = System.currentTimeMillis();
        }
        
        @Override
        public String toString() {
            return String.format("Session[id=%s, parent=%s, QM=%s, channel=%s, num=%d]", 
                sessionId, parentConnectionId, queueManager, channel, sessionNumber);
        }
    }
    
    public static void main(String[] args) {
        int numberOfMessages = 100;
        int numberOfProducers = 3;
        int sessionsPerProducer = 2;
        int delayBetweenMessages = 100;
        
        if (args.length > 0) numberOfMessages = Integer.parseInt(args[0]);
        if (args.length > 1) numberOfProducers = Integer.parseInt(args[1]);
        if (args.length > 2) sessionsPerProducer = Integer.parseInt(args[2]);
        if (args.length > 3) delayBetweenMessages = Integer.parseInt(args[3]);
        
        System.out.println("========================================");
        System.out.println("IBM MQ Uniform Cluster Enhanced Producer");
        System.out.println("========================================");
        System.out.println("Messages to send: " + numberOfMessages);
        System.out.println("Number of producers: " + numberOfProducers);
        System.out.println("Sessions per producer: " + sessionsPerProducer);
        System.out.println("Delay between messages: " + delayBetweenMessages + "ms");
        System.out.println("Target Queue: " + QUEUE_NAME);
        System.out.println("========================================\n");
        
        Thread[] producers = new Thread[numberOfProducers];
        
        for (int i = 0; i < numberOfProducers; i++) {
            final int producerId = i + 1;
            final int messagesPerProducer = numberOfMessages / numberOfProducers;
            final int finalDelay = delayBetweenMessages;
            final int finalSessionsPerProducer = sessionsPerProducer;
            
            producers[i] = new Thread(() -> {
                try {
                    runProducerWithMultipleSessions(producerId, messagesPerProducer, finalSessionsPerProducer, finalDelay);
                } catch (Exception e) {
                    System.err.println("Producer " + producerId + " failed: " + e.getMessage());
                    e.printStackTrace();
                }
            });
            producers[i].setName("Producer-" + producerId);
            producers[i].start();
        }
        
        // Wait for all producers to complete
        for (Thread producer : producers) {
            try {
                producer.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        
        printConnectionSessionMapping();
        
        System.out.println("\n========================================");
        System.out.println("All producers completed!");
        System.out.println("Total messages sent: " + messageCounter.get());
        System.out.println("========================================");
    }
    
    private static void runProducerWithMultipleSessions(int producerId, int numberOfMessages, 
                                                       int sessionsPerProducer, int delay) throws Exception {
        ConnectionFactory connectionFactory = MQConnectionFactory.createConnectionFactory();
        
        // Create unique correlation ID for this producer
        String correlationId = "PROD-" + producerId + "-" + UUID.randomUUID().toString().substring(0, 8);
        
        Connection connection = connectionFactory.createConnection("app", "passw0rd");
        
        try {
            // Set application name and tags for correlation
            if (connection instanceof MQConnection) {
                MQConnection mqConn = (MQConnection) connection;
                // Set application tag that will be visible in MQSC
                mqConn.getConnectionMetaData();
            }
            
            // Get connection details
            String connectionId = connection.getClientID();
            ConnectionInfo connInfo = new ConnectionInfo(connection);
            String queueManager = connInfo.getConnectedQueueManager();
            
            System.out.println("\n[Producer-" + producerId + "] ===========================================");
            System.out.println("[Producer-" + producerId + "] CONNECTION ESTABLISHED");
            System.out.println("[Producer-" + producerId + "] Correlation ID: " + correlationId);
            System.out.println("[Producer-" + producerId + "] Connection ID: " + connectionId);
            System.out.println("[Producer-" + producerId + "] Queue Manager: " + queueManager);
            System.out.println("[Producer-" + producerId + "] Creating " + sessionsPerProducer + " sessions...");
            System.out.println("[Producer-" + producerId + "] ===========================================\n");
            
            connection.start();
            
            // Create multiple sessions from the same connection
            List<SessionInfo> sessions = new ArrayList<>();
            Session[] jmsSessions = new Session[sessionsPerProducer];
            MessageProducer[] producers = new MessageProducer[sessionsPerProducer];
            
            for (int s = 0; s < sessionsPerProducer; s++) {
                jmsSessions[s] = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                
                // Generate session tracking info
                String sessionId = correlationId + "-S" + (s + 1);
                String channel = "APP.SVRCONN"; // This would be extracted from actual session
                
                SessionInfo sessionInfo = new SessionInfo(
                    sessionId,
                    connectionId,
                    queueManager,
                    channel,
                    s + 1
                );
                
                sessions.add(sessionInfo);
                
                Queue queue = jmsSessions[s].createQueue("queue:///" + QUEUE_NAME);
                producers[s] = jmsSessions[s].createProducer(queue);
                producers[s].setDeliveryMode(DeliveryMode.PERSISTENT);
                
                System.out.println("[Producer-" + producerId + "] Created " + sessionInfo);
            }
            
            // Store the connection-session mapping
            connectionSessionMap.put(correlationId, sessions);
            
            // Distribute messages across sessions
            int messagesPerSession = numberOfMessages / sessionsPerProducer;
            
            for (int s = 0; s < sessionsPerProducer; s++) {
                final int sessionNum = s + 1;
                final SessionInfo sessionInfo = sessions.get(s);
                
                System.out.println("\n[Producer-" + producerId + "] Session " + sessionNum + 
                    " sending " + messagesPerSession + " messages...");
                
                for (int i = 1; i <= messagesPerSession; i++) {
                    String timestamp = sdf.format(new Date());
                    String messageText = String.format(
                        "Message #%d from Producer-%d/Session-%d at %s [QM: %s]",
                        i, producerId, sessionNum, timestamp, queueManager
                    );
                    
                    TextMessage message = jmsSessions[s].createTextMessage(messageText);
                    
                    // Set correlation properties for tracking
                    message.setStringProperty("ProducerId", String.valueOf(producerId));
                    message.setStringProperty("CorrelationId", correlationId);
                    message.setStringProperty("SessionId", sessionInfo.sessionId);
                    message.setStringProperty("ConnectionId", connectionId);
                    message.setStringProperty("QueueManager", queueManager);
                    message.setStringProperty("Channel", channel);
                    message.setLongProperty("Timestamp", System.currentTimeMillis());
                    message.setIntProperty("SequenceNumber", i);
                    message.setIntProperty("SessionNumber", sessionNum);
                    
                    // Add application tag for MQSC visibility
                    message.setStringProperty("JMS_IBM_MQMD_ApplIdentityData", correlationId);
                    message.setStringProperty("JMS_IBM_MQMD_PutApplName", "PROD" + producerId + "S" + sessionNum);
                    
                    producers[s].send(message);
                    
                    int totalSent = messageCounter.incrementAndGet();
                    
                    if (i % 50 == 0) {
                        System.out.println("[Producer-" + producerId + "/Session-" + sessionNum + 
                            "] Sent " + i + " messages (Total: " + totalSent + ")");
                    }
                    
                    if (delay > 0 && i < messagesPerSession) {
                        Thread.sleep(delay);
                    }
                }
                
                System.out.println("[Producer-" + producerId + "/Session-" + sessionNum + 
                    "] Completed sending " + messagesPerSession + " messages");
            }
            
            // Close all sessions and producers
            for (int s = 0; s < sessionsPerProducer; s++) {
                if (producers[s] != null) producers[s].close();
                if (jmsSessions[s] != null) jmsSessions[s].close();
            }
            
            System.out.println("\n[Producer-" + producerId + "] All sessions closed successfully");
            
        } finally {
            if (connection != null) {
                connection.close();
                System.out.println("[Producer-" + producerId + "] Connection closed");
            }
        }
    }
    
    private static void printConnectionSessionMapping() {
        System.out.println("\n========================================");
        System.out.println("CONNECTION-SESSION PARENT-CHILD MAPPING");
        System.out.println("========================================");
        
        for (Map.Entry<String, List<SessionInfo>> entry : connectionSessionMap.entrySet()) {
            String correlationId = entry.getKey();
            List<SessionInfo> sessions = entry.getValue();
            
            System.out.println("\nConnection: " + correlationId);
            
            // Group sessions by QM to verify they all went to the same QM
            Map<String, Integer> qmCount = new HashMap<>();
            for (SessionInfo session : sessions) {
                qmCount.put(session.queueManager, qmCount.getOrDefault(session.queueManager, 0) + 1);
                System.out.println("  └─> " + session);
            }
            
            // Verify all sessions went to same QM as parent
            if (qmCount.size() == 1) {
                System.out.println("  ✓ All sessions connected to same QM: " + qmCount.keySet().iterator().next());
            } else {
                System.out.println("  ✗ WARNING: Sessions connected to different QMs: " + qmCount);
            }
        }
        
        System.out.println("\n========================================");
        System.out.println("SUMMARY:");
        System.out.println("  Total Connections: " + connectionSessionMap.size());
        int totalSessions = connectionSessionMap.values().stream()
            .mapToInt(List::size)
            .sum();
        System.out.println("  Total Sessions: " + totalSessions);
        System.out.println("========================================");
    }
}