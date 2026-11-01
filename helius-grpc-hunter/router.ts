import { CommitmentLevel, SubscribeRequest, SubscribeUpdate } from "@triton-one/yellowstone-grpc";
import bs58 from "bs58";

export const PUMP = "6EF8rrecthR5Dkzon8Nwu78hRvfCKubJ14M5uBEwF6P";
export const RAY = "675kPX9MHTjS2zt1qfr1NYHuzeLXfQM9H24wFSUt1Mp8";
const WSOL = "So11111111111111111111111111111111111111112";
const AMM = "pAMMBay6oceH9fJKBRHGP5D4bD4sWpmSwMn52FMfXEA";
const CPMM = "CPMMoo8L3F4NbTegBCKVNunggL7H1ZpdTHKxQB5qKP1C";
const knownPrograms = new Set([PUMP, RAY, AMM, CPMM]);
const quotes = new Set([WSOL,"EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v","Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB"]);

// Android asks for named launch filters and a bounded tape. It cannot widen the relay to arbitrary account/block feeds.
export function upstreamRequest(input: SubscribeRequest): SubscribeRequest {
    const commitment = input.commitment;
    if (commitment !== CommitmentLevel.PROCESSED && commitment !== CommitmentLevel.CONFIRMED) throw new Error("invalid commitment");
    const request = SubscribeRequest.fromPartial({ commitment, blocksMeta: commitment === CommitmentLevel.CONFIRMED ? { clock: {} } : {} });
    for (const [tag, f] of Object.entries(input.transactions)) {
        if (!/^(mint_launch|pool_launch|tape_[01])$/.test(tag) || f.accountInclude.length === 0 || f.accountInclude.length > 10 ||
            f.accountRequired.length > 1 || f.accountExclude.length || [...f.accountInclude,...f.accountRequired].some(a => !/^[1-9A-HJ-NP-Za-km-z]{32,44}$/.test(a))) {
            throw new Error("invalid app filter");
        }
        request.transactions[tag] = {...f,vote:false,failed:false};
    }
    request.transactions.firehose = { accountInclude:[...knownPrograms],accountExclude:[],accountRequired:[],vote:false,failed:false };
    return request;
}

type Info = NonNullable<NonNullable<SubscribeUpdate["transaction"]>["transaction"]>;
type Buy = {mint:string; wallet:string};
const tradeDiscriminator = Buffer.from([189,219,127,211,78,230,97,238]);

// Verified events identify the mint. Account positions alone do not distinguish swaps, liquidity deposits or transfers.
function buyIdentity(info: Info): Buy | undefined {
    const msg=info.transaction?.message, meta=info.meta;
    if (!msg || !meta || info.isVote || meta.err) return;
    const stack:string[]=[];
    let dexSwap=false;
    for (const line of meta.logMessages) {
        if (line.startsWith("Program ") && line.includes(" invoke [")) { stack.push(line.split(" ")[1]); continue; }
        if (/^Program \S+ (success|failed)/.test(line)) { stack.pop(); continue; }
        const owner=stack.at(-1);
        if (owner===PUMP && line.startsWith("Program data: ")) {
            const e=Buffer.from(line.slice(14),"base64");
            if(e.length>=113 && e.subarray(0,8).equals(tradeDiscriminator) && e[56]===1 && e.readBigUInt64LE(40)>0n && e.readBigUInt64LE(48)>0n)
                return {mint:bs58.encode(e.subarray(8,40)), wallet:bs58.encode(e.subarray(57,89))};
        }
        if (owner && knownPrograms.has(owner) && (/^Program log: Instruction: (Buy|Swap|SwapBaseInput|SwapBaseOutput|SwapV2)$/.test(line) ||
            (owner===RAY && line.startsWith("Program log: ray_log: ") && [3,4].includes(Buffer.from(line.slice(22),"base64")[0])))) dexSwap=true;
    }
    if (!dexSwap) return;
    const signerKeys=new Set(msg.accountKeys.slice(0,msg.header?.numRequiredSignatures ?? 0).map(k=>bs58.encode(k)));
    const deltas=new Map<string,Map<string,bigint>>();
    for (const [rows,side] of [[meta.preTokenBalances,-1n],[meta.postTokenBalances,1n]] as const) {
        for(const row of rows) {
            if (!signerKeys.has(row.owner) || !row.uiTokenAmount || !/^\d+$/.test(row.uiTokenAmount.amount)) continue;
            const owned=deltas.get(row.owner) ?? new Map<string,bigint>();
            owned.set(row.mint,(owned.get(row.mint)??0n)+side*BigInt(row.uiTokenAmount.amount)); deltas.set(row.owner,owned);
        }
    }
    const candidates:Buy[]=[];
    for(const [wallet,delta] of deltas) {
        const base=[...delta].filter(([m,v])=>!quotes.has(m) && v!==0n);
        if(base.length===1 && base[0][1]>0n && [...delta].some(([m,v])=>quotes.has(m)&&v<0n)) candidates.push({mint:base[0][0],wallet});
    }
    return candidates.length===1?candidates[0]:undefined;
}

