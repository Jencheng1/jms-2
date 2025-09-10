#!/bin/bash

echo "================================================"
echo "SELECTIVE FAILOVER TEST - FINDING DIFFERENT QMs"
echo "================================================"
echo ""

# Ensure all QMs are running
echo "Starting all Queue Managers..."
docker start qm1 qm2 qm3 2>/dev/null
sleep 3

# Compile the test
echo "Compiling test..."
javac -cp "libs/*:." ProperSelectiveFailoverTest.java

# Keep trying until we get C1 and C2 on different QMs
MAX_ATTEMPTS=10
ATTEMPT=1

while [ $ATTEMPT -le $MAX_ATTEMPTS ]; do
    echo ""
    echo "Attempt $ATTEMPT of $MAX_ATTEMPTS..."
    
    # Run the test and check initial distribution
    OUTPUT=$(docker run --rm \
        --network mq-uniform-cluster_mqnet \
        -v "$(pwd):/app" \
        -v "$(pwd)/libs:/libs" \
        -v "$(pwd)/mq/ccdt:/workspace/ccdt" \
        openjdk:17 \
        java -cp "/app:/libs/*" ProperSelectiveFailoverTest 2>&1 | head -20)
    
    # Extract C1 and C2 QMs
    C1_QM=$(echo "$OUTPUT" | grep "C1: Connected to" | awk '{print $4}')
    C2_QM=$(echo "$OUTPUT" | grep "C2: Connected to" | awk '{print $4}')
    
    echo "C1 on: $C1_QM"
    echo "C2 on: $C2_QM"
    
    if [ "$C1_QM" != "$C2_QM" ] && [ -n "$C1_QM" ] && [ -n "$C2_QM" ]; then
        echo ""
        echo "✅ SUCCESS! C1 and C2 are on different QMs!"
        echo "C1 on $C1_QM, C2 on $C2_QM"
        echo ""
        echo "Now running full selective failover test..."
        echo "When prompted, stop $C1_QM to test selective failover"
        echo ""
        
        # Run the full test
        docker run --rm \
            --network mq-uniform-cluster_mqnet \
            -v "$(pwd):/app" \
            -v "$(pwd)/libs:/libs" \
            -v "$(pwd)/mq/ccdt:/workspace/ccdt" \
            openjdk:17 \
            java -cp "/app:/libs/*" ProperSelectiveFailoverTest 2>&1 | tee selective_failover_$(date +%s).log
        
        exit 0
    fi
    
    ATTEMPT=$((ATTEMPT + 1))
    sleep 1
done

echo ""
echo "❌ Failed to get C1 and C2 on different QMs after $MAX_ATTEMPTS attempts"
echo "This is due to random CCDT distribution. Try running again."