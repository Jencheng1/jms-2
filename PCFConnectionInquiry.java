import com.ibm.mq.*;
import com.ibm.mq.constants.*;
import com.ibm.mq.headers.pcf.*;
import java.util.*;

/**
 * PCF Connection Inquiry - Fixed version
 * Tests different parameter combinations to find the correct format
 */
public class PCFConnectionInquiry {
    public static void main(String[] args) throws Exception {
        MQQueueManager qmgr = null;
        PCFMessageAgent agent = null;
        
        try {
            // Connect to QM
            Hashtable<String, Object> props = new Hashtable<>();
            props.put(CMQC.HOST_NAME_PROPERTY, "10.10.10.10");
            props.put(CMQC.PORT_PROPERTY, 1414);
            props.put(CMQC.CHANNEL_PROPERTY, "APP.SVRCONN");
            props.put(CMQC.USE_MQCSP_AUTHENTICATION_PROPERTY, false);
            
            System.out.println("Connecting to QM1...");
            qmgr = new MQQueueManager("QM1", props);
            agent = new PCFMessageAgent(qmgr);
            System.out.println("Connected successfully\n");
            
            // Test 1: Basic inquiry without parameters (this works)
            System.out.println("Test 1: Basic INQUIRE_CONNECTION without parameters");
            try {
                PCFMessage request = new PCFMessage(CMQCFC.MQCMD_INQUIRE_CONNECTION);
                PCFMessage[] responses = agent.send(request);
                System.out.println("✓ Success: " + responses.length + " connections found\n");
            } catch (Exception e) {
                System.out.println("✗ Failed: " + e.getMessage() + "\n");
            }
            
            // Test 2: With generic WHERE clause
            System.out.println("Test 2: INQUIRE_CONNECTION with generic WHERE");
            try {
                PCFMessage request = new PCFMessage(CMQCFC.MQCMD_INQUIRE_CONNECTION);
                // Use generic WHERE instead of specific parameters
                request.addParameter(CMQCFC.MQIACF_CONNECTION_ID, new byte[24]); // Wildcard
                PCFMessage[] responses = agent.send(request);
                System.out.println("✓ Success: " + responses.length + " connections found\n");
            } catch (Exception e) {
                System.out.println("✗ Failed: " + e.getMessage() + "\n");
            }
            
            // Test 3: With ByteString filter for connection ID
            System.out.println("Test 3: INQUIRE_CONNECTION with ByteString filter");
            try {
                PCFMessage request = new PCFMessage(CMQCFC.MQCMD_INQUIRE_CONNECTION);
                // Use ByteString parameter for connection ID (all connections)
                byte[] allConnId = new byte[48]; // MQ connection ID is 48 bytes
                Arrays.fill(allConnId, (byte)0);
                request.addParameter(CMQCFC.MQBACF_GENERIC_CONNECTION_ID, allConnId);
                PCFMessage[] responses = agent.send(request);
                System.out.println("✓ Success: " + responses.length + " connections found\n");
            } catch (Exception e) {
                System.out.println("✗ Failed: " + e.getMessage() + "\n");
            }
            
            // Test 4: Query with minimal required parameters
            System.out.println("Test 4: INQUIRE_CONNECTION with minimal parameters");
            try {
                PCFMessage request = new PCFMessage(CMQCFC.MQCMD_INQUIRE_CONNECTION);
                // Don't specify CONN_INFO_TYPE - let it default
                // Don't specify attributes - let it default
                PCFMessage[] responses = agent.send(request);
                
                System.out.println("✓ Success: " + responses.length + " connections found");
                
                // Try to extract some data
                if (responses.length > 0) {
                    System.out.println("\nSample connection details:");
                    for (int i = 0; i < Math.min(3, responses.length); i++) {
                        try {
                            // Try different parameter extractions
                            String channel = responses[i].getStringParameterValue(CMQCFC.MQCACH_CHANNEL_NAME);
                            String conname = responses[i].getStringParameterValue(CMQCFC.MQCACH_CONNECTION_NAME);
                            System.out.println("  Conn " + i + ": Channel=" + channel + ", ConnName=" + conname);
                            
                            // Try to get APPLTAG
                            try {
                                String appltag = responses[i].getStringParameterValue(CMQCFC.MQCACF_APPL_TAG);
                                System.out.println("    APPLTAG=" + appltag);
                            } catch (Exception e) {
                                // No APPLTAG
                            }
                        } catch (Exception e) {
                            System.out.println("  Conn " + i + ": Could not extract details");
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("✗ Failed: " + e.getMessage() + "\n");
            }
            
            // Test 5: Filter by channel name (string parameter)
            System.out.println("\nTest 5: INQUIRE_CONNECTION filtered by channel");
            try {
                PCFMessage request = new PCFMessage(CMQCFC.MQCMD_INQUIRE_CONNECTION);
                // Add channel filter as string parameter
                request.addParameter(CMQCFC.MQCACH_CHANNEL_NAME, "APP.SVRCONN");
                PCFMessage[] responses = agent.send(request);
                System.out.println("✓ Success: " + responses.length + " connections on APP.SVRCONN\n");
            } catch (Exception e) {
                System.out.println("✗ Failed: " + e.getMessage() + "\n");
            }
            
            // Test 6: Create connection with known APPLTAG and query it
            System.out.println("Test 6: Query specific APPLTAG");
            String testTag = "PCF-TEST-" + System.currentTimeMillis();
            
            // Create a test connection with specific APPLTAG
            createTestConnection(testTag);
            Thread.sleep(1000); // Let it establish
            
            try {
                PCFMessage request = new PCFMessage(CMQCFC.MQCMD_INQUIRE_CONNECTION);
                
                // Get all connections and filter manually
                PCFMessage[] responses = agent.send(request);
                int found = 0;
                
                for (PCFMessage resp : responses) {
                    try {
                        String tag = resp.getStringParameterValue(CMQCFC.MQCACF_APPL_TAG);
                        if (testTag.equals(tag)) {
                            found++;
                            String channel = resp.getStringParameterValue(CMQCFC.MQCACH_CHANNEL_NAME);
                            String conname = resp.getStringParameterValue(CMQCFC.MQCACH_CONNECTION_NAME);
                            System.out.println("✓ Found connection with APPLTAG=" + testTag);
                            System.out.println("  Channel=" + channel + ", ConnName=" + conname);
                        }
                    } catch (PCFException e) {
                        // No APPLTAG for this connection
                    }
                }
                
                if (found > 0) {
                    System.out.println("✓ Successfully found " + found + " connection(s) with our APPLTAG\n");
                } else {
                    System.out.println("⚠ No connections found with APPLTAG=" + testTag + "\n");
                }
                
            } catch (Exception e) {
                System.out.println("✗ Failed: " + e.getMessage() + "\n");
            }
            
            System.out.println("========================================");
            System.out.println("PCF INQUIRE_CONNECTION Testing Complete");
            System.out.println("========================================");
            
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (agent != null) try { agent.disconnect(); } catch (Exception e) {}
            if (qmgr != null) try { qmgr.disconnect(); } catch (Exception e) {}
        }
    }
    
    private static void createTestConnection(String appTag) {
        try {
            Hashtable<String, Object> props = new Hashtable<>();
            props.put(CMQC.HOST_NAME_PROPERTY, "10.10.10.10");
            props.put(CMQC.PORT_PROPERTY, 1414);
            props.put(CMQC.CHANNEL_PROPERTY, "APP.SVRCONN");
            props.put(CMQC.APPNAME_PROPERTY, appTag);
            
            MQQueueManager testQm = new MQQueueManager("QM1", props);
            // Keep it open - it will be cleaned up later
        } catch (Exception e) {
            System.out.println("Could not create test connection: " + e.getMessage());
        }
    }
}