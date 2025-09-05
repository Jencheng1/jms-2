#!/bin/bash

# IBM MQ Uniform Cluster Distribution Checker
# This script analyzes connection and message distribution

echo "================================================"
echo "IBM MQ Uniform Cluster - Distribution Analysis"
echo "================================================"
echo ""

# Arrays to store statistics
declare -A connections
declare -A messages

# Function to get connection count for a queue manager
get_connection_count() {
    local qm_name=$1
    local container_name=$2
    
    docker exec $container_name bash -c "echo 'DISPLAY CONN(*) WHERE(CHANNEL EQ APP.SVRCONN)' | runmqsc $qm_name" 2>/dev/null | \
        grep -c "CONN("
}

# Function to get queue depth
get_queue_depth() {
    local qm_name=$1
    local container_name=$2
    
    docker exec $container_name bash -c "echo 'DISPLAY QL(UNIFORM.QUEUE) CURDEPTH' | runmqsc $qm_name" 2>/dev/null | \
        grep "CURDEPTH" | \
        sed 's/.*CURDEPTH(//' | sed 's/).*//'
}

# Collect statistics
echo "Collecting statistics..."
echo ""

total_connections=0
total_messages=0

for qm in qm1 qm2 qm3; do
    upper_qm=$(echo $qm | tr '[:lower:]' '[:upper:]')
    
    if docker ps --format "table {{.Names}}" | grep -q "^$qm$"; then
        conn_count=$(get_connection_count $upper_qm $qm)
        queue_depth=$(get_queue_depth $upper_qm $qm)
        
        connections[$upper_qm]=$conn_count
        messages[$upper_qm]=$queue_depth
        
        total_connections=$((total_connections + conn_count))
        total_messages=$((total_messages + queue_depth))
    else
        connections[$upper_qm]=0
        messages[$upper_qm]=0
    fi
done

# Display results
echo "============ Connection Distribution ============"
echo ""

for qm in QM1 QM2 QM3; do
    conn=${connections[$qm]}
    if [ $total_connections -gt 0 ]; then
        percentage=$(awk "BEGIN {printf \"%.1f\", $conn * 100 / $total_connections}")
    else
        percentage="0.0"
    fi
    
    # Create visual bar
    bar_length=$(awk "BEGIN {printf \"%.0f\", $conn * 20 / ($total_connections > 0 ? $total_connections : 1)}")
    bar=""
    for ((i=0; i<bar_length; i++)); do
        bar="${bar}█"
    done
    for ((i=bar_length; i<20; i++)); do
        bar="${bar}░"
    done
    
    printf "%-4s: [%s] %2d connections (%5.1f%%)\n" "$qm" "$bar" "$conn" "$percentage"
done

echo ""
echo "Total connections: $total_connections"
echo ""

echo "============ Message Distribution ============"
echo ""

for qm in QM1 QM2 QM3; do
    msg=${messages[$qm]}
    if [ $total_messages -gt 0 ]; then
        percentage=$(awk "BEGIN {printf \"%.1f\", $msg * 100 / $total_messages}")
    else
        percentage="0.0"
    fi
    
    # Create visual bar
    bar_length=$(awk "BEGIN {printf \"%.0f\", $msg * 20 / ($total_messages > 0 ? $total_messages : 1)}")
    bar=""
    for ((i=0; i<bar_length; i++)); do
        bar="${bar}█"
    done
    for ((i=bar_length; i<20; i++)); do
        bar="${bar}░"
    done
    
    printf "%-4s: [%s] %4d messages (%5.1f%%)\n" "$qm" "$bar" "$msg" "$percentage"
done

echo ""
echo "Total messages in queues: $total_messages"
echo ""

# Calculate distribution metrics
echo "============ Distribution Metrics ============"
echo ""

if [ $total_connections -gt 0 ]; then
    # Calculate standard deviation for connections
    mean=$(awk "BEGIN {printf \"%.2f\", $total_connections / 3}")
    variance=0
    for qm in QM1 QM2 QM3; do
        conn=${connections[$qm]}
        diff=$(awk "BEGIN {printf \"%.2f\", ($conn - $mean) * ($conn - $mean)}")
        variance=$(awk "BEGIN {printf \"%.2f\", $variance + $diff}")
    done
    variance=$(awk "BEGIN {printf \"%.2f\", $variance / 3}")
    std_dev=$(awk "BEGIN {printf \"%.2f\", sqrt($variance)}")
    
    echo "Connection Distribution:"
    echo "  Mean: $mean connections per QM"
    echo "  Standard Deviation: $std_dev"
    
    # Calculate evenness score (0-100, where 100 is perfectly even)
    if [ "$mean" != "0.00" ]; then
        evenness=$(awk "BEGIN {printf \"%.1f\", 100 * (1 - $std_dev / $mean)}")
        if (( $(awk "BEGIN {print ($evenness < 0)}") )); then
            evenness="0.0"
        fi
    else
        evenness="0.0"
    fi
    
    echo "  Evenness Score: ${evenness}% (100% = perfectly even)"
else
    echo "No active connections to analyze"
fi

echo ""
echo "================================================"
echo "Note: For best distribution, ensure:"
echo "  1. CCDT affinity is set to 'none'"
echo "  2. Client reconnect is enabled"
echo "  3. Uniform cluster balancing is active"
echo "================================================"