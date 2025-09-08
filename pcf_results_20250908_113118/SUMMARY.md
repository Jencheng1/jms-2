# PCF Monitoring Results

## Test Run: 20250908_113118

### Overview
This test demonstrated using PCF (Programmable Command Format) to monitor IBM MQ connections
and correlate JMS-level operations with MQ-level connections.

### Key Findings
1. PCF provides programmatic access to same information as RUNMQSC
2. PCF can correlate JMS connections with MQ connections in real-time
3. Parent-child relationships are clearly visible through PCF
4. All connections share the same APPTAG for correlation

### PCF vs RUNMQSC
- **PCF**: Structured data, programmatic access, real-time correlation
- **RUNMQSC**: Text output, requires parsing, manual correlation

### Files Generated
pcf_correlation_20250908_113123.log
pcf_correlation_output.log
pcf_demo_output.log
SUMMARY.md

### How to Verify
```bash
# Check the demo output
cat pcf_results_20250908_113118/pcf_demo_output.log

# Check the correlation monitor output
cat pcf_results_20250908_113118/pcf_correlation_output.log
```

### Conclusion
PCF successfully demonstrated that it can collect the same information as RUNMQSC
commands but with better correlation capabilities and programmatic access suitable
for automated monitoring and validation.
