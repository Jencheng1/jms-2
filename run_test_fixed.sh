#!/bin/bash

echo "════════════════════════════════════════════════════════════════════════════════"
echo "    COMPLETE TEST WITH LIVE MQSC CAPTURE - FIXED VERSION"
echo "════════════════════════════════════════════════════════════════════════════════"
echo "Start time: $(date)"
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

# Start the test
echo "🚀 Starting test with 90-second keep-alive..."
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

# Wait for test to establish connections
echo "⏳ Waiting for connections to be established..."
sleep 10

# Get the tracking key directly from the log
echo "📝 Extracting tracking key..."
docker logs live-mqsc-test 2>&1 > temp_log.txt
TRACKING_KEY=$(grep "TRACKING KEY:" temp_log.txt | head -1 | sed 's/.*TRACKING KEY: //')
rm temp_log.txt

TIMESTAMP=$(date +%s)

if [ -z "$TRACKING_KEY" ]; then
    echo "❌ Failed to get tracking key"
    docker stop live-mqsc-test 2>/dev/null
    exit 1
fi

echo "✅ Test is running with tracking key: $TRACKING_KEY"
echo ""

# Create MQSC debug log
MQSC_LOG="MQSC_DEBUG_${TIMESTAMP}.log"
echo "📝 Starting MQSC capture to: $MQSC_LOG"
echo ""

