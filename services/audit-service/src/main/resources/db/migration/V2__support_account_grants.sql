create table support_account_grant (
    id uuid primary key,
    incident_id uuid not null unique references incident(id),
    version bigint not null check (version > 0),
    issued_by varchar(200) not null,
    issued_at timestamptz not null,
    expires_at timestamptz not null,
    revoked_at timestamptz,
    reason varchar(60) not null check (reason ~ '^[A-Z][A-Z0-9_]{2,59}$'),
    check (expires_at > issued_at and expires_at <= issued_at + interval '1 hour')
);

create function support_grant_guard() returns trigger language plpgsql as $$
begin
    if TG_OP <> 'UPDATE' then raise exception 'IMMUTABLE_GRANT_IDENTITY'; end if;
    if NEW.id <> OLD.id or NEW.incident_id <> OLD.incident_id or NEW.version <> OLD.version + 1 then
        raise exception 'INVALID_GRANT_VERSION';
    end if;
    if NEW.revoked_at is not null and (OLD.revoked_at is not null or
        NEW.issued_by <> OLD.issued_by or NEW.issued_at <> OLD.issued_at or NEW.expires_at <> OLD.expires_at) then
        raise exception 'INVALID_GRANT_REVOCATION';
    end if;
    return NEW;
end $$;
create trigger support_grant_guard before update or delete on support_account_grant
    for each row execute function support_grant_guard();
create trigger support_grant_no_truncate before truncate on support_account_grant
    for each statement execute function support_grant_guard();

-- Resolution invalidates even an expired grant. Reopening never revives it.
create function incident_revoke_support() returns trigger language plpgsql as $$
begin
    if NEW.status = 'RESOLVED' and OLD.status <> 'RESOLVED' then
        update support_account_grant set version=version+1, revoked_at=clock_timestamp(), reason='INCIDENT_RESOLVED'
            where incident_id=NEW.id and revoked_at is null;
    end if;
    return NEW;
end $$;
create trigger incident_revoke_support after update on incident
    for each row execute function incident_revoke_support();

grant select, insert on support_account_grant to audit_runtime;
grant update(version,issued_by,issued_at,expires_at,revoked_at,reason) on support_account_grant to audit_runtime;
