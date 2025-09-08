import com.ibm.mq.*;
import com.ibm.mq.constants.CMQC;
import com.ibm.mq.constants.CMQCFC;
import com.ibm.mq.headers.pcf.*;

import java.util.*;

/**
 * Reusable PCF Utility Class for MQ Monitoring
 * Provides convenient methods for common PCF operations
 */
public class PCFUtils {
    
    private MQQueueManager queueManager;
    private PCFMessageAgent pcfAgent;
    private String queueManagerName;
    
    /**
     * Constructor with connection parameters
     */
    public PCFUtils(String qmgrName, String hostName, int port, String channel, String userId, String password) 
            throws Exception {
        this.queueManagerName = qmgrName;
        
        Hashtable<String, Object> props = new Hashtable<>();
        props.put(CMQC.CHANNEL_PROPERTY, channel);
        props.put(CMQC.HOST_NAME_PROPERTY, hostName);
        props.put(CMQC.PORT_PROPERTY, port);
        props.put(CMQC.USER_ID_PROPERTY, userId);
        props.put(CMQC.PASSWORD_PROPERTY, password);
        
        queueManager = new MQQueueManager(qmgrName, props);
        pcfAgent = new PCFMessageAgent(queueManager);
    }
    
    /**
     * Get all connections with specific APPTAG
     * Equivalent to: DIS CONN(*) WHERE(APPLTAG EQ 'tag')
     */
    public List<ConnectionDetails> getConnectionsByAppTag(String appTag) throws Exception {
        List<ConnectionDetails> connections = new ArrayList<>();
        
        PCFMessage request = new PCFMessage(CMQCFC.MQCMD_INQUIRE_CONNECTION);
        request.addParameter(CMQCFC.MQBACF_GENERIC_CONNECTION_ID, new byte[48]);
        request.addParameter(CMQCFC.MQIACF_CONNECTION_ATTRS, new int[] { CMQCFC.MQIACF_ALL });
        
        PCFMessage[] responses = pcfAgent.send(request);
        
        for (PCFMessage response : responses) {
            try {
                String connAppTag = response.getStringParameterValue(CMQCFC.MQCACF_APPL_TAG);
                
                if (connAppTag != null && connAppTag.trim().equals(appTag)) {
                    ConnectionDetails conn = new ConnectionDetails();
                    
                    // Extract all available fields
                    conn.connectionId = bytesToHex(response.getBytesParameterValue(CMQCFC.MQBACF_CONNECTION_ID));
                    conn.appTag = connAppTag.trim();
                    conn.channelName = response.getStringParameterValue(CMQCFC.MQCACH_CHANNEL_NAME);
                    conn.connectionName = response.getStringParameterValue(CMQCFC.MQCACH_CONNECTION_NAME);
                    conn.userId = response.getStringParameterValue(CMQCFC.MQCACF_USER_IDENTIFIER);
                    
                    try {
                        conn.pid = response.getIntParameterValue(CMQCFC.MQIACF_PROCESS_ID);
                        conn.tid = response.getIntParameterValue(CMQCFC.MQIACF_THREAD_ID);
                    } catch (Exception e) { /* Optional fields */ }
                    
                    // Extended connection ID may not be available in all versions
                    
                    try {
                        conn.connectionTag = bytesToHex(response.getBytesParameterValue(CMQCFC.MQBACF_CONN_TAG));
                    } catch (Exception e) { /* Optional field */ }
                    
                    try {
                        conn.messagesSent = response.getIntParameterValue(CMQCFC.MQIACH_MSGS_SENT);
                        conn.messagesReceived = response.getIntParameterValue(CMQCFC.MQIACH_MSGS_RCVD);
                    } catch (Exception e) { /* Optional fields */ }
                    
                    connections.add(conn);
                }
            } catch (Exception e) {
                // Skip problematic connections
            }
        }
        
        return connections;
    }
    
    /**
     * Get all active connections on a channel
     * Equivalent to: DIS CONN(*) WHERE(CHANNEL EQ 'channel')
     */
    public List<ConnectionDetails> getConnectionsByChannel(String channelName) throws Exception {
        List<ConnectionDetails> connections = new ArrayList<>();
        
        PCFMessage request = new PCFMessage(CMQCFC.MQCMD_INQUIRE_CONNECTION);
        request.addParameter(CMQCFC.MQBACF_GENERIC_CONNECTION_ID, new byte[48]);
        request.addParameter(CMQCFC.MQIACF_CONNECTION_ATTRS, new int[] { CMQCFC.MQIACF_ALL });
        
        PCFMessage[] responses = pcfAgent.send(request);
        
        for (PCFMessage response : responses) {
            try {
                String connChannel = response.getStringParameterValue(CMQCFC.MQCACH_CHANNEL_NAME);
                
                if (connChannel != null && connChannel.trim().equals(channelName)) {
                    ConnectionDetails conn = parseConnectionResponse(response);
                    connections.add(conn);
                }
            } catch (Exception e) {
                // Skip problematic connections
            }
        }
        
        return connections;
    }
    
