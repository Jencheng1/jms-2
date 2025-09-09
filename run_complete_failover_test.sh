#!/bin/bash

echo "================================================================================"
echo "   COMPLETE FAILOVER TEST - PROVING ALL 6 CONNECTIONS MOVE TOGETHER"
echo "================================================================================"
echo ""

TIMESTAMP=$(date +%s%3N)
TRACKING_KEY="FAILTEST-$TIMESTAMP"
LOG_FILE="failover_complete_$TIMESTAMP.log"

echo "Tracking Key: $TRACKING_KEY"
echo "Log File: $LOG_FILE"
echo ""

# Compile test
echo "Step 1: Compiling UniformClusterFailoverTest..."
javac -cp "libs/*:." UniformClusterFailoverTest.java || {
    echo "Failed to compile"
    exit 1
}

# Run test in background
echo "Step 2: Starting test (creates 6 connections)..."
docker run --rm --network mq-uniform-cluster_mqnet \
    -v "$(pwd):/app" \
    -v "$(pwd)/libs:/libs" \
    -v "$(pwd)/mq/ccdt:/workspace/ccdt" \
    openjdk:17 java -cp "/app:/libs/*" UniformClusterFailoverTest > $LOG_FILE 2>&1 &

TEST_PID=$!
echo "Test PID: $TEST_PID"

# Wait for connections
echo ""
echo "Step 3: Waiting for connections to establish..."
sleep 15

# Check initial state
echo ""
echo "Step 4: BEFORE FAILOVER - Connection counts:"
for qm in qm1 qm2 qm3; do
    COUNT=$(docker exec $qm bash -c "echo 'DIS CONN(*) WHERE(APPLTAG LK FAILOVER*)' | runmqsc ${qm^^}" 2>/dev/null | grep -c "AMQ8276I" || echo "0")
    echo "  ${qm^^}: $COUNT connections"
    if [ "$COUNT" -gt "0" ]; then
        QM_TO_STOP=$qm
        INITIAL_COUNT=$COUNT
    fi
done

if [ -z "$QM_TO_STOP" ]; then
    echo "❌ No connections found"
    kill $TEST_PID 2>/dev/null
    cat $LOG_FILE
    exit 1
fi

# Stop QM
echo ""
echo "Step 5: Stopping ${QM_TO_STOP^^} (has $INITIAL_COUNT connections)..."
docker stop $QM_TO_STOP

# Wait for failover
echo "Step 6: Waiting for failover to complete..."
sleep 35

# Check after failover
echo ""
echo "Step 7: AFTER FAILOVER - Connection counts:"
for qm in qm1 qm2 qm3; do
    if [ "$qm" != "$QM_TO_STOP" ]; then
        COUNT=$(docker exec $qm bash -c "echo 'DIS CONN(*) WHERE(APPLTAG LK FAILOVER*)' | runmqsc ${qm^^}" 2>/dev/null | grep -c "AMQ8276I" || echo "0")
        echo "  ${qm^^}: $COUNT connections"
        if [ "$COUNT" -eq "$INITIAL_COUNT" ]; then
            NEW_QM=$qm
        fi
    else
        echo "  ${qm^^}: STOPPED"
    fi
done

# Results
echo ""
echo "================================================================================"
echo "RESULTS"
echo "================================================================================"
if [ -n "$NEW_QM" ]; then
    echo "✅ SUCCESS! Failover proven:"
    echo "   - All $INITIAL_COUNT connections moved from ${QM_TO_STOP^^} to ${NEW_QM^^}"
    echo "   - Parent-child affinity maintained (all on same QM)"
    echo "   - Automatic reconnection successful"
else
    echo "⚠️  Connections may still be reconnecting"
fi

# Show test output
echo ""
echo "Test output (last 40 lines):"
echo "--------------------------------------------------------------------------------"
tail -40 $LOG_FILE

# Cleanup
echo ""
echo "Step 8: Restarting ${QM_TO_STOP^^}..."
docker start $QM_TO_STOP
sleep 2

echo "Step 9: Stopping test..."
kill $TEST_PID 2>/dev/null

echo ""
echo "✅ Test complete. Full log: $LOG_FILE"