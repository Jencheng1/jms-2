# IBM MQ Uniform Cluster Dual Connection Test - Technical Documentation

## Overview

This document provides comprehensive technical documentation for the `UniformClusterDualConnectionTest.java` program, which demonstrates and proves parent-child connection affinity in IBM MQ Uniform Clusters using CCDT (Client Channel Definition Table).

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [CCDT Configuration](#ccdt-configuration)
3. [Test Program Design](#test-program-design)
4. [Key Technical Components](#key-technical-components)
5. [Evidence Collection Methods](#evidence-collection-methods)
6. [Test Results Analysis](#test-results-analysis)
7. [How to Run Tests](#how-to-run-tests)
8. [Troubleshooting](#troubleshooting)

---

## Architecture Overview

### Test Environment

```
┌─────────────────────────────────────────────────────┐
│                 Java Test Application                │
│                                                       │
│  Connection 1 (C1)          Connection 2 (C2)       │
│  ┌───────────────┐          ┌───────────────┐       │
│  │ 1 Parent      │          │ 1 Parent      │       │
│  │ 5 Sessions    │          │ 3 Sessions    │       │
│  └───────────────┘          └───────────────┘       │
└─────────────────────────────────────────────────────┘
                           │
                           │ CCDT
                           ▼
        ┌──────────────────────────────────────┐
        │         IBM MQ Uniform Cluster        │
        │                                        │
        │   QM1           QM2           QM3     │
        │   10.10.10.10   10.10.10.11   10.10.10.12 │
        │   Port: 1414    Port: 1414    Port: 1414  │
        └──────────────────────────────────────┘
```

### Network Configuration

- **Docker Network**: mq-uniform-cluster_mqnet (10.10.10.0/24)
- **Queue Managers**:
  - QM1: Container `qm1` at 10.10.10.10:1414
  - QM2: Container `qm2` at 10.10.10.11:1414
  - QM3: Container `qm3` at 10.10.10.12:1414

---

## CCDT Configuration

### File Location
`/workspace/ccdt/ccdt.json`

### Configuration Details

```json
{
  "channel": [
    {
      "name": "APP.SVRCONN",
      "type": "clientConnection",
      "clientConnection": {
        "connection": [
          {"host": "10.10.10.10", "port": 1414},  // QM1
          {"host": "10.10.10.11", "port": 1414},  // QM2
          {"host": "10.10.10.12", "port": 1414}   // QM3
        ],
        "queueManager": ""  // Empty = Accept ANY Queue Manager
      },
      "connectionManagement": {
        "affinity": "none",         // No sticky sessions
        "clientWeight": 1,          // Equal distribution weight
        "sharingConversations": 10  // Connection multiplexing
      }
    }
  ]
}
```

### Key CCDT Properties Explained

| Property | Value | Purpose |
|----------|-------|---------|
| `queueManager` | `""` (empty) | Client accepts connection to ANY available QM |
| `affinity` | `"none"` | Each new connection randomly selects a QM |
| `clientWeight` | `1` | All QMs have equal selection probability |
| `connection[]` | 3 entries | List of all available Queue Managers |

### How CCDT Enables Distribution

1. **Random Selection**: With `affinity: none`, each `createConnection()` randomly picks a QM
2. **No Preference**: Empty `queueManager` means no specific QM required
3. **Equal Weight**: All QMs equally likely to be selected
4. **Connection Independence**: Each connection makes its own QM selection

---

## Test Program Design

### UniformClusterDualConnectionTest.java

#### Program Flow

```
1. Initialize
   ├── Create tracking keys (UNIFORM-timestamp-C1, UNIFORM-timestamp-C2)
   └── Set up logging

2. Connection 1
   ├── Create factory with APPLTAG: BASE-C1
   ├── Set CCDT URL and WMQ_QUEUE_MANAGER: "*"
   ├── Create parent connection → Random QM selected
   ├── Extract CONNECTION_ID and EXTCONN
   └── Create 5 child sessions → All inherit parent's QM

3. Connection 2
   ├── Create NEW factory with APPLTAG: BASE-C2
   ├── Set CCDT URL and WMQ_QUEUE_MANAGER: "*"
   ├── Create parent connection → Random QM selected
   ├── Extract CONNECTION_ID and EXTCONN
   └── Create 3 child sessions → All inherit parent's QM

4. Analysis
   ├── Compare EXTCONN values
   ├── Verify parent-child affinity
   └── Generate evidence report
```

#### Key Code Sections

##### Setting Application Tag (Lines 57-72, 166-180)

```java
// Connection 1
String TRACKING_KEY_C1 = BASE_TRACKING_KEY + "-C1";
factory1.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TRACKING_KEY_C1);

// Connection 2
String TRACKING_KEY_C2 = BASE_TRACKING_KEY + "-C2";
factory2.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TRACKING_KEY_C2);
```

**Purpose**: Creates unique APPLTAG visible in MQSC for correlation

##### Enabling Any Queue Manager (Lines 65-71, 173-179)

```java
// Point to CCDT with all 3 QMs
factory.setStringProperty(WMQConstants.WMQ_CCDTURL, "file:///workspace/ccdt/ccdt.json");

// Accept connection to ANY Queue Manager
factory.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "*");
```

**Purpose**: Allows CCDT to randomly select from available QMs

##### Connection ID Extraction (Lines 93-100)

```java
conn1Id = getFieldValue(conn1Data, "CONNECTION_ID");
// Example: 414D5143514D312020202020202020208A11C06800670040

// Extract EXTCONN (QM identifier)
conn1ExtConn = conn1Id.substring(0, 32);
// Result: 414D5143514D31202020202020202020
```

**Purpose**: Extract Queue Manager identifier from connection

---

## Key Technical Components

### 1. CONNECTION_ID Structure

```
414D5143514D312020202020202020208A11C06800670040
└──────┘└──────────────────────┘└────────────────┘
   │              │                      │
   │              │                      └─ Unique Handle (16 chars)
   │              └─ Queue Manager Name (24 chars, padded)
   └─ "AMQC" prefix (8 chars)
```

**Decoding**:
- `414D5143` = "AMQC" in hex
- `514D31202020...` = "QM1" with space padding
- Last 16 chars = Unique connection handle

### 2. EXTCONN Field

The first 32 characters of CONNECTION_ID, used to identify Queue Manager:

| Queue Manager | EXTCONN Value |
|---------------|---------------|
| QM1 | `414D5143514D31202020202020202020` |
| QM2 | `414D5143514D32202020202020202020` |
| QM3 | `414D5143514D33202020202020202020` |

### 3. Session Correlation Method

```java
// For each session
if (session instanceof MQSession) {
    MQSession mqSession = (MQSession) session;
    Map<String, Object> sessionData = extractAllConnectionDetails(mqSession);
    String sessConnId = getFieldValue(sessionData, "CONNECTION_ID");
    
    // Compare with parent
    boolean matchesParent = sessConnId.equals(parentConnId);
}
```

**Correlation Points**:
1. Session CONNECTION_ID equals parent CONNECTION_ID
2. Session EXTCONN equals parent EXTCONN
3. Session inherits parent's APPLTAG

### 4. Field Extraction Methods

The program uses reflection and delegate access to extract internal MQ fields:

```java
extractViaDelegate()     // Access delegate/commonConn fields
extractViaReflection()   // Use Java reflection for private fields
extractViaGetters()      // Call getter methods
extractPropertyMaps()    // Extract internal property maps
```

---

## Evidence Collection Methods

### 1. Java Application Level

- **CONNECTION_ID**: Unique identifier for each connection
- **RESOLVED_QUEUE_MANAGER**: Actual QM name connected to
- **HOST_NAME**: IP address of connected QM
- **APPLTAG**: Application identifier for grouping

### 2. MQSC Level

```bash
# Display connections by APPLTAG
DIS CONN(*) WHERE(APPLTAG EQ 'UNIFORM-timestamp-C1') ALL

# Key fields shown:
# - CONN: Connection handle
# - EXTCONN: External connection ID (QM identifier)
# - APPLTAG: Application tag
# - PID/TID: Process and thread IDs
```

### 3. Correlation Process

```
Java Level                    MQSC Level
──────────                    ──────────
CONNECTION_ID     ────────►   EXTCONN (first 32 chars)
APPLTAG          ────────►   APPLTAG
Session Count    ────────►   Connection Count
```

---

## Test Results Analysis

### Expected Outcomes

| Metric | Connection 1 | Connection 2 |
|--------|-------------|--------------|
| MQ Connections | 6 (1 parent + 5 sessions) | 4 (1 parent + 3 sessions) |
| APPLTAG | UNIFORM-xxx-C1 | UNIFORM-xxx-C2 |
| Queue Manager | Random (QM1/QM2/QM3) | Random (QM1/QM2/QM3) |
| Session Affinity | All on parent's QM | All on parent's QM |

### Success Criteria

1. **Distribution**: Connections should randomly distribute across QMs
2. **Affinity**: All sessions must connect to parent's QM
3. **Correlation**: APPLTAG correctly groups parent-child
4. **Identification**: EXTCONN correctly identifies QM

### Actual Test Results (5 Iterations)

| Iteration | C1 Location | C2 Location | Distribution |
|-----------|-------------|-------------|--------------|
| 1 | QM1 | QM3 | ✅ Different |
| 2 | QM2 | QM1 | ✅ Different |
| 3 | QM1 | QM1 | ❌ Same |
| 4 | QM1 | QM3 | ✅ Different |
| 5 | QM2 | QM2 | ❌ Same |

**Success Rate**: 60% different QMs (expected with random selection)

---

## How to Run Tests

### Prerequisites

1. Docker containers running:
```bash
docker ps | grep -E "qm1|qm2|qm3"
```

2. Required Java libraries in `libs/`:
- `com.ibm.mq.allclient-9.3.5.0.jar`
- `javax.jms-api-2.0.1.jar`
- `json-20231013.jar`

### Running Single Test

```bash
# Compile
javac -cp "libs/*:." UniformClusterDualConnectionTest.java

# Run
docker run --rm --network mq-uniform-cluster_mqnet \
    -v "$(pwd):/app" \
    -v "$(pwd)/libs:/libs" \
    -v "$(pwd)/mq/ccdt:/workspace/ccdt" \
    openjdk:17 java -cp "/app:/libs/*" UniformClusterDualConnectionTest
```

### Running Multiple Iterations with Evidence Collection

```bash
# Make script executable
chmod +x run_comprehensive_evidence_collection.sh

# Run 5 iterations with full evidence
./run_comprehensive_evidence_collection.sh
```

### Monitoring During Test

In a separate terminal:
```bash
# Monitor all QMs
for qm in qm1 qm2 qm3; do
    echo "=== $qm ==="
    docker exec $qm bash -c "echo 'DIS CONN(*) WHERE(CHANNEL EQ APP.SVRCONN) ALL' | runmqsc ${qm^^}" | grep -E "CONN\(|APPLTAG"
done
```

---

## Troubleshooting

### Common Issues and Solutions

#### 1. Both Connections to Same QM
**Issue**: Random selection sometimes picks same QM
**Solution**: This is expected behavior. Run multiple iterations.

#### 2. Authentication Errors
**Issue**: MQRC_NOT_AUTHORIZED
**Solution**: 
```bash
docker exec qm1 bash -c "echo 'ALTER CHANNEL(APP.SVRCONN) CHLTYPE(SVRCONN) MCAUSER('\''mqm'\'')' | runmqsc QM1"
```

#### 3. CCDT Not Found
**Issue**: Cannot find ccdt.json
**Solution**: Ensure volume mount is correct:
```bash
-v "$(pwd)/mq/ccdt:/workspace/ccdt"
```

#### 4. Connection Count Mismatch
**Issue**: More connections than expected
**Solution**: Previous test connections may still be active. Wait or restart QMs.

### Debug Commands

```bash
# Check CCDT contents
cat mq/ccdt/ccdt.json | jq '.'

# View connection details on specific QM
docker exec qm1 bash -c "echo 'DIS CONN(*) ALL' | runmqsc QM1" | less

# Check Queue Manager status
docker exec qm1 bash -c "echo 'DIS QMGR' | runmqsc QM1"

# View channel status
docker exec qm1 bash -c "echo 'DIS CHS(APP.SVRCONN)' | runmqsc QM1"
```

---

## Conclusions

The test successfully demonstrates:

1. **CCDT with `affinity: none` enables random QM selection** - Connections distribute across available Queue Managers

2. **Parent-child affinity is absolute** - Sessions ALWAYS connect to the same QM as their parent connection

3. **APPLTAG provides reliable correlation** - Easy to track parent-child relationships in MQSC

4. **EXTCONN identifies Queue Manager** - First 32 chars of CONNECTION_ID uniquely identify the QM

5. **Architecture ensures transaction integrity** - Sessions can't accidentally connect to different QM than parent

This behavior is fundamental to IBM MQ's connection architecture and ensures that transactional work within a connection remains consistent.

---

## References

- IBM MQ 9.3 Documentation: [Client Channel Definition Table](https://www.ibm.com/docs/en/ibm-mq/9.3?topic=tables-client-channel-definition-table)
- IBM MQ Uniform Clusters: [Overview](https://www.ibm.com/docs/en/ibm-mq/9.3?topic=clusters-uniform)
- WMQConstants JavaDoc: [API Reference](https://www.ibm.com/docs/en/ibm-mq/9.3?topic=applications-developing-jms)

---

*Document Version: 1.0*  
*Last Updated: September 9, 2025*  
*Author: IBM MQ Uniform Cluster Test Team*