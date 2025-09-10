#!/bin/bash

echo "================================================"
echo "VERIFIED FAILOVER TEST WITH MANUAL QM STOP"
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
        echo "❌ $qm is not running - attempting to start..."
        docker start $qm
        sleep 2
    fi
done

echo ""
echo "Compiling test..."
javac -cp "libs/*:." VerifiedFailoverTest.java

if [ $? -ne 0 ]; then
    echo "❌ Compilation failed!"
    exit 1
fi

echo "✅ Compilation successful"
echo ""

LOG_FILE="verified_failover_$(date +%s).log"
echo "Starting interactive failover test..."
echo "Log file: $LOG_FILE"
echo ""
echo "================================================"
echo "IMPORTANT: When prompted, you need to manually"
echo "stop the appropriate queue manager in another"
echo "terminal using: docker stop <qm_name>"
echo "================================================"
echo ""

# Run the test
docker run --rm \
    --network mq-uniform-cluster_mqnet \
    -v "$(pwd):/app" \
    -v "$(pwd)/libs:/libs" \
    -v "$(pwd)/mq/ccdt:/workspace/ccdt" \
    openjdk:17 \
    java -cp "/app:/libs/*" VerifiedFailoverTest 2>&1 | tee $LOG_FILE

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