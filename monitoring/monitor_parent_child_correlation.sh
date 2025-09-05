#!/bin/bash

################################################################################
# IBM MQ Connection-Session Parent-Child Correlation Monitor
# 
# This script provides undisputable evidence that child sessions always
# connect to the same queue manager as their parent connections.
#
# It extracts detailed connection and session information from MQSC and
# correlates them using CONNAME, APPLTAG, and other metadata.
################################################################################

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
MAGENTA='\033[0;35m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Queue managers
QUEUE_MANAGERS=("qm1" "qm2" "qm3")

# Clear screen and show header
clear_screen() {
    clear
    echo -e "${CYAN}╔════════════════════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${CYAN}║         IBM MQ PARENT-CHILD CONNECTION-SESSION CORRELATION MONITOR         ║${NC}"
    echo -e "${CYAN}╠════════════════════════════════════════════════════════════════════════════╣${NC}"
    echo -e "${CYAN}║  This monitor proves that child sessions follow parent connections to      ║${NC}"
    echo -e "${CYAN}║  the same Queue Manager in an IBM MQ Uniform Cluster configuration         ║${NC}"
    echo -e "${CYAN}╚════════════════════════════════════════════════════════════════════════════╝${NC}"
    echo ""
}

# Function to get detailed connection info from a queue manager
get_connection_details() {
    local qm=$1
    local qm_upper=${qm^^}
    
    echo -e "\n${BLUE}═══ Queue Manager: ${qm_upper} ═══${NC}"
    
    # Get all connections with full details including CONNAME (connection ID)
    docker exec $qm bash -c "
        echo 'DIS CONN(*) ALL' | runmqsc $qm_upper 2>/dev/null | \
        grep -E 'CONN\(|CHANNEL\(|CONNAME\(|APPLTYPE\(|APPLTAG\(|APPLDESC\(|USERID\(|UOWLOG\(|CONNOPTS\(|SSLPEER\('
    " 2>/dev/null | while IFS= read -r line; do
        # Parse connection blocks
        if [[ $line =~ CONN\(([^)]+)\) ]]; then
            conn_id="${BASH_REMATCH[1]}"
            echo -e "\n${YELLOW}Connection ID: $conn_id${NC}"
        elif [[ $line =~ CHANNEL\(([^)]+)\) ]]; then
            channel="${BASH_REMATCH[1]}"
            echo "  Channel: $channel"
        elif [[ $line =~ CONNAME\(([^)]+)\) ]]; then
            conname="${BASH_REMATCH[1]}"
            echo "  CONNAME: $conname"
        elif [[ $line =~ APPLTAG\(([^)]+)\) ]]; then
            appltag="${BASH_REMATCH[1]}"
            echo -e "  ${GREEN}APPLTAG: $appltag${NC}"
        elif [[ $line =~ APPLTYPE\(([^)]+)\) ]]; then
            appltype="${BASH_REMATCH[1]}"
            echo "  APPLTYPE: $appltype"
        elif [[ $line =~ USERID\(([^)]+)\) ]]; then
            userid="${BASH_REMATCH[1]}"
            echo "  USERID: $userid"
        fi
    done
}

