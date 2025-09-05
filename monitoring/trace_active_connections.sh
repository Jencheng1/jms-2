#!/bin/bash

################################################################################
# Active Connection Tracer - Monitors EXISTING connections from JMS applications
# Does NOT create new connections - only observes what JMS apps have created
################################################################################

# Color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
MAGENTA='\033[0;35m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Queue managers
QUEUE_MANAGERS=("qm1" "qm2" "qm3")

# Output file for evidence
EVIDENCE_FILE="connection_trace_$(date +%Y%m%d_%H%M%S).log"

print_header() {
    echo -e "${CYAN}╔════════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${CYAN}║         ACTIVE CONNECTION TRACER - MONITORING MODE             ║${NC}"
    echo -e "${CYAN}║                                                                ║${NC}"
    echo -e "${CYAN}║  Monitoring EXISTING connections from JMS Producer/Consumer    ║${NC}"
    echo -e "${CYAN}║  NOT creating any new connections                              ║${NC}"
    echo -e "${CYAN}╚════════════════════════════════════════════════════════════════╝${NC}"
    echo ""
}

# Function to extract detailed connection info from MQSC
get_connection_details() {
    local qm=$1
    local qm_upper=${qm^^}
    local timestamp=$(date '+%Y-%m-%d %H:%M:%S.%3N')
    
    echo -e "\n${BLUE}═══ Queue Manager: ${qm_upper} at ${timestamp} ═══${NC}"
    
    # Get ALL connection attributes for correlation
    docker exec $qm bash -c "
        echo 'DIS CONN(*) WHERE(CHANNEL EQ APP.SVRCONN) ALL' | runmqsc $qm_upper 2>/dev/null
    " 2>/dev/null | awk -v qm="$qm_upper" '
        BEGIN {
            conn_id = ""
            channel = ""
            conname = ""
            appltag = ""
            appltype = ""
            userid = ""
            pid = ""
            tid = ""
            uowlogda = ""
            uowlogti = ""
            uowstda = ""
            uowstti = ""
            urtype = ""
            exturid = ""
        }
        /CONN\(/ {
            # Print previous connection if exists
            if (conn_id != "") {
                print "────────────────────────────────────────────────────"
                print "Connection ID:        " conn_id
                print "Queue Manager:        " qm
                print "Channel:              " channel
                print "Connection Name:      " conname
                print "Application Tag:      " appltag
                print "Application Type:     " appltype
                print "User ID:              " userid
                print "Process ID:           " pid
                print "Thread ID:            " tid
                print "UOW Log Date:         " uowlogda
                print "UOW Log Time:         " uowlogti
                print "UOW Start Date:       " uowstda
                print "UOW Start Time:       " uowstti
                print "UR Type:              " urtype
                print "External UR ID:       " exturid
                print ""
            }
            
            # Extract new connection ID
            match($0, /CONN\(([^)]+)\)/, arr)
            conn_id = arr[1]
            
            # Reset other fields
            channel = ""
            conname = ""
            appltag = ""
            appltype = ""
            userid = ""
            pid = ""
            tid = ""
            uowlogda = ""
            uowlogti = ""
            uowstda = ""
            uowstti = ""
            urtype = ""
            exturid = ""
        }
        /CHANNEL\(/ { match($0, /CHANNEL\(([^)]+)\)/, arr); channel = arr[1] }
        /CONNAME\(/ { match($0, /CONNAME\(([^)]+)\)/, arr); conname = arr[1] }
        /APPLTAG\(/ { match($0, /APPLTAG\(([^)]+)\)/, arr); appltag = arr[1] }
        /APPLTYPE\(/ { match($0, /APPLTYPE\(([^)]+)\)/, arr); appltype = arr[1] }
        /USERID\(/ { match($0, /USERID\(([^)]+)\)/, arr); userid = arr[1] }
        /PID\(/ { match($0, /PID\(([^)]+)\)/, arr); pid = arr[1] }
        /TID\(/ { match($0, /TID\(([^)]+)\)/, arr); tid = arr[1] }
        /UOWLOGDA\(/ { match($0, /UOWLOGDA\(([^)]+)\)/, arr); uowlogda = arr[1] }
        /UOWLOGTI\(/ { match($0, /UOWLOGTI\(([^)]+)\)/, arr); uowlogti = arr[1] }
        /UOWSTDA\(/ { match($0, /UOWSTDA\(([^)]+)\)/, arr); uowstda = arr[1] }
        /UOWSTTI\(/ { match($0, /UOWSTTI\(([^)]+)\)/, arr); uowstti = arr[1] }
        /URTYPE\(/ { match($0, /URTYPE\(([^)]+)\)/, arr); urtype = arr[1] }
        /EXTURID\(/ { match($0, /EXTURID\(([^)]+)\)/, arr); exturid = arr[1] }
        END {
            # Print last connection
            if (conn_id != "") {
                print "────────────────────────────────────────────────────"
                print "Connection ID:        " conn_id
                print "Queue Manager:        " qm
                print "Channel:              " channel
                print "Connection Name:      " conname
                print "Application Tag:      " appltag
                print "Application Type:     " appltype
                print "User ID:              " userid
                print "Process ID:           " pid
                print "Thread ID:            " tid
                print "UOW Log Date:         " uowlogda
                print "UOW Log Time:         " uowlogti
                print "UOW Start Date:       " uowstda
                print "UOW Start Time:       " uowstti
                print "UR Type:              " urtype
                print "External UR ID:       " exturid
                print ""
            }
        }
    '
}

