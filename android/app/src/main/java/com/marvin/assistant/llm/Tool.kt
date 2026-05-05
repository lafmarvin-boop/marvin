package com.marvin.assistant.llm

import org.json.JSONObject

/**
 * Outil que Claude peut invoquer. Le `inputSchema` est un objet JSON Schema
 * (forme exigée par l'API Anthropic). [execute] reçoit l'input parsé et
 * retourne une string lisible — Claude la relira pour formuler sa réponse.
 */
class Tool(
    val name: String,
    val description: String,
    val inputSchema: JSONObject,
    val execute: suspend (JSONObject) -> String
)
