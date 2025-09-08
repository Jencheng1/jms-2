# IBM MQ PCF (Programmable Command Format) Monitoring Guide

## Overview
PCF provides programmatic access to IBM MQ administrative commands, allowing Java applications to query and manage MQ resources just like RUNMQSC commands, but with structured data responses.

## PCF vs RUNMQSC Comparison

### Key Advantages of PCF
1. **Structured Data**: Returns data in programmatic format (not text)
2. **Real-time Correlation**: Can query MQ while JMS operations are active
3. **Automation**: No need for shell access or text parsing
4. **Integration**: Direct integration with Java applications
5. **Performance**: More efficient than parsing RUNMQSC output

## PCF Command Equivalents

### 1. Display Connections
```
RUNMQSC: DIS CONN(*) WHERE(APPLTAG EQ 'tag') ALL
PCF: MQCMD_INQUIRE_CONNECTION with MQCACF_APPL_TAG filter
```

### 2. Display Channel Status
```
RUNMQSC: DIS CHSTATUS(channel) ALL
PCF: MQCMD_INQUIRE_CHANNEL_STATUS
```

### 3. Display Queue Manager
```
RUNMQSC: DIS QMGR ALL
PCF: MQCMD_INQUIRE_Q_MGR
```

### 4. Display Queue Status
```
RUNMQSC: DIS QSTATUS(queue) ALL
PCF: MQCMD_INQUIRE_Q_STATUS
```

## PCF Architecture

### Components
1. **PCFMessageAgent**: Main interface for sending PCF commands
2. **PCFMessage**: Command request/response messages
3. **MQQueueManager**: Connection to queue manager
4. **CMQCFC Constants**: Command and parameter identifiers

### Connection Flow
```
Java App → PCFMessageAgent → SYSTEM.ADMIN.COMMAND.QUEUE → Queue Manager
         ← PCF Response    ← SYSTEM.ADMIN.REPLY.QUEUE    ← Command Server
```

## Implementation Architecture

### Core Classes

#### 1. PCFMonitor - Main Monitoring Class
- Manages PCF agent lifecycle
- Coordinates monitoring operations
- Handles connection pooling

#### 2. PCFConnectionTracker
- Tracks JMS to MQ connection mapping
- Maintains parent-child relationships
- Correlates APPTAG with connection IDs

#### 3. PCFQueryBuilder
- Builds PCF command messages
- Handles parameter encoding
- Manages filter conditions

#### 4. PCFResponseParser
- Parses PCF responses
- Extracts relevant fields
- Handles error conditions

## Key PCF Parameters for Connection Monitoring

### Connection Attributes (MQCMD_INQUIRE_CONNECTION)
```java
// Request Parameters
MQBACF_CONNECTION_ID      - Connection identifier (48 bytes)
MQBACF_GENERIC_CONNECTION_ID - Wildcard connection ID
MQIACF_CONNECTION_ATTRS   - Attributes to return

// Response Parameters
MQCACH_CHANNEL_NAME       - Channel name
MQCACH_CONNECTION_NAME    - Client connection name (IP:port)
MQCACF_APPL_TAG          - Application tag
MQCACF_USER_IDENTIFIER   - User ID
MQIACF_PROCESS_ID        - Process ID
MQIACF_THREAD_ID         - Thread ID
MQBACF_CONN_TAG          - Connection tag
MQCACH_MCA_USER_ID       - MCA user ID
MQIACH_MSGS_SENT         - Messages sent count
MQIACH_MSGS_RCVD         - Messages received count
```

### Channel Status Attributes (MQCMD_INQUIRE_CHANNEL_STATUS)
```java
MQCACH_CHANNEL_NAME      - Channel name
MQIACH_CHANNEL_STATUS    - Current status
MQIACH_CHANNEL_TYPE      - Channel type
MQCACH_CONNECTION_NAME   - Connection name
MQIACH_MSGS              - Message count
MQIACH_BYTES_SENT        - Bytes sent
MQIACH_BYTES_RECEIVED    - Bytes received
```

### Queue Manager Attributes (MQCMD_INQUIRE_Q_MGR)
```java
MQCA_Q_MGR_NAME          - Queue manager name
MQCA_Q_MGR_IDENTIFIER    - Queue manager ID
MQIA_COMMAND_LEVEL       - Command level
MQIA_PLATFORM            - Platform type
MQIACF_CONNECTION_COUNT  - Current connection count
```

## Parent-Child Correlation Strategy

### 1. JMS Level Tracking
```java
// Parent Connection
Connection connection = factory.createConnection();
String parentId = connection.getClientID();
String appTag = "TRACK-" + System.currentTimeMillis();

// Child Sessions
Session session1 = connection.createSession(...);
Session session2 = connection.createSession(...);
// Each session creates an MQ connection, but shares parent's context
```

