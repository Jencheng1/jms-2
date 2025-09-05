#!/bin/bash

# Real-time connection and session monitor for IBM MQ Uniform Cluster
# This script queries actual MQ queue managers for real connection data
# No simulation, no fake data - only real MQ statistics

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'
BOLD='\033[1m'

# Configuration
REFRESH_INTERVAL=2  # seconds
CLEAR_SCREEN=true

# Check if running in terminal
if [ ! -t 1 ]; then
    CLEAR_SCREEN=false
fi

# Function to get connections from a queue manager
get_qm_connections() {
    local qm=$1
    local qm_num=$2
    
    # Get all connections on APP.SVRCONN channel
    local conn_output=$(docker exec qm$qm_num bash -c "echo 'DIS CONN(*) WHERE(CHANNEL EQ APP.SVRCONN) ALL' | runmqsc $qm 2>/dev/null" 2>/dev/null)
    
    # Count connections
    local conn_count=$(echo "$conn_output" | grep -c "CONN(" | tr -d ' ')
    
    # Get connection details
    local conns=$(echo "$conn_output" | grep "CONN(" | sed 's/.*CONN(\([^)]*\)).*/\1/')
    
    # Get application names
    local apps=$(echo "$conn_output" | grep "APPLTAG(" | sed 's/.*APPLTAG(\([^)]*\)).*/\1/' | tr '\n' ',' | sed 's/,$//')
    
    # Get IP addresses
    local ips=$(echo "$conn_output" | grep "CONNAME(" | sed 's/.*CONNAME(\([^)]*\)).*/\1/' | tr '\n' ',' | sed 's/,$//')
    
    echo "$conn_count|$conns|$apps|$ips"
}

# Function to get channel status
get_channel_status() {
    local qm=$1
    local qm_num=$2
    
    # Get channel status
    local status=$(docker exec qm$qm_num bash -c "echo 'DIS CHS(APP.SVRCONN) ALL' | runmqsc $qm 2>/dev/null" 2>/dev/null)
    
    # Count active channels
    local active=$(echo "$status" | grep -c "STATUS(RUNNING)" | tr -d ' ')
    
    echo "$active"
}

# Function to get queue depth
get_queue_depth() {
    local qm=$1
    local qm_num=$2
    
    # Get queue depth
    local depth=$(docker exec qm$qm_num bash -c "echo 'DIS QL(UNIFORM.QUEUE) CURDEPTH' | runmqsc $qm 2>/dev/null" 2>/dev/null | grep "CURDEPTH(" | sed 's/.*CURDEPTH(\([^)]*\)).*/\1/' | tr -d ' ')
    
    if [ -z "$depth" ]; then
        echo "0"
    else
        echo "$depth"
    fi
}

# Function to draw a bar graph
draw_bar() {
    local value=$1
    local max=$2
    local width=30
    
    if [ "$max" -eq 0 ]; then
        max=1
    fi
    
    local filled=$(( (value * width) / max ))
    local empty=$(( width - filled ))
    
    printf "["
    for ((i=0; i<filled; i++)); do
        printf "█"
    done
    for ((i=0; i<empty; i++)); do
        printf "░"
    done
    printf "]"
}

