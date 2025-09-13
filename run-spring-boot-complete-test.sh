#!/bin/bash

# Complete Spring Boot Failover Test with Evidence Collection
set -e

# Configuration
TEST_ID="SPRING-$(date +%s | tail -c 6)"
EVIDENCE_DIR="spring_complete_evidence_$(date +%Y%m%d_%H%M%S)"
NETWORK="mq-uniform-cluster_mqnet"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Create evidence directory
mkdir -p ${EVIDENCE_DIR}

echo -e "${BLUE}=================================================================================${NC}"
echo -e "${BLUE}          SPRING BOOT MQ FAILOVER COMPLETE TEST WITH EVIDENCE${NC}"
echo -e "${BLUE}=================================================================================${NC}"
echo "Test ID: ${TEST_ID}"
echo "Evidence Directory: ${EVIDENCE_DIR}"
echo

# Check QMs are running
echo -e "${YELLOW}Checking Queue Managers...${NC}"
for qm in qm1 qm2 qm3; do
    if docker ps | grep -q "$qm"; then
        echo -e "${GREEN}✓ $qm is running${NC}"
    else
        echo -e "${RED}✗ $qm is not running${NC}"
        exit 1
    fi
done

# Start tcpdump
echo -e "${YELLOW}Starting tcpdump...${NC}"
docker rm -f tcpdump-spring 2>/dev/null || true
docker run -d \
    --name tcpdump-spring \
    --network host \
    -v "$(pwd)/${EVIDENCE_DIR}:/capture" \
    nicolaka/netshoot \
    tcpdump -i any -w /capture/mq_traffic.pcap \
    'tcp port 1414 or tcp port 1415 or tcp port 1416' \
    -s 0 > /dev/null 2>&1
echo -e "${GREEN}✓ tcpdump started${NC}"

# Function to capture MQSC with full details
capture_mqsc_full() {
    local phase=$1
    local file="${EVIDENCE_DIR}/${phase}_mqsc_full.log"
    
    echo -e "${YELLOW}Capturing MQSC: ${phase}${NC}"
    echo "=== MQSC Full Capture: ${phase} - $(date '+%Y-%m-%d %H:%M:%S.%3N') ===" > $file
    
    for qm in qm1 qm2 qm3; do
        if docker ps | grep -q $qm; then
            echo "" >> $file
            echo "=============== Queue Manager: ${qm^^} ===============" >> $file
            
            # Get all connections with SPRING APPTAG
            docker exec $qm bash -c "
                echo 'DIS CONN(*) WHERE(APPLTAG LK SPRING*) ALL' | runmqsc ${qm^^}
            " >> $file 2>&1
            
            # Count connections and extract key info
            local conn_count=$(grep -c "CONN(" $file 2>/dev/null || echo "0")
            local conntags=$(grep "CONNTAG(" $file | sed 's/.*CONNTAG(//' | sed 's/).*//' | sort -u)
            
            echo "" >> $file
            echo "Summary for ${qm^^}:" >> $file
            echo "  Total connections: $conn_count" >> $file
            echo "  Unique CONNTAGs:" >> $file
            for tag in $conntags; do
                echo "    - $tag" >> $file
            done
        fi
    done
    
    echo -e "${GREEN}✓ MQSC captured: $file${NC}"
}

# Initial MQSC capture
capture_mqsc_full "initial"

# Run the Java test in background
echo -e "${BLUE}Starting Spring Boot Failover Test Application...${NC}"
docker run -d \
    --name spring-failover-test \
    --network ${NETWORK} \
    -v "$(pwd):/app" \
    -v "$(pwd)/libs:/libs" \
    -v "$(pwd)/mq/ccdt:/workspace/ccdt:ro" \
    -v "$(pwd)/${EVIDENCE_DIR}:/evidence" \
    openjdk:17 \
    sh -c "cd /app && java -cp '.:/libs/*' \
        -Dcom.ibm.msg.client.commonservices.trace.status=ON \
        -Dcom.ibm.msg.client.commonservices.trace.outputName=/evidence/jms_trace.log \
        SpringBootFailoverTableTest" > ${EVIDENCE_DIR}/docker_start.log 2>&1

