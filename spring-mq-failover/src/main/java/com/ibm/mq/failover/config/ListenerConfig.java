package com.ibm.mq.failover.config;

import com.ibm.mq.failover.listener.FailoverMessageListener;
import jakarta.jms.ConnectionFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.config.JmsListenerContainerFactory;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.util.ErrorHandler;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class ListenerConfig {
    
    @Value("${ibm.mq.connection.pool.sessions-per-connection:5}")
    private int sessionsPerConnection;
    
    @Value("${ibm.mq.connection.pool.parent-connections:2}")
    private int parentConnections;
    
    @Bean
    public JmsListenerContainerFactory<DefaultMessageListenerContainer> mqListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        
        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        
        // Configure concurrent consumers (child sessions)
        factory.setConcurrency(String.format("%d-%d", 
            sessionsPerConnection, 
            parentConnections * sessionsPerConnection));
        
        // Session configuration
        factory.setSessionTransacted(true);
        factory.setSessionAcknowledgeMode(jakarta.jms.Session.AUTO_ACKNOWLEDGE);
        
        // Recovery settings for failover
        factory.setRecoveryInterval(5000L); // 5 seconds
        factory.setBackOff(new org.springframework.util.backoff.ExponentialBackOff());
        
        // Error handler for connection failures
        factory.setErrorHandler(new ErrorHandler() {
            @Override
            public void handleError(Throwable t) {
                log.error("Error in message listener container", t);
                if (isConnectionError(t)) {
                    log.warn("Connection error detected, container will attempt recovery");
                }
            }
            
            private boolean isConnectionError(Throwable t) {
                String message = t.getMessage();
                return message != null && (
                    message.contains("connection") || 
                    message.contains("MQRC") ||
                    message.contains("JMS"));
            }
        });
        
        // Cache settings
        factory.setCacheLevel(DefaultJmsListenerContainerFactory.CACHE_CONNECTION);
        
        log.info("Configured JMS Listener Container Factory with {} concurrent consumers", 
            parentConnections * sessionsPerConnection);
        
        return factory;
    }
    
    @Bean
    public DefaultMessageListenerContainer customListenerContainer(
            ConnectionFactory connectionFactory,
            FailoverMessageListener messageListener) {
        
        DefaultMessageListenerContainer container = new DefaultMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setDestinationName("DEV.QUEUE.1");
        container.setMessageListener(messageListener);
        
        // Configure multiple sessions per connection
        container.setConcurrentConsumers(sessionsPerConnection);
        container.setMaxConcurrentConsumers(parentConnections * sessionsPerConnection);
        
        // Session pooling
        container.setCacheLevel(DefaultMessageListenerContainer.CACHE_SESSION);
        container.setSessionTransacted(true);
        
        // Recovery configuration
        container.setRecoveryInterval(3000L);
        container.setAcceptMessagesWhileStopping(false);
        
        // Connection failure handling
        container.setExceptionListener(ex -> {
            log.error("JMS Exception in container: {}", ex.getMessage());
            if (ex.getMessage().contains("MQRC_Q_MGR_NOT_AVAILABLE")) {
                log.warn("Queue Manager not available, triggering failover recovery");
            }
        });
        
        return container;
    }
}