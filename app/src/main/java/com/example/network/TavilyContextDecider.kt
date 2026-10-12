package com.example.network

/**
 * Intelligent context-based analyzer that decides whether a user prompt warrants live Tavily web search.
 * Strictly adheres to user criteria:
 * 1. Personal matters (feelings, daily life, casual chat, small talk, emotions, grammar of personal writing) -> NO search, unless explicitly requested.
 * 2. Technical, technological, programming, libraries, architecture, current events, releases, or news -> LIVE search enabled.
 * 3. Explicit directives ("search", "lookup", "latest on", etc.) -> ALWAYS search.
 */
object TavilyContextDecider {

    fun shouldSearch(query: String): Boolean {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return false
        val lower = trimmed.lowercase()

        // 1. Explicit search requests ALWAYS trigger web search
        val explicitTriggers = listOf(
            "search", "lookup", "look up", "google", "web search", "browse",
            "find online", "find on the web", "search internet", "search for",
            "what's the latest on", "what is the latest on", "recent news",
            "latest updates on", "check the web", "look online"
        )
        if (explicitTriggers.any { lower.contains(it) }) {
            return true
        }

        // 2. Personal / Casual / Emotional / Small-talk queries -> NO search unless pushed
        val personalPhrases = listOf(
            "how are you", "my day", "i feel", "i am feeling", "i'm feeling",
            "i am sad", "i'm sad", "i am happy", "i'm happy", "i'm tired", "i am tired",
            "tell me about yourself", "who are you", "what do you think of me",
            "my girlfriend", "my boyfriend", "my wife", "my husband", "my friend",
            "my mother", "my father", "my mom", "my dad", "my dog", "my cat",
            "what should i eat", "recommend a routine", "my diary", "my thoughts",
            "good morning", "good night", "good evening", "good afternoon",
            "hello", "hey", "hi", "bye", "see you", "thank you", "thanks",
            "tell me a joke", "write a poem", "tell me a story",
            "is this sentence correct", "correct my grammar", "say this more politely",
            "how do i pronounce", "translate this to"
        )
        if (personalPhrases.any { lower.startsWith(it) || lower == it }) {
            return false
        }

        // 3. Technical & Engineering Context (Technology grows daily, keep up-to-date)
        val technicalTerms = listOf(
            "kotlin", "jetpack", "compose", "android", "gradle", "ksp", "room", "retrofit",
            "okhttp", "ktor", "python", "javascript", "typescript", "react", "nextjs", "vue",
            "rust", "c++", "golang", "swift", "swiftui", "java", "sql", "postgres", "sqlite",
            "redis", "mongodb", "docker", "kubernetes", "linux", "bash", "git", "github",
            "api", "sdk", "framework", "library", "endpoint", "dependency", "version",
            "architecture", "algorithm", "compiler", "debug", "stacktrace", "exception",
            "crash", "coroutine", "flow", "lifecycle", "viewmodel", "thread", "concurrency",
            "gemini", "openai", "gpt", "claude", "llama", "llm", "ai model", "transformer",
            "benchmark", "gpu", "cpu", "chip", "hardware", "spec", "specs", "vram",
            "nvidia", "apple", "google", "microsoft", "meta", "aws", "cloud",
            "documentation", "doc", "rfc", "protocol", "http", "websocket", "oauth",
            "tavily", "rag", "vector", "embedding", "npm", "pip", "package"
        )

        val hasTechnicalTerm = technicalTerms.any { term ->
            val pattern = "\\b${Regex.escape(term)}\\b".toRegex()
            pattern.containsMatchIn(lower)
        }

        // 4. Current Events, Temporal updates, News & Factual Developments
        val temporalAndNewsTerms = listOf(
            "news", "release", "released", "announced", "launch", "launched",
            "update", "updated", "current", "today", "yesterday", "this week",
            "this month", "recent", "latest", "price", "stock", "earnings",
            "election", "winner", "score", "game", "match", "status", "who is the current",
            "2024", "2025", "2026", "2027"
        )

        val hasNewsOrTemporalTerm = temporalAndNewsTerms.any { term ->
            val pattern = "\\b${Regex.escape(term)}\\b".toRegex()
            pattern.containsMatchIn(lower)
        }

        return hasTechnicalTerm || hasNewsOrTemporalTerm
    }
}
