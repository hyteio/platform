#!/bin/bash

# create the keystore form the kubernetes ca.crt
if [ -f "etc/cacert.jks" ]; then
    rm etc/cacert.jks
fi
if [ -f "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt" ]; then
    keytool -import -alias kubernetes.io -file /var/run/secrets/kubernetes.io/serviceaccount/ca.crt -keystore etc/cacert.jks -storepass password -noprompt
fi

# start karaf and trap signals (pass them on)
trap 'kill -TERM $PID' TERM INT
bin/karaf server &
PID=$!
wait $PID
trap - TERM INT
wait $PID
EXIT_STATUS=$?
