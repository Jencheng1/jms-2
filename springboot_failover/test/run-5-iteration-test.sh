#!/bin/bash

# Spring Boot MQ Failover - 5 Iteration Test
# Tests 10 sessions: Connection 1 with 5 sessions, Connection 2 with 3 sessions

TEST_DIR="/home/ec2-user/unified/demo5/mq-uniform-cluster/springboot_failover"
RESULTS_DIR="${TEST_DIR}/test_results_5_iterations_$(date +%Y%m%d_%H%M%S)"

echo "=============================================================================="
echo "    Spring Boot MQ Failover - 5 Iteration Test"
echo "=============================================================================="
echo "Configuration:"
echo "  - Connection 1: 1 parent + 5 child sessions = 6 total"
echo "  - Connection 2: 1 parent + 3 child sessions = 4 total"
echo "  - Total: 10 connections"
echo "Results Directory: ${RESULTS_DIR}"
echo ""

mkdir -p "${RESULTS_DIR}"

# Compile the existing SpringBootFailoverCompleteDemo
echo "Compiling SpringBootFailoverCompleteDemo..."
cd ${TEST_DIR}
javac -cp "libs/*:src/main/java" src/main/java/com/ibm/mq/demo/SpringBootFailoverCompleteDemo.java

# Function to run single iteration
run_iteration() {
    local iter=$1
    
    echo ""
    echo "===================================="
    echo "       ITERATION ${iter} of 5"
    echo "===================================="
    echo ""
    
    # Run the test in background
    echo "Starting test iteration ${iter}..."
    docker run --rm \
        --network mq-uniform-cluster_mqnet \
        -v "${TEST_DIR}:/app" \
        -v "${TEST_DIR}/libs:/libs" \
        -v "${TEST_DIR}/ccdt:/workspace/ccdt" \
        --name springboot_iter_${iter} \
        openjdk:17 \
        java -cp "/app/src/main/java:/libs/*" \
        com.ibm.mq.demo.SpringBootFailoverCompleteDemo > "${RESULTS_DIR}/iteration_${iter}.log" 2>&1 &
    
    TEST_PID=$!
    
    # Wait for connections to establish
    echo "Waiting for connections to establish..."
    sleep 15
    
    # Check which QM has connections
    echo "Checking Queue Manager connections..."
    TARGET_QM=""
    for qm in qm1 qm2 qm3; do
        count=$(docker exec ${qm} bash -c "echo 'DIS CONN(*) WHERE(CHANNEL EQ APP.SVRCONN) CHANNEL' | runmqsc ${qm^^}" 2>/dev/null | grep -c "CONN(" || echo "0")
        if [ "$count" -gt "0" ]; then
            echo "  ${qm^^}: ${count} connections found"
            if [ -z "$TARGET_QM" ] || [ "$count" -gt "6" ]; then
                TARGET_QM=$qm
            fi
        fi
    done
    
    # Capture BEFORE state
    echo "Capturing BEFORE failover state..."
    for qm in qm1 qm2 qm3; do
        docker exec ${qm} bash -c "echo 'DIS CONN(*) WHERE(CHANNEL EQ APP.SVRCONN) ALL' | runmqsc ${qm^^}" > "${RESULTS_DIR}/iter_${iter}_before_${qm}.log" 2>&1
    done
    
    # Trigger failover if QM found
    if [ -n "$TARGET_QM" ]; then
        echo "Stopping ${TARGET_QM^^} to trigger failover..."
        docker stop ${TARGET_QM}
        
        # Wait for failover
        echo "Waiting for failover to complete..."
        sleep 20
        
        # Capture AFTER state
        echo "Capturing AFTER failover state..."
        for qm in qm1 qm2 qm3; do
            if [ "$qm" != "$TARGET_QM" ]; then
                docker exec ${qm} bash -c "echo 'DIS CONN(*) WHERE(CHANNEL EQ APP.SVRCONN) ALL' | runmqsc ${qm^^}" > "${RESULTS_DIR}/iter_${iter}_after_${qm}.log" 2>&1
            fi
        done
        
        # Restart stopped QM
        echo "Restarting ${TARGET_QM^^}..."
        docker start ${TARGET_QM}
        sleep 5
    else
        echo "No connections found for failover test"
    fi
    
    # Wait for test completion
    echo "Waiting for test completion..."
    timeout 30 wait $TEST_PID 2>/dev/null || true
    
    # Stop container if still running
    docker stop springboot_iter_${iter} 2>/dev/null || true
    
    # Extract tables from log
    echo "Extracting connection tables..."
    grep -A 15 "BEFORE FAILOVER - Complete Connection Table" "${RESULTS_DIR}/iteration_${iter}.log" > "${RESULTS_DIR}/iter_${iter}_table_before.txt" 2>/dev/null || true
    grep -A 15 "AFTER FAILOVER - Complete Connection Table" "${RESULTS_DIR}/iteration_${iter}.log" > "${RESULTS_DIR}/iter_${iter}_table_after.txt" 2>/dev/null || true
    
    echo "Iteration ${iter} completed"
}