echo "Waiting 20 seconds for connections to establish..."
sleep 20

# Get application output so far
docker logs spring-failover-test > ${EVIDENCE_DIR}/app_initial.log 2>&1

# Capture pre-failover MQSC
capture_mqsc_full "pre_failover"

# Analyze which QM to stop
echo -e "${YELLOW}Analyzing connection distribution...${NC}"
declare -A qm_counts
for qm in qm1 qm2 qm3; do
    count=$(docker exec $qm bash -c "echo 'DIS CONN(*) WHERE(APPLTAG LK SPRING*) CHANNEL' | runmqsc ${qm^^}" 2>/dev/null | grep -c "CONN(" || echo "0")
    qm_counts[$qm]=$count
    echo "  ${qm^^}: $count connections"
done

# Find QM with most connections
TARGET_QM=""
MAX_CONNS=0
for qm in qm1 qm2 qm3; do
    if [ ${qm_counts[$qm]} -gt $MAX_CONNS ]; then
        MAX_CONNS=${qm_counts[$qm]}
        TARGET_QM=$qm
    fi
done

if [ $MAX_CONNS -eq 0 ]; then
    echo -e "${RED}No connections found! Check the test output.${NC}"
    docker logs spring-failover-test
    exit 1
fi

# Capture CONNTAG before failover
echo -e "${YELLOW}Capturing CONNTAG from ${TARGET_QM} before failover...${NC}"
docker exec $TARGET_QM bash -c "
    echo 'DIS CONN(*) WHERE(APPLTAG LK SPRING*) ALL' | runmqsc ${TARGET_QM^^} | 
    grep -E 'CONNTAG\(' | head -5
" > ${EVIDENCE_DIR}/conntag_before_failover.txt

echo -e "${RED}=================================================================================${NC}"
echo -e "${RED}                    TRIGGERING FAILOVER BY STOPPING ${TARGET_QM^^}${NC}"
echo -e "${RED}=================================================================================${NC}"
echo "Stopping ${TARGET_QM} which has $MAX_CONNS connections..."

# Stop the QM
docker stop $TARGET_QM
FAILOVER_TIME=$(date '+%H:%M:%S')
echo -e "${RED}${TARGET_QM} stopped at ${FAILOVER_TIME}${NC}"

echo "Waiting 30 seconds for failover to complete..."
sleep 30

# Capture post-failover state
docker logs spring-failover-test > ${EVIDENCE_DIR}/app_post_failover.log 2>&1
capture_mqsc_full "post_failover"

# Find where connections moved
echo -e "${YELLOW}Finding new Queue Manager...${NC}"
NEW_QM=""
for qm in qm1 qm2 qm3; do
    if [ "$qm" != "$TARGET_QM" ] && docker ps | grep -q "$qm"; then
        count=$(docker exec $qm bash -c "echo 'DIS CONN(*) WHERE(APPLTAG LK SPRING*) CHANNEL' | runmqsc ${qm^^}" 2>/dev/null | grep -c "CONN(" || echo "0")
        if [ $count -gt 0 ]; then
            NEW_QM=$qm
            echo -e "${GREEN}Connections moved to ${qm^^}: $count connections${NC}"
            
            # Capture new CONNTAG
            docker exec $qm bash -c "
                echo 'DIS CONN(*) WHERE(APPLTAG LK SPRING*) ALL' | runmqsc ${qm^^} | 
                grep -E 'CONNTAG\(' | head -5
            " > ${EVIDENCE_DIR}/conntag_after_failover.txt
            break
        fi
    fi
done

# Wait for test to complete
echo "Waiting for test to complete (60 more seconds)..."
sleep 60

# Get final application output
docker logs spring-failover-test > ${EVIDENCE_DIR}/app_final.log 2>&1

# Capture final MQSC state
capture_mqsc_full "final"

# Stop and remove test container
docker stop spring-failover-test 2>/dev/null || true
docker rm spring-failover-test 2>/dev/null || true

# Stop tcpdump
echo -e "${YELLOW}Stopping tcpdump...${NC}"
docker stop tcpdump-spring 2>/dev/null || true
docker rm tcpdump-spring 2>/dev/null || true
echo -e "${GREEN}✓ tcpdump stopped${NC}"

