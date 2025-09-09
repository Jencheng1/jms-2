#!/bin/bash

# Complete trace and debug collection script
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
TRACE_DIR="complete_trace_${TIMESTAMP}"

echo "=============================================="
echo " COMPLETE TRACE AND DEBUG COLLECTION"
echo " Timestamp: ${TIMESTAMP}"
echo "=============================================="
echo ""

# Create trace directory
mkdir -p "${TRACE_DIR}"
cd "${TRACE_DIR}"

echo "Created trace directory: $(pwd)"
echo ""

# 1. Enable MQ trace on all QMs
echo "Step 1: Enabling MQ trace on all Queue Managers..."
echo "-----------------------------------------------"
for qm in qm1 qm2 qm3; do
    echo "  Enabling trace on ${qm^^}..."
    docker exec $qm bash -c "strmqtrc -m ${qm^^} -t all -t detail" 2>/dev/null || echo "    (Already running or not available)"
done
echo ""

# 2. Clear old connections
echo "Step 2: Checking current connection status..."
echo "-----------------------------------------------"
for qm in qm1 qm2 qm3; do
    echo "  ${qm^^}:"
    total=$(docker exec $qm bash -c "echo 'DIS CONN(*) TYPE(CONN)' | runmqsc ${qm^^} 2>/dev/null | grep -c 'CONN('" || echo "0")
    app=$(docker exec $qm bash -c "echo 'DIS CONN(*) WHERE(CHANNEL EQ APP.SVRCONN)' | runmqsc ${qm^^} 2>/dev/null | grep -c 'CONN('" || echo "0")
    echo "    Total connections: $total"
    echo "    APP.SVRCONN connections: $app"
done
echo ""

# 3. Compile all test programs
echo "Step 3: Compiling test programs..."
echo "-----------------------------------------------"
cd ..
javac -cp "libs/*:." FinalTraceTest.java 2>&1 | tee "${TRACE_DIR}/compile_final.log"
javac -cp "libs/*:." CCDTDistributionTest.java 2>&1 | tee "${TRACE_DIR}/compile_ccdt.log"
javac -cp "libs/*:." PCFDebugTest.java 2>&1 | tee "${TRACE_DIR}/compile_pcf.log"
javac -cp "libs/*:." PCFMinimalTest.java 2>&1 | tee "${TRACE_DIR}/compile_minimal.log"
echo "  Compilation complete"
echo ""

# 4. Run FinalTraceTest with JMS tracing
echo "Step 4: Running FinalTraceTest with full JMS trace..."
echo "-----------------------------------------------"
export MQTRACE=1
export JMS_TRACE_LEVEL=9
export WMQ_TRACE_LEVEL=9

java -Dcom.ibm.msg.client.wmq.v6Trace=true \
     -Dcom.ibm.msg.client.wmq.traceLevel=9 \
     -Dcom.ibm.msg.client.wmq.traceFile="${TRACE_DIR}/wmq_final_trace.log" \
     -Dcom.ibm.mq.cfg.TCP.TraceLevel=9 \
     -Djava.util.logging.config.file=logging.properties \
     -cp "libs/*:." FinalTraceTest 2>&1 | tee "${TRACE_DIR}/final_trace_output.log"
echo ""

# 5. Capture MQSC snapshot after FinalTraceTest
echo "Step 5: Capturing MQSC snapshots after FinalTraceTest..."
echo "-----------------------------------------------"
for qm in qm1 qm2 qm3; do
    echo "  Capturing ${qm^^} connections..."
    docker exec $qm bash -c "echo 'DIS CONN(*) ALL' | runmqsc ${qm^^}" > "${TRACE_DIR}/mqsc_${qm}_after_final.log" 2>&1
done
echo ""

# 6. Run CCDTDistributionTest
echo "Step 6: Running CCDTDistributionTest..."
echo "-----------------------------------------------"
java -Dcom.ibm.msg.client.wmq.v6Trace=true \
     -Dcom.ibm.msg.client.wmq.traceLevel=9 \
     -Dcom.ibm.msg.client.wmq.traceFile="${TRACE_DIR}/wmq_ccdt_trace.log" \
     -cp "libs/*:." CCDTDistributionTest 2>&1 | tee "${TRACE_DIR}/ccdt_test_output.log"
