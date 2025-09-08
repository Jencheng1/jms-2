#!/bin/bash

echo "Starting Direct Connection Proof and live monitoring..."
echo ""

# Compile first
javac -cp "libs/*:." DirectConnectionProof.java

# Run Java app and capture output
docker run --rm --network mq-uniform-cluster_mqnet \
    -v "$(pwd):/app" \
    -v "$(pwd)/libs:/libs" \
    -w /app \
    openjdk:17 \
    java -cp "/libs/*:." DirectConnectionProof 2>&1 | tee direct_proof.log &

# Store PID
JAVA_PID=$!

# Wait for app to start and extract APPTAG
sleep 3
APPTAG=$(grep "APPTAG:" direct_proof.log | tail -1 | cut -d: -f2 | tr -d ' ')

if [ -z "$APPTAG" ]; then
    echo "Failed to extract APPTAG"
    exit 1
fi

echo "Monitoring connections for APPTAG: $APPTAG"
echo ""

# Monitor connections every 5 seconds for 60 seconds
for i in {1..12}; do
    echo "==================== Check $i/12 ($(date +%H:%M:%S)) ===================="
    
    # Count connections with our APPTAG
    COUNT=$(docker exec qm1 bash -c "echo 'DIS CONN(*) WHERE(APPLTAG EQ $APPTAG)' | runmqsc QM1" | grep -c "CONN(")
    echo "Connections with APPTAG=$APPTAG: $COUNT"
    
    if [ "$COUNT" -gt "0" ]; then
        echo ""
        echo "Connection details:"
        docker exec qm1 bash -c "echo 'DIS CONN(*) WHERE(APPLTAG EQ $APPTAG) ALL' | runmqsc QM1" | \
            grep -E "CONN\(|CONNAME\(|PID\(|TID\(|CONNTAG\(" | head -20
    fi
    
    echo ""
    sleep 5
done

# Final detailed capture
echo ""
echo "==================== FINAL DETAILED CAPTURE ===================="
docker exec qm1 bash -c "echo 'DIS CONN(*) WHERE(APPLTAG EQ $APPTAG) ALL' | runmqsc QM1" > final_connections_$APPTAG.log
echo "Final details saved to: final_connections_$APPTAG.log"

# Count final
FINAL_COUNT=$(grep -c "CONN(" final_connections_$APPTAG.log)
echo ""
echo "SUMMARY:"
echo "  APPTAG: $APPTAG"
echo "  Expected connections: 6 (1 parent + 5 sessions)"
echo "  Actual connections found: $FINAL_COUNT"

if [ "$FINAL_COUNT" -eq "6" ]; then
    echo "  Result: ✓ SUCCESS - Parent-child correlation proven!"
else
    echo "  Result: ✗ Unexpected count"
fi

wait $JAVA_PID
echo ""
echo "Test completed"