# QM1LiveDebugv2 Test Instructions - Maximum Session Debugging

## Overview
QM1LiveDebugv2.java provides exhaustive debugging of the parent-child connection relationship with maximum session data extraction. The test creates 1 parent connection and 5 child sessions to QM1, keeping them alive for 90 seconds for MQSC verification.

## Prerequisites

### 1. Verify Queue Managers are Running
```bash
docker ps | grep qm
```
You should see qm1, qm2, and qm3 running.

### 2. Verify Network
```bash
docker network ls | grep mqnet
```
Should show `mq-uniform-cluster_mqnet`

### 3. Check Libraries
```bash
ls -la libs/*.jar
```
Ensure you have:
- com.ibm.mq.allclient-9.3.5.0.jar
- javax.jms-api-2.0.1.jar
- json-20231013.jar

## Step-by-Step Test Execution

### Step 1: Compile the Test
```bash
javac -cp "libs/*" QM1LiveDebugv2.java
```

Expected output:
```
(no output means successful compilation)
```

### Step 2: Run the Test in Terminal 1
```bash
docker run --rm \
    --network mq-uniform-cluster_mqnet \
    -v "$(pwd):/app" \
    -v "$(pwd)/libs:/libs" \
    -v "$(pwd)/mq/ccdt:/workspace/ccdt" \
    openjdk:17 \
    java -cp "/app:/libs/*" QM1LiveDebugv2
```

The test will:
1. Create a connection to QM1
2. Create 5 sessions from that connection
3. Display the tracking key (e.g., `V2-1757098123456`)
4. Keep connections alive for 90 seconds
5. Create a detailed log file

### Step 3: While Test is Running - Terminal 2

#### A. Get the Tracking Key
Look at the test output for the line:
```
🔑 TRACKING KEY: V2-[timestamp]
```
Use this exact key in the following commands.

#### B. Check Connections with Tracking Key
Replace `V2-XXXXX` with your actual tracking key:
```bash
docker exec qm1 bash -c "echo 'DIS CONN(*) WHERE(APPLTAG EQ '\''V2-XXXXX'\'') ALL' | runmqsc QM1"
```

#### C. Count Connections
```bash
docker exec qm1 bash -c "echo 'DIS CONN(*) WHERE(APPLTAG EQ '\''V2-XXXXX'\'') ALL' | runmqsc QM1" | grep -c "CONN("
```
Should return: **6** (1 parent + 5 sessions)

#### D. Get Detailed Connection Info
```bash
docker exec qm1 bash -c "echo 'DIS CONN(*) WHERE(APPLTAG EQ '\''V2-XXXXX'\'') TYPE(*) CONNOPTS USERID CHANNEL CONNAME' | runmqsc QM1"
```

#### E. Save MQSC Output to File
```bash
TRACKING_KEY="V2-XXXXX"  # Replace with your key
docker exec qm1 bash -c "echo 'DIS CONN(*) WHERE(APPLTAG EQ '\''$TRACKING_KEY'\'') ALL' | runmqsc QM1" > mqsc_output_$TRACKING_KEY.txt
```

### Step 4: Analyze the Results

#### A. Check the Debug Log
The test creates a detailed log file:
```bash
ls -la QM1_DEBUG_V2_*.log
cat QM1_DEBUG_V2_*.log | grep -E "(CONNECTION_ID|QUEUE_MANAGER|SESSION #)"
```

#### B. Verify Parent-Child Relationship
Look for in the log:
```
SESSION #1 CREATION AND ANALYSIS
...
  CONNECTION_ID:
    Parent: 414D5143514D312020202020202020206147BA6800D10740
    Session: 414D5143514D312020202020202020206147BA6800D10740
    Match: ✅ YES
```

#### C. Check MQSC Evidence
In the MQSC output, look for:
1. **6 connections total** with same APPLTAG
2. **Parent connection** identified by `MQCNO_GENERATE_CONN_TAG`
3. **5 child sessions** without that flag
4. All share same **PID** and **TID**

## Complete Test Script

