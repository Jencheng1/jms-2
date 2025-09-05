#!/bin/bash

# Check REAL connections and sessions on MQ

echo "═══════════════════════════════════════════════════════════════"
echo "REAL-TIME MQ CONNECTION AND SESSION DATA"
echo "═══════════════════════════════════════════════════════════════"
echo "Timestamp: $(date)"
echo ""

for i in 1 2 3; do
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "Queue Manager: QM$i"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    
    echo ""
    echo "CONNECTIONS (Parent):"
    docker exec qm$i bash -c "
        echo 'DIS CONN(*) WHERE(CHANNEL EQ APP.SVRCONN) ALL' | 
        runmqsc QM$i 2>/dev/null | 
        grep -E 'CONN\(|APPLTAG\(|CONNAME\(|CHANNEL\(' | 
        head -20
    "
    
    echo ""
    echo "ACTIVE SESSIONS/CHANNELS:"
    docker exec qm$i bash -c "
        echo 'DIS CHSTATUS(APP.SVRCONN) ALL' | 
        runmqsc QM$i 2>/dev/null | 
        grep -E 'CHSTATUS\(|STATUS\(|CONNAME\(|MSGS\(' | 
        head -20
    "
    
    echo ""
    echo "SUMMARY:"
    conn_count=$(docker exec qm$i bash -c "
        echo 'DIS CONN(*) WHERE(CHANNEL EQ APP.SVRCONN)' | 
        runmqsc QM$i 2>/dev/null | grep -c 'CONN('
    " 2>/dev/null || echo 0)
    
    sess_count=$(docker exec qm$i bash -c "
        echo 'DIS CHSTATUS(APP.SVRCONN)' | 
        runmqsc QM$i 2>/dev/null | grep -c 'CHSTATUS('
    " 2>/dev/null || echo 0)
    
    echo "  Total Connections: $conn_count"
    echo "  Total Sessions: $sess_count"
    echo ""
done

echo "═══════════════════════════════════════════════════════════════"
echo ""

# Check Java containers
echo "JAVA JMS CONTAINERS:"
echo "--------------------"
docker ps -a | grep -E "producer-|consumer-" | while read line; do
    container_id=$(echo $line | awk '{print $1}')
    container_name=$(echo $line | awk '{print $NF}')
    status=$(echo $line | awk '{print $7}')
    
    echo "Container: $container_name (Status: $status)"
    
    # Try to get logs
    if docker logs "$container_id" 2>&1 | head -5 | grep -q "Error\|Exception"; then
        echo "  ERROR FOUND:"
        docker logs "$container_id" 2>&1 | head -10 | sed 's/^/    /'
    else
        echo "  Log sample:"
        docker logs "$container_id" 2>&1 | head -5 | sed 's/^/    /'
    fi
    echo ""
done