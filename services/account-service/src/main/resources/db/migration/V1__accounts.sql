CREATE TABLE account (
  id uuid PRIMARY KEY, customer_id uuid NOT NULL, owner_subject varchar(200) NOT NULL,
  key varchar(120) NOT NULL, currency varchar(3) NOT NULL CHECK(currency IN ('MAD','EUR','USD')),
  status varchar(20) NOT NULL CHECK(status IN ('PROVISIONING','ACTIVE','FROZEN','CLOSED','GUARD_PENDING')),
  ledger_id uuid UNIQUE, version bigint NOT NULL DEFAULT 0, ledger_guard_revision bigint NOT NULL DEFAULT 0,
  guard_target varchar(10), guard_previous varchar(10), guard_error varchar(40),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(), UNIQUE(owner_subject,key),
  CHECK((status='PROVISIONING')=(ledger_id IS NULL))
);
CREATE TABLE account_outbox (
  id uuid PRIMARY KEY, account_id uuid NOT NULL REFERENCES account(id), aggregate_version bigint NOT NULL,
  event_type varchar(60) NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(), UNIQUE(account_id,aggregate_version)
);
REVOKE ALL ON account,account_outbox FROM PUBLIC;
GRANT SELECT,INSERT,UPDATE ON account TO account_runtime;
GRANT SELECT,INSERT ON account_outbox TO account_runtime;