    /**
     * Get channel status information
     * Equivalent to: DIS CHSTATUS('channel')
     */
    public List<ChannelStatus> getChannelStatus(String channelName) throws Exception {
        List<ChannelStatus> statusList = new ArrayList<>();
        
        PCFMessage request = new PCFMessage(CMQCFC.MQCMD_INQUIRE_CHANNEL_STATUS);
        request.addParameter(CMQCFC.MQCACH_CHANNEL_NAME, channelName);
        
        PCFMessage[] responses = pcfAgent.send(request);
        
        for (PCFMessage response : responses) {
            ChannelStatus status = new ChannelStatus();
            
            status.channelName = response.getStringParameterValue(CMQCFC.MQCACH_CHANNEL_NAME);
            status.connectionName = response.getStringParameterValue(CMQCFC.MQCACH_CONNECTION_NAME);
            status.status = response.getIntParameterValue(CMQCFC.MQIACH_CHANNEL_STATUS);
            status.channelType = response.getIntParameterValue(CMQCFC.MQIACH_CHANNEL_TYPE);
            
            try {
                status.messages = response.getIntParameterValue(CMQCFC.MQIACH_MSGS);
                status.bytesSent = response.getIntParameterValue(CMQCFC.MQIACH_BYTES_SENT);
                status.bytesReceived = response.getIntParameterValue(CMQCFC.MQIACH_BYTES_RECEIVED);
            } catch (Exception e) { /* Optional fields */ }
            
            status.mcaUser = response.getStringParameterValue(CMQCFC.MQCACH_MCA_USER_ID);
            
            statusList.add(status);
        }
        
        return statusList;
    }
    
    /**
     * Get queue manager information
     * Equivalent to: DIS QMGR
     */
    public QueueManagerInfo getQueueManagerInfo() throws Exception {
        PCFMessage request = new PCFMessage(CMQCFC.MQCMD_INQUIRE_Q_MGR);
        PCFMessage[] responses = pcfAgent.send(request);
        
        if (responses.length > 0) {
            PCFMessage response = responses[0];
            QueueManagerInfo info = new QueueManagerInfo();
            
            info.name = response.getStringParameterValue(CMQC.MQCA_Q_MGR_NAME);
            info.identifier = response.getStringParameterValue(CMQC.MQCA_Q_MGR_IDENTIFIER);
            info.commandLevel = response.getIntParameterValue(CMQC.MQIA_COMMAND_LEVEL);
            info.platform = response.getIntParameterValue(CMQC.MQIA_PLATFORM);
            
            try {
                info.connectionCount = response.getIntParameterValue(CMQCFC.MQIACF_CONNECTION_COUNT);
            } catch (Exception e) { /* Optional field */ }
            
            try {
                info.startDate = response.getStringParameterValue(CMQCFC.MQCACF_Q_MGR_START_DATE);
                info.startTime = response.getStringParameterValue(CMQCFC.MQCACF_Q_MGR_START_TIME);
            } catch (Exception e) { /* Optional fields */ }
            
            return info;
        }
        
        return null;
    }
    
    /**
     * Get queue status
     * Equivalent to: DIS QSTATUS('queue')
     */
    public QueueStatus getQueueStatus(String queueName) throws Exception {
        PCFMessage request = new PCFMessage(CMQCFC.MQCMD_INQUIRE_Q_STATUS);
        request.addParameter(CMQC.MQCA_Q_NAME, queueName);
        request.addParameter(CMQC.MQIA_Q_TYPE, CMQC.MQQT_LOCAL);
        
        PCFMessage[] responses = pcfAgent.send(request);
        
        if (responses.length > 0) {
            PCFMessage response = responses[0];
            QueueStatus status = new QueueStatus();
            
            status.queueName = response.getStringParameterValue(CMQC.MQCA_Q_NAME);
            status.currentDepth = response.getIntParameterValue(CMQC.MQIA_CURRENT_Q_DEPTH);
            status.openInputCount = response.getIntParameterValue(CMQC.MQIA_OPEN_INPUT_COUNT);
            status.openOutputCount = response.getIntParameterValue(CMQC.MQIA_OPEN_OUTPUT_COUNT);
            
            return status;
        }
        
        return null;
    }
    
    /**
     * Count connections by APPTAG
     * Useful for quick validation
     */
    public int countConnectionsByAppTag(String appTag) throws Exception {
        List<ConnectionDetails> connections = getConnectionsByAppTag(appTag);
        return connections.size();
    }
    
