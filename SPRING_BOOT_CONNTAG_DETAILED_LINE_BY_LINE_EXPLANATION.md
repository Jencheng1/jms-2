# Spring Boot CONNTAG Retrieval - Detailed Line-by-Line Code Explanation

## Why Spring Boot is Different from Regular JMS

### Regular JMS Uses XMSC Constants:
```java
// Regular JMS uses XMSC namespace
import com.ibm.msg.client.jms.JmsConstants;
String conntag = connection.getStringProperty(XMSC.WMQ_RESOLVED_CONNECTION_TAG);
```

### Spring Boot Uses String Literals:
```java
// Spring Boot uses string literal property names
import com.ibm.mq.jms.MQConnection;
MQConnection mqConnection = (MQConnection) connection;
String conntag = mqConnection.getStringProperty("JMS_IBM_CONNECTION_TAG");
```

## Detailed CONNTAG Extraction Code with Line Numbers

### File: SpringBootFailoverTest.java

```java
001: import com.ibm.msg.client.wmq.WMQConstants;
002: import com.ibm.mq.jms.MQConnection;
003: import com.ibm.mq.jms.MQSession;
004: import javax.jms.*;
005: import java.lang.reflect.Method;
006: 
007: public class SpringBootFailoverTest {
008:     
009:     /**
010:      * Extract CONNTAG from Connection - Spring Boot Way
011:      * Lines 11-51: Main extraction method with reflection fallback
012:      */
013:     private static String extractFullConnTag(Connection connection) {
014:         try {
015:             // Lines 16-24: Try direct property extraction using Spring Boot approach
016:             // THIS IS THE KEY DIFFERENCE - Spring Boot uses string literal "JMS_IBM_CONNECTION_TAG"
017:             // Regular JMS would use XMSC.WMQ_RESOLVED_CONNECTION_TAG constant
018:             
019:             // Cast to MQConnection for Spring Boot style access
020:             if (connection instanceof MQConnection) {
021:                 MQConnection mqConnection = (MQConnection) connection;
022:                 
023:                 // Spring Boot way: Use string literal property name
024:                 String conntag = mqConnection.getStringProperty("JMS_IBM_CONNECTION_TAG");
025:                 
026:                 if (conntag != null && !conntag.isEmpty()) {
027:                     // Line 27: Return full CONNTAG without truncation
028:                     return conntag;  // Format: MQCT<16-char-handle><QM>_<timestamp>
029:                 }
030:             }
031:             
032:             // Lines 32-47: Fallback using reflection if direct access fails
033:             Method getPropertyMethod = connection.getClass().getMethod(
034:                 "getStringProperty", String.class
035:             );
036:             
037:             // Try alternate property names - Spring Boot uses string literals
038:             String[] propertyNames = {
039:                 "JMS_IBM_CONNECTION_TAG",           // Spring Boot primary (string literal)
040:                 "XMSC_WMQ_RESOLVED_CONNECTION_TAG", // Regular JMS fallback
041:                 "XMSC.WMQ_RESOLVED_CONNECTION_TAG"  // Alternate format
042:             };
043:             
044:             for (String prop : propertyNames) {
045:                 try {
046:                     Object result = getPropertyMethod.invoke(connection, prop);
047:                     if (result != null) {
048:                         return result.toString();
049:                     }
050:                 } catch (Exception e) {
051:                     // Continue to next property
052:                 }
053:             }
054:         } catch (Exception e) {
055:             System.err.println("Failed to extract CONNTAG: " + e.getMessage());
056:         }
057:         return "CONNTAG_EXTRACTION_FAILED";
058:     }
059:     
060:     /**
061:      * Extract CONNTAG from Session - Inherits from Parent
062:      * Lines 62-80: Session CONNTAG extraction
063:      */
064:     private static String extractSessionConnTag(Session session) {
065:         try {
066:             // Lines 67-71: Sessions inherit parent's CONNTAG
067:             // Spring Boot sessions use same approach as connection
068:             
069:             if (session instanceof MQSession) {
070:                 MQSession mqSession = (MQSession) session;
071:                 
072:                 // Spring Boot way: Use string literal property name
073:                 String conntag = mqSession.getStringProperty("JMS_IBM_CONNECTION_TAG");
074:                 
075:                 if (conntag != null) {
076:                     // Line 76: Return inherited CONNTAG (same as parent)
077:                     return conntag;
078:                 }
079:             }
080:         } catch (Exception e) {
081:             // Session doesn't have direct property, inherit from parent
082:         }
083:         return "INHERITED_FROM_PARENT";
084:     }
085: }
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
| **CONNTAG Property** | `XMSC.WMQ_RESOLVED_CONNECTION_TAG` (constant) | `"JMS_IBM_CONNECTION_TAG"` (string literal) |
| **Connection Cast** | Generic `Connection` | Cast to `MQConnection` |
| **Session Cast** | Generic `Session` | Cast to `MQSession` |
| **Import** | `com.ibm.msg.client.jms.JmsConstants` | `com.ibm.mq.jms.MQConnection/MQSession` |
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