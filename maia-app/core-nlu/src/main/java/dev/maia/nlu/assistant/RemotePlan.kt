package dev.maia.nlu.assistant

import dev.maia.nlu.Intent
import dev.maia.nlu.calc.Calc

/**
 * The devbox assistant's structured reply, checked field by field before it
 * is allowed to become an [Intent] (M9 PRD section 8.4).
 *
 * The reply arrives already decoded, as a `Map<String, Any?>`, so this module
 * keeps its no-dependency rule: whoever parsed the JSON owns the parser. What
 * this file owns is the distrust. A remote model proposing an action is
 * untrusted input, and the validation is strict on purpose:
 *
 *  - the only accepted shapes are `{"type": "answer", "text": ...}` and
 *    `{"type": "action", "action": "<kind>", ...fields}`;
 *  - only the allowlisted action kinds map to an [Intent];
 *  - every required field must be present with the right type, fields that
 *    are not in the schema are rejected, and strings are length-capped;
 *  - a calculate action must be an expression [Calc] can actually evaluate.
 *
 * Anything else is [Rejected]. A rejection is not a fault: the caller shows
 * the honest fallback, which is what the PRD asks an unreadable reply to
 * become.
 */
sealed interface RemotePlan {

    /** The devbox answered in prose. Short enough to show, never an action. */
    data class Answer(val text: String) : RemotePlan

    /** The devbox proposed a typed action and it passed validation. */
    data class Act(val intent: Intent) : RemotePlan

    /** The reply could not be read as the closed schema. */
    data object Rejected : RemotePlan

    companion object {

        private const val MAX_ANSWER = 2_000
        private const val MAX_FIELD = 200
        private const val MAX_TARGET = 500
        private const val MAX_SECONDS = 86_400L

        fun validate(reply: Map<String, Any?>): RemotePlan = when (reply["type"]) {
            "answer" -> answer(reply)
            "action" -> action(reply)
            else -> Rejected
        }

        private fun answer(reply: Map<String, Any?>): RemotePlan {
            if (reply.keys != setOf("type", "text")) return Rejected
            val text = reply["text"] as? String ?: return Rejected
            if (text.isBlank() || text.length > MAX_ANSWER) return Rejected
            return Answer(text)
        }

        private fun action(reply: Map<String, Any?>): RemotePlan {
            val kind = reply["action"] as? String ?: return Rejected
            val f = reply - "type" - "action"
            val intent = when (kind) {
                "timer" -> {
                    if (!allowed(f, "duration_seconds", "label")) return Rejected
                    val seconds = int(f["duration_seconds"], 1, MAX_SECONDS) ?: return Rejected
                    if (!validOpt(f, "label", MAX_FIELD)) return Rejected
                    Intent.SetTimer(seconds * 1_000, opt(f, "label"))
                }
                "alarm" -> {
                    if (!allowed(f, "hour", "minute", "label")) return Rejected
                    val hour = int(f["hour"], 0, 23) ?: return Rejected
                    val minute = int(f["minute"], 0, 59) ?: return Rejected
                    if (!validOpt(f, "label", MAX_FIELD)) return Rejected
                    Intent.SetAlarm(hour.toInt(), minute.toInt(), opt(f, "label"))
                }
                "calculate" -> {
                    if (!allowed(f, "expression")) return Rejected
                    val expr = req(f, "expression", 64) ?: return Rejected
                    if (Calc.eval(expr) !is Calc.Ok) return Rejected
                    Intent.Calculate(expr)
                }
                "open_settings" -> {
                    if (!allowed(f, "panel")) return Rejected
                    val name = req(f, "panel", MAX_FIELD) ?: return Rejected
                    val panel = Intent.OpenSettings.Panel.entries.firstOrNull {
                        it.name.equals(name, ignoreCase = true)
                    } ?: if (name.equals("wi-fi", true)) {
                        Intent.OpenSettings.Panel.WIFI
                    } else {
                        return Rejected
                    }
                    Intent.OpenSettings(panel)
                }
                "dial" -> {
                    if (!allowed(f, "number")) return Rejected
                    Intent.Dial(req(f, "number", 64) ?: return Rejected)
                }
                "message" -> {
                    if (!allowed(f, "to", "body")) return Rejected
                    val to = req(f, "to", MAX_FIELD) ?: return Rejected
                    if (!validOpt(f, "body", MAX_ANSWER)) return Rejected
                    Intent.ComposeMessage(to, opt(f, "body"))
                }
                "navigate" -> {
                    if (!allowed(f, "destination", "mode")) return Rejected
                    val dest = req(f, "destination", MAX_TARGET) ?: return Rejected
                    if (!validOpt(f, "mode", MAX_FIELD)) return Rejected
                    val mode = opt(f, "mode")
                    if (mode != null && mode !in setOf("walk", "drive", "bike", "transit")) {
                        return Rejected
                    }
                    Intent.Navigate(dest, mode)
                }
                "open_web" -> {
                    if (!allowed(f, "target")) return Rejected
                    Intent.OpenWeb(req(f, "target", MAX_TARGET) ?: return Rejected)
                }
                "media" -> {
                    if (!allowed(f, "command")) return Rejected
                    val name = req(f, "command", MAX_FIELD) ?: return Rejected
                    val command = Intent.Media.Command.entries.firstOrNull {
                        it.name.equals(name, ignoreCase = true)
                    } ?: return Rejected
                    Intent.Media(command)
                }
                "open_app" -> {
                    if (!allowed(f, "name")) return Rejected
                    Intent.OpenApp(req(f, "name", MAX_FIELD) ?: return Rejected)
                }
                else -> return Rejected
            }
            return Act(intent)
        }

        /** The fields present must all be in the allowlist. */
        private fun allowed(f: Map<String, Any?>, vararg keys: String): Boolean =
            f.keys.all { it in keys }

        /** A required field: a non-blank string no longer than [max]. */
        private fun req(f: Map<String, Any?>, key: String, max: Int): String? =
            (f[key] as? String)?.takeIf { it.isNotBlank() && it.length <= max }

        /** An optional field: absent and null are fine, the wrong type is not. */
        private fun validOpt(f: Map<String, Any?>, key: String, max: Int): Boolean =
            f[key] == null || (f[key] as? String)?.let { it.length <= max } == true

        private fun opt(f: Map<String, Any?>, key: String): String? =
            (f[key] as? String)?.ifBlank { null }

        /** A required whole number inside [min]..[max]. */
        private fun int(v: Any?, min: Long, max: Long): Long? {
            val d = (v as? Number)?.toDouble() ?: return null
            if (d.isNaN() || d != Math.floor(d)) return null
            val n = d.toLong()
            return n.takeIf { it in min..max }
        }
    }
}