    /**
     * Analyze parent-child relationships
     * Returns analysis of connections sharing same APPTAG
     */
    public ParentChildAnalysis analyzeParentChildRelationships(String appTag) throws Exception {
        List<ConnectionDetails> connections = getConnectionsByAppTag(appTag);
        ParentChildAnalysis analysis = new ParentChildAnalysis();
        
        analysis.appTag = appTag;
        analysis.totalConnections = connections.size();
        
        if (connections.size() > 0) {
            // Sort by connection ID to identify parent (usually first)
            connections.sort((a, b) -> a.connectionId.compareTo(b.connectionId));
            
            analysis.parentConnection = connections.get(0);
            
            if (connections.size() > 1) {
                analysis.childConnections = connections.subList(1, connections.size());
            }
            
            // Analyze commonalities
            Set<Integer> pids = new HashSet<>();
            Set<Integer> tids = new HashSet<>();
            Set<String> connNames = new HashSet<>();
            
            for (ConnectionDetails conn : connections) {
                if (conn.pid > 0) pids.add(conn.pid);
                if (conn.tid > 0) tids.add(conn.tid);
                if (conn.connectionName != null) connNames.add(conn.connectionName);
            }
            
            analysis.uniquePids = pids.size();
            analysis.uniqueTids = tids.size();
            analysis.uniqueConnectionNames = connNames.size();
            
            // All from same process/thread indicates parent-child relationship
            analysis.allFromSameProcess = (pids.size() == 1 && tids.size() == 1);
        }
        
        return analysis;
    }
    
    /**
     * Close PCF connection
     */
    public void close() {
        try {
            if (pcfAgent != null) {
                pcfAgent.disconnect();
            }
            if (queueManager != null && queueManager.isConnected()) {
                queueManager.disconnect();
            }
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }
    
    // Helper method
    private String bytesToHex(byte[] bytes) {
        if (bytes == null) return null;
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02X", b));
        }
        return result.toString();
    }
    
    private ConnectionDetails parseConnectionResponse(PCFMessage response) throws Exception {
        ConnectionDetails conn = new ConnectionDetails();
        
        conn.connectionId = bytesToHex(response.getBytesParameterValue(CMQCFC.MQBACF_CONNECTION_ID));
        conn.appTag = response.getStringParameterValue(CMQCFC.MQCACF_APPL_TAG);
        conn.channelName = response.getStringParameterValue(CMQCFC.MQCACH_CHANNEL_NAME);
        conn.connectionName = response.getStringParameterValue(CMQCFC.MQCACH_CONNECTION_NAME);
        conn.userId = response.getStringParameterValue(CMQCFC.MQCACF_USER_IDENTIFIER);
        
        try {
            conn.pid = response.getIntParameterValue(CMQCFC.MQIACF_PROCESS_ID);
            conn.tid = response.getIntParameterValue(CMQCFC.MQIACF_THREAD_ID);
        } catch (Exception e) { /* Optional */ }
        
        return conn;
    }
    
    // Data classes
    public static class ConnectionDetails {
        public String connectionId;
        public String extConnectionId;
        public String connectionTag;
        public String appTag;
        public String channelName;
        public String connectionName;
        public String userId;
        public int pid;
        public int tid;
        public int messagesSent;
        public int messagesReceived;
        
        @Override
        public String toString() {
            return String.format("Connection[id=%s, appTag=%s, channel=%s, conn=%s, pid=%d, tid=%d]",
                connectionId != null ? connectionId.substring(0, 16) + "..." : "null",
                appTag, channelName, connectionName, pid, tid);
        }
    }
    
    public static class ChannelStatus {
        public String channelName;
        public String connectionName;
        public int status;
        public int channelType;
        public int messages;
        public int bytesSent;
        public int bytesReceived;
        public String mcaUser;
        
        public String getStatusString() {
            switch (status) {
                case CMQCFC.MQCHS_RUNNING: return "RUNNING";
                case CMQCFC.MQCHS_STARTING: return "STARTING";
                case CMQCFC.MQCHS_STOPPING: return "STOPPING";
                case CMQCFC.MQCHS_STOPPED: return "STOPPED";
                default: return "UNKNOWN(" + status + ")";
            }
        }
    }
    
    public static class QueueManagerInfo {
        public String name;
        public String identifier;
        public int commandLevel;
        public int platform;
        public int connectionCount;
        public String startDate;
        public String startTime;
        
        public String getPlatformString() {
            switch (platform) {
                case CMQC.MQPL_UNIX: return "UNIX/Linux";
                case 2: return "Windows";
                case 15: return "z/OS";
                default: return "Platform(" + platform + ")";
            }
        }
    }
    
    public static class QueueStatus {
        public String queueName;
        public int currentDepth;
        public int openInputCount;
        public int openOutputCount;
    }
    
    public static class ParentChildAnalysis {
        public String appTag;
        public int totalConnections;
        public ConnectionDetails parentConnection;
        public List<ConnectionDetails> childConnections;
        public int uniquePids;
        public int uniqueTids;
        public int uniqueConnectionNames;
        public boolean allFromSameProcess;
        
        public void printAnalysis() {
            System.out.println("Parent-Child Analysis for APPTAG: " + appTag);
            System.out.println("  Total Connections: " + totalConnections);
            if (parentConnection != null) {
                System.out.println("  Parent: " + parentConnection.connectionId.substring(0, 16) + "...");
            }
            if (childConnections != null) {
                System.out.println("  Children: " + childConnections.size());
            }
            System.out.println("  Unique PIDs: " + uniquePids);
            System.out.println("  Unique TIDs: " + uniqueTids);
            System.out.println("  All from same process: " + allFromSameProcess);
            if (allFromSameProcess && totalConnections > 1) {
                System.out.println("  ✓ Parent-Child Affinity CONFIRMED");
            }
        }
    }
}