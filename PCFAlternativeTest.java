import com.ibm.mq.*;
import com.ibm.mq.headers.pcf.*;
import com.ibm.mq.headers.*;
import java.util.Hashtable;

public class PCFAlternativeTest {
    
    public static void main(String[] args) throws Exception {
        MQQueueManager qmgr = null;
        
        try {
            // Connect to QM
            Hashtable<String, Object> props = new Hashtable<>();
            props.put(com.ibm.mq.constants.CMQC.HOST_NAME_PROPERTY, "10.10.10.10");
            props.put(com.ibm.mq.constants.CMQC.PORT_PROPERTY, 1414);
            props.put(com.ibm.mq.constants.CMQC.CHANNEL_PROPERTY, "APP.SVRCONN");
            props.put(com.ibm.mq.constants.CMQC.USE_MQCSP_AUTHENTICATION_PROPERTY, false);
            
            System.out.println("Connecting to QM1...");
            qmgr = new MQQueueManager("QM1", props);
            System.out.println("Connected successfully\n");
            
            // Try using PCF directly without agent
            System.out.println("Test: Direct PCF Message to SYSTEM.ADMIN.COMMAND.QUEUE");
            
            MQQueue cmdQueue = null;
            MQQueue replyQueue = null;
            
            try {
                // Open command queue
                int openOptions = com.ibm.mq.constants.CMQC.MQOO_OUTPUT | 
                                com.ibm.mq.constants.CMQC.MQOO_FAIL_IF_QUIESCING;
                cmdQueue = qmgr.accessQueue("SYSTEM.ADMIN.COMMAND.QUEUE", openOptions);
                
                // Open reply queue
                openOptions = com.ibm.mq.constants.CMQC.MQOO_INPUT_AS_Q_DEF | 
                            com.ibm.mq.constants.CMQC.MQOO_FAIL_IF_QUIESCING;
                String modelQName = "SYSTEM.DEFAULT.MODEL.QUEUE";
                replyQueue = qmgr.accessQueue(modelQName, openOptions, "", "", "");
                
                // Create PCF message for INQUIRE_CONNECTION
                PCFMessage request = new PCFMessage(1201); // MQCMD_INQUIRE_CONNECTION
                
                // Create MQ message
                MQMessage requestMsg = new MQMessage();
                requestMsg.messageType = com.ibm.mq.constants.CMQC.MQMT_REQUEST;
                requestMsg.format = com.ibm.mq.constants.CMQC.MQFMT_ADMIN;
                requestMsg.replyToQueueName = replyQueue.getName();
                requestMsg.replyToQueueManagerName = qmgr.getName();
                
                // Write PCF message to MQ message
                requestMsg.writeBytes(request.getMessage());
                
                // Send the request
                cmdQueue.put(requestMsg);
                System.out.println("Sent PCF request");
                
                // Get reply
                MQMessage replyMsg = new MQMessage();
                replyMsg.correlationId = requestMsg.messageId;
                MQGetMessageOptions gmo = new MQGetMessageOptions();
                gmo.options = com.ibm.mq.constants.CMQC.MQGMO_WAIT | 
                            com.ibm.mq.constants.CMQC.MQGMO_CONVERT;
                gmo.waitInterval = 5000;
                
                replyQueue.get(replyMsg, gmo);
                System.out.println("Got reply message");
                
                // Parse reply
                PCFMessage reply = new PCFMessage(replyMsg);
                int reason = reply.getReason();
                System.out.println("Reply reason: " + reason);
                
                if (reason == 0) {
                    System.out.println("✓ Success!");
                } else {
                    System.out.println("✗ Failed with reason: " + reason);
                }
                
            } catch (MQException mqe) {
                System.out.println("MQ Error: " + mqe.getMessage());
                System.out.println("Reason: " + mqe.getReason());
            } finally {
                if (cmdQueue != null) cmdQueue.close();
                if (replyQueue != null) replyQueue.close();
            }
            
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (qmgr != null) try { qmgr.disconnect(); } catch (Exception e) {}
        }
    }
}