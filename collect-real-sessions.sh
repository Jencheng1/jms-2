#!/bin/bash

# Real session and connection data collector for MQ Uniform Cluster
# This script collects ACTUAL connection and session data from running QMs
# No simulation, no UUIDs, no fake data - only real MQ telemetry

export PATH=$PATH:/usr/local/bin:/bin:/usr/bin

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}============================================${NC}"
echo -e "${BLUE}MQ UNIFORM CLUSTER - REAL SESSION COLLECTOR${NC}"
echo -e "${BLUE}============================================${NC}"
echo "Start Time: $(date)"
echo ""

# Create results directory
RESULTS_DIR="real_session_data_$(date +%Y%m%d_%H%M%S)"
mkdir -p "$RESULTS_DIR"

# Function to get real connection data
get_connection_data() {
    local qm=$1
    local qm_num=$2
    
    echo "Collecting data from $qm..."
    
    # Get all connections with full details
    local conn_data=$(docker exec qm$qm_num bash -c "
        echo 'DIS CONN(*) ALL' | runmqsc $qm 2>/dev/null | 
        grep -E 'CONN\(|CHANNEL\(|CONNAME\(|APPLTAG\(|APPLTYPE\(|USERID\(|UOWSTATE\(|UOWLOG\('
    " 2>/dev/null)
    
    echo "$conn_data"
}

# Function to get real session/handle data
get_session_data() {
    local qm=$1
    local qm_num=$2
    
    # Get channel status (sessions)
    local session_data=$(docker exec qm$qm_num bash -c "
        echo 'DIS CHSTATUS(*) ALL' | runmqsc $qm 2>/dev/null |
        grep -E 'CHSTATUS\(|CHANNEL\(|CONNAME\(|STATUS\(|MSGS\(|BYTSSENT\(|BYTSRCVD\(|JOBNAME\('
    " 2>/dev/null)
    
    echo "$session_data"
}

# Function to count real objects
count_objects() {
    local qm=$1
    local qm_num=$2
    
    # Count connections
    local conn_count=$(docker exec qm$qm_num bash -c "
        echo 'DIS CONN(*) WHERE(CHANNEL NE SYSTEM.*)' | runmqsc $qm 2>/dev/null | 
        grep -c 'CONN('
    " 2>/dev/null || echo 0)
    
    # Count active channels (sessions)
    local session_count=$(docker exec qm$qm_num bash -c "
        echo 'DIS CHSTATUS(*) WHERE(CHANNEL NE SYSTEM.*)' | runmqsc $qm 2>/dev/null |
        grep -c 'CHSTATUS('
    " 2>/dev/null || echo 0)
    
    echo "$conn_count|$session_count"
}

echo -e "${YELLOW}Phase 1: Verifying Queue Managers${NC}"
for i in 1 2 3; do
    if docker ps | grep -q "qm$i"; then
        echo -e "  ${GREEN}✓${NC} QM$i is running"
    else
        echo -e "  ${RED}✗${NC} QM$i is not running"
        exit 1
    fi
done

echo ""
echo -e "${YELLOW}Phase 2: Collecting Initial State${NC}"

# Collect baseline data
{
    echo "=========================================="
    echo "BASELINE CONNECTION AND SESSION DATA"
    echo "=========================================="
    echo "Collection Time: $(date)"
    echo ""
    
    for i in 1 2 3; do
        echo "Queue Manager: QM$i"
        echo "-------------------"
        
        IFS='|' read -r conns sessions <<< $(count_objects "QM$i" "$i")
        echo "  Connections: $conns"
        echo "  Sessions: $sessions"
        echo ""
    done
} | tee "$RESULTS_DIR/baseline.txt"

echo ""
echo -e "${YELLOW}Phase 3: Creating Test Connections${NC}"

# Create real test connections using MQ samples
for i in 1 2 3; do
    echo "  Creating test clients for QM$i..."
    
    # Use MQ sample programs to create real connections
    for j in 1 2 3; do
        docker exec -d qm$i bash -c "
            # Create a persistent connection using amqsphac (high availability client)
            export MQSERVER='APP.SVRCONN/TCP/localhost(1414)'
            
            # Run sample program in background
            /opt/mqm/samp/bin/amqsphac UNIFORM.QUEUE QM$i &
            
            # Also create a putting client
            echo 'Test Message $j' | /opt/mqm/samp/bin/amqsputc UNIFORM.QUEUE QM$i &
        " 2>/dev/null
    done
done

echo "  Waiting for connections to establish..."
sleep 10

echo ""
echo -e "${YELLOW}Phase 4: Collecting Detailed Connection Data${NC}"

# Now collect detailed data with active connections
{
    echo "=========================================="
    echo "ACTIVE CONNECTION AND SESSION ANALYSIS"
    echo "=========================================="
    echo "Collection Time: $(date)"
    echo ""
    
    total_connections=0
    total_sessions=0
    declare -A qm_connections qm_sessions
    
    for i in 1 2 3; do
        echo ""
        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        echo "Queue Manager: QM$i"
        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        
        # Get counts
        IFS='|' read -r conns sessions <<< $(count_objects "QM$i" "$i")
        qm_connections[$i]=$conns
        qm_sessions[$i]=$sessions
        total_connections=$((total_connections + conns))
        total_sessions=$((total_sessions + sessions))
        
        echo ""
        echo "CONNECTION DETAILS (Parent):"
        echo "----------------------------"
        
        # Get detailed connection info
        docker exec qm$i bash -c "
            echo 'DIS CONN(*) WHERE(CHANNEL NE SYSTEM.*) CHANNEL CONNAME APPLTAG USERID' | 
            runmqsc QM$i 2>/dev/null
        " | grep -A5 "CONN(" | while IFS= read -r line; do
            if [[ $line == *"CONN("* ]]; then
                conn_id=$(echo "$line" | sed -n 's/.*CONN(\([^)]*\)).*/\1/p')
                echo "  Connection: $conn_id"
            elif [[ $line == *"CHANNEL("* ]]; then
                channel=$(echo "$line" | sed -n 's/.*CHANNEL(\([^)]*\)).*/\1/p')
                echo "    Channel: $channel"
            elif [[ $line == *"CONNAME("* ]]; then
                client=$(echo "$line" | sed -n 's/.*CONNAME(\([^)]*\)).*/\1/p')
                echo "    Client: $client"
            elif [[ $line == *"APPLTAG("* ]]; then
                app=$(echo "$line" | sed -n 's/.*APPLTAG(\([^)]*\)).*/\1/p')
                echo "    Application: $app"
            fi
        done
        
        echo ""
        echo "SESSION/CHANNEL STATUS (Children):"
        echo "-----------------------------------"
        
        # Get detailed session info
        docker exec qm$i bash -c "
            echo 'DIS CHSTATUS(*) WHERE(CHANNEL NE SYSTEM.*) STATUS CONNAME MSGS BYTSSENT' | 
            runmqsc QM$i 2>/dev/null
        " | grep -A5 "CHSTATUS(" | while IFS= read -r line; do
            if [[ $line == *"CHSTATUS("* ]]; then
                channel=$(echo "$line" | sed -n 's/.*CHSTATUS(\([^)]*\)).*/\1/p')
                echo "  Session: $channel"
            elif [[ $line == *"STATUS("* ]]; then
                status=$(echo "$line" | sed -n 's/.*STATUS(\([^)]*\)).*/\1/p')
                echo "    Status: $status"
            elif [[ $line == *"CONNAME("* ]]; then
                client=$(echo "$line" | sed -n 's/.*CONNAME(\([^)]*\)).*/\1/p')
                echo "    Client: $client"
            elif [[ $line == *"MSGS("* ]]; then
                msgs=$(echo "$line" | sed -n 's/.*MSGS(\([^)]*\)).*/\1/p')
                echo "    Messages: $msgs"
            fi
        done
        
        echo ""
        echo "SUMMARY for QM$i:"
        echo "  Parent Connections: $conns"
        echo "  Active Sessions: $sessions"
        if [ "$conns" -gt 0 ]; then
            ratio=$(echo "scale=2; $sessions / $conns" | bc 2>/dev/null || echo "N/A")
            echo "  Sessions per Connection: $ratio"
        fi
    done
    
    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "DISTRIBUTION ANALYSIS"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""
    echo "Total Parent Connections: $total_connections"
    echo "Total Active Sessions: $total_sessions"
    echo ""
    
    if [ "$total_connections" -gt 0 ]; then
        echo "Connection Distribution:"
        for i in 1 2 3; do
            pct=$(echo "scale=1; ${qm_connections[$i]} * 100 / $total_connections" | bc 2>/dev/null || echo 0)
            
            # Visual bar
            bar=""
            bar_len=$(echo "scale=0; $pct / 5" | bc 2>/dev/null || echo 0)
            for ((j=0; j<bar_len; j++)); do bar="${bar}█"; done
            for ((j=bar_len; j<20; j++)); do bar="${bar}░"; done
            
            echo "  QM$i: [$bar] ${qm_connections[$i]} connections ($pct%)"
        done
    fi
    
    if [ "$total_sessions" -gt 0 ]; then
        echo ""
        echo "Session Distribution:"
        for i in 1 2 3; do
            pct=$(echo "scale=1; ${qm_sessions[$i]} * 100 / $total_sessions" | bc 2>/dev/null || echo 0)
            
            # Visual bar
            bar=""
            bar_len=$(echo "scale=0; $pct / 5" | bc 2>/dev/null || echo 0)
            for ((j=0; j<bar_len; j++)); do bar="${bar}█"; done
            for ((j=bar_len; j<20; j++)); do bar="${bar}░"; done
            
            echo "  QM$i: [$bar] ${qm_sessions[$i]} sessions ($pct%)"
        done
    fi
    
} | tee "$RESULTS_DIR/connection_analysis.txt"

echo ""
echo -e "${YELLOW}Phase 5: Monitoring Distribution Over Time${NC}"

# Monitor for 30 seconds to show distribution
{
    echo ""
    echo "TIME-SERIES DISTRIBUTION DATA"
    echo "=============================="
    echo ""
    
    for iteration in 1 2 3; do
        echo "Sample #$iteration - $(date '+%H:%M:%S')"
        echo "------------------------"
        
        for i in 1 2 3; do
            IFS='|' read -r conns sessions <<< $(count_objects "QM$i" "$i")
            echo "  QM$i: Connections=$conns, Sessions=$sessions"
        done
        echo ""
        
        if [ "$iteration" -lt 3 ]; then
            sleep 10
        fi
    done
    
} | tee -a "$RESULTS_DIR/time_series.txt"

echo ""
echo -e "${GREEN}============================================${NC}"
echo -e "${GREEN}DATA COLLECTION COMPLETE${NC}"
echo -e "${GREEN}============================================${NC}"
echo ""
echo "Results saved in: $RESULTS_DIR/"
echo "  - baseline.txt (initial state)"
echo "  - connection_analysis.txt (detailed analysis)"
echo "  - time_series.txt (distribution over time)"
echo ""
echo "Key Findings:"
echo "  • Real connection counts per QM"
echo "  • Active session distribution"
echo "  • Parent-child relationships"
echo "  • Distribution percentages with visual bars"
echo ""