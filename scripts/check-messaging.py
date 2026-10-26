"""Exercise the private broker ACL, poison retry/DLQ and audited same-identity replay. No credentials printed."""
import json,subprocess,time,uuid

def run(args,body=None):
    return subprocess.run(["docker","compose",*args],input=body,capture_output=True,text=True,timeout=90)
def operator(*args):
    r=run(["run","--rm","--no-deps","-T","notification-operator",*args])
    if r.returncode:raise AssertionError("Scoped notification operator failed")
    return json.loads(r.stdout)
def produce(user,topic,body):
    return run(["exec","-T","-e","KAFKA_HEAP_OPTS=-Xms32m -Xmx128m","kafka","/opt/kafka/bin/kafka-console-producer.sh","--bootstrap-server","kafka:9092","--producer.config","/etc/kafka/secrets/"+user+".properties","--topic",topic,"--sync","--property","parse.key=true","--property","key.separator=|","--producer-property","max.block.ms=10000","--producer-property","request.timeout.ms=5000","--producer-property","delivery.timeout.ms=12000"],body+"\n")
def wait_new(before):
    end=time.monotonic()+90
    while time.monotonic()<end:
        rows=[r for r in operator("list")["items"] if r["deadLetterId"] not in before]
        if rows:return rows
        time.sleep(2)
    raise AssertionError("Poison record was not durably quarantined")
def main():
    identifier=str(uuid.uuid4())
    denied=produce("customer","fincore.payment.completed.v1",identifier+"|"+json.dumps({"mustNeverPublish":True}))
    assert "TopicAuthorizationException" in denied.stderr+denied.stdout,"Producer was not denied its foreign topic"
    print("PASS: customer Kafka identity cannot publish payment facts",flush=True)
    before={r["deadLetterId"] for r in operator("list")["items"]}
    body=json.dumps({"eventId":str(uuid.uuid4()),"eventType":"customer.created","schemaVersion":99,"aggregateId":identifier,"aggregateVersion":0,"correlationId":identifier,"occurredAt":"2026-10-26T10:00:00Z","payload":{}})
    sent=produce("customer","fincore.customer.created.v1",identifier+"|"+body)
    assert sent.returncode==0,"Authorized poison test publication failed"
    letters=wait_new(before);letter=next(r for r in letters if r["sourceTopic"]=="fincore.customer.created.v1")
    before.update(r["deadLetterId"] for r in letters)
    result=operator("replay",letter["deadLetterId"],str(letter["version"]),"VERIFY_RETAINED_POISON")
    assert result["replayId"]
    again=wait_new(before)
    assert any(r["sourceTopic"]==letter["sourceTopic"] for r in again)
    original=next(r for r in operator("list")["items"] if r["deadLetterId"]==letter["deadLetterId"])
    assert original["version"]==letter["version"]+1
    print("PASS: bounded poison retries, durable DLQ, versioned replay audit and retained invalid original",flush=True)
    print("Replay deliberately preserves the unsupported schema; it remains quarantined until a compatible consumer exists.",flush=True)
if __name__=="__main__":
    try:main()
    except (AssertionError,ValueError,subprocess.TimeoutExpired) as e:raise SystemExit(str(e) if isinstance(e,AssertionError) else "Messaging integration could not be confirmed") from None
