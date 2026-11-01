import test from "node:test";
import assert from "node:assert/strict";
import { once } from "node:events";
import { readFileSync } from "node:fs";
import * as grpc from "@grpc/grpc-js";
import Client, { CommitmentLevel, SubscribeRequest, SubscribeUpdate } from "@triton-one/yellowstone-grpc";
import { FirehoseRouter, PUMP, RAY, upstreamRequest } from "./router";
import { serverFor, service } from "./index";

function frame(i:number,wallet=i,now=Date.now(),filters=["firehose"]):SubscribeUpdate {
    const bytes=Buffer.alloc(129);
    Buffer.from([189,219,127,211,78,230,97,238]).copy(bytes);
    Buffer.alloc(32,11).copy(bytes,8);
    bytes.writeBigUInt64LE(2_000_000_000n,40);bytes.writeBigUInt64LE(1_000_000n,48);bytes[56]=1;
    Buffer.alloc(32,wallet).copy(bytes,57);
    bytes.writeBigInt64LE(BigInt(Math.floor(now/1000)),89);
    return SubscribeUpdate.fromPartial({filters,createdAt:new Date(now),transaction:{slot:"321",transaction:{
        signature:Buffer.alloc(64,i),transaction:{message:{accountKeys:[Buffer.alloc(32,wallet)],header:{numRequiredSignatures:1}}},
        meta:{logMessages:[`Program ${PUMP} invoke [1]`,"Program data: "+bytes.toString("base64"),`Program ${PUMP} success`]}
    }}});
}
const request=()=>SubscribeRequest.fromPartial({commitment:CommitmentLevel.PROCESSED,transactions:{
    mint_launch:{vote:false,failed:false,accountInclude:["TSLvdd1pWpHVjahSpsvCXUbgwsL3JAcvokwaKt1eokM"]},
    tape_0:{vote:false,failed:false,accountInclude:[RAY]}
}});

test("processed firehose targets the actual program, with votes and failed transactions excluded",()=>{
    const r=upstreamRequest(request());
    assert.equal(r.commitment,0);
    assert.ok(r.transactions.firehose.accountInclude.includes(PUMP));
    assert.ok(r.transactions.firehose.accountInclude.includes(RAY));
    assert.equal(r.transactions.firehose.failed,false);
    assert.equal(r.transactions.firehose.vote,false);
    assert.throws(()=>upstreamRequest(SubscribeRequest.fromPartial({commitment:0,transactions:{anything:{}}})));
});
test("three independent buyers deliver the retained first prints, then the hot mint's sells",()=>{
    const r=new FirehoseRouter(),now=Date.now();
    assert.deepEqual(r.select(frame(1,1,now),now),[]);
    assert.deepEqual(r.select(frame(2,2,now),now),[]);
    const delivered=r.select(frame(3,3,now),now);
    assert.equal(delivered.length,3);
    assert.deepEqual(delivered.map(v=>v.transaction!.transaction!.signature[0]),[1,2,3]);
    const sell=frame(4,4,now);
    sell.transaction!.transaction!.meta!.postTokenBalances=[{mint: "k7FaK87WHGVXzkaoHb7CdVPgkKDQhZ29VLDeBVbDfYn",accountIndex:0,owner:"",programId:"",uiTokenAmount:undefined}];
    // The tracked-mint route preserves sells independently of server discovery heuristics.
    sell.filters=["tape_0","firehose"];
    assert.equal(r.select(sell,now).length,1);
    assert.equal(r.select(sell,now).length,0);
});
test("single-wallet repeated trades, stale frames, failed transactions and arbitrary programs do not activate discovery",()=>{
    const r=new FirehoseRouter(),now=Date.now();
    for(let i=1;i<9;i++)assert.equal(r.select(frame(i,1,now),now).length,0);
    assert.equal(r.select(frame(10,2,now-20000),now).length,0);
    const bad=frame(11,3,now);bad.transaction!.transaction!.meta!.err={err:Buffer.from([1])};
    assert.equal(r.select(bad,now).length,0);
    const spoof=frame(12,4,now);
    spoof.transaction!.transaction!.meta!.logMessages[0]="Program spoof invoke [1]";
    assert.equal(r.select(spoof,now).length,0);
});
test("creation and watch traffic pass immediately without a metadata score",()=>{
    const r=new FirehoseRouter(),now=Date.now(),a=frame(1,1,now,["pool_launch"]);
    a.transaction!.transaction!.meta!.logMessages=[`Program ${PUMP} invoke [1]`,"Program log: Instruction: CreateV2",`Program ${PUMP} success`];
    assert.equal(r.select(a,now).length,1);
    assert.equal(r.select(frame(2,2,now,["tape_0"]),now).length,1);
});
test("independent raw Android fixture preserves v0 loaded addresses and protobuf signature",()=>{
    const kotlin=readFileSync("../lifecycle_apk/app/src/test/kotlin/com/lifecyclebot/network/ValidatorWire8506Test.kt","utf8");
    const raw=Buffer.from(kotlin.match(/private val fixture = "([^"]+)"/)![1],"base64");
    const decoded=SubscribeUpdate.decode(raw);
    assert.equal(decoded.transaction!.slot,"123456");
    assert.equal(decoded.transaction!.transaction!.signature.length,64);
    assert.equal(decoded.transaction!.transaction!.meta!.loadedWritableAddresses.length,1);
    assert.equal(decoded.transaction!.transaction!.meta!.loadedReadonlyAddresses.length,1);
    assert.equal(decoded.transaction!.transaction!.transaction!.message!.header!.numRequiredSignatures,2);
    assert.deepEqual(SubscribeUpdate.decode(SubscribeUpdate.encode(decoded).finish()),decoded);
});

