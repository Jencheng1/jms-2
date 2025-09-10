#!/bin/bash

echo "================================================"
echo "AUTOMATED FAILOVER TEST WITH QM STOP"
echo "================================================"
echo ""

# Ensure all QMs are running first
echo "Starting all Queue Managers..."
for qm in qm1 qm2 qm3; do
    docker start $qm 2>/dev/null
done
sleep 3

echo "Checking Queue Managers..."
for qm in qm1 qm2 qm3; do
    if docker ps | grep -q $qm; then
        echo "✅ $qm is running"
    else
        echo "❌ $qm is not running"
        exit 1
    fi
done

echo ""
echo "Compiling test..."
javac -cp "libs/*:." FixedSelectiveFailoverTest.java

if [ $? -ne 0 ]; then
    echo "❌ Compilation failed!"
    exit 1
fi

echo "✅ Compilation successful"
echo ""

# Start the test in background
echo "Starting test in background..."
LOG_FILE="automated_failover_test_$(date +%s).log"

docker run --rm \
    --network mq-uniform-cluster_mqnet \
    -v "$(pwd):/app" \
    -v "$(pwd)/libs:/libs" \
    -v "$(pwd)/mq/ccdt:/workspace/ccdt" \
    openjdk:17 \
    java -cp "/app:/libs/*" FixedSelectiveFailoverTest 2>&1 | tee $LOG_FILE &

TEST_PID=$!

# Wait for test to establish connections
echo "Waiting for connections to be established..."
sleep 10

# Check which QM has C1 connection
echo ""
echo "Checking where C1 is connected..."
for qm in qm1 qm2 qm3; do
    COUNT=$(docker exec $qm bash -c "echo 'DIS CONN(*) WHERE(APPLTAG LK \"*SELECTIVE*C1*\") CHANNEL' | runmqsc ${qm^^}" 2>/dev/null | grep -c "CONN(")
    if [ $COUNT -gt 0 ]; then
        echo "✅ Found C1 on $qm with $COUNT connections"
        TARGET_QM=$qm
        break
    fi
done

if [ -z "$TARGET_QM" ]; then
    echo "❌ Could not find C1 connections on any QM"
    kill $TEST_PID 2>/dev/null
    exit 1
fi

# Stop the QM where C1 is connected
echo ""
echo "🛑 Stopping $TARGET_QM to trigger failover..."
docker stop $TARGET_QM

# Wait for test to complete
echo "Waiting for test to complete (max 60 seconds)..."
wait $TEST_PID

echo ""
echo "================================================"
echo "RESTARTING STOPPED QUEUE MANAGER"
echo "================================================"
echo "Starting $TARGET_QM..."
docker start $TARGET_QM

echo ""
echo "================================================"
echo "TEST COMPLETED"
echo "================================================"
echo "Results saved to: $LOG_FILE"
echo ""

# Check final state
echo "Final Queue Manager Status:"
for qm in qm1 qm2 qm3; do
    if docker ps | grep -q $qm; then
        echo "✅ $qm is running"
    else
        echo "⚠️  $qm is stopped"
    fi
done