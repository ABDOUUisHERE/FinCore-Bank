ALTER TABLE risk_payment DROP CONSTRAINT risk_payment_kind_check;
ALTER TABLE risk_payment ALTER COLUMN kind TYPE varchar(30);
ALTER TABLE risk_payment ADD CONSTRAINT risk_payment_kind_check CHECK(kind IN ('TRANSFER','DEPOSIT','REVERSAL','MERCHANT_PAYMENT'));
