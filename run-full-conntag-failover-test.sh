#!/bin/bash

# Spring Boot Failover Test with Full CONNTAG Display
set -e

# Configuration
TEST_ID="SPR$(date +%s | tail -c 5)"
EVIDENCE_DIR="spring_full_conntag_evidence_$(date +%Y%m%d_%H%M%S)"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Create evidence directory
mkdir -p ${EVIDENCE_DIR}

echo -e "${BLUE}=================================================================================${NC}"
echo -e "${BLUE}        SPRING BOOT FAILOVER TEST WITH FULL CONNTAG (NO TRUNCATION)${NC}"
echo -e "${BLUE}=================================================================================${NC}"
echo "Evidence Directory: ${EVIDENCE_DIR}"
echo

# Function to capture MQSC with full CONNTAG
capture_mqsc_conntags() {
    local phase=$1
    local file="${EVIDENCE_DIR}/${phase}_mqsc_conntags.log"
    
    echo -e "${YELLOW}Capturing MQSC CONNTAGs: ${phase}${NC}"
    echo "=== MQSC CONNTAG Capture: ${phase} - $(date '+%Y-%m-%d %H:%M:%S.%3N') ===" > $file
    
    for qm in qm1 qm2 qm3; do
        if docker ps | grep -q $qm; then
            echo "" >> $file
            echo "=============== Queue Manager: ${qm^^} ===============" >> $file
            
            # Get connections with SPR APPTAG
            docker exec $qm bash -c "
                echo 'DIS CONN(*) WHERE(APPLTAG LK SPR*) ALL' | runmqsc ${qm^^} | 
                grep -E 'CONN\(|CONNTAG\(|APPLTAG\(|CHANNEL\(' 
            " >> $file 2>&1 || true
            
            # Extract just CONNTAGs
            echo "" >> $file
            echo "CONNTAGs on ${qm^^}:" >> $file
            docker exec $qm bash -c "
                echo 'DIS CONN(*) WHERE(APPLTAG LK SPR*) ALL' | runmqsc ${qm^^} | 
                grep 'CONNTAG(' | sed 's/.*CONNTAG(//' | sed 's/).*//'
            " >> $file 2>&1 || true
        fi
    done
    
    echo -e "${GREEN}✓ MQSC CONNTAGs captured: $file${NC}"
}

# Start test in background with output capture
echo -e "${BLUE}Starting Spring Boot Failover Test...${NC}"
docker run -d \
    --name spring-conntag-test \
    --network mq-uniform-cluster_mqnet \
    -v /home/ec2-user/unified/demo5/mq-uniform-cluster:/app \
    -v /home/ec2-user/unified/demo5/mq-uniform-cluster/libs:/libs \
    -v /home/ec2-user/unified/demo5/mq-uniform-cluster/mq/ccdt:/workspace/ccdt:ro \
    openjdk:17 \
    java -cp "/app:/libs/*" SpringBootFailoverFullConntagTest > /dev/null 2>&1

echo "Waiting 15 seconds for connections to establish..."
sleep 15

# Get initial output
docker logs spring-conntag-test > ${EVIDENCE_DIR}/test_initial.log 2>&1

# Capture before failover
capture_mqsc_conntags "before_failover"

# Find which QM has most connections
echo -e "${YELLOW}Finding Queue Manager with connections...${NC}"
TARGET_QM=""
MAX_CONNS=0
for qm in qm1 qm2 qm3; do
    count=$(docker exec $qm bash -c "echo 'DIS CONN(*) WHERE(APPLTAG LK SPR*) CHANNEL' | runmqsc ${qm^^}" 2>/dev/null | grep -c "CONN(" || echo "0")
    echo "  ${qm^^}: $count connections"
    if [ $count -gt $MAX_CONNS ]; then
        MAX_CONNS=$count
        TARGET_QM=$qm
    fi
done

if [ $MAX_CONNS -gt 0 ]; then
    echo -e "${RED}=================================================================================${NC}"
    echo -e "${RED}                 TRIGGERING FAILOVER BY STOPPING ${TARGET_QM^^}${NC}"
    echo -e "${RED}=================================================================================${NC}"
    
    # Stop the QM
    docker stop $TARGET_QM
    echo -e "${RED}${TARGET_QM} stopped at $(date '+%H:%M:%S')${NC}"
    
    echo "Waiting 20 seconds for failover..."
    sleep 20
    
    # Capture after failover
    capture_mqsc_conntags "after_failover"
    
    # Get logs after failover
    docker logs spring-conntag-test > ${EVIDENCE_DIR}/test_after_failover.log 2>&1
    
    # Wait for test to complete
    echo "Waiting for test to complete (40 more seconds)..."
    sleep 40
    
    # Restart stopped QM
    echo -e "${YELLOW}Restarting ${TARGET_QM}...${NC}"
    docker restart $TARGET_QM
else
    echo -e "${YELLOW}No connections found yet, waiting...${NC}"
    sleep 30
fi

# Get final output
docker logs spring-conntag-test > ${EVIDENCE_DIR}/test_final.log 2>&1

# Stop and remove test container
docker stop spring-conntag-test 2>/dev/null || true
docker rm spring-conntag-test 2>/dev/null || true

# Extract tables from log
echo -e "${BLUE}=================================================================================${NC}"
echo -e "${BLUE}                    EXTRACTING FULL CONNTAG TABLES${NC}"
echo -e "${BLUE}=================================================================================${NC}"

# Show before failover table
echo -e "${GREEN}=== BEFORE FAILOVER TABLE ===${NC}"
grep -A 15 "BEFORE FAILOVER - FULL CONNTAG TABLE" ${EVIDENCE_DIR}/test_final.log 2>/dev/null || echo "Before table not found"

echo
echo -e "${GREEN}=== AFTER FAILOVER TABLE ===${NC}"
grep -A 15 "AFTER FAILOVER - FULL CONNTAG TABLE" ${EVIDENCE_DIR}/test_final.log 2>/dev/null || echo "After table not found"

echo
echo -e "${GREEN}=== CONNTAG CHANGE SUMMARY ===${NC}"
grep -A 10 "CONNTAG CHANGE SUMMARY" ${EVIDENCE_DIR}/test_final.log 2>/dev/null || echo "Summary not found"

echo
echo -e "${GREEN}Test Complete! Full evidence in: ${EVIDENCE_DIR}${NC}"