package com.ibm.mq.failover.test;

import com.ibm.mq.failover.model.ConnectionInfo;
import com.ibm.mq.failover.model.SessionInfo;
import com.ibm.mq.failover.service.ConnTagCorrelationService;
import com.ibm.mq.failover.service.ConnectionTrackingService;
import com.ibm.mq.jms.MQConnectionFactory;
import jakarta.jms.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class FailoverTestService {
    
    private final MQConnectionFactory mqConnectionFactory;
    private final ConnectionTrackingService trackingService;
    private final ConnTagCorrelationService correlationService;
    
    @Value("${ibm.mq.test-queue}")
    private String testQueue;
    
    @Value("${failover.test.duration-seconds:180}")
    private int testDurationSeconds;
    
    @Value("${failover.test.simulate-failure-at-seconds:60}")
    private int failureAtSeconds;
    
    @Value("${ibm.mq.connection.pool.parent-connections:2}")
    private int parentConnections;
    
    @Value("${ibm.mq.connection.pool.sessions-per-connection:5}")
    private int sessionsPerConnection;
    
    private final Map<String, Connection> activeConnections = new ConcurrentHashMap<>();
    private final Map<String, List<Session>> connectionSessions = new ConcurrentHashMap<>();
    private final AtomicBoolean testRunning = new AtomicBoolean(false);
    private final AtomicInteger messagesSent = new AtomicInteger(0);
    private final AtomicInteger messagesReceived = new AtomicInteger(0);
    
    @Async
    public CompletableFuture<TestResult> runFailoverTest() {
        if (!testRunning.compareAndSet(false, true)) {
            log.warn("Test already running");
            return CompletableFuture.completedFuture(
                new TestResult(false, "Test already in progress"));
        }
        
        try {
            log.info("=== Starting Failover Test ===");
            log.info("Duration: {} seconds, Failure at: {} seconds", 
                testDurationSeconds, failureAtSeconds);
            log.info("Connections: {}, Sessions per connection: {}", 
                parentConnections, sessionsPerConnection);
            
            TestResult result = new TestResult();
            result.setStartTime(LocalDateTime.now());
            
            // Step 1: Create parent connections
            createParentConnections(result);
            
            // Step 2: Create child sessions
            createChildSessions(result);
            
            // Step 3: Start message producers
            CompletableFuture<Void> producerTask = startMessageProducers();
            
            // Step 4: Start message consumers
            CompletableFuture<Void> consumerTask = startMessageConsumers();
            
            // Step 5: Capture initial state
            captureConnectionState(result, "BEFORE_FAILOVER");
            
            // Step 6: Wait for failure point
            Thread.sleep(failureAtSeconds * 1000);
            
            // Step 7: Simulate session failure
            simulateSessionFailure(result);
            
            // Step 8: Wait for recovery
            Thread.sleep(10000); // 10 seconds for recovery
            
            // Step 9: Capture post-failure state
            captureConnectionState(result, "AFTER_FAILOVER");
            
            // Step 10: Verify coherence
            verifyFailoverCoherence(result);
            
            // Wait for remaining test duration
            int remainingTime = testDurationSeconds - failureAtSeconds - 10;
            if (remainingTime > 0) {
                Thread.sleep(remainingTime * 1000);
            }
            
            // Stop producers and consumers
            producerTask.cancel(true);
            consumerTask.cancel(true);
            
            result.setEndTime(LocalDateTime.now());
            result.setSuccess(true);
            result.setMessagesSent(messagesSent.get());
            result.setMessagesReceived(messagesReceived.get());
            
            log.info("=== Failover Test Completed ===");
            log.info("Result: {}", result);
            
            return CompletableFuture.completedFuture(result);
            
        } catch (Exception e) {
            log.error("Failover test failed", e);
            return CompletableFuture.completedFuture(
                new TestResult(false, "Test failed: " + e.getMessage()));
        } finally {
            testRunning.set(false);
            cleanup();
        }
    }
    
    private void createParentConnections(TestResult result) throws JMSException {
        log.info("Creating {} parent connections...", parentConnections);
        
        for (int i = 1; i <= parentConnections; i++) {
            String trackingKey = String.format("SPRING-TEST-%d-%d", 
                System.currentTimeMillis(), i);
            
            Connection connection = mqConnectionFactory.createConnection();
            connection.setClientID("CLIENT-" + i);
            connection.start();
            
            ConnectionInfo connInfo = trackingService.trackConnection(connection, trackingKey);
            activeConnections.put(connInfo.getConnectionId(), connection);
            
            result.addParentConnection(connInfo);
            
            log.info("Created parent connection {}: ID={}, QM={}, CONNTAG={}", 
                i, connInfo.getConnectionId(), 
                connInfo.getExtractedQueueManager(), 
                connInfo.getFullConnTag());
        }
    }
    
    private void createChildSessions(TestResult result) throws JMSException {
        log.info("Creating {} sessions per connection...", sessionsPerConnection);
        
        for (Map.Entry<String, Connection> entry : activeConnections.entrySet()) {
            String connectionId = entry.getKey();
            Connection connection = entry.getValue();
            List<Session> sessions = new ArrayList<>();
            
            for (int i = 1; i <= sessionsPerConnection; i++) {
                Session session = connection.createSession(true, Session.AUTO_ACKNOWLEDGE);
                sessions.add(session);
                
                SessionInfo sessionInfo = trackingService.trackSession(session, connectionId, i);
                result.addChildSession(sessionInfo);
                
                log.info("Created session {} for connection {}: QM={}, CONNTAG={}", 
                    i, connectionId, 
                    sessionInfo.getQueueManager(), 
                    sessionInfo.getFullConnTag());
            }
            
            connectionSessions.put(connectionId, sessions);
        }
    }
    
    @Async
    private CompletableFuture<Void> startMessageProducers() {
        return CompletableFuture.runAsync(() -> {
            try {
                for (Map.Entry<String, List<Session>> entry : connectionSessions.entrySet()) {
                    String connectionId = entry.getKey();
                    List<Session> sessions = entry.getValue();
                    
                    // Use first session for producing
                    Session session = sessions.get(0);
                    MessageProducer producer = session.createProducer(
                        session.createQueue(testQueue));
                    
                    // Send messages periodically
                    ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
                    executor.scheduleAtFixedRate(() -> {
                        try {
                            String messageText = String.format("MSG-%d-CONN-%s-TIME-%d",
                                messagesSent.incrementAndGet(),
                                connectionId,
                                System.currentTimeMillis());
                            
                            TextMessage message = session.createTextMessage(messageText);
                            producer.send(message);
                            session.commit();
                            
                            log.debug("Sent message: {}", messageText);
                        } catch (Exception e) {
                            log.error("Error sending message", e);
                        }
                    }, 0, 1, TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                log.error("Error starting producers", e);
            }
        });
    }
    
    @Async
    private CompletableFuture<Void> startMessageConsumers() {
        return CompletableFuture.runAsync(() -> {
            try {
                for (Map.Entry<String, List<Session>> entry : connectionSessions.entrySet()) {
                    List<Session> sessions = entry.getValue();
                    
                    // Use remaining sessions for consuming
                    for (int i = 1; i < sessions.size(); i++) {
                        Session session = sessions.get(i);
                        MessageConsumer consumer = session.createConsumer(
                            session.createQueue(testQueue));
                        
                        consumer.setMessageListener(message -> {
                            try {
                                messagesReceived.incrementAndGet();
                                if (message instanceof TextMessage) {
                                    String text = ((TextMessage) message).getText();
                                    log.debug("Received message: {}", text);
                                }
                                session.commit();
                            } catch (Exception e) {
                                log.error("Error processing message", e);
                            }
                        });
                    }
                }
            } catch (Exception e) {
                log.error("Error starting consumers", e);
            }
        });
    }
    
    private void simulateSessionFailure(TestResult result) {
        log.warn("=== SIMULATING SESSION FAILURE ===");
        
        try {
            // Select a random session to fail
            List<Session> allSessions = connectionSessions.values().stream()
                .flatMap(List::stream)
                .toList();
            
            if (!allSessions.isEmpty()) {
                int randomIndex = new Random().nextInt(allSessions.size());
                Session targetSession = allSessions.get(randomIndex);
                
                log.warn("Forcibly closing session at index {}", randomIndex);
                
                // Force close the session
                targetSession.close();
                
                // Simulate thread death
                Thread sessionThread = new Thread(() -> {
                    log.error("Session thread dying!");
                    throw new RuntimeException("Simulated session thread failure");
                });
                sessionThread.setName("Failed-Session-Thread");
                sessionThread.setUncaughtExceptionHandler((t, e) -> {
                    log.error("Thread {} died with exception: {}", t.getName(), e.getMessage());
                });
                sessionThread.start();
                
                result.addFailureEvent("Session forcibly closed at index " + randomIndex);
            }
            
            // Additionally, we can stop a queue manager if running locally
            String qmToStop = selectQueueManagerToStop();
            if (qmToStop != null) {
                log.warn("Stopping Queue Manager: {}", qmToStop);
                stopQueueManager(qmToStop);
                result.addFailureEvent("Queue Manager " + qmToStop + " stopped");
            }
            
        } catch (Exception e) {
            log.error("Error simulating failure", e);
        }
    }
    
    private String selectQueueManagerToStop() {
        // Find which QM has the most connections
        Map<String, Integer> qmCounts = new HashMap<>();
        for (ConnectionInfo conn : trackingService.getAllParentConnections()) {
            String qm = conn.getExtractedQueueManager();
            qmCounts.put(qm, qmCounts.getOrDefault(qm, 0) + 1);
        }
        
        // Return the QM with most connections
        return qmCounts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);
    }
    
    private void stopQueueManager(String qmName) {
        try {
            String containerName = qmName.toLowerCase();
            ProcessBuilder pb = new ProcessBuilder("docker", "stop", containerName);
            Process process = pb.start();
            boolean stopped = process.waitFor(10, TimeUnit.SECONDS);
            if (stopped) {
                log.info("Queue Manager {} stopped successfully", qmName);
            }
        } catch (Exception e) {
            log.error("Failed to stop Queue Manager {}", qmName, e);
        }
    }
    
    private void captureConnectionState(TestResult result, String phase) {
        log.info("=== Capturing Connection State: {} ===", phase);
        
        trackingService.printConnectionTable();
        correlationService.correlateConnTags();
        
        String tableSnapshot = trackingService.generateConnectionTable();
        result.addSnapshot(phase, tableSnapshot);
    }
    
    private void verifyFailoverCoherence(TestResult result) {
        log.info("=== Verifying Failover Coherence ===");
        
        for (ConnectionInfo parent : trackingService.getAllParentConnections()) {
            correlationService.verifyParentChildGrouping(parent.getConnectionId());
            
            boolean allOnSameQM = parent.getSessions().stream()
                .allMatch(s -> s.getQueueManager().equals(parent.getExtractedQueueManager()));
            
            if (allOnSameQM) {
                result.addCoherenceCheck(parent.getConnectionId(), true, 
                    "All sessions remained with parent on " + parent.getExtractedQueueManager());
            } else {
                result.addCoherenceCheck(parent.getConnectionId(), false, 
                    "Sessions split across different QMs!");
            }
        }
    }
    
    private void cleanup() {
        log.info("Cleaning up test resources...");
        
        // Close all sessions
        connectionSessions.values().forEach(sessions -> 
            sessions.forEach(session -> {
                try {
                    session.close();
                } catch (Exception e) {
                    log.debug("Error closing session", e);
                }
            }));
        
        // Close all connections
        activeConnections.values().forEach(connection -> {
            try {
                connection.close();
            } catch (Exception e) {
                log.debug("Error closing connection", e);
            }
        });
        
        activeConnections.clear();
        connectionSessions.clear();
        
        // Restart any stopped QMs
        restartQueueManagers();
    }
    
    private void restartQueueManagers() {
        try {
            for (String qm : Arrays.asList("qm1", "qm2", "qm3")) {
                ProcessBuilder pb = new ProcessBuilder("docker", "start", qm);
                pb.start();
            }
            Thread.sleep(5000); // Wait for QMs to start
        } catch (Exception e) {
            log.error("Error restarting queue managers", e);
        }
    }
}