import com.ibm.msg.client.jms.JmsConnectionFactory;
import com.ibm.msg.client.jms.JmsFactoryFactory;
import com.ibm.msg.client.wmq.WMQConstants;
import com.ibm.mq.jms.MQConnection;
import javax.jms.*;
import java.util.*;

public class QuickConntagTest3Times {
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== QUICK CONNTAG TEST - 3 ITERATIONS ===\n");
        
        int diffQmCount = 0;
        int sameQmCount = 0;
        
        for (int iter = 1; iter <= 3; iter++) {
            System.out.println("ITERATION " + iter);
            System.out.println("-----------");
            
            String timestamp = String.valueOf(System.currentTimeMillis());
            String TRACKING_KEY = "QUICK-" + timestamp;
            
            // Force new factory instances each time
            JmsFactoryFactory ff1 = JmsFactoryFactory.getInstance(WMQConstants.WMQ_PROVIDER);
            JmsFactoryFactory ff2 = JmsFactoryFactory.getInstance(WMQConstants.WMQ_PROVIDER);
            
            // Connection 1
            JmsConnectionFactory factory1 = ff1.createConnectionFactory();
            factory1.setStringProperty(WMQConstants.WMQ_CCDTURL, "file:///workspace/ccdt/ccdt.json");
            factory1.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
            factory1.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
            factory1.setStringProperty(WMQConstants.USERID, "app");
            factory1.setStringProperty(WMQConstants.PASSWORD, "passw0rd");
            factory1.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TRACKING_KEY + "-C1");
            // Explicitly set to empty string instead of not setting
            factory1.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "");
            
            // Add random delay to avoid timing patterns
            Thread.sleep((long)(Math.random() * 500));
            
            Connection conn1 = factory1.createConnection();
            String qm1 = getQM(conn1);
            
            // Connection 2 with delay
            Thread.sleep((long)(Math.random() * 500));
            
            JmsConnectionFactory factory2 = ff2.createConnectionFactory();
            factory2.setStringProperty(WMQConstants.WMQ_CCDTURL, "file:///workspace/ccdt/ccdt.json");
            factory2.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
            factory2.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
            factory2.setStringProperty(WMQConstants.USERID, "app");
            factory2.setStringProperty(WMQConstants.PASSWORD, "passw0rd");
            factory2.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TRACKING_KEY + "-C2");
            factory2.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "");
            
            Connection conn2 = factory2.createConnection();
            String qm2 = getQM(conn2);
            
            System.out.println("Tracking: " + TRACKING_KEY);
            System.out.println("C1 → " + qm1);
            System.out.println("C2 → " + qm2);
            
            if (qm1.equals(qm2)) {
                System.out.println("Result: SAME QM\n");
                sameQmCount++;
            } else {
                System.out.println("Result: ✅ DIFFERENT QMs\n");
                diffQmCount++;
            }
            
            // Keep alive briefly for CONNTAG capture
            Thread.sleep(5000);
            
            // Close connections
            conn1.close();
            conn2.close();
            
            // Delay between iterations
            Thread.sleep(2000);
        }
        
        System.out.println("\n=== SUMMARY ===");
        System.out.println("Different QMs: " + diffQmCount + "/3");
        System.out.println("Same QM: " + sameQmCount + "/3");
        System.out.println("Distribution Rate: " + (diffQmCount * 100 / 3) + "%");
        
        if (diffQmCount > 0) {
            System.out.println("\n✅ Distribution is working!");
        } else {
            System.out.println("\n⚠️ Distribution issue detected - all on same QM");
        }
    }
    
    private static String getQM(Connection conn) {
        try {
            if (conn instanceof MQConnection) {
                MQConnection mqConn = (MQConnection) conn;
                java.lang.reflect.Field[] fields = mqConn.getClass().getDeclaredFields();
                for (java.lang.reflect.Field field : fields) {
                    if (field.getName().equals("delegate") || field.getName().equals("commonConn")) {
                        field.setAccessible(true);
                        Object delegate = field.get(mqConn);
                        if (delegate != null) {
                            java.lang.reflect.Method getStringProp = delegate.getClass().getMethod("getStringProperty", String.class);
                            Object connId = getStringProp.invoke(delegate, "XMSC_WMQ_CONNECTION_ID");
                            if (connId != null) {
                                String id = connId.toString();
                                if (id.contains("514D31")) return "QM1";
                                if (id.contains("514D32")) return "QM2";
                                if (id.contains("514D33")) return "QM3";
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return "UNKNOWN";
    }
}