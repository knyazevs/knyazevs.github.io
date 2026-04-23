package dev.knyazev.rag

data class Skill(
    val name: String,
    val description: String,
    val triggers: List<String>,
    val sources: List<String>,
    val instruction: String,
)
