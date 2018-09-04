package matt.bot.discord.laplace

import java.io.File

val elements = File("PeriodicTable.csv").readLines().map {it.split(",")}.associateBy({it[0].toLowerCase()}) {it[1]}

fun isElementSymbol(text: String) = text.toLowerCase() in elements
fun getElementNameBySymbol(text: String) = elements[text.toLowerCase()]