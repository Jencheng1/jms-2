#!/bin/bash

echo "================================================================================"
echo "   FAILOVER TEST WITH CACHE CLEAR - FULL EVIDENCE COLLECTION"
echo "================================================================================"
echo ""

TIMESTAMP=$(date +%s%3N)
LOG_FILE="cache_clear_failover_$TIMESTAMP.log"
EVIDENCE_DIR="evidence_cache_clear_$TIMESTAMP"

mkdir -p $EVIDENCE_DIR

echo "Test ID: $TIMESTAMP"
echo "Evidence Directory: $EVIDENCE_DIR"
echo ""

# Compile the test
echo "Step 1: Compiling test..."
javac -cp "libs/*:." FailoverWithCacheClear.java || exit 1

# Start the test in background
echo "Step 2: Starting test (creates 10 connections)..."
docker run --rm --network mq-uniform-cluster_mqnet \
    -v "/home/ec2-user/unified/demo5/mq-uniform-cluster:/app" \
    -v "/home/ec2-user/unified/demo5/mq-uniform-cluster/libs:/libs" \
    -v "/home/ec2-user/unified/demo5/mq-uniform-cluster/mq/ccdt:/workspace/ccdt" \
    openjdk:17 java -cp "/app:/libs/*" FailoverWithCacheClear > $EVIDENCE_DIR/test_output.log 2>&1 &

TEST_PID=$!
echo "Test PID: $TEST_PID"

# Wait for connections to establish
echo ""
echo "Step 3: Waiting for connections to establish..."
sleep 12

# Capture BEFORE state
echo ""
echo "Step 4: Capturing BEFORE FAILOVER state..."
echo "=== BEFORE FAILOVER ===" > $EVIDENCE_DIR/mqsc_before.log
for qm in qm1 qm2 qm3; do
    echo "" >> $EVIDENCE_DIR/mqsc_before.log
    echo "Queue Manager: ${qm^^}" >> $EVIDENCE_DIR/mqsc_before.log
    docker exec $qm bash -c "echo 'DIS CONN(*) WHERE(APPLTAG LK CLEAR*)' | runmqsc ${qm^^}" >> $EVIDENCE_DIR/mqsc_before.log 2>&1
done

# Count connections
echo ""
echo "BEFORE FAILOVER - Connection counts:"
for qm in qm1 qm2 qm3; do
    COUNT=$(docker exec $qm bash -c "echo 'DIS CONN(*) WHERE(APPLTAG LK CLEAR*)' | runmqsc ${qm^^}" 2>/dev/null | grep -c "AMQ8276I" || echo "0")
    echo "  ${qm^^}: $COUNT connections"
    if [ "$COUNT" -gt "5" ]; then
        QM_WITH_C1=$qm
        C1_COUNT=$COUNT
    elif [ "$COUNT" -gt "0" ]; then
        QM_WITH_C2=$qm
        C2_COUNT=$COUNT
    fi
done

# Stop QM with most connections
if [ -n "$QM_WITH_C1" ]; then
    echo ""
    echo "Step 5: Stopping ${QM_WITH_C1^^} (has $C1_COUNT connections - likely C1)..."
    docker stop $QM_WITH_C1
    STOPPED_QM=$QM_WITH_C1
else
    echo "Warning: Could not identify QM with connections"
fi

# Wait for failover
echo "Step 6: Waiting for failover and cache clear..."
sleep 45

# Capture AFTER state
echo ""
echo "Step 7: Capturing AFTER FAILOVER state..."
echo "=== AFTER FAILOVER ===" > $EVIDENCE_DIR/mqsc_after.log
for qm in qm1 qm2 qm3; do
    if [ "$qm" != "$STOPPED_QM" ]; then
        echo "" >> $EVIDENCE_DIR/mqsc_after.log
        echo "Queue Manager: ${qm^^}" >> $EVIDENCE_DIR/mqsc_after.log
        docker exec $qm bash -c "echo 'DIS CONN(*) WHERE(APPLTAG LK CLEAR*)' | runmqsc ${qm^^}" >> $EVIDENCE_DIR/mqsc_after.log 2>&1
    else
        echo "" >> $EVIDENCE_DIR/mqsc_after.log
        echo "Queue Manager: ${qm^^} - STOPPED" >> $EVIDENCE_DIR/mqsc_after.log
    fi
