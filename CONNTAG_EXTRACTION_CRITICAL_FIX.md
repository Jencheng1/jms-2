# CRITICAL: Correct CONNTAG Extraction in Spring Boot vs Regular JMS

## The Problem

The Spring Boot code is using **INCORRECT** methods to extract the FULL CONNTAG. This is a critical issue because CONNTAG is the key evidence for proving parent-child affinity.

## Wrong Approach (Current Spring Boot Code)

```java
// WRONG - This doesn't give you the full CONNTAG!
private String extractConnTag(Connection connection) throws JMSException {
    if (connection instanceof MQConnection) {
        MQConnection mqConn = (MQConnection) connection;
        JmsPropertyContext context = mqConn.getPropertyContext();
        Object connTag = context.getObjectProperty(WMQConstants.JMS_IBM_MQMD_CORRELID);  // WRONG!
        if (connTag != null) {
            return connTag.toString();
        }
    }
    return "UNKNOWN";
}
```

## Correct Approach (Regular JMS Client)

```java
// CORRECT - This is how regular JMS clients get the FULL CONNTAG
private String extractFullConnTag(Connection connection) throws JMSException {
    if (connection instanceof MQConnection) {
        MQConnection mqConn = (MQConnection) connection;
        JmsPropertyContext context = mqConn.getPropertyContext();
        
        // CORRECT: Use JMS_IBM_CONNECTION_TAG for full CONNTAG
        String fullConnTag = context.getStringProperty(WMQConstants.JMS_IBM_CONNECTION_TAG);
        
        // Alternative: Use XMSC constants if WMQ constants not available
        // String fullConnTag = context.getStringProperty(XMSC.WMQ_RESOLVED_CONNECTION_TAG);
        
        return fullConnTag;  // Returns: MQCT<handle><QM>_<timestamp>
    }
    return "UNKNOWN";
}
```

## Key Differences

### 1. WMQConstants.JMS_IBM_MQMD_CORRELID (WRONG)
- This is the correlation ID from MQMD header
- Used for message correlation, NOT connection tagging
- Returns binary/hex data, not the CONNTAG

### 2. WMQConstants.JMS_IBM_CONNECTION_TAG (CORRECT)
- This is the actual connection tag
- Format: `MQCT<16-char-handle><QM-name>_<timestamp>`
- Example: `MQCT12A4C06800370040QM2_2025-09-05_02.13.42`
- This is what appears in MQSC `DIS CONN` output

### 3. XMSC Constants (Alternative)
The regular JMS test code uses XMSC constants because it's using the older IBM MQ classes:

```java
// In regular JMS test code (UniformClusterDualConnectionTest.java)
import com.ibm.msg.client.wmq.common.CommonConstants;

// Getting CONNTAG using XMSC constants
String connTag = (String) connectionProps.get(XMSC.WMQ_RESOLVED_CONNECTION_TAG);
```

But in Spring Boot with `com.ibm.mq.jms.*` classes, we should use:

```java
// Spring Boot with IBM MQ JMS classes
import com.ibm.msg.client.wmq.WMQConstants;

// Getting CONNTAG using WMQConstants
String connTag = context.getStringProperty(WMQConstants.JMS_IBM_CONNECTION_TAG);
```

## Critical Properties Comparison

| Property | Purpose | Returns | Example |
|----------|---------|---------|---------|
| `WMQConstants.JMS_IBM_CONNECTION_TAG` | Full connection tag | String | `MQCT12A4C06800370040QM2_2025-09-05_02.13.42` |
| `WMQConstants.JMS_IBM_CONNECTION_ID` | Connection identifier | Hex String | `414D5143514D32...` |
| `WMQConstants.JMS_IBM_RESOLVED_QUEUE_MANAGER` | Resolved QM name | String | `QM2` |
| `WMQConstants.JMS_IBM_MQMD_CORRELID` | Message correlation | Binary/Hex | Not for CONNTAG! |
| `XMSC.WMQ_RESOLVED_CONNECTION_TAG` | Full connection tag (XMSC) | String | Same as JMS_IBM_CONNECTION_TAG |

