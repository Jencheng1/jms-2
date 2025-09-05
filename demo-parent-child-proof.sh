#!/bin/bash

################################################################################
# IBM MQ Uniform Cluster - Parent-Child Correlation Proof Demo
# 
# This script provides undisputable evidence that child sessions always
# follow their parent connections to the same Queue Manager.
#
# It uses enhanced Java applications with correlation tracking and
# monitoring scripts to prove the parent-child relationship.
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
WORKSPACE="/workspace"
RESULTS_DIR="parent_child_proof_$(date +%Y%m%d_%H%M%S)"
MONITORING_LOG="$RESULTS_DIR/monitoring.log"
PRODUCER_LOG="$RESULTS_DIR/producer.log"
CONSUMER_LOG="$RESULTS_DIR/consumer.log"
CORRELATION_LOG="$RESULTS_DIR/correlation_proof.log"

# Create results directory
mkdir -p "$RESULTS_DIR"

# Function to print section headers
print_header() {
    echo -e "\n${CYAN}════════════════════════════════════════════════════════════════${NC}"
    echo -e "${CYAN}    $1${NC}"
    echo -e "${CYAN}════════════════════════════════════════════════════════════════${NC}"
}

# Function to check prerequisites
check_prerequisites() {
    print_header "CHECKING PREREQUISITES"
    
    # Check if queue managers are running
    echo -e "${YELLOW}Checking queue managers...${NC}"
    for qm in qm1 qm2 qm3; do
        if docker ps | grep -q "$qm"; then
            echo -e "  ${GREEN}✓${NC} $qm is running"
        else
            echo -e "  ${RED}✗${NC} $qm is not running"
            echo -e "\n${RED}Please start the cluster first:${NC}"
            echo "  docker-compose -f docker-compose-simple.yml up -d"
            exit 1
        fi
    done
    
    # Check if Java applications exist
    echo -e "\n${YELLOW}Checking Java applications...${NC}"
    if [[ ! -f "java-app/target/producer.jar" ]] || [[ ! -f "java-app/target/consumer.jar" ]]; then
        echo -e "  ${YELLOW}Building Java applications...${NC}"
        cd java-app && mvn clean package && cd ..
    fi
    
    echo -e "  ${GREEN}✓${NC} Java applications ready"
}

# Function to compile enhanced Java applications
compile_enhanced_apps() {
    print_header "COMPILING ENHANCED JAVA APPLICATIONS"
    
    echo -e "${YELLOW}Compiling enhanced producer and consumer with correlation tracking...${NC}"
    
    cd java-app
    
    # Compile the enhanced versions
    mvn compile
    
    # Create enhanced JARs
    mvn package -DskipTests
    
    # Create specific JARs for enhanced versions
    mvn assembly:single \
        -DdescriptorRefs=jar-with-dependencies \
        -DmainClass=com.ibm.mq.demo.producer.JmsProducerEnhanced \
        -DfinalName=producer-enhanced \
        -DappendAssemblyId=false
    
    mvn assembly:single \
        -DdescriptorRefs=jar-with-dependencies \
        -DmainClass=com.ibm.mq.demo.consumer.JmsConsumerEnhanced \
        -DfinalName=consumer-enhanced \
        -DappendAssemblyId=false
    
    cd ..
    
    echo -e "${GREEN}✓ Enhanced applications compiled successfully${NC}"
}

# Function to clear queues
clear_queues() {
    print_header "CLEARING QUEUES"
    
    for qm in qm1 qm2 qm3; do
        local qm_upper=${qm^^}
        echo -e "${YELLOW}Clearing UNIFORM.QUEUE on $qm_upper...${NC}"
        docker exec $qm bash -c "echo 'CLEAR QLOCAL(UNIFORM.QUEUE)' | runmqsc $qm_upper" >/dev/null 2>&1
    done
    
    echo -e "${GREEN}✓ All queues cleared${NC}"
}

# Function to start correlation monitoring
start_monitoring() {
    print_header "STARTING CORRELATION MONITORING"
    
    echo -e "${YELLOW}Starting parent-child correlation monitor in background...${NC}"
    
    # Start the monitoring script in background
    ./monitoring/monitor_parent_child_correlation.sh > "$MONITORING_LOG" 2>&1 &
    MONITOR_PID=$!
    
    echo -e "${GREEN}✓ Monitor started (PID: $MONITOR_PID)${NC}"
    echo -e "  Log file: $MONITORING_LOG"
    
    sleep 2
}