echo ""

# 7. Run PCF tests
echo "Step 7: Running PCF tests..."
echo "-----------------------------------------------"
echo "  Running PCFDebugTest..."
java -cp "libs/*:." PCFDebugTest 2>&1 | tee "${TRACE_DIR}/pcf_debug_output.log"

echo "  Running PCFMinimalTest..."
java -cp "libs/*:." PCFMinimalTest 2>&1 | tee "${TRACE_DIR}/pcf_minimal_output.log"
echo ""

# 8. Create detailed connection test with specific tracking
echo "Step 8: Running detailed connection tracking test..."
echo "-----------------------------------------------"
cat > DetailedTrackingTest.java << 'EOF'
import javax.jms.*;
import com.ibm.msg.client.jms.*;
import com.ibm.msg.client.wmq.WMQConstants;
import java.util.*;
import java.io.*;
import java.text.SimpleDateFormat;

public class DetailedTrackingTest {
    private static SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
    
    public static void main(String[] args) throws Exception {
        PrintWriter log = new PrintWriter(new FileWriter("detailed_tracking.log"));
        
        log.println("================================================");
        log.println(" DETAILED CONNECTION TRACKING TEST");
        log.println(" Time: " + sdf.format(new Date()));
        log.println("================================================\n");
        
        // Test with unique tag
        String uniqueTag = "TRACK" + (System.currentTimeMillis() % 100000);
        log.println("Unique tracking tag: " + uniqueTag);
        log.println();
        
        Connection conn = null;
        
        try {
            // Create connection
            log.println("[" + sdf.format(new Date()) + "] Creating JMS Connection...");
            
            JmsFactoryFactory ff = JmsFactoryFactory.getInstance(WMQConstants.WMQ_PROVIDER);
            JmsConnectionFactory cf = ff.createConnectionFactory();
            
            cf.setStringProperty(WMQConstants.WMQ_HOST_NAME, "10.10.10.10");
            cf.setIntProperty(WMQConstants.WMQ_PORT, 1414);
            cf.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "QM1");
            cf.setStringProperty(WMQConstants.WMQ_CHANNEL, "APP.SVRCONN");
            cf.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
            cf.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, uniqueTag);
            
            conn = cf.createConnection("app", "passw0rd");
            conn.start();
            
            log.println("[" + sdf.format(new Date()) + "] Connection created");
            
            // Get connection properties
            if (conn instanceof JmsPropertyContext) {
                JmsPropertyContext cpc = (JmsPropertyContext) conn;
                String resolvedQm = cpc.getStringProperty(WMQConstants.WMQ_RESOLVED_QUEUE_MANAGER);
                log.println("  Resolved Queue Manager: " + resolvedQm);
                
                // Try to get more properties
                try {
                    String hostName = cpc.getStringProperty(WMQConstants.WMQ_HOST_NAME);
                    int port = cpc.getIntProperty(WMQConstants.WMQ_PORT);
                    log.println("  Connected to: " + hostName + ":" + port);
                } catch (Exception e) {
                    // Ignore
                }
            }
            
            // Check MQSC before sessions
            log.println("\n[" + sdf.format(new Date()) + "] MQSC check before sessions:");
            int before = countConnections("QM1", uniqueTag);
            log.println("  Connections with APPLTAG " + uniqueTag + ": " + before);
            
            // Get detailed MQSC info
            getDetailedMQSC("QM1", uniqueTag, log);
            
            // Create sessions
            log.println("\n[" + sdf.format(new Date()) + "] Creating 5 sessions...");
            List<Session> sessions = new ArrayList<>();
            List<MessageProducer> producers = new ArrayList<>();
            
