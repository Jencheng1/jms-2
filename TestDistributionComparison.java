import com.ibm.msg.client.jms.JmsConnectionFactory;
import com.ibm.msg.client.jms.JmsFactoryFactory;
import com.ibm.msg.client.wmq.WMQConstants;
import com.ibm.mq.jms.MQConnection;
import javax.jms.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

public class TestDistributionComparison {
    
    public static void main(String[] args) throws Exception {
        System.out.println("================================================================");
        System.out.println("TESTING: Effect of WMQ_QUEUE_MANAGER on Distribution");
        System.out.println("================================================================\n");
        
        // Test 1: WITHOUT WMQ_QUEUE_MANAGER setting
        System.out.println("TEST 1: WITHOUT setting WMQ_QUEUE_MANAGER");
        System.out.println("-------------------------------------------");
        testDistribution(false, 10);
        
        System.out.println("\n");
        
        // Test 2: WITH WMQ_QUEUE_MANAGER = "*"
        System.out.println("TEST 2: WITH WMQ_QUEUE_MANAGER = '*'");
        System.out.println("-------------------------------------------");
        testDistribution(true, 10);
        
        System.out.println("\n================================================================");
        System.out.println("CONCLUSION:");
        System.out.println("Compare the distribution patterns above to see the effect");
        System.out.println("================================================================");
    }
    
    private static void testDistribution(boolean setQueueManager, int numConnections) throws Exception {
        JmsFactoryFactory ff = JmsFactoryFactory.getInstance(WMQConstants.WMQ_PROVIDER);
        
        Map<String, Integer> distribution = new HashMap<>();
        distribution.put("QM1", 0);
        distribution.put("QM2", 0);
        distribution.put("QM3", 0);
        distribution.put("UNKNOWN", 0);
        
        for (int i = 1; i <= numConnections; i++) {
            JmsConnectionFactory factory = ff.createConnectionFactory();
            
            // Common configuration
            factory.setStringProperty(WMQConstants.WMQ_CCDTURL, "file:///workspace/ccdt/ccdt.json");
            factory.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
            factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, WMQConstants.WMQ_CLIENT_RECONNECT);
            factory.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
            factory.setStringProperty(WMQConstants.USERID, "app");
            factory.setStringProperty(WMQConstants.PASSWORD, "passw0rd");
            factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, "DIST-TEST-" + i);
            
            // KEY DIFFERENCE: Conditionally set WMQ_QUEUE_MANAGER
            if (setQueueManager) {
                factory.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "*");
            }
            // If not set, it defaults to "" which lets CCDT choose
            
            try {
                Connection conn = factory.createConnection();
                
                String qm = "UNKNOWN";
                if (conn instanceof MQConnection) {
                    MQConnection mqConn = (MQConnection) conn;
                    qm = extractQueueManager(mqConn);
                }
                
                distribution.put(qm, distribution.get(qm) + 1);
                System.out.printf("  Connection %2d -> %s\n", i, qm);
                
                conn.close();
                Thread.sleep(50); // Small delay
                
            } catch (Exception e) {
                System.out.printf("  Connection %2d -> FAILED: %s\n", i, e.getMessage());
                distribution.put("UNKNOWN", distribution.get("UNKNOWN") + 1);
            }
        }
        
        System.out.println("\nDistribution Summary:");
        int total = 0;
        for (Map.Entry<String, Integer> entry : distribution.entrySet()) {
            if (entry.getValue() > 0 && !entry.getKey().equals("UNKNOWN")) {
                System.out.printf("  %s: %d connections (%.0f%%)\n", 
                    entry.getKey(), entry.getValue(), (entry.getValue() * 100.0 / numConnections));
                total++;
            }
        }
        
        if (total > 1) {
            System.out.println("  ✅ DISTRIBUTED across " + total + " Queue Managers");
        } else if (total == 1) {
            System.out.println("  ⚠️ ALL connections on SINGLE Queue Manager");
        } else {
            System.out.println("  ❌ No successful connections");
        }
    }
    
    private static String extractQueueManager(MQConnection mqConn) {
        try {
            // Try via delegate
            Field[] fields = mqConn.getClass().getDeclaredFields();
            for (Field field : fields) {
                if (field.getName().equals("delegate") || field.getName().equals("commonConn")) {
                    field.setAccessible(true);
                    Object delegate = field.get(mqConn);
                    if (delegate != null) {
                        Method getStringProp = delegate.getClass().getMethod("getStringProperty", String.class);
                        
                        // Try to get resolved QM
                        try {
                            Object resolvedQM = getStringProp.invoke(delegate, "XMSC_WMQ_RESOLVED_QUEUE_MANAGER");
                            if (resolvedQM != null && resolvedQM.toString().contains("QM")) {
                                String qm = resolvedQM.toString();
                                if (qm.contains("QM1")) return "QM1";
                                if (qm.contains("QM2")) return "QM2";
                                if (qm.contains("QM3")) return "QM3";
                            }
                        } catch (Exception e) {
                            // Continue
                        }
                        
                        // Try CONNECTION_ID
                        try {
                            Object connId = getStringProp.invoke(delegate, "XMSC_WMQ_CONNECTION_ID");
                            if (connId != null && connId.toString().length() > 16) {
                                String id = connId.toString();
                                if (id.contains("514D31")) return "QM1";
                                if (id.contains("514D32")) return "QM2";
                                if (id.contains("514D33")) return "QM3";
                            }
                        } catch (Exception e) {
                            // Continue
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