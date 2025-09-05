package com.ibm.mq.demo.consumer;

import com.ibm.mq.demo.utils.ConnectionInfo;
import com.ibm.mq.demo.utils.MQConnectionFactory;
import com.ibm.mq.demo.utils.SessionTracker;
import com.ibm.mq.jms.MQConnection;
import com.ibm.mq.jms.MQSession;
import javax.jms.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class JmsConsumerEnhanced {
    private static final String QUEUE_NAME = "UNIFORM.QUEUE";
    private static final AtomicInteger totalMessageCount = new AtomicInteger(0);
    private static final Map<String, AtomicInteger> qmMessageCount = new ConcurrentHashMap<>();
    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Map<String, List<SessionInfo>> connectionSessionMap = new ConcurrentHashMap<>();
    
    // Class to track session information
    static class SessionInfo {
        String sessionId;
        String parentConnectionId;
        String queueManager;
        String channel;
        int sessionNumber;
        long createdTime;
        int messagesConsumed = 0;
        
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
            return String.format("Session[id=%s, parent=%s, QM=%s, channel=%s, num=%d, consumed=%d]", 
                sessionId, parentConnectionId, queueManager, channel, sessionNumber, messagesConsumed);
        }
    }
    
    public static void main(String[] args) {
        int numberOfConsumers = 3;
        int sessionsPerConsumer = 2;
        int receiveTimeout = 5000;
        boolean continuous = false;
        
        if (args.length > 0) numberOfConsumers = Integer.parseInt(args[0]);
        if (args.length > 1) sessionsPerConsumer = Integer.parseInt(args[1]);
        if (args.length > 2) receiveTimeout = Integer.parseInt(args[2]);
        if (args.length > 3) continuous = Boolean.parseBoolean(args[3]);
        
        System.out.println("========================================");
        System.out.println("IBM MQ Uniform Cluster Enhanced Consumer");
        System.out.println("========================================");
        System.out.println("Number of consumers: " + numberOfConsumers);
        System.out.println("Sessions per consumer: " + sessionsPerConsumer);
        System.out.println("Receive timeout: " + receiveTimeout + "ms");
        System.out.println("Continuous mode: " + continuous);
        System.out.println("Target Queue: " + QUEUE_NAME);
        System.out.println("========================================\n");
        
        // Initialize QM counters
        qmMessageCount.put("QM1", new AtomicInteger(0));
        qmMessageCount.put("QM2", new AtomicInteger(0));
        qmMessageCount.put("QM3", new AtomicInteger(0));
        
        Thread[] consumers = new Thread[numberOfConsumers];
        
        for (int i = 0; i < numberOfConsumers; i++) {
            final int consumerId = i + 1;
            final int timeout = receiveTimeout;
            final boolean cont = continuous;
            final int finalSessionsPerConsumer = sessionsPerConsumer;
            
            consumers[i] = new Thread(() -> {
                try {
                    runConsumerWithMultipleSessions(consumerId, finalSessionsPerConsumer, timeout, cont);
                } catch (Exception e) {
                    System.err.println("Consumer " + consumerId + " failed: " + e.getMessage());
                    e.printStackTrace();
                }
            });
            consumers[i].setName("Consumer-" + consumerId);
            consumers[i].start();
        }
        
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down consumers...");
            printStatistics();
            printConnectionSessionMapping();
            SessionTracker.printTrackingReport();
        }));
        
        for (Thread consumer : consumers) {
            try {
                consumer.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        
        printStatistics();
        printConnectionSessionMapping();
        SessionTracker.printTrackingReport();
    }
    
    private static void runConsumerWithMultipleSessions(int consumerId, int sessionsPerConsumer, 
                                                       int timeout, boolean continuous) throws Exception {
        ConnectionFactory connectionFactory = MQConnectionFactory.createConnectionFactory();
        
        // Create unique correlation ID for this consumer
        String correlationId = "CONS-" + consumerId + "-" + UUID.randomUUID().toString().substring(0, 8);
        
        Connection connection = connectionFactory.createConnection("app", "passw0rd");
        
        try {
            // Track the connection
            SessionTracker.ConnectionTracking connTracking = SessionTracker.trackConnection(connection, correlationId);
            
            String connectionId = connection.getClientID();
            ConnectionInfo connInfo = new ConnectionInfo(connection);
            String queueManager = connInfo.getConnectedQueueManager();
            
            System.out.println("\n[Consumer-" + consumerId + "] ===========================================");
            System.out.println("[Consumer-" + consumerId + "] CONNECTION ESTABLISHED");
            System.out.println("[Consumer-" + consumerId + "] Correlation ID: " + correlationId);
            System.out.println("[Consumer-" + consumerId + "] Connection ID: " + connectionId);
            System.out.println("[Consumer-" + consumerId + "] Queue Manager: " + queueManager);
            System.out.println("[Consumer-" + consumerId + "] Creating " + sessionsPerConsumer + " sessions...");
            System.out.println("[Consumer-" + consumerId + "] ===========================================\n");
            
            connection.setExceptionListener(e -> {
                System.out.println("[Consumer-" + consumerId + "] Connection exception: " + e.getMessage());
                System.out.println("[Consumer-" + consumerId + "] Will attempt automatic reconnection...");
            });
            
            connection.start();
            
            // Create multiple sessions from the same connection
            List<SessionInfo> sessions = new ArrayList<>();
            Session[] jmsSessions = new Session[sessionsPerConsumer];
            MessageConsumer[] consumers = new MessageConsumer[sessionsPerConsumer];
            
            for (int s = 0; s < sessionsPerConsumer; s++) {
                jmsSessions[s] = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                
                // Track the session
                SessionTracker.SessionTracking sessionTracking = SessionTracker.trackSession(
                    jmsSessions[s], correlationId, connection, s + 1
                );
                
                String sessionId = correlationId + "-S" + (s + 1);
                String channel = "APP.SVRCONN";
                
                SessionInfo sessionInfo = new SessionInfo(
                    sessionId,
                    connectionId,
                    queueManager,
                    channel,
                    s + 1
                );
                
                sessions.add(sessionInfo);
                
                Queue queue = jmsSessions[s].createQueue("queue:///" + QUEUE_NAME);
                consumers[s] = jmsSessions[s].createConsumer(queue);
                
                System.out.println("[Consumer-" + consumerId + "] Created " + sessionInfo);
            }
            
            // Store the connection-session mapping
            connectionSessionMap.put(correlationId, sessions);
            
            // Create threads for each session to consume messages concurrently
            Thread[] sessionThreads = new Thread[sessionsPerConsumer];
            
            for (int s = 0; s < sessionsPerConsumer; s++) {
                final int sessionNum = s + 1;
                final SessionInfo sessionInfo = sessions.get(s);
                final MessageConsumer consumer = consumers[s];
                final Session session = jmsSessions[s];
                
                sessionThreads[s] = new Thread(() -> {
                    try {
                        consumeMessages(consumerId, sessionNum, sessionInfo, consumer, 
                                      session, timeout, continuous, correlationId);
                    } catch (Exception e) {
                        System.err.println("[Consumer-" + consumerId + "/Session-" + sessionNum + 
                            "] Error: " + e.getMessage());
                    }
                });
                
                sessionThreads[s].start();
            }
            
            // Wait for all session threads to complete
            for (Thread sessionThread : sessionThreads) {
                sessionThread.join();
            }
            
            // Close all sessions and consumers
            for (int s = 0; s < sessionsPerConsumer; s++) {
                if (consumers[s] != null) consumers[s].close();
                if (jmsSessions[s] != null) jmsSessions[s].close();
            }
            
            System.out.println("\n[Consumer-" + consumerId + "] All sessions closed successfully");
            
        } finally {
            if (connection != null) {
                connection.close();
                System.out.println("[Consumer-" + consumerId + "] Connection closed");
            }
        }
    }
    
    private static void consumeMessages(int consumerId, int sessionNum, SessionInfo sessionInfo,
                                       MessageConsumer consumer, Session session, int timeout,
                                       boolean continuous, String correlationId) throws Exception {
        int localMessageCount = 0;
        int consecutiveNulls = 0;
        
        System.out.println("[Consumer-" + consumerId + "/Session-" + sessionNum + 
            "] Starting message consumption...");
        
        while (true) {
            Message message = consumer.receive(timeout);
            
            if (message == null) {
                consecutiveNulls++;
                if (!continuous || consecutiveNulls > 3) {
                    System.out.println("[Consumer-" + consumerId + "/Session-" + sessionNum + 
                        "] No more messages after " + consecutiveNulls + " attempts. Exiting.");
                    break;
                }
                System.out.println("[Consumer-" + consumerId + "/Session-" + sessionNum + 
                    "] Waiting for messages... (attempt " + consecutiveNulls + ")");
                continue;
            }
            
            consecutiveNulls = 0;
            localMessageCount++;
            sessionInfo.messagesConsumed++;
            
            if (message instanceof TextMessage) {
                TextMessage textMessage = (TextMessage) message;
                String text = textMessage.getText();
                
                // Extract correlation properties
                String msgProducerId = textMessage.getStringProperty("ProducerId");
                String msgCorrelationId = textMessage.getStringProperty("CorrelationId");
                String msgSessionId = textMessage.getStringProperty("SessionId");
                String msgConnectionId = textMessage.getStringProperty("ConnectionId");
                String sourceQM = textMessage.getStringProperty("QueueManager");
                String msgChannel = textMessage.getStringProperty("Channel");
                int msgSessionNum = textMessage.getIntProperty("SessionNumber");
                int sequenceNumber = textMessage.getIntProperty("SequenceNumber");
                
                // Update counters
                totalMessageCount.incrementAndGet();
                if (sourceQM != null && qmMessageCount.containsKey(sourceQM)) {
                    qmMessageCount.get(sourceQM).incrementAndGet();
                }
                
                // Log correlation details for verification
                if (localMessageCount <= 5 || localMessageCount % 50 == 0) {
                    System.out.println("\n[Consumer-" + consumerId + "/Session-" + sessionNum + 
                        "] Message #" + localMessageCount);
                    System.out.println("  From: Producer-" + msgProducerId + "/Session-" + msgSessionNum);
                    System.out.println("  Producer Correlation: " + msgCorrelationId);
                    System.out.println("  Producer Session: " + msgSessionId);
                    System.out.println("  Source QM: " + sourceQM);
                    System.out.println("  Consumer QM: " + sessionInfo.queueManager);
                    
                    if (sourceQM != null && sourceQM.equals(sessionInfo.queueManager)) {
                        System.out.println("  ✓ Same QM for producer and consumer");
                    } else {
                        System.out.println("  ℹ Different QMs (expected with uniform cluster)");
                    }
                }
            }
        }
        
        System.out.println("[Consumer-" + consumerId + "/Session-" + sessionNum + 
            "] Completed. Consumed " + localMessageCount + " messages");
    }
    
    private static void printStatistics() {
        System.out.println("\n========================================");
        System.out.println("CONSUMER STATISTICS");
        System.out.println("========================================");
        System.out.println("Total messages consumed: " + totalMessageCount.get());
        System.out.println("\nMessage distribution by source Queue Manager:");
        
        int total = 0;
        for (Map.Entry<String, AtomicInteger> entry : qmMessageCount.entrySet()) {
            int count = entry.getValue().get();
            total += count;
            double percentage = total > 0 ? (count * 100.0 / totalMessageCount.get()) : 0;
            System.out.printf("  %s: %d messages (%.1f%%)\n", entry.getKey(), count, percentage);
        }
        
        System.out.println("========================================");
    }
    
    private static void printConnectionSessionMapping() {
        System.out.println("\n========================================");
        System.out.println("CONSUMER CONNECTION-SESSION MAPPING");
        System.out.println("========================================");
        
        for (Map.Entry<String, List<SessionInfo>> entry : connectionSessionMap.entrySet()) {
            String correlationId = entry.getKey();
            List<SessionInfo> sessions = entry.getValue();
            
            System.out.println("\nConnection: " + correlationId);
            
            Map<String, Integer> qmCount = new HashMap<>();
            for (SessionInfo session : sessions) {
                qmCount.put(session.queueManager, qmCount.getOrDefault(session.queueManager, 0) + 1);
                System.out.println("  └─> " + session);
            }
            
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
        int totalMessagesProcessed = connectionSessionMap.values().stream()
            .flatMap(List::stream)
            .mapToInt(s -> s.messagesConsumed)
            .sum();
        System.out.println("  Total Messages Processed: " + totalMessagesProcessed);
        System.out.println("========================================");
    }
}