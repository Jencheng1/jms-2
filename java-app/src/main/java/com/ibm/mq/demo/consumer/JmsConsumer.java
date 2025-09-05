package com.ibm.mq.demo.consumer;

import com.ibm.mq.demo.utils.ConnectionInfo;
import com.ibm.mq.demo.utils.MQConnectionFactory;
import javax.jms.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class JmsConsumer {
    private static final String QUEUE_NAME = "UNIFORM.QUEUE";
    private static final AtomicInteger totalMessageCount = new AtomicInteger(0);
    private static final Map<String, AtomicInteger> qmMessageCount = new HashMap<>();
    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    
    public static void main(String[] args) {
        int numberOfConsumers = 3;
        int receiveTimeout = 5000; // milliseconds
        boolean continuous = false;
        
        if (args.length > 0) {
            numberOfConsumers = Integer.parseInt(args[0]);
        }
        if (args.length > 1) {
            receiveTimeout = Integer.parseInt(args[1]);
        }
        if (args.length > 2) {
            continuous = Boolean.parseBoolean(args[2]);
        }
        
        System.out.println("========================================");
        System.out.println("IBM MQ Uniform Cluster Consumer Demo");
        System.out.println("========================================");
        System.out.println("Number of consumers: " + numberOfConsumers);
        System.out.println("Receive timeout: " + receiveTimeout + "ms");
        System.out.println("Continuous mode: " + continuous);
        System.out.println("Target Queue: " + QUEUE_NAME);
        System.out.println("========================================\n");
        
        // Initialize QM counters
        qmMessageCount.put("QM1", new AtomicInteger(0));
        qmMessageCount.put("QM2", new AtomicInteger(0));
        qmMessageCount.put("QM3", new AtomicInteger(0));
        
        // Create multiple consumer threads to demonstrate session distribution
        Thread[] consumers = new Thread[numberOfConsumers];
        
        for (int i = 0; i < numberOfConsumers; i++) {
            final int consumerId = i + 1;
            final int timeout = receiveTimeout;
            final boolean cont = continuous;
            
            consumers[i] = new Thread(() -> {
                try {
                    runConsumer(consumerId, timeout, cont);
                } catch (Exception e) {
                    System.err.println("Consumer " + consumerId + " failed: " + e.getMessage());
                    e.printStackTrace();
                }
            });
            consumers[i].setName("Consumer-" + consumerId);
            consumers[i].start();
        }
        
        // Add shutdown hook for graceful termination
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down consumers...");
            printStatistics();
        }));
        
        // Wait for all consumers to complete
        for (Thread consumer : consumers) {
            try {
                consumer.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        
        printStatistics();
    }
    
    private static void runConsumer(int consumerId, int timeout, boolean continuous) throws Exception {
        ConnectionFactory connectionFactory = MQConnectionFactory.createConnectionFactory();
        
        try (Connection connection = connectionFactory.createConnection("app", "passw0rd")) {
            // Get connection metadata
            ConnectionInfo connInfo = new ConnectionInfo(connection);
            String queueManager = connInfo.getConnectedQueueManager();
            
            System.out.println("[Consumer-" + consumerId + "] Connected to Queue Manager: " + queueManager);
            System.out.println("[Consumer-" + consumerId + "] Client ID: " + connection.getClientID());
            
            // Set exception listener for reconnection events
            connection.setExceptionListener(e -> {
                System.out.println("[Consumer-" + consumerId + "] Connection exception: " + e.getMessage());
                System.out.println("[Consumer-" + consumerId + "] Will attempt automatic reconnection...");
            });
            
            connection.start();
            
            try (Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                Queue queue = session.createQueue("queue:///" + QUEUE_NAME);
                
                try (MessageConsumer consumer = session.createConsumer(queue)) {
                    int localMessageCount = 0;
                    int consecutiveNulls = 0;
                    
                    while (true) {
                        Message message = consumer.receive(timeout);
                        
                        if (message == null) {
                            consecutiveNulls++;
                            if (!continuous || consecutiveNulls > 3) {
                                System.out.println("[Consumer-" + consumerId + "] No more messages after " + 
                                    consecutiveNulls + " attempts. Exiting.");
                                break;
                            }
                            System.out.println("[Consumer-" + consumerId + "] Waiting for messages... (attempt " + 
                                consecutiveNulls + ")");
                            continue;
                        }
                        
                        consecutiveNulls = 0;
                        localMessageCount++;
                        
                        if (message instanceof TextMessage) {
                            TextMessage textMessage = (TextMessage) message;
                            String text = textMessage.getText();
                            String producerId = textMessage.getStringProperty("ProducerId");
                            String sourceQM = textMessage.getStringProperty("QueueManager");
                            int sequenceNumber = textMessage.getIntProperty("SequenceNumber");
                            
                            // Update counters
                            totalMessageCount.incrementAndGet();
                            if (sourceQM != null && qmMessageCount.containsKey(sourceQM)) {
                                qmMessageCount.get(sourceQM).incrementAndGet();
                            }
                            
                            // Log every 100th message or if it's one of the first 10
                            if (localMessageCount <= 10 || localMessageCount % 100 == 0) {
                                System.out.println("[Consumer-" + consumerId + "] Received message #" + 
                                    localMessageCount + " from Producer-" + producerId + 
                                    " (Seq: " + sequenceNumber + ") via " + sourceQM + 
                                    " [Connected to: " + queueManager + "]");
                            }
                        }
                    }
                    
                    System.out.println("[Consumer-" + consumerId + "] Completed. Received " + 
                        localMessageCount + " messages from " + queueManager);
                }
            }
        }
    }
    
    private static void printStatistics() {
        System.out.println("\n========================================");
        System.out.println("Consumer Statistics");
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
}