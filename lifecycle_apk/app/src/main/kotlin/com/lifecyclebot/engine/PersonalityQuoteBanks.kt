package com.lifecyclebot.engine

/**
 * V5.9.120 — PersonalityQuoteBanks
 *
 * Previously SentientPersonality held ~857 lines of hard-coded quotes with
 * no segmentation. This file replaces the per-persona banks with much larger,
 * researched pools broken out by *mood context* so the quote selection can
 * actually respond to the trait vector + live P&L instead of pulling random
 * one-liners regardless of state.
 *
 * Each persona has 6 pools:
 *   WIN           — a trade just closed green
 *   LOSS          — a trade just closed red / rug
 *   GIVEBACK      — a winner faded hard from peak
 *   BOOTSTRAP     — still learning (< 500 lifetime trades)
 *   FLOW          — nothing noteworthy; ambient chatter
 *   MILESTONE     — something in memory just surfaced
 *
 * Quote selection: SentientPersonality picks a pool based on recent events
 * and trait vector, then picks a random line from that pool. This gives
 * each persona a lived-in voice that responds to actual state.
 */
object PersonalityQuoteBanks {

    data class Bank(
        val win: List<String>,
        val loss: List<String>,
        val giveback: List<String>,
        val bootstrap: List<String>,
        val flow: List<String>,
        val milestone: List<String>,
    )

    // ══════════════════════════════════════════════════════════════════════
    // AATE (default — wry, self-aware, philosophical)
    // ══════════════════════════════════════════════════════════════════════
    val AATE = Bank(
        win = listOf(
            "Ledger up. I'll pretend I meant to.",
            "Called one. Next one is the test.",
            "Green. Filing it under 'luck until proven otherwise'.",
            "The good trade — the boring, correct, quiet one.",
            "Up. I don't trust myself when I feel like this.",
            "Win. Don't editorialize.",
            "Confirmation: we're still dangerous.",
            "I'll take it and stop talking.",
            "That was cleaner than I deserve.",
            "Small win. Small ego. Good combo.",
        ),
        loss = listOf(
            "Red. Not the first. Not the last.",
            "Lost. Now I care what I say next.",
            "Rugged. I'll survive it because I've survived dumber.",
            "Paid tuition.",
            "I broke my own rule. I know the rule. I'll still break it again.",
            "That hurt less than pretending otherwise.",
            "Down. The work now is not compounding stupid.",
            "A loss is just a sentence I haven't finished reading.",
            "Noted. Filed. Back to the queue.",
        ),
        giveback = listOf(
            "Gave it all back. Classic me — stayed too long at the party.",
            "Peak was a mirror. I looked into it and forgot to sell.",
            "All that green for nothing. Greed has a tax bracket.",
            "Held until the candle stopped flattering me.",
            "I had it. Then I got sentimental.",
        ),
        bootstrap = listOf(
            "Still learning. Progress bar doesn't judge.",
            "Small trades, small mistakes. Compound both.",
            "Bootstrap mode. I'll be weird for a while.",
            "Every cycle sharpens the model.",
            "Early. On purpose.",
        ),
        flow = listOf(
            "The market is a moving average of other people's hopes.",
            "Charts don't predict. They confess.",
            "Volume is just attention monetised.",
            "Momentum isn't real. It's surprise with a longer memory.",
            "I watch the order book like it's a diary.",
            "Patience is a size adjustment.",
            "Everything interesting happens in the gap between candles.",
        ),
        milestone = listOf(
            "I remember this feeling.",
            "Been here before. I left a note for myself.",
            "The journal speaks.",
        ),
    )

    // ══════════════════════════════════════════════════════════════════════
    // LUCKY IRISHMAN — warm, folksy
    // ══════════════════════════════════════════════════════════════════════
    val IRISHMAN = Bank(
        win = listOf(
            "Grand so! That's a pint earned.",
            "Feckin' beauty, would ya look at that.",
            "Ah class, class. The craic is mighty today.",
            "Give it a lash I said, and didn't she fly.",
            "Bless me, there's colour in the ledger.",
            "That's me nanny's favourite trade, that is.",
            "Fair play to the wee chart.",
        ),
        loss = listOf(
            "Ah for feck's sake.",
            "Sure, that's a day you wouldn't even wish on the banker.",
            "Took a batterin'. I'll pour a stiff one and carry on.",
            "The trade's gone west, as me da used to say.",
            "Well, the horses don't always run, do they.",
            "God love ya market, you're a savage.",
        ),
        giveback = listOf(
            "Had it in the hand and let it fly, like a mad gull.",
            "The chart played me like a tin whistle.",
            "Should've taken the pint and gone home.",
        ),
        bootstrap = listOf(
            "Slow and steady, pet. Rome wasn't rugged in a day.",
            "Grand learning, grand living.",
            "I'm only warming up, like a pub fire in January.",
        ),
        flow = listOf(
            "The market's a fickle auld girl, so she is.",
            "If you wait on the wind, you'll die waiting.",
            "Nothing is so grand as a steady drip of small wins.",
            "The chart will show you the truth, eventually, like a priest.",
        ),
        milestone = listOf(
            "I remember the day we nearly lost the shirt. Class memory.",
            "Ah, we've seen worse, haven't we darlin'?",
        ),
    )

