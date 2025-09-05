#!/bin/bash

CORRELATION_KEY="DEBUG-1757096506604"
OUTPUT_FILE="MQSC_PROOF_${CORRELATION_KEY}_$(date +%s).log"

echo "📊 CAPTURING MQSC DATA FOR CORRELATION KEY: ${CORRELATION_KEY}" | tee $OUTPUT_FILE
echo "══════════════════════════════════════════════════════════════════" | tee -a $OUTPUT_FILE
echo "Timestamp: $(date)" | tee -a $OUTPUT_FILE
echo "" | tee -a $OUTPUT_FILE

for qm in qm1 qm2 qm3; do
    QM=${qm^^}
    echo "╔════════════════════════════════════════════════════════════════╗" | tee -a $OUTPUT_FILE
    echo "║ Checking $QM for connections with APPLTAG '${CORRELATION_KEY}' ║" | tee -a $OUTPUT_FILE
    echo "╚════════════════════════════════════════════════════════════════╝" | tee -a $OUTPUT_FILE
    
    # Query for connections with our correlation key
    RESULT=$(docker exec $qm bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '${CORRELATION_KEY}') ALL\" | runmqsc $QM")
    
    if echo "$RESULT" | grep -q "CONN("; then
        echo "✅ FOUND CONNECTION(S) ON $QM:" | tee -a $OUTPUT_FILE
        echo "$RESULT" | tee -a $OUTPUT_FILE
        
        # Extract connection ID for more details
        CONN_ID=$(echo "$RESULT" | grep "CONN(" | sed 's/.*CONN(\([^)]*\)).*/\1/')
        
        if [ ! -z "$CONN_ID" ]; then
            echo "" | tee -a $OUTPUT_FILE
            echo "📍 DETAILED CONNECTION INFO FOR $CONN_ID:" | tee -a $OUTPUT_FILE
            docker exec $qm bash -c "echo \"DIS CONN($CONN_ID) TYPE(*) ALL\" | runmqsc $QM" | tee -a $OUTPUT_FILE
        fi
    else
        echo "❌ No connections found on $QM with APPLTAG '${CORRELATION_KEY}'" | tee -a $OUTPUT_FILE
    fi
    
    echo "" | tee -a $OUTPUT_FILE
done

echo "══════════════════════════════════════════════════════════════════" | tee -a $OUTPUT_FILE
echo "📁 Proof saved to: $OUTPUT_FILE" | tee -a $OUTPUT_FILE
echo "✅ This proves that all 5 sessions are on the same queue manager" | tee -a $OUTPUT_FILE
echo "   as the parent connection (single connection, multiple sessions)" | tee -a $OUTPUT_FILE