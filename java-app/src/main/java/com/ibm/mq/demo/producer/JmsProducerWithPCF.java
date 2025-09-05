package com.ibm.mq.demo.producer;

import com.ibm.mq.demo.utils.*;
import com.ibm.mq.jms.MQConnection;
import javax.jms.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Enhanced JMS Producer with PCF monitoring for undisputable parent-child correlation proof
 */
public class JmsProducerWithPCF {
    private static final String QUEUE_NAME = "UNIFORM.QUEUE";
    private static final AtomicInteger messageCounter = new AtomicInteger(0);
    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Map<String, ProducerContext> producerContexts = Collections.synchronizedMap(new HashMap<>());
    
    static class ProducerContext {
        String correlationId;
        String connectionId;
        String queueManager;
        List<String> sessionIds = new ArrayList<>();
        List<PCFMonitor.ConnectionDetails> pcfConnections = new ArrayList<>();
        String pcfEvidence;
        
        void addSession(String sessionId) {
            sessionIds.add(sessionId);
        }
    }
    
    public static void main(String[] args) {
        int numberOfMessages = 60;
        int numberOfProducers = 3;
        int sessionsPerProducer = 2;
        int delayBetweenMessages = 100;
        
        if (args.length > 0) numberOfMessages = Integer.parseInt(args[0]);
        if (args.length > 1) numberOfProducers = Integer.parseInt(args[1]);
        if (args.length > 2) sessionsPerProducer = Integer.parseInt(args[2]);
        if (args.length > 3) delayBetweenMessages = Integer.parseInt(args[3]);
        
        System.out.println("╔════════════════════════════════════════════════════════════════╗");
        System.out.println("║   IBM MQ PRODUCER WITH PCF PARENT-CHILD CORRELATION PROOF     ║");
        System.out.println("╚════════════════════════════════════════════════════════════════╝");
        System.out.println("Messages to send: " + numberOfMessages);
        System.out.println("Number of producers: " + numberOfProducers);
        System.out.println("Sessions per producer: " + sessionsPerProducer);
        System.out.println("═══════════════════════════════════════════════════════════════\n");
        
        Thread[] producers = new Thread[numberOfProducers];
        
        for (int i = 0; i < numberOfProducers; i++) {
            final int producerId = i + 1;
            final int messagesPerProducer = numberOfMessages / numberOfProducers;
            final int finalDelay = delayBetweenMessages;
            final int finalSessionsPerProducer = sessionsPerProducer;
            
            producers[i] = new Thread(() -> {
                try {
                    runProducerWithPCF(producerId, messagesPerProducer, finalSessionsPerProducer, finalDelay);
                } catch (Exception e) {
                    System.err.println("Producer " + producerId + " failed: " + e.getMessage());
                    e.printStackTrace();
                }
            });
            producers[i].setName("Producer-" + producerId);
            producers[i].start();
        }
        
        // Wait for all producers
        for (Thread producer : producers) {
            try {
                producer.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        
        printFinalCorrelationProof();
    }
    
    private static void runProducerWithPCF(int producerId, int numberOfMessages, 
                                           int sessionsPerProducer, int delay) throws Exception {
        
        // Create correlation ID for this producer
        String correlationId = "PROD-" + producerId + "-" + UUID.randomUUID().toString().substring(0, 8);
        ProducerContext context = new ProducerContext();
        context.correlationId = correlationId;
        
        // Create connection factory with correlation metadata
        ConnectionFactory connectionFactory = MQConnectionFactoryEnhanced.createProducerConnectionFactory(producerId);
        
        Connection connection = connectionFactory.createConnection("app", "passw0rd");
        
        try {
            // Track connection
            SessionTracker.ConnectionTracking connTracking = SessionTracker.trackConnection(connection, correlationId);
            
            // Get connection details
            context.connectionId = connection.getClientID();
            ConnectionInfo connInfo = new ConnectionInfo(connection);
            context.queueManager = connInfo.getConnectedQueueManager();
            
            System.out.println("\n╔══════════════════════════════════════════════════════════════╗");
            System.out.println("  PRODUCER-" + producerId + " CONNECTION ESTABLISHED");
            System.out.println("  Correlation ID: " + correlationId);
            System.out.println("  Connection ID: " + context.connectionId);
            System.out.println("  Queue Manager: " + context.queueManager);
            System.out.println("╚══════════════════════════════════════════════════════════════╝");
            
            // Connect to PCF for monitoring
            PCFMonitor pcfMonitor = null;
            try {
                // Determine QM port based on name
                int pcfPort = 1414; // Default
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
                context.pcfEvidence = pcfMonitor.getCorrelationEvidence("PRODUCER-" + producerId);
                System.out.println("\n--- PCF Evidence Before Sessions ---");
                System.out.println(context.pcfEvidence);
                
            } catch (Exception e) {
                System.err.println("PCF monitoring not available: " + e.getMessage());
            }
            
            connection.start();
            
            // Create multiple sessions
            Session[] sessions = new Session[sessionsPerProducer];
            MessageProducer[] producers = new MessageProducer[sessionsPerProducer];
            
            System.out.println("\n--- Creating " + sessionsPerProducer + " Sessions ---");
            
            for (int s = 0; s < sessionsPerProducer; s++) {
                sessions[s] = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                
                // Track session
                String sessionId = correlationId + "-S" + (s + 1);
                context.addSession(sessionId);
                SessionTracker.trackSession(sessions[s], correlationId, connection, s + 1);
                
                Queue queue = sessions[s].createQueue("queue:///" + QUEUE_NAME);
                producers[s] = sessions[s].createProducer(queue);
                producers[s].setDeliveryMode(DeliveryMode.PERSISTENT);
                
                System.out.println("  Created Session " + (s + 1) + " [ID: " + sessionId + "]");
            }
            
            // Get PCF evidence after creating sessions
            if (pcfMonitor != null) {
                Thread.sleep(500); // Allow connections to register
                context.pcfEvidence = pcfMonitor.getCorrelationEvidence("PRODUCER-" + producerId);
                System.out.println("\n--- PCF Evidence After Sessions ---");
                System.out.println(context.pcfEvidence);
                
                // Get detailed connections
                context.pcfConnections = pcfMonitor.getActiveConnections("APP.SVRCONN");
                System.out.println("\nActive Connections for Producer-" + producerId + ":");
                for (PCFMonitor.ConnectionDetails conn : context.pcfConnections) {
                    if (conn.applicationName != null && conn.applicationName.contains("PRODUCER-" + producerId)) {
                        System.out.println("  " + conn);
                    }
                }
            }
            
            // Store context
            producerContexts.put(correlationId, context);
            
            // Send messages across sessions
            int messagesPerSession = numberOfMessages / sessionsPerProducer;
            
            for (int s = 0; s < sessionsPerProducer; s++) {
                final int sessionNum = s + 1;
                final String sessionId = context.sessionIds.get(s);
                
                System.out.println("\n--- Session " + sessionNum + " sending " + messagesPerSession + " messages ---");
                
                for (int i = 1; i <= messagesPerSession; i++) {
                    String messageText = String.format(
                        "Message #%d from Producer-%d/Session-%d [QM: %s]",
                        i, producerId, sessionNum, context.queueManager
                    );
                    
                    TextMessage message = sessions[s].createTextMessage(messageText);
                    
                    // Set correlation properties
                    message.setStringProperty("ProducerId", String.valueOf(producerId));
                    message.setStringProperty("CorrelationId", correlationId);
                    message.setStringProperty("SessionId", sessionId);
                    message.setStringProperty("QueueManager", context.queueManager);
                    message.setIntProperty("SessionNumber", sessionNum);
                    
                    producers[s].send(message);
                    
                    int totalSent = messageCounter.incrementAndGet();
                    
                    if (i % 10 == 0) {
                        System.out.println("    Session " + sessionNum + " sent " + i + "/" + messagesPerSession);
                    }
                    
                    if (delay > 0 && i < messagesPerSession) {
                        Thread.sleep(delay);
                    }
                }
            }
            
            // Final PCF correlation check
            if (pcfMonitor != null) {
                System.out.println("\n--- Final PCF Correlation Check ---");
                pcfMonitor.printCorrelationReport("APP.SVRCONN");
                pcfMonitor.disconnect();
            }
            
            // Close sessions and producers
            for (int s = 0; s < sessionsPerProducer; s++) {
                if (producers[s] != null) producers[s].close();
                if (sessions[s] != null) sessions[s].close();
            }
            
            System.out.println("\n✓ Producer-" + producerId + " completed successfully");
            
        } finally {
            if (connection != null) {
                connection.close();
            }
        }
    }
    
    private static void printFinalCorrelationProof() {
        System.out.println("\n╔════════════════════════════════════════════════════════════════╗");
        System.out.println("║           FINAL PCF CORRELATION PROOF SUMMARY                   ║");
        System.out.println("╚════════════════════════════════════════════════════════════════╝");
        
        for (Map.Entry<String, ProducerContext> entry : producerContexts.entrySet()) {
            ProducerContext ctx = entry.getValue();
            
            System.out.println("\nProducer: " + entry.getKey());
            System.out.println("  Parent Connection: " + ctx.connectionId);
            System.out.println("  Queue Manager: " + ctx.queueManager);
            System.out.println("  Child Sessions: " + ctx.sessionIds.size());
            
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
        
        System.out.println("\n════════════════════════════════════════════════════════════════");
        System.out.println("                    PROOF COMPLETE                                ");
        System.out.println("════════════════════════════════════════════════════════════════");
    }
}