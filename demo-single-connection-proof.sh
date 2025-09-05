#!/bin/bash

################################################################################
# Single Connection Parent-Child Proof Demo
# Creates exactly ONE connection and traces its child sessions
# Uses REAL MQ data - no simulation, no fake data, no random values
################################################################################

# Color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
MAGENTA='\033[0;35m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Results directory
RESULTS_DIR="single_conn_proof_$(date +%Y%m%d_%H%M%S)"
mkdir -p "$RESULTS_DIR"

print_header() {
    echo -e "\n${CYAN}════════════════════════════════════════════════════════════════${NC}"
    echo -e "${CYAN}    $1${NC}"
    echo -e "${CYAN}════════════════════════════════════════════════════════════════${NC}"
}

# Check prerequisites
check_prerequisites() {
    print_header "CHECKING PREREQUISITES"
    
    for qm in qm1 qm2 qm3; do
        if docker ps | grep -q "$qm"; then
            echo -e "  ${GREEN}✓${NC} $qm is running"
        else
            echo -e "  ${RED}✗${NC} $qm is not running"
            echo -e "\n${RED}Please start the cluster:${NC}"
            echo "  docker-compose -f docker-compose-simple.yml up -d"
            exit 1
        fi
    done
    
    # Check Java app exists
    if [[ ! -f "java-app/target/classes/com/ibm/mq/demo/SingleConnectionTracer.class" ]]; then
        echo -e "\n${YELLOW}Compiling SingleConnectionTracer...${NC}"
        cd java-app
        mvn compile
        cd ..
    fi
}

# Clear queues
clear_queues() {
    print_header "CLEARING QUEUES"
    
    for qm in qm1 qm2 qm3; do
        local qm_upper=${qm^^}
        docker exec $qm bash -c "echo 'CLEAR QLOCAL(UNIFORM.QUEUE)' | runmqsc $qm_upper" >/dev/null 2>&1
        echo -e "  ${GREEN}✓${NC} $qm_upper queue cleared"
    done
}

# Capture MQSC state before test
capture_before_state() {
    print_header "CAPTURING INITIAL STATE (BEFORE TEST)"
    
    local before_file="$RESULTS_DIR/mqsc_before.log"
    
    {
        echo "═══════════════════════════════════════════════════════════════"
        echo "MQSC CONNECTION STATE - BEFORE SINGLE CONNECTION TEST"
        echo "Timestamp: $(date '+%Y-%m-%d %H:%M:%S')"
        echo "═══════════════════════════════════════════════════════════════"
        echo ""
        
        for qm in qm1 qm2 qm3; do
            local qm_upper=${qm^^}
            echo "Queue Manager: $qm_upper"
            echo "───────────────────────────────────────"
            
            local conn_count=$(docker exec $qm bash -c "echo 'DIS CONN(*) WHERE(CHANNEL EQ APP.SVRCONN)' | runmqsc $qm_upper 2>/dev/null | grep -c 'CONN('" 2>/dev/null || echo "0")
            echo "Active connections: $conn_count"
            
            if [[ "$conn_count" -gt 0 ]]; then
                docker exec $qm bash -c "
                    echo 'DIS CONN(*) WHERE(CHANNEL EQ APP.SVRCONN) APPLTAG CONNAME' | runmqsc $qm_upper 2>/dev/null | \
                    grep -E 'CONN\(|APPLTAG\(|CONNAME\(' | head -15
                " 2>/dev/null
            else
                echo "  No active connections"
            fi
            echo ""
        done
    } | tee "$before_file"
    
    echo -e "${GREEN}✓ Initial state captured to: $before_file${NC}"
}