### 2. PCF Correlation Query
```java
// Query all connections with our APPTAG
PCFMessage request = new PCFMessage(MQCMD_INQUIRE_CONNECTION);
request.addParameter(MQCACF_APPL_TAG, appTag);

PCFMessage[] responses = agent.send(request);
// Returns all connections (parent + children) with same APPTAG
```

### 3. Identifying Parent vs Child
```java
// Parent connection characteristics:
// - Created first (lowest connection ID)
// - Has MQCNO_GENERATE_CONN_TAG flag
// - May have different connection options

// Child session characteristics:
// - Created after parent
// - Share same APPTAG
// - Same PID/TID as parent
// - Same CONNAME (IP:port)
```

## Data Correlation Fields

### Primary Correlation
1. **APPTAG**: Set via WMQ_APPLICATIONNAME, visible in both JMS and PCF
2. **CONNECTION_ID**: Unique MQ connection identifier
3. **EXTCONN**: Extended connection ID (includes QM name)

### Secondary Correlation
1. **PID/TID**: Process and thread IDs
2. **CONNAME**: Client IP and port
3. **CHANNEL**: Channel name
4. **USERID**: Authentication user

## PCF Error Handling

### Common PCF Errors
```java
MQRC_NOT_AUTHORIZED (2035)     - Insufficient permissions
MQRC_Q_MGR_NOT_AVAILABLE (2059) - Queue manager not running
MQRC_UNKNOWN_OBJECT_NAME (2085) - Invalid queue/channel name
MQRC_COMMAND_FAILED (3008)      - PCF command failed
```

### Required Permissions
- Connect to queue manager
- INQ (inquire) authority on objects
- Access to SYSTEM.ADMIN.* queues
- Display authority on connections/channels

## Performance Considerations

### Best Practices
1. **Reuse PCF Agent**: Don't create new agent for each query
2. **Batch Queries**: Combine multiple queries when possible
3. **Filter Early**: Use WHERE conditions in PCF request
4. **Limit Attributes**: Only request needed attributes
5. **Connection Pooling**: Reuse MQ connections

### Monitoring Impact
- PCF queries are lightweight (< 10ms typical)
- Minimal impact on queue manager
- Safe for production use
- Can be run continuously

## Sample PCF Query Patterns

### Pattern 1: Find All Connections for an Application
```java
PCFMessage request = new PCFMessage(MQCMD_INQUIRE_CONNECTION);
request.addParameter(MQCACF_APPL_TAG, "MyApp");
request.addParameter(MQIACF_CONNECTION_ATTRS, new int[] {
    MQIACF_ALL
});
```

### Pattern 2: Monitor Active Channels
```java
PCFMessage request = new PCFMessage(MQCMD_INQUIRE_CHANNEL_STATUS);
request.addParameter(MQCACH_CHANNEL_NAME, "*");
request.addParameter(MQIACH_CHANNEL_INSTANCE_TYPE, MQOT_CURRENT_CHANNEL);
```

### Pattern 3: Get Queue Manager Statistics
```java
PCFMessage request = new PCFMessage(MQCMD_INQUIRE_Q_MGR);
request.addParameter(MQIACF_Q_MGR_ATTRS, new int[] {
    MQIACF_ALL
});
```

## Correlation Workflow

### Step 1: Establish JMS Connection
```java
// Set identifying APPTAG
factory.setStringProperty(WMQ_APPLICATIONNAME, "CORR-12345");
Connection conn = factory.createConnection();
```

### Step 2: Create Sessions
```java
Session session1 = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
Session session2 = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
```

### Step 3: Query via PCF
```java
PCFMessageAgent agent = new PCFMessageAgent(qmgr);
PCFMessage request = new PCFMessage(MQCMD_INQUIRE_CONNECTION);
request.addParameter(MQCACF_APPL_TAG, "CORR-12345");
PCFMessage[] responses = agent.send(request);
```

### Step 4: Analyze Results
```java
// Process responses to identify:
// - Total connection count (should be 1 + session count)
// - Parent connection (first/lowest ID)
// - Child sessions (subsequent IDs)
// - All share same APPTAG, PID, TID
```

## Advantages for Uniform Cluster Monitoring

### 1. Real-time Affinity Proof
- Query connections while JMS operations active
- See parent-child relationships immediately
- Verify all on same queue manager

### 2. Automated Validation
- No manual RUNMQSC commands needed
- Programmatic verification
- Continuous monitoring possible

### 3. Detailed Metrics
- Message counts per connection
- Byte transfer statistics
- Connection duration
- Channel performance

### 4. Integration Benefits
- Same JVM as application
- No external dependencies
- Works in containerized environments
- Cloud-friendly approach

## Summary

PCF provides powerful programmatic access to MQ administrative data, enabling:
- **Real-time monitoring** of connections and sessions
- **Correlation** between JMS and MQ levels
- **Proof** of parent-child relationships in Uniform Clusters
- **Automation** of monitoring and validation
- **Integration** with Java applications

This approach is superior to RUNMQSC for programmatic monitoring because it provides structured data, requires no text parsing, and can be integrated directly into Java applications for continuous monitoring and validation.