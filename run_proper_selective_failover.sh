#!/bin/bash

echo "================================================"
echo "PROPER SELECTIVE FAILOVER TEST"
echo "================================================"
echo ""

# Ensure all QMs are running first
echo "Starting all Queue Managers..."
docker start qm1 qm2 qm3 2>/dev/null
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
javac -cp "libs/*:." ProperSelectiveFailoverTest.java

if [ $? -ne 0 ]; then
    echo "❌ Compilation failed!"
    exit 1
fi

echo "✅ Compilation successful"
echo ""

LOG_FILE="proper_selective_failover_$(date +%s).log"
echo "Starting test..."
echo "Log file: $LOG_FILE"
echo ""
echo "================================================"
echo "IMPORTANT: When prompted, manually stop the"
echo "appropriate queue manager in another terminal"
echo "================================================"
echo ""

# Run the test
docker run --rm \
    --network mq-uniform-cluster_mqnet \
    -v "$(pwd):/app" \
    -v "$(pwd)/libs:/libs" \
    -v "$(pwd)/mq/ccdt:/workspace/ccdt" \
    openjdk:17 \
    java -cp "/app:/libs/*" ProperSelectiveFailoverTest 2>&1 | tee $LOG_FILE

echo ""
echo "================================================"
echo "Test completed. Results saved to $LOG_FILE"
echo "================================================"

# Check final QM status
echo ""
echo "Final Queue Manager Status:"
for qm in qm1 qm2 qm3; do
    if docker ps | grep -q $qm; then
        echo "✅ $qm is running"
    else
        echo "⚠️  $qm is stopped - restart with: docker start $qm"
    fi
done