Create `run_v2_test.sh`:
```bash
#!/bin/bash

echo "════════════════════════════════════════════════════════════════"
echo "     QM1LiveDebugv2 - Maximum Session Debug Test"
echo "════════════════════════════════════════════════════════════════"

# Compile
echo "📦 Compiling QM1LiveDebugv2.java..."
javac -cp "libs/*" QM1LiveDebugv2.java
if [ $? -ne 0 ]; then
    echo "❌ Compilation failed!"
    exit 1
fi

# Run test in background
echo "🚀 Starting test..."
docker run --rm -d \
    --name debugv2-test \
    --network mq-uniform-cluster_mqnet \
    -v "$(pwd):/app" \
    -v "$(pwd)/libs:/libs" \
    -v "$(pwd)/mq/ccdt:/workspace/ccdt" \
    openjdk:17 \
    java -cp "/app:/libs/*" QM1LiveDebugv2

# Wait for test to start
sleep 5

# Get tracking key from container logs
TRACKING_KEY=$(docker logs debugv2-test 2>&1 | grep "TRACKING KEY:" | awk '{print $3}')
echo "🔑 Tracking Key: $TRACKING_KEY"

# Capture MQSC data
echo ""
echo "📊 Capturing MQSC Data..."
docker exec qm1 bash -c "echo 'DIS CONN(*) WHERE(APPLTAG EQ '\''$TRACKING_KEY'\'') ALL' | runmqsc QM1" > mqsc_$TRACKING_KEY.txt

# Count connections
COUNT=$(grep -c "CONN(" mqsc_$TRACKING_KEY.txt)
echo "✅ Found $COUNT connections (expected 6)"

# Show summary
echo ""
echo "📁 Files created:"
echo "  - QM1_DEBUG_V2_*.log (detailed debug log)"
echo "  - mqsc_$TRACKING_KEY.txt (MQSC output)"

# Wait for test to complete
docker wait debugv2-test > /dev/null 2>&1
docker rm debugv2-test > /dev/null 2>&1

echo ""
echo "✅ Test completed!"
```

Make it executable:
```bash
chmod +x run_v2_test.sh
./run_v2_test.sh
```

## Expected Evidence

### 1. Debug Log Evidence
The log file will contain:
- Complete connection internal state (~100+ fields)
- Complete session internal state for each of 5 sessions
- Field-by-field comparison between parent and child
- Categorized fields (CONNECTION, QUEUE_MANAGER, SESSION, etc.)

### 2. MQSC Evidence
You should see 6 connections:
```
CONN(6147BA6800D10740) - Parent (has MQCNO_GENERATE_CONN_TAG)
CONN(6147BA6800D20740) - Session 1
CONN(6147BA6800D30740) - Session 2
CONN(6147BA6800D40740) - Session 3
CONN(6147BA6800D50740) - Session 4
CONN(6147BA6800D60740) - Session 5
```

All will share:
- Same APPLTAG (your tracking key)
- Same PID and TID
- Same CONNAME
- Same CHANNEL (APP.SVRCONN)

## Key Fields to Look For

### In Debug Log:
```
XMSC_WMQ_CONNECTION_ID = 414D5143514D312020202020202020206147BA6800D10740
XMSC_WMQ_RESOLVED_QUEUE_MANAGER = QM1
XMSC_WMQ_HOST_NAME = /10.10.10.10
XMSC_WMQ_PORT = 1414
XMSC_WMQ_APPNAME = V2-[timestamp]
```

### In MQSC Output:
```
APPLTAG(V2-[timestamp])
CHANNEL(APP.SVRCONN)
CONNAME(10.10.10.2)
USERID(mqm)
PID([same for all])
TID([same for all])
```

## Troubleshooting

### Connection Fails
```bash
# Check authentication
docker exec qm1 bash -c "echo 'DIS CHLAUTH(APP.SVRCONN)' | runmqsc QM1"
docker exec qm1 bash -c "echo 'DIS CHANNEL(APP.SVRCONN)' | runmqsc QM1"
```

### No Connections Found in MQSC
- Make sure to run MQSC commands while test is still running (within 90 seconds)
- Check the exact tracking key from test output

### Compilation Errors
```bash
# Verify libraries
ls -la libs/*.jar

# If missing, download:
wget https://repo1.maven.org/maven2/com/ibm/mq/com.ibm.mq.allclient/9.3.5.0/com.ibm.mq.allclient-9.3.5.0.jar -O libs/com.ibm.mq.allclient-9.3.5.0.jar
```

## Summary
This test provides the most comprehensive debugging available for parent-child connection relationships in IBM MQ, with:
- 90-second keep-alive for MQSC verification
- Exhaustive field extraction (100+ fields per object)
- Detailed comparison between parent and child
- Categorized field display
- Complete logging to file
- Clear MQSC verification commands