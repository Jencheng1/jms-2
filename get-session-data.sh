#!/bin/bash

echo "==========================================="
echo "IBM MQ UNIFORM CLUSTER - SESSION DATA"
echo "==========================================="
echo "Time: $(date)"
echo ""

# Function to get detailed connection info
get_connection_details() {
    local qm=$1
    local container=$2
    
    echo "Queue Manager: $qm"
    echo "------------------------"
    
    # Get all connections
    echo "PARENT CONNECTIONS:"
    docker exec $container bash -c "echo 'DISPLAY CONN(*) ALL' | runmqsc $qm 2>/dev/null | grep -E 'CONN\(|CHANNEL\(|CONNAME\(|APPLTAG\('" || echo "  No connections"
    
    echo ""
    echo "ACTIVE SESSIONS (Child):"
    # Get channel status showing active sessions
    docker exec $container bash -c "echo 'DISPLAY CHSTATUS(*) ALL' | runmqsc $qm 2>/dev/null | grep -E 'CHANNEL\(|CONNAME\(|STATUS\(|MSGS\('" || echo "  No active sessions"
    
    echo ""
    echo "HANDLES (Sessions per Connection):"
    # Show handle information
    docker exec $container bash -c "echo 'DISPLAY CONN(*) HSTATE' | runmqsc $qm 2>/dev/null | grep -E 'CONN\(|HSTATE\('" || echo "  No handles"
    
    echo ""
}

# Collect data for each QM
echo "===========================================" 
echo "COLLECTING REAL CONNECTION AND SESSION DATA"
echo "==========================================="
echo ""

for i in 1 2 3; do
    if docker ps | grep -q "qm$i"; then
        get_connection_details "QM$i" "qm$i"
        echo "==========================================="
        echo ""
    fi
done

# Summary statistics
echo "DISTRIBUTION SUMMARY"
echo "===================="

total_conn=0
total_sess=0

for i in 1 2 3; do
    if docker ps | grep -q "qm$i"; then
        # Count connections
        conn=$(docker exec qm$i bash -c "echo 'DIS CONN(*)' | runmqsc QM$i" 2>/dev/null | grep -c "CONN(" || echo 0)
        
        # Count active sessions/channels  
        sess=$(docker exec qm$i bash -c "echo 'DIS CHS(*)' | runmqsc QM$i" 2>/dev/null | grep -c "CHANNEL(" || echo 0)
        
        echo "QM$i:"
        echo "  Parent Connections: $conn"
        echo "  Active Sessions: $sess"
        
        total_conn=$((total_conn + conn))
        total_sess=$((total_sess + sess))
    fi
done

echo ""
echo "TOTALS:"
echo "  Total Connections: $total_conn"
echo "  Total Sessions: $total_sess"

if [ $total_conn -gt 0 ]; then
    echo ""
    echo "CONNECTION DISTRIBUTION:"
    for i in 1 2 3; do
        conn=$(docker exec qm$i bash -c "echo 'DIS CONN(*)' | runmqsc QM$i" 2>/dev/null | grep -c "CONN(" || echo 0)
        pct=$(awk "BEGIN {printf \"%.1f\", $conn * 100 / $total_conn}")
        echo "  QM$i: $pct%"
    done
fi

if [ $total_sess -gt 0 ]; then
    echo ""
    echo "SESSION DISTRIBUTION:"
    for i in 1 2 3; do
        sess=$(docker exec qm$i bash -c "echo 'DIS CHS(*)' | runmqsc QM$i" 2>/dev/null | grep -c "CHANNEL(" || echo 0)
        pct=$(awk "BEGIN {printf \"%.1f\", $sess * 100 / $total_sess}")
        echo "  QM$i: $pct%"
    done
fi

echo ""
echo "==========================================="