            for (int i = 1; i <= 5; i++) {
                Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
                sessions.add(session);
                
                // Create queue and producer
                Queue queue = session.createQueue("DEMO.QUEUE");
                MessageProducer producer = session.createProducer(queue);
                producers.add(producer);
                
                // Send a test message
                TextMessage msg = session.createTextMessage("Test message " + i);
                msg.setStringProperty("SessionNumber", String.valueOf(i));
                msg.setStringProperty("TrackingTag", uniqueTag);
                producer.send(msg);
                
                log.println("  Session " + i + " created and message sent");
                
                // Brief pause
                Thread.sleep(200);
            }
            
            // Wait for connections to establish
            Thread.sleep(2000);
            
            // Check MQSC after sessions
            log.println("\n[" + sdf.format(new Date()) + "] MQSC check after sessions:");
            int after = countConnections("QM1", uniqueTag);
            log.println("  Connections with APPLTAG " + uniqueTag + ": " + after);
            log.println("  New connections created: " + (after - before));
            
            // Get detailed MQSC info
            getDetailedMQSC("QM1", uniqueTag, log);
            
            // Analysis
            log.println("\n================================================");
            log.println(" ANALYSIS");
            log.println("================================================");
            log.println("JMS Objects created:");
            log.println("  1 Connection");
            log.println("  5 Sessions");
            log.println("  5 MessageProducers");
            log.println("  5 Messages sent");
            log.println("\nMQ Connections observed:");
            log.println("  Before: " + before);
            log.println("  After: " + after);
            log.println("  Difference: " + (after - before));
            
            if (after > before) {
                log.println("\n✓ Child sessions created additional MQ connections");
                log.println("✓ All connections on same QM (parent-child affinity proven)");
            } else {
                log.println("\n⚠ Connection sharing active (sessions multiplexed)");
            }
            
            // Cleanup
            log.println("\n[" + sdf.format(new Date()) + "] Cleaning up...");
            for (MessageProducer p : producers) p.close();
            for (Session s : sessions) s.close();
            conn.close();
            
            Thread.sleep(1000);
            
            // Final check
            int finalCount = countConnections("QM1", uniqueTag);
            log.println("[" + sdf.format(new Date()) + "] After cleanup: " + finalCount + " connections remain");
            
        } catch (Exception e) {
            log.println("\nERROR: " + e.getMessage());
            e.printStackTrace(log);
        } finally {
            if (conn != null) {
                try { conn.close(); } catch (Exception e) {}
            }
        }
        
        log.println("\n[" + sdf.format(new Date()) + "] Test complete");
        log.close();
        
        // Print summary to console
        System.out.println("Detailed tracking test complete. See detailed_tracking.log");
    }
    
    private static int countConnections(String qm, String appTag) {
        try {
            String cmd = String.format(
                "docker exec %s bash -c \"echo 'DIS CONN(*) WHERE(APPLTAG EQ %s)' | runmqsc %s | grep -c 'CONN('\"",
                qm.toLowerCase(), appTag, qm
            );
            Process p = Runtime.getRuntime().exec(new String[]{"bash", "-c", cmd});
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = reader.readLine();
            p.waitFor();
            return line != null ? Integer.parseInt(line.trim()) : 0;
        } catch (Exception e) {
            return -1;
        }
    }
    
    private static void getDetailedMQSC(String qm, String appTag, PrintWriter log) {
        try {
            String cmd = String.format(
                "docker exec %s bash -c \"echo 'DIS CONN(*) WHERE(APPLTAG EQ %s) ALL' | runmqsc %s\"",
                qm.toLowerCase(), appTag, qm
            );
            Process p = Runtime.getRuntime().exec(new String[]{"bash", "-c", cmd});
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            log.println("  MQSC Details:");
            while ((line = reader.readLine()) != null) {
                if (line.contains("CONN(") || line.contains("PID(") || 
                    line.contains("TID(") || line.contains("CHANNEL(") ||
                    line.contains("CONNAME(") || line.contains("EXTCONN(")) {
                    log.println("    " + line.trim());
                }
            }
            p.waitFor();
        } catch (Exception e) {
            log.println("  Error getting MQSC details: " + e.getMessage());
        }
    }
}
EOF

