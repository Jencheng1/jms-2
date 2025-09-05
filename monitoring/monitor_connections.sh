#!/bin/bash

# IBM MQ Uniform Cluster Connection Monitor
# This script monitors and displays connection distribution across queue managers

echo "================================================"
echo "IBM MQ Uniform Cluster - Connection Monitor"
echo "================================================"
echo ""

# Function to check connections on a queue manager
check_qm_connections() {
    local qm_name=$1
    local container_name=$2
    
    echo "[$qm_name] Checking connections..."
    
    # Display active client connections
    docker exec $container_name bash -c "echo 'DISPLAY CONN(*) WHERE(CHANNEL EQ APP.SVRCONN)' | runmqsc $qm_name" 2>/dev/null | \
        grep -E "CONN\(|CHANNEL\(|CONNAME\(" | \
        awk '/CONN\(/ {conn=$1} /CHANNEL\(/ {channel=$1} /CONNAME\(/ {print "  Connection: " conn " Channel: " channel " Client: " $1}'
    
    # Count total connections
    local conn_count=$(docker exec $container_name bash -c "echo 'DISPLAY CONN(*) WHERE(CHANNEL EQ APP.SVRCONN)' | runmqsc $qm_name" 2>/dev/null | \
        grep -c "CONN(")
    
    echo "  Total connections: $conn_count"
    echo ""
}

# Function to check queue depth
check_queue_depth() {
    local qm_name=$1
    local container_name=$2
    
    echo "[$qm_name] Queue depths:"
    
    docker exec $container_name bash -c "echo 'DISPLAY QL(UNIFORM.QUEUE) CURDEPTH' | runmqsc $qm_name" 2>/dev/null | \
        grep "CURDEPTH" | \
        awk '{print "  UNIFORM.QUEUE: " $1}'
    
    echo ""
}

# Function to check cluster status
check_cluster_status() {
    local qm_name=$1
    local container_name=$2
    
    echo "[$qm_name] Cluster status:"
    
    docker exec $container_name bash -c "echo 'DISPLAY CLUSQMGR(*)' | runmqsc $qm_name" 2>/dev/null | \
        grep -E "CLUSQMGR\(|STATUS\(" | \
        awk '/CLUSQMGR\(/ {qm=$1} /STATUS\(/ {print "  " qm " Status: " $1}'
    
    echo ""
}

# Main monitoring loop
while true; do
    clear
    echo "================================================"
    echo "IBM MQ Uniform Cluster - Live Monitor"
    echo "Time: $(date '+%Y-%m-%d %H:%M:%S')"
    echo "================================================"
    echo ""
    
    # Check each queue manager
    for qm in qm1 qm2 qm3; do
        upper_qm=$(echo $qm | tr '[:lower:]' '[:upper:]')
        
        echo "------------ Queue Manager: $upper_qm ------------"
        
        # Check if container is running
        if docker ps --format "table {{.Names}}" | grep -q "^$qm$"; then
            check_qm_connections $upper_qm $qm
            check_queue_depth $upper_qm $qm
            
            if [ "$qm" == "qm1" ]; then
                check_cluster_status $upper_qm $qm
            fi
        else
            echo "  [Container not running]"
            echo ""
        fi
    done
    
    echo "================================================"
    echo "Press Ctrl+C to exit, refreshing in 5 seconds..."
    sleep 5
done