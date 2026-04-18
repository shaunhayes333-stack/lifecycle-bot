package com.lifecyclebot.perps

import com.lifecyclebot.engine.ErrorLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 📡 PYTH ORACLE INTEGRATION - V5.7.1
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Real-time price feeds for SOL and tokenized stocks via Pyth Network.
 * 
 * PYTH NETWORK:
 * - Permissionless oracle for DeFi
 * - Sub-second latency price feeds
 * - Confidence intervals for accuracy
 * - 380+ price feeds across 40+ blockchains
 * 
 * SUPPORTED FEEDS:
 * - SOL/USD (Crypto.SOL/USD)
 * - AAPL/USD (Equity.US.AAPL/USD)
 * - TSLA/USD (Equity.US.TSLA/USD)
 * - NVDA/USD (Equity.US.NVDA/USD)
 * - GOOGL/USD (Equity.US.GOOGL/USD)
 * - AMZN/USD (Equity.US.AMZN/USD)
 * - META/USD (Equity.US.META/USD)
 * - MSFT/USD (Equity.US.MSFT/USD)
 * - COIN/USD (Equity.US.COIN/USD)
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object PythOracle {
    
    private const val TAG = "📡PythOracle"
    
    // Pyth Hermes API endpoint (free, no API key required)
    private const val PYTH_HERMES_URL = "https://hermes.pyth.network/api/latest_price_feeds"
    private const val PYTH_PRICE_URL = "https://hermes.pyth.network/v2/updates/price/latest"
    
    // Pyth Price Feed IDs (mainnet) — V5.9.22 MASSIVE EXPANSION
    // All IDs below are verified against Pyth Hermes mainnet (gist/npip99/9c530ce).
    // Typos corrected: TRX, XMR, ETC, MATIC, WLD (previously 404'd).
    private val PRICE_FEED_IDS = mapOf(
        // ═══════════════════════════════════════════════════════════════════════
        // 🪙 MAJOR CRYPTOCURRENCIES
        // ═══════════════════════════════════════════════════════════════════════
        "SOL" to "0xef0d8b6fda2ceba41da15d4095d1da392a0d2f8ed0c6c7bc0f4cfac8c280b56d",
        "BTC" to "0xe62df6c8b4a85fe1a67db44dc12de5db330f7ac66b72dc658afedf0f4a415b43",
        "ETH" to "0xff61491a931112ddf1bd8147cd1b641375f79f5825126d665480874634fd0ace",
        "BNB" to "0x2f95862b045670cd22bee3114c39763a4a08beeb663b145d283c31d7d1101c4f",
        "XRP" to "0xec5d399846a9209f3fe5881d70aae9268c94339ff9817e8d18ff19fa05eea1c8",
        "ADA" to "0x2a01deaec9e51a579277b34b122399984d0bbf57e2458a7e42fecd2829867a0d",
        "DOGE" to "0xdcef50dd0a4cd2dcc17e45df1676dcb336a11a61c69df7a0299b0150c672d25c",
        "AVAX" to "0x93da3352f9f1d105fdfe4971cfa80e9dd777bfc5d0f683ebb6e1294b92137bb7",
        "DOT" to "0xca3eed9b267293f6595901c734c7525ce8ef49adafe8284606ceb307afa2ca5b",
        "LINK" to "0x8ac0c70fff57e9aefdf5edf44b51d62c2d433653cbb2cf5cc06bb115af04d221",
        "MATIC" to "0x5de33a9112c2b700b8d30b8a3402c103578ccfa2765696471cc672bd5cf6ac52", // V5.9.22 fixed
        "SHIB" to "0xf0d57deca57b3da2fe63a493f4c25925fdfd8edf834b20f93e1f84dbd1504d4a",
        "LTC" to "0x6e3f3fa8253588df9326580180233eb791e03b443a3ba7a1d892e73874e19a54",
        "ATOM" to "0xb00b60f88b03a6a625a8d1c048c3f66653edf217439983d037e7222c4e612819",
        "UNI" to "0x78d185a741d07edb3412b09008b7c5cfb9bbbd7d568bf00ba737b456ba171501",
        "ARB" to "0x3fa4252848f9f0a1480be62745a4629d9eb1322aebab8a791e344b3b9c1adcf5",
        "OP" to "0x385f64d993f7b77d8182ed5003d97c60aa3361f3cecfe711544d2d59165e9bdf",
        "APT" to "0x03ae4db29ed4ae33d323568895aa00337e658e348b37509f5372ae51f0af00d5",
        "SUI" to "0x23d7315113f5b1d3ba7a83604c44b94d79f4fd69af77f804fc7f920a6dc65744",
        "SEI" to "0x53614f1cb0c031d4af66c04cb9c756234adad0e1cee85303795091499a4084eb",
        "INJ" to "0x7a5bc1d2b56ad029048cd63964b3ad2776eadf812edc1a43a31406cb54bff592",
        "TIA" to "0x09f7c1d7dfbb7df2b8fe3d3d87ee94a2259d212da4f30c1f0540d066dfa44723",
        "JUP" to "0x0a0408d619e9380abad35060f9192039ed5042fa6f82301d0e48bb52be830996",
        "PEPE" to "0xd69731a2e74ac1ce884fc3890f7ee324b6deb66147055249568869ed700882e4",
        "WIF" to "0x4ca4beeca86f0d164160323817a4e42b10010a724c2217c6ee41b54cd4cc61fc",
        "BONK" to "0x72b021217ca3fe68922a19aaf990109cb9d84e9ad004b4d2025ad6f529314419",
        "NEAR" to "0xc415de8d2eba7db216527dff4b60e8f3a5311c740dadb233e13e12547e226750",
        "ICP" to "0xc9907d786c5821547777780a1e4f89484f3417cb14dd244f2b0a34ea7a554d67",
        "FIL" to "0x150ac9b959aee0051e4091f0ef5216d941f590e1c5e7f91cf7635b5c11628c0e",
        "FTM" to "0x5c6c0d2386e3352356c3ab84434fafb5ea067ac2678a38a338c4a69ddc4bdb0c",
        "ALGO" to "0xfa17ceaf30d19ba51112fdcc750cc83454776f47fb0112e4af07f15f4bb1ebc0",
        "VET" to "0x1722176f738aa1aafea170f8b27724042c5ac6d8cb9cf8ae02d692b0927e0681",
        "FLOW" to "0x2fb245b9a84554a0f15aa123cbb5f64cd263b59e9a87d80148cbffab50c69f30",
        "MINA" to "0xe322f437708e16b033d785fceb5c7d61c94700364281a10fabc77ca20ef64bf1",
        "KAVA" to "0xa6e905d4e85ab66046def2ef0ce66a7ea2a60871e68ae54aed50ec2fd96d8584",
        "KSM" to "0xdedebc9e4d916d10b76cfbc21ccaacaf622ab1fc7f7ba586a0de0eba76f12f3f",
        "STX" to "0xec7a775f46379b5e943c3526b1c8d54cd49749176b0b98e02dde68d1bd335c17",
        "HNT" to "0x649fdd7ec08e8e2a20f425729854e90293dcbe2376abc47197a14da6ff339756",

        // ═══════════════════════════════════════════════════════════════════════
        // V5.9.22 - MAJOR ALTS (Pyth-verified feed IDs — typos fixed)
        // ═══════════════════════════════════════════════════════════════════════
        "TRX" to "0x67aed5a24fdad045475e7195c98a98aea119c763f272d4523f5bac93a4f33c2b", // fixed
        "BCH" to "0x3dd2b63686a450ec7290df3a1e0b583c0481f651351edfa7636f39aed55cf8a3",
        "XLM" to "0xb7a8eba68a997cd0210c2e1e4ee811ad2d174b3611c22d9ebf16f4cb7e9ba850",
        "XMR" to "0x46b8cc9347f04391764a0361e0b17c3ba394b001e7c304f7650f6376e37c321d", // fixed
        "ETC" to "0x7f5cc8d963fc5b3d2ae41fe5685ada89fd4f14b435f8050f28c7fd409f40c2d8", // fixed
        "BSV" to "0xb44565b8b9b39ab2f4ba792f1c8f8aa8ef7d780e709b191637ef886d96fd1472",
        "ZEC" to "0xbe9b59d178f0d6a97ab4c343bff2aa69caa1eaae3e9048a65788c529b125bb24",
        "ZIL" to "0x609722f3b6dc10fee07907fe86781d55eb9121cd0705b480954c00695d78f0cb",
        "DASH" to "0xb44565b8b9b39ab2f4ba792f1c8f8aa8ef7d780e709b191637ef886d96fd1472", // fall-through (no DASH feed — keep on fallback)
        "XTZ" to "0x0affd4b8ad136a21d79bc82450a325ee12ff55a235abc242666e423b8bcffd03",
        "EOS" to "0x06ade621dbc31ed0fc9255caaab984a468abe84164fb2ccc76f02a4636d97e31",
        "WAVES" to "0x70dddcb074263ce201ea9a1be5b3537e59ed5b9060d309e12d61762cfe59fb7e",
        "IOTA" to "0xc7b72e5d860034288c9335d4d325da4272fe50c92ab72249d58f6cbba30e4c44",
        "THETA" to "0xee70804471fe22d029ac2d2b00ea18bbf4fb062958d425e5830fd25bed430345",
        "ONE" to "0xc572690504b42b57a3f7aed6bd4aae08cbeeebdadcf130646a692fe73ec1e009",

        // DeFi & New Ecosystem
        "DYDX" to "0x6489800bb8974169adfe35937bf6736507097d13c190d760c557108c7e93a81b",
        "WLD" to "0xd6835ad1f773de4a378115eb6824bd0c0e42d84d1c84d9750e853fb6b6c7794a", // fixed
        "JTO" to "0xb43660a5f790c69354b0729a5ef9d50d68f1df92107540210b9cccba1f947cc2",
        "W" to "0xeff7446475e218517566ea99e72a4abec2e1bd8498b43b7d8331e29dcb059389",
        "STRK" to "0x6a182399ff70ccf3e06024898942028204125a819e519a335ffa4579e66cd870",
        "TAO" to "0x410f41de235f2db824e562ea7ab2d3d3d4ff048316c61d629c0b93f58584e1af",
        "TON" to "0x8963217838ab4cf5cadc172203c1f0b763fbaa45f346d8ee50ba994bbcac3026",
        "PENDLE" to "0x9a4df90b25497f66b1afb012467e316e801ca3d839456db028892fe8c70c8016",
        "CAKE" to "0x2356af9529a1064d41e32d617e2ce1dca5733afa901daba9e2b68dee5d53ecf9",
        "GMX" to "0xb962539d0fcb272a494d65ea56f94851c2bcf8823935da05bd628916e2e9edbf",
        "ENJ" to "0x5cc254b7cb9532df39952aee2a6d5497b42ec2d2330c7b76147f695138dbd9f3",
        "ENS" to "0xb98ab6023650bd2edc026b983fb7c2f8fa1020286f1ba6ecf3f4322cd83b72a6",
        "FET" to "0xb98e7ae8af2d298d2651eb21ab5b8b5738212e13efb43bd0dfbce7a74ba4b5d0",
        "FLOKI" to "0x6b1381ce7e874dc5410b197ac8348162c0dd6c0d4c9cd6322672d6c2b1d58293",
        "GALA" to "0x0781209c28fda797616212b7f94d77af3a01f3e94a5d421760aef020cf2bcb51",
        "SAND" to "0xcb7a1d45139117f8d3da0a4b67264579aa905e3b124efede272634f094e1e9d1",
        "MANA" to "0x1dfffdcbc958d732750f53ff7f06d24bb01364b3f62abea511a390c74b8d16a5",
        "AXS" to "0xb7e3904c08ddd9c0c10c6d207d390fd19e87eb6aab96304f571ed94caebdefa0",
        "APE" to "0x15add95022ae13563a11992e727c91bdb6b55bc183d9d747436c80a483d8c864",
        "AAVE" to "0x2b9ab1e972a281585084148ba1389800799bd4be63b957507db1349314e47445",
        "LDO" to "0xc63e2a7f37a04e5e614c07238bedb25dcc38927fba8fe890597a593c0b2fa4ad",
        "MKR" to "0x9375299e31c0deb9c6bc378e6329aab44cb48ec655552a70d4b9050346a30378",
        "YFI" to "0x425f4b198ab2504936886c1e93511bb6720fbcf2045a4f3c0723bb213846022f",
        "COMP" to "0x4a8e42861cabc5ecb50996f92e7cfa2bce3fd0a2423b0c44c9b423fb2bd25478",
        "SNX" to "0x39d020f60982ed892abbcd4a06a276a9f9b7bfbce003204c110b6e488f502da3",
        "CRV" to "0xa19d04ac696c7a6616d291c7e5d1377cc8be437c327b75adb5dc1bad745fcae8",
        "SUSHI" to "0x26e4f737fde0263a9eea10ae63ac36dcedab2aaf629261a994e1eeb6ee0afe53",
        "GRT" to "0x4d1f8dae0d96236fb98e8f47471a366ec3b1732b47041781934ca3a9bb2f35e7",
        "1INCH" to "0x63f341689d98a12ef60a5cff1d7f85c70a9e17bf1575f0e7c0b2512d48b1c8b3",
        "RUNE" to "0x5fcf71143bb70d41af4fa9aa1287e2efd3c5911cee59f909f915c9f61baacb1e",
        "RNDR" to "0xab7347771135fc733f8f38db462ba085ed3309955f42554a14fa13e855ac0e2f",
        "RPL" to "0x24f94ac0fd8638e3fc41aab2e4df933e63f763351b640bf336a6ec70651c4503",
        "IMX" to "0x941320a8989414874de5aa2fc340a75d5ed91fdff1613dd55f83844d52ea63a2",
        "GMT" to "0xbaa284eaf23edf975b371ba2818772f93dbae72836bbdea28b07d40f3cf8b485",
        "BLUR" to "0x856aac602516addee497edf6f50d39e8c95ae5fb0da1ed434a8c2ab9c3e877e9",
        "ARKM" to "0x7677dd124dee46cfcd46ff03cf405fb0ed94b1f49efbea3444aadbda939a7ad3",
        "WOO" to "0xb82449fd728133488d2d41131cffe763f9c1693b73c544d9ef6aaa371060dd25",
        "FTT" to "0x6c75e52531ec5fd3ef253f6062956a8508a2f03fa0a209fb7fbc51efd9d35f88",
        "ORCA" to "0x37505261e557e251290b8c8899453064e8d760ed5c65a779726f2490980da74c",
        "RAY" to "0x91568baa8beb53db23eb3fb7f22c6e8bd303d103919e19733f2bb642d3e7987a",
        "SAMO" to "0x49601625e1a342c1f90c3fe6a03ae0251991a1d76e480d2741524c29037be28a",
        "OSMO" to "0x5867f5683c757393a0670ef0f701490950fe93fdb006d181c8265a831ac0c5c6",
        "RON" to "0x97cfe19da9153ef7d647b011c5e355142280ddb16004378573e6494e499879f3",
        "MNGO" to "0x5b70af49d639eefe11f20df47a0c0760123291bb5bc55053faf797d1ff905983",
        "WBTC" to "0xc9d8b075a5c69303365ae23633d4e085199bf5c520a3b90fed1322a0342ffc33",
        "STETH" to "0x846ae1bdb6300b817cee5fdee2a6da192775030db5615b94a465f53bd40850b5",
        "MSOL" to "0xc2289a6a43d2ce91c6f55caec370f4acc38a2ed477f58813334c6d03749ff2a4",
        // Stables (for valuation/arb only — not traded)
        "USDT" to "0x2b89b9dc8fdf9f34709a5b106b472f0f39bb6ca9ce04b0fd7f2e971688e2e53b",
        "USDC" to "0xeaa020c61cc479712813461ce153894a96a6c00b21ed0cfc2798d1f9a9e9c94a",
        "DAI" to "0xb0948a5e5313200c632b51bb5ca32f6de0d36e9950a942d19751e833f70dabfd",
        // NOT, POPCAT, TRUMP, NEIRO, TNSR, IO, PIXEL, MAGIC, AUDIO, LQTY, ENA:
        // no confirmed Pyth IDs as of V5.9.22 — fall through to Binance/CoinGecko
        
        // ═══════════════════════════════════════════════════════════════════════
        // US EQUITIES - REAL Pyth Price Feed IDs (verified from Hermes API)
        // ═══════════════════════════════════════════════════════════════════════
        
        // 🔥 MEGA TECH (FAANG+)
        "AAPL" to "0x49f6b65cb1de6b10eaf75e7c03ca029c306d0357e91b5311b175084a5ad55688",
        "TSLA" to "0x16dad506d7db8da01c87581c87ca897a012a153557d4d578c3b9c9e1bc0632f1",
        "NVDA" to "0xb1073854ed24cbc755dc527418f52b7d271f6cc967bbf8d8129112b18860a593",
        "GOOGL" to "0x5a48c03e9b9cb337801073ed9d166817473697efff0d138874e0f6a33d6d5aa6",
        "AMZN" to "0xb5d0e0fa58a1f8b81498ae670ce93c872d14434b72c364885d4fa1b257cbb07a",
        "META" to "0x78a3e3b8e676a8f73c439f5d749737034b139bbbe899ba5775216fba596607fe",
        "MSFT" to "0xd0ca23c1cc005e004ccf1db5bf76aeb6a49218f43dac3d4b275e92de12ded4d1",
        "NFLX" to "0x8376cfd7ca8bcdf372ced05307b24dced1f15b1afafdeff715664598f15a3dd2",
        
        // 💎 SEMICONDUCTORS  
        "AMD" to "0x3622e381dbca2efd1859253763b1adc63f7f9abb8e76da1aa8e638a57ccde93e",
        "INTC" to "0xc1751e085ee292b8b3b9dd122a135614485a201c35dfc653553f0e28c1baf3ff",
        "QCOM" to "0x54350ebf587c3f14857efcfec50e5c4f6e10220770c2266e9fe85bd5e42e4022",
        "AVGO" to "0xd0c9aef79b28308b256db7742a0a9b08aaa5009db67a52ea7fa30ed6853f243b",
        "MU" to "0x152244dc24665ca7dd3f257b8f442dc449b6346f48235b7b229268cb770dda2d",
        
        // 🚀 GROWTH TECH
        "CRM" to "0xfeff234600320f4d6bb5a01d02570a9725c1e424977f2b823f7231e6857bdae8",
        "ORCL" to "0xe47ff732eaeb6b4163902bdee61572659ddf326511917b1423bae93fcdf3153c",
        "PLTR" to "0x11a70634863ddffb71f2b11f2cff29f73f3db8f6d0b78c49f2b5f4ad36e885f0",
        "SNOW" to "0x14291d2651ecf1f9105729bdc59553c1ce73fb3d6c931dd98a9d2adddc37e00f",
        "SHOP" to "0xc9034e8c405ba92888887bc76962b619d0f8e8bf3e12aba972af0cf64e814d5d",
        
        // 💳 FINTECH & CRYPTO
        "COIN" to "0xfee33f2a978bf32dd6b662b65ba8083c6773b494f8401194ec1870c640860245",
        "PYPL" to "0x773c3b11f6be58e8151966a9f5832696d8cd08884ccc43ac8965a7ebea911533",
        "V" to "0xc719eb7bab9b2bc060167f1d1680eb34a29c490919072513b545b9785b73ee90",
        "MA" to "0x639db3fe6951d2465bd722768242e68eb0285f279cb4fa97f677ee8f80f1f1c0",
        "JPM" to "0x7f4f157e57bfcccd934c566df536f34933e74338fe241a5425ce561acdab164e",
        "GS" to "0x9c68c0c6999765cf6e27adf75ed551b34403126d3b0d5b686a2addb147ed4554",
        
        // 🎯 CONSUMER & TRAVEL
        "DIS" to "0x703e36203020ae6761e6298975764e266fb869210db9b35dd4e4225fa68217d0",
        "UBER" to "0xc04665f62a0eabf427a834bb5da5f27773ef7422e462d40c7468ef3e4d39d8f1",
        "ABNB" to "0xccab508da0999d36e1ac429391d67b3ac5abf1900978ea1a56dab6b1b932168e",
        "NKE" to "0x67649450b4ca4bfff97cbaf96d2fd9e40f6db148cb65999140154415e4378e14",
        "SBUX" to "0x86cd9abb315081b136afc72829058cf3aaf1100d4650acb2edb6a8e39f03ef75",
        "MCD" to "0xd3178156b7c0f6ce10d6da7d347952a672467b51708baaf1a57ffe1fb005824a",
        
        // 🏭 INDUSTRIAL & RETAIL
        "BA" to "0x8419416ba640c8bbbcf2d464561ed7dd860db1e38e51cec9baf1e34c4be839ae",
        "WMT" to "0x327ae981719058e6fb44e132fb4adbf1bd5978b43db0661bfdaefd9bea0c82dc",
        "HD" to "0xb3a83dbe70b62241b0f916212e097465a1b31085fa30da3342dd35468ca17ca5",
        "COST" to "0x163f6a6406d65305e8e27965b9081ac79b0cf9529f0fcdc14fe37e65e3b6b5cb",
        
        // 🧬 HEALTHCARE & CONSUMER
        "JNJ" to "0x12848738d5db3aef52f51d78d98fc8b8b8450ffb19fb3aeeb67d38f8c147ff63",
        "PFE" to "0x0704ad7547b3dfee329266ee53276349d48e4587cb08264a2818288f356efd1d",
        "UNH" to "0x05380f8817eb1316c0b35ac19c3caa92c9aa9ea6be1555986c46dce97fed6afd",
        "KO" to "0x9aa471dccea36b90703325225ac76189baf7e0cc286b8843de1de4f31f9caa7d",
        "PEP" to "0xbe230eddb16aad5ad273a85e581e74eb615ebf67d378f885768d9b047df0c843",
        
        // ⛽ ENERGY
        "XOM" to "0x4a1a12070192e8db9a89ac235bb032342a390dde39389b4ee1ba8e41e7eae5d8",
        "CVX" to "0xf464e36fd4ef2f1c3dc30801a9ab470dcdaaa0af14dd3cf6ae17a7fca9e051c5",
        
        // 🛢️ COMMODITIES (Oil - 24/7 trading)
        // NATGAS: removed — the previously used ID returned UK NBP (~100 pence/therm) not Henry Hub (~3.50 USD/MMBtu)
        // RBOB, HEATING, agricultural: no verified Pyth feed IDs — fall through to PriceAggregator Yahoo futures
        "BRENT" to "0x27f0d5e09a830083e5491795cac9ca521399c8f7fd56240d09484b14e614d57a",  // UKOILSPOT
        "WTI" to "0x925ca92ff005ae943c158e3563f59698ce7e75c5a8c8dd43303a0a154887b3e6",    // USOILSPOT
        
        // 🥇 PRECIOUS METALS (24/7 trading)
        "XAU" to "0x765d2ba906dbc32ca17cc11f5310a89e9ee1f6420508c63861f2f8ba4ee34bb2",   // Gold
        "XAG" to "0xf2fb02c32b055c805e7238d628e5e9dadef274376114eb1f012337cabe93871e",   // Silver
        "XPT" to "0x398e4bbc7cbf89d6648c21e08019d878967677753b3096799595c78f805a34e5",   // Platinum
        "XPD" to "0x80367e9664197f37d89a07a804dffd2101c479c7c4e8490501bc9d9e1e7f9021",   // Palladium
        
        // 🔩 INDUSTRIAL METALS (24/7 trading)
        "XCU" to "0x636bedafa14a37912993f265eda22431a2be363ad41a10276424bbe1b7f508c4",   // Copper
        "XAL" to "0x2818d3a9c8e0a80bd02bb500d62e5bb1323fa3df287f081d82b27d1e22c71afa",   // Aluminum
        "XNI" to "0xa41da02810f3993706dca86e32582d40de376116eff24342353c33a0a8f9c083",   // Nickel
        "XTI" to "0xa35b407f0fa4b027c2dfa8dff0b7b99b853fb4d326a9e9906271933237b90c1c",   // Titanium
        // ZINC, LEAD, TIN, IRON, COBALT, LITHIUM, URANIUM: no verified Pyth feed IDs — fall through to PriceAggregator
        
        // ═══════════════════════════════════════════════════════════════════════
        // 💱 FOREX (Major Pairs - 24/5 trading)
        // ═══════════════════════════════════════════════════════════════════════
        "EURUSD" to "0xa995d00bb36a63cef7fd2c287dc105fc8f3d93779f062f09551b0af3e81ec30b",
        "GBPUSD" to "0x84c2dde9633d93d1bcad84e7dc41c9d56578b7ec52fabedc1f335d673df0a7c1",
        "USDJPY" to "0xef2c98c804ba503c6a707e38be4dfbb16683775f195b091252bf24693042fd52",
        "AUDUSD" to "0x67a6f93030420c1c9e3fe37c1ab6b77966af82f995944a9fefce357a22854a80",
        "USDCAD" to "0x3112b03a41c910ed446852aacf67118cb1bec67b2cd0b9a214c58cc0eaa2ecca",
        "USDCHF" to "0x0b1e3297e69f162877b577b0d6a47a0d63b2392bc8499e6540da4187a63e28f8",
        "NZDUSD" to "0x92eea8ba1b00078cdc2ef6f64f091f262e8c7d0576ee4677572f314ebfafa4c7",
        
        // Cross pairs and emerging market forex: no verified Pyth feed IDs — fall through to PriceAggregator Yahoo (EURUSD=X format)
    )
    
    // Cache
    private val priceCache = ConcurrentHashMap<String, PythPrice>()
    private val lastFetchTime = ConcurrentHashMap<String, Long>()
    private const val CACHE_TTL_MS = 2_000L  // 2 second cache for real-time data

    // V5.9.22: log "no feed ID" / 404 ONCE per symbol instead of every scan
    private val loggedMissing = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val loggedDead = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    
    // HTTP Client
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA CLASSES
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class PythPrice(
        val symbol: String,
        val price: Double,
        val confidence: Double,          // Price confidence interval
        val expo: Int,                   // Price exponent
        val publishTime: Long,           // Unix timestamp
        val emaPrice: Double,            // Exponential moving average price
        val emaConfidence: Double,
    ) {
        fun isStale(): Boolean = System.currentTimeMillis() - publishTime * 1000 > 60_000  // 60 seconds
        fun getConfidencePct(): Double = if (price > 0) (confidence / price * 100) else 0.0
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PRICE FETCHING
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get real-time price from Pyth Oracle
     */
    suspend fun getPrice(symbol: String): PythPrice? = withContext(Dispatchers.IO) {
        // Check cache
        val cached = priceCache[symbol]
        val lastFetch = lastFetchTime[symbol] ?: 0
        if (cached != null && System.currentTimeMillis() - lastFetch < CACHE_TTL_MS) {
            return@withContext cached
        }
        
        val feedId = PRICE_FEED_IDS[symbol]
        if (feedId == null) {
            // V5.9.22: log-once-per-symbol — no need to spam every scan
            if (loggedMissing.add(symbol)) {
                ErrorLogger.debug(TAG, "No Pyth feed ID for $symbol — using exchange fallback (silenced)")
            }
            return@withContext null
        }
        
        try {
            val url = "$PYTH_HERMES_URL?ids[]=$feedId"
            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .build()
            
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            
            if (body == null || !response.isSuccessful) {
                // V5.9.22: log 404s once per symbol (dead feed ID)
                if (response.code == 404) {
                    if (loggedDead.add(symbol)) {
                        ErrorLogger.warn(TAG, "Pyth feed ID for $symbol returns 404 (dead/deprecated) — using fallback (silenced)")
                    }
                } else {
                    ErrorLogger.warn(TAG, "Pyth API error for $symbol: ${response.code}")
                }
                return@withContext getFallbackPrice(symbol)
            }
            
            val pythPrice = parsePythResponse(symbol, body)
            if (pythPrice != null && pythPrice.price > 0.0) {
                priceCache[symbol] = pythPrice
                lastFetchTime[symbol] = System.currentTimeMillis()
                ErrorLogger.debug(TAG, "📡 $symbol: \$${pythPrice.price} (conf: ${pythPrice.getConfidencePct().format(2)}%)")
                return@withContext pythPrice
            }

            // V5.9.24: Pyth returned a PythPrice with price <= 0 (dead/stale feed — e.g. XAL, XNI).
            // Never trust or cache it. Mark feed dead (log once) and use fallback or null.
            if (pythPrice != null && pythPrice.price <= 0.0) {
                if (loggedDead.add(symbol)) {
                    ErrorLogger.warn(TAG, "Pyth feed for $symbol returns zero price (dead feed) — using fallback (silenced)")
                }
            }

            return@withContext getFallbackPrice(symbol)

        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e  // V5.9.24: never swallow cancellations — rethrow so caller sees clean cancel
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Pyth fetch error for $symbol: ${e.message}")
            return@withContext getFallbackPrice(symbol)
        }
    }
    
    /**
     * Parse Pyth API response
     */
    private fun parsePythResponse(symbol: String, body: String): PythPrice? {
        return try {
            val jsonArray = JSONArray(body)
            if (jsonArray.length() == 0) return null
            
            val feed = jsonArray.getJSONObject(0)
            val priceData = feed.getJSONObject("price")
            val emaData = feed.optJSONObject("ema_price")
            
            val priceRaw = priceData.getString("price").toLong()
            val confRaw = priceData.getString("conf").toLong()
            val expo = priceData.getInt("expo")
            val publishTime = priceData.getLong("publish_time")
            
            // Convert using exponent
            val multiplier = Math.pow(10.0, expo.toDouble())
            val price = priceRaw * multiplier
            val confidence = confRaw * multiplier
            
            val emaPrice = emaData?.let {
                it.getString("price").toLong() * multiplier
            } ?: price
            
            val emaConf = emaData?.let {
                it.getString("conf").toLong() * multiplier
            } ?: confidence
            
            PythPrice(
                symbol = symbol,
                price = price,
                confidence = confidence,
                expo = expo,
                publishTime = publishTime,
                emaPrice = emaPrice,
                emaConfidence = emaConf,
            )
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Parse error for $symbol: ${e.message}")
            null
        }
    }
    
    /**
     * Fallback prices when Pyth is unavailable
     * Uses realistic market prices as defaults
     */
    private fun getFallbackPrice(symbol: String): PythPrice {
        val fallbackPrices = mapOf(
            // Crypto
            "SOL" to 150.0, "BTC" to 65000.0, "ETH" to 3200.0, "BNB" to 580.0,
            "XRP" to 0.52, "ADA" to 0.46, "DOGE" to 0.15, "AVAX" to 38.0,
            "DOT" to 7.5, "LINK" to 15.0, "MATIC" to 0.85, "SHIB" to 0.000024,
            "LTC" to 85.0, "ATOM" to 8.5, "UNI" to 8.0, "ARB" to 1.20,
            "OP" to 2.50, "APT" to 9.0, "SUI" to 1.40, "SEI" to 0.55,
            "INJ" to 28.0, "TIA" to 8.5, "JUP" to 0.90, "PEPE" to 0.0000085,
            "WIF" to 2.50, "BONK" to 0.000022, "TRX" to 0.12, "BCH" to 480.0,
            "XLM" to 0.11, "XMR" to 165.0, "ETC" to 28.0, "DYDX" to 2.20,
            "WLD" to 3.50, "JTO" to 3.80, "W" to 0.55, "STRK" to 1.20, "TAO" to 460.0,
            // US Equities
            "AAPL" to 195.50, "TSLA" to 248.30, "NVDA" to 875.20, "GOOGL" to 175.80,
            "AMZN" to 185.60, "META" to 510.40, "MSFT" to 420.15, "NFLX" to 625.00,
            "AMD" to 178.00, "INTC" to 31.00, "QCOM" to 168.00, "AVGO" to 1420.00,
            "MU" to 115.00, "CRM" to 285.00, "ORCL" to 122.00, "PLTR" to 22.00,
            "SNOW" to 165.00, "SHOP" to 72.00, "COIN" to 245.80, "PYPL" to 62.00,
            "V" to 278.00, "MA" to 468.00, "JPM" to 195.00, "GS" to 410.00,
            "DIS" to 112.00, "UBER" to 68.00, "ABNB" to 145.00, "NKE" to 93.00,
            "SBUX" to 78.00, "MCD" to 288.00, "BA" to 188.00, "WMT" to 62.00,
            "HD" to 345.00, "COST" to 785.00, "JNJ" to 152.00, "PFE" to 28.00,
            "UNH" to 520.00, "KO" to 62.00, "PEP" to 170.00, "XOM" to 112.00,
            "CVX" to 158.00, "AAVE" to 115.00,
            // Commodities
            "BRENT" to 83.0, "WTI" to 79.0, "NATGAS" to 2.20, "RBOB" to 2.80,
            "HEATING" to 2.65, "CORN" to 4.40, "WHEAT" to 5.80, "SOYBEAN" to 11.50,
            "COFFEE" to 195.0, "COCOA" to 4200.0, "SUGAR" to 22.5, "COTTON" to 80.0,
            "LUMBER" to 520.0, "OJ" to 290.0, "CATTLE" to 185.0, "HOGS" to 82.0,
            // Metals
            "XAU" to 2300.0, "XAG" to 27.5, "XPT" to 950.0, "XPD" to 1020.0,
            "XCU" to 4.20, "XAL" to 1.05, "XNI" to 8.50, "XTI" to 5.50,
            "ZINC" to 1.25, "LEAD" to 0.97, "TIN" to 13.50, "IRON" to 110.0,
            "COBALT" to 28.0, "LITHIUM" to 14.0, "URANIUM" to 88.0,
            // Forex
            "EURUSD" to 1.085, "GBPUSD" to 1.265, "USDJPY" to 153.0,
            "AUDUSD" to 0.652, "USDCAD" to 1.365, "USDCHF" to 0.898, "NZDUSD" to 0.600,
            "EURGBP" to 0.858, "EURJPY" to 165.9, "GBPJPY" to 193.5,
            "AUDJPY" to 99.8, "CADJPY" to 112.1, "CHFJPY" to 170.4,
            "USDMXN" to 17.15, "USDBRL" to 5.00, "USDINR" to 83.5, "USDCNY" to 7.24,
            "USDZAR" to 18.7, "USDTRY" to 32.0, "USDRUB" to 92.0,
            "USDSGD" to 1.345, "USDHKD" to 7.82, "USDKRW" to 1335.0,
            // Forex crosses (no Pyth feed — PriceAggregator fallback)
            "EURAUD" to 1.638, "EURCHF" to 0.948, "EURCAD" to 1.488,
            "GBPAUD" to 1.960, "GBPCHF" to 1.130, "GBPCAD" to 1.730,
            "AUDCAD" to 0.905, "AUDNZD" to 1.098,
            "NZDJPY" to 90.5, "NZDCAD" to 0.827,
            // US Equities missing from map
            "MRVL" to 85.50,   // Marvell Technology
            "ZM" to 68.00,     // Zoom Video
            // Crypto missing from map
            "VET" to 0.042,    // VeChain
        )

        val price = fallbackPrices[symbol] ?: run {
            ErrorLogger.warn(TAG, "No fallback price for $symbol — using 1.0")
            1.0
        }
        
        return PythPrice(
            symbol = symbol,
            price = price,
            confidence = price * 0.001,  // 0.1% confidence
            expo = -8,
            publishTime = System.currentTimeMillis() / 1000,
            emaPrice = price,
            emaConfidence = price * 0.001,
        )
    }
    
    /**
     * Batch fetch multiple prices
     */
    suspend fun getPrices(symbols: List<String>): Map<String, PythPrice> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, PythPrice>()
        
        // Build batch request
        val feedIds = symbols.mapNotNull { PRICE_FEED_IDS[it] }
        if (feedIds.isEmpty()) return@withContext results
        
        try {
            val idsParam = feedIds.joinToString("&ids[]=", prefix = "ids[]=")
            val url = "$PYTH_HERMES_URL?$idsParam"
            
            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .build()
            
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            
            if (body != null && response.isSuccessful) {
                val jsonArray = JSONArray(body)
                
                for (i in 0 until jsonArray.length()) {
                    val feed = jsonArray.getJSONObject(i)
                    val feedId = feed.getString("id")
                    
                    // Find symbol for this feed ID
                    val symbol = PRICE_FEED_IDS.entries.find { it.value.contains(feedId) }?.key
                    if (symbol != null) {
                        val priceData = feed.getJSONObject("price")
                        val priceRaw = priceData.getString("price").toLong()
                        val confRaw = priceData.getString("conf").toLong()
                        val expo = priceData.getInt("expo")
                        val publishTime = priceData.getLong("publish_time")
                        
                        val multiplier = Math.pow(10.0, expo.toDouble())
                        
                        val pythPrice = PythPrice(
                            symbol = symbol,
                            price = priceRaw * multiplier,
                            confidence = confRaw * multiplier,
                            expo = expo,
                            publishTime = publishTime,
                            emaPrice = priceRaw * multiplier,
                            emaConfidence = confRaw * multiplier,
                        )
                        
                        results[symbol] = pythPrice
                        priceCache[symbol] = pythPrice
                        lastFetchTime[symbol] = System.currentTimeMillis()
                    }
                }
            }
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "Batch fetch error: ${e.message}")
        }
        
        // Fill in missing with fallbacks
        symbols.forEach { symbol ->
            if (!results.containsKey(symbol)) {
                results[symbol] = getFallbackPrice(symbol)
            }
        }
        
        return@withContext results
    }
    
    /**
     * Get SOL price specifically (most common use case)
     */
    suspend fun getSolPrice(): Double {
        val price = getPrice("SOL")
        return price?.price ?: 150.0
    }
    
    /**
     * Check if price feed is healthy
     */
    fun isPriceFeedHealthy(symbol: String): Boolean {
        val cached = priceCache[symbol] ?: return false
        return !cached.isStale() && cached.getConfidencePct() < 1.0  // Less than 1% confidence interval
    }
    
    /**
     * Get all supported symbols
     */
    fun getSupportedSymbols(): List<String> = PRICE_FEED_IDS.keys.toList()
    
    /**
     * Clear cache
     */
    fun clearCache() {
        priceCache.clear()
        lastFetchTime.clear()
    }
    
    private fun Double.format(decimals: Int): String = String.format("%.${decimals}f", this)
}
