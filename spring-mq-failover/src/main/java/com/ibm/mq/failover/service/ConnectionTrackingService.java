package com.ibm.mq.failover.service;

import com.ibm.mq.failover.model.ConnectionInfo;
import com.ibm.mq.failover.model.SessionInfo;
import com.ibm.mq.jms.MQConnection;
import com.ibm.mq.jms.MQSession;
import com.ibm.msg.client.jms.JmsPropertyContext;
import com.ibm.msg.client.wmq.WMQConstants;
import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import jakarta.jms.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ConnectionTrackingService {
    
    private final Map<String, ConnectionInfo> parentConnections = new ConcurrentHashMap<>();
    private final Map<String, List<SessionInfo>> sessionsByConnection = new ConcurrentHashMap<>();
    private final AtomicInteger connectionCounter = new AtomicInteger(0);
    private final AtomicInteger sessionCounter = new AtomicInteger(0);
    
    public ConnectionInfo trackConnection(Connection connection, String trackingKey) {
        try {
            String connectionId = extractConnectionId(connection);
            String connTag = extractConnTag(connection);
            String queueManager = extractQueueManager(connection);
            
            ConnectionInfo info = ConnectionInfo.builder()
                .connectionId(connectionId)
                .connectionTag(connTag)
                .fullConnTag(extractFullConnTag(connection))
                .queueManager(queueManager)
                .applicationTag(trackingKey)
                .resolvedQueueManager(extractResolvedQueueManager(connection))
                .hostName(extractHostName(connection))
                .port(extractPort(connection))
                .createdAt(LocalDateTime.now())
                .lastUpdated(LocalDateTime.now())
                .status(ConnectionInfo.ConnectionStatus.CONNECTED)
                .isParent(true)
                .build();
            
            parentConnections.put(connectionId, info);
            sessionsByConnection.put(connectionId, new ArrayList<>());
            
            log.info("Tracked parent connection: ID={}, QM={}, CONNTAG={}, AppTag={}", 
                connectionId, queueManager, connTag, trackingKey);
            
            return info;
        } catch (Exception e) {
            log.error("Error tracking connection", e);
            return null;
        }
    }
    
    public SessionInfo trackSession(Session session, String parentConnectionId, int sessionNumber) {
        try {
            String sessionId = "SESSION-" + sessionCounter.incrementAndGet();
            String connTag = extractSessionConnTag(session);
            String queueManager = extractSessionQueueManager(session);
            
            SessionInfo info = SessionInfo.builder()
                .sessionId(sessionId)
                .parentConnectionId(parentConnectionId)
                .sessionTag(connTag)
                .fullConnTag(extractFullSessionConnTag(session))
                .queueManager(queueManager)
                .sessionNumber(sessionNumber)
                .createdAt(LocalDateTime.now())
                .status(SessionInfo.SessionStatus.ACTIVE)
                .threadName(Thread.currentThread().getName())
                .threadId(Thread.currentThread().getId())
                .transacted(session.getTransacted())
                .acknowledgeMode(session.getAcknowledgeMode())
                .build();
            
            List<SessionInfo> sessions = sessionsByConnection.computeIfAbsent(
                parentConnectionId, k -> new ArrayList<>());
            sessions.add(info);
            
            ConnectionInfo parent = parentConnections.get(parentConnectionId);
            if (parent != null) {
                parent.addSession(info);
            }
            
            log.info("Tracked session #{}: ParentID={}, QM={}, CONNTAG={}, Thread={}", 
                sessionNumber, parentConnectionId, queueManager, connTag, info.getThreadName());
            
            return info;
        } catch (Exception e) {
            log.error("Error tracking session", e);
            return null;
        }
    }
    
    private String extractConnectionId(Connection connection) throws JMSException {
        if (connection instanceof MQConnection) {
            MQConnection mqConn = (MQConnection) connection;
            JmsPropertyContext context = mqConn.getPropertyContext();
            return context.getStringProperty(WMQConstants.JMS_IBM_CONNECTION_ID);
        }
        return connection.getClientID() != null ? connection.getClientID() : 
            "CONN-" + connectionCounter.incrementAndGet();
    }
    
    private String extractConnTag(Connection connection) throws JMSException {
        // This method now delegates to extractFullConnTag for consistency
        return extractFullConnTag(connection);
    }
    
    private String extractFullConnTag(Connection connection) throws JMSException {
        if (connection instanceof MQConnection) {
            MQConnection mqConn = (MQConnection) connection;
            JmsPropertyContext context = mqConn.getPropertyContext();
            
            // CORRECT: Use JMS_IBM_CONNECTION_TAG for full CONNTAG
            // This gives us format: MQCT<handle><QM>_<timestamp>
            String fullConnTag = context.getStringProperty(WMQConstants.JMS_IBM_CONNECTION_TAG);
            
            if (fullConnTag != null && !fullConnTag.isEmpty()) {
                return fullConnTag;
            }
            
            // Fallback: Try without constant in case of version differences
            try {
                fullConnTag = context.getStringProperty("JMS_IBM_CONNECTION_TAG");
                if (fullConnTag != null && !fullConnTag.isEmpty()) {
                    return fullConnTag;
                }
            } catch (Exception e) {
                log.debug("Fallback CONNTAG extraction failed: {}", e.getMessage());
            }
        }
        return "UNKNOWN";
    }
    
    private String extractQueueManager(Connection connection) throws JMSException {
        if (connection instanceof MQConnection) {
            MQConnection mqConn = (MQConnection) connection;
            JmsPropertyContext context = mqConn.getPropertyContext();
            return context.getStringProperty(WMQConstants.JMS_IBM_MQMD_REPLYTOQMGR);
        }
        return "UNKNOWN";
    }
    
    private String extractResolvedQueueManager(Connection connection) throws JMSException {
        if (connection instanceof MQConnection) {
            MQConnection mqConn = (MQConnection) connection;
            JmsPropertyContext context = mqConn.getPropertyContext();
            String resolved = context.getStringProperty(WMQConstants.JMS_IBM_RESOLVED_QUEUE_MANAGER);
            if (resolved != null && !resolved.trim().isEmpty()) {
                return resolved.trim();
            }
        }
        return extractQueueManager(connection);
    }
    
    private String extractHostName(Connection connection) throws JMSException {
        if (connection instanceof MQConnection) {
            MQConnection mqConn = (MQConnection) connection;
            JmsPropertyContext context = mqConn.getPropertyContext();
            return context.getStringProperty(WMQConstants.JMS_IBM_HOST_NAME);
        }
        return "UNKNOWN";
    }
    
    private Integer extractPort(Connection connection) throws JMSException {
        if (connection instanceof MQConnection) {
            MQConnection mqConn = (MQConnection) connection;
            JmsPropertyContext context = mqConn.getPropertyContext();
            return context.getIntProperty(WMQConstants.JMS_IBM_PORT);
        }
        return 0;
    }
    
    private String extractSessionConnTag(Session session) throws JMSException {
        // This method now delegates to extractFullSessionConnTag for consistency
        return extractFullSessionConnTag(session);
    }
    
    private String extractFullSessionConnTag(Session session) throws JMSException {
        if (session instanceof MQSession) {
            MQSession mqSession = (MQSession) session;
            JmsPropertyContext context = mqSession.getPropertyContext();
            
            // CORRECT: Sessions inherit parent's CONNTAG
            // Use JMS_IBM_CONNECTION_TAG for full CONNTAG
            String fullConnTag = context.getStringProperty(WMQConstants.JMS_IBM_CONNECTION_TAG);
            
            if (fullConnTag != null && !fullConnTag.isEmpty()) {
                return fullConnTag;
            }
            
            // Fallback: Try without constant in case of version differences
            try {
                fullConnTag = context.getStringProperty("JMS_IBM_CONNECTION_TAG");
                if (fullConnTag != null && !fullConnTag.isEmpty()) {
                    return fullConnTag;
                }
            } catch (Exception e) {
                log.debug("Fallback session CONNTAG extraction failed: {}", e.getMessage());
            }
        }
        return "UNKNOWN";
    }
    
    private String extractSessionQueueManager(Session session) throws JMSException {
        if (session instanceof MQSession) {
            MQSession mqSession = (MQSession) session;
            JmsPropertyContext context = mqSession.getPropertyContext();
            String resolved = context.getStringProperty(WMQConstants.JMS_IBM_RESOLVED_QUEUE_MANAGER);
            if (resolved != null && !resolved.trim().isEmpty()) {
                return resolved.trim();
            }
        }
        return "UNKNOWN";
    }
    
    public Map<String, List<SessionInfo>> getSessionGroupsByConnection() {
        return new HashMap<>(sessionsByConnection);
    }
    
    public List<ConnectionInfo> getAllParentConnections() {
        return new ArrayList<>(parentConnections.values());
    }
    
    public void updateConnectionStatus(String connectionId, ConnectionInfo.ConnectionStatus status) {
        ConnectionInfo conn = parentConnections.get(connectionId);
        if (conn != null) {
            conn.setStatus(status);
            conn.setLastUpdated(LocalDateTime.now());
            log.info("Updated connection {} status to {}", connectionId, status);
        }
    }
    
    public void printConnectionTable() {
        log.info("\n{}", generateConnectionTable());
    }
    
    public String generateConnectionTable() {
        StringBuilder table = new StringBuilder();
        table.append("\n================== CONNECTION AND SESSION TRACKING TABLE ==================\n");
        table.append(String.format("| %-3s | %-7s | %-4s | %-7s | %-48s | %-50s | %-14s | %-25s |\n",
            "#", "Type", "Conn", "Session", "CONNECTION_ID", "FULL_CONNTAG", "Queue Manager", "APPLTAG"));
        table.append("|-----|---------|------|---------|--------------------------------------------------|" +
            "----------------------------------------------------|----------------|---------------------------|\n");
        
        int rowNum = 1;
        for (ConnectionInfo parent : parentConnections.values()) {
            // Parent connection row
            table.append(String.format("| %-3d | %-7s | C%-3d | %-7s | %-48s | %-50s | %-14s | %-25s |\n",
                rowNum++,
                "Parent",
                connectionCounter.get(),
                "-",
                parent.getConnectionId(),
                parent.getFullConnTag(),
                parent.getExtractedQueueManager(),
                parent.getApplicationTag()));
            
            // Child sessions
            List<SessionInfo> sessions = sessionsByConnection.get(parent.getConnectionId());
            if (sessions != null) {
                for (SessionInfo session : sessions) {
                    table.append(String.format("| %-3d | %-7s | C%-3d | S%-6d | %-48s | %-50s | %-14s | %-25s |\n",
                        rowNum++,
                        "Session",
                        connectionCounter.get(),
                        session.getSessionNumber(),
                        parent.getConnectionId(),
                        session.getFullConnTag(),
                        session.getQueueManager(),
                        parent.getApplicationTag()));
                }
            }
        }
        
        table.append("\nSummary: ");
        table.append(String.format("%d Parent Connections, %d Total Sessions\n", 
            parentConnections.size(), 
            sessionsByConnection.values().stream().mapToInt(List::size).sum()));
        
        // Group by Queue Manager
        Map<String, Long> qmDistribution = parentConnections.values().stream()
            .collect(Collectors.groupingBy(ConnectionInfo::getExtractedQueueManager, 
                Collectors.counting()));
        
        table.append("Distribution by QM: ").append(qmDistribution).append("\n");
        
        return table.toString();
    }
}