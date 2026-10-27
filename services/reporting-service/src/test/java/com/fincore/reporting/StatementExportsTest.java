package com.fincore.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.support.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StatementExportsTest {
 static JsonNode statement(int count){var entries=new ArrayList<Object>();for(int i=0;i<count;i++)entries.add(Map.of("journalId",new UUID(0,i+1).toString(),"postingId",UUID.randomUUID(),"at","2026-01-15T12:00:00Z","kind",i%2==0?"MERCHANT_PAYMENT":"REVERSAL","side","CREDIT","amount","1.25","signedAmount","1.25","reference",i==0?"=HYPERLINK(\"https://example.invalid\")":"synthetic"));var s=new LinkedHashMap<String,Object>();s.put("accountId","10000000-0000-0000-0000-000000000001");s.put("month","2026-01");s.put("currency","MAD");s.put("opening","100.00");s.put("movement",new BigDecimal("1.25").multiply(BigDecimal.valueOf(count)).setScale(2).toPlainString());s.put("closing",new BigDecimal("100.00").add(new BigDecimal("1.25").multiply(BigDecimal.valueOf(count))).setScale(2).toPlainString());s.put("currentBooked","138.75");s.put("currentReserved","20.00");s.put("currentAvailable","118.75");s.put("ledgerVersion",35);s.put("observedAt","2026-01-31T20:00:00Z");s.put("source","ledger-postings");s.put("entries",entries);return Json.read(Json.text(Map.of("statementId","20000000-0000-0000-0000-000000000002","contentHash","abcdef0123456789".repeat(4),"snapshot",s)));}
 @Test void pdfContainsEveryJournalAcrossPagesAndProducesReviewablePreview()throws Exception{var statement=statement(31);var bytes=StatementExports.pdf(statement);try(var pdf=Loader.loadPDF(bytes)){assertThat(pdf.getNumberOfPages()).isEqualTo(3);String text=new PDFTextStripper().getText(pdf);for(var entry:statement.path("snapshot").path("entries"))assertThat(text).contains(entry.path("journalId").asText());assertThat(text).contains("138.75","SHA-256","Synthetic banking");Path output=Path.of("target");Files.createDirectories(output);Files.write(output.resolve("statement-preview.pdf"),bytes);var renderer=new PDFRenderer(pdf);ImageIO.write(renderer.renderImageWithDPI(0,110),"png",output.resolve("statement-preview-first.png").toFile());ImageIO.write(renderer.renderImageWithDPI(2,110),"png",output.resolve("statement-preview-last.png").toFile());}}
 @Test void emptyMonthStillExportsAnExplicitStatement()throws Exception{try(var pdf=Loader.loadPDF(StatementExports.pdf(statement(0)))){assertThat(pdf.getNumberOfPages()).isEqualTo(1);assertThat(new PDFTextStripper().getText(pdf)).contains("No booked movements","100.00");}}
 @Test void csvEscapesFormulaTextQuotesAndKeepsExactSignedMoney(){String csv=new String(StatementExports.csv(statement(2)),java.nio.charset.StandardCharsets.UTF_8);assertThat(csv).startsWith("\ufeff").contains("\"'=HYPERLINK(\"\"https://example.invalid\"\")\"").contains("\"1.25\"");assertThat(StatementExports.csvCell("-60.00")).isEqualTo("\"-60.00\"");assertThat(StatementExports.csvCell(" \t@SUM(1)")).startsWith("\"'");}
}
