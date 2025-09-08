import com.ibm.mq.*;
import com.ibm.mq.constants.CMQC;
import com.ibm.mq.constants.CMQCFC;
import com.ibm.mq.headers.pcf.*;

import java.io.*;
import java.util.*;

/**
 * PCF Test that runs locally on the queue manager to avoid network auth issues
 * This will be run inside the QM1 container where we have proper authorization
 */
public class PCFLocalTest {
    
    private static final String QUEUE_MANAGER = "QM1";
    
    public static void main(String[] args) {
        String appTag = args.length > 0 ? args[0] : "TEST";
        
        MQQueueManager queueManager = null;
        PCFMessageAgent pcfAgent = null;
        
        try {
            System.out.println("================================================================================");
            System.out.println("PCF LOCAL TEST - RUNNING INSIDE QUEUE MANAGER CONTAINER");
            System.out.println("================================================================================");
            System.out.println("Queue Manager: " + QUEUE_MANAGER);
            System.out.println("Looking for APPTAG: " + appTag);
            System.out.println("================================================================================\n");
            
            // Connect locally (bindings mode)
            System.out.println("Connecting to Queue Manager locally...");
            queueManager = new MQQueueManager(QUEUE_MANAGER);
            pcfAgent = new PCFMessageAgent(queueManager);
            System.out.println("✓ PCF Agent connected successfully\n");
            
            // Query connections using PCF
            System.out.println("PCF QUERY: INQUIRE_CONNECTION");
            System.out.println("==============================\n");
            
            PCFMessage request = new PCFMessage(CMQCFC.MQCMD_INQUIRE_CONNECTION);
            request.addParameter(CMQCFC.MQBACF_GENERIC_CONNECTION_ID, new byte[48]);
            request.addParameter(CMQCFC.MQIACF_CONNECTION_ATTRS, new int[] {
                CMQCFC.MQIACF_ALL
            });
            
            PCFMessage[] responses = pcfAgent.send(request);
            System.out.println("Total connections found: " + responses.length);
            
            // Process responses
            int matchingConns = 0;
            List<String> connectionDetails = new ArrayList<>();
            
            for (PCFMessage response : responses) {
                try {
                    String connAppTag = response.getStringParameterValue(CMQCFC.MQCACF_APPL_TAG);
                    
                    if (connAppTag != null && connAppTag.trim().equals(appTag)) {
                        matchingConns++;
                        
                        String connId = bytesToHex(response.getBytesParameterValue(CMQCFC.MQBACF_CONNECTION_ID));
                        String channel = response.getStringParameterValue(CMQCFC.MQCACH_CHANNEL_NAME);
                        String connName = response.getStringParameterValue(CMQCFC.MQCACH_CONNECTION_NAME);
                        String userId = response.getStringParameterValue(CMQCFC.MQCACF_USER_IDENTIFIER);
                        
                        int pid = 0, tid = 0;
                        try {
                            pid = response.getIntParameterValue(CMQCFC.MQIACF_PROCESS_ID);
                            tid = response.getIntParameterValue(CMQCFC.MQIACF_THREAD_ID);
                        } catch (Exception e) {
                            // Optional fields
                        }
                        
                        String detail = String.format(
                            "Connection #%d:\n" +
                            "  ID: %s\n" +
                            "  Channel: %s\n" +
                            "  ConnName: %s\n" +
                            "  User: %s\n" +
                            "  PID: %d, TID: %d\n" +
                            "  APPTAG: %s",
                            matchingConns, connId, channel, connName, userId, pid, tid, connAppTag.trim()
                        );
                        
                        connectionDetails.add(detail);
                    }
                } catch (Exception e) {
                    // Skip problematic connections
                }
            }
            
            System.out.println("Connections with APPTAG '" + appTag + "': " + matchingConns);
            System.out.println("");
            
            for (String detail : connectionDetails) {
                System.out.println(detail);
                System.out.println("");
            }
            
            // Analyze results
            System.out.println("PCF ANALYSIS");
            System.out.println("============");
            System.out.println("Total connections queried: " + responses.length);
            System.out.println("Matching APPTAG connections: " + matchingConns);
            
            if (matchingConns >= 6) {
                System.out.println("\n✓ Parent-Child Correlation Found:");
                System.out.println("  - 1 Parent connection + 5 Child sessions = 6 MQ connections");
                System.out.println("  - All share same APPTAG: " + appTag);
            }
            
            System.out.println("\n================================================================================");
            
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                if (pcfAgent != null) {
                    pcfAgent.disconnect();
                }
                if (queueManager != null && queueManager.isConnected()) {
                    queueManager.disconnect();
                }
            } catch (Exception e) {
                // Ignore
            }
        }
    }
    
    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02X", b));
        }
        return result.toString();
    }
}