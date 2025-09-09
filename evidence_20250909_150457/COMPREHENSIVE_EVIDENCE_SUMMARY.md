# Comprehensive Evidence Summary - Uniform Cluster Dual Connection Test

## Test Execution Details
- **Test Date**: September 9, 2025
- **Evidence Directory**: evidence_20250909_150457
- **Total Iterations**: 5
- **Test Configuration**: 
  - Connection 1: 5 sessions
  - Connection 2: 3 sessions
  - Expected: 10 total MQ connections (2 parent + 8 sessions)

## Critical Evidence Collected

### 1. Queue Manager Distribution Proof

**✅ PROVEN: Connections distribute across different Queue Managers**

| Iteration | Connection 1 (C1) | Connection 2 (C2) | Distribution Result |
|-----------|------------------|------------------|-------------------|
| 1 | QM1 (6 connections) | QM3 (4 connections) | ✅ Different QMs |
| 2 | QM2 (6 connections) | QM1 (4 connections) | ✅ Different QMs |
| 3 | QM1 (6 connections) | QM1 (4 connections) | ❌ Same QM |
| 4 | QM1 (6 connections) | QM3 (4 connections) | ✅ Different QMs |
| 5 | QM2 (6 connections) | QM2 (4 connections) | ❌ Same QM |

**Distribution Success Rate: 60% (3 out of 5 iterations)**

### 2. Parent-Child Affinity Proof

**✅ PROVEN: Child sessions ALWAYS stay with parent connection**

Every iteration consistently shows:
- Connection 1: Exactly 6 MQ connections (1 parent + 5 sessions)
- Connection 2: Exactly 4 MQ connections (1 parent + 3 sessions)

#### Evidence Pattern:
```
Connection 1 (APPLTAG: UNIFORM-xxx-C1):
  When on QM1: 6 connections with EXTCONN(414D5143514D31...)
  When on QM2: 6 connections with EXTCONN(414D5143514D32...)
  When on QM3: Never observed (but would be EXTCONN(414D5143514D33...))

Connection 2 (APPLTAG: UNIFORM-xxx-C2):
  When on QM1: 4 connections with EXTCONN(414D5143514D31...)
  When on QM2: 4 connections with EXTCONN(414D5143514D32...)
  When on QM3: 4 connections with EXTCONN(414D5143514D33...)
```

### 3. APPLTAG Correlation Proof

**✅ PROVEN: APPLTAG successfully groups parent and child connections**

Each iteration uses unique tracking keys:
- Iteration 1: UNIFORM-1757430298349-C1 and UNIFORM-1757430298349-C2
- Iteration 2: UNIFORM-1757430426237-C1 and UNIFORM-1757430426237-C2
- Iteration 3: UNIFORM-1757430554399-C1 and UNIFORM-1757430554399-C2
- Iteration 4: UNIFORM-1757430682143-C1 and UNIFORM-1757430682143-C2
- Iteration 5: UNIFORM-1757430810515-C1 and UNIFORM-1757430810515-C2

All child sessions inherit their parent's APPLTAG.

### 4. EXTCONN Queue Manager Identification

**✅ PROVEN: EXTCONN field identifies Queue Manager**

| Queue Manager | EXTCONN Pattern | Hex Decode |
|---------------|-----------------|------------|
| QM1 | 414D5143514D31202020202020202020 | AMQCQM1 (padded) |
| QM2 | 414D5143514D32202020202020202020 | AMQCQM2 (padded) |
| QM3 | 414D5143514D33202020202020202020 | AMQCQM3 (padded) |

- 414D5143 = "AMQC" (IBM MQ prefix)
- 514D31/32/33 = "QM1"/"QM2"/"QM3"
- Padding with 20 (spaces) to fixed length

### 5. Thread Isolation Evidence

Each connection uses a separate thread:
- Connection 1 typically uses TID 62, 64, 66, etc.
- Connection 2 typically uses TID 63, 65, 67, etc.
- All sessions within a connection share the same TID as their parent

## MQSC Evidence Examples

### Iteration 1 - Connection 1 on QM1 (6 connections):
```
CONN(8A11C06800920040) APPLTAG(UNIFORM-1757430298349-C1) EXTCONN(414D5143514D31...)
CONN(8A11C06800930040) APPLTAG(UNIFORM-1757430298349-C1) EXTCONN(414D5143514D31...)
CONN(8A11C06800940040) APPLTAG(UNIFORM-1757430298349-C1) EXTCONN(414D5143514D31...)
CONN(8A11C06800950040) APPLTAG(UNIFORM-1757430298349-C1) EXTCONN(414D5143514D31...)
CONN(8A11C06800960040) APPLTAG(UNIFORM-1757430298349-C1) EXTCONN(414D5143514D31...)
CONN(8A11C06800970040) APPLTAG(UNIFORM-1757430298349-C1) EXTCONN(414D5143514D31...)
```

### Iteration 1 - Connection 2 on QM3 (4 connections):
```
CONN(B73FC86800010040) APPLTAG(UNIFORM-1757430298349-C2) EXTCONN(414D5143514D33...)
CONN(B73FC86800020040) APPLTAG(UNIFORM-1757430298349-C2) EXTCONN(414D5143514D33...)
CONN(B73FC86800030040) APPLTAG(UNIFORM-1757430298349-C2) EXTCONN(414D5143514D33...)
CONN(B73FC86800040040) APPLTAG(UNIFORM-1757430298349-C2) EXTCONN(414D5143514D33...)
```

## Key Findings Summary

1. **Uniform Cluster Distribution**: ✅ WORKING
   - Connections randomly distribute across QMs
   - 60% achieved different QMs (expected with random selection)

2. **Parent-Child Affinity**: ✅ PERFECT
   - 100% of sessions stayed with parent QM
   - No session ever connected to different QM than parent

3. **APPLTAG Correlation**: ✅ PERFECT
   - Successfully tracked all parent-child relationships
   - Clear separation between Connection 1 and Connection 2

4. **EXTCONN Identification**: ✅ PERFECT
   - Reliably identifies Queue Manager assignment
   - Consistent pattern across all tests

## Conclusion

The test comprehensively proves:

1. **IBM MQ Uniform Cluster with CCDT correctly distributes connections** across available Queue Managers when affinity:none is configured

2. **Child sessions ALWAYS inherit their parent connection's Queue Manager** - this is fundamental MQ behavior where sessions are multiplexed over the parent's physical connection

3. **APPLTAG and EXTCONN provide reliable correlation** between JMS-level abstractions and MQ-level connections

4. **The architecture ensures session affinity** while allowing connection-level distribution, which is critical for transaction integrity

## Evidence Files

All raw evidence is preserved in:
- `evidence_20250909_150457/iteration_*_evidence.txt` - MQSC outputs
- `evidence_20250909_150457/test_*_java.log` - Application logs
- `evidence_20250909_150457/FINAL_EVIDENCE_REPORT.txt` - Summary report