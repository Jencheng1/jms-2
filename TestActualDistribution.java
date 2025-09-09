import com.ibm.msg.client.jms.JmsConnectionFactory;
import com.ibm.msg.client.jms.JmsFactoryFactory;
import com.ibm.msg.client.wmq.WMQConstants;
import com.ibm.mq.jms.MQConnection;
import javax.jms.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

public class TestActualDistribution {
    public static void main(String[] args) throws Exception {
        System.out.println("Testing Actual QM Distribution with CCDT");
        System.out.println("=========================================");
        
        JmsFactoryFactory ff = JmsFactoryFactory.getInstance(WMQConstants.WMQ_PROVIDER);
        
        Map<String, Integer> distribution = new HashMap<>();
        distribution.put("QM1", 0);
        distribution.put("QM2", 0);
        distribution.put("QM3", 0);
        
        for (int i = 1; i <= 12; i++) {
            // Create new factory each time to ensure fresh connection
            JmsConnectionFactory factory = ff.createConnectionFactory();
            factory.setStringProperty(WMQConstants.WMQ_CCDTURL, "file:///workspace/ccdt/ccdt.json");
            factory.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
            factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, WMQConstants.WMQ_CLIENT_RECONNECT);
            factory.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "*");
            factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, "DIST-" + i);
            factory.setStringProperty(WMQConstants.USERID, "app");
            factory.setStringProperty(WMQConstants.PASSWORD, "passw0rd");
            factory.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
            
            Connection conn = null;
            try {
                conn = factory.createConnection();
                
                if (conn instanceof MQConnection) {
                    MQConnection mqConn = (MQConnection) conn;
                    
                    // Extract QM info using reflection
                    String qmInfo = extractQMInfo(mqConn);
                    
                    String qm = "UNKNOWN";
                    if (qmInfo.contains("10.10.10.10")) {
                        qm = "QM1";
                    } else if (qmInfo.contains("10.10.10.11")) {
                        qm = "QM2";
                    } else if (qmInfo.contains("10.10.10.12")) {
                        qm = "QM3";
                    }
                    
                    System.out.printf("Connection %2d: %s (Host: %s)\n", i, qm, qmInfo);
                    distribution.put(qm, distribution.get(qm) + 1);
                }
                
                if (conn != null) conn.close();
                
                // Small random delay to avoid connection rush
                Thread.sleep((long)(Math.random() * 200));
                
            } catch (Exception e) {
                System.out.println("Connection " + i + " failed: " + e.getMessage());
            }
        }
        
        System.out.println("\n=== Distribution Results ===");
        for (Map.Entry<String, Integer> entry : distribution.entrySet()) {
            if (!entry.getKey().equals("UNKNOWN") && entry.getValue() > 0) {
                System.out.printf("%s: %d connections (%.0f%%)\n", 
                    entry.getKey(), entry.getValue(), (entry.getValue() / 12.0) * 100);
            }
        }
        
        // Check if distribution occurred
        long activeQMs = distribution.entrySet().stream()
            .filter(e -> !e.getKey().equals("UNKNOWN") && e.getValue() > 0)
            .count();
            
        if (activeQMs > 1) {
            System.out.println("\n✅ SUCCESS: Connections distributed across " + activeQMs + " Queue Managers!");
        } else {
            System.out.println("\n⚠️  All connections went to the same Queue Manager");
        }
    }
    
    private static String extractQMInfo(MQConnection mqConn) {
        try {
            // Try to get delegate
            Field[] fields = mqConn.getClass().getDeclaredFields();
            for (Field field : fields) {
                if (field.getName().equals("delegate") || field.getName().equals("commonConn")) {
                    field.setAccessible(true);
                    Object delegate = field.get(mqConn);
                    if (delegate != null) {
                        // Try to get host info
                        Method[] methods = delegate.getClass().getMethods();
                        for (Method method : methods) {
                            if (method.getName().equals("getStringProperty") && method.getParameterCount() == 1) {
                                try {
                                    Object host = method.invoke(delegate, "XMSC_WMQ_HOST_NAME");
                                    if (host != null) {
                                        return host.toString();
                                    }
                                } catch (Exception e) {
                                    // Try next approach
                                }
                            }
                        }
                        
                        // Try property enumeration
                        Method getPropNames = delegate.getClass().getMethod("getPropertyNames");
                        Object propNames = getPropNames.invoke(delegate);
                        if (propNames instanceof Enumeration) {
                            Enumeration<?> names = (Enumeration<?>) propNames;
                            while (names.hasMoreElements()) {
                                String name = names.nextElement().toString();
                                if (name.contains("HOST")) {
                                    Method getString = delegate.getClass().getMethod("getStringProperty", String.class);
                                    Object value = getString.invoke(delegate, name);
                                    if (value != null && value.toString().contains("10.10.10")) {
                                        return value.toString();
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Ignore and return unknown
        }
        return "UNKNOWN";
    }
}