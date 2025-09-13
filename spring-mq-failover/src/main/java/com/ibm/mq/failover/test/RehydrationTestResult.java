package com.ibm.mq.failover.test;

import lombok.Data;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.*;

@Data
public class RehydrationTestResult {
    private boolean success = true;
    private String errorMessage;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String targetQM;
    
    private List<String> phases = new ArrayList<>();
    private List<String> events = new ArrayList<>();
    private Map<String, String> snapshots = new HashMap<>();
    
    // Connection states at different phases
    private Map<String, ConnectionState> initialStates = new HashMap<>();
    private Map<String, ConnectionState> redistributedStates = new HashMap<>();
    private Map<String, ConnectionState> finalStates = new HashMap<>();
    
    // Coherence tracking
    private Map<String, CoherenceInfo> coherenceMap = new HashMap<>();
    
    // Analysis results
    private boolean connectionsRebalanced = false;
    private int connectionsMovedBack = 0;
    private int connectionsStayedOnFailover = 0;
    
    @Data
    public static class ConnectionState {
        private String connectionId;
        private String queueManager;
        private String connTag;
        private LocalDateTime timestamp;
        
        public ConnectionState(String connectionId, String queueManager, String connTag) {
            this.connectionId = connectionId;
            this.queueManager = queueManager;
            this.connTag = connTag;
            this.timestamp = LocalDateTime.now();
        }
    }
    
    @Data
    public static class CoherenceInfo {
        private String connectionId;
        private boolean coherent;
        private Set<String> queueManagers;
        
        public CoherenceInfo(String connectionId, boolean coherent, Set<String> queueManagers) {
            this.connectionId = connectionId;
            this.coherent = coherent;
            this.queueManagers = queueManagers;
        }
    }
    
    public void addPhase(String phase) {
        phases.add(phase);
    }
    
    public void addEvent(String event) {
        events.add(String.format("[%s] %s", LocalDateTime.now(), event));
    }
    
    public void addSnapshot(String phase, String snapshot) {
        snapshots.put(phase, snapshot);
    }
    
    public void recordInitialState(String connId, String qm, String connTag) {
        initialStates.put(connId, new ConnectionState(connId, qm, connTag));
    }
    
    public void recordRedistribution(String connId, String qm) {
        redistributedStates.put(connId, new ConnectionState(connId, qm, "POST-FAILURE"));
    }
    
    public void recordFinalState(String connId, String qm) {
        finalStates.put(connId, new ConnectionState(connId, qm, "FINAL"));
    }
    
    public void recordCoherence(String connId, boolean coherent, Set<String> qms) {
        coherenceMap.put(connId, new CoherenceInfo(connId, coherent, qms));
    }
    
    public void analyzeRehydrationBehavior() {
        // Check if connections moved back to original QM after rehydration
        for (String connId : initialStates.keySet()) {
            ConnectionState initial = initialStates.get(connId);
            ConnectionState redistributed = redistributedStates.get(connId);
            ConnectionState finalState = finalStates.get(connId);
            
            if (initial != null && redistributed != null && finalState != null) {
                // Check if connection was on target QM initially
                if (initial.getQueueManager().equals(targetQM)) {
                    // It should have moved during failure
                    if (!redistributed.getQueueManager().equals(targetQM)) {
                        // Check if it moved back after rehydration
                        if (finalState.getQueueManager().equals(targetQM)) {
                            connectionsMovedBack++;
                            connectionsRebalanced = true;
                        } else {
                            connectionsStayedOnFailover++;
                        }
                    }
                }
            }
        }
    }
    
