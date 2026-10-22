CREATE TABLE foundation_metadata (
    component VARCHAR(80) PRIMARY KEY,
    installed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO foundation_metadata(component) VALUES ('customer-service');
REVOKE ALL ON foundation_metadata FROM PUBLIC;
GRANT SELECT ON foundation_metadata TO customer_runtime;
