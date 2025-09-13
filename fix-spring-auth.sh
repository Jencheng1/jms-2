#!/bin/bash

echo "Fixing Spring Boot authentication on all Queue Managers..."
echo "=================================================="

for qm in qm1 qm2 qm3; do
    QM_UPPER=${qm^^}
    echo ""
    echo "=== Configuring $QM_UPPER ==="
    
    # Set MCAUSER to mqm
    echo "Setting MCAUSER to 'mqm'..."
    docker exec $qm bash -c "echo \"ALTER CHANNEL('APP.SVRCONN') CHLTYPE(SVRCONN) MCAUSER('mqm')\" | runmqsc $QM_UPPER" 2>&1 | grep -E "AMQ|completed"
    
    # Set channel auth to block nobody
    echo "Setting channel authentication..."
    docker exec $qm bash -c "echo \"SET CHLAUTH('APP.SVRCONN') TYPE(BLOCKUSER) USERLIST('nobody') ACTION(REPLACE)\" | runmqsc $QM_UPPER" 2>&1 | grep -E "AMQ|completed"
    
    # Refresh security
    echo "Refreshing security..."
    docker exec $qm bash -c "echo \"REFRESH SECURITY TYPE(CONNAUTH)\" | runmqsc $QM_UPPER" 2>&1 | grep -E "AMQ|completed"
    
    # Display channel status
    echo "Verifying channel configuration..."
    docker exec $qm bash -c "echo \"DIS CHANNEL('APP.SVRCONN') MCAUSER\" | runmqsc $QM_UPPER" 2>&1 | grep -E "CHANNEL|MCAUSER"
done

echo ""
echo "Authentication configuration complete!"
echo "You can now run Spring Boot tests without authentication errors."