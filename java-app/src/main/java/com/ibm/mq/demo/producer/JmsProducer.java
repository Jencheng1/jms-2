package com.ibm.mq.demo.producer;

import com.ibm.mq.demo.utils.ConnectionInfo;
import com.ibm.mq.demo.utils.MQConnectionFactory;
import javax.jms.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

public class JmsProducer {
    private static final String QUEUE_NAME = "UNIFORM.QUEUE";
    private static final AtomicInteger messageCounter = new AtomicInteger(0);
    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    
    public static void main(String[] args) {
        int numberOfMessages = 1000;
        int numberOfProducers = 3;
        int delayBetweenMessages = 100; // milliseconds
        
        if (args.length > 0) {
            numberOfMessages = Integer.parseInt(args[0]);
        }
        if (args.length > 1) {
            numberOfProducers = Integer.parseInt(args[1]);
        }
        if (args.length > 2) {
            delayBetweenMessages = Integer.parseInt(args[2]);
        }
        
        System.out.println("========================================");
        System.out.println("IBM MQ Uniform Cluster Producer Demo");
        System.out.println("========================================");
        System.out.println("Messages to send: " + numberOfMessages);
        System.out.println("Number of producers: " + numberOfProducers);
        System.out.println("Delay between messages: " + delayBetweenMessages + "ms");
        System.out.println("Target Queue: " + QUEUE_NAME);
        System.out.println("========================================\n");
        
        // Create multiple producer threads to demonstrate connection distribution
        Thread[] producers = new Thread[numberOfProducers];
        
        for (int i = 0; i < numberOfProducers; i++) {
            final int producerId = i + 1;
            final int messagesPerProducer = numberOfMessages / numberOfProducers;
            final int finalDelay = delayBetweenMessages;
            
            producers[i] = new Thread(() -> {
                try {
                    runProducer(producerId, messagesPerProducer, finalDelay);
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
        
        System.out.println("\n========================================");
        System.out.println("All producers completed!");
        System.out.println("Total messages sent: " + messageCounter.get());
        System.out.println("========================================");
    }
    
    private static void runProducer(int producerId, int numberOfMessages, int delay) throws Exception {
        ConnectionFactory connectionFactory = MQConnectionFactory.createConnectionFactory();
        
        try (Connection connection = connectionFactory.createConnection("app", "passw0rd")) {
            // Get connection metadata to show which QM we're connected to
            ConnectionInfo connInfo = new ConnectionInfo(connection);
            String queueManager = connInfo.getConnectedQueueManager();
            
            System.out.println("[Producer-" + producerId + "] Connected to Queue Manager: " + queueManager);
            System.out.println("[Producer-" + producerId + "] Client ID: " + connection.getClientID());
            
            connection.start();
            
            try (Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                Queue queue = session.createQueue("queue:///" + QUEUE_NAME);
                
                try (MessageProducer producer = session.createProducer(queue)) {
                    producer.setDeliveryMode(DeliveryMode.PERSISTENT);
                    
                    for (int i = 1; i <= numberOfMessages; i++) {
                        String timestamp = sdf.format(new Date());
                        String messageText = String.format("Message #%d from Producer-%d at %s [QM: %s]", 
                            i, producerId, timestamp, queueManager);
                        
                        TextMessage message = session.createTextMessage(messageText);
                        message.setStringProperty("ProducerId", String.valueOf(producerId));
                        message.setStringProperty("QueueManager", queueManager);
                        message.setLongProperty("Timestamp", System.currentTimeMillis());
                        message.setIntProperty("SequenceNumber", i);
                        
                        producer.send(message);
                        
                        int totalSent = messageCounter.incrementAndGet();
                        
                        if (i % 100 == 0) {
                            System.out.println("[Producer-" + producerId + "] Sent " + i + " messages to " + queueManager + 
                                " (Total: " + totalSent + ")");
                        }
                        
                        if (delay > 0 && i < numberOfMessages) {
                            Thread.sleep(delay);
                        }
                    }
                    
                    System.out.println("[Producer-" + producerId + "] Completed sending " + numberOfMessages + 
                        " messages to " + queueManager);
                }
            }
        }
    }
}