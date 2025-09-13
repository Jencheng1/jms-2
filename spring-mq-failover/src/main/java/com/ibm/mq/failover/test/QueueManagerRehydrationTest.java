package com.ibm.mq.failover.test;

import com.ibm.mq.failover.model.ConnectionInfo;
import com.ibm.mq.failover.model.SessionInfo;
import com.ibm.mq.failover.service.ConnTagCorrelationService;
import com.ibm.mq.failover.service.ConnectionTrackingService;
import com.ibm.mq.jms.MQConnectionFactory;
import com.ibm.msg.client.wmq.WMQConstants;
import jakarta.jms.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class QueueManagerRehydrationTest {
    
    private final MQConnectionFactory mqConnectionFactory;
    private final ConnectionTrackingService trackingService;
    private final ConnTagCorrelationService correlationService;
    
    @Value("${ibm.mq.test-queue}")
    private String testQueue;
    
    /**
     * Test Queue Manager rehydration scenario:
     * 1. Create connections with sessions across multiple QMs
     * 2. Force a QM to go down (simulate failure)
     * 3. Monitor how sessions are redistributed
     * 4. Bring QM back up (rehydration)
     * 5. Verify if sessions rebalance or stay on failover QMs
     * 6. Track parent-child grouping throughout
     */
    public RehydrationTestResult runRehydrationTest() {
        log.info("=== Starting Queue Manager Rehydration Test ===");
        
        RehydrationTestResult result = new RehydrationTestResult();
        result.setStartTime(LocalDateTime.now());
        
        Map<String, Connection> connections = new HashMap<>();
        Map<String, List<Session>> sessionMap = new HashMap<>();
        
        try {
            // Phase 1: Initial Setup
            log.info("PHASE 1: Creating initial connections and sessions");
            result.addPhase("INITIAL_SETUP");
            
            for (int i = 1; i <= 3; i++) {
                String trackingKey = String.format("REHYDRATION-%d-%d", 
                    System.currentTimeMillis(), i);
                
                Connection conn = createTrackedConnection(trackingKey);
                String connId = extractConnectionId(conn);
                connections.put(connId, conn);
                
                List<Session> sessions = createTrackedSessions(conn, connId, 5);
                sessionMap.put(connId, sessions);
                
                result.recordInitialState(connId, 
                    extractQueueManager(conn), 
                    extractConnTag(conn));
            }
            
            // Capture initial distribution
            captureDistribution(result, "INITIAL");
            Thread.sleep(5000);
            
            // Phase 2: Identify target QM for failure
            String targetQM = identifyTargetQM(connections);
            log.info("PHASE 2: Target QM for failure: {}", targetQM);
            result.addPhase("QM_FAILURE");
            result.setTargetQM(targetQM);
            
            // Phase 3: Simulate QM failure
            log.warn("PHASE 3: Stopping Queue Manager {}", targetQM);
            stopQueueManager(targetQM);
            result.addEvent(String.format("Queue Manager %s stopped", targetQM));
            
            // Wait for failover
            Thread.sleep(10000);
            
            // Phase 4: Check redistribution
            log.info("PHASE 4: Checking redistribution after failure");
            result.addPhase("REDISTRIBUTION");
            
            Map<String, String> redistributedQMs = new HashMap<>();
            for (Map.Entry<String, Connection> entry : connections.entrySet()) {
                String connId = entry.getKey();
                Connection conn = entry.getValue();
                
                // Try to get current QM (may require recreating connection)
                String currentQM = attemptToGetCurrentQM(conn, connId);
                redistributedQMs.put(connId, currentQM);
                
                result.recordRedistribution(connId, currentQM);
            }
            
            captureDistribution(result, "POST_FAILURE");
            
            // Phase 5: Rehydrate (restart) the failed QM
            log.info("PHASE 5: Rehydrating Queue Manager {}", targetQM);
            result.addPhase("REHYDRATION");
            startQueueManager(targetQM);
            result.addEvent(String.format("Queue Manager %s restarted", targetQM));
            
            // Wait for QM to fully start
            Thread.sleep(15000);
            
            // Phase 6: Check if connections rebalance
            log.info("PHASE 6: Checking for rebalancing after rehydration");
            result.addPhase("REBALANCE_CHECK");
            
            // Force some activity to potentially trigger rebalancing
            sendTestMessages(sessionMap);
            
            Thread.sleep(10000);
            
            // Phase 7: Final state verification
            log.info("PHASE 7: Final state verification");
            result.addPhase("FINAL_VERIFICATION");
            
            for (Map.Entry<String, Connection> entry : connections.entrySet()) {
                String connId = entry.getKey();
                Connection conn = entry.getValue();
                
                String finalQM = attemptToGetCurrentQM(conn, connId);
                result.recordFinalState(connId, finalQM);
                
                // Verify parent-child coherence
                verifyParentChildCoherence(connId, sessionMap.get(connId), result);
            }
            
            captureDistribution(result, "FINAL");
            
            // Generate analysis
            result.analyzeRehydrationBehavior();
            
        } catch (Exception e) {
            log.error("Rehydration test failed", e);
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
        } finally {
            // Cleanup
            cleanup(connections, sessionMap);
            result.setEndTime(LocalDateTime.now());
        }
        
        return result;
    }
    
    private Connection createTrackedConnection(String trackingKey) throws JMSException {
        Connection conn = mqConnectionFactory.createConnection();
        conn.setClientID("REHYDRATION-" + UUID.randomUUID().toString());
        conn.start();
        
        ConnectionInfo connInfo = trackingService.trackConnection(conn, trackingKey);
        log.info("Created connection: {} on QM: {} with CONNTAG: {}", 
            connInfo.getConnectionId(), 
            connInfo.getExtractedQueueManager(),
            connInfo.getFullConnTag());
        
        return conn;
    }
    
    private List<Session> createTrackedSessions(Connection conn, String connId, int count) 
            throws JMSException {
        List<Session> sessions = new ArrayList<>();
        
        for (int i = 1; i <= count; i++) {
            Session session = conn.createSession(true, Session.AUTO_ACKNOWLEDGE);
            sessions.add(session);
            
            SessionInfo sessionInfo = trackingService.trackSession(session, connId, i);
            log.info("Created session {} for connection {}: QM={}", 
                i, connId, sessionInfo.getQueueManager());
        }
        
        return sessions;
    }
    
    private String extractConnectionId(Connection conn) {
        try {
            if (conn instanceof com.ibm.mq.jms.MQConnection) {
                com.ibm.mq.jms.MQConnection mqConn = (com.ibm.mq.jms.MQConnection) conn;
                return mqConn.getPropertyContext()
                    .getStringProperty(WMQConstants.JMS_IBM_CONNECTION_ID);
            }
        } catch (Exception e) {
            log.error("Error extracting connection ID", e);
        }
        return conn.toString();
    }
    
    private String extractQueueManager(Connection conn) {
        try {
            if (conn instanceof com.ibm.mq.jms.MQConnection) {
                com.ibm.mq.jms.MQConnection mqConn = (com.ibm.mq.jms.MQConnection) conn;
                String qm = mqConn.getPropertyContext()
                    .getStringProperty(WMQConstants.JMS_IBM_RESOLVED_QUEUE_MANAGER);
                if (qm != null && !qm.trim().isEmpty()) {
                    return qm.trim();
                }
            }
        } catch (Exception e) {
            log.error("Error extracting queue manager", e);
        }
        return "UNKNOWN";
    }
    
    private String extractConnTag(Connection conn) {
        try {
            if (conn instanceof com.ibm.mq.jms.MQConnection) {
                com.ibm.mq.jms.MQConnection mqConn = (com.ibm.mq.jms.MQConnection) conn;
                return mqConn.getPropertyContext()
                    .getStringProperty(WMQConstants.JMS_IBM_CONNECTION_TAG);
            }
        } catch (Exception e) {
            log.error("Error extracting CONNTAG", e);
        }
        return "UNKNOWN";
    }
    
    private String identifyTargetQM(Map<String, Connection> connections) {
        Map<String, Integer> qmCounts = new HashMap<>();
        
        for (Connection conn : connections.values()) {
            String qm = extractQueueManager(conn);
            qmCounts.put(qm, qmCounts.getOrDefault(qm, 0) + 1);
        }
        
        // Return QM with most connections
        return qmCounts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse("QM1");
    }
    
    private void stopQueueManager(String qmName) {
        try {
            String containerName = qmName.toLowerCase();
            ProcessBuilder pb = new ProcessBuilder("docker", "stop", containerName);
            Process process = pb.start();
            boolean stopped = process.waitFor(10, TimeUnit.SECONDS);
            if (stopped) {
                log.info("Queue Manager {} stopped", qmName);
            }
        } catch (Exception e) {
            log.error("Failed to stop Queue Manager {}", qmName, e);
        }
    }
    
    private void startQueueManager(String qmName) {
        try {
            String containerName = qmName.toLowerCase();
            ProcessBuilder pb = new ProcessBuilder("docker", "start", containerName);
            Process process = pb.start();
            boolean started = process.waitFor(10, TimeUnit.SECONDS);
            if (started) {
                log.info("Queue Manager {} started", qmName);
            }
        } catch (Exception e) {
            log.error("Failed to start Queue Manager {}", qmName, e);
        }
    }
    
    private String attemptToGetCurrentQM(Connection conn, String connId) {
        try {
            // Try to use existing connection
            String qm = extractQueueManager(conn);
            if (!"UNKNOWN".equals(qm)) {
                return qm;
            }
            
            // Connection might be stale, try creating a new session
            Session testSession = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
            if (testSession instanceof com.ibm.mq.jms.MQSession) {
                com.ibm.mq.jms.MQSession mqSession = (com.ibm.mq.jms.MQSession) testSession;
                qm = mqSession.getPropertyContext()
                    .getStringProperty(WMQConstants.JMS_IBM_RESOLVED_QUEUE_MANAGER);
                testSession.close();
                if (qm != null && !qm.trim().isEmpty()) {
                    return qm.trim();
                }
            }
        } catch (Exception e) {
            log.error("Error getting current QM for connection {}", connId, e);
        }
        return "FAILED";
    }
    
    private void sendTestMessages(Map<String, List<Session>> sessionMap) {
        AtomicInteger messageCount = new AtomicInteger(0);
        
        for (Map.Entry<String, List<Session>> entry : sessionMap.entrySet()) {
            String connId = entry.getKey();
            List<Session> sessions = entry.getValue();
            
            if (!sessions.isEmpty()) {
                try {
                    Session session = sessions.get(0);
                    MessageProducer producer = session.createProducer(
                        session.createQueue(testQueue));
                    
                    for (int i = 0; i < 5; i++) {
                        String text = String.format("REHYDRATION-TEST-MSG-%d-CONN-%s",
                            messageCount.incrementAndGet(), connId);
                        TextMessage message = session.createTextMessage(text);
                        producer.send(message);
                        session.commit();
                    }
                    
                    producer.close();
                    log.info("Sent {} test messages from connection {}", 5, connId);
                    
                } catch (Exception e) {
                    log.error("Error sending test messages for connection {}", connId, e);
                }
            }
        }
    }
    
    private void verifyParentChildCoherence(String connId, List<Session> sessions, 
                                           RehydrationTestResult result) {
        try {
            Set<String> sessionQMs = new HashSet<>();
            String parentQM = null;
            
            for (Session session : sessions) {
                if (session instanceof com.ibm.mq.jms.MQSession) {
                    com.ibm.mq.jms.MQSession mqSession = (com.ibm.mq.jms.MQSession) session;
                    String qm = mqSession.getPropertyContext()
                        .getStringProperty(WMQConstants.JMS_IBM_RESOLVED_QUEUE_MANAGER);
                    if (qm != null && !qm.trim().isEmpty()) {
                        sessionQMs.add(qm.trim());
                        if (parentQM == null) {
                            parentQM = qm.trim();
                        }
                    }
                }
            }
            
            boolean coherent = sessionQMs.size() <= 1;
            result.recordCoherence(connId, coherent, sessionQMs);
            
            if (coherent) {
                log.info("✅ Connection {} maintains coherence: All sessions on {}", 
                    connId, parentQM);
            } else {
                log.error("❌ Connection {} lost coherence: Sessions split across {}", 
                    connId, sessionQMs);
            }
            
        } catch (Exception e) {
            log.error("Error verifying coherence for connection {}", connId, e);
        }
    }
    
    private void captureDistribution(RehydrationTestResult result, String phase) {
        trackingService.printConnectionTable();
        correlationService.correlateConnTags();
        
        String snapshot = trackingService.generateConnectionTable();
        result.addSnapshot(phase, snapshot);
    }
    
    private void cleanup(Map<String, Connection> connections, 
                        Map<String, List<Session>> sessionMap) {
        // Close sessions
        sessionMap.values().forEach(sessions -> 
            sessions.forEach(session -> {
                try {
                    session.close();
                } catch (Exception e) {
                    log.debug("Error closing session", e);
                }
            }));
        
        // Close connections
        connections.values().forEach(conn -> {
            try {
                conn.close();
            } catch (Exception e) {
                log.debug("Error closing connection", e);
            }
        });
        
        // Ensure all QMs are running
        for (String qm : Arrays.asList("qm1", "qm2", "qm3")) {
            try {
                ProcessBuilder pb = new ProcessBuilder("docker", "start", qm);
                pb.start();
            } catch (Exception e) {
                log.debug("Error starting {}", qm);
            }
        }
    }
}