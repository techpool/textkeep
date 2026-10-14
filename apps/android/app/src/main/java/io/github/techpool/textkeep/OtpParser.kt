package io.github.techpool.textkeep

object OtpParser {
 const val VERSION = 1
 private val context = Regex("(?i)\\b(?:otp|verification\\s+code|security\\s+code|one[ -]time\\s+(?:password|code)|passcode)\\b|सत्यापन\\s*कोड|ओटीपी|একবারের\\s*পাসওয়ার্ড|যাচাইকরণ\\s*কোড|ওটিপি")
 private val candidates = Regex("(?<![\\p{L}\\p{N}])(?:[0-9](?:[ -]?[0-9]){3,7}|(?=[A-Z0-9]{4,8}(?![\\p{L}\\p{N}]))(?=[A-Z0-9]*[0-9])(?=[A-Z0-9]*[A-Z])[A-Z0-9]{4,8})(?![\\p{L}\\p{N}])")
 fun parse(body: String): String? {
  val anchors=context.findAll(body).toList();if(anchors.isEmpty()) return null
  val ranked=candidates.findAll(body).mapNotNull { m ->
   val code=m.value.replace(" ", "").replace("-", "")
   val before=body.substring(maxOf(0,m.range.first-20),m.range.first)
   if(Regex("(?i)(?:rs\\.?|inr|₹|amount|account|a/c|order|phone|ending|date)\\s*[:=]?\\s*$").containsMatchIn(before)) return@mapNotNull null
   val distance=anchors.minOf { a -> maxOf(0, maxOf(a.range.first-m.range.last,m.range.first-a.range.last)) }
   if(distance>45 || Regex("[0-9]{2}-[0-9]{2}-[0-9]{4}").matches(m.value)) null else code to distance
  }.sortedBy { it.second }.toList()
  val best=ranked.firstOrNull()?:return null
  if(ranked.drop(1).any { it.first!=best.first && it.second<=best.second+12 })return null
  return best.first
 }
}
