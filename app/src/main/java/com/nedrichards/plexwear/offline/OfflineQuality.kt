package com.nedrichards.plexwear.offline

enum class OfflineQuality(
  val bitrateKbps: Int,
  val label: String,
) {
  DataSaver(96, "Data saver"),
  Balanced(192, "Balanced"),
  High(320, "High");

  val summary: String = "$label (${bitrateKbps}k)"

  fun next(): OfflineQuality = entries[(ordinal + 1) % entries.size]

  companion object {
    val Default: OfflineQuality = Balanced

    fun fromBitrateKbps(value: Int): OfflineQuality =
      entries.firstOrNull { it.bitrateKbps == value } ?: Default
  }
}
