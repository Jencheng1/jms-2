import com.ibm.mq.*;
import com.ibm.mq.headers.pcf.*;
import java.util.Hashtable;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

public class PCFDebugTest {
    
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
            
            // Create PCF message for INQUIRE_CONNECTION
            System.out.println("Creating INQUIRE_CONNECTION message...");
            PCFMessage request = new PCFMessage(1201); // MQCMD_INQUIRE_CONNECTION
            
            // Debug the message structure
            System.out.println("Message details:");
            System.out.println("  Type: " + request.getType());
            System.out.println("  Command: " + request.getCommand());
            System.out.println("  Parameter count: " + request.getParameterCount());
            System.out.println("  Reason: " + request.getReason());
            System.out.println("  CompCode: " + request.getCompCode());
            
            // Try to serialize to see the structure
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            int written = request.write(dos);
            System.out.println("  Serialized size: " + written + " bytes");
            
            // Now send it
            System.out.println("\nSending request...");
            try {
                PCFMessage[] responses = agent.send(request);
                System.out.println("✓ Success! Got " + responses.length + " response(s)");
                
                // Show first response details
                if (responses.length > 0) {
                    System.out.println("\nFirst response:");
                    System.out.println("  Type: " + responses[0].getType());
                    System.out.println("  Reason: " + responses[0].getReason());
                    System.out.println("  Parameters: " + responses[0].getParameterCount());
                }
                
            } catch (PCFException e) {
                System.out.println("✗ PCF Error: " + e.getMessage());
                System.out.println("  Reason code: " + e.getReason());
                System.out.println("  Completion code: " + e.getCompCode());
                
                // Check if it's a specific error
                if (e.getReason() == 3007) {
                    System.out.println("  Error 3007: MQRCCF_CFH_TYPE_ERROR");
                    System.out.println("  This suggests the PCF message type is incorrect");
                } else if (e.getReason() == 3019) {
                    System.out.println("  Error 3019: MQRCCF_COMMAND_FAILED");
                    System.out.println("  The command is not supported or failed");
                }
            }
            
            // Try listing available commands
            System.out.println("\n\nTrying INQUIRE_Q_MGR as control test...");
            request = new PCFMessage(com.ibm.mq.constants.CMQCFC.MQCMD_INQUIRE_Q_MGR);
            
            System.out.println("Message details:");
            System.out.println("  Type: " + request.getType());
            System.out.println("  Command: " + request.getCommand());
            
            try {
                PCFMessage[] responses = agent.send(request);
                System.out.println("✓ Success! Got " + responses.length + " response(s)");
                System.out.println("  QM inquiry works, so PCF is functional");
            } catch (PCFException e) {
                System.out.println("✗ Failed: Reason " + e.getReason());
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