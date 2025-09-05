package com.ibm.mq.demo.utils;

import com.ibm.msg.client.jms.JmsConnectionFactory;
import com.ibm.msg.client.jms.JmsFactoryFactory;
import com.ibm.msg.client.wmq.WMQConstants;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import java.io.File;

public class MQConnectionFactory {
    
    private static final String CCDT_FILE_PATH = "/workspace/ccdt/ccdt.json";
    private static final String CCDT_URL_ENV = "CCDT_URL";
    
    public static ConnectionFactory createConnectionFactory() throws JMSException {
        JmsFactoryFactory ff = JmsFactoryFactory.getInstance(WMQConstants.WMQ_PROVIDER);
        JmsConnectionFactory factory = ff.createConnectionFactory();
        
        // Get CCDT URL from environment or use default
        String ccdtUrl = System.getenv(CCDT_URL_ENV);
        if (ccdtUrl == null || ccdtUrl.isEmpty()) {
            // Check if file exists in container
            File ccdtFile = new File(CCDT_FILE_PATH);
            if (ccdtFile.exists()) {
                ccdtUrl = "file://" + CCDT_FILE_PATH;
            } else {
                // Fallback to relative path for local testing
                ccdtUrl = "file:./mq/ccdt/ccdt.json";
            }
        }
        
        System.out.println("Using CCDT URL: " + ccdtUrl);
        
        // Configure connection factory to use CCDT
        factory.setStringProperty(WMQConstants.WMQ_CCDTURL, ccdtUrl);
        factory.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
        
        // Enable automatic reconnection for uniform cluster support
        factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, WMQConstants.WMQ_CLIENT_RECONNECT);
        factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_TIMEOUT, 1800); // 30 minutes
        
        // Set application name for monitoring
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, "UniformClusterDemo");
        
        // Enable connection balancing
        factory.setBooleanProperty(WMQConstants.WMQ_SYNCPOINT_ALL_GETS, false);
        
        // Set connection options for uniform cluster
        factory.setIntProperty(WMQConstants.WMQ_SHARE_CONV_ALLOWED, 10);
        
        // Configure SSL if needed (disabled for demo)
        factory.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
        
        return factory;
    }
}