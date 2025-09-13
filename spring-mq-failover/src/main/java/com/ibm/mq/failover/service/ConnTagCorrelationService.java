package com.ibm.mq.failover.service;

import com.ibm.mq.failover.model.ConnectionInfo;
import com.ibm.mq.failover.model.SessionInfo;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConnTagCorrelationService {
    
    private final ConnectionTrackingService trackingService;
    private final Map<String, ConnTagGroup> connTagGroups = new ConcurrentHashMap<>();
    private final Map<String, String> connTagToQueueManager = new ConcurrentHashMap<>();
    
    @Data
    public static class ConnTagGroup {
        private String groupId;
        private String baseConnTag;
        private String queueManager;
        private ConnectionInfo parentConnection;
        private List<SessionInfo> childSessions = new ArrayList<>();
        private LocalDateTime createdAt;
        private LocalDateTime lastVerified;
        private boolean isCoherent;
        
        public void addSession(SessionInfo session) {
            childSessions.add(session);
            verifyCoherence();
        }
        
        public void verifyCoherence() {
            // Check if all sessions are on the same QM as parent
            if (parentConnection != null && !childSessions.isEmpty()) {
                String parentQM = parentConnection.getExtractedQueueManager();
                isCoherent = childSessions.stream()
                    .allMatch(s -> s.getQueueManager().equals(parentQM));
                
                if (!isCoherent) {
                    log.warn("CONNTAG group {} is NOT coherent! Parent on {}, but found sessions on different QMs",
                        groupId, parentQM);
                }
            }
        }
        
        public String getGroupSummary() {
            return String.format("Group %s: QM=%s, Parent=%s, Sessions=%d, Coherent=%s",
                groupId, queueManager, 
                parentConnection != null ? parentConnection.getConnectionId() : "N/A",
                childSessions.size(), isCoherent);
        }
    }
    
    public void correlateConnTags() {
        log.info("Starting CONNTAG correlation analysis...");
        
        // Clear previous groups
        connTagGroups.clear();
        
        // Get all parent connections
        List<ConnectionInfo> parents = trackingService.getAllParentConnections();
        
        for (ConnectionInfo parent : parents) {
            String connTag = parent.getFullConnTag();
            String groupId = extractGroupId(connTag);
            
            ConnTagGroup group = connTagGroups.computeIfAbsent(groupId, k -> {
                ConnTagGroup newGroup = new ConnTagGroup();
                newGroup.setGroupId(k);
                newGroup.setBaseConnTag(connTag);
                newGroup.setQueueManager(parent.getExtractedQueueManager());
                newGroup.setCreatedAt(LocalDateTime.now());
                newGroup.setParentConnection(parent);
                return newGroup;
            });
            
            // Add all sessions for this parent
            for (SessionInfo session : parent.getSessions()) {
                group.addSession(session);
                
                // Track CONNTAG to QM mapping
                connTagToQueueManager.put(session.getFullConnTag(), session.getQueueManager());
            }
            
            group.setLastVerified(LocalDateTime.now());
            group.verifyCoherence();
        }
        
        printCorrelationReport();
    }
    
    private String extractGroupId(String connTag) {
        // Extract the base CONNTAG identifier that groups parent and children
        if (connTag != null && connTag.length() >= 20) {
            // Format: MQCTXXXXXXXXXXXXXXXX...
            // Extract the hex portion after MQCT
            if (connTag.startsWith("MQCT")) {
                return connTag.substring(4, Math.min(20, connTag.length()));
            }
            return connTag.substring(0, Math.min(20, connTag.length()));
        }
        return connTag;
    }
    
    public void verifyParentChildGrouping(String parentConnectionId) {
        ConnectionInfo parent = trackingService.getAllParentConnections().stream()
            .filter(c -> c.getConnectionId().equals(parentConnectionId))
            .findFirst()
            .orElse(null);
        
        if (parent == null) {
            log.warn("Parent connection {} not found", parentConnectionId);
            return;
        }
        
        log.info("=== Parent-Child Grouping Verification ===");
        log.info("Parent Connection: {}", parentConnectionId);
        log.info("Parent QM: {}", parent.getExtractedQueueManager());
        log.info("Parent CONNTAG: {}", parent.getFullConnTag());
        
        List<SessionInfo> sessions = parent.getSessions();
        Map<String, List<SessionInfo>> sessionsByQM = sessions.stream()
            .collect(Collectors.groupingBy(SessionInfo::getQueueManager));
        
        if (sessionsByQM.size() == 1) {
            log.info("✅ SUCCESS: All {} sessions are on the same QM as parent: {}", 
                sessions.size(), parent.getExtractedQueueManager());
        } else {
            log.error("❌ FAILURE: Sessions are split across {} different QMs!", sessionsByQM.size());
            sessionsByQM.forEach((qm, list) -> 
                log.error("  - QM {}: {} sessions", qm, list.size()));
        }
        
        // Verify CONNTAG patterns
        Set<String> uniqueConnTags = sessions.stream()
            .map(SessionInfo::getFullConnTag)
            .collect(Collectors.toSet());
        
        log.info("Unique CONNTAGs in group: {}", uniqueConnTags.size());
        uniqueConnTags.forEach(tag -> log.info("  - {}", tag));
    }
    
    @Scheduled(fixedDelay = 10000) // Run every 10 seconds
    public void periodicCorrelation() {
        correlateConnTags();
    }
    
    public void printCorrelationReport() {
        log.info("\n================== CONNTAG CORRELATION REPORT ==================");
        log.info("Total CONNTAG Groups: {}", connTagGroups.size());
        
        for (ConnTagGroup group : connTagGroups.values()) {
            log.info("\n{}", group.getGroupSummary());
            
            if (!group.isCoherent()) {
                log.warn("⚠️ WARNING: Incoherent group detected!");
                log.warn("Parent QM: {}", group.getParentConnection().getExtractedQueueManager());
                Map<String, Long> sessionQMs = group.getChildSessions().stream()
                    .collect(Collectors.groupingBy(SessionInfo::getQueueManager, 
                        Collectors.counting()));
                sessionQMs.forEach((qm, count) -> 
                    log.warn("  Sessions on {}: {}", qm, count));
            }
        }
        
        // Summary statistics
        long coherentGroups = connTagGroups.values().stream()
            .filter(ConnTagGroup::isCoherent)
            .count();
        
        log.info("\n=== Summary ===");
        log.info("Coherent Groups: {}/{} ({}%)", 
            coherentGroups, connTagGroups.size(), 
            coherentGroups * 100 / Math.max(1, connTagGroups.size()));
        
        // QM Distribution
        Map<String, Long> qmDistribution = connTagGroups.values().stream()
            .collect(Collectors.groupingBy(ConnTagGroup::getQueueManager, 
                Collectors.counting()));
        
        log.info("Distribution by Queue Manager:");
        qmDistribution.forEach((qm, count) -> 
            log.info("  {}: {} groups", qm, count));
    }
    
    public boolean verifyFailoverCoherence(String beforeConnTag, String afterConnTag) {
        log.info("Verifying failover coherence...");
        log.info("Before CONNTAG: {}", beforeConnTag);
        log.info("After CONNTAG: {}", afterConnTag);
        
        String beforeGroup = extractGroupId(beforeConnTag);
        String afterGroup = extractGroupId(afterConnTag);
        
        ConnTagGroup beforeGroupData = connTagGroups.get(beforeGroup);
        ConnTagGroup afterGroupData = connTagGroups.get(afterGroup);
        
        if (beforeGroupData != null && afterGroupData != null) {
            log.info("Before: {}", beforeGroupData.getGroupSummary());
            log.info("After: {}", afterGroupData.getGroupSummary());
            
            // Check if sessions moved together
            int beforeSessions = beforeGroupData.getChildSessions().size();
            int afterSessions = afterGroupData.getChildSessions().size();
            
            if (beforeSessions == afterSessions) {
                log.info("✅ Failover SUCCESS: All {} sessions moved together", beforeSessions);
                return true;
            } else {
                log.error("❌ Failover ISSUE: Session count mismatch. Before: {}, After: {}", 
                    beforeSessions, afterSessions);
                return false;
            }
        }
        
        log.warn("Could not verify failover coherence - missing group data");
        return false;
    }
}