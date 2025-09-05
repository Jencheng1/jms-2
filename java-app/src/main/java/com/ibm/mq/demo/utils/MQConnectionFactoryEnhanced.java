package com.ibm.mq.demo.utils;

import com.ibm.msg.client.jms.JmsConnectionFactory;
import com.ibm.msg.client.jms.JmsFactoryFactory;
import com.ibm.msg.client.wmq.WMQConstants;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import java.io.File;
import java.util.UUID;

/**
 * Enhanced MQ Connection Factory with correlation metadata support
 * Sets application tags that will be visible in MQSC for correlation tracking
 */
public class MQConnectionFactoryEnhanced {
    
    private static final String CCDT_FILE_PATH = "/workspace/ccdt/ccdt.json";
    private static final String CCDT_URL_ENV = "CCDT_URL";
    
    public static ConnectionFactory createConnectionFactory() throws JMSException {
        return createConnectionFactory(null, null);
    }
    
    public static ConnectionFactory createConnectionFactory(String applicationId, String applicationTag) throws JMSException {
        JmsFactoryFactory ff = JmsFactoryFactory.getInstance(WMQConstants.WMQ_PROVIDER);
        JmsConnectionFactory factory = ff.createConnectionFactory();
        
        // Get CCDT URL from environment or use default
        String ccdtUrl = System.getenv(CCDT_URL_ENV);
        if (ccdtUrl == null || ccdtUrl.isEmpty()) {
            File ccdtFile = new File(CCDT_FILE_PATH);
            if (ccdtFile.exists()) {
                ccdtUrl = "file://" + CCDT_FILE_PATH;
            } else {
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
        
        // Set application identification for MQSC visibility
        if (applicationId != null && !applicationId.isEmpty()) {
            // This will appear in APPLTAG field in MQSC DIS CONN
            factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, applicationId);
        } else {
            factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, "UniformClusterDemo");
        }
        
        // Set application tag for correlation
        if (applicationTag != null && !applicationTag.isEmpty()) {
            // This custom property will help with correlation
            factory.setStringProperty("JMS_IBM_MQMD_ApplIdentityData", applicationTag);
        }
        
        // Enable connection balancing
        factory.setBooleanProperty(WMQConstants.WMQ_SYNCPOINT_ALL_GETS, false);
        
        // Set connection options for uniform cluster
        factory.setIntProperty(WMQConstants.WMQ_SHARE_CONV_ALLOWED, 10);
        
        // Configure SSL if needed (disabled for demo)
        factory.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
        
        // Additional properties for tracking
        factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_DISABLED, WMQConstants.WMQ_CLIENT_RECONNECT);
        
        // Set temporary queue prefix for better identification
        factory.setStringProperty(WMQConstants.WMQ_TEMPORARY_MODEL, "SYSTEM.DEFAULT.MODEL.QUEUE");
        
        // Enable message property conversion
        factory.setBooleanProperty(WMQConstants.WMQ_MESSAGE_BODY, WMQConstants.WMQ_MESSAGE_BODY_JMS);
        
        return factory;
    }
    
    /**
     * Create a connection factory with specific producer correlation
     */
    public static ConnectionFactory createProducerConnectionFactory(int producerId) throws JMSException {
        String appId = "PRODUCER-" + producerId;
        String appTag = "PROD" + producerId + "-" + UUID.randomUUID().toString().substring(0, 8);
        
        System.out.println("[MQConnectionFactory] Creating producer connection factory:");
        System.out.println("  Application ID: " + appId);
        System.out.println("  Application Tag: " + appTag);
        
        return createConnectionFactory(appId, appTag);
    }
    
    /**
     * Create a connection factory with specific consumer correlation
     */
    public static ConnectionFactory createConsumerConnectionFactory(int consumerId) throws JMSException {
        String appId = "CONSUMER-" + consumerId;
        String appTag = "CONS" + consumerId + "-" + UUID.randomUUID().toString().substring(0, 8);
        
        System.out.println("[MQConnectionFactory] Creating consumer connection factory:");
        System.out.println("  Application ID: " + appId);
        System.out.println("  Application Tag: " + appTag);
        
        return createConnectionFactory(appId, appTag);
    }
}