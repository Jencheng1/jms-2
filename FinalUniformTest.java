import javax.jms.*;
import com.ibm.msg.client.jms.*;
import com.ibm.msg.client.wmq.WMQConstants;
import com.ibm.mq.*;
import com.ibm.mq.headers.pcf.*;
import java.util.*;

public class FinalUniformTest {
    public static void main(String[] args) throws Exception {
        String tag = "FINAL-TEST-" + System.currentTimeMillis();
        System.out.println("========================================");
        System.out.println(" FINAL UNIFORM CLUSTER TEST");
        System.out.println("========================================");
        System.out.println("APPLTAG: " + tag);
        
        // Step 1: Create JMS Connection
        JmsFactoryFactory ff = JmsFactoryFactory.getInstance(WMQConstants.WMQ_PROVIDER);
        JmsConnectionFactory cf = ff.createConnectionFactory();
        
        cf.setStringProperty(WMQConstants.WMQ_HOST_NAME, "10.10.10.10");
        cf.setIntProperty(WMQConstants.WMQ_PORT, 1414);
        cf.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "QM1");
        cf.setStringProperty(WMQConstants.WMQ_CHANNEL, "APP.SVRCONN");
        cf.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
        cf.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, tag);
        
        // Disable connection sharing to see all connections
        cf.setIntProperty(WMQConstants.WMQ_SHARE_CONV_ALLOWED, 0);
        
        Connection conn = cf.createConnection("app", "passw0rd");
        conn.start();
        
        JmsPropertyContext cpc = (JmsPropertyContext) conn;
        String resolvedQm = cpc.getStringProperty(WMQConstants.WMQ_RESOLVED_QUEUE_MANAGER);
        System.out.println("Resolved QM: " + resolvedQm);
        
        // Step 2: Check initial connections (parent only)
        System.out.println("\n--- BEFORE creating sessions ---");
        checkConnectionsViaProcess("qm1", tag);
        
        // Step 3: Create 5 sessions
        System.out.println("\nCreating 5 sessions...");
        List<Session> sessions = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
            sessions.add(session);
            System.out.println("  Created session " + i);
            Thread.sleep(200); // Small delay between sessions
        }
        
        // Step 4: Wait for connections to establish
        System.out.println("\nWaiting for connections to stabilize...");
        Thread.sleep(2000);
        
        // Step 5: Check connections after sessions
        System.out.println("\n--- AFTER creating sessions ---");
        checkConnectionsViaProcess("qm1", tag);
        
        // Step 6: Try PCF (may fail due to auth, but worth trying)
        System.out.println("\n--- Attempting PCF verification ---");
        try {
            checkViaPCF("10.10.10.10", 1414, "QM1", tag);
        } catch (Exception e) {
            System.out.println("PCF failed: " + e.getMessage());
            System.out.println("Using MQSC verification instead");
        }
        
        // Step 7: Hold open for manual verification
        System.out.println("\n========================================");
        System.out.println("Connection will stay open for 20 seconds");
        System.out.println("Run this command to verify:");
        System.out.println("docker exec qm1 bash -c \"echo 'DIS CONN(*) WHERE(APPLTAG EQ " + tag + ")' | runmqsc QM1\"");
        System.out.println("========================================");
        
        Thread.sleep(20000);
        
        // Cleanup
        for (Session s : sessions) s.close();
        conn.close();
        
        System.out.println("\nTest complete");
    }
    
    private static void checkConnectionsViaProcess(String container, String tag) {
        try {
            String cmd = String.format(
                "docker exec %s bash -c \"echo 'DIS CONN(*) WHERE(APPLTAG EQ %s)' | runmqsc QM1 | grep -c 'CONN('\"",
                container, tag
            );
            Process p = Runtime.getRuntime().exec(new String[]{"bash", "-c", cmd});
            p.waitFor();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream())
            );
            String line = reader.readLine();
            int count = (line != null) ? Integer.parseInt(line.trim()) : 0;
            System.out.println("Connections found via MQSC: " + count);
        } catch (Exception e) {
            System.out.println("MQSC check failed: " + e.getMessage());
        }
    }
    
    private static void checkViaPCF(String host, int port, String qm, String tag) throws Exception {
        PCFMessageAgent agent = new PCFMessageAgent(host, port, "APP.SVRCONN");
        agent.connect(qm);
        
        PCFMessage req = new PCFMessage(com.ibm.mq.constants.CMQCFC.MQCMD_INQUIRE_CONNECTION);
        req.addParameter(com.ibm.mq.constants.CMQCFC.MQIACF_CONN_INFO_TYPE, com.ibm.mq.constants.CMQCFC.MQIACF_CONN_INFO_CONN);
        req.addParameter(com.ibm.mq.constants.CMQCFC.MQCACF_APPL_TAG, tag);
        
        PCFMessage[] responses = agent.send(req);
        System.out.println("PCF found " + responses.length + " connections with tag: " + tag);
        
        for (PCFMessage resp : responses) {
            try {
                byte[] connId = resp.getBytesParameterValue(com.ibm.mq.constants.CMQCFC.MQBACF_CONNECTION_ID);
                String channel = resp.getStringParameterValue(com.ibm.mq.constants.CMQCFC.MQCACH_CHANNEL_NAME);
                System.out.println("  - Connection on channel: " + channel);
            } catch (Exception e) {
                // Ignore field extraction errors
            }
        }
        
        agent.disconnect();
    }
}