    public String generateReport() {
        StringBuilder report = new StringBuilder();
        report.append("\n================== QUEUE MANAGER REHYDRATION TEST REPORT ==================\n");
        
        // Basic info
        report.append(String.format("Status: %s\n", success ? "SUCCESS" : "FAILED"));
        if (errorMessage != null) {
            report.append(String.format("Error: %s\n", errorMessage));
        }
        
        Duration duration = Duration.between(startTime, endTime);
        report.append(String.format("Duration: %d seconds\n", duration.getSeconds()));
        report.append(String.format("Target QM for failure: %s\n", targetQM));
        
        // Phases
        report.append("\n=== Test Phases ===\n");
        phases.forEach(phase -> report.append(String.format("  - %s\n", phase)));
        
        // Events
        report.append("\n=== Events ===\n");
        events.forEach(event -> report.append(String.format("  %s\n", event)));
        
        // Initial Distribution
        report.append("\n=== Initial Distribution ===\n");
        Map<String, Long> initialDist = initialStates.values().stream()
            .collect(java.util.stream.Collectors.groupingBy(
                ConnectionState::getQueueManager,
                java.util.stream.Collectors.counting()));
        initialDist.forEach((qm, count) -> 
            report.append(String.format("  %s: %d connections\n", qm, count)));
        
        // Post-Failure Distribution
        report.append("\n=== Post-Failure Distribution ===\n");
        Map<String, Long> redistDist = redistributedStates.values().stream()
            .collect(java.util.stream.Collectors.groupingBy(
                ConnectionState::getQueueManager,
                java.util.stream.Collectors.counting()));
        redistDist.forEach((qm, count) -> 
            report.append(String.format("  %s: %d connections\n", qm, count)));
        
        // Final Distribution
        report.append("\n=== Final Distribution (After Rehydration) ===\n");
        Map<String, Long> finalDist = finalStates.values().stream()
            .collect(java.util.stream.Collectors.groupingBy(
                ConnectionState::getQueueManager,
                java.util.stream.Collectors.counting()));
        finalDist.forEach((qm, count) -> 
            report.append(String.format("  %s: %d connections\n", qm, count)));
        
        // Coherence Analysis
        report.append("\n=== Parent-Child Coherence ===\n");
        long coherentCount = coherenceMap.values().stream()
            .filter(CoherenceInfo::isCoherent)
            .count();
        report.append(String.format("Coherent connections: %d/%d\n", 
            coherentCount, coherenceMap.size()));
        
        coherenceMap.values().forEach(info -> {
            report.append(String.format("  Connection %s: %s", 
                info.getConnectionId(),
                info.isCoherent() ? "✅ COHERENT" : "❌ NOT COHERENT"));
            if (!info.isCoherent()) {
                report.append(String.format(" (split across %s)", info.getQueueManagers()));
            }
            report.append("\n");
        });
        
        // Rehydration Behavior Analysis
        report.append("\n=== Rehydration Behavior Analysis ===\n");
        report.append(String.format("Connections rebalanced after rehydration: %s\n", 
            connectionsRebalanced ? "YES" : "NO"));
        report.append(String.format("Connections moved back to %s: %d\n", 
            targetQM, connectionsMovedBack));
        report.append(String.format("Connections stayed on failover QMs: %d\n", 
            connectionsStayedOnFailover));
        
        // Key Finding
        report.append("\n=== KEY FINDING ===\n");
        if (connectionsRebalanced) {
            report.append("⚠️ CONNECTIONS REBALANCED: Some connections moved back to the ");
            report.append(String.format("rehydrated Queue Manager %s after it came back online.\n", targetQM));
            report.append("This indicates dynamic rebalancing behavior in the uniform cluster.\n");
        } else {
            report.append("✅ NO REBALANCING: Connections remained on their failover Queue Managers ");
            report.append("even after the failed QM was rehydrated.\n");
            report.append("This indicates stable failover with no automatic rebalancing.\n");
        }
        
        // Parent-Child Affinity Summary
        if (coherentCount == coherenceMap.size()) {
            report.append("\n✅ PERFECT PARENT-CHILD AFFINITY: All connections maintained ");
            report.append("parent-child session grouping throughout the test.\n");
        } else {
            report.append("\n⚠️ PARENT-CHILD AFFINITY ISSUES: Some connections lost ");
            report.append("parent-child session grouping during failover/rehydration.\n");
        }
        
        return report.toString();
    }
}