# Function to analyze parent-child relationships
analyze_parent_child() {
    echo -e "\n${MAGENTA}════════════════════════════════════════════════════════════════${NC}"
    echo -e "${MAGENTA}           PARENT-CHILD CONNECTION ANALYSIS                      ${NC}"
    echo -e "${MAGENTA}════════════════════════════════════════════════════════════════${NC}"
    
    # Collect all connections into temporary file
    local temp_file="/tmp/conn_analysis_$$.txt"
    > "$temp_file"
    
    for qm in "${QUEUE_MANAGERS[@]}"; do
        local qm_upper=${qm^^}
        
        docker exec $qm bash -c "
            echo 'DIS CONN(*) WHERE(CHANNEL EQ APP.SVRCONN) ALL' | runmqsc $qm_upper 2>/dev/null
        " 2>/dev/null | awk -v qm="$qm_upper" '
            /CONN\(/ { 
                if (conn_id != "") {
                    printf "%s|%s|%s|%s|%s|%s|%s|%s\n", 
                        qm, conn_id, conname, appltag, pid, tid, userid, channel
                }
                match($0, /CONN\(([^)]+)\)/, arr); conn_id = arr[1]
            }
            /CONNAME\(/ { match($0, /CONNAME\(([^)]+)\)/, arr); conname = arr[1] }
            /APPLTAG\(/ { match($0, /APPLTAG\(([^)]+)\)/, arr); appltag = arr[1] }
            /PID\(/ { match($0, /PID\(([^)]+)\)/, arr); pid = arr[1] }
            /TID\(/ { match($0, /TID\(([^)]+)\)/, arr); tid = arr[1] }
            /USERID\(/ { match($0, /USERID\(([^)]+)\)/, arr); userid = arr[1] }
            /CHANNEL\(/ { match($0, /CHANNEL\(([^)]+)\)/, arr); channel = arr[1] }
            END {
                if (conn_id != "") {
                    printf "%s|%s|%s|%s|%s|%s|%s|%s\n", 
                        qm, conn_id, conname, appltag, pid, tid, userid, channel
                }
            }
        ' >> "$temp_file"
    done
    
    echo -e "\n${YELLOW}Grouping connections by IP address (parent-child detection):${NC}\n"
    
    # Group by CONNAME (IP address) to find parent-child relationships
    declare -A ip_groups
    while IFS='|' read -r qm conn_id conname appltag pid tid userid channel; do
        if [[ -n "$conname" ]]; then
            # Extract base IP from CONNAME
            base_ip=$(echo "$conname" | sed 's/(.*//')
            
            if [[ -n "$base_ip" ]]; then
                if [[ -n "${ip_groups[$base_ip]}" ]]; then
                    ip_groups[$base_ip]="${ip_groups[$base_ip]};$qm:$conn_id:$appltag"
                else
                    ip_groups[$base_ip]="$qm:$conn_id:$appltag"
                fi
            fi
        fi
    done < "$temp_file"
    
    # Analyze groups
    local group_num=1
    for ip in "${!ip_groups[@]}"; do
        connections="${ip_groups[$ip]}"
        IFS=';' read -ra conn_array <<< "$connections"
        
        if [[ ${#conn_array[@]} -gt 1 ]]; then
            echo -e "${YELLOW}Connection Group #$group_num (IP: $ip)${NC}"
            echo "  This IP has ${#conn_array[@]} connections (1 parent + $((${#conn_array[@]} - 1)) sessions)"
            
            # Check if all are on same QM
            declare -A qm_set
            for conn in "${conn_array[@]}"; do
                IFS=':' read -r qm conn_id appltag <<< "$conn"
                qm_set[$qm]=1
                echo -e "    ${CYAN}→${NC} Connection: $conn_id on $qm (Tag: $appltag)"
            done
            
            if [[ ${#qm_set[@]} -eq 1 ]]; then
                echo -e "  ${GREEN}✓ All connections from this IP on SAME Queue Manager!${NC}"
            else
                echo -e "  ${RED}✗ Connections from this IP on DIFFERENT Queue Managers${NC}"
            fi
            
            unset qm_set
            ((group_num++))
            echo ""
        fi
    done
    
    # Analyze by Application Tag patterns
    echo -e "\n${YELLOW}Grouping by Application Tag patterns:${NC}\n"
    
    declare -A tag_groups
    while IFS='|' read -r qm conn_id conname appltag pid tid userid channel; do
        if [[ -n "$appltag" && "$appltag" != " " ]]; then
            # Extract base tag (e.g., PRODUCER-1, CONSUMER-2)
            if [[ "$appltag" =~ ^(PRODUCER|CONSUMER|PROD|CONS|TRACER)[-]?([0-9]+) ]]; then
                base_tag="${BASH_REMATCH[1]}-${BASH_REMATCH[2]}"
                
                if [[ -n "${tag_groups[$base_tag]}" ]]; then
                    tag_groups[$base_tag]="${tag_groups[$base_tag]};$qm:$conn_id"
                else
                    tag_groups[$base_tag]="$qm:$conn_id"
                fi
            fi
        fi
    done < "$temp_file"
    
    for tag in "${!tag_groups[@]}"; do
        connections="${tag_groups[$tag]}"
        IFS=';' read -ra conn_array <<< "$connections"
        
        echo -e "${CYAN}Application: $tag${NC}"
        echo "  Connections: ${#conn_array[@]}"
        
        declare -A qm_count
        for conn in "${conn_array[@]}"; do
            IFS=':' read -r qm conn_id <<< "$conn"
            qm_count[$qm]=$((${qm_count[$qm]:-0} + 1))
            echo "    → $conn_id on $qm"
        done
        
        if [[ ${#qm_count[@]} -eq 1 ]]; then
            echo -e "  ${GREEN}✓ All connections for $tag on SAME Queue Manager${NC}"
        else
            echo -e "  ${YELLOW}ℹ Connections distributed across QMs:${NC}"
            for qm in "${!qm_count[@]}"; do
                echo "      $qm: ${qm_count[$qm]} connections"
            done
        fi
        
        unset qm_count
        echo ""
    done
    
    rm -f "$temp_file"
}

# Function to save evidence to file
save_evidence() {
    echo -e "\n${YELLOW}Saving evidence to: $EVIDENCE_FILE${NC}"
    
    {
        echo "════════════════════════════════════════════════════════════════"
        echo "     CONNECTION TRACE EVIDENCE - $(date '+%Y-%m-%d %H:%M:%S')"
        echo "════════════════════════════════════════════════════════════════"
        echo ""
        
        for qm in "${QUEUE_MANAGERS[@]}"; do
            get_connection_details "$qm"
        done
        
        echo ""
        analyze_parent_child
    } | tee "$EVIDENCE_FILE"
    
    echo -e "\n${GREEN}Evidence saved to: $EVIDENCE_FILE${NC}"
}

# Main monitoring function
monitor_connections() {
    print_header
    
    echo -e "${YELLOW}Starting connection monitoring...${NC}"
    echo -e "Press Ctrl+C to stop and save evidence\n"
    
    while true; do
        clear
        print_header
        
        echo -e "${YELLOW}Monitoring active connections from JMS applications...${NC}"
        echo -e "Timestamp: $(date '+%Y-%m-%d %H:%M:%S')\n"
        
        # Get connection details from all QMs
        for qm in "${QUEUE_MANAGERS[@]}"; do
            get_connection_details "$qm"
        done
        
        # Analyze parent-child relationships
        analyze_parent_child
        
        echo -e "\n${CYAN}Refreshing in 5 seconds... Press Ctrl+C to stop${NC}"
        sleep 5
    done
}

# Trap Ctrl+C to save evidence before exit
trap 'echo -e "\n${YELLOW}Stopping monitor and saving evidence...${NC}"; save_evidence; exit 0' INT

# Check if queue managers are running
check_qms() {
    echo -e "${YELLOW}Checking queue managers...${NC}"
    for qm in "${QUEUE_MANAGERS[@]}"; do
        if docker ps | grep -q "$qm"; then
            echo -e "  ${GREEN}✓${NC} $qm is running"
        else
            echo -e "  ${RED}✗${NC} $qm is not running"
            exit 1
        fi
    done
    echo ""
}

# Main execution
main() {
    check_qms
    
    if [[ "$1" == "--once" ]]; then
        # Run once and save evidence
        print_header
        
        for qm in "${QUEUE_MANAGERS[@]}"; do
            get_connection_details "$qm"
        done
        
        analyze_parent_child
        save_evidence
    else
        # Continuous monitoring
        monitor_connections
    fi
}

# Run main function
main "$@"