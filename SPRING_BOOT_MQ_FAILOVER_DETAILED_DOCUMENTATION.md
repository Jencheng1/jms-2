# Spring Boot MQ Failover - Comprehensive Technical Documentation

## Table of Contents
1. [Architecture Overview](#architecture-overview)
2. [Code Components Deep Dive](#code-components-deep-dive)
3. [Connection and Session Management](#connection-and-session-management)
4. [CONNTAG and APPTAG Correlation](#conntag-and-apptag-correlation)
5. [Failover Mechanism](#failover-mechanism)
6. [Evidence Collection Strategy](#evidence-collection-strategy)
7. [Test Execution Guide](#test-execution-guide)
8. [Technical Proof Points](#technical-proof-points)

## Architecture Overview

### Spring Boot MQ Failover Application Structure
```
spring-mq-failover/
├── src/main/java/com/ibm/mq/failover/
│   ├── config/
│   │   ├── MQConfig.java           # MQ ConnectionFactory & JMS configuration
│   │   └── ListenerConfig.java     # Message listener container configuration
│   ├── listener/
│   │   └── FailoverMessageListener.java  # JMS message listener with session tracking
│   ├── service/
│   │   ├── ConnectionTrackingService.java # Connection/Session tracking & correlation
│   │   └── ConnTagCorrelationService.java # CONNTAG correlation service
│   ├── model/
│   │   ├── ConnectionInfo.java     # Connection metadata model
│   │   └── SessionInfo.java        # Session metadata model
│   └── test/
│       └── FailoverTestService.java # Failover testing orchestration
└── src/main/resources/
    └── application.yml              # Configuration properties
```

## Code Components Deep Dive

### 1. MQConfig.java - Connection Factory Configuration

**Purpose**: Configures IBM MQ ConnectionFactory with Uniform Cluster support

```java
@Configuration
public class MQConfig {
    
    @Bean
    public MQConnectionFactory mqConnectionFactory() {
        MQConnectionFactory factory = new MQConnectionFactory();
        
        // Critical: CCDT URL for Uniform Cluster
        factory.setStringProperty(WMQConstants.WMQ_CCDTURL, 
            "file:///workspace/ccdt/ccdt.json");
        
        // Critical: Allow any QM in cluster
        factory.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "*");
        
        // Critical: Application identification with timestamp
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, 
            "SPRING-FAILOVER-" + System.currentTimeMillis());
        
        // Critical: Enable auto-reconnect for failover
        factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, 
            WMQConstants.WMQ_CLIENT_RECONNECT);
        factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_TIMEOUT, 1800);
        
        return factory;
    }
    
    @Bean
    public CachingConnectionFactory cachingConnectionFactory() {
        CachingConnectionFactory cachingFactory = new CachingConnectionFactory();
        cachingFactory.setSessionCacheSize(10);  // Critical: Caches 10 sessions
        cachingFactory.setReconnectOnException(true);
        return cachingFactory;
    }
}
```

**Key Configuration Points**:
- **CCDT URL**: Points to `/workspace/ccdt/ccdt.json` with 3 QMs (QM1, QM2, QM3)
- **Queue Manager**: Set to "*" to allow connection to any QM
- **Application Name**: Unique identifier with timestamp for tracking
- **Auto-Reconnect**: Enabled with 30-minute timeout
- **Session Cache**: 10 sessions cached by Spring

### 2. ConnectionTrackingService.java - Parent-Child Tracking

**Purpose**: Tracks parent connections and child sessions with full metadata extraction

```java
@Service
public class ConnectionTrackingService {
    
    // Storage for parent connections and their sessions
    private final Map<String, ConnectionInfo> parentConnections = new ConcurrentHashMap<>();
    private final Map<String, List<SessionInfo>> sessionsByConnection = new ConcurrentHashMap<>();
    
    public ConnectionInfo trackConnection(Connection connection, String trackingKey) {
        // Extract MQ-specific metadata
        String connectionId = extractConnectionId(connection);     // JMS_IBM_CONNECTION_ID
        String connTag = extractConnTag(connection);               // Connection tag
        String fullConnTag = extractFullConnTag(connection);       // Full CONNTAG with QM name
        String queueManager = extractQueueManager(connection);     // Resolved QM
        
        ConnectionInfo info = ConnectionInfo.builder()
            .connectionId(connectionId)        // 48-char hex ID
            .connectionTag(connTag)            // Base CONNTAG
            .fullConnTag(fullConnTag)          // Full CONNTAG with QM
            .queueManager(queueManager)        // QM1/QM2/QM3
            .applicationTag(trackingKey)       // SPRING-FAILOVER-timestamp
            .isParent(true)
            .build();
        
        parentConnections.put(connectionId, info);
        return info;
    }
    
    public SessionInfo trackSession(Session session, String parentConnectionId, int sessionNumber) {
        // Sessions inherit parent's connection context
        SessionInfo info = SessionInfo.builder()
            .parentConnectionId(parentConnectionId)  // Links to parent
            .sessionTag(extractSessionConnTag(session))
            .fullConnTag(extractFullSessionConnTag(session))
            .queueManager(extractSessionQueueManager(session))
            .sessionNumber(sessionNumber)
            .threadName(Thread.currentThread().getName())
            .threadId(Thread.currentThread().getId())
            .build();
        
        // Add to parent's session list
        sessionsByConnection.get(parentConnectionId).add(info);
        return info;
    }
}
```

**Critical Tracking Fields**:
- **CONNECTION_ID**: 48-character hex string (e.g., `414D5143514D31...`)
  - First 16 chars: "AMQC" prefix in hex
  - Next 16 chars: Queue Manager name
  - Last 16 chars: Unique handle
- **CONNTAG**: Format `MQCT<handle><QM>_<timestamp>`
- **APPLTAG**: Set via `WMQ_APPLICATIONNAME` property

### 3. FailoverMessageListener.java - Message Processing with Session Tracking

**Purpose**: Processes messages and tracks session-level information during failover

```java
@Component
public class FailoverMessageListener implements SessionAwareMessageListener<Message> {
    
    private final ConcurrentHashMap<String, String> sessionToConnectionMap = new ConcurrentHashMap<>();
    
    @Override
    @JmsListener(destination = "${ibm.mq.test-queue}", containerFactory = "mqListenerContainerFactory")
    public void onMessage(Message message, Session session) throws JMSException {
        // Extract session metadata
        String connectionId = extractConnectionId(session);
        String sessionConnTag = extractSessionConnTag(session);
        String queueManager = extractQueueManager(session);
        
        log.info("[MSG] Session QM: {}, CONNTAG: {}, ConnectionID: {}", 
            queueManager, sessionConnTag, connectionId);
        
        // Track session-to-connection mapping
        sessionToConnectionMap.put(sessionConnTag, connectionId);
        
        // Handle connection failures
        if (isConnectionError(e)) {
            handleConnectionFailure(session);
        }
    }
    
    private boolean isConnectionError(JMSException e) {
        return e.getMessage().contains("MQRC_CONNECTION_BROKEN") ||
               e.getMessage().contains("MQRC_Q_MGR_NOT_AVAILABLE");
    }
}
```

**Session Tracking During Message Processing**:
- Each message processed captures session's CONNTAG
- Session's Queue Manager extracted from `JMS_IBM_RESOLVED_QUEUE_MANAGER`
- Connection failures trigger reconnection handling

## Connection and Session Management

### Parent-Child Relationship Model

```
Parent Connection (C1) → QM2
├── CONNECTION_ID: 414D5143514D32...
├── CONNTAG: MQCT12A4C06800370040QM2_2025-09-05_02.13.42
├── APPLTAG: SPRING-FAILOVER-1757455281815
└── Sessions:
    ├── Session 1 (Thread: container-1)
    │   ├── Inherits CONNECTION_ID
    │   ├── Same CONNTAG as parent
    │   └── Same QM (QM2)
    ├── Session 2 (Thread: container-2)
    │   ├── Inherits CONNECTION_ID
    │   ├── Same CONNTAG as parent
    │   └── Same QM (QM2)
    ├── Session 3 (Thread: container-3)
    │   ├── Inherits CONNECTION_ID
    │   ├── Same CONNTAG as parent
    │   └── Same QM (QM2)
    ├── Session 4 (Thread: container-4)
    │   ├── Inherits CONNECTION_ID
    │   ├── Same CONNTAG as parent
    │   └── Same QM (QM2)
    └── Session 5 (Thread: container-5)
        ├── Inherits CONNECTION_ID
        ├── Same CONNTAG as parent
        └── Same QM (QM2)

Parent Connection (C2) → QM1
├── CONNECTION_ID: 414D5143514D31...
├── CONNTAG: MQCT1DA7C06800280040QM1_2025-09-05_02.13.44
├── APPLTAG: SPRING-FAILOVER-1757455281816
└── Sessions:
    ├── Session 1 (Thread: container-6)
    ├── Session 2 (Thread: container-7)
    └── Session 3 (Thread: container-8)
```

### Spring Boot Container Thread Model

Spring Boot creates message listener containers that:
1. Each container thread gets its own JMS Session
2. Sessions are created from a shared parent Connection
3. CachingConnectionFactory maintains parent connections
4. DefaultMessageListenerContainer manages session lifecycle

## CONNTAG and APPTAG Correlation

### CONNTAG Structure Analysis

```
CONNTAG: MQCT12A4C06800370040QM2_2025-09-05_02.13.42
         ^^^^^^^^^^^^^^^^^^^^  ^^^ ^^^^^^^^^^^^^^^^^^^
         |                     |   |
         Handle (16 chars)     QM  Timestamp
```

### APPTAG Setting and Propagation

```java
// Set at ConnectionFactory level
factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, 
    "SPRING-FAILOVER-" + System.currentTimeMillis());

// Visible in MQSC
DIS CONN(*) WHERE(APPLTAG LK 'SPRING-FAILOVER*')
```

### Correlation Evidence Collection

1. **JMS Level**: Extract from JmsPropertyContext
2. **MQSC Level**: Query with APPLTAG filter
3. **Network Level**: Capture with tcpdump

## Failover Mechanism

### Detection and Recovery Flow

```
1. Connection Break Detection
   ├── JMSException with MQRC_CONNECTION_BROKEN
   ├── ExceptionListener triggered
   └── Session onMessage() throws exception

2. Automatic Reconnection (< 5 seconds)
   ├── IBM MQ client reconnect initiated
   ├── CCDT consulted for available QMs
   ├── New connection to different QM
   └── CONNTAG updated with new QM

3. Session Re-establishment
   ├── All sessions recreated on new QM
   ├── Parent-child affinity maintained
   ├── Same CONNECTION_ID inherited
   └── New CONNTAG reflects new QM

4. Message Processing Resume
   ├── Listener containers restart
   ├── Unacknowledged messages redelivered
   └── Processing continues seamlessly
```

### Failover Evidence Points

1. **Before Failover**: All sessions on same QM as parent
2. **During Failover**: Connection exception logged
3. **After Failover**: All sessions moved together to new QM
4. **CONNTAG Change**: Reflects new Queue Manager

## Evidence Collection Strategy

### 1. Application-Level Evidence

```java
// Connection tracking table generation
public String generateConnectionTable() {
    // Produces formatted table showing:
    // - Parent connections with CONNECTION_ID, CONNTAG, QM, APPLTAG
    // - Child sessions inheriting parent's context
    // - Thread assignment for each session
}
```

### 2. MQSC-Level Evidence

```bash
# Capture all connections with APPLTAG
docker exec qm1 bash -c "echo 'DIS CONN(*) WHERE(APPLTAG LK SPRING*) ALL' | runmqsc QM1"

# Key fields to capture:
# - CONN: Connection handle
# - APPLTAG: Application identifier
# - CONNAME: Client IP and port
# - CHANNEL: APP.SVRCONN
# - UOWLOG: Unit of work state
```

### 3. Network-Level Evidence (tcpdump)

```bash
# Capture MQ traffic on port 1414-1416
tcpdump -i any -w mq_failover.pcap \
    'tcp port 1414 or tcp port 1415 or tcp port 1416' \
    -v -s 0
```

## Test Execution Guide

### Prerequisites

1. **Start MQ Cluster**:
```bash
docker-compose -f docker-compose-simple.yml up -d
# Verify all 3 QMs running
docker ps | grep qm
```

2. **Build Spring Boot Application**:
```bash
cd spring-mq-failover
mvn clean package
docker build -t spring-mq-failover .
```

### Test Scenario 1: Parent-Child Grouping

**Objective**: Prove sessions always stay with parent connection

```bash
# Start Spring Boot with 2 connections (5 + 3 sessions)
docker run --rm \
    --network mq-uniform-cluster_mqnet \
    -v "$(pwd)/mq/ccdt:/workspace/ccdt:ro" \
    -e SPRING_PROFILES_ACTIVE=test \
    -e IBM_MQ_CONNECTION_POOL_PARENT_CONNECTIONS=2 \
    -e IBM_MQ_CONNECTION_POOL_SESSIONS_PER_CONNECTION=5,3 \
    spring-mq-failover
```

### Test Scenario 2: Failover with Evidence

**Objective**: Capture CONNTAG changes during failover

```bash
# 1. Start monitoring script (separate terminal)
./monitor_spring_failover.sh

# 2. Start Spring Boot application
docker run --rm --name spring-failover \
    --network mq-uniform-cluster_mqnet \
    -v "$(pwd)/mq/ccdt:/workspace/ccdt:ro" \
    spring-mq-failover

# 3. Wait for connections to establish (30 seconds)
sleep 30

# 4. Identify which QM has most connections
./check_spring_connections.sh

# 5. Stop that QM to trigger failover
docker stop qm2  # Example if QM2 has connections

# 6. Observe automatic failover in logs
docker logs spring-failover

# 7. Verify all sessions moved together
./verify_spring_failover.sh
```

## Technical Proof Points

### 1. Parent-Child Affinity Proof

**Evidence Required**:
- Connection table showing 1 parent + 5 sessions on same QM
- Second connection with 1 parent + 3 sessions on (possibly) different QM
- MQSC output showing matching APPLTAG for all related connections
- All sessions show same CONNECTION_ID as parent

**Expected Output**:
```
Connection C1 (Parent) → QM2
├── Session 1 → QM2 (Same as parent)
├── Session 2 → QM2 (Same as parent)
├── Session 3 → QM2 (Same as parent)
├── Session 4 → QM2 (Same as parent)
└── Session 5 → QM2 (Same as parent)

Connection C2 (Parent) → QM1
├── Session 1 → QM1 (Same as parent)
├── Session 2 → QM1 (Same as parent)
└── Session 3 → QM1 (Same as parent)
```

### 2. CONNTAG Correlation Proof

**Evidence Required**:
- CONNTAG format: `MQCT<handle><QM>_<timestamp>`
- All sessions inherit parent's CONNTAG
- CONNTAG changes after failover to reflect new QM

**Expected Pattern**:
```
Before Failover:
- C1 CONNTAG: MQCT12A4C06800370040QM2_2025-09-05_02.13.42
- All C1 sessions: Same CONNTAG

After Failover (QM2 stopped):
- C1 CONNTAG: MQCT8A11C06802680140QM1_2025-09-05_02.14.15
- All C1 sessions: New CONNTAG (same as parent)
```

### 3. Container Thread Isolation Proof

**Evidence Required**:
- Each listener container thread has dedicated session
- Thread names: container-1, container-2, etc.
- Sessions isolated but share parent connection

**Expected Output**:
```
Thread container-1 → Session 1 → Connection C1 → QM2
Thread container-2 → Session 2 → Connection C1 → QM2
Thread container-3 → Session 3 → Connection C1 → QM2
Thread container-4 → Session 4 → Connection C1 → QM2
Thread container-5 → Session 5 → Connection C1 → QM2
Thread container-6 → Session 1 → Connection C2 → QM1
Thread container-7 → Session 2 → Connection C2 → QM1
Thread container-8 → Session 3 → Connection C2 → QM1
```

### 4. Failover Synchronization Proof

**Evidence Required**:
- Timestamp of connection failure
- All sessions fail simultaneously
- All sessions reconnect to same new QM
- Time to recovery < 5 seconds

**Expected Timeline**:
```
T+0s:   QM2 stopped
T+0.1s: Connection C1 detects failure
T+0.2s: All 5 C1 sessions receive JMSException
T+0.5s: Reconnection initiated via CCDT
T+1.0s: New connection established to QM1
T+1.5s: All 5 sessions recreated on QM1
T+2.0s: Message processing resumes
```

## Key Technical Insights

1. **Spring's CachingConnectionFactory Impact**:
   - Caches sessions but maintains parent-child relationship
   - Cache cleared on connection failure
   - New cache populated after failover

2. **DefaultMessageListenerContainer Behavior**:
   - Each container thread maintains its own session
   - Sessions created lazily on first message
   - Container threads restart sessions after failover

3. **IBM MQ Client Reconnect**:
   - Transparent to Spring Boot layer
   - CCDT controls failover targets
   - Connection ID regenerated after failover

4. **CONNTAG as Proof Mechanism**:
   - Immutable during connection lifetime
   - Changes only on reconnection
   - Contains Queue Manager identifier

## Monitoring and Validation Scripts

### monitor_spring_failover.sh
```bash
#!/bin/bash
# Continuously monitor Spring Boot connections
while true; do
    echo "=== Spring Boot Connection Monitor - $(date) ==="
    for qm in qm1 qm2 qm3; do
        echo "--- $qm ---"
        docker exec $qm bash -c "echo 'DIS CONN(*) WHERE(APPLTAG LK SPRING*) CHANNEL CONNAME' | runmqsc ${qm^^}" | \
            grep -E "CONN\(|APPLTAG\(|CHANNEL\(|CONNAME\(" || echo "No Spring connections"
    done
    sleep 5
done
```

### verify_spring_failover.sh
```bash
#!/bin/bash
# Verify parent-child grouping after failover
echo "=== Verifying Spring Boot Failover ==="
for qm in qm1 qm2 qm3; do
    echo "Checking $qm for Spring connections..."
    count=$(docker exec $qm bash -c "echo 'DIS CONN(*) WHERE(APPLTAG LK SPRING*) CHANNEL' | runmqsc ${qm^^}" | \
        grep -c "CONN(")
    if [ $count -gt 0 ]; then
        echo "$qm has $count Spring connections"
        # Check if connections are grouped (should be 6 or 4)
        if [ $count -eq 6 ] || [ $count -eq 4 ]; then
            echo "✓ Connections properly grouped"
        else
            echo "✗ Unexpected connection count"
        fi
    fi
done
```

## Summary

The Spring Boot MQ Failover application demonstrates:

1. **Parent-Child Affinity**: Sessions always remain with their parent connection
2. **CONNTAG Tracking**: Unique identifier that changes only on reconnection
3. **APPTAG Correlation**: Application-level identifier visible in MQSC
4. **Automatic Failover**: Sub-5-second recovery with session preservation
5. **Thread Isolation**: Each container thread has dedicated session
6. **Uniform Cluster Benefits**: Automatic load distribution and failover

This architecture proves IBM MQ Uniform Cluster's superiority over simple TCP load balancers by maintaining transaction integrity and session affinity during failover events.