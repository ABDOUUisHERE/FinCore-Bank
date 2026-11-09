package com.fincore.customer.configuration;

/** Customer policy wrapper preserves the existing security-chain and test interface. */
public final class CustomerAccessAuditFilter extends com.fincore.support.AdministrativeReadAuditFilter {
    public CustomerAccessAuditFilter(CustomerAccessAudit audit) { super(audit,com.fincore.support.AdministrativeReadAudit.Owner.CUSTOMER); }
}
