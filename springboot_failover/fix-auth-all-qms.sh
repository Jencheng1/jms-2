#!/bin/bash

echo "Fixing authentication on all QMs..."

for qm in qm1 qm2 qm3; do
    echo "Configuring ${qm^^}..."
    
    # Set MCAUSER to mqm
    docker exec $qm bash -c "echo 'ALTER CHANNEL('\''APP.SVRCONN'\'') CHLTYPE(SVRCONN) MCAUSER('\''mqm'\'')' | runmqsc ${qm^^}"
    
    # Disable channel auth
    docker exec $qm bash -c "echo 'ALTER QMGR CHLAUTH(DISABLED)' | runmqsc ${qm^^}"
    
    # Refresh security
    docker exec $qm bash -c "echo 'REFRESH SECURITY TYPE(CONNAUTH)' | runmqsc ${qm^^}"
    
    echo "${qm^^} configured"
    echo ""
done

echo "All QMs configured for no authentication"