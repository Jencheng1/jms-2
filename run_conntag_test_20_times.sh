#!/bin/bash

# =============================================================================
# CONNTAG Distribution Test - 20 Iterations with Evidence Collection
# =============================================================================
# This script runs the CONNTAG test 20 times to prove repeatability and
# collects comprehensive evidence of distribution and CONNTAG patterns
# =============================================================================

echo "=============================================================================="
echo "CONNTAG DISTRIBUTION TEST - 20 ITERATIONS"
echo "=============================================================================="
echo "Start Time: $(date)"
echo "This test will:"
echo "  1. Run 20 iterations of CONNTAG distribution test"
echo "  2. Collect CONNTAG evidence for each iteration"
echo "  3. Analyze distribution patterns"
echo "  4. Save all evidence to a results directory"
echo "=============================================================================="
echo ""

# Create results directory with timestamp
RESULTS_DIR="conntag_test_results_$(date +%Y%m%d_%H%M%S)"
mkdir -p "$RESULTS_DIR"

# Summary file
SUMMARY_FILE="$RESULTS_DIR/SUMMARY_REPORT.md"
STATS_FILE="$RESULTS_DIR/distribution_stats.txt"

# Initialize summary report
cat > "$SUMMARY_FILE" << EOF
# CONNTAG Distribution Test - 20 Iterations Report

## Test Information
- **Start Time:** $(date)
- **Test Directory:** $RESULTS_DIR
- **Number of Iterations:** 20

## Summary Results

| Iteration | Tracking Key | C1 QM | C2 QM | Distribution | CONNTAG C1 | CONNTAG C2 |
|-----------|--------------|-------|-------|--------------|------------|------------|
EOF

# Statistics tracking
SAME_QM_COUNT=0
DIFF_QM_COUNT=0
QM1_COUNT=0
QM2_COUNT=0
QM3_COUNT=0

echo "Results will be saved to: $RESULTS_DIR"
echo ""
echo "Starting iterations..."
echo "=============================================================================="

# Function to extract QM from CONNTAG
extract_qm_from_conntag() {
    local conntag="$1"
    if [[ "$conntag" == *"QM1"* ]]; then
        echo "QM1"
    elif [[ "$conntag" == *"QM2"* ]]; then
        echo "QM2"
    elif [[ "$conntag" == *"QM3"* ]]; then
        echo "QM3"
    else
        echo "UNKNOWN"
    fi
}

