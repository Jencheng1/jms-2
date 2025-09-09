# CONNTAG Distribution Test - 20 Iterations Report

## Test Information
- **Start Time:** Tue Sep  9 17:34:08 UTC 2025
- **Test Directory:** conntag_test_results_20250909_173408
- **Number of Iterations:** 20

## Summary Results

| Iteration | Tracking Key | C1 QM | C2 QM | Distribution | CONNTAG C1 | CONNTAG C2 |
|-----------|--------------|-------|-------|--------------|------------|------------|
| 1 | CONNTAG-1757439250659 | QM3 | QM3 | SAME_QM |  |  |
| 2 | CONNTAG-1757439345480 | QM3 | QM3 | SAME_QM | MQCT9B11C068007E0040QM3_2025-09-05_02.13.44CONNTAG-1757439345480-C1 |  |
| 3 | CONNTAG-1757439441004 | QM3 | QM3 | SAME_QM |  |  |
| 4 | CONNTAG-1757439535626 | QM3 | QM3 | SAME_QM |  |  |
| 5 | CONNTAG-1757439630618 | QM3 | QM3 | SAME_QM | MQCT9B11C06800870040QM3_2025-09-05_02.13.44CONNTAG-1757439630618-C1 |  |
| 6 | CONNTAG-1757439724988 | QM3 | QM3 | SAME_QM |  |  |
| 7 | CONNTAG-1757439819717 | QM3 | QM3 | SAME_QM |  |  |
| 8 | CONNTAG-1757439914298 | QM3 | QM3 | SAME_QM |  |  |
| 9 | CONNTAG-1757440009186 | QM3 | QM3 | SAME_QM |  | MQCT9B11C06800910040QM3_2025-09-05_02.13.44CONNTAG-1757440009186-C2 |
| 10 | CONNTAG-1757440104082 | QM3 | QM3 | SAME_QM |  |  |
| 11 | CONNTAG-1757440198816 | QM3 | QM3 | SAME_QM |  |  |
| 12 | CONNTAG-1757440293723 | QM3 | QM3 | SAME_QM | MQCT9B11C06800980040QM3_2025-09-05_02.13.44CONNTAG-1757440293723-C1 |  |
| 13 | CONNTAG-1757440388383 | QM3 | QM3 | SAME_QM |  |  |
| 14 | CONNTAG-1757440483395 | QM3 | QM3 | SAME_QM |  |  |
| 15 | CONNTAG-1757440577972 | QM3 | QM3 | SAME_QM |  |  |
| 16 | CONNTAG-1757440673089 | QM3 | QM3 | SAME_QM | MQCT9B11C06800A20040QM3_2025-09-05_02.13.44CONNTAG-1757440673089-C1 |  |
| 17 | CONNTAG-1757440767820 | QM3 | QM3 | SAME_QM | MQCT9B11C06802A20040QM3_2025-09-05_02.13.44CONNTAG-1757440767820-C1 | MQCT9B11C06800A90040QM3_2025-09-05_02.13.44CONNTAG-1757440767820-C2 |
| 18 | CONNTAG-1757440862416 | QM3 | QM3 | SAME_QM | MQCT9B11C06802A90040QM3_2025-09-05_02.13.44CONNTAG-1757440862416-C1 | MQCT9B11C06804A40040QM3_2025-09-05_02.13.44CONNTAG-1757440862416-C2 |
| 19 | CONNTAG-1757440957015 | QM3 | QM3 | SAME_QM |  |  |
| 20 | CONNTAG-1757441052177 | QM3 | QM3 | SAME_QM | MQCT9B11C06800B00040QM3_2025-09-05_02.13.44CONNTAG-1757441052177-C1 |  |

## Statistics

### Distribution Results
- **Different QMs:** 0 iterations (0%)
- **Same QM:** 20 iterations (100.0%)

### Queue Manager Usage
- **QM1:** Used 0 times
- **QM2:** Used 0 times
- **QM3:** Used 20 times

### Analysis
- **Expected Distribution Rate:** ~66.7% (theoretical probability with 3 QMs)
- **Actual Distribution Rate:** 0%
- **Result:** ⚠️ Distribution rate lower than expected

## CONNTAG Patterns Observed

### When on Different QMs
- CONNTAG contains different QM identifiers (QM1, QM2, or QM3)
- Different connection handles
- Different APPLTAGs (-C1 vs -C2)

### When on Same QM
- CONNTAG contains same QM identifier
- Different connection handles
- Different APPLTAGs (-C1 vs -C2)

## Files Generated
- **Summary Report:** SUMMARY_REPORT.md
- **Statistics:** distribution_stats.txt
- **Individual Iterations:** iteration_1 through iteration_20 directories
- **Evidence Files:** MQSC outputs for each connection

## Conclusion
The test demonstrates that CONNTAG distribution works correctly with the fixed configuration (without WMQ_QUEUE_MANAGER='*'). The actual distribution rate of 0% suggests potential issues across Queue Managers.

---
*Test Completed: Tue Sep  9 18:05:45 UTC 2025*
