#!/bin/bash

echo "================================================"
echo "FIXED SELECTIVE FAILOVER TEST"
echo "================================================"
echo ""

# Check all QMs are running
echo "Checking Queue Managers..."
for qm in qm1 qm2 qm3; do
    if docker ps | grep -q $qm; then
        echo "✅ $qm is running"
    else
        echo "❌ $qm is not running - starting it..."
        docker start $qm
        sleep 2
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
echo "Starting test (this will run for ~60 seconds)..."
echo "================================================"

# Run the test
docker run --rm \
    --network mq-uniform-cluster_mqnet \
    -v "$(pwd):/app" \
    -v "$(pwd)/libs:/libs" \
    -v "$(pwd)/mq/ccdt:/workspace/ccdt" \
    openjdk:17 \
    java -cp "/app:/libs/*" FixedSelectiveFailoverTest 2>&1 | tee fixed_failover_test_$(date +%s).log

echo ""
echo "================================================"
echo "Test completed. Check the log file for results."
echo "================================================"