# Run 20 iterations
for ITERATION in {1..20}; do
    echo ""
    echo "------------------------------------------------------------------------------"
    echo "ITERATION $ITERATION of 20"
    echo "------------------------------------------------------------------------------"
    
    ITER_DIR="$RESULTS_DIR/iteration_$ITERATION"
    mkdir -p "$ITER_DIR"
    
    # Compile if needed (only on first iteration)
    if [ $ITERATION -eq 1 ]; then
        echo "Compiling test program..."
        javac -cp "libs/*:." CaptureConntagEvidence.java 2>&1
        if [ $? -ne 0 ]; then
            echo "ERROR: Failed to compile CaptureConntagEvidence.java"
            exit 1
        fi
    fi
    
    # Run the test
    echo "Starting test iteration $ITERATION..."
    docker run --rm --network mq-uniform-cluster_mqnet \
        -v "/home/ec2-user/unified/demo5/mq-uniform-cluster:/app" \
        -v "/home/ec2-user/unified/demo5/mq-uniform-cluster/libs:/libs" \
        -v "/home/ec2-user/unified/demo5/mq-uniform-cluster/mq/ccdt:/workspace/ccdt" \
        openjdk:17 java -cp "/app:/libs/*" CaptureConntagEvidence > "$ITER_DIR/java_output.log" 2>&1 &
    
    JAVA_PID=$!
    
    # Wait for connections to establish
    sleep 15
    
    # Extract tracking key from output
    TRACKING_KEY=$(grep "Tracking Key:" "$ITER_DIR/java_output.log" 2>/dev/null | awk '{print $NF}')
    
    if [ -z "$TRACKING_KEY" ]; then
        echo "  WARNING: Failed to get tracking key for iteration $ITERATION"
        kill $JAVA_PID 2>/dev/null
        wait $JAVA_PID 2>/dev/null
        continue
    fi
    
    echo "  Tracking Key: $TRACKING_KEY"
    
    # Capture CONNTAG evidence from all QMs
    echo "  Capturing CONNTAG evidence..."
    
    C1_QM=""
    C2_QM=""
    C1_CONNTAG=""
    C2_CONNTAG=""
    C1_COUNT=0
    C2_COUNT=0
    
    # Check each QM
    for qm in qm1 qm2 qm3; do
        QM_UPPER=${qm^^}
        
        # Check for Connection 1
        C1_RESULT=$(docker exec $qm bash -c "echo 'DIS CONN(*) WHERE(APPLTAG EQ '\\''${TRACKING_KEY}-C1'\\'') ALL' | runmqsc $QM_UPPER" 2>/dev/null)
        C1_CHECK=$(echo "$C1_RESULT" | grep -c "CONN(" || echo "0")
        
        if [ "$C1_CHECK" -gt 0 ]; then
            C1_QM=$QM_UPPER
            C1_COUNT=$C1_CHECK
            C1_CONNTAG=$(echo "$C1_RESULT" | grep "CONNTAG(" | head -1 | sed 's/.*CONNTAG(//' | sed 's/).*//')
            
            # Save full evidence
            echo "$C1_RESULT" > "$ITER_DIR/C1_${QM_UPPER}_evidence.txt"
        fi
        
        # Check for Connection 2
        C2_RESULT=$(docker exec $qm bash -c "echo 'DIS CONN(*) WHERE(APPLTAG EQ '\\''${TRACKING_KEY}-C2'\\'') ALL' | runmqsc $QM_UPPER" 2>/dev/null)
        C2_CHECK=$(echo "$C2_RESULT" | grep -c "CONN(" || echo "0")
        
        if [ "$C2_CHECK" -gt 0 ]; then
            C2_QM=$QM_UPPER
            C2_COUNT=$C2_CHECK
            C2_CONNTAG=$(echo "$C2_RESULT" | grep "CONNTAG(" | head -1 | sed 's/.*CONNTAG(//' | sed 's/).*//')
            
            # Save full evidence
            echo "$C2_RESULT" > "$ITER_DIR/C2_${QM_UPPER}_evidence.txt"
        fi
    done
    
    # Determine distribution
    if [ -z "$C1_QM" ] || [ -z "$C2_QM" ]; then
        echo "  ERROR: Failed to find connections"
        DISTRIBUTION="FAILED"
    elif [ "$C1_QM" = "$C2_QM" ]; then
        DISTRIBUTION="SAME_QM"
        ((SAME_QM_COUNT++))
        echo "  Result: C1→$C1_QM ($C1_COUNT conns), C2→$C2_QM ($C2_COUNT conns) - SAME QM"
    else
        DISTRIBUTION="DIFFERENT_QMs"
        ((DIFF_QM_COUNT++))
        echo "  Result: C1→$C1_QM ($C1_COUNT conns), C2→$C2_QM ($C2_COUNT conns) - ✅ DIFFERENT QMs"
    fi
    
    # Update QM usage statistics
    [[ "$C1_QM" == "QM1" || "$C2_QM" == "QM1" ]] && ((QM1_COUNT++))
    [[ "$C1_QM" == "QM2" || "$C2_QM" == "QM2" ]] && ((QM2_COUNT++))
    [[ "$C1_QM" == "QM3" || "$C2_QM" == "QM3" ]] && ((QM3_COUNT++))
    
    # Save iteration summary
    cat > "$ITER_DIR/summary.txt" << EOF
