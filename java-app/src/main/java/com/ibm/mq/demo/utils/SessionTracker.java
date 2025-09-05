package com.ibm.mq.demo.utils;

import com.ibm.mq.MQException;
import com.ibm.mq.jms.MQConnection;
import com.ibm.mq.jms.MQSession;
import com.ibm.msg.client.wmq.WMQConstants;
import javax.jms.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility class to track JMS Connection and Session relationships
 * and extract MQ-specific metadata for correlation
 */
public class SessionTracker {
    
    // Track all connections and their child sessions
    private static final Map<String, ConnectionTracking> connections = new ConcurrentHashMap<>();
    
    public static class ConnectionTracking {
        public final String connectionId;
        public final String correlationId;
        public final String queueManager;
        public final String channel;
        public final long establishedTime;
        public final List<SessionTracking> sessions = Collections.synchronizedList(new ArrayList<>());
        
        public ConnectionTracking(String connectionId, String correlationId, String queueManager, String channel) {
            this.connectionId = connectionId;
            this.correlationId = correlationId;
            this.queueManager = queueManager;
            this.channel = channel;
            this.establishedTime = System.currentTimeMillis();
        }
        
        public void addSession(SessionTracking session) {
            sessions.add(session);
        }
    }
    
    public static class SessionTracking {
        public final String sessionId;
        public final String parentConnectionId;
        public final String queueManager;
        public final String channel;
        public final int sessionNumber;
        public final long createdTime;
        
        public SessionTracking(String sessionId, String parentConnectionId, 
                              String queueManager, String channel, int sessionNumber) {
            this.sessionId = sessionId;
            this.parentConnectionId = parentConnectionId;
            this.queueManager = queueManager;
            this.channel = channel;
            this.sessionNumber = sessionNumber;
            this.createdTime = System.currentTimeMillis();
        }
    }
    
