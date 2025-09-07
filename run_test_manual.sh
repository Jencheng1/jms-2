#!/bin/bash

echo "════════════════════════════════════════════════════════════════════════════════"
echo "    MANUAL TEST WITH PROPER TRACKING KEY CAPTURE"
echo "════════════════════════════════════════════════════════════════════════════════"
echo ""

# Compile the test
echo "📦 Compiling QM1LiveDebugv2.java..."
javac -cp "libs/*" QM1LiveDebugv2.java
if [ $? -ne 0 ]; then
    echo "❌ Compilation failed!"
    exit 1
fi
echo "✅ Compiled successfully"
echo ""

# Start the test in background
echo "🚀 Starting test container..."
docker run --rm -d \
    --name live-mqsc-test \
    --network mq-uniform-cluster_mqnet \
    -v "$(pwd):/app" \
    -v "$(pwd)/libs:/libs" \
    -v "$(pwd)/mq/ccdt:/workspace/ccdt" \
    openjdk:17 \
    java -cp "/app:/libs/*" QM1LiveDebugv2

if [ $? -ne 0 ]; then
    echo "❌ Failed to start test container"
    exit 1
fi
echo "✅ Test container started"
echo ""

# Wait for connections to be established
echo "⏳ Waiting 10 seconds for connections to be established..."
sleep 10

# Get tracking key from container logs
echo "📝 Extracting tracking key from container logs..."
# Extract just the V2-timestamp part
TRACKING_KEY=$(docker logs live-mqsc-test 2>&1 | grep "TRACKING KEY:" | head -1 | sed 's/.*TRACKING KEY: //' | tr -d ' ')
TIMESTAMP=$(date +%s)

echo "🔑 Tracking Key: $TRACKING_KEY"
echo ""

if [ -z "$TRACKING_KEY" ] || [[ "$TRACKING_KEY" == *"Binary"* ]] || [[ "$TRACKING_KEY" == *"matches"* ]]; then
    echo "❌ Failed to extract valid tracking key"
    echo "Raw logs:"
    docker logs live-mqsc-test 2>&1 | head -20
    docker stop live-mqsc-test 2>/dev/null
    exit 1
fi

# Now capture MQSC data
MQSC_LOG="MQSC_COMPLETE_${TRACKING_KEY}_${TIMESTAMP}.log"
echo "📝 Capturing MQSC data to: $MQSC_LOG"
echo ""

{
    echo "════════════════════════════════════════════════════════════════════════════════"
    echo "    MQSC DEBUG CAPTURE - LIVE CONNECTIONS"
    echo "    Tracking Key: $TRACKING_KEY"
    echo "    Time: $(date)"
    echo "════════════════════════════════════════════════════════════════════════════════"
    echo ""
    
    # Capture 5 rounds
    for ROUND in 1 2 3 4 5; do
        echo ""
        echo "──────────────────────────────────────────────────────────────────────────────"
        echo "ROUND $ROUND - $(date '+%H:%M:%S')"
        echo "──────────────────────────────────────────────────────────────────────────────"
        echo ""
        
        echo "► Basic Connection List"
        docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY')\" | runmqsc QM1"
        echo ""
        
        echo "► Full Connection Details (ALL attributes)"
        docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY') ALL\" | runmqsc QM1"
        echo ""
        
        # Count connections
        COUNT=$(docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY')\" | runmqsc QM1" | grep -c "CONN(")
        echo "► Connection Count: $COUNT (Expected: 6)"
        echo ""
        
        # Look for parent
        echo "► Checking for Parent Connection (MQCNO_GENERATE_CONN_TAG):"
        docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY') ALL\" | runmqsc QM1" | grep -A2 -B2 "MQCNO_GENERATE_CONN_TAG" || echo "   Not found in this round"
        echo ""
        
        if [ $ROUND -lt 5 ]; then
            echo "Waiting 10 seconds..."
            sleep 10
        fi
    done
    
    echo ""
    echo "════════════════════════════════════════════════════════════════════════════════"
    echo "    FINAL SUMMARY"
    echo "════════════════════════════════════════════════════════════════════════════════"
    
    # Final count
    FINAL_COUNT=$(docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY')\" | runmqsc QM1" | grep -c "CONN(")
    echo "Total Connections: $FINAL_COUNT"
    
    # List all connections
    echo ""
    echo "Connection List:"
    docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY')\" | runmqsc QM1" | grep "CONN(" | while read line; do
        CONN_ID=$(echo "$line" | sed -n 's/.*CONN(\([^)]*\)).*/\1/p')
        echo "  • $CONN_ID"
    done
    
    # Check for parent
    PARENT_CHECK=$(docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY') ALL\" | runmqsc QM1" | grep -c "MQCNO_GENERATE_CONN_TAG")
    echo ""
    if [ "$PARENT_CHECK" -gt 0 ]; then
        echo "✅ Parent connection identified (MQCNO_GENERATE_CONN_TAG found)"
    else
        echo "⚠️ Parent connection flag not found"
    fi
    
} > "$MQSC_LOG" 2>&1

echo "✅ MQSC capture complete"

# Get JMS logs
JMS_LOG="JMS_COMPLETE_${TRACKING_KEY}_${TIMESTAMP}.log"
docker logs live-mqsc-test > "$JMS_LOG" 2>&1
echo "✅ JMS logs saved"

# Summary
echo ""
echo "════════════════════════════════════════════════════════════════════════════════"
echo "    TEST COMPLETE"
echo "════════════════════════════════════════════════════════════════════════════════"
echo ""
echo "📊 Summary:"
echo "  • Tracking Key: $TRACKING_KEY"
echo "  • MQSC Log: $MQSC_LOG ($(wc -c < "$MQSC_LOG") bytes)"
echo "  • JMS Log: $JMS_LOG ($(wc -c < "$JMS_LOG") bytes)"
echo ""

# Quick analysis
echo "📈 Quick Analysis:"
CONN_COUNT=$(grep -c "CONN(" "$MQSC_LOG" 2>/dev/null || echo "0")
PARENT_COUNT=$(grep -c "MQCNO_GENERATE_CONN_TAG" "$MQSC_LOG" 2>/dev/null || echo "0")
echo "  • Total CONN entries in log: $CONN_COUNT"
echo "  • Parent connections found: $PARENT_COUNT"

if [ "$PARENT_COUNT" -gt 0 ]; then
    echo "  • Result: ✅ Parent-child relationship PROVEN"
else
    echo "  • Result: ⚠️ Need to verify logs manually"
fi

# Wait for test to finish
echo ""
echo "⏳ Waiting for test to complete (should take ~30 more seconds)..."
docker wait live-mqsc-test > /dev/null 2>&1

echo ""
echo "✅ All done!"