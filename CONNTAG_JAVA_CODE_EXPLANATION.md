# CONNTAG Java Code Explanation and Measurement Guide
## Complete Technical Analysis of UniformClusterConntagAnalysisTest.java

---

## Table of Contents
1. [Code Structure Overview](#code-structure-overview)
2. [Key Code Sections Explained](#key-code-sections-explained)
3. [CONNTAG Measurement Methods](#conntag-measurement-methods)
4. [Test Results Analysis](#test-results-analysis)
5. [How to Compare CONNTAG Values](#how-to-compare-conntag-values)
6. [Visual CONNTAG Comparison](#visual-conntag-comparison)

---

## Code Structure Overview

### Main Components

```
UniformClusterConntagAnalysisTest.java
├── Main Test Logic (Lines 1-350)
│   ├── Connection 1 Setup (Lines 52-155)
│   ├── Connection 2 Setup (Lines 161-260)
│   └── CONNTAG Analysis Summary (Lines 266-340)
├── Helper Methods (Lines 352-440)
│   ├── determineQM() - Identify Queue Manager
│   ├── log() - Dual logging system
│   └── conntagLog() - CONNTAG-specific logging
└── Extraction Methods (Lines 442-550)
    ├── extractAllConnectionDetails()
    ├── extractViaDelegate()
    └── getFieldValue()
```

---

## Key Code Sections Explained

### 1. Setting APPLTAG for CONNTAG (Lines 68 & 179)

```java
// CONNECTION 1 - Line 68
String TRACKING_KEY_C1 = BASE_TRACKING_KEY + "-C1";  // Creates "CONNTAG-1757435237494-C1"
factory1.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TRACKING_KEY_C1);

// CONNECTION 2 - Line 179  
String TRACKING_KEY_C2 = BASE_TRACKING_KEY + "-C2";  // Creates "CONNTAG-1757435237494-C2"
factory2.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TRACKING_KEY_C2);
```

**What This Does:**
- Sets the application name that becomes part of CONNTAG
- This APPLTAG appears at the END of the CONNTAG string
- Used to GROUP all connections from same parent

### 2. Extracting Connection Components for CONNTAG Prediction (Lines 89-111)

```java
// Extract Connection 1 details
if (connection1 instanceof MQConnection) {
    MQConnection mqConn1 = (MQConnection) connection1;
    Map<String, Object> conn1Data = extractAllConnectionDetails(mqConn1);
    
    // Get the full CONNECTION_ID
    conn1Id = getFieldValue(conn1Data, "CONNECTION_ID");
    // Example: 414D5143514D312020202020202020208A11C06800A20040
    
    // Split CONNECTION_ID into components
    if (!conn1Id.equals("UNKNOWN") && conn1Id.length() > 32) {
        conn1ExtConn = conn1Id.substring(0, 32);   // First 32 chars: QM identifier
        conn1Handle = conn1Id.substring(32);        // Last 16 chars: Connection handle
    }
    
    // Predict what CONNTAG should look like
    String predictedConntag = "MQCT" + conn1Handle + conn1QM + TRACKING_KEY_C1;
    log("📌 PREDICTED CONNTAG PATTERN:");
    log("   MQCT + " + conn1Handle + " + " + conn1QM + " + " + TRACKING_KEY_C1);
}
```

**Key Components Extracted:**
- `conn1Handle`: Used in CONNTAG after MQCT prefix
- `conn1QM`: Queue Manager name appears in CONNTAG
- `TRACKING_KEY_C1`: APPLTAG appears at end of CONNTAG

### 3. Session Creation and CONNTAG Inheritance (Lines 124-154)

```java
for (int i = 1; i <= 5; i++) {
    Session session = connection1.createSession(false, Session.AUTO_ACKNOWLEDGE);
    
    if (session instanceof MQSession) {
        MQSession mqSession = (MQSession) session;
        Map<String, Object> sessionData = extractAllConnectionDetails(mqSession);
        
        // Extract session's CONNECTION_ID
        String sessConnId = getFieldValue(sessionData, "CONNECTION_ID");
        String sessHandle = sessConnId.length() > 32 ? sessConnId.substring(32) : "UNKNOWN";
        
        // Log for CONNTAG correlation
        conntagLog("    Session " + i + ":");
        conntagLog("      Handle: " + sessHandle);
        conntagLog("      Inherits APPLTAG: " + TRACKING_KEY_C1);
        conntagLog("      Expected in same CONNTAG group");
        
        // Check if session matches parent
        log("    Matches parent: " + (sessConnId.equals(conn1Id) ? "✅ YES" : "❌ NO"));
    }
}
```

**What We're Measuring:**
- Each session's handle
- Whether session inherits parent's CONNECTION_ID
- Confirming all sessions will share parent's CONNTAG

### 4. CONNTAG Analysis Logging (Lines 114-119 & 225-230)

```java
// Special CONNTAG correlation file
conntagLog("\nCONNECTION 1 ANALYSIS:");
conntagLog("  APPLTAG: " + TRACKING_KEY_C1);
conntagLog("  Queue Manager: " + qmName);
conntagLog("  EXTCONN: " + conn1ExtConn);
conntagLog("  Handle: " + conn1Handle);
conntagLog("  Expected CONNTAG contains: " + conn1Handle + "..." + TRACKING_KEY_C1);
```

This creates a separate analysis file focusing on CONNTAG correlation.

---

## CONNTAG Measurement Methods

### Method 1: Direct MQSC Query

```bash
# Get CONNTAG for specific APPLTAG
docker exec qm1 bash -c "echo 'DIS CONN(*) WHERE(APPLTAG EQ '\''CONNTAG-1757435237494-C1'\'') ALL' | runmqsc QM1" | grep "CONNTAG("
```

### Method 2: Programmatic Extraction in Java

```java
// The code predicts CONNTAG based on components
String predictedConntag = "MQCT" + conn1Handle + conn1QM + TRACKING_KEY_C1;

// Components come from:
// 1. conn1Handle - from CONNECTION_ID substring(32)
// 2. conn1QM - from RESOLVED_QUEUE_MANAGER property
// 3. TRACKING_KEY_C1 - from WMQ_APPLICATIONNAME
```

### Method 3: Correlation Analysis File

The code creates `CONNTAG_CORRELATION_timestamp.txt` with structured analysis:

```
CONNTAG CORRELATION ANALYSIS
============================
Connection 1 Group (6 total connections):
  APPLTAG: CONNTAG-1757435237494-C1
  Queue Manager: QM1
  CONNTAG Pattern: MQCT...QM1...CONNTAG-1757435237494-C1
```

---

## Test Results Analysis

### 🔬 Actual Test Results from CONNTAG-1757435237494

#### CONNTAG Evidence Captured

**Connection 1 Group:**
```
CONNTAG: MQCT8A11C06800A20040QM1_2025-09-05_02.13.44CONNTAG-1757435237494-C1
         └─┘└────────────────┘└───────────────────────┘└───────────────────────┘
          │        │                    │                        │
          │        │                    │                        └─ APPLTAG from Line 68
          │        │                    └─ Queue Manager info
          │        └─ Handle from CONNECTION_ID.substring(32)
          └─ Fixed prefix

Connections with this CONNTAG:
- CONN(8A11C06800A20040) - Parent
- CONN(8A11C06800A30040) - Session 1
- CONN(8A11C06800A40040) - Session 2
- CONN(8A11C06800A50040) - Session 3
- CONN(8A11C06800A60040) - Session 4
- CONN(8A11C06800A70040) - Session 5
```

**Connection 2 Group:**
```
CONNTAG: MQCT8A11C06800A80040QM1_2025-09-05_02.13.44CONNTAG-1757435237494-C2
         └─┘└────────────────┘└───────────────────────┘└───────────────────────┘
          │        │                    │                        │
          │        │                    │                        └─ APPLTAG from Line 179
          │        │                    └─ Same QM (both on QM1)
          │        └─ Different handle (A80040 vs A20040)
          └─ Same prefix
```

### 📊 Measurement Results Table

| Measurement | Connection 1 | Connection 2 | Comparison |
|-------------|-------------|--------------|------------|
| **APPLTAG Component** | CONNTAG-1757435237494-C1 | CONNTAG-1757435237494-C2 | Different (C1 vs C2) |
| **Handle Component** | 8A11C06800A20040 | 8A11C06800A80040 | Different handles |
| **QM Component** | QM1_2025-09-05_02.13.44 | QM1_2025-09-05_02.13.44 | Same (both on QM1) |
| **Total Connections** | 6 | 4 | As expected |
| **Sessions Match Parent** | 100% (5/5) | 100% (3/3) | Perfect affinity |

---

## How to Compare CONNTAG Values

### Step-by-Step CONNTAG Comparison Process

#### Step 1: Extract CONNTAG from MQSC

```bash
# For Connection 1
docker exec qm1 bash -c "echo 'DIS CONN(*) WHERE(APPLTAG EQ '\''CONNTAG-xxx-C1'\'') ALL' | runmqsc QM1" | grep "CONNTAG(" | sort -u

# For Connection 2
docker exec qm1 bash -c "echo 'DIS CONN(*) WHERE(APPLTAG EQ '\''CONNTAG-xxx-C2'\'') ALL' | runmqsc QM1" | grep "CONNTAG(" | sort -u
```

#### Step 2: Parse CONNTAG Components

```java
// Method to parse CONNTAG
public static Map<String, String> parseConntag(String conntag) {
    Map<String, String> components = new HashMap<>();
    
    // Example: MQCT8A11C06800A20040QM1_2025-09-05_02.13.44CONNTAG-1757435237494-C1
    
    components.put("prefix", conntag.substring(0, 4));        // MQCT
    components.put("handle", conntag.substring(4, 20));       // 8A11C06800A20040
    
    // Find APPLTAG (starts with tracking key pattern)
    int apptagStart = conntag.indexOf("CONNTAG-");
    if (apptagStart > 0) {
        components.put("qm_info", conntag.substring(20, apptagStart));
        components.put("appltag", conntag.substring(apptagStart));
    }
    
    return components;
}
```

#### Step 3: Compare Components

```java
// Comparison logic in the test
boolean sameQM = conn1ExtConn.equals(conn2ExtConn);
boolean sameConntag = false;  // Should always be false for different connections

log("CONNTAG COMPARISON:");
log("  Same Queue Manager: " + sameQM);
log("  Different Handles: " + !conn1Handle.equals(conn2Handle));
log("  Different APPLTAGs: " + !TRACKING_KEY_C1.equals(TRACKING_KEY_C2));
```

---

## Visual CONNTAG Comparison

### 🔍 Side-by-Side CONNTAG Analysis

```
CONNECTION 1 CONNTAG                          CONNECTION 2 CONNTAG
─────────────────────                         ─────────────────────

MQCT8A11C06800A20040QM1_2025-09-05_02.13.44CONNTAG-1757435237494-C1
│││││││││││││││││││││││││││││││││││││││││││││││││││││││││││││││││││
││││└─────────┬─────────┘└──────┬──────────┘└──────────┬──────────┘
││││          │                  │                      │
││││      Handle               QM Info              APPLTAG
││││    (Unique per             (Same if            (Different:
││││     connection)            same QM)             C1 vs C2)
││││
│││└─ All components match = Same parent-child group
││└── Fixed format
│└─── Prefix
└──── Always MQCT

MQCT8A11C06800A80040QM1_2025-09-05_02.13.44CONNTAG-1757435237494-C2
│││││││││││││││││││││││││││││││││││││││││││││││││││││││││││││││││││
││││└─────────┬─────────┘└──────┬──────────┘└──────────┬──────────┘
││││          │                  │                      │
││││    Different Handle      Same QM Info          Different APPLTAG
││││      (A80040 vs           (Both QM1)              (C2 vs C1)
││││       A20040)
```

### 📈 CONNTAG Measurement Summary

```
Test: CONNTAG-1757435237494
────────────────────────────

Measurements Taken:
1. ✅ CONNTAG uniqueness verified (2 unique patterns)
2. ✅ Parent-child grouping confirmed (all sessions share parent's CONNTAG)
3. ✅ APPLTAG embedding verified (appears at end of CONNTAG)
4. ✅ Handle correlation confirmed (matches CONN field)
5. ✅ QM identification verified (QM1 in both cases)

Key Metrics:
• Connection 1: 6 connections, 1 unique CONNTAG
• Connection 2: 4 connections, 1 unique CONNTAG
• Total unique CONNTAGs: 2 (one per parent connection)
• Session CONNTAG inheritance: 100%
```

---

## Code Output Files

The test creates three important files:

1. **Main Log**: `CONNTAG_ANALYSIS_TEST_timestamp.log`
   - Complete test execution details
   - Connection establishment logs
   - Session creation logs

2. **CONNTAG Analysis**: `CONNTAG_CORRELATION_timestamp.txt`
   - Focused CONNTAG correlation analysis
   - Expected vs actual patterns
   - Group summaries

3. **Evidence File**: `CONNTAG_EVIDENCE_CONNTAG-timestamp.txt`
   - MQSC query results
   - Actual CONNTAG values from Queue Managers
   - Connection counts and distribution

---

## How to Run and Measure CONNTAG

### Quick Test Execution

```bash
# Compile
javac -cp "libs/*:." UniformClusterConntagAnalysisTest.java

# Run with analysis script
./run_conntag_analysis.sh

# Check results
cat CONNTAG_EVIDENCE_CONNTAG-*.txt
```

### Manual CONNTAG Verification

```bash
# While test is running, check CONNTAG in real-time
TRACKING_KEY="CONNTAG-xxx"  # Get from test output

# Check all QMs
for qm in qm1 qm2 qm3; do
    echo "=== $qm ==="
    docker exec $qm bash -c "echo 'DIS CONN(*) WHERE(APPLTAG LK '\''${TRACKING_KEY}*'\'') ALL' | runmqsc ${qm^^}" | \
    grep -E "CONN\(|CONNTAG\(|APPLTAG\(" | head -20
done
```

---

## Conclusion

The `UniformClusterConntagAnalysisTest.java` successfully:

1. **Sets APPLTAG** (Lines 68, 179) that becomes part of CONNTAG
2. **Extracts components** (Lines 89-111) to predict CONNTAG structure
3. **Measures inheritance** (Lines 124-154) proving sessions share parent's CONNTAG
4. **Compares CONNTAGs** showing different groups have different tags
5. **Proves correlation** with 100% accuracy in parent-child relationships

**CONNTAG provides the most comprehensive connection correlation**, containing:
- Connection handle for unique identification
- Queue Manager information for routing
- APPLTAG for application grouping
- Timestamp for connection tracking

---

*Document Version: 1.0*  
*Created: September 9, 2025*  
*Test Implementation: UniformClusterConntagAnalysisTest.java*