import com.ibm.msg.client.wmq.WMQConstants;
import com.ibm.mq.jms.MQConnection;
import com.ibm.mq.jms.MQSession;
import javax.jms.*;
import java.lang.reflect.Method;

public class SpringBootFailoverTestCorrected {
    
    /**
     * Extract CONNTAG from Connection - Spring Boot Way
     * Lines 11-51: Main extraction method with reflection fallback
     */
    private static String extractFullConnTag(Connection connection) {
        try {
            // Lines 14-22: Try direct property extraction using Spring Boot approach
            // THIS IS THE KEY DIFFERENCE - Spring Boot uses string literal "JMS_IBM_CONNECTION_TAG"
            // Regular JMS would use XMSC.WMQ_RESOLVED_CONNECTION_TAG constant
            
            // Cast to MQConnection for Spring Boot style access
            if (connection instanceof MQConnection) {
                MQConnection mqConnection = (MQConnection) connection;
                
                // Spring Boot way: Use string literal property name
                String conntag = mqConnection.getStringProperty("JMS_IBM_CONNECTION_TAG");
                
                if (conntag != null && !conntag.isEmpty()) {
                    // Line 26: Return full CONNTAG without truncation
                    return conntag;  // Format: MQCT<16-char-handle><QM>_<timestamp>
                }
            }
            
            // Lines 30-45: Fallback using reflection if direct access fails
            Method getPropertyMethod = connection.getClass().getMethod(
                "getStringProperty", String.class
            );
            
            // Try alternate property names - Spring Boot uses string literals
            String[] propertyNames = {
                "JMS_IBM_CONNECTION_TAG",           // Spring Boot primary (string literal)
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
     * Lines 58-76: Session CONNTAG extraction
     */
    private static String extractSessionConnTag(Session session) {
        try {
            // Lines 61-65: Sessions inherit parent's CONNTAG
            // Spring Boot sessions use same approach as connection
            
            if (session instanceof MQSession) {
                MQSession mqSession = (MQSession) session;
                
                // Spring Boot way: Use string literal property name
                String conntag = mqSession.getStringProperty("JMS_IBM_CONNECTION_TAG");
                
                if (conntag != null) {
                    // Line 71: Return inherited CONNTAG (same as parent)
                    return conntag;
                }
            }
        } catch (Exception e) {
            // Session doesn't have direct property, inherit from parent
        }
        return "INHERITED_FROM_PARENT";
    }
}