type Seen = { at:number; signature:string; wallet:string; frame:SubscribeUpdate };
export class FirehoseRouter {
    private pending = new Map<string,Seen[]>();
    private hot = new Map<string,number>();
    private seen = new Map<string,number>();
    private transactions=0;
    private boundedSet<V>(map:Map<string,V>,key:string,value:V,limit:number) {
        map.delete(key); map.set(key,value);
        if(map.size>limit) map.delete(map.keys().next().value!);
    }
    select(frame:SubscribeUpdate, now:number): SubscribeUpdate[] {
        if(!frame.transaction) return frame.blockMeta || frame.ping || frame.pong ? [frame] : [];
        const info=frame.transaction.transaction;
        if(!info || info.isVote || info.meta?.err || !info.meta || info.signature.length!==64) return [];
        const published=frame.createdAt?.getTime();
        if(published===undefined || now-published>15000 || published-now>2000) return [];
        const signature=bs58.encode(info.signature);
        if(this.seen.has(signature)) return [];
        this.boundedSet(this.seen,signature,now,8192);
        if(++this.transactions%128===0) {
            for(const [m,rows] of this.pending) if(now-rows.at(-1)!.at>4000) this.pending.delete(m);
            for(const [m,until] of this.hot) if(now>until) this.hot.delete(m);
        }
        // The requested held/watch tape, named creations and migrations have immediate passage, including sells.
        if(frame.filters.some(f=>f!=="firehose" && f!=="pool_launch")) return [frame];
        if(info.meta.logMessages.some(l=>/^Program log: (Instruction: (Create|Initialize)|initialize2)/.test(l))) return [frame];
        if(this.hot.size && [...info.meta.preTokenBalances,...info.meta.postTokenBalances].some(b=>(this.hot.get(b.mint)??0)>now))
            return [{...frame,filters:["tape_relay"]}];
        const buyer=buyIdentity(info);
        if(!buyer) return [];
        // Bound retained RAW frames as well as key counts on the server. Oversized transactions remain eligible via held/watch.
        if(SubscribeUpdate.encode(frame).finish().length>32768) return [];
        const rows=(this.pending.get(buyer.mint)??[]).filter(r=>now-r.at<=4000);
        rows.push({at:now,signature,wallet:buyer.wallet,frame});
        if(rows.length>4) rows.shift();
        if(new Set(rows.map(r=>r.wallet)).size<3) {this.boundedSet(this.pending,buyer.mint,rows,256); return [];}
        this.pending.delete(buyer.mint); this.boundedSet(this.hot,buyer.mint,now+30_000,256);
        return rows.map(r=>({...r.frame,filters:["tape_relay"]}));
    }
}
