package com.fincore.reporting;

import com.fincore.support.*;
import com.fincore.support.AdministrativeReadAudit.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value=ReportingController.class,properties="REPORTING_CLIENT_SECRET=synthetic-test-secret")
@Import({ReportingConfiguration.class,ReportingSecurityTest.Decoders.class})
class ReportingAccessAuditSecurityTest {
    @Autowired MockMvc http;
    @MockitoBean StatementStore store;
    @MockitoBean ReportingCoordinator coordinator;
    @MockitoBean Remote remote;
    @MockitoBean AdministrativeReadAudit readAudit;
    final UUID id=UUID.randomUUID();
    record Read(String path,String action,String role,UUID reference){}
    List<Read> reads(){return List.of(new Read("/api/v1/statements","STATEMENT_LIST","CUSTOMER",null),new Read("/api/v1/statements/"+id,"STATEMENT_INSPECT","CUSTOMER",id),new Read("/api/v1/statements/"+id+"/csv","STATEMENT_EXPORT_CSV","CUSTOMER",id),new Read("/api/v1/statements/"+id+"/pdf","STATEMENT_EXPORT_PDF","CUSTOMER",id),new Read("/api/v1/admin/reports/reconciliation","RECONCILIATION_INSPECT","FINANCE_OPERATOR",null),new Read("/api/v1/admin/ledger/journals/"+id,"JOURNAL_INSPECT","FINANCE_OPERATOR",id));}
    String token(String role){return SignedTokens.token("fincore-api","current-reader","browser",List.of(role),Map.of());}
    void fixture(){var statement=(com.fasterxml.jackson.databind.node.ObjectNode)StatementExportsTest.statement(3);statement.put("statementId",id.toString());when(store.own(id,"current-reader")).thenReturn(statement);when(store.list(eq("current-reader"),any())).thenReturn(Map.of("items",List.of()));when(coordinator.create(any(),anyString(),eq("current-reader"),anyString())).thenReturn(statement);when(coordinator.reconcile()).thenReturn(Json.read("{\"reconciled\":true}"));when(coordinator.journal(id)).thenReturn(Json.read("{\"journalId\":\""+id+"\"}"));}
    @Test void everyPreparedReadAndCreateRequestRetainsOnlyCurrentSignedCallerAndTypedTarget()throws Exception{
        fixture();for(var read:reads()){
            http.perform(get(read.path()).header("Authorization","Bearer "+token(read.role())).header("X-User-ID","FORGED_OWNER").header("X-Correlation-ID","signed-read")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
            verify(readAudit).retain(new Target(read.action(),read.reference()),"GET",200,new Actor("current-reader"),"signed-read");
        }
        http.perform(post("/api/v1/statements").header("Authorization","Bearer "+token("CUSTOMER")).header("X-Correlation-ID","create-request").contentType("application/json").content("{\"accountId\":\""+id+"\",\"month\":\"2026-01\"}")).andExpect(status().isOk());
        verify(readAudit).retain(new Target("STATEMENT_CREATE_REQUEST",null),"POST",200,new Actor("current-reader"),"create-request");verifyNoMoreInteractions(readAudit);
    }
    @Test void absentInvalidAndWrongAudienceCredentialsNeverEstablishAnActor()throws Exception{
        for(var read:reads())for(String bearer:List.of("","Bearer forged.PRIVATE_TOKEN.invalid","Bearer "+SignedTokens.token("foreign-api","forged","browser",List.of(read.role()),Map.of()))){
            var request=get(read.path()).header("X-Correlation-ID","unknown-caller").header("X-User-ID","FORGED_OWNER");if(!bearer.isEmpty())request.header("Authorization",bearer);
            http.perform(request).andExpect(status().isUnauthorized());
        }
        for(var read:reads())verify(readAudit,times(3)).retain(new Target(read.action(),read.reference()),"GET",401,null,"unknown-caller");verifyNoInteractions(store,coordinator);
    }
    @Test void financeAndCustomerAuthorityRemainSeparateAndMethodDenialsAreRetained()throws Exception{
        for(var read:reads())for(String role:List.of("ADMIN","SUPPORT_AGENT","FRAUD_ANALYST",read.role().equals("CUSTOMER")?"FINANCE_OPERATOR":"CUSTOMER"))
            http.perform(get(read.path()).header("Authorization","Bearer "+token(role)).header("X-Correlation-ID","role-denial")).andExpect(status().isForbidden());
        for(var read:reads())verify(readAudit,times(4)).retain(new Target(read.action(),read.reference()),"GET",403,new Actor("current-reader"),"role-denial");
        http.perform(post("/api/v1/statements/"+id+"/csv").header("Authorization","Bearer "+token("CUSTOMER")).header("X-Correlation-ID","wrong-method").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        verify(readAudit).retain(new Target("STATEMENT_EXPORT_CSV",id),"POST",403,new Actor("current-reader"),"wrong-method");verifyNoInteractions(store,coordinator);
    }
    @Test void malformedReferencesBodiesAndCursorsDoNotBecomeAuditPayloads()throws Exception{
        http.perform(get("/api/v1/statements/PRIVATE_INVALID_UUID/pdf").header("Authorization","Bearer "+token("CUSTOMER")).header("X-Correlation-ID","invalid-id")).andExpect(status().isBadRequest());
        verify(readAudit).retain(new Target("STATEMENT_EXPORT_PDF",null),"GET",400,new Actor("current-reader"),"invalid-id");
        http.perform(get("/api/v1/statements?after=PRIVATE_CURSOR").header("Authorization","Bearer "+token("CUSTOMER")).header("X-Correlation-ID","invalid-cursor")).andExpect(status().isBadRequest());
        verify(readAudit).retain(new Target("STATEMENT_LIST",null),"GET",400,new Actor("current-reader"),"invalid-cursor");
        http.perform(post("/api/v1/statements").header("Authorization","Bearer "+token("CUSTOMER")).header("X-Correlation-ID","invalid-body").contentType("application/json").content("{\"PRIVATE_UNKNOWN_FIELD\":true}")).andExpect(status().isBadRequest());
        verify(readAudit).retain(new Target("STATEMENT_CREATE_REQUEST",null),"POST",400,new Actor("current-reader"),"invalid-body");verifyNoInteractions(store,coordinator);
    }
    @Test void insertBeginAndCommitFailureWithholdCsvPdfAndUnretainedDenials()throws Exception{
        fixture();for(RuntimeException error:List.of(new DataAccessResourceFailureException("PRIVATE_INSERT"),new org.springframework.transaction.CannotCreateTransactionException("PRIVATE_BEGIN"),new org.springframework.transaction.TransactionSystemException("PRIVATE_COMMIT"))){
            doThrow(error).when(readAudit).retain(any(),anyString(),anyInt(),nullable(Actor.class),anyString());
            for(String suffix:List.of("/csv","/pdf"))for(String bearer:List.of("Bearer "+token("CUSTOMER"),"")){
                var request=get("/api/v1/statements/"+id+suffix);if(!bearer.isEmpty())request.header("Authorization",bearer);
                var result=http.perform(request).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE")).andExpect(header().doesNotExist("Content-Disposition")).andExpect(header().doesNotExist("WWW-Authenticate")).andReturn();
                assertThat(result.getResponse().getContentAsString()).doesNotContain(id.toString(),"PRIVATE_","%PDF-","FinCore synthetic statement");
            }
        }
    }
    @Test void suffixesAndOtherOwnersAreOutsideThisFiniteRegistry()throws Exception{
        for(String path:List.of("/api/v1/statements/"+id+"/raw","/api/v1/statements/"+id+"/csv/extra","/api/v1/admin/ledger/journals","/api/v1/accounts"))http.perform(get(path).header("Authorization","Bearer "+token("CUSTOMER"))).andExpect(status().isForbidden());
        verifyNoInteractions(readAudit,store,coordinator);
    }
}
