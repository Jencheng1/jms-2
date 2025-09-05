package com.ibm.mq.demo.utils;

import com.ibm.mq.jms.MQConnection;
import javax.jms.Connection;
import javax.jms.JMSException;

public class ConnectionInfo {
    private final Connection connection;
    
    public ConnectionInfo(Connection connection) {
        this.connection = connection;
    }
    
    public String getConnectedQueueManager() {
        try {
            if (connection instanceof MQConnection) {
                MQConnection mqConn = (MQConnection) connection;
                return mqConn.getQueueManagerName();
            }
        } catch (Exception e) {
            System.err.println("Unable to get Queue Manager name: " + e.getMessage());
        }
        return "UNKNOWN";
    }
    
    public String getConnectionId() {
        try {
            return connection.getClientID();
        } catch (JMSException e) {
            return "UNKNOWN";
        }
    }
    
    public void printConnectionDetails() {
        System.out.println("Connection Details:");
        System.out.println("  Queue Manager: " + getConnectedQueueManager());
        System.out.println("  Client ID: " + getConnectionId());
        
        if (connection instanceof MQConnection) {
            try {
                MQConnection mqConn = (MQConnection) connection;
                System.out.println("  Host: " + mqConn.getHostName());
                System.out.println("  Port: " + mqConn.getPort());
                System.out.println("  Channel: " + mqConn.getChannel());
            } catch (Exception e) {
                System.err.println("Unable to get additional connection details: " + e.getMessage());
            }
        }
    }
}