#!/bin/bash

# Spring Boot MQ Failover - 5 Iteration Comprehensive Test
# This script runs 5 complete failover tests with full evidence collection

TEST_DIR="/home/ec2-user/unified/demo5/mq-uniform-cluster/springboot_failover"
RESULTS_DIR="${TEST_DIR}/test_results_$(date +%Y%m%d_%H%M%S)"
ITERATION_COUNT=5

echo "=============================================================================="
echo "    Spring Boot MQ Failover - 5 Iteration Comprehensive Test"
echo "=============================================================================="
echo "Test Directory: ${TEST_DIR}"
echo "Results Directory: ${RESULTS_DIR}"
echo "Iterations: ${ITERATION_COUNT}"
echo "Start Time: $(date)"
echo ""

# Create results directory
mkdir -p "${RESULTS_DIR}"

# Function to check which QM has connections
check_qm_connections() {
    local test_id=$1
    local phase=$2
    echo "[$(date +%H:%M:%S)] Checking QM connections for ${phase}..."
    
    for qm in qm1 qm2 qm3; do
        local count=$(docker exec ${qm} bash -c "echo 'DIS CONN(*) WHERE(APPLTAG LK *${test_id}*) CHANNEL' | runmqsc ${qm^^}" 2>/dev/null | grep -c "CONN(" || echo "0")
        echo "  ${qm^^}: ${count} connections"
        
        if [ "$count" -gt "0" ]; then
            # Capture detailed MQSC evidence
            docker exec ${qm} bash -c "echo 'DIS CONN(*) WHERE(APPLTAG LK *${test_id}*) ALL' | runmqsc ${qm^^}" > "${RESULTS_DIR}/iteration_${3}_${phase}_${qm}_mqsc.log" 2>&1
        fi
    done
}

# Function to start tcpdump
start_tcpdump() {
    local iteration=$1
    echo "[$(date +%H:%M:%S)] Starting tcpdump for iteration ${iteration}..."
    docker run --rm -d \
        --name tcpdump_iter_${iteration} \
        --network mq-uniform-cluster_mqnet \
        --cap-add=NET_ADMIN \
        nicolaka/netshoot \
        tcpdump -i any -w /tmp/capture_iter_${iteration}.pcap \
        'port 1414 or port 1415 or port 1416' &
    
    sleep 2
}

# Function to stop tcpdump
stop_tcpdump() {
    local iteration=$1
    echo "[$(date +%H:%M:%S)] Stopping tcpdump for iteration ${iteration}..."
    docker stop tcpdump_iter_${iteration} 2>/dev/null || true
    
    # Copy pcap file
    docker cp tcpdump_iter_${iteration}:/tmp/capture_iter_${iteration}.pcap "${RESULTS_DIR}/" 2>/dev/null || true
}