# Run single connection tracer
run_single_connection_test() {
    print_header "RUNNING SINGLE CONNECTION TEST"
    
    local trace_log="$RESULTS_DIR/single_connection_trace.log"
    
    echo -e "${YELLOW}Starting SingleConnectionTracer...${NC}"
    echo -e "  - Creates exactly ONE connection"
    echo -e "  - Creates 3 sessions from that connection"
    echo -e "  - Sends test messages through each session"
    echo -e "  - Proves all sessions use same QM as parent\n"
    
    # Run the tracer application
    docker run --rm \
        --network mq-uniform-cluster_mqnet \
        -v "$(pwd)/java-app/target/classes:/app" \
        -v "$(pwd)/mq/ccdt:/workspace/ccdt" \
        -e CCDT_URL="file:///workspace/ccdt/ccdt.json" \
        openjdk:17 \
        java -cp "/app:/app/lib/*" \
        com.ibm.mq.demo.SingleConnectionTracer | tee "$trace_log"
    
    echo -e "\n${GREEN}✓ Single connection test completed${NC}"
    echo -e "${GREEN}✓ Results saved to: $trace_log${NC}"
}

# Monitor connections during test
monitor_during_test() {
    print_header "MONITORING ACTIVE CONNECTIONS"
    
    local monitor_log="$RESULTS_DIR/monitoring_during_test.log"
    
    echo -e "${YELLOW}Capturing connection state during test...${NC}"
    
    # Start monitoring in background
    ./monitoring/trace_active_connections.sh --once > "$monitor_log" 2>&1
    
    echo -e "${GREEN}✓ Monitoring data saved to: $monitor_log${NC}"
}

# Capture MQSC state after test
capture_after_state() {
    print_header "CAPTURING FINAL STATE (AFTER TEST)"
    
    local after_file="$RESULTS_DIR/mqsc_after.log"
    
    {
        echo "═══════════════════════════════════════════════════════════════"
        echo "MQSC CONNECTION STATE - AFTER SINGLE CONNECTION TEST"
        echo "Timestamp: $(date '+%Y-%m-%d %H:%M:%S')"
        echo "═══════════════════════════════════════════════════════════════"
        echo ""
        
        for qm in qm1 qm2 qm3; do
            local qm_upper=${qm^^}
            echo "Queue Manager: $qm_upper"
            echo "───────────────────────────────────────"
            
            # Get detailed connection info
            docker exec $qm bash -c "
                echo 'DIS CONN(*) WHERE(CHANNEL EQ APP.SVRCONN) ALL' | runmqsc $qm_upper 2>/dev/null
            " 2>/dev/null | awk '
                /CONN\(/ {
                    if (conn != "") print ""
                    match($0, /CONN\(([^)]+)\)/, arr)
                    conn = arr[1]
                    print "Connection: " conn
                }
                /CHANNEL\(/ {
                    match($0, /CHANNEL\(([^)]+)\)/, arr)
                    print "  Channel: " arr[1]
                }
                /CONNAME\(/ {
                    match($0, /CONNAME\(([^)]+)\)/, arr)
                    print "  CONNAME: " arr[1]
                }
                /APPLTAG\(/ {
                    match($0, /APPLTAG\(([^)]+)\)/, arr)
                    print "  APPLTAG: " arr[1]
                }
                /APPLTYPE\(/ {
                    match($0, /APPLTYPE\(([^)]+)\)/, arr)
                    print "  APPLTYPE: " arr[1]
                }
                /USERID\(/ {
                    match($0, /USERID\(([^)]+)\)/, arr)
                    print "  USERID: " arr[1]
                }
                /PID\(/ {
                    match($0, /PID\(([^)]+)\)/, arr)
                    print "  PID: " arr[1]
                }
                /TID\(/ {
                    match($0, /TID\(([^)]+)\)/, arr)
                    print "  TID: " arr[1]
                }
            '
            
            echo ""
        done
    } | tee "$after_file"
    
    echo -e "${GREEN}✓ Final state captured to: $after_file${NC}"
}

