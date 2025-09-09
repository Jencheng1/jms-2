# IBM MQ Uniform Cluster Failover Test Analysis

## Test Execution Summary

### Test Configuration
- **Test Date**: September 9, 2025
- **Test Type**: Failover with parent-child CONNTAG preservation
- **Base Tracking Key**: FAILOVER-1757451583749
- **Test Duration**: 3 minutes with failover at 30 seconds

### Initial Connection Distribution

#### Pre-Failover State (All 10 Connections on QM1)
```
CONNECTION 1 (6 connections total):
- Parent Connection ID: 414D5143514D312020202020202020208A11C068002A0140
- Parent CONNTAG: MQCT8A11C068002A0140QM1_2025-09-05_02.13.44FAILOVER-1757451583749-C1
- Queue Manager: QM1
- Host: qm1.mq-uniform-cluster_mqnet/10.10.10.10
- Sessions: 5 (all sharing same CONNECTION_ID and CONNTAG)

CONNECTION 2 (4 connections total):
- Parent Connection ID: 414D5143514D312020202020202020208A11C06800300140
- Parent CONNTAG: MQCT8A11C06800300140QM1_2025-09-05_02.13.44FAILOVER-1757451583749-C2
- Queue Manager: QM1
- Host: qm1.mq-uniform-cluster_mqnet/10.10.10.10
- Sessions: 3 (all sharing same CONNECTION_ID and CONNTAG)
```

### Failover Event
- **Target QM**: QM3 was stopped (but connections were on QM1)
- **Timing**: 30 seconds after test start
- **Expected Behavior**: Automatic reconnection to alternative QM

### Post-Failover State

#### Issue Identified: No Failover Occurred
```
CONNECTION 1 (Post-Failover):
- Connection ID: 414D5143514D312020202020202020208A11C068002A0140 (UNCHANGED)
- CONNTAG: MQCT8A11C068002A0140QM1_2025-09-05_02.13.44FAILOVER-1757451583749-C1 (UNCHANGED)
- Queue Manager: QM1 (UNCHANGED)
- Changed: false

CONNECTION 2 (Post-Failover):
- Connection ID: 414D5143514D312020202020202020208A11C06800300140 (UNCHANGED)
- CONNTAG: MQCT8A11C06800300140QM1_2025-09-05_02.13.44FAILOVER-1757451583749-C2 (UNCHANGED)
- Queue Manager: QM1 (UNCHANGED)
- Changed: false
```

## Key Findings

### 1. Parent-Child Relationship Preservation
✅ **CONFIRMED**: All sessions maintained their parent's CONNTAG throughout the test
- Connection 1: All 5 sessions kept the same CONNTAG as parent
- Connection 2: All 3 sessions kept the same CONNTAG as parent

### 2. CONNTAG Structure Analysis
The CONNTAG format observed:
```
MQCT + [Connection Handle] + [Queue Manager Name] + [Application Tag]

Example:
MQCT8A11C068002A0140QM1_2025-09-05_02.13.44FAILOVER-1757451583749-C1
│    │              │                        │
│    │              │                        └─ Application Tag (APPLTAG)
│    │              └─ Queue Manager Name with timestamp
│    └─ Connection Handle (16 hex chars)
└─ Prefix "MQCT" (MQ Connection Tag)
```

### 3. Failover Behavior

**Issue**: The test stopped QM3 while connections were on QM1, so no actual failover occurred.

**Expected Behavior with Proper Failover**:
1. When QM1 stops, connections should automatically reconnect to QM2 or QM3
2. New CONNECTION_ID would be generated
3. New CONNTAG would reflect the new Queue Manager
4. All sessions would follow their parent to the new QM

### 4. Connection Table Comparison

#### Pre-Failover (10 connections)
| # | Type | Conn | Session | CONNECTION_ID | CONNTAG | QM | Host |
|---|------|------|---------|---------------|---------|----|----|
| 1 | Parent | C1 | - | ...8A11C068002A0140 | MQCT8A11C068002A0140QM1... | QM1 | 10.10.10.10 |
| 2-6 | Session | C1 | 1-5 | ...8A11C068002A0140 | MQCT8A11C068002A0140QM1... | QM1 | 10.10.10.10 |
| 7 | Parent | C2 | - | ...8A11C06800300140 | MQCT8A11C06800300140QM1... | QM1 | 10.10.10.10 |
| 8-10 | Session | C2 | 1-3 | ...8A11C06800300140 | MQCT8A11C06800300140QM1... | QM1 | 10.10.10.10 |

#### Post-Failover (No Change - QM3 was stopped but connections were on QM1)
- All values remained identical
- No reconnection occurred

## Recommendations for Proper Failover Test

1. **Identify Active QM**: Run initial test to confirm which QM has the connections
2. **Stop Correct QM**: Stop the QM that actually has the 6 connections
3. **Monitor Reconnection**: Watch for:
   - Exception listeners triggering
   - CONNECTION_ID changes
   - CONNTAG updates with new QM name
   - All sessions reconnecting with parent

4. **Verify Parent-Child Preservation**: Confirm that:
   - All sessions from C1 reconnect to same QM as C1 parent
   - All sessions from C2 reconnect to same QM as C2 parent
   - CONNTAG reflects new QM but maintains correlation

## Test Configuration Used

### Connection Factory Settings
```java
factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, 
                      WMQConstants.WMQ_CLIENT_RECONNECT);
factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_TIMEOUT, 1800); // 30 minutes
factory.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "*"); // Any QM
```

### CCDT Configuration
- File: `/workspace/ccdt/ccdt.json`
- Contains 3 Queue Managers: QM1, QM2, QM3
- Affinity: none (allows reconnection to any available QM)

## Conclusion

The test successfully demonstrated:
1. ✅ Parent-child CONNTAG relationships are maintained
2. ✅ All 10 connections properly tracked with full CONNTAG values
3. ✅ Sessions always share parent's connection properties

However, actual failover was not triggered because the wrong QM was stopped. A proper retest is needed where:
1. Connections establish to a QM (verify which one)
2. Stop THAT specific QM
3. Monitor automatic reconnection to alternative QM
4. Verify CONNTAG updates while maintaining parent-child relationships