# Restart stopped QM
echo -e "${YELLOW}Restarting ${TARGET_QM}...${NC}"
docker-compose -f docker-compose-simple.yml up -d $TARGET_QM
sleep 5

# Extract key information from logs
echo -e "${BLUE}=================================================================================${NC}"
echo -e "${BLUE}                           EXTRACTING KEY EVIDENCE${NC}"
echo -e "${BLUE}=================================================================================${NC}"

# Extract tables from application log
echo -e "${YELLOW}Extracting connection tables from application log...${NC}"
grep -A 20 "BEFORE FAILOVER - CONNECTION TABLE" ${EVIDENCE_DIR}/app_final.log > ${EVIDENCE_DIR}/table_before.txt 2>/dev/null || echo "Before table not found"
grep -A 20 "AFTER FAILOVER - CONNECTION TABLE" ${EVIDENCE_DIR}/app_final.log > ${EVIDENCE_DIR}/table_after.txt 2>/dev/null || echo "After table not found"

# Analyze CONNTAG changes
echo -e "${YELLOW}Analyzing CONNTAG changes...${NC}"
if [ -f ${EVIDENCE_DIR}/conntag_before_failover.txt ] && [ -f ${EVIDENCE_DIR}/conntag_after_failover.txt ]; then
    echo "CONNTAG Before Failover:"
    cat ${EVIDENCE_DIR}/conntag_before_failover.txt
    echo
    echo "CONNTAG After Failover:"
    cat ${EVIDENCE_DIR}/conntag_after_failover.txt
    
    # Check if they're different
    BEFORE=$(cat ${EVIDENCE_DIR}/conntag_before_failover.txt | head -1 | grep -oP "CONNTAG\(\K[^)]+")
    AFTER=$(cat ${EVIDENCE_DIR}/conntag_after_failover.txt | head -1 | grep -oP "CONNTAG\(\K[^)]+")
    
    if [ "$BEFORE" != "$AFTER" ]; then
        echo -e "${GREEN}✓ CONNTAG changed after failover (expected)${NC}"
        echo "  Before: $BEFORE"
        echo "  After:  $AFTER"
    fi
fi

# Summary
echo -e "${BLUE}=================================================================================${NC}"
echo -e "${BLUE}                              TEST SUMMARY${NC}"
echo -e "${BLUE}=================================================================================${NC}"

echo "Evidence collected in: ${EVIDENCE_DIR}"
echo
echo "Key files:"
echo "  Application Output:"
echo "    - app_final.log: Complete test output with tables"
echo "    - table_before.txt: Connection table before failover"
echo "    - table_after.txt: Connection table after failover"
echo
echo "  MQSC Evidence:"
echo "    - pre_failover_mqsc_full.log: MQSC before failover"
echo "    - post_failover_mqsc_full.log: MQSC after failover"
echo "    - conntag_before_failover.txt: CONNTAGs before"
echo "    - conntag_after_failover.txt: CONNTAGs after"
echo
echo "  Network Evidence:"
echo "    - mq_traffic.pcap: tcpdump capture"
echo
echo "  JMS Trace:"
echo "    - jms_trace.log: IBM MQ JMS trace"
echo
echo "Failover Details:"
echo "  - Stopped QM: ${TARGET_QM^^} at ${FAILOVER_TIME}"
echo "  - Connections moved to: ${NEW_QM^^}"
echo "  - Original connections: $MAX_CONNS"
echo

# Display the tables if found
if [ -f ${EVIDENCE_DIR}/table_before.txt ]; then
    echo -e "${GREEN}=== BEFORE FAILOVER TABLE (from application) ===${NC}"
    cat ${EVIDENCE_DIR}/table_before.txt
fi

if [ -f ${EVIDENCE_DIR}/table_after.txt ]; then
    echo -e "${GREEN}=== AFTER FAILOVER TABLE (from application) ===${NC}"
    cat ${EVIDENCE_DIR}/table_after.txt
fi

echo -e "${GREEN}Test Complete!${NC}"