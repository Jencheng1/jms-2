#!/bin/bash

# Quick test of JMS with CCDT - 60 second version for verification

export PATH=$PATH:/usr/local/bin:/bin:/usr/bin

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'
BOLD='\033[1m'

echo -e "${BOLD}${BLUE}═══════════════════════════════════════════════════════${NC}"
echo -e "${BOLD}${BLUE}    QUICK JMS CCDT TEST (60 seconds)${NC}"
echo -e "${BOLD}${BLUE}═══════════════════════════════════════════════════════${NC}"
echo "Start Time: $(date)"
echo ""

# Configuration
TEST_DURATION=60
MESSAGES_PER_PRODUCER=20
SESSIONS_PER_CONNECTION=2

# Create results directory
RESULTS_DIR="quick_test_$(date +%Y%m%d_%H%M%S)"
mkdir -p "$RESULTS_DIR"

# Function to get metrics
get_metrics() {
    local label=$1
    echo ""
    echo -e "${CYAN}═══ $label ═══${NC}"
    
    for i in 1 2 3; do
        local conns=$(docker exec qm$i bash -c "
            echo 'DIS CONN(*) WHERE(CHANNEL NE SYSTEM.*)' | 
            runmqsc QM$i 2>/dev/null | grep -c 'CONN(' || echo 0
        " 2>/dev/null || echo 0)
        
        local sessions=$(docker exec qm$i bash -c "
            echo 'DIS CHSTATUS(*) WHERE(CHANNEL NE SYSTEM.*)' | 
            runmqsc QM$i 2>/dev/null | grep -c 'CHSTATUS(' || echo 0
        " 2>/dev/null || echo 0)
        
        local depth=$(docker exec qm$i bash -c "
            echo 'DIS QL(UNIFORM.QUEUE) CURDEPTH' | 
            runmqsc QM$i 2>/dev/null | 
            grep 'CURDEPTH(' | sed 's/.*CURDEPTH(\([^)]*\)).*/\1/' | tr -d ' '
        " 2>/dev/null || echo 0)
        
        echo "  QM$i: Connections=$conns, Sessions=$sessions, QueueDepth=$depth"
    done
}

# PHASE 1: Check environment
echo -e "${YELLOW}PHASE 1: Environment Check${NC}"
echo "────────────────────────────"

for i in 1 2 3; do
    if docker ps | grep -q qm$i; then
        echo -e "  ${GREEN}✓${NC} QM$i is running"
    else
        echo -e "  ${RED}✗${NC} QM$i not running"
        exit 1
    fi
done

# Check CCDT
if [ -f "mq/ccdt/ccdt.json" ]; then
    echo -e "  ${GREEN}✓${NC} CCDT file exists"
    echo -e "  ${CYAN}CCDT settings:${NC}"
    grep -E "affinity|host.*10\.10" mq/ccdt/ccdt.json | head -4 | while read line; do
        echo "    $line"
    done
else
    echo -e "  ${RED}✗${NC} CCDT file missing!"
    exit 1
fi

# Check Java application
if [ -f "java-app/target/mq-uniform-cluster-demo.jar" ]; then
    echo -e "  ${GREEN}✓${NC} Java application compiled"
else
    echo -e "  ${RED}✗${NC} Java application not compiled"
    exit 1
fi

# PHASE 2: Get baseline
echo ""
echo -e "${YELLOW}PHASE 2: Baseline Metrics${NC}"
echo "────────────────────────────"
get_metrics "Before Test"

# PHASE 3: Start test producers
echo ""
echo -e "${YELLOW}PHASE 3: Starting JMS Producers${NC}"
echo "────────────────────────────"

for i in 1 2 3; do
    echo "  Starting Producer-$i (using CCDT)"
    
    docker run -d \
        --name "test-producer-$i" \
        --network mq-uniform-cluster_mqnet \
        -v "$(pwd)/mq/ccdt:/workspace/ccdt:ro" \
        -v "$(pwd)/java-app:/app:ro" \
        -e CCDT_URL="file:///workspace/ccdt/ccdt.json" \
        openjdk:17 \
        bash -c "
            cd /app && \
            java -cp target/mq-uniform-cluster-demo.jar \
            com.ibm.mq.demo.producer.JmsProducer \
            $MESSAGES_PER_PRODUCER $SESSIONS_PER_CONNECTION 500
        " > "$RESULTS_DIR/producer_$i.log" 2>&1
done

echo -e "  ${GREEN}✓${NC} Started 3 JMS producers"

# PHASE 4: Start test consumers  
echo ""
echo -e "${YELLOW}PHASE 4: Starting JMS Consumers${NC}"
echo "────────────────────────────"

for i in 1 2; do
    echo "  Starting Consumer-$i (using CCDT)"
    
    docker run -d \
        --name "test-consumer-$i" \
        --network mq-uniform-cluster_mqnet \
        -v "$(pwd)/mq/ccdt:/workspace/ccdt:ro" \
        -v "$(pwd)/java-app:/app:ro" \
        -e CCDT_URL="file:///workspace/ccdt/ccdt.json" \
        openjdk:17 \
        bash -c "
            cd /app && \
            java -cp target/mq-uniform-cluster-demo.jar \
            com.ibm.mq.demo.consumer.JmsConsumer \
            $TEST_DURATION $SESSIONS_PER_CONNECTION
        " > "$RESULTS_DIR/consumer_$i.log" 2>&1
done

echo -e "  ${GREEN}✓${NC} Started 2 JMS consumers"

# PHASE 5: Monitor distribution
echo ""
echo -e "${YELLOW}PHASE 5: Monitoring Distribution${NC}"
echo "────────────────────────────"

# Wait for connections
echo "  Waiting for connections to establish..."
sleep 10

# Take samples
for sample in 1 2 3; do
    get_metrics "Sample #$sample ($(date '+%H:%M:%S'))"
    
    if [ $sample -lt 3 ]; then
        echo "  Waiting 15 seconds..."
        sleep 15
    fi
done

# PHASE 6: Final metrics
echo ""
echo -e "${YELLOW}PHASE 6: Final Results${NC}"
echo "────────────────────────────"

# Get final metrics
get_metrics "After Test"

# Check producer logs
echo ""
echo -e "${CYAN}Producer Status:${NC}"
for i in 1 2 3; do
    if docker ps | grep -q "test-producer-$i"; then
        echo -e "  Producer-$i: ${GREEN}Running${NC}"
    else
        echo -e "  Producer-$i: ${YELLOW}Completed${NC}"
    fi
    
    # Check for errors in log
    if [ -f "$RESULTS_DIR/producer_$i.log" ]; then
        if grep -q "Connected to" "$RESULTS_DIR/producer_$i.log" 2>/dev/null; then
            qm=$(grep "Connected to" "$RESULTS_DIR/producer_$i.log" | tail -1)
            echo "    └─ $qm"
        fi
        if grep -q "Error\|Exception" "$RESULTS_DIR/producer_$i.log" 2>/dev/null; then
            echo -e "    └─ ${RED}Errors found in log${NC}"
        fi
    fi
done

echo ""
echo -e "${CYAN}Consumer Status:${NC}"
for i in 1 2; do
    if docker ps | grep -q "test-consumer-$i"; then
        echo -e "  Consumer-$i: ${GREEN}Running${NC}"
    else
        echo -e "  Consumer-$i: ${YELLOW}Completed${NC}"
    fi
    
    # Check for errors in log
    if [ -f "$RESULTS_DIR/consumer_$i.log" ]; then
        if grep -q "Connected to" "$RESULTS_DIR/consumer_$i.log" 2>/dev/null; then
            qm=$(grep "Connected to" "$RESULTS_DIR/consumer_$i.log" | tail -1)
            echo "    └─ $qm"
        fi
        if grep -q "Error\|Exception" "$RESULTS_DIR/consumer_$i.log" 2>/dev/null; then
            echo -e "    └─ ${RED}Errors found in log${NC}"
        fi
    fi
done

# PHASE 7: Cleanup
echo ""
echo -e "${YELLOW}PHASE 7: Cleanup${NC}"
echo "────────────────────────────"

docker rm -f test-producer-1 test-producer-2 test-producer-3 test-consumer-1 test-consumer-2 2>/dev/null

echo -e "  ${GREEN}✓${NC} Test containers removed"

# Summary
echo ""
echo -e "${BOLD}${GREEN}═══════════════════════════════════════════════════════${NC}"
echo -e "${BOLD}${GREEN}                 TEST COMPLETE${NC}"
echo -e "${BOLD}${GREEN}═══════════════════════════════════════════════════════${NC}"
echo ""
echo "Results saved in: $RESULTS_DIR/"
echo "  • producer_*.log - Producer outputs"
echo "  • consumer_*.log - Consumer outputs"
echo ""
echo "End Time: $(date)"
echo ""

# Show sample log
echo -e "${CYAN}Sample Producer Log (first 10 lines):${NC}"
head -10 "$RESULTS_DIR/producer_1.log" 2>/dev/null || echo "  (No log available)"

echo ""
echo -e "${CYAN}Sample Consumer Log (first 10 lines):${NC}"
head -10 "$RESULTS_DIR/consumer_1.log" 2>/dev/null || echo "  (No log available)"