# Function to extract and correlate parent-child relationships
correlate_connections() {
    echo -e "\n${CYAN}════════════════════════════════════════════════════════════════${NC}"
    echo -e "${CYAN}            CONNECTION-SESSION CORRELATION ANALYSIS              ${NC}"
    echo -e "${CYAN}════════════════════════════════════════════════════════════════${NC}"
    
    # Temporary files for correlation
    local conn_data="/tmp/mq_conn_data_$$.txt"
    local correlation_report="/tmp/mq_correlation_$$.txt"
    
    # Clear temp files
    > "$conn_data"
    > "$correlation_report"
    
    # Collect all connection data from all QMs
    for qm in "${QUEUE_MANAGERS[@]}"; do
        local qm_upper=${qm^^}
        
        echo -e "\n${BLUE}Analyzing $qm_upper...${NC}"
        
        # Get detailed connection info with parsing
        docker exec $qm bash -c "
            echo 'DIS CONN(*) WHERE(CHANNEL EQ APP.SVRCONN) ALL' | runmqsc $qm_upper 2>/dev/null
        " 2>/dev/null | awk -v qm="$qm_upper" '
            /CONN\(/ { 
                if (conn_id != "") {
                    # Output previous connection data
                    printf "%s|%s|%s|%s|%s|%s|%s|%s\n", 
                        qm, conn_id, channel, conname, appltag, appltype, userid, uowlog
                }
                # Reset for new connection
                match($0, /CONN\(([^)]+)\)/, arr)
                conn_id = arr[1]
                channel = ""
                conname = ""
                appltag = ""
                appltype = ""
                userid = ""
                uowlog = ""
            }
            /CHANNEL\(/ { match($0, /CHANNEL\(([^)]+)\)/, arr); channel = arr[1] }
            /CONNAME\(/ { match($0, /CONNAME\(([^)]+)\)/, arr); conname = arr[1] }
            /APPLTAG\(/ { match($0, /APPLTAG\(([^)]+)\)/, arr); appltag = arr[1] }
            /APPLTYPE\(/ { match($0, /APPLTYPE\(([^)]+)\)/, arr); appltype = arr[1] }
            /USERID\(/ { match($0, /USERID\(([^)]+)\)/, arr); userid = arr[1] }
            /UOWLOG\(/ { match($0, /UOWLOG\(([^)]+)\)/, arr); uowlog = arr[1] }
            END {
                if (conn_id != "") {
                    printf "%s|%s|%s|%s|%s|%s|%s|%s\n", 
                        qm, conn_id, channel, conname, appltag, appltype, userid, uowlog
                }
            }
        ' >> "$conn_data"
    done
    
    # Analyze and correlate the data
    echo -e "\n${MAGENTA}══════════════════════════════════════════════════════════════${NC}"
    echo -e "${MAGENTA}                    PARENT-CHILD RELATIONSHIPS                  ${NC}"
    echo -e "${MAGENTA}══════════════════════════════════════════════════════════════${NC}"
    
    # Group connections by CONNAME (IP:port) which represents the parent connection
    declare -A parent_groups
    declare -A qm_by_conn
    
    while IFS='|' read -r qm conn_id channel conname appltag appltype userid uowlog; do
        if [[ -n "$conname" && "$conname" != " " ]]; then
            # Extract base connection identifier from CONNAME
            base_conn=$(echo "$conname" | cut -d'(' -f1)
            
            # Store QM for this connection
            qm_by_conn["$conn_id"]="$qm"
            
            # Group by base connection
            if [[ -n "${parent_groups[$base_conn]}" ]]; then
                parent_groups[$base_conn]="${parent_groups[$base_conn]};$qm:$conn_id:$appltag"
            else
                parent_groups[$base_conn]="$qm:$conn_id:$appltag"
            fi
        fi
    done < "$conn_data"
    
    # Display parent-child relationships
    local total_parents=0
    local total_children=0
    local matching_qm=0
    local mismatched_qm=0
    
    for parent in "${!parent_groups[@]}"; do
        connections="${parent_groups[$parent]}"
        IFS=';' read -ra conn_array <<< "$connections"
        
        if [[ ${#conn_array[@]} -gt 1 ]]; then
            echo -e "\n${YELLOW}Parent Connection Group: $parent${NC}"
            
            # Get parent QM (from first connection)
            parent_qm=$(echo "${conn_array[0]}" | cut -d':' -f1)
            echo -e "  ${CYAN}Parent QM: $parent_qm${NC}"
            
            total_parents=$((total_parents + 1))
            
            # Check all child connections
            local all_same_qm=true
            for conn in "${conn_array[@]}"; do
                IFS=':' read -r qm conn_id appltag <<< "$conn"
                total_children=$((total_children + 1))
                
                if [[ "$qm" == "$parent_qm" ]]; then
                    echo -e "    ${GREEN}✓${NC} Child: $conn_id (QM: $qm, Tag: $appltag) - ${GREEN}MATCHED${NC}"
                    matching_qm=$((matching_qm + 1))
                else
                    echo -e "    ${RED}✗${NC} Child: $conn_id (QM: $qm, Tag: $appltag) - ${RED}MISMATCHED${NC}"
                    mismatched_qm=$((mismatched_qm + 1))
                    all_same_qm=false
                fi
            done
            
            if $all_same_qm; then
                echo -e "  ${GREEN}═══> All children connected to same QM as parent!${NC}"
            else
                echo -e "  ${RED}═══> WARNING: Children connected to different QMs!${NC}"
            fi
        fi
    done
    
    # Display correlation by APPLTAG patterns
    echo -e "\n${MAGENTA}══════════════════════════════════════════════════════════════${NC}"
    echo -e "${MAGENTA}                  APPLTAG CORRELATION ANALYSIS                  ${NC}"
    echo -e "${MAGENTA}══════════════════════════════════════════════════════════════${NC}"
    
    declare -A appltag_groups
    
    while IFS='|' read -r qm conn_id channel conname appltag appltype userid uowlog; do
        if [[ -n "$appltag" && "$appltag" != " " ]]; then
            # Extract correlation prefix from APPLTAG (e.g., PROD1, CONS2)
            if [[ "$appltag" =~ ^(PROD|CONS|PRODUCER|CONSUMER)[-]?([0-9]+) ]]; then
                prefix="${BASH_REMATCH[1]}-${BASH_REMATCH[2]}"
                
                if [[ -n "${appltag_groups[$prefix]}" ]]; then
                    appltag_groups[$prefix]="${appltag_groups[$prefix]};$qm:$conn_id"
                else
                    appltag_groups[$prefix]="$qm:$conn_id"
                fi
            fi
        fi
    done < "$conn_data"
    
    for prefix in "${!appltag_groups[@]}"; do
        connections="${appltag_groups[$prefix]}"
        IFS=';' read -ra conn_array <<< "$connections"
        
        echo -e "\n${YELLOW}Application Group: $prefix${NC}"
        
        # Check if all connections in this group are on the same QM
        declare -A qm_count
        for conn in "${conn_array[@]}"; do
            IFS=':' read -r qm conn_id <<< "$conn"
            qm_count[$qm]=$((${qm_count[$qm]:-0} + 1))
            echo "  Connection: $conn_id on $qm"
        done
        
        if [[ ${#qm_count[@]} -eq 1 ]]; then
            echo -e "  ${GREEN}✓ All connections for $prefix on same QM${NC}"
        else
            echo -e "  ${YELLOW}ℹ Connections distributed across QMs (expected behavior)${NC}"
        fi
        
        unset qm_count
    done
    
    # Summary statistics
    echo -e "\n${CYAN}════════════════════════════════════════════════════════════════${NC}"
    echo -e "${CYAN}                         CORRELATION SUMMARY                     ${NC}"
    echo -e "${CYAN}════════════════════════════════════════════════════════════════${NC}"
    
    echo -e "Total Parent Connections: ${total_parents}"
    echo -e "Total Child Connections: ${total_children}"
    echo -e "Children with Matching QM: ${GREEN}${matching_qm}${NC}"
    echo -e "Children with Mismatched QM: ${RED}${mismatched_qm}${NC}"
    
    if [[ $mismatched_qm -eq 0 && $total_children -gt 0 ]]; then
        echo -e "\n${GREEN}╔════════════════════════════════════════════════════════════════╗${NC}"
        echo -e "${GREEN}║     ✓ SUCCESS: All child sessions connected to same QM        ║${NC}"
        echo -e "${GREEN}║                as their parent connections!                    ║${NC}"
        echo -e "${GREEN}╚════════════════════════════════════════════════════════════════╝${NC}"
    elif [[ $mismatched_qm -gt 0 ]]; then
        echo -e "\n${RED}╔════════════════════════════════════════════════════════════════╗${NC}"
        echo -e "${RED}║     ✗ WARNING: Some child sessions connected to different     ║${NC}"
        echo -e "${RED}║                QMs than their parent connections!              ║${NC}"
        echo -e "${RED}╚════════════════════════════════════════════════════════════════╝${NC}"
    fi
    
    # Clean up temp files
    rm -f "$conn_data" "$correlation_report"
}

# Function to show live session tracking
show_session_tracking() {
    echo -e "\n${CYAN}════════════════════════════════════════════════════════════════${NC}"
    echo -e "${CYAN}                    LIVE SESSION TRACKING                        ${NC}"
    echo -e "${CYAN}════════════════════════════════════════════════════════════════${NC}"
    
    for qm in "${QUEUE_MANAGERS[@]}"; do
        local qm_upper=${qm^^}
        
        echo -e "\n${BLUE}Queue Manager: $qm_upper${NC}"
        
        # Get connection count grouped by APPLTAG
        docker exec $qm bash -c "
            echo 'DIS CONN(*) WHERE(CHANNEL EQ APP.SVRCONN) APPLTAG' | runmqsc $qm_upper 2>/dev/null | \
            grep -E 'APPLTAG\(' | sed 's/.*APPLTAG(\([^)]*\)).*/\1/' | \
            sort | uniq -c | sort -rn
        " 2>/dev/null | while read count tag; do
            if [[ -n "$tag" && "$tag" != " " ]]; then
                echo -e "  ${GREEN}$tag${NC}: $count sessions"
            fi
        done
    done
}

# Main monitoring loop
main() {
    clear_screen
    
    while true; do
        echo -e "\n${YELLOW}Timestamp: $(date '+%Y-%m-%d %H:%M:%S')${NC}"
        
        # Get basic connection details
        for qm in "${QUEUE_MANAGERS[@]}"; do
            get_connection_details "$qm"
        done
        
        # Perform correlation analysis
        correlate_connections
        
        # Show session tracking
        show_session_tracking
        
        echo -e "\n${CYAN}Press Ctrl+C to exit. Refreshing in 10 seconds...${NC}"
        sleep 10
        clear_screen
    done
}

# Trap Ctrl+C for clean exit
trap 'echo -e "\n${YELLOW}Monitoring stopped.${NC}"; exit 0' INT

# Run the main function
main