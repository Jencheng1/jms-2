#!/bin/bash

echo "=================================="
echo "MQ UNIFORM CLUSTER FAILOVER TEST"
echo "=================================="
echo ""

# Check which QMs are running
echo "Current Queue Managers status:"
docker ps --format "table {{.Names}}\t{{.Status}}" | grep -E "qm1|qm2|qm3"
echo ""

# Start the test in background
echo "Starting failover test..."
docker run --rm --network mq-uniform-cluster_mqnet \
    -v /home/ec2-user/unified/demo5/mq-uniform-cluster:/app \
    -v /home/ec2-user/unified/demo5/mq-uniform-cluster/libs:/libs \
    -v /home/ec2-user/unified/demo5/mq-uniform-cluster/mq/ccdt:/workspace/ccdt \
    openjdk:17 java -cp "/app:/libs/*" UniformClusterFailoverTest &

TEST_PID=$!
echo "Test started with PID: $TEST_PID"
echo ""

# Wait for test to establish connections
echo "Waiting 20 seconds for connections to establish..."
sleep 20

# Prompt user to stop QM
echo ""
echo "⚠️  IMPORTANT: Look at the test output above to identify which QM has 6 connections"
echo "Then run one of these commands in another terminal:"
echo "  docker stop qm1  (if QM1 has 6 connections)"
echo "  docker stop qm2  (if QM2 has 6 connections)" 
echo "  docker stop qm3  (if QM3 has 6 connections)"
echo ""
echo "Waiting for test to complete (3 minutes total)..."

# Wait for test to complete
wait $TEST_PID

echo ""
echo "Test completed! Check the log files for results."
echo ""

# Show log files created
echo "Log files created:"
ls -la FAILOVER_*.log 2>/dev/null
ls -la mqtrace_failover_*.trc 2>/dev/null

echo ""
echo "To restart the stopped QM, use:"
echo "  docker start qm1  (if you stopped QM1)"
echo "  docker start qm2  (if you stopped QM2)"
echo "  docker start qm3  (if you stopped QM3)"