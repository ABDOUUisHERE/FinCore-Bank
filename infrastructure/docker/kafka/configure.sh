#!/bin/sh
set -eu
K=/opt/kafka/bin
C=/etc/kafka/secrets/admin.properties
for topic in customer.created customer.updated account.created account.restriction.requested account.restriction.applied account.restriction.rejected ledger.journal.posted payment.initiated payment.validating payment.pending_risk_review payment.processing payment.completed payment.failed payment.rejected payment.reversal_pending payment.reversed notifications.dlt notifications.replay fraud.alert.created card.created card.frozen card.state.changed; do
 max_bytes=131072
 case "$topic" in notifications.dlt|notifications.replay) max_bytes=1048576 ;; esac
 "$K/kafka-topics.sh" --bootstrap-server kafka:9092 --command-config "$C" --create --if-not-exists --topic "fincore.$topic.v1" --partitions 3 --replication-factor 1 --config retention.ms=604800000 --config max.message.bytes="$max_bytes"
 done
for user in customer account ledger payment fraud card; do
 "$K/kafka-acls.sh" --bootstrap-server kafka:9092 --command-config "$C" --add --allow-principal "User:$user" --operation Write --operation Describe --topic "fincore.$user." --resource-pattern-type prefixed
 "$K/kafka-acls.sh" --bootstrap-server kafka:9092 --command-config "$C" --add --allow-principal "User:$user" --operation IdempotentWrite --cluster
 done
"$K/kafka-acls.sh" --bootstrap-server kafka:9092 --command-config "$C" --add --allow-principal User:notification --operation Read --operation Describe --topic fincore. --resource-pattern-type prefixed
"$K/kafka-acls.sh" --bootstrap-server kafka:9092 --command-config "$C" --add --allow-principal User:notification --operation Read --group fincore-notifications --resource-pattern-type prefixed
"$K/kafka-acls.sh" --bootstrap-server kafka:9092 --command-config "$C" --add --allow-principal User:notification --operation Write --operation Describe --topic fincore.notifications. --resource-pattern-type prefixed
"$K/kafka-acls.sh" --bootstrap-server kafka:9092 --command-config "$C" --add --allow-principal User:notification --operation IdempotentWrite --cluster
for topic in notifications.dlt notifications.replay; do
 "$K/kafka-configs.sh" --bootstrap-server kafka:9092 --command-config "$C" --entity-type topics --entity-name "fincore.$topic.v1" --alter --add-config max.message.bytes=1048576
 done
