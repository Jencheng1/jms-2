#\!/bin/bash

echo "========================================="
echo " SIMPLE PARENT-CHILD RELATIONSHIP TEST"
echo "========================================="
echo

# Unique tag for this test
TAG="SIMPLE-$(date +%s)"

# Create a single connection with sessions
echo "Creating test connection with tag: $TAG"

# Run simple Java test
cat > SimpleTest.java << 'JAVA'
import com.ibm.msg.client.jms.*;
import com.ibm.msg.client.wmq.WMQConstants;
import javax.jms.*;

public class SimpleTest {
    public static void main(String[] args) throws Exception {
        String tag = args[0];
        
        // Create factory
        JmsFactoryFactory ff = JmsFactoryFactory.getInstance(WMQConstants.WMQ_PROVIDER);
        JmsConnectionFactory factory = ff.createConnectionFactory();
        
        // Direct connection to QM1
        factory.setStringProperty(WMQConstants.WMQ_HOST_NAME, "10.10.10.10");
        factory.setIntProperty(WMQConstants.WMQ_PORT, 1414);
        factory.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "QM1");
        factory.setStringProperty(WMQConstants.WMQ_CHANNEL, "APP.SVRCONN");
        factory.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, tag);
        
        // Create connection
        Connection conn = factory.createConnection("app", "passw0rd");
        conn.start();
        
        System.out.println("Created parent connection with tag: " + tag);
        
        // Create 3 sessions
        for (int i = 1; i <= 3; i++) {
            Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
            System.out.println("  Created session " + i);
        }
        
        System.out.println("\nHolding connection open for 10 seconds...");
        Thread.sleep(10000);
        
        conn.close();
        System.out.println("Connection closed");
    }
}
JAVA

# Compile
javac -cp "libs/*:." SimpleTest.java

# Run test
java -cp "libs/*:." SimpleTest "$TAG" &
TEST_PID=$\!

# Wait for connection to establish
sleep 3

# Check connections via MQSC
echo
echo "Checking MQ connections with APPTAG=$TAG:"
echo

for qm in 1 2 3; do
    echo "QM$qm connections:"
    docker exec qm$qm bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TAG') CHANNEL CONNAME APPLTAG\" | runmqsc QM$qm" 2>/dev/null | grep -E "CONN\(|CHANNEL\(|CONNAME\(|APPLTAG\(" | sed 's/^/  /'
    echo
done

# Wait for test to complete
wait $TEST_PID

echo "Test complete\!"
