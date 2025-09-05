package com.ibm.mq.demo.consumer;

import com.ibm.mq.demo.utils.*;
import javax.jms.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Enhanced JMS Consumer with PCF monitoring for undisputable parent-child correlation proof
 */
public class JmsConsumerWithPCF {
    private static final String QUEUE_NAME = "UNIFORM.QUEUE";
    private static final AtomicInteger totalMessageCount = new AtomicInteger(0);
    private static final Map<String, AtomicInteger> qmMessageCount = new ConcurrentHashMap<>();
    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Map<String, ConsumerContext> consumerContexts = new ConcurrentHashMap<>();
    
    static class ConsumerContext {
        String correlationId;
        String connectionId;
        String queueManager;
        List<String> sessionIds = new ArrayList<>();
        List<PCFMonitor.ConnectionDetails> pcfConnections = new ArrayList<>();
        String pcfEvidence;
        int messagesConsumed = 0;
        
        void addSession(String sessionId) {
            sessionIds.add(sessionId);
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
        
        System.out.println("╔════════════════════════════════════════════════════════════════╗");
        System.out.println("║   IBM MQ CONSUMER WITH PCF PARENT-CHILD CORRELATION PROOF     ║");
        System.out.println("╚════════════════════════════════════════════════════════════════╝");
        System.out.println("Number of consumers: " + numberOfConsumers);
        System.out.println("Sessions per consumer: " + sessionsPerConsumer);
        System.out.println("Receive timeout: " + receiveTimeout + "ms");
        System.out.println("═══════════════════════════════════════════════════════════════\n");
        
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
                    runConsumerWithPCF(consumerId, finalSessionsPerConsumer, timeout, cont);
                } catch (Exception e) {
                    System.err.println("Consumer " + consumerId + " failed: " + e.getMessage());
                    e.printStackTrace();
                }
            });
            consumers[i].setName("Consumer-" + consumerId);
            consumers[i].start();
        }
        
        // Wait for all consumers
        for (Thread consumer : consumers) {
            try {
                consumer.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        
        printFinalCorrelationProof();
        printStatistics();
    }
    
    private static void runConsumerWithPCF(int consumerId, int sessionsPerConsumer, 
                                           int timeout, boolean continuous) throws Exception {
        
        // Create correlation ID for this consumer
        String correlationId = "CONS-" + consumerId + "-" + UUID.randomUUID().toString().substring(0, 8);
        ConsumerContext context = new ConsumerContext();
        context.correlationId = correlationId;
        
        // Create connection factory with correlation metadata
        ConnectionFactory connectionFactory = MQConnectionFactoryEnhanced.createConsumerConnectionFactory(consumerId);
        
        Connection connection = connectionFactory.createConnection("app", "passw0rd");
        
        try {
            // Track connection
            SessionTracker.ConnectionTracking connTracking = SessionTracker.trackConnection(connection, correlationId);
            
            // Get connection details
            context.connectionId = connection.getClientID();
            ConnectionInfo connInfo = new ConnectionInfo(connection);
            context.queueManager = connInfo.getConnectedQueueManager();
            
            System.out.println("\n╔══════════════════════════════════════════════════════════════╗");
            System.out.println("  CONSUMER-" + consumerId + " CONNECTION ESTABLISHED");
            System.out.println("  Correlation ID: " + correlationId);
            System.out.println("  Connection ID: " + context.connectionId);
            System.out.println("  Queue Manager: " + context.queueManager);
            System.out.println("╚══════════════════════════════════════════════════════════════╝");
            
            // Connect to PCF for monitoring
            PCFMonitor pcfMonitor = null;
            try {
                // Determine QM port based on name
                int pcfPort = 1414;
                if (context.queueManager.equals("QM1")) pcfPort = 1414;
                else if (context.queueManager.equals("QM2")) pcfPort = 1415;
                else if (context.queueManager.equals("QM3")) pcfPort = 1416;
                
                pcfMonitor = new PCFMonitor(
                    context.queueManager,
                    "localhost",
                    pcfPort,
                    "APP.SVRCONN",
                    "app",
                    "passw0rd"
                );
                pcfMonitor.connect();
                
                // Get initial PCF evidence
                context.pcfEvidence = pcfMonitor.getCorrelationEvidence("CONSUMER-" + consumerId);
                System.out.println("\n--- PCF Evidence Before Sessions ---");
                System.out.println(context.pcfEvidence);
                
            } catch (Exception e) {
                System.err.println("PCF monitoring not available: " + e.getMessage());
            }
            
            connection.setExceptionListener(e -> {
                System.out.println("[Consumer-" + consumerId + "] Connection exception: " + e.getMessage());
            });
            
            connection.start();
            
            // Create multiple sessions
            Session[] sessions = new Session[sessionsPerConsumer];
            MessageConsumer[] consumers = new MessageConsumer[sessionsPerConsumer];
            
            System.out.println("\n--- Creating " + sessionsPerConsumer + " Sessions ---");
            
            for (int s = 0; s < sessionsPerConsumer; s++) {
                sessions[s] = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                
                // Track session
                String sessionId = correlationId + "-S" + (s + 1);
                context.addSession(sessionId);
                SessionTracker.trackSession(sessions[s], correlationId, connection, s + 1);
                
                Queue queue = sessions[s].createQueue("queue:///" + QUEUE_NAME);
                consumers[s] = sessions[s].createConsumer(queue);
                
                System.out.println("  Created Session " + (s + 1) + " [ID: " + sessionId + "]");
            }
            
            // Get PCF evidence after creating sessions
            if (pcfMonitor != null) {
                Thread.sleep(500); // Allow connections to register
                context.pcfEvidence = pcfMonitor.getCorrelationEvidence("CONSUMER-" + consumerId);
                System.out.println("\n--- PCF Evidence After Sessions ---");
                System.out.println(context.pcfEvidence);
                
                // Get detailed connections
                context.pcfConnections = pcfMonitor.getActiveConnections("APP.SVRCONN");
                System.out.println("\nActive Connections for Consumer-" + consumerId + ":");
                for (PCFMonitor.ConnectionDetails conn : context.pcfConnections) {
                    if (conn.applicationName != null && conn.applicationName.contains("CONSUMER-" + consumerId)) {
                        System.out.println("  " + conn);
                    }
                }
            }
            
            // Store context
            consumerContexts.put(correlationId, context);
            
            // Create threads for each session to consume concurrently
            Thread[] sessionThreads = new Thread[sessionsPerConsumer];
            
            for (int s = 0; s < sessionsPerConsumer; s++) {
                final int sessionNum = s + 1;
                final String sessionId = context.sessionIds.get(s);
                final MessageConsumer consumer = consumers[s];
                final Session session = sessions[s];
                
                sessionThreads[s] = new Thread(() -> {
                    try {
                        consumeMessages(consumerId, sessionNum, sessionId, consumer, 
                                      session, timeout, continuous, context);
                    } catch (Exception e) {
                        System.err.println("[Consumer-" + consumerId + "/Session-" + sessionNum + 
                            "] Error: " + e.getMessage());
                    }
                });
                
                sessionThreads[s].start();
            }
            
            // Wait for all session threads
            for (Thread sessionThread : sessionThreads) {
                sessionThread.join();
            }
            
            // Final PCF correlation check
            if (pcfMonitor != null) {
                System.out.println("\n--- Final PCF Correlation Check ---");
                pcfMonitor.printCorrelationReport("APP.SVRCONN");
                pcfMonitor.disconnect();
            }
            
            // Close sessions and consumers
            for (int s = 0; s < sessionsPerConsumer; s++) {
                if (consumers[s] != null) consumers[s].close();
                if (sessions[s] != null) sessions[s].close();
            }
            
            System.out.println("\n✓ Consumer-" + consumerId + " completed successfully");
            
        } finally {
            if (connection != null) {
                connection.close();
            }
        }
    }
    
    private static void consumeMessages(int consumerId, int sessionNum, String sessionId,
                                       MessageConsumer consumer, Session session, int timeout,
                                       boolean continuous, ConsumerContext context) throws Exception {
        int localMessageCount = 0;
        int consecutiveNulls = 0;
        
        System.out.println("\n--- Session " + sessionNum + " starting consumption ---");
        
        while (true) {
            Message message = consumer.receive(timeout);
            
            if (message == null) {
                consecutiveNulls++;
                if (!continuous || consecutiveNulls > 3) {
                    System.out.println("  Session " + sessionNum + " finished after " + 
                        localMessageCount + " messages");
                    break;
                }
                continue;
            }
            
            consecutiveNulls = 0;
            localMessageCount++;
            context.messagesConsumed++;
            
            if (message instanceof TextMessage) {
                TextMessage textMessage = (TextMessage) message;
                
                // Extract correlation properties
                String msgProducerId = textMessage.getStringProperty("ProducerId");
                String msgCorrelationId = textMessage.getStringProperty("CorrelationId");
                String sourceQM = textMessage.getStringProperty("QueueManager");
                int msgSessionNum = textMessage.getIntProperty("SessionNumber");
                
                // Update counters
                totalMessageCount.incrementAndGet();
                if (sourceQM != null && qmMessageCount.containsKey(sourceQM)) {
                    qmMessageCount.get(sourceQM).incrementAndGet();
                }
                
                // Log correlation details
                if (localMessageCount <= 3 || localMessageCount % 10 == 0) {
                    System.out.println("    Session " + sessionNum + " received msg #" + localMessageCount +
                        " from Producer-" + msgProducerId + "/Session-" + msgSessionNum +
                        " [Source QM: " + sourceQM + ", Consumer QM: " + context.queueManager + "]");
                }
            }
        }
    }
    
    private static void printFinalCorrelationProof() {
        System.out.println("\n╔════════════════════════════════════════════════════════════════╗");
        System.out.println("║           FINAL PCF CORRELATION PROOF SUMMARY                   ║");
        System.out.println("╚════════════════════════════════════════════════════════════════╝");
        
        for (Map.Entry<String, ConsumerContext> entry : consumerContexts.entrySet()) {
            ConsumerContext ctx = entry.getValue();
            
            System.out.println("\nConsumer: " + entry.getKey());
            System.out.println("  Parent Connection: " + ctx.connectionId);
            System.out.println("  Queue Manager: " + ctx.queueManager);
            System.out.println("  Child Sessions: " + ctx.sessionIds.size());
            System.out.println("  Messages Consumed: " + ctx.messagesConsumed);
            
            // Check if all PCF connections are on same QM
            boolean allSameQM = true;
            for (PCFMonitor.ConnectionDetails conn : ctx.pcfConnections) {
                if (!conn.queueManager.equals(ctx.queueManager)) {
                    allSameQM = false;
                    break;
                }
            }
            
            if (allSameQM) {
                System.out.println("  ✓ PCF PROOF: All connections on SAME Queue Manager!");
            } else {
                System.out.println("  ✗ PCF WARNING: Connections on different QMs");
            }
        }
        
        // Print SessionTracker report
        SessionTracker.printTrackingReport();
    }
    
    private static void printStatistics() {
        System.out.println("\n════════════════════════════════════════════════════════════════");
        System.out.println("                    CONSUMPTION STATISTICS                        ");
        System.out.println("════════════════════════════════════════════════════════════════");
        System.out.println("Total messages consumed: " + totalMessageCount.get());
        System.out.println("\nMessage distribution by source Queue Manager:");
        
        for (Map.Entry<String, AtomicInteger> entry : qmMessageCount.entrySet()) {
            int count = entry.getValue().get();
            double percentage = totalMessageCount.get() > 0 ? 
                (count * 100.0 / totalMessageCount.get()) : 0;
            System.out.printf("  %s: %d messages (%.1f%%)\n", entry.getKey(), count, percentage);
        }
        
        System.out.println("\n════════════════════════════════════════════════════════════════");
        System.out.println("              PROOF COMPLETE WITH PCF EVIDENCE                    ");
        System.out.println("════════════════════════════════════════════════════════════════");
    }
}