## Why This Matters

1. **Evidence Collection**: We need the FULL CONNTAG to prove parent-child affinity
2. **MQSC Correlation**: The CONNTAG from JMS must match what appears in MQSC output
3. **Failover Proof**: CONNTAG changes when connection moves to different QM
4. **Parent-Child Tracking**: All sessions inherit parent's CONNTAG

## Correct Implementation for Spring Boot

### ConnectionTrackingService.java (FIXED)

```java
private String extractFullConnTag(Connection connection) throws JMSException {
    if (connection instanceof MQConnection) {
        MQConnection mqConn = (MQConnection) connection;
        JmsPropertyContext context = mqConn.getPropertyContext();
        
        // CORRECT: Get the full CONNTAG
        String fullConnTag = context.getStringProperty(WMQConstants.JMS_IBM_CONNECTION_TAG);
        
        if (fullConnTag == null || fullConnTag.isEmpty()) {
            // Fallback: Try alternative property
            fullConnTag = context.getStringProperty("JMS_IBM_CONNECTION_TAG");
        }
        
        return fullConnTag != null ? fullConnTag : "UNKNOWN";
    }
    return "UNKNOWN";
}

private String extractFullSessionConnTag(Session session) throws JMSException {
    if (session instanceof MQSession) {
        MQSession mqSession = (MQSession) session;
        JmsPropertyContext context = mqSession.getPropertyContext();
        
        // CORRECT: Sessions inherit parent's CONNTAG
        String fullConnTag = context.getStringProperty(WMQConstants.JMS_IBM_CONNECTION_TAG);
        
        if (fullConnTag == null || fullConnTag.isEmpty()) {
            // Fallback: Try alternative property
            fullConnTag = context.getStringProperty("JMS_IBM_CONNECTION_TAG");
        }
        
        return fullConnTag != null ? fullConnTag : "UNKNOWN";
    }
    return "UNKNOWN";
}
```

### FailoverMessageListener.java (FIXED)

```java
private String extractSessionConnTag(Session session) {
    try {
        if (session instanceof MQSession) {
            MQSession mqSession = (MQSession) session;
            JmsPropertyContext context = mqSession.getPropertyContext();
            
            // CORRECT: Get the full CONNTAG from session
            String connTag = context.getStringProperty(WMQConstants.JMS_IBM_CONNECTION_TAG);
            
            if (connTag != null && !connTag.isEmpty()) {
                return connTag;
            }
        }
    } catch (Exception e) {
        log.debug("Could not extract session CONNTAG: {}", e.getMessage());
    }
    return "UNKNOWN";
}
```

## Testing the Fix

To verify the fix works correctly:

```java
// Test code to verify CONNTAG extraction
@Test
public void testConnTagExtraction() throws JMSException {
    Connection connection = connectionFactory.createConnection();
    
    if (connection instanceof MQConnection) {
        MQConnection mqConn = (MQConnection) connection;
        JmsPropertyContext context = mqConn.getPropertyContext();
        
        // Get CONNTAG the CORRECT way
        String connTag = context.getStringProperty(WMQConstants.JMS_IBM_CONNECTION_TAG);
        
        System.out.println("Full CONNTAG: " + connTag);
        // Expected: MQCT12A4C06800370040QM2_2025-09-05_02.13.42
        
        // Verify format
        assertTrue(connTag.startsWith("MQCT"));
        assertTrue(connTag.contains("QM"));
        assertTrue(connTag.length() > 20);
    }
}
```

## Summary

**CRITICAL FIX REQUIRED**:
1. Replace `WMQConstants.JMS_IBM_MQMD_CORRELID` with `WMQConstants.JMS_IBM_CONNECTION_TAG`
2. This applies to both Connection and Session extraction
3. The CONNTAG must match what appears in MQSC output
4. Without this fix, we cannot prove parent-child affinity correctly

The regular JMS client code (UniformClusterDualConnectionTest.java) uses the correct approach with XMSC constants. The Spring Boot code must be fixed to use the equivalent WMQConstants for proper CONNTAG extraction.