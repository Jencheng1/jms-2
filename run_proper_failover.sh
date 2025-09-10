#!/bin/bash

echo "================================================"
echo "PROPER FAILOVER TEST WITH MQSC VERIFICATION"
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
javac -cp "libs/*:." ProperFailoverTest.java

if [ $? -ne 0 ]; then
    echo "❌ Compilation failed!"
    exit 1
fi

echo "✅ Compilation successful"
echo ""

LOG_FILE="proper_failover_test_$(date +%s).log"
echo "Running test (will automatically stop appropriate QM)..."
echo "Log file: $LOG_FILE"
echo "================================================"

# Run the test
docker run --rm \
    --network mq-uniform-cluster_mqnet \
    -v "$(pwd):/app" \
    -v "$(pwd)/libs:/libs" \
    -v "$(pwd)/mq/ccdt:/workspace/ccdt" \
    -v "/var/run/docker.sock:/var/run/docker.sock" \
    openjdk:17 \
    java -cp "/app:/libs/*" ProperFailoverTest 2>&1 | tee $LOG_FILE

echo ""
echo "================================================"
echo "Test completed. Check $LOG_FILE for details."
echo "================================================"