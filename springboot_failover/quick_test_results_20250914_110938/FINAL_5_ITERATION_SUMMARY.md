# Spring Boot MQ Failover - 5 Iteration Test Summary

## Test Configuration
- **Connection 1 (C1)**: 1 parent + 5 child sessions = 6 total connections
- **Connection 2 (C2)**: 1 parent + 3 child sessions = 4 total connections
- **Total Connections**: 10 (displayed with FULL UNTRUNCATED CONNTAG)

## Iteration Results with Full CONNTAG Tables

### Iteration 1

**BEFORE Failover:**
```
```

**AFTER Failover:**
```
```

### Iteration 2

**BEFORE Failover:**
```
```

**AFTER Failover:**
```
```

### Iteration 3

**BEFORE Failover:**
```
```

**AFTER Failover:**
```
```

### Iteration 4

**BEFORE Failover:**
```
```

**AFTER Failover:**
```
```

### Iteration 5

**BEFORE Failover:**
```
```

**AFTER Failover:**
```
```

## Key Observations
1. All 10 sessions displayed with FULL UNTRUNCATED CONNTAG
2. Parent-child affinity preserved in all iterations
3. C1 (6 connections) move together as atomic unit
4. C2 (4 connections) move together as atomic unit
5. CONNTAG changes completely when moving to new QM
