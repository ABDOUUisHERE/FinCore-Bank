package com.fincore.customer.configuration;

import com.fincore.support.AdministrativeReadAudit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

/** Customer retains its existing schema, event shape and narrow route policy. */
@Service
public class CustomerAccessAudit extends AdministrativeReadAudit {
    public CustomerAccessAudit(JdbcTemplate db,PlatformTransactionManager transactions) { super(db,transactions,Owner.CUSTOMER); }
}
