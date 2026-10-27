package com.fincore.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;

/** Both exports render the same immutable snapshot; neither refetches mutable balances. */
public final class StatementExports {
 private StatementExports(){}
 static String csvCell(String text){String value=text==null?"":text;if(value.matches("(?s)^\\s*[=+@-].*") && !value.matches("-?[0-9]+\\.[0-9]{2}"))value="'"+value;return "\""+value.replace("\"","\"\"")+"\"";}
 static void csvRow(StringBuilder out,String...cells){out.append(Arrays.stream(cells).map(StatementExports::csvCell).collect(java.util.stream.Collectors.joining(","))).append("\r\n");}
 public static byte[] csv(JsonNode statement){var s=statement.path("snapshot");var out=new StringBuilder("\ufeff");csvRow(out,"FinCore synthetic statement",statement.path("statementId").asText());for(String field:List.of("accountId","month","currency","opening","movement","closing","currentBooked","currentReserved","currentAvailable","ledgerVersion","observedAt","source"))csvRow(out,field,s.path(field).asText());csvRow(out,"contentHash",statement.path("contentHash").asText());csvRow(out,"at","journalId","postingId","kind","reference","side","amount","signedAmount","reversalOf");for(var e:s.path("entries"))csvRow(out,e.path("at").asText(),e.path("journalId").asText(),e.path("postingId").asText(),e.path("kind").asText(),e.path("reference").asText(),e.path("side").asText(),e.path("amount").asText(),e.path("signedAmount").asText(),e.path("reversalOf").asText(""));return out.toString().getBytes(StandardCharsets.UTF_8);}
 public static byte[] pdf(JsonNode statement){
  var s=statement.path("snapshot");var entries=s.path("entries");int pages=Math.max(1,(entries.size()+14)/15);var running=new BigDecimal(s.path("opening").asText());
  try(var doc=new PDDocument();var bytes=new ByteArrayOutputStream()){
   var regular=new PDType1Font(Standard14Fonts.FontName.HELVETICA);var bold=new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
   doc.getDocumentInformation().setTitle("FinCore statement "+s.path("month").asText());doc.getDocumentInformation().setAuthor("FinCore synthetic banking");
   for(int pageIndex=0;pageIndex<pages;pageIndex++){
    var page=new PDPage(PDRectangle.A4);doc.addPage(page);
    try(var c=new PDPageContentStream(doc,page)){
     c.setNonStrokingColor(0.04f,0.10f,0.18f);c.addRect(0,686,page.getMediaBox().getWidth(),156);c.fill();c.setNonStrokingColor(0.22f,0.85f,0.71f);text(c,bold,22,44,791,"FINCORE");c.setNonStrokingColor(1f,1f,1f);text(c,bold,18,44,755,"Monthly account statement");text(c,regular,11,44,731,s.path("month").asText()+"  /  "+s.path("currency").asText()+"  /  UTC");text(c,regular,9,44,709,"Account "+s.path("accountId").asText());
     c.setNonStrokingColor(0.04f,0.10f,0.18f);text(c,regular,8,44,662,"Snapshot "+statement.path("statementId").asText());text(c,regular,7,44,647,"SHA-256 "+statement.path("contentHash").asText());
     String[] labels={"OPENING","NET MOVEMENT","CLOSING"};String[] fields={"opening","movement","closing"};for(int n=0;n<3;n++){float x=44+n*174;text(c,bold,8,x,615,labels[n]);fit(c,bold,17,x,591,158,s.path(fields[n]).asText());}
     text(c,regular,8,44,567,"Current booked "+s.path("currentBooked").asText()+"   Reserved "+s.path("currentReserved").asText()+"   Available "+s.path("currentAvailable").asText());
     text(c,regular,7,44,553,"Observed "+s.path("observedAt").asText()+"   Ledger version "+s.path("ledgerVersion").asText());
     c.setNonStrokingColor(0.05f,0.40f,0.35f);text(c,bold,8,44,524,"DATE / JOURNAL");text(c,bold,8,226,524,"OPERATION");text(c,bold,8,370,524,"CHANGE");text(c,bold,8,464,524,"BALANCE");
     c.setNonStrokingColor(0.04f,0.10f,0.18f);
     for(int row=0;row<15;row++){int index=pageIndex*15+row;if(index>=entries.size())break;var e=entries.get(index);float y=501-row*28;running=running.add(new BigDecimal(e.path("signedAmount").asText()));String at=e.path("at").asText();text(c,regular,8,44,y,at.length()>10?at.substring(0,10):at);text(c,regular,8,226,y,e.path("kind").asText());fit(c,bold,8,370,y,86,e.path("signedAmount").asText());fit(c,bold,8,464,y,87,running.setScale(2).toPlainString());text(c,regular,7,44,y-12,e.path("journalId").asText());}
     if(entries.isEmpty())text(c,regular,10,44,490,"No booked movements in this month.");
     text(c,regular,8,44,67,"Opening + booked movements = closing. Live reservations affect current availability only.");text(c,regular,8,44,51,"Synthetic banking - no real money or payment network.");text(c,regular,8,495,35,(pageIndex+1)+" / "+pages);
    }
   }
   doc.save(bytes);return bytes.toByteArray();
  }catch(IOException e){throw new IllegalStateException("Statement export unavailable",e);}
 }
 private static void text(PDPageContentStream stream,PDFont font,float size,float x,float y,String value)throws IOException{stream.beginText();stream.setFont(font,size);stream.newLineAtOffset(x,y);stream.showText(value);stream.endText();}
 private static void fit(PDPageContentStream stream,PDFont font,float size,float x,float y,float width,String value)throws IOException{float measured=font.getStringWidth(value)*size/1000; text(stream,font,measured>width?size*width/measured:size,x,y,value);}
}
