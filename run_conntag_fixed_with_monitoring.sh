#!/bin/bash

echo "================================================================"
echo "CONNTAG FIXED DISTRIBUTION TEST WITH LIVE MONITORING"
echo "================================================================"
echo ""

# Start the Java test in background
echo "Starting Java test (will run for 120 seconds)..."
docker run --rm --network mq-uniform-cluster_mqnet \
    -v "/home/ec2-user/unified/demo5/mq-uniform-cluster:/app" \
    -v "/home/ec2-user/unified/demo5/mq-uniform-cluster/libs:/libs" \
    -v "/home/ec2-user/unified/demo5/mq-uniform-cluster/mq/ccdt:/workspace/ccdt" \
    openjdk:17 java -cp "/app:/libs/*" UniformClusterConntagFixed 2>&1 &

JAVA_PID=$!

# Wait for connections to establish
echo "Waiting for connections to establish..."
sleep 20

# Get the tracking key from the latest log
TRACKING_KEY=$(ls -t CONNTAG_FIXED_TEST_*.log 2>/dev/null | head -1 | xargs grep "BASE TRACKING KEY:" | awk '{print $NF}')

if [ -z "$TRACKING_KEY" ]; then
    echo "Failed to get tracking key, waiting for process..."
    wait $JAVA_PID
    exit 1
fi

echo "Tracking Key: $TRACKING_KEY"
echo ""

# Create comprehensive evidence file
EVIDENCE_FILE="CONNTAG_EVIDENCE_${TRACKING_KEY}_COMPLETE.txt"

echo "========================================" > $EVIDENCE_FILE
echo "CONNTAG DISTRIBUTION EVIDENCE" >> $EVIDENCE_FILE
echo "========================================" >> $EVIDENCE_FILE
echo "Tracking Key: $TRACKING_KEY" >> $EVIDENCE_FILE
echo "Collection Time: $(date)" >> $EVIDENCE_FILE
echo "" >> $EVIDENCE_FILE

# Collect evidence from all QMs
echo "Collecting CONNTAG evidence from all Queue Managers..."
echo ""

for qm in qm1 qm2 qm3; do
    QM_UPPER=${qm^^}
    
    echo "=== Checking $QM_UPPER ===" | tee -a $EVIDENCE_FILE
    
    # Check for Connection 1
    echo "--- Connection 1 (${TRACKING_KEY}-C1) ---" | tee -a $EVIDENCE_FILE
    C1_RESULT=$(docker exec $qm bash -c "echo 'DIS CONN(*) WHERE(APPLTAG EQ '\\''${TRACKING_KEY}-C1'\\'') ALL' | runmqsc $QM_UPPER" 2>/dev/null)
    
    C1_COUNT=$(echo "$C1_RESULT" | grep -c "CONN(" || echo "0")
    
    if [ "$C1_COUNT" -gt 0 ]; then
        echo "Found $C1_COUNT connections on $QM_UPPER" | tee -a $EVIDENCE_FILE
        
        # Extract CONNTAG patterns
        echo "CONNTAG patterns:" | tee -a $EVIDENCE_FILE
        echo "$C1_RESULT" | grep "CONNTAG(" | head -3 | tee -a $EVIDENCE_FILE
        
        # Extract connection handles
        echo "Connection handles:" | tee -a $EVIDENCE_FILE
        echo "$C1_RESULT" | grep "CONN(" | head -6 | tee -a $EVIDENCE_FILE
    else
        echo "No connections found on $QM_UPPER" | tee -a $EVIDENCE_FILE
    fi
    
    echo "" | tee -a $EVIDENCE_FILE
    
    # Check for Connection 2
    echo "--- Connection 2 (${TRACKING_KEY}-C2) ---" | tee -a $EVIDENCE_FILE
    C2_RESULT=$(docker exec $qm bash -c "echo 'DIS CONN(*) WHERE(APPLTAG EQ '\\''${TRACKING_KEY}-C2'\\'') ALL' | runmqsc $QM_UPPER" 2>/dev/null)
    
    C2_COUNT=$(echo "$C2_RESULT" | grep -c "CONN(" || echo "0")
    
    if [ "$C2_COUNT" -gt 0 ]; then
        echo "Found $C2_COUNT connections on $QM_UPPER" | tee -a $EVIDENCE_FILE
        
        # Extract CONNTAG patterns
        echo "CONNTAG patterns:" | tee -a $EVIDENCE_FILE
        echo "$C2_RESULT" | grep "CONNTAG(" | head -3 | tee -a $EVIDENCE_FILE
        
        # Extract connection handles
        echo "Connection handles:" | tee -a $EVIDENCE_FILE
        echo "$C2_RESULT" | grep "CONN(" | head -4 | tee -a $EVIDENCE_FILE
    else
        echo "No connections found on $QM_UPPER" | tee -a $EVIDENCE_FILE
    fi
    
    echo "" | tee -a $EVIDENCE_FILE
    echo "----------------------------------------" | tee -a $EVIDENCE_FILE
    echo "" | tee -a $EVIDENCE_FILE
done

echo "========================================" | tee -a $EVIDENCE_FILE
echo "CONNTAG ANALYSIS SUMMARY" | tee -a $EVIDENCE_FILE
echo "========================================" | tee -a $EVIDENCE_FILE

# Analyze distribution
C1_QM=""
C2_QM=""

for qm in qm1 qm2 qm3; do
    QM_UPPER=${qm^^}
    C1_CHECK=$(docker exec $qm bash -c "echo 'DIS CONN(*) WHERE(APPLTAG EQ '\\''${TRACKING_KEY}-C1'\\'') ALL' | runmqsc $QM_UPPER" 2>/dev/null | grep -c "CONN(" || echo "0")
    C2_CHECK=$(docker exec $qm bash -c "echo 'DIS CONN(*) WHERE(APPLTAG EQ '\\''${TRACKING_KEY}-C2'\\'') ALL' | runmqsc $QM_UPPER" 2>/dev/null | grep -c "CONN(" || echo "0")
    
    if [ "$C1_CHECK" -gt 0 ]; then
        C1_QM=$QM_UPPER
    fi
    if [ "$C2_CHECK" -gt 0 ]; then
        C2_QM=$QM_UPPER
    fi
done

echo "Connection 1 distributed to: $C1_QM" | tee -a $EVIDENCE_FILE
echo "Connection 2 distributed to: $C2_QM" | tee -a $EVIDENCE_FILE

if [ "$C1_QM" != "$C2_QM" ]; then
    echo "" | tee -a $EVIDENCE_FILE
    echo "✅ SUCCESS: Connections distributed to DIFFERENT Queue Managers!" | tee -a $EVIDENCE_FILE
    echo "This proves CONNTAG will have different QM components" | tee -a $EVIDENCE_FILE
else
    echo "" | tee -a $EVIDENCE_FILE
    echo "⚠️  Both connections on same QM ($C1_QM)" | tee -a $EVIDENCE_FILE
    echo "CONNTAG will differ only in handle and APPLTAG components" | tee -a $EVIDENCE_FILE
fi

echo "" | tee -a $EVIDENCE_FILE
echo "Evidence saved to: $EVIDENCE_FILE" | tee -a $EVIDENCE_FILE

# Wait for Java process to complete
echo ""
echo "Waiting for test to complete..."
wait $JAVA_PID

echo ""
echo "================================================================"
echo "TEST COMPLETE - Check evidence file: $EVIDENCE_FILE"
echo "================================================================"