import org.springframework.stereotype.Component;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import javax.jms.*;
import java.text.SimpleDateFormat;
import java.util.Date;

@Component
public class MQContainerListener {
    
    @JmsListener(destination = "TEST.QUEUE", containerFactory = "jmsFactory")
    public void onMessage(Message message) {
        // Lines 81-85: Normal message processing
        try {
            processMessage(message);
        } catch (JMSException e) {
            // Line 85: Exception during processing triggers recovery
            handleFailure(e);
        }
    }
    
    private void processMessage(Message message) throws JMSException {
        // Process the message
        System.out.println("Processing message: " + message.getJMSMessageID());
    }
    
    private void handleFailure(JMSException e) {
        System.err.println("Message processing failed: " + e.getMessage());
    }
    
    /**
     * Container Factory Configuration with Exception Listener
     * Lines 92-110: How Spring Boot detects connection failures
     */
    @Configuration
    public static class MQConfig {
        
        @Bean
        public DefaultJmsListenerContainerFactory jmsFactory(
                ConnectionFactory connectionFactory) {
            
            DefaultJmsListenerContainerFactory factory = 
                new DefaultJmsListenerContainerFactory();
            
            // Line 101-103: Set connection factory with CCDT
            factory.setConnectionFactory(connectionFactory);
            
            // Lines 104-109: CRITICAL - Exception listener for failure detection
            factory.setExceptionListener(new ExceptionListener() {
                @Override
                public void onException(JMSException exception) {
                    // Line 108: Queue Manager failure detected here
                    System.out.println("[" + timestamp() + "] QM FAILURE DETECTED!");
                    System.out.println("Error Code: " + exception.getErrorCode());
                    
                    // Lines 112-116: Connection failure codes
                    if (exception.getErrorCode().equals("MQJMS2002") ||  // Connection broken
                        exception.getErrorCode().equals("MQJMS2008") ||  // QM unavailable
                        exception.getErrorCode().equals("MQJMS1107")) {  // Connection closed
                        
                        // Line 117: Trigger reconnection via CCDT
                        triggerReconnection();
                    }
                }
            });
            
            // Lines 123-126: Session caching (important for parent-child)
            factory.setCacheLevelName("CACHE_CONNECTION");  // Cache connection
            factory.setSessionCacheSize(10);  // Cache up to 10 sessions
            
            return factory;
        }
        
        /**
         * Reconnection Handling
         * Lines 132-145: How Uniform Cluster handles failover
         */
        private static void triggerReconnection() {
            // Line 135: CCDT automatically selects new QM
            // Uniform Cluster ensures:
            // 1. Parent connection gets new QM from CCDT
            // 2. All child sessions move with parent
            // 3. CONNTAG changes to reflect new QM
            
            System.out.println("[" + timestamp() + "] RECONNECTING via CCDT...");
            
            // Lines 143-145: Uniform Cluster guarantees
            // - Parent connection atomically moves to new QM
            // - All sessions (children) move together
            // - Zero message loss during transition
        }
        
        private static String timestamp() {
            return new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
        }
    }
}