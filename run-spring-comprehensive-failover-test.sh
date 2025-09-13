#!/bin/bash

# Comprehensive Spring Boot Failover Test with Full Evidence Collection
# This script runs a complete failover test with JMS debug trace, MQSC, and tcpdump

set -e

# Configuration
TEST_ID="SPRING-FAILOVER-$(date +%s)"
EVIDENCE_DIR="spring_failover_evidence_$(date +%Y%m%d_%H%M%S)"
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

# Function to check QMs
check_qms() {
    echo -e "${YELLOW}Checking Queue Managers...${NC}"
    for qm in qm1 qm2 qm3; do
        if docker ps | grep -q "$qm"; then
            echo -e "${GREEN}✓ $qm is running${NC}"
        else
            echo -e "${RED}✗ $qm is not running - starting...${NC}"
            docker-compose -f docker-compose-simple.yml up -d $qm
            sleep 5
        fi
    done
    echo
}

# Function to start tcpdump
start_tcpdump() {
    echo -e "${YELLOW}Starting tcpdump for MQ traffic capture...${NC}"
    
    # Kill any existing tcpdump
    docker rm -f tcpdump-mq 2>/dev/null || true
    
    # Start tcpdump in container
    docker run -d \
        --name tcpdump-mq \
        --network host \
        -v "$(pwd)/${EVIDENCE_DIR}:/capture" \
        nicolaka/netshoot \
        tcpdump -i any -w /capture/mq_traffic_failover.pcap \
        'tcp port 1414 or tcp port 1415 or tcp port 1416' \
        -v -s 0
    
    echo -e "${GREEN}✓ tcpdump started${NC}"
    echo
}

# Function to stop tcpdump
stop_tcpdump() {
    echo -e "${YELLOW}Stopping tcpdump...${NC}"
    docker stop tcpdump-mq 2>/dev/null || true
    docker rm tcpdump-mq 2>/dev/null || true
    echo -e "${GREEN}✓ tcpdump stopped${NC}"
}

# Function to capture MQSC state
capture_mqsc_state() {
    local label=$1
    local file="${EVIDENCE_DIR}/${label}_mqsc.log"
    
    echo -e "${YELLOW}Capturing MQSC state: ${label}${NC}"
    echo "=== MQSC Capture: ${label} - $(date '+%Y-%m-%d %H:%M:%S.%3N') ===" > $file
    
    for qm in qm1 qm2 qm3; do
        echo "" >> $file
        echo "=== Queue Manager: ${qm^^} ===" >> $file
        
        # Get all connections with CONNTAG and APPTAG
        docker exec $qm bash -c "
            echo 'DIS CONN(*) WHERE(APPLTAG LK ${TEST_ID}*) ALL' | runmqsc ${qm^^}
        " >> $file 2>&1
        
        # Count connections
        local count=$(grep -c "CONN(" $file 2>/dev/null || echo "0")
        echo "Connection count on ${qm^^}: $count" >> $file
    done
    
    echo -e "${GREEN}✓ MQSC captured to: $file${NC}"
}

