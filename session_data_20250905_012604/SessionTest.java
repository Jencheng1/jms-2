import com.ibm.mq.*;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.headers.*;
import java.util.Hashtable;

public class SessionTest {
    public static void main(String[] args) {
        try {
            // Connection parameters
            Hashtable<String, Object> props = new Hashtable<>();
            props.put(MQConstants.HOST_NAME_PROPERTY, args[0]);
            props.put(MQConstants.PORT_PROPERTY, Integer.parseInt(args[1]));
            props.put(MQConstants.CHANNEL_PROPERTY, "APP.SVRCONN");
            props.put(MQConstants.USER_ID_PROPERTY, "app");
            props.put(MQConstants.PASSWORD_PROPERTY, "passw0rd");
            
            String clientId = args[2];
            int sessionCount = Integer.parseInt(args[3]);
            
            // Create connection (Parent)
            MQQueueManager qmgr = new MQQueueManager("QM" + args[4], props);
            System.out.println(clientId + " - Connected to QM" + args[4]);
            
            // Create multiple sessions (Children)
            for (int i = 1; i <= sessionCount; i++) {
                MQQueue queue = qmgr.accessQueue("UNIFORM.QUEUE", 
                    MQConstants.MQOO_OUTPUT | MQConstants.MQOO_INPUT_AS_Q_DEF);
                System.out.println(clientId + " - Session " + i + " created");
                
                // Keep session active
                Thread.sleep(1000);
            }
            
            // Keep connection alive
            System.out.println(clientId + " - Keeping connection active...");
            Thread.sleep(60000);
            
            qmgr.disconnect();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