javac -cp "libs/*:." DetailedTrackingTest.java
java -cp "libs/*:." DetailedTrackingTest
mv detailed_tracking.log "${TRACE_DIR}/"
echo ""

# 9. Capture final MQSC state
echo "Step 9: Capturing final MQSC state..."
echo "-----------------------------------------------"
for qm in qm1 qm2 qm3; do
    echo "  Final snapshot of ${qm^^}..."
    docker exec $qm bash -c "echo 'DIS CONN(*) ALL' | runmqsc ${qm^^}" > "${TRACE_DIR}/mqsc_${qm}_final.log" 2>&1
    docker exec $qm bash -c "echo 'DIS QMGR ALL' | runmqsc ${qm^^}" > "${TRACE_DIR}/mqsc_${qm}_qmgr.log" 2>&1
    docker exec $qm bash -c "echo 'DIS CHL(APP.SVRCONN)' | runmqsc ${qm^^}" > "${TRACE_DIR}/mqsc_${qm}_channel.log" 2>&1
done
echo ""

# 10. Stop MQ trace
echo "Step 10: Stopping MQ trace..."
echo "-----------------------------------------------"
for qm in qm1 qm2 qm3; do
    echo "  Stopping trace on ${qm^^}..."
    docker exec $qm bash -c "endmqtrc -m ${qm^^}" 2>/dev/null || echo "    (Not running)"
done
echo ""

# 11. Generate summary report
echo "Step 11: Generating summary report..."
echo "-----------------------------------------------"
cd "${TRACE_DIR}"

cat > TRACE_SUMMARY.md << EOF
# Complete Trace Collection Summary
## Timestamp: ${TIMESTAMP}
## Directory: $(pwd)

### Files Generated:

#### Test Outputs:
- final_trace_output.log - FinalTraceTest execution with trace
- ccdt_test_output.log - CCDTDistributionTest execution
- pcf_debug_output.log - PCF debug test results
- pcf_minimal_output.log - PCF minimal test results
- detailed_tracking.log - Detailed connection tracking

#### MQSC Snapshots:
- mqsc_qm1_after_final.log - QM1 after FinalTraceTest
- mqsc_qm2_after_final.log - QM2 after FinalTraceTest
- mqsc_qm3_after_final.log - QM3 after FinalTraceTest
- mqsc_qm*_final.log - Final state of all QMs
- mqsc_qm*_qmgr.log - Queue Manager configurations
- mqsc_qm*_channel.log - Channel configurations

#### Trace Files:
- wmq_final_trace.log - WMQ trace from FinalTraceTest (if generated)
- wmq_ccdt_trace.log - WMQ trace from CCDTDistributionTest (if generated)

### Test Results Summary:

$(grep -A5 "TEST 1: PCF API STATUS" final_trace_output.log 2>/dev/null || echo "PCF test results not found")

$(grep -A10 "TEST 2: PARENT-CHILD PROOF" final_trace_output.log 2>/dev/null || echo "Parent-child test results not found")

$(grep -A10 "TEST 3: UNIFORM CLUSTER" final_trace_output.log 2>/dev/null || echo "Distribution test results not found")

### Key Findings:
- See individual log files for detailed results
- Check MQSC snapshots for connection evidence
- Review trace files for debugging information

### Generated: $(date)
EOF

echo "  Summary report created: TRACE_SUMMARY.md"
echo ""

# 12. List all files
echo "Step 12: Trace collection complete. Files generated:"
echo "-----------------------------------------------"
ls -lah *.log *.md 2>/dev/null | head -20
echo ""

cd ..
echo "=============================================="
echo " TRACE COLLECTION COMPLETE"
echo "=============================================="
echo ""
echo "Results directory: ${TRACE_DIR}"
echo "View summary: cat ${TRACE_DIR}/TRACE_SUMMARY.md"
echo "View detailed tracking: cat ${TRACE_DIR}/detailed_tracking.log"