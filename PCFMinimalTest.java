import com.ibm.mq.*;
import com.ibm.mq.headers.pcf.*;
import java.util.Hashtable;

public class PCFMinimalTest {
    
    // Define constants directly to avoid conflicts
    static final int MQCMD_INQUIRE_CONNECTION = 1201;
    static final int MQIACF_CONNECTION_ATTRS = 1011;
    static final int MQIACF_ALL = 1009;
    
    public static void main(String[] args) throws Exception {
        MQQueueManager qmgr = null;
        PCFMessageAgent agent = null;
        
        try {
            // Connect to QM
            Hashtable<String, Object> props = new Hashtable<>();
            props.put(com.ibm.mq.constants.CMQC.HOST_NAME_PROPERTY, "10.10.10.10");
            props.put(com.ibm.mq.constants.CMQC.PORT_PROPERTY, 1414);
            props.put(com.ibm.mq.constants.CMQC.CHANNEL_PROPERTY, "APP.SVRCONN");
            props.put(com.ibm.mq.constants.CMQC.USE_MQCSP_AUTHENTICATION_PROPERTY, false);
            
            System.out.println("Connecting to QM1...");
            qmgr = new MQQueueManager("QM1", props);
            agent = new PCFMessageAgent(qmgr);
            System.out.println("Connected successfully\n");
            
            // Test 1: Basic INQUIRE_Q_MGR (this should work)
            System.out.println("Test 1: INQUIRE_Q_MGR");
            try {
                PCFMessage request = new PCFMessage(com.ibm.mq.constants.CMQCFC.MQCMD_INQUIRE_Q_MGR);
                PCFMessage[] responses = agent.send(request);
                System.out.println("✓ Success: " + responses.length + " response(s)\n");
            } catch (Exception e) {
                System.out.println("✗ Failed: " + e.getMessage() + "\n");
            }
            
            // Test 2: INQUIRE_CONNECTION with no parameters
            System.out.println("Test 2: INQUIRE_CONNECTION (no params)");
            try {
                PCFMessage request = new PCFMessage(MQCMD_INQUIRE_CONNECTION);
                // No parameters added
                PCFMessage[] responses = agent.send(request);
                System.out.println("✓ Success: " + responses.length + " connection(s)\n");
            } catch (Exception e) {
                System.out.println("✗ Failed: " + e.getMessage());
                System.out.println("Reason code: " + ((PCFException)e).getReason() + "\n");
            }
            
            // Test 3: INQUIRE_CONNECTION with MQIACF_ALL
            System.out.println("Test 3: INQUIRE_CONNECTION with MQIACF_ALL");
            try {
                PCFMessage request = new PCFMessage(MQCMD_INQUIRE_CONNECTION);
                request.addParameter(MQIACF_CONNECTION_ATTRS, MQIACF_ALL);
                PCFMessage[] responses = agent.send(request);
                System.out.println("✓ Success: " + responses.length + " connection(s)\n");
            } catch (Exception e) {
                System.out.println("✗ Failed: " + e.getMessage());
                System.out.println("Reason code: " + ((PCFException)e).getReason() + "\n");
            }
            
            // Test 4: INQUIRE_CONNECTION with empty byte array
            System.out.println("Test 4: INQUIRE_CONNECTION with empty connection ID");
            try {
                PCFMessage request = new PCFMessage(MQCMD_INQUIRE_CONNECTION);
                // Try with empty connection ID (should match all)
                byte[] emptyConnId = new byte[48];
                request.addParameter(3052, emptyConnId); // MQBACF_CONNECTION_ID
                PCFMessage[] responses = agent.send(request);
                System.out.println("✓ Success: " + responses.length + " connection(s)\n");
            } catch (Exception e) {
                System.out.println("✗ Failed: " + e.getMessage());
                System.out.println("Reason code: " + ((PCFException)e).getReason() + "\n");
            }
            
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (agent != null) try { agent.disconnect(); } catch (Exception e) {}
            if (qmgr != null) try { qmgr.disconnect(); } catch (Exception e) {}
        }
    }
}