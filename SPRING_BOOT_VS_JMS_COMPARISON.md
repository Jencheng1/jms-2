# Spring Boot vs Regular JMS Client - Critical Comparison

## Executive Summary
After reviewing both codebases, I found **CRITICAL DIFFERENCES** in how Spring Boot and regular JMS clients extract MQ properties. The Spring Boot code needs alignment with the regular JMS approach.

## Side-by-Side Property Extraction Comparison

### 1. CONNTAG (Connection Tag) - **FIXED**

#### Regular JMS Client (UniformClusterDualConnectionTest.java)
```java
// Uses XMSC constants (older approach but correct)
import com.ibm.msg.client.wmq.common.CommonConstants;

private String getResolvedConnectionTag(Connection connection) {
    Map<String, Object> props = getConnectionProperties(connection);
    String connTag = (String) props.get(XMSC.WMQ_RESOLVED_CONNECTION_TAG);
    return connTag != null ? connTag : "UNKNOWN";
}
```

#### Spring Boot (ConnectionTrackingService.java) - **NOW FIXED**
```java
// BEFORE (WRONG):
Object connTag = context.getObjectProperty(WMQConstants.JMS_IBM_MQMD_CORRELID);

// AFTER (CORRECT):
String fullConnTag = context.getStringProperty(WMQConstants.JMS_IBM_CONNECTION_TAG);
```

**Verdict**: ✅ Now aligned after fix

### 2. CONNECTION_ID

#### Regular JMS Client
```java
private String getConnectionId(Connection connection) {
    Map<String, Object> props = getConnectionProperties(connection);
    String connId = (String) props.get(XMSC.WMQ_CONNECTION_ID);
    return connId != null ? connId : "UNKNOWN";
}
```

#### Spring Boot
```java
private String extractConnectionId(Connection connection) {
    if (connection instanceof MQConnection) {
        MQConnection mqConn = (MQConnection) connection;
        JmsPropertyContext context = mqConn.getPropertyContext();
        return context.getStringProperty(WMQConstants.JMS_IBM_CONNECTION_ID);
    }
    return "UNKNOWN";
}
```

**Verdict**: ✅ Functionally equivalent (XMSC.WMQ_CONNECTION_ID = WMQConstants.JMS_IBM_CONNECTION_ID)

### 3. APPLICATION TAG (APPTAG)

#### Regular JMS Client
```java
// Set during factory creation
factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TRACKING_KEY);
```

#### Spring Boot
```java
// Set during factory creation (MQConfig.java)
factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, 
    applicationName + "-" + System.currentTimeMillis());
```

**Verdict**: ✅ Identical approach

### 4. Queue Manager Resolution

#### Regular JMS Client
```java
private String getExtractedQueueManager(Connection connection) {
    String connId = getConnectionId(connection);
    if (connId != null && connId.length() >= 32) {
        String qmPrefix = connId.substring(0, 32);
        if (qmPrefix.startsWith("414D5143514D31")) return "QM1";
        if (qmPrefix.startsWith("414D5143514D32")) return "QM2";
        if (qmPrefix.startsWith("414D5143514D33")) return "QM3";
    }
    // Fallback to resolved QM
    return getResolvedQueueManager(connection);
}

private String getResolvedQueueManager(Connection connection) {
    Map<String, Object> props = getConnectionProperties(connection);
    String qm = (String) props.get(XMSC.WMQ_RESOLVED_QUEUE_MANAGER);
    return qm != null && !qm.trim().isEmpty() ? qm.trim() : "UNKNOWN";
}
```

#### Spring Boot
```java
private String extractResolvedQueueManager(Connection connection) {
    if (connection instanceof MQConnection) {
        MQConnection mqConn = (MQConnection) connection;
        JmsPropertyContext context = mqConn.getPropertyContext();
        String resolved = context.getStringProperty(WMQConstants.JMS_IBM_RESOLVED_QUEUE_MANAGER);
        if (resolved != null && !resolved.trim().isEmpty()) {
            return resolved.trim();
        }
    }
    return extractQueueManager(connection);
}

// Missing the CONNECTION_ID parsing logic!
```

**Verdict**: ⚠️ Spring Boot missing CONNECTION_ID parsing for QM extraction

### 5. Host and Port

#### Regular JMS Client
```java
// Not explicitly extracted in the test code
```

