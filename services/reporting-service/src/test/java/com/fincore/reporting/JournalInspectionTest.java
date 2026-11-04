package com.fincore.reporting;

import com.fincore.support.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JournalInspectionTest {
    @Test void readsAuthoritativeJournalWithMachineIdentityWithoutCreatingSnapshot() {
        var remote=mock(Remote.class);var store=mock(StatementStore.class);var id=UUID.randomUUID();
        var journal=Json.read("{\"journalId\":\""+id+"\",\"source\":\"ledger-postings\",\"entries\":[]}");
        when(remote.serviceToken()).thenReturn("memory-only");when(remote.call("GET","http://ledger/internal/v1/ledger/reports/journals/"+id,"memory-only",null)).thenReturn(journal);
        assertThat(new ReportingCoordinator(remote,store,"http://account","http://ledger").journal(id)).isEqualTo(journal);
        verifyNoInteractions(store);
    }
    @Test void rejectsDifferentJournalOrUnverifiedSource() {
        var remote=mock(Remote.class);var id=UUID.randomUUID();when(remote.serviceToken()).thenReturn("memory-only");
        for(var source:List.of("{}","{\"journalId\":\""+UUID.randomUUID()+"\",\"source\":\"ledger-postings\",\"entries\":[]}")){
            when(remote.call("GET","http://ledger/internal/v1/ledger/reports/journals/"+id,"memory-only",null)).thenReturn(Json.read(source));
            assertThatThrownBy(()->new ReportingCoordinator(remote,mock(StatementStore.class),"http://account","http://ledger").journal(id)).isInstanceOf(ApiFailure.class).extracting("status").isEqualTo(503);
        }
    }
}
