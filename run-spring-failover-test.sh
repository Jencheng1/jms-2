#\!/bin/bash

TEST_ID="SPRING-$(date +%s)"
EVIDENCE_DIR="spring_failover_evidence_${TEST_ID}"
mkdir -p "$EVIDENCE_DIR"

echo "========================================================================"
echo "    SPRING BOOT MQ FAILOVER TEST WITH FULL CONNTAG - NO TRUNCATION"
echo "========================================================================"
echo "Test ID: $TEST_ID"
echo "Time: $(date '+%Y-%m-%d %H:%M:%S')"
echo ""

# Run the test
echo "Starting test with tracking key: $TEST_ID"
echo "----------------------------------------"

docker run --rm \
    --network mq-uniform-cluster_mqnet \
    -v "$(pwd):/app" \
    -v "$(pwd)/libs:/libs" \
    -v "$(pwd)/mq/ccdt:/workspace/ccdt" \
    openjdk:17 \
    java -cp "/app:/libs/*" UniformClusterDualConnectionTest 2>&1 | tee "$EVIDENCE_DIR/test_output.log"

echo ""
echo "Test complete\! Check $EVIDENCE_DIR for full evidence."
echo ""
echo "Extracting Full CONNTAGs from output..."
grep "MQCT" "$EVIDENCE_DIR/test_output.log" | head -20
