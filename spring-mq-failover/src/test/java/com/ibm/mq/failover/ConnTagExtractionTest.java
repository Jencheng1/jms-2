package com.ibm.mq.failover;

import com.ibm.mq.jms.MQConnection;
import com.ibm.mq.jms.MQConnectionFactory;
import com.ibm.mq.jms.MQSession;
import com.ibm.msg.client.jms.JmsPropertyContext;
import com.ibm.msg.client.wmq.WMQConstants;
import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import jakarta.jms.Session;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class to verify correct CONNTAG extraction
 * This proves the difference between correct and incorrect approaches
 */
public class ConnTagExtractionTest {
    
    private static final Logger log = LoggerFactory.getLogger(ConnTagExtractionTest.class);
    
    /**
     * Demonstrates the CORRECT way to extract CONNTAG
     */
    @Test
    public void testCorrectConnTagExtraction() throws JMSException {
        // Setup connection factory
        MQConnectionFactory factory = new MQConnectionFactory();
        factory.setStringProperty(WMQConstants.WMQ_CCDTURL, "file:///workspace/ccdt/ccdt.json");
        factory.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "*");
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, "TEST-CONNTAG-" + System.currentTimeMillis());
        factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, WMQConstants.WMQ_CLIENT_RECONNECT);
        
        Connection connection = null;
        Session session = null;
        
        try {
            connection = factory.createConnection();
            connection.start();
            
            if (connection instanceof MQConnection) {
                MQConnection mqConn = (MQConnection) connection;
                JmsPropertyContext context = mqConn.getPropertyContext();
                
                // CORRECT: Use JMS_IBM_CONNECTION_TAG
                String correctConnTag = context.getStringProperty(WMQConstants.JMS_IBM_CONNECTION_TAG);
                
                // WRONG: Using CORRELID (for comparison)
                Object wrongConnTag = context.getObjectProperty(WMQConstants.JMS_IBM_MQMD_CORRELID);
                
                // Log both values
                log.info("=== CONNTAG Extraction Comparison ===");
                log.info("CORRECT (JMS_IBM_CONNECTION_TAG): {}", correctConnTag);
                log.info("WRONG (JMS_IBM_MQMD_CORRELID): {}", wrongConnTag);
                
                // Verify correct format
                assertNotNull(correctConnTag, "CONNTAG should not be null");
                assertTrue(correctConnTag.startsWith("MQCT"), 
                    "CONNTAG should start with MQCT, got: " + correctConnTag);
                assertTrue(correctConnTag.contains("QM"), 
                    "CONNTAG should contain QM name, got: " + correctConnTag);
                assertTrue(correctConnTag.length() > 20, 
                    "CONNTAG should be longer than 20 chars, got: " + correctConnTag.length());
                
                // Pattern: MQCT<16-char-handle><QM-name>_<timestamp>
                log.info("CONNTAG Format Analysis:");
                if (correctConnTag.length() >= 20) {
                    String prefix = correctConnTag.substring(0, 4);
                    String handle = correctConnTag.substring(4, 20);
                    String qmAndTimestamp = correctConnTag.substring(20);
                    
                    log.info("  Prefix: {} (should be 'MQCT')", prefix);
                    log.info("  Handle: {} (16 characters)", handle);
                    log.info("  QM+Timestamp: {}", qmAndTimestamp);
                    
                    assertEquals("MQCT", prefix, "Prefix should be MQCT");
                    assertEquals(16, handle.length(), "Handle should be 16 characters");
                    assertTrue(qmAndTimestamp.matches("QM[123].*"), 
                        "Should contain QM1, QM2, or QM3");
                }
                
                // Test session inherits parent's CONNTAG
                session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                
                if (session instanceof MQSession) {
                    MQSession mqSession = (MQSession) session;
                    JmsPropertyContext sessionContext = mqSession.getPropertyContext();
                    
                    String sessionConnTag = sessionContext.getStringProperty(WMQConstants.JMS_IBM_CONNECTION_TAG);
                    
                    log.info("Parent Connection CONNTAG: {}", correctConnTag);
                    log.info("Child Session CONNTAG: {}", sessionConnTag);
                    
                    // Verify session inherits parent's CONNTAG
                    assertEquals(correctConnTag, sessionConnTag, 
                        "Session should inherit parent's CONNTAG");
                }
                
                // Also test CONNECTION_ID extraction
                String connectionId = context.getStringProperty(WMQConstants.JMS_IBM_CONNECTION_ID);
                log.info("CONNECTION_ID: {}", connectionId);
                
                assertNotNull(connectionId, "CONNECTION_ID should not be null");
                assertTrue(connectionId.startsWith("414D5143"), 
                    "CONNECTION_ID should start with AMQC in hex (414D5143)");
                assertEquals(48, connectionId.length(), 
                    "CONNECTION_ID should be 48 characters");
                
                // Extract Queue Manager from CONNECTION_ID
                if (connectionId.length() >= 24) {
                    String qmHex = connectionId.substring(8, 24);
                    log.info("QM from CONNECTION_ID (hex): {}", qmHex);
                    
                    // Convert hex to string to get QM name
                    StringBuilder qmName = new StringBuilder();
                    for (int i = 0; i < qmHex.length(); i += 2) {
                        String hex = qmHex.substring(i, i + 2);
                        int val = Integer.parseInt(hex, 16);
                        if (val > 32 && val < 127) { // Printable ASCII
                            qmName.append((char) val);
                        }
                    }
                    log.info("Extracted QM name: {}", qmName.toString().trim());
                }
                
                // Get resolved Queue Manager
                String resolvedQM = context.getStringProperty(WMQConstants.JMS_IBM_RESOLVED_QUEUE_MANAGER);
                log.info("Resolved Queue Manager: {}", resolvedQM);
                
                // Summary
                log.info("\n=== CONNTAG Extraction Summary ===");
                log.info("✓ Full CONNTAG: {}", correctConnTag);
                log.info("✓ CONNECTION_ID: {}", connectionId);
                log.info("✓ Queue Manager: {}", resolvedQM);
                log.info("✓ Session inherits parent CONNTAG: VERIFIED");
                
            }
            
        } finally {
            if (session != null) {
                try { session.close(); } catch (Exception e) {}
            }
            if (connection != null) {
                try { connection.close(); } catch (Exception e) {}
            }
        }
    }
    
    /**
     * Test to demonstrate all important MQ connection properties
     */
    @Test
    public void testAllMQConnectionProperties() throws JMSException {
        MQConnectionFactory factory = new MQConnectionFactory();
        factory.setStringProperty(WMQConstants.WMQ_CCDTURL, "file:///workspace/ccdt/ccdt.json");
        factory.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "*");
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, "PROPERTY-TEST-" + System.currentTimeMillis());
        
        Connection connection = null;
        
        try {
            connection = factory.createConnection();
            
            if (connection instanceof MQConnection) {
                MQConnection mqConn = (MQConnection) connection;
                JmsPropertyContext context = mqConn.getPropertyContext();
                
                log.info("\n=== All MQ Connection Properties ===");
                
                // Critical properties for parent-child tracking
                logProperty(context, "JMS_IBM_CONNECTION_TAG", WMQConstants.JMS_IBM_CONNECTION_TAG);
                logProperty(context, "JMS_IBM_CONNECTION_ID", WMQConstants.JMS_IBM_CONNECTION_ID);
                logProperty(context, "JMS_IBM_RESOLVED_QUEUE_MANAGER", WMQConstants.JMS_IBM_RESOLVED_QUEUE_MANAGER);
                logProperty(context, "JMS_IBM_HOST_NAME", WMQConstants.JMS_IBM_HOST_NAME);
                logProperty(context, "JMS_IBM_PORT", WMQConstants.JMS_IBM_PORT);
                
                // Application identification
                logProperty(context, "JMS_IBM_APPLICATIONNAME", WMQConstants.JMS_IBM_APPLICATIONNAME);
                
                // These should NOT be used for CONNTAG
                log.info("\n--- Properties NOT for CONNTAG ---");
                logProperty(context, "JMS_IBM_MQMD_CORRELID (WRONG for CONNTAG)", WMQConstants.JMS_IBM_MQMD_CORRELID);
                logProperty(context, "JMS_IBM_MQMD_MSGID", WMQConstants.JMS_IBM_MQMD_MSGID);
                logProperty(context, "JMS_IBM_MQMD_REPLYTOQMGR", WMQConstants.JMS_IBM_MQMD_REPLYTOQMGR);
            }
            
        } finally {
            if (connection != null) {
                try { connection.close(); } catch (Exception e) {}
            }
        }
    }
    
    private void logProperty(JmsPropertyContext context, String name, String propertyKey) {
        try {
            Object value = context.getObjectProperty(propertyKey);
            if (value != null) {
                log.info("{}: {} (Type: {})", name, value, value.getClass().getSimpleName());
            } else {
                log.info("{}: null", name);
            }
        } catch (JMSException e) {
            log.info("{}: Error - {}", name, e.getMessage());
        }
    }
}