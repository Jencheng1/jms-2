#!/bin/bash

echo "=================================================================================="
echo "COMPREHENSIVE MQ MONITOR - COMPILE AND RUN"
echo "=================================================================================="

# Check if libs directory exists
if [ ! -d "libs" ]; then
    echo "Error: libs directory not found"
    echo "Please ensure IBM MQ JARs are in the libs directory"
    exit 1
fi

# Clean previous runs
echo "Cleaning previous artifacts..."
rm -f *.class
rm -f comprehensive_monitor_*.log
rm -f mqtrace_*.log

# Compile the Java program
echo "Compiling ComprehensiveMQMonitor.java..."
javac -cp "libs/*:." ComprehensiveMQMonitor.java

if [ $? -ne 0 ]; then
    echo "Error: Compilation failed"
    exit 1
fi

echo "Compilation successful!"
echo ""

# Create results directory
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
RESULTS_DIR="monitor_results_${TIMESTAMP}"
mkdir -p $RESULTS_DIR

echo "Results will be saved to: $RESULTS_DIR"
echo ""

# Run the monitor in Docker container
echo "Starting Comprehensive MQ Monitor..."
echo "This will:"
echo "  1. Create 1 JMS Connection to QM1"
echo "  2. Create 5 Sessions from that connection"
echo "  3. Enable ALL trace and debug logging"
echo "  4. Use PCF to query connection details"
echo "  5. Correlate JMS and MQSC data"
echo ""

docker run --rm \
    --network mq-uniform-cluster_mqnet \
    -v "$(pwd):/app" \
    -v "$(pwd)/libs:/libs" \
    -v "$(pwd)/mq/ccdt:/workspace/ccdt" \
    -w /app \
    openjdk:17 \
    java -cp "/libs/*:." \
    -Dcom.ibm.mq.cfg.useIBMCipherMappings=false \
    -Djavax.net.ssl.trustStore=/app/truststore.jks \
    -Djavax.net.ssl.trustStorePassword=password \
    ComprehensiveMQMonitor

# Move logs to results directory
echo ""
echo "Moving logs to results directory..."
mv comprehensive_monitor_*.log $RESULTS_DIR/ 2>/dev/null
mv mqtrace_*.log $RESULTS_DIR/ 2>/dev/null

# Also capture MQSC output
echo ""
echo "Capturing MQSC connection information..."
APPTAG=$(grep "Application Tag:" $RESULTS_DIR/comprehensive_monitor_*.log | head -1 | cut -d: -f2 | tr -d ' ')

if [ ! -z "$APPTAG" ]; then
    echo "Querying connections with APPTAG: $APPTAG"
    docker exec qm1 bash -c "echo 'DIS CONN(*) WHERE(APPLTAG EQ $APPTAG) ALL' | runmqsc QM1" > $RESULTS_DIR/mqsc_connections.log
    
    echo "Querying all connections on QM1..."
    docker exec qm1 bash -c "echo 'DIS CONN(*) WHERE(CHANNEL EQ APP.SVRCONN) ALL' | runmqsc QM1" > $RESULTS_DIR/mqsc_all_connections.log
fi

echo ""
echo "=================================================================================="
echo "MONITOR COMPLETED"
echo "Results saved to: $RESULTS_DIR"
echo ""
echo "Files generated:"
ls -la $RESULTS_DIR/
echo "=================================================================================="