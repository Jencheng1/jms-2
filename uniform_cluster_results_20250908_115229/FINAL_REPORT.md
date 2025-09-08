# Uniform Cluster Distribution Test Report

## Test Execution
- **Timestamp**: 20250908_115229
- **CCDT**: ccdt-uniform.json (QM1, QM2, QM3)
- **Connections Created**: 3
- **Sessions per Connection**: 5

## Distribution Results

### Connection Distribution


### Parent-Child Relationships
Each connection maintains parent-child affinity:
- Parent connection selects a Queue Manager randomly via CCDT
- All child sessions remain on the same Queue Manager as parent
- No session-level distribution occurs

## Key Findings

1. **Random Distribution**: CCDT with affinity:none randomly distributes connections
2. **Session Affinity**: Child sessions always stay with parent's QM
3. **Connection Tracking**: Each connection trackable via unique APPTAG
4. **Debug Visibility**: Full trace shows internal JMS/MQ operations

## Files Generated

### Main Outputs
- test_output.log - Main test execution log
- pcf_style_analysis.txt - PCF-style connection analysis

### MQSC Outputs
3 MQSC output files collected

### Trace Files
- uniform_cluster_trace_*.log - Application trace
- mqtrace_*.log - MQ client trace
- mqjavaclient*.trc - Java client trace

## Verification Commands

To verify connections on each Queue Manager:

```bash
# QM1
docker exec qm1 bash -c "echo 'DIS CONN(*) WHERE(CHANNEL EQ APP.SVRCONN)' | runmqsc QM1"

# QM2
docker exec qm2 bash -c "echo 'DIS CONN(*) WHERE(CHANNEL EQ APP.SVRCONN)' | runmqsc QM2"

# QM3
docker exec qm3 bash -c "echo 'DIS CONN(*) WHERE(CHANNEL EQ APP.SVRCONN)' | runmqsc QM3"
```

## Conclusion

The test successfully demonstrates:
- Uniform Cluster distributes connections (not sessions) across Queue Managers
- Parent-child affinity is maintained within each Queue Manager
- Full traceability via APPTAG and debug logging
