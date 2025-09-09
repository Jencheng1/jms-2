# CONNTAG Evidence for 10 Sessions - Test Run
**Date**: September 9, 2025  
**Time**: 18:54 UTC  
**Tracking Key**: UNIFORM-1757444067807

## Test Configuration
- **Connection 1**: 1 parent + 5 sessions = 6 total connections
- **Connection 2**: 1 parent + 3 sessions = 4 total connections
- **Total**: 10 connections across 2 JMS connections

## Connection Distribution Results
✅ **SUCCESS: Connections distributed to DIFFERENT Queue Managers**
- **Connection 1**: Connected to **QM2** (10.10.10.11)
- **Connection 2**: Connected to **QM1** (10.10.10.10)

## Complete 10-Session Table with CONNTAG

```
#    Type     Conn  Session  CONNECTION_ID                                      CONNTAG                        QM  Host           
------------------------------------------------------------------------------------------------------------------------------------------------------
1    Parent   C1    -        414D5143514D322020202020202020209211C06800D60040   MQCT9211C06800D60040QM2_2025   QM2_2025-09-05_02.13.42   /10.10.10.11   
2    Session  C1    1        414D5143514D322020202020202020209211C06800D60040   MQCT9211C06800D60040QM2_2025   QM2_2025-09-05_02.13.42   /10.10.10.11   
3    Session  C1    2        414D5143514D322020202020202020209211C06800D60040   MQCT9211C06800D60040QM2_2025   QM2_2025-09-05_02.13.42   /10.10.10.11   
4    Session  C1    3        414D5143514D322020202020202020209211C06800D60040   MQCT9211C06800D60040QM2_2025   QM2_2025-09-05_02.13.42   /10.10.10.11   
5    Session  C1    4        414D5143514D322020202020202020209211C06800D60040   MQCT9211C06800D60040QM2_2025   QM2_2025-09-05_02.13.42   /10.10.10.11   
6    Session  C1    5        414D5143514D322020202020202020209211C06800D60040   MQCT9211C06800D60040QM2_2025   QM2_2025-09-05_02.13.42   /10.10.10.11   
7    Parent   C2    -        414D5143514D312020202020202020208A11C06800200140   MQCT8A11C06800200140QM1_2025   QM1_2025-09-05_02.13.44   /10.10.10.10   
8    Session  C2    1        414D5143514D312020202020202020208A11C06800200140   MQCT8A11C06800200140QM1_2025   QM1_2025-09-05_02.13.44   /10.10.10.10   
9    Session  C2    2        414D5143514D312020202020202020208A11C06800200140   MQCT8A11C06800200140QM1_2025   QM1_2025-09-05_02.13.44   /10.10.10.10   
10   Session  C2    3        414D5143514D312020202020202020208A11C06800200140   MQCT8A11C06800200140QM1_2025   QM1_2025-09-05_02.13.44   /10.10.10.10   
```

## CONNTAG Analysis

### Connection 1 Group (QM2)
**CONNTAG**: `MQCT9211C06800D60040QM2_2025-09-05_02.13.42UNIFORM-1757444067807-C1`
- **Shared by**: 6 connections (1 parent + 5 sessions)
- **Queue Manager**: QM2_2025-09-05_02.13.42
- **Host**: 10.10.10.11
- **APPLTAG**: UNIFORM-1757444067807-C1

#### CONNTAG Breakdown:
- `MQCT`: MQ Connection Tag prefix
- `9211C06800D60040`: Connection handle
- `QM2_2025-09-05_02.13.42`: Queue Manager name
- `UNIFORM-1757444067807-C1`: Application tag

### Connection 2 Group (QM1)
**CONNTAG**: `MQCT8A11C06800200140QM1_2025-09-05_02.13.44UNIFORM-1757444067807-C2`
- **Shared by**: 4 connections (1 parent + 3 sessions)
- **Queue Manager**: QM1_2025-09-05_02.13.44
- **Host**: 10.10.10.10
- **APPLTAG**: UNIFORM-1757444067807-C2

#### CONNTAG Breakdown:
- `MQCT`: MQ Connection Tag prefix
- `8A11C06800200140`: Connection handle
- `QM1_2025-09-05_02.13.44`: Queue Manager name
- `UNIFORM-1757444067807-C2`: Application tag

## Key Findings

1. ✅ **CONNTAG Successfully Retrieved**: Using `XMSC_WMQ_RESOLVED_CONNECTION_TAG` field
2. ✅ **Parent-Child Affinity Proven**: All sessions share parent's CONNTAG
3. ✅ **Queue Manager Distribution**: Connections distributed across QM1 and QM2
4. ✅ **10 Total Connections**: 6 on QM2 (C1) + 4 on QM1 (C2) = 10 total

## CONNECTION_ID Analysis

### Connection 1 (QM2)
- **CONNECTION_ID**: `414D5143514D322020202020202020209211C06800D60040`
  - Prefix: `414D5143514D3220...` = "AMQCQM2 " (QM2 identifier)
  - All 6 connections share this exact CONNECTION_ID

### Connection 2 (QM1)
- **CONNECTION_ID**: `414D5143514D312020202020202020208A11C06800200140`
  - Prefix: `414D5143514D3120...` = "AMQCQM1 " (QM1 identifier)
  - All 4 connections share this exact CONNECTION_ID

## Technical Implementation Notes

The CONNTAG was retrieved using the following approach:
1. Primary field: `XMSC_WMQ_RESOLVED_CONNECTION_TAG`
2. Fallback fields: `RESOLVED_CONNECTION_TAG`, `CONNTAG`, `CONNECTION_TAG`
3. If not found, constructed from CONNECTION_ID + Queue Manager name

## Files in Evidence Directory
- `java_output.log` - First test run (both connections on QM2)
- `test_run_2.log` - Second test run (distributed: C1→QM2, C2→QM1)
- `10_SESSIONS_CONNTAG_EVIDENCE.md` - This summary document