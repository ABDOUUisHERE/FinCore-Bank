package com.fincore.support;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdministrativeReadAuditFilterTest {
    final AdministrativeReadAudit audit=mock(AdministrativeReadAudit.class);
    final UUID id=UUID.randomUUID();
    final MockHttpServletResponse response=new MockHttpServletResponse();
    MockHttpServletRequest request(String path){var request=new MockHttpServletRequest("GET",path);request.setServletPath(path);request.setAttribute("correlationId","bounded-test");return request;}
    void run(jakarta.servlet.FilterChain chain)throws Exception{
        new AdministrativeReadAuditFilter(audit,AdministrativeReadAudit.Owner.REPORTING).doFilter(request("/api/v1/statements/"+id+"/csv"),response,chain);
    }
    void unavailable()throws Exception{
        assertThat(response.getStatus()).isEqualTo(503);assertThat(response.getContentAsString()).contains("RESPONSE_TOO_LARGE").doesNotContain("PRIVATE_BODY");
        assertThat(response.getHeader("Content-Disposition")).isNull();
        verify(audit).retain(new AdministrativeReadAudit.Target("STATEMENT_EXPORT_CSV",id),"GET",503,null,"bounded-test");
    }
    @Test void oneOversizedByteWriteNeverReleasesTheAttachment()throws Exception{
        run((q,r)->{var out=(jakarta.servlet.http.HttpServletResponse)r;out.setHeader("Content-Disposition","attachment; filename=PRIVATE_BODY.csv");out.getOutputStream().write(new byte[4*1024*1024+1]);});unavailable();
    }
    @Test void chunkedWriterCannotBypassTheBufferCeiling()throws Exception{
        run((q,r)->{var out=r.getWriter();for(int n=0;n<5;n++){out.write("PRIVATE_BODY".repeat(100_000));out.flush();}});unavailable();
    }
    @Test void anOversizedDeclaredLengthIsRejectedBeforeBufferAllocation()throws Exception{
        run((q,r)->r.setContentLengthLong(4L*1024*1024+1));unavailable();
    }
    @Test void aValidDeclaredLengthAfterWritingDoesNotCountTheSameBytesTwice()throws Exception{
        doAnswer(call->{assertThat(response.getContentAsByteArray()).isEmpty();return null;}).when(audit).retain(any(),anyString(),eq(200),isNull(),anyString());
        run((q,r)->{r.getOutputStream().write(new byte[3*1024*1024]);r.setContentLength(3*1024*1024);});
        assertThat(response.getStatus()).isEqualTo(200);assertThat(response.getContentAsByteArray()).hasSize(3*1024*1024);
    }
    @Test void failedCommitClearsPrivateFilenameAndBody()throws Exception{
        doThrow(new org.springframework.transaction.TransactionSystemException("PRIVATE_COMMIT")).when(audit).retain(any(),anyString(),anyInt(),isNull(),anyString());
        run((q,r)->{var out=(jakarta.servlet.http.HttpServletResponse)r;out.setHeader("Content-Disposition","attachment; filename=PRIVATE_BODY.csv");out.getOutputStream().write("PRIVATE_BODY".getBytes(java.nio.charset.StandardCharsets.UTF_8));});
        assertThat(response.getContentAsString()).contains("AUDIT_UNAVAILABLE").doesNotContain("PRIVATE_");assertThat(response.getHeader("Content-Disposition")).isNull();
    }
    @Test void existingOwnerCharacterResponsesStillFlushBeforeDisclosure()throws Exception{
        response.setCharacterEncoding("UTF-8");
        new AdministrativeReadAuditFilter(audit,AdministrativeReadAudit.Owner.CUSTOMER).doFilter(request("/api/v1/admin/customers/"+id),response,(q,r)->r.getWriter().write("Données synthétiques"));
        assertThat(response.getContentAsString()).isEqualTo("Données synthétiques");verify(audit).retain(new AdministrativeReadAudit.Target("CUSTOMER_INSPECT",id),"GET",200,null,"bounded-test");
    }
}
