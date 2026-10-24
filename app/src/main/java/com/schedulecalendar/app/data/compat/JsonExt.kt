// app/src/main/java/com/schedulecalendar/app/data/compat/JsonExt.kt
package com.schedulecalendar.app.data.compat

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 备份格式转换用的 JSON 读取小工具。
 *
 * 全部按「**读不到就用兜底值，绝不抛异常**」的原则实现：
 * 备份文件可能来自任何历史版本、甚至被用户手工编辑过，
 * 转换器必须在任何畸形输入下都能产出一个可解析的当前格式 JSON，
 * 而不是抛异常把恢复流程打断（原始 JSON 仍保留在用户的备份文件里，可随时重试）。
 */

/** 取字符串值；缺失 / null / 非原始类型 → null */
fun JsonObject.stringOrNull(key: String): String? {
    val el = get(key) ?: return null
    if (el.isJsonNull || !el.isJsonPrimitive) return null
    return el.asString
}

/** 取数组；缺失 / null / 非数组 → null */
fun JsonObject.arrayOrNull(key: String): com.google.gson.JsonArray? {
    val el = get(key) ?: return null
    if (el.isJsonNull || !el.isJsonArray) return null
    return el.asJsonArray
}

/** 取布尔值；缺失 / null / 非布尔 → 兜底值 */
fun JsonObject.boolOrDefault(key: String, default: Boolean): Boolean {
    val el = get(key) ?: return default
    if (el.isJsonNull || !el.isJsonPrimitive) return default
    val p = el.asJsonPrimitive
    return when {
        p.isBoolean -> p.asBoolean
        p.isString -> p.asString.toBooleanStrictOrNull() ?: default
        else -> default
    }
}

/** 取数值；缺失 / null / 非数值 → 兜底值 */
fun JsonObject.doubleOrDefault(key: String, default: Double): Double {
    val el = get(key) ?: return default
    if (el.isJsonNull || !el.isJsonPrimitive) return default
    val p = el.asJsonPrimitive
    return runCatching { p.asDouble }.getOrDefault(default)
}

/** 取长整型；缺失 / null / 非数值 → 兜底值 */
fun JsonObject.longOrDefault(key: String, default: Long): Long {
    val el = get(key) ?: return default
    if (el.isJsonNull || !el.isJsonPrimitive) return default
    return runCatching { el.asJsonPrimitive.asLong }.getOrDefault(default)
}

/**
 * 取对象值；缺失 / null → 空对象；
 * 字符串形态（部分历史格式把嵌套对象存成 JSON 字符串）→ 尝试解析，失败则返回空对象。
 */
fun JsonObject.objectOrParsed(key: String): JsonObject {
    val el = get(key) ?: return JsonObject()
    return asObjectOrParsed(el) as JsonObject
}

/**
 * 把元素转成「对象或数组」形态：
 * - 已是对象/数组 → 原样返回；
 * - 是字符串 → 尝试当 JSON 解析（历史格式里嵌套对象常被序列化成字符串）；
 * - 其它 → 空对象。
 */
fun asObjectOrParsed(el: JsonElement): JsonElement {
    if (el.isJsonObject || el.isJsonArray) return el
    if (el.isJsonPrimitive) {
        val raw = el.asString
        if (raw.isNotBlank() && (raw.trimStart().startsWith("{") || raw.trimStart().startsWith("["))) {
            val parsed = runCatching { JsonParser.parseString(raw) }.getOrNull()
            if (parsed != null && (parsed.isJsonObject || parsed.isJsonArray)) return parsed
        }
    }
    return JsonObject()
}
