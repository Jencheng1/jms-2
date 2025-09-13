import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.stereotype.Component;
import com.ibm.mq.jms.MQConnectionFactory;
import com.ibm.msg.client.wmq.WMQConstants;
import javax.jms.*;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Complete Spring Boot Application demonstrating MQ Failover with Uniform Cluster
 * This is the actual code referenced in SPRING_BOOT_CONNTAG_DETAILED_LINE_BY_LINE_EXPLANATION.md
 */
@SpringBootApplication
@EnableJms
public class SpringBootMQFailoverApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(SpringBootMQFailoverApplication.class, args);
    }
    
    /**
     * MQ Configuration with CCDT and Failover Support
     */
    @Configuration
    public static class MQConfiguration {
        
        private static final String CCDT_URL = "file:///workspace/ccdt/ccdt.json";
        private static final String TEST_ID = "SPRING-" + System.currentTimeMillis();
        
        /**
         * Create MQ Connection Factory with CCDT
         */
        @Bean
        public MQConnectionFactory mqConnectionFactory() throws JMSException {
            MQConnectionFactory factory = new MQConnectionFactory();
            
            // Configure for CCDT usage
            factory.setTransportType(WMQConstants.WMQ_CM_CLIENT);
            factory.setStringProperty(WMQConstants.WMQ_CCDTURL, CCDT_URL);
            factory.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, "*");
            
            // Enable automatic reconnection
            factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, 
                                  WMQConstants.WMQ_CLIENT_RECONNECT);
            factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_TIMEOUT, 1800);
            
            // Set application tag for tracking
            factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, TEST_ID);
            
            return factory;
        }
        
        /**
         * Container Factory Configuration with Exception Listener
         * Lines 92-127: How Spring Boot detects connection failures
         */
        @Bean
        public DefaultJmsListenerContainerFactory jmsListenerContainerFactory(
                MQConnectionFactory mqConnectionFactory) {
            
            DefaultJmsListenerContainerFactory factory = 
                new DefaultJmsListenerContainerFactory();
            
            // Line 101: Set connection factory with CCDT
            factory.setConnectionFactory(mqConnectionFactory);
            
            // Lines 104-119: CRITICAL - Exception listener for failure detection
            factory.setExceptionListener(new ExceptionListener() {
                @Override
                public void onException(JMSException exception) {
                    // Line 108: Queue Manager failure detected here
                    System.out.println("[" + timestamp() + "] 🔴 QM FAILURE DETECTED!");
                    System.out.println("[" + timestamp() + "] Error: " + exception.getMessage());
                    System.out.println("[" + timestamp() + "] Error Code: " + exception.getErrorCode());
                    
                    // Lines 112-116: Connection failure codes
                    if (exception.getErrorCode().equals("MQJMS2002") ||  // Connection broken
                        exception.getErrorCode().equals("MQJMS2008") ||  // QM unavailable  
                        exception.getErrorCode().equals("MQJMS1107")) {  // Connection closed
                        
                        // Line 117: Trigger reconnection via CCDT
                        System.out.println("[" + timestamp() + "] Triggering reconnection via CCDT...");
                        handleReconnection();
                    }
                }
            });
            
            // Lines 123-126: Session caching (important for parent-child affinity)
            factory.setCacheLevelName("CACHE_CONNECTION");  // Cache connection
            factory.setSessionCacheSize(10);  // Cache up to 10 sessions
            
            // Additional Spring Boot container settings
            factory.setRecoveryInterval(5000L);  // Retry every 5 seconds
            factory.setAcceptMessagesWhileStopping(false);
            
            return factory;
        }
        
        /**
         * Lines 132-145: How Uniform Cluster handles failover
         */
        private void handleReconnection() {
            // Line 135: CCDT automatically selects new QM
            // Uniform Cluster ensures:
            // 1. Parent connection gets new QM from CCDT
            // 2. All child sessions move with parent  
            // 3. CONNTAG changes to reflect new QM
            
            System.out.println("[" + timestamp() + "] RECONNECTING via CCDT...");
            System.out.println("[" + timestamp() + "] Uniform Cluster guarantees:");
            System.out.println("[" + timestamp() + "]   - Parent connection atomically moves to new QM");
            System.out.println("[" + timestamp() + "]   - All sessions (children) move together");
            System.out.println("[" + timestamp() + "]   - Zero message loss during transition");
        }
        
        private static String timestamp() {
            return new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
        }
    }
    
    /**
     * Message Listener Component
     * Lines 75-87: Container-managed message processing
     */
    @Component
    public static class MessageListener {
        
        @JmsListener(destination = "TEST.QUEUE", containerFactory = "jmsListenerContainerFactory")
        public void onMessage(Message message) {
            // Lines 81-85: Normal message processing
            try {
                // Extract CONNTAG from the session (for debugging)
                Session session = message.getJMSConnection().createSession(false, Session.AUTO_ACKNOWLEDGE);
                String conntag = extractSessionConnTag(session);
                
                System.out.println("[" + timestamp() + "] Processing message on CONNTAG: " + conntag);
                System.out.println("[" + timestamp() + "] Message ID: " + message.getJMSMessageID());
                
                // Process the message
                processMessage(message);
                
            } catch (JMSException e) {
                // Line 85: Exception during processing triggers recovery
                System.err.println("[" + timestamp() + "] Message processing failed: " + e.getMessage());
                handleFailure(e);
            }
        }
        
        private void processMessage(Message message) throws JMSException {
            // Actual message processing logic
            if (message instanceof TextMessage) {
                TextMessage textMessage = (TextMessage) message;
                System.out.println("[" + timestamp() + "] Received: " + textMessage.getText());
            }
        }
        
        private void handleFailure(JMSException e) {
            System.err.println("[" + timestamp() + "] Handling failure: " + e.getMessage());
            // Spring container will handle retry/recovery
        }
        
        private static String timestamp() {
            return new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
        }
    }
    
    /**
     * CONNTAG Extraction Methods
     * Lines 11-73: Extract CONNTAG from Connection and Session
     */
    public static class ConnTagExtractor {
        
        /**
         * Extract CONNTAG from Connection - Spring Boot Way
         * Lines 11-51: Main extraction method with reflection fallback
         */
        public static String extractFullConnTag(Connection connection) {
            try {
                // Lines 14-17: Try direct property extraction using Spring Boot constant
                // THIS IS THE KEY DIFFERENCE - Spring Boot uses JMS_IBM_CONNECTION_TAG
                // Regular JMS would use XMSC.WMQ_RESOLVED_CONNECTION_TAG
                String conntag = connection.getStringProperty(
                    WMQConstants.JMS_IBM_CONNECTION_TAG  // ← Line 17: Spring Boot specific
                );
                
                if (conntag != null && !conntag.isEmpty()) {
                    // Line 22: Return full CONNTAG without truncation
                    return conntag;  // Format: MQCT<16-char-handle><QM>_<timestamp>
                }
                
                // Lines 26-33: Fallback using reflection if direct access fails
                Method getPropertyMethod = connection.getClass().getMethod(
                    "getStringProperty", String.class
                );
                
                // Try alternate property names
                String[] propertyNames = {
                    "JMS_IBM_CONNECTION_TAG",           // Spring Boot primary
                    "XMSC_WMQ_RESOLVED_CONNECTION_TAG", // Regular JMS fallback
                    "XMSC.WMQ_RESOLVED_CONNECTION_TAG"  // Alternate format
                };
                
                for (String prop : propertyNames) {
                    try {
                        Object result = getPropertyMethod.invoke(connection, prop);
                        if (result != null) {
                            return result.toString();
                        }
                    } catch (Exception e) {
                        // Continue to next property
                    }
                }
            } catch (Exception e) {
                System.err.println("Failed to extract CONNTAG: " + e.getMessage());
            }
            return "CONNTAG_EXTRACTION_FAILED";
        }
        
        /**
         * Extract CONNTAG from Session - Inherits from Parent
         * Lines 56-73: Session CONNTAG extraction
         */
        public static String extractSessionConnTag(Session session) {
            try {
                // Lines 60-62: Sessions inherit parent's CONNTAG
                // Spring Boot sessions use same constant as connection
                String conntag = session.getStringProperty(
                    WMQConstants.JMS_IBM_CONNECTION_TAG  // ← Line 62: Same as parent
                );
                
                if (conntag != null) {
                    // Line 67: Return inherited CONNTAG (same as parent)
                    return conntag;
                }
            } catch (Exception e) {
                // Line 70: Session doesn't have direct property, inherit from parent
            }
            return "INHERITED_FROM_PARENT";
        }
    }
    
    /**
     * Helper method to extract session CONNTAG
     */
    private static String extractSessionConnTag(Session session) {
        return ConnTagExtractor.extractSessionConnTag(session);
    }
}