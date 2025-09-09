import com.ibm.msg.client.jms.JmsConnectionFactory;
import com.ibm.msg.client.jms.JmsFactoryFactory;
import com.ibm.msg.client.wmq.WMQConstants;
import javax.jms.*;

public class QuickDistributionTest {
    public static void main(String[] args) throws Exception {
        System.out.println("Quick Distribution Test - Creating 10 connections");
        System.out.println("=================================================");
        
        JmsFactoryFactory ff = JmsFactoryFactory.getInstance(WMQConstants.WMQ_PROVIDER);
        
        int qm1Count = 0, qm2Count = 0, qm3Count = 0;
        
        for (int i = 1; i <= 10; i++) {
            // Create new factory for each connection to ensure independence
            JmsConnectionFactory factory = ff.createConnectionFactory();
            factory.setStringProperty(WMQConstants.WMQ_CCDTURL, "file:///workspace/ccdt/ccdt.json");
            factory.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
            factory.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "*");
            factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, "DIST-TEST-" + i);
            factory.setStringProperty(WMQConstants.USERID, "app");
            factory.setStringProperty(WMQConstants.PASSWORD, "passw0rd");
            
            try {
                Connection conn = factory.createConnection();
                ConnectionMetaData meta = conn.getMetaData();
                String providerVersion = meta.getProviderVersion();
                
                // The provider version contains QM info
                String qm = "UNKNOWN";
                if (providerVersion.contains("QM1")) {
                    qm = "QM1";
                    qm1Count++;
                } else if (providerVersion.contains("QM2")) {
                    qm = "QM2";
                    qm2Count++;
                } else if (providerVersion.contains("QM3")) {
                    qm = "QM3";
                    qm3Count++;
                }
                
                System.out.println("Connection " + i + ": Connected to " + qm);
                
                conn.close();
                Thread.sleep(100); // Small delay between connections
            } catch (Exception e) {
                System.out.println("Connection " + i + ": Failed - " + e.getMessage());
            }
        }
        
        System.out.println("\nDistribution Summary:");
        System.out.println("QM1: " + qm1Count + " connections (" + (qm1Count * 10) + "%)");
        System.out.println("QM2: " + qm2Count + " connections (" + (qm2Count * 10) + "%)");
        System.out.println("QM3: " + qm3Count + " connections (" + (qm3Count * 10) + "%)");
        
        if (qm1Count > 0 && qm2Count > 0 && qm3Count > 0) {
            System.out.println("\n✅ SUCCESS: Connections distributed across all QMs!");
        } else {
            System.out.println("\n⚠️  Warning: Not all QMs received connections");
        }
    }
}