# Function to run single failover test iteration
run_failover_iteration() {
    local iteration=$1
    local test_id="ITER${iteration}-$(date +%s)"
    
    echo ""
    echo "=============================================================================="
    echo "                        ITERATION ${iteration} of ${ITERATION_COUNT}"
    echo "=============================================================================="
    echo "Test ID: ${test_id}"
    echo "Start Time: $(date +%H:%M:%S)"
    echo ""
    
    # Start tcpdump for network capture
    start_tcpdump ${iteration}
    
    # Create iteration log file
    ITERATION_LOG="${RESULTS_DIR}/iteration_${iteration}_complete.log"
    
    # Compile the test if needed
    echo "[$(date +%H:%M:%S)] Compiling SpringBootFailoverCompleteDemo..."
    cd ${TEST_DIR}
    javac -cp "libs/*:." src/main/java/com/ibm/mq/demo/SpringBootFailoverCompleteDemo.java 2>&1
    
    # Start the test in background
    echo "[$(date +%H:%M:%S)] Starting Spring Boot failover test..."
    docker run --rm \
        --network mq-uniform-cluster_mqnet \
        -v "${TEST_DIR}:/app" \
        -v "${TEST_DIR}/libs:/libs" \
        -v "${TEST_DIR}/ccdt:/workspace/ccdt" \
        -e TEST_ID="${test_id}" \
        -e JMS_IBM_TRACE_LEVEL=9 \
        -e JMS_IBM_TRACE_FILE="/app/test_results/iteration_${iteration}_jms_trace.log" \
        --name springboot_iter_${iteration} \
        openjdk:17 \
        java -cp "/app/src/main/java:/libs/*" \
        -Dcom.ibm.msg.client.commonservices.trace.status=ON \
        -Dcom.ibm.msg.client.commonservices.trace.outputName=/app/test_results/iteration_${iteration}_trace.trc \
        com.ibm.mq.demo.SpringBootFailoverCompleteDemo > "${ITERATION_LOG}" 2>&1 &
    
    TEST_PID=$!
    
    # Wait for connections to establish
    echo "[$(date +%H:%M:%S)] Waiting for connections to establish..."
    sleep 10
    
    # Check initial state (BEFORE failover)
    check_qm_connections "${test_id}" "BEFORE" ${iteration}
    
    # Determine which QM to stop
    echo "[$(date +%H:%M:%S)] Determining which QM to stop..."
    TARGET_QM=""
    MAX_CONN=0
    
    for qm in qm1 qm2 qm3; do
        count=$(docker exec ${qm} bash -c "echo 'DIS CONN(*) WHERE(APPLTAG LK *${test_id}*) CHANNEL' | runmqsc ${qm^^}" 2>/dev/null | grep -c "CONN(" || echo "0")
        if [ "$count" -gt "$MAX_CONN" ]; then
            MAX_CONN=$count
            TARGET_QM=$qm
        fi
    done
    
    if [ -n "$TARGET_QM" ] && [ "$MAX_CONN" -gt "0" ]; then
        echo "[$(date +%H:%M:%S)] Stopping ${TARGET_QM^^} (has ${MAX_CONN} connections)..."
        docker stop ${TARGET_QM}
        
        # Wait for failover to complete
        echo "[$(date +%H:%M:%S)] Waiting for failover to complete..."
        sleep 15
        
        # Check state AFTER failover
        check_qm_connections "${test_id}" "AFTER" ${iteration}
        
        # Restart the stopped QM
        echo "[$(date +%H:%M:%S)] Restarting ${TARGET_QM^^}..."
        docker start ${TARGET_QM}
        sleep 10
    else
        echo "[$(date +%H:%M:%S)] WARNING: No connections found to trigger failover"
    fi
    
    # Wait for test to complete
    echo "[$(date +%H:%M:%S)] Waiting for test to complete..."
    wait $TEST_PID 2>/dev/null || true
    
    # Stop tcpdump
    stop_tcpdump ${iteration}
    
    # Stop the test container if still running
    docker stop springboot_iter_${iteration} 2>/dev/null || true
    
    # Extract and save the connection tables from log
    echo "[$(date +%H:%M:%S)] Extracting connection tables..."
    grep -A 15 "BEFORE FAILOVER - Complete Connection Table" "${ITERATION_LOG}" > "${RESULTS_DIR}/iteration_${iteration}_table_before.txt" 2>/dev/null || true
    grep -A 15 "AFTER FAILOVER - Complete Connection Table" "${ITERATION_LOG}" > "${RESULTS_DIR}/iteration_${iteration}_table_after.txt" 2>/dev/null || true
    
    echo "[$(date +%H:%M:%S)] Iteration ${iteration} completed"
    echo ""
    
    # Wait between iterations
    if [ ${iteration} -lt ${ITERATION_COUNT} ]; then
        echo "Waiting 30 seconds before next iteration..."
        sleep 30
    fi
}

# Main execution
echo "Starting ${ITERATION_COUNT} iteration test sequence..."
echo ""

# Ensure all QMs are running before starting
echo "Ensuring all Queue Managers are running..."
for qm in qm1 qm2 qm3; do
    docker start ${qm} 2>/dev/null || true
