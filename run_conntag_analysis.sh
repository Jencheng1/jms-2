#!/bin/bash

echo "Starting CONNTAG Analysis Test..."

# Run the Java test in background
docker run --rm --network mq-uniform-cluster_mqnet \
    -v "/home/ec2-user/unified/demo5/mq-uniform-cluster:/app" \
    -v "/home/ec2-user/unified/demo5/mq-uniform-cluster/libs:/libs" \
    -v "/home/ec2-user/unified/demo5/mq-uniform-cluster/mq/ccdt:/workspace/ccdt" \
    openjdk:17 java -cp "/app:/libs/*" UniformClusterConntagAnalysisTest > conntag_test_output.log 2>&1 &

JAVA_PID=$!

# Wait for connections to establish
sleep 10

# Extract tracking key
TRACKING_KEY=$(grep "BASE TRACKING KEY:" conntag_test_output.log | awk '{print $NF}')
echo "Tracking Key: $TRACKING_KEY"

# Create evidence file
EVIDENCE_FILE="CONNTAG_EVIDENCE_${TRACKING_KEY}.txt"

echo "=====================================================================" > $EVIDENCE_FILE
echo "CONNTAG EVIDENCE COLLECTION - $(date)" >> $EVIDENCE_FILE
echo "Tracking Key: $TRACKING_KEY" >> $EVIDENCE_FILE
echo "=====================================================================" >> $EVIDENCE_FILE
echo "" >> $EVIDENCE_FILE

# Check CONNTAG on all QMs
for qm in qm1 qm2 qm3; do
    echo "" | tee -a $EVIDENCE_FILE
    echo "=== ${qm^^} CONNTAG ANALYSIS ===" | tee -a $EVIDENCE_FILE
    
    # Get full connection details
    docker exec $qm bash -c "echo 'DIS CONN(*) WHERE(APPLTAG LK '\''${TRACKING_KEY}*'\'') ALL' | runmqsc ${qm^^}" 2>/dev/null > temp_mqsc.txt
    
    # Count connections
    C1_COUNT=$(grep -c "${TRACKING_KEY}-C1" temp_mqsc.txt || echo "0")
    C2_COUNT=$(grep -c "${TRACKING_KEY}-C2" temp_mqsc.txt || echo "0")
    
    if [ $C1_COUNT -gt 0 ] || [ $C2_COUNT -gt 0 ]; then
        echo "Found connections on ${qm^^}:" | tee -a $EVIDENCE_FILE
        echo "  Connection 1 (C1): $C1_COUNT connections" | tee -a $EVIDENCE_FILE
        echo "  Connection 2 (C2): $C2_COUNT connections" | tee -a $EVIDENCE_FILE
        echo "" | tee -a $EVIDENCE_FILE
        
        # Extract key fields
        echo "CONNTAG Details:" | tee -a $EVIDENCE_FILE
        grep -E "CONN\(|CONNTAG\(|APPLTAG\(|EXTCONN\(" temp_mqsc.txt | head -30 | tee -a $EVIDENCE_FILE
        
        # Extract unique CONNTAG values
        echo "" | tee -a $EVIDENCE_FILE
        echo "Unique CONNTAG patterns:" | tee -a $EVIDENCE_FILE
        grep "CONNTAG(" temp_mqsc.txt | sort -u | tee -a $EVIDENCE_FILE
    else
        echo "No connections found on ${qm^^}" | tee -a $EVIDENCE_FILE
    fi
    
    rm -f temp_mqsc.txt
done

# Wait for test to complete
wait $JAVA_PID 2>/dev/null

echo ""
echo "=====================================================================" | tee -a $EVIDENCE_FILE
echo "CONNTAG ANALYSIS SUMMARY" | tee -a $EVIDENCE_FILE
echo "=====================================================================" | tee -a $EVIDENCE_FILE

# Extract key findings from Java log
echo "" | tee -a $EVIDENCE_FILE
echo "Java Application Analysis:" | tee -a $EVIDENCE_FILE
grep -E "CONNECTION .* CONNECTED TO:|PREDICTED CONNTAG" conntag_test_output.log | tee -a $EVIDENCE_FILE

echo ""
echo "Test completed!"
echo "Evidence file: $EVIDENCE_FILE"
echo "Java log: conntag_test_output.log"