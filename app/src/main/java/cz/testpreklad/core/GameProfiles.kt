package cz.testpreklad.core

/** Profil hry: jména postav a míst, která se nemají překládat. */
data class GameProfile(val id: String, val title: String, val names: List<String>)

object GameProfiles {

    private val RDR2 = OfflinePrep.NAMES

    private val GTA = listOf(
        "Los Santos", "San Andreas", "Vice City", "Liberty City", "Blaine County", "Sandy Shores", "Paleto Bay",
        "Vinewood", "Leonida", "Grove Street", "Michael", "De Santa", "Franklin", "Clinton", "Trevor", "Philips",
        "Lamar", "Lester", "Amanda", "Tracey", "Jimmy", "Chop", "Wade", "Simeon", "Devin Weston", "Merryweather",
        "Ballas", "Vagos", "Niko", "Bellic", "Roman", "Tommy Vercetti", "Carl Johnson", "Big Smoke", "Jason", "Lucia",
    )
    private val TLOU = listOf(
        "Joel", "Ellie", "Tommy", "Tess", "Marlene", "Sarah", "Henry", "Sam", "Abby", "Lev", "Yara", "Dina", "Jesse",
        "Owen", "Mel", "Manny", "Isaac", "Jackson", "Seattle", "Boston", "Pittsburgh", "Fireflies", "Seraphites",
        "Wolves", "FEDRA", "WLF", "Clickers", "Cordyceps",
    )
    private val GOW = listOf(
        "Kratos", "Atreus", "Freya", "Mimir", "Baldur", "Thor", "Odin", "Brok", "Sindri", "Tyr", "Faye", "Laufey",
        "Loki", "Angrboda", "Thrud", "Heimdall", "Fenrir", "Jormungandr", "Midgard", "Asgard", "Alfheim", "Helheim",
        "Jotunheim", "Vanaheim", "Svartalfheim", "Niflheim", "Muspelheim", "Ragnarok", "Leviathan", "Mjolnir",
    )
    private val SPIDERMAN = listOf(
        "Peter", "Parker", "Miles", "Morales", "Mary Jane", "MJ", "Aunt May", "Harry", "Osborn", "Norman", "Otto",
        "Octavius", "Kraven", "Venom", "Martin Li", "Yuri", "Ganke", "Rio", "Oscorp", "Harlem", "Queens", "Manhattan",
        "Spider-Man", "Kingpin", "Wilson Fisk", "Rhino", "Scorpion", "Electro", "Vulture",
    )
    private val HORIZON = listOf(
        "Aloy", "Rost", "Varl", "Erend", "Sylens", "Elisabet", "Sobeck", "Ted Faro", "Zo", "Kotallo", "Alva", "Beta",
        "Tilda", "Nora", "Carja", "Oseram", "Banuk", "Tenakth", "Utaru", "Quen", "Meridian", "GAIA", "HADES",
        "HEPHAESTUS", "MINERVA", "Zenith", "Thunderjaw", "Watcher",
    )
    private val TSUSHIMA = listOf(
        "Jin", "Sakai", "Shimura", "Yuna", "Taka", "Ishikawa", "Masako", "Norio", "Kenji", "Ryuzo", "Khotun Khan",
        "Tomoe", "Yuriko", "Tsushima", "Izuhara", "Toyotama", "Kamiagata", "Komoda", "Atsu", "Ezo",
    )
    private val CYBERPUNK = listOf(
        "Johnny", "Silverhand", "Jackie", "Welles", "Judy", "Alvarez", "Panam", "Palmer", "Takemura", "Hanako",
        "Yorinobu", "Saburo", "Arasaka", "Militech", "Dexter DeShawn", "Evelyn", "Rogue", "Kerry", "Misty", "Viktor",
        "Night City", "Watson", "Westbrook", "Pacifica", "Badlands", "Afterlife", "Songbird", "Reed", "Dogtown",
    )
    private val WITCHER = listOf(
        "Geralt", "Yennefer", "Triss", "Ciri", "Cirilla", "Vesemir", "Dandelion", "Jaskier", "Zoltan", "Eskel",
        "Lambert", "Emhyr", "Radovid", "Dijkstra", "Keira", "Roach", "Novigrad", "Velen", "Skellige", "Oxenfurt",
        "Kaer Morhen", "Vizima", "Toussaint", "Nilfgaard", "Redania", "Temeria", "Rivia", "Crach", "Eredin",
    )
    private val UNCHARTED = listOf(
        "Nathan", "Nate", "Drake", "Sully", "Sullivan", "Elena", "Chloe", "Nadine", "Rafe", "Avery", "Libertalia",
        "Shambhala", "Lazarevic", "Cutter",
    )
    private val HOGWARTS = listOf(
        "Hogwarts", "Hogsmeade", "Gryffindor", "Slytherin", "Ravenclaw", "Hufflepuff", "Sebastian", "Sallow",
        "Ominis", "Gaunt", "Poppy", "Natsai", "Ranrok", "Rookwood", "Fig", "Weasley", "Deek", "Azkaban",
    )
    private val ELDEN = listOf(
        "Ranni", "Melina", "Radahn", "Malenia", "Godrick", "Rennala", "Morgott", "Margit", "Radagon", "Marika",
        "Godfrey", "Miquella", "Blaidd", "Limgrave", "Liurnia", "Caelid", "Leyndell", "Erdtree", "Torrent", "Tarnished",
    )

