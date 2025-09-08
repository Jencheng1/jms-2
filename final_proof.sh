#!/bin/bash

echo "=========================================================================="
echo "FINAL PARENT-CHILD CORRELATION PROOF"
echo "=========================================================================="
echo ""

# Compile
javac -cp "libs/*:." SimpleProof.java

# Run in background
docker run --rm --network mq-uniform-cluster_mqnet \
    -v "/home/ec2-user/unified/demo5/mq-uniform-cluster:/app" \
    -v "/home/ec2-user/unified/demo5/mq-uniform-cluster/libs:/libs" \
    -w /app \
    openjdk:17 \
    java -cp "/libs/*:." SimpleProof 2>&1 | tee final_proof_output.log &

JAVA_PID=$!

# Wait for startup
sleep 5

# Extract APPTAG
APPTAG=$(grep "APPTAG:" final_proof_output.log | head -1 | cut -d: -f2 | tr -d ' ')

if [ -z "$APPTAG" ]; then
    echo "Failed to extract APPTAG"
    exit 1
fi

echo "Monitoring connections for APPTAG: $APPTAG"
echo ""

# Monitor for 60 seconds
for i in {1..6}; do
    echo "=== Check $i/6 ($(date +%H:%M:%S)) ==="
    
    # Count connections
    COUNT=$(docker exec qm1 bash -c "echo 'DIS CONN(*) WHERE(APPLTAG EQ $APPTAG)' | runmqsc QM1" | grep -c "CONN(")
    echo "Connections found: $COUNT (Expected: 6)"
    
    # Get details if we have connections
    if [ "$COUNT" -gt "0" ]; then
        echo ""
        echo "Connection details:"
        docker exec qm1 bash -c "echo 'DIS CONN(*) WHERE(APPLTAG EQ $APPTAG) ALL' | runmqsc QM1" > apptag_connections_$i.log
        grep -E "CONN\(|PID\(|TID\(" apptag_connections_$i.log | head -10
    fi
    
    echo ""
    sleep 10
done

# Final capture
echo "=========================================================================="
echo "FINAL RESULTS"
echo "=========================================================================="
docker exec qm1 bash -c "echo 'DIS CONN(*) WHERE(APPLTAG EQ $APPTAG) ALL' | runmqsc QM1" > final_apptag_connections.log

FINAL_COUNT=$(grep -c "CONN(" final_apptag_connections.log)
echo "APPTAG: $APPTAG"
echo "Total connections found: $FINAL_COUNT"
echo "Expected: 6 (1 parent + 5 child sessions)"

if [ "$FINAL_COUNT" -eq "6" ]; then
    echo ""
    echo "✓✓✓ SUCCESS: Parent-child correlation PROVEN! ✓✓✓"
    echo "1 JMS Connection + 5 Sessions = 6 MQ Connections"
else
    echo ""
    echo "Unexpected count - checking details..."
fi

echo ""
echo "Full details saved to: final_apptag_connections.log"
echo "=========================================================================="

wait $JAVA_PID