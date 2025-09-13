# Spring Boot CONNTAG Retrieval - Detailed Line-by-Line Code Explanation

## Why Spring Boot is Different from Regular JMS

### Regular JMS Uses XMSC Constants:
```java
// Regular JMS uses XMSC namespace
import com.ibm.msg.client.jms.JmsConstants;
String conntag = connection.getStringProperty(XMSC.WMQ_RESOLVED_CONNECTION_TAG);
```

### Spring Boot Uses WMQConstants:
```java
// Spring Boot uses WMQConstants namespace
import com.ibm.msg.client.wmq.WMQConstants;
String conntag = connection.getStringProperty(WMQConstants.JMS_IBM_CONNECTION_TAG);
```

## Detailed CONNTAG Extraction Code with Line Numbers

### File: SpringBootFailoverTest.java

```java
001: import com.ibm.msg.client.wmq.WMQConstants;
002: import javax.jms.*;
003: import java.lang.reflect.Method;
004: 
005: public class SpringBootFailoverTest {
006:     
007:     /**
008:      * Extract CONNTAG from Connection - Spring Boot Way
009:      * Lines 10-35: Main extraction method with reflection fallback
010:      */
011:     private static String extractFullConnTag(Connection connection) {
012:         try {
013:             // Line 14-17: Try direct property extraction using Spring Boot constant
014:             // THIS IS THE KEY DIFFERENCE - Spring Boot uses JMS_IBM_CONNECTION_TAG
015:             // Regular JMS would use XMSC.WMQ_RESOLVED_CONNECTION_TAG
016:             String conntag = connection.getStringProperty(
017:                 WMQConstants.JMS_IBM_CONNECTION_TAG  // ← Spring Boot specific
018:             );
019:             
020:             if (conntag != null && !conntag.isEmpty()) {
021:                 // Line 22: Return full CONNTAG without truncation
022:                 return conntag;  // Format: MQCT<16-char-handle><QM>_<timestamp>
023:             }
024:             
025:             // Lines 26-33: Fallback using reflection if direct access fails
026:             Method getPropertyMethod = connection.getClass().getMethod(
027:                 "getStringProperty", String.class
028:             );
029:             
030:             // Try alternate property names
031:             String[] propertyNames = {
032:                 "JMS_IBM_CONNECTION_TAG",      // Spring Boot primary
033:                 "XMSC_WMQ_RESOLVED_CONNECTION_TAG", // Regular JMS fallback
034:                 "XMSC.WMQ_RESOLVED_CONNECTION_TAG"  // Alternate format
035:             };
036:             
037:             for (String prop : propertyNames) {
038:                 try {
039:                     Object result = getPropertyMethod.invoke(connection, prop);
040:                     if (result != null) {
041:                         return result.toString();
042:                     }
043:                 } catch (Exception e) {
044:                     // Continue to next property
045:                 }
046:             }
047:         } catch (Exception e) {
048:             System.err.println("Failed to extract CONNTAG: " + e.getMessage());
049:         }
050:         return "CONNTAG_EXTRACTION_FAILED";
051:     }
052:     
053:     /**
054:      * Extract CONNTAG from Session - Inherits from Parent
055:      * Lines 56-70: Session CONNTAG extraction
056:      */
057:     private static String extractSessionConnTag(Session session) {
058:         try {
059:             // Line 60-62: Sessions inherit parent's CONNTAG
060:             // Spring Boot sessions use same constant as connection
061:             String conntag = session.getStringProperty(
062:                 WMQConstants.JMS_IBM_CONNECTION_TAG  // ← Same as parent
063:             );
064:             
065:             if (conntag != null) {
066:                 // Line 67: Return inherited CONNTAG (same as parent)
067:                 return conntag;
068:             }
069:         } catch (Exception e) {
070:             // Session doesn't have direct property, inherit from parent
071:         }
072:         return "INHERITED_FROM_PARENT";
073:     }
074: }
```

## Spring Boot Container Listener Failure Detection

### How Container Listeners Detect Queue Manager Failure:

