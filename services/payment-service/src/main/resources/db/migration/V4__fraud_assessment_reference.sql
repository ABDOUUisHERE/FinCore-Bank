-- External Fraud identity; no cross-database foreign key and no rewrite of old financial facts.
ALTER TABLE payment ADD COLUMN risk_assessment_id uuid;
ALTER TABLE payment ADD COLUMN risk_ruleset varchar(80);