async function bind(server:grpc.Server) {
    return await new Promise<number>((resolve,reject)=>server.bindAsync("127.0.0.1:0",grpc.ServerCredentials.createInsecure(),(e,p)=>e?reject(e):resolve(p)));
}
test("real bidirectional gRPC connection authenticates, injects processed program filters and forwards raw tx",async t=>{
    const upstream=new grpc.Server();
    let received:SubscribeRequest|undefined;
    upstream.addService(service,{subscribe:(c:grpc.ServerDuplexStream<SubscribeRequest,SubscribeUpdate>)=>{
        c.on("error",()=>{});
        c.on("data",r=>{
            received=r;
            c.write(frame(5,5,Date.now(),["tape_0"]));
        });
    }});
    const upPort=await bind(upstream);
    const provider=new Client(`http://127.0.0.1:${upPort}`,"test-provider-token",{});
    const relay=serverFor(()=>provider.subscribe(),"test-device-token");
    const port=await bind(relay);
    const consumer=new Client(`http://127.0.0.1:${port}`,"test-device-token",{});
    t.after(()=>{consumer._client.close();provider._client.close();relay.forceShutdown();upstream.forceShutdown();});
    const stream=await consumer.subscribe();
    stream.on("error",()=>{});
    t.after(()=>stream.cancel());
    stream.write(request());
    const [answer]=await once(stream,"data",{signal:AbortSignal.timeout(5000)});
    assert.equal(answer.transaction.transaction.signature[0],5);
    assert.equal(received!.commitment,CommitmentLevel.PROCESSED);
    assert.ok(received!.transactions.firehose.accountInclude.includes(PUMP));
    stream.cancel();
});
test("relay rejects an unauthorized device before opening a provider stream",async t=>{
    let opened=0;
    const relay=serverFor(async()=>{opened++;throw new Error("must not reach");},"correct");
    const port=await bind(relay);
    const client=new Client(`http://127.0.0.1:${port}`,"incorrect",{});
    t.after(()=>{client._client.close();relay.forceShutdown();});
    const stream=await client.subscribe();
    const [error]=await once(stream,"error",{signal:AbortSignal.timeout(5000)});
    assert.equal(error.code,grpc.status.UNAUTHENTICATED);
    assert.equal(opened,0);
});