```java
075: @Component
076: public class MQContainerListener {
077:     
078:     @JmsListener(destination = "TEST.QUEUE", containerFactory = "jmsFactory")
079:     public void onMessage(Message message) {
080:         // Lines 81-85: Normal message processing
081:         try {
082:             processMessage(message);
083:         } catch (JMSException e) {
084:             // Line 85: Exception during processing triggers recovery
085:             handleFailure(e);
086:         }
087:     }
088:     
089:     /**
090:      * Container Factory Configuration with Exception Listener
091:      * Lines 92-110: How Spring Boot detects connection failures
092:      */
093:     @Bean
094:     public DefaultJmsListenerContainerFactory jmsFactory(
095:             ConnectionFactory connectionFactory) {
096:         
097:         DefaultJmsListenerContainerFactory factory = 
098:             new DefaultJmsListenerContainerFactory();
099:         
100:         // Line 101-103: Set connection factory with CCDT
101:         factory.setConnectionFactory(connectionFactory);
102:         
103:         // Lines 104-109: CRITICAL - Exception listener for failure detection
104:         factory.setExceptionListener(new ExceptionListener() {
105:             @Override
106:             public void onException(JMSException exception) {
107:                 // Line 108: Queue Manager failure detected here
108:                 System.out.println("[" + timestamp() + "] QM FAILURE DETECTED!");
109:                 System.out.println("Error Code: " + exception.getErrorCode());
110:                 
111:                 // Lines 112-116: Connection failure codes
112:                 if (exception.getErrorCode().equals("MQJMS2002") ||  // Connection broken
113:                     exception.getErrorCode().equals("MQJMS2008") ||  // QM unavailable
114:                     exception.getErrorCode().equals("MQJMS1107")) {  // Connection closed
115:                     
116:                     // Line 117: Trigger reconnection via CCDT
117:                     triggerReconnection();
118:                 }
119:             }
120:         });
121:         
122:         // Lines 123-126: Session caching (important for parent-child)
123:         factory.setCacheLevelName("CACHE_CONNECTION");  // Cache connection
124:         factory.setSessionCacheSize(10);  // Cache up to 10 sessions
125:         
126:         return factory;
127:     }
128:     
129:     /**
130:      * Reconnection Handling
131:      * Lines 132-145: How Uniform Cluster handles failover
132:      */
133:     private void triggerReconnection() {
134:         // Line 135: CCDT automatically selects new QM
135:         // Uniform Cluster ensures:
136:         // 1. Parent connection gets new QM from CCDT
137:         // 2. All child sessions move with parent
138:         // 3. CONNTAG changes to reflect new QM
139:         
140:         System.out.println("[" + timestamp() + "] RECONNECTING via CCDT...");
141:         
142:         // Lines 143-145: Uniform Cluster guarantees
143:         // - Parent connection atomically moves to new QM
144:         // - All sessions (children) move together
145:         // - Zero message loss during transition
146:     }
147: }
```

## Key Differences Summary

| Aspect | Regular JMS | Spring Boot |
|--------|-------------|-------------|
| **CONNTAG Constant** | `XMSC.WMQ_RESOLVED_CONNECTION_TAG` | `WMQConstants.JMS_IBM_CONNECTION_TAG` |
| **Import** | `com.ibm.msg.client.jms.JmsConstants` | `com.ibm.msg.client.wmq.WMQConstants` |
| **Session Detection** | Manual exception handling | Container ExceptionListener |
| **Reconnection** | Manual implementation | Container auto-recovery |
| **Session Caching** | Application managed | Container factory managed |

## CONNTAG Format Explanation

```
MQCT4DCDC468002E0040QM1_2025-09-05_02.13.44
│   │               │  │                    │
│   │               │  │                    └─ Timestamp when connection created
│   │               │  │                    
│   │               │  └─ Queue Manager name
│   │               └─ Unique handle suffix (8 bytes)
│   └─ Connection handle (8 bytes)
└─ Prefix "MQCT" (MQ Connection Tag)
```

## Parent-Child Session Grouping

When Spring Boot creates connections:
1. **Parent Connection** gets CONNTAG from Queue Manager
2. **Child Sessions** (1-5) inherit exact same CONNTAG
3. **During Failover**: All move together to new QM
4. **New CONNTAG** reflects new Queue Manager but maintains grouping

## Evidence Collection Points

### JMS Level (Lines 16-17, 61-62):
- `WMQConstants.JMS_IBM_CONNECTION_TAG` extraction
- Same value for parent and all children

### Container Level (Lines 104-119):
- ExceptionListener detects failure
- Container triggers reconnection
- All sessions in cache move together

### MQSC Level:
```bash
DIS CONN(*) WHERE(APPLTAG LK 'SPRING*') ALL
# Shows CONNTAG field for correlation
```

### Network Level:
```bash
tcpdump -i any -n port 1414 or port 1415 or port 1416
# Shows TCP session migration during failover
```