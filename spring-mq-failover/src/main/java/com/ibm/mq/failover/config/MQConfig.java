package com.ibm.mq.failover.config;

import com.ibm.mq.jms.MQConnectionFactory;
import com.ibm.msg.client.wmq.WMQConstants;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jms.connection.CachingConnectionFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.jms.support.converter.MappingJackson2MessageConverter;
import org.springframework.jms.support.converter.MessageConverter;
import org.springframework.jms.support.converter.MessageType;

@Slf4j
@Configuration
public class MQConfig {
    
    @Value("${ibm.mq.ccdt-url}")
    private String ccdtUrl;
    
    @Value("${ibm.mq.queue-manager}")
    private String queueManager;
    
    @Value("${ibm.mq.channel}")
    private String channel;
    
    @Value("${ibm.mq.application-name}")
    private String applicationName;
    
    @Value("${ibm.mq.user}")
    private String user;
    
    @Value("${ibm.mq.password}")
    private String password;
    
    @Value("${ibm.mq.client-reconnect}")
    private boolean clientReconnect;
    
    @Value("${ibm.mq.reconnect-timeout}")
    private int reconnectTimeout;
    
    @Bean
    @Primary
    public MQConnectionFactory mqConnectionFactory() throws JMSException {
        MQConnectionFactory factory = new MQConnectionFactory();
        
        // Set CCDT URL for uniform cluster configuration
        factory.setStringProperty(WMQConstants.WMQ_CCDTURL, ccdtUrl);
        factory.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, queueManager);
        
        // Set application identification
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, 
            applicationName + "-" + System.currentTimeMillis());
        
        // Enable auto-reconnect for failover
        if (clientReconnect) {
            factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, 
                WMQConstants.WMQ_CLIENT_RECONNECT);
            factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_TIMEOUT, reconnectTimeout);
        }
        
        // Set transport type
        factory.setTransportType(WMQConstants.WMQ_CM_CLIENT);
        
        // Authentication if needed
        if (user != null && !user.isEmpty()) {
            factory.setStringProperty(WMQConstants.USERID, user);
            factory.setStringProperty(WMQConstants.PASSWORD, password);
        }
        
        log.info("MQ ConnectionFactory configured with CCDT: {}, QueueManager: {}, AppName: {}", 
            ccdtUrl, queueManager, applicationName);
        
        return factory;
    }
    
    @Bean
    public ConnectionFactory cachingConnectionFactory(MQConnectionFactory mqConnectionFactory) {
        CachingConnectionFactory cachingFactory = new CachingConnectionFactory();
        cachingFactory.setTargetConnectionFactory(mqConnectionFactory);
        cachingFactory.setSessionCacheSize(10);
        cachingFactory.setCacheConsumers(false);
        cachingFactory.setCacheProducers(false);
        cachingFactory.setReconnectOnException(true);
        
        // Set exception listener for failover handling
        cachingFactory.setExceptionListener(ex -> {
            log.error("JMS Exception occurred, triggering reconnection: {}", ex.getMessage());
        });
        
        return cachingFactory;
    }
    
    @Bean
    public JmsTemplate jmsTemplate(ConnectionFactory connectionFactory) {
        JmsTemplate template = new JmsTemplate();
        template.setConnectionFactory(connectionFactory);
        template.setMessageConverter(messageConverter());
        template.setReceiveTimeout(5000);
        return template;
    }
    
    @Bean
    public MessageConverter messageConverter() {
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setTargetType(MessageType.TEXT);
        converter.setTypeIdPropertyName("_type");
        return converter;
    }
}