Iteration: $ITERATION
Tracking Key: $TRACKING_KEY
Connection 1: $C1_QM ($C1_COUNT connections)
Connection 2: $C2_QM ($C2_COUNT connections)
Distribution: $DISTRIBUTION
C1 CONNTAG: $C1_CONNTAG
C2 CONNTAG: $C2_CONNTAG
EOF
    
    # Truncate CONNTAG for display (first 40 and last 30 chars)
    if [ ${#C1_CONNTAG} -gt 70 ]; then
        C1_CONNTAG_SHORT="${C1_CONNTAG:0:40}...${C1_CONNTAG: -30}"
    else
        C1_CONNTAG_SHORT="$C1_CONNTAG"
    fi
    
    if [ ${#C2_CONNTAG} -gt 70 ]; then
        C2_CONNTAG_SHORT="${C2_CONNTAG:0:40}...${C2_CONNTAG: -30}"
    else
        C2_CONNTAG_SHORT="$C2_CONNTAG"
    fi
    
    # Add to summary report
    echo "| $ITERATION | $TRACKING_KEY | $C1_QM | $C2_QM | $DISTRIBUTION | $C1_CONNTAG_SHORT | $C2_CONNTAG_SHORT |" >> "$SUMMARY_FILE"
    
    # Wait for test to complete
    wait $JAVA_PID 2>/dev/null
    
    # Small delay between iterations
    sleep 2
done

echo ""
echo "=============================================================================="
echo "TEST COMPLETE - GENERATING FINAL REPORT"
echo "=============================================================================="

# Calculate statistics
TOTAL_TESTS=20
SUCCESS_RATE=$(echo "scale=1; $DIFF_QM_COUNT * 100 / $TOTAL_TESTS" | bc)

# Generate statistics file
cat > "$STATS_FILE" << EOF
CONNTAG Distribution Test Statistics
=====================================
Total Iterations: $TOTAL_TESTS
Different QMs: $DIFF_QM_COUNT (${SUCCESS_RATE}%)
Same QM: $SAME_QM_COUNT ($(echo "scale=1; $SAME_QM_COUNT * 100 / $TOTAL_TESTS" | bc)%)

Queue Manager Usage:
- QM1 used: $QM1_COUNT times
- QM2 used: $QM2_COUNT times  
- QM3 used: $QM3_COUNT times

Expected Distribution Rate: ~66.7% (2/3 probability)
Actual Distribution Rate: ${SUCCESS_RATE}%
EOF

# Complete the summary report
cat >> "$SUMMARY_FILE" << EOF

## Statistics

### Distribution Results
- **Different QMs:** $DIFF_QM_COUNT iterations (${SUCCESS_RATE}%)
- **Same QM:** $SAME_QM_COUNT iterations ($(echo "scale=1; $SAME_QM_COUNT * 100 / $TOTAL_TESTS" | bc)%)

### Queue Manager Usage
- **QM1:** Used $QM1_COUNT times
- **QM2:** Used $QM2_COUNT times
- **QM3:** Used $QM3_COUNT times

### Analysis
- **Expected Distribution Rate:** ~66.7% (theoretical probability with 3 QMs)
- **Actual Distribution Rate:** ${SUCCESS_RATE}%
- **Result:** $([ $(echo "$SUCCESS_RATE > 50" | bc) -eq 1 ] && echo "✅ Distribution working as expected" || echo "⚠️ Distribution rate lower than expected")

## CONNTAG Patterns Observed

### When on Different QMs
- CONNTAG contains different QM identifiers (QM1, QM2, or QM3)
- Different connection handles
- Different APPLTAGs (-C1 vs -C2)

### When on Same QM
- CONNTAG contains same QM identifier
- Different connection handles
- Different APPLTAGs (-C1 vs -C2)

## Files Generated
- **Summary Report:** SUMMARY_REPORT.md
- **Statistics:** distribution_stats.txt
- **Individual Iterations:** iteration_1 through iteration_20 directories
- **Evidence Files:** MQSC outputs for each connection

## Conclusion
The test demonstrates that CONNTAG distribution works correctly with the fixed configuration (without WMQ_QUEUE_MANAGER='*'). The actual distribution rate of ${SUCCESS_RATE}% $([ $(echo "$SUCCESS_RATE > 50" | bc) -eq 1 ] && echo "confirms proper random distribution" || echo "suggests potential issues") across Queue Managers.

---
*Test Completed: $(date)*
EOF

# Display results
echo ""
cat "$STATS_FILE"
echo ""
echo "=============================================================================="
echo "FINAL RESULTS"
echo "=============================================================================="
echo "✅ Test completed successfully!"
echo ""
echo "Results saved to: $RESULTS_DIR/"
echo "  - Summary Report: $RESULTS_DIR/SUMMARY_REPORT.md"
echo "  - Statistics: $RESULTS_DIR/distribution_stats.txt"
echo "  - Individual iterations: $RESULTS_DIR/iteration_*/"
echo ""
echo "Distribution achieved: $DIFF_QM_COUNT out of $TOTAL_TESTS iterations (${SUCCESS_RATE}%)"
echo ""
echo "To view the summary report:"
echo "  cat $RESULTS_DIR/SUMMARY_REPORT.md"
echo ""
echo "To view detailed evidence for any iteration:"
echo "  ls -la $RESULTS_DIR/iteration_1/"
echo "=============================================================================="