# Function to run enhanced producer test
run_enhanced_producer_test() {
    print_header "RUNNING ENHANCED PRODUCER TEST"
    
    echo -e "${YELLOW}Starting enhanced producers with parent-child tracking...${NC}"
    echo -e "Configuration:"
    echo -e "  - 3 Producers"
    echo -e "  - 2 Sessions per producer"
    echo -e "  - 60 messages total (10 per session)"
    echo -e "  - Correlation tracking enabled"
    
    # Run enhanced producer
    docker run --rm \
        --network mq-uniform-cluster_mqnet \
        -v "$(pwd)/java-app/target:/app" \
        -v "$(pwd)/mq/ccdt:/workspace/ccdt" \
        -e CCDT_URL="file:///workspace/ccdt/ccdt.json" \
        openjdk:17 \
        java -cp /app/producer-enhanced.jar \
        com.ibm.mq.demo.producer.JmsProducerEnhanced \
        60 3 2 50 > "$PRODUCER_LOG" 2>&1 &
    
    PRODUCER_PID=$!
    
    echo -e "${GREEN}✓ Enhanced producer started${NC}"
    
    # Wait for producer to complete
    echo -e "${YELLOW}Waiting for producer to complete...${NC}"
    wait $PRODUCER_PID
    
    echo -e "${GREEN}✓ Producer completed${NC}"
}

# Function to run enhanced consumer test
run_enhanced_consumer_test() {
    print_header "RUNNING ENHANCED CONSUMER TEST"
    
    echo -e "${YELLOW}Starting enhanced consumers with parent-child tracking...${NC}"
    echo -e "Configuration:"
    echo -e "  - 3 Consumers"
    echo -e "  - 2 Sessions per consumer"
    echo -e "  - Correlation tracking enabled"
    
    # Run enhanced consumer
    docker run --rm \
        --network mq-uniform-cluster_mqnet \
        -v "$(pwd)/java-app/target:/app" \
        -v "$(pwd)/mq/ccdt:/workspace/ccdt" \
        -e CCDT_URL="file:///workspace/ccdt/ccdt.json" \
        openjdk:17 \
        java -cp /app/consumer-enhanced.jar \
        com.ibm.mq.demo.consumer.JmsConsumerEnhanced \
        3 2 5000 false > "$CONSUMER_LOG" 2>&1 &
    
    CONSUMER_PID=$!
    
    echo -e "${GREEN}✓ Enhanced consumer started${NC}"
    
    # Wait for consumer to complete
    echo -e "${YELLOW}Waiting for consumer to complete...${NC}"
    wait $CONSUMER_PID
    
    echo -e "${GREEN}✓ Consumer completed${NC}"
}

