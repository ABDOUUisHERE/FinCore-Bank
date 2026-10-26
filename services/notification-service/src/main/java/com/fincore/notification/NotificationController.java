package com.fincore.notification;
import java.util.*;
import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.fincore.support.ApiFailure;
@RestController
public class NotificationController {
 private final NotificationStore store;
 private final List<Stream> streams=new CopyOnWriteArrayList<>();
 private static final class Stream {final String subject;final Instant expiry;final SseEmitter emitter;long cursor=-1;
  Stream(String subject,Instant expiry,SseEmitter emitter){this.subject=subject;this.expiry=expiry;this.emitter=emitter;}}
 public NotificationController(NotificationStore store){this.store=store;}
 @GetMapping("/api/v1/notifications") public Object list(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false)Long before,@RequestParam(defaultValue="false")boolean unread){return store.list(jwt.getSubject(),before,unread);}
 @GetMapping("/api/v1/notifications/preferences") public Object preferences(@AuthenticationPrincipal Jwt jwt){return store.preferences(jwt.getSubject());}
 public record Preferences(Boolean inApp,Boolean email,Boolean payments,Boolean security,Long expectedVersion){}
 @PutMapping("/api/v1/notifications/preferences") public Object preferences(@AuthenticationPrincipal Jwt jwt,@RequestBody Preferences p){
  if(p.inApp()==null || p.email()==null || p.payments()==null || p.security()==null || p.expectedVersion()==null)throw new ApiFailure(400,"INVALID_PREFERENCES");
  return store.preferences(jwt.getSubject(),p.inApp(),p.email(),p.payments(),p.security(),p.expectedVersion());
 }
 public record Read(){}
 @PutMapping("/api/v1/notifications/{id}/read") public Object read(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestBody Read body){store.read(jwt.getSubject(),id);return Map.of("read",true);}
 @GetMapping("/api/v1/notifications/emails") public Object emails(@AuthenticationPrincipal Jwt jwt){return Map.of("items",store.emails(jwt.getSubject()));}
 @GetMapping(value="/api/v1/notifications/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
 public ResponseEntity<SseEmitter> stream(@AuthenticationPrincipal Jwt jwt){
  var expiry=jwt.getExpiresAt();if(expiry==null || !expiry.isAfter(Instant.now().plusSeconds(2)))throw new ApiFailure(401,"SESSION_EXPIRED");
  var emitter=new SseEmitter(Math.min(25000,Math.max(1000,java.time.Duration.between(Instant.now(),expiry.minusSeconds(1)).toMillis())));
  var stream=new Stream(jwt.getSubject(),expiry,emitter);
  synchronized(streams){if(streams.stream().filter(s->s.subject.equals(jwt.getSubject())).count()>=5)throw new ApiFailure(429,"STREAM_LIMIT");streams.add(stream);}
  Runnable cleanup=()->streams.remove(stream);emitter.onCompletion(cleanup);emitter.onTimeout(()->{cleanup.run();emitter.complete();});emitter.onError(e->cleanup.run());
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Accel-Buffering","no").body(emitter);
 }
 @Scheduled(fixedDelay=2000) public void updates(){for(var stream:streams){
  try{
   if(!Instant.now().isBefore(stream.expiry.minusSeconds(1))){stream.emitter.complete();streams.remove(stream);continue;}
   long cursor=store.cursor(stream.subject);
   if(cursor!=stream.cursor){stream.emitter.send(SseEmitter.event().name("notification.changed").data(Map.of("cursor",String.valueOf(cursor))));stream.cursor=cursor;}
   else stream.emitter.send(SseEmitter.event().comment("keepalive"));
  }catch(Exception error){stream.emitter.complete();streams.remove(stream);}
 }}
 @GetMapping("/internal/v1/notifications/dead-letters") public Object deadLetters(){return Map.of("items",store.deadLetters());}
 public record Replay(Long expectedVersion,String reason){}
 @PostMapping("/internal/v1/notifications/dead-letters/{id}/replay") public ResponseEntity<?> replay(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestBody Replay r){
  if(r.expectedVersion()==null)throw new ApiFailure(400,"INVALID_REPLAY_VERSION");
  return ResponseEntity.accepted().body(Map.of("replayId",store.replay(id,r.expectedVersion(),jwt.getSubject(),r.reason())));
 }
}
