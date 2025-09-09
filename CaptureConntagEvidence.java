import com.ibm.msg.client.jms.JmsConnectionFactory;
import com.ibm.msg.client.jms.JmsFactoryFactory;
import com.ibm.msg.client.wmq.WMQConstants;
import com.ibm.mq.jms.MQConnection;
import com.ibm.mq.jms.MQSession;
import javax.jms.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.text.SimpleDateFormat;
import java.io.*;

public class CaptureConntagEvidence {
    
    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    
    public static void main(String[] args) throws Exception {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String TRACKING_KEY = "CONNTAG-" + timestamp;
        
        System.out.println("================================================================");
        System.out.println("CONNTAG EVIDENCE CAPTURE TEST");
        System.out.println("================================================================");
        System.out.println("Tracking Key: " + TRACKING_KEY);
        System.out.println("This test will:");
        System.out.println("1. Create 2 connections with different APPLTAGs");
        System.out.println("2. Keep them alive for MQSC capture");
        System.out.println("3. Output MQSC commands for verification");
        System.out.println("================================================================\n");
        
        JmsFactoryFactory ff = JmsFactoryFactory.getInstance(WMQConstants.WMQ_PROVIDER);
        
        // ========== CONNECTION 1 ==========
        System.out.println("Creating Connection 1...");
        JmsConnectionFactory factory1 = ff.createConnectionFactory();
        factory1.setStringProperty(WMQConstants.WMQ_CCDTURL, "file:///workspace/ccdt/ccdt.json");
        factory1.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
        factory1.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, WMQConstants.WMQ_CLIENT_RECONNECT);
        factory1.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
        factory1.setStringProperty(WMQConstants.USERID, "app");
        factory1.setStringProperty(WMQConstants.PASSWORD, "passw0rd");
        // NOT setting WMQ_QUEUE_MANAGER to allow distribution
        factory1.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TRACKING_KEY + "-C1");
        
        Connection connection1 = factory1.createConnection();
        
        String conn1QM = "UNKNOWN";
        String conn1Handle = "UNKNOWN";
        
        if (connection1 instanceof MQConnection) {
            MQConnection mqConn1 = (MQConnection) connection1;
            Map<String, Object> conn1Data = extractConnectionDetails(mqConn1);
            String conn1Id = getFieldValue(conn1Data, "CONNECTION_ID");
            conn1QM = determineQM(conn1Id);
            if (conn1Id.length() > 32) {
                conn1Handle = conn1Id.substring(32);
            }
        }
        
        System.out.println("✅ Connection 1 created on " + conn1QM);
        System.out.println("   APPLTAG: " + TRACKING_KEY + "-C1");
        System.out.println("   Handle: " + conn1Handle);
        
        // Create 5 sessions
        List<Session> sessions1 = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            sessions1.add(connection1.createSession(false, Session.AUTO_ACKNOWLEDGE));
        }
        System.out.println("   Created 5 sessions (total 6 MQ connections)\n");
        
        // ========== CONNECTION 2 ==========
        System.out.println("Creating Connection 2...");
        JmsConnectionFactory factory2 = ff.createConnectionFactory();
        factory2.setStringProperty(WMQConstants.WMQ_CCDTURL, "file:///workspace/ccdt/ccdt.json");
        factory2.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
        factory2.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, WMQConstants.WMQ_CLIENT_RECONNECT);
        factory2.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
        factory2.setStringProperty(WMQConstants.USERID, "app");
        factory2.setStringProperty(WMQConstants.PASSWORD, "passw0rd");
        // NOT setting WMQ_QUEUE_MANAGER to allow distribution
        factory2.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TRACKING_KEY + "-C2");
        
        Connection connection2 = factory2.createConnection();
        
        String conn2QM = "UNKNOWN";
        String conn2Handle = "UNKNOWN";
        
        if (connection2 instanceof MQConnection) {
            MQConnection mqConn2 = (MQConnection) connection2;
            Map<String, Object> conn2Data = extractConnectionDetails(mqConn2);
            String conn2Id = getFieldValue(conn2Data, "CONNECTION_ID");
            conn2QM = determineQM(conn2Id);
            if (conn2Id.length() > 32) {
                conn2Handle = conn2Id.substring(32);
            }
        }
        
        System.out.println("✅ Connection 2 created on " + conn2QM);
        System.out.println("   APPLTAG: " + TRACKING_KEY + "-C2");
        System.out.println("   Handle: " + conn2Handle);
        
        // Create 3 sessions
        List<Session> sessions2 = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            sessions2.add(connection2.createSession(false, Session.AUTO_ACKNOWLEDGE));
        }
        System.out.println("   Created 3 sessions (total 4 MQ connections)\n");
        
        // ========== DISTRIBUTION ANALYSIS ==========
        System.out.println("================================================================");
        System.out.println("DISTRIBUTION RESULTS:");
        System.out.println("================================================================");
        System.out.println("Connection 1: " + conn1QM + " (6 connections)");
        System.out.println("Connection 2: " + conn2QM + " (4 connections)");
        
        if (!conn1QM.equals(conn2QM)) {
            System.out.println("\n✅ CONNECTIONS DISTRIBUTED TO DIFFERENT QMs!");
            System.out.println("Expected CONNTAG differences:");
            System.out.println("  - Different QM components (" + conn1QM + " vs " + conn2QM + ")");
            System.out.println("  - Different handles");
            System.out.println("  - Different APPLTAGs (-C1 vs -C2)");
        } else {
            System.out.println("\n⚠️  Both connections on same QM: " + conn1QM);
            System.out.println("Expected CONNTAG differences:");
            System.out.println("  - Same QM component (" + conn1QM + ")");
            System.out.println("  - Different handles");
            System.out.println("  - Different APPLTAGs (-C1 vs -C2)");
        }
        
        // ========== MQSC COMMANDS ==========
        System.out.println("\n================================================================");
        System.out.println("COPY AND RUN THESE COMMANDS TO CAPTURE CONNTAG:");
        System.out.println("================================================================\n");
        
        System.out.println("# For Connection 1 on " + conn1QM + ":");
        String qm1Lower = conn1QM.toLowerCase();
        System.out.println("docker exec " + qm1Lower + " bash -c \"echo 'DIS CONN(*) WHERE(APPLTAG EQ '\\'''" + TRACKING_KEY + "-C1'\\'') ALL' | runmqsc " + conn1QM + "\" | grep -E 'CONN\\(|CONNTAG\\('");
        
        System.out.println("\n# For Connection 2 on " + conn2QM + ":");
        String qm2Lower = conn2QM.toLowerCase();
        System.out.println("docker exec " + qm2Lower + " bash -c \"echo 'DIS CONN(*) WHERE(APPLTAG EQ '\\'''" + TRACKING_KEY + "-C2'\\'') ALL' | runmqsc " + conn2QM + "\" | grep -E 'CONN\\(|CONNTAG\\('");
        
        System.out.println("\n# Or run this to check all QMs:");
        System.out.println("for qm in qm1 qm2 qm3; do");
        System.out.println("  echo \"=== Checking $qm ===\"");
        System.out.println("  docker exec $qm bash -c \"echo 'DIS CONN(*) WHERE(APPLTAG LK '\\'''" + TRACKING_KEY + "*'\\'') ALL' | runmqsc ${qm^^}\" | grep -E 'CONN\\(|CONNTAG\\('");
        System.out.println("done");
        
        // Create script file
        PrintWriter scriptWriter = new PrintWriter(new FileWriter("capture_conntag_" + timestamp + ".sh"));
        scriptWriter.println("#!/bin/bash");
        scriptWriter.println("echo 'CONNTAG Evidence for " + TRACKING_KEY + "'");
        scriptWriter.println("echo '========================================'");
        scriptWriter.println("for qm in qm1 qm2 qm3; do");
        scriptWriter.println("  echo \"\"");
        scriptWriter.println("  echo \"=== Checking ${qm^^} ===\"");
        scriptWriter.println("  docker exec $qm bash -c \"echo 'DIS CONN(*) WHERE(APPLTAG LK '\\\\''\"" + TRACKING_KEY + "\"*\\\\'') ALL' | runmqsc ${qm^^}\" | grep -E 'CONN\\(|CONNTAG\\(|APPLTAG\\('");
        scriptWriter.println("done");
        scriptWriter.close();
        Runtime.getRuntime().exec("chmod +x capture_conntag_" + timestamp + ".sh");
        
        System.out.println("\n================================================================");
        System.out.println("Script created: ./capture_conntag_" + timestamp + ".sh");
        System.out.println("================================================================");
        
        // Keep alive
        System.out.println("\n⏰ Keeping connections alive for 90 seconds...");
        System.out.println("   RUN THE COMMANDS ABOVE NOW TO CAPTURE CONNTAG!");
        
        for (int i = 90; i > 0; i -= 10) {
            System.out.println("   " + i + " seconds remaining...");
            Thread.sleep(10000);
        }
        
        // Cleanup
        System.out.println("\nClosing connections...");
        for (Session s : sessions1) s.close();
        for (Session s : sessions2) s.close();
        connection1.close();
        connection2.close();
        
        System.out.println("✅ Test complete!");
    }
    
    private static String determineQM(String connId) {
        if (connId.contains("514D31")) return "QM1";
        if (connId.contains("514D32")) return "QM2";
        if (connId.contains("514D33")) return "QM3";
        return "UNKNOWN";
    }
    
    private static Map<String, Object> extractConnectionDetails(MQConnection mqConn) {
        Map<String, Object> details = new HashMap<>();
        try {
            Field[] fields = mqConn.getClass().getDeclaredFields();
            for (Field field : fields) {
                if (field.getName().equals("delegate") || field.getName().equals("commonConn")) {
                    field.setAccessible(true);
                    Object delegate = field.get(mqConn);
                    if (delegate != null) {
                        Method getStringProp = delegate.getClass().getMethod("getStringProperty", String.class);
                        try {
                            Object connId = getStringProp.invoke(delegate, "XMSC_WMQ_CONNECTION_ID");
                            if (connId != null) {
                                details.put("CONNECTION_ID", connId.toString());
                            }
                        } catch (Exception e) {
                            // Continue
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Continue
        }
        return details;
    }
    
    private static String getFieldValue(Map<String, Object> data, String fieldName) {
        Object value = data.get(fieldName);
        return value != null ? value.toString() : "UNKNOWN";
    }
}