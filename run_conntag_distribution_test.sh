#!/bin/bash

echo "=========================================================================="
echo "CONNTAG DISTRIBUTION TEST - Multiple Iterations"
echo "=========================================================================="
echo "This test runs multiple times to demonstrate QM distribution"
echo "With affinity:none, connections randomly select QMs"
echo ""

# Create results directory
RESULTS_DIR="conntag_distribution_$(date +%Y%m%d_%H%M%S)"
mkdir -p $RESULTS_DIR

echo "Results directory: $RESULTS_DIR"
echo ""

# Summary file
SUMMARY_FILE="$RESULTS_DIR/DISTRIBUTION_SUMMARY.txt"

echo "CONNTAG Distribution Test Summary" > $SUMMARY_FILE
echo "=================================" >> $SUMMARY_FILE
echo "Test Date: $(date)" >> $SUMMARY_FILE
echo "" >> $SUMMARY_FILE

# Function to run single test
run_single_test() {
    local iteration=$1
    echo "----------------------------------------"
    echo "ITERATION $iteration - $(date +%H:%M:%S)"
    echo "----------------------------------------"
    
    # Run the Java test
    timeout 125 docker run --rm --network mq-uniform-cluster_mqnet \
        -v "/home/ec2-user/unified/demo5/mq-uniform-cluster:/app" \
        -v "/home/ec2-user/unified/demo5/mq-uniform-cluster/libs:/libs" \
        -v "/home/ec2-user/unified/demo5/mq-uniform-cluster/mq/ccdt:/workspace/ccdt" \
        openjdk:17 java -cp "/app:/libs/*" UniformClusterConntagAnalysisTest > $RESULTS_DIR/iteration_${iteration}.log 2>&1 &
    
    JAVA_PID=$!
    
    # Wait for connections to establish
    sleep 10
    
    # Extract tracking key
    TRACKING_KEY=$(grep "BASE TRACKING KEY:" $RESULTS_DIR/iteration_${iteration}.log 2>/dev/null | awk '{print $NF}')
    
    if [ -z "$TRACKING_KEY" ]; then
        echo "Failed to get tracking key for iteration $iteration"
        wait $JAVA_PID 2>/dev/null
        return
    fi
    
    echo "Tracking Key: $TRACKING_KEY"
    
    # Check distribution
    C1_QM=""
    C2_QM=""
    
    # Check each QM
    for qm in qm1 qm2 qm3; do
        # Check for C1
        C1_COUNT=$(docker exec $qm bash -c "echo 'DIS CONN(*) WHERE(APPLTAG EQ '\''${TRACKING_KEY}-C1'\'') ALL' | runmqsc ${qm^^}" 2>/dev/null | grep -c "CONN(" || echo "0")
        if [ $C1_COUNT -gt 0 ]; then
            C1_QM="${qm^^}"
            C1_CONNTAG=$(docker exec $qm bash -c "echo 'DIS CONN(*) WHERE(APPLTAG EQ '\''${TRACKING_KEY}-C1'\'') ALL' | runmqsc ${qm^^}" 2>/dev/null | grep "CONNTAG(" | head -1)
        fi
        
        # Check for C2
        C2_COUNT=$(docker exec $qm bash -c "echo 'DIS CONN(*) WHERE(APPLTAG EQ '\''${TRACKING_KEY}-C2'\'') ALL' | runmqsc ${qm^^}" 2>/dev/null | grep -c "CONN(" || echo "0")
        if [ $C2_COUNT -gt 0 ]; then
            C2_QM="${qm^^}"
            C2_CONNTAG=$(docker exec $qm bash -c "echo 'DIS CONN(*) WHERE(APPLTAG EQ '\''${TRACKING_KEY}-C2'\'') ALL' | runmqsc ${qm^^}" 2>/dev/null | grep "CONNTAG(" | head -1)
        fi
    done
    
    # Display results
    echo "Connection 1: $C1_QM (6 connections expected)"
    echo "Connection 2: $C2_QM (4 connections expected)"
    
    if [ "$C1_QM" != "$C2_QM" ]; then
        echo "✅ DISTRIBUTION SUCCESS - Different QMs!"
        DISTRIBUTION="SUCCESS"
    else
        echo "⚠️  Same QM - Random selection chose same QM"
        DISTRIBUTION="SAME_QM"
    fi
    
    # Log CONNTAG patterns
    echo ""
    echo "CONNTAG Patterns:"
    echo "C1: $C1_CONNTAG" | head -c 100
    echo ""
    echo "C2: $C2_CONNTAG" | head -c 100
    echo ""
    
    # Save to summary
    echo "Iteration $iteration: C1→$C1_QM, C2→$C2_QM - $DISTRIBUTION" >> $SUMMARY_FILE
    echo "  C1 CONNTAG: ${C1_CONNTAG:0:80}..." >> $SUMMARY_FILE
    echo "  C2 CONNTAG: ${C2_CONNTAG:0:80}..." >> $SUMMARY_FILE
    echo "" >> $SUMMARY_FILE
    
    # Wait for test to complete
    wait $JAVA_PID 2>/dev/null
    
    echo ""
}

# Run 5 iterations
SUCCESS_COUNT=0
for i in {1..5}; do
    run_single_test $i
    
    # Check if distribution was successful
    if grep -q "SUCCESS" $RESULTS_DIR/iteration_${i}.log 2>/dev/null; then
        ((SUCCESS_COUNT++))
    fi
    
    # Short pause between iterations
    if [ $i -lt 5 ]; then
        sleep 3
    fi
done

echo "=========================================================================="
echo "FINAL DISTRIBUTION SUMMARY"
echo "=========================================================================="

# Calculate statistics
echo ""
echo "Distribution Statistics:"
cat $SUMMARY_FILE | grep "Iteration" | while read line; do
    echo "  $line"
done

echo ""
echo "CONNTAG Analysis:"
echo "- Each parent connection generates unique CONNTAG"
echo "- CONNTAG contains QM name showing distribution"
echo "- All child sessions inherit parent's CONNTAG"

echo ""
echo "Success Rate: Check iterations above for different QM distributions"
echo "With random selection, expect 60-80% different QM rate"

echo ""
echo "Full results saved in: $RESULTS_DIR/"
echo "Summary file: $SUMMARY_FILE"

# Show unique CONNTAG patterns
echo ""
echo "Unique CONNTAG patterns observed:"
grep "CONNTAG:" $SUMMARY_FILE | cut -d: -f2 | sort -u | head -5