# Main monitoring loop
monitor_loop() {
    local iteration=0
    
    while true; do
        if [ "$CLEAR_SCREEN" = true ]; then
            clear
        fi
        
        # Header
        echo -e "${BOLD}${BLUE}╔══════════════════════════════════════════════════════════════════╗${NC}"
        echo -e "${BOLD}${BLUE}║       IBM MQ UNIFORM CLUSTER - REAL-TIME MONITOR                ║${NC}"
        echo -e "${BOLD}${BLUE}╠══════════════════════════════════════════════════════════════════╣${NC}"
        echo -e "${BOLD}${BLUE}║${NC} Timestamp: $(date '+%Y-%m-%d %H:%M:%S')                              ${BOLD}${BLUE}║${NC}"
        echo -e "${BOLD}${BLUE}║${NC} Refresh: Every ${REFRESH_INTERVAL}s | Iteration: #$((++iteration))                           ${BOLD}${BLUE}║${NC}"
        echo -e "${BOLD}${BLUE}╚══════════════════════════════════════════════════════════════════╝${NC}"
        echo
        
        # Check if QMs are running
        local qm1_running=$(docker ps | grep -c qm1)
        local qm2_running=$(docker ps | grep -c qm2)
        local qm3_running=$(docker ps | grep -c qm3)
        
        if [ "$qm1_running" -eq 0 ] && [ "$qm2_running" -eq 0 ] && [ "$qm3_running" -eq 0 ]; then
            echo -e "${RED}ERROR: No queue managers are running!${NC}"
            echo -e "${YELLOW}Start them with: docker-compose -f docker-compose-simple.yml up -d${NC}"
            sleep "$REFRESH_INTERVAL"
            continue
        fi
        
        # Collect data from all QMs
        declare -A connections channels depths
        local total_connections=0
        local max_connections=0
        
        for i in 1 2 3; do
            if docker ps | grep -q qm$i; then
                # Get connection data
                local conn_data=$(get_qm_connections "QM$i" "$i")
                IFS='|' read -r conn_count conn_ids apps ips <<< "$conn_data"
                connections[$i]=${conn_count:-0}
                
                # Get channel status
                channels[$i]=$(get_channel_status "QM$i" "$i")
                
                # Get queue depth
                depths[$i]=$(get_queue_depth "QM$i" "$i")
                
                total_connections=$((total_connections + ${connections[$i]}))
                if [ "${connections[$i]}" -gt "$max_connections" ]; then
                    max_connections=${connections[$i]}
                fi
            else
                connections[$i]=0
                channels[$i]=0
                depths[$i]=0
            fi
        done
        
        # Connection Distribution Table
        echo -e "${BOLD}${CYAN}CONNECTION DISTRIBUTION${NC}"
        echo -e "┌─────────┬────────────┬──────────┬─────────────────────────────────┬──────────┐"
        echo -e "│ Queue   │ Connections│ Channels │ Distribution                    │ Percent  │"
        echo -e "│ Manager │   (Count)  │ (Active) │                                 │          │"
        echo -e "├─────────┼────────────┼──────────┼─────────────────────────────────┼──────────┤"
        
        for i in 1 2 3; do
            local percent=0
            if [ "$total_connections" -gt 0 ]; then
                percent=$(( (${connections[$i]} * 100) / total_connections ))
            fi
            
            local status_icon="✓"
            local color=$GREEN
            if [ "${connections[$i]}" -eq 0 ]; then
                if docker ps | grep -q qm$i; then
                    status_icon="○"
                    color=$YELLOW
                else
                    status_icon="✗"
                    color=$RED
                fi
            fi
            
            printf "│ QM%d %b%s%b  │     %2d     │    %2d    │ " "$i" "$color" "$status_icon" "$NC" "${connections[$i]}" "${channels[$i]}"
            draw_bar "${connections[$i]}" "$max_connections"
            printf " │  %3d%%    │\n" "$percent"
        done
        
        echo -e "├─────────┼────────────┼──────────┼─────────────────────────────────┼──────────┤"
        printf "│ ${BOLD}TOTAL${NC}   │     ${BOLD}%2d${NC}     │    ${BOLD}%2d${NC}    │                                 │  ${BOLD}100%%${NC}    │\n" \
               "$total_connections" "$((${channels[1]} + ${channels[2]} + ${channels[3]}))"
        echo -e "└─────────┴────────────┴──────────┴─────────────────────────────────┴──────────┘"
        echo
        
        # Queue Depths
        echo -e "${BOLD}${CYAN}QUEUE DEPTHS${NC}"
        echo -e "┌─────────┬──────────────┬─────────────────────────────────────┐"
        echo -e "│ Queue   │ Message      │ Visual                              │"
        echo -e "│ Manager │ Count        │                                     │"
        echo -e "├─────────┼──────────────┼─────────────────────────────────────┤"
        
        local max_depth=1
        for i in 1 2 3; do
            if [ "${depths[$i]}" -gt "$max_depth" ]; then
                max_depth=${depths[$i]}
            fi
        done
        
        for i in 1 2 3; do
            printf "│ QM%d     │     %4d     │ " "$i" "${depths[$i]}"
            draw_bar "${depths[$i]}" "$max_depth"
            printf "     │\n"
        done
        
        echo -e "└─────────┴──────────────┴─────────────────────────────────────┘"
        echo
        
        # Connection Details (if any)
        if [ "$total_connections" -gt 0 ]; then
            echo -e "${BOLD}${CYAN}ACTIVE CONNECTION DETAILS${NC}"
            echo -e "┌─────────┬──────────────────────┬─────────────────────────────┐"
            echo -e "│ QM      │ Connection ID        │ Client IP                   │"
            echo -e "├─────────┼──────────────────────┼─────────────────────────────┤"
            
            for i in 1 2 3; do
                if [ "${connections[$i]}" -gt 0 ]; then
                    # Get detailed connection info
                    local conn_details=$(docker exec qm$i bash -c "echo 'DIS CONN(*) WHERE(CHANNEL EQ APP.SVRCONN)' | runmqsc QM$i 2>/dev/null" 2>/dev/null)
                    
                    echo "$conn_details" | grep "CONN(" | while read -r line; do
                        local conn_id=$(echo "$line" | sed 's/.*CONN(\([^)]*\)).*/\1/')
                        
                        # Get IP for this connection
                        local ip_detail=$(docker exec qm$i bash -c "echo 'DIS CONN($conn_id) CONNAME' | runmqsc QM$i 2>/dev/null" 2>/dev/null | grep "CONNAME(" | sed 's/.*CONNAME(\([^)]*\)).*/\1/' | head -1)
                        
                        if [ -n "$conn_id" ]; then
                            printf "│ QM%d     │ %-20s │ %-27s │\n" "$i" "${conn_id:0:20}" "${ip_detail:-N/A}"
                        fi
                    done
                fi
            done
            
            echo -e "└─────────┴──────────────────────┴─────────────────────────────┘"
        fi
        
        echo
        echo -e "${BOLD}${CYAN}STATISTICS SUMMARY${NC}"
        echo -e "• Load Balance: ${BOLD}"
        
        # Calculate standard deviation for balance assessment
        if [ "$total_connections" -gt 0 ]; then
            local ideal_per_qm=$((total_connections / 3))
            local variance_sum=0
            
            for i in 1 2 3; do
                local diff=$((${connections[$i]} - ideal_per_qm))
                variance_sum=$((variance_sum + (diff * diff)))
            done
            
            if [ "$variance_sum" -eq 0 ]; then
                echo -e "${GREEN}PERFECTLY BALANCED${NC}"
            elif [ "$variance_sum" -le 2 ]; then
                echo -e "${GREEN}WELL BALANCED${NC}"
            elif [ "$variance_sum" -le 8 ]; then
                echo -e "${YELLOW}MODERATELY BALANCED${NC}"
            else
                echo -e "${RED}IMBALANCED${NC}"
            fi
        else
            echo -e "${YELLOW}NO CONNECTIONS${NC}"
        fi
        
        echo -e "• Total Connections: ${BOLD}$total_connections${NC}"
        echo -e "• Active Channels: ${BOLD}$((${channels[1]} + ${channels[2]} + ${channels[3]}))${NC}"
        echo -e "• Total Messages: ${BOLD}$((${depths[1]} + ${depths[2]} + ${depths[3]}))${NC}"
        echo
        echo -e "${YELLOW}Press Ctrl+C to stop monitoring${NC}"
        
        sleep "$REFRESH_INTERVAL"
    done
}

# Trap Ctrl+C to exit cleanly
trap 'echo -e "\n${GREEN}Monitoring stopped.${NC}"; exit 0' INT

# Start monitoring
echo -e "${BOLD}${GREEN}Starting Real-Time MQ Uniform Cluster Monitor...${NC}"
echo -e "${YELLOW}This monitor queries actual MQ queue managers for real data.${NC}"
echo -e "${YELLOW}No simulation or fake data - only real MQ connections!${NC}"
echo
sleep 1

monitor_loop