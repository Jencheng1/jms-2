#!/bin/bash

# Run PCF Correlation Proof to demonstrate uniform cluster connection/session grouping

echo "========================================"
echo " PCF CORRELATION PROOF"
echo " Proving Uniform Cluster Grouping"
echo "========================================"
echo

# Check if containers are running
echo "Checking Queue Managers..."
for i in 1 2 3; do
    if docker ps | grep -q "qm$i"; then
        echo "  ✓ QM$i is running"
    else
        echo "  ✗ QM$i is not running - starting..."
        docker-compose -f docker-compose-simple.yml up -d qm$i
    fi
done
echo

# Fix PCF permissions
echo "Fixing PCF permissions..."
./fix-pcf-permissions.sh
echo

# Clean up old logs
echo "Cleaning up old logs..."
rm -f PCF_*.log jms_trace_*.log 2>/dev/null
echo

# Compile the PCF Correlation Proof
echo "Compiling PCF Correlation Proof..."
if [ ! -d "libs" ]; then
    echo "  Error: libs directory not found"
    echo "  Please ensure MQ JARs are in ./libs/"
    exit 1
fi

# Check for required JARs
REQUIRED_JARS=(
    "com.ibm.mq.allclient-9.3.5.0.jar"
    "javax.jms-api-2.0.1.jar"
    "json-20231013.jar"
)

for jar in "${REQUIRED_JARS[@]}"; do
    if [ ! -f "libs/$jar" ]; then
        echo "  Missing: libs/$jar"
        echo "  Downloading..."
        # Try to download if missing
        case "$jar" in
            "javax.jms-api-2.0.1.jar")
                wget -q -O "libs/$jar" "https://repo1.maven.org/maven2/javax/jms/javax.jms-api/2.0.1/javax.jms-api-2.0.1.jar"
                ;;
            "json-20231013.jar")
                wget -q -O "libs/$jar" "https://repo1.maven.org/maven2/org/json/json/20231013/json-20231013.jar"
                ;;
            *)
                echo "  Cannot auto-download $jar - please add manually"
                ;;
        esac
    fi
done

# Compile
javac -cp "libs/*:." PCFCorrelationProof.java
if [ $? -ne 0 ]; then
    echo "  ✗ Compilation failed"
    exit 1
fi
echo "  ✓ Compilation successful"
echo

# Run the proof
echo "Running PCF Correlation Proof..."
echo "This will:"
echo "  1. Create connections to each Queue Manager"
echo "  2. Create multiple sessions per connection"
echo "  3. Use PCF to query actual MQ connections"
echo "  4. Prove all sessions stay on same QM as parent"
echo

# Run with full tracing
java -cp "libs/*:." \
    -Dcom.ibm.msg.client.commonservices.trace.status=ON \
    -Dcom.ibm.msg.client.commonservices.trace.level=9 \
    -Dcom.ibm.mq.trace.status=ON \
    -Dcom.ibm.mq.trace.level=9 \
    PCFCorrelationProof

echo
echo "========================================"
echo " PCF CORRELATION PROOF COMPLETE"
echo "========================================"
echo
echo "Check the following files for evidence:"
echo "  - PCF_JMS_DEBUG_*.log - Full JMS debug trace"
echo "  - PCF_CORRELATION_*.log - PCF verification results"
echo "  - jms_trace_*.log - Low-level JMS trace files"
echo
echo "Key Evidence Points:"
echo "  1. Each JMS Connection creates 1 MQ connection"
echo "  2. Each JMS Session creates 1 additional MQ connection"
echo "  3. All sessions from a parent stay on same QM"
echo "  4. PCF confirms connection grouping by APPTAG"
echo