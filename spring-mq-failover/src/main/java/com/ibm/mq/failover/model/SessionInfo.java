package com.ibm.mq.failover.model;

import lombok.Data;
import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionInfo {
    private String sessionId;
    private String parentConnectionId;
    private String sessionTag;
    private String fullConnTag;
    private String queueManager;
    private Integer sessionNumber;
    private LocalDateTime createdAt;
    private SessionStatus status;
    private String threadName;
    private Long threadId;
    private boolean transacted;
    private Integer acknowledgeMode;
    
    public enum SessionStatus {
        ACTIVE,
        IDLE,
        PROCESSING,
        FAILED,
        CLOSED
    }
    
    public String getCorrelationKey() {
        return String.format("%s-%s-%d", parentConnectionId, sessionTag, sessionNumber);
    }
    
    public boolean belongsToConnection(String connectionId) {
        return parentConnectionId != null && parentConnectionId.equals(connectionId);
    }
}