    /**
     * Register a new connection with tracking
     */
    public static ConnectionTracking trackConnection(Connection connection, String correlationId) {
        try {
            String connectionId = connection.getClientID();
            String queueManager = extractQueueManager(connection);
            String channel = extractChannel(connection);
            
            ConnectionTracking tracking = new ConnectionTracking(
                connectionId != null ? connectionId : UUID.randomUUID().toString(),
                correlationId,
                queueManager,
                channel
            );
            
            connections.put(correlationId, tracking);
            
            System.out.println("[SessionTracker] Tracked Connection:");
            System.out.println("  Correlation ID: " + correlationId);
            System.out.println("  Connection ID: " + tracking.connectionId);
            System.out.println("  Queue Manager: " + queueManager);
            System.out.println("  Channel: " + channel);
            
            return tracking;
        } catch (Exception e) {
            System.err.println("[SessionTracker] Failed to track connection: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Register a session as child of a connection
     */
    public static SessionTracking trackSession(Session session, String correlationId, 
                                              Connection parentConnection, int sessionNumber) {
        try {
            ConnectionTracking connTracking = connections.get(correlationId);
            if (connTracking == null) {
                System.err.println("[SessionTracker] No connection found for correlation ID: " + correlationId);
                return null;
            }
            
            String sessionId = correlationId + "-S" + sessionNumber;
            String queueManager = extractQueueManagerFromSession(session);
            String channel = extractChannelFromSession(session);
            
            // If we couldn't extract from session, use parent connection values
            if (queueManager == null || queueManager.equals("UNKNOWN")) {
                queueManager = connTracking.queueManager;
            }
            if (channel == null || channel.equals("UNKNOWN")) {
                channel = connTracking.channel;
            }
            
            SessionTracking tracking = new SessionTracking(
                sessionId,
                connTracking.connectionId,
                queueManager,
                channel,
                sessionNumber
            );
            
            connTracking.addSession(tracking);
            
            System.out.println("[SessionTracker] Tracked Session:");
            System.out.println("  Session ID: " + sessionId);
            System.out.println("  Parent Connection: " + connTracking.connectionId);
            System.out.println("  Queue Manager: " + queueManager);
            System.out.println("  Channel: " + channel);
            
            // Verify parent-child relationship
            if (queueManager.equals(connTracking.queueManager)) {
                System.out.println("  ✓ Session connected to SAME QM as parent connection");
            } else {
                System.out.println("  ✗ WARNING: Session connected to DIFFERENT QM than parent!");
            }
            
            return tracking;
        } catch (Exception e) {
            System.err.println("[SessionTracker] Failed to track session: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Extract Queue Manager name from Connection using reflection
     */
    private static String extractQueueManager(Connection connection) {
        try {
            if (connection instanceof MQConnection) {
                MQConnection mqConn = (MQConnection) connection;
                
                // Try to get QM name using reflection
                try {
                    Field field = mqConn.getClass().getDeclaredField("queueManagerName");
                    field.setAccessible(true);
                    String qmName = (String) field.get(mqConn);
                    if (qmName != null && !qmName.trim().isEmpty()) {
                        return qmName.trim();
                    }
                } catch (NoSuchFieldException e) {
                    // Try alternative field names
                }
                
                // Try using methods
                try {
                    Method method = mqConn.getClass().getMethod("getQueueManagerName");
                    String qmName = (String) method.invoke(mqConn);
                    if (qmName != null && !qmName.trim().isEmpty()) {
                        return qmName.trim();
                    }
                } catch (NoSuchMethodException e) {
                    // Try alternative method
                }
            }
            
            // Fallback to parsing client ID
            String clientId = connection.getClientID();
            return parseQueueManagerFromClientId(clientId);
            
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }
    
    /**
     * Extract Channel name from Connection
     */
    private static String extractChannel(Connection connection) {
        try {
            if (connection instanceof MQConnection) {
                // MQ connections typically use APP.SVRCONN channel
                return "APP.SVRCONN";
            }
            return "UNKNOWN";
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }
    
    /**
     * Extract Queue Manager from Session using reflection
     */
    private static String extractQueueManagerFromSession(Session session) {
        try {
            if (session instanceof MQSession) {
                MQSession mqSession = (MQSession) session;
                
                // Try to access internal fields
                try {
                    Field field = mqSession.getClass().getDeclaredField("qmgrName");
                    field.setAccessible(true);
                    String qmName = (String) field.get(mqSession);
                    if (qmName != null && !qmName.trim().isEmpty()) {
                        return qmName.trim();
                    }
                } catch (NoSuchFieldException e) {
                    // Try alternative approaches
                }
            }
            
            return "UNKNOWN";
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }
    
    /**
     * Extract Channel from Session
     */
    private static String extractChannelFromSession(Session session) {
        try {
            if (session instanceof MQSession) {
                return "APP.SVRCONN";
            }
            return "UNKNOWN";
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }
    
    /**
     * Parse Queue Manager name from Client ID
     */
    private static String parseQueueManagerFromClientId(String clientId) {
        if (clientId == null) return "UNKNOWN";
        
        // Client ID format: ID:414d5120514d312020202020202020206e8a4166204b6003
        // The hex "414d5120514d31202020202020202020" decodes to "AMQ QM1        "
        if (clientId.startsWith("ID:") && clientId.length() > 20) {
            try {
                String hex = clientId.substring(3, Math.min(35, clientId.length()));
                StringBuilder qmName = new StringBuilder();
                
                // Skip "AMQ " prefix (8 chars) and decode QM name
                for (int i = 8; i < hex.length() && i < 24; i += 2) {
                    String hexByte = hex.substring(i, i + 2);
                    char ch = (char) Integer.parseInt(hexByte, 16);
                    if (ch != ' ' && ch != 0) {
                        qmName.append(ch);
                    }
                }
                
                if (qmName.length() > 0) {
                    return qmName.toString();
                }
            } catch (Exception e) {
                // Fallback to pattern matching
            }
        }
        
        // Try simple pattern matching
        if (clientId.contains("QM1")) return "QM1";
        if (clientId.contains("QM2")) return "QM2";
        if (clientId.contains("QM3")) return "QM3";
        
        return "UNKNOWN";
    }
    
    /**
     * Print full tracking report
     */
    public static void printTrackingReport() {
        System.out.println("\n========================================");
        System.out.println("CONNECTION-SESSION TRACKING REPORT");
        System.out.println("========================================");
        
        int totalConnections = 0;
        int totalSessions = 0;
        int matchingQMs = 0;
        int mismatchedQMs = 0;
        
        for (Map.Entry<String, ConnectionTracking> entry : connections.entrySet()) {
            ConnectionTracking conn = entry.getValue();
            totalConnections++;
            
            System.out.println("\nConnection [" + entry.getKey() + "]:");
            System.out.println("  Connection ID: " + conn.connectionId);
            System.out.println("  Queue Manager: " + conn.queueManager);
            System.out.println("  Channel: " + conn.channel);
            System.out.println("  Established: " + new Date(conn.establishedTime));
            System.out.println("  Sessions (" + conn.sessions.size() + "):");
            
            for (SessionTracking session : conn.sessions) {
                totalSessions++;
                System.out.println("    └─> Session " + session.sessionNumber + ":");
                System.out.println("        Session ID: " + session.sessionId);
                System.out.println("        Queue Manager: " + session.queueManager);
                System.out.println("        Channel: " + session.channel);
                
                if (session.queueManager.equals(conn.queueManager)) {
                    System.out.println("        Status: ✓ MATCHED - Same QM as parent");
                    matchingQMs++;
                } else {
                    System.out.println("        Status: ✗ MISMATCHED - Different QM than parent!");
                    mismatchedQMs++;
                }
            }
        }
        
        System.out.println("\n========================================");
        System.out.println("SUMMARY:");
        System.out.println("  Total Connections: " + totalConnections);
        System.out.println("  Total Sessions: " + totalSessions);
        System.out.println("  Sessions with matching QM: " + matchingQMs);
        System.out.println("  Sessions with mismatched QM: " + mismatchedQMs);
        
        if (mismatchedQMs == 0) {
            System.out.println("\n✓ SUCCESS: All child sessions connected to same QM as parent connections!");
        } else {
            System.out.println("\n✗ WARNING: Some sessions connected to different QMs than parent connections!");
        }
        
        System.out.println("========================================");
    }
    
    /**
     * Get all tracked connections
     */
    public static Map<String, ConnectionTracking> getAllConnections() {
        return new HashMap<>(connections);
    }
    
    /**
     * Clear all tracking data
     */
    public static void clearTracking() {
        connections.clear();
    }
}