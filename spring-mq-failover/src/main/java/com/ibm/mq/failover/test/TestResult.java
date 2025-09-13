package com.ibm.mq.failover.test;

import com.ibm.mq.failover.model.ConnectionInfo;
import com.ibm.mq.failover.model.SessionInfo;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
public class TestResult {
    private boolean success;
    private String message;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private int messagesSent;
    private int messagesReceived;
    
    private List<ConnectionInfo> parentConnections = new ArrayList<>();
    private List<SessionInfo> childSessions = new ArrayList<>();
    private List<String> failureEvents = new ArrayList<>();
    private Map<String, String> snapshots = new HashMap<>();
    private Map<String, CoherenceCheck> coherenceChecks = new HashMap<>();
    
    @Data
    public static class CoherenceCheck {
        private String connectionId;
        private boolean coherent;
        private String details;
        
        public CoherenceCheck(String connectionId, boolean coherent, String details) {
            this.connectionId = connectionId;
            this.coherent = coherent;
            this.details = details;
        }
    }
    
    public TestResult(boolean success, String message) {
        this.success = success;
        this.message = message;
    }
    
    public void addParentConnection(ConnectionInfo conn) {
        parentConnections.add(conn);
    }
    
    public void addChildSession(SessionInfo session) {
        childSessions.add(session);
    }
    
    public void addFailureEvent(String event) {
        failureEvents.add(event);
    }
    
    public void addSnapshot(String phase, String snapshot) {
        snapshots.put(phase, snapshot);
    }
    
    public void addCoherenceCheck(String connectionId, boolean coherent, String details) {
        coherenceChecks.put(connectionId, new CoherenceCheck(connectionId, coherent, details));
    }
    
    public String generateReport() {
        StringBuilder report = new StringBuilder();
        report.append("\n================== FAILOVER TEST REPORT ==================\n");
        report.append(String.format("Status: %s\n", success ? "SUCCESS" : "FAILED"));
        report.append(String.format("Duration: %s to %s\n", startTime, endTime));
        report.append(String.format("Messages: Sent=%d, Received=%d\n", messagesSent, messagesReceived));
        
        report.append("\n=== Parent Connections ===\n");
        parentConnections.forEach(conn -> 
            report.append(String.format("  - %s on %s (CONNTAG: %s)\n", 
                conn.getConnectionId(), 
                conn.getExtractedQueueManager(), 
                conn.getFullConnTag())));
        
        report.append("\n=== Child Sessions ===\n");
        Map<String, List<SessionInfo>> sessionsByParent = new HashMap<>();
        childSessions.forEach(session -> 
            sessionsByParent.computeIfAbsent(session.getParentConnectionId(), 
                k -> new ArrayList<>()).add(session));
        
        sessionsByParent.forEach((parentId, sessions) -> {
            report.append(String.format("  Parent %s:\n", parentId));
            sessions.forEach(session -> 
                report.append(String.format("    - Session %d on %s (Thread: %s)\n", 
                    session.getSessionNumber(), 
                    session.getQueueManager(), 
                    session.getThreadName())));
        });
        
        report.append("\n=== Failure Events ===\n");
        failureEvents.forEach(event -> 
            report.append(String.format("  - %s\n", event)));
        
        report.append("\n=== Coherence Checks ===\n");
        coherenceChecks.values().forEach(check -> 
            report.append(String.format("  - Connection %s: %s - %s\n", 
                check.getConnectionId(),
                check.isCoherent() ? "✅ COHERENT" : "❌ NOT COHERENT",
                check.getDetails())));
        
        long coherentCount = coherenceChecks.values().stream()
            .filter(CoherenceCheck::isCoherent)
            .count();
        
        report.append(String.format("\nCoherence Summary: %d/%d connections maintained parent-child affinity\n",
            coherentCount, coherenceChecks.size()));
        
        return report.toString();
    }
}