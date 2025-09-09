import com.ibm.mq.*;
import com.ibm.mq.constants.*;
import com.ibm.mq.headers.pcf.*;
import java.util.Hashtable;

public class PCFDirectTest {
    public static void main(String[] args) {
        try {
            // Direct connection to QM1 using MQQueueManager
            Hashtable<String, Object> props = new Hashtable<>();
            props.put(com.ibm.mq.constants.CMQC.HOST_NAME_PROPERTY, "10.10.10.10");
            props.put(com.ibm.mq.constants.CMQC.PORT_PROPERTY, 1414);
            props.put(com.ibm.mq.constants.CMQC.CHANNEL_PROPERTY, "APP.SVRCONN");
            props.put(com.ibm.mq.constants.CMQC.USE_MQCSP_AUTHENTICATION_PROPERTY, false);
            
            System.out.println("Connecting to QM1 directly...");
            MQQueueManager qmgr = new MQQueueManager("QM1", props);
            
            System.out.println("Creating PCF agent...");
            PCFMessageAgent agent = new PCFMessageAgent(qmgr);
            
            System.out.println("Sending PCF command...");
            // Try a simple inquiry
            PCFMessage request = new PCFMessage(com.ibm.mq.constants.CMQCFC.MQCMD_INQUIRE_Q_MGR);
            PCFMessage[] responses = agent.send(request);
            
            System.out.println("✅ PCF SUCCESS! Got " + responses.length + " response(s)");
            
            // Try to get QM name from response
            if (responses.length > 0) {
                try {
                    String qmName = responses[0].getStringParameterValue(com.ibm.mq.constants.CMQC.MQCA_Q_MGR_NAME);
                    System.out.println("Queue Manager Name: " + qmName);
                } catch (Exception e) {
                    System.out.println("Could not extract QM name");
                }
            }
            
            agent.disconnect();
            qmgr.disconnect();
            
        } catch (Exception e) {
            System.out.println("❌ Error: " + e.getClass().getName() + " - " + e.getMessage());
            e.printStackTrace();
        }
    }
}