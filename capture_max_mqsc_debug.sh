#!/bin/bash

TRACKING_KEY="V2-1757100085364"
TIMESTAMP=$(date +%s)
MQSC_LOG="MQSC_PROOF_DEBUG-${TRACKING_KEY}_${TIMESTAMP}.log"

echo "═══════════════════════════════════════════════════════════════════════════════"
echo "    🔍 MAXIMUM MQSC DEBUG CAPTURE - COMPREHENSIVE ANALYSIS"
echo "    Tracking Key: $TRACKING_KEY"
echo "    Output File: $MQSC_LOG"
echo "═══════════════════════════════════════════════════════════════════════════════"

{
    echo "═══════════════════════════════════════════════════════════════════════════════"
    echo "    🔍 PARENT-CHILD CONNECTION PROOF - SINGLE CONNECTION, MULTIPLE SESSIONS"
    echo "    Tracking Key: $TRACKING_KEY"
    echo "    Time: $(date)"
    echo "═══════════════════════════════════════════════════════════════════════════════"
    echo ""
    
    echo "▶️ MQSC DATA COLLECTION STARTING..."
    echo ""
    
    # Perform 5 captures with different detail levels
    for ROUND in 1 2 3 4 5; do
        echo "═══════════════════════════════════════════════════════════════════════════════"
        echo "    CAPTURE ROUND #$ROUND - $(date '+%H:%M:%S.%N')"
        echo "═══════════════════════════════════════════════════════════════════════════════"
        echo ""
        
        echo "🔴 QM1 CONNECTION ANALYSIS"
        echo "────────────────────────────────────────────────────────────────────────────"
        
        echo ""
        echo "1. BASIC CONNECTION LIST WITH APPLTAG FILTER"
        echo "Command: DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY')"
        echo "────────────────────────────────────────────────────────────────────────────"
        docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY')\" | runmqsc QM1"
        
        echo ""
        echo "2. FULL CONNECTION DETAILS - ALL ATTRIBUTES"
        echo "Command: DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY') ALL"
        echo "────────────────────────────────────────────────────────────────────────────"
        docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY') ALL\" | runmqsc QM1"
        
        echo ""
        echo "3. CONNECTION TYPE AND OPTIONS ANALYSIS"
        echo "Command: DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY') TYPE(*) CONNOPTS"
        echo "────────────────────────────────────────────────────────────────────────────"
        docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY') TYPE(*) CONNOPTS\" | runmqsc QM1"
        
        echo ""
        echo "4. CHANNEL, USER, AND NETWORK DETAILS"
        echo "Command: DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY') CHANNEL CONNAME USERID APPLDESC"
        echo "────────────────────────────────────────────────────────────────────────────"
        docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY') CHANNEL CONNAME USERID APPLDESC\" | runmqsc QM1"
        
        echo ""
        echo "5. PROCESS AND THREAD INFORMATION"
        echo "Command: DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY') PID TID APPLTYPE ASTATE"
        echo "────────────────────────────────────────────────────────────────────────────"
        docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY') PID TID APPLTYPE ASTATE\" | runmqsc QM1"
        
        echo ""
        echo "6. CONNECTION TAGS AND IDENTIFIERS"
        echo "Command: DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY') CONN EXTCONN CONNTAG"
        echo "────────────────────────────────────────────────────────────────────────────"
        docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY') CONN EXTCONN CONNTAG\" | runmqsc QM1"
        
        echo ""
        echo "7. UNIT OF WORK DETAILS"
        echo "Command: DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY') UOWLOG UOWSTDA UOWSTTI UOWLOGDA UOWLOGTI UOWSTATE QMURID"
        echo "────────────────────────────────────────────────────────────────────────────"
        docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY') UOWLOG UOWSTDA UOWSTTI UOWLOGDA UOWLOGTI UOWSTATE QMURID\" | runmqsc QM1"
        
        echo ""
        echo "8. EXTENDED URI AND CLIENT DETAILS"
        echo "Command: DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY') URTYPE EXTURID CLIENTID"
        echo "────────────────────────────────────────────────────────────────────────────"
        docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY') URTYPE EXTURID CLIENTID\" | runmqsc QM1"
        
        echo ""
        echo "9. CHANNEL STATUS FOR APP.SVRCONN"
        echo "Command: DIS CHS(APP.SVRCONN)"
        echo "────────────────────────────────────────────────────────────────────────────"
        docker exec qm1 bash -c "echo 'DIS CHS(APP.SVRCONN)' | runmqsc QM1"
        
        echo ""
        echo "10. CHANNEL DEFINITION DETAILS"
        echo "Command: DIS CHANNEL(APP.SVRCONN) ALL"
        echo "────────────────────────────────────────────────────────────────────────────"
        docker exec qm1 bash -c "echo 'DIS CHANNEL(APP.SVRCONN) ALL' | runmqsc QM1" | head -100
        
        echo ""
        echo "11. CONNECTION AUTHENTICATION RECORDS"
        echo "Command: DIS CHLAUTH(APP.SVRCONN) ALL"
        echo "────────────────────────────────────────────────────────────────────────────"
        docker exec qm1 bash -c "echo 'DIS CHLAUTH(APP.SVRCONN) ALL' | runmqsc QM1"
        
        echo ""
        echo "12. ALL CONNECTIONS ON APP.SVRCONN (WIDER CONTEXT)"
        echo "Command: DIS CONN(*) WHERE(CHANNEL EQ APP.SVRCONN)"
        echo "────────────────────────────────────────────────────────────────────────────"
        docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(CHANNEL EQ APP.SVRCONN)\" | runmqsc QM1" | head -50
        
        echo ""
        echo "13. QUEUE MANAGER STATUS"
        echo "Command: DIS QMSTATUS CONNS"
        echo "────────────────────────────────────────────────────────────────────────────"
        docker exec qm1 bash -c "echo 'DIS QMSTATUS CONNS' | runmqsc QM1"
        
        echo ""
        echo "14. INDIVIDUAL CONNECTION DETAILS (TARGETED QUERY)"
        echo "────────────────────────────────────────────────────────────────────────────"
        # Get each connection ID and query individually
        docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY')\" | runmqsc QM1" | grep "CONN(" | while read line; do
            CONN_ID=$(echo "$line" | sed -n 's/.*CONN(\([^)]*\)).*/\1/p')
            if [ ! -z "$CONN_ID" ]; then
                echo ""
                echo "  Detailed info for $CONN_ID:"
                docker exec qm1 bash -c "echo 'DIS CONN($CONN_ID) ALL' | runmqsc QM1" | grep -E "(CONN\(|CONNOPTS|APPLTAG|PID|TID|CONNTAG)"
            fi
        done
        
        echo ""
        echo "15. CONNECTION COUNT AND ANALYSIS"
        echo "────────────────────────────────────────────────────────────────────────────"
        COUNT=$(docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY')\" | runmqsc QM1" | grep -c "CONN(")
        echo "Total connections with APPLTAG='$TRACKING_KEY': $COUNT"
        echo "Expected: 6 (1 parent + 5 sessions)"
        
        if [ "$COUNT" -eq "6" ]; then
            echo "✅ COUNT MATCHES EXPECTED!"
        else
            echo "⚠️ Count mismatch - found $COUNT connections"
        fi
        
        echo ""
        echo "16. PARENT CONNECTION IDENTIFICATION"
        echo "────────────────────────────────────────────────────────────────────────────"
        echo "Looking for connection with MQCNO_GENERATE_CONN_TAG..."
        docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY') CONNOPTS\" | runmqsc QM1" | grep -B1 -A1 "MQCNO_GENERATE_CONN_TAG"
        
        echo ""
        echo "17. SESSION CONNECTIONS (WITHOUT GENERATE_CONN_TAG)"
        echo "────────────────────────────────────────────────────────────────────────────"
        docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY') CONNOPTS\" | runmqsc QM1" | grep -v "MQCNO_GENERATE_CONN_TAG" | grep "CONNOPTS"
        
        echo ""
        echo "18. CONNECTION TAG BASE ANALYSIS"
        echo "────────────────────────────────────────────────────────────────────────────"
        docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY') CONNTAG\" | runmqsc QM1" | grep "CONNTAG" | sort | uniq -c
        
        if [ $ROUND -lt 5 ]; then
            echo ""
            echo "⏳ Waiting 10 seconds before round #$((ROUND+1))..."
            sleep 10
        fi
    done
    
    echo ""
    echo "═══════════════════════════════════════════════════════════════════════════════"
    echo "    📊 FINAL COMPREHENSIVE ANALYSIS"
    echo "═══════════════════════════════════════════════════════════════════════════════"
    
    echo ""
    echo "CONNECTION SUMMARY TABLE:"
    echo "────────────────────────"
    docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY') CONN CONNOPTS\" | runmqsc QM1" | grep -E "(CONN\(|CONNOPTS)" | paste -d " " - - | while read line; do
        echo "$line"
    done
    
    echo ""
    echo "PROCESS/THREAD VERIFICATION:"
    echo "───────────────────────────"
    docker exec qm1 bash -c "echo \"DIS CONN(*) WHERE(APPLTAG EQ '$TRACKING_KEY') PID TID\" | runmqsc QM1" | grep -E "(PID|TID)" | sort | uniq -c
    
    echo ""
    echo "═══════════════════════════════════════════════════════════════════════════════"
    echo "✅ MQSC DEBUG CAPTURE COMPLETE - Evidence saved to: $MQSC_LOG"
    echo "   Tracking Key: $TRACKING_KEY"
    echo "   Timestamp: $TIMESTAMP"
    echo "   Time: $(date)"
    echo "═══════════════════════════════════════════════════════════════════════════════"
    
} > "$MQSC_LOG" 2>&1

echo "✅ MQSC comprehensive debug log created: $MQSC_LOG"
echo ""
echo "📊 Quick Summary:"
grep -c "CONN(" "$MQSC_LOG" | head -1 | xargs -I {} echo "  Total CONN entries captured: {}"
grep -c "MQCNO_GENERATE_CONN_TAG" "$MQSC_LOG" | xargs -I {} echo "  Parent connections found: {}"
echo ""
echo "📁 Full details in: $MQSC_LOG"