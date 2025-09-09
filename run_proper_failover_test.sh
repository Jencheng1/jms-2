#!/bin/bash

echo "=========================================="
echo "IBM MQ UNIFORM CLUSTER PROPER FAILOVER TEST"
echo "=========================================="
echo ""

TIMESTAMP=$(date +%s%3N)
OUTPUT_DIR="failover_test_${TIMESTAMP}"
mkdir -p "$OUTPUT_DIR"

echo "Test output directory: $OUTPUT_DIR"
echo ""

# Function to identify which QM has the most connections
identify_target_qm() {
    echo "Identifying which QM has the most connections..."
    
    # Run a quick test to see connection distribution
    docker run --rm --network mq-uniform-cluster_mqnet \
        -v /home/ec2-user/unified/demo5/mq-uniform-cluster:/app \
        -v /home/ec2-user/unified/demo5/mq-uniform-cluster/libs:/libs \
        -v /home/ec2-user/unified/demo5/mq-uniform-cluster/mq/ccdt:/workspace/ccdt \
        openjdk:17 java -cp "/app:/libs/*" UniformClusterDualConnectionTest 2>&1 | \
        tee "$OUTPUT_DIR/initial_test.log" | \
        grep "CONNECTION.*CONNECTED TO" | \
        awk '{print $NF}' | \
        sort | uniq -c | sort -rn | head -1 | awk '{print $2}'
}

# Step 1: Check all QMs are running
echo "Step 1: Checking Queue Manager status..."
docker ps --format "table {{.Names}}\t{{.Status}}" | grep -E "qm1|qm2|qm3"
echo ""

# Step 2: Identify target QM
echo "Step 2: Running initial test to identify target QM..."
TARGET_QM=$(identify_target_qm)

if [ -z "$TARGET_QM" ]; then
    echo "ERROR: Could not identify target QM"
    exit 1
fi

echo "Target QM identified: $TARGET_QM (will have 6 connections)"
echo ""

# Step 3: Start the failover test in background
echo "Step 3: Starting failover test..."
docker run --rm --network mq-uniform-cluster_mqnet \
    -v /home/ec2-user/unified/demo5/mq-uniform-cluster:/app \
    -v /home/ec2-user/unified/demo5/mq-uniform-cluster/libs:/libs \
    -v /home/ec2-user/unified/demo5/mq-uniform-cluster/mq/ccdt:/workspace/ccdt \
    openjdk:17 java -cp "/app:/libs/*" UniformClusterFailoverTest \
    > "$OUTPUT_DIR/failover_test.log" 2>&1 &

TEST_PID=$!
echo "Failover test started with PID: $TEST_PID"
echo ""

# Step 4: Wait for connections to establish
echo "Step 4: Waiting 20 seconds for connections to establish..."
sleep 20

# Step 5: Capture pre-failover state
echo "Step 5: Capturing pre-failover MQSC state..."
for qm in qm1 qm2 qm3; do
    echo "Checking $qm..." | tee -a "$OUTPUT_DIR/pre_failover_mqsc.log"
    docker exec $qm bash -c "echo 'DIS CONN(*) WHERE(APPLTAG LK FAILOVER*) ALL' | runmqsc ${qm^^}" \
        >> "$OUTPUT_DIR/pre_failover_mqsc.log" 2>&1
done

# Step 6: Stop the target QM
echo ""
echo "Step 6: STOPPING $TARGET_QM to trigger failover..."
docker stop $TARGET_QM
echo "$TARGET_QM stopped at $(date)"
echo ""

# Step 7: Wait for reconnection
echo "Step 7: Waiting 45 seconds for reconnection..."
sleep 45

# Step 8: Capture post-failover state
echo "Step 8: Capturing post-failover MQSC state..."
for qm in qm1 qm2 qm3; do
    if [ "$qm" != "$TARGET_QM" ]; then
        echo "Checking $qm..." | tee -a "$OUTPUT_DIR/post_failover_mqsc.log"
        docker exec $qm bash -c "echo 'DIS CONN(*) WHERE(APPLTAG LK FAILOVER*) ALL' | runmqsc ${qm^^}" \
            >> "$OUTPUT_DIR/post_failover_mqsc.log" 2>&1
    fi
done

# Step 9: Wait for test completion
echo ""
echo "Step 9: Waiting for test to complete..."
wait $TEST_PID

# Step 10: Restart the stopped QM
echo ""
echo "Step 10: Restarting $TARGET_QM..."
docker start $TARGET_QM
sleep 5

# Step 11: Analyze results
echo ""
echo "Step 11: Analyzing results..."
echo "========================================" | tee "$OUTPUT_DIR/analysis.txt"
echo "FAILOVER TEST ANALYSIS" | tee -a "$OUTPUT_DIR/analysis.txt"
echo "========================================" | tee -a "$OUTPUT_DIR/analysis.txt"
echo "" | tee -a "$OUTPUT_DIR/analysis.txt"

# Check for CONNECTION_ID changes
echo "Checking for CONNECTION_ID changes..." | tee -a "$OUTPUT_DIR/analysis.txt"
grep "CONNECTION 1 POST-FAILOVER STATE" "$OUTPUT_DIR/failover_test.log" -A 4 | tee -a "$OUTPUT_DIR/analysis.txt"
echo "" | tee -a "$OUTPUT_DIR/analysis.txt"
grep "CONNECTION 2 POST-FAILOVER STATE" "$OUTPUT_DIR/failover_test.log" -A 4 | tee -a "$OUTPUT_DIR/analysis.txt"
echo "" | tee -a "$OUTPUT_DIR/analysis.txt"

# Check for CONNTAG changes
echo "Checking CONNTAG preservation..." | tee -a "$OUTPUT_DIR/analysis.txt"
grep "CONNTAG Changed:" "$OUTPUT_DIR/failover_test.log" | tee -a "$OUTPUT_DIR/analysis.txt"
echo "" | tee -a "$OUTPUT_DIR/analysis.txt"

# Check reconnection status
echo "Checking reconnection status..." | tee -a "$OUTPUT_DIR/analysis.txt"
grep "Automatic reconnection:" "$OUTPUT_DIR/failover_test.log" | tee -a "$OUTPUT_DIR/analysis.txt"
grep "Parent-child affinity maintained:" "$OUTPUT_DIR/failover_test.log" | tee -a "$OUTPUT_DIR/analysis.txt"

echo ""
echo "=========================================="
echo "FAILOVER TEST COMPLETED"
echo "=========================================="
echo ""
echo "Results saved in: $OUTPUT_DIR/"
echo "  - initial_test.log: Initial connection distribution"
echo "  - failover_test.log: Complete failover test output"
echo "  - pre_failover_mqsc.log: MQSC state before failover"
echo "  - post_failover_mqsc.log: MQSC state after failover"
echo "  - analysis.txt: Test analysis summary"
echo ""
echo "Target QM that was stopped: $TARGET_QM"

# Show files created
echo ""
echo "Files created:"
ls -la "$OUTPUT_DIR/"