done
sleep 10

# Run iterations
for i in $(seq 1 ${ITERATION_COUNT}); do
    run_failover_iteration ${i}
done

# Generate summary report
echo ""
echo "=============================================================================="
echo "                           TEST SUMMARY REPORT"
echo "=============================================================================="
echo ""

cat > "${RESULTS_DIR}/COMPLETE_TEST_SUMMARY.md" << EOF
# Spring Boot MQ Failover Test - 5 Iterations Complete Summary

## Test Execution Details
- **Test Date**: $(date)
- **Test Directory**: ${TEST_DIR}
- **Results Directory**: ${RESULTS_DIR}
- **Total Iterations**: ${ITERATION_COUNT}

## Evidence Collected Per Iteration
1. **JMS Application Logs**: iteration_X_complete.log
2. **JMS Debug Traces**: iteration_X_jms_trace.log
3. **MQSC Connection Data**: iteration_X_BEFORE/AFTER_qmX_mqsc.log
4. **Network Captures**: capture_iter_X.pcap
5. **Connection Tables**: iteration_X_table_before/after.txt

## Iteration Results
EOF

# Parse and add results for each iteration
for i in $(seq 1 ${ITERATION_COUNT}); do
    echo "" >> "${RESULTS_DIR}/COMPLETE_TEST_SUMMARY.md"
    echo "### Iteration ${i}" >> "${RESULTS_DIR}/COMPLETE_TEST_SUMMARY.md"
    
    if [ -f "${RESULTS_DIR}/iteration_${i}_complete.log" ]; then
        # Extract key information
        echo '```' >> "${RESULTS_DIR}/COMPLETE_TEST_SUMMARY.md"
        grep -A 20 "BEFORE FAILOVER - Complete Connection Table" "${RESULTS_DIR}/iteration_${i}_complete.log" 2>/dev/null | head -25 >> "${RESULTS_DIR}/COMPLETE_TEST_SUMMARY.md" || echo "Before table not found" >> "${RESULTS_DIR}/COMPLETE_TEST_SUMMARY.md"
        echo '```' >> "${RESULTS_DIR}/COMPLETE_TEST_SUMMARY.md"
        
        echo "" >> "${RESULTS_DIR}/COMPLETE_TEST_SUMMARY.md"
        echo '```' >> "${RESULTS_DIR}/COMPLETE_TEST_SUMMARY.md"
        grep -A 20 "AFTER FAILOVER - Complete Connection Table" "${RESULTS_DIR}/iteration_${i}_complete.log" 2>/dev/null | head -25 >> "${RESULTS_DIR}/COMPLETE_TEST_SUMMARY.md" || echo "After table not found" >> "${RESULTS_DIR}/COMPLETE_TEST_SUMMARY.md"
        echo '```' >> "${RESULTS_DIR}/COMPLETE_TEST_SUMMARY.md"
    fi
done

echo "" >> "${RESULTS_DIR}/COMPLETE_TEST_SUMMARY.md"
echo "## Test Completion" >> "${RESULTS_DIR}/COMPLETE_TEST_SUMMARY.md"
echo "- **End Time**: $(date)" >> "${RESULTS_DIR}/COMPLETE_TEST_SUMMARY.md"
echo "- **Status**: COMPLETE" >> "${RESULTS_DIR}/COMPLETE_TEST_SUMMARY.md"

echo ""
echo "=============================================================================="
echo "                         ALL TESTS COMPLETED"
echo "=============================================================================="
echo "Results saved to: ${RESULTS_DIR}"
echo "Summary report: ${RESULTS_DIR}/COMPLETE_TEST_SUMMARY.md"
echo ""
echo "Files collected per iteration:"
echo "  - Complete application log with full CONNTAG tables"
echo "  - JMS debug and trace logs"
echo "  - MQSC connection evidence (before and after)"
echo "  - Network packet captures (tcpdump)"
echo "  - Extracted connection tables"
echo ""
echo "End Time: $(date)"
echo "=============================================================================="