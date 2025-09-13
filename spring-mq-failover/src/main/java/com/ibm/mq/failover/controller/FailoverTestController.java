package com.ibm.mq.failover.controller;

import com.ibm.mq.failover.service.ConnTagCorrelationService;
import com.ibm.mq.failover.service.ConnectionTrackingService;
import com.ibm.mq.failover.test.FailoverTestService;
import com.ibm.mq.failover.test.QueueManagerRehydrationTest;
import com.ibm.mq.failover.test.RehydrationTestResult;
import com.ibm.mq.failover.test.TestResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/api/failover")
@RequiredArgsConstructor
public class FailoverTestController {
    
    private final FailoverTestService failoverTestService;
    private final QueueManagerRehydrationTest rehydrationTest;
    private final ConnectionTrackingService trackingService;
    private final ConnTagCorrelationService correlationService;
    
    @PostMapping("/test/start")
    public ResponseEntity<String> startFailoverTest() {
        log.info("Starting failover test via REST API");
        
        CompletableFuture<TestResult> future = failoverTestService.runFailoverTest();
        
        future.thenAccept(result -> {
            log.info("Failover test completed");
            log.info(result.generateReport());
        });
        
        return ResponseEntity.ok("Failover test started. Check logs for progress.");
    }
    
    @PostMapping("/test/rehydration")
    public ResponseEntity<String> startRehydrationTest() {
        log.info("Starting rehydration test via REST API");
        
        RehydrationTestResult result = rehydrationTest.runRehydrationTest();
        String report = result.generateReport();
        
        log.info(report);
        
        return ResponseEntity.ok(report);
    }
    
    @GetMapping("/connections")
    public ResponseEntity<String> getConnectionStatus() {
        String table = trackingService.generateConnectionTable();
        return ResponseEntity.ok(table);
    }
    
    @GetMapping("/correlation")
    public ResponseEntity<String> getCorrelationReport() {
        correlationService.correlateConnTags();
        return ResponseEntity.ok("Correlation report generated. Check logs.");
    }
    
    @PostMapping("/verify/{connectionId}")
    public ResponseEntity<String> verifyParentChildGrouping(@PathVariable String connectionId) {
        correlationService.verifyParentChildGrouping(connectionId);
        return ResponseEntity.ok("Verification complete. Check logs for details.");
    }
}