{
    echo "═══════════════════════════════════════════════════════════════════════════════"
    echo "    🔍 COMPLETE MQSC DEBUG CAPTURE - LIVE CONNECTIONS"
    echo "    Tracking Key: $TRACKING_KEY"
    echo "    Time: $(date)"
    echo "═══════════════════════════════════════════════════════════════════════════════"
    echo ""
    
    # Perform multiple captures while test is running
    for ROUND in 1 2 3 4 5; do
        echo "╔════════════════════════════════════════════════════════════════════════════╗"
        echo "║ CAPTURE ROUND #$ROUND - $(date '+%H:%M:%S')                                ║"
        echo "╚════════════════════════════════════════════════════════════════════════════╝"
        echo ""
        
        echo "1. BASIC CONNECTION LIST"
        echo "   Command: DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY')"
        echo "────────────────────────────────────────────────────────────────────────────"
        docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY')\" | runmqsc QM1"
        echo ""
        
        echo "2. FULL CONNECTION DETAILS - ALL ATTRIBUTES"
        echo "   Command: DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY') ALL"
        echo "────────────────────────────────────────────────────────────────────────────"
        docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY') ALL\" | runmqsc QM1"
        echo ""
        
        # Get connection IDs for individual queries
        echo "3. INDIVIDUAL CONNECTION ANALYSIS"
        echo "────────────────────────────────────────────────────────────────────────────"
        CONN_IDS=$(docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY')\" | runmqsc QM1" | grep "CONN(" | sed -n 's/.*CONN(\([^)]*\)).*/\1/p')
        
        if [ ! -z "$CONN_IDS" ]; then
            for CONN_ID in $CONN_IDS; do
                echo ""
                echo "   Detailed info for CONN($CONN_ID):"
                echo "   ─────────────────────────────────"
                docker exec qm1 bash -c "echo 'DIS CONN($CONN_ID) ALL' | runmqsc QM1" | grep -v "^5724-H72" | grep -v "^Starting MQSC" | grep -v "^$"
            done
        else
            echo "   No connections found yet..."
        fi
        echo ""
        
        echo "4. CONNECTION COUNT AND ANALYSIS"
        echo "────────────────────────────────────────────────────────────────────────────"
        COUNT=$(docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY')\" | runmqsc QM1" | grep -c "CONN(")
        echo "   Total connections found: $COUNT"
        echo "   Expected: 6 (1 parent + 5 sessions)"
        
        if [ "$COUNT" -eq "6" ]; then
            echo "   ✅ COUNT MATCHES EXPECTED!"
        else
            echo "   ⏳ Found $COUNT connections so far..."
        fi
        echo ""
        
        echo "5. PARENT CONNECTION IDENTIFICATION"
        echo "────────────────────────────────────────────────────────────────────────────"
        echo "   Looking for MQCNO_GENERATE_CONN_TAG flag..."
        docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY') ALL\" | runmqsc QM1" | grep -B2 -A2 "MQCNO_GENERATE_CONN_TAG"
        
        if [ $? -eq 0 ]; then
            echo "   ✅ Parent connection identified!"
        else
            echo "   ⏳ Searching for parent connection..."
        fi
        echo ""
        
        echo "6. PROCESS AND THREAD VERIFICATION"
        echo "────────────────────────────────────────────────────────────────────────────"
        docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY') ALL\" | runmqsc QM1" | grep -E "(PID|TID)"
        echo ""
        
        if [ $ROUND -lt 5 ]; then
            echo "⏳ Waiting 10 seconds before round #$((ROUND+1))..."
            sleep 10
        fi
    done
    
    echo "═══════════════════════════════════════════════════════════════════════════════"
    echo "    📊 FINAL ANALYSIS AND SUMMARY"
    echo "═══════════════════════════════════════════════════════════════════════════════"
    echo ""
    
    echo "FINAL CONNECTION COUNT:"
    echo "──────────────────────"
    FINAL_COUNT=$(docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY')\" | runmqsc QM1" | grep -c "CONN(")
    echo "Total connections with tracking key '$TRACKING_KEY': $FINAL_COUNT"
    echo ""
    
    echo "CONNECTION DETAILS SUMMARY:"
    echo "──────────────────────────"
    docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY')\" | runmqsc QM1" | grep "CONN(" | while read line; do
        CONN_ID=$(echo "$line" | sed -n 's/.*CONN(\([^)]*\)).*/\1/p')
        echo "  • $CONN_ID"
        
        # Check if it's the parent
        IS_PARENT=$(docker exec qm1 bash -c "echo 'DIS CONN($CONN_ID) ALL' | runmqsc QM1" | grep -c "MQCNO_GENERATE_CONN_TAG")
        if [ "$IS_PARENT" -gt 0 ]; then
            echo "    └─ ⭐ PARENT CONNECTION (has MQCNO_GENERATE_CONN_TAG)"
        else
            echo "    └─ 📌 Child Session"
        fi
    done
    echo ""
    
    echo "═══════════════════════════════════════════════════════════════════════════════"
    echo "✅ MQSC DEBUG CAPTURE COMPLETE"
    echo "   Tracking Key: $TRACKING_KEY"
    echo "   Log File: $MQSC_LOG"
    echo "   Capture Time: $(date)"
    echo "═══════════════════════════════════════════════════════════════════════════════"
    
} > "$MQSC_LOG" 2>&1

echo "✅ MQSC capture complete: $MQSC_LOG"

# Capture JMS output
JMS_LOG="JMS_DEBUG_${TIMESTAMP}.log"
docker logs live-mqsc-test > "$JMS_LOG" 2>&1
echo "✅ JMS output saved: $JMS_LOG"

# Create summary report
SUMMARY_LOG="SUMMARY_${TIMESTAMP}.md"
{
    echo "# Test Summary Report"
    echo ""
    echo "## Test Details"
    echo "- **Tracking Key**: $TRACKING_KEY"
    echo "- **Timestamp**: $TIMESTAMP"
    echo "- **Date**: $(date)"
    echo ""
    echo "## Files Generated"
    echo "1. **MQSC Debug**: $MQSC_LOG ($(stat -c%s "$MQSC_LOG" 2>/dev/null || echo "N/A") bytes)"
    echo "2. **JMS Debug**: $JMS_LOG ($(stat -c%s "$JMS_LOG" 2>/dev/null || echo "N/A") bytes)"
    echo "3. **Summary**: $SUMMARY_LOG"
    echo ""
    echo "## Results"
    
    # Count connections
    CONN_COUNT=$(grep -c "CONN(" "$MQSC_LOG" 2>/dev/null || echo "0")
    echo "- **Total CONN entries captured**: $CONN_COUNT"
    
    # Check for parent
    PARENT_COUNT=$(grep -c "MQCNO_GENERATE_CONN_TAG" "$MQSC_LOG" 2>/dev/null || echo "0")
    echo "- **Parent connections found**: $PARENT_COUNT"
    
    echo ""
    echo "## Verification"
    if [ "$PARENT_COUNT" -gt "0" ]; then
        echo "✅ Parent-child relationship proven with MQCNO_GENERATE_CONN_TAG"
    else
        echo "⚠️ Parent connection flag not found"
    fi
    
} > "$SUMMARY_LOG"

echo "✅ Summary created: $SUMMARY_LOG"
echo ""

# Display summary
echo "════════════════════════════════════════════════════════════════════════════════"
echo "                              TEST COMPLETE"
echo "════════════════════════════════════════════════════════════════════════════════"
cat "$SUMMARY_LOG"

# Wait for test to complete
echo ""
echo "⏳ Waiting for test container to finish..."
docker wait live-mqsc-test > /dev/null 2>&1
docker rm live-mqsc-test > /dev/null 2>&1

echo ""
echo "✅ All done! Review the log files for complete details."
echo "   - MQSC Log: $MQSC_LOG"
echo "   - JMS Log: $JMS_LOG"
echo "   - Summary: $SUMMARY_LOG"