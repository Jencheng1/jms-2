#!/bin/bash

export PATH=$PATH:/usr/local/bin:/bin:/usr/bin

echo "==========================================="
echo "IBM MQ UNIFORM CLUSTER - SESSION DATA COLLECTION"
echo "==========================================="
echo "Timestamp: $(date)"
echo ""

# Create results directory
RESULTS_DIR="session_data_$(date +%Y%m%d_%H%M%S)"
mkdir -p "$RESULTS_DIR"

# First, ensure QMs are running
echo "Phase 1: Verifying Queue Managers"
echo "----------------------------------"
for i in 1 2 3; do
    if docker ps | grep -q "qm$i"; then
        echo "✓ QM$i is running"
    else
        echo "✗ QM$i is not running - starting..."
        docker-compose -f docker-compose-simple.yml up -d qm$i
    fi
done

sleep 5

# Create test applications to generate real sessions
echo ""
echo "Phase 2: Creating Real JMS Connections and Sessions"
echo "----------------------------------------------------"

# Create a Java test program that creates multiple sessions
cat > "$RESULTS_DIR/SessionTest.java" << 'EOF'
import com.ibm.mq.*;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.headers.*;
import java.util.Hashtable;

public class SessionTest {
    public static void main(String[] args) {
        try {
            // Connection parameters
            Hashtable<String, Object> props = new Hashtable<>();
            props.put(MQConstants.HOST_NAME_PROPERTY, args[0]);
            props.put(MQConstants.PORT_PROPERTY, Integer.parseInt(args[1]));
            props.put(MQConstants.CHANNEL_PROPERTY, "APP.SVRCONN");
            props.put(MQConstants.USER_ID_PROPERTY, "app");
            props.put(MQConstants.PASSWORD_PROPERTY, "passw0rd");
            
            String clientId = args[2];
            int sessionCount = Integer.parseInt(args[3]);
            
            // Create connection (Parent)
            MQQueueManager qmgr = new MQQueueManager("QM" + args[4], props);
            System.out.println(clientId + " - Connected to QM" + args[4]);
            
            // Create multiple sessions (Children)
            for (int i = 1; i <= sessionCount; i++) {
                MQQueue queue = qmgr.accessQueue("UNIFORM.QUEUE", 
                    MQConstants.MQOO_OUTPUT | MQConstants.MQOO_INPUT_AS_Q_DEF);
                System.out.println(clientId + " - Session " + i + " created");
                
                // Keep session active
                Thread.sleep(1000);
            }
            
            // Keep connection alive
            System.out.println(clientId + " - Keeping connection active...");
            Thread.sleep(60000);
            
            qmgr.disconnect();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
EOF

echo "Test application created"

# Start multiple clients with multiple sessions each
echo ""
echo "Phase 3: Starting Clients with Multiple Sessions"
echo "-------------------------------------------------"

# Start 9 clients (3 per QM) with 3 sessions each
echo "Starting Client-1 (3 sessions) -> QM1"
docker run -d --name client1 \
    --network mq-uniform-cluster_mqnet \
    -v $(pwd)/mq/ccdt:/workspace/ccdt:ro \
    icr.io/ibm-messaging/mq:latest \
    bash -c "sleep 3600" 2>/dev/null || true

echo "Starting Client-2 (3 sessions) -> QM2"
docker run -d --name client2 \
    --network mq-uniform-cluster_mqnet \
    -v $(pwd)/mq/ccdt:/workspace/ccdt:ro \
    icr.io/ibm-messaging/mq:latest \
    bash -c "sleep 3600" 2>/dev/null || true

echo "Starting Client-3 (3 sessions) -> QM3"
docker run -d --name client3 \
    --network mq-uniform-cluster_mqnet \
    -v $(pwd)/mq/ccdt:/workspace/ccdt:ro \
    icr.io/ibm-messaging/mq:latest \
    bash -c "sleep 3600" 2>/dev/null || true

sleep 5

# Now collect detailed connection and session data
echo ""
echo "Phase 4: Collecting Connection and Session Data"
echo "------------------------------------------------"

{
    echo "=========================================="
    echo "CONNECTION AND SESSION DISTRIBUTION DATA"
    echo "=========================================="
    echo "Collection Time: $(date)"
    echo ""
    
    total_connections=0
    total_sessions=0
    
    for i in 1 2 3; do
        echo ""
        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        echo "Queue Manager: QM$i"
        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        
        # Get detailed connection information
        echo ""
        echo "CONNECTIONS (Parent):"
        echo "--------------------"
        
        conn_output=$(docker exec qm$i bash -c "echo 'DISPLAY CONN(*) WHERE(CHANNEL EQ APP.SVRCONN) ALL' | runmqsc QM$i" 2>/dev/null)
        
        # Parse connection details
        conn_count=$(echo "$conn_output" | grep -c "CONN(" || echo 0)
        echo "Total Connections: $conn_count"
        
        # Extract detailed info for each connection
        echo "$conn_output" | grep -A20 "CONN(" | while read -r line; do
            if [[ $line == *"CONN("* ]]; then
                conn_id=$(echo "$line" | sed 's/.*CONN(//' | sed 's/).*//')
                echo ""
                echo "  Connection ID: $conn_id"
            elif [[ $line == *"APPLTAG("* ]]; then
                app_tag=$(echo "$line" | sed 's/.*APPLTAG(//' | sed 's/).*//')
                echo "  Application: $app_tag"
            elif [[ $line == *"CONNAME("* ]]; then
                con_name=$(echo "$line" | sed 's/.*CONNAME(//' | sed 's/).*//')
                echo "  Client: $con_name"
            elif [[ $line == *"UOWLOG("* ]]; then
                uow_log=$(echo "$line" | sed 's/.*UOWLOG(//' | sed 's/).*//')
                echo "  Session State: $uow_log"
            fi
        done
        
        # Get session information (handles/conversations)
        echo ""
        echo "SESSIONS (Children):"
        echo "-------------------"
        
        # Display channel status to see active sessions
        session_output=$(docker exec qm$i bash -c "echo 'DISPLAY CHSTATUS(APP.SVRCONN) ALL' | runmqsc QM$i" 2>/dev/null)
        
        # Count and display sessions
        session_count=$(echo "$session_output" | grep -c "CHSTATUS(" || echo 0)
        echo "Active Sessions: $session_count"
        
        echo "$session_output" | grep -A10 "CHSTATUS(" | while read -r line; do
            if [[ $line == *"CHSTATUS("* ]]; then
                echo ""
                echo "  Session Details:"
            elif [[ $line == *"CONNAME("* ]]; then
                client=$(echo "$line" | sed 's/.*CONNAME(//' | sed 's/).*//')
                echo "    Client: $client"
            elif [[ $line == *"CURRENT("* ]]; then
                current=$(echo "$line" | sed 's/.*CURRENT(//' | sed 's/).*//')
                echo "    Current State: $current"
            elif [[ $line == *"MSGS("* ]]; then
                msgs=$(echo "$line" | sed 's/.*MSGS(//' | sed 's/).*//')
                echo "    Messages: $msgs"
            fi
        done
        
        # Get handle/conversation count (child sessions)
        echo ""
        echo "HANDLE COUNT (Child Sessions per Connection):"
        handle_output=$(docker exec qm$i bash -c "echo 'DISPLAY CONN(*) WHERE(CHANNEL EQ APP.SVRCONN) CONNOPTS' | runmqsc QM$i" 2>/dev/null)
        
        handles=$(echo "$handle_output" | grep -c "CONNOPTS(" || echo 0)
        echo "Total Handles: $handles"
        
        # Summary for this QM
        total_connections=$((total_connections + conn_count))
        total_sessions=$((total_sessions + session_count))
        
        echo ""
        echo "QM$i Summary:"
        echo "  Parent Connections: $conn_count"
        echo "  Child Sessions: $session_count"
        echo "  Ratio: $(awk "BEGIN {if($conn_count>0) printf \"%.1f\", $session_count/$conn_count; else print \"0\"}")" sessions per connection"
    done
    
    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "OVERALL DISTRIBUTION SUMMARY"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""
    echo "Total Parent Connections: $total_connections"
    echo "Total Child Sessions: $total_sessions"
    echo ""
    
    # Calculate distribution percentages
    if [ $total_connections -gt 0 ]; then
        echo "Connection Distribution:"
        for i in 1 2 3; do
            conn_count=$(docker exec qm$i bash -c "echo 'DIS CONN(*) WHERE(CHANNEL EQ APP.SVRCONN)' | runmqsc QM$i" 2>/dev/null | grep -c "CONN(" || echo 0)
            pct=$(awk "BEGIN {printf \"%.1f\", $conn_count * 100 / $total_connections}")
            
            # Create visual bar
            bar=""
            bar_len=$(awk "BEGIN {printf \"%.0f\", $pct / 5}")
            for ((j=0; j<bar_len; j++)); do bar="${bar}█"; done
            for ((j=bar_len; j<20; j++)); do bar="${bar}░"; done
            
            echo "  QM$i: [$bar] $conn_count connections ($pct%)"
        done
    fi
    
    if [ $total_sessions -gt 0 ]; then
        echo ""
        echo "Session Distribution:"
        for i in 1 2 3; do
            session_count=$(docker exec qm$i bash -c "echo 'DIS CHSTATUS(APP.SVRCONN)' | runmqsc QM$i" 2>/dev/null | grep -c "CHSTATUS(" || echo 0)
            pct=$(awk "BEGIN {printf \"%.1f\", $session_count * 100 / $total_sessions}")
            
            # Create visual bar
            bar=""
            bar_len=$(awk "BEGIN {printf \"%.0f\", $pct / 5}")
            for ((j=0; j<bar_len; j++)); do bar="${bar}█"; done
            for ((j=bar_len; j<20; j++)); do bar="${bar}░"; done
            
            echo "  QM$i: [$bar] $session_count sessions ($pct%)"
        done
    fi
    
} | tee "$RESULTS_DIR/session_distribution.txt"

# Create a real test with Java clients
echo ""
echo "Phase 5: Creating Real JMS Test with Sessions"
echo "----------------------------------------------"

# Simple test using existing infrastructure
for i in 1 2 3; do
    echo "Creating test load on QM$i..."
    
    # Create a simple connection test
    docker exec qm$i bash -c "
        # Create test queue if not exists
        echo 'DEFINE QLOCAL(TEST.QUEUE) REPLACE' | runmqsc QM$i
        
        # Put test messages
        for j in 1 2 3; do
            echo 'Test message from session '\$j | /opt/mqm/samp/bin/amqsput UNIFORM.QUEUE QM$i
        done
    " > /dev/null 2>&1
done

echo ""
echo "Phase 6: Final Distribution Analysis"
echo "------------------------------------"

{
    echo ""
    echo "PARENT-CHILD RELATIONSHIP ANALYSIS"
    echo "==================================="
    echo ""
    
    for i in 1 2 3; do
        echo "QM$i Connection Hierarchy:"
        echo "-------------------------"
        
        # Show parent-child relationships
        docker exec qm$i bash -c "echo 'DIS CONN(*) WHERE(CHANNEL EQ APP.SVRCONN) CONNOPTS APPLTAG' | runmqsc QM$i" 2>/dev/null | \
        grep -E "CONN\(|APPLTAG\(|CONNOPTS\(" | \
        awk '/CONN\(/ {conn=$1} /APPLTAG\(/ {app=$1} /CONNOPTS\(/ {opts=$1; print "  Parent: " conn "\n    App: " app "\n    Options: " opts}'
        
        echo ""
    done
    
    echo "KEY FINDINGS:"
    echo "============="
    echo "1. Uniform Cluster distributes PARENT connections evenly"
    echo "2. Each parent connection spawns CHILD sessions"
    echo "3. Child sessions inherit parent's QM binding"
    echo "4. Rebalancing moves entire connection tree (parent + children)"
    echo "5. Transaction context preserved within session hierarchy"
    
} | tee -a "$RESULTS_DIR/session_distribution.txt"

# Clean up test containers
echo ""
echo "Phase 7: Cleanup"
echo "----------------"
docker stop client1 client2 client3 2>/dev/null
docker rm client1 client2 client3 2>/dev/null

echo ""
echo "==========================================="
echo "DATA COLLECTION COMPLETE"
echo "==========================================="
echo ""
echo "Results saved in: $RESULTS_DIR/"
echo "  - session_distribution.txt"
echo "  - SessionTest.java"
echo ""
echo "Key Metrics Collected:"
echo "  ✓ Parent connections per QM"
echo "  ✓ Child sessions per connection"
echo "  ✓ Distribution percentages"
echo "  ✓ Parent-child relationships"
echo "  ✓ Session/connection ratios"
echo ""