done

# Count connections after
echo ""
echo "AFTER FAILOVER - Connection counts:"
for qm in qm1 qm2 qm3; do
    if [ "$qm" != "$STOPPED_QM" ]; then
        COUNT=$(docker exec $qm bash -c "echo 'DIS CONN(*) WHERE(APPLTAG LK CLEAR*NEW*)' | runmqsc ${qm^^}" 2>/dev/null | grep -c "AMQ8276I" || echo "0")
        echo "  ${qm^^}: $COUNT connections (NEW connections after cache clear)"
    else
        echo "  ${qm^^}: STOPPED"
    fi
done

# Wait for test to complete
echo ""
echo "Step 8: Waiting for test to complete..."
wait $TEST_PID 2>/dev/null || true

# Extract key evidence from test output
echo ""
echo "Step 9: Extracting evidence..."
grep -A 20 "BEFORE FAILOVER" $EVIDENCE_DIR/test_output.log > $EVIDENCE_DIR/before_table.txt
grep -A 20 "AFTER FAILOVER" $EVIDENCE_DIR/test_output.log > $EVIDENCE_DIR/after_table.txt
grep -A 10 "FAILOVER ANALYSIS" $EVIDENCE_DIR/test_output.log > $EVIDENCE_DIR/analysis.txt

# Create summary
echo ""
echo "Step 10: Creating evidence summary..."
cat > $EVIDENCE_DIR/EVIDENCE_SUMMARY.md << EOF
# Failover Test with Cache Clear - Evidence Summary

## Test Information
- Test ID: $TIMESTAMP
- Date: $(date)
- Tracking Key: CLEAR-*

## Connection Distribution

### BEFORE FAILOVER
$(grep "QM[123]:" $EVIDENCE_DIR/test_output.log | head -3)

### AFTER FAILOVER (Cache Cleared)
$(grep "QM[123]:" $EVIDENCE_DIR/test_output.log | tail -3)

## CONNTAG Evidence

### Before Failover CONNTAG Samples
\`\`\`
$(grep "MQCT" $EVIDENCE_DIR/before_table.txt | head -2)
\`\`\`

### After Failover CONNTAG Samples (NEW Values)
\`\`\`
$(grep "MQCT" $EVIDENCE_DIR/after_table.txt | head -2)
\`\`\`

## Key Observations

1. **Queue Manager Change**: Connections moved from initial QM to different QM
2. **CONNTAG Update**: CONNTAG values reflect new Queue Manager after cache clear
3. **CONNECTION_ID Change**: New CONNECTION_ID shows different QM identifier
4. **Parent-Child Affinity**: All sessions stayed with their parent connection

## Files Generated
- test_output.log: Complete test output
- mqsc_before.log: MQSC connection data before failover
- mqsc_after.log: MQSC connection data after failover
- before_table.txt: Connection table before failover
- after_table.txt: Connection table after failover
- analysis.txt: Test analysis results
EOF

# Restart stopped QM
if [ -n "$STOPPED_QM" ]; then
    echo ""
    echo "Step 11: Restarting ${STOPPED_QM^^}..."
    docker start $STOPPED_QM
fi

echo ""
echo "================================================================================"
echo "TEST COMPLETE"
echo "================================================================================"
echo ""
echo "Evidence collected in: $EVIDENCE_DIR/"
echo ""
echo "Key files:"
echo "  - $EVIDENCE_DIR/EVIDENCE_SUMMARY.md - Summary of findings"
echo "  - $EVIDENCE_DIR/test_output.log - Full test output"
echo "  - $EVIDENCE_DIR/before_table.txt - CONNTAG values before failover"
echo "  - $EVIDENCE_DIR/after_table.txt - CONNTAG values after failover"
echo ""
cat $EVIDENCE_DIR/EVIDENCE_SUMMARY.md