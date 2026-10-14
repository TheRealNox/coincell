package com.therealnox.gbflash.editor

import com.therealnox.gbflash.pkcore.PlayTime
import com.therealnox.gbflash.pkcore.text.LanguageId

/** One badge: the bit of [com.therealnox.gbflash.pkcore.Trainer.badges] and its name. */
data class BadgeInfo(val bit: Int, val name: String, val region: String)

/**
 * Badge bits as `:pkcore` documents them: Generation 1 has the 8 Kanto badges in the low byte; Generation 2 has the
 * Johto badges in the low byte and the Kanto badges in the high byte. Names in French when the save is French
 * (game names are facts, not artwork), English otherwise.
 */
object Badges {
    private val kantoEn = listOf("Boulder", "Cascade", "Thunder", "Rainbow", "Soul", "Marsh", "Volcano", "Earth")
    private val kantoFr = listOf("Roche", "Cascade", "Foudre", "Arc-en-ciel", "Âme", "Marais", "Volcan", "Terre")
    private val johtoEn = listOf("Zephyr", "Hive", "Plain", "Fog", "Storm", "Mineral", "Glacier", "Rising")
    private val johtoFr = listOf("Zéphyr", "Essaim", "Plaine", "Brume", "Tempête", "Minéral", "Glacier", "Lever")

    fun list(generation: Int, language: Int): List<BadgeInfo> {
        val fr = language == LanguageId.FRENCH
        val kanto = if (fr) kantoFr else kantoEn
        val johto = if (fr) johtoFr else johtoEn
        return if (generation == 1) {
            kanto.mapIndexed { i, n -> BadgeInfo(i, n, "Kanto") }
        } else {
            johto.mapIndexed { i, n -> BadgeInfo(i, n, "Johto") } + kanto.mapIndexed { i, n -> BadgeInfo(8 + i, n, "Kanto") }
        }
    }
}

/** Limits of the trainer fields (the games' own: 6 digit money, 4 digit coins, 7 / 5 character names). */
object TrainerLimits {
    const val MAX_MONEY = 999_999
    const val MAX_COINS = 9_999

    fun maxHours(generation: Int): Int = if (generation == 1) 255 else 999

    fun nameLength(japanese: Boolean): Int = if (japanese) 5 else 7

    fun formatTime(t: PlayTime): String = "%d:%02d:%02d".format(t.hours, t.minutes, t.seconds)
}

/** Hidden Power types (index 0 = Fighting ... 15 = Dark), English and French. */
object TypeNames {
    private val en = listOf("Fighting", "Flying", "Poison", "Ground", "Rock", "Bug", "Ghost", "Steel", "Fire", "Water", "Grass", "Electric", "Psychic", "Ice", "Dragon", "Dark")
    private val fr = listOf("Combat", "Vol", "Poison", "Sol", "Roche", "Insecte", "Spectre", "Acier", "Feu", "Eau", "Plante", "Électrik", "Psy", "Glace", "Dragon", "Ténèbres")

    fun hiddenPower(index: Int, language: Int): String {
        val list = if (language == LanguageId.FRENCH) fr else en
        return list.getOrElse(index) { "?" }
    }
}

/** Short stat labels in the save's language (French or English). */
object StatLabels {
    fun hp(language: Int) = if (language == LanguageId.FRENCH) "PV" else "HP"
    fun attack(language: Int) = if (language == LanguageId.FRENCH) "Attaque" else "Attack"
    fun defense(language: Int) = if (language == LanguageId.FRENCH) "Défense" else "Defense"
    fun speed(language: Int) = if (language == LanguageId.FRENCH) "Vitesse" else "Speed"
    fun special(language: Int) = if (language == LanguageId.FRENCH) "Spécial" else "Special"
    fun spAtk(language: Int) = if (language == LanguageId.FRENCH) "Att. Spé." else "Sp. Atk"
    fun spDef(language: Int) = if (language == LanguageId.FRENCH) "Déf. Spé." else "Sp. Def"
}
