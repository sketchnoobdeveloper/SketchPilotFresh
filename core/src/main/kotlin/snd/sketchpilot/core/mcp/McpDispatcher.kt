package snd.sketchpilot.core.mcp

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import snd.sketchpilot.core.tools.ToolRegistry

/**
 * Minimal JSON-RPC MCP dispatcher backed by the same ToolRegistry the agent uses.
 * Transport (HTTP) is supplied by the app module; no tool logic lives here.
 */
class McpDispatcher(private val registry: ToolRegistry) {
    fun handle(body: String): String {
        val request = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return error(null, -32700, "Parse error")
        val id = (request["id"] as? JsonPrimitive)?.content?.toIntOrNull()
        val method = (request["method"] as? JsonPrimitive)?.content
            ?: return error(id, -32600, "Invalid Request")

        val result = when (method) {
            "initialize" -> buildJsonObject {
                put("protocolVersion", "2024-11-05")
                put("capabilities", buildJsonObject {
                    put("tools", buildJsonObject { put("listChanged", false) })
                    put("resources", buildJsonObject {})
                    put("prompts", buildJsonObject {})
                })
                put("serverInfo", buildJsonObject {
                    put("name", "sketchpilot")
                    put("version", "0.1.0")
                })
            }

            "ping" -> JsonObject(emptyMap())

            "tools/list" -> buildJsonObject {
                put("tools", JsonArray(registry.list().map { spec ->
                    buildJsonObject {
                        put("name", spec.name)
                        put("description", spec.description)
                        put("inputSchema", spec.inputSchema)
                    }
                }))
            }

            "tools/call" -> {
                val params = request["params"] as? JsonObject
                    ?: return error(id, -32602, "Invalid params")
                val name = (params["name"] as? JsonPrimitive)?.content
                    ?: return error(id, -32602, "Tool name required")
                val args = (params["arguments"] as? JsonObject)?.entries
                    ?.associate { (k, v) -> k to (v as? JsonPrimitive)?.content.orEmpty() }
                    ?: emptyMap()
                val outcome = registry.call(name, args, actor = "mcp")
                buildJsonObject {
                    put("content", JsonArray(listOf(buildJsonObject {
                        put("type", "text")
                        put("text", outcome.content)
                    })))
                    put("isError", !outcome.ok)
                }
            }

            "resources/list" -> buildJsonObject {
                put("resources", JsonArray(listOf(buildJsonObject {
                    put("uri", "sketchpilot://projects")
                    put("name", "Sketchware projects")
                    put("description", "Read-only list of discoverable projects")
                    put("mimeType", "application/json")
                })))
            }

            "resources/read" -> {
                val params = request["params"] as? JsonObject
                val uri = (params?.get("uri") as? JsonPrimitive)?.content.orEmpty()
                if (uri != "sketchpilot://projects") {
                    return error(id, -32602, "Unknown resource: $uri")
                }
                val content = registry.call("sketchpilot.project.list").content
                buildJsonObject {
                    put("contents", JsonArray(listOf(buildJsonObject {
                        put("uri", uri)
                        put("mimeType", "application/json")
                        put("text", content)
                    })))
                }
            }

            "prompts/list" -> buildJsonObject {
                put("prompts", JsonArray(listOf(buildJsonObject {
                    put("name", "inspect-project")
                    put("description", "Inspect a Sketchware project before changing it")
                })))
            }

            "prompts/get" -> {
                val params = request["params"] as? JsonObject
                val name = (params?.get("name") as? JsonPrimitive)?.content.orEmpty()
                if (name != "inspect-project") return error(id, -32602, "Unknown prompt: $name")
                val args = params?.get("arguments") as? JsonObject
                val scId = (args?.get("sc_id") as? JsonPrimitive)?.content.orEmpty()
                buildJsonObject {
                    put("description", "Read-only project inspection workflow")
                    put("messages", JsonArray(listOf(buildJsonObject {
                        put("role", "user")
                        put("content", buildJsonObject {
                            put("type", "text")
                            put("text", "Inspect project $scId using sketchpilot.project.inspect, then report its screens, components and validation issues. Do not modify anything.")
                        })
                    })))
                }
            }

            else -> return error(id, -32601, "Method not found: $method")
        }

        return Json.encodeToString(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: 0)
            put("result", result)
        })
    }

    private fun error(id: Int?, code: Int, message: String): String =
        Json.encodeToString(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: 0)
            put("error", buildJsonObject {
                put("code", code)
                put("message", message)
            })
        })
}
