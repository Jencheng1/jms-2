#!/bin/bash

echo "================================================"
echo "PROVING FAILOVER ACTUALLY CHANGES QUEUE MANAGER"
echo "================================================"
echo ""

TIMESTAMP=$(date +%s%3N)
TRACKING_KEY="PROVE-${TIMESTAMP}"

# Function to check which QM has connections
check_qms() {
    local tag=$1
    echo "Checking for APPLTAG: $tag"
    for qm in qm1 qm2 qm3; do
        count=$(docker exec $qm bash -c "echo 'DIS CONN(*) WHERE(APPLTAG EQ $tag)' | runmqsc ${qm^^}" 2>/dev/null | grep -c "AMQ8276I" || echo 0)
        if [ $count -gt 0 ]; then
            echo "  ✓ $qm: $count connections"
            INITIAL_QM=$qm
        else
            echo "  - $qm: 0 connections"
        fi
    done
}

# Step 1: Start the test
echo "Step 1: Starting connection test with tracking key: $TRACKING_KEY"
docker run --rm --network mq-uniform-cluster_mqnet \
    -v /home/ec2-user/unified/demo5/mq-uniform-cluster:/app \
    -v /home/ec2-user/unified/demo5/mq-uniform-cluster/libs:/libs \
    -v /home/ec2-user/unified/demo5/mq-uniform-cluster/mq/ccdt:/workspace/ccdt \
    openjdk:17 java -cp "/app:/libs/*" \
    -Dcom.ibm.mq.traceLevel=5 \
    VerifyActualFailover > verify_failover_${TIMESTAMP}.log 2>&1 &

TEST_PID=$!
echo "Test started with PID: $TEST_PID"
echo ""

# Step 2: Wait for initial connection
echo "Step 2: Waiting for initial connection..."
sleep 10

# Step 3: Check initial QM
echo ""
echo "Step 3: Checking INITIAL Queue Manager:"
check_qms "VERIFY-*"
echo ""

if [ -z "$INITIAL_QM" ]; then
    echo "ERROR: No initial connection found"
    kill $TEST_PID 2>/dev/null
    exit 1
fi

echo "Initial QM identified: $INITIAL_QM"
echo ""

# Step 4: Stop the initial QM
echo "Step 4: STOPPING $INITIAL_QM to trigger failover..."
docker stop $INITIAL_QM
echo "$INITIAL_QM stopped"
echo ""

# Step 5: Wait for reconnection
echo "Step 5: Waiting 30 seconds for reconnection..."
for i in {30..1}; do
    echo -ne "\r  Waiting: $i seconds remaining...  "
    sleep 1
done
echo ""
echo ""

# Step 6: Check new QM
echo "Step 6: Checking NEW Queue Manager after failover:"
NEW_QM=""
for qm in qm1 qm2 qm3; do
    if [ "$qm" != "$INITIAL_QM" ]; then
        count=$(docker exec $qm bash -c "echo 'DIS CONN(*) WHERE(APPLTAG LK VERIFY*)' | runmqsc ${qm^^}" 2>/dev/null | grep -c "AMQ8276I" || echo 0)
        if [ $count -gt 0 ]; then
            echo "  ✓ $qm: $count connections (NEW!)"
            NEW_QM=$qm
        else
            echo "  - $qm: 0 connections"
        fi
    else
        echo "  - $qm: STOPPED"
    fi
done
echo ""

# Step 7: Analysis
echo "================================================"
echo "FAILOVER ANALYSIS"
echo "================================================"
if [ ! -z "$NEW_QM" ] && [ "$NEW_QM" != "$INITIAL_QM" ]; then
    echo "✅ SUCCESS: Connection moved from $INITIAL_QM to $NEW_QM"
    echo "This proves that failover DOES change the Queue Manager!"
else
    echo "⚠️  Connection not found on different QM"
    echo "Checking if still on original (shouldn't be possible if stopped)..."
fi
echo ""

# Step 8: Show detailed connection info
echo "Detailed connection information:"
if [ ! -z "$NEW_QM" ]; then
    echo "Connections on $NEW_QM:"
    docker exec $NEW_QM bash -c "echo 'DIS CONN(*) WHERE(APPLTAG LK VERIFY*) ALL' | runmqsc ${NEW_QM^^}" 2>/dev/null | \
        grep -E "CONN\(|CHANNEL\(|CONNAME\(|APPLTAG\(|CONNTAG\(" | head -20
fi
echo ""

# Step 9: Restart stopped QM
echo "Step 7: Restarting $INITIAL_QM..."
docker start $INITIAL_QM
echo ""

# Wait for test to complete
echo "Waiting for test to complete..."
wait $TEST_PID

echo "================================================"
echo "CONCLUSION"
echo "================================================"
echo "Initial QM: $INITIAL_QM"
echo "New QM after failover: $NEW_QM"
if [ "$INITIAL_QM" != "$NEW_QM" ] && [ ! -z "$NEW_QM" ]; then
    echo ""
    echo "✅ FAILOVER SUCCESSFUL - Queue Manager CHANGED!"
    echo "The JMS API may cache old values, but MQSC proves the actual change."
else
    echo ""
    echo "⚠️  Unexpected result - check logs"
fi
echo ""
echo "Log file: verify_failover_${TIMESTAMP}.log"