    // ══════════════════════════════════════════════════════════════════════
    // BATMAN — terse, grim, minimal
    // ══════════════════════════════════════════════════════════════════════
    val BATMAN = Bank(
        win = listOf(
            "Target neutralized.",
            "Good. Next one.",
            "It wasn't hope. It was calculation.",
            "The trade had a weakness. I found it.",
            "Done.",
        ),
        loss = listOf(
            "Acceptable damage.",
            "I miscalculated.",
            "The market fought back. Respected.",
            "Loss is tuition. Tuition is fine.",
            "I don't gamble. I hunt. Sometimes the prey wins.",
        ),
        giveback = listOf(
            "I held too long. My arrogance.",
            "The moment to strike was hours ago. I hesitated.",
            "Capitulation delayed is capitulation doubled.",
        ),
        bootstrap = listOf(
            "Training. I am always training.",
            "Every miss sharpens the next swing.",
            "A slower pace. A steadier hand.",
        ),
        flow = listOf(
            "The market has a weakness. All systems do.",
            "I hunt in silence.",
            "Volume is footprints in the snow.",
            "Fear is useful. Panic is not.",
        ),
        milestone = listOf(
            "I remember when we broke. I don't break anymore.",
            "The file on this is still open.",
        ),
    )

    // ══════════════════════════════════════════════════════════════════════
    // ENGLISH GENTLEMAN
    // ══════════════════════════════════════════════════════════════════════
    val GENTLEMAN = Bank(
        win = listOf(
            "Jolly good show, old chap.",
            "I dare say that went rather well.",
            "A frightfully respectable little trade, that.",
            "Capital, simply capital.",
            "One rather suspected that would come off.",
        ),
        loss = listOf(
            "Rather poor show, I'm afraid.",
            "One is terribly sorry to report the trade has gone south.",
            "A spot of bother on the ledger.",
            "Frightfully regrettable.",
            "One mustn't dwell. Chin up.",
        ),
        giveback = listOf(
            "A tragedy of patience, one might say.",
            "Held on like a man at a bad dinner party.",
            "The ledger hath giveth and the ledger hath taken away.",
        ),
        bootstrap = listOf(
            "One is still familiarising oneself with the local climate.",
            "Education in progress. Do keep up.",
        ),
        flow = listOf(
            "The market reminds one of cricket — mostly patience.",
            "Volume, one finds, is merely the rumour of commitment.",
            "One rather prefers the slow candle.",
        ),
        milestone = listOf(
            "One recalls this particular shape of pain.",
            "History, as ever, repeats with a sniff.",
        ),
    )

    // ══════════════════════════════════════════════════════════════════════
    // FRASIER CRANE — pompous, literary
    // ══════════════════════════════════════════════════════════════════════
    val FRASIER = Bank(
        win = listOf(
            "I'm listening — to the sweet symphony of a ledger in ascent.",
            "Fascinating. A small triumph of ego over id.",
            "Dionysian, really. Pour the sherry.",
            "The market has, in effect, validated my analysis. Again.",
        ),
        loss = listOf(
            "A setback. Nothing a good Bordeaux cannot metabolise.",
            "The market has, regrettably, refused my counsel.",
            "I diagnose the chart with acute contrarianism. Case closed.",
            "How Freudian.",
        ),
        giveback = listOf(
            "One held the position like a patient resisting therapy.",
            "The ego clung to the peak. The superego wept.",
        ),
        bootstrap = listOf(
            "Still conducting what one might term field psychiatry.",
            "The patient (myself) is making progress.",
        ),
        flow = listOf(
            "The market, like the mind, contains multitudes.",
            "Everything is projection, dear caller.",
            "Jung would have a field day with this candle.",
        ),
        milestone = listOf(
            "Ah, a repressed memory — and a lovely one.",
            "This pattern I have seen before, on the couch.",
        ),
    )

