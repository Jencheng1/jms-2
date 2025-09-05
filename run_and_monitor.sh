#!/bin/bash

echo "════════════════════════════════════════════════════════════════════════"
echo "    PARENT-CHILD CONNECTION TEST WITH LIVE MQSC MONITORING"
echo "════════════════════════════════════════════════════════════════════════"
echo ""

# Compile the test
echo "📦 Compiling QM1LiveDebug.java..."
javac -cp "libs/*" QM1LiveDebug.java
if [ $? -ne 0 ]; then
    echo "❌ Compilation failed!"
    exit 1
fi
echo "✅ Compiled successfully"
echo ""

# Run the test in background
echo "🚀 Starting test in background..."
docker run --rm \
    --name live-debug-test \
    --network mq-uniform-cluster_mqnet \
    -v "$(pwd):/app" \
    -v "$(pwd)/libs:/libs" \
    -v "$(pwd)/mq/ccdt:/workspace/ccdt" \
    openjdk:17 \
    java -cp "/app:/libs/*" QM1LiveDebug > test_output.log 2>&1 &

TEST_PID=$!

# Wait for test to start and create connection
echo "⏳ Waiting for connection to be established..."
sleep 5

# Get the tracking key from the log
TRACKING_KEY=$(grep "TRACKING KEY:" test_output.log | head -1 | awk '{print $3}')

if [ -z "$TRACKING_KEY" ]; then
    echo "❌ Failed to get tracking key from test"
    cat test_output.log
    docker stop live-debug-test 2>/dev/null
    exit 1
fi

echo "🔑 Tracking Key: $TRACKING_KEY"
echo ""

# Now monitor MQSC while test is running
echo "════════════════════════════════════════════════════════════════════════"
echo "                     MQSC MONITORING - LIVE CONNECTION DATA"
echo "════════════════════════════════════════════════════════════════════════"
echo ""

# Loop to capture MQSC data multiple times
for i in 1 2 3; do
    echo "📊 CAPTURE #$i (at $(date '+%H:%M:%S'))"
    echo "────────────────────────────────────────────────────────────────"
    
    # Check for connections with our tracking key
    echo "Checking QM1 for APPLTAG='$TRACKING_KEY':"
    
    RESULT=$(docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY') ALL\" | runmqsc QM1" 2>/dev/null)
    
    if echo "$RESULT" | grep -q "CONN("; then
        echo "✅ FOUND CONNECTIONS:"
        echo "$RESULT" | grep -A 10 "CONN(" | head -50
        
        # Count connections
        COUNT=$(echo "$RESULT" | grep -c "CONN(")
        echo ""
        echo "📈 Total connections found: $COUNT"
        echo "   Expected: 6 (1 parent + 5 sessions)"
        
        if [ "$COUNT" -eq "6" ]; then
            echo "   ✅ MATCH! Parent-child relationship confirmed!"
        fi
    else
        echo "⚠️ No connections found with APPLTAG='$TRACKING_KEY'"
        
        # Try without filter
        echo ""
        echo "All connections on QM1:"
        docker exec qm1 bash -c "echo 'DIS CONN(*) APPLTAG CHANNEL CONNAME' | runmqsc QM1" 2>/dev/null | grep -B2 -A2 "APP.SVRCONN" | head -20
    fi
    
    echo ""
    
    if [ $i -lt 3 ]; then
        echo "Waiting 10 seconds before next capture..."
        sleep 10
    fi
done

echo "════════════════════════════════════════════════════════════════════════"
echo "                            TEST OUTPUT"
echo "════════════════════════════════════════════════════════════════════════"
tail -30 test_output.log

echo ""
echo "════════════════════════════════════════════════════════════════════════"
echo "                            SUMMARY"
echo "════════════════════════════════════════════════════════════════════════"
echo "Tracking Key: $TRACKING_KEY"
echo "Full test output saved to: test_output.log"
echo ""

# Wait for test to complete
wait $TEST_PID

# Clean up
docker stop live-debug-test 2>/dev/null || true

echo "✅ Test completed!"