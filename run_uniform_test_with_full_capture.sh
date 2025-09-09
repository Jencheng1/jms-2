#!/bin/bash

echo "=========================================================================="
echo "UNIFORM CLUSTER DUAL CONNECTION TEST WITH FULL MQSC CAPTURE"
echo "=========================================================================="
echo "Starting at: $(date)"
echo ""

# Start the Java test in background
echo "Starting Java test application..."
docker run --rm --network mq-uniform-cluster_mqnet \
    -v "/home/ec2-user/unified/demo5/mq-uniform-cluster:/app" \
    -v "/home/ec2-user/unified/demo5/mq-uniform-cluster/libs:/libs" \
    -v "/home/ec2-user/unified/demo5/mq-uniform-cluster/mq/ccdt:/workspace/ccdt" \
    openjdk:17 java -cp "/app:/libs/*" UniformClusterDualConnectionTest > uniform_test_live.log 2>&1 &

JAVA_PID=$!

# Wait for connections to establish
echo "Waiting for connections to establish..."
sleep 8

# Extract tracking key from log
TRACKING_KEY=$(grep "BASE TRACKING KEY:" uniform_test_live.log | awk '{print $NF}')
echo "Tracking Key: $TRACKING_KEY"
echo ""

# Create capture file
CAPTURE_FILE="MQSC_CAPTURE_${TRACKING_KEY}.log"
echo "Capture file: $CAPTURE_FILE"
echo ""

# Function to check connections on a QM
check_qm() {
    local qm_name=$1
    local qm_container=$2
    local tracking=$3
    
    echo "=== Checking $qm_name ===" | tee -a $CAPTURE_FILE
    
    # Check for Connection 1
    echo "Connection 1 (${tracking}-C1) on $qm_name:" | tee -a $CAPTURE_FILE
    docker exec $qm_container bash -c "echo 'DIS CONN(*) WHERE(APPLTAG EQ '\''${tracking}-C1'\'') ALL' | runmqsc $qm_name" 2>/dev/null | grep -E "CONN\(|APPLTAG\(|EXTCONN\(|CONNAME\(|PID\(|TID\(" | tee -a $CAPTURE_FILE || echo "  No connections found" | tee -a $CAPTURE_FILE
    
    C1_COUNT=$(docker exec $qm_container bash -c "echo 'DIS CONN(*) WHERE(APPLTAG EQ '\''${tracking}-C1'\'') ALL' | runmqsc $qm_name" 2>/dev/null | grep -c "CONN(" || echo "0")
    echo "  Count: $C1_COUNT connections" | tee -a $CAPTURE_FILE
    echo "" | tee -a $CAPTURE_FILE
    
    # Check for Connection 2
    echo "Connection 2 (${tracking}-C2) on $qm_name:" | tee -a $CAPTURE_FILE
    docker exec $qm_container bash -c "echo 'DIS CONN(*) WHERE(APPLTAG EQ '\''${tracking}-C2'\'') ALL' | runmqsc $qm_name" 2>/dev/null | grep -E "CONN\(|APPLTAG\(|EXTCONN\(|CONNAME\(|PID\(|TID\(" | tee -a $CAPTURE_FILE || echo "  No connections found" | tee -a $CAPTURE_FILE
    
    C2_COUNT=$(docker exec $qm_container bash -c "echo 'DIS CONN(*) WHERE(APPLTAG EQ '\''${tracking}-C2'\'') ALL' | runmqsc $qm_name" 2>/dev/null | grep -c "CONN(" || echo "0")
    echo "  Count: $C2_COUNT connections" | tee -a $CAPTURE_FILE
    echo "" | tee -a $CAPTURE_FILE
    
    return $((C1_COUNT + C2_COUNT))
}

# Perform multiple captures
for capture_num in {1..6}; do
    echo "=========================================================================="
    echo "CAPTURE $capture_num at $(date)"
    echo "=========================================================================="
    
    # Check all three QMs
    check_qm "QM1" "qm1" "$TRACKING_KEY"
    QM1_TOTAL=$?
    
    check_qm "QM2" "qm2" "$TRACKING_KEY"
    QM2_TOTAL=$?
    
    check_qm "QM3" "qm3" "$TRACKING_KEY"
    QM3_TOTAL=$?
    
    echo "SUMMARY:"
    echo "  QM1: $QM1_TOTAL connections"
    echo "  QM2: $QM2_TOTAL connections"
    echo "  QM3: $QM3_TOTAL connections"
    echo "  Total: $((QM1_TOTAL + QM2_TOTAL + QM3_TOTAL)) connections (expecting 10)"
    echo ""
    
    # Detailed EXTCONN analysis for active QMs
    echo "EXTCONN ANALYSIS:" | tee -a $CAPTURE_FILE
    if [ $QM1_TOTAL -gt 0 ]; then
        echo "QM1 EXTCONNs:" | tee -a $CAPTURE_FILE
        docker exec qm1 bash -c "echo 'DIS CONN(*) WHERE(APPLTAG LK '\''${TRACKING_KEY}*'\'') ALL' | runmqsc QM1" 2>/dev/null | grep "EXTCONN" | sort -u | tee -a $CAPTURE_FILE
    fi
    
    if [ $QM2_TOTAL -gt 0 ]; then
        echo "QM2 EXTCONNs:" | tee -a $CAPTURE_FILE
        docker exec qm2 bash -c "echo 'DIS CONN(*) WHERE(APPLTAG LK '\''${TRACKING_KEY}*'\'') ALL' | runmqsc QM2" 2>/dev/null | grep "EXTCONN" | sort -u | tee -a $CAPTURE_FILE
    fi
    
    if [ $QM3_TOTAL -gt 0 ]; then
        echo "QM3 EXTCONNs:" | tee -a $CAPTURE_FILE
        docker exec qm3 bash -c "echo 'DIS CONN(*) WHERE(APPLTAG LK '\''${TRACKING_KEY}*'\'') ALL' | runmqsc QM3" 2>/dev/null | grep "EXTCONN" | sort -u | tee -a $CAPTURE_FILE
    fi
    
    echo ""
    
    if [ $capture_num -lt 6 ]; then
        sleep 20
    fi
done

# Wait for Java process to complete
echo "Waiting for test to complete..."
wait $JAVA_PID

echo ""
echo "=========================================================================="
echo "TEST COMPLETED"
echo "=========================================================================="
echo "Java log: uniform_test_live.log"
echo "MQSC capture: $CAPTURE_FILE"
echo ""

# Final analysis
echo "FINAL ANALYSIS:"
cat uniform_test_live.log | grep -E "CONNECTION .* CONNECTED TO:|Different QMs:|Distribution:"

echo ""
echo "Done!"