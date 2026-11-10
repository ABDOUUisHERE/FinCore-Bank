# Phase 8 lot 11 — collected Reporting traces

Status: implementation and validation in progress. This lot follows the [Reporting audit acceptance](../verification/phase-8-reporting-access-audit.md); it does not complete Phase 8.

The bounded acceptance flow is hosted PKCE finance reconciliation through Gateway, Reporting and Ledger, followed by the committed Reporting access receipt, delayed outbox publication and Audit consumption. The stored trace graph must prove parent relationships, including publication after the HTTP request has ended. Kafka bodies, immutable source envelopes and their digests must remain unchanged. Retries create distinct attempt spans under the original request context.

Tracing accepts only a single strict W3C version-00 traceparent with nonzero IDs. These IDs are untrusted diagnostic context, never identity or authorization. No baggage, tracestate, raw URLs/query strings, names, resource/subject IDs, headers, credentials, financial contents or exception messages enter spans. Names and attribute keys/values are closed. New local roots are sampled; an incoming unsampled parent remains unsampled.

Only Gateway, Reporting, Ledger and Audit enable collection in this lot. Other owners keep their existing behavior. Only new Reporting receipts retain an origin context; existing deliveries have no fabricated origin. Context is delivery metadata, separate from immutable business/audit envelopes. Collector export uses a bounded asynchronous queue with a short timeout; collection is best effort and is not an audit durability guarantee. Collector outage must leave the business request and retained receipt working; dropped exports are not promised for later recovery.

Acceptance requires malformed/duplicate context isolation, delayed/retried publication with identical body bytes, central consume parentage, real stored graph assertions, collector outage/recovery and redaction checks. A trace header or dependency declaration alone is not proof of collection. The six required dashboards and remaining security/audit coverage retain their separate gates.

Implementation references: [OpenTelemetry SDK](https://opentelemetry.io/docs/languages/java/sdk/), [Zipkin v2 API](https://zipkin.io/zipkin-api/), [Zipkin 3.6.1 release](https://github.com/openzipkin/zipkin/releases/tag/3.6.1).
