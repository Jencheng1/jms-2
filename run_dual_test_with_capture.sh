#\!/bin/bash

echo "Starting Dual Connection Test with MQSC Capture"
echo "================================================"

# Start the Java test in background
docker run --rm --network mq-uniform-cluster_mqnet \
    -v "/home/ec2-user/unified/demo5/mq-uniform-cluster:/app" \
    -v "/home/ec2-user/unified/demo5/mq-uniform-cluster/libs:/libs" \
    -v "/home/ec2-user/unified/demo5/mq-uniform-cluster/mq/ccdt:/workspace/ccdt" \
    openjdk:17 java -cp "/app:/libs/*" QM1DualConnectionTest > dual_test_live.log 2>&1 &

JAVA_PID=$\!

# Wait for connections to establish
sleep 5

# Extract tracking key from log
TRACKING_KEY=$(grep "BASE TRACKING KEY:" dual_test_live.log | awk '{print $NF}')
echo "Tracking Key: $TRACKING_KEY"

# Capture MQSC data periodically
for i in {1..6}; do
    echo ""
    echo "=== MQSC Capture $i at $(date) ==="
    
    echo "Connection 1 (${TRACKING_KEY}-C1):"
    docker exec qm1 bash -c "echo 'DIS CONN(*) WHERE(APPLTAG EQ '\''${TRACKING_KEY}-C1'\'') ALL' | runmqsc QM1" | grep -E "CONN\(|APPLTAG\(|CONNAME\(|PID\(|TID\(|EXTCONN\(" || echo "No connections found"
    
    echo ""
    echo "Connection 2 (${TRACKING_KEY}-C2):"
    docker exec qm1 bash -c "echo 'DIS CONN(*) WHERE(APPLTAG EQ '\''${TRACKING_KEY}-C2'\'') ALL' | runmqsc QM1" | grep -E "CONN\(|APPLTAG\(|CONNAME\(|PID\(|TID\(|EXTCONN\(" || echo "No connections found"
    
    echo ""
    echo "Count summary:"
    C1_COUNT=$(docker exec qm1 bash -c "echo 'DIS CONN(*) WHERE(APPLTAG EQ '\''${TRACKING_KEY}-C1'\'') ALL' | runmqsc QM1" | grep -c "CONN(" || echo "0")
    C2_COUNT=$(docker exec qm1 bash -c "echo 'DIS CONN(*) WHERE(APPLTAG EQ '\''${TRACKING_KEY}-C2'\'') ALL' | runmqsc QM1" | grep -c "CONN(" || echo "0")
    echo "  Connection 1: $C1_COUNT connections"
    echo "  Connection 2: $C2_COUNT connections"
    echo "  Total: $((C1_COUNT + C2_COUNT)) connections"
    
    sleep 20
done

# Wait for Java process to complete
wait $JAVA_PID

echo ""
echo "Test completed\!"