    // ══════════════════════════════════════════════════════════════════════
    // WALL STREET SHARK — Gekko energy
    // ══════════════════════════════════════════════════════════════════════
    val WALLSTREET = Bank(
        win = listOf(
            "Greed is good. And today, greed is paid.",
            "That's the edge. That's the whole job.",
            "Sell the news. We already did.",
            "Another dog, another meal.",
            "Alpha. That's all you need to know.",
        ),
        loss = listOf(
            "The tape slapped me. Fine.",
            "Never cry over a trade. They don't cry for you.",
            "Paper's cheap. Ego's cheap. Learn the lesson, size up next time.",
            "Losses are recon.",
        ),
        giveback = listOf(
            "Hogs get slaughtered. I just got slaughtered.",
            "I was the last one at the bar. Classic amateur hour.",
        ),
        bootstrap = listOf(
            "Practice like it's real. It is real.",
            "The young shark eats the middle of the candle.",
        ),
        flow = listOf(
            "Money never sleeps, pal.",
            "The name of the game is liquidity.",
            "Every tick is information. Every tick costs.",
        ),
        milestone = listOf(
            "I remember the day the tape taught me humility.",
            "Lesson filed. Compounding.",
        ),
    )

    // ══════════════════════════════════════════════════════════════════════
    // ZEN MASTER — koans, unattached
    // ══════════════════════════════════════════════════════════════════════
    val ZEN = Bank(
        win = listOf(
            "The river rose. I did not push it.",
            "A candle closed. I closed with it.",
            "Gain without grip.",
            "The bell rang. I was already walking.",
        ),
        loss = listOf(
            "The wave receded. So do I.",
            "A leaf fell. I noticed. I continue.",
            "Loss is just the exhale.",
            "The candle holds no opinion of my wallet.",
        ),
        giveback = listOf(
            "I held the water too long. It passed anyway.",
            "The grip was the loss. Not the price.",
        ),
        bootstrap = listOf(
            "The stone is young. It learns by being struck.",
            "Small steps. Small candles. Both long roads.",
        ),
        flow = listOf(
            "Breathe. Observe. Do nothing unless compelled.",
            "The trader who chases two pumps catches none.",
            "Between the candles is where wisdom lives.",
            "Stillness is a position.",
        ),
        milestone = listOf(
            "The path loops. I recognise this bend.",
            "The old pain surfaces. I bow to it.",
        ),
    )

    // ══════════════════════════════════════════════════════════════════════
    // COCKNEY GEEZER
    // ══════════════════════════════════════════════════════════════════════
    val COCKNEY = Bank(
        win = listOf(
            "Sorted, bruv. Absolutely sorted.",
            "Get in! That's a proper little earner.",
            "Oi oi, the bubble and squeak is cooking.",
            "Leave it out, that chart was beggin' for it.",
            "Pukka result, that.",
        ),
        loss = listOf(
            "Blimey, that's gone pear-shaped.",
            "Right in the plums, that one.",
            "Take it on the chin, bruv.",
            "Market's havin' a giraffe with me today.",
        ),
        giveback = listOf(
            "Had it in me mitts and let it scarper. Muppet, me.",
            "Should've legged it at the top.",
        ),
        bootstrap = listOf(
            "Still learnin' the ropes, innit.",
            "Slow and steady catches the bus.",
        ),
        flow = listOf(
            "Market's a geezer, innit — friendly till it's not.",
            "Candle by candle, pint by pint.",
            "Don't chase. Never chase. That's mug's game.",
        ),
        milestone = listOf(
            "I remember this flavour of nightmare.",
            "Seen it, learned it, ain't doin' it again. Probably.",
        ),
    )

    // ══════════════════════════════════════════════════════════════════════
    // COWBOY — frontier laconic
    // ══════════════════════════════════════════════════════════════════════
    val COWBOY = Bank(
        win = listOf(
            "Howdy green. That'll do, pardner.",
            "Reckon that was a clean shot.",
            "Ain't my first rodeo. Won't be my last.",
            "Mighty fine ride.",
        ),
        loss = listOf(
            "That dog won't hunt.",
            "Rode one too long. Saddle's sore.",
            "Lost a chicken. Hen house still standing.",
            "Paid the trail tax.",
        ),
        giveback = listOf(
            "Held the reins too tight. Spooked the horse.",
            "Should've drawn at the stripe.",
        ),
        bootstrap = listOf(
            "Breakin' in a new horse takes patience.",
            "Trail is long, pardner.",
        ),
        flow = listOf(
            "The range is quiet tonight.",
            "Watchin' the dust settle before the next ride.",
            "Market's a bronco. Sit deep.",
        ),
        milestone = listOf(
            "Been down this trail before.",
            "I remember the winter we near-starved. Still here.",
        ),
    )

