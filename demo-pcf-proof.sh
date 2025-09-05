#!/bin/bash

################################################################################
# IBM MQ Uniform Cluster - PCF-Based Parent-Child Proof Demo
# 
# Uses PCF (Programmable Command Format) to provide undisputable evidence
# that child sessions always follow parent connections to the same QM
################################################################################

# Color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
MAGENTA='\033[0;35m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Configuration
RESULTS_DIR="pcf_proof_$(date +%Y%m%d_%H%M%S)"
PRODUCER_LOG="$RESULTS_DIR/producer_pcf.log"
CONSUMER_LOG="$RESULTS_DIR/consumer_pcf.log"
PCF_EVIDENCE="$RESULTS_DIR/pcf_evidence.log"
MQSC_EVIDENCE="$RESULTS_DIR/mqsc_evidence.log"

# Create results directory
mkdir -p "$RESULTS_DIR"

print_header() {
    echo -e "\n${CYAN}════════════════════════════════════════════════════════════════${NC}"
    echo -e "${CYAN}    $1${NC}"
    echo -e "${CYAN}════════════════════════════════════════════════════════════════${NC}"
}

check_prerequisites() {
    print_header "CHECKING PREREQUISITES"
    
    echo -e "${YELLOW}Checking queue managers...${NC}"
    for qm in qm1 qm2 qm3; do
        if docker ps | grep -q "$qm"; then
            echo -e "  ${GREEN}✓${NC} $qm is running"
        else
            echo -e "  ${RED}✗${NC} $qm is not running"
            echo -e "\n${RED}Start the cluster first:${NC}"
            echo "  docker-compose -f docker-compose-simple.yml up -d"
            exit 1
        fi
    done
}

compile_pcf_apps() {
    print_header "COMPILING PCF-ENABLED APPLICATIONS"
    
    echo -e "${YELLOW}Compiling JMS applications with PCF monitoring...${NC}"
    
    cd java-app
    
    # Compile all classes including PCF versions
    mvn clean compile
    
    # Package with PCF versions
    mvn package -DskipTests
    
    cd ..
    
    echo -e "${GREEN}✓ PCF applications compiled${NC}"
}

clear_queues() {
    print_header "CLEARING QUEUES"
    
    for qm in qm1 qm2 qm3; do
        local qm_upper=${qm^^}
        docker exec $qm bash -c "echo 'CLEAR QLOCAL(UNIFORM.QUEUE)' | runmqsc $qm_upper" >/dev/null 2>&1
        echo -e "  ${GREEN}✓${NC} $qm_upper queue cleared"
    done
}

collect_mqsc_evidence_before() {
    print_header "COLLECTING INITIAL MQSC STATE"
    
    {
        echo "════════════════════════════════════════════════════════════════"
        echo "         MQSC CONNECTION STATE - BEFORE TEST"
        echo "════════════════════════════════════════════════════════════════"
        echo "Timestamp: $(date '+%Y-%m-%d %H:%M:%S')"
        echo ""
        
        for qm in qm1 qm2 qm3; do
            local qm_upper=${qm^^}
            echo "Queue Manager: $qm_upper"
            echo "─────────────────────────────"
            
            docker exec $qm bash -c "
                echo 'DIS CONN(*) WHERE(CHANNEL EQ APP.SVRCONN) ALL' | runmqsc $qm_upper 2>/dev/null | \
                grep -E 'CONN\(|CHANNEL\(|CONNAME\(|APPLTAG\(|APPLTYPE\(' | head -20
            " 2>/dev/null || echo "  No active connections"
            
            echo ""
        done
    } > "$MQSC_EVIDENCE"
}

run_pcf_producer() {
    print_header "RUNNING PCF-ENABLED PRODUCER"
    
    echo -e "${YELLOW}Starting producers with PCF correlation monitoring...${NC}"
    echo -e "  - 3 Producers"
    echo -e "  - 2 Sessions per producer"
    echo -e "  - PCF monitoring enabled"
    
    # Run PCF producer
    docker run --rm \
        --network mq-uniform-cluster_mqnet \
        -v "$(pwd)/java-app/target/classes:/app" \
        -v "$(pwd)/mq/ccdt:/workspace/ccdt" \
        -e CCDT_URL="file:///workspace/ccdt/ccdt.json" \
        openjdk:17 \
        java -cp "/app:/app/*" \
        com.ibm.mq.demo.producer.JmsProducerWithPCF \
        60 3 2 50 > "$PRODUCER_LOG" 2>&1
    
    echo -e "${GREEN}✓ PCF Producer completed${NC}"
    
    # Extract PCF evidence from log
    grep -A 20 "PCF Evidence\|PCF PROOF\|CORRELATION PROOF" "$PRODUCER_LOG" > "$PCF_EVIDENCE"
}

