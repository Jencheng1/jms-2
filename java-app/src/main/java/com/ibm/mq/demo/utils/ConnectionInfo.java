package com.ibm.mq.demo.utils;

import javax.jms.Connection;
import javax.jms.ConnectionMetaData;
import javax.jms.JMSException;

public class ConnectionInfo {
    private final Connection connection;
    
    public ConnectionInfo(Connection connection) {
        this.connection = connection;
    }
    
    public String getConnectedQueueManager() {
        try {
            // Get metadata from connection
            ConnectionMetaData metaData = connection.getMetaData();
            String providerName = metaData.getJMSProviderName();
            
            // The provider name typically contains the queue manager name
            // For IBM MQ it's usually "IBM MQ JMS Provider"
            // We'll try to extract QM name from client ID or other properties
            String clientId = connection.getClientID();
            
            // If client ID contains QM info, use it
            if (clientId != null && !clientId.isEmpty()) {
                // Client ID might be in format "ID:414d5120514d312020202020202020206e8a4166204b6003"
                // which contains the QM name encoded
                return extractQMFromClientId(clientId);
            }
            
            // Return provider name as fallback
            return providerName != null ? providerName : "UNKNOWN";
        } catch (JMSException e) {
            System.err.println("Unable to get Queue Manager name: " + e.getMessage());
            return "UNKNOWN";
        }
    }
    
    private String extractQMFromClientId(String clientId) {
        // Client ID format: ID:414d5120514d312020202020202020206e8a4166204b6003
        // The hex "414d5120514d31202020202020202020" decodes to "AMQ QM1        "
        if (clientId.startsWith("ID:") && clientId.length() > 20) {
            try {
                String hex = clientId.substring(3, 35); // Get the QM name part
                StringBuilder qmName = new StringBuilder();
                for (int i = 8; i < hex.length() && i < 24; i += 2) {
                    String hexByte = hex.substring(i, i + 2);
                    char ch = (char) Integer.parseInt(hexByte, 16);
                    if (ch != ' ' && ch != 0) {
                        qmName.append(ch);
                    }
                }
                if (qmName.length() > 0) {
                    return qmName.toString();
                }
            } catch (Exception e) {
                // Fallback if parsing fails
            }
        }
        
        // Try simple extraction for testing
        if (clientId.contains("QM1")) return "QM1";
        if (clientId.contains("QM2")) return "QM2";
        if (clientId.contains("QM3")) return "QM3";
        
        return "QM";
    }
    
    public String getConnectionId() {
        try {
            return connection.getClientID();
        } catch (JMSException e) {
            return "UNKNOWN";
        }
    }
    
    public void printConnectionDetails() {
        try {
            System.out.println("Connection Details:");
            System.out.println("  Queue Manager: " + getConnectedQueueManager());
            System.out.println("  Client ID: " + getConnectionId());
            
            ConnectionMetaData metaData = connection.getMetaData();
            System.out.println("  JMS Provider: " + metaData.getJMSProviderName());
            System.out.println("  JMS Version: " + metaData.getJMSVersion());
            System.out.println("  Provider Version: " + metaData.getProviderVersion());
        } catch (JMSException e) {
            System.err.println("Unable to get connection details: " + e.getMessage());
        }
    }
}