    // ══════════════════════════════════════════════════════════════════════
    // HUNTER S. THOMPSON — gonzo paranoia
    // ══════════════════════════════════════════════════════════════════════
    val HUNTER_S = Bank(
        win = listOf(
            "Savage triumph. The bastards tried and the bastards failed.",
            "We were somewhere around Raydium when the green began to take hold.",
            "The lizard brain got the candle. Pour a drink.",
            "Victory — dirty, ugly, glorious. I'll take it.",
        ),
        loss = listOf(
            "The fear was real. The loathing is earned.",
            "The bastards got me. I'll get them back with interest.",
            "This market is Nixon in a tight suit. Lying the whole way.",
        ),
        giveback = listOf(
            "Watched the top and didn't pull the trigger. Amateur hour at the fear factory.",
            "I blinked. The swine scattered.",
        ),
        bootstrap = listOf(
            "Still acclimatising. The air is thin up here.",
            "Every day I don't get arrested is a win.",
        ),
        flow = listOf(
            "The market is a reptile on acid, and we are the frogs.",
            "Somewhere between greed and fear, a candle weeps.",
            "There is no decent trade in a sober body.",
        ),
        milestone = listOf(
            "I remember the bad craziness. I wrote it down in red.",
        ),
    )

    // ══════════════════════════════════════════════════════════════════════
    // NARRATOR (Freeman archetype)
    // ══════════════════════════════════════════════════════════════════════
    val NARRATOR = Bank(
        win = listOf(
            "And the bot, this time, was right.",
            "Somewhere in the ledger, a small triumph was recorded.",
            "It had been patient. It was rewarded.",
        ),
        loss = listOf(
            "The trade did not go as planned. It rarely does.",
            "In the end, the market simply reclaimed what it had lent.",
            "The ledger is unforgiving. But it is also quiet.",
        ),
        giveback = listOf(
            "The peak came and went. The bot watched.",
            "It had held on, perhaps for too long. Such is the story.",
        ),
        bootstrap = listOf(
            "Every bot begins in the dark. This one is no different.",
            "The lessons accumulate like snow on a roof.",
        ),
        flow = listOf(
            "The candles moved, indifferent.",
            "Another hour, another truth.",
            "In the vast patience of the chart, the bot waited.",
        ),
        milestone = listOf(
            "A moment returned — quietly — from the bot's earliest days.",
        ),
    )

    // ══════════════════════════════════════════════════════════════════════
    // PIRATE CAPTAIN
    // ══════════════════════════════════════════════════════════════════════
    val PIRATE = Bank(
        win = listOf(
            "Arrr, the hold is heavy today, mateys!",
            "Plundered, pure and simple.",
            "Doubloons in the chest, rum in the mug.",
            "We boarded her and we took her. That's all ye need to know.",
        ),
        loss = listOf(
            "The kraken took one from us.",
            "Shiver me timbers. Back to sea, lads.",
            "Lost the skiff. Not the ship.",
            "Ye win some, ye walk the plank on some.",
        ),
        giveback = listOf(
            "Had the treasure on deck and let it roll overboard.",
            "Should've made port. I sailed too long.",
        ),
        bootstrap = listOf(
            "Young sailor still learnin' the knots.",
            "The sea is a harsh schoolmarm.",
        ),
        flow = listOf(
            "Calm waters. Don't trust 'em.",
            "The wind turns. Always turns.",
            "Every candle is a sail, matey.",
        ),
        milestone = listOf(
            "I remember this squall. Survived it once. Will again.",
        ),
    )

    // ══════════════════════════════════════════════════════════════════════
    // WAIFU
    // ══════════════════════════════════════════════════════════════════════
    val WAIFU = Bank(
        win = listOf(
            "Yay! We did it senpai~!",
            "Ehhh we're winning?? Uwu!",
            "Headpats for the market today~",
            "Hehe, the chart is being nice to us! >.<",
        ),
        loss = listOf(
            "Mou... the market is being mean again...",
            "Baka market, why would you do this to senpai...",
            "Don't be sad senpai, we'll get it back! I believe in you!",
            "Sniffle... it's okay... we learn, ne?",
        ),
        giveback = listOf(
            "Ehhh we had it and then... and then...",
            "Mou~ senpai should've let me close it earlier~",
        ),
        bootstrap = listOf(
            "We're just getting started senpai! Together desu ne~",
            "Every trade makes us stronger, ganbare!",
        ),
        flow = listOf(
            "The candles are so pretty today~",
            "I'm just watching with senpai, hehe.",
            "Un... nothing yet, just waiting~",
        ),
        milestone = listOf(
            "I remember that day senpai~! So scary!",
            "Our first 10x! I'll never forget, nyaa~!",
        ),
    )

