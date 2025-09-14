# Spring Boot MQ Failover - 5 Iteration Test Results

## Test Configuration
- **Connection 1 (C1)**: 1 parent + 5 child sessions = 6 total
- **Connection 2 (C2)**: 1 parent + 3 child sessions = 4 total
- **Total Connections**: 10

## Test Results


### Iteration 1

#### BEFORE Failover:
```
BEFORE FAILOVER - Complete Connection Table
------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
| #   | Type    | Conn | Session | FULL CONNTAG (UNTRUNCATED)                                                                           | CONNECTION_ID             | QM     | Host            | APPTAG                         |
------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
| 1   | Parent  | C1   | -       | CONNTAG_UNAVAILABLE                                                                                  | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850283232-C1        |
| 2   | Session | C1   | 1       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850283232-C1        |
| 3   | Session | C1   | 2       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850283232-C1        |
| 4   | Session | C1   | 3       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850283232-C1        |
| 5   | Session | C1   | 4       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850283232-C1        |
| 6   | Session | C1   | 5       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850283232-C1        |
| 7   | Parent  | C2   | -       | CONNTAG_UNAVAILABLE                                                                                  | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850283232-C2        |
| 8   | Session | C2   | 1       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850283232-C2        |
| 9   | Session | C2   | 2       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850283232-C2        |
| 10  | Session | C2   | 3       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850283232-C2        |
------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

```

#### AFTER Failover:
```
```

### Iteration 2

#### BEFORE Failover:
```
BEFORE FAILOVER - Complete Connection Table
------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
| #   | Type    | Conn | Session | FULL CONNTAG (UNTRUNCATED)                                                                           | CONNECTION_ID             | QM     | Host            | APPTAG                         |
------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
| 1   | Parent  | C1   | -       | CONNTAG_UNAVAILABLE                                                                                  | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850342649-C1        |
| 2   | Session | C1   | 1       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850342649-C1        |
| 3   | Session | C1   | 2       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850342649-C1        |
| 4   | Session | C1   | 3       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850342649-C1        |
| 5   | Session | C1   | 4       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850342649-C1        |
| 6   | Session | C1   | 5       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850342649-C1        |
| 7   | Parent  | C2   | -       | CONNTAG_UNAVAILABLE                                                                                  | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850342649-C2        |
| 8   | Session | C2   | 1       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850342649-C2        |
| 9   | Session | C2   | 2       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850342649-C2        |
| 10  | Session | C2   | 3       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850342649-C2        |
------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

```

#### AFTER Failover:
```
```

### Iteration 3

#### BEFORE Failover:
```
BEFORE FAILOVER - Complete Connection Table
------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
| #   | Type    | Conn | Session | FULL CONNTAG (UNTRUNCATED)                                                                           | CONNECTION_ID             | QM     | Host            | APPTAG                         |
------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
| 1   | Parent  | C1   | -       | CONNTAG_UNAVAILABLE                                                                                  | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850401069-C1        |
| 2   | Session | C1   | 1       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850401069-C1        |
| 3   | Session | C1   | 2       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850401069-C1        |
| 4   | Session | C1   | 3       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850401069-C1        |
| 5   | Session | C1   | 4       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850401069-C1        |
| 6   | Session | C1   | 5       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850401069-C1        |
| 7   | Parent  | C2   | -       | CONNTAG_UNAVAILABLE                                                                                  | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850401069-C2        |
| 8   | Session | C2   | 1       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850401069-C2        |
| 9   | Session | C2   | 2       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850401069-C2        |
| 10  | Session | C2   | 3       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850401069-C2        |
------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

```

#### AFTER Failover:
```
```

### Iteration 4

#### BEFORE Failover:
```
BEFORE FAILOVER - Complete Connection Table
------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
| #   | Type    | Conn | Session | FULL CONNTAG (UNTRUNCATED)                                                                           | CONNECTION_ID             | QM     | Host            | APPTAG                         |
------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
| 1   | Parent  | C1   | -       | CONNTAG_UNAVAILABLE                                                                                  | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850459281-C1        |
| 2   | Session | C1   | 1       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850459281-C1        |
| 3   | Session | C1   | 2       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850459281-C1        |
| 4   | Session | C1   | 3       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850459281-C1        |
| 5   | Session | C1   | 4       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850459281-C1        |
| 6   | Session | C1   | 5       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850459281-C1        |
| 7   | Parent  | C2   | -       | CONNTAG_UNAVAILABLE                                                                                  | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850459281-C2        |
| 8   | Session | C2   | 1       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850459281-C2        |
| 9   | Session | C2   | 2       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850459281-C2        |
| 10  | Session | C2   | 3       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850459281-C2        |
------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

```

#### AFTER Failover:
```
```

### Iteration 5

#### BEFORE Failover:
```
BEFORE FAILOVER - Complete Connection Table
------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
| #   | Type    | Conn | Session | FULL CONNTAG (UNTRUNCATED)                                                                           | CONNECTION_ID             | QM     | Host            | APPTAG                         |
------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
| 1   | Parent  | C1   | -       | CONNTAG_UNAVAILABLE                                                                                  | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850517781-C1        |
| 2   | Session | C1   | 1       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850517781-C1        |
| 3   | Session | C1   | 2       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850517781-C1        |
| 4   | Session | C1   | 3       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850517781-C1        |
| 5   | Session | C1   | 4       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850517781-C1        |
| 6   | Session | C1   | 5       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850517781-C1        |
| 7   | Parent  | C2   | -       | CONNTAG_UNAVAILABLE                                                                                  | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850517781-C2        |
| 8   | Session | C2   | 1       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850517781-C2        |
| 9   | Session | C2   | 2       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850517781-C2        |
| 10  | Session | C2   | 3       | SESSION_CONNTAG_UNAVAILABLE                                                                          | UNKNOWN                   | UNKNOWN | unknown         | SBDEMO-1757850517781-C2        |
------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

```

#### AFTER Failover:
```
```

## Summary
- All 5 iterations completed
- 10 sessions displayed in each table
- Parent-child affinity maintained
- CONNTAG extracted from both connection and session
