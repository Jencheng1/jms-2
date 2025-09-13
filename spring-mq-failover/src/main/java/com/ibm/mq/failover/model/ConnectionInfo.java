package com.ibm.mq.failover.model;

import lombok.Data;
import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConnectionInfo {
    private String connectionId;
    private String connectionTag;
    private String fullConnTag;
    private String queueManager;
    private String applicationTag;
    private String resolvedQueueManager;
    private String hostName;
    private Integer port;
    private LocalDateTime createdAt;
    private LocalDateTime lastUpdated;
    private ConnectionStatus status;
    private String parentConnectionId;
    private boolean isParent;
    
    @Builder.Default
    private List<SessionInfo> sessions = new CopyOnWriteArrayList<>();
    
    public enum ConnectionStatus {
        CONNECTED,
        RECONNECTING,
        FAILED,
        CLOSED
    }
    
    public void addSession(SessionInfo session) {
        sessions.add(session);
        session.setParentConnectionId(this.connectionId);
    }
    
    public String getExtractedQueueManager() {
        if (connectionId != null && connectionId.length() >= 32) {
            String prefix = connectionId.substring(0, 32);
            if (prefix.startsWith("414D5143514D31")) {
                return "QM1";
            } else if (prefix.startsWith("414D5143514D32")) {
                return "QM2";
            } else if (prefix.startsWith("414D5143514D33")) {
                return "QM3";
            }
        }
        return resolvedQueueManager;
    }
    
    public String getCorrelationKey() {
        return String.format("%s-%s-%s", applicationTag, connectionId, fullConnTag);
    }
}