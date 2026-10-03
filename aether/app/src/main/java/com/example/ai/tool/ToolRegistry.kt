package com.example.ai.tool

import com.example.ai.tool.model.ToolDefinition

/**
 * Deterministic registry responsible for storing, indexing, and retrieving [Tool] instances.
 * Operates without dynamic reflection or class scanning.
 */
class ToolRegistry {

    private val toolsLock = Any()
    private val registeredTools = LinkedHashMap<String, Tool>()

    /**
     * Registers a tool. If a tool with the same stable name is already registered,
     * the registration is rejected to maintain determinism and prevent accidental shadowing.
     *
     * @param tool The tool to register.
     * @return True if registered successfully, False if a duplicate name was rejected.
     */
    fun register(tool: Tool): Boolean {
        synchronized(toolsLock) {
            val normalizedName = tool.name.trim()
            if (normalizedName.isBlank() || registeredTools.containsKey(normalizedName)) {
                return false
            }
            registeredTools[normalizedName] = tool
            return true
        }
    }

    /**
     * Registers multiple tools deterministically.
     *
     * @param tools The list of tools to register.
     * @return The number of tools successfully registered.
     */
    fun registerAll(tools: Collection<Tool>): Int {
        var count = 0
        synchronized(toolsLock) {
            tools.forEach { tool ->
                if (register(tool)) {
                    count++
                }
            }
        }
        return count
    }

    /**
     * Retrieves a tool by its stable name.
     *
     * @param name The tool name.
     * @return The [Tool] instance, or null if not found.
     */
    fun get(name: String): Tool? {
        synchronized(toolsLock) {
            return registeredTools[name.trim()]
        }
    }

    /**
     * Checks if a tool is registered.
     */
    fun contains(name: String): Boolean {
        synchronized(toolsLock) {
            return registeredTools.containsKey(name.trim())
        }
    }

    /**
     * Returns an immutable snapshot list of all registered tools in deterministic insertion order.
     */
    fun getAll(): List<Tool> {
        synchronized(toolsLock) {
            return registeredTools.values.toList()
        }
    }

    /**
     * Returns an immutable snapshot list of all registered tool definitions.
     */
    fun getDefinitions(): List<ToolDefinition> {
        synchronized(toolsLock) {
            return registeredTools.values.map { it.definition }
        }
    }

    /**
     * Unregisters a tool by name.
     *
     * @param name The name of the tool to remove.
     * @return True if a tool was removed, False otherwise.
     */
    fun unregister(name: String): Boolean {
        synchronized(toolsLock) {
            return registeredTools.remove(name.trim()) != null
        }
    }

    /**
     * Clears all registered tools.
     */
    fun clear() {
        synchronized(toolsLock) {
            registeredTools.clear()
        }
    }

    /**
     * Returns the number of registered tools.
     */
    val size: Int
        get() = synchronized(toolsLock) { registeredTools.size }
}
