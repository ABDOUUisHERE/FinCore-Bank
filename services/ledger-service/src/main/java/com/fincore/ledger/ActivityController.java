package com.fincore.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** Read-only confirmed ledger facts; no financial writes or inferred payment success. */
@RestController
@RequestMapping("/internal/v1/ledger/accounts/{id}")
public class ActivityController {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;
    public ActivityController(org.springframework.jdbc.core.JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc); this.json = json;
    }
    @GetMapping("/transactions") public Map<String,Object> history(@PathVariable UUID id, @RequestParam Map<String,String> filters) {
        var params = parameters(id, filters);
        var rows = jdbc.query("""
            with facts as (
              select j.*,p.side,p.amount,o.kind,coalesce(i.metadata->>'reference',o.business_reference) reference,
                     (select r.id from ledger.journal r where r.reversal_of=j.id) reversed_by,
                     i.id payment_id,i.metadata->>'sourceAccountId' source_account_id
              from ledger.posting p join ledger.journal j on j.id=p.journal_id
              join ledger.operation o on o.id=j.operation_id
              left join ledger.instruction i on i.issuer=o.issuer and i.id::text=o.business_reference
              where p.account_id=:id
            )
            select jsonb_build_object('journalId',id,'operationId',operation_id,'currency',currency,
              'side',side,'amount',amount::text,'createdAt',created_at,'reversalOf',reversal_of,
              'kind',kind,'reference',reference,'paymentId',payment_id,'reversedBy',reversed_by,'sourceAccountId',source_account_id)::text
            from facts where
              (cast(:before as uuid) is null or (created_at,id)<(
                select c.created_at,c.id from ledger.journal c join ledger.posting cp on cp.journal_id=c.id
                where cp.account_id=:id and c.id=cast(:before as uuid)))
              and (cast(:journal as uuid) is null or id=cast(:journal as uuid))
              and (cast(:from as timestamptz) is null or created_at>=cast(:from as timestamptz))
              and (cast(:until as timestamptz) is null or created_at<cast(:until as timestamptz))
              and (cast(:minimum as numeric) is null or amount>=cast(:minimum as numeric))
              and (cast(:maximum as numeric) is null or amount<=cast(:maximum as numeric))
              and (:side='' or side=:side) and (:kind='' or kind=:kind)
              and (:search='' or position(lower(:search) in lower(reference))>0)
            order by created_at desc,id desc limit 50
            """, params, (rs,n) -> decode(rs.getString(1)));
        var result = new LinkedHashMap<String,Object>();
        result.put("items", rows); result.put("nextCursor", rows.size()==50 ? rows.getLast().path("journalId").asText() : null);
        return result;
    }
    @GetMapping("/activity") public Object activity(@PathVariable UUID id, @RequestParam(required=false) String month) {
        YearMonth period;
        try { period = month==null ? YearMonth.now(ZoneOffset.UTC) : YearMonth.parse(month); }
        catch (java.time.DateTimeException error) { throw new IllegalArgumentException("Invalid month"); }
        if (period.getYear()<1970 || period.getYear()>9998) throw new IllegalArgumentException("Invalid month");
        var params = Map.<String,Object>of("id",id,"from",period.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC).toString(),
            "until",period.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC).toString());
        var rows = jdbc.query("""
            select jsonb_build_object('date',(j.created_at at time zone 'UTC')::date,
              'incoming',coalesce(sum(p.amount) filter(where p.side='CREDIT'),0)::text,
              'outgoing',coalesce(sum(p.amount) filter(where p.side='DEBIT'),0)::text)::text
            from ledger.posting p join ledger.journal j on j.id=p.journal_id where p.account_id=:id
              and j.created_at>=cast(:from as timestamptz) and j.created_at<cast(:until as timestamptz)
            group by (j.created_at at time zone 'UTC')::date order by (j.created_at at time zone 'UTC')::date
            """, params, (rs,n) -> decode(rs.getString(1)));
        return Map.of("month",period.toString(),"days",rows);
    }
    private static Map<String,Object> parameters(UUID id, Map<String,String> input) {
        var values = new HashMap<String,Object>(); values.put("id",id);
        String before=input.get("before"), from=input.get("from"), to=input.get("to");
        if (before!=null) UUID.fromString(before);
        values.put("before",before);
        String journal=input.get("journal");if(journal!=null)UUID.fromString(journal);values.put("journal",journal);
        try {
            var start=from==null ? null : LocalDate.parse(from); var end=to==null ? null : LocalDate.parse(to);
            if(start!=null && end!=null && start.isAfter(end)) throw new IllegalArgumentException("Invalid date range");
            values.put("from",start==null ? null : start.atStartOfDay().toInstant(ZoneOffset.UTC).toString());
            values.put("until",end==null ? null : end.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toString());
        } catch(java.time.DateTimeException error) { throw new IllegalArgumentException("Invalid date"); }
        var minimum=amount(input.get("minimum")); var maximum=amount(input.get("maximum"));
        if(minimum!=null && maximum!=null && minimum.compareTo(maximum)>0) throw new IllegalArgumentException("Invalid amount range");
        values.put("minimum",minimum); values.put("maximum",maximum);
        var side=input.getOrDefault("side",""); var kind=input.getOrDefault("kind",""); var search=input.getOrDefault("search","");
        if(!Set.of("","DEBIT","CREDIT").contains(side) || !Set.of("","DEPOSIT","TRANSFER","REVERSAL","WITHDRAWAL","MERCHANT_PAYMENT").contains(kind)
            || search.length()>140 || search.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid filter");
        values.put("side",side); values.put("kind",kind); values.put("search",search); return values;
    }
    private static BigDecimal amount(String value) {
        if(value==null) return null;
        if(!value.matches("(0|[1-9][0-9]{0,17})(\\.[0-9]{1,2})?")) throw new IllegalArgumentException("Invalid amount");
        return new BigDecimal(value);
    }
    private JsonNode decode(String value) {
        try { return json.readTree(value); } catch(java.io.IOException error) { throw new IllegalStateException("Invalid read data",error); }
    }
}