    /** Slova, která jsou zároveň běžná anglická slova – do univerzálního profilu nepatří. */
    private val AMBIGUOUS = setOf(
        "Gray", "Grays", "Bell", "Bill", "Smith", "Jack", "John", "Uncle", "Sam", "Wolves", "Beta", "Rio", "Reed",
        "Rogue", "Fig", "Watcher", "Chop", "Roach", "Zenith", "Torrent", "Tarnished", "Jackson", "Summers", "Wade",
        "Drake", "Queens", "Watson", "Roman", "Jason", "Nora", "Zo", "Taka", "Jin", "Tess", "Mel", "Lev", "Faye",
        "Peter", "Harry", "Miles", "Michael", "Henry", "Sarah", "Jesse", "Owen", "Isaac", "Tommy", "Jimmy", "Amanda",
        "Karen", "Simon", "Susan", "Charles", "Sean", "Molly", "Elena", "Nathan", "Kerry", "Misty", "Evelyn", "Judy",
        "Johnny", "Jackie", "Sebastian", "Poppy", "Otto", "Norman", "Yuri", "Franklin", "Clinton", "Philips",
        "Morgan", "Milton", "Ross", "Leviathan", "Afterlife", "Badlands", "Cutter", "Avery", "Scorpion", "Rhino",
        "Vulture", "Electro", "Kingpin", "Venom", "Boston", "Seattle", "Pittsburgh", "Manhattan", "Harlem",
    )

    private val GAMES: List<GameProfile> = listOf(
        GameProfile("rdr2", "Red Dead Redemption 2", RDR2),
        GameProfile("gta", "Grand Theft Auto", GTA),
        GameProfile("tlou", "The Last of Us", TLOU),
        GameProfile("gow", "God of War", GOW),
        GameProfile("spiderman", "Spider-Man", SPIDERMAN),
        GameProfile("horizon", "Horizon", HORIZON),
        GameProfile("tsushima", "Ghost of Tsushima / Yōtei", TSUSHIMA),
        GameProfile("cyberpunk", "Cyberpunk 2077", CYBERPUNK),
        GameProfile("witcher", "The Witcher 3", WITCHER),
        GameProfile("uncharted", "Uncharted", UNCHARTED),
        GameProfile("hogwarts", "Hogwarts Legacy", HOGWARTS),
        GameProfile("elden", "Elden Ring", ELDEN),
    )

    /** Univerzální profil: nezaměnitelná jména ze všech her dohromady (bez běžných slov). */
    val UNIVERSAL = GameProfile("all", "*", GAMES.flatMap { it.names }.filter { it !in AMBIGUOUS }.distinct())

    val ALL: List<GameProfile> = listOf(UNIVERSAL) + GAMES

    fun of(id: String?): GameProfile = ALL.firstOrNull { it.id == id } ?: UNIVERSAL
    fun next(id: String?): GameProfile = ALL[(ALL.indexOf(of(id)) + 1) % ALL.size]
}