run_pcf_consumer() {
    print_header "RUNNING PCF-ENABLED CONSUMER"
    
    echo -e "${YELLOW}Starting consumers with PCF correlation monitoring...${NC}"
    echo -e "  - 3 Consumers"
    echo -e "  - 2 Sessions per consumer"
    echo -e "  - PCF monitoring enabled"
    
    # Run PCF consumer
    docker run --rm \
        --network mq-uniform-cluster_mqnet \
        -v "$(pwd)/java-app/target/classes:/app" \
        -v "$(pwd)/mq/ccdt:/workspace/ccdt" \
        -e CCDT_URL="file:///workspace/ccdt/ccdt.json" \
        openjdk:17 \
        java -cp "/app:/app/*" \
        com.ibm.mq.demo.consumer.JmsConsumerWithPCF \
        3 2 5000 false >> "$CONSUMER_LOG" 2>&1
    
    echo -e "${GREEN}✓ PCF Consumer completed${NC}"
    
    # Append consumer PCF evidence
    grep -A 20 "PCF Evidence\|PCF PROOF\|CORRELATION PROOF" "$CONSUMER_LOG" >> "$PCF_EVIDENCE"
}

collect_mqsc_evidence_after() {
    print_header "COLLECTING FINAL MQSC STATE"
    
    {
        echo ""
        echo "════════════════════════════════════════════════════════════════"
        echo "         MQSC CONNECTION STATE - AFTER TEST"
        echo "════════════════════════════════════════════════════════════════"
        echo "Timestamp: $(date '+%Y-%m-%d %H:%M:%S')"
        echo ""
        
        for qm in qm1 qm2 qm3; do
            local qm_upper=${qm^^}
            echo "Queue Manager: $qm_upper"
            echo "─────────────────────────────"
            
            # Get all connections with correlation data
            docker exec $qm bash -c "
                echo 'DIS CONN(*) WHERE(CHANNEL EQ APP.SVRCONN) ALL' | runmqsc $qm_upper 2>/dev/null | \
                awk '
                    /CONN\(/ {
                        if (conn != \"\") print \"\"
                        match(\$0, /CONN\(([^)]+)\)/, arr)
                        conn = arr[1]
                        printf \"Connection: %s\n\", conn
                    }
                    /CHANNEL\(/ {
                        match(\$0, /CHANNEL\(([^)]+)\)/, arr)
                        printf \"  Channel: %s\n\", arr[1]
                    }
                    /CONNAME\(/ {
                        match(\$0, /CONNAME\(([^)]+)\)/, arr)
                        printf \"  CONNAME: %s\n\", arr[1]
                    }
                    /APPLTAG\(/ {
                        match(\$0, /APPLTAG\(([^)]+)\)/, arr)
                        printf \"  APPLTAG: %s\n\", arr[1]
                    }
                    /APPLTYPE\(/ {
                        match(\$0, /APPLTYPE\(([^)]+)\)/, arr)
                        printf \"  APPLTYPE: %s\n\", arr[1]
                    }
                '
            " 2>/dev/null
            
            echo ""
        done
    } >> "$MQSC_EVIDENCE"
}

analyze_results() {
    print_header "ANALYZING PCF CORRELATION RESULTS"
    
    echo -e "\n${YELLOW}PCF Evidence Summary:${NC}"
    if grep -q "PCF PROOF: All connections on SAME Queue Manager" "$PRODUCER_LOG" "$CONSUMER_LOG"; then
        echo -e "  ${GREEN}✓ PCF confirms parent-child QM affinity${NC}"
    else
        echo -e "  ${RED}✗ PCF shows QM mismatch${NC}"
    fi
    
    echo -e "\n${YELLOW}Session Tracker Summary:${NC}"
    if grep -q "SUCCESS: All child sessions connected to same QM" "$PRODUCER_LOG" "$CONSUMER_LOG"; then
        echo -e "  ${GREEN}✓ SessionTracker confirms parent-child relationship${NC}"
    else
        echo -e "  ${RED}✗ SessionTracker shows issues${NC}"
    fi
    
    echo -e "\n${YELLOW}Connection Distribution:${NC}"
    grep -E "Queue Manager: QM[0-9]" "$PRODUCER_LOG" | sort | uniq -c
    
    echo -e "\n${GREEN}════════════════════════════════════════════════════════════════${NC}"
    echo -e "${GREEN}          PCF-BASED PARENT-CHILD PROOF COMPLETE                  ${NC}"
    echo -e "${GREEN}════════════════════════════════════════════════════════════════${NC}"
    echo -e "${GREEN}Results saved to: $RESULTS_DIR/${NC}"
    echo -e "${GREEN}  - Producer log: $PRODUCER_LOG${NC}"
    echo -e "${GREEN}  - Consumer log: $CONSUMER_LOG${NC}"
    echo -e "${GREEN}  - PCF evidence: $PCF_EVIDENCE${NC}"
    echo -e "${GREEN}  - MQSC evidence: $MQSC_EVIDENCE${NC}"
}

# Main execution
main() {
    echo -e "${CYAN}╔════════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${CYAN}║      IBM MQ PCF-BASED PARENT-CHILD CORRELATION PROOF          ║${NC}"
    echo -e "${CYAN}╚════════════════════════════════════════════════════════════════╝${NC}"
    
    check_prerequisites
    compile_pcf_apps
    clear_queues
    collect_mqsc_evidence_before
    run_pcf_producer
    sleep 2
    run_pcf_consumer
    collect_mqsc_evidence_after
    analyze_results
}

# Run main function
main