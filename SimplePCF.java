import com.ibm.mq.*;
import com.ibm.mq.headers.pcf.*;
import java.util.Hashtable;

public class SimplePCF {
    public static void main(String[] args) throws Exception {
        MQQueueManager qmgr = null;
        PCFMessageAgent agent = null;
        
        try {
            // Connect
            Hashtable<String, Object> props = new Hashtable<>();
            props.put(com.ibm.mq.constants.CMQC.HOST_NAME_PROPERTY, "10.10.10.10");
            props.put(com.ibm.mq.constants.CMQC.PORT_PROPERTY, 1414);
            props.put(com.ibm.mq.constants.CMQC.CHANNEL_PROPERTY, "APP.SVRCONN");
            
            qmgr = new MQQueueManager("QM1", props);
            agent = new PCFMessageAgent(qmgr);
            
            // Get QM info (this worked before)
            PCFMessage request = new PCFMessage(com.ibm.mq.constants.CMQCFC.MQCMD_INQUIRE_Q_MGR);
            PCFMessage[] responses = agent.send(request);
            System.out.println("QM inquiry: " + responses.length + " responses");
            
            // Now try connection inquiry without parameters
            request = new PCFMessage(com.ibm.mq.constants.CMQCFC.MQCMD_INQUIRE_CONNECTION);
            responses = agent.send(request);
            System.out.println("Connection inquiry (no params): " + responses.length + " responses");
            
            // Show some connection info
            for (int i = 0; i < Math.min(3, responses.length); i++) {
                try {
                    String channel = responses[i].getStringParameterValue(com.ibm.mq.constants.CMQCFC.MQCACH_CHANNEL_NAME);
                    System.out.println("  Connection " + i + " on channel: " + channel);
                } catch (Exception e) {
                    System.out.println("  Connection " + i + " (no channel info)");
                }
            }
            
        } finally {
            if (agent != null) agent.disconnect();
            if (qmgr != null) qmgr.disconnect();
        }
    }
}
