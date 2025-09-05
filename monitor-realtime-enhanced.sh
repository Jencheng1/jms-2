#!/bin/bash

# Enhanced real-time monitor for IBM MQ Uniform Cluster
# Shows REAL connections and sessions from MQ with detailed metrics
# No simulation, no fake data - queries actual MQ queue managers

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
MAGENTA='\033[0;35m'
NC='\033[0m'
BOLD='\033[1m'

# Configuration
REFRESH_INTERVAL=10  # Slower refresh rate (10 seconds)
CLEAR_SCREEN=true
SHOW_DETAILS=true

# Check if running in terminal
if [ ! -t 1 ]; then
    CLEAR_SCREEN=false
fi

# Function to get detailed connection and session data
get_detailed_metrics() {
    local qm=$1
    local qm_num=$2
    
    # Get connection count (parent connections)
    local conn_count=$(docker exec qm$qm_num bash -c "
        echo 'DIS CONN(*) WHERE(CHANNEL NE SYSTEM.*)' | runmqsc $qm 2>/dev/null | 
        grep -c 'CONN(' || echo 0
    " 2>/dev/null || echo 0)
    
    # Get session count (active channels)
    local session_count=$(docker exec qm$qm_num bash -c "
        echo 'DIS CHSTATUS(*) WHERE(CHANNEL NE SYSTEM.*)' | runmqsc $qm 2>/dev/null |
        grep -c 'CHSTATUS(' || echo 0
    " 2>/dev/null || echo 0)
    
    # Get queue depth
    local queue_depth=$(docker exec qm$qm_num bash -c "
        echo 'DIS QL(UNIFORM.QUEUE) CURDEPTH' | runmqsc $qm 2>/dev/null |
        grep 'CURDEPTH(' | sed 's/.*CURDEPTH(\([^)]*\)).*/\1/' | tr -d ' '
    " 2>/dev/null || echo 0)
    
    # Get message count
    local msg_count=$(docker exec qm$qm_num bash -c "
        echo 'DIS CHSTATUS(*) MSGS' | runmqsc $qm 2>/dev/null |
        grep 'MSGS(' | sed 's/.*MSGS(\([^)]*\)).*/\1/' | 
        awk '{sum += \$1} END {print sum+0}'
    " 2>/dev/null || echo 0)
    
    echo "$conn_count|$session_count|$queue_depth|$msg_count"
}

# Function to get connection details
get_connection_details() {
    local qm=$1
    local qm_num=$2
    
    docker exec qm$qm_num bash -c "
        echo 'DIS CONN(*) WHERE(CHANNEL NE SYSTEM.*) CHANNEL CONNAME APPLTAG' | 
        runmqsc $qm 2>/dev/null
    " 2>/dev/null | grep -A3 "CONN(" | while read -r line; do
        if [[ $line == *"CONN("* ]]; then
            conn_id=$(echo "$line" | sed -n 's/.*CONN(\([^)]*\)).*/\1/p')
            printf "%-20s" "$conn_id"
        elif [[ $line == *"CHANNEL("* ]]; then
            channel=$(echo "$line" | sed -n 's/.*CHANNEL(\([^)]*\)).*/\1/p')
            printf "%-15s" "$channel"
        elif [[ $line == *"CONNAME("* ]]; then
            client=$(echo "$line" | sed -n 's/.*CONNAME(\([^)]*\)).*/\1/p')
            printf "%-25s" "$client"
        elif [[ $line == *"APPLTAG("* ]]; then
            app=$(echo "$line" | sed -n 's/.*APPLTAG(\([^)]*\)).*/\1/p')
            printf "%-20s\n" "${app:0:20}"
        fi
    done
}

# Function to get session details
get_session_details() {
    local qm=$1
    local qm_num=$2
    
    docker exec qm$qm_num bash -c "
        echo 'DIS CHSTATUS(*) WHERE(CHANNEL NE SYSTEM.*) STATUS CONNAME MSGS' | 
        runmqsc $qm 2>/dev/null
    " 2>/dev/null | grep -A3 "CHSTATUS(" | while read -r line; do
        if [[ $line == *"CHSTATUS("* ]]; then
            channel=$(echo "$line" | sed -n 's/.*CHSTATUS(\([^)]*\)).*/\1/p')
            printf "%-20s" "$channel"
        elif [[ $line == *"STATUS("* ]]; then
            status=$(echo "$line" | sed -n 's/.*STATUS(\([^)]*\)).*/\1/p')
            printf "%-10s" "$status"
        elif [[ $line == *"CONNAME("* ]]; then
            client=$(echo "$line" | sed -n 's/.*CONNAME(\([^)]*\)).*/\1/p')
            printf "%-25s" "$client"
        elif [[ $line == *"MSGS("* ]]; then
            msgs=$(echo "$line" | sed -n 's/.*MSGS(\([^)]*\)).*/\1/p')
            printf "%8s\n" "$msgs"
        fi
    done
}

# Function to draw a bar graph
draw_bar() {
    local value=$1
    local max=$2
    local width=25
    
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

# Function to calculate distribution quality
calc_distribution_quality() {
    local val1=$1
    local val2=$2
    local val3=$3
    local total=$((val1 + val2 + val3))
    
    if [ "$total" -eq 0 ]; then
        echo "NO_DATA"
        return
    fi
    
    local ideal=$((total / 3))
    local diff1=$((val1 - ideal))
    local diff2=$((val2 - ideal))
    local diff3=$((val3 - ideal))
    
    # Calculate variance
    local variance=$(( (diff1 * diff1 + diff2 * diff2 + diff3 * diff3) / 3 ))
    
    if [ "$variance" -le 1 ]; then
        echo "PERFECT"
    elif [ "$variance" -le 5 ]; then
        echo "EXCELLENT"
    elif [ "$variance" -le 15 ]; then
        echo "GOOD"
    elif [ "$variance" -le 30 ]; then
        echo "FAIR"
    else
        echo "POOR"
    fi
}

# Main monitoring loop
monitor_loop() {
    local iteration=0
    local start_time=$(date +%s)
    
    # Arrays to store historical data
    declare -a hist_conn1 hist_conn2 hist_conn3
    declare -a hist_sess1 hist_sess2 hist_sess3
    
    while true; do
        if [ "$CLEAR_SCREEN" = true ]; then
            clear
        fi
        
        local current_time=$(date +%s)
        local elapsed=$((current_time - start_time))
        local elapsed_min=$((elapsed / 60))
        local elapsed_sec=$((elapsed % 60))
        
        # Header
        echo -e "${BOLD}${BLUE}╔════════════════════════════════════════════════════════════════════════════╗${NC}"
        echo -e "${BOLD}${BLUE}║          IBM MQ UNIFORM CLUSTER - ENHANCED REAL-TIME MONITOR              ║${NC}"
        echo -e "${BOLD}${BLUE}╠════════════════════════════════════════════════════════════════════════════╣${NC}"
        echo -e "${BOLD}${BLUE}║${NC} Timestamp: $(date '+%Y-%m-%d %H:%M:%S')     Runtime: ${elapsed_min}m ${elapsed_sec}s     Iteration: #$((++iteration)) ${BOLD}${BLUE}║${NC}"
        echo -e "${BOLD}${BLUE}║${NC} Refresh: Every ${REFRESH_INTERVAL}s | Monitoring REAL MQ connections and sessions         ${BOLD}${BLUE}║${NC}"
        echo -e "${BOLD}${BLUE}╚════════════════════════════════════════════════════════════════════════════╝${NC}"
        echo
        
        # Check QM status
        local qm_running=0
        for i in 1 2 3; do
            if docker ps | grep -q qm$i; then
                ((qm_running++))
            fi
        done
        
        if [ "$qm_running" -eq 0 ]; then
            echo -e "${RED}ERROR: No queue managers are running!${NC}"
            echo -e "${YELLOW}Start with: docker-compose -f docker-compose-simple.yml up -d${NC}"
            sleep "$REFRESH_INTERVAL"
            continue
        fi
        
        # Collect metrics
        declare -A connections sessions depths messages
        local total_connections=0
        local total_sessions=0
        local total_messages=0
        local max_connections=0
        local max_sessions=0
        
        for i in 1 2 3; do
            if docker ps | grep -q qm$i; then
                IFS='|' read -r conn sess depth msgs <<< $(get_detailed_metrics "QM$i" "$i")
                connections[$i]=${conn:-0}
                sessions[$i]=${sess:-0}
                depths[$i]=${depth:-0}
                messages[$i]=${msgs:-0}
                
                # Track history
                eval "hist_conn$i+=($conn)"
                eval "hist_sess$i+=($sess)"
                
                total_connections=$((total_connections + ${connections[$i]}))
                total_sessions=$((total_sessions + ${sessions[$i]}))
                total_messages=$((total_messages + ${messages[$i]}))
                
                [ "${connections[$i]}" -gt "$max_connections" ] && max_connections=${connections[$i]}
                [ "${sessions[$i]}" -gt "$max_sessions" ] && max_sessions=${sessions[$i]}
            else
                connections[$i]=0
                sessions[$i]=0
                depths[$i]=0
                messages[$i]=0
            fi
        done
        
        # CONNECTION & SESSION DISTRIBUTION
        echo -e "${BOLD}${CYAN}CONNECTION & SESSION DISTRIBUTION${NC}"
        echo -e "┌─────────┬─────────────┬──────────┬───────────┬──────────────────────────┬──────────┐"
        echo -e "│ Queue   │ Connections │ Sessions │ Sess/Conn │ Distribution             │ Percent  │"
        echo -e "│ Manager │   (Parent)  │ (Active) │   Ratio   │                          │  (Conn)  │"
        echo -e "├─────────┼─────────────┼──────────┼───────────┼──────────────────────────┼──────────┤"
        
        for i in 1 2 3; do
            local conn_pct=0
            if [ "$total_connections" -gt 0 ]; then
                conn_pct=$(( (${connections[$i]} * 100) / total_connections ))
            fi
            
            local ratio="N/A"
            if [ "${connections[$i]}" -gt 0 ]; then
                ratio=$(echo "scale=1; ${sessions[$i]} / ${connections[$i]}" | bc 2>/dev/null || echo "N/A")
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
            
            printf "│ QM%d %b%s%b  │     %3d     │    %3d   │   %5s   │ " \
                   "$i" "$color" "$status_icon" "$NC" \
                   "${connections[$i]}" "${sessions[$i]}" "$ratio"
            draw_bar "${connections[$i]}" "$max_connections"
            printf " │   %3d%%   │\n" "$conn_pct"
        done
        
        echo -e "├─────────┼─────────────┼──────────┼───────────┼──────────────────────────┼──────────┤"
        
        local avg_ratio="N/A"
        if [ "$total_connections" -gt 0 ]; then
            avg_ratio=$(echo "scale=1; $total_sessions / $total_connections" | bc 2>/dev/null || echo "N/A")
        fi
        
        printf "│ ${BOLD}TOTAL${NC}   │     ${BOLD}%3d${NC}     │    ${BOLD}%3d${NC}   │   ${BOLD}%5s${NC}   │                          │  ${BOLD}100%%${NC}    │\n" \
               "$total_connections" "$total_sessions" "$avg_ratio"
        echo -e "└─────────┴─────────────┴──────────┴───────────┴──────────────────────────┴──────────┘"
        echo
        
        # SESSION DISTRIBUTION
        if [ "$total_sessions" -gt 0 ]; then
            echo -e "${BOLD}${CYAN}SESSION DISTRIBUTION ANALYSIS${NC}"
            echo -e "┌─────────┬──────────┬───────────────────────────┬──────────┐"
            echo -e "│ Queue   │ Sessions │ Visual Distribution       │ Percent  │"
            echo -e "│ Manager │  Count   │                           │          │"
            echo -e "├─────────┼──────────┼───────────────────────────┼──────────┤"
            
            for i in 1 2 3; do
                local sess_pct=0
                if [ "$total_sessions" -gt 0 ]; then
                    sess_pct=$(( (${sessions[$i]} * 100) / total_sessions ))
                fi
                
                printf "│ QM%d     │    %3d   │ " "$i" "${sessions[$i]}"
                draw_bar "${sessions[$i]}" "$max_sessions"
                printf " │   %3d%%   │\n" "$sess_pct"
            done
            
            echo -e "└─────────┴──────────┴───────────────────────────┴──────────┘"
            echo
        fi
        
        # ACTIVE CONNECTION DETAILS
        if [ "$total_connections" -gt 0 ] && [ "$SHOW_DETAILS" = true ]; then
            echo -e "${BOLD}${CYAN}ACTIVE CONNECTIONS (REAL-TIME)${NC}"
            echo -e "┌─────┬────────────────────┬───────────────┬─────────────────────────┬────────────────────┐"
            echo -e "│ QM  │ Connection ID      │ Channel       │ Client Address          │ Application        │"
            echo -e "├─────┼────────────────────┼───────────────┼─────────────────────────┼────────────────────┤"
            
            for i in 1 2 3; do
                if [ "${connections[$i]}" -gt 0 ]; then
                    get_connection_details "QM$i" "$i" | while IFS= read -r line; do
                        if [ -n "$line" ]; then
                            printf "│ QM%d │ %s │\n" "$i" "$line"
                        fi
                    done
                fi
            done
            
            echo -e "└─────┴────────────────────┴───────────────┴─────────────────────────┴────────────────────┘"
            echo
        fi
        
        # ACTIVE SESSION DETAILS
        if [ "$total_sessions" -gt 0 ] && [ "$SHOW_DETAILS" = true ]; then
            echo -e "${BOLD}${CYAN}ACTIVE SESSIONS (REAL-TIME)${NC}"
            echo -e "┌─────┬────────────────────┬──────────┬─────────────────────────┬────────┐"
            echo -e "│ QM  │ Session/Channel    │ Status   │ Client Address          │  Msgs  │"
            echo -e "├─────┼────────────────────┼──────────┼─────────────────────────┼────────┤"
            
            for i in 1 2 3; do
                if [ "${sessions[$i]}" -gt 0 ]; then
                    get_session_details "QM$i" "$i" | while IFS= read -r line; do
                        if [ -n "$line" ]; then
                            printf "│ QM%d │ %s │\n" "$i" "$line"
                        fi
                    done
                fi
            done
            
            echo -e "└─────┴────────────────────┴──────────┴─────────────────────────┴────────┘"
            echo
        fi
        
        # STATISTICS SUMMARY
        echo -e "${BOLD}${CYAN}STATISTICS SUMMARY${NC}"
        
        # Calculate distribution quality
        local conn_quality=$(calc_distribution_quality "${connections[1]}" "${connections[2]}" "${connections[3]}")
        local sess_quality=$(calc_distribution_quality "${sessions[1]}" "${sessions[2]}" "${sessions[3]}")
        
        echo -e "┌──────────────────────┬────────────────────────────────────────────┐"
        printf "│ Connection Balance:  │ %-42s │\n" "$(
            case $conn_quality in
                PERFECT) echo -e "${GREEN}PERFECT DISTRIBUTION${NC}" ;;
                EXCELLENT) echo -e "${GREEN}EXCELLENT DISTRIBUTION${NC}" ;;
                GOOD) echo -e "${GREEN}GOOD DISTRIBUTION${NC}" ;;
                FAIR) echo -e "${YELLOW}FAIR DISTRIBUTION${NC}" ;;
                POOR) echo -e "${RED}POOR DISTRIBUTION${NC}" ;;
                NO_DATA) echo -e "${YELLOW}NO CONNECTIONS${NC}" ;;
            esac
        )"
        printf "│ Session Balance:     │ %-42s │\n" "$(
            case $sess_quality in
                PERFECT) echo -e "${GREEN}PERFECT DISTRIBUTION${NC}" ;;
                EXCELLENT) echo -e "${GREEN}EXCELLENT DISTRIBUTION${NC}" ;;
                GOOD) echo -e "${GREEN}GOOD DISTRIBUTION${NC}" ;;
                FAIR) echo -e "${YELLOW}FAIR DISTRIBUTION${NC}" ;;
                POOR) echo -e "${RED}POOR DISTRIBUTION${NC}" ;;
                NO_DATA) echo -e "${YELLOW}NO SESSIONS${NC}" ;;
            esac
        )"
        echo -e "├──────────────────────┼────────────────────────────────────────────┤"
        printf "│ Total Connections:   │ %-42d │\n" "$total_connections"
        printf "│ Total Sessions:      │ %-42d │\n" "$total_sessions"
        printf "│ Total Messages:      │ %-42d │\n" "$total_messages"
        printf "│ Avg Sessions/Conn:   │ %-42s │\n" "$avg_ratio"
        echo -e "└──────────────────────┴────────────────────────────────────────────┘"
        echo
        
        # TREND ANALYSIS (after 3 samples)
        if [ "$iteration" -ge 3 ]; then
            echo -e "${BOLD}${CYAN}TREND ANALYSIS (Last 3 Samples)${NC}"
            echo -e "┌─────────┬──────────────────────┬──────────────────────┐"
            echo -e "│ Queue   │ Connection Trend     │ Session Trend        │"
            echo -e "│ Manager │                      │                      │"
            echo -e "├─────────┼──────────────────────┼──────────────────────┤"
            
            for i in 1 2 3; do
                local conn_trend="STABLE"
                local sess_trend="STABLE"
                
                # Get last 3 samples for trend
                eval "local conn_hist=(\${hist_conn$i[@]})"
                eval "local sess_hist=(\${hist_sess$i[@]})"
                
                if [ "${#conn_hist[@]}" -ge 3 ]; then
                    local last=${conn_hist[-1]}
                    local prev=${conn_hist[-2]}
                    local prev2=${conn_hist[-3]}
                    
                    if [ "$last" -gt "$prev" ] && [ "$prev" -gt "$prev2" ]; then
                        conn_trend="${GREEN}↑ INCREASING${NC}"
                    elif [ "$last" -lt "$prev" ] && [ "$prev" -lt "$prev2" ]; then
                        conn_trend="${RED}↓ DECREASING${NC}"
                    else
                        conn_trend="${YELLOW}→ STABLE${NC}"
                    fi
                fi
                
                if [ "${#sess_hist[@]}" -ge 3 ]; then
                    local last=${sess_hist[-1]}
                    local prev=${sess_hist[-2]}
                    local prev2=${sess_hist[-3]}
                    
                    if [ "$last" -gt "$prev" ] && [ "$prev" -gt "$prev2" ]; then
                        sess_trend="${GREEN}↑ INCREASING${NC}"
                    elif [ "$last" -lt "$prev" ] && [ "$prev" -lt "$prev2" ]; then
                        sess_trend="${RED}↓ DECREASING${NC}"
                    else
                        sess_trend="${YELLOW}→ STABLE${NC}"
                    fi
                fi
                
                printf "│ QM%d     │ %-30b │ %-30b │\n" "$i" "$conn_trend" "$sess_trend"
            done
            
            echo -e "└─────────┴──────────────────────┴──────────────────────┘"
            echo
        fi
        
        echo -e "${YELLOW}Next refresh in ${REFRESH_INTERVAL} seconds... Press Ctrl+C to stop${NC}"
        
        sleep "$REFRESH_INTERVAL"
    done
}

# Trap Ctrl+C
trap 'echo -e "\n${GREEN}Monitoring stopped.${NC}"; exit 0' INT

# Start monitoring
echo -e "${BOLD}${GREEN}Starting Enhanced Real-Time MQ Uniform Cluster Monitor${NC}"
echo -e "${YELLOW}Monitoring REAL connections and sessions from MQ queue managers${NC}"
echo -e "${YELLOW}Refresh rate: Every ${REFRESH_INTERVAL} seconds${NC}"
echo
sleep 2

monitor_loop