/** Předpřipravené překlady krátkých herních hlášek, které offline překladač často kazí. */
object BuiltinPhrases {
    private val CS = mapOf(
        "Get on your horse." to "Nasedni na koně.",
        "Get on the horse." to "Nasedni na koně.",
        "Mount up!" to "Na koně!",
        "Mount up." to "Na koně.",
        "Let's go!" to "Jdeme!",
        "Let's go." to "Jdeme.",
        "Let's ride!" to "Jedeme!",
        "Let's ride." to "Jedeme.",
        "Come on!" to "No tak!",
        "Come on." to "No tak.",
        "Hold on!" to "Vydrž!",
        "Hold on." to "Vydrž.",
        "Get down!" to "K zemi!",
        "Take cover!" to "Kryjte se!",
        "Watch out!" to "Pozor!",
        "Look out!" to "Pozor!",
        "Follow me." to "Pojď za mnou.",
        "Follow me!" to "Za mnou!",
        "Stay here." to "Zůstaň tady.",
        "Stay close." to "Drž se u mě.",
        "Hands up!" to "Ruce vzhůru!",
        "Drop your weapon!" to "Zahoď zbraň!",
        "Hold your fire!" to "Nestřílejte!",
        "Open fire!" to "Palte!",
        "Fall back!" to "Ústup!",
        "Move out!" to "Vyrážíme!",
        "Cover me!" to "Kryj mě!",
        "I'm out of ammo!" to "Došly mi náboje!",
        "Never mind." to "To je jedno.",
        "Fair enough." to "Dobrá.",
        "You're welcome." to "Není zač.",
        "What the hell?" to "Co to sakra je?",
        "Son of a bitch!" to "Ty hajzle!",
        "Goddammit!" to "Sakra!",
        "Damn it!" to "Sakra!",
        "Howdy." to "Zdravím.",
        "Howdy, partner." to "Zdravím, parťáku.",
        "Much obliged." to "Mockrát děkuju.",
        "Sure thing." to "Jasně.",
        "You bet." to "To si piš.",
    )
    private val SK = mapOf(
        "Get on your horse." to "Nasadni na koňa.",
        "Get on the horse." to "Nasadni na koňa.",
        "Mount up!" to "Na kone!",
        "Mount up." to "Na kone.",
        "Let's go!" to "Ideme!",
        "Let's go." to "Ideme.",
        "Let's ride!" to "Ideme!",
        "Let's ride." to "Ideme.",
        "Come on!" to "No tak!",
        "Come on." to "No tak.",
        "Hold on!" to "Vydrž!",
        "Hold on." to "Vydrž.",
        "Get down!" to "K zemi!",
        "Take cover!" to "Kryte sa!",
        "Watch out!" to "Pozor!",
        "Look out!" to "Pozor!",
        "Follow me." to "Poď za mnou.",
        "Follow me!" to "Za mnou!",
        "Stay here." to "Zostaň tu.",
        "Stay close." to "Drž sa pri mne.",
        "Hands up!" to "Ruky hore!",
        "Drop your weapon!" to "Zahoď zbraň!",
        "Hold your fire!" to "Nestrieľajte!",
        "Open fire!" to "Páľte!",
        "Fall back!" to "Ústup!",
        "Move out!" to "Vyrážame!",
        "Cover me!" to "Kry ma!",
        "I'm out of ammo!" to "Došli mi náboje!",
        "Never mind." to "To je jedno.",
        "Fair enough." to "Dobre.",
        "You're welcome." to "Nie je za čo.",
        "What the hell?" to "Čo to dopekla je?",
        "Son of a bitch!" to "Ty sviňa!",
        "Goddammit!" to "Dopekla!",
        "Damn it!" to "Dopekla!",
        "Howdy." to "Zdravím.",
        "Howdy, partner." to "Zdravím, parťák.",
        "Much obliged." to "Veľmi pekne ďakujem.",
        "Sure thing." to "Jasné.",
        "You bet." to "To si píš.",
    )

    fun forLang(lang: Lang): Map<String, String> = when (lang) {
        Lang.CS -> CS
        Lang.SK -> SK
        else -> emptyMap()
    }
}
