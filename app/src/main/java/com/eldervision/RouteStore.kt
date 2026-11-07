package com.eldervision

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SavedRoute(val name: String, val start: String, val legs: List<Leg>)

class RouteStore(context: Context) {
    private val prefs = context.getSharedPreferences("routes", Context.MODE_PRIVATE)

    fun save(name: String, start: String, legs: List<Leg>) {
        val array = JSONArray()
        for (leg in legs) array.put(JSONObject().put("heading", leg.headingDeg.toDouble()).put("steps", leg.steps))
        val route = JSONObject().put("start", start).put("legs", array)
        prefs.edit().putString(key(name), route.toString()).apply()
    }

    fun find(destination: String): SavedRoute? {
        val wanted = words(destination)
        val name = prefs.all.keys.firstOrNull { saved -> words(saved).any { it in wanted } } ?: return null
        val route = JSONObject(prefs.getString(name, "{}")!!)
        val array = route.optJSONArray("legs") ?: return null
        val legs = (0 until array.length()).map { i ->
            val leg = array.getJSONObject(i)
            Leg(leg.getDouble("heading").toFloat(), leg.getInt("steps"), "")
        }
        return SavedRoute(name, route.optString("start"), legs)
    }

    fun isEmpty() = prefs.all.isEmpty()

    private fun key(name: String) = words(name).joinToString(" ")

    private fun words(text: String) =
        text.lowercase().split(Regex("[^a-z]+")).filter { it.length >= 3 && it !in IGNORED }.toSet()

    private companion object {
        val IGNORED = setOf("the", "take", "and", "please", "towards", "that", "this", "want")
    }
}
