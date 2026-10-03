package com.example.ai.preference

import java.util.concurrent.ConcurrentHashMap

object CommunicationPreferenceStore {
    private val preferences = ConcurrentHashMap<PreferenceCategory, CommunicationPreference>()

    fun getPreference(category: PreferenceCategory): CommunicationPreference? {
        return preferences[category]
    }

    fun getAllPreferences(): List<CommunicationPreference> {
        return preferences.values.toList()
    }

    fun savePreference(preference: CommunicationPreference) {
        preferences[preference.category] = preference
    }

    fun removePreference(category: PreferenceCategory) {
        preferences.remove(category)
    }

    fun clear() {
        preferences.clear()
    }
}