#### Spring Boot
```java
private String extractHostName(Connection connection) {
    if (connection instanceof MQConnection) {
        MQConnection mqConn = (MQConnection) connection;
        JmsPropertyContext context = mqConn.getPropertyContext();
        return context.getStringProperty(WMQConstants.JMS_IBM_HOST_NAME);
    }
    return "UNKNOWN";
}

private Integer extractPort(Connection connection) {
    if (connection instanceof MQConnection) {
        MQConnection mqConn = (MQConnection) connection;
        JmsPropertyContext context = mqConn.getPropertyContext();
        return context.getIntProperty(WMQConstants.JMS_IBM_PORT);
    }
    return 0;
}
```

**Verdict**: ✅ Spring Boot has additional useful properties

## Critical Issues Found and Fixed

### Issue 1: CONNTAG Extraction (FIXED)
- **Problem**: Used `JMS_IBM_MQMD_CORRELID` instead of `JMS_IBM_CONNECTION_TAG`
- **Impact**: Would not get proper CONNTAG format
- **Fix**: Changed to use `WMQConstants.JMS_IBM_CONNECTION_TAG`

### Issue 2: Missing QM Extraction from CONNECTION_ID
- **Problem**: Spring Boot doesn't parse CONNECTION_ID to extract QM like regular JMS
- **Impact**: May not correctly identify QM in all cases
- **Fix Needed**: Add CONNECTION_ID parsing logic

## Recommended Additional Fix for Spring Boot

Add this method to ConnectionTrackingService.java:

```java
public String getExtractedQueueManager(ConnectionInfo connectionInfo) {
    String connId = connectionInfo.getConnectionId();
    if (connId != null && connId.length() >= 32) {
        String qmPrefix = connId.substring(0, 32);
        // Parse QM from CONNECTION_ID prefix
        if (qmPrefix.startsWith("414D5143514D31")) return "QM1";
        if (qmPrefix.startsWith("414D5143514D32")) return "QM2";
        if (qmPrefix.startsWith("414D5143514D33")) return "QM3";
    }
    // Fallback to resolved QM
    return connectionInfo.getResolvedQueueManager();
}
```

## Property Mapping Reference

| Regular JMS (XMSC) | Spring Boot (WMQConstants) | Purpose | Format |
|-------------------|---------------------------|---------|---------|
| `XMSC.WMQ_RESOLVED_CONNECTION_TAG` | `WMQConstants.JMS_IBM_CONNECTION_TAG` | Full CONNTAG | `MQCT<handle><QM>_<timestamp>` |
| `XMSC.WMQ_CONNECTION_ID` | `WMQConstants.JMS_IBM_CONNECTION_ID` | Connection ID | 48-char hex |
| `XMSC.WMQ_RESOLVED_QUEUE_MANAGER` | `WMQConstants.JMS_IBM_RESOLVED_QUEUE_MANAGER` | QM name | `QM1`, `QM2`, `QM3` |
| N/A | `WMQConstants.WMQ_APPLICATIONNAME` | App tag | Custom string |
| N/A | `WMQConstants.JMS_IBM_HOST_NAME` | Host | IP/hostname |
| N/A | `WMQConstants.JMS_IBM_PORT` | Port | Integer |

## Test Verification Checklist

✅ **CONNTAG Format**
- Must start with `MQCT`
- Must contain QM name
- Must be > 20 characters
- Format: `MQCT<16-char-handle><QM>_<timestamp>`

✅ **CONNECTION_ID Format**
- Must be 48 characters
- Must start with `414D5143` (AMQC in hex)
- Contains encoded QM name

✅ **APPTAG**
- Set via `WMQ_APPLICATIONNAME`
- Visible in MQSC `DIS CONN` output
- Used for correlation

✅ **Parent-Child Inheritance**
- Sessions must inherit parent's CONNECTION_ID
- Sessions must inherit parent's CONNTAG
- Sessions must connect to same QM as parent

## Summary

After fixing the CONNTAG extraction issue, the Spring Boot code is now mostly aligned with the regular JMS client. The main remaining difference is that the regular JMS client has additional logic to parse the Queue Manager name from the CONNECTION_ID prefix, which could be useful as a fallback mechanism.

**Critical fixes applied**:
1. ✅ Changed from `JMS_IBM_MQMD_CORRELID` to `JMS_IBM_CONNECTION_TAG`
2. ✅ Added fallback extraction methods
3. ⚠️ Consider adding CONNECTION_ID parsing for QM extraction (optional enhancement)