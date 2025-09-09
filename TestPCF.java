import com.ibm.mq.headers.pcf.*;
import com.ibm.mq.constants.*;

public class TestPCF {
    public static void main(String[] args) {
        try {
            // Test PCF connection to each QM
            for (int i = 1; i <= 3; i++) {
                String qm = "QM" + i;
                String host = "10.10.10." + (9 + i);
                
                System.out.print("Testing " + qm + " at " + host + ": ");
                
                PCFMessageAgent agent = new PCFMessageAgent(host, 1414, "APP.SVRCONN");
                agent.connect(qm);
                
                // Simple PCF command - get QM status
                PCFMessage request = new PCFMessage(com.ibm.mq.constants.CMQCFC.MQCMD_INQUIRE_Q_MGR_STATUS);
                PCFMessage[] responses = agent.send(request);
                
                if (responses.length > 0) {
                    System.out.println("✅ PCF SUCCESS - Got " + responses.length + " response(s)");
                } else {
                    System.out.println("⚠️  PCF connected but no responses");
                }
                
                agent.disconnect();
            }
        } catch (Exception e) {
            System.out.println("❌ PCF FAILED: " + e.getMessage());
        }
    }
}
