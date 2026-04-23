package dev.knyazev.config

import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.yaml.YamlConfigLoader

data class AppConfig(
    val port: Int,
    val openRouterApiKey: String,
    val llmModel: String,
    val llmFallbackModels: List<String>,
    val classifierModel: String,
    val llmBaseUrl: String,
    val embeddingBaseUrl: String,
    val embeddingModel: String,
    val docsPath: String,
    val codePath: String,
    val skillsPath: String,
    val cachePath: String,
    val agentDefaultMode: String,
    val corsAllowedOrigin: String,
    val sessionSecret: String,
) {
    companion object {
        /**
         * Читает конфиг из application.yaml рядом с процессом (рабочая директория).
         * Все env-переменные резолвит сам Ktor через `$VAR[:default]` синтаксис.
         */
        fun load(): AppConfig {
            val config = YamlConfigLoader().load("application.yaml")
                ?: error("application.yaml not found in working directory")
            return from(config)
        }

        fun from(config: ApplicationConfig): AppConfig {
            val sessionSecret = config.property("app.session.secret").getString()
            if (sessionSecret.isBlank()) {
                error("SESSION_SECRET must be set (32+ random bytes, hex/base64). Generate with: openssl rand -hex 32")
            }
            return AppConfig(
                port = config.property("ktor.deployment.port").getString().toInt(),
                openRouterApiKey = config.property("app.llm.openRouterApiKey").getString(),
                llmModel = config.property("app.llm.model").getString(),
                llmFallbackModels = config.property("app.llm.fallbackModels").getString()
                    .split(",")
                    .map(String::trim)
                    .filter(String::isNotEmpty),
                classifierModel = config.property("app.llm.classifierModel").getString(),
                llmBaseUrl = config.property("app.llm.baseUrl").getString(),
                embeddingBaseUrl = config.property("app.embedding.baseUrl").getString(),
                embeddingModel = config.property("app.embedding.model").getString(),
                docsPath = config.property("app.rag.docsPath").getString(),
                codePath = config.property("app.rag.codePath").getString(),
                skillsPath = config.property("app.rag.skillsPath").getString(),
                cachePath = config.property("app.rag.cachePath").getString(),
                agentDefaultMode = config.property("app.agent.defaultMode").getString(),
                corsAllowedOrigin = config.property("app.cors.allowedOrigin").getString(),
                sessionSecret = sessionSecret,
            )
        }
    }
}
