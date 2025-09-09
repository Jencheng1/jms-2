# Complete Trace Collection Summary
## Timestamp: 20250909_121033
## Directory: /home/ec2-user/unified/demo5/mq-uniform-cluster/complete_trace_20250909_121033

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

TEST 1: PCF API STATUS
-----------------------
QM1: INQUIRE_Q_MGR=✓ INQUIRE_CONNECTION=✗(3007)
QM2: INQUIRE_Q_MGR=✓ INQUIRE_CONNECTION=✗(3007)
QM3: INQUIRE_Q_MGR=✓ INQUIRE_CONNECTION=✗(3007)

TEST 2: PARENT-CHILD PROOF WITH MQSC
-------------------------------------
APPLTAG: TEST843439

Creating JMS Connection...
  Resolved to: QM1

Before sessions:
  Connections with APPLTAG: 3

Creating 3 sessions...

TEST 3: UNIFORM CLUSTER DISTRIBUTION
-------------------------------------
Creating 6 connections with round-robin distribution...

Connection 1 (tag=DIST1) -> QM1
Connection 2 (tag=DIST2) -> QM2
Connection 3 (tag=DIST3) -> QM3
Connection 4 (tag=DIST4) -> QM1
Connection 5 (tag=DIST5) -> QM2
Connection 6 (tag=DIST6) -> QM3

### Key Findings:
- See individual log files for detailed results
- Check MQSC snapshots for connection evidence
- Review trace files for debugging information

### Generated: Tue Sep  9 12:11:07 UTC 2025