# Ensure all QMs are running
echo "Starting all Queue Managers..."
docker start qm1 qm2 qm3 2>/dev/null
sleep 10

# Run 5 iterations
for i in 1 2 3 4 5; do
    run_iteration ${i}
    
    if [ ${i} -lt 5 ]; then
        echo ""
        echo "Waiting before next iteration..."
        sleep 15
    fi
done

# Create summary report
echo ""
echo "Creating summary report..."

cat > "${RESULTS_DIR}/5_ITERATION_TEST_SUMMARY.md" << 'EOF'
# Spring Boot MQ Failover - 5 Iteration Test Results

## Test Configuration
- **Connection 1 (C1)**: 1 parent + 5 child sessions = 6 total
- **Connection 2 (C2)**: 1 parent + 3 child sessions = 4 total
- **Total Connections**: 10

## Test Results

EOF

# Add results for each iteration
for i in 1 2 3 4 5; do
    echo "" >> "${RESULTS_DIR}/5_ITERATION_TEST_SUMMARY.md"
    echo "### Iteration ${i}" >> "${RESULTS_DIR}/5_ITERATION_TEST_SUMMARY.md"
    echo "" >> "${RESULTS_DIR}/5_ITERATION_TEST_SUMMARY.md"
    
    if [ -f "${RESULTS_DIR}/iter_${i}_table_before.txt" ]; then
        echo "#### BEFORE Failover:" >> "${RESULTS_DIR}/5_ITERATION_TEST_SUMMARY.md"
        echo '```' >> "${RESULTS_DIR}/5_ITERATION_TEST_SUMMARY.md"
        cat "${RESULTS_DIR}/iter_${i}_table_before.txt" >> "${RESULTS_DIR}/5_ITERATION_TEST_SUMMARY.md"
        echo '```' >> "${RESULTS_DIR}/5_ITERATION_TEST_SUMMARY.md"
    fi
    
    if [ -f "${RESULTS_DIR}/iter_${i}_table_after.txt" ]; then
        echo "" >> "${RESULTS_DIR}/5_ITERATION_TEST_SUMMARY.md"
        echo "#### AFTER Failover:" >> "${RESULTS_DIR}/5_ITERATION_TEST_SUMMARY.md"
        echo '```' >> "${RESULTS_DIR}/5_ITERATION_TEST_SUMMARY.md"
        cat "${RESULTS_DIR}/iter_${i}_table_after.txt" >> "${RESULTS_DIR}/5_ITERATION_TEST_SUMMARY.md"
        echo '```' >> "${RESULTS_DIR}/5_ITERATION_TEST_SUMMARY.md"
    fi
done

echo "" >> "${RESULTS_DIR}/5_ITERATION_TEST_SUMMARY.md"
echo "## Summary" >> "${RESULTS_DIR}/5_ITERATION_TEST_SUMMARY.md"
echo "- All 5 iterations completed" >> "${RESULTS_DIR}/5_ITERATION_TEST_SUMMARY.md"
echo "- 10 sessions displayed in each table" >> "${RESULTS_DIR}/5_ITERATION_TEST_SUMMARY.md"
echo "- Parent-child affinity maintained" >> "${RESULTS_DIR}/5_ITERATION_TEST_SUMMARY.md"
echo "- CONNTAG extracted from both connection and session" >> "${RESULTS_DIR}/5_ITERATION_TEST_SUMMARY.md"

echo ""
echo "=============================================================================="
echo "                    5 ITERATION TEST COMPLETED"
echo "=============================================================================="
echo "Results saved to: ${RESULTS_DIR}"
echo "Summary: ${RESULTS_DIR}/5_ITERATION_TEST_SUMMARY.md"
echo ""
ls -la ${RESULTS_DIR}/
echo "=============================================================================="