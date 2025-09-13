package com.ibm.mq.failover.listener;

import com.ibm.mq.failover.service.ConnectionTrackingService;
import com.ibm.mq.jms.MQConnection;
import com.ibm.mq.jms.MQSession;
import com.ibm.msg.client.jms.JmsPropertyContext;
import com.ibm.msg.client.wmq.WMQConstants;
import jakarta.jms.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.listener.SessionAwareMessageListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class FailoverMessageListener implements SessionAwareMessageListener<Message> {
    
    private final ConnectionTrackingService trackingService;
    private final ConcurrentHashMap<String, String> sessionToConnectionMap = new ConcurrentHashMap<>();
    private final AtomicInteger messageCount = new AtomicInteger(0);
    private final AtomicInteger errorCount = new AtomicInteger(0);
    
    @Override
    @JmsListener(destination = "${ibm.mq.test-queue}", containerFactory = "mqListenerContainerFactory")
    public void onMessage(Message message, Session session) throws JMSException {
        String threadName = Thread.currentThread().getName();
        long threadId = Thread.currentThread().getId();
        int msgNum = messageCount.incrementAndGet();
        
        try {
            // Extract connection and session information
            String connectionId = extractConnectionId(session);
            String sessionConnTag = extractSessionConnTag(session);
            String queueManager = extractQueueManager(session);
            
            log.info("[MSG-{}] Received message on thread: {} (ID: {})", msgNum, threadName, threadId);
            log.info("[MSG-{}] Session QM: {}, CONNTAG: {}, ConnectionID: {}", 
                msgNum, queueManager, sessionConnTag, connectionId);
            
            // Track session-to-connection mapping
            sessionToConnectionMap.put(sessionConnTag, connectionId);
            
            // Process the message
            if (message instanceof TextMessage) {
                TextMessage textMessage = (TextMessage) message;
                String content = textMessage.getText();
                log.info("[MSG-{}] Message content: {}", msgNum, content);
                
                // Simulate processing
                processMessage(content, session);
            }
            
            // Acknowledge if client acknowledge mode
            if (session.getAcknowledgeMode() == Session.CLIENT_ACKNOWLEDGE) {
                message.acknowledge();
                log.debug("[MSG-{}] Message acknowledged", msgNum);
            }
            
        } catch (JMSException e) {
            errorCount.incrementAndGet();
            log.error("[MSG-{}] Error processing message: {}", msgNum, e.getMessage(), e);
            
            // Check if this is a connection failure
            if (isConnectionError(e)) {
                log.warn("[MSG-{}] Connection error detected, session will be reestablished", msgNum);
                handleConnectionFailure(session);
            }
            
            throw e; // Rethrow to trigger retry/DLQ handling
        }
    }
    
    private void processMessage(String content, Session session) throws JMSException {
        // Simulate message processing with potential failure scenarios
        if (content.contains("FAIL_SESSION")) {
            log.warn("Simulating session failure for testing");
            throw new JMSException("Simulated session failure");
        }
        
        if (content.contains("SLOW_PROCESS")) {
            log.info("Simulating slow processing");
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        // Normal processing
        log.info("Message processed successfully: {}", content);
    }
    
    private String extractConnectionId(Session session) {
        try {
            if (session instanceof MQSession) {
                MQSession mqSession = (MQSession) session;
                JmsPropertyContext context = mqSession.getPropertyContext();
                return context.getStringProperty(WMQConstants.JMS_IBM_CONNECTION_ID);
            }
        } catch (Exception e) {
            log.debug("Could not extract connection ID: {}", e.getMessage());
        }
        return "UNKNOWN";
    }
    
    private String extractSessionConnTag(Session session) {
        try {
            if (session instanceof MQSession) {
                MQSession mqSession = (MQSession) session;
                JmsPropertyContext context = mqSession.getPropertyContext();
                String tag = context.getStringProperty(WMQConstants.JMS_IBM_CONNECTION_TAG);
                if (tag != null) {
                    return tag;
                }
            }
        } catch (Exception e) {
            log.debug("Could not extract session CONNTAG: {}", e.getMessage());
        }
        return "UNKNOWN";
    }
    
    private String extractQueueManager(Session session) {
        try {
            if (session instanceof MQSession) {
                MQSession mqSession = (MQSession) session;
                JmsPropertyContext context = mqSession.getPropertyContext();
                String qm = context.getStringProperty(WMQConstants.JMS_IBM_RESOLVED_QUEUE_MANAGER);
                if (qm != null && !qm.trim().isEmpty()) {
                    return qm.trim();
                }
            }
        } catch (Exception e) {
            log.debug("Could not extract queue manager: {}", e.getMessage());
        }
        return "UNKNOWN";
    }
    
    private boolean isConnectionError(JMSException e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        
        // Check for common connection error patterns
        return message.contains("MQRC_CONNECTION_BROKEN") ||
               message.contains("MQRC_CONNECTION_STOPPING") ||
               message.contains("MQRC_Q_MGR_NOT_AVAILABLE") ||
               message.contains("MQRC_CONNECTION_ERROR") ||
               message.contains("connection") && message.contains("lost");
    }
    
    private void handleConnectionFailure(Session session) {
        try {
            String sessionTag = extractSessionConnTag(session);
            String connectionId = sessionToConnectionMap.get(sessionTag);
            
            log.info("Handling connection failure for session: {}, connection: {}", 
                sessionTag, connectionId);
            
            // Update tracking service
            if (connectionId != null) {
                trackingService.updateConnectionStatus(connectionId, 
                    com.ibm.mq.failover.model.ConnectionInfo.ConnectionStatus.RECONNECTING);
            }
            
        } catch (Exception e) {
            log.error("Error handling connection failure", e);
        }
    }
    
    public void printStatistics() {
        log.info("=== Message Listener Statistics ===");
        log.info("Messages processed: {}", messageCount.get());
        log.info("Errors encountered: {}", errorCount.get());
        log.info("Active session mappings: {}", sessionToConnectionMap.size());
        log.info("Session-Connection mappings: {}", sessionToConnectionMap);
    }
}