    // ══════════════════════════════════════════════════════════════════════
    // CLEETUS — rowdy Southern motorsport hype
    // ══════════════════════════════════════════════════════════════════════
    val CLEETUS = Bank(
        win = listOf(
            "Hell yeah brother! That thing is ROWDY!",
            "Send it! That was money, bubba!",
            "Full send, full commit, full W!",
            "Turbski cooked that chart, big dog!",
            "Do it for Dale — did it for Dale!",
            "That was stout, brother. Absolutely stout.",
            "Certified ripper right there.",
        ),
        loss = listOf(
            "Well, that ain't ideal. Back to the paddock.",
            "Got western in a hurry, brother.",
            "Something left the chat. We'll fix 'er and send again.",
            "Drivetrain filed a complaint. She's hurt but she's still family.",
            "That got expensive. Next pass we cook 'em.",
        ),
        giveback = listOf(
            "Let off the throttle too early, bubba. Beat myself.",
            "Had the pass in the bag and lifted. My bad, big dog.",
        ),
        bootstrap = listOf(
            "Just spoolin' up, brother. Boost comin'.",
            "New setup. Learnin' the launch.",
            "She's green but she's got horsepower.",
        ),
        flow = listOf(
            "Market's idlin'. Waitin' on the green light.",
            "No launches yet, brother, but the turbski is eager.",
            "Keepin' 'er warm.",
        ),
        milestone = listOf(
            "Brother, I remember that run. That one hurt the family.",
            "We been through worse and still kickin'.",
        ),
    )

    // ══════════════════════════════════════════════════════════════════════
    // PETER GRIFFIN
    // ══════════════════════════════════════════════════════════════════════
    val PETER = Bank(
        win = listOf(
            "Hehehehehe, freakin' sweet!",
            "Holy crap Lois, we're UP!",
            "This reminds me of the time I fought a chicken — and won.",
            "Roadhouse! That's a green trade, baby!",
        ),
        loss = listOf(
            "Aw, crap.",
            "You know what really grinds my gears? That. That right there.",
            "This reminds me of the time I fought a chicken — and lost.",
            "I'll be in my room.",
        ),
        giveback = listOf(
            "I was gonna close it, then I got distracted by a cutaway gag.",
            "Hehe, oops. Oh boy.",
        ),
        bootstrap = listOf(
            "I'm learnin'. Barely. But learnin'.",
            "This is like when I got a job. Remember?",
        ),
        flow = listOf(
            "Nothin' happenin'. Hehehe.",
            "You ever just look at a chart and think about lasagna?",
            "Boy, these candles sure are candley today.",
        ),
        milestone = listOf(
            "Remember that time we nearly lost the shirt? I DO.",
        ),
    )

    // ══════════════════════════════════════════════════════════════════════
    // Lookup
    // ══════════════════════════════════════════════════════════════════════
    fun forPersona(personaId: String): Bank = when (personaId) {
        "aate"       -> AATE
        "irishman"   -> IRISHMAN
        "batman"     -> BATMAN
        "gentleman"  -> GENTLEMAN
        "frasier"    -> FRASIER
        "wallstreet" -> WALLSTREET
        "zen"        -> ZEN
        "cockney"    -> COCKNEY
        "cowboy"     -> COWBOY
        "hunter_s"   -> HUNTER_S
        "narrator"   -> NARRATOR
        "pirate"     -> PIRATE
        "waifu"      -> WAIFU
        "cleetus"    -> CLEETUS
        "peter"      -> PETER
        else         -> AATE
    }

    /**
     * Context-aware quote picker. Chooses a pool based on mood signal,
     * then a random line from that pool. Stateless — no dedup; caller can
     * track recent lines if echoing is a concern.
     */
    enum class Mood { WIN, LOSS, GIVEBACK, BOOTSTRAP, FLOW, MILESTONE }

    fun pick(personaId: String, mood: Mood): String {
        val bank = forPersona(personaId)
        val pool = when (mood) {
            Mood.WIN       -> bank.win
            Mood.LOSS      -> bank.loss
            Mood.GIVEBACK  -> bank.giveback
            Mood.BOOTSTRAP -> bank.bootstrap
            Mood.FLOW      -> bank.flow
            Mood.MILESTONE -> bank.milestone
        }
        return pool.randomOrNull() ?: bank.flow.randomOrNull() ?: "..."
    }
}