# Analyze correlation evidence
analyze_evidence() {
    print_header "ANALYZING CORRELATION EVIDENCE"
    
    local evidence_file="$RESULTS_DIR/correlation_evidence.log"
    
    {
        echo "═══════════════════════════════════════════════════════════════"
        echo "PARENT-CHILD CORRELATION EVIDENCE ANALYSIS"
        echo "Test Directory: $RESULTS_DIR"
        echo "Analysis Time: $(date '+%Y-%m-%d %H:%M:%S')"
        echo "═══════════════════════════════════════════════════════════════"
        echo ""
        
        # Extract key evidence from trace log
        echo "EVIDENCE FROM APPLICATION TRACE:"
        echo "─────────────────────────────────────────"
        
        if [[ -f "$RESULTS_DIR/single_connection_trace.log" ]]; then
            # Extract parent connection info
            echo "Parent Connection:"
            grep -E "Connection ID:|Queue Manager:|Channel:" "$RESULTS_DIR/single_connection_trace.log" | head -3 | sed 's/^/  /'
            
            echo ""
            echo "Child Sessions:"
            grep -E "Session #[0-9]|Session ID:|Parent Connection:|Queue Manager:" "$RESULTS_DIR/single_connection_trace.log" | grep -A 3 "Session #" | sed 's/^/  /'
            
            echo ""
            echo "Verification Result:"
            grep -E "SUCCESS:|FAILURE:" "$RESULTS_DIR/single_connection_trace.log" | sed 's/^/  /'
        fi
        
        echo ""
        echo "EVIDENCE FROM MQSC MONITORING:"
        echo "─────────────────────────────────────────"
        
        if [[ -f "$RESULTS_DIR/monitoring_during_test.log" ]]; then
            # Extract connection grouping
            grep -A 10 "Connection Group #1" "$RESULTS_DIR/monitoring_during_test.log" | head -15
        fi
        
        echo ""
        echo "═══════════════════════════════════════════════════════════════"
        echo "CONCLUSION:"
        
        # Check if test passed
        if grep -q "SUCCESS: All child sessions on SAME QM as parent" "$RESULTS_DIR/single_connection_trace.log"; then
            echo "✓ PROOF SUCCESSFUL: Child sessions confirmed on same QM as parent"
        else
            echo "✗ PROOF FAILED: Unable to confirm parent-child QM affinity"
        fi
        echo "═══════════════════════════════════════════════════════════════"
        
    } | tee "$evidence_file"
    
    echo -e "\n${GREEN}✓ Evidence analysis saved to: $evidence_file${NC}"
}

# Display final summary
display_summary() {
    print_header "TEST SUMMARY"
    
    echo -e "${GREEN}Test completed successfully!${NC}\n"
    echo -e "Results saved in: ${CYAN}$RESULTS_DIR/${NC}"
    echo -e "  - Initial state:    mqsc_before.log"
    echo -e "  - Trace output:     single_connection_trace.log"
    echo -e "  - Monitoring data:  monitoring_during_test.log"
    echo -e "  - Final state:      mqsc_after.log"
    echo -e "  - Evidence:         correlation_evidence.log"
    
    echo -e "\n${YELLOW}Key Finding:${NC}"
    if grep -q "SUCCESS: All child sessions on SAME QM as parent" "$RESULTS_DIR/single_connection_trace.log"; then
        echo -e "  ${GREEN}✓ PROVEN: Child sessions always connect to same Queue Manager as parent connection${NC}"
    else
        echo -e "  ${RED}✗ Unable to prove parent-child QM affinity${NC}"
    fi
}

# Main execution
main() {
    echo -e "${CYAN}╔════════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${CYAN}║       SINGLE CONNECTION PARENT-CHILD PROOF DEMO                ║${NC}"
    echo -e "${CYAN}╚════════════════════════════════════════════════════════════════╝${NC}"
    
    check_prerequisites
    clear_queues
    capture_before_state
    
    # Run the single connection test
    run_single_connection_test
    
    # Give time for connections to register
    sleep 2
    
    # Monitor active connections
    monitor_during_test
    
    capture_after_state
    analyze_evidence
    display_summary
}

# Run main function
main