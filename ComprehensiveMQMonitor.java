import com.ibm.mq.*;
import com.ibm.mq.constants.CMQC;
import com.ibm.mq.constants.CMQCFC;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.headers.pcf.*;
import com.ibm.mq.jms.*;
import com.ibm.msg.client.wmq.WMQConstants;

import javax.jms.*;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ComprehensiveMQMonitor {
    private static final String CCDT_URL = "file:///workspace/ccdt/ccdt-qm1.json";
    private static final String QUEUE_MANAGER = "QM1";
    private static final String QUEUE_NAME = "TEST.QUEUE";
    private static final int SESSION_COUNT = 5;
    
    private static PrintWriter logWriter;
    private static String appTag;
    private static String timestamp;
    
    public static void main(String[] args) {
        try {
            // Initialize logging
            timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            appTag = "MONITOR-" + System.currentTimeMillis();
            
            String logFileName = "comprehensive_monitor_" + timestamp + ".log";
            logWriter = new PrintWriter(new FileWriter(logFileName, true));
            
            // Enable ALL MQ trace and debug
            enableComprehensiveTracing();
            
            log("================================================================================");
            log("COMPREHENSIVE MQ MONITOR WITH PCF");
            log("Timestamp: " + timestamp);
            log("Application Tag: " + appTag);
            log("CCDT URL: " + CCDT_URL);
            log("Target Queue Manager: " + QUEUE_MANAGER);
            log("Session Count: " + SESSION_COUNT);
            log("================================================================================\n");
            
            // Create JMS connection with external CCDT
            log("PHASE 1: Creating JMS Connection with External CCDT");
            log("------------------------------------------------");
            
            MQConnectionFactory factory = createConnectionFactory();
            
            // Create connection
            Connection connection = factory.createConnection();
            String connectionId = connection.getClientID();
            
            log("JMS Connection created successfully");
            log("Connection ID: " + connectionId);
            
            // Extract all connection properties
            extractConnectionProperties(connection);
            
            // Start connection
            connection.start();
            log("Connection started\n");
            
            // Create sessions
            log("PHASE 2: Creating " + SESSION_COUNT + " Sessions");
            log("------------------------------------------------");
            
            List<Session> sessions = new ArrayList<>();
            List<MessageProducer> producers = new ArrayList<>();
            
            for (int i = 1; i <= SESSION_COUNT; i++) {
                log("Creating Session " + i + "...");
                Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                sessions.add(session);
                
                // Extract session properties
                extractSessionProperties(session, i);
                
                // Create producer for each session
                javax.jms.Queue queue = session.createQueue(QUEUE_NAME);
                MessageProducer producer = session.createProducer(queue);
                producers.add(producer);
                
                log("Session " + i + " created with producer\n");
            }
            
            // Wait a moment for connections to establish
            Thread.sleep(2000);
            
            // Now use PCF to query MQ for connection information
            log("PHASE 3: PCF Query - Connection Information");
            log("------------------------------------------------");
            
            PCFMessageAgent pcfAgent = createPCFAgent();
            
            // Query connections with our APPTAG
            queryConnectionsWithPCF(pcfAgent);
            
            // Query channel status
            log("\nPHASE 4: PCF Query - Channel Status");
            log("------------------------------------------------");
            queryChannelStatusWithPCF(pcfAgent);
            
            // Query queue manager info
            log("\nPHASE 5: PCF Query - Queue Manager Information");
            log("------------------------------------------------");
            queryQueueManagerWithPCF(pcfAgent);
            
            // Get connection statistics
            log("\nPHASE 6: PCF Query - Connection Statistics");
            log("------------------------------------------------");
            queryConnectionStatistics(pcfAgent);
            
            // Send test messages to verify sessions
            log("\nPHASE 7: Sending Test Messages");
            log("------------------------------------------------");
            for (int i = 0; i < producers.size(); i++) {
                MessageProducer producer = producers.get(i);
                Session session = sessions.get(i);
                
                TextMessage message = session.createTextMessage("Test message from Session " + (i + 1));
                message.setStringProperty("SessionNumber", String.valueOf(i + 1));
                message.setStringProperty("AppTag", appTag);
                producer.send(message);
                
                log("Message sent from Session " + (i + 1));
            }
            
            // Keep connection alive for monitoring
            log("\nPHASE 8: Monitoring Active - Connection kept alive for 60 seconds");
            log("------------------------------------------------");
            log("Run MQSC commands in another terminal to verify:");
            log("  docker exec qm1 bash -c \"echo 'DIS CONN(*) WHERE(APPLTAG EQ " + appTag + ")' | runmqsc QM1\"");
            
            for (int i = 0; i < 6; i++) {
                Thread.sleep(10000);
                log("Active for " + ((i + 1) * 10) + " seconds...");
                
                // Re-query connections every 10 seconds
                if (i % 2 == 1) {
                    log("\n--- Re-querying connections ---");
                    queryConnectionsWithPCF(pcfAgent);
                }
            }
            
            // Cleanup
            log("\nPHASE 9: Cleanup");
            log("------------------------------------------------");
            
            pcfAgent.disconnect();
            
            for (MessageProducer producer : producers) {
                producer.close();
            }
            
            for (Session session : sessions) {
                session.close();
            }
            
            connection.close();
            
            log("All resources closed successfully");
            log("\n================================================================================");
            log("MONITOR COMPLETED SUCCESSFULLY");
            log("Log file: " + logFileName);
            log("================================================================================");
            
        } catch (Exception e) {
            log("ERROR: " + e.getMessage());
            e.printStackTrace(logWriter);
        } finally {
            if (logWriter != null) {
                logWriter.close();
            }
        }
    }
    
    private static void enableComprehensiveTracing() {
        // Enable ALL MQ trace properties
        System.setProperty("com.ibm.mq.cfg.useIBMCipherMappings", "false");
        System.setProperty("javax.net.debug", "all");
        System.setProperty("com.ibm.msg.client.commonservices.trace.enable", "true");
        System.setProperty("com.ibm.msg.client.commonservices.trace.level", "9");
        System.setProperty("com.ibm.msg.client.commonservices.trace.outputName", "mqtrace_" + timestamp + ".log");
        System.setProperty("com.ibm.msg.client.commonservices.trace.status", "ON");
        System.setProperty("com.ibm.msg.client.commonservices.trace.compress", "false");
        System.setProperty("com.ibm.msg.client.commonservices.trace.append", "true");
        System.setProperty("com.ibm.msg.client.commonservices.trace.limit", "104857600"); // 100MB
        System.setProperty("com.ibm.msg.client.commonservices.trace.count", "3");
        
        // Enable JMS trace
        System.setProperty("com.ibm.msg.client.jms.trace.enable", "true");
        System.setProperty("com.ibm.msg.client.jms.trace.level", "9");
        
        // Enable WMQ trace
        System.setProperty("com.ibm.msg.client.wmq.trace.enable", "true");
        System.setProperty("com.ibm.msg.client.wmq.trace.level", "9");
        
        log("Comprehensive tracing enabled:");
        log("  - MQ Common Services trace: Level 9");
        log("  - JMS trace: Level 9");
        log("  - WMQ trace: Level 9");
        log("  - Trace file: mqtrace_" + timestamp + ".log\n");
    }
    
    private static MQConnectionFactory createConnectionFactory() throws JMSException {
        MQConnectionFactory factory = new MQConnectionFactory();
        
        // Use external CCDT
        factory.setStringProperty(WMQConstants.WMQ_CCDTURL, CCDT_URL);
        
        // Set connection mode to use CCDT
        factory.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
        
        // Connect to specific queue manager
        factory.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, QUEUE_MANAGER);
        
        // Set application name for tracking
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, appTag);
        
        // Enable auto-reconnect
        factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, WMQConstants.WMQ_CLIENT_RECONNECT);
        factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_TIMEOUT, 1800);
        
        // Set channel and transport type
        factory.setStringProperty(WMQConstants.WMQ_CHANNEL, "APP.SVRCONN");
        factory.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
        
        // Enable connection pooling
        factory.setBooleanProperty(WMQConstants.WMQ_USE_CONNECTION_POOLING, true);
        
        log("MQConnectionFactory configured:");
        log("  - CCDT URL: " + CCDT_URL);
        log("  - Queue Manager: " + QUEUE_MANAGER);
        log("  - Application Name: " + appTag);
        log("  - Channel: APP.SVRCONN");
        log("  - Auto-reconnect: ENABLED");
        log("  - Connection Pooling: ENABLED\n");
        
        return factory;
    }
    
    private static void extractConnectionProperties(Connection connection) {
        try {
            log("\nConnection Properties:");
            log("----------------------");
            
            if (connection instanceof MQConnection) {
                MQConnection mqConn = (MQConnection) connection;
                
                // Get all possible properties
                log("  Client ID: " + connection.getClientID());
                
                // Try to get connection metadata
                ConnectionMetaData metadata = connection.getMetaData();
                log("  JMS Version: " + metadata.getJMSVersion());
                log("  JMS Major Version: " + metadata.getJMSMajorVersion());
                log("  JMS Minor Version: " + metadata.getJMSMinorVersion());
                log("  Provider Name: " + metadata.getJMSProviderName());
                log("  Provider Version: " + metadata.getProviderVersion());
                
                // Get property names
                Enumeration propNames = metadata.getJMSXPropertyNames();
                log("\n  JMSX Properties:");
                while (propNames.hasMoreElements()) {
                    String propName = (String) propNames.nextElement();
                    log("    - " + propName);
                }
                
                // Try to extract MQ-specific properties using reflection
                log("\n  MQ-Specific Properties (via reflection):");
                extractMQProperties(mqConn);
            }
            
        } catch (Exception e) {
            log("  Error extracting connection properties: " + e.getMessage());
        }
    }
    
    private static void extractSessionProperties(Session session, int sessionNumber) {
        try {
            log("\n  Session " + sessionNumber + " Properties:");
            log("  ------------------------");
            
            if (session instanceof MQSession) {
                MQSession mqSession = (MQSession) session;
                
                log("    Transacted: " + session.getTransacted());
                log("    Acknowledge Mode: " + getAckModeString(session.getAcknowledgeMode()));
                
                // Try to extract MQ-specific session properties using reflection
                extractMQSessionProperties(mqSession, sessionNumber);
            }
            
        } catch (Exception e) {
            log("    Error extracting session properties: " + e.getMessage());
        }
    }
    
    private static void extractMQProperties(MQConnection mqConn) {
        try {
            // Use reflection to get internal properties
            java.lang.reflect.Field[] fields = mqConn.getClass().getDeclaredFields();
            for (java.lang.reflect.Field field : fields) {
                field.setAccessible(true);
                Object value = field.get(mqConn);
                if (value != null) {
                    log("    " + field.getName() + ": " + value.toString());
                }
            }
        } catch (Exception e) {
            log("    Unable to extract via reflection: " + e.getMessage());
        }
    }
    
    private static void extractMQSessionProperties(MQSession mqSession, int sessionNumber) {
        try {
            // Use reflection to get internal session properties
            java.lang.reflect.Field[] fields = mqSession.getClass().getDeclaredFields();
            for (java.lang.reflect.Field field : fields) {
                field.setAccessible(true);
                Object value = field.get(mqSession);
                if (value != null && !field.getName().contains("$")) {
                    log("    " + field.getName() + ": " + value.toString());
                }
            }
        } catch (Exception e) {
            log("    Unable to extract via reflection: " + e.getMessage());
        }
    }
    
    private static PCFMessageAgent createPCFAgent() throws Exception {
        // Create PCF agent to query MQ
        MQQueueManager qmgr = new MQQueueManager(QUEUE_MANAGER);
        PCFMessageAgent agent = new PCFMessageAgent(qmgr);
        
        log("PCF Agent created for Queue Manager: " + QUEUE_MANAGER);
        return agent;
    }
    
    private static void queryConnectionsWithPCF(PCFMessageAgent agent) {
        try {
            log("\nQuerying connections with APPTAG: " + appTag);
            
            // Create PCF request to inquire connections
            PCFMessage request = new PCFMessage(CMQCFC.MQCMD_INQUIRE_CONNECTION);
            
            // Add generic connection name parameter to get all connections
            request.addParameter(CMQCFC.MQBACF_GENERIC_CONNECTION_ID, new byte[48]);
            
            // Set attributes to retrieve
            request.addParameter(CMQCFC.MQIACF_CONNECTION_ATTRS, new int[] {
                CMQCFC.MQIACF_ALL
            });
            
            // Send request
            PCFMessage[] responses = agent.send(request);
            
            log("Found " + responses.length + " connection(s)");
            
            int connCount = 0;
            for (PCFMessage response : responses) {
                String connAppTag = response.getStringParameterValue(CMQCFC.MQCACF_APPL_TAG);
                
                if (connAppTag != null && connAppTag.trim().equals(appTag)) {
                    connCount++;
                    log("\n  Connection #" + connCount + " (Matching APPTAG):");
                    log("  --------------------------------");
                    
                    // Extract all connection details
                    byte[] connId = response.getBytesParameterValue(CMQCFC.MQBACF_CONNECTION_ID);
                    String connIdHex = bytesToHex(connId);
                    log("    Connection ID (hex): " + connIdHex);
                    
                    String channelName = response.getStringParameterValue(CMQCFC.MQCACH_CHANNEL_NAME);
                    log("    Channel Name: " + channelName);
                    
                    String connName = response.getStringParameterValue(CMQCFC.MQCACH_CONNECTION_NAME);
                    log("    Connection Name: " + connName);
                    
                    log("    Application Tag: " + connAppTag);
                    
                    String userId = response.getStringParameterValue(CMQCFC.MQCACF_USER_IDENTIFIER);
                    log("    User ID: " + userId);
                    
                    int pid = response.getIntParameterValue(CMQCFC.MQIACF_PROCESS_ID);
                    log("    Process ID: " + pid);
                    
                    int tid = response.getIntParameterValue(CMQCFC.MQIACF_THREAD_ID);
                    log("    Thread ID: " + tid);
                    
                    // Application type and connection time fields may not be available in all versions
                    
                    // Get message counts
                    try {
                        int msgsReceived = response.getIntParameterValue(CMQCFC.MQIACH_MSGS_RCVD);
                        int msgsSent = response.getIntParameterValue(CMQCFC.MQIACH_MSGS_SENT);
                        log("    Messages Received: " + msgsReceived);
                        log("    Messages Sent: " + msgsSent);
                    } catch (Exception e) {
                        // Fields may not exist
                    }
                }
            }
            
            if (connCount == 0) {
                log("  No connections found with APPTAG: " + appTag);
            } else {
                log("\nTotal connections with our APPTAG: " + connCount);
            }
            
        } catch (Exception e) {
            log("Error querying connections: " + e.getMessage());
            e.printStackTrace(logWriter);
        }
    }
    
    private static void queryChannelStatusWithPCF(PCFMessageAgent agent) {
        try {
            log("\nQuerying channel status for APP.SVRCONN");
            
            // Create PCF request for channel status
            PCFMessage request = new PCFMessage(CMQCFC.MQCMD_INQUIRE_CHANNEL_STATUS);
            request.addParameter(CMQCFC.MQCACH_CHANNEL_NAME, "APP.SVRCONN");
            
            PCFMessage[] responses = agent.send(request);
            
            log("Channel status responses: " + responses.length);
            
            for (int i = 0; i < responses.length; i++) {
                PCFMessage response = responses[i];
                
                log("\n  Channel Instance #" + (i + 1) + ":");
                log("  ---------------------");
                
                String channelName = response.getStringParameterValue(CMQCFC.MQCACH_CHANNEL_NAME);
                log("    Channel Name: " + channelName);
                
                String connName = response.getStringParameterValue(CMQCFC.MQCACH_CONNECTION_NAME);
                log("    Connection Name: " + connName);
                
                int channelStatus = response.getIntParameterValue(CMQCFC.MQIACH_CHANNEL_STATUS);
                log("    Status: " + getChannelStatusString(channelStatus));
                
                int channelType = response.getIntParameterValue(CMQCFC.MQIACH_CHANNEL_TYPE);
                log("    Type: " + getChannelTypeString(channelType));
                
                String mcaUser = response.getStringParameterValue(CMQCFC.MQCACH_MCA_USER_ID);
                log("    MCA User: " + mcaUser);
                
                int msgs = response.getIntParameterValue(CMQCFC.MQIACH_MSGS);
                log("    Messages: " + msgs);
                
                int byteSent = response.getIntParameterValue(CMQCFC.MQIACH_BYTES_SENT);
                int byteReceived = response.getIntParameterValue(CMQCFC.MQIACH_BYTES_RECEIVED);
                log("    Bytes Sent: " + byteSent);
                log("    Bytes Received: " + byteReceived);
            }
            
        } catch (Exception e) {
            log("Error querying channel status: " + e.getMessage());
        }
    }
    
    private static void queryQueueManagerWithPCF(PCFMessageAgent agent) {
        try {
            log("\nQuerying Queue Manager information");
            
            // Create PCF request for queue manager info
            PCFMessage request = new PCFMessage(CMQCFC.MQCMD_INQUIRE_Q_MGR);
            
            PCFMessage[] responses = agent.send(request);
            
            if (responses.length > 0) {
                PCFMessage response = responses[0];
                
                String qmgrName = response.getStringParameterValue(CMQC.MQCA_Q_MGR_NAME);
                log("  Queue Manager Name: " + qmgrName);
                
                String qmgrId = response.getStringParameterValue(CMQC.MQCA_Q_MGR_IDENTIFIER);
                log("  Queue Manager ID: " + qmgrId);
                
                int cmdLevel = response.getIntParameterValue(CMQC.MQIA_COMMAND_LEVEL);
                log("  Command Level: " + cmdLevel);
                
                int platform = response.getIntParameterValue(CMQC.MQIA_PLATFORM);
                log("  Platform: " + getPlatformString(platform));
                
                String startDate = response.getStringParameterValue(CMQCFC.MQCACF_Q_MGR_START_DATE);
                String startTime = response.getStringParameterValue(CMQCFC.MQCACF_Q_MGR_START_TIME);
                log("  Start Date/Time: " + startDate + " " + startTime);
                
                // Max connections field may not be available in all versions
                
                int currentConns = response.getIntParameterValue(CMQCFC.MQIACF_CONNECTION_COUNT);
                log("  Current Connections: " + currentConns);
            }
            
        } catch (Exception e) {
            log("Error querying queue manager: " + e.getMessage());
        }
    }
    
    private static void queryConnectionStatistics(PCFMessageAgent agent) {
        try {
            log("\nQuerying connection statistics");
            
            // Create PCF request for connection statistics
            PCFMessage request = new PCFMessage(CMQCFC.MQCMD_INQUIRE_CONNECTION);
            request.addParameter(CMQCFC.MQBACF_GENERIC_CONNECTION_ID, new byte[48]);
            request.addParameter(CMQCFC.MQIACF_CONNECTION_ATTRS, new int[] {
                CMQCFC.MQIACF_ALL
            });
            
            PCFMessage[] responses = agent.send(request);
            
            int totalConns = 0;
            int ourConns = 0;
            Map<String, Integer> channelCounts = new HashMap<>();
            
            for (PCFMessage response : responses) {
                totalConns++;
                
                String appTag = response.getStringParameterValue(CMQCFC.MQCACF_APPL_TAG);
                if (appTag != null && appTag.trim().equals(ComprehensiveMQMonitor.appTag)) {
                    ourConns++;
                }
                
                String channel = response.getStringParameterValue(CMQCFC.MQCACH_CHANNEL_NAME);
                channelCounts.put(channel, channelCounts.getOrDefault(channel, 0) + 1);
            }
            
            log("  Total Connections: " + totalConns);
            log("  Our Connections (APPTAG=" + appTag + "): " + ourConns);
            log("\n  Connections by Channel:");
            for (Map.Entry<String, Integer> entry : channelCounts.entrySet()) {
                log("    " + entry.getKey() + ": " + entry.getValue());
            }
            
        } catch (Exception e) {
            log("Error querying statistics: " + e.getMessage());
        }
    }
    
    // Helper methods
    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02X", b));
        }
        return result.toString();
    }
    
    private static String getAckModeString(int mode) {
        switch (mode) {
            case Session.AUTO_ACKNOWLEDGE: return "AUTO_ACKNOWLEDGE";
            case Session.CLIENT_ACKNOWLEDGE: return "CLIENT_ACKNOWLEDGE";
            case Session.DUPS_OK_ACKNOWLEDGE: return "DUPS_OK_ACKNOWLEDGE";
            case Session.SESSION_TRANSACTED: return "SESSION_TRANSACTED";
            default: return "UNKNOWN (" + mode + ")";
        }
    }
    
    private static String getApplTypeString(int type) {
        switch (type) {
            case CMQC.MQAT_JAVA: return "JAVA";
            case CMQC.MQAT_USER: return "USER";
            case CMQC.MQAT_QMGR: return "QMGR";
            default: return "OTHER (" + type + ")";
        }
    }
    
    private static String getChannelStatusString(int status) {
        switch (status) {
            case CMQCFC.MQCHS_RUNNING: return "RUNNING";
            case CMQCFC.MQCHS_STARTING: return "STARTING";
            case CMQCFC.MQCHS_STOPPING: return "STOPPING";
            case CMQCFC.MQCHS_STOPPED: return "STOPPED";
            default: return "UNKNOWN (" + status + ")";
        }
    }
    
    private static String getChannelTypeString(int type) {
        // Channel type constants
        if (type == 6) return "CLNTCONN";
        if (type == 7) return "SVRCONN";
        if (type == 1) return "SENDER";
        if (type == 2) return "RECEIVER";
        return "OTHER (" + type + ")";
    }
    
    private static String getPlatformString(int platform) {
        switch (platform) {
            case CMQC.MQPL_UNIX: return "UNIX";
            case CMQC.MQPL_WINDOWS: return "WINDOWS";
            case CMQC.MQPL_ZOS: return "z/OS";
            default: return "OTHER (" + platform + ")";
        }
    }
    
    private static void log(String message) {
        System.out.println(message);
        if (logWriter != null) {
            logWriter.println(message);
            logWriter.flush();
        }
    }
}