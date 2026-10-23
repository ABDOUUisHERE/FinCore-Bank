CREATE TABLE customer_profile (
  id uuid PRIMARY KEY, subject varchar(200) NOT NULL UNIQUE,
  display_name varchar(80) NOT NULL CHECK(length(btrim(display_name)) BETWEEN 1 AND 80),
  country_code varchar(2) NOT NULL CHECK(country_code ~ '^[A-Z]{2}$'),
  status varchar(10) NOT NULL DEFAULT 'ACTIVE' CHECK(status='ACTIVE'),
  version bigint NOT NULL DEFAULT 0 CHECK(version>=0), created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE customer_outbox (
  id uuid PRIMARY KEY, customer_id uuid NOT NULL REFERENCES customer_profile(id),
  aggregate_version bigint NOT NULL, event_type varchar(60) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(), UNIQUE(customer_id,aggregate_version)
);
REVOKE ALL ON customer_profile,customer_outbox FROM PUBLIC;
GRANT SELECT,INSERT,UPDATE ON customer_profile TO customer_runtime;
GRANT SELECT,INSERT ON customer_outbox TO customer_runtime;
