import Client, { SubscribeRequest, SubscribeUpdate } from "@triton-one/yellowstone-grpc";
import * as grpc from "@grpc/grpc-js";
import { readFileSync } from "node:fs";
import { createHash, timingSafeEqual } from "node:crypto";
import { once } from "node:events";
import { setTimeout as wait } from "node:timers/promises";
import { FirehoseRouter, upstreamRequest } from "./router";

export const service = {subscribe: {
    path:"/geyser.Geyser/Subscribe",requestStream:true,responseStream:true,
    requestSerialize:(v:SubscribeRequest)=>Buffer.from(SubscribeRequest.encode(v).finish()),
    requestDeserialize:(v:Buffer)=>SubscribeRequest.decode(v),
    responseSerialize:(v:SubscribeUpdate)=>Buffer.from(SubscribeUpdate.encode(v).finish()),
    responseDeserialize:(v:Buffer)=>SubscribeUpdate.decode(v)
}};

type Call = grpc.ServerDuplexStream<SubscribeRequest,SubscribeUpdate>;
type Upstream = Awaited<ReturnType<Client["subscribe"]>>;
type Open = ()=>Promise<Upstream>;
const digest=(v:string)=>createHash("sha256").update(v).digest();

export function serverFor(open:Open,token:string) {
    const server=new grpc.Server({"grpc.max_receive_message_length":65536});
    let clients=0;
    server.addService(service,{subscribe:(call:Call)=>{
        const secret=call.metadata.get("x-token");
        if(secret.length!==1 || typeof secret[0]!=="string" || !timingSafeEqual(digest(secret[0]),digest(token))) {
            call.emit("error",{code:grpc.status.UNAUTHENTICATED,details:"invalid relay token"});return;
        }
        if(clients>=4){call.emit("error",{code:grpc.status.RESOURCE_EXHAUSTED,details:"device streams in use"});return;}
        clients++;
        const stop=new AbortController();
        let upstream:Upstream|undefined, request:SubscribeRequest|undefined, running=false;
        const close=()=>{
            if(stop.signal.aborted)return;
            stop.abort(); clients--; upstream?.cancel(); upstream=undefined;
        };
        call.on("cancelled",close); call.on("close",close); call.on("error",close);
        call.on("end",()=>{close();call.end();});
        async function connect() {
            let delay=500;
            const router=new FirehoseRouter();
            while(!stop.signal.aborted && request) {
                try {
                    const stream=await open();
                    stream.on("error",()=>{}); // errors also reject the async iterator
                    if(stop.signal.aborted){stream.cancel();return;}
                    upstream=stream; stream.write(request);
                    for await(const frame of stream as AsyncIterable<SubscribeUpdate>) {
                        if(stop.signal.aborted)return;
                        if(frame.ping) {stream.write(SubscribeRequest.fromPartial({ping:{id:1}}));continue;}
                        for(const item of router.select(frame,Date.now())) {
                            if(!call.write(item)) await once(call,"drain",{signal:stop.signal});
                        }
                        delay=500;
                    }
                } catch { /* retry without logging either provider credential */ }
                finally {upstream?.cancel();upstream=undefined;}
                if(!stop.signal.aborted) await wait(delay,undefined,{signal:stop.signal}).catch(()=>{});
                delay=Math.min(delay*2,30_000);
            }
        }
        call.on("data",(incoming:SubscribeRequest)=>{
            if(stop.signal.aborted)return;
            if(incoming.ping && Object.keys(incoming.transactions).length===0) {call.write(SubscribeUpdate.fromPartial({pong:{id:incoming.ping.id},createdAt:new Date()}));return;}
            try {
                const next=upstreamRequest(incoming);
                if(request && next.commitment!==request.commitment) throw new Error();
                request=next;
            } catch {call.emit("error",{code:grpc.status.INVALID_ARGUMENT,details:"invalid subscription"});return;}
            if(upstream)upstream.write(request);
            if(!running){running=true;void connect();}
        });
    }});
    return server;
}

if(require.main===module) {
    function required(key:string) {const v=process.env[key];if(!v)throw new Error("Missing "+key);return v;}
    const endpoint=process.env.HELIUS_GRPC_ENDPOINT??"https://laserstream-mainnet-tyo.helius-rpc.com";
    if(new URL(endpoint).protocol!=="https:") throw new Error("HELIUS_GRPC_ENDPOINT requires TLS");
    const client=new Client(endpoint,required("HELIUS_API_KEY"),{"grpc.max_receive_message_length":64*1024*1024});
    const server=serverFor(()=>client.subscribe(),required("AATE_RELAY_TOKEN"));
    const tls=grpc.ServerCredentials.createSsl(null,[{private_key:readFileSync(required("TLS_KEY_FILE")),cert_chain:readFileSync(required("TLS_CERT_FILE"))}]);
    server.bindAsync(process.env.LISTEN_ADDRESS??"127.0.0.1:9443",tls,(err,port)=>{
        if(err)throw err;
        console.info("AATE validator relay listening on port",port);
    });
    for(const signal of ["SIGTERM","SIGINT"])process.once(signal,()=>server.forceShutdown());
}
