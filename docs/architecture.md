# IBM MQ Uniform Cluster Architecture

## Overview Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        IBM MQ UNIFORM CLUSTER                               │
│                                                                              │
│  ┌──────────────┐     ┌──────────────┐     ┌──────────────┐                │
│  │     QM1      │     │     QM2      │     │     QM3      │                │
│  │ (Full Repo)  │◄────┤ (Full Repo)  │────►│ (Partial)    │                │
│  │  Port:1414   │     │  Port:1415   │     │  Port:1416   │                │
│  └──────┬───────┘     └──────┬───────┘     └──────┬───────┘                │
│         │                     │                     │                        │
│         └─────────────────────┼─────────────────────┘                        │
│                               │                                              │
│                     CLUSTER: UNICLUSTER                                      │
│                   Queue: UNIFORM.QUEUE                                       │
│                                                                              │
└─────────────────────────────────┼───────────────────────────────────────────┘
                                  │
                          ┌───────┴───────┐
                          │     CCDT      │
                          │ (JSON Config) │
                          └───────┬───────┘
                                  │
                    ┌─────────────┼─────────────┐
                    │             │             │
            ┌───────▼────┐ ┌─────▼─────┐ ┌─────▼─────┐
            │ Producer 1 │ │ Producer 2│ │ Producer 3│
            └────────────┘ └───────────┘ └───────────┘
                    │             │             │
            ┌───────▼────┐ ┌─────▼─────┐ ┌─────▼─────┐
            │ Consumer 1 │ │ Consumer 2│ │ Consumer 3│
            └────────────┘ └───────────┘ └───────────┘
```

## Connection Flow Diagram

```
┌────────────────────────────────────────────────────────────────────┐
│                    JMS CONNECTION FLOW                             │
└────────────────────────────────────────────────────────────────────┘

1. Initial Connection:
   JMS Client ──► CCDT ──► Random QM Selection (affinity:none)
                            │
                            ▼
                    ┌───────────────┐
                    │ QM1/QM2/QM3   │
                    └───────────────┘

2. Session Creation:
   Connection ──► Session ──► MessageProducer/Consumer
       │             │              │
       │             │              └──► Queue: UNIFORM.QUEUE
       │             └──► Transactional Context
       └──► Auto-Reconnect Enabled

3. Load Distribution:
   ┌─────────┐     Connection 1     ┌─────┐
   │Client 1 ├────────────────────►│ QM1 │
   └─────────┘                      └─────┘
   
   ┌─────────┐     Connection 2     ┌─────┐
   │Client 2 ├────────────────────►│ QM2 │
   └─────────┘                      └─────┘
   
   ┌─────────┐     Connection 3     ┌─────┐
   │Client 3 ├────────────────────►│ QM3 │
   └─────────┘                      └─────┘

4. Automatic Rebalancing:
   When QM1 overloaded:
   Client 1 ──X──► QM1
            │
            └────► Auto-Reconnect ──► QM2/QM3
```

## Uniform Cluster vs AWS NLB Comparison

```
┌──────────────────────────────────────────────────────────────────┐
│           UNIFORM CLUSTER (MQ-Native)                            │
├──────────────────────────────────────────────────────────────────┤
│                                                                   │
│   Client ──► CCDT ──► [QM1, QM2, QM3] ──► Auto-Balance          │
│                │                                                  │
│                └─► Policies:                                     │
│                    • affinity: none                              │
│                    • clientWeight: 1                             │
│                    • auto-reconnect                              │
│                                                                   │
│   Features:                                                      │
│   ✓ MQ Protocol Aware                                           │
│   ✓ Session/Transaction Aware                                   │
│   ✓ Automatic Rebalancing                                       │
│   ✓ Message-Level Distribution                                  │
│   ✓ Cluster-Wide Visibility                                     │
│                                                                   │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│               AWS NLB (Layer-4)                                  │
├──────────────────────────────────────────────────────────────────┤
│                                                                   │
│   Client ──► NLB ──► [QM1, QM2, QM3] ──X─► No Rebalance        │
│                │                                                  │
│                └─► TCP Load Balancing:                          │
│                    • Round-robin                                 │
│                    • Least connections                          │
│                    • Flow hash                                  │
│                                                                   │
│   Limitations:                                                   │
│   ✗ No MQ Protocol Awareness                                   │
│   ✗ No Session/Transaction Awareness                           │
│   ✗ No Automatic Rebalancing                                   │
│   ✗ TCP Connection Sticky                                       │
│   ✗ No Message-Level Control                                   │
│                                                                   │
└──────────────────────────────────────────────────────────────────┘
```

## MQSC Configuration Flow

```
┌────────────────────────────────────────────────────────────────┐
│                    MQSC SETUP FLOW                             │
└────────────────────────────────────────────────────────────────┘

