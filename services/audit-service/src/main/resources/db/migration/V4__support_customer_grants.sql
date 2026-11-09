-- Preserve all existing Account/Payment grant identities and command receipts.
create table support_customer_grant (like support_account_grant including all);
alter table support_customer_grant add foreign key (incident_id) references incident(id);
create trigger support_grant_guard before update or delete on support_customer_grant
    for each row execute function support_grant_guard();
create trigger support_grant_no_truncate before truncate on support_customer_grant
    for each statement execute function support_grant_guard();
grant select, insert on support_customer_grant to audit_runtime;
grant update(version,issued_by,issued_at,expires_at,revoked_at,reason) on support_customer_grant to audit_runtime;

create or replace function incident_revoke_support() returns trigger language plpgsql as $$
begin
    if NEW.status = 'RESOLVED' and OLD.status <> 'RESOLVED' then
        update support_account_grant set version=version+1, revoked_at=clock_timestamp(), reason='INCIDENT_RESOLVED'
            where incident_id=NEW.id and revoked_at is null;
        update support_payment_grant set version=version+1, revoked_at=clock_timestamp(), reason='INCIDENT_RESOLVED'
            where incident_id=NEW.id and revoked_at is null;
        update support_customer_grant set version=version+1, revoked_at=clock_timestamp(), reason='INCIDENT_RESOLVED'
            where incident_id=NEW.id and revoked_at is null;
    end if;
    return NEW;
end $$;
