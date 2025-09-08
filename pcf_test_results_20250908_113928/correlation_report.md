# PCF vs RUNMQSC Correlation Report

## Test Details
- **Timestamp**: 20250908_113928
- **APPTAG**: PCF1568
- **JMS Configuration**: 1 Connection + 5 Sessions

## Results Summary

### RUNMQSC Results
- **Connections Found**: 13
- **Query Method**: Text-based MQSC command
- **Data Format**: Text output requiring parsing

### PCF Results (if successful)
- **Query Method**: Programmatic PCF API
- **Data Format**: Structured PCFMessage objects
- **Access Method**: Typed getter methods

## Correlation Proof

1. **Connection Count**: 
   - Expected: 6 (1 parent + 5 children)
   - Found: 13

2. **All connections share**:
   - Same APPTAG: PCF1568
   - Same PID/TID (from same JMS connection)
   - Same Queue Manager: QM1

## PCF Code Advantages

```java
// PCF provides structured access:
String appTag = response.getStringParameterValue(CMQCFC.MQCACF_APPL_TAG);
int pid = response.getIntParameterValue(CMQCFC.MQIACF_PROCESS_ID);

// vs RUNMQSC requiring text parsing:
// grep "APPLTAG(" | awk ...
```

## Conclusion
The test demonstrates that PCF can collect the same information as RUNMQSC
but with programmatic access suitable for automation and real-time correlation.
