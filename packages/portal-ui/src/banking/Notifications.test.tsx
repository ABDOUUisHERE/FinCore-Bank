import { beforeEach, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { Session } from "../auth";
import { LiveNotifications, Notifications } from "./Notifications";
import { readEventStream } from "./eventStream";
const mocks=vi.hoisted(()=>({session:{} as Session}));
vi.mock("../auth",()=>({useSession:()=>mocks.session}));
const response=(body:unknown,status=200)=>new Response(JSON.stringify(body),{status});
function open(children:React.ReactNode){return render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false,gcTime:0}}})}>{children}</QueryClientProvider>);}
beforeEach(()=>{vi.restoreAllMocks();mocks.session={status:"authenticated",roles:["CUSTOMER"],username:"synthetic",login:vi.fn(),logout:vi.fn(),token:vi.fn().mockResolvedValue("memory-only-bearer")};});
it("parses split SSE frames and ignores keepalives and unrelated event names",async()=>{
 const changed=vi.fn();const encoder=new TextEncoder();
 const stream=new ReadableStream<Uint8Array>({start(c){for(const part of [":keepalive\n\nevent:notification.ch","anged\r\ndata:{\"cursor\":\"1\"}\r","\n\r\nevent:other\ndata:{}\n\nevent: notification.changed\ndata:{}\n\n"])c.enqueue(encoder.encode(part));c.close();}});
 await readEventStream(stream.getReader(),changed);expect(changed).toHaveBeenCalledTimes(2);
});
it("keeps bearer authentication in the header and stops reconnecting on an expired session",async()=>{
 const fetch=vi.fn().mockResolvedValue(response({},401));vi.stubGlobal("fetch",fetch);
 open(<LiveNotifications subject="signed-owner"/>);
 expect(await screen.findByText("Sign in again for live updates")).toBeVisible();
 const [url,options]=fetch.mock.calls[0];expect(url).toBe("http://localhost:8280/api/v1/notifications/stream");
 expect(options.headers.Authorization).toBe("Bearer memory-only-bearer");expect(options.credentials).toBe("omit");
 expect(url).not.toContain("bearer");expect(JSON.stringify(sessionStorage)).not.toContain("memory-only-bearer");
 await userEvent.click(screen.getByRole("button",{name:"Sign in again"}));expect(mocks.session.login).toHaveBeenCalledOnce();
});
it("confirms versioned preferences and marks only the selected notification read",async()=>{
 const calls:{url:string;body:unknown}[]=[];
 const prefs={inApp:true,email:false,payments:true,security:true,version:4};let read=false;
 vi.stubGlobal("fetch",vi.fn().mockImplementation((url:string,options:RequestInit)=>{
  if(options.method==="PUT"){
   const body=options.body?JSON.parse(options.body as string):undefined;calls.push({url,body});
   if(url.endsWith("/preferences"))return Promise.resolve(response({...body,version:5}));read=true;return Promise.resolve(response({read:true}));
  }
  return Promise.resolve(response(url.endsWith("/preferences")?prefs:url.endsWith("/emails")?{items:[]}:{items:[{notificationId:"notice-1",aggregateId:"payment-1",category:"PAYMENTS",title:"Transfer completed",body:"100.00 MAD",createdAt:"2026-10-26T10:00:00Z",readAt:read?"2026-10-26T10:01:00Z":null}],nextCursor:null,unreadCount:read?0:1}));
 }));
 open(<Notifications subject="signed-owner"/>);
 expect(await screen.findByText("Transfer completed")).toBeVisible();
 await userEvent.click(screen.getByLabelText("Simulated emails"));expect(calls).toHaveLength(0);
 await userEvent.click(screen.getByRole("button",{name:"Review notification preferences"}));expect(calls).toHaveLength(0);
 await userEvent.click(screen.getByRole("button",{name:"Confirm change"}));
 await waitFor(()=>expect(calls[0]?.body).toEqual({inApp:true,email:true,payments:true,security:true,expectedVersion:4}));
 await userEvent.click(screen.getByRole("button",{name:"Mark as read"}));
 await waitFor(()=>expect(calls.some(c=>c.url.endsWith("/notifications/notice-1/read"))).toBe(true));
 expect(await screen.findByText("0 unread")).toBeVisible();
});