# Function to build Spring Boot test app
build_spring_test_app() {
    echo -e "${YELLOW}Building Spring Boot test application...${NC}"
    
    # Create simple Spring Boot test app
    cat > ${EVIDENCE_DIR}/SpringFailoverTest.java << 'EOF'
import com.ibm.mq.jms.MQConnectionFactory;
import com.ibm.mq.jms.MQConnection;
import com.ibm.mq.jms.MQSession;
import com.ibm.msg.client.jms.JmsPropertyContext;
import com.ibm.msg.client.wmq.WMQConstants;
import javax.jms.*;
import java.util.*;
import java.text.SimpleDateFormat;

public class SpringFailoverTest {
    private static final String TEST_ID = System.getenv("TEST_ID");
    private static final String CCDT_URL = "file:///workspace/ccdt/ccdt.json";
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== Spring Boot Failover Test ===");
        System.out.println("Test ID: " + TEST_ID);
        System.out.println("Time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date()));
        
        // Enable JMS debug trace
        System.setProperty("com.ibm.msg.client.commonservices.trace.status", "ON");
        System.setProperty("com.ibm.msg.client.commonservices.trace.outputName", "/evidence/jms_debug_trace.log");
        
        // Create connection factory
        MQConnectionFactory factory = new MQConnectionFactory();
        factory.setStringProperty(WMQConstants.WMQ_CCDTURL, CCDT_URL);
        factory.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "*");
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TEST_ID + "-C1");
        factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, WMQConstants.WMQ_CLIENT_RECONNECT);
        factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_TIMEOUT, 1800);
        factory.setTransportType(WMQConstants.WMQ_CM_CLIENT);
        
        // Create connection 1 with 5 sessions
        Connection conn1 = factory.createConnection();
        conn1.setExceptionListener(ex -> {
            System.out.println("[C1] Exception: " + ex.getMessage());
        });
        conn1.start();
        
        List<Session> sessions1 = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            Session session = conn1.createSession(false, Session.AUTO_ACKNOWLEDGE);
            sessions1.add(session);
            System.out.println("Created C1 Session " + i);
        }
        
        // Create connection 2 with 3 sessions
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TEST_ID + "-C2");
        Connection conn2 = factory.createConnection();
        conn2.setExceptionListener(ex -> {
            System.out.println("[C2] Exception: " + ex.getMessage());
        });
        conn2.start();
        
        List<Session> sessions2 = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            Session session = conn2.createSession(false, Session.AUTO_ACKNOWLEDGE);
            sessions2.add(session);
            System.out.println("Created C2 Session " + i);
        }
        
        // Extract and display CONNTAG for both connections
        System.out.println("\n=== Initial Connection State ===");
        printConnectionDetails("C1", conn1, sessions1);
        printConnectionDetails("C2", conn2, sessions2);
        
        // Keep alive for failover test
        System.out.println("\n=== Waiting for failover test (3 minutes) ===");
        for (int i = 0; i < 18; i++) {
            Thread.sleep(10000); // 10 seconds
            System.out.println("Time elapsed: " + (i+1)*10 + " seconds");
            
            // Check connection state every 30 seconds
            if (i % 3 == 2) {
                System.out.println("\n=== Connection State Check ===");
                printConnectionDetails("C1", conn1, sessions1);
                printConnectionDetails("C2", conn2, sessions2);
            }
        }
        
        // Final state
        System.out.println("\n=== Final Connection State ===");
        printConnectionDetails("C1", conn1, sessions1);
        printConnectionDetails("C2", conn2, sessions2);
        
        // Cleanup
        conn1.close();
        conn2.close();
        
        System.out.println("\n=== Test Complete ===");
    }
    
    private static void printConnectionDetails(String label, Connection conn, List<Session> sessions) {
        try {
            if (conn instanceof MQConnection) {
                MQConnection mqConn = (MQConnection) conn;
                JmsPropertyContext context = mqConn.getPropertyContext();
                
                // Extract CONNTAG using correct constant
                String connTag = context.getStringProperty(WMQConstants.JMS_IBM_CONNECTION_TAG);
                String connId = context.getStringProperty(WMQConstants.JMS_IBM_CONNECTION_ID);
                String qm = context.getStringProperty(WMQConstants.JMS_IBM_RESOLVED_QUEUE_MANAGER);
                
                System.out.println(label + " Parent Connection:");
                System.out.println("  CONNTAG: " + connTag);
                System.out.println("  CONNECTION_ID: " + connId);
                System.out.println("  Queue Manager: " + qm);
                
                // Check first session
                if (!sessions.isEmpty() && sessions.get(0) instanceof MQSession) {
                    MQSession mqSession = (MQSession) sessions.get(0);
                    JmsPropertyContext sessionContext = mqSession.getPropertyContext();
                    String sessionConnTag = sessionContext.getStringProperty(WMQConstants.JMS_IBM_CONNECTION_TAG);
                    System.out.println("  First Session CONNTAG: " + sessionConnTag);
                    System.out.println("  Session inherits parent: " + connTag.equals(sessionConnTag));
                }
            }
        } catch (Exception e) {
            System.err.println("Error getting connection details: " + e.getMessage());
        }
    }
}
EOF
    
    # Compile the test
    docker run --rm \
        -v "$(pwd)/${EVIDENCE_DIR}:/evidence" \
        -v "$(pwd)/libs:/libs" \
        openjdk:17 \
        javac -cp "/libs/*" /evidence/SpringFailoverTest.java
    
    echo -e "${GREEN}✓ Test application built${NC}"
    echo
}

