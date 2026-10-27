REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO fraud_runtime;

CREATE TABLE fraud_rule (
 version bigint PRIMARY KEY CHECK(version>0), rules jsonb NOT NULL,
 actor varchar(200) NOT NULL, reason varchar(100) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
INSERT INTO fraud_rule(version,rules,actor,reason) VALUES(1,'{
 "reviewAmounts":{"MAD":"10000.00","EUR":"10000.00","USD":"10000.00"},
 "blockAmounts":{"MAD":"100000.00","EUR":"100000.00","USD":"100000.00"},
 "newBeneficiaryAmounts":{"MAD":"5000.00","EUR":"5000.00","USD":"5000.00"},
 "frequencyReview":30,"frequencyBlock":60,"rapidCount":12,"velocityCount":15,
 "failedCount":5,"newBeneficiaryHours":24,"flaggedCountries":["ZZ"]
 }','migration','INITIAL_SIMULATION_POLICY');
CREATE TABLE current_rule (singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),version bigint NOT NULL REFERENCES fraud_rule(version));
INSERT INTO current_rule VALUES(true,1);

CREATE TABLE risk_payment (
 payment_id uuid PRIMARY KEY, fingerprint varchar(64) NOT NULL, actor varchar(200) NOT NULL,
 kind varchar(10) NOT NULL CHECK(kind IN ('TRANSFER','DEPOSIT','REVERSAL')),
 amount numeric(20,2) NOT NULL CHECK(amount>0),currency varchar(3) NOT NULL CHECK(currency IN ('MAD','EUR','USD')),
 destination_id uuid,first_seen timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE velocity_admission (
 payment_id uuid PRIMARY KEY REFERENCES risk_payment(payment_id), actor varchar(200) NOT NULL,
 destination_id uuid,last_attempt timestamptz NOT NULL,expires_at timestamptz NOT NULL
);
CREATE INDEX velocity_actor ON velocity_admission(actor,last_attempt);
CREATE TABLE risk_assessment (
 id uuid PRIMARY KEY,payment_id uuid NOT NULL REFERENCES risk_payment(payment_id),
 sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
 rules_version bigint NOT NULL REFERENCES fraud_rule(version),case_version bigint NOT NULL,
 context_hash varchar(64) NOT NULL,context jsonb NOT NULL,
 verdict varchar(6) NOT NULL CHECK(verdict IN ('ALLOW','REVIEW','BLOCK')),
 score int NOT NULL CHECK(score BETWEEN 0 AND 100),reasons jsonb NOT NULL,
 expires_at timestamptz NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX assessment_payment ON risk_assessment(payment_id,sequence DESC);
CREATE TABLE fraud_case (
 id uuid PRIMARY KEY,payment_id uuid NOT NULL UNIQUE REFERENCES risk_payment(payment_id),
 version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 status varchar(10) NOT NULL CHECK(status IN ('OPEN','APPROVED','REJECTED','BLOCKED','CLEARED')),
 rules_version bigint NOT NULL REFERENCES fraud_rule(version),reason_codes jsonb NOT NULL,
 last_assessment uuid REFERENCES risk_assessment(id) DEFERRABLE INITIALLY DEFERRED,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE investigation_decision (
 id uuid PRIMARY KEY,case_id uuid NOT NULL REFERENCES fraud_case(id),
 request_key varchar(120) NOT NULL, fingerprint varchar(64) NOT NULL,
 case_version bigint NOT NULL,actor varchar(200) NOT NULL,approve boolean NOT NULL,reason varchar(100) NOT NULL,
 response jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(case_id,request_key),UNIQUE(case_id,case_version)
);
CREATE TABLE fraud_alert (
 id uuid PRIMARY KEY,sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
 case_id uuid NOT NULL REFERENCES fraud_case(id),payment_id uuid NOT NULL UNIQUE REFERENCES risk_payment(payment_id),
 assessment_id uuid NOT NULL REFERENCES risk_assessment(id) DEFERRABLE INITIALLY DEFERRED,
 verdict varchar(6) NOT NULL CHECK(verdict IN ('REVIEW','BLOCK')),score int NOT NULL CHECK(score BETWEEN 0 AND 100),
 rules_version bigint NOT NULL REFERENCES fraud_rule(version),reasons jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE fraud_outbox (
 id uuid PRIMARY KEY,aggregate_id uuid NOT NULL,aggregate_version bigint NOT NULL,
 event_type varchar(100) NOT NULL,payload jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE VIEW fincore_event_source AS SELECT id AS event_id,created_at AS occurred_at,
 jsonb_build_object('eventId',id,'eventType',event_type,'schemaVersion',1,
 'aggregateId',aggregate_id,'aggregateVersion',aggregate_version,'correlationId',id::text,
 'occurredAt',created_at,'payload',payload) AS envelope FROM fraud_outbox;
CREATE TABLE event_delivery (
 event_id uuid PRIMARY KEY REFERENCES fraud_outbox(id),sent_at timestamptz,lease_token uuid,lease_until timestamptz,
 attempts int NOT NULL DEFAULT 0 CHECK(attempts>=0),next_attempt timestamptz NOT NULL DEFAULT clock_timestamp(),last_code varchar(40)
);
CREATE INDEX event_delivery_pending ON event_delivery(next_attempt,event_id) WHERE sent_at IS NULL;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC;
GRANT SELECT,INSERT ON fraud_rule,risk_payment,risk_assessment,investigation_decision,fraud_alert,fraud_outbox TO fraud_runtime;
GRANT SELECT ON current_rule,fraud_case,velocity_admission,fincore_event_source TO fraud_runtime;
GRANT INSERT ON fraud_case,velocity_admission TO fraud_runtime;
GRANT UPDATE(version) ON current_rule TO fraud_runtime;
GRANT UPDATE(last_attempt,expires_at) ON velocity_admission TO fraud_runtime;
GRANT UPDATE(version,status,rules_version,reason_codes,last_assessment,updated_at) ON fraud_case TO fraud_runtime;
GRANT SELECT,INSERT,UPDATE ON event_delivery TO fraud_runtime;
GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO fraud_runtime;