QM1 (Full Repository):
├── ALTER QMGR REPOS(UNICLUSTER)
├── DEFINE NAMELIST(UNICLUSTER.NL) NAMES(QM1,QM2)
├── DEFINE CHANNEL(TO.QM1) CHLTYPE(CLUSRCVR)
├── DEFINE CHANNEL(TO.QM2) CHLTYPE(CLUSSDR)
├── DEFINE QLOCAL(UNIFORM.QUEUE) CLUSTER(UNICLUSTER)
└── ALTER QMGR CLWLDATA('UniformCluster=true')

QM2 (Full Repository):
├── ALTER QMGR REPOS(UNICLUSTER)
├── DEFINE NAMELIST(UNICLUSTER.NL) NAMES(QM1,QM2)
├── DEFINE CHANNEL(TO.QM2) CHLTYPE(CLUSRCVR)
├── DEFINE CHANNEL(TO.QM1) CHLTYPE(CLUSSDR)
├── DEFINE QLOCAL(UNIFORM.QUEUE) CLUSTER(UNICLUSTER)
└── ALTER QMGR CLWLDATA('UniformCluster=true')

QM3 (Partial Repository):
├── ALTER QMGR REPOS(' ')
├── DEFINE CHANNEL(TO.QM3) CHLTYPE(CLUSRCVR)
├── DEFINE CHANNEL(TO.QM1) CHLTYPE(CLUSSDR)
├── DEFINE QLOCAL(UNIFORM.QUEUE) CLUSTER(UNICLUSTER)
└── ALTER QMGR CLWLDATA('UniformCluster=true')
```

## CCDT Structure

```json
{
  "channel": [{
    "name": "APP.SVRCONN",
    "clientConnection": {
      "connection": [
        {"host": "qm1", "port": 1414},  // Equal weight
        {"host": "qm2", "port": 1414},  // distribution
        {"host": "qm3", "port": 1414}   // across QMs
      ]
    },
    "connectionManagement": {
      "affinity": "none",        // No sticky sessions
      "clientWeight": 1,         // Equal weight
      "reconnect": {
        "enabled": true,         // Auto-reconnect
        "timeout": 1800         // 30 minutes
      }
    }
  }]
}
```

## Session Distribution Pattern

```
Time T0: Initial State (3 Producers, 3 Consumers)
┌────────────────────────────────────────────────┐
│ QM1: Producer-1, Consumer-1 (2 connections)   │
│ QM2: Producer-2, Consumer-2 (2 connections)   │
│ QM3: Producer-3, Consumer-3 (2 connections)   │
└────────────────────────────────────────────────┘

Time T1: QM3 Goes Down
┌────────────────────────────────────────────────┐
│ QM1: Producer-1, Consumer-1, Producer-3       │
│      (3 connections)                           │
│ QM2: Producer-2, Consumer-2, Consumer-3       │
│      (3 connections)                           │
│ QM3: [OFFLINE]                                 │
└────────────────────────────────────────────────┘

Time T2: QM3 Comes Back, Automatic Rebalancing
┌────────────────────────────────────────────────┐
│ QM1: Producer-1, Consumer-2 (2 connections)   │
│ QM2: Producer-2, Consumer-3 (2 connections)   │
│ QM3: Producer-3, Consumer-1 (2 connections)   │
└────────────────────────────────────────────────┘
```

## Key Metrics for Monitoring

```
┌─────────────────────────────────────────────────┐
│           MONITORING POINTS                     │
├─────────────────────────────────────────────────┤
│                                                  │
│ 1. Connection Distribution:                     │
│    • Connections per QM                         │
│    • Standard deviation                         │
│    • Evenness score                            │
│                                                  │
│ 2. Message Distribution:                        │
│    • Queue depth per QM                        │
│    • Message throughput                        │
│    • Processing latency                        │
│                                                  │
│ 3. Cluster Health:                             │
│    • Cluster channel status                    │
│    • Repository synchronization                │
│    • Automatic balancing events                │
│                                                  │
│ 4. Performance Metrics:                        │
│    • Messages/second                           │
│    • Connection establishment time             │
│    • Reconnection frequency                    │
│                                                  │
└─────────────────────────────────────────────────┘
```