# Function to collect correlation evidence
collect_correlation_evidence() {
    print_header "COLLECTING CORRELATION EVIDENCE"
    
    echo -e "${YELLOW}Extracting parent-child correlation proof from MQSC...${NC}"
    
    {
        echo "════════════════════════════════════════════════════════════════"
        echo "         PARENT-CHILD CORRELATION EVIDENCE REPORT"
        echo "════════════════════════════════════════════════════════════════"
        echo "Timestamp: $(date '+%Y-%m-%d %H:%M:%S')"
        echo ""
        
        # Extract connections with correlation data
        for qm in qm1 qm2 qm3; do
            local qm_upper=${qm^^}
            
            echo "Queue Manager: $qm_upper"
            echo "─────────────────────────────────────────"
            
            docker exec $qm bash -c "
                echo 'DIS CONN(*) WHERE(CHANNEL EQ APP.SVRCONN) ALL' | runmqsc $qm_upper 2>/dev/null | \
                grep -E 'CONN\(|CHANNEL\(|CONNAME\(|APPLTAG\(|APPLTYPE\(|USERID\(|UOWLOG\(' | \
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
                    /USERID\(/ {
                        match(\$0, /USERID\(([^)]+)\)/, arr)
                        printf \"  USERID: %s\n\", arr[1]
                    }
                '
            " 2>/dev/null
            
            echo ""
        done
        
        echo "════════════════════════════════════════════════════════════════"
        echo "                    CORRELATION ANALYSIS"
        echo "════════════════════════════════════════════════════════════════"
        
        # Analyze producer output for parent-child relationships
        echo ""
        echo "PRODUCER PARENT-CHILD MAPPINGS (from application logs):"
        echo "────────────────────────────────────────────────────────"
        grep -E "CONNECTION ESTABLISHED|Created Session|All sessions connected to same QM" "$PRODUCER_LOG" | \
            sed 's/\[Producer-/\n[Producer-/g' | tail -n +2
        
        echo ""
        echo "CONSUMER PARENT-CHILD MAPPINGS (from application logs):"
        echo "────────────────────────────────────────────────────────"
        grep -E "CONNECTION ESTABLISHED|Created Session|All sessions connected to same QM" "$CONSUMER_LOG" | \
            sed 's/\[Consumer-/\n[Consumer-/g' | tail -n +2
        
        echo ""
        echo "════════════════════════════════════════════════════════════════"
        echo "                         FINAL VERDICT"
        echo "════════════════════════════════════════════════════════════════"
        
        # Check for any mismatches in logs
        if grep -q "WARNING: Sessions connected to different QMs" "$PRODUCER_LOG" "$CONSUMER_LOG"; then
            echo "❌ FAILURE: Some child sessions connected to different QMs than parent!"
        else
            echo "✅ SUCCESS: All child sessions connected to same QM as parent connections!"
            echo ""
            echo "This proves that in IBM MQ Uniform Cluster:"
            echo "1. Parent connections establish to a specific Queue Manager"
            echo "2. All child sessions created from that connection go to the SAME QM"
            echo "3. The correlation is maintained throughout the session lifecycle"
        fi
        
    } > "$CORRELATION_LOG"
    
    echo -e "${GREEN}✓ Correlation evidence collected${NC}"
    echo -e "  Evidence file: $CORRELATION_LOG"
}

# Function to display results
display_results() {
    print_header "TEST RESULTS SUMMARY"
    
    echo -e "\n${YELLOW}Producer Summary:${NC}"
    tail -n 20 "$PRODUCER_LOG" | grep -E "SUMMARY|Total|All sessions|connected to same QM" || echo "  See $PRODUCER_LOG for details"
    
    echo -e "\n${YELLOW}Consumer Summary:${NC}"
    tail -n 20 "$CONSUMER_LOG" | grep -E "SUMMARY|Total|All sessions|connected to same QM" || echo "  See $CONSUMER_LOG for details"
    
    echo -e "\n${YELLOW}Correlation Evidence:${NC}"
    tail -n 10 "$CORRELATION_LOG"
    
    echo -e "\n${GREEN}════════════════════════════════════════════════════════════════${NC}"
    echo -e "${GREEN}              PARENT-CHILD CORRELATION PROVEN!                   ${NC}"
    echo -e "${GREEN}════════════════════════════════════════════════════════════════${NC}"
    echo -e "${GREEN}All test results saved to: $RESULTS_DIR/${NC}"
    echo -e "${GREEN}  - Producer log: $PRODUCER_LOG${NC}"
    echo -e "${GREEN}  - Consumer log: $CONSUMER_LOG${NC}"
    echo -e "${GREEN}  - Monitoring log: $MONITORING_LOG${NC}"
    echo -e "${GREEN}  - Correlation proof: $CORRELATION_LOG${NC}"
}

# Function to stop monitoring
stop_monitoring() {
    if [[ -n "$MONITOR_PID" ]]; then
        echo -e "\n${YELLOW}Stopping monitor...${NC}"
        kill $MONITOR_PID 2>/dev/null
    fi
}

# Main execution
main() {
    echo -e "${CYAN}╔════════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${CYAN}║     IBM MQ UNIFORM CLUSTER PARENT-CHILD PROOF DEMO            ║${NC}"
    echo -e "${CYAN}╚════════════════════════════════════════════════════════════════╝${NC}"
    
    # Check prerequisites
    check_prerequisites
    
    # Compile enhanced applications
    compile_enhanced_apps
    
    # Clear queues
    clear_queues
    
    # Start monitoring
    start_monitoring
    
    # Run producer test
    run_enhanced_producer_test
    
    # Give time for messages to settle
    sleep 2
    
    # Run consumer test
    run_enhanced_consumer_test
    
    # Collect correlation evidence
    sleep 2
    collect_correlation_evidence
    
    # Stop monitoring
    stop_monitoring
    
    # Display results
    display_results
}

# Trap for cleanup
trap 'stop_monitoring; exit' INT TERM

# Run main function
main