# Function to run the failover test
run_failover_test() {
    echo -e "${BLUE}=== Running Failover Test ===${NC}"
    
    # Start the test application in background
    docker run -d \
        --name spring-failover-test \
        --network ${NETWORK} \
        -v "$(pwd)/${EVIDENCE_DIR}:/evidence" \
        -v "$(pwd)/libs:/libs" \
        -v "$(pwd)/mq/ccdt:/workspace/ccdt:ro" \
        -e TEST_ID="${TEST_ID}" \
        openjdk:17 \
        java -cp "/evidence:/libs/*" SpringFailoverTest > ${EVIDENCE_DIR}/app_startup.log 2>&1
    
    echo "Test application started"
    echo "Waiting 30 seconds for connections to establish..."
    sleep 30
    
    # Capture initial state
    capture_mqsc_state "pre_failover"
    docker logs spring-failover-test > ${EVIDENCE_DIR}/app_logs_pre_failover.log 2>&1
    
    # Identify which QM to stop
    TARGET_QM=""
    MAX_CONNS=0
    for qm in qm1 qm2 qm3; do
        count=$(docker exec $qm bash -c "echo 'DIS CONN(*) WHERE(APPLTAG LK ${TEST_ID}*) CHANNEL' | runmqsc ${qm^^}" | grep -c "CONN(" || echo "0")
        echo "QM ${qm^^} has $count connections"
        if [ $count -gt $MAX_CONNS ]; then
            MAX_CONNS=$count
            TARGET_QM=$qm
        fi
    done
    
    if [ -z "$TARGET_QM" ] || [ $MAX_CONNS -eq 0 ]; then
        echo -e "${RED}No connections found! Test failed.${NC}"
        return 1
    fi
    
    echo -e "${YELLOW}Stopping ${TARGET_QM} (has $MAX_CONNS connections) to trigger failover...${NC}"
    
    # Capture pre-failover CONNTAG
    docker exec $TARGET_QM bash -c "
        echo 'DIS CONN(*) WHERE(APPLTAG LK ${TEST_ID}*) ALL' | runmqsc ${TARGET_QM^^} | 
        grep -E 'CONNTAG\(' | head -1
    " > ${EVIDENCE_DIR}/pre_failover_conntag.txt
    
    # Stop the QM to trigger failover
    docker stop $TARGET_QM
    echo -e "${RED}${TARGET_QM} stopped at $(date '+%H:%M:%S')${NC}"
    
    echo "Waiting 20 seconds for failover..."
    sleep 20
    
    # Capture post-failover state
    capture_mqsc_state "post_failover"
    docker logs spring-failover-test > ${EVIDENCE_DIR}/app_logs_post_failover.log 2>&1
    
    # Find where connections moved
    NEW_QM=""
    for qm in qm1 qm2 qm3; do
        if [ "$qm" != "$TARGET_QM" ] && docker ps | grep -q "$qm"; then
            count=$(docker exec $qm bash -c "echo 'DIS CONN(*) WHERE(APPLTAG LK ${TEST_ID}*) CHANNEL' | runmqsc ${qm^^}" | grep -c "CONN(" || echo "0")
            if [ $count -gt 0 ]; then
                NEW_QM=$qm
                echo -e "${GREEN}Connections moved to ${qm^^}: $count connections${NC}"
                
                # Capture post-failover CONNTAG
                docker exec $qm bash -c "
                    echo 'DIS CONN(*) WHERE(APPLTAG LK ${TEST_ID}*) ALL' | runmqsc ${qm^^} | 
                    grep -E 'CONNTAG\(' | head -1
                " > ${EVIDENCE_DIR}/post_failover_conntag.txt
                break
            fi
        fi
    done
    
    # Wait for test to complete
    echo "Waiting for test to complete..."
    sleep 60
    
    # Get final logs
    docker logs spring-failover-test > ${EVIDENCE_DIR}/app_logs_final.log 2>&1
    
    # Stop test application
    docker stop spring-failover-test 2>/dev/null || true
    docker rm spring-failover-test 2>/dev/null || true
    
    # Restart stopped QM
    echo -e "${YELLOW}Restarting ${TARGET_QM}...${NC}"
    docker-compose -f docker-compose-simple.yml up -d $TARGET_QM
    
    echo -e "${GREEN}✓ Failover test complete${NC}"
}

# Function to analyze results
analyze_results() {
    echo -e "${BLUE}=== Analyzing Results ===${NC}"
    
    # Check CONNTAG change
    if [ -f ${EVIDENCE_DIR}/pre_failover_conntag.txt ] && [ -f ${EVIDENCE_DIR}/post_failover_conntag.txt ]; then
        PRE_TAG=$(cat ${EVIDENCE_DIR}/pre_failover_conntag.txt | grep -oP "CONNTAG\(\K[^)]+")
        POST_TAG=$(cat ${EVIDENCE_DIR}/post_failover_conntag.txt | grep -oP "CONNTAG\(\K[^)]+")
        
        echo "Pre-failover CONNTAG:  $PRE_TAG"
        echo "Post-failover CONNTAG: $POST_TAG"
        
        if [ "$PRE_TAG" != "$POST_TAG" ]; then
            echo -e "${GREEN}✓ CONNTAG changed after failover (expected)${NC}"
        else
            echo -e "${YELLOW}⚠ CONNTAG unchanged (check if failover occurred)${NC}"
        fi
    fi
    
    # Check JMS debug trace
    if [ -f ${EVIDENCE_DIR}/jms_debug_trace.log ]; then
        echo -e "${GREEN}✓ JMS debug trace captured${NC}"
        echo "  Size: $(du -h ${EVIDENCE_DIR}/jms_debug_trace.log | cut -f1)"
    else
        echo -e "${YELLOW}⚠ JMS debug trace not found${NC}"
    fi
    
    # Check tcpdump
    if [ -f ${EVIDENCE_DIR}/mq_traffic_failover.pcap ]; then
        echo -e "${GREEN}✓ Network traffic captured${NC}"
        echo "  Size: $(du -h ${EVIDENCE_DIR}/mq_traffic_failover.pcap | cut -f1)"
    fi
    
    echo
}

# Main execution
main() {
    echo -e "${BLUE}Starting Comprehensive Spring Boot Failover Test${NC}"
    echo "================================================"
    echo
    
    # Prerequisites
    check_qms
    
    # Build test app
    build_spring_test_app
    
    # Start captures
    start_tcpdump
    
    # Run test
    run_failover_test
    
    # Stop captures
    stop_tcpdump
    
    # Analyze
    analyze_results
    
    echo -e "${GREEN}=== Test Complete ===${NC}"
    echo "Evidence collected in: ${EVIDENCE_DIR}"
    echo
    echo "Key files:"
    echo "  - JMS Debug Trace: ${EVIDENCE_DIR}/jms_debug_trace.log"
    echo "  - MQSC Pre-failover: ${EVIDENCE_DIR}/pre_failover_mqsc.log"
    echo "  - MQSC Post-failover: ${EVIDENCE_DIR}/post_failover_mqsc.log"
    echo "  - Network Capture: ${EVIDENCE_DIR}/mq_traffic_failover.pcap"
    echo "  - Application Logs: ${EVIDENCE_DIR}/app_logs_*.log"
}

# Run main
main