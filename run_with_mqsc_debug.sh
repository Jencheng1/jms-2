#!/bin/bash

echo "════════════════════════════════════════════════════════════════════════"
echo "     ENHANCED TEST WITH MAXIMUM MQSC DEBUGGING"
echo "════════════════════════════════════════════════════════════════════════"
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

# Start the test in background
echo "🚀 Starting test in background..."
docker run --rm -d \
    --name mqsc-debug-test \
    --network mq-uniform-cluster_mqnet \
    -v "$(pwd):/app" \
    -v "$(pwd)/libs:/libs" \
    -v "$(pwd)/mq/ccdt:/workspace/ccdt" \
    openjdk:17 \
    java -cp "/app:/libs/*" QM1LiveDebugv2

# Wait for test to establish connections
echo "⏳ Waiting for connections to be established..."
sleep 5

# Get the tracking key
TRACKING_KEY=$(docker logs mqsc-debug-test 2>&1 | grep "TRACKING KEY:" | head -1 | awk '{print $3}')
TIMESTAMP=$(date +%s)

if [ -z "$TRACKING_KEY" ]; then
    echo "❌ Failed to get tracking key"
    docker stop mqsc-debug-test 2>/dev/null
    exit 1
fi

echo "🔑 Tracking Key: $TRACKING_KEY"
echo "📅 Timestamp: $TIMESTAMP"
echo ""

# Create comprehensive MQSC debug log
MQSC_LOG="MQSC_DEBUG_${TRACKING_KEY}_${TIMESTAMP}.log"
echo "📝 Creating MQSC debug log: $MQSC_LOG"
echo ""

{
    echo "═══════════════════════════════════════════════════════════════════════════════"
    echo "    🔍 MQSC COMPREHENSIVE DEBUG CAPTURE"
    echo "    Tracking Key: $TRACKING_KEY"
    echo "    Time: $(date)"
    echo "═══════════════════════════════════════════════════════════════════════════════"
    echo ""
    
    # Loop for multiple captures while test is running
    for CAPTURE in 1 2 3; do
        echo "╔════════════════════════════════════════════════════════════════════════════╗"
        echo "║ CAPTURE #$CAPTURE - $(date '+%H:%M:%S.%N')                                  ║"
        echo "╚════════════════════════════════════════════════════════════════════════════╝"
        echo ""
        
        echo "1️⃣ BASIC CONNECTION QUERY - WHERE APPLTAG"
        echo "────────────────────────────────────────────────────────────────────────────"
        docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY')\" | runmqsc QM1" 2>&1
        echo ""
        
        echo "2️⃣ DETAILED CONNECTION INFO - ALL FIELDS"
        echo "────────────────────────────────────────────────────────────────────────────"
        docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY') ALL\" | runmqsc QM1" 2>&1
        echo ""
        
        echo "3️⃣ CONNECTION TYPE AND OPTIONS"
        echo "────────────────────────────────────────────────────────────────────────────"
        docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY') TYPE(*) CONNOPTS\" | runmqsc QM1" 2>&1
        echo ""
        
        echo "4️⃣ CHANNEL AND NETWORK INFO"
        echo "────────────────────────────────────────────────────────────────────────────"
        docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY') CHANNEL CONNAME USERID\" | runmqsc QM1" 2>&1
        echo ""
        
        echo "5️⃣ EXTENDED CONNECTION DETAILS"
        echo "────────────────────────────────────────────────────────────────────────────"
        docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY') EXTCONN PID TID APPLDESC APPLTYPE\" | runmqsc QM1" 2>&1
        echo ""
        
        echo "6️⃣ UNIT OF WORK INFORMATION"
        echo "────────────────────────────────────────────────────────────────────────────"
        docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY') UOWLOG UOWSTDA UOWSTTI UOWSTATE\" | runmqsc QM1" 2>&1
        echo ""
        
        echo "7️⃣ CONNECTION TAGS"
        echo "────────────────────────────────────────────────────────────────────────────"
        docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY') CONNTAG\" | runmqsc QM1" 2>&1
        echo ""
        
        echo "8️⃣ ALL APP.SVRCONN CONNECTIONS (WIDER VIEW)"
        echo "────────────────────────────────────────────────────────────────────────────"
        docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(CHANNEL EQ APP.SVRCONN) APPLTAG CONNAME\" | runmqsc QM1" 2>&1 | head -50
        echo ""
        
        echo "9️⃣ CHANNEL STATUS"
        echo "────────────────────────────────────────────────────────────────────────────"
        docker exec qm1 bash -c "echo \"DIS CHS(APP.SVRCONN)\" | runmqsc QM1" 2>&1 | head -100
        echo ""
        
        echo "🔟 CHANNEL DEFINITION"
        echo "────────────────────────────────────────────────────────────────────────────"
        docker exec qm1 bash -c "echo \"DIS CHANNEL(APP.SVRCONN) ALL\" | runmqsc QM1" 2>&1 | head -50
        echo ""
        
        echo "1️⃣1️⃣ CONNECTION COUNT ANALYSIS"
        echo "────────────────────────────────────────────────────────────────────────────"
        COUNT=$(docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY')\" | runmqsc QM1" 2>&1 | grep -c "CONN(")
        echo "Total connections with APPLTAG='$TRACKING_KEY': $COUNT"
        
        # Extract connection IDs
        echo ""
        echo "Connection IDs found:"
        docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY')\" | runmqsc QM1" 2>&1 | grep "CONN(" | while read line; do
            echo "  - $line"
        done
        echo ""
        
        echo "1️⃣2️⃣ PARENT CONNECTION IDENTIFICATION"
        echo "────────────────────────────────────────────────────────────────────────────"
        echo "Looking for MQCNO_GENERATE_CONN_TAG flag..."
        docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY') CONNOPTS\" | runmqsc QM1" 2>&1 | grep -B1 "MQCNO_GENERATE_CONN_TAG" || echo "Parent connection identification by CONNOPTS"
        echo ""
        
        if [ $CAPTURE -lt 3 ]; then
            echo "⏳ Waiting 15 seconds before next capture..."
            sleep 15
            echo ""
        fi
    done
    
    echo "═══════════════════════════════════════════════════════════════════════════════"
    echo "    📊 FINAL STATISTICS AND ANALYSIS"
    echo "═══════════════════════════════════════════════════════════════════════════════"
    echo ""
    
    echo "SUMMARY OF CONNECTIONS:"
    echo "──────────────────────"
    docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY')\" | runmqsc QM1" 2>&1 | grep "CONN(" | sort | uniq | while IFS= read -r line; do
        CONN_ID=$(echo "$line" | grep -oP 'CONN\(\K[^)]+')
        echo ""
        echo "Connection: $CONN_ID"
        
        # Get detailed info for this specific connection
        docker exec qm1 bash -c "echo \"DIS CONN($CONN_ID) CONNOPTS\" | runmqsc QM1" 2>&1 | grep "CONNOPTS" | head -1
    done
    
    echo ""
    echo "═══════════════════════════════════════════════════════════════════════════════"
    echo "    ✅ MQSC DEBUG CAPTURE COMPLETE"
    echo "    Log file: $MQSC_LOG"
    echo "    Time: $(date)"
    echo "═══════════════════════════════════════════════════════════════════════════════"
    
} > "$MQSC_LOG" 2>&1

echo "✅ MQSC debug log created: $MQSC_LOG"
echo ""

# Also capture JMS test output
JMS_LOG="JMS_OUTPUT_${TRACKING_KEY}_${TIMESTAMP}.log"
docker logs mqsc-debug-test > "$JMS_LOG" 2>&1
echo "✅ JMS test output saved: $JMS_LOG"
echo ""

# Show summary on console
echo "════════════════════════════════════════════════════════════════════════"
echo "                          RESULTS SUMMARY"
echo "════════════════════════════════════════════════════════════════════════"
echo ""
echo "Tracking Key: $TRACKING_KEY"
echo ""
echo "Connections Found:"
grep -c "CONN(" "$MQSC_LOG" | head -1 | xargs -I {} echo "  Total CONN entries in log: {}"
echo ""
echo "Files Created:"
echo "  - $MQSC_LOG (MQSC comprehensive debug)"
echo "  - $JMS_LOG (JMS test output)"
echo ""

# Extract key findings
echo "Key Findings:"
echo "─────────────"
if grep -q "MQCNO_GENERATE_CONN_TAG" "$MQSC_LOG"; then
    echo "  ✅ Parent connection identified (has MQCNO_GENERATE_CONN_TAG)"
    grep -B1 "MQCNO_GENERATE_CONN_TAG" "$MQSC_LOG" | head -2
fi
echo ""

# Wait for test to complete
echo "⏳ Waiting for test to complete (remaining time)..."
docker wait mqsc-debug-test > /dev/null 2>&1

echo ""
echo "✅ Test completed successfully!"
echo ""
echo "📁 Review the following files for complete details:"
echo "   - $MQSC_LOG"
echo "   - $JMS_LOG"