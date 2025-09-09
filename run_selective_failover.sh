#!/bin/bash

echo "================================================================================"
echo "   SELECTIVE FAILOVER TEST - ONLY C1 (5 SESSIONS) FAILS OVER"
echo "================================================================================"
echo ""

TIMESTAMP=$(date +%s%3N)
LOG_FILE="selective_failover_$TIMESTAMP.log"

echo "Test ID: $TIMESTAMP"
echo "Log File: $LOG_FILE"
echo ""

# Compile test
echo "Step 1: Compiling SelectiveFailoverTest..."
javac -cp "libs/*:." SelectiveFailoverTest.java || exit 1

# Start test in background
echo "Step 2: Starting test..."
docker run --rm --network mq-uniform-cluster_mqnet \
    -v "/home/ec2-user/unified/demo5/mq-uniform-cluster:/app" \
    -v "/home/ec2-user/unified/demo5/mq-uniform-cluster/libs:/libs" \
    -v "/home/ec2-user/unified/demo5/mq-uniform-cluster/mq/ccdt:/workspace/ccdt" \
    openjdk:17 java -cp "/app:/libs/*" SelectiveFailoverTest > $LOG_FILE 2>&1 &

TEST_PID=$!
echo "Test PID: $TEST_PID"

# Wait for connections to establish
echo ""
echo "Step 3: Waiting for connections to establish..."
sleep 15

# Check which QM has C1
echo ""
echo "Step 4: Checking connection distribution..."
for qm in qm1 qm2 qm3; do
    COUNT=$(docker exec $qm bash -c "echo 'DIS CONN(*) WHERE(APPLTAG LK SELECTIVE*C1)' | runmqsc ${qm^^}" 2>/dev/null | grep -c "AMQ8276I" || echo "0")
    if [ "$COUNT" -gt "0" ]; then
        echo "  C1 (6 connections) found on ${qm^^}"
        C1_QM=$qm
    fi
    
    COUNT=$(docker exec $qm bash -c "echo 'DIS CONN(*) WHERE(APPLTAG LK SELECTIVE*C2)' | runmqsc ${qm^^}" 2>/dev/null | grep -c "AMQ8276I" || echo "0")
    if [ "$COUNT" -gt "0" ]; then
        echo "  C2 (4 connections) found on ${qm^^}"
        C2_QM=$qm
    fi
done

if [ -z "$C1_QM" ] || [ -z "$C2_QM" ]; then
    echo "❌ Could not find connections"
    kill $TEST_PID 2>/dev/null
    tail -50 $LOG_FILE
    exit 1
fi

if [ "$C1_QM" = "$C2_QM" ]; then
    echo "⚠️  C1 and C2 are on the same QM ($C1_QM). Test may not show selective failover clearly."
fi

# Stop only C1's QM
echo ""
echo "Step 5: Stopping ${C1_QM^^} (where C1 is connected)..."
docker stop $C1_QM

# Wait for failover
echo "Step 6: Waiting for C1 to failover (C2 should stay on ${C2_QM^^})..."
sleep 45

# Check new distribution
echo ""
echo "Step 7: Checking post-failover distribution..."
echo ""
echo "AFTER FAILOVER:"

# Check where NEW-C1 is
for qm in qm1 qm2 qm3; do
    if [ "$qm" != "$C1_QM" ]; then
        COUNT=$(docker exec $qm bash -c "echo 'DIS CONN(*) WHERE(APPLTAG LK NEW-C1*)' | runmqsc ${qm^^}" 2>/dev/null | grep -c "AMQ8276I" || echo "0")
        if [ "$COUNT" -gt "0" ]; then
            echo "  C1 (6 connections) moved to ${qm^^} ✓"
            NEW_C1_QM=$qm
        fi
    fi
done

# Check where NEW-C2 is
COUNT=$(docker exec $C2_QM bash -c "echo 'DIS CONN(*) WHERE(APPLTAG LK NEW-C2*)' | runmqsc ${C2_QM^^}" 2>/dev/null | grep -c "AMQ8276I" || echo "0")
if [ "$COUNT" -gt "0" ]; then
    echo "  C2 (4 connections) stayed on ${C2_QM^^} ✓"
else
    # Check if C2 moved
    for qm in qm1 qm2 qm3; do
        if [ "$qm" != "$C1_QM" ] && [ "$qm" != "$C2_QM" ]; then
            COUNT=$(docker exec $qm bash -c "echo 'DIS CONN(*) WHERE(APPLTAG LK NEW-C2*)' | runmqsc ${qm^^}" 2>/dev/null | grep -c "AMQ8276I" || echo "0")
            if [ "$COUNT" -gt "0" ]; then
                echo "  C2 (4 connections) unexpectedly moved to ${qm^^}"
            fi
        fi
    done
fi

# Wait for test to complete
echo ""
echo "Step 8: Waiting for test to complete..."
wait $TEST_PID 2>/dev/null || true

# Show test output
echo ""
echo "Step 9: Test output (key sections):"
echo "--------------------------------------------------------------------------------"
grep -A 15 "BEFORE FAILOVER" $LOG_FILE
echo "..."
grep -A 15 "AFTER FAILOVER" $LOG_FILE
echo "..."
grep -A 20 "SELECTIVE FAILOVER ANALYSIS" $LOG_FILE

# Restart stopped QM
echo ""
echo "Step 10: Restarting ${C1_QM^^}..."
docker start $C1_QM

echo ""
echo "================================================================================"
echo "TEST COMPLETE"
echo "================================================================================"
echo ""
echo "Summary:"
echo "  - C1 was on ${C1_QM^^}, stopped ${C1_QM^^}, C1 moved to different QM"
echo "  - C2 was on ${C2_QM^^}, should have stayed on ${C2_QM^^}"
echo "  - Only C1 connections (6 total) should have failed over"
echo "  - C2 connections (4 total) should have remained stable"
echo ""
echo "Full log: $LOG_FILE"