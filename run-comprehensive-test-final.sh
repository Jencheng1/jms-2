#!/bin/bash

# Final Comprehensive Spring Boot Failover Test
# Collects JMS debug trace, MQSC evidence, and tcpdump

set -e

# Configuration
TEST_ID="SPRING-FINAL-$(date +%s)"
EVIDENCE_DIR="spring_final_evidence_$(date +%Y%m%d_%H%M%S)"
NETWORK="mq-uniform-cluster_mqnet"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Create evidence directory
mkdir -p ${EVIDENCE_DIR}

echo -e "${BLUE}=== Comprehensive Spring Boot Failover Test ===${NC}"
echo "Test ID: ${TEST_ID}"
echo "Evidence Directory: ${EVIDENCE_DIR}"
echo

# Function to start tcpdump
start_tcpdump() {
    echo -e "${YELLOW}Starting tcpdump...${NC}"
    docker rm -f tcpdump-test 2>/dev/null || true
    
    docker run -d \
        --name tcpdump-test \
        --network host \
        -v "$(pwd)/${EVIDENCE_DIR}:/capture" \
        nicolaka/netshoot \
        tcpdump -i any -w /capture/mq_traffic.pcap \
        'tcp port 1414 or tcp port 1415 or tcp port 1416' \
        -s 0
    
    echo -e "${GREEN}✓ tcpdump started${NC}"
}

# Function to stop tcpdump
stop_tcpdump() {
    echo -e "${YELLOW}Stopping tcpdump...${NC}"
    docker stop tcpdump-test 2>/dev/null || true
    docker rm tcpdump-test 2>/dev/null || true
    echo -e "${GREEN}✓ tcpdump stopped${NC}"
}

# Function to capture MQSC
capture_mqsc() {
    local label=$1
    local file="${EVIDENCE_DIR}/${label}_mqsc.log"
    
    echo -e "${YELLOW}Capturing MQSC: ${label}${NC}"
    echo "=== MQSC Capture: ${label} - $(date '+%Y-%m-%d %H:%M:%S.%3N') ===" > $file
    
    for qm in qm1 qm2 qm3; do
        if docker ps | grep -q $qm; then
            echo "" >> $file
            echo "=== Queue Manager: ${qm^^} ===" >> $file
            
            # Get all connections with APPTAG containing SPRING
            docker exec $qm bash -c "
                echo 'DIS CONN(*) WHERE(APPLTAG LK SPRING*) ALL' | runmqsc ${qm^^}
            " >> $file 2>&1
            
            # Count connections
            local count=$(grep -c "CONN(" $file 2>/dev/null || echo "0")
            echo "Connections on ${qm^^}: $count" >> $file
        fi
    done
    
    echo -e "${GREEN}✓ MQSC captured: $file${NC}"
}

# Start tcpdump
start_tcpdump

# Capture initial MQSC state
capture_mqsc "initial"

echo -e "${BLUE}=== Starting Spring Boot Test Application ===${NC}"

# Run the test in background
docker run -d \
    --name spring-test \
    --network ${NETWORK} \
    -v "$(pwd):/app" \
    -v "$(pwd)/libs:/libs" \
    -v "$(pwd)/mq/ccdt:/workspace/ccdt:ro" \
    -v "$(pwd)/${EVIDENCE_DIR}:/evidence" \
    openjdk:17 \
    sh -c "cd /app && java -cp '.:/libs/*' \
        -Dcom.ibm.msg.client.commonservices.trace.status=ON \
        -Dcom.ibm.msg.client.commonservices.trace.outputName=/evidence/jms_trace.log \
        SpringBootFailoverTestSimple" > ${EVIDENCE_DIR}/docker_run.log 2>&1

echo "Test started, waiting 30 seconds for connections..."
sleep 30

# Capture pre-failover state
capture_mqsc "pre_failover"
docker logs spring-test > ${EVIDENCE_DIR}/app_pre_failover.log 2>&1

# Find QM with most connections
TARGET_QM=""
MAX_CONNS=0
for qm in qm1 qm2 qm3; do
    count=$(docker exec $qm bash -c "echo 'DIS CONN(*) WHERE(APPLTAG LK SPRING*) CHANNEL' | runmqsc ${qm^^}" 2>/dev/null | grep -c "CONN(" || echo "0")
    echo "QM ${qm^^}: $count connections"
    if [ $count -gt $MAX_CONNS ]; then
        MAX_CONNS=$count
        TARGET_QM=$qm
    fi
done

if [ $MAX_CONNS -gt 0 ]; then
    echo -e "${YELLOW}Stopping ${TARGET_QM} (has $MAX_CONNS connections)...${NC}"
    
    # Get CONNTAG before failover
    docker exec $TARGET_QM bash -c "
        echo 'DIS CONN(*) WHERE(APPLTAG LK SPRING*) ALL' | runmqsc ${TARGET_QM^^} | 
        grep 'CONNTAG(' | head -1
    " > ${EVIDENCE_DIR}/conntag_before.txt
    
    # Stop QM to trigger failover
    docker stop $TARGET_QM
    echo -e "${RED}${TARGET_QM} stopped at $(date '+%H:%M:%S')${NC}"
    
    echo "Waiting 30 seconds for failover..."
    sleep 30
    
    # Capture post-failover state
    capture_mqsc "post_failover"
    docker logs spring-test > ${EVIDENCE_DIR}/app_post_failover.log 2>&1
    
    # Find new QM
    for qm in qm1 qm2 qm3; do
        if [ "$qm" != "$TARGET_QM" ] && docker ps | grep -q "$qm"; then
            count=$(docker exec $qm bash -c "echo 'DIS CONN(*) WHERE(APPLTAG LK SPRING*) CHANNEL' | runmqsc ${qm^^}" 2>/dev/null | grep -c "CONN(" || echo "0")
            if [ $count -gt 0 ]; then
                echo -e "${GREEN}Connections moved to ${qm^^}: $count${NC}"
                
                # Get CONNTAG after failover
                docker exec $qm bash -c "
                    echo 'DIS CONN(*) WHERE(APPLTAG LK SPRING*) ALL' | runmqsc ${qm^^} | 
                    grep 'CONNTAG(' | head -1
                " > ${EVIDENCE_DIR}/conntag_after.txt
                break
            fi
        fi
    done
    
    # Wait for test to complete
    echo "Waiting for test completion..."
    sleep 60
    
    # Restart stopped QM
    echo -e "${YELLOW}Restarting ${TARGET_QM}...${NC}"
    docker-compose -f docker-compose-simple.yml up -d $TARGET_QM
else
    echo -e "${RED}No connections found!${NC}"
fi

# Get final logs
docker logs spring-test > ${EVIDENCE_DIR}/app_final.log 2>&1

# Capture final MQSC state
capture_mqsc "final"

# Stop test
docker stop spring-test 2>/dev/null || true
docker rm spring-test 2>/dev/null || true

# Stop tcpdump
stop_tcpdump

# Analyze CONNTAG change
echo -e "${BLUE}=== Analyzing CONNTAG Change ===${NC}"
if [ -f ${EVIDENCE_DIR}/conntag_before.txt ] && [ -f ${EVIDENCE_DIR}/conntag_after.txt ]; then
    BEFORE=$(cat ${EVIDENCE_DIR}/conntag_before.txt | grep -oP "CONNTAG\(\K[^)]+")
    AFTER=$(cat ${EVIDENCE_DIR}/conntag_after.txt | grep -oP "CONNTAG\(\K[^)]+")
    
    echo "CONNTAG before failover: $BEFORE"
    echo "CONNTAG after failover:  $AFTER"
    
    if [ "$BEFORE" != "$AFTER" ]; then
        echo -e "${GREEN}✓ CONNTAG changed (expected)${NC}"
    fi
fi

echo -e "${GREEN}=== Test Complete ===${NC}"
echo "Evidence collected in: ${EVIDENCE_DIR}"
